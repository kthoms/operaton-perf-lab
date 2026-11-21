package io.github.kthoms.perflab.jobs;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.env.FingerprintCollector;
import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.extract.ReactorSummaryExtractor;
import io.github.kthoms.perflab.extract.SurefireExtractor;
import io.github.kthoms.perflab.git.OperatonCheckout;
import io.github.kthoms.perflab.jobs.CommitResolver.Resolved;
import io.github.kthoms.perflab.jobs.CommitResolver.Unresolved;
import io.github.kthoms.perflab.results.Measurement;
import io.github.kthoms.perflab.results.RunRecord;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds one Operaton commit with the Maven wrapper. Exit 0 yields Surefire and reactor
 * measurements; any other exit yields a FAILED run without measurements.
 */
public class BuildJobExecutor implements JobExecutor {

  private static final System.Logger LOG = System.getLogger(BuildJobExecutor.class.getName());

  private final PerfLabConfig config;
  private final OperatonCheckout checkout;
  private final CommitResolver resolver;
  private final ProcessRunner runner;
  private final FingerprintCollector fingerprints;
  private final Clock clock;

  public BuildJobExecutor(
      PerfLabConfig config,
      OperatonCheckout checkout,
      CommitResolver resolver,
      ProcessRunner runner,
      FingerprintCollector fingerprints,
      Clock clock) {
    this.config = config;
    this.checkout = checkout;
    this.resolver = resolver;
    this.runner = runner;
    this.fingerprints = fingerprints;
    this.clock = clock;
  }

  @Override
  public Outcome execute(Job job, CancellationToken token) {
    try {
      return build(job, token);
    } catch (RuntimeException e) {
      if (token.isCancelled()) {
        return new Cancelled();
      }
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Cancelled();
    } catch (IOException e) {
      throw new UncheckedIOException("build of job " + job.id() + " failed to run", e);
    }
  }

  private Outcome build(Job job, CancellationToken token) throws IOException, InterruptedException {
    Instant started = clock.instant();
    checkout.ensureCloned(token);
    checkout.fetch(token);
    var resolution = resolver.resolve(job);
    if (resolution instanceof Unresolved(String reason)) {
      return new NotApplicable(reason);
    }
    Resolved resolved = (Resolved) resolution;
    String sha = resolved.sha();
    BranchConfig branch = resolved.branch();
    checkout.checkout(sha, token);
    if (token.isCancelled()) {
      return new Cancelled();
    }

    Fingerprint fingerprint = fingerprints.collect(branch, config);
    List<String> cmd = new ArrayList<>();
    cmd.add(config.checkout().resolve("mvnw").toString());
    cmd.addAll(branch.mavenArgs());
    cmd.add("-Dmaven.repo.local=" + config.mavenRepo());
    Path log = config.logs().resolve(job.id() + "-" + sha + ".log");
    Files.deleteIfExists(log); // the runner appends; a requeued job must not see its old output
    LOG.log(System.Logger.Level.INFO, "job {0}: building {1} ({2}), log {3}", job.id(), sha, branch.name(), log);

    ProcessRunner.Result result =
        runner.run(cmd, config.checkout(), env(branch), log, token, config.idle().activityCheckInterval());
    if (result.cancelled()) {
      return new Cancelled();
    }
    Instant finished = clock.instant();
    boolean success = result.exitCode() == 0;
    List<Measurement> measurements = new ArrayList<>();
    if (success) {
      measurements.addAll(SurefireExtractor.extract(config.checkout()));
      measurements.addAll(ReactorSummaryExtractor.extract(log));
    }
    RunRecord run =
        new RunRecord(
            job.id(),
            JobKind.BUILD,
            branch.name(),
            sha,
            null,
            null,
            success ? "SUCCESS" : "FAILED",
            started,
            finished,
            fingerprint,
            String.join(" ", cmd),
            log);
    return new Completed(run, measurements);
  }

  private static Map<String, String> env(BranchConfig branch) {
    String javaHome = branch.javaHome().toString();
    String path = System.getenv("PATH");
    String javaBin = branch.javaHome().resolve("bin").toString();
    return Map.of(
        "JAVA_HOME", javaHome,
        "PATH", path == null || path.isEmpty() ? javaBin : javaBin + File.pathSeparator + path);
  }
}
