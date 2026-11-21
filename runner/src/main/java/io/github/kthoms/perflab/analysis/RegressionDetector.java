package io.github.kthoms.perflab.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * Flags a series (kpi, dims without repetition/iteration, branch, db, segment) when this run and
 * the previous run both exceed {@code median + max(3 * MAD, 3% of median, absolute floor of the
 * kpi)} of their own baseline:
 * the 10 successful runs of the same series and segment before them. A single outlier is never
 * flagged, and fewer than 5 baseline runs give no verdict. Because the previous run needs its own
 * 5-run baseline too, a series needs at least 6 earlier runs before the first verdict is possible.
 * The verdict is returned on every analysis, but a regression row is stored only once per step
 * change: not when an earlier run since the last good one already has one.
 */
public class RegressionDetector {

  /** A stored regression verdict. {@code lastGoodSha} and {@code compareUrl} are null if every baseline run exceeds the threshold. */
  public record Regression(
      long runId,
      String seriesKey,
      String kpi,
      Map<String, String> dims,
      double value,
      double baselineMedian,
      String lastGoodSha,
      String compareUrl) {}

  static final List<String> KPIS =
      List.of(
          "test.module.duration",
          "build.total.wall",
          "benchmark.median.duration",
          "benchmark.operation.median.duration");
  static final List<String> IGNORED_DIMS = List.of("repetition", "iteration");
  static final int BASELINE_SIZE = 10;
  static final int MIN_BASELINE = 5;
  static final double MAD_FACTOR = 3.0;
  static final double MIN_RELATIVE_MARGIN = 0.03;
  /**
   * Smallest margin per KPI, in the KPI's unit, so tiny or quantized series (Surefire times in 0.1 s
   * steps, wall time in whole seconds, sub-millisecond benchmark medians) do not raise false alarms:
   * test.module.duration 1 s, build.total.wall 30 s, benchmark.median.duration 1 ms,
   * benchmark.operation.median.duration 0.5 ms. The Grafana noise bands use the same floors.
   */
  static final Map<String, Double> ABSOLUTE_FLOOR =
      Map.of(
          "test.module.duration", 1.0,
          "build.total.wall", 30.0,
          "benchmark.median.duration", 1.0,
          "benchmark.operation.median.duration", 0.5);

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, String>> DIMS_TYPE = new TypeReference<>() {};

  private final DataSource ds;

  public RegressionDetector(Database db) {
    this.ds = db.dataSource();
  }

  /** Analyses a stored run; returns the regressions flagged for it (empty for a FAILED run). Idempotent. */
  public List<Regression> analyze(long runId) {
    try (Connection c = ds.getConnection()) {
      Run run = loadRun(c, runId);
      if (run == null || !"SUCCESS".equals(run.status)) {
        return List.of();
      }
      List<Regression> flagged = new ArrayList<>();
      for (Map.Entry<Series, List<Double>> e : currentSeries(c, runId).entrySet()) {
        Series series = e.getKey();
        List<RunValue> history = history(c, run, series);
        Verdict v = evaluate(run, series, median(e.getValue()), history);
        if (v != null) {
          // a sustained step change is stored once: not again if a run since the last good one has a row
          if (!flaggedSince(c, v.stepRunIds, v.regression.seriesKey)) {
            store(c, v.regression);
          }
          flagged.add(v.regression);
        }
      }
      return flagged;
    } catch (SQLException | JsonProcessingException e) {
      throw new IllegalStateException("regression analysis of run " + runId + " failed", e);
    }
  }

  /** history is newest first: [0] is the previous run, [0..9] this run's baseline, [1..10] the previous run's. */
  private static Verdict evaluate(Run run, Series series, double value, List<RunValue> history) {
    if (history.isEmpty()) {
      return null;
    }
    List<RunValue> baseline = history.subList(0, Math.min(BASELINE_SIZE, history.size()));
    if (baseline.size() < MIN_BASELINE) {
      return null;
    }
    double median = median(values(baseline));
    double threshold = threshold(series.kpi, values(baseline));
    if (value <= threshold) {
      return null;
    }
    List<RunValue> previousBaseline =
        history.subList(1, Math.min(1 + BASELINE_SIZE, history.size()));
    if (previousBaseline.size() < MIN_BASELINE
        || history.get(0).value <= threshold(series.kpi, values(previousBaseline))) {
      return null;
    }
    String lastGood = null;
    List<Long> stepRunIds = new ArrayList<>();
    for (RunValue b : baseline) {
      if (b.value <= threshold) {
        lastGood = b.commitSha;
        break;
      }
      stepRunIds.add(b.runId);
    }
    String url =
        lastGood == null
            ? null
            : "https://github.com/operaton/operaton/compare/" + lastGood + "..." + run.commitSha;
    return new Verdict(
        new Regression(
            run.id, series.key(run), series.kpi, series.dims, value, median, lastGood, url),
        stepRunIds);
  }

  /** stepRunIds: earlier runs of the series newer than the last one under the threshold. */
  private record Verdict(Regression regression, List<Long> stepRunIds) {}

