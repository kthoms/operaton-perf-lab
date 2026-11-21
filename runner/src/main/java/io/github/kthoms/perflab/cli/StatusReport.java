package io.github.kthoms.perflab.cli;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.git.OperatonCheckout;
import io.github.kthoms.perflab.idle.IdleGate;
import io.github.kthoms.perflab.jobs.DurationEstimator;
import io.github.kthoms.perflab.jobs.Job;
import io.github.kthoms.perflab.jobs.JobRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/** Read-only overview of the daemon's state; never changes a job. */
class StatusReport {

  private final PerfLabConfig config;
  private final DataSource ds;
  private final JobRepository jobs;
  private final DurationEstimator estimator;
  private final IdleGate gate;
  private final OperatonCheckout checkout;

  StatusReport(
      PerfLabConfig config,
      DataSource ds,
      JobRepository jobs,
      DurationEstimator estimator,
      IdleGate gate,
      OperatonCheckout checkout) {
    this.config = config;
    this.ds = ds;
    this.jobs = jobs;
    this.estimator = estimator;
    this.gate = gate;
    this.checkout = checkout;
  }

  String render(Instant now) {
    var out = new StringBuilder();
    try {
      running(out);
      List<Job> queued = jobs.queued();
      queue(out, queued);
      lastRuns(out);
      idle(out, queued, now);
    } catch (SQLException e) {
      throw new IllegalStateException("cannot read status: " + e.getMessage(), e);
    }
    releaseBranches(out);
    return out.toString();
  }

  private void running(StringBuilder out) throws SQLException {
    out.append("Running job:\n");
    String sql =
        "select id, kind, branch, commit_sha, benchmark, db, started_at, cancel_requested"
            + " from job where status='RUNNING' order by id";
    boolean any = false;
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        any = true;
        Timestamp started = rs.getTimestamp("started_at");
        out.append("  #").append(rs.getLong("id")).append(' ')
            .append(rs.getString("kind")).append(' ')
            .append(describe(rs.getString("branch"), rs.getString("commit_sha"),
                rs.getString("benchmark"), rs.getString("db")))
            .append(started == null ? "" : ", since " + secs(started.toInstant()))
            .append(rs.getBoolean("cancel_requested") ? " (cancel requested)" : "")
            .append('\n');
      }
    }
    if (!any) {
      out.append("  none\n");
    }
  }

  private static void queue(StringBuilder out, List<Job> queued) {
    out.append("Queue (").append(queued.size()).append("):\n");
    if (queued.isEmpty()) {
      out.append("  empty\n");
    }
    for (Job j : queued) {
      out.append("  #").append(j.id()).append(" prio ").append(j.priority()).append(' ')
          .append(j.kind()).append(' ')
          .append(describe(j.branch(), j.commitSha(), j.benchmark(), j.db()))
          .append(j.attempts() > 0 ? " (attempts " + j.attempts() + ")" : "")
          .append('\n');
    }
  }

  private void lastRuns(StringBuilder out) throws SQLException {
    out.append("Last run per series:\n");
    String sql =
        "select distinct on (kind, branch, benchmark, db) kind, branch, commit_sha, benchmark, db,"
            + " status, started_at from run order by kind, branch, benchmark, db, started_at desc";
    boolean any = false;
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        any = true;
        out.append("  ").append(rs.getString("kind")).append(' ')
            .append(describe(rs.getString("branch"), null, rs.getString("benchmark"), rs.getString("db")))
            .append(": ").append(rs.getString("status")).append(' ')
            .append(abbreviate(rs.getString("commit_sha"))).append(' ')
            .append(secs(rs.getTimestamp("started_at").toInstant()))
            .append('\n');
      }
    }
    if (!any) {
      out.append("  none yet\n");
    }
  }

  private void idle(StringBuilder out, List<Job> queued, Instant now) {
    out.append("Idle decision");
    Duration expected = Duration.ZERO;
    boolean manual = false;
    if (queued.isEmpty()) {
      out.append(" (no queued job, machine state only)");
    } else {
      Job first = queued.get(0);
      expected = estimator.expected(first);
      manual = first.priority() == Job.PRIORITY_MANUAL;
      out.append(" for job #").append(first.id()).append(" (expected ").append(fmt(expected)).append(')');
    }
    out.append(":\n");
    IdleGate.Decision d = gate.mayStart(expected, now, manual);
    out.append("  ").append(d.ok() ? "may start now" : "would not start").append(": ")
        .append(d.reason()).append('\n');
  }

  private void releaseBranches(StringBuilder out) {
    out.append("Upstream release branches not in config:\n");
    if (!checkout.exists()) {
      out.append("  unknown: ").append(config.checkout()).append(" is not cloned yet\n");
      return;
    }
    try {
      List<String> configured = config.branches().stream().map(PerfLabConfig.BranchConfig::name).toList();
      List<String> unknown = checkout.remoteReleaseBranches().stream()
          .filter(b -> !configured.contains(b)).toList();
      if (unknown.isEmpty()) {
        out.append("  none\n");
      }
      unknown.forEach(b -> out.append("  ").append(b).append('\n'));
    } catch (RuntimeException e) {
      out.append("  unavailable: ").append(e.getMessage()).append('\n');
    }
  }

  private static String describe(String branch, String sha, String benchmark, String db) {
    List<String> parts = new ArrayList<>();
    if (branch != null) parts.add(branch);
    if (sha != null) parts.add(abbreviate(sha));
    if (benchmark != null) parts.add(benchmark);
    if (db != null) parts.add(db);
    return parts.stream().filter(Objects::nonNull).collect(Collectors.joining(" "));
  }

  private static String abbreviate(String sha) {
    return sha == null ? "" : sha.substring(0, Math.min(10, sha.length()));
  }

  private static String secs(Instant t) {
    return t.truncatedTo(ChronoUnit.SECONDS).toString();
  }

  private static String fmt(Duration d) {
    return d.toHours() > 0 ? d.toHours() + "h" + d.toMinutesPart() + "m" : d.toMinutes() + "m";
  }
}
