package io.github.kthoms.perflab.jobs;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BenchmarkConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.env.FingerprintCollector;
import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.extract.BenchmarkJsonExtractor;
import io.github.kthoms.perflab.git.OperatonCheckout;
import io.github.kthoms.perflab.jobs.CommitResolver.Resolved;
import io.github.kthoms.perflab.jobs.CommitResolver.Unresolved;
import io.github.kthoms.perflab.results.Measurement;
import io.github.kthoms.perflab.results.RunRecord;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs a perf-lab benchmark (module {@code benchmarks}) against one Operaton commit. Untimed prep:
 * install the engine of the checkout into the lab's Maven repo, resolve the benchmark's
 * dependencies for that version, and compile it offline. Only a compilation error means the
 * benchmark does not apply; any other prep failure is an infrastructure failure (thrown, so the
 * job is retried). The benchmark then runs {@code repetitions} times, for PostgreSQL in a fresh
 * container per job that is always removed afterwards.
 */
public class BenchmarkJobExecutor implements JobExecutor {

  private static final System.Logger LOG = System.getLogger(BenchmarkJobExecutor.class.getName());

  static final String BENCHMARK_ID = "task-authorization";
  static final String H2 = "h2";
  static final String POSTGRESQL = "postgresql";
  static final int PG_PORT = 5434;
  static final String PG_IMAGE = "postgres:17";
  /** Throwaway credentials of a container bound to 127.0.0.1 that lives for one job. */
  static final String PG_PASSWORD = "perflab-bench";
  static final String CONTAINER_PREFIX = "perflab-bench-pg-";
  static final String LABEL_KEY = "io.github.kthoms.perflab";
  static final String LABEL_VALUE = "bench-pg";
  static final Duration READINESS_TIMEOUT = Duration.ofSeconds(90);
  private static final Duration READINESS_POLL = Duration.ofSeconds(1);
  private static final Duration CLEANUP_TIMEOUT = Duration.ofMinutes(2);
  private static final Pattern VERSION = Pattern.compile("^[0-9][0-9A-Za-z._-]*$");
  private static final String COMPILATION_ERROR = "COMPILATION ERROR";

  private final PerfLabConfig config;
  private final Path perfLabHome;
  private final OperatonCheckout checkout;
  private final CommitResolver resolver;
  private final ProcessRunner runner;
  private final FingerprintCollector fingerprints;
  private final Clock clock;
  private final Duration readinessTimeout;

  public BenchmarkJobExecutor(
      PerfLabConfig config,
      Path perfLabHome,
      OperatonCheckout checkout,
      CommitResolver resolver,
      ProcessRunner runner,
      FingerprintCollector fingerprints,
      Clock clock) {
    this(config, perfLabHome, checkout, resolver, runner, fingerprints, clock, READINESS_TIMEOUT);
  }

  BenchmarkJobExecutor(
      PerfLabConfig config,
      Path perfLabHome,
      OperatonCheckout checkout,
      CommitResolver resolver,
      ProcessRunner runner,
      FingerprintCollector fingerprints,
      Clock clock,
      Duration readinessTimeout) {
    this.config = config;
    this.perfLabHome = perfLabHome.toAbsolutePath();
    this.checkout = checkout;
    this.resolver = resolver;
    this.runner = runner;
    this.fingerprints = fingerprints;
    this.clock = clock;
    this.readinessTimeout = readinessTimeout;
  }

  @Override
  public Outcome execute(Job job, CancellationToken token) {
    try {
      return benchmark(job, token);
    } catch (RuntimeException e) {
      if (token.isCancelled()) {
        return new Cancelled();
      }
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Cancelled();
    } catch (IOException e) {
      throw new UncheckedIOException("benchmark job " + job.id() + " failed to run", e);
    }
  }

