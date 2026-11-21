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
        IdleConfig idle) {

    public record Db(String jdbcUrl, String user, String password) {}

    public record BranchConfig(String name, Path javaHome, List<String> mavenArgs) {}

    public record BenchmarkConfig(String id, List<String> databases, List<String> branches, int repetitions) {}

    public record IdleConfig(
            Duration minUserIdle,
            double maxLoad1,
            List<String> heavyProcessNames,
            Duration pollInterval,
            Duration activityCheckInterval) {}

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
