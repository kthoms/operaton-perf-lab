package io.github.kthoms.perflab.bench;

import io.github.kthoms.perflab.bench.BenchmarkResult.Metric;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The startup race of the BPMN engine comparison, for the embedded engine: every iteration forks
 * a fresh JVM ({@link StartupProbe}, JVM defaults, same classpath) on an empty database and
 * measures the wall time from the fork until the engine has its schema, both scenarios deployed
 * and one verified scenario A instance done. Operations: {@code jvmBoot} (fork to main, plus the
 * remainder not covered by the others), {@code buildEngine} (incl. schema creation), {@code
 * deploy}, {@code firstInstance}. Metrics: idle heap after GC and resident set size of the ready
 * JVM (the original's idle RAM).
 */
public class EngineStartupBenchmark {

  public static final String ID = "engine-startup";
  static final List<String> OPERATIONS = List.of("jvmBoot", "buildEngine", "deploy", "firstInstance");
  static final Duration READY_TIMEOUT = Duration.ofMinutes(5);

  public record Options(
      String database, String jdbcUrl, String jdbcUser, String jdbcPassword, int warmup, int iterations) {

    static Options fromSystemProperties() {
      return new Options(
          Scenarios.property("benchmark.database", "h2"),
          Scenarios.property("benchmark.jdbcUrl", "jdbc:h2:mem:perflab-bench;DB_CLOSE_DELAY=-1"),
          Scenarios.property("benchmark.jdbcUser", "sa"),
          System.getProperty("benchmark.jdbcPassword", ""),
          Scenarios.intProperty("benchmark.warmup", 1),
          Scenarios.intProperty("benchmark.iterations", 10));
    }

    Map<String, Integer> params() {
      Map<String, Integer> params = new LinkedHashMap<>();
      params.put("warmup", warmup);
      params.put("iterations", iterations);
      return params;
    }
  }

  /** One started child JVM. */
  record Start(double wallMs, double jvmToMainMs, double buildEngineMs, double deployMs,
      double firstInstanceMs, long heapBytes, OptionalDouble rssMiB) {}

  public BenchmarkResult run(Options options) throws IOException, InterruptedException {
    for (int i = 0; i < options.warmup(); i++) {
      start(options);
    }
    Map<String, List<Double>> operationsMs = new LinkedHashMap<>();
    OPERATIONS.forEach(op -> operationsMs.put(op, new ArrayList<>()));
    List<Double> iterationsMs = new ArrayList<>();
    List<Double> heapMiB = new ArrayList<>();
    List<Double> rssMiB = new ArrayList<>();
    for (int i = 0; i < options.iterations(); i++) {
      Start s = start(options);
      double inEngine = s.buildEngineMs() + s.deployMs() + s.firstInstanceMs();
      operationsMs.get("jvmBoot").add(Math.max(0, s.wallMs() - inEngine));
      operationsMs.get("buildEngine").add(s.buildEngineMs());
      operationsMs.get("deploy").add(s.deployMs());
      operationsMs.get("firstInstance").add(s.firstInstanceMs());
      iterationsMs.add(s.wallMs());
      heapMiB.add(s.heapBytes() / (1024d * 1024d));
      s.rssMiB().ifPresent(rssMiB::add);
    }
    Map<String, Metric> metrics = new LinkedHashMap<>();
    metrics.put("idleHeapAfterGc", new Metric(Scenarios.median(heapMiB), "MiB"));
    if (!rssMiB.isEmpty()) {
      metrics.put("idleRss", new Metric(Scenarios.median(rssMiB), "MiB"));
    }
    return new BenchmarkResult(ID, options.database(), options.params(), iterationsMs, operationsMs, metrics);
  }

  private Start start(Options options) throws IOException, InterruptedException {
    String java = ProcessHandle.current().info().command()
        .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    List<String> cmd = List.of(
        java,
        "-Dbenchmark.jdbcUrl=" + options.jdbcUrl(),
        "-Dbenchmark.jdbcUser=" + options.jdbcUser(),
        "-Dbenchmark.jdbcPassword=" + options.jdbcPassword(),
        "-cp", System.getProperty("java.class.path"),
        StartupProbe.class.getName());
    long forked = System.nanoTime();
    Process child = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try {
      BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
      CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> readyLine(out));
      String line;
      try {
        line = ready.get(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        throw new IllegalStateException("engine not ready after " + READY_TIMEOUT);
      } catch (ExecutionException e) {
        throw new IllegalStateException("startup probe failed: " + e.getCause().getMessage(), e.getCause());
      }
      double wallMs = (System.nanoTime() - forked) / 1e6;
      String[] f = line.trim().split(" ");
      OptionalDouble rss = rssMiB(child.pid());
      try (OutputStream in = child.getOutputStream()) {
        in.write('\n');
      }
      if (!child.waitFor(2, TimeUnit.MINUTES)) {
        throw new IllegalStateException("startup probe did not exit after closing the engine");
      }
      if (child.exitValue() != 0) {
        throw new IllegalStateException("startup probe exited with " + child.exitValue());
      }
      return new Start(wallMs, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
          Double.parseDouble(f[4]), Long.parseLong(f[5]), rss);
    } finally {
      child.destroyForcibly();
    }
  }

  private static String readyLine(BufferedReader out) {
    try {
      String line;
      while ((line = out.readLine()) != null) {
        if (line.startsWith(StartupProbe.READY + " ")) {
          return line;
        }
        System.out.println(line); // anything else the child prints goes to the job log
      }
      throw new IllegalStateException("startup probe ended without " + StartupProbe.READY + " (see its stderr above)");
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Resident set size via {@code ps} (macOS and Linux report KiB); empty if unavailable. */
  static OptionalDouble rssMiB(long pid) {
    try {
      Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid))
          .redirectErrorStream(true).start();
      String text = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!ps.waitFor(10, TimeUnit.SECONDS) || ps.exitValue() != 0 || text.isEmpty()) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(Long.parseLong(text) / 1024d);
    } catch (IOException | NumberFormatException e) {
      return OptionalDouble.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return OptionalDouble.empty();
    }
  }
}
