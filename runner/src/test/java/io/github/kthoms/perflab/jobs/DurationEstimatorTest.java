package io.github.kthoms.perflab.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.results.ResultRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DurationEstimatorTest {

  private static DurationEstimator with(List<Duration> history) {
    return new DurationEstimator(
        new ResultRepository(null) {
          @Override
          public List<Duration> recentDurations(
              JobKind kind, String branch, String benchmark, String db, int limit) {
            return history;
          }
        });
  }

  private static Job job(JobKind kind) {
    return new Job(1, kind, "main", "sha", null, null, 10, JobStatus.QUEUED, 0);
  }

  @Test
  void medianOfLastFive() {
    var d = with(List.of(m(50), m(10), m(30), m(90), m(20)));
    assertThat(d.expected(job(JobKind.BUILD))).isEqualTo(m(30));
  }

  @Test
  void evenCountUsesMeanOfMiddleTwo() {
    assertThat(with(List.of(m(10), m(40), m(20), m(30))).expected(job(JobKind.BUILD)))
        .isEqualTo(m(25));
  }

  @Test
  void defaultWithoutHistory() {
    assertThat(with(List.of()).expected(job(JobKind.BUILD))).isEqualTo(m(90));
    assertThat(with(List.of()).expected(job(JobKind.BENCHMARK))).isEqualTo(m(20));
  }

  private static Duration m(int minutes) {
    return Duration.ofMinutes(minutes);
  }
}
