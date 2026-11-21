package io.github.kthoms.perflab.git;

import io.github.kthoms.perflab.exec.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Operaton working copy managed through the git CLI (argument lists only, no shell). */
public class OperatonCheckout {

    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{7,40}$");
    private static final Duration POLL = Duration.ofMillis(200);

    private final Path dir;
    private final String upstreamUrl;
    private final ProcessRunner runner;
    private final Path gitLog;

    public OperatonCheckout(Path dir, String upstreamUrl, ProcessRunner runner) {
        this.dir = dir.toAbsolutePath();
        this.upstreamUrl = upstreamUrl;
        this.runner = runner;
        Path parent = this.dir.getParent();
        this.gitLog = (parent != null ? parent : this.dir).resolve("git.log");
    }

    public void ensureCloned() {
        if (Files.isDirectory(dir.resolve(".git"))) return;
        try {
            Files.createDirectories(dir.getParent());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        run(dir.getParent(), "git", "clone", upstreamUrl, dir.toString());
    }

    public void fetch() {
        git("fetch", "--prune", "origin");
    }

    public String resolveBranch(String branch) {
        return capture("rev-parse", "--verify", "origin/" + branch + "^{commit}").trim();
    }

    public boolean isReachableFromUpstream(String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("not a commit sha: " + sha);
        }
        if (exit("cat-file", "-e", sha + "^{commit}") != 0) return false;
        return !capture("branch", "-r", "--contains", sha).isBlank();
    }

    public void checkout(String sha) {
        if (!SHA.matcher(sha).matches()) throw new IllegalArgumentException("not a commit sha: " + sha);
        git("checkout", "--detach", sha);
        git("clean", "-ffdx");
        git("reset", "--hard", sha);
    }

    private void git(String... args) {
        int code = exit(args);
        if (code != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed with " + code + " (see " + gitLog + ")");
    }

    private int exit(String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return run(dir, cmd.toArray(String[]::new));
    }

    private String capture(String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(gitLog.toFile()));
        try {
            Files.createDirectories(gitLog.getParent());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed (see " + gitLog + ")");
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int run(Path workDir, String... cmd) {
        try {
            return runner.run(List.of(cmd), workDir, Map.of(), gitLog, () -> false, POLL).exitCode();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
