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
 * {@code {"benchmark":..,"database":..,"params":{..},"iterationsMs":[..]}}.
 */
public record BenchmarkResult(
    String benchmark, String database, Map<String, Integer> params, List<Double> iterationsMs) {

  public BenchmarkResult {
    params = new LinkedHashMap<>(params);
    iterationsMs = List.copyOf(iterationsMs);
  }

  public String toJson() {
    String paramsJson =
        params.entrySet().stream()
            .map(e -> quote(e.getKey()) + ":" + e.getValue())
            .collect(Collectors.joining(",", "{", "}"));
    String iterationsJson =
        iterationsMs.stream()
            .map(ms -> String.format(Locale.ROOT, "%.3f", ms))
            .collect(Collectors.joining(",", "[", "]"));
    return "{\"benchmark\":" + quote(benchmark)
        + ",\"database\":" + quote(database)
        + ",\"params\":" + paramsJson
        + ",\"iterationsMs\":" + iterationsJson
        + "}";
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