  private Outcome benchmark(Job job, CancellationToken token) throws IOException, InterruptedException {
    if (!BENCHMARK_ID.equals(job.benchmark())) {
      return new NotApplicable("unknown benchmark '" + job.benchmark() + "'");
    }
    if (!H2.equals(job.db()) && !POSTGRESQL.equals(job.db())) {
      return new NotApplicable("unsupported database '" + job.db() + "'");
    }
    Optional<BenchmarkConfig> benchmarkConfig =
        config.benchmarks().stream().filter(b -> b.id().equals(job.benchmark())).findFirst();
    if (benchmarkConfig.isEmpty()) {
      return new NotApplicable("benchmark '" + job.benchmark() + "' is not configured");
    }
    Path pom = perfLabHome.resolve("benchmarks").resolve("pom.xml");
    if (!Files.isRegularFile(pom)) {
      throw new IllegalStateException("benchmark module not found: " + pom + " (check perfLabHome)");
    }

    Instant started = clock.instant();
    checkout.ensureCloned(token);
    checkout.fetch(token);
    var resolution = resolver.resolve(job);
    if (resolution instanceof Unresolved(String reason)) {
      return new NotApplicable(reason);
    }
    Resolved resolved = (Resolved) resolution;
    String sha = resolved.sha();
    BranchConfig branch = resolved.branch();
    checkout.checkout(sha, token);
    if (token.isCancelled()) {
      return new Cancelled();
    }

    Fingerprint fingerprint = fingerprints.collect(branch, config);
    Map<String, String> env = env(branch);
    Path log = config.logs().resolve(job.id() + "-" + sha + ".log");
    Files.deleteIfExists(log); // the runner appends; a requeued job must not see its old output
    Path work = config.dataDir().resolve("work").resolve("benchmarks");
    Files.createDirectories(work);
    LOG.log(System.Logger.Level.INFO, "job {0}: benchmark {1} on {2} at {3} ({4}), log {5}",
        job.id(), job.benchmark(), job.db(), sha, branch.name(), log);

    // untimed prep: install the engine under test
    List<String> prep = List.of(
        mvnw(), "install", "-DskipTests", "-Pdistro", "-Dskip.frontend.build=true", "-pl", "engine", "-am",
        repoLocal());
    if (!succeeded(run(prep, config.checkout(), env, log, token), token, "engine install (prep)", log)) {
      return new Cancelled();
    }
    Optional<String> version = operatonVersion(job, sha, env, log, token);
    if (version.isEmpty()) {
      return new Cancelled();
    }

    Path buildDir = work.resolve("build");
    deleteRecursively(buildDir); // never mix classes compiled against another engine version
    List<String> mvn = List.of(
        "mvn", "-B", "-f", pom.toString(), repoLocal(), "-Doperaton.version=" + version.get(),
        "-Dbenchmark.buildDir=" + buildDir);
    // network and repository problems surface here, so they are never mistaken for a compile error
    List<String> resolveCmd = with(mvn, "dependency:resolve", "dependency:resolve-plugins");
    if (!succeeded(run(resolveCmd, work, env, log, token), token,
        "dependency:resolve of the benchmark for Operaton " + version.get(), log)) {
      return new Cancelled();
    }
    long compileOutputStart = Files.exists(log) ? Files.size(log) : 0;
    ProcessRunner.Result compiled = run(with(mvn, "-o", "compile"), work, env, log, token);
    if (compiled.cancelled() || token.isCancelled()) {
      return new Cancelled();
    }
    if (compiled.exitCode() != 0) {
      if (readFrom(log, compileOutputStart).contains(COMPILATION_ERROR)) {
        return new NotApplicable("benchmark " + job.benchmark() + " does not compile against Operaton "
            + version.get() + " (see " + log + ")");
      }
      throw new IllegalStateException("benchmark compile step failed with " + compiled.exitCode()
          + " without a compilation error (see " + log + ")");
    }

    boolean postgres = POSTGRESQL.equals(job.db());
    String container = CONTAINER_PREFIX + job.id();
    try {
      if (postgres && !startPostgres(container, work, log, token)) {
        return new Cancelled();
      }
      int repetitions = Math.max(1, benchmarkConfig.get().repetitions());
      List<Measurement> measurements = new ArrayList<>();
      String commandLine = null;
      for (int repetition = 1; repetition <= repetitions; repetition++) {
        Path output = work.resolve("results").resolve(job.id() + "-" + repetition + ".json");
        Files.deleteIfExists(output);
        List<String> cmd = new ArrayList<>(mvn);
        cmd.add("-o");
        cmd.addAll(databaseArgs(job.db()));
        cmd.add("-Dbenchmark.output=" + output);
        cmd.add("exec:exec");
        if (commandLine == null) {
          commandLine = String.join(" ", cmd);
        }
        ProcessRunner.Result result = run(cmd, work, env, log, token);
        if (result.cancelled() || token.isCancelled()) {
          return new Cancelled();
        }
        // from here on the benchmark itself ran: its failures are stored as FAILED runs
        if (result.exitCode() != 0) {
          return completed(job, branch, sha, "FAILED", started, fingerprint, commandLine, log, List.of());
        }
        List<Measurement> extracted;
        try {
          // a result of another benchmark or database (IllegalStateException) is not caught
          extracted = BenchmarkJsonExtractor.extract(output, job.benchmark(), job.db());
        } catch (UncheckedIOException | IllegalArgumentException e) {
          LOG.log(System.Logger.Level.WARNING,
              "job " + job.id() + ": benchmark ran but its result is unreadable, storing the run as FAILED: " + e, e);
          return completed(job, branch, sha, "FAILED", started, fingerprint, commandLine, log, List.of());
        }
        for (Measurement m : extracted) {
          Map<String, String> dims = new HashMap<>(m.dims());
          dims.put("repetition", String.valueOf(repetition));
          measurements.add(new Measurement(m.kpi(), m.value(), m.unit(), Map.copyOf(dims)));
        }
      }
      return completed(job, branch, sha, "SUCCESS", started, fingerprint, commandLine, log, measurements);
    } finally {
      if (postgres) {
        removeContainer(container, work, log);
      }
    }
  }

