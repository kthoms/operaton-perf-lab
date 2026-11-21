package io.github.kthoms.perflab.idle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MacActivityProbeTest {

  @Test
  void parsesHidIdleTime() {
    String out =
        "    | | | \"IdleTime\" = 5\n    | | | \"HIDIdleTime\" = 8466325967875\n";
    assertThat(MacActivityProbe.parseHidIdleNanos(out))
        .isEqualTo(Duration.ofNanos(8466325967875L));
  }

  @Test
  void parsesLoadAvg() {
    assertThat(MacActivityProbe.parseLoad1("{ 2.56 2.68 4.82 }\n")).isEqualTo(2.56);
    assertThat(MacActivityProbe.parseLoad1("{ 12,5 1,0 1,0 }")).isEqualTo(12.5);
  }

  @Test
  void parsesPmsetBattery() {
    assertThat(
            MacActivityProbe.parseOnAc(
                "Now drawing from 'AC Power'\n -InternalBattery-0 (id=1) 80%; charging"))
        .isTrue();
    assertThat(
            MacActivityProbe.parseOnAc(
                "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1) 80%; discharging"))
        .isFalse();
  }

  @Test
  void heavyProcessesExcludeOwnTreeAndMatchBasename() {
    String ps =
        "  PID  PPID COMM\n"
            + "    1     0 /sbin/launchd\n"
            + "  100     1 /usr/bin/java\n"
            + "  101   100 /opt/homebrew/bin/mvn\n"
            + "  102   101 /usr/bin/java\n"
            + "  200     1 /Applications/Docker.app/Contents/MacOS/com.docker.backend\n"
            + "  201     1 /usr/bin/javac-wrapper\n"
            + "  300     1 /usr/bin/java\n";
    var found = MacActivityProbe.parseHeavyForeign(ps, Set.of("java", "com.docker.backend"), 100);
    assertThat(found).containsExactly("com.docker.backend", "java");
  }

  @Test
  void logsProbeFailureOnlyOnStateTransitions() {
    var messages = new java.util.ArrayList<String>();
    System.Logger rec =
        new System.Logger() {
          public String getName() {
            return "rec";
          }

          public boolean isLoggable(Level level) {
            return true;
          }

          public void log(Level level, java.util.ResourceBundle b, String msg, Throwable t) {
            messages.add(level + " " + msg);
          }

          public void log(Level level, java.util.ResourceBundle b, String fmt, Object... params) {
            messages.add(level + " " + java.text.MessageFormat.format(fmt, params));
          }
        };
    boolean[] broken = {true};
    var probe =
        new MacActivityProbe(
            java.util.List.of(),
            cmd -> {
              if (broken[0]) {
                throw new IllegalStateException("boom");
              }
              return "    | | | \"HIDIdleTime\" = 1000000000\n";
            },
            rec);

    assertThat(probe.userIdle()).isEmpty();
    assertThat(probe.userIdle()).isEmpty();
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0)).startsWith("WARNING").contains("ioreg").contains("IllegalStateException").contains("boom");

    broken[0] = false;
    assertThat(probe.userIdle()).contains(Duration.ofSeconds(1));
    assertThat(probe.userIdle()).contains(Duration.ofSeconds(1));
    assertThat(messages).hasSize(2);
    assertThat(messages.get(1)).isEqualTo("INFO probe ioreg recovered");
  }
}
