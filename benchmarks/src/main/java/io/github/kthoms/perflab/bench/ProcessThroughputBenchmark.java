package io.github.kthoms.perflab.bench;

import io.github.kthoms.perflab.bench.BenchmarkResult.Metric;
import io.github.kthoms.perflab.bench.Scenarios.PricingInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.RuntimeService;

/**
 * Process throughput of the BPMN engine comparison (scenario A: three expression tasks, scenario
 * B: a 100-rule DMN table), embedded in this JVM instead of behind REST and a load balancer.
 * Every instance is started synchronously and verified (counter = 3, or the expected fee), like a
 * request of the original load test; a single wrong result fails the benchmark.
 *
 * <p>One iteration runs, for every concurrency level (the original's virtual users), a batch of
 * {@code instances} process instances on that many threads; the batch's wall time is the
 * operation {@code concurrency-<n>}, the iteration's time their sum. Metrics per level: throughput
 * (instances / median batch time) and the p50 / p95 / p99 latency of a single instance over all
 * timed batches.
 */
public class ProcessThroughputBenchmark {

  /** The benchmark variants: scenario and history level. */
  public enum Variant {
    SCENARIO_A("scenario-a", Scenarios.SCENARIO_A, ProcessEngineConfiguration.HISTORY_FULL),
    SCENARIO_A_HISTORY_NONE("scenario-a-history-none", Scenarios.SCENARIO_A, ProcessEngineConfiguration.HISTORY_NONE),
    SCENARIO_B("scenario-b", Scenarios.SCENARIO_B, ProcessEngineConfiguration.HISTORY_FULL);

    final String id;
    final String processKey;
    final String history;

    Variant(String id, String processKey, String history) {
      this.id = id;
      this.processKey = processKey;
      this.history = history;
    }

    public String id() {
      return id;
    }

    static Variant of(String id) {
      for (Variant v : values()) {
        if (v.id.equals(id)) {
          return v;
        }
      }
      throw new IllegalArgumentException("not a process throughput benchmark: " + id);
    }
  }

  static final List<Integer> DEFAULT_CONCURRENCY = List.of(1, 10, 25, 50);
  static final long PRICING_SEED = 42;

  public record Options(
      String database,
      String jdbcUrl,
      String jdbcUser,
      String jdbcPassword,
      List<Integer> concurrency,
      int instances,
      int warmup,
      int iterations) {

    public Options {
      concurrency = List.copyOf(concurrency);
      if (concurrency.isEmpty() || instances < 1 || warmup < 0 || iterations < 1) {
        throw new IllegalArgumentException("invalid options " + concurrency + " " + instances + " " + warmup + " " + iterations);
      }
    }

    static Options fromSystemProperties() {
      return new Options(
          Scenarios.property("benchmark.database", "h2"),
          Scenarios.property("benchmark.jdbcUrl", "jdbc:h2:mem:perflab-bench;DB_CLOSE_DELAY=-1"),
          Scenarios.property("benchmark.jdbcUser", "sa"),
          System.getProperty("benchmark.jdbcPassword", ""),
          Scenarios.intListProperty("benchmark.concurrency", DEFAULT_CONCURRENCY),
          Scenarios.intProperty("benchmark.instances", 2000),
          Scenarios.intProperty("benchmark.warmup", 2),
          Scenarios.intProperty("benchmark.iterations", 10));
    }

    Map<String, Integer> params() {
      Map<String, Integer> params = new LinkedHashMap<>();
      params.put("instances", instances);
      params.put("warmup", warmup);
      params.put("iterations", iterations);
      for (int c : concurrency) {
        params.put("concurrency-" + c, c);
      }
      return params;
    }
  }

  private final Variant variant;

  public ProcessThroughputBenchmark(Variant variant) {
    this.variant = variant;
  }

