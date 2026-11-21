package io.github.kthoms.perflab.jobs;

import static io.github.kthoms.perflab.jobs.BuildFakes.MAIN_SHA;
import static io.github.kthoms.perflab.jobs.BuildFakes.OTHER_SHA;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.env.FingerprintCollector;
import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.jobs.BuildFakes.FakeCheckout;
import io.github.kthoms.perflab.jobs.BuildFakes.FakeRunner;
import io.github.kthoms.perflab.jobs.BuildFakes.MutableClock;
import io.github.kthoms.perflab.jobs.JobExecutor.Cancelled;
import io.github.kthoms.perflab.jobs.JobExecutor.Completed;
import io.github.kthoms.perflab.jobs.JobExecutor.NotApplicable;
import io.github.kthoms.perflab.jobs.JobExecutor.Outcome;
import io.github.kthoms.perflab.results.Measurement;
import io.github.kthoms.perflab.results.RunRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildJobExecutorTest {

  private static final Instant T0 = Instant.parse("2026-11-21T02:00:00Z");
  private static final CancellationToken NEVER = () -> false;

  @TempDir Path dataDir;
  PerfLabConfig config;
  FakeCheckout checkout;
  FakeRunner runner;
  MutableClock clock;
  BuildJobExecutor executor;

  @BeforeEach
  void setUp() {
    BuildFakes.quietLogs();
    config = BuildFakes.config(dataDir);
    checkout = new FakeCheckout(config.checkout());
    runner = new FakeRunner();
    clock = new MutableClock(T0);
    runner.script =
        (token, every) -> {
          clock.advance(Duration.ofMinutes(42));
          return new ProcessRunner.Result(0, false, Duration.ofMinutes(42));
        };
    executor =
        new BuildJobExecutor(
            config,
            checkout,
            new CommitResolver(checkout, config.branches()),
            runner,
            new FingerprintCollector(
                cmd -> {
                  throw new IllegalStateException("no probes in tests");
                }),
            clock);
  }

  private static Job branchJob(String branch) {
    return new Job(7, JobKind.BUILD, branch, null, null, null, Job.PRIORITY_BUILD, JobStatus.RUNNING, 1);
  }

  private static Job commitJob(String sha) {
    return new Job(8, JobKind.BUILD, null, sha, null, null, Job.PRIORITY_MANUAL, JobStatus.RUNNING, 1);
  }

  @Test
  void successfulBuildRunsMavenWrapperAndExtractsMeasurements() {
    Outcome outcome = executor.execute(branchJob("main"), NEVER);

    assertThat(outcome).isInstanceOf(Completed.class);
    Completed done = (Completed) outcome;
    RunRecord run = done.run();
    Path checkoutDir = config.checkout();
    String expectedCmd =
        checkoutDir.resolve("mvnw")
            + " -Pfast clean install -Dmaven.repo.local="
            + config.mavenRepo();
    assertThat(run.status()).isEqualTo("SUCCESS");
    assertThat(run.jobId()).isEqualTo(7);
    assertThat(run.kind()).isEqualTo(JobKind.BUILD);
    assertThat(run.branch()).isEqualTo("main");
    assertThat(run.commitSha()).isEqualTo(MAIN_SHA);
    assertThat(run.startedAt()).isEqualTo(T0);
    assertThat(run.finishedAt()).isEqualTo(T0.plus(Duration.ofMinutes(42)));
    assertThat(run.commandLine()).isEqualTo(expectedCmd);
    assertThat(run.logPath()).isEqualTo(config.logs().resolve("7-" + MAIN_SHA + ".log"));
    assertThat(run.fingerprint().stable()).containsEntry("mavenArgs", "-Pfast clean install");

    assertThat(checkout.checkedOut).containsExactly(MAIN_SHA);
    assertThat(runner.commands.getFirst())
        .containsExactly(
            checkoutDir.resolve("mvnw").toString(),
            "-Pfast",
            "clean",
            "install",
            "-Dmaven.repo.local=" + config.mavenRepo());
    assertThat(runner.workDirs.getFirst()).isEqualTo(checkoutDir);
    assertThat(runner.logs.getFirst()).isEqualTo(run.logPath());
    assertThat(runner.envs.getFirst()).containsEntry("JAVA_HOME", "/opt/jdk21");
    assertThat(runner.envs.getFirst().get("PATH")).startsWith("/opt/jdk21/bin:");

    assertThat(done.measurements())
        .contains(
            new Measurement("build.total.wall", 62.0, "s", java.util.Map.of()),
            new Measurement(
                "test.class.duration",
                2.5,
                "s",
                java.util.Map.of("module", "engine", "class", "org.x.ATest")));
  }

  @Test
  void checkoutOperationsGetTheJobToken() {
    CancellationToken token = () -> false;
    executor.execute(branchJob("main"), token);
    assertThat(checkout.tokens).isNotEmpty().allMatch(t -> t == token);
  }

  @Test
  void failedBuildYieldsFailedRunWithoutMeasurements() {
    runner.script = (token, every) -> new ProcessRunner.Result(1, false, Duration.ofMinutes(3));

    Outcome outcome = executor.execute(branchJob("main"), NEVER);

    assertThat(outcome).isInstanceOf(Completed.class);
    Completed done = (Completed) outcome;
    assertThat(done.run().status()).isEqualTo("FAILED");
    assertThat(done.run().logPath()).isEqualTo(config.logs().resolve("7-" + MAIN_SHA + ".log"));
    assertThat(done.measurements()).isEmpty();
  }

  @Test
  void cancelledBuildYieldsCancelled() {
    runner.script = (token, every) -> new ProcessRunner.Result(143, true, Duration.ofSeconds(5));
    assertThat(executor.execute(branchJob("main"), NEVER)).isInstanceOf(Cancelled.class);
  }

  @Test
  void cancellationDuringCheckoutYieldsCancelled() {
    checkout.checkoutFailure = new IllegalStateException("git checkout was cancelled");
    assertThat(executor.execute(branchJob("main"), () -> true)).isInstanceOf(Cancelled.class);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void unreachableCommitIsNotApplicable() {
    String fork = "c".repeat(40);

    Outcome outcome = executor.execute(commitJob(fork), NEVER);

    assertThat(outcome).isInstanceOf(NotApplicable.class);
    assertThat(((NotApplicable) outcome).reason()).contains(fork).contains("not reachable");
    assertThat(checkout.checkedOut).isEmpty();
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void commitJobUsesConfigOfBranchContainingShaPreferringMain() {
    checkout.contains.add("1.0:" + MAIN_SHA);

    Completed done = (Completed) executor.execute(commitJob(MAIN_SHA.substring(0, 10)), NEVER);

    assertThat(done.run().branch()).isEqualTo("main");
    assertThat(done.run().commitSha()).isEqualTo(MAIN_SHA);
    assertThat(runner.envs.getFirst()).containsEntry("JAVA_HOME", "/opt/jdk21");
  }

  @Test
  void runSourceIsBranchForBranchJobsAndCommitForCommitJobs() {
    Completed branchRun = (Completed) executor.execute(branchJob("main"), NEVER);
    Completed commitRun = (Completed) executor.execute(commitJob(MAIN_SHA), NEVER);
    assertThat(branchRun.run().source()).isEqualTo(RunRecord.SOURCE_BRANCH);
    assertThat(commitRun.run().source()).isEqualTo(RunRecord.SOURCE_COMMIT);
  }

  @Test
  void commitJobOnlyOnMaintenanceBranchUsesThatBranchConfig() {
    Completed done = (Completed) executor.execute(commitJob(OTHER_SHA), NEVER);

    assertThat(done.run().branch()).isEqualTo("1.0");
    assertThat(runner.envs.getFirst()).containsEntry("JAVA_HOME", "/opt/jdk17");
    assertThat(runner.commands.getFirst()).contains("clean", "install").doesNotContain("-Pfast");
  }

  @Test
  void unconfiguredBranchIsNotApplicable() {
    assertThat(executor.execute(branchJob("feature-x"), NEVER)).isInstanceOf(NotApplicable.class);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void logOfAnEarlierAttemptIsReplaced() throws Exception {
    Path log = config.logs().resolve("7-" + MAIN_SHA + ".log");
    Files.createDirectories(log.getParent());
    Files.writeString(log, "[INFO] Old Module ........ SUCCESS [ 9.000 s]\n");

    Completed done = (Completed) executor.execute(branchJob("main"), NEVER);

    List<Measurement> modules =
        done.measurements().stream().filter(m -> m.kpi().equals("build.module.wall")).toList();
    assertThat(modules).extracting(m -> m.dims().get("module")).containsExactly("Operaton - Engine");
  }

  @Test
  void extractorFailureAfterSuccessfulBuildYieldsFailedRunWithoutMeasurements() {
    runner.script =
        (token, every) -> {
          try {
            Files.delete(runner.logs.getLast()); // the reactor extractor cannot read the log
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          return new ProcessRunner.Result(0, false, Duration.ofMinutes(42));
        };

    Outcome outcome = executor.execute(branchJob("main"), NEVER);

    assertThat(outcome).isInstanceOf(Completed.class);
    assertThat(((Completed) outcome).run().status()).isEqualTo("FAILED");
    assertThat(((Completed) outcome).measurements()).isEmpty();
  }
}
