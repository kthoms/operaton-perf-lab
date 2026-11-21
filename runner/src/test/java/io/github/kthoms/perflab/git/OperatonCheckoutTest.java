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
    void rejectsUnreachableCommit() throws Exception {
        var c = checkout();
        c.ensureCloned();
        c.fetch();
        assertThat(c.isReachableFromUpstream(prSha)).isFalse();
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
}
