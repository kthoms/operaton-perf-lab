package io.github.kthoms.perflab.results;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.jobs.JobKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Plain-JDBC persistence for runs and their measurements. */
public class ResultRepository {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final DataSource ds;

  public ResultRepository(DataSource ds) {
    this.ds = ds;
  }

  /** Stores the run and all measurements in one transaction; returns the run id. */
  public long save(RunRecord run, List<Measurement> measurements) {
    String runSql =
        "insert into run(job_id, kind, branch, commit_sha, db, benchmark, status, started_at,"
            + " finished_at, fingerprint, segment, command_line, log_path)"
            + " values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)";
    String measurementSql =
        "insert into measurement(run_id, kpi, value, unit, dims) values (?,?,?,?,?::jsonb)";
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      try {
        long runId;
        try (PreparedStatement ps = c.prepareStatement(runSql, Statement.RETURN_GENERATED_KEYS)) {
          ps.setLong(1, run.jobId());
          ps.setString(2, run.kind().name());
          ps.setString(3, run.branch());
          ps.setString(4, run.commitSha());
          setNullable(ps, 5, run.db());
          setNullable(ps, 6, run.benchmark());
          ps.setString(7, run.status());
          ps.setTimestamp(8, Timestamp.from(run.startedAt()));
          if (run.finishedAt() == null) {
            ps.setNull(9, Types.TIMESTAMP);
          } else {
            ps.setTimestamp(9, Timestamp.from(run.finishedAt()));
          }
          ps.setString(10, fingerprintJson(run.fingerprint()));
          ps.setString(11, run.fingerprint().segment());
          setNullable(ps, 12, run.commandLine());
          setNullable(ps, 13, run.logPath() == null ? null : run.logPath().toString());
          ps.executeUpdate();
          try (ResultSet rs = ps.getGeneratedKeys()) {
            rs.next();
            runId = rs.getLong("id");
          }
        }
        try (PreparedStatement ps = c.prepareStatement(measurementSql)) {
          for (Measurement m : measurements) {
            ps.setLong(1, runId);
            ps.setString(2, m.kpi());
            ps.setDouble(3, m.value());
            setNullable(ps, 4, m.unit());
            ps.setString(5, JSON.writeValueAsString(m.dims() == null ? Map.of() : m.dims()));
            ps.addBatch();
          }
          ps.executeBatch();
        }
        c.commit();
        return runId;
      } catch (SQLException | JsonProcessingException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException | JsonProcessingException e) {
      throw new ResultRepositoryException("save run " + run.kind() + " " + run.branch(), e);
    }
  }

  /** Durations of successful runs of one series, newest first. */
  public List<Duration> recentDurations(
      JobKind kind, String branch, String benchmark, String db, int limit) {
    String sql =
        "select extract(epoch from (finished_at - started_at)) * 1000 as d_ms from run"
            + " where kind=? and status='SUCCESS'"
            + " and finished_at is not null"
            + " and branch=? and benchmark is not distinct from ? and db is not distinct from ?"
            + " order by started_at desc, id desc limit ?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, kind.name());
      ps.setString(2, branch);
      setNullable(ps, 3, benchmark);
      setNullable(ps, 4, db);
      ps.setInt(5, limit);
      try (ResultSet rs = ps.executeQuery()) {
        List<Duration> out = new ArrayList<>();
        while (rs.next()) {
          out.add(Duration.ofMillis(Math.round(rs.getDouble("d_ms"))));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new ResultRepositoryException("recentDurations " + kind + " " + branch, e);
    }
  }

  private static String fingerprintJson(Fingerprint f) throws JsonProcessingException {
    return JSON.writeValueAsString(Map.of("stable", f.stable(), "volatile", f.volatileInfo()));
  }

  private static void setNullable(PreparedStatement ps, int index, String value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  /** Unchecked wrapper for failures while storing or reading results. */
  public static class ResultRepositoryException extends RuntimeException {
    public ResultRepositoryException(String context, Exception cause) {
      super("Result operation failed: " + context, cause);
    }
  }
}
