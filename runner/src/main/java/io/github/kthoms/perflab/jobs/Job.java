package io.github.kthoms.perflab.jobs;

/** A queued unit of work. Lower priority values run first. */
public record Job(
    long id,
    JobKind kind,
    String branch,
    String commitSha,
    String benchmark,
    String db,
    int priority,
    JobStatus status,
    int attempts) {

  public static final int PRIORITY_MANUAL = 0;
  public static final int PRIORITY_BUILD = 10;
  public static final int PRIORITY_BENCHMARK = 20;
}
