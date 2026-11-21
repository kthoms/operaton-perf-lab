package io.github.kthoms.perflab.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A daily local-time window {@code [start, end)}, written {@code "HH:mm-HH:mm"}. If end is not
 * after start the window wraps midnight, e.g. {@code 22:00-07:00}.
 */
public record TimeWindow(LocalTime start, LocalTime end) {

  private static final Pattern FORMAT = Pattern.compile("(\\d{2}:\\d{2})-(\\d{2}:\\d{2})");

  public TimeWindow {
    if (start == null || end == null) {
      throw new IllegalArgumentException("time window needs a start and an end");
    }
  }

  @JsonCreator
  public static TimeWindow parse(String text) {
    Matcher m = FORMAT.matcher(text == null ? "" : text.trim());
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "Invalid time window '" + text + "' (expected HH:mm-HH:mm, e.g. 22:00-07:00)");
    }
    try {
      return new TimeWindow(LocalTime.parse(m.group(1)), LocalTime.parse(m.group(2)));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Invalid time window '" + text + "': " + e.getMessage(), e);
    }
  }

  public boolean contains(LocalTime t) {
    if (start.isBefore(end)) {
      return !t.isBefore(start) && t.isBefore(end);
    }
    return !t.isBefore(start) || t.isBefore(end);
  }

  @Override
  public String toString() {
    return start + "-" + end;
  }
}