  /** False if cancelled; throws if the step failed. */
  private static boolean succeeded(ProcessRunner.Result result, CancellationToken token, String step, Path log) {
    if (result.cancelled() || token.isCancelled()) {
      return false;
    }
    if (result.exitCode() != 0) {
      throw new IllegalStateException(step + " failed with " + result.exitCode() + " (see " + log + ")");
    }
    return true;
  }

  /** The checkout's project version, empty if cancelled. */
  private Optional<String> operatonVersion(
      Job job, String sha, Map<String, String> env, Path log, CancellationToken token)
      throws IOException, InterruptedException {
    Path out = config.logs().resolve(job.id() + "-" + sha + "-version.txt");
    Files.deleteIfExists(out);
    List<String> cmd = List.of(
        mvnw(), "-q", "-N", "help:evaluate", "-Dexpression=project.version", "-DforceStdout",
        "-Doutput=" + out, repoLocal());
    if (!succeeded(run(cmd, config.checkout(), env, log, token), token, "reading the Operaton version", log)) {
      return Optional.empty();
    }
    String version = Files.exists(out) ? Files.readString(out).strip() : "";
    if (!VERSION.matcher(version).matches()) {
      throw new IllegalStateException(
          "cannot read the Operaton version: '" + version + "' in " + out + " (see " + log + ")");
    }
    return Optional.of(version);
  }

