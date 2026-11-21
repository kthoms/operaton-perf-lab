package io.github.kthoms.perflab.cli;

import io.github.kthoms.perflab.analysis.RegressionDetector;
import io.github.kthoms.perflab.backup.Backup;
import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.daemon.Daemon;
import io.github.kthoms.perflab.db.Database;
import io.github.kthoms.perflab.env.FingerprintCollector;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.git.OperatonCheckout;
import io.github.kthoms.perflab.idle.ActivityHistory;
import io.github.kthoms.perflab.idle.ActivityProbe;
import io.github.kthoms.perflab.idle.IdleGate;
import io.github.kthoms.perflab.idle.MacActivityProbe;
import io.github.kthoms.perflab.jobs.BenchmarkJobExecutor;
import io.github.kthoms.perflab.jobs.BuildJobExecutor;
import io.github.kthoms.perflab.jobs.CommitResolver;
import io.github.kthoms.perflab.jobs.DurationEstimator;
import io.github.kthoms.perflab.jobs.JobExecutor;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
import io.github.kthoms.perflab.jobs.Scheduler;
import io.github.kthoms.perflab.results.ResultRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/** Builds the real daemon from its collaborators. */
final class DaemonWiring {

  /**
   * The parts a test replaces: the executors (and with them checkout, git and Maven), the activity
   * probe, the clock and the sleeper. Everything else is wired as in production.
   */
  record Overrides(
      Map<JobKind, JobExecutor> executors,
      ActivityProbe probe,
      Clock clock,
      Daemon.Sleeper sleeper) {}

  private DaemonWiring() {}

  static Daemon create(PerfLabConfig config, Path composeFile) {
    var runner = new ProcessRunner();
    var checkout = new OperatonCheckout(config.checkout(), config.upstreamUrl(), runner);
    var resolver = new CommitResolver(checkout, config.branches());
    var fingerprints = new FingerprintCollector();
    var buildExecutor =
        new BuildJobExecutor(config, checkout, resolver, runner, fingerprints, Clock.systemUTC());
    Path perfLabHome =
        config.perfLabHome() != null ? config.perfLabHome() : PerfLabCli.defaultPerfLabHome();
    var benchmarkExecutor =
        new BenchmarkJobExecutor(
            config, perfLabHome, checkout, resolver, runner, fingerprints, Clock.systemUTC());
    return create(
        config,
        composeFile,
        new Overrides(
            Map.of(JobKind.BUILD, buildExecutor, JobKind.BENCHMARK, benchmarkExecutor),
            new MacActivityProbe(config.idle().heavyProcessNames()),
            Clock.systemUTC(),
            d -> Thread.sleep(d)));
  }

  static Daemon create(PerfLabConfig config, Path composeFile, Overrides parts) {
    var dataSource = new AtomicReference<DataSource>();
    var database = new AtomicReference<Database>();
    // idle hooks only run after connect() succeeded, so the data source is set by then
    var backup = new Backup(config, composeFile, dataSource::get);

    return new Daemon(
        config,
        () -> {
          Database db =
              Database.connect(
                  config.resultsDb().jdbcUrl(),
                  config.resultsDb().user(),
                  config.resultsDb().password());
          dataSource.set(db.dataSource());
          database.set(db);
          var jobs = new JobRepository(db.dataSource());
          var results = new ResultRepository(db.dataSource());
          var probe = parts.probe();
          var history = new ActivityHistory(db, config.idle().coldStartWindow());
          return new Daemon.Services(
              jobs,
              results,
              probe,
              history,
              new IdleGate(probe, history, config.idle()),
              new DurationEstimator(results),
              new Scheduler(jobs, results, config),
              parts.executors());
        },
        // after-run hooks only run after connect() succeeded, so the database is set by then
        List.of(runId -> new RegressionDetector(database.get()).analyze(runId)),
        List.of(backup),
        parts.clock(),
        parts.sleeper());
  }
}
