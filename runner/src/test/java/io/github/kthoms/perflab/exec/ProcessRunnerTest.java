package io.github.kthoms.perflab.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {

    @TempDir Path tmp;

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void returnsExitCode() throws Exception {
        var r = runner.run(List.of("sh", "-c", "exit 3"), tmp, Map.of(), tmp.resolve("l.log"),
                () -> false, Duration.ofMillis(50));
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.cancelled()).isFalse();
    }

    @Test
    void logsCombinedOutput() throws Exception {
        Path log = tmp.resolve("sub/dir/l.log");
        var r = runner.run(List.of("sh", "-c", "echo out; echo err 1>&2; echo $FOO"), tmp,
                Map.of("FOO", "bar"), log, () -> false, Duration.ofMillis(50));
        assertThat(r.exitCode()).isZero();
        assertThat(Files.readString(log)).contains("out", "err", "bar");
    }

    @Test
    void cancelKillsDescendants() throws Exception {
        var cancel = new AtomicBoolean();
        var pidFile = tmp.resolve("pids");
        var t = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            cancel.set(true);
        });
        t.start();
        long before = ProcessHandle.current().descendants().count();
        Instant start = Instant.now();
        var r = runner.run(List.of("sh", "-c", "sleep 300 & echo $! >> pids; sleep 300 & echo $! >> pids; wait"),
                tmp, Map.of(), tmp.resolve("l.log"), cancel::get, Duration.ofMillis(100));
        assertThat(r.cancelled()).isTrue();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
        var pids = Files.readAllLines(pidFile).stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::parseLong).toList();
        assertThat(pids).hasSize(2);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && pids.stream().anyMatch(p -> ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false))) {
            Thread.sleep(100);
        }
        assertThat(pids).noneMatch(p -> ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false));
        assertThat(ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count())
                .isLessThanOrEqualTo(before);
    }
}