  /**
   * Removes leftover benchmark containers (by label or name prefix; only one job runs at a time),
   * starts a fresh PostgreSQL container and waits until it accepts TCP connections. False if
   * cancelled.
   */
  private boolean startPostgres(String container, Path work, Path log, CancellationToken token)
      throws IOException, InterruptedException {
    if (!sweepContainers(work, log, token)) {
      return false;
    }
    ProcessRunner.Result started = run(
        List.of("docker", "run", "--rm", "-d", "--name", container, "--label", LABEL_KEY + "=" + LABEL_VALUE,
            "-p", "127.0.0.1:" + PG_PORT + ":5432", "-e", "POSTGRES_PASSWORD=" + PG_PASSWORD, PG_IMAGE),
        work, Map.of(), log, token);
    if (!succeeded(started, token, "docker run " + PG_IMAGE, log)) {
      return false;
    }
    long deadline = System.nanoTime() + readinessTimeout.toNanos();
    while (true) {
      // TCP, not the socket: the image's init phase runs a socket-only server first
      ProcessRunner.Result ready = run(
          List.of("docker", "exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"),
          work, Map.of(), log, token);
      if (ready.cancelled() || token.isCancelled()) {
        return false;
      }
      if (ready.exitCode() == 0) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "PostgreSQL container " + container + " not ready after " + readinessTimeout + " (see " + log + ")");
      }
      Thread.sleep(READINESS_POLL.toMillis());
    }
  }

  /** A container left by a crashed daemon would otherwise hold the port forever. False if cancelled. */
  private boolean sweepContainers(Path work, Path log, CancellationToken token)
      throws IOException, InterruptedException {
    Path listing = work.resolve("containers.txt");
    Files.deleteIfExists(listing);
    ProcessRunner.Result listed = run(
        List.of("docker", "ps", "-a", "--format",
            "{{.ID}}\t{{.Names}}\t{{.Label \"" + LABEL_KEY + "\"}}"),
        work, Map.of(), listing, token);
    if (!succeeded(listed, token, "docker ps", listing)) {
      return false;
    }
    List<String> leftovers = new ArrayList<>();
    for (String line : Files.readAllLines(listing)) {
      String[] parts = line.strip().split("\t", -1);
      String name = parts.length > 1 ? parts[1].strip() : "";
      String label = parts.length > 2 ? parts[2].strip() : "";
      if (!parts[0].isBlank() && (LABEL_VALUE.equals(label) || name.startsWith(CONTAINER_PREFIX))) {
        leftovers.add(parts[0].strip());
      }
    }
    if (leftovers.isEmpty()) {
      return true;
    }
    LOG.log(System.Logger.Level.WARNING, "removing leftover benchmark containers {0}", leftovers);
    return succeeded(run(with(List.of("docker", "rm", "-f"), leftovers.toArray(String[]::new)),
        work, Map.of(), log, token), token, "removing leftover benchmark containers", log);
  }

  /** Never stopped by the job token, and runs even if the thread was interrupted. */
  private void removeContainer(String container, Path work, Path log) {
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
    try {
      ProcessRunner.Result result = runner.run(
          List.of("docker", "rm", "-f", container), work, Map.of(), log,
          () -> System.nanoTime() > deadline, config.idle().activityCheckInterval());
      if (result.exitCode() != 0 || result.cancelled()) {
        LOG.log(System.Logger.Level.WARNING, "could not remove container {0} (see {1})", container, log);
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "could not remove container " + container + ": " + e);
    } catch (InterruptedException e) {
      interrupted = true;
      LOG.log(System.Logger.Level.WARNING, "interrupted while removing container " + container);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static List<String> databaseArgs(String db) {
    if (POSTGRESQL.equals(db)) {
      return List.of(
          "-Dbenchmark.database=" + POSTGRESQL,
          "-Dbenchmark.jdbcUrl=jdbc:postgresql://127.0.0.1:" + PG_PORT + "/postgres",
          "-Dbenchmark.jdbcUser=postgres",
          "-Dbenchmark.jdbcPassword=" + PG_PASSWORD);
    }
    return List.of(
        "-Dbenchmark.database=" + H2,
        "-Dbenchmark.jdbcUrl=jdbc:h2:mem:perflab-bench;DB_CLOSE_DELAY=-1",
        "-Dbenchmark.jdbcUser=sa",
        "-Dbenchmark.jdbcPassword=");
  }

  private Completed completed(
      Job job, BranchConfig branch, String sha, String status, Instant started, Fingerprint fingerprint,
      String commandLine, Path log, List<Measurement> measurements) {
    RunRecord run = new RunRecord(
        job.id(), JobKind.BENCHMARK, branch.name(), sha, job.db(), job.benchmark(), status,
        started, clock.instant(), fingerprint, commandLine, log);
    return new Completed(run, measurements);
  }

  private ProcessRunner.Result run(
      List<String> cmd, Path workDir, Map<String, String> env, Path log, CancellationToken token)
      throws IOException, InterruptedException {
    return runner.run(cmd, workDir, env, log, token, config.idle().activityCheckInterval());
  }

  private static List<String> with(List<String> base, String... more) {
    List<String> cmd = new ArrayList<>(base);
    cmd.addAll(List.of(more));
    return cmd;
  }

  /** The log's content from {@code offset} on, i.e. the output of the last step. */
  private static String readFrom(Path log, long offset) throws IOException {
    if (!Files.exists(log)) {
      return "";
    }
    try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
      long length = Math.max(0, file.length() - offset);
      byte[] bytes = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
      file.seek(offset);
      file.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private String mvnw() {
    return config.checkout().resolve("mvnw").toString();
  }

  private String repoLocal() {
    return "-Dmaven.repo.local=" + config.mavenRepo();
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  private static Map<String, String> env(BranchConfig branch) {
    String path = System.getenv("PATH");
    String javaBin = branch.javaHome().resolve("bin").toString();
    return Map.of(
        "JAVA_HOME", branch.javaHome().toString(),
        "PATH", path == null || path.isEmpty() ? javaBin : javaBin + File.pathSeparator + path);
  }
}
