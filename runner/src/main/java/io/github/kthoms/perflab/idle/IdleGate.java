package io.github.kthoms.perflab.idle;

import io.github.kthoms.perflab.config.PerfLabConfig.IdleConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Decides whether a job of the expected duration may start now. */
public class IdleGate {

  public record Decision(boolean ok, String reason) {}

  static final int FAILURES_BEFORE_ACTIVE = 3;

  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final ActivityProbe probe;
  private final ActivityHistory history;
  private final IdleConfig config;

  public IdleGate(ActivityProbe probe, ActivityHistory history, IdleConfig config) {
    this.probe = probe;
    this.history = history;
    this.config = config;
  }

  /** Gate for a scheduled job: all checks including the predicted idle time. */
  public Decision mayStart(Duration expected, Instant now) {
    return mayStart(expected, now, false);
  }

  /**
   * {@code manual}: a job the owner enqueued by hand; it skips only the predicted-idle check (the
   * owner asked for it), never the current idle time, load, power or heavy-process checks.
   */
  public Decision mayStart(Duration expected, Instant now, boolean manual) {
    Optional<Duration> probed = probe.userIdle();
    if (probed.isEmpty()) {
      return no("user idle probe failed");
    }
    consecutiveFailures.set(0);
    Duration idle = probed.get();
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
    if (manual) {
      return new Decision(true, "ok (manual job, prediction skipped)");
    }
    Duration predicted = history.predictedIdle(now);
    if (predicted.compareTo(expected) < 0) {
      return no("predicted idle " + fmt(predicted) + " < expected " + fmt(expected));
    }
    return new Decision(true, "ok");
  }

  /**
   * True when the user is back; used by the cancel watchdog. A failed probe counts as activity only
   * after {@value #FAILURES_BEFORE_ACTIVE} consecutive failures, so one glitch does not cancel a job.
   */
  public boolean userActive() {
    Optional<Duration> idle = probe.userIdle();
    if (idle.isEmpty()) {
      // intentionally keeps counting (stays >= 3) until any successful probe resets it
      return consecutiveFailures.incrementAndGet() >= FAILURES_BEFORE_ACTIVE;
    }
    consecutiveFailures.set(0);
    return idle.get().compareTo(config.activityCheckInterval().multipliedBy(2)) < 0;
  }

  private static Decision no(String reason) {
    return new Decision(false, reason);
  }

  private static String fmt(Duration d) {
    long m = d.toMinutes();
    return m + "m";
  }
}
