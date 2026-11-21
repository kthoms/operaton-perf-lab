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

    private static void killTree(Process root) throws InterruptedException {
        List<ProcessHandle> victims = new ArrayList<>(root.toHandle().descendants().toList());
        victims.add(root.toHandle());
        // descendants first, root last
        for (int i = 0; i < victims.size() - 1; i++) victims.get(i).destroy();
        root.destroy();
        long deadline = System.nanoTime() + GRACE.toNanos();
        while (System.nanoTime() < deadline && victims.stream().anyMatch(ProcessHandle::isAlive)) {
            Thread.sleep(50);
        }
        // catch any late-forked children of survivors
        victims.stream().filter(ProcessHandle::isAlive).forEach(h -> {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
        });
    }
}
