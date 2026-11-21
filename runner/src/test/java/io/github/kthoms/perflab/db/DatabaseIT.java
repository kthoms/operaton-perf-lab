package io.github.kthoms.perflab.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DatabaseIT {

  @Container
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  @Test
  void migratesSchema() throws SQLException {
    var db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    assertThat(tables(db))
        .contains("job", "run", "measurement", "activity_sample", "regression", "backup_log");
  }

  @Test
  void connectIsIdempotent() throws SQLException {
    Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    var db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    assertThat(tables(db)).contains("job");
  }

  @Test
  void enforcesJobStatusCheck() throws SQLException {
    var db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.executeUpdate(
          "insert into job(kind,priority,status) values ('BUILD',1,'QUEUED')");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "insert into job(kind,priority,status) values ('BUILD',1,'BOGUS')"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void grafanaRoUserCanReadButNotWrite() throws SQLException {
    Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c =
            java.sql.DriverManager.getConnection(pg.getJdbcUrl(), "grafana_ro", "grafana-ro");
        var st = c.createStatement()) {
      try (var rs = st.executeQuery("select count(*) from regression")) {
        assertThat(rs.next()).isTrue();
      }
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> st.executeUpdate("delete from measurement"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
    }
  }

  @Test
  void passwordChangeIsAppliedOnReconnect() throws SQLException {
    Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), "first-pw");
    Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), "second-pw");
    try (var c = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), "grafana_ro", "second-pw")) {
      assertThat(c.isValid(2)).isTrue();
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> java.sql.DriverManager.getConnection(pg.getJdbcUrl(), "grafana_ro", "first-pw"))
        .isInstanceOf(SQLException.class);
  }

  private static List<String> tables(Database db) throws SQLException {
    var names = new ArrayList<String>();
    try (var c = db.dataSource().getConnection();
        var rs =
            c.createStatement()
                .executeQuery(
                    "select table_name from information_schema.tables where table_schema='public'")) {
      while (rs.next()) {
        names.add(rs.getString(1));
      }
    }
    return names;
  }
}
