package io.github.kthoms.perflab.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.config.PerfLabConfig.BenchmarkConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkConfigTest {

  @Test
  void withoutTasksThereIsOneSeriesNamedByTheId() {
    var b = new BenchmarkConfig("task-authorization", List.of("h2"), List.of("main"), 5);
    assertThat(b.seriesIds()).containsExactly("task-authorization");
    assertThat(BenchmarkConfig.tasksOf("task-authorization")).isEmpty();
  }

  @Test
  void eachTaskCountIsItsOwnSeriesInConfiguredOrder() {
    var b = new BenchmarkConfig("task-authorization", List.of("h2"), List.of("main"), 5, List.of(1000, 5000, 20000));
    assertThat(b.seriesIds())
        .containsExactly("task-authorization@1000", "task-authorization@5000", "task-authorization@20000");
  }

  @Test
  void seriesNameSplitsIntoIdAndTasks() {
    assertThat(BenchmarkConfig.baseId("task-authorization@1000")).isEqualTo("task-authorization");
    assertThat(BenchmarkConfig.tasksOf("task-authorization@1000")).hasValue(1000);
  }
}
