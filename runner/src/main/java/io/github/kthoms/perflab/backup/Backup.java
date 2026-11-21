package io.github.kthoms.perflab.backup;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.daemon.IdleHook;
import java.io.File;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Daily {@code pg_dump} of the results database into {@code backupDir}. Runs only between jobs (it
 * is an {@link IdleHook}) and never writes to the internal disk when the backup volume is missing.
 */
public class Backup implements IdleHook {

  /** Runs an external command with stdout redirected to a file. */
  @FunctionalInterface
  public interface CommandRunner {
    record Result(int exitCode, String stderr) {}

    /** Throws IOException when the command cannot be started or exceeds the timeout. */
    Result run(List<String> command, Path stdout, Duration timeout)
        throws IOException, InterruptedException;
  }

  /** Where the time of the last successful backup is kept (the {@code backup_log} table). */
  public interface BackupLog {
    Optional<Instant> lastBackup();

    void record(Instant ts, Path path);
  }

  public static class BackupException extends RuntimeException {
    public BackupException(String message) {
      super(message);
    }

    public BackupException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static final Duration INTERVAL = Duration.ofHours(24);
  static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);
  static final Duration TIMEOUT = Duration.ofMinutes(10);

  private static final System.Logger LOG = System.getLogger(Backup.class.getName());
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
  private static final Pattern DUMP_NAME = Pattern.compile("perflab-(\\d{8}-\\d{4})\\.dump");
  private static final Pattern DB_NAME = Pattern.compile("^jdbc:postgresql://[^/]+/([^?/]+)");

  private final PerfLabConfig config;
  private final Path composeFile;
  private final BackupLog log;
  private final CommandRunner runner;
  private final Clock clock;
  private Instant lastFailure;

  public Backup(PerfLabConfig config, Path composeFile, Supplier<DataSource> dataSource) {
    this(
        config,
        composeFile,
        new JdbcBackupLog(dataSource),
        new ProcessCommandRunner(),
        Clock.systemDefaultZone());
  }

  Backup(PerfLabConfig config, Path composeFile, BackupLog log, CommandRunner runner, Clock clock) {
    this.config = config;
    this.composeFile = composeFile;
    this.log = log;
    this.runner = runner;
    this.clock = clock;
  }

  /** True when the last successful backup is more than 24 h old (and no failure is too recent). */
  public boolean due(Instant now) {
    if (lastFailure != null && now.isBefore(lastFailure.plus(RETRY_AFTER_FAILURE))) {
      return false;
    }
    return log.lastBackup().map(last -> now.isAfter(last.plus(INTERVAL))).orElse(true);
  }

  @Override
  public void betweenJobs(Instant now) {
    if (!due(now)) {
      return;
    }
    try {
      Path dump = run();
      LOG.log(Level.INFO, "backup written: {0}", dump);
    } catch (BackupException e) {
      LOG.log(Level.WARNING, "backup skipped/failed: " + e.getMessage());
    }
  }

  /** Dumps the database and applies the retention; throws BackupException when no dump was kept. */
  public Path run() {
    try {
      Path dump = dump();
      lastFailure = null;
      return dump;
    } catch (BackupException e) {
      lastFailure = clock.instant();
      throw e;
    }
  }

