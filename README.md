# operaton-perf-lab

A daemon that benchmarks the Operaton BPM engine on this Mac and stores the results in
PostgreSQL, viewed in Grafana.

## Layout

- `runner/` - the daemon (Java 25, Maven), schema migrations via Flyway
- `infra/` - docker compose for the results database (port 5433) and Grafana (port 3000)

## Setup

```bash
cp infra/.env.example infra/.env   # then edit
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d
```

### First start and grafana_ro password

The `grafana-init` service fixes ownership of `$PERFLAB_DATA_DIR/grafana` for Grafana (uid 472),
so no manual `chown` is needed.

`PERFLAB_GRAFANA_RO_PASSWORD` (in `infra/.env`, default `grafana-ro`, no single quotes) is the
password of the read-only DB user `grafana_ro`. Grafana reads it from compose; the runner applies
it on every start from its own environment, so both must see the same value. The launchd install
script (`ops/install-launchd.sh`) exports it from `infra/.env`. After changing it, re-run the
install script and `docker compose ... up -d`.

## Tests

Integration tests (`*IT`) use Testcontainers (`postgres:17`) and run in `mvn verify`.
The failsafe plugin sets the Colima-specific Testcontainers variables; you only need
`DOCKER_HOST` pointing at the Colima socket.

```bash
mvn -pl runner verify
```
