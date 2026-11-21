package io.github.kthoms.perflab.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class FingerprintTest {

  private static final String COLIMA = "{\"name\":\"default\",\"cpus\":4,\"memory\":8589934592}";

  private static Function<List<String>, String> exec(String javaVersion, String load) {
    return cmd -> {
      String joined = String.join(" ", cmd);
      if (joined.equals("sysctl -n hw.model")) return "Mac14,2\n";
      if (joined.equals("sysctl -n machdep.cpu.brand_string")) return "Apple M2\n";
      if (joined.equals("sysctl -n hw.memsize")) return "17179869184\n";
      if (joined.equals("sw_vers -productVersion")) return "15.1\n";
      if (joined.endsWith("/bin/java -version"))
        return "openjdk version \"" + javaVersion + "\" 2025-01-21\nOpenJDK Runtime\n";
      if (joined.equals("mvn -v")) return "Apache Maven 3.9.9 (abc)\nMaven home: /x\n";
      if (joined.equals("colima list --json")) return COLIMA + "\n";
      if (joined.equals("sysctl -n vm.loadavg")) return "{ " + load + " 1.00 1.00 }\n";
      if (joined.startsWith("ioreg")) return "      | \"HIDIdleTime\" = 5000000000\n";
      throw new IllegalStateException("unexpected " + joined);
    };
  }

  private static PerfLabConfig config() {
    return new PerfLabConfig(
        Path.of("/data"), null, 0, null, null, List.of(), List.of(), null);
  }

  private static BranchConfig branch() {
    return new BranchConfig("main", Path.of("/jdk"), List.of("-DskipTests"));
  }

  @Test
  void sameStableInfoSameSegment() {
    var a = new FingerprintCollector(exec("21.0.5", "0.10")).collect(branch(), config());
    var b = new FingerprintCollector(exec("21.0.5", "3.70")).collect(branch(), config());

    assertThat(a.volatileInfo()).isNotEqualTo(b.volatileInfo());
    assertThat(a.segment()).isEqualTo(b.segment()).hasSize(12).matches("[0-9a-f]{12}");
  }

  @Test
  void changedJavaVersionNewSegment() {
    var a = new FingerprintCollector(exec("21.0.5", "0.10")).collect(branch(), config());
    var b = new FingerprintCollector(exec("25.0.1", "0.10")).collect(branch(), config());

    assertThat(a.segment()).isNotEqualTo(b.segment());
  }

  @Test
  void parsesAllStableKeys() {
    var f = new FingerprintCollector(exec("21.0.5", "0.10")).collect(branch(), config());

    assertThat(f.stable())
        .containsAllEntriesOf(
            Map.of(
                "hw.model", "Mac14,2",
                "hw.cpu", "Apple M2",
                "hw.memBytes", "17179869184",
                "os.version", "15.1",
                "java.version", "21.0.5",
                "maven.version", "Apache Maven 3.9.9 (abc)",
                "colima.cpus", "4",
                "colima.memory", "8589934592",
                "dataDir", "/data"))
        .containsEntry("mavenArgs", "-DskipTests");
    assertThat(f.volatileInfo()).containsEntry("load1.start", "0.10");
  }

  @Test
  void failingProbeYieldsUnknownInStable() {
    var f =
        new FingerprintCollector(
                cmd -> {
                  throw new IllegalStateException("boom");
                })
            .collect(branch(), config());

    assertThat(f.stable()).containsEntry("hw.model", "unknown").containsEntry("colima.cpus", "unknown");
  }

  @Test
  void versionParsingSkipsNoiseLines() {
    var base = exec("21.0.5", "0.10");
    Function<List<String>, String> noisy =
        cmd -> {
          String j = String.join(" ", cmd);
          if (j.endsWith("/bin/java -version"))
            return "Picked up JAVA_TOOL_OPTIONS: -Xmx1g\n" + base.apply(cmd);
          if (j.equals("mvn -v")) return "WARNING: something\n" + base.apply(cmd);
          return base.apply(cmd);
        };

    var f = new FingerprintCollector(noisy).collect(branch(), config());

    assertThat(f.stable())
        .containsEntry("java.version", "21.0.5")
        .containsEntry("maven.version", "Apache Maven 3.9.9 (abc)");
  }

  @Test
  void hangingProbeTimesOutInsteadOfBlocking() {
    long start = System.nanoTime();

    assertThatThrownBy(
            () -> FingerprintCollector.run(List.of("sh", "-c", "sleep 60"), Duration.ofMillis(500)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timeout");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));

    // and through the collector, a hanging probe degrades to "unknown"
    var f =
        new FingerprintCollector(
                cmd -> FingerprintCollector.run(List.of("sh", "-c", "sleep 60"), Duration.ofMillis(200)))
            .collect(branch(), config());
    assertThat(f.stable()).containsEntry("hw.model", "unknown");
  }
}
