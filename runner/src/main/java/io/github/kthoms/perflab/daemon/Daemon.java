package io.github.kthoms.perflab.daemon;

import io.github.kthoms.perflab.config.DataDirGuard;
import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.idle.ActivityHistory;
import io.github.kthoms.perflab.idle.ActivityProbe;
import io.github.kthoms.perflab.idle.IdleGate;
import io.github.kthoms.perflab.jobs.DurationEstimator;
import io.github.kthoms.perflab.jobs.Job;
import io.github.kthoms.perflab.jobs.JobExecutor;
import io.github.kthoms.perflab.jobs.JobExecutor.Cancelled;
import io.github.kthoms.perflab.jobs.JobExecutor.Completed;
import io.github.kthoms.perflab.jobs.JobExecutor.NotApplicable;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
import io.github.kthoms.perflab.jobs.JobStatus;
import io.github.kthoms.perflab.jobs.Scheduler;
import io.github.kthoms.perflab.results.ResultRepository;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The single-threaded daemon loop: connect (with backoff), then every poll interval record an
 * activity sample (also while a job runs), check the data dir, schedule due jobs and run at most one job the idle gate
 * admits. A running job is cancelled as soon as the owner is active or a cancel was requested.
 */
public class Daemon {

  /** Injectable sleep so tests can observe the backoff and poll delays. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  /** Everything that needs the results DB; created by the connect supplier. */
  public record Services(
      JobRepository jobs,
      ResultRepository results,
      ActivityProbe probe,
      ActivityHistory history,
      IdleGate gate,
      DurationEstimator estimator,
      Scheduler scheduler,
      Map<JobKind, JobExecutor> executors) {
    public Services {
      executors = Map.copyOf(executors);
    }
  }

  static final Duration BACKOFF_START = Duration.ofSeconds(5);
  static final Duration BACKOFF_MAX = Duration.ofMinutes(5);
  /** After this many attempts an infrastructure failure finishes the job FAILED instead of requeueing. */
  static final int MAX_ATTEMPTS = 3;

  private static final System.Logger LOG = System.getLogger(Daemon.class.getName());

  private final PerfLabConfig config;
  private final Supplier<Services> connect;
  private final List<AfterRunHook> afterRunHooks;
  private final List<IdleHook> idleHooks;
  private final Clock clock;
  private final Sleeper sleeper;
  private boolean staleJobsPossible;
  private boolean tickFailing;

  public Daemon(
      PerfLabConfig config,
      Supplier<Services> connect,
      List<AfterRunHook> afterRunHooks,
      List<IdleHook> idleHooks) {
    this(config, connect, afterRunHooks, idleHooks, Clock.systemUTC(), d -> Thread.sleep(d));
  }

  public Daemon(
      PerfLabConfig config,
      Supplier<Services> connect,
      List<AfterRunHook> afterRunHooks,
      List<IdleHook> idleHooks,
      Clock clock,
      Sleeper sleeper) {
    this.config = config;
    this.connect = connect;
    this.afterRunHooks = List.copyOf(afterRunHooks);
    this.idleHooks = List.copyOf(idleHooks);
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /** Runs until the thread is interrupted. Never exits because the DB is down. */
  public void runForever() {
    try {
      Services services = start();
      while (true) {
        tick(services);
        sleeper.sleep(config.idle().pollInterval());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.log(Level.INFO, "daemon interrupted, stopping");
    }
  }

  /** Connects and requeues jobs left RUNNING by a previous process, retrying with backoff. */
  Services start() throws InterruptedException {
    Duration delay = BACKOFF_START;
    while (true) {
      try {
        Services services = connect.get();
        int stale = services.jobs().requeueStale();
        if (stale > 0) {
          LOG.log(Level.INFO, "requeued {0} job(s) left running by a previous daemon", stale);
        }
        LOG.log(Level.INFO, "daemon started");
        return services;
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "results DB unavailable (" + e + "), retrying in " + delay);
        sleeper.sleep(delay);
        delay = delay.multipliedBy(2).compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : delay.multipliedBy(2);
      }
    }
  }

  void tick(Services s) {
    Instant now = clock.instant();
    recordSample(s, now);
    Optional<String> dataDirProblem = DataDirGuard.check(config);
    if (dataDirProblem.isPresent()) {
      // also no idle hooks: their targets (e.g. backupDir) may live on the missing volume
      LOG.log(Level.WARNING, dataDirProblem.get() + " - starting no job");
      return;
    }
    try {
      recoverStaleJobs(s);
      s.scheduler().ensureDueJobs(now);
      nextJob(s, now).ifPresent(job -> run(s, job));
      if (tickFailing) {
        LOG.log(Level.INFO, "tick recovered");
        tickFailing = false;
      }
    } catch (RuntimeException e) {
      if (tickFailing) {
        LOG.log(Level.WARNING, "tick failed again: " + e);
      } else {
        LOG.log(Level.WARNING, "tick failed: " + e, e);
        tickFailing = true;
      }
    }
    runIdleHooks();
  }

  private void runIdleHooks() {
    Instant after = clock.instant();
    for (IdleHook hook : idleHooks) {
      try {
        hook.betweenJobs(after);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "idle hook failed: " + e, e);
      }
    }
  }

  private void recordSample(Services s, Instant now) {
    try {
      Optional<Duration> idle = s.probe().userIdle();
      if (idle.isPresent()) {
        s.history().record(now, idle.get(), s.probe().load1());
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "could not record activity sample: " + e);
    }
  }

