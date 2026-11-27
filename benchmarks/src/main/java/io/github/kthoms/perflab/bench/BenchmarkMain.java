package io.github.kthoms.perflab.bench;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point of {@code exec:exec}: runs the benchmark named by {@code -Dbenchmark.id} (default
 * {@value TaskAuthorizationBenchmark#ID}) and writes its JSON result to {@code -Dbenchmark.output}.
 * The other {@code benchmark.*} properties are read by the benchmark itself; a blank one means its
 * own default.
 */
public final class BenchmarkMain {

  /** Every benchmark id this module runs. */
  public static final List<String> IDS =
      List.of(
          TaskAuthorizationBenchmark.ID,
          EngineStartupBenchmark.ID,
          ProcessThroughputBenchmark.Variant.SCENARIO_A.id(),
          ProcessThroughputBenchmark.Variant.SCENARIO_A_HISTORY_NONE.id(),
          ProcessThroughputBenchmark.Variant.SCENARIO_B.id());

  private BenchmarkMain() {}

  public static void main(String[] args) throws Exception {
    String output = System.getProperty("benchmark.output");
    if (output == null || output.isBlank()) {
      throw new IllegalArgumentException("-Dbenchmark.output=<file> is required");
    }
    String id = Scenarios.property("benchmark.id", TaskAuthorizationBenchmark.ID);
    run(id).write(Path.of(output));
  }

  static BenchmarkResult run(String id) throws Exception {
    return switch (id) {
      case TaskAuthorizationBenchmark.ID ->
          new TaskAuthorizationBenchmark().run(TaskAuthorizationBenchmark.Options.fromSystemProperties());
      case EngineStartupBenchmark.ID ->
          new EngineStartupBenchmark().run(EngineStartupBenchmark.Options.fromSystemProperties());
      default -> {
        if (!IDS.contains(id)) {
          throw new IllegalArgumentException("unknown benchmark '" + id + "', known: " + IDS);
        }
        yield new ProcessThroughputBenchmark(ProcessThroughputBenchmark.Variant.of(id))
            .run(ProcessThroughputBenchmark.Options.fromSystemProperties());
      }
    };
  }
}
