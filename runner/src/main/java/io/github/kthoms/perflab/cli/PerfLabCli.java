package io.github.kthoms.perflab.cli;

import io.github.kthoms.perflab.backup.Backup;
import io.github.kthoms.perflab.config.ConfigLoader;
import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.db.Database;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.git.OperatonCheckout;
import io.github.kthoms.perflab.idle.ActivityHistory;
import io.github.kthoms.perflab.idle.IdleGate;
import io.github.kthoms.perflab.idle.MacActivityProbe;
import io.github.kthoms.perflab.jobs.DurationEstimator;
import io.github.kthoms.perflab.jobs.Job;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.jobs.JobRepository;
import io.github.kthoms.perflab.results.ResultRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Entry point of {@code perflab.jar}: daemon, enqueue, cancel, status and backup. */
@Command(name = "perflab", mixinStandardHelpOptions = true, description = "Operaton performance lab")
public class PerfLabCli implements Runnable {

  static final Path DEFAULT_CONFIG =
      Path.of(System.getProperty("user.home"), ".config", "perflab", "perflab.yml");
  static final Path DEFAULT_LOCK =
      Path.of(System.getProperty("user.home"), ".config", "perflab", "daemon.lock");
  private static final Pattern SHA = Pattern.compile("^[0-9a-f]{7,40}$");

  /** Seams for tests: how the job repository and the backup are obtained from the config. */
  record Wiring(Function<PerfLabConfig, JobRepository> jobs, Function<PerfLabConfig, Backup> backup) {}

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  public static void main(String[] args) {
    System.exit(commandLine(realWiring()).execute(args));
  }

  static CommandLine commandLine(Wiring wiring) {
    var cmd = new CommandLine(new PerfLabCli());
    cmd.addSubcommand(new DaemonCommand());
    cmd.addSubcommand(new EnqueueCommand(wiring));
    cmd.addSubcommand(new CancelCommand(wiring));
    cmd.addSubcommand(new StatusCommand());
    cmd.addSubcommand(new BackupCommand(wiring));
    cmd.setExecutionExceptionHandler(
        (e, c, parseResult) -> {
          String message = e.getMessage() != null ? e.getMessage() : e.toString();
          c.getErr().println("perflab: " + message);
          return 1;
        });
    return cmd;
  }

  private static Wiring realWiring() {
    return new Wiring(
        config -> new JobRepository(connect(config).dataSource()),
        config -> new Backup(config, defaultComposeFile(), () -> connect(config).dataSource()));
  }

  static Database connect(PerfLabConfig config) {
    return Database.connect(
        config.resultsDb().jdbcUrl(), config.resultsDb().user(), config.resultsDb().password());
  }

  /** {@code <repo>/infra/docker-compose.yml}, found relative to {@code <repo>/runner/target/perflab.jar}. */
  static Path defaultComposeFile() {
    return defaultPerfLabHome().resolve("infra").resolve("docker-compose.yml");
  }

  /** The perf-lab repository: {@code <repo>} of {@code <repo>/runner/target/perflab.jar}, else the working directory. */
  static Path defaultPerfLabHome() {
    try {
      Path location =
          Path.of(PerfLabCli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isRegularFile(location)) {
        Path repo = location.toAbsolutePath().getParent().getParent().getParent();
        if (repo != null) {
          return repo;
        }
      }
    } catch (Exception ignored) {
      // fall through to the working directory
    }
    return Path.of("").toAbsolutePath();
  }

  abstract static class Base implements Callable<Integer> {
    @Spec CommandSpec spec;

    @Option(names = "--config", description = "Config file (default: ${DEFAULT-VALUE})")
    Path config = DEFAULT_CONFIG;

    PerfLabConfig loadConfig() {
      return ConfigLoader.load(config);
    }
  }

  @Command(name = "daemon", description = "Run the scheduler loop in the foreground")
  static class DaemonCommand extends Base {
    @Option(names = "--compose-file", description = "docker-compose.yml of the results DB (backup)")
    Path composeFile = defaultComposeFile();

