package io.github.kthoms.perflab.results;

import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.jobs.Job;
import io.github.kthoms.perflab.jobs.JobKind;
import java.nio.file.Path;
import java.time.Instant;

/**
 * One finished build or benchmark run. {@code status} is "SUCCESS" or "FAILED". {@code source} is
 * {@value #SOURCE_BRANCH} for a scheduled series run (branch tip) or {@value #SOURCE_COMMIT} for a
 * job of an explicit commit (manual or bisect); commit runs never enter trends or baselines.
 */
public record RunRecord(
    long jobId,
    JobKind kind,
    String branch,
    String commitSha,
    String db,
    String benchmark,
    String status,
    Instant startedAt,
    Instant finishedAt,
    Fingerprint fingerprint,
    String commandLine,
    Path logPath,
    String source) {

  public static final String SOURCE_BRANCH = "branch";
  public static final String SOURCE_COMMIT = "commit";

  public RunRecord {
    if (source == null) {
      source = SOURCE_BRANCH;
    }
    if (!SOURCE_BRANCH.equals(source) && !SOURCE_COMMIT.equals(source)) {
      throw new IllegalArgumentException("unknown run source '" + source + "'");
    }
  }

  /** A scheduled branch run. */
  public RunRecord(
      long jobId,
      JobKind kind,
      String branch,
      String commitSha,
      String db,
      String benchmark,
      String status,
      Instant startedAt,
      Instant finishedAt,
      Fingerprint fingerprint,
      String commandLine,
      Path logPath) {
    this(jobId, kind, branch, commitSha, db, benchmark, status, startedAt, finishedAt, fingerprint,
        commandLine, logPath, SOURCE_BRANCH);
  }

  /** {@value #SOURCE_COMMIT} for a job of an explicit commit, else {@value #SOURCE_BRANCH}. */
  public static String sourceOf(Job job) {
    return job.commitSha() != null ? SOURCE_COMMIT : SOURCE_BRANCH;
  }
}
