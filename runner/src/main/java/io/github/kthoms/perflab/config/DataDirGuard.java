package io.github.kthoms.perflab.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class DataDirGuard {

    private DataDirGuard() {}

    /** Empty = OK, otherwise the reason the data dir is unusable. */
    public static Optional<String> check(PerfLabConfig config) {
        Path dir = config.dataDir();
        if (dir == null) {
            return Optional.of("dataDir not configured");
        }
        if (!Files.exists(dir)) {
            return Optional.of("Data dir does not exist (volume not mounted?): " + dir);
        }
        if (!Files.isDirectory(dir)) {
            return Optional.of("Data dir is not a directory: " + dir);
        }
        try {
            Files.delete(Files.createTempFile(dir, ".perflab-write-check", ".tmp"));
        } catch (IOException | RuntimeException e) {
            return Optional.of("Data dir is not writable: " + dir + " (" + e.getMessage() + ")");
        }
        return Optional.empty();
    }
}
