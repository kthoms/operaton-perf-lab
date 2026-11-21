package io.github.kthoms.perflab.jobs;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.IdleConfig;
import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.exec.ProcessRunner;
import io.github.kthoms.perflab.git.OperatonCheckout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fakes for checkout, process runner and clock, shared by executor and daemon tests. */
public final class BuildFakes {

  public static final String MAIN_SHA = "a".repeat(40);
  public static final String OTHER_SHA = "b".repeat(40);

  /** Held strongly so the level survives GC; the daemon's INFO/WARNING lines are expected here. */
  private static final java.util.logging.Logger PERFLAB_LOGGER =
      java.util.logging.Logger.getLogger("io.github.kthoms.perflab");

  private BuildFakes() {}

  public static void quietLogs() {
    PERFLAB_LOGGER.setLevel(java.util.logging.Level.SEVERE);
  }

  public static PerfLabConfig config(Path dataDir) {
    return new PerfLabConfig(
        dataDir,
        dataDir.resolve("backup"),
        14,
        "https://example.invalid/operaton.git",
        new PerfLabConfig.Db("jdbc:postgresql://localhost/x", "u", "p"),
        List.of(
            new BranchConfig("main", Path.of("/opt/jdk21"), List.of("-Pfast", "clean", "install")),
            new BranchConfig("1.0", Path.of("/opt/jdk17"), List.of("clean", "install"))),
        List.of(),
        new IdleConfig(
            Duration.ofMinutes(15), 4.0, List.of("java"), Duration.ofSeconds(60), Duration.ofSeconds(30)));
  }

  /** Checkout that never runs git; branch tips and containment are configured per test. */
  public static class FakeCheckout extends OperatonCheckout {
    public final Map<String, String> branchTips = new java.util.HashMap<>(Map.of("main", MAIN_SHA, "1.0", OTHER_SHA));
    public final Set<String> reachable = new HashSet<>(Set.of(MAIN_SHA, OTHER_SHA));
    /** Entries "branch:sha" meaning origin/branch contains sha. */
    public final Set<String> contains = new HashSet<>(Set.of("main:" + MAIN_SHA, "1.0:" + OTHER_SHA));
    public final List<String> checkedOut = new ArrayList<>();
    public final List<CancellationToken> tokens = new ArrayList<>();
    public RuntimeException checkoutFailure;
    private final Path dir;

    public FakeCheckout(Path dir) {
      super(dir, "unused", new ProcessRunner());
      this.dir = dir;
    }

    @Override
    public void ensureCloned(CancellationToken token) {
      tokens.add(token);
      try {
        Files.createDirectories(dir);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void fetch(CancellationToken token) {
      tokens.add(token);
    }

    @Override
    public String resolveBranch(String branch) {
      String sha = branchTips.get(branch);
      if (sha == null) {
        throw new IllegalStateException("git rev-parse origin/" + branch + " failed");
      }
      return sha;
    }

    @Override
    public boolean isReachableFromUpstream(String sha) {
      return reachable.stream().anyMatch(s -> s.startsWith(sha));
    }

    @Override
    public String resolveCommit(String sha) {
      return reachable.stream().filter(s -> s.startsWith(sha)).findFirst().orElseThrow();
    }

    @Override
    public boolean branchContains(String branch, String sha) {
      return contains.contains(branch + ":" + sha);
    }

    @Override
    public void checkout(String sha, CancellationToken token) {
      tokens.add(token);
      if (checkoutFailure != null) {
        throw checkoutFailure;
      }
      checkedOut.add(sha);
    }
  }

  /** What the fake build does while "running"; returns the process result. */
  @FunctionalInterface
  public interface Script {
    ProcessRunner.Result run(CancellationToken token, Duration checkEvery) throws InterruptedException;
  }

  /**
   * Process runner that writes a reactor summary to the log and one Surefire report into the
   * work dir (even for failing builds, like a real Maven run that fails late), then runs a script.
   */
  public static class FakeRunner extends ProcessRunner {
    public final List<List<String>> commands = new ArrayList<>();
    public final List<Map<String, String>> envs = new ArrayList<>();
    public final List<Path> workDirs = new ArrayList<>();
    public final List<Path> logs = new ArrayList<>();
    public Script script = (token, every) -> new Result(0, false, Duration.ofMinutes(1));

    @Override
    public Result run(
        List<String> cmd,
        Path workDir,
        Map<String, String> env,
        Path logFile,
        CancellationToken token,
        Duration checkEvery)
        throws IOException, InterruptedException {
      commands.add(List.copyOf(cmd));
      envs.add(Map.copyOf(env));
      workDirs.add(workDir);
      logs.add(logFile);
      Files.createDirectories(logFile.getParent());
      Files.writeString(
          logFile,
          "[INFO] Operaton - Engine ................................ SUCCESS [01:02 min]\n"
              + "[INFO] Total time:  1:02 min\n",
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      Path reports = workDir.resolve("engine/target/surefire-reports");
      Files.createDirectories(reports);
      Files.writeString(
          reports.resolve("TEST-org.x.ATest.xml"),
          "<?xml version=\"1.0\"?><testsuite name=\"org.x.ATest\" time=\"2.5\" tests=\"3\"/>");
      return script.run(token, checkEvery);
    }
  }

  /** Clock that only moves when told to. */
  public static class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant start) {
      this.now = start;
    }

    public void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
