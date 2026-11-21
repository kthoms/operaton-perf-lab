package io.github.kthoms.perflab.config;

import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @Test
    void loadsExample() {
        var c = ConfigLoader.load(Path.of("../config/perflab.example.yml"));
        assertThat(c.branches()).extracting(BranchConfig::name).containsExactly("main", "release/2.2.x");
        assertThat(c.idle().minUserIdle()).isEqualTo(Duration.ofMinutes(15));
        assertThat(c.idle().pollInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(c.idle().activityCheckInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(c.idle().maxLoad1()).isEqualTo(2.0);
        assertThat(c.idle().heavyProcessNames()).containsExactly("java", "mvn", "gradle", "idea");
        assertThat(c.backupRetentionDays()).isEqualTo(14);
        assertThat(c.resultsDb().jdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5433/perflab");
        assertThat(c.benchmarks()).singleElement().satisfies(b -> {
            assertThat(b.id()).isEqualTo("task-authorization");
            assertThat(b.databases()).containsExactly("h2", "postgresql");
            assertThat(b.repetitions()).isEqualTo(5);
        });
        assertThat(c.branches().get(0).mavenArgs()).containsExactly(
                "-Pdistro,distro-webjar-neo,h2-in-memory", "-Dskip.frontend.build=true", "clean", "install");
    }

    @Test
    void derivesPathsFromDataDir() {
        var c = ConfigLoader.load(Path.of("../config/perflab.example.yml"));
        assertThat(c.mavenRepo()).isEqualTo(c.dataDir().resolve("m2"));
        assertThat(c.checkout()).isEqualTo(c.dataDir().resolve("work/operaton"));
        assertThat(c.logs()).isEqualTo(c.dataDir().resolve("logs"));
    }

    @Test
    void parsesShortDurations() {
        assertThat(ConfigLoader.parseDuration("90m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(ConfigLoader.parseDuration("20h")).isEqualTo(Duration.ofHours(20));
        assertThatThrownBy(() -> ConfigLoader.parseDuration("soon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingDataDirKeyFailsAtLoad(@TempDir Path t) throws IOException {
        String yml = Files.readString(Path.of("../config/perflab.example.yml"))
                .replaceFirst("(?m)^dataDir:.*$", "");
        Path f = Files.writeString(t.resolve("c.yml"), yml);
        assertThatThrownBy(() -> ConfigLoader.load(f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataDir");
    }

    @Test
    void missingIdleSectionFailsAtLoad(@TempDir Path t) throws IOException {
        String yml = Files.readString(Path.of("../config/perflab.example.yml"));
        Path f = Files.writeString(t.resolve("c.yml"), yml.substring(0, yml.indexOf("idle:")));
        assertThatThrownBy(() -> ConfigLoader.load(f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idle");
    }
}
