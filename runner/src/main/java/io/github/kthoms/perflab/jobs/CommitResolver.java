package io.github.kthoms.perflab.jobs;

import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import io.github.kthoms.perflab.git.OperatonCheckout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which commit a job runs and with which branch configuration. Expects a fetched clone.
 * Branch job: the tip of {@code origin/<branch>}. Commit job: the commit, if reachable from
 * upstream, with the config of a configured branch containing it (the job's branch, then main).
 * Only an unknown branch or commit is {@link Unresolved}; any other git failure is thrown.
 */
public class CommitResolver {

  static final String PREFERRED_BRANCH = "main";

  public sealed interface Resolution permits Resolved, Unresolved {}

  public record Resolved(String sha, BranchConfig branch) implements Resolution {}

  public record Unresolved(String reason) implements Resolution {}

  private final OperatonCheckout checkout;
  private final List<BranchConfig> branches;

  public CommitResolver(OperatonCheckout checkout, List<BranchConfig> branches) {
    this.checkout = checkout;
    this.branches = List.copyOf(branches);
  }

  public Resolution resolve(Job job) {
    if (job.commitSha() != null) {
      return resolveCommit(job);
    }
    if (job.branch() == null) {
      return new Unresolved("job " + job.id() + " has neither branch nor commit");
    }
    Optional<BranchConfig> config = configOf(job.branch());
    if (config.isEmpty()) {
      return new Unresolved("branch '" + job.branch() + "' is not configured");
    }
    // only an unknown branch is not applicable; any other git failure is thrown (infrastructure)
    if (!checkout.hasUpstreamBranch(job.branch())) {
      return new Unresolved("branch '" + job.branch() + "' not found upstream");
    }
    return new Resolved(checkout.resolveBranch(job.branch()), config.get());
  }

  private Resolution resolveCommit(Job job) {
    String sha = job.commitSha();
    try {
      if (!checkout.isReachableFromUpstream(sha)) {
        return new Unresolved("commit " + sha + " is not reachable from any upstream branch");
      }
    } catch (IllegalArgumentException e) {
      return new Unresolved(e.getMessage());
    }
    String full = checkout.resolveCommit(sha);
    for (BranchConfig candidate : candidates(job.branch())) {
      if (checkout.branchContains(candidate.name(), full)) {
        return new Resolved(full, candidate);
      }
    }
    return new Unresolved("commit " + sha + " is not on any configured branch");
  }

  private List<BranchConfig> candidates(String jobBranch) {
    Set<BranchConfig> ordered = new LinkedHashSet<>();
    if (jobBranch != null) {
      configOf(jobBranch).ifPresent(ordered::add);
    }
    configOf(PREFERRED_BRANCH).ifPresent(ordered::add);
    ordered.addAll(branches);
    return new ArrayList<>(ordered);
  }

  private Optional<BranchConfig> configOf(String name) {
    return branches.stream().filter(b -> b.name().equals(name)).findFirst();
  }
}
