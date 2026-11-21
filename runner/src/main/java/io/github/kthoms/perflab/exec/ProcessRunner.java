package io.github.kthoms.perflab.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs external commands under caffeinate, logs combined output, and kills the whole process tree on cancel. */
public class ProcessRunner {

    public record Result(int exitCode, boolean cancelled, Duration elapsed) {}

    private static final Duration GRACE = Duration.ofSeconds(10);

    private final List<String> wrapperPrefix;

    public ProcessRunner() {
        this(List.of("/usr/bin/caffeinate", "-i"));
    }

    public ProcessRunner(List<String> wrapperPrefix) {
        this.wrapperPrefix = List.copyOf(wrapperPrefix);
    }

    public Result run(List<String> cmd, Path workDir, Map<String, String> env, Path logFile,
            CancellationToken token, Duration checkEvery) throws IOException, InterruptedException {
        Instant start = Instant.now();
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        var full = new ArrayList<>(wrapperPrefix);
        full.addAll(cmd);
        var pb = new ProcessBuilder(full)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.environment().putAll(env);
        Process process = pb.start();
        boolean cancelled = false;
        try {
            while (!process.waitFor(checkEvery.toMillis(), TimeUnit.MILLISECONDS)) {
                if (token.isCancelled()) {
                    cancelled = true;
                    killTree(process);
                    break;
                }
            }
        } catch (InterruptedException e) {
            killTree(process);
            throw e;
        }
        int exit = process.waitFor();
        return new Result(exit, cancelled, Duration.between(start, Instant.now()));
    }

    private static void collect(ProcessHandle root, java.util.Set<ProcessHandle> known) {
        known.add(root);
        root.descendants().forEach(known::add);
        for (ProcessHandle h : List.copyOf(known)) {
            if (h.isAlive()) h.descendants().forEach(known::add);
        }
    }

    private static boolean anyAlive(java.util.Set<ProcessHandle> known) {
        return known.stream().anyMatch(ProcessHandle::isAlive);
    }

    private static void waitDead(java.util.Set<ProcessHandle> known, long maxNanos) throws InterruptedException {
        long end = System.nanoTime() + maxNanos;
        while (System.nanoTime() < end && anyAlive(known)) Thread.sleep(50);
    }

    /** Repeated snapshot+destroy rounds so late-spawned descendants are caught; then force-kill leftovers. */
    private static void killTree(Process process) throws InterruptedException {
        ProcessHandle root = process.toHandle();
        java.util.Set<ProcessHandle> known = new java.util.LinkedHashSet<>();
        long deadline = System.nanoTime() + GRACE.toNanos();
        for (int round = 0; round < 5 && System.nanoTime() < deadline; round++) {
            collect(root, known);
            if (!anyAlive(known)) break;
            // descendants first, root last
            known.stream().filter(h -> !h.equals(root)).filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
            if (root.isAlive()) root.destroy();
            waitDead(known, Duration.ofSeconds(1).toNanos());
        }
        collect(root, known);
        known.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        waitDead(known, Duration.ofSeconds(5).toNanos());
    }
}
