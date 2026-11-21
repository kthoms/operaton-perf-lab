package io.github.kthoms.perflab.daemon;

import java.time.Instant;

/**
 * Work done between jobs (e.g. backup): called at the end of each tick, but never while the data
 * dir is unusable. Exceptions are logged, never fatal.
 */
@FunctionalInterface
public interface IdleHook {
  void betweenJobs(Instant now);
}
