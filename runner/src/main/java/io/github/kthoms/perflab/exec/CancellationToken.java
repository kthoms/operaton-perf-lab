package io.github.kthoms.perflab.exec;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();
}
