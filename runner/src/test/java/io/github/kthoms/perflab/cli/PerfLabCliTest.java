package io.github.kthoms.perflab.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.jobs.Job;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PerfLabCliTest {

  static final Path CONFIG = Path.of("../config/perflab.example.yml");

  @TempDir Path tmp;

  /** Records calls instead of talking to a database. */
  static class RecordingJobs extends JobRepository {
    final List<Object[]> enqueued = new ArrayList<>();
    int cancels;

    RecordingJobs() {
      super(null);
    }

    @Override
    public long enqueue(JobKind kind, String branch, String commitSha, String benchmark, String db, int priority) {
      enqueued.add(new Object[] {kind, branch, commitSha, benchmark, db, priority});
      return 42;
    }

    @Override
    public void requestCancel() {
      cancels++;
    }
  }

  final RecordingJobs jobs = new RecordingJobs();
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();

  int run(String... args) {
    CommandLine cmd = PerfLabCli.commandLine(new PerfLabCli.Wiring(config -> jobs, config -> null));
    cmd.setOut(new PrintWriter(out, true));
    cmd.setErr(new PrintWriter(err, true));
    return cmd.execute(args);
  }

  @Test
  void enqueueCommitUsesManualPriority() {
    int exit = run("enqueue", "--config", CONFIG.toString(), "--commit", "abc1234def");

    assertThat(exit).isZero();
    assertThat(jobs.enqueued).hasSize(1);
    assertThat(jobs.enqueued.get(0))
        .containsExactly(JobKind.BUILD, null, "abc1234def", null, null, Job.PRIORITY_MANUAL);
    assertThat(Job.PRIORITY_MANUAL).isZero();
    assertThat(out.toString()).contains("42");
  }

  @Test
  void enqueueBranchBenchmarkJob() {
    int exit = run("enqueue", "--config", CONFIG.toString(), "--branch", "main",
        "--benchmark", "task-authorization", "--db", "h2");

    assertThat(exit).isZero();
    assertThat(jobs.enqueued.get(0))
        .containsExactly(JobKind.BENCHMARK, "main", null, "task-authorization", "h2", 0);
  }

  @Test
  void enqueueRequiresBranchOrCommit() {
    assertThat(run("enqueue", "--config", CONFIG.toString())).isNotZero();
    assertThat(err.toString()).contains("--branch or --commit");
    assertThat(run("enqueue", "--config", CONFIG.toString(), "--branch", "main", "--commit", "abc1234")).isNotZero();
    assertThat(run("enqueue", "--config", CONFIG.toString(), "--branch", "main", "--benchmark", "b")).isNotZero();
    assertThat(run("enqueue", "--config", CONFIG.toString(), "--commit", "not-a-sha")).isNotZero();
    assertThat(jobs.enqueued).isEmpty();
  }

  @Test
  void cancelRequestsCancellation() {
    assertThat(run("cancel", "--config", CONFIG.toString())).isZero();
    assertThat(jobs.cancels).isEqualTo(1);
  }

  @Test
  void missingConfigFileIsReportedWithoutStackTrace() {
    assertThat(run("cancel", "--config", tmp.resolve("nope.yml").toString())).isEqualTo(1);
    assertThat(err.toString()).startsWith("perflab: ").contains("nope.yml");
    assertThat(err.toString()).doesNotContain("\tat ");
  }

  @Test
  void secondDaemonInstanceIsRefused() throws Exception {
    Path lock = tmp.resolve("daemon.lock");
    try (var first = SingleInstanceLock.acquire(lock)) {
      int exit = run("daemon", "--config", CONFIG.toString(), "--lock-file", lock.toString());

      assertThat(exit).isNotZero();
      assertThat(err.toString()).contains("already running").contains(lock.toString());
    }
    // released again: acquiring works after the first holder is gone
    SingleInstanceLock.acquire(lock).close();
  }

  @Test
  void lockCanOnlyBeHeldOnce() throws Exception {
    Path lock = tmp.resolve("sub/daemon.lock");
    try (var first = SingleInstanceLock.acquire(lock)) {
      assertThatThrownBy(() -> SingleInstanceLock.acquire(lock))
          .isInstanceOf(SingleInstanceLock.AlreadyRunningException.class);
    }
  }
}