  private static double threshold(String kpi, List<Double> baseline) {
    double median = median(baseline);
    List<Double> deviations = baseline.stream().map(x -> Math.abs(x - median)).toList();
    double mad = median(deviations);
    double margin = Math.max(MAD_FACTOR * mad, MIN_RELATIVE_MARGIN * median);
    return median + Math.max(margin, ABSOLUTE_FLOOR.getOrDefault(kpi, 0.0));
  }

  static double median(List<Double> values) {
    double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    int n = sorted.length;
    return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
  }

  private static List<Double> values(List<RunValue> runs) {
    return runs.stream().map(r -> r.value).toList();
  }

  private record Run(long id, String status, String kind, String branch, String db, String segment, String commitSha) {}

  private record Series(String kpi, Map<String, String> dims) {
    String key(Run run) {
      StringBuilder d = new StringBuilder();
      new TreeMap<>(dims).forEach((k, v) -> d.append(d.isEmpty() ? "" : ",").append(k).append('=').append(v));
      return String.join(
          "|", kpi, run.branch, run.db == null ? "" : run.db, run.segment, d.toString());
    }
  }

  private record RunValue(long runId, String commitSha, double value) {}

  private static Run loadRun(Connection c, long runId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "select id, status, kind, branch, db, segment, commit_sha from run where id=?")) {
      ps.setLong(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? new Run(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7))
            : null;
      }
    }
  }

  /** This run's analysed series with all their values (one per repetition, if repeated). */
  private static Map<Series, List<Double>> currentSeries(Connection c, long runId)
      throws SQLException, JsonProcessingException {
    Map<Series, List<Double>> out = new LinkedHashMap<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "select kpi, value, coalesce(dims, '{}'::jsonb)::text from measurement"
                + " where run_id=? and kpi = any(?) order by id")) {
      ps.setLong(1, runId);
      ps.setArray(2, c.createArrayOf("text", KPIS.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String, String> dims = new TreeMap<>(JSON.readValue(rs.getString(3), DIMS_TYPE));
          IGNORED_DIMS.forEach(dims::remove);
          out.computeIfAbsent(new Series(rs.getString(1), Map.copyOf(dims)), k -> new ArrayList<>())
              .add(rs.getDouble(2));
        }
      }
    }
    return out;
  }

  /** Per earlier successful run of the series (median of its values), newest first, at most 11 runs. */
  private static List<RunValue> history(Connection c, Run run, Series series)
      throws SQLException, JsonProcessingException {
    String sql =
        "select r.id, r.commit_sha, array_agg(m.value) from run r join measurement m on m.run_id=r.id"
            + " join run cur on cur.id=?"
            + " where r.status='SUCCESS' and r.kind=cur.kind and r.branch=cur.branch"
            + " and r.db is not distinct from cur.db and r.segment=cur.segment"
            + " and (r.started_at, r.id) < (cur.started_at, cur.id)"
            + " and m.kpi=? and (coalesce(m.dims,'{}'::jsonb) - 'repetition' - 'iteration') = ?::jsonb"
            + " group by r.id, r.commit_sha, r.started_at order by r.started_at desc, r.id desc limit ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, run.id);
      ps.setString(2, series.kpi);
      ps.setString(3, JSON.writeValueAsString(series.dims));
      ps.setInt(4, BASELINE_SIZE + 1);
      try (ResultSet rs = ps.executeQuery()) {
        List<RunValue> out = new ArrayList<>();
        while (rs.next()) {
          Double[] vs = (Double[]) rs.getArray(3).getArray();
          out.add(new RunValue(rs.getLong(1), rs.getString(2), median(Arrays.asList(vs))));
        }
        return out;
      }
    }
  }

  private static boolean flaggedSince(Connection c, List<Long> runIds, String seriesKey)
      throws SQLException {
    if (runIds.isEmpty()) {
      return false;
    }
    try (PreparedStatement ps =
        c.prepareStatement("select 1 from regression where series_key=? and run_id = any(?)")) {
      ps.setString(1, seriesKey);
      ps.setArray(2, c.createArrayOf("bigint", runIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void store(Connection c, Regression r) throws SQLException, JsonProcessingException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "insert into regression(run_id, series_key, kpi, dims, value, baseline_median,"
                + " last_good_sha, compare_url)"
                + " select ?,?,?,?::jsonb,?,?,?,? where not exists"
                + " (select 1 from regression where run_id=? and series_key=?)")) {
      ps.setLong(1, r.runId);
      ps.setString(2, r.seriesKey);
      ps.setString(3, r.kpi);
      ps.setString(4, JSON.writeValueAsString(r.dims));
      ps.setDouble(5, r.value);
      ps.setDouble(6, r.baselineMedian);
      ps.setString(7, r.lastGoodSha);
      ps.setString(8, r.compareUrl);
      ps.setLong(9, r.runId);
      ps.setString(10, r.seriesKey);
      ps.executeUpdate();
    }
  }
}
