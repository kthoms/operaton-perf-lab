package io.github.kthoms.perflab.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.kthoms.perflab.results.Measurement;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReactorSummaryExtractorTest {
  private final Path log = Path.of("src/test/resources/fixtures/reactor-build.log");

  @Test
  void extractsModuleAndTotalWallTime() {
    assertThat(ReactorSummaryExtractor.extract(log))
        .contains(
            new Measurement("build.module.wall", 62.0, "s", Map.of("module", "Operaton - Engine")),
            new Measurement("build.total.wall", 3723.0, "s", Map.of()));
  }

  @Test
  void toleratesTimestampPrefixAndSecondsFormat() {
    List<Measurement> all = ReactorSummaryExtractor.extract(log);
    assertThat(all)
        .anySatisfy(
            m -> {
              assertThat(m.dims()).isEqualTo(Map.of("module", "Operaton - Spin Plugin"));
              assertThat(m.value()).isCloseTo(1.234, within(1e-9));
            });
  }

  @Test
  void ignoresUnparseableLinesAndNonSuccessModules() {
    List<Measurement> all = ReactorSummaryExtractor.extract(log);
    assertThat(all).hasSize(3);
    assertThat(all).noneMatch(m -> m.dims().getOrDefault("module", "").contains("Broken"));
    assertThat(all).noneMatch(m -> m.dims().getOrDefault("module", "").contains("Webapps"));
  }
}
