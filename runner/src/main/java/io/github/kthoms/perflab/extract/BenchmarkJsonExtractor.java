package io.github.kthoms.perflab.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.results.Measurement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads a benchmark result file ({@code {"benchmark":..,"iterationsMs":[..],"operationsMs":{..}}}):
 * one {@code benchmark.iteration.duration} per iteration (1-based), their median, and the median
 * of each operation, and one {@code benchmark.metric} per derived metric ({@code
 * "metrics":{"<name>":{"value":..,"unit":".."}}}, e.g. throughput in 1/s; dims benchmark and
 * metric). A malformed file throws {@link UncheckedIOException} or {@link
 * IllegalArgumentException}; a file of another benchmark or database {@link IllegalStateException}.
 */
public final class BenchmarkJsonExtractor {

  private static final ObjectMapper JSON = new ObjectMapper();

  private BenchmarkJsonExtractor() {}

  public static List<Measurement> extract(Path json) {
    return extract(json, null, null);
  }

  /** As {@link #extract(Path)}, and the file must belong to this benchmark and database. */
  public static List<Measurement> extract(Path json, String expectedBenchmark, String expectedDatabase) {
    return extract(json, expectedBenchmark, expectedBenchmark, expectedDatabase);
  }

  /**
   * As above, with the series name stored in the {@code benchmark} dimension: for a benchmark run at
   * several sizes it is {@code <id>@<tasks>}, so every size has its own baseline. The file's own
   * id must still be {@code expectedBenchmark}, and for a sized series its {@code params.tasks}
   * must match the size in the name.
   */
  public static List<Measurement> extract(
      Path json, String expectedBenchmark, String seriesName, String expectedDatabase) {
    JsonNode root;
    try {
      root = JSON.readTree(json.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read benchmark result " + json, e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException(json + ": not a JSON object");
    }
    String benchmark = root.path("benchmark").asText(null);
    String database = root.path("database").asText(null);
    if (benchmark == null || benchmark.isBlank()) {
      throw new IllegalArgumentException(json + ": no benchmark id");
    }
    if (expectedBenchmark != null && !expectedBenchmark.equals(benchmark)) {
      throw new IllegalStateException(
          json + ": result of benchmark '" + benchmark + "', expected '" + expectedBenchmark + "'");
    }
    if (expectedDatabase != null && !expectedDatabase.equals(database)) {
      throw new IllegalStateException(
          json + ": result for database '" + database + "', expected '" + expectedDatabase + "'");
    }
    if (seriesName != null && !seriesName.equals(benchmark)) {
      int at = seriesName.indexOf('@');
      if (at >= 0) {
        String expectedTasks = seriesName.substring(at + 1);
        String actualTasks = root.path("params").path("tasks").asText(null);
        if (!expectedTasks.equals(actualTasks)) {
          throw new IllegalStateException(
              json + ": result with tasks=" + actualTasks + ", expected " + expectedTasks + " for series '" + seriesName + "'");
        }
      }
    }
    String series = seriesName == null ? benchmark : seriesName;
    List<Double> iterations = values(json, "iterationsMs", root.path("iterationsMs"));
    List<Measurement> out = new ArrayList<>();
    for (int i = 0; i < iterations.size(); i++) {
      out.add(
          new Measurement(
              "benchmark.iteration.duration",
              iterations.get(i),
              "ms",
              Map.of("benchmark", series, "iteration", String.valueOf(i + 1))));
    }
    out.add(
        new Measurement("benchmark.median.duration", median(iterations), "ms", Map.of("benchmark", series)));

    JsonNode operations = root.path("operationsMs");
    if (!operations.isMissingNode()) {
      if (!operations.isObject()) {
        throw new IllegalArgumentException(json + ": operationsMs is not an object");
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = operations.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> op = it.next();
        List<Double> values = values(json, "operationsMs." + op.getKey(), op.getValue());
        out.add(
            new Measurement(
                "benchmark.operation.median.duration",
                median(values),
                "ms",
                Map.of("benchmark", series, "operation", op.getKey())));
      }
    }
    JsonNode metrics = root.path("metrics");
    if (!metrics.isMissingNode()) {
      if (!metrics.isObject()) {
        throw new IllegalArgumentException(json + ": metrics is not an object");
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = metrics.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> metric = it.next();
        JsonNode value = metric.getValue().path("value");
        JsonNode unit = metric.getValue().path("unit");
        if (!value.isNumber() || !unit.isTextual()) {
          throw new IllegalArgumentException(json + ": metric " + metric.getKey() + " needs a numeric value and a unit");
        }
        out.add(
            new Measurement(
                "benchmark.metric",
                value.asDouble(),
                unit.asText(),
                Map.of("benchmark", series, "metric", metric.getKey())));
      }
    }
    return out;
  }

  private static List<Double> values(Path json, String name, JsonNode array) {
    if (!array.isArray() || array.isEmpty()) {
      throw new IllegalArgumentException(json + ": " + name + " missing or empty");
    }
    List<Double> values = new ArrayList<>();
    for (JsonNode node : array) {
      if (!node.isNumber()) {
        throw new IllegalArgumentException(json + ": " + name + " contains " + node);
      }
      values.add(node.asDouble());
    }
    return values;
  }

  private static double median(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }
}
