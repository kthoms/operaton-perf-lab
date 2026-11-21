package io.github.kthoms.perflab.daemon;

import java.time.Instant;

/** Called once per tick while no job runs (e.g. backup). Exceptions are logged, never fatal. */
@FunctionalInterface
public interface IdleHook {
  void betweenJobs(Instant now);
}
