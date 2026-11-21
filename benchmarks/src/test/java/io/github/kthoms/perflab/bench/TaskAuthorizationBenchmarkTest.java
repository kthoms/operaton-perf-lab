package io.github.kthoms.perflab.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskAuthorizationBenchmarkTest {

  @TempDir Path dir;

  @Test
  void writesJsonResult() throws Exception {
    Path out = dir.resolve("result.json");
    var options =
        new TaskAuthorizationBenchmark.Options(
            "h2",
            "jdbc:h2:mem:bench-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "",
            20,
            2,
            1,
            3);

    new TaskAuthorizationBenchmark().run(options).write(out);

    JsonNode json = new ObjectMapper().readTree(out.toFile());
    assertThat(json.get("benchmark").asText()).isEqualTo("task-authorization");
    assertThat(json.get("database").asText()).isEqualTo("h2");
    JsonNode params = json.get("params");
    assertThat(params.get("tasks").asInt()).isEqualTo(20);
    assertThat(params.get("groups").asInt()).isEqualTo(2);
    assertThat(params.get("warmup").asInt()).isEqualTo(1);
    assertThat(params.get("iterations").asInt()).isEqualTo(3);
    JsonNode iterations = json.get("iterationsMs");
    assertThat(iterations.isArray()).isTrue();
    assertThat(iterations).hasSize(3);
    iterations.forEach(ms -> assertThat(ms.asDouble()).isPositive());
    JsonNode operations = json.get("operationsMs");
    assertThat(operations.fieldNames())
        .toIterable()
        .containsExactly("listPage", "listPageOrderedByCreateTimeDesc", "count");
    for (int i = 0; i < 3; i++) {
      double sum = 0;
      for (JsonNode op : operations) {
        assertThat(op).hasSize(3);
        sum += op.get(i).asDouble();
      }
      // iterationsMs is the per-iteration sum of the operations (values are rounded to 3 decimals)
      assertThat(iterations.get(i).asDouble()).isCloseTo(sum, org.assertj.core.data.Offset.offset(0.005));
    }
  }
}
