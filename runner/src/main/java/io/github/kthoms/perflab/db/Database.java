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
    var ds = new PGSimpleDataSource();
    ds.setUrl(jdbcUrl);
    ds.setUser(user);
    ds.setPassword(password);
    var grafanaRoPassword = System.getenv().getOrDefault("PERFLAB_GRAFANA_RO_PASSWORD", "grafana-ro");
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("grafanaRoPassword", grafanaRoPassword))
        .load()
        .migrate();
    return new Database(ds);
  }

  public DataSource dataSource() {
    return dataSource;
  }
}
