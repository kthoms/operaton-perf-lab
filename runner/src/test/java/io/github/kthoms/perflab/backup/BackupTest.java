package io.github.kthoms.perflab.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.backup.Backup.CommandRunner;
import io.github.kthoms.perflab.config.PerfLabConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupTest {

  static final Instant NOW = Instant.parse("2026-11-21T10:30:00Z");
  static final Path COMPOSE = Path.of("/repo/infra/docker-compose.yml");

  @TempDir Path tmp;

  // System.Logger of Backup is backed by this JUL logger; capture instead of printing
  final Logger julLogger = Logger.getLogger(Backup.class.getName());
  final List<LogRecord> logged = new ArrayList<>();
  final Handler handler =
      new Handler() {
        @Override
        public void publish(LogRecord r) {
          logged.add(r);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };
  boolean useParentHandlers;

  @BeforeEach
  void captureLogs() {
    useParentHandlers = julLogger.getUseParentHandlers();
    julLogger.setLevel(Level.ALL); // other tests may have silenced the root logger
    julLogger.setUseParentHandlers(false);
    julLogger.addHandler(handler);
  }

  @AfterEach
  void restoreLogs() {
    julLogger.removeHandler(handler);
    julLogger.setUseParentHandlers(useParentHandlers);
    julLogger.setLevel(null);
  }

  /** Records commands; writes a fake dump to stdout unless configured to fail. */
  static class FakeRunner implements CommandRunner {
    final List<List<String>> commands = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    int exit;
    boolean throwIo;

    @Override
    public Result run(List<String> command, Path stdout, Duration timeout) throws IOException {
      commands.add(command);
      timeouts.add(timeout);
      Files.writeString(stdout, "PGDMP-data");
      if (throwIo) {
        throw new IOException("timed out");
      }
      return new Result(exit, exit == 0 ? "" : "pg_dump: boom");
    }
  }

  static class MemoryLog implements Backup.BackupLog {
    Optional<Instant> last = Optional.empty();
    final List<Path> recorded = new ArrayList<>();

    @Override
    public Optional<Instant> lastBackup() {
      return last;
    }

    @Override
    public void record(Instant ts, Path path) {
      last = Optional.of(ts);
      recorded.add(path);
    }
  }

  PerfLabConfig config(Path backupDir, int retentionDays) {
    return new PerfLabConfig(
        tmp.resolve("data"),
        backupDir,
        retentionDays,
        "https://example.invalid/x.git",
        new PerfLabConfig.Db("jdbc:postgresql://127.0.0.1:5433/perflab_results?ssl=false", "perflab", "pw"),
        List.of(),
        List.of(),
        null);
  }

  Backup backup(PerfLabConfig c, FakeRunner runner, MemoryLog log) {
    return new Backup(c, COMPOSE, log, runner, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void backupRetentionDeletesOldDumps() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("backups"));
    Path old = Files.writeString(dir.resolve("perflab-20261101-0300.dump"), "old");
    Path edge = Files.writeString(dir.resolve("perflab-20261108-1100.dump"), "edge"); // 13d 23h
    Path unrelated = Files.writeString(dir.resolve("notes.txt"), "keep");
    var runner = new FakeRunner();
    var log = new MemoryLog();

    Path dump = backup(config(dir, 14), runner, log).run();

    assertThat(dump).isEqualTo(dir.resolve("perflab-20261121-1030.dump")).hasContent("PGDMP-data");
    assertThat(old).doesNotExist();
    assertThat(edge).exists();
    assertThat(unrelated).exists();
    assertThat(log.recorded).containsExactly(dump);
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".partial"));
    }
  }

  @Test
  void backupRunsPgDumpInsideTheResultsDbContainer() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("backups"));
    var runner = new FakeRunner();

    backup(config(dir, 14), runner, new MemoryLog()).run();

    assertThat(runner.commands).hasSize(1);
    assertThat(runner.commands.get(0))
        .containsExactly(
            "docker", "compose", "-f", COMPOSE.toString(), "exec", "-T", "results-db", "pg_dump",
            "-U", "perflab", "-Fc", "perflab_results");
    assertThat(runner.timeouts.get(0)).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void backupDueAfter24h() {
    var log = new MemoryLog();
    var backup = backup(config(tmp.resolve("b"), 14), new FakeRunner(), log);

    assertThat(backup.due(NOW)).as("never backed up").isTrue();
    log.last = Optional.of(NOW.minus(Duration.ofHours(23)));
    assertThat(backup.due(NOW)).isFalse();
    log.last = Optional.of(NOW.minus(Duration.ofHours(25)));
    assertThat(backup.due(NOW)).isTrue();
  }

  @Test
  void failedDumpIsDeletedAndReported() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("backups"));
    var runner = new FakeRunner();
    runner.exit = 1;
    var log = new MemoryLog();

    assertThatThrownBy(() -> backup(config(dir, 14), runner, log).run())
        .isInstanceOf(Backup.BackupException.class)
        .hasMessageContaining("pg_dump: boom");

    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files).isEmpty();
    }
    assertThat(log.recorded).isEmpty();
  }

  @Test
  void interruptedOrTimedOutDumpIsDeleted() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("backups"));
    var runner = new FakeRunner();
    runner.throwIo = true;

    assertThatThrownBy(() -> backup(config(dir, 14), runner, new MemoryLog()).run())
        .isInstanceOf(Backup.BackupException.class);

    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void missingBackupVolumeIsNeverCreatedOnTheInternalDisk() {
    // /Volumes/Backup is not mounted: neither the mount point nor the leaf may be created
    Path mountPoint = tmp.resolve("Volumes/Backup");
    Path dir = mountPoint.resolve("perflab-backups");
    var runner = new FakeRunner();
    var log = new MemoryLog();

    assertThatThrownBy(() -> backup(config(dir, 14), runner, log).run())
        .isInstanceOf(Backup.BackupException.class)
        .hasMessageContaining(dir.toString());

    assertThat(runner.commands).isEmpty();
    assertThat(tmp.resolve("Volumes")).doesNotExist();
    assertThat(log.recorded).isEmpty();
  }

  @Test
  void missingBackupVolumeIsLoggedAsWarningBetweenJobs() {
    var runner = new FakeRunner();
    backup(config(tmp.resolve("Volumes/Backup/perflab-backups"), 14), runner, new MemoryLog()).betweenJobs(NOW);

    assertThat(runner.commands).isEmpty();
    assertThat(logged).anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("not mounted"));
    assertThat(tmp.resolve("Volumes")).doesNotExist();
  }

  @Test
  void leafDirectoryIsCreatedWhenItsParentExists() throws Exception {
    Path mountPoint = Files.createDirectories(tmp.resolve("Volumes/Backup"));
    Path dir = mountPoint.resolve("perflab-backups");

    Path dump = backup(config(dir, 14), new FakeRunner(), new MemoryLog()).run();

    assertThat(dump).exists().hasParent(dir);
  }

  @Test
  void betweenJobsBacksUpOnlyWhenDueAndNeverThrows() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("backups"));
    var runner = new FakeRunner();
    var log = new MemoryLog();
    var backup = backup(config(dir, 14), runner, log);

    backup.betweenJobs(NOW);
    backup.betweenJobs(NOW.plusSeconds(60));
    assertThat(runner.commands).as("second call: not due").hasSize(1);

    var failing = new FakeRunner();
    failing.exit = 2;
    var failingBackup = backup(config(dir, 14), failing, new MemoryLog());
    failingBackup.betweenJobs(NOW); // logs a warning, must not throw
    failingBackup.betweenJobs(NOW.plusSeconds(60));
    assertThat(logged).anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("pg_dump exited with 2"));
    assertThat(failing.commands).as("failed backup is retried only after the backoff").hasSize(1);
    failingBackup.betweenJobs(NOW.plus(Duration.ofHours(2)));
    assertThat(failing.commands).hasSize(2);
  }

  @Test
  void dbNameIsParsedFromJdbcUrl() {
    assertThat(Backup.dbName("jdbc:postgresql://127.0.0.1:5433/perflab")).isEqualTo("perflab");
    assertThat(Backup.dbName("jdbc:postgresql://h/perflab?ssl=false&x=y")).isEqualTo("perflab");
    assertThatThrownBy(() -> Backup.dbName("jdbc:postgresql://h:5432")).isInstanceOf(IllegalArgumentException.class);
  }
}
