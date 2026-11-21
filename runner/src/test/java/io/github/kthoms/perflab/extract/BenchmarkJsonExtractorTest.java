package io.github.kthoms.perflab.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.results.Measurement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkJsonExtractorTest {

  @TempDir Path dir;

  @Test
  void extractsIterationsAndMedian() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"task-authorization\",\"database\":\"h2\","
                + "\"params\":{\"tasks\":20,\"groups\":2,\"warmup\":1,\"iterations\":4},"
                + "\"iterationsMs\":[4.0,1.5,3.0,2.0]}");

    List<Measurement> measurements = BenchmarkJsonExtractor.extract(json);

    assertThat(measurements)
        .containsExactly(
            new Measurement("benchmark.iteration.duration", 4.0, "ms",
                Map.of("benchmark", "task-authorization", "iteration", "1")),
            new Measurement("benchmark.iteration.duration", 1.5, "ms",
                Map.of("benchmark", "task-authorization", "iteration", "2")),
            new Measurement("benchmark.iteration.duration", 3.0, "ms",
                Map.of("benchmark", "task-authorization", "iteration", "3")),
            new Measurement("benchmark.iteration.duration", 2.0, "ms",
                Map.of("benchmark", "task-authorization", "iteration", "4")),
            new Measurement("benchmark.median.duration", 2.5, "ms",
                Map.of("benchmark", "task-authorization")));
  }

  @Test
  void extractsOperationMedians() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"task-authorization\",\"database\":\"postgresql\",\"params\":{},"
                + "\"iterationsMs\":[3.0,6.0,9.0],"
                + "\"operationsMs\":{\"listPage\":[1.0,2.0,3.0],\"count\":[2.0,4.0,6.0]}}");

    List<Measurement> measurements = BenchmarkJsonExtractor.extract(json, "task-authorization", "postgresql");

    assertThat(measurements)
        .filteredOn(m -> m.kpi().equals("benchmark.operation.median.duration"))
        .containsExactly(
            new Measurement("benchmark.operation.median.duration", 2.0, "ms",
                Map.of("benchmark", "task-authorization", "operation", "listPage")),
            new Measurement("benchmark.operation.median.duration", 4.0, "ms",
                Map.of("benchmark", "task-authorization", "operation", "count")));
    assertThat(measurements).filteredOn(m -> m.kpi().equals("benchmark.iteration.duration")).hasSize(3);
  }

  @Test
  void resultOfAnotherBenchmarkOrDatabaseIsRejected() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"task-authorization\",\"database\":\"h2\",\"params\":{},\"iterationsMs\":[1]}");

    assertThatThrownBy(() -> BenchmarkJsonExtractor.extract(json, "task-authorization", "postgresql"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("h2");
    assertThatThrownBy(() -> BenchmarkJsonExtractor.extract(json, "other", "h2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("task-authorization");
  }

  @Test
  void medianOfOddCountIsTheMiddleValue() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"b\",\"database\":\"h2\",\"params\":{},\"iterationsMs\":[9,1,5]}");

    assertThat(BenchmarkJsonExtractor.extract(json))
        .filteredOn(m -> m.kpi().equals("benchmark.median.duration"))
        .singleElement()
        .extracting(Measurement::value)
        .isEqualTo(5.0);
  }

  @Test
  void resultWithoutIterationsIsRejected() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"b\",\"database\":\"h2\",\"params\":{},\"iterationsMs\":[]}");

    assertThatThrownBy(() -> BenchmarkJsonExtractor.extract(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("iterationsMs");
  }
}
