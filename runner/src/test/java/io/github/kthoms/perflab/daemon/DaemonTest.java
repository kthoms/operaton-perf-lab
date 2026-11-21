package io.github.kthoms.perflab.daemon;

import static io.github.kthoms.perflab.jobs.BuildFakes.MAIN_SHA;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.env.FingerprintCollector;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.idle.ActivityHistory;
import io.github.kthoms.perflab.idle.ActivityProbe;
import io.github.kthoms.perflab.idle.IdleGate;
import io.github.kthoms.perflab.jobs.BuildFakes;
import io.github.kthoms.perflab.jobs.BuildFakes.FakeCheckout;
import io.github.kthoms.perflab.jobs.BuildFakes.FakeRunner;
import io.github.kthoms.perflab.jobs.BuildFakes.MutableClock;
import io.github.kthoms.perflab.jobs.BuildJobExecutor;
import io.github.kthoms.perflab.jobs.CommitResolver;
import io.github.kthoms.perflab.jobs.DurationEstimator;
import io.github.kthoms.perflab.jobs.Job;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
import io.github.kthoms.perflab.jobs.JobStatus;
import io.github.kthoms.perflab.jobs.Scheduler;
import io.github.kthoms.perflab.results.Measurement;
import io.github.kthoms.perflab.results.ResultRepository;
import io.github.kthoms.perflab.results.RunRecord;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonTest {

  private static final Instant T0 = Instant.parse("2026-11-21T02:00:00Z");

  @TempDir Path dataDir;

  MutableClock clock;
  FakeProbe probe;
  FakeJobs jobs;
  FakeResults results;
  FakeCheckout checkout;
  FakeRunner runner;
  List<Duration> sleeps;
  List<Long> afterRunIds;
  List<Instant> idleHookCalls;
  Duration predictedIdle = Duration.ofHours(8);
  RuntimeException recordFailure;
  final List<Instant> sampleTimes = new ArrayList<>();
  final List<LogRecord> daemonLog = new ArrayList<>();
  final Logger daemonLogger = Logger.getLogger(Daemon.class.getName());
  final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord r) {
          daemonLog.add(r);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void setUp() {
    BuildFakes.quietLogs();
    daemonLogger.setLevel(Level.ALL);
    daemonLogger.setUseParentHandlers(false);
    daemonLogger.addHandler(capture);
    clock = new MutableClock(T0);
    probe = new FakeProbe();
    jobs = new FakeJobs(clock::instant);
    results = new FakeResults(jobs);
    runner = new FakeRunner();
    sleeps = new ArrayList<>();
    afterRunIds = new ArrayList<>();
    idleHookCalls = new ArrayList<>();
  }

  @AfterEach
  void restoreLogging() {
    daemonLogger.removeHandler(capture);
    daemonLogger.setUseParentHandlers(true);
    daemonLogger.setLevel(null);
  }

  private List<LogRecord> logged(Level level) {
    return daemonLog.stream().filter(r -> r.getLevel().equals(level)).toList();
  }

  private Daemon daemon(PerfLabConfig config) {
    return daemon(config, () -> services(config));
  }

  private Daemon daemon(PerfLabConfig config, java.util.function.Supplier<Daemon.Services> connect) {
    AfterRunHook failing =
        runId -> {
          throw new IllegalStateException("hook broke");
        };
    AfterRunHook recording = afterRunIds::add;
    IdleHook idle = idleHookCalls::add;
    return new Daemon(
        config, connect, List.of(failing, recording), List.of(idle), clock, sleeps::add);
  }

  private Daemon.Services services(PerfLabConfig config) {
    checkout = new FakeCheckout(config.checkout());
    FakeHistory history = new FakeHistory();
    IdleGate gate = new IdleGate(probe, history, config.idle());
    BuildJobExecutor build =
        new BuildJobExecutor(
            config,
            checkout,
            new CommitResolver(checkout, config.branches()),
            runner,
            new FingerprintCollector(
                cmd -> {
                  throw new IllegalStateException("no probes in tests");
                }),
            clock);
    return new Daemon.Services(
        jobs,
        results,
        probe,
        history,
        gate,
        new DurationEstimator(results),
        new Scheduler(jobs, results, config),
        Map.of(JobKind.BUILD, build));
  }

  private PerfLabConfig mainOnly(Path dir) {
    PerfLabConfig c = BuildFakes.config(dir);
    return new PerfLabConfig(
        c.dataDir(),
        c.backupDir(),
        c.backupRetentionDays(),
        c.upstreamUrl(),
        c.resultsDb(),
        c.branches().subList(0, 1),
        c.benchmarks(),
        c.idle());
  }

  @Test
  void failedBuildStoredWithoutMeasurementsAndNotRequeued() {
    PerfLabConfig config = mainOnly(dataDir);
    runner.script = (token, every) -> new ProcessRunner.Result(1, false, Duration.ofMinutes(5));
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);

    daemon.tick(s);

    assertThat(results.saved).hasSize(1);
    RunRecord run = results.saved.getFirst().run();
    assertThat(run.status()).isEqualTo("FAILED");
    assertThat(run.commitSha()).isEqualTo(MAIN_SHA);
    assertThat(run.logPath()).isEqualTo(config.logs().resolve("1-" + MAIN_SHA + ".log"));
    assertThat(results.saved.getFirst().measurements()).isEmpty();
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.FAILED);
    assertThat(afterRunIds).containsExactly(1L);

    // next tick: the job is not retried and the series is not rescheduled before 20 h
    clock.advance(Duration.ofMinutes(10));
    daemon.tick(s);

    assertThat(jobs.all).hasSize(1);
    assertThat(runner.commands).hasSize(1);
    assertThat(results.saved).hasSize(1);
  }

  @Test
  void successfulBuildFinishesDoneAndHookFailureIsNotFatal() {
    PerfLabConfig config = mainOnly(dataDir);
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.DONE);
    assertThat(results.saved.getFirst().run().status()).isEqualTo("SUCCESS");
    assertThat(results.saved.getFirst().measurements()).isNotEmpty();
    assertThat(afterRunIds).containsExactly(1L);
    assertThat(idleHookCalls).hasSize(1);
  }

  @Test
  void userActivityCancelsAndRequeues() {
    PerfLabConfig config = mainOnly(dataDir);
    AtomicInteger checks = new AtomicInteger();
    runner.script =
        (token, every) -> {
          while (!token.isCancelled()) {
            checks.incrementAndGet();
            clock.advance(every);
            if (checks.get() == 3) {
              probe.idle = Duration.ofSeconds(2); // owner touches the keyboard
            }
          }
          return new ProcessRunner.Result(143, true, Duration.ofMinutes(2));
        };
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(checks.get()).isEqualTo(3);
    assertThat(results.saved).isEmpty();
    FakeJobs.Row job = jobs.get(1);
    assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(job.attempts()).as("a user cancellation is not an attempt").isZero();
    assertThat(afterRunIds).isEmpty();
  }

  @Test
  void repeatedCancellationsNeverFailTheJob() {
    PerfLabConfig config = mainOnly(dataDir);
    runner.script =
        (token, every) -> {
          jobs.requestCancel();
          clock.advance(every);
          token.isCancelled();
          return new ProcessRunner.Result(143, true, Duration.ofMinutes(1));
        };
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);

    for (int i = 0; i < Daemon.MAX_ATTEMPTS + 2; i++) {
      daemon.tick(s);
    }

    assertThat(runner.commands).hasSize(Daemon.MAX_ATTEMPTS + 2);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(jobs.get(1).attempts()).isZero();
  }

  @Test
  void cancelRequestCancelsAndRequeues() {
    PerfLabConfig config = mainOnly(dataDir);
    runner.script =
        (token, every) -> {
          jobs.requestCancel();
          clock.advance(every);
          while (!token.isCancelled()) {
            clock.advance(every);
          }
          return new ProcessRunner.Result(143, true, Duration.ofMinutes(1));
        };
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(results.saved).isEmpty();
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(jobs.get(1).cancelRequested).isFalse();
  }

  @Test
  void unreachableCommitIsFinishedNotApplicable() {
    PerfLabConfig config = mainOnly(dataDir);
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);
    jobs.enqueue(JobKind.BUILD, null, "c".repeat(40), null, null, Job.PRIORITY_MANUAL);

    daemon.tick(s);

    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.NOT_APPLICABLE);
    assertThat(results.saved).isEmpty();
  }

  @Test
  void manualJobIgnoresThePredictionButScheduledJobsWait() {
    PerfLabConfig config = mainOnly(dataDir);
    predictedIdle = Duration.ZERO; // cold start: nothing known yet
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);

    daemon.tick(s);
    assertThat(jobs.get(1).status()).as("scheduled build").isEqualTo(JobStatus.QUEUED);
    assertThat(runner.commands).isEmpty();

    long manual = jobs.enqueue(JobKind.BUILD, null, MAIN_SHA, null, null, Job.PRIORITY_MANUAL);
    daemon.tick(s);
    assertThat(jobs.get(manual).status()).isEqualTo(JobStatus.DONE);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
  }

  @Test
  void manualJobStillWaitsForAnIdleUser() {
    PerfLabConfig config = mainOnly(dataDir);
    predictedIdle = Duration.ZERO;
    probe.idle = Duration.ofMinutes(1);
    jobs.enqueue(JobKind.BUILD, null, MAIN_SHA, null, null, Job.PRIORITY_MANUAL);
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void runningJobRecordsActivitySamplesAtMostOncePerPollInterval() {
    PerfLabConfig config = mainOnly(dataDir); // poll 60s, activity check 30s
    runner.script =
        (token, every) -> {
          for (int i = 0; i < 10; i++) {
            clock.advance(every);
            token.isCancelled();
            token.isCancelled(); // throttled, records nothing
          }
          return new ProcessRunner.Result(0, false, Duration.ofMinutes(5));
        };
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.DONE);
    assertThat(sampleTimes)
        .containsExactly(
            T0, T0.plusSeconds(60), T0.plusSeconds(120), T0.plusSeconds(180), T0.plusSeconds(240),
            T0.plusSeconds(300));
  }

  @Test
  void failingSampleRecordingNeverAffectsCancellation() {
    PerfLabConfig config = mainOnly(dataDir);
    recordFailure = new IllegalStateException("activity_sample insert failed");
    List<Boolean> observed = new ArrayList<>();
    runner.script =
        (token, every) -> {
          clock.advance(Duration.ofMinutes(2));
          observed.add(token.isCancelled());
          probe.idle = Duration.ofSeconds(2); // owner is back
          clock.advance(Duration.ofMinutes(2));
          observed.add(token.isCancelled());
          return new ProcessRunner.Result(143, true, Duration.ofMinutes(4));
        };
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(observed).containsExactly(false, true);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
  }

  @Test
  void busyUserStartsNoJob() {
    PerfLabConfig config = mainOnly(dataDir);
    probe.idle = Duration.ofMinutes(1);
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(runner.commands).isEmpty();
    assertThat(probe.samples).isEqualTo(1);
  }

  @Test
  void missingDataDirStartsNoJob() {
    PerfLabConfig config = mainOnly(dataDir.resolve("unplugged-nvme"));
    jobs.enqueue(JobKind.BUILD, "main", null, null, null, Job.PRIORITY_MANUAL);
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(jobs.all).hasSize(1); // the scheduler did not run either
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(jobs.get(1).attempts()).isZero();
    assertThat(runner.commands).isEmpty();
    assertThat(idleHookCalls).isEmpty(); // backupDir may be on the missing volume
    assertThat(logged(Level.WARNING))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.getMessage())
                    .contains("Data dir does not exist", "unplugged-nvme", "starting no job"));
  }

  @Test
  void dbUnavailableRetriesWithBackoff() {
    PerfLabConfig config = mainOnly(dataDir);
    AtomicInteger attempts = new AtomicInteger();
    FakeJobs.Row stale = jobs.row(jobs.enqueue(JobKind.BUILD, "main", null, null, null, 10));
    stale.status = JobStatus.RUNNING;
    probe.idle = Duration.ofMinutes(1); // owner busy: the first tick must not start the job
    List<Duration> sleepsSeen = sleeps;
    Daemon daemon =
        new Daemon(
            config,
            () -> {
              if (attempts.incrementAndGet() <= 8) {
                throw new IllegalStateException("Connection refused");
              }
              return services(config);
            },
            List.of(),
            List.of(),
            clock,
            d -> {
              sleepsSeen.add(d);
              if (d.equals(config.idle().pollInterval())) {
                throw new InterruptedException("stop after the first tick");
              }
            });

    daemon.runForever();

    assertThat(sleeps)
        .containsExactly(
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            Duration.ofSeconds(40),
            Duration.ofSeconds(80),
            Duration.ofSeconds(160),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5),
            config.idle().pollInterval());
    assertThat(attempts.get()).isEqualTo(9);
    assertThat(jobs.requeueStaleCalls).isEqualTo(1);
    assertThat(stale.status()).isEqualTo(JobStatus.QUEUED); // RF3: left RUNNING, requeued on start
    assertThat(runner.commands).isEmpty();
    assertThat(Thread.interrupted()).isTrue(); // interrupt status restored, then cleared here
  }

  @Test
  void giveBackRequeuesBelowMaxAttemptsAndFailsAtCap() {
    PerfLabConfig config = mainOnly(dataDir);
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);
    checkout.checkoutFailure = new IllegalStateException("git checkout failed with 128");

    daemon.tick(s);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(jobs.get(1).attempts()).isEqualTo(1);

    daemon.tick(s);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(jobs.get(1).attempts()).isEqualTo(2);

    daemon.tick(s);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.FAILED);
    assertThat(jobs.get(1).attempts()).isEqualTo(Daemon.MAX_ATTEMPTS);
    assertThat(results.saved).isEmpty();
  }

  @Test
  void permanentInfraFailureDoesNotReenqueueWithin20h() {
    PerfLabConfig config = mainOnly(dataDir);
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);
    checkout.checkoutFailure = new IllegalStateException("git checkout failed with 128");
    for (int i = 0; i < Daemon.MAX_ATTEMPTS; i++) {
      daemon.tick(s);
    }
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.FAILED);

    clock.advance(Duration.ofHours(1));
    daemon.tick(s);
    assertThat(jobs.all).hasSize(1);

    clock.advance(Duration.ofHours(20));
    daemon.tick(s);
    assertThat(jobs.all).hasSize(2); // the next daily job tries again
  }

  @Test
  void notApplicableDoesNotReenqueueWithin20h() {
    PerfLabConfig config = mainOnly(dataDir);
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);
    checkout.branchTips.remove("main"); // branch vanished upstream

    daemon.tick(s);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.NOT_APPLICABLE);

    clock.advance(Duration.ofHours(1));
    daemon.tick(s);
    assertThat(jobs.all).hasSize(1);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void extractorFailureStoresFailedRunWithoutRebuild() {
    PerfLabConfig config = mainOnly(dataDir);
    runner.script =
        (token, every) -> {
          try {
            java.nio.file.Files.delete(runner.logs.getLast());
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          return new ProcessRunner.Result(0, false, Duration.ofMinutes(90));
        };
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);

    daemon.tick(s);
    clock.advance(Duration.ofMinutes(10));
    daemon.tick(s);

    assertThat(results.saved).singleElement().satisfies(saved -> {
      assertThat(saved.run().status()).isEqualTo("FAILED");
      assertThat(saved.measurements()).isEmpty();
    });
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.FAILED);
    assertThat(jobs.all).hasSize(1);
    assertThat(runner.commands).hasSize(1);
  }

  @Test
  void failedStorageStoresNothingAndGivesTheJobBack() {
    PerfLabConfig config = mainOnly(dataDir);
    results.saveFailure = new IllegalStateException("connection lost");
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(results.saved).isEmpty();
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(afterRunIds).isEmpty();
  }

  @Test
  void cancellationCheckFailureIsNotCancellation() {
    PerfLabConfig config = mainOnly(dataDir);
    List<Boolean> observed = new ArrayList<>();
    runner.script =
        (token, every) -> {
          clock.advance(every); // past the throttle, so the failing check really runs
          probe.idleProbeThrows = true;
          observed.add(token.isCancelled());
          probe.idleProbeThrows = false;
          probe.idle = Duration.ofSeconds(2);
          clock.advance(every);
          observed.add(token.isCancelled());
          return new ProcessRunner.Result(143, true, Duration.ofMinutes(1));
        };
    Daemon daemon = daemon(config);

    daemon.tick(services(config));

    assertThat(observed).containsExactly(false, true);
    assertThat(jobs.get(1).status()).isEqualTo(JobStatus.QUEUED);
    assertThat(logged(Level.WARNING))
        .anySatisfy(r -> assertThat(r.getMessage()).contains("cancellation check failed"));
  }

  @Test
  void tickFailureLogsStackTraceOnlyOnStateChange() {
    PerfLabConfig config = mainOnly(dataDir);
    Daemon daemon = daemon(config);
    Daemon.Services s = services(config);
    jobs.hasOpenFailure = new IllegalStateException("results DB gone");

    daemon.tick(s);
    daemon.tick(s);
    jobs.hasOpenFailure = null;
    daemon.tick(s);

    List<LogRecord> warnings =
        logged(Level.WARNING).stream().filter(r -> r.getMessage().startsWith("tick")).toList();
    assertThat(warnings).hasSize(2);
    assertThat(warnings.get(0).getThrown()).isNotNull();
    assertThat(warnings.get(1).getThrown()).isNull();
    assertThat(warnings.get(1).getMessage()).contains("results DB gone");
    assertThat(logged(Level.INFO)).anySatisfy(r -> assertThat(r.getMessage()).contains("recovered"));
  }

  // ---- fakes ----

  static class FakeProbe implements ActivityProbe {
    Duration idle = Duration.ofHours(1);
    boolean idleProbeThrows;
    int samples;

    @Override
    public Optional<Duration> userIdle() {
      if (idleProbeThrows) {
        throw new IllegalStateException("probe exploded");
      }
      return Optional.of(idle);
    }

    @Override
    public double load1() {
      return 0.5;
    }

    @Override
    public boolean onAcPower() {
      return true;
    }

    @Override
    public List<String> heavyForeignProcesses() {
      return List.of();
    }
  }

  class FakeHistory extends ActivityHistory {
    FakeHistory() {
      super(null);
    }

    @Override
    public void record(Instant ts, Duration userIdle, double load1) {
      if (recordFailure != null) {
        throw recordFailure;
      }
      probe.samples++;
      sampleTimes.add(ts);
    }

    @Override
    public Duration predictedIdle(Instant now) {
      return predictedIdle;
    }
  }

  static class FakeJobs extends JobRepository {
    static final class Row {
      final long id;
      final JobKind kind;
      final String branch;
      final String commitSha;
      final String benchmark;
      final String db;
      final int priority;
      JobStatus status = JobStatus.QUEUED;
      Instant finishedAt;
      int attempts;
      boolean cancelRequested;

      Row(long id, JobKind kind, String branch, String sha, String benchmark, String db, int prio) {
        this.id = id;
        this.kind = kind;
        this.branch = branch;
        this.commitSha = sha;
        this.benchmark = benchmark;
        this.db = db;
        this.priority = prio;
      }

      JobStatus status() {
        return status;
      }

      int attempts() {
        return attempts;
      }

      Job toJob() {
        return new Job(id, kind, branch, commitSha, benchmark, db, priority, status, attempts);
      }
    }

    final List<Row> all = new ArrayList<>();
    final java.util.function.Supplier<Instant> now;
    int requeueStaleCalls;
    RuntimeException hasOpenFailure;

    FakeJobs(java.util.function.Supplier<Instant> now) {
      super(null);
      this.now = now;
    }

    Row get(long id) {
      return row(id);
    }

    Row row(long id) {
      return all.stream().filter(r -> r.id == id).findFirst().orElseThrow();
    }

    @Override
    public long enqueue(
        JobKind kind, String branch, String commitSha, String benchmark, String db, int priority) {
      Row r = new Row(all.size() + 1, kind, branch, commitSha, benchmark, db, priority);
      all.add(r);
      return r.id;
    }

    @Override
    public List<Job> queued() {
      return all.stream()
          .filter(r -> r.status == JobStatus.QUEUED)
          .sorted(java.util.Comparator.comparingInt((Row r) -> r.priority).thenComparingLong(r -> r.id))
          .map(Row::toJob)
          .toList();
    }

    @Override
    public boolean markRunning(long id) {
      Row r = row(id);
      if (r.status != JobStatus.QUEUED) {
        return false;
      }
      r.status = JobStatus.RUNNING;
      r.attempts++;
      return true;
    }

    @Override
    public void finish(long id, JobStatus status) {
      row(id).status = status;
      row(id).finishedAt = now.get();
    }

    @Override
    public Optional<Instant> lastFinished(JobKind kind, String branch, String benchmark, String db) {
      return all.stream()
          .filter(r -> r.finishedAt != null && sameSeries(r, kind, branch, benchmark, db))
          .map(r -> r.finishedAt)
          .max(Instant::compareTo);
    }

    /** Like the real repository: only scheduled jobs (no commit) belong to a series. */
    private static boolean sameSeries(Row r, JobKind kind, String branch, String benchmark, String db) {
      return r.commitSha == null
          && r.kind == kind
          && java.util.Objects.equals(r.branch, branch)
          && java.util.Objects.equals(r.benchmark, benchmark)
          && java.util.Objects.equals(r.db, db);
    }

    @Override
    public void requeue(long id) {
      Row r = row(id);
      if (r.status == JobStatus.RUNNING) {
        r.status = JobStatus.QUEUED;
        r.cancelRequested = false;
      }
    }

    @Override
    public void requeueCancelled(long id) {
      Row r = row(id);
      if (r.status == JobStatus.RUNNING) {
        r.status = JobStatus.QUEUED;
        r.cancelRequested = false;
        r.attempts = Math.max(0, r.attempts - 1);
      }
    }

    @Override
    public int requeueStale() {
      requeueStaleCalls++;
      int n = 0;
      for (Row r : all) {
        if (r.status == JobStatus.RUNNING) {
          r.status = JobStatus.QUEUED;
          r.cancelRequested = false;
          n++;
        }
      }
      return n;
    }

    @Override
    public void requestCancel() {
      all.stream().filter(r -> r.status == JobStatus.RUNNING).forEach(r -> r.cancelRequested = true);
    }

    @Override
    public boolean cancelRequested(long id) {
      return row(id).cancelRequested;
    }

    @Override
    public boolean hasOpen(JobKind kind, String branch, String benchmark, String db) {
      if (hasOpenFailure != null) {
        throw hasOpenFailure;
      }
      return all.stream()
          .anyMatch(
              r ->
                  (r.status == JobStatus.QUEUED || r.status == JobStatus.RUNNING)
                      && sameSeries(r, kind, branch, benchmark, db));
    }
  }

  record Saved(RunRecord run, List<Measurement> measurements) {}

  static class FakeResults extends ResultRepository {
    final List<Saved> saved = new ArrayList<>();
    final FakeJobs jobs;
    RuntimeException saveFailure;

    FakeResults(FakeJobs jobs) {
      super(null);
      this.jobs = jobs;
    }

    @Override
    public long save(RunRecord run, List<Measurement> measurements) {
      throw new UnsupportedOperationException("the daemon must store runs with saveAndFinish");
    }

    /** Atomic like the real one: either run stored and job finished, or nothing. */
    @Override
    public long saveAndFinish(RunRecord run, List<Measurement> measurements, JobStatus jobStatus) {
      if (saveFailure != null) {
        throw saveFailure;
      }
      saved.add(new Saved(run, List.copyOf(measurements)));
      jobs.finish(run.jobId(), jobStatus);
      return saved.size();
    }

    @Override
    public List<Duration> recentDurations(
        JobKind kind, String branch, String benchmark, String db, int limit) {
      return List.of();
    }

    @Override
    public Optional<Instant> lastRunStart(JobKind kind, String branch, String benchmark, String db) {
      return saved.stream()
          .map(Saved::run)
          .filter(
              r ->
                  r.kind() == kind
                      && r.source().equals(RunRecord.SOURCE_BRANCH)
                      && r.branch().equals(branch)
                      && java.util.Objects.equals(r.benchmark(), benchmark)
                      && java.util.Objects.equals(r.db(), db))
          .map(RunRecord::startedAt)
          .max(Instant::compareTo);
    }
  }
}
