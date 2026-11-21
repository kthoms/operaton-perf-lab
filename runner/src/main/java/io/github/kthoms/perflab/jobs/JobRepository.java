package io.github.kthoms.perflab.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** Plain-JDBC persistence for the job queue. */
public class JobRepository {

  private static final String COLUMNS =
      "id, kind, branch, commit_sha, benchmark, db, priority, status, attempts";

  private final DataSource ds;

  public JobRepository(DataSource ds) {
    this.ds = ds;
  }

  public long enqueue(
      JobKind kind, String branch, String commitSha, String benchmark, String db, int priority) {
    String sql =
        "insert into job(kind, branch, commit_sha, benchmark, db, priority, status)"
            + " values (?,?,?,?,?,?,'QUEUED')";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, kind.name());
      setNullable(ps, 2, branch);
      setNullable(ps, 3, commitSha);
      setNullable(ps, 4, benchmark);
      setNullable(ps, 5, db);
      ps.setInt(6, priority);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        return rs.getLong("id");
      }
    } catch (SQLException e) {
      throw new JobRepositoryException("enqueue " + kind + " " + branch, e);
    }
  }

  public List<Job> queued() {
    String sql =
        "select " + COLUMNS + " from job where status='QUEUED' order by priority, created_at, id";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      List<Job> jobs = new ArrayList<>();
      while (rs.next()) {
        jobs.add(
            new Job(
                rs.getLong("id"),
                JobKind.valueOf(rs.getString("kind")),
                rs.getString("branch"),
                rs.getString("commit_sha"),
                rs.getString("benchmark"),
                rs.getString("db"),
                rs.getInt("priority"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts")));
      }
      return jobs;
    } catch (SQLException e) {
      throw new JobRepositoryException("list queued jobs", e);
    }
  }

  /** Atomically moves QUEUED to RUNNING; returns false if the job was not QUEUED. */
  public boolean markRunning(long id) {
    return update(
            "update job set status='RUNNING', started_at=now(), attempts=attempts+1"
                + " where id=? and status='QUEUED'",
            "markRunning " + id,
            id)
        == 1;
  }

  public void finish(long id, JobStatus status) {
    String sql = "update job set status=?, finished_at=now() where id=?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, status.name());
      ps.setLong(2, id);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new JobRepositoryException("finish " + id + " as " + status, e);
    }
  }

  public void requeue(long id) {
    update(
        "update job set status='QUEUED', started_at=null, cancel_requested=false"
            + " where id=? and status='RUNNING'",
        "requeue " + id,
        id);
  }

  /** Recovers jobs left RUNNING by a crashed daemon; returns the number requeued. */
  public int requeueStale() {
    return update(
        "update job set status='QUEUED', started_at=null, cancel_requested=false"
            + " where status='RUNNING'",
        "requeueStale");
  }

  public void requestCancel() {
    update(
        "update job set cancel_requested=true where status='RUNNING'", "requestCancel");
  }

  public boolean cancelRequested(long id) {
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement("select cancel_requested from job where id=?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    } catch (SQLException e) {
      throw new JobRepositoryException("cancelRequested " + id, e);
    }
  }

  public boolean hasOpen(JobKind kind, String branch, String benchmark, String db) {
    String sql =
        "select exists(select 1 from job where kind=? and status in ('QUEUED','RUNNING')"
            + " and branch is not distinct from ?"
            + " and benchmark is not distinct from ?"
            + " and db is not distinct from ?)";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, kind.name());
      setNullable(ps, 2, branch);
      setNullable(ps, 3, benchmark);
      setNullable(ps, 4, db);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    } catch (SQLException e) {
      throw new JobRepositoryException("hasOpen " + kind + " " + branch, e);
    }
  }

  /** When a job of this series last reached a terminal status (DONE, FAILED, NOT_APPLICABLE). */
  public Optional<Instant> lastFinished(JobKind kind, String branch, String benchmark, String db) {
    String sql =
        "select max(finished_at) from job where kind=?"
            + " and status in ('DONE','FAILED','NOT_APPLICABLE')"
            + " and branch is not distinct from ?"
            + " and benchmark is not distinct from ?"
            + " and db is not distinct from ?";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, kind.name());
      setNullable(ps, 2, branch);
      setNullable(ps, 3, benchmark);
      setNullable(ps, 4, db);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Timestamp ts = rs.getTimestamp(1);
        return ts == null ? Optional.empty() : Optional.of(ts.toInstant());
      }
    } catch (SQLException e) {
      throw new JobRepositoryException("lastFinished " + kind + " " + branch, e);
    }
  }

  private int update(String sql, String context, long... ids) {
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < ids.length; i++) {
        ps.setLong(i + 1, ids[i]);
      }
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new JobRepositoryException(context, e);
    }
  }

  private static void setNullable(PreparedStatement ps, int index, String value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  /** Unchecked wrapper for SQL failures in the job queue. */
  public static class JobRepositoryException extends RuntimeException {
    public JobRepositoryException(String context, SQLException cause) {
      super("Job queue operation failed: " + context, cause);
    }
  }
}
