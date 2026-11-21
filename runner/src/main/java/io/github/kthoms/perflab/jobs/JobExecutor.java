package io.github.kthoms.perflab.jobs;

import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.results.Measurement;
import io.github.kthoms.perflab.results.RunRecord;
import java.util.List;

/** Runs one job. Infrastructure failures (git, I/O) are thrown; build failures are Completed. */
public interface JobExecutor {

  Outcome execute(Job job, CancellationToken token);

  sealed interface Outcome permits Completed, Cancelled, NotApplicable {}

  /** A finished run, status SUCCESS (with measurements) or FAILED (without). */
  record Completed(RunRecord run, List<Measurement> measurements) implements Outcome {
    public Completed {
      measurements = List.copyOf(measurements);
    }
  }

  /** Stopped by the cancellation token; nothing is stored. */
  record Cancelled() implements Outcome {}

  /** The job cannot run at all, e.g. its commit is not reachable from upstream. */
  record NotApplicable(String reason) implements Outcome {}
}
