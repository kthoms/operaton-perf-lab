package io.github.kthoms.perflab.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kthoms.perflab.exec.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatonCheckoutTest {

    @TempDir Path tmp;
    Path bare;
    String mainSha;
    String prSha;

    String git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
        return out.trim();
    }

    @BeforeEach
    void fixture() throws Exception {
        bare = tmp.resolve("upstream.git");
        Files.createDirectories(bare);
        git(bare, "init", "--bare", "-b", "main");
        Path work = tmp.resolve("work");
        Files.createDirectories(work);
        git(work, "init", "-b", "main");
        git(work, "config", "user.email", "t@example.com");
        git(work, "config", "user.name", "t");
        git(work, "config", "commit.gpgsign", "false");
        Files.writeString(work.resolve("a.txt"), "a");
        git(work, "add", ".");
        git(work, "commit", "-m", "one");
        git(work, "remote", "add", "origin", bare.toString());
        git(work, "push", "origin", "main");
        mainSha = git(work, "rev-parse", "HEAD");
        git(work, "checkout", "-b", "pr");
        Files.writeString(work.resolve("b.txt"), "b");
        git(work, "add", ".");
        git(work, "commit", "-m", "pr");
        prSha = git(work, "rev-parse", "HEAD");
        git(work, "push", "origin", "HEAD:refs/pull/1/head");
    }

    OperatonCheckout checkout() {
        return new OperatonCheckout(tmp.resolve("clone/operaton"), bare.toString(), new ProcessRunner());
    }

    @Test
    void resolvesBranchAndReachability() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.ensureCloned();
        c.fetch();
        assertThat(c.resolveBranch("main")).isEqualTo(mainSha);
        assertThat(c.isReachableFromUpstream(mainSha)).isTrue();
    }

    @Test
    void failedFetchThrows() throws Exception {
        var c = checkout();
        c.ensureCloned();
        Files.move(bare, tmp.resolve("gone.git")); // upstream unreachable
        assertThatThrownBy(c::fetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("git fetch");
    }

    @Test
    void knowsWhichBranchesExistUpstream() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        assertThat(c.hasUpstreamBranch("main")).isTrue();
        assertThat(c.hasUpstreamBranch("release/9.9")).isFalse();
    }

    @Test
    void rejectsUnreachableCommit() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        assertThat(c.isReachableFromUpstream(prSha)).isFalse();
    }

    @Test
    void resolvesShortShaToFullSha() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        assertThat(c.resolveCommit(mainSha.substring(0, 10))).isEqualTo(mainSha);
    }

    @Test
    void branchContainsChecksOriginBranch() throws Exception {
        Path work = tmp.resolve("work");
        git(work, "checkout", "-b", "maint", mainSha);
        Files.writeString(work.resolve("c.txt"), "c");
        git(work, "add", ".");
        git(work, "commit", "-m", "maint");
        String maintSha = git(work, "rev-parse", "HEAD");
        git(work, "push", "origin", "maint");
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        assertThat(c.branchContains("main", mainSha)).isTrue();
        assertThat(c.branchContains("maint", mainSha)).isTrue();
        assertThat(c.branchContains("main", maintSha)).isFalse();
        assertThat(c.branchContains("maint", maintSha)).isTrue();
        assertThat(c.branchContains("no-such-branch", mainSha)).isFalse();
        assertThatThrownBy(() -> c.branchContains("main", "--all")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsRemoteReleaseBranchesFromTheLocalClone() throws Exception {
        Path work = tmp.resolve("work");
        git(work, "push", "origin", "main:refs/heads/release/2.2.x");
        git(work, "push", "origin", "main:refs/heads/release/2.1.x");
        git(work, "push", "origin", "main:refs/heads/feature/x");
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        // later upstream branches are invisible until the next fetch: no network involved
        git(work, "push", "origin", "main:refs/heads/release/2.3.x");
        assertThat(c.remoteReleaseBranches()).containsExactly("release/2.1.x", "release/2.2.x");
        c.fetch();
        assertThat(c.remoteReleaseBranches()).containsExactly("release/2.1.x", "release/2.2.x", "release/2.3.x");
    }

    @Test
    void existsOnlyOnceCloned() throws Exception {
        var c = checkout();
        assertThat(c.exists()).isFalse();
        c.ensureCloned();
        assertThat(c.exists()).isTrue();
    }

    @Test
    void rejectsMalformedSha() throws Exception {
        var c = checkout();
        c.ensureCloned();
        assertThatThrownBy(() -> c.isReachableFromUpstream("abc; rm -rf /")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.isReachableFromUpstream("HEAD")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkoutLeavesCleanTree() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        Path dir = tmp.resolve("clone/operaton");
        Files.writeString(dir.resolve("junk.txt"), "x");
        Files.writeString(dir.resolve("a.txt"), "modified");
        c.checkout(mainSha);
        assertThat(git(dir, "status", "--porcelain")).isEmpty();
        assertThat(git(dir, "rev-parse", "HEAD")).isEqualTo(mainSha);
    }

    @Test
    void reportsUnreachableEvenWhenCommitExistsLocally() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        Path dir = tmp.resolve("clone/operaton");
        git(dir, "fetch", "origin", "refs/pull/1/head");
        assertThat(git(dir, "cat-file", "-t", prSha)).isEqualTo("commit");
        assertThat(c.isReachableFromUpstream(prSha)).isFalse();
    }

    @Test
    void checkoutSwitchesCommitFromDirtyTree() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        Path work = tmp.resolve("work");
        git(work, "checkout", "main");
        Files.writeString(work.resolve("a.txt"), "second");
        git(work, "commit", "-am", "two");
        git(work, "push", "origin", "main");
        String second = git(work, "rev-parse", "HEAD");
        c.fetch();
        c.checkout(mainSha);
        Path dir = tmp.resolve("clone/operaton");
        Files.writeString(dir.resolve("a.txt"), "dirty");
        Files.writeString(dir.resolve("junk.txt"), "x");
        c.checkout(second);
        assertThat(git(dir, "rev-parse", "HEAD")).isEqualTo(second);
        assertThat(git(dir, "status", "--porcelain")).isEmpty();
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("second");
    }

    @Test
    void failedCloneThrows() {
        var c = new OperatonCheckout(tmp.resolve("x/operaton"), tmp.resolve("missing.git").toString(), new ProcessRunner());
        assertThatThrownBy(c::ensureCloned).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesDirectoryInsideEnclosingRepo() throws Exception {
        Path outer = tmp.resolve("outer");
        Files.createDirectories(outer.resolve("operaton"));
        git(outer, "init", "-b", "main");
        var c = new OperatonCheckout(outer.resolve("operaton"), bare.toString(), new ProcessRunner());
        assertThatThrownBy(c::fetch).isInstanceOf(IllegalStateException.class).hasMessageContaining("not the root");
        assertThatThrownBy(() -> c.checkout(mainSha)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void repinsOriginToUpstream() throws Exception {
        var c = checkout();
        c.ensureCloned();
        Path dir = tmp.resolve("clone/operaton");
        git(dir, "remote", "set-url", "origin", tmp.resolve("fork.git").toString());
        c.fetch();
        assertThat(git(dir, "remote", "get-url", "origin")).isEqualTo(bare.toString());
        assertThat(c.resolveBranch("main")).isEqualTo(mainSha);
    }
}
