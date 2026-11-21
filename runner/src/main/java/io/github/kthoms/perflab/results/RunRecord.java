package io.github.kthoms.perflab.results;

import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.jobs.JobKind;
import java.nio.file.Path;
import java.time.Instant;

/** One finished build or benchmark run. {@code status} is "SUCCESS" or "FAILED". */
public record RunRecord(
    long jobId,
    JobKind kind,
    String branch,
    String commitSha,
    String db,
    String benchmark,
    String status,
    Instant startedAt,
    Instant finishedAt,
    Fingerprint fingerprint,
    String commandLine,
    Path logPath) {}
