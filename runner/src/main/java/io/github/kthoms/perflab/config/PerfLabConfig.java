package io.github.kthoms.perflab.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record PerfLabConfig(
        Path dataDir,
        Path backupDir,
        int backupRetentionDays,
        String upstreamUrl,
        Db resultsDb,
        List<BranchConfig> branches,
        List<BenchmarkConfig> benchmarks,
        IdleConfig idle,
        Path perfLabHome) {

    /** Without {@code perfLabHome}: the daemon derives it from the location of perflab.jar. */
    public PerfLabConfig(
            Path dataDir,
            Path backupDir,
            int backupRetentionDays,
            String upstreamUrl,
            Db resultsDb,
            List<BranchConfig> branches,
            List<BenchmarkConfig> benchmarks,
            IdleConfig idle) {
        this(dataDir, backupDir, backupRetentionDays, upstreamUrl, resultsDb, branches, benchmarks, idle, null);
    }

    public record Db(String jdbcUrl, String user, String password) {}

    public record BranchConfig(String name, Path javaHome, List<String> mavenArgs) {}

    public record BenchmarkConfig(String id, List<String> databases, List<String> branches, int repetitions) {}

    /**
     * {@code coldStartWindow} (optional, default {@link #DEFAULT_COLD_START_WINDOW}): local times at
     * which a slot without enough activity history is predicted idle.
     */
    public record IdleConfig(
            Duration minUserIdle,
            double maxLoad1,
            List<String> heavyProcessNames,
            Duration pollInterval,
            Duration activityCheckInterval,
            TimeWindow coldStartWindow) {

        public static final TimeWindow DEFAULT_COLD_START_WINDOW = TimeWindow.parse("22:00-07:00");

        public IdleConfig {
            if (coldStartWindow == null) {
                coldStartWindow = DEFAULT_COLD_START_WINDOW;
            }
        }

        public IdleConfig(
                Duration minUserIdle,
                double maxLoad1,
                List<String> heavyProcessNames,
                Duration pollInterval,
                Duration activityCheckInterval) {
            this(minUserIdle, maxLoad1, heavyProcessNames, pollInterval, activityCheckInterval, null);
        }
    }

    public Path mavenRepo() {
        return dataDir.resolve("m2");
    }

    public Path checkout() {
        return dataDir.resolve("work").resolve("operaton");
    }

    public Path logs() {
        return dataDir.resolve("logs");
    }
}
