package io.github.kthoms.perflab.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.db.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the dashboards' noise-band SQL against PostgreSQL and checks it against the detector. */
@Testcontainers
class DashboardBandIT {

  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
  Database db;
  int counter;

  @BeforeEach
  void setUp() throws Exception {
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job restart identity cascade");
    }
    counter = 0;
  }

  long run(String kind, String segment, String source, String db, String benchmark) throws Exception {
    int n = ++counter;
    try (Connection c = this.db.dataSource().getConnection()) {
      long job;
      try (var ps = c.prepareStatement(
              "insert into job(kind, priority, status) values (?, 1, 'DONE') returning id")) {
        ps.setString(1, kind);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          job = rs.getLong(1);
        }
      }
      try (PreparedStatement ps = c.prepareStatement(
          "insert into run(job_id, kind, branch, commit_sha, db, benchmark, status, started_at, segment, source)"
              + " values (?, ?, 'main', ?, ?, ?, 'SUCCESS', ?, ?, ?) returning id")) {
        ps.setLong(1, job);
        ps.setString(2, kind);
        ps.setString(3, "sha" + n);
        ps.setString(4, db);
        ps.setString(5, benchmark);
        ps.setTimestamp(6, java.sql.Timestamp.from(T0.plusSeconds(n * 3600L)));
        ps.setString(7, segment);
        ps.setString(8, source);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1);
        }
      }
    }
  }

  void measure(long run, String kpi, String dims, double... values) throws Exception {
    try (Connection c = db.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(
            "insert into measurement(run_id, kpi, value, dims) values (?,?,?,?::jsonb)")) {
      for (double v : values) {
        ps.setLong(1, run);
        ps.setString(2, kpi);
        ps.setDouble(3, v);
        ps.setString(4, dims);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  static String panelSql(String dashboard, String title) throws Exception {
    JsonNode d = new ObjectMapper().readTree(Path.of("../grafana/dashboards", dashboard).toFile());
    for (JsonNode p : d.path("panels")) {
      if (p.path("title").asText().equals(title)) {
        for (JsonNode t : p.path("targets")) {
          if (t.path("refId").asText().equals("B")) {
            return t.path("rawSql").asText();
          }
        }
      }
    }
    throw new AssertionError("no band query in " + title);
  }

  /** Interpolates the variables and macro like Grafana would; returns time -> {hi, lo}. */
  Map<Instant, double[]> band(String sql) throws Exception {
    String q = sql.replace("${band:sqlstring}", "'main'")
        .replace("${bandBranch:sqlstring}", "'main'")
        .replace("${bandDb:sqlstring}", "'h2'")
        .replace("${benchmark:sqlstring}", "'b1'")
        .replace("${segment:sqlstring}", "'s'")
        .replace("$__timeFilter(r.started_at)", "true");
    Map<Instant, double[]> out = new LinkedHashMap<>();
    try (Connection c = db.dataSource().getConnection();
        var st = c.createStatement();
        ResultSet rs = st.executeQuery(q)) {
      while (rs.next()) {
        double[] hl = out.computeIfAbsent(rs.getTimestamp(1).toInstant(), k -> new double[2]);
        hl[rs.getString(2).equals("noise band hi") ? 0 : 1] = rs.getDouble(3);
      }
    }
    return out;
  }

  @Test
  void engineBandIsMedianOfPreviousTenRunsPlusMinusDetectorMargin() throws Exception {
    double[] values = {100, 102, 98, 101, 99, 100, 103, 97, 100, 101, 104, 96};
    List<Long> runs = new ArrayList<>();
    for (double v : values) {
      long r = run("BUILD", "s", "branch", null, null);
      measure(r, "test.module.duration", "{\"module\":\"engine\"}", v);
      runs.add(r);
    }
    long other = run("BUILD", "other", "branch", null, null); // other segment: no band, no baseline
    measure(other, "test.module.duration", "{\"module\":\"engine\"}", 500);
    long commit = run("BUILD", "s", "commit", null, null); // bisect run: neither
    measure(commit, "test.module.duration", "{\"module\":\"engine\"}", 500);
    long last = run("BUILD", "s", "branch", null, null);
    measure(last, "test.module.duration", "{\"module\":\"engine\"}", 100);

    Map<Instant, double[]> band = band(panelSql("build-tests.json", "Engine module test time"));

    // runs 1-5 have fewer than 5 earlier runs: no band; then one point per branch run of segment s
    assertThat(band).hasSize(values.length - 5 + 1);
    // the last run's baseline: runs 3..12 (98,101,99,100,103,97,100,101,104,96) -> median 100,
    // MAD 1.5 -> margin max(3 * 1.5, 3% of 100, 1 s) = 4.5
    double[] hl = band.get(T0.plusSeconds(15 * 3600L));
    assertThat(hl[0]).isCloseTo(104.5, within(1e-9));
    assertThat(hl[1]).isCloseTo(95.5, within(1e-9));
  }

  @Test
  void wallBandUsesThirtySecondFloor() throws Exception {
    for (int i = 0; i < 6; i++) {
      measure(run("BUILD", "s", "branch", null, null), "build.total.wall", "{}", 600);
    }
    Map<Instant, double[]> band = band(panelSql("build-tests.json", "Total build wall time"));
    assertThat(band.values()).singleElement().satisfies(hl -> {
      assertThat(hl[0]).isEqualTo(630.0);
      assertThat(hl[1]).isEqualTo(570.0);
    });
  }

  @Test
  void benchmarkBandUsesRunMediansOfRepetitionsAndOneMillisecondFloor() throws Exception {
    for (int i = 0; i < 6; i++) {
      long r = run("BENCHMARK", "s", "branch", "h2", "b1");
      measure(r, "benchmark.median.duration", "{\"benchmark\":\"b1\"}", 10, 20, 11); // median 11
      long pg = run("BENCHMARK", "s", "branch", "postgresql", "b1"); // other db: own series
      measure(pg, "benchmark.median.duration", "{\"benchmark\":\"b1\"}", 50);
    }
    Map<Instant, double[]> band =
        band(panelSql("benchmarks.json", "Median per database and branch ($benchmark)"));
    assertThat(band.values()).singleElement().satisfies(hl -> {
      assertThat(hl[0]).isEqualTo(12.0);
      assertThat(hl[1]).isEqualTo(10.0);
    });
  }

  @Test
  void metricPanelsShowTheMedianOfTheRepetitionsWithTheirUnit() throws Exception {
    long r = run("BENCHMARK", "s", "branch", "h2", "b1");
    try (Connection c = db.dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(
            "insert into measurement(run_id, kpi, value, unit, dims) values (?,'benchmark.metric',?,?,?::jsonb)")) {
      Object[][] rows = {
        {100.0, "1/s", "throughput.concurrency-10", "1"},
        {300.0, "1/s", "throughput.concurrency-10", "2"},
        {200.0, "1/s", "throughput.concurrency-10", "3"},
        {5.0, "ms", "latencyP95.concurrency-10", "1"},
        {7.0, "ms", "latencyP95.concurrency-10", "2"},
      };
      for (Object[] row : rows) {
        ps.setLong(1, r);
        ps.setDouble(2, (Double) row[0]);
        ps.setString(3, (String) row[1]);
        ps.setString(4, "{\"benchmark\":\"b1\",\"metric\":\"" + row[2] + "\",\"repetition\":\"" + row[3] + "\"}");
        ps.addBatch();
      }
      ps.executeBatch();
    }

    assertThat(series(querySql("Throughput per concurrency level ($benchmark)")))
        .containsExactly(Map.entry("main / h2 throughput.concurrency-10 [1/s] (s)", 200.0));
    assertThat(series(querySql("Latency and memory metrics ($benchmark)")))
        .containsExactly(Map.entry("main / h2 latencyP95.concurrency-10 [ms] (s)", 6.0));
  }

  static String querySql(String title) throws Exception {
    JsonNode d = new ObjectMapper().readTree(Path.of("../grafana/dashboards", "benchmarks.json").toFile());
    for (JsonNode p : d.path("panels")) {
      if (p.path("title").asText().equals(title)) {
        return p.path("targets").get(0).path("rawSql").asText();
      }
    }
    throw new AssertionError("no panel " + title);
  }

  /** metric -> value of a time series query, variables interpolated like Grafana would. */
  Map<String, Double> series(String sql) throws Exception {
    String q = sql.replace("${benchmark:sqlstring}", "'b1'")
        .replace("${branch:sqlstring}", "'main'")
        .replace("${db:sqlstring}", "'h2'")
        .replace("${segment:sqlstring}", "'s'")
        .replace("$__timeFilter(r.started_at)", "true");
    Map<String, Double> out = new LinkedHashMap<>();
    try (Connection c = db.dataSource().getConnection();
        var st = c.createStatement();
        ResultSet rs = st.executeQuery(q)) {
      while (rs.next()) {
        out.put(rs.getString(2), rs.getDouble(3));
      }
    }
    return out;
  }
}
