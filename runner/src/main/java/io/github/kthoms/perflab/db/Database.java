package io.github.kthoms.perflab.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Connection entry point for the results database; migrates the schema on connect. */
public final class Database {

  private final DataSource dataSource;

  private Database(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public static Database connect(String jdbcUrl, String user, String password) {
    var grafanaRoPassword =
        System.getenv().getOrDefault("PERFLAB_GRAFANA_RO_PASSWORD", "grafana-ro");
    return connect(jdbcUrl, user, password, grafanaRoPassword);
  }

  public static Database connect(
      String jdbcUrl, String user, String password, String grafanaRoPassword) {
    var ds = new PGSimpleDataSource();
    ds.setUrl(jdbcUrl);
    ds.setUser(user);
    ds.setPassword(password);
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("grafanaRoPassword", grafanaRoPassword))
        .load()
        .migrate();
    syncGrafanaRoPassword(ds, grafanaRoPassword);
    return new Database(ds);
  }

  /**
   * Applies the configured grafana_ro password on every connect. Flyway does not put placeholder
   * values into migration checksums, so a repeatable migration would not re-run on a changed
   * password.
   */
  private static void syncGrafanaRoPassword(DataSource ds, String password) {
    try (var c = ds.getConnection();
        var st = c.createStatement()) {
      st.execute("alter role grafana_ro password '" + password.replace("'", "''") + "'");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("cannot set grafana_ro password", e);
    }
  }

  public DataSource dataSource() {
    return dataSource;
  }
}
