package io.github.kthoms.perflab.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;

/**
 * Child JVM of {@link EngineStartupBenchmark}: boots an engine on an empty database (schema
 * creation included), deploys both comparison scenarios, runs one verified scenario A instance
 * (the "ready" check), then prints {@code READY <jvmToMainMs> <buildEngineMs> <deployMs>
 * <firstInstanceMs> <heapAfterGcBytes>} and waits for a line on stdin before it closes the engine
 * (dropping the schema) and exits.
 */
public final class StartupProbe {

  static final String READY = "READY";

  private StartupProbe() {}

  public static void main(String[] args) throws Exception {
    double jvmToMainMs = ManagementFactory.getRuntimeMXBean().getUptime();
    long t0 = System.nanoTime();
    ProcessEngine engine =
        Scenarios.buildEngine(
            Scenarios.property("benchmark.jdbcUrl", "jdbc:h2:mem:perflab-bench;DB_CLOSE_DELAY=-1"),
            Scenarios.property("benchmark.jdbcUser", "sa"),
            System.getProperty("benchmark.jdbcPassword", ""),
            ProcessEngineConfiguration.HISTORY_FULL);
    try {
      long t1 = System.nanoTime();
      Scenarios.deploy(engine);
      long t2 = System.nanoTime();
      Scenarios.runScenarioA(engine.getRuntimeService());
      long t3 = System.nanoTime();
      System.gc();
      long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      System.out.println(
          String.format(
              Locale.ROOT,
              "%s %.3f %.3f %.3f %.3f %d",
              READY,
              jvmToMainMs,
              (t1 - t0) / 1e6,
              (t2 - t1) / 1e6,
              (t3 - t2) / 1e6,
              heap));
      System.out.flush();
      // the parent measures the process' memory now, then releases us
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    } finally {
      engine.close();
    }
  }
}
