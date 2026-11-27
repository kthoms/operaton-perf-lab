package io.github.kthoms.perflab.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.bench.ProcessThroughputBenchmark.Variant;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EngineComparisonBenchmarksTest {

  @TempDir Path dir;

  private static String h2() {
    return "jdbc:h2:mem:bench-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
  }

  @ParameterizedTest
  @EnumSource(Variant.class)
  void throughputBenchmarkWritesBatchTimesAndMetrics(Variant variant) throws Exception {
    var options = new ProcessThroughputBenchmark.Options("h2", h2(), "sa", "", List.of(1, 4), 20, 1, 2);
    Path out = dir.resolve(variant.id() + ".json");

    new ProcessThroughputBenchmark(variant).run(options).write(out);

    JsonNode json = new ObjectMapper().readTree(out.toFile());
    assertThat(json.get("benchmark").asText()).isEqualTo(variant.id());
    assertThat(json.get("params").get("instances").asInt()).isEqualTo(20);
    assertThat(json.get("iterationsMs")).hasSize(2);
    assertThat(json.get("operationsMs").fieldNames())
        .toIterable()
        .containsExactly("concurrency-1", "concurrency-4");
    JsonNode metrics = json.get("metrics");
    assertThat(metrics.get("throughput.concurrency-4").get("unit").asText()).isEqualTo("1/s");
    assertThat(metrics.get("throughput.concurrency-4").get("value").asDouble()).isPositive();
    assertThat(metrics.get("latencyP95.concurrency-1").get("unit").asText()).isEqualTo("ms");
    assertThat(metrics.get("latencyP95.concurrency-1").get("value").asDouble())
        .isGreaterThanOrEqualTo(metrics.get("latencyP50.concurrency-1").get("value").asDouble());
  }

  @Test
  void pricingInputsHitTheCatchAllRuleAndSomeOthers() {
    List<Scenarios.PricingInput> inputs = Scenarios.pricingInputs(2000, 42);
    assertThat(Scenarios.rules()).hasSize(100);
    assertThat(inputs).allSatisfy(i -> assertThat(i.expectedFee()).isGreaterThanOrEqualTo(1000));
    assertThat(inputs).anySatisfy(i -> assertThat(i.expectedFee()).isGreaterThan(1000));
    // deterministic: the same seed gives the same requests
    assertThat(Scenarios.pricingInputs(2000, 42)).isEqualTo(inputs);
  }

  @Test
  void startupBenchmarkForksReadyEngines() throws Exception {
    var options = new EngineStartupBenchmark.Options("h2", "jdbc:h2:mem:perflab-bench;DB_CLOSE_DELAY=-1", "sa", "", 0, 2);
    Path out = dir.resolve("startup.json");

    new EngineStartupBenchmark().run(options).write(out);

    JsonNode json = new ObjectMapper().readTree(out.toFile());
    assertThat(json.get("benchmark").asText()).isEqualTo("engine-startup");
    assertThat(json.get("iterationsMs")).hasSize(2);
    assertThat(json.get("operationsMs").fieldNames())
        .toIterable()
        .containsExactly("jvmBoot", "buildEngine", "deploy", "firstInstance");
    json.get("operationsMs").get("buildEngine").forEach(ms -> assertThat(ms.asDouble()).isPositive());
    assertThat(json.get("metrics").get("idleHeapAfterGc").get("value").asDouble()).isPositive();
    assertThat(json.get("metrics").get("idleRss").get("unit").asText()).isEqualTo("MiB");
  }

  @Test
  void percentileIsNearestRank() {
    List<Double> values = List.of(5.0, 1.0, 4.0, 2.0, 3.0);
    assertThat(Scenarios.percentile(values, 50)).isEqualTo(3.0);
    assertThat(Scenarios.percentile(values, 95)).isEqualTo(5.0);
    assertThat(Scenarios.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
  }
}
