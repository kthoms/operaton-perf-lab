package io.github.kthoms.perflab.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Connection entry point for the results database; migrates the schema on connect. */
public final class Database {

  private static final System.Logger LOG = System.getLogger(Database.class.getName());
  private static final String DEFAULT_GRAFANA_RO_PASSWORD = "grafana-ro";

  private final DataSource dataSource;

  private Database(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Reads the grafana_ro password from PERFLAB_GRAFANA_RO_PASSWORD; unset leaves it unchanged. */
  public static Database connect(String jdbcUrl, String user, String password) {
    return connect(jdbcUrl, user, password, System.getenv("PERFLAB_GRAFANA_RO_PASSWORD"));
  }

  /**
   * @param grafanaRoPassword password to set for grafana_ro; {@code null} leaves an existing
   *     password untouched (a first-time role creation then uses a default); blank is rejected
   */
  public static Database connect(
      String jdbcUrl, String user, String password, String grafanaRoPassword) {
    if (grafanaRoPassword != null && grafanaRoPassword.isBlank()) {
      throw new IllegalArgumentException(
          "PERFLAB_GRAFANA_RO_PASSWORD is set but blank; unset it or give a password");
    }
    var ds = new PGSimpleDataSource();
    ds.setUrl(jdbcUrl);
    ds.setUser(user);
    ds.setPassword(password);
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .placeholders(
            java.util.Map.of(
                "grafanaRoPassword",
                grafanaRoPassword != null ? grafanaRoPassword : DEFAULT_GRAFANA_RO_PASSWORD))
        .load()
        .migrate();
    if (grafanaRoPassword == null) {
      LOG.log(
          System.Logger.Level.INFO,
          "PERFLAB_GRAFANA_RO_PASSWORD not set: grafana_ro password left unchanged");
    } else {
      setGrafanaRoPassword(ds, grafanaRoPassword);
    }
    return new Database(ds);
  }

  /**
   * Applies the configured password on every connect. Flyway does not put placeholder values into
   * migration checksums, so a repeatable migration would not re-run on a changed password.
   */
  private static void setGrafanaRoPassword(DataSource ds, String password) {
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
