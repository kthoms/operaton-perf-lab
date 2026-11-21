package io.github.kthoms.perflab.idle;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** The macOS-specific seam: what the machine is doing right now. */
public interface ActivityProbe {

  /** Time since the last keyboard/mouse input; empty when the probe failed. */
  Optional<Duration> userIdle();

  /** One-minute load average. */
  double load1();

  boolean onAcPower();

  /** Names of configured heavy processes that are not the daemon or its descendants. */
  List<String> heavyForeignProcesses();
}
