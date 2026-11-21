package io.github.kthoms.perflab.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.db.Database;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
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
  private long jobId;

  @BeforeEach
  void setUp() throws SQLException {
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job restart identity cascade");
    }
    jobId = new JobRepository(db.dataSource()).enqueue(JobKind.BENCHMARK, "main", "sha", "b1", "h2", 3);
    repo = new ResultRepository(db.dataSource());
  }

  private RunRecord run(String status, long seconds) {
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
}
