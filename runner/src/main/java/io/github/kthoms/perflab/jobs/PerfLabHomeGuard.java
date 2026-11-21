package io.github.kthoms.perflab.jobs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The benchmarks are compiled from the perf-lab working tree, so only reviewed code may run: the
 * tree must be clean (untracked files under {@code target/} and {@code .superpowers/} aside) and
 * HEAD must be the commit of the local branch {@code main}. Throws an IllegalStateException
 * (an infrastructure failure) naming the reason otherwise.
 */
public class PerfLabHomeGuard {

  static final String BRANCH = "main";
  private static final long TIMEOUT_SECONDS = 60;
  private static final int MAX_LISTED = 5;

  public void verify(Path home) {
    if (run(home, "rev-parse", "--show-toplevel").exit != 0) {
      throw fail(home, "not a git working tree");
    }
    List<String> dirty = new ArrayList<>();
    for (String line : git(home, "status", "--porcelain=v1", "--untracked-files=all").lines().toList()) {
      if (!line.isBlank() && !ignoredUntracked(line)) {
        dirty.add(line.strip());
      }
    }
    if (!dirty.isEmpty()) {
      String listed = String.join(", ", dirty.subList(0, Math.min(MAX_LISTED, dirty.size())));
      throw fail(home, "working tree is not clean (" + dirty.size() + " change(s): " + listed
          + (dirty.size() > MAX_LISTED ? ", ..." : "") + ")");
    }
    Result main = run(home, "rev-parse", "--verify", "--quiet", "refs/heads/" + BRANCH);
    if (main.exit != 0) {
      throw fail(home, "there is no local branch '" + BRANCH + "'");
    }
    String head = git(home, "rev-parse", "HEAD").strip();
    if (!head.equals(main.out.strip())) {
      throw fail(home, "HEAD " + head + " is not the commit of local branch '" + BRANCH + "' ("
          + main.out.strip() + ")");
    }
  }

  private static boolean ignoredUntracked(String line) {
    if (!line.startsWith("?? ")) {
      return false;
    }
    String path = line.substring(3).replace("\"", "");
    return path.startsWith(".superpowers/") || path.startsWith("target/") || path.contains("/target/");
  }

  private static IllegalStateException fail(Path home, String reason) {
    return new IllegalStateException(
        "refusing to compile benchmarks from perfLabHome " + home + ": " + reason
            + " (benchmarks only run from a clean checkout of '" + BRANCH + "')");
  }

  private record Result(int exit, String out) {}

  private static String git(Path home, String... args) {
    Result r = run(home, args);
    if (r.exit != 0) {
      throw fail(home, "git " + String.join(" ", args) + " failed with " + r.exit + ": " + r.out.strip());
    }
    return r.out;
  }

  private static Result run(Path home, String... args) {
    List<String> cmd = new ArrayList<>(List.of("git", "-C", home.toString()));
    cmd.addAll(List.of(args));
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd)
          .redirectErrorStream(true)
          .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
      pb.environment().put("GIT_TERMINAL_PROMPT", "0");
      Process p = pb.start();
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw fail(home, "git " + String.join(" ", args) + " timed out");
      }
      return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw fail(home, "cannot run git: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw fail(home, "interrupted while running git");
    }
  }
}