    @Option(names = "--lock-file", hidden = true)
    Path lockFile = DEFAULT_LOCK;

    @Override
    public Integer call() throws Exception {
      try (var ignored = SingleInstanceLock.acquire(lockFile)) {
        DaemonWiring.create(loadConfig(), composeFile).runForever();
        return 0;
      } catch (SingleInstanceLock.AlreadyRunningException e) {
        spec.commandLine().getErr().println("perflab: " + e.getMessage());
        return 1;
      }
    }
  }

  @Command(name = "enqueue", description = "Queue a manual job (priority 0)")
  static class EnqueueCommand extends Base {
    private final Wiring wiring;

    @Option(names = "--branch", description = "Build/benchmark the head of this branch")
    String branch;

    @Option(names = "--commit", description = "Build/benchmark this commit (hex sha)")
    String commit;

    @Option(names = "--benchmark", description = "Benchmark id; makes it a benchmark job (needs --db)")
    String benchmark;

    @Option(names = "--db", description = "Database of the benchmark job")
    String db;

    EnqueueCommand(Wiring wiring) {
      this.wiring = wiring;
    }

    @Override
    public Integer call() {
      if ((branch == null) == (commit == null)) {
        throw new ParameterException(
            spec.commandLine(), "exactly one of --branch or --commit is required");
      }
      if (commit != null && !SHA.matcher(commit).matches()) {
        throw new ParameterException(spec.commandLine(), "--commit must be a hex sha of 7 to 40 characters");
      }
      if ((benchmark == null) != (db == null)) {
        throw new ParameterException(spec.commandLine(), "--benchmark and --db must be given together");
      }
      JobKind kind = benchmark != null ? JobKind.BENCHMARK : JobKind.BUILD;
      long id = wiring.jobs().apply(loadConfig()).enqueue(kind, branch, commit, benchmark, db, Job.PRIORITY_MANUAL);
      spec.commandLine().getOut().println("Enqueued job " + id + " (" + kind + ", manual priority)");
      return 0;
    }
  }

  @Command(name = "cancel", description = "Cancel the running job; it is requeued")
  static class CancelCommand extends Base {
    private final Wiring wiring;

    CancelCommand(Wiring wiring) {
      this.wiring = wiring;
    }

    @Override
    public Integer call() {
      wiring.jobs().apply(loadConfig()).requestCancel();
      spec.commandLine().getOut().println("Cancel requested for the running job (if any)");
      return 0;
    }
  }

  @Command(name = "status", description = "Show running job, queue, last runs and the idle decision")
  static class StatusCommand extends Base {
    @Override
    public Integer call() {
      PerfLabConfig cfg = loadConfig();
      Database db = connect(cfg);
      var results = new ResultRepository(db.dataSource());
      var gate = new IdleGate(
          new MacActivityProbe(cfg.idle().heavyProcessNames()), new ActivityHistory(db), cfg.idle());
      var report = new StatusReport(
          cfg,
          db.dataSource(),
          new JobRepository(db.dataSource()),
          new DurationEstimator(results),
          gate,
          new OperatonCheckout(cfg.checkout(), cfg.upstreamUrl(), new ProcessRunner()));
      spec.commandLine().getOut().print(report.render(Instant.now()));
      spec.commandLine().getOut().flush();
      return 0;
    }
  }

  @Command(name = "backup", description = "Dump the results database now")
  static class BackupCommand extends Base {
    private final Wiring wiring;

    @Option(names = "--compose-file", description = "docker-compose.yml of the results DB")
    Path composeFile;

    BackupCommand(Wiring wiring) {
      this.wiring = wiring;
    }

    @Override
    public Integer call() {
      PerfLabConfig cfg = loadConfig();
      Backup backup =
          composeFile == null
              ? wiring.backup().apply(cfg)
              : new Backup(cfg, composeFile, () -> connect(cfg).dataSource());
      spec.commandLine().getOut().println("Backup written: " + backup.run());
      return 0;
    }
  }
}
