package io.github.kthoms.perflab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.config.ConfigLoader;
import io.github.kthoms.perflab.db.Database;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.git.OperatonCheckout;
import io.github.kthoms.perflab.idle.ActivityHistory;
import io.github.kthoms.perflab.idle.ActivityProbe;
import io.github.kthoms.perflab.idle.IdleGate;
import io.github.kthoms.perflab.jobs.DurationEstimator;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
import io.github.kthoms.perflab.results.ResultRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StatusReportIT {

  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  @TempDir Path tmp;

  Database db;
  JobRepository jobs;
  Duration userIdle = Duration.ofMinutes(1);

  final ActivityProbe probe =
      new ActivityProbe() {
        @Override
        public Optional<Duration> userIdle() {
          return Optional.of(userIdle);
        }

        @Override
        public double load1() {
          return 0.1;
        }

        @Override
        public boolean onAcPower() {
          return true;
        }

        @Override
        public List<String> heavyForeignProcesses() {
          return List.of();
        }
      };

  @BeforeEach
  void setUp() throws Exception {
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job restart identity cascade");
    }
    jobs = new JobRepository(db.dataSource());
  }

  String git(Path dir, String... args) throws Exception {
    var cmd = new java.util.ArrayList<String>();
    cmd.add("git");
    cmd.addAll(List.of(args));
    var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
    return out.trim();
  }

  StatusReport report(Path checkoutDir) {
    var config = ConfigLoader.load(Path.of("../config/perflab.example.yml"));
    var results = new ResultRepository(db.dataSource());
    return new StatusReport(
        config,
        db.dataSource(),
        jobs,
        new DurationEstimator(results),
        new IdleGate(probe, new ActivityHistory(db), config.idle()),
        new OperatonCheckout(checkoutDir, "https://example.invalid/operaton.git", new ProcessRunner()));
  }

  @Test
  void rendersRunningQueueLastRunsIdleDecisionAndNewReleaseBranches() throws Exception {
    long done = jobs.enqueue(JobKind.BUILD, "main", null, null, null, 10);
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute(
          "insert into run(job_id, kind, branch, commit_sha, status, started_at, segment)"
              + " values (" + done + ", 'BUILD', 'main', 'aaaaaaaaaaaaaaaaaaaaaaaa', 'SUCCESS',"
              + " '2026-11-20T01:00:00Z', 's')");
      st.execute("update job set status='DONE' where id=" + done);
    }
    long running = jobs.enqueue(JobKind.BUILD, "release/2.2.x", null, null, null, 10);
    jobs.markRunning(running);
    long manual = jobs.enqueue(JobKind.BUILD, null, "abc1234def", null, null, 0);
    jobs.enqueue(JobKind.BENCHMARK, "main", null, "task-authorization", "h2", 20);

    Path clone = Files.createDirectories(tmp.resolve("clone"));
    git(clone, "init", "-b", "main");
    git(clone, "config", "user.email", "t@example.com");
    git(clone, "config", "user.name", "t");
    git(clone, "config", "commit.gpgsign", "false");
    Files.writeString(clone.resolve("a.txt"), "a");
    git(clone, "add", ".");
    git(clone, "commit", "-m", "one");
    // origin/release/2.2.x is configured, origin/release/2.9.x is not
    git(clone, "update-ref", "refs/remotes/origin/release/2.2.x", "HEAD");
    git(clone, "update-ref", "refs/remotes/origin/release/2.9.x", "HEAD");

    String text = report(clone).render(Instant.parse("2026-11-21T10:00:00Z"));

    assertThat(text).contains("Running job:\n  #" + running + " BUILD release/2.2.x");
    assertThat(text).contains("Queue (2):");
    assertThat(text).contains("#" + manual + " prio 0 BUILD abc1234def");
    assertThat(text).contains("BENCHMARK main task-authorization h2");
    assertThat(text).contains("BUILD main: SUCCESS aaaaaaaaaa 2026-11-20T01:00:00Z");
    assertThat(text).contains("Idle decision for job #" + manual + " (expected 1h30m)");
    assertThat(text).contains("would not start: user active");
    assertThat(text).contains("Upstream release branches not in config:\n  release/2.9.x\n");
    assertThat(text).doesNotContain("  release/2.2.x\n");
    // read-only: still exactly one running and two queued jobs
    assertThat(jobs.queued()).hasSize(2);
  }

  @Test
  void saysSoWhenCheckoutDoesNotExistYetAndQueueIsEmpty() {
    userIdle = Duration.ofHours(3);

    String text = report(tmp.resolve("no-clone")).render(Instant.parse("2026-11-21T10:00:00Z"));

    assertThat(text).contains("Running job:\n  none");
    assertThat(text).contains("Queue (0):\n  empty");
    assertThat(text).contains("Last run per series:\n  none yet");
    assertThat(text).contains("no queued job");
    assertThat(text).contains("is not cloned yet");
    assertThat(tmp.resolve("no-clone")).doesNotExist();
  }
}
