package io.github.kthoms.perflab.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RegressionDetectorIT {

  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
  static final String KPI = "test.module.duration";
  static final double[] NOISY = {100, 102, 98, 101, 99, 100, 103, 97, 100, 101};

  Database db;
  RegressionDetector detector;
  int counter;

  @BeforeEach
  void setUp() throws Exception {
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job restart identity cascade");
    }
    detector = new RegressionDetector(db);
    counter = 0;
  }

  /** Inserts a run (time increases with each call) with one KPI value per entry of values. */
  long run(String segment, String status, String kpi, String dims, double... values) throws SQLException {
    int n = ++counter;
    try (Connection c = db.dataSource().getConnection()) {
      long job;
      try (var ps =
          c.prepareStatement(
              "insert into job(kind, priority, status) values ('BUILD', 1, 'DONE') returning id");
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        job = rs.getLong(1);
      }
      long run;
      try (PreparedStatement ps =
          c.prepareStatement(
              "insert into run(job_id, kind, branch, commit_sha, db, status, started_at, segment)"
                  + " values (?, 'BUILD', 'main', ?, 'h2', ?, ?, ?) returning id")) {
        ps.setLong(1, job);
        ps.setString(2, "sha" + n);
        ps.setString(3, status);
        ps.setTimestamp(4, java.sql.Timestamp.from(T0.plusSeconds(n * 3600L)));
        ps.setString(5, segment);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          run = rs.getLong(1);
        }
      }
      for (double v : values) {
        try (PreparedStatement ps =
            c.prepareStatement(
                "insert into measurement(run_id, kpi, value, dims) values (?,?,?,?::jsonb)")) {
          ps.setLong(1, run);
          ps.setString(2, kpi);
          ps.setDouble(3, v);
          ps.setString(4, dims);
          ps.executeUpdate();
        }
      }
      return run;
    }
  }

  void baseline(String segment, double... values) throws SQLException {
    for (double v : values) {
      run(segment, "SUCCESS", KPI, "{\"module\":\"engine\"}", v);
    }
  }

  long ok(double value) throws SQLException {
    return run("s", "SUCCESS", KPI, "{\"module\":\"engine\"}", value);
  }

  int storedRegressions() throws SQLException {
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement();
        var rs = st.executeQuery("select count(*) from regression")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void singleOutlierNotFlagged() throws Exception {
    baseline("s", NOISY);
    long outlier = ok(200);
    assertThat(detector.analyze(outlier)).isEmpty();
    long back = ok(100);
    assertThat(detector.analyze(back)).isEmpty();
    assertThat(storedRegressions()).isZero();
  }

  @Test
  void twoConsecutiveOutliersFlagged() throws Exception {
    baseline("s", NOISY); // sha1..sha10
    ok(200); // sha11, previous run
    long second = ok(210); // sha12

    List<RegressionDetector.Regression> found = detector.analyze(second);

    assertThat(found).hasSize(1);
    var r = found.get(0);
    assertThat(r.kpi()).isEqualTo(KPI);
    assertThat(r.value()).isEqualTo(210);
    // baseline = sha2..sha11 including the previous outlier
    assertThat(r.baselineMedian()).isEqualTo(100.5);
    assertThat(r.seriesKey()).isEqualTo("test.module.duration|main|h2|s|module=engine");
    // sha11 (200) is above the threshold, so the newest run under it is sha10
    assertThat(r.lastGoodSha()).isEqualTo("sha10");
    assertThat(r.compareUrl())
        .isEqualTo("https://github.com/operaton/operaton/compare/sha10...sha12");
    assertThat(storedRegressions()).isEqualTo(1);
  }

  @Test
  void analyzingTheSameRunTwiceStoresOneRow() throws Exception {
    baseline("s", NOISY);
    ok(200);
    long second = ok(210);
    detector.analyze(second);
    detector.analyze(second);
    assertThat(storedRegressions()).isEqualTo(1);
  }

  @Test
  void noVerdictWithFewerThanFiveRuns() throws Exception {
    baseline("s", 100, 100, 100, 100);
    ok(200);
    long second = ok(210);
    assertThat(detector.analyze(second)).isEmpty();
    assertThat(storedRegressions()).isZero();
  }

  @Test
  void otherSegmentIgnored() throws Exception {
    baseline("other", NOISY); // would be a full baseline, but in another segment
    baseline("s", 100, 100, 100);
    ok(200);
    long second = ok(210);
    assertThat(detector.analyze(second)).isEmpty();
  }

  @Test
  void smallMadUsesThreePercentFloor() throws Exception {
    baseline("s", 100, 100, 100, 100, 100, 100, 100, 100, 100, 100); // MAD 0
    ok(102.9); // below 103
    long within = ok(102.9);
    assertThat(detector.analyze(within)).isEmpty();
    ok(103.5); // above the 3 % floor
    long above = ok(103.5);
    assertThat(detector.analyze(above)).hasSize(1);
  }

  @Test
  void failedRunYieldsNothing() throws Exception {
    baseline("s", NOISY);
    ok(200);
    long failed = run("s", "FAILED", KPI, "{\"module\":\"engine\"}", 300);
    assertThat(detector.analyze(failed)).isEmpty();
  }

  @Test
  void repetitionsAreCollapsedToTheirMedianAndDimsKeepSeriesApart() throws Exception {
    for (double v : NOISY) {
      run("s", "SUCCESS", "benchmark.median.duration", "{\"benchmark\":\"a\",\"repetition\":\"1\"}", v);
    }
    // another benchmark with slow values must not pollute benchmark a's baseline
    run("s", "SUCCESS", "benchmark.median.duration", "{\"benchmark\":\"b\",\"repetition\":\"1\"}", 500);
    long prev = run("s", "SUCCESS", "benchmark.median.duration", "{\"benchmark\":\"a\",\"repetition\":\"1\"}", 100, 300, 300);
    long cur = run("s", "SUCCESS", "benchmark.median.duration", "{\"benchmark\":\"a\",\"repetition\":\"2\"}", 400, 250, 260);

    assertThat(detector.analyze(prev)).isEmpty(); // single outlier so far (median 300)
    var found = detector.analyze(cur);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).value()).isEqualTo(260);
    assertThat(found.get(0).seriesKey()).endsWith("|benchmark=a");
  }
}
