package io.github.kthoms.perflab.git;

import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.exec.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration LOCAL_TIMEOUT = Duration.ofMinutes(2);
    private static final Map<String, String> GIT_ENV = Map.of("GIT_TERMINAL_PROMPT", "0");
    private static final CancellationToken NEVER = () -> false;

    private final Path dir;
    private final String upstreamUrl;
    private final ProcessRunner runner;
    private final Path gitLog;
    private final Duration networkTimeout;
    private final Duration localTimeout;

    public OperatonCheckout(Path dir, String upstreamUrl, ProcessRunner runner) {
        this(dir, upstreamUrl, runner, NETWORK_TIMEOUT, LOCAL_TIMEOUT);
    }

    OperatonCheckout(Path dir, String upstreamUrl, ProcessRunner runner, Duration networkTimeout, Duration localTimeout) {
        this.dir = dir.toAbsolutePath();
        this.upstreamUrl = upstreamUrl;
        this.runner = runner;
        this.networkTimeout = networkTimeout;
        this.localTimeout = localTimeout;
        Path parent = this.dir.getParent();
        this.gitLog = (parent != null ? parent : this.dir).resolve("git.log");
    }

    public void ensureCloned() {
        ensureCloned(NEVER);
    }

    public void ensureCloned(CancellationToken token) {
        if (!Files.isDirectory(dir.resolve(".git"))) {
            try {
                Files.createDirectories(dir.getParent());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            int code = run(dir.getParent(), networkTimeout, token, "git", "clone", upstreamUrl, dir.toString());
            if (code != 0) throw new IllegalStateException("git clone failed with " + code + " (see " + gitLog + ")");
        }
        requireRepo(token);
        pinOrigin(token);
    }

    public void fetch() {
        fetch(NEVER);
    }

    public void fetch(CancellationToken token) {
        requireRepo(token);
        pinOrigin(token);
        int code = run(dir, networkTimeout, token, "git", "fetch", "--prune", "origin");
        if (code != 0) throw new IllegalStateException("git fetch failed with " + code + " (see " + gitLog + ")");
    }

    /** Whether {@code origin/<branch>} exists in the fetched clone; throws on any other git failure. */
    public boolean hasUpstreamBranch(String branch) {
        requireRepo(NEVER);
        int code = exit(NEVER, "show-ref", "--verify", "--quiet", "refs/remotes/origin/" + branch);
        if (code == 0) return true;
        if (code == 1) return false;
        throw new IllegalStateException("git show-ref origin/" + branch + " failed with " + code + " (see " + gitLog + ")");
    }

    /** Whether the working copy has been cloned; touches nothing on disk. */
    public boolean exists() {
        return Files.isDirectory(dir.resolve(".git"));
    }

    /** {@code release/*} branches of the already-fetched local clone (no network), sorted. */
    public List<String> remoteReleaseBranches() {
        requireRepo(NEVER);
        return capture(NEVER, "branch", "-r", "--list", "origin/release/*").lines()
                .map(String::trim)
                .filter(l -> l.startsWith("origin/") && !l.contains(" -> "))
                .map(l -> l.substring("origin/".length()))
                .sorted()
                .toList();
    }

    public String resolveBranch(String branch) {
        requireRepo(NEVER);
        return capture(NEVER, "rev-parse", "--verify", "origin/" + branch + "^{commit}").trim();
    }

    public boolean isReachableFromUpstream(String sha) {
        requireSha(sha);
        requireRepo(NEVER);
        if (exit(NEVER, "cat-file", "-e", sha + "^{commit}") != 0) return false;
        return !capture(NEVER, "branch", "-r", "--contains", sha).isBlank();
    }

    /** The full SHA of a (possibly abbreviated) commit present in the clone. */
    public String resolveCommit(String sha) {
        requireSha(sha);
        requireRepo(NEVER);
        return capture(NEVER, "rev-parse", "--verify", sha + "^{commit}").trim();
    }

    /** True iff {@code origin/<branch>} exists and contains the commit. */
    public boolean branchContains(String branch, String sha) {
        requireSha(sha);
        requireRepo(NEVER);
        return exit(NEVER, "merge-base", "--is-ancestor", sha, "origin/" + branch) == 0;
    }

    public void checkout(String sha) {
        checkout(sha, NEVER);
    }

    public void checkout(String sha, CancellationToken token) {
        requireSha(sha);
        requireRepo(token);
        // a dirty tree from an aborted build must never block switching commits
        git(token, "reset", "--hard");
        git(token, "clean", "-ffdx");
        git(token, "checkout", "-f", "--detach", sha);
        git(token, "clean", "-ffdx");
    }

    private static void requireSha(String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("not a commit sha: " + sha);
        }
    }

    /** The git toplevel of dir must be dir itself, never an enclosing repository. */
    private void requireRepo(CancellationToken token) {
        try {
            String top = capture(token, "rev-parse", "--show-toplevel").trim();
            if (!Path.of(top).toRealPath().equals(dir.toRealPath())) {
                throw new IllegalStateException(dir + " is not the root of its own git repository (found " + top + ")");
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void pinOrigin(CancellationToken token) {
        if (exit(token, "remote", "get-url", "origin") != 0) {
            git(token, "remote", "add", "origin", upstreamUrl);
        } else if (!capture(token, "remote", "get-url", "origin").trim().equals(upstreamUrl)) {
            git(token, "remote", "set-url", "origin", upstreamUrl);
        }
    }

    private void git(CancellationToken token, String... args) {
        int code = exit(token, args);
        if (code != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed with " + code + " (see " + gitLog + ")");
    }

    private int exit(CancellationToken token, String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        boolean network = args[0].equals("fetch") || args[0].equals("clone");
        return run(dir, network ? networkTimeout : localTimeout, token, cmd.toArray(String[]::new));
    }

    private String capture(CancellationToken token, String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Path out = null;
        try {
            Files.createDirectories(gitLog.getParent());
            out = Files.createTempFile(gitLog.getParent(), "git-out", ".tmp");
            var pb = new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(gitLog.toFile()));
            pb.environment().putAll(GIT_ENV);
            Process p = pb.start();
            long deadline = System.nanoTime() + localTimeout.toNanos();
            while (!p.waitFor(POLL.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (token.isCancelled() || System.nanoTime() > deadline) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    p.waitFor();
                    throw new IllegalStateException("git " + String.join(" ", args) + " timed out or was cancelled");
                }
            }
            if (p.exitValue() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed (see " + gitLog + ")");
            return Files.readString(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            if (out != null) try { Files.deleteIfExists(out); } catch (IOException ignored) { }
        }
    }

    private int run(Path workDir, Duration timeout, CancellationToken token, String... cmd) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean[] timedOut = {false};
        CancellationToken bounded = () -> {
            if (System.nanoTime() > deadline) timedOut[0] = true;
            return timedOut[0] || token.isCancelled();
        };
        try {
            var r = runner.run(List.of(cmd), workDir, GIT_ENV, gitLog, bounded, POLL);
            if (r.cancelled()) {
                throw new IllegalStateException(String.join(" ", cmd) + (timedOut[0] ? " timed out" : " was cancelled"));
            }
            return r.exitCode();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
