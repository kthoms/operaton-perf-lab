package io.github.kthoms.perflab.daemon;

/** Called after a run was stored (e.g. regression detection). Exceptions are logged, never fatal. */
@FunctionalInterface
public interface AfterRunHook {
  void afterRun(long runId);
}