  /** Only needed after a failure left a job RUNNING; this daemon is the only job runner. */
  private void recoverStaleJobs(Services s) {
    if (staleJobsPossible) {
      s.jobs().requeueStale();
      staleJobsPossible = false;
    }
  }

  private Optional<Job> nextJob(Services s, Instant now) {
    for (Job job : s.jobs().queued()) {
      if (!s.executors().containsKey(job.kind())) {
        LOG.log(Level.DEBUG, "job {0}: no executor for {1} yet", job.id(), job.kind());
        continue;
      }
      Duration expected = s.estimator().expected(job);
      boolean manual = job.priority() == Job.PRIORITY_MANUAL;
      IdleGate.Decision decision = s.gate().mayStart(expected, now, manual);
      if (decision.ok()) {
        return Optional.of(job);
      }
      LOG.log(Level.DEBUG, "job {0} not started: {1}", job.id(), decision.reason());
    }
    return Optional.empty();
  }

  private void run(Services s, Job job) {
    if (!s.jobs().markRunning(job.id())) {
      return;
    }
    LOG.log(Level.INFO, "job {0} started: {1} {2}", job.id(), job.kind(), describe(job));
    try {
      JobExecutor.Outcome outcome = s.executors().get(job.kind()).execute(job, cancelToken(s, job.id()));
      switch (outcome) {
        case Completed done -> {
          boolean success = "SUCCESS".equals(done.run().status());
          // one transaction: once the run is stored the job is terminal and never requeued
          long runId =
              s.results()
                  .saveAndFinish(
                      done.run(), done.measurements(), success ? JobStatus.DONE : JobStatus.FAILED);
          LOG.log(Level.INFO, "job {0} finished {1}, run {2}, log {3}", job.id(), done.run().status(), runId, done.run().logPath());
          afterRun(runId);
        }
        case Cancelled cancelled -> {
          s.jobs().requeue(job.id());
          LOG.log(Level.INFO, "job {0} cancelled and requeued", job.id());
        }
        case NotApplicable na -> {
          s.jobs().finish(job.id(), JobStatus.NOT_APPLICABLE);
          LOG.log(Level.INFO, "job {0} not applicable: {1}", job.id(), na.reason());
        }
      }
    } catch (RuntimeException e) {
      LOG.log(Level.ERROR, "job " + job.id() + " failed: " + e, e);
      giveBack(s, job);
    }
  }

  /** Infrastructure failure: retry later, but not forever. */
  private void giveBack(Services s, Job job) {
    try {
      if (job.attempts() + 1 >= MAX_ATTEMPTS) {
        s.jobs().finish(job.id(), JobStatus.FAILED);
      } else {
        s.jobs().requeue(job.id());
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "could not release job " + job.id() + ": " + e);
      staleJobsPossible = true;
    }
  }

  private void afterRun(long runId) {
    for (AfterRunHook hook : afterRunHooks) {
      try {
        hook.afterRun(runId);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "after-run hook failed for run " + runId + ": " + e, e);
      }
    }
  }

  /**
   * Cancelled once the owner is active or a cancel was requested; latches. Evaluated at most every
   * half activity-check interval, because checkout operations poll the token far more often. Also
   * records an activity sample from the probed idle time at most once per poll interval, so the
   * history has no gap while a job runs (the tick recorded one just before the job started).
   */
  private CancellationToken cancelToken(Services s, long jobId) {
    Duration minGap = config.idle().activityCheckInterval().dividedBy(2);
    Duration sampleEvery = config.idle().pollInterval();
    Instant jobStart = clock.instant();
    return new CancellationToken() {
      private boolean cancelled;
      private Instant lastCheck;
      private Instant lastSample = jobStart;

      @Override
      public boolean isCancelled() {
        if (cancelled) {
          return true;
        }
        try {
          Instant now = clock.instant();
          if (lastCheck != null && now.isBefore(lastCheck.plus(minGap))) {
            return false;
          }
          lastCheck = now;
          IdleGate.ActivityCheck check = s.gate().checkActivity();
          recordDuringJob(now, check);
          if (check.active()) {
            LOG.log(Level.INFO, "job {0}: user active, cancelling", jobId);
            cancelled = true;
          } else if (s.jobs().cancelRequested(jobId)) {
            LOG.log(Level.INFO, "job {0}: cancel requested", jobId);
            cancelled = true;
          }
        } catch (RuntimeException e) {
          // must never escape the runner's wait loop and orphan the build's process tree
          LOG.log(Level.WARNING, "job " + jobId + ": cancellation check failed, continuing: " + e);
        }
        return cancelled;
      }

      /** Never throws: a failed sample must not influence cancellation. */
      private void recordDuringJob(Instant now, IdleGate.ActivityCheck check) {
        if (check.userIdle().isEmpty() || now.isBefore(lastSample.plus(sampleEvery))) {
          return;
        }
        lastSample = now;
        try {
          s.history().record(now, check.userIdle().get(), s.probe().load1());
        } catch (RuntimeException e) {
          LOG.log(Level.WARNING, "job " + jobId + ": could not record activity sample: " + e);
        }
      }
    };
  }

  private static String describe(Job job) {
    StringBuilder sb = new StringBuilder();
    for (String part : new String[] {job.branch(), job.commitSha(), job.benchmark(), job.db()}) {
      if (part != null) {
        sb.append(sb.isEmpty() ? "" : " ").append(part);
      }
    }
    return sb.toString();
  }
}
