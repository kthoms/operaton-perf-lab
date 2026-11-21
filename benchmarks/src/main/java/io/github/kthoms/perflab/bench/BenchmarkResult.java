package io.github.kthoms.perflab.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of one benchmark execution, written as
 * {@code {"benchmark":..,"database":..,"params":{..},"iterationsMs":[..],"operationsMs":{"<op>":[..]}}}.
 */
public record BenchmarkResult(
    String benchmark,
    String database,
    Map<String, Integer> params,
    List<Double> iterationsMs,
    Map<String, List<Double>> operationsMs) {

  public BenchmarkResult {
    params = new LinkedHashMap<>(params);
    iterationsMs = List.copyOf(iterationsMs);
    Map<String, List<Double>> ops = new LinkedHashMap<>();
    operationsMs.forEach((name, values) -> ops.put(name, List.copyOf(values)));
    operationsMs = ops;
  }

  public String toJson() {
    String paramsJson =
        params.entrySet().stream()
            .map(e -> quote(e.getKey()) + ":" + e.getValue())
            .collect(Collectors.joining(",", "{", "}"));
    String operationsJson =
        operationsMs.entrySet().stream()
            .map(e -> quote(e.getKey()) + ":" + array(e.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
    return "{\"benchmark\":" + quote(benchmark)
        + ",\"database\":" + quote(database)
        + ",\"params\":" + paramsJson
        + ",\"iterationsMs\":" + array(iterationsMs)
        + ",\"operationsMs\":" + operationsJson
        + "}";
  }

  private static String array(List<Double> ms) {
    return ms.stream()
        .map(v -> String.format(Locale.ROOT, "%.3f", v))
        .collect(Collectors.joining(",", "[", "]"));
  }

  public void write(Path file) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, toJson() + "\n", StandardCharsets.UTF_8);
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
