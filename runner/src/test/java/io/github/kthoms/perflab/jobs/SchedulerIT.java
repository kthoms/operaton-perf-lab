package io.github.kthoms.perflab.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BenchmarkConfig;
import io.github.kthoms.perflab.db.Database;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.results.ResultRepository;
import io.github.kthoms.perflab.results.RunRecord;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SchedulerIT {

  @Container
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  private static final Instant NOW = Instant.parse("2026-11-21T02:00:00Z");

  private JobRepository jobs;
  private ResultRepository results;
  private Scheduler scheduler;

  @BeforeEach
  void setUp() throws SQLException {
    BuildFakes.quietLogs();
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job restart identity cascade");
    }
    jobs = new JobRepository(db.dataSource());
    results = new ResultRepository(db.dataSource());
    PerfLabConfig base = BuildFakes.config(Path.of("/nvme/perflab"));
    PerfLabConfig config =
        new PerfLabConfig(
            base.dataDir(),
            base.backupDir(),
            base.backupRetentionDays(),
            base.upstreamUrl(),
            base.resultsDb(),
            base.branches(),
            List.of(new BenchmarkConfig("task-auth", List.of("h2", "postgres"), List.of("main"), 5)),
            base.idle());
    scheduler = new Scheduler(jobs, results, config);
  }

  private Database db;

  /** A finished job of the series whose finished_at is pinned (finish() uses the DB clock). */
  private long finishedJob(
      JobKind kind, String branch, String benchmark, String db, JobStatus status, Instant finishedAt) {
    long jobId = jobs.enqueue(kind, branch, null, benchmark, db, 99);
    jobs.markRunning(jobId);
    jobs.finish(jobId, status);
    try (var c = this.db.dataSource().getConnection();
        var ps = c.prepareStatement("update job set finished_at=? where id=?")) {
      ps.setTimestamp(1, java.sql.Timestamp.from(finishedAt));
      ps.setLong(2, jobId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return jobId;
  }

  private void storeRun(JobKind kind, String branch, String benchmark, String db, Instant started) {
    long jobId = finishedJob(kind, branch, benchmark, db, JobStatus.FAILED, started.plusSeconds(60));
    results.save(
        new RunRecord(
            jobId,
            kind,
            branch,
            "a".repeat(40),
            db,
            benchmark,
            "FAILED",
            started,
            started.plusSeconds(60),
            new Fingerprint(Map.of("k", "v"), Map.of()),
            "mvnw",
            Path.of("/l.log")),
        List.of());
  }

  @Test
  void schedulerEnqueuesDueBuildsOnce() {
    scheduler.ensureDueJobs(NOW);
    scheduler.ensureDueJobs(NOW);

    assertThat(jobs.queued())
        .extracting(Job::kind, Job::branch, Job::commitSha, Job::benchmark, Job::db, Job::priority)
        .containsExactlyInAnyOrder(
            tuple(JobKind.BUILD, "main", null, null, null, Job.PRIORITY_BUILD),
            tuple(JobKind.BUILD, "1.0", null, null, null, Job.PRIORITY_BUILD),
            tuple(JobKind.BENCHMARK, "main", null, "task-auth", "h2", Job.PRIORITY_BENCHMARK),
            tuple(JobKind.BENCHMARK, "main", null, "task-auth", "postgres", Job.PRIORITY_BENCHMARK));
  }

  @Test
  void runningJobCountsAsOpen() {
    scheduler.ensureDueJobs(NOW);
    jobs.queued().forEach(j -> jobs.markRunning(j.id()));

    scheduler.ensureDueJobs(NOW);

    assertThat(jobs.queued()).isEmpty();
  }

  @Test
  void runStartedWithinTwentyHoursSuppressesTheSeriesWhateverItsStatus() {
    storeRun(JobKind.BUILD, "main", null, null, NOW.minus(Duration.ofHours(19)));
    storeRun(JobKind.BUILD, "1.0", null, null, NOW.minus(Duration.ofHours(21)));
    storeRun(JobKind.BENCHMARK, "main", "task-auth", "h2", NOW.minus(Duration.ofHours(1)));
    // a build run of main must not count for the benchmark series of main, and vice versa
    storeRun(JobKind.BENCHMARK, "1.0", "task-auth", "postgres", NOW.minus(Duration.ofHours(1)));

    scheduler.ensureDueJobs(NOW);

    assertThat(jobs.queued())
        .extracting(Job::kind, Job::branch, Job::db)
        .containsExactlyInAnyOrder(
            tuple(JobKind.BUILD, "1.0", null), tuple(JobKind.BENCHMARK, "main", "postgres"));
  }

  @Test
  void notApplicableDoesNotReenqueueWithin20h() {
    finishedJob(JobKind.BUILD, "main", null, null, JobStatus.NOT_APPLICABLE, NOW.minus(Duration.ofHours(1)));
    finishedJob(JobKind.BUILD, "1.0", null, null, JobStatus.NOT_APPLICABLE, NOW.minus(Duration.ofHours(21)));
    // given up after infrastructure failures: FAILED without a run
    finishedJob(JobKind.BENCHMARK, "main", "task-auth", "h2", JobStatus.FAILED, NOW.minus(Duration.ofHours(19)));

    scheduler.ensureDueJobs(NOW);

    assertThat(jobs.queued())
        .extracting(Job::kind, Job::branch, Job::db)
        .containsExactlyInAnyOrder(
            tuple(JobKind.BUILD, "1.0", null), tuple(JobKind.BENCHMARK, "main", "postgres"));
  }
}