  public BenchmarkResult run(Options options) throws InterruptedException {
    List<PricingInput> inputs =
        variant.processKey.equals(Scenarios.SCENARIO_B)
            ? Scenarios.pricingInputs(options.instances(), PRICING_SEED)
            : List.of();
    ProcessEngine engine =
        Scenarios.buildEngine(options.jdbcUrl(), options.jdbcUser(), options.jdbcPassword(), variant.history);
    int maxThreads = Collections.max(options.concurrency());
    ExecutorService pool = Executors.newFixedThreadPool(maxThreads);
    try {
      Scenarios.deploy(engine);
      RuntimeService runtime = engine.getRuntimeService();
      Batch batch = new Batch(runtime, pool, inputs, options.instances());

      for (int w = 0; w < options.warmup(); w++) {
        for (int c : options.concurrency()) {
          batch.run(c);
        }
      }
      Map<String, List<Double>> operationsMs = new LinkedHashMap<>();
      Map<Integer, List<Double>> latencies = new LinkedHashMap<>();
      for (int c : options.concurrency()) {
        operationsMs.put(operation(c), new ArrayList<>(options.iterations()));
        latencies.put(c, new ArrayList<>(options.iterations() * options.instances()));
      }
      List<Double> iterationsMs = new ArrayList<>(options.iterations());
      for (int i = 0; i < options.iterations(); i++) {
        double iterationMs = 0;
        for (int c : options.concurrency()) {
          double ms = batch.run(c);
          operationsMs.get(operation(c)).add(ms);
          for (double l : batch.latenciesMs) {
            latencies.get(c).add(l);
          }
          iterationMs += ms;
        }
        iterationsMs.add(iterationMs);
      }

      Map<String, Metric> metrics = new LinkedHashMap<>();
      for (int c : options.concurrency()) {
        double medianBatchMs = Scenarios.median(operationsMs.get(operation(c)));
        List<Double> l = latencies.get(c);
        metrics.put("throughput." + operation(c), new Metric(options.instances() * 1000d / medianBatchMs, "1/s"));
        metrics.put("latencyP50." + operation(c), new Metric(Scenarios.percentile(l, 50), "ms"));
        metrics.put("latencyP95." + operation(c), new Metric(Scenarios.percentile(l, 95), "ms"));
        metrics.put("latencyP99." + operation(c), new Metric(Scenarios.percentile(l, 99), "ms"));
      }
      return new BenchmarkResult(
          variant.id, options.database(), options.params(), iterationsMs, operationsMs, metrics);
    } finally {
      pool.shutdownNow();
      engine.close();
    }
  }

  static String operation(int concurrency) {
    return "concurrency-" + concurrency;
  }

  /** Runs {@code instances} process instances on n threads; keeps each instance's latency. */
  private final class Batch {
    final RuntimeService runtime;
    final ExecutorService pool;
    final List<PricingInput> inputs;
    final int instances;
    final double[] latenciesMs;

    Batch(RuntimeService runtime, ExecutorService pool, List<PricingInput> inputs, int instances) {
      this.runtime = runtime;
      this.pool = pool;
      this.inputs = inputs;
      this.instances = instances;
      this.latenciesMs = new double[instances];
    }

    /** Wall time of the batch in ms; throws the first failure of any instance. */
    double run(int threads) throws InterruptedException {
      AtomicInteger next = new AtomicInteger();
      CountDownLatch go = new CountDownLatch(1);
      List<Future<?>> workers = new ArrayList<>(threads);
      for (int t = 0; t < threads; t++) {
        workers.add(
            pool.submit(
                () -> {
                  go.await();
                  int i;
                  while ((i = next.getAndIncrement()) < instances) {
                    long start = System.nanoTime();
                    if (inputs.isEmpty()) {
                      Scenarios.runScenarioA(runtime);
                    } else {
                      Scenarios.runScenarioB(runtime, inputs.get(i));
                    }
                    latenciesMs[i] = (System.nanoTime() - start) / 1_000_000d;
                  }
                  return null;
                }));
      }
      long start = System.nanoTime();
      go.countDown();
      for (Future<?> worker : workers) {
        try {
          worker.get();
        } catch (java.util.concurrent.ExecutionException e) {
          next.set(instances); // stop the other workers
          throw new IllegalStateException(
              variant.id + " failed at concurrency " + threads + ": " + e.getCause(), e.getCause());
        }
      }
      return (System.nanoTime() - start) / 1_000_000d;
    }
  }
}