  private Path dump() {
    Path dir = config.backupDir();
    requireBackupDir(dir);
    Instant now = clock.instant();
    Path target = dir.resolve("perflab-" + STAMP.format(now.atZone(clock.getZone())) + ".dump");
    Path partial = dir.resolve(target.getFileName() + ".partial");
    var cmd =
        List.of(
            "docker", "compose", "-f", composeFile.toString(), "exec", "-T", "results-db",
            "pg_dump", "-U", config.resultsDb().user(), "-Fc",
            dbName(config.resultsDb().jdbcUrl()));
    try {
      deleteStalePartials(dir);
      CommandRunner.Result result = runner.run(cmd, partial, TIMEOUT);
      if (result.exitCode() != 0) {
        throw new BackupException(
            "pg_dump exited with " + result.exitCode() + ": " + result.stderr().strip());
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new BackupException("backup failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BackupException("backup interrupted", e);
    } finally {
      deleteQuietly(partial);
    }
    log.record(now, target);
    deleteOldDumps(dir, now);
    return target;
  }

  /**
   * The backup volume must be mounted: a missing directory is only created when its parent exists,
   * so an unmounted /Volumes/X/... never turns into a directory on the internal disk.
   */
  private static void requireBackupDir(Path dir) {
    if (Files.isDirectory(dir)) {
      return;
    }
    Path parent = dir.toAbsolutePath().getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw new BackupException(
          "backup dir " + dir + " does not exist and neither does its parent (volume not mounted?)");
    }
    try {
      Files.createDirectory(dir);
    } catch (IOException e) {
      throw new BackupException("cannot create backup dir " + dir + ": " + e.getMessage(), e);
    }
  }

  private void deleteOldDumps(Path dir, Instant now) {
    LocalDateTime cutoff =
        now.atZone(clock.getZone()).toLocalDateTime().minusDays(config.backupRetentionDays());
    try (Stream<Path> files = Files.list(dir)) {
      files
          .filter(
              p -> {
                Matcher m = DUMP_NAME.matcher(p.getFileName().toString());
                return m.matches() && LocalDateTime.parse(m.group(1), STAMP).isBefore(cutoff);
              })
          .forEach(Backup::deleteQuietly);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "retention cleanup failed in " + dir + ": " + e);
    }
  }

  private static void deleteStalePartials(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      files.filter(p -> p.getFileName().toString().endsWith(".dump.partial")).forEach(Backup::deleteQuietly);
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "cannot delete " + p + ": " + e);
    }
  }

  static String dbName(String jdbcUrl) {
    Matcher m = DB_NAME.matcher(jdbcUrl);
    if (!m.find()) {
      throw new IllegalArgumentException("cannot find the database name in " + jdbcUrl);
    }
    return m.group(1);
  }

  /** {@code backup_log} in the results DB. */
  static final class JdbcBackupLog implements BackupLog {
    private final Supplier<DataSource> dataSource;

    JdbcBackupLog(Supplier<DataSource> dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public Optional<Instant> lastBackup() {
      try (Connection c = dataSource.get().getConnection();
          PreparedStatement ps = c.prepareStatement("select max(ts) from backup_log");
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        Timestamp ts = rs.getTimestamp(1);
        return ts == null ? Optional.empty() : Optional.of(ts.toInstant());
      } catch (SQLException e) {
        throw new BackupException("cannot read backup_log: " + e.getMessage(), e);
      }
    }

    @Override
    public void record(Instant ts, Path path) {
      try (Connection c = dataSource.get().getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  "insert into backup_log(ts, path) values (?,?) on conflict (ts) do nothing")) {
        ps.setTimestamp(1, Timestamp.from(ts));
        ps.setString(2, path.toString());
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new BackupException("cannot write backup_log: " + e.getMessage(), e);
      }
    }
  }

  /** Real process execution; the whole process tree is killed on timeout. */
  static final class ProcessCommandRunner implements CommandRunner {
    private static final int STDERR_TAIL = 2000;

    @Override
    public Result run(List<String> command, Path stdout, Duration timeout)
        throws IOException, InterruptedException {
      Path stderr = Files.createTempFile("perflab-backup", ".err");
      try {
        Process p =
            new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        try {
          if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IOException("command timed out after " + timeout);
          }
        } finally {
          if (p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor();
          }
        }
        String err = Files.readString(stderr, StandardCharsets.UTF_8);
        if (err.length() > STDERR_TAIL) {
          err = err.substring(err.length() - STDERR_TAIL);
        }
        return new Result(p.exitValue(), err);
      } finally {
        Files.deleteIfExists(stderr);
      }
    }
  }
}
