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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkJobExecutorTest {

  private static final Instant T0 = Instant.parse("2026-11-21T02:00:00Z");
  private static final String VERSION = "2.3.0-SNAPSHOT";
  private static final String CONTAINER = "perflab-bench-pg-9";

  @TempDir Path dataDir;
  @TempDir Path perfLabHome;
  PerfLabConfig config;
  FakeCheckout checkout;
  ScriptedRunner runner;
  MutableClock clock;
  BenchmarkJobExecutor executor;

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
        readinessTimeout);
  }

  private static Job job(String benchmark, String db) {
    return new Job(9, JobKind.BENCHMARK, "main", null, benchmark, db, Job.PRIORITY_BENCHMARK, JobStatus.RUNNING, 1);
  }

  private static Predicate<List<String>> has(String arg) {
    return cmd -> cmd.contains(arg);
  }

  @Test
  void h2RunPreparesEngineCompilesBenchmarkAndRepeats() {
    Outcome outcome = executor.execute(job("task-authorization", "h2"), () -> false);

    assertThat(outcome).isInstanceOf(Completed.class);
    Completed done = (Completed) outcome;
    String mvnw = config.checkout().resolve("mvnw").toString();
    String repo = "-Dmaven.repo.local=" + config.mavenRepo();
    String pom = perfLabHome.resolve("benchmarks/pom.xml").toString();
    assertThat(runner.commands.get(0))
        .containsExactly(mvnw, "install", "-DskipTests", "-Pdistro", "-Dskip.frontend.build=true",
            "-pl", "engine", "-am", repo);
    assertThat(runner.commands.get(1)).startsWith(mvnw).contains("help:evaluate", "-Dexpression=project.version", repo);
    assertThat(runner.commands.get(2))
        .startsWith("mvn")
        .contains("-f", pom, repo, "-Doperaton.version=" + VERSION, "compile");
    assertThat(runner.commands.subList(3, 5))
        .allSatisfy(cmd -> assertThat(cmd)
            .contains("-f", pom, repo, "-Doperaton.version=" + VERSION, "-Dbenchmark.database=h2", "exec:exec"));
    assertThat(runner.commands).hasSize(5).noneMatch(cmd -> cmd.getFirst().equals("docker"));
    assertThat(runner.envs).allSatisfy(env -> assertThat(env).containsEntry("JAVA_HOME", "/opt/jdk21"));
    assertThat(checkout.checkedOut).containsExactly(MAIN_SHA);

    assertThat(done.run().kind()).isEqualTo(JobKind.BENCHMARK);
    assertThat(done.run().status()).isEqualTo("SUCCESS");
    assertThat(done.run().benchmark()).isEqualTo("task-authorization");
    assertThat(done.run().db()).isEqualTo("h2");
    assertThat(done.run().commitSha()).isEqualTo(MAIN_SHA);
    assertThat(done.run().logPath()).isEqualTo(config.logs().resolve("9-" + MAIN_SHA + ".log"));
    assertThat(done.measurements())
        .contains(
            new Measurement("benchmark.median.duration", 2.0, "ms",
                Map.of("benchmark", "task-authorization", "repetition", "1")),
            new Measurement("benchmark.median.duration", 2.0, "ms",
                Map.of("benchmark", "task-authorization", "repetition", "2")),
            new Measurement("benchmark.iteration.duration", 3.0, "ms",
                Map.of("benchmark", "task-authorization", "iteration", "3", "repetition", "2")))
        .hasSize(8);
  }

  @Test
  void compileFailureIsNotApplicable() {
    runner.exitCodes.put("compile", 1);

    Outcome outcome = executor.execute(job("task-authorization", "postgresql"), () -> false);

    assertThat(outcome).isInstanceOf(NotApplicable.class);
    assertThat(((NotApplicable) outcome).reason()).contains("compile").contains(VERSION);
    assertThat(runner.commands).noneMatch(has("exec:exec")).noneMatch(cmd -> cmd.getFirst().equals("docker"));
  }

  @Test
  void prepFailureYieldsFailedRunWithoutMeasurements() {
    runner.exitCodes.put("install", 1);

    Completed done = (Completed) executor.execute(job("task-authorization", "h2"), () -> false);

    assertThat(done.run().status()).isEqualTo("FAILED");
    assertThat(done.measurements()).isEmpty();
    assertThat(runner.commands).hasSize(1);
  }

  @Test
  void postgresContainerRemovedOnCancel() {
    runner.cancelOn = "exec:exec";
    CancellationToken token = () -> false;

    Outcome outcome = executor.execute(job("task-authorization", "postgresql"), token);

    assertThat(outcome).isInstanceOf(Cancelled.class);
    List<String> run =
        runner.commands.stream().filter(cmd -> cmd.size() > 1 && cmd.get(1).equals("run")).findFirst().orElseThrow();
    assertThat(run)
        .startsWith("docker", "run", "--rm", "-d", "--name", CONTAINER, "-p", "127.0.0.1:5434:5432")
        .contains("postgres:17");
    assertThat(runner.commands).anyMatch(cmd -> cmd.equals(List.of(
        "docker", "exec", CONTAINER, "pg_isready", "-h", "127.0.0.1", "-U", "postgres")));
    assertThat(runner.commands.stream().filter(has("exec:exec")).findFirst().orElseThrow())
        .contains("-Dbenchmark.database=postgresql", "-Dbenchmark.jdbcUrl=jdbc:postgresql://127.0.0.1:5434/postgres");
    assertThat(runner.commands.getLast()).containsExactly("docker", "rm", "-f", CONTAINER);
    // every process except the final cleanup is stopped by the job token
    assertThat(runner.tokens.subList(0, runner.tokens.size() - 1)).allMatch(t -> t == token);
  }

  @Test
  void postgresContainerRemovedWhenBenchmarkRunFails() {
    runner.failOn = "exec:exec";

    assertThatThrownBy(() -> executor.execute(job("task-authorization", "postgresql"), () -> false))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(runner.commands.getLast()).containsExactly("docker", "rm", "-f", CONTAINER);
  }

  @Test
  void postgresNeverReadyFailsAndRemovesContainer() {
    runner.exitCodes.put("pg_isready", 2);
    executor = executor(Duration.ZERO);

    assertThatThrownBy(() -> executor.execute(job("task-authorization", "postgresql"), () -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ready");

    assertThat(runner.commands).noneMatch(has("exec:exec"));
    assertThat(runner.commands.getLast()).containsExactly("docker", "rm", "-f", CONTAINER);
  }

  @Test
  void unknownBenchmarkOrDatabaseIsNotApplicable() {
    assertThat(executor.execute(job("other", "h2"), () -> false)).isInstanceOf(NotApplicable.class);
    assertThat(executor.execute(job("task-authorization", "oracle"), () -> false)).isInstanceOf(NotApplicable.class);
    assertThat(runner.commands).isEmpty();
  }

  /** Records commands; answers help:evaluate with a version and exec:exec with a result file. */
  static class ScriptedRunner extends ProcessRunner {
    final List<List<String>> commands = new ArrayList<>();
    final List<Map<String, String>> envs = new ArrayList<>();
    final List<CancellationToken> tokens = new ArrayList<>();
    /** Exit code for the first command containing the key as an argument. */
    final Map<String, Integer> exitCodes = new java.util.HashMap<>();
    String cancelOn;
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
      if (failOn != null && cmd.contains(failOn)) {
        throw new IOException("boom");
      }
      if (cancelOn != null && cmd.contains(cancelOn)) {
        return new Result(143, true, Duration.ofSeconds(1));
      }
      for (var e : exitCodes.entrySet()) {
        if (cmd.contains(e.getKey())) {
          return new Result(e.getValue(), false, Duration.ofSeconds(1));
        }
      }
      Files.createDirectories(logFile.getParent());
      if (cmd.contains("help:evaluate")) {
        Files.writeString(logFile, "Downloading wrapper...\n" + VERSION);
      }
      if (cmd.contains("exec:exec")) {
        String output =
            cmd.stream().filter(a -> a.startsWith("-Dbenchmark.output=")).findFirst().orElseThrow()
                .substring("-Dbenchmark.output=".length());
        Files.createDirectories(Path.of(output).getParent());
        Files.writeString(
            Path.of(output),
            "{\"benchmark\":\"task-authorization\",\"database\":\"h2\",\"params\":{},"
                + "\"iterationsMs\":[1.0,2.0,3.0]}");
      }
      return new Result(0, false, Duration.ofSeconds(1));
    }
  }
}
