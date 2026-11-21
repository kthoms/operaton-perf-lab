package io.github.kthoms.perflab.jobs;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BenchmarkConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import io.github.kthoms.perflab.results.ResultRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Enqueues the daily series: one build per configured branch and one benchmark job per benchmark
 * x db x branch. A series is due only if it has no open job, no run started within the last 20
 * hours, and no job that reached a terminal status within the last 20 hours (so a job that ended
 * without a run, e.g. NOT_APPLICABLE or given up after infrastructure failures, is not retried
 * every tick). Only scheduled jobs and runs count: manual or bisect jobs of an explicit commit
 * (run source 'commit') never suppress the daily series.
 */
public class Scheduler {

  static final Duration MIN_INTERVAL = Duration.ofHours(20);
  private static final System.Logger LOG = System.getLogger(Scheduler.class.getName());

  private final JobRepository jobs;
  private final ResultRepository results;
  private final PerfLabConfig config;

  public Scheduler(JobRepository jobs, ResultRepository results, PerfLabConfig config) {
    this.jobs = jobs;
    this.results = results;
    this.config = config;
  }

  public void ensureDueJobs(Instant now) {
    for (BranchConfig branch : config.branches()) {
      ensure(JobKind.BUILD, branch.name(), null, null, Job.PRIORITY_BUILD, now);
    }
    for (BenchmarkConfig benchmark : orEmpty(config.benchmarks())) {
      for (String db : orEmpty(benchmark.databases())) {
        for (String branch : orEmpty(benchmark.branches())) {
          ensure(JobKind.BENCHMARK, branch, benchmark.id(), db, Job.PRIORITY_BENCHMARK, now);
        }
      }
    }
  }

  private void ensure(
      JobKind kind, String branch, String benchmark, String db, int priority, Instant now) {
    if (jobs.hasOpen(kind, branch, benchmark, db)) {
      return;
    }
    if (isRecent(results.lastRunStart(kind, branch, benchmark, db), now)
        || isRecent(jobs.lastFinished(kind, branch, benchmark, db), now)) {
      return;
    }
    long id = jobs.enqueue(kind, branch, null, benchmark, db, priority);
    String series = benchmark == null ? branch : branch + " " + benchmark + " " + db;
    LOG.log(System.Logger.Level.INFO, "enqueued job {0}: {1} {2}", id, kind, series);
  }

  private static boolean isRecent(Optional<Instant> when, Instant now) {
    return when.isPresent() && !when.get().isBefore(now.minus(MIN_INTERVAL));
  }

  private static <T> List<T> orEmpty(List<T> list) {
    return list == null ? List.of() : list;
  }
}
