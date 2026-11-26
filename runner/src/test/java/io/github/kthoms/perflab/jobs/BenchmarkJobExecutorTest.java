package io.github.kthoms.perflab.jobs;

import static io.github.kthoms.perflab.jobs.BuildFakes.MAIN_SHA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BenchmarkConfig;
import io.github.kthoms.perflab.env.FingerprintCollector;
import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.jobs.BuildFakes.FakeCheckout;
import io.github.kthoms.perflab.jobs.BuildFakes.MutableClock;
import io.github.kthoms.perflab.jobs.JobExecutor.Cancelled;
import io.github.kthoms.perflab.jobs.JobExecutor.Completed;
import io.github.kthoms.perflab.jobs.JobExecutor.NotApplicable;
import io.github.kthoms.perflab.jobs.JobExecutor.Outcome;
import io.github.kthoms.perflab.results.Measurement;
import io.github.kthoms.perflab.results.RunRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkJobExecutorTest {

  private static final Instant T0 = Instant.parse("2026-11-21T02:00:00Z");
  private static final String VERSION = "2.3.0-SNAPSHOT";
  private static final String CONTAINER = "perflab-bench-pg-9";
  private static final List<String> REMOVE_CONTAINER = List.of("docker", "rm", "-f", CONTAINER);

  @TempDir Path dataDir;
  @TempDir Path perfLabHome;
  PerfLabConfig config;
  FakeCheckout checkout;
  ScriptedRunner runner;
  MutableClock clock;
  BenchmarkJobExecutor executor;
  /** The job's cancellation flag; the fake runner can raise it while a command "runs". */
  final AtomicBoolean cancelled = new AtomicBoolean();
  final CancellationToken token = cancelled::get;

  @BeforeEach
  void setUp() throws IOException {
    BuildFakes.quietLogs();
    PerfLabConfig base = BuildFakes.config(dataDir);
    config =
        new PerfLabConfig(
            base.dataDir(),
            base.backupDir(),
            base.backupRetentionDays(),
            base.upstreamUrl(),
            base.resultsDb(),
            base.branches(),
            List.of(new BenchmarkConfig("task-authorization", List.of("h2", "postgresql"), List.of("main"), 2)),
            base.idle());
    Files.createDirectories(perfLabHome.resolve("benchmarks"));
    Files.writeString(perfLabHome.resolve("benchmarks/pom.xml"), "<project/>");
    checkout = new FakeCheckout(config.checkout());
    runner = new ScriptedRunner();
    clock = new MutableClock(T0);
    executor = executor(Duration.ofSeconds(5));
  }

  private BenchmarkJobExecutor executor(Duration readinessTimeout) {
    return new BenchmarkJobExecutor(
        config,
        perfLabHome,
        checkout,
        new CommitResolver(checkout, config.branches()),
        runner,
        new FingerprintCollector(
            cmd -> {
              throw new IllegalStateException("no probes in tests");
            }),
        clock,
        readinessTimeout,
        guard);
  }

  /** perfLabHome is a plain temp dir in these tests; PerfLabHomeGuardTest covers the real check. */
  RuntimeException guardFailure;
  final PerfLabHomeGuard guard =
      new PerfLabHomeGuard() {
        @Override
        public void verify(Path home) {
          if (guardFailure != null) {
            throw guardFailure;
          }
        }
      };

  private static Job job(String db) {
    return job("task-authorization", db);
  }

  private static Job job(String benchmark, String db) {
    return new Job(9, JobKind.BENCHMARK, "main", null, benchmark, db, Job.PRIORITY_BENCHMARK, JobStatus.RUNNING, 1);
  }

  private static Predicate<List<String>> has(String arg) {
    return cmd -> cmd.contains(arg);
  }

  private static boolean isDocker(List<String> cmd) {
    return cmd.getFirst().equals("docker");
  }

  @Test
  void h2RunPreparesEngineResolvesCompilesOfflineAndRepeats() {
    Outcome outcome = executor.execute(job("h2"), token);

    assertThat(outcome).isInstanceOf(Completed.class);
    Completed done = (Completed) outcome;
    String mvnw = config.checkout().resolve("mvnw").toString();
    String repo = "-Dmaven.repo.local=" + config.mavenRepo();
    String pom = perfLabHome.resolve("benchmarks/pom.xml").toString();
    assertThat(runner.commands.get(0))
        .containsExactly(mvnw, "install", "-DskipTests", "-Pdistro", "-Dskip.frontend.build=true",
            "-pl", "engine", "-am", repo);
    assertThat(runner.commands.get(1))
        .startsWith(mvnw)
        .contains("help:evaluate", "-Dexpression=project.version", "-q", "-DforceStdout", repo)
        .anyMatch(a -> a.startsWith("-Doutput="));
    assertThat(runner.commands.get(2))
        .startsWith("mvn")
        .contains("-f", pom, repo, "-Doperaton.version=" + VERSION, "dependency:resolve", "dependency:resolve-plugins")
        .doesNotContain("-o");
    assertThat(runner.commands.get(3)).contains("-f", pom, "-o", "-Doperaton.version=" + VERSION, "compile");
    assertThat(runner.commands.subList(4, 6))
        .allSatisfy(cmd -> assertThat(cmd)
            .contains("-f", pom, "-o", repo, "-Doperaton.version=" + VERSION, "-Dbenchmark.database=h2", "exec:exec"));
    assertThat(runner.commands).hasSize(6).noneMatch(BenchmarkJobExecutorTest::isDocker);
    assertThat(runner.envs).allSatisfy(env -> assertThat(env).containsEntry("JAVA_HOME", "/opt/jdk21"));
    assertThat(runner.tokens).allMatch(t -> t == token);
    assertThat(checkout.checkedOut).containsExactly(MAIN_SHA);

    assertThat(done.run().kind()).isEqualTo(JobKind.BENCHMARK);
    assertThat(done.run().status()).isEqualTo("SUCCESS");
    assertThat(done.run().benchmark()).isEqualTo("task-authorization");
    assertThat(done.run().db()).isEqualTo("h2");
    assertThat(done.run().commitSha()).isEqualTo(MAIN_SHA);
    assertThat(done.run().source()).isEqualTo(RunRecord.SOURCE_BRANCH);
    assertThat(done.run().logPath()).isEqualTo(config.logs().resolve("9-" + MAIN_SHA + ".log"));
    assertThat(done.measurements())
        .contains(
            new Measurement("benchmark.median.duration", 2.0, "ms",
                Map.of("benchmark", "task-authorization", "repetition", "1")),
            new Measurement("benchmark.median.duration", 2.0, "ms",
                Map.of("benchmark", "task-authorization", "repetition", "2")),
            new Measurement("benchmark.iteration.duration", 3.0, "ms",
                Map.of("benchmark", "task-authorization", "iteration", "3", "repetition", "2")),
            new Measurement("benchmark.operation.median.duration", 1.0, "ms",
                Map.of("benchmark", "task-authorization", "operation", "count", "repetition", "1")))
        .hasSize(12); // per repetition: 3 iterations, median, 2 operation medians
  }

  @Test
  void sizedSeriesPassesTheTaskCountAndKeepsTheSizeInTheSeriesName() {
    config = withTasks(List.of(1000, 5000));
    executor = executor(Duration.ofSeconds(5));

    Completed done = (Completed) executor.execute(job("task-authorization@1000", "h2"), token);

    assertThat(runner.commands.stream().filter(has("exec:exec")))
        .isNotEmpty()
        .allSatisfy(cmd -> assertThat(cmd).contains("-Dbenchmark.tasks=1000"));
    assertThat(done.run().benchmark()).isEqualTo("task-authorization@1000");
    assertThat(done.run().status()).isEqualTo("SUCCESS");
    assertThat(done.measurements())
        .isNotEmpty()
        .allSatisfy(m -> assertThat(m.dims()).containsEntry("benchmark", "task-authorization@1000"));
  }

  @Test
  void unsizedSeriesPassesNoTaskCount() {
    executor.execute(job("h2"), token);
    assertThat(runner.commands.stream().filter(has("exec:exec")))
        .isNotEmpty()
        .allSatisfy(cmd -> assertThat(cmd).noneMatch(a -> a.startsWith("-Dbenchmark.tasks=")));
  }

  @Test
  void aSizeThatIsNotConfiguredIsNotApplicable() {
    config = withTasks(List.of(1000));
    executor = executor(Duration.ofSeconds(5));

    Outcome outcome = executor.execute(job("task-authorization@5000", "h2"), token);

    assertThat(outcome).isInstanceOf(NotApplicable.class);
    assertThat(runner.commands).isEmpty();
  }

  private PerfLabConfig withTasks(List<Integer> tasks) {
    return new PerfLabConfig(
        config.dataDir(), config.backupDir(), config.backupRetentionDays(), config.upstreamUrl(),
        config.resultsDb(), config.branches(),
        List.of(new BenchmarkConfig("task-authorization", List.of("h2", "postgresql"), List.of("main"), 2, tasks)),
        config.idle());
  }

  @Test
  void prepInstallsTheBomModulesWhereTheCheckoutHasThem() throws IOException {
    Files.createDirectories(config.checkout().resolve("bom/operaton-bom"));

    executor.execute(job("h2"), token);

    assertThat(runner.commands.get(0))
        .containsSequence("-pl", "engine,bom/operaton-bom,bom/operaton-only-bom", "-am");
  }

  @Test
  void commitJobRunIsMarkedAsCommitSource() {
    Job commitJob =
        new Job(9, JobKind.BENCHMARK, null, MAIN_SHA, "task-authorization", "h2", Job.PRIORITY_MANUAL,
            JobStatus.RUNNING, 1);
    Completed done = (Completed) executor.execute(commitJob, token);
    assertThat(done.run().source()).isEqualTo(RunRecord.SOURCE_COMMIT);
  }

  @Test
  void dirtyPerfLabHomeStopsTheJobBeforeAnythingIsBuilt() {
    guardFailure = new IllegalStateException("perfLabHome is not clean: M benchmarks/pom.xml");
    assertThatThrownBy(() -> executor.execute(job("h2"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not clean");
    assertThat(runner.commands).isEmpty();
    assertThat(checkout.checkedOut).isEmpty();
  }

  @Test
  void realGuardRejectsAPerfLabHomeThatIsNoGitRepository() {
    BenchmarkJobExecutor real =
        new BenchmarkJobExecutor(
            config, perfLabHome, checkout, new CommitResolver(checkout, config.branches()), runner,
            new FingerprintCollector(cmd -> {
              throw new IllegalStateException("no probes in tests");
            }),
            clock);
    assertThatThrownBy(() -> real.execute(job("h2"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a git working tree");
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void compilationErrorIsNotApplicable() {
    runner.exitCodes.put("compile", 1);
    runner.logOutput.put("compile", "[ERROR] COMPILATION ERROR : \n[ERROR] Foo.java:[3,1] cannot find symbol\n");

    Outcome outcome = executor.execute(job("postgresql"), token);

    assertThat(outcome).isInstanceOf(NotApplicable.class);
    assertThat(((NotApplicable) outcome).reason()).contains("compile").contains(VERSION);
    assertThat(runner.commands).noneMatch(has("exec:exec")).noneMatch(BenchmarkJobExecutorTest::isDocker);
  }

  @Test
  void compileStepFailureWithoutCompilationErrorIsAnInfrastructureFailure() {
    runner.exitCodes.put("compile", 1);
    runner.logOutput.put("compile", "[ERROR] Failed to execute goal ... Cannot access central in offline mode\n");

    assertThatThrownBy(() -> executor.execute(job("h2"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("compile");
  }

  @Test
  void compilationErrorInEarlierLogOutputDoesNotCount() {
    // e.g. the engine build logged the marker; only the compile step's own output is inspected
    runner.logOutput.put("install", "[WARNING] test output mentioning COMPILATION ERROR\n");
    runner.exitCodes.put("compile", 1);

    assertThatThrownBy(() -> executor.execute(job("h2"), token)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void dependencyResolutionFailureIsAnInfrastructureFailure() {
    runner.exitCodes.put("dependency:resolve", 1);

    assertThatThrownBy(() -> executor.execute(job("h2"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("resolve");
    assertThat(runner.commands).noneMatch(has("compile"));
  }

  @Test
  void prepFailureIsAnInfrastructureFailure() {
    runner.exitCodes.put("install", 1);

    assertThatThrownBy(() -> executor.execute(job("h2"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("install");
    assertThat(runner.commands).hasSize(1);
  }

  @Test
  void unreadableVersionIsAnInfrastructureFailure() {
    runner.version = "[ERROR] no pom";

    assertThatThrownBy(() -> executor.execute(job("h2"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("version");
  }

  @Test
  void sweepsLeftoverBenchmarkContainersBeforeStartingPostgres() {
    runner.psOutput =
        "aaa111\tperflab-bench-pg-3\tbench-pg\n"
            + "bbb222\told-name\tbench-pg\n"
            + "ccc333\tperflab-bench-pg-7\t\n"
            + "ddd444\tperflab-t11-results-db-1\t\n";

    executor.execute(job("postgresql"), token);

    int ps = indexOf(cmd -> cmd.size() > 1 && cmd.get(1).equals("ps"));
    int sweep = indexOf(cmd -> cmd.equals(List.of("docker", "rm", "-f", "aaa111", "bbb222", "ccc333")));
    int run = indexOf(cmd -> cmd.size() > 1 && cmd.get(1).equals("run"));
    assertThat(ps).isNotNegative().isLessThan(sweep);
    assertThat(sweep).isLessThan(run);
    assertThat(runner.commands.get(ps)).contains("-a", "--format");
    assertThat(runner.commands.get(run))
        .startsWith("docker", "run", "--rm", "-d", "--name", CONTAINER,
            "--label", "io.github.kthoms.perflab=bench-pg", "-p", "127.0.0.1:5434:5432")
        .contains("postgres:17");
    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
  }

  @Test
  void noSweepWhenNoLeftoverContainers() {
    executor.execute(job("postgresql"), token);

    assertThat(runner.commands)
        .filteredOn(cmd -> cmd.size() > 1 && cmd.get(1).equals("rm"))
        .containsExactly(REMOVE_CONTAINER);
  }

  @Test
  void failingContainerListingIsAnInfrastructureFailure() {
    runner.exitCodes.put("ps", 1);

    assertThatThrownBy(() -> executor.execute(job("postgresql"), token))
        .isInstanceOf(IllegalStateException.class);
    assertThat(runner.commands).noneMatch(cmd -> cmd.size() > 1 && cmd.get(1).equals("run"));
  }

  @Test
  void postgresContainerRemovedOnCancel() {
    runner.cancelOn = "exec:exec";

    Outcome outcome = executor.execute(job("postgresql"), token);

    assertThat(outcome).isInstanceOf(Cancelled.class);
    assertThat(runner.commands).anyMatch(cmd -> cmd.equals(List.of(
        "docker", "exec", CONTAINER, "pg_isready", "-h", "127.0.0.1", "-U", "postgres")));
    assertThat(runner.commands.stream().filter(has("exec:exec")).findFirst().orElseThrow())
        .contains("-Dbenchmark.database=postgresql", "-Dbenchmark.jdbcUrl=jdbc:postgresql://127.0.0.1:5434/postgres");
    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
    // every process except the final cleanup is stopped by the job token
    assertThat(runner.tokens.subList(0, runner.tokens.size() - 1)).allMatch(t -> t == token);
    assertThat(runner.tokens.getLast()).isNotSameAs(token);
  }

  @Test
  void cancelByTokenDuringBenchmarkRunIsCancelledAndRemovesContainer() {
    runner.raiseCancelOn = "exec:exec"; // the process ends normally, but the token is cancelled

    Outcome outcome = executor.execute(job("postgresql"), token);

    assertThat(outcome).isInstanceOf(Cancelled.class);
    assertThat(runner.commands).filteredOn(has("exec:exec")).hasSize(1);
    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
  }

  @Test
  void cancelDuringReadinessWaitRemovesContainer() {
    runner.exitCodes.put("pg_isready", 2);
    runner.raiseCancelOn = "pg_isready";

    Outcome outcome = executor.execute(job("postgresql"), token);

    assertThat(outcome).isInstanceOf(Cancelled.class);
    assertThat(runner.commands).noneMatch(has("exec:exec"));
    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
  }

  @Test
  void postgresContainerRemovedWhenBenchmarkRunFails() {
    runner.failOn = "exec:exec";

    assertThatThrownBy(() -> executor.execute(job("postgresql"), token))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
  }

  @Test
  void postgresNeverReadyFailsAndRemovesContainer() {
    runner.exitCodes.put("pg_isready", 2);
    executor = executor(Duration.ZERO);

    assertThatThrownBy(() -> executor.execute(job("postgresql"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ready");

    assertThat(runner.commands).noneMatch(has("exec:exec"));
    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
  }

  @Test
  void failingBenchmarkRunIsStoredAsFailed() {
    runner.exitCodes.put("exec:exec", 1);

    Completed done = (Completed) executor.execute(job("h2"), token);

    assertThat(done.run().status()).isEqualTo("FAILED");
    assertThat(done.measurements()).isEmpty();
  }

  @Test
  void unreadableResultIsStoredAsFailed() {
    runner.result = "{not json";

    Completed done = (Completed) executor.execute(job("postgresql"), token);

    assertThat(done.run().status()).isEqualTo("FAILED");
    assertThat(done.measurements()).isEmpty();
    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
  }

  @Test
  void resultOfAnotherDatabaseIsAnInfrastructureFailure() {
    runner.result =
        "{\"benchmark\":\"task-authorization\",\"database\":\"h2\",\"params\":{},\"iterationsMs\":[1.0]}";

    assertThatThrownBy(() -> executor.execute(job("postgresql"), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("h2");
    assertThat(runner.commands.getLast()).isEqualTo(REMOVE_CONTAINER);
  }

  @Test
  void unknownBenchmarkOrDatabaseIsNotApplicable() {
    assertThat(executor.execute(job("other", "h2"), token)).isInstanceOf(NotApplicable.class);
    assertThat(executor.execute(job("oracle"), token)).isInstanceOf(NotApplicable.class);
    assertThat(runner.commands).isEmpty();
  }

  private int indexOf(Predicate<List<String>> p) {
    for (int i = 0; i < runner.commands.size(); i++) {
      if (p.test(runner.commands.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Records commands. Answers {@code help:evaluate} with a version in its {@code -Doutput} file,
   * {@code docker ps} with {@link #psOutput}, and {@code exec:exec} with a result file for the
   * requested database.
   */
  class ScriptedRunner extends ProcessRunner {
    final List<List<String>> commands = new ArrayList<>();
    final List<Map<String, String>> envs = new ArrayList<>();
    final List<CancellationToken> tokens = new ArrayList<>();
    /** Exit code for commands containing the key as an argument. */
    final Map<String, Integer> exitCodes = new HashMap<>();
    /** Text appended to the log for commands containing the key as an argument. */
    final Map<String, String> logOutput = new HashMap<>();
    String version = VERSION;
    String psOutput = "";
    String result;
    /** The runner reports the process as killed by cancellation. */
    String cancelOn;
    /** The process ends normally, but the job token is cancelled meanwhile. */
    String raiseCancelOn;
    String failOn;

    @Override
    public Result run(
        List<String> cmd,
        Path workDir,
        Map<String, String> env,
        Path logFile,
        CancellationToken token,
        Duration checkEvery)
        throws IOException {
      commands.add(List.copyOf(cmd));
      envs.add(Map.copyOf(env));
      tokens.add(token);
      Files.createDirectories(logFile.getParent());
      if (failOn != null && cmd.contains(failOn)) {
        throw new IOException("boom");
      }
      if (cancelOn != null && cmd.contains(cancelOn)) {
        return new Result(143, true, Duration.ofSeconds(1));
      }
      if (raiseCancelOn != null && cmd.contains(raiseCancelOn)) {
        cancelled.set(true);
      }
      for (var e : logOutput.entrySet()) {
        if (cmd.contains(e.getKey())) {
          Files.writeString(logFile, e.getValue(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
      }
      for (var e : exitCodes.entrySet()) {
        if (cmd.contains(e.getKey())) {
          return new Result(e.getValue(), false, Duration.ofSeconds(1));
        }
      }
      if (cmd.contains("help:evaluate")) {
        Files.writeString(Path.of(arg(cmd, "-Doutput=")), version);
      }
      if (cmd.size() > 1 && cmd.getFirst().equals("docker") && cmd.get(1).equals("ps")) {
        Files.writeString(logFile, psOutput, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
      if (cmd.contains("exec:exec")) {
        Path output = Path.of(arg(cmd, "-Dbenchmark.output="));
        Files.createDirectories(output.getParent());
        Files.writeString(
            output,
            result != null
                ? result
                : "{\"benchmark\":\"task-authorization\",\"database\":\"" + arg(cmd, "-Dbenchmark.database=")
                    + "\",\"params\":" + (cmd.stream().anyMatch(a -> a.startsWith("-Dbenchmark.tasks="))
                        ? "{\"tasks\":" + arg(cmd, "-Dbenchmark.tasks=") + "}" : "{}")
                    + ",\"iterationsMs\":[1.0,2.0,3.0],"
                    + "\"operationsMs\":{\"listPage\":[0.5,1.0,1.5],\"count\":[0.5,1.0,1.5]}}");
      }
      return new Result(0, false, Duration.ofSeconds(1));
    }

    private static String arg(List<String> cmd, String prefix) {
      return cmd.stream().filter(a -> a.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length());
    }
  }
}
