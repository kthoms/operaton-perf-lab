package io.github.kthoms.perflab.results;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    return store(run, measurements, null);
  }

  /**
   * Stores the run and its measurements and sets the job's terminal status in one transaction, so
   * a stored run never leaves its job requeueable. Nothing is stored if any part fails.
   */
  public long saveAndFinish(RunRecord run, List<Measurement> measurements, JobStatus jobStatus) {
    return store(run, measurements, jobStatus);
  }

  private long store(RunRecord run, List<Measurement> measurements, JobStatus jobStatus) {
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
        if (jobStatus != null) {
          finishJob(c, run.jobId(), jobStatus);
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

  private static void finishJob(Connection c, long jobId, JobStatus status) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("update job set status=?, finished_at=now() where id=?")) {
      ps.setString(1, status.name());
      ps.setLong(2, jobId);
      if (ps.executeUpdate() != 1) {
        throw new SQLException("job " + jobId + " not found");
      }
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

  /** Start of the most recent run of one series, any status; empty if it never ran. */
  public Optional<Instant> lastRunStart(JobKind kind, String branch, String benchmark, String db) {
    String sql =
        "select max(started_at) from run where kind=? and branch=?"
            + " and benchmark is not distinct from ? and db is not distinct from ?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, kind.name());
      ps.setString(2, branch);
      setNullable(ps, 3, benchmark);
      setNullable(ps, 4, db);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Timestamp ts = rs.getTimestamp(1);
        return ts == null ? Optional.empty() : Optional.of(ts.toInstant());
      }
    } catch (SQLException e) {
      throw new ResultRepositoryException("lastRunStart " + kind + " " + branch, e);
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
