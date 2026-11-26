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

    /**
     * {@code tasks} (optional): the numbers of tasks to measure with, one series per size. Absent or
     * empty: one series with the benchmark's default size, named by the plain id. With sizes, each
     * series is named {@code <id>@<tasks>} (see {@link #seriesId}); Grafana and the regression
     * detector treat every size as its own benchmark, so sizes never share a baseline.
     *
     * <p>{@code minInterval} (optional, default 20h): how long after a series' last run or terminal
     * job the next one is queued. Shorter means more frequent runs; each still waits for the idle gate.
     */
    public record BenchmarkConfig(
            String id,
            List<String> databases,
            List<String> branches,
            int repetitions,
            List<Integer> tasks,
            Duration minInterval) {

        public BenchmarkConfig(String id, List<String> databases, List<String> branches, int repetitions) {
            this(id, databases, branches, repetitions, null, null);
        }

        public BenchmarkConfig(
                String id, List<String> databases, List<String> branches, int repetitions, List<Integer> tasks) {
            this(id, databases, branches, repetitions, tasks, null);
        }

        /** The series names of this benchmark, in configured order. */
        public List<String> seriesIds() {
            if (tasks == null || tasks.isEmpty()) {
                return List.of(id);
            }
            return tasks.stream().map(t -> seriesId(id, t)).toList();
        }

        public static String seriesId(String id, int tasks) {
            return id + "@" + tasks;
        }

        /** The benchmark id of a series name: the part before {@code @}. */
        public static String baseId(String seriesId) {
            int at = seriesId.indexOf('@');
            return at < 0 ? seriesId : seriesId.substring(0, at);
        }

        /** The task count of a series name, empty if it names the default size. */
        public static java.util.OptionalInt tasksOf(String seriesId) {
            int at = seriesId.indexOf('@');
            return at < 0 ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(Integer.parseInt(seriesId.substring(at + 1)));
        }
    }

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
