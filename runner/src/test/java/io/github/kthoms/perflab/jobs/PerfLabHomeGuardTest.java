package io.github.kthoms.perflab.jobs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerfLabHomeGuardTest {

  @TempDir Path home;
  final PerfLabHomeGuard guard = new PerfLabHomeGuard();

  static String git(Path dir, String... args) throws Exception {
    List<String> cmd = new ArrayList<>(List.of("git"));
    cmd.addAll(List.of(args));
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
    }
    return out.trim();
  }

  @BeforeEach
  void repo() throws Exception {
    git(home, "init", "-b", "main");
    git(home, "config", "user.email", "t@example.com");
    git(home, "config", "user.name", "t");
    git(home, "config", "commit.gpgsign", "false");
    Files.createDirectories(home.resolve("benchmarks"));
    Files.writeString(home.resolve("benchmarks/pom.xml"), "<project/>");
    git(home, "add", ".");
    git(home, "commit", "-m", "one");
  }

  @Test
  void cleanTreeOnMainPasses() {
    assertThatCode(() -> guard.verify(home)).doesNotThrowAnyException();
  }

  @Test
  void untrackedBuildOutputAndSddNotesAreIgnored() throws Exception {
    Files.createDirectories(home.resolve("runner/target/classes"));
    Files.writeString(home.resolve("runner/target/classes/X.class"), "x");
    Files.createDirectories(home.resolve("target"));
    Files.writeString(home.resolve("target/y"), "y");
    Files.createDirectories(home.resolve(".superpowers/sdd"));
    Files.writeString(home.resolve(".superpowers/sdd/notes.md"), "n");
    assertThatCode(() -> guard.verify(home)).doesNotThrowAnyException();
  }

  @Test
  void modifiedTrackedFileIsRejected() throws Exception {
    Files.writeString(home.resolve("benchmarks/pom.xml"), "<project><!-- local edit --></project>");
    assertThatThrownBy(() -> guard.verify(home))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not clean")
        .hasMessageContaining("benchmarks/pom.xml");
  }

  @Test
  void untrackedSourceFileIsRejected() throws Exception {
    Files.createDirectories(home.resolve("benchmarks/src"));
    Files.writeString(home.resolve("benchmarks/src/Evil.java"), "class Evil {}");
    assertThatThrownBy(() -> guard.verify(home))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not clean")
        .hasMessageContaining("benchmarks/src/Evil.java");
  }

  @Test
  void headOtherThanLocalMainIsRejected() throws Exception {
    git(home, "checkout", "-b", "feature");
    Files.writeString(home.resolve("f.txt"), "f");
    git(home, "add", ".");
    git(home, "commit", "-m", "feature");
    assertThatThrownBy(() -> guard.verify(home))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HEAD")
        .hasMessageContaining("main");
  }

  @Test
  void detachedHeadAtMainPasses() throws Exception {
    git(home, "checkout", "--detach", "main");
    assertThatCode(() -> guard.verify(home)).doesNotThrowAnyException();
  }

  @Test
  void missingMainBranchIsRejected() throws Exception {
    git(home, "branch", "-m", "main", "trunk");
    assertThatThrownBy(() -> guard.verify(home))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local branch 'main'");
  }

  @Test
  void directoryWithoutGitIsRejected(@TempDir Path plain) {
    assertThatThrownBy(() -> guard.verify(plain))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a git working tree");
  }
}
