package io.github.kthoms.perflab.idle;

import io.github.kthoms.perflab.config.PerfLabConfig.IdleConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Decides whether a job of the expected duration may start now. */
public class IdleGate {

  public record Decision(boolean ok, String reason) {}

  private final ActivityProbe probe;
  private final ActivityHistory history;
  private final IdleConfig config;

  public IdleGate(ActivityProbe probe, ActivityHistory history, IdleConfig config) {
    this.probe = probe;
    this.history = history;
    this.config = config;
  }

  public Decision mayStart(Duration expected, Instant now) {
    Duration idle = probe.userIdle();
    if (idle.compareTo(config.minUserIdle()) < 0) {
      return no("user active " + fmt(idle) + " < " + fmt(config.minUserIdle()));
    }
    double load = probe.load1();
    if (load > config.maxLoad1()) {
      return no("load " + load + " > " + config.maxLoad1());
    }
    if (!probe.onAcPower()) {
      return no("on battery");
    }
    List<String> heavy = probe.heavyForeignProcesses();
    if (!heavy.isEmpty()) {
      return no("heavy process running: " + String.join(", ", heavy));
    }
    Duration predicted = history.predictedIdle(now);
    if (predicted.compareTo(expected) < 0) {
      return no("predicted idle " + fmt(predicted) + " < expected " + fmt(expected));
    }
    return new Decision(true, "ok");
  }

  /** True when the user is back; used by the cancel watchdog. */
  public boolean userActive() {
    return probe.userIdle().compareTo(config.activityCheckInterval().multipliedBy(2)) < 0;
  }

  private static Decision no(String reason) {
    return new Decision(false, reason);
  }

  private static String fmt(Duration d) {
    long m = d.toMinutes();
    return m + "m";
  }
}
