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
  void extractsDerivedMetrics() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"scenario-a\",\"database\":\"h2\",\"params\":{},"
                + "\"iterationsMs\":[100.0],\"operationsMs\":{\"concurrency-10\":[100.0]},"
                + "\"metrics\":{\"throughput.concurrency-10\":{\"value\":5000.5,\"unit\":\"1/s\"},"
                + "\"latencyP95.concurrency-10\":{\"value\":3.25,\"unit\":\"ms\"}}}");

    List<Measurement> measurements = BenchmarkJsonExtractor.extract(json, "scenario-a", "h2");

    assertThat(measurements)
        .filteredOn(m -> m.kpi().equals("benchmark.metric"))
        .containsExactly(
            new Measurement("benchmark.metric", 5000.5, "1/s",
                Map.of("benchmark", "scenario-a", "metric", "throughput.concurrency-10")),
            new Measurement("benchmark.metric", 3.25, "ms",
                Map.of("benchmark", "scenario-a", "metric", "latencyP95.concurrency-10")));
  }

  @Test
  void metricWithoutUnitIsMalformed() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"scenario-a\",\"database\":\"h2\",\"iterationsMs\":[1.0],"
                + "\"metrics\":{\"throughput\":{\"value\":1.0}}}");

    assertThatThrownBy(() -> BenchmarkJsonExtractor.extract(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("throughput");
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

  @Test
  void sizedSeriesIsStoredInTheBenchmarkDimension() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"task-authorization\",\"database\":\"h2\",\"params\":{\"tasks\":1000},"
                + "\"iterationsMs\":[3.0,6.0,9.0],\"operationsMs\":{\"count\":[2.0,4.0,6.0]}}");

    List<Measurement> measurements =
        BenchmarkJsonExtractor.extract(json, "task-authorization", "task-authorization@1000", "h2");

    assertThat(measurements).isNotEmpty();
    assertThat(measurements).allSatisfy(m -> assertThat(m.dims()).containsEntry("benchmark", "task-authorization@1000"));
  }

  @Test
  void sizedSeriesRejectsAResultOfAnotherSize() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"task-authorization\",\"database\":\"h2\",\"params\":{\"tasks\":5000},"
                + "\"iterationsMs\":[3.0],\"operationsMs\":{\"count\":[2.0]}}");

    assertThatThrownBy(
            () -> BenchmarkJsonExtractor.extract(json, "task-authorization", "task-authorization@1000", "h2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tasks=5000")
        .hasMessageContaining("expected 1000");
  }

  @Test
  void sizedSeriesRejectsAResultWithoutTasks() throws Exception {
    Path json =
        Files.writeString(
            dir.resolve("r.json"),
            "{\"benchmark\":\"task-authorization\",\"database\":\"h2\",\"params\":{},"
                + "\"iterationsMs\":[3.0],\"operationsMs\":{\"count\":[2.0]}}");

    assertThatThrownBy(
            () -> BenchmarkJsonExtractor.extract(json, "task-authorization", "task-authorization@1000", "h2"))
        .isInstanceOf(IllegalStateException.class);
  }
}
