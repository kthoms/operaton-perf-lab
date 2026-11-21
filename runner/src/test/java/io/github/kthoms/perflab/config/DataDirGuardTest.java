package io.github.kthoms.perflab.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DataDirGuardTest {

    private static PerfLabConfig cfgWithDataDir(Path dataDir) {
        return new PerfLabConfig(dataDir, dataDir, 14, "u", null, List.of(), List.of(), null);
    }

    @Test
    void missingDataDirBlocks(@TempDir Path t) {
        assertThat(DataDirGuard.check(cfgWithDataDir(t.resolve("absent"))))
                .hasValueSatisfying(m -> assertThat(m).contains("absent"));
    }

    @Test
    void nullDataDirBlocksWithoutException() {
        assertThat(DataDirGuard.check(cfgWithDataDir(null)))
                .hasValueSatisfying(m -> assertThat(m).contains("dataDir"));
    }

    @Test
    void fileInsteadOfDirBlocks(@TempDir Path t) throws IOException {
        Path f = Files.createFile(t.resolve("afile"));
        assertThat(DataDirGuard.check(cfgWithDataDir(f)))
                .hasValueSatisfying(m -> assertThat(m).contains("afile"));
    }

    @Test
    void writableDirIsOk(@TempDir Path t) {
        assertThat(DataDirGuard.check(cfgWithDataDir(t))).isEmpty();
    }

    @Test
    void dataDirMustBeWritable(@TempDir Path t) {
        Path ro = t;
        assumeFalse(System.getProperty("user.name").equals("root"));
        assertThat(ro.toFile().setWritable(false)).isTrue();
        try {
            assertThat(DataDirGuard.check(cfgWithDataDir(ro)))
                    .hasValueSatisfying(m -> assertThat(m).contains(ro.toString()));
        } finally {
            ro.toFile().setWritable(true);
        }
    }
}
