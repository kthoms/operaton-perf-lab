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
}
