package io.github.kthoms.perflab.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.results.Measurement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a benchmark result file ({@code {"benchmark":..,"iterationsMs":[..]}}): one
 * {@code benchmark.iteration.duration} per iteration (1-based) and their median.
 */
public final class BenchmarkJsonExtractor {

  private static final ObjectMapper JSON = new ObjectMapper();

  private BenchmarkJsonExtractor() {}

  public static List<Measurement> extract(Path json) {
    JsonNode root;
    try {
      root = JSON.readTree(json.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read benchmark result " + json, e);
    }
    String benchmark = root.path("benchmark").asText(null);
    JsonNode iterations = root.path("iterationsMs");
    if (benchmark == null || benchmark.isBlank()) {
      throw new IllegalArgumentException(json + ": no benchmark id");
    }
    if (!iterations.isArray() || iterations.isEmpty()) {
      throw new IllegalArgumentException(json + ": iterationsMs missing or empty");
    }
    List<Measurement> out = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    int i = 1;
    for (JsonNode node : iterations) {
      if (!node.isNumber()) {
        throw new IllegalArgumentException(json + ": iterationsMs contains " + node);
      }
      double ms = node.asDouble();
      values.add(ms);
      out.add(
          new Measurement(
              "benchmark.iteration.duration",
              ms,
              "ms",
              Map.of("benchmark", benchmark, "iteration", String.valueOf(i++))));
    }
    out.add(new Measurement("benchmark.median.duration", median(values), "ms", Map.of("benchmark", benchmark)));
    return out;
  }

  private static double median(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }
}
