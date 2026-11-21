package io.github.kthoms.perflab.jobs;

import io.github.kthoms.perflab.results.ResultRepository;
import java.time.Duration;
import java.util.List;

/** Expected runtime of a job: median of its last 5 successful runs, else a per-kind default. */
public class DurationEstimator {

  static final Duration DEFAULT_BUILD = Duration.ofMinutes(90);
  static final Duration DEFAULT_BENCHMARK = Duration.ofMinutes(20);
  private static final int SAMPLE = 5;

  private final ResultRepository results;

  public DurationEstimator(ResultRepository results) {
    this.results = results;
  }

  public Duration expected(Job job) {
    List<Duration> recent =
        results.recentDurations(job.kind(), job.branch(), job.benchmark(), job.db(), SAMPLE);
    if (recent.isEmpty()) {
      return job.kind() == JobKind.BUILD ? DEFAULT_BUILD : DEFAULT_BENCHMARK;
    }
    List<Duration> sorted = recent.stream().sorted().toList();
    int n = sorted.size();
    if (n % 2 == 1) {
      return sorted.get(n / 2);
    }
    return sorted.get(n / 2 - 1).plus(sorted.get(n / 2).minus(sorted.get(n / 2 - 1)).dividedBy(2));
  }
}
