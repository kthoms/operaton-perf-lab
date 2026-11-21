package io.github.kthoms.perflab.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.kthoms.perflab.results.Measurement;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurefireExtractorTest {
  private final Path root = Path.of("src/test/resources/fixtures/surefire");

  @Test
  void extractsClassModuleDurationAndCount() {
    assertThat(SurefireExtractor.extract(root))
        .contains(
            new Measurement("test.module.duration", 3.5, "s", Map.of("module", "engine")),
            new Measurement("test.module.count", 7.0, "count", Map.of("module", "engine")),
            new Measurement(
                "test.class.duration", 2.0, "s", Map.of("module", "engine", "class", "org.x.ATest")),
            new Measurement(
                "test.class.duration", 1.5, "s", Map.of("module", "engine", "class", "org.x.BTest")));
  }

  @Test
  void nestedModulePathWithThousandsSeparator() {
    List<Measurement> all = SurefireExtractor.extract(root);
    assertThat(all)
        .anySatisfy(
            m -> {
              assertThat(m.kpi()).isEqualTo("test.module.duration");
              assertThat(m.dims()).isEqualTo(Map.of("module", "engine-plugins/spin-plugin"));
              assertThat(m.value()).isCloseTo(1234.5, within(1e-9));
            });
  }

  @Test
  void skipsNodeModules() {
    assertThat(SurefireExtractor.extract(root))
        .noneMatch(m -> m.dims().values().stream().anyMatch(v -> v.contains("node_modules")))
        .noneMatch(m -> "org.n.NodeTest".equals(m.dims().get("class")));
  }

  @Test
  void rootWithoutReportsYieldsNothing() {
    assertThat(SurefireExtractor.extract(Path.of("src/test/java")))
        .isEmpty();
  }
}
