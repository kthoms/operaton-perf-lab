package io.github.kthoms.perflab;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Static checks of the provisioned Grafana dashboards' SQL. */
class DashboardQueriesTest {

  private static final Path DASHBOARDS = Path.of("../grafana/dashboards");

  record Query(String where, String sql) {}

  static List<Query> panelAndAnnotationQueries() throws IOException {
    List<Query> out = new ArrayList<>();
    ObjectMapper json = new ObjectMapper();
    try (Stream<Path> files = Files.list(DASHBOARDS)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        JsonNode d = json.readTree(f.toFile());
        for (JsonNode a : d.path("annotations").path("list")) {
          String sql = a.path("rawQuery").asText(a.path("target").path("rawSql").asText(""));
          out.add(new Query(f.getFileName() + " annotation " + a.path("name").asText(), sql));
        }
        for (JsonNode p : d.path("panels")) {
          for (JsonNode t : p.path("targets")) {
            out.add(new Query(f.getFileName() + " panel " + p.path("title").asText() + " " + t.path("refId").asText(),
                t.path("rawSql").asText("")));
          }
        }
      }
    }
    return out;
  }

  @Test
  void everyPanelAndAnnotationShowsOnlyScheduledBranchRuns() throws IOException {
    List<Query> queries = panelAndAnnotationQueries();
    assertThat(queries).hasSizeGreaterThan(10);
    assertThat(queries)
        .allSatisfy(
            q ->
                assertThat(q.sql())
                    .as(q.where())
                    .containsAnyOf("r.source='branch'", "source='branch'"));
  }

  static JsonNode panel(String dashboard, String title) throws IOException {
    JsonNode d = new ObjectMapper().readTree(DASHBOARDS.resolve(dashboard).toFile());
    for (JsonNode p : d.path("panels")) {
      if (p.path("title").asText().equals(title)) {
        return p;
      }
    }
    throw new AssertionError("no panel " + title + " in " + dashboard);
  }

  /** The band query (refId B) of a panel. */
  static String bandSql(String dashboard, String title) throws IOException {
    for (JsonNode t : panel(dashboard, title).path("targets")) {
      if (t.path("refId").asText().equals("B")) {
        return t.path("rawSql").asText();
      }
    }
    throw new AssertionError("no band query in " + title);
  }

  @Test
  void trendPanelsHaveANoiseBandFilledBetweenTwoSeries() throws IOException {
    record Band(String dashboard, String title, String floor) {}
    for (Band b :
        List.of(
            new Band("build-tests.json", "Engine module test time", "1.0"),
            new Band("build-tests.json", "Total build wall time", "30.0"),
            new Band("benchmarks.json", "Median per database and branch ($benchmark)", "1.0"))) {
      String sql = bandSql(b.dashboard(), b.title());
      assertThat(sql)
          .as(b.title())
          .contains("'noise band hi'", "'noise band lo'", "limit 10", "3 * d.mad", "0.03 * md.med",
              ", " + b.floor() + ")", "r2.segment=r.segment", "r2.source='branch'");
      JsonNode overrides = panel(b.dashboard(), b.title()).path("fieldConfig").path("overrides");
      assertThat(overrides.toString()).contains("\"custom.fillBelowTo\"", "\"noise band lo\"");
    }
  }
}
