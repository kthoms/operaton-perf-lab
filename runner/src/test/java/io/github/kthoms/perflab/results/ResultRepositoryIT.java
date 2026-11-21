package io.github.kthoms.perflab.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.db.Database;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
import io.github.kthoms.perflab.jobs.JobStatus;
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
class ResultRepositoryIT {

  @Container
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  private Database db;
  private ResultRepository repo;
  private JobRepository jobs;

  @BeforeEach
  void setUp() throws SQLException {
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job restart identity cascade");
    }
    jobs = new JobRepository(db.dataSource());
    repo = new ResultRepository(db.dataSource());
  }

  /** A run of a fresh job (at most one run per job). */
  private RunRecord run(String status, long seconds) {
    return run(jobs.enqueue(JobKind.BENCHMARK, "main", "sha", "b1", "h2", 3), status, seconds);
  }

  private RunRecord run(long jobId, String status, long seconds) {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    return new RunRecord(
        jobId,
        JobKind.BENCHMARK,
        "main",
        "sha",
        "h2",
        "b1",
        status,
        start,
        start.plusSeconds(seconds),
        new Fingerprint(Map.of("hw.model", "m"), Map.of("load1.start", "0.1")),
        "mvn test",
        Path.of("/logs/x.log"));
  }

  private long count(String table) throws SQLException {
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement();
        var rs = st.executeQuery("select count(*) from " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void saveStoresRunAndMeasurementsAtomically() throws SQLException {
    long id =
        repo.save(
            run("SUCCESS", 10),
            List.of(
                new Measurement("throughput", 12.5, "ops/s", Map.of("threads", "4")),
                new Measurement("latency", 3.0, null, Map.of())));

    assertThat(id).isPositive();
    assertThat(count("run")).isEqualTo(1);
    assertThat(count("measurement")).isEqualTo(2);
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement();
        var rs =
            st.executeQuery(
                "select r.segment, r.fingerprint->'stable'->>'hw.model' m,"
                    + " (select dims->>'threads' from measurement where run_id=r.id and kpi='throughput') t"
                    + " from run r")) {
      rs.next();
      assertThat(rs.getString("segment")).hasSize(12);
      assertThat(rs.getString("m")).isEqualTo("m");
      assertThat(rs.getString("t")).isEqualTo("4");
    }

    // NaN is storable in double precision, so force a failure with a null kpi (NOT NULL).
    assertThatThrownBy(
            () ->
                repo.save(
                    run("SUCCESS", 10),
                    List.of(
                        new Measurement("ok", 1, null, Map.of()),
                        new Measurement(null, 2, null, Map.of()))))
        .isInstanceOf(ResultRepository.ResultRepositoryException.class);
    assertThat(count("run")).isEqualTo(1);
    assertThat(count("measurement")).isEqualTo(2);
  }

  @Test
  void recentDurationsOnlySuccessful() {
    repo.save(run("SUCCESS", 10), List.of());
    repo.save(run("FAILED", 99), List.of());
    repo.save(run("SUCCESS", 20), List.of());

    assertThat(repo.recentDurations(JobKind.BENCHMARK, "main", "b1", "h2", 10))
        .containsExactly(Duration.ofSeconds(20), Duration.ofSeconds(10));
    assertThat(repo.recentDurations(JobKind.BENCHMARK, "main", "b1", "h2", 1))
        .containsExactly(Duration.ofSeconds(20));
    assertThat(repo.recentDurations(JobKind.BUILD, "main", null, null, 10)).isEmpty();
  }

  private String jobStatus(long jobId) throws SQLException {
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement();
        var rs = st.executeQuery("select status, finished_at from job where id=" + jobId)) {
      rs.next();
      return rs.getString(1) + (rs.getTimestamp(2) == null ? "" : "+finished");
    }
  }

  @Test
  void saveAndFinishStoresRunAndFinishesJobTogether() throws SQLException {
    long jobId = jobs.enqueue(JobKind.BUILD, "main", null, null, null, 10);
    jobs.markRunning(jobId);

    long runId =
        repo.saveAndFinish(
            run(jobId, "SUCCESS", 5), List.of(new Measurement("k", 1, "s", Map.of())), JobStatus.DONE);

    assertThat(runId).isPositive();
    assertThat(count("run")).isEqualTo(1);
    assertThat(count("measurement")).isEqualTo(1);
    assertThat(jobStatus(jobId)).isEqualTo("DONE+finished");
  }

  @Test
  void failingJobUpdateRollsBackTheRun() throws SQLException {
    long jobId = jobs.enqueue(JobKind.BUILD, "main", null, null, null, 10);
    jobs.markRunning(jobId);
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute(
          "create function fail_job_finish() returns trigger language plpgsql as"
              + " $$ begin raise exception 'injected job update failure'; end $$");
      st.execute(
          "create trigger fail_job_finish before update on job"
              + " for each row execute function fail_job_finish()");
    }
    try {
      assertThatThrownBy(
              () ->
                  repo.saveAndFinish(
                      run(jobId, "SUCCESS", 5),
                      List.of(new Measurement("k", 1, "s", Map.of())),
                      JobStatus.DONE))
          .isInstanceOf(ResultRepository.ResultRepositoryException.class);
    } finally {
      try (var c = db.dataSource().getConnection();
          var st = c.createStatement()) {
        st.execute("drop trigger fail_job_finish on job");
        st.execute("drop function fail_job_finish()");
      }
    }
    assertThat(count("run")).isZero();
    assertThat(count("measurement")).isZero();
    assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
  }

  @Test
  void secondRunForTheSameJobIsRejected() throws SQLException {
    long jobId = jobs.enqueue(JobKind.BUILD, "main", null, null, null, 10);
    repo.save(run(jobId, "FAILED", 5), List.of());

    assertThatThrownBy(() -> repo.save(run(jobId, "SUCCESS", 5), List.of()))
        .isInstanceOf(ResultRepository.ResultRepositoryException.class)
        .hasStackTraceContaining("run_job_id_unique");
    assertThat(count("run")).isEqualTo(1);
  }

  @Test
  void sourceIsStoredAndCommitRunsDoNotCountForTheSchedule() throws SQLException {
    RunRecord commitRun = withSource(run("SUCCESS", 5), RunRecord.SOURCE_COMMIT);
    repo.save(commitRun, List.of());
    assertThat(repo.lastRunStart(JobKind.BENCHMARK, "main", "b1", "h2")).isEmpty();
    repo.save(run("FAILED", 5), List.of());
    assertThat(repo.lastRunStart(JobKind.BENCHMARK, "main", "b1", "h2")).isPresent();
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement();
        var rs = st.executeQuery("select source from run order by id")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("commit");
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("branch");
    }
  }

  @Test
  void sourceIsConstrained() throws SQLException {
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      long job = jobs.enqueue(JobKind.BUILD, "main", null, null, null, 1);
      assertThatThrownBy(
              () ->
                  st.execute(
                      "insert into run(job_id, kind, branch, commit_sha, status, started_at, segment, source)"
                          + " values (" + job + ", 'BUILD', 'main', 's', 'SUCCESS', now(), 'x', 'pr')"))
          .hasMessageContaining("source");
    }
  }

  private static RunRecord withSource(RunRecord r, String source) {
    return new RunRecord(
        r.jobId(), r.kind(), r.branch(), r.commitSha(), r.db(), r.benchmark(), r.status(),
        r.startedAt(), r.finishedAt(), r.fingerprint(), r.commandLine(), r.logPath(), source);
  }

  @Test
  void lastRunStartIsLatestStartOfTheSeriesAnyStatus() {
    assertThat(repo.lastRunStart(JobKind.BENCHMARK, "main", "b1", "h2")).isEmpty();
    repo.save(run("FAILED", 5), List.of());
    assertThat(repo.lastRunStart(JobKind.BENCHMARK, "main", "b1", "h2"))
        .contains(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(repo.lastRunStart(JobKind.BENCHMARK, "main", "b1", "pg")).isEmpty();
  }
}
