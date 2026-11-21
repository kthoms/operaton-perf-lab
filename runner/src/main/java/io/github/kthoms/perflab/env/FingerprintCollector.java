package io.github.kthoms.perflab.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes the machine by shelling out. A failing probe yields "unknown" in the stable part, so a
 * broken probe surfaces as a segment change rather than being silently ignored.
 */
public class FingerprintCollector {

  static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
  static final String UNKNOWN = "unknown";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern JAVA_VERSION = Pattern.compile("version \"([^\"]+)\"");
  private static final Pattern DIGITS = Pattern.compile("(\\d+)");

  private final Function<List<String>, String> exec;

  public FingerprintCollector() {
    this(cmd -> run(cmd, PROBE_TIMEOUT));
  }

  /** The executor returns stdout (and stderr) of the command or throws on failure. */
  public FingerprintCollector(Function<List<String>, String> exec) {
    this.exec = exec;
  }

  public Fingerprint collect(BranchConfig branch, PerfLabConfig config) {
    Map<String, String> stable = new LinkedHashMap<>();
    stable.put("hw.model", probe(() -> firstLine(exec.apply(List.of("sysctl", "-n", "hw.model")))));
    stable.put(
        "hw.cpu",
        probe(() -> firstLine(exec.apply(List.of("sysctl", "-n", "machdep.cpu.brand_string")))));
    stable.put(
        "hw.memBytes", probe(() -> firstLine(exec.apply(List.of("sysctl", "-n", "hw.memsize")))));
    stable.put(
        "os.version", probe(() -> firstLine(exec.apply(List.of("sw_vers", "-productVersion")))));
    stable.put("java.version", probe(() -> javaVersion(branch)));
    stable.put("maven.version", probe(this::mavenVersion));
    JsonNode colima = colima();
    stable.put("colima.cpus", probe(() -> colima.get("cpus").asText()));
    stable.put("colima.memory", probe(() -> colima.get("memory").asText()));
    stable.put("dataDir", String.valueOf(config.dataDir()));
    stable.put("mavenArgs", String.join(" ", branch.mavenArgs()));

    Map<String, String> volatileInfo = new LinkedHashMap<>();
    volatileInfo.put("load1.start", probe(this::load1));
    volatileInfo.put("userIdle.start", probe(this::userIdleSeconds));
    return new Fingerprint(stable, volatileInfo);
  }

  private String javaVersion(BranchConfig branch) {
    String out = exec.apply(List.of(branch.javaHome().resolve("bin/java").toString(), "-version"));
    for (String line : out.split("\\R")) {
      Matcher m = JAVA_VERSION.matcher(line);
      if (m.find()) {
        return m.group(1);
      }
    }
    throw new IllegalStateException("no java version in: " + out);
  }

  private String mavenVersion() {
    for (String line : exec.apply(List.of("mvn", "-v")).split("\\R")) {
      if (line.startsWith("Apache Maven")) {
        return line.strip();
      }
    }
    throw new IllegalStateException("no maven version");
  }

  private JsonNode colima() {
    try {
      String out = exec.apply(List.of("colima", "list", "--json"));
      for (String line : out.split("\\R")) {
        if (line.trim().startsWith("{")) {
          return JSON.readTree(line);
        }
      }
    } catch (IOException | RuntimeException e) {
      // fall through to the empty node; the individual probes report "unknown"
    }
    return JSON.createObjectNode();
  }

  private String load1() {
    String out = firstLine(exec.apply(List.of("sysctl", "-n", "vm.loadavg")));
    return out.replaceAll("[{}]", "").trim().split("\\s+")[0];
  }

  private String userIdleSeconds() {
    String out = exec.apply(List.of("ioreg", "-c", "IOHIDSystem"));
    for (String line : out.split("\\R")) {
      if (line.contains("HIDIdleTime")) {
        Matcher m = DIGITS.matcher(line.substring(line.indexOf('=') + 1));
        if (m.find()) {
          return String.valueOf(Long.parseLong(m.group(1)) / 1_000_000_000L);
        }
      }
    }
    throw new IllegalStateException("no HIDIdleTime");
  }

  private static String firstLine(String out) {
    String line = out.strip().split("\\R", 2)[0].strip();
    if (line.isEmpty()) {
      throw new IllegalStateException("empty output");
    }
    return line;
  }

  private static String probe(Supplier<String> s) {
    try {
      return s.get();
    } catch (RuntimeException e) {
      return UNKNOWN;
    }
  }

  static String run(List<String> cmd, Duration timeout) {
    Path out = null;
    try {
      out = Files.createTempFile("perflab-probe", ".out");
      Process p =
          new ProcessBuilder(cmd)
              .redirectErrorStream(true)
              .redirectOutput(out.toFile())
              .start();
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("timeout: " + cmd);
      }
      if (p.exitValue() != 0) {
        throw new IllegalStateException("exit " + p.exitValue() + ": " + cmd);
      }
      return Files.readString(out, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot run " + cmd, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted: " + cmd, e);
    } finally {
      if (out != null) {
        try {
          Files.deleteIfExists(out);
        } catch (IOException ignored) {
          // best effort
        }
      }
    }
  }
}
