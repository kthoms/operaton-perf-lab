package io.github.kthoms.perflab.idle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.config.PerfLabConfig.IdleConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdleGateTest {

  private static final Instant NOW = Instant.parse("2026-11-21T02:00:00Z");
  private static final IdleConfig CONFIG =
      new IdleConfig(
          Duration.ofMinutes(15),
          4.0,
          List.of("java"),
          Duration.ofSeconds(60),
          Duration.ofSeconds(30));

  static class FakeProbe implements ActivityProbe {
    Duration idle = Duration.ofHours(1);
    boolean idleProbeFails = false;
    double load = 0.5;
    boolean ac = true;
    List<String> heavy = List.of();

    public Optional<Duration> userIdle() {
      return idleProbeFails ? Optional.empty() : Optional.of(idle);
    }

    public double load1() {
      return load;
    }

    public boolean onAcPower() {
      return ac;
    }

    public List<String> heavyForeignProcesses() {
      return heavy;
    }
  }

  private static ActivityHistory fixed(Duration predicted) {
    return new ActivityHistory(null) {
      @Override
      public Duration predictedIdle(Instant now) {
        return predicted;
      }
    };
  }

  private static IdleGate gate(FakeProbe p, Duration predicted) {
    return new IdleGate(p, fixed(predicted), CONFIG);
  }

  @Test
  void allowsWhenAllConditionsHold() {
    var d = gate(new FakeProbe(), Duration.ofHours(3)).mayStart(Duration.ofMinutes(90), NOW);
    assertThat(d.ok()).isTrue();
  }

  @Test
  void blocksWhenPredictedIdleTooShort() {
    var d = gate(new FakeProbe(), Duration.ofMinutes(45)).mayStart(Duration.ofMinutes(90), NOW);
    assertThat(d.ok()).isFalse();
    assertThat(d.reason()).isEqualTo("predicted idle 45m < expected 90m");
  }

  @Test
  void blocksOnBattery() {
    var p = new FakeProbe();
    p.ac = false;
    var d = gate(p, Duration.ofHours(3)).mayStart(Duration.ofMinutes(90), NOW);
    assertThat(d.ok()).isFalse();
    assertThat(d.reason()).contains("battery");
  }

  @Test
  void blocksOnForeignHeavyProcess() {
    var p = new FakeProbe();
    p.heavy = List.of("java");
    var d = gate(p, Duration.ofHours(3)).mayStart(Duration.ofMinutes(90), NOW);
    assertThat(d.ok()).isFalse();
    assertThat(d.reason()).contains("java");
  }

  @Test
  void blocksWhenUserActiveAndNamesFirstFailure() {
    var p = new FakeProbe();
    p.idle = Duration.ofMinutes(2);
    p.ac = false;
    var d = gate(p, Duration.ZERO).mayStart(Duration.ofMinutes(90), NOW);
    assertThat(d.reason()).isEqualTo("user active 2m < 15m");
  }

  @Test
  void blocksOnHighLoad() {
    var p = new FakeProbe();
    p.load = 9.0;
    assertThat(gate(p, Duration.ofHours(3)).mayStart(Duration.ofMinutes(1), NOW).ok()).isFalse();
  }

  @Test
  void userActiveUsesTwiceTheCheckInterval() {
    var p = new FakeProbe();
    p.idle = Duration.ofSeconds(59);
    assertThat(gate(p, Duration.ZERO).userActive()).isTrue();
    p.idle = Duration.ofSeconds(60);
    assertThat(gate(p, Duration.ZERO).userActive()).isFalse();
  }

  @Test
  void singleProbeFailureDoesNotCountAsActive() {
    var p = new FakeProbe();
    var g = gate(p, Duration.ZERO);
    p.idleProbeFails = true;
    assertThat(g.userActive()).isFalse();
    assertThat(g.userActive()).isFalse();
    p.idleProbeFails = false;
    assertThat(g.userActive()).isFalse();
    p.idleProbeFails = true; // counter was reset by the success
    assertThat(g.userActive()).isFalse();
  }

  @Test
  void threeConsecutiveFailuresCountAsActive() {
    var p = new FakeProbe();
    p.idleProbeFails = true;
    var g = gate(p, Duration.ZERO);
    assertThat(g.userActive()).isFalse();
    assertThat(g.userActive()).isFalse();
    assertThat(g.userActive()).isTrue();
  }

  @Test
  void probeFailureClosesGate() {
    var p = new FakeProbe();
    p.idleProbeFails = true;
    var d = gate(p, Duration.ofHours(3)).mayStart(Duration.ofMinutes(1), NOW);
    assertThat(d.ok()).isFalse();
    assertThat(d.reason()).isEqualTo("user idle probe failed");
  }
}
