package io.github.kthoms.perflab.extract;

import io.github.kthoms.perflab.results.Measurement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts per-module and total wall time from a Maven build log's reactor summary. */
public final class ReactorSummaryExtractor {
  private ReactorSummaryExtractor() {}

  private static final String PREFIX = "^(?:\\d{1,2}:\\d{2}:\\d{2}[,.]\\d+\\s+)?\\[INFO\\]\\s+";
  private static final Pattern MODULE =
      Pattern.compile(PREFIX + "(.+?)\\s+\\.{2,}\\s+SUCCESS\\s+\\[\\s*(.+?)\\s*\\]\\s*$");
  private static final Pattern TOTAL = Pattern.compile(PREFIX + "Total time:\\s+(.+?)\\s*$");
  private static final Pattern TIME =
      Pattern.compile("^(?:(\\d+):(\\d{1,2}):(\\d{2})\\s+h|(\\d+):(\\d{2})\\s+min|(\\d+(?:\\.\\d+)?)\\s+s)$");

  public static List<Measurement> extract(Path buildLog) {
    List<String> lines;
    try {
      lines = Files.readAllLines(buildLog);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Measurement> out = new ArrayList<>();
    for (String line : lines) {
      Matcher m = MODULE.matcher(line);
      if (m.matches()) {
        Double s = seconds(m.group(2));
        if (s != null) {
          out.add(new Measurement("build.module.wall", s, "s", Map.of("module", m.group(1))));
        }
        continue;
      }
      Matcher t = TOTAL.matcher(line);
      if (t.matches()) {
        Double s = seconds(t.group(1));
        if (s != null) {
          out.add(new Measurement("build.total.wall", s, "s", Map.of()));
        }
      }
    }
    return out;
  }

  private static Double seconds(String text) {
    Matcher m = TIME.matcher(text.trim());
    if (!m.matches()) {
      return null;
    }
    if (m.group(1) != null) {
      return Long.parseLong(m.group(1)) * 3600.0
          + Long.parseLong(m.group(2)) * 60.0
          + Long.parseLong(m.group(3));
    }
    if (m.group(4) != null) {
      return Long.parseLong(m.group(4)) * 60.0 + Long.parseLong(m.group(5));
    }
    return Double.parseDouble(m.group(6));
  }
}
