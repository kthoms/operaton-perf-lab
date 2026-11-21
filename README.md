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

`PERFLAB_GRAFANA_RO_PASSWORD` (in `infra/.env`, default `grafana-ro`, unquoted, no single quotes or whitespace) is the
password of the read-only DB user `grafana_ro`. Grafana reads it from compose; the runner applies
it on every start from its own environment (if unset it leaves the password unchanged; a blank value is an error), so both must see the same value. The launchd install
script (`ops/install-launchd.sh`) renders it from `infra/.env` into the plist (no entry if the key is absent). After changing it, re-run the
install script and `docker compose ... up -d`.

## When jobs start (idle gate)

A job starts only while the owner has been idle for `idle.minUserIdle`, the 1-minute load is at most
`idle.maxLoad1`, the Mac is on AC power and no heavy foreign process runs. Scheduled jobs also need
the predicted idle time (from 8 weeks of activity samples in 15-minute slots of the week) to cover
their expected duration.

- Manual jobs (`perflab enqueue ...`, priority 0) skip only the prediction; all other checks stay.
- Cold start: a slot with fewer than 4 samples counts as idle when its local start time lies in
  `idle.coldStartWindow` (optional, `"HH:mm-HH:mm"`, may wrap midnight, default `"22:00-07:00"`),
  otherwise as 50% active (never idle). So a fresh install runs its first scheduled jobs at night
  instead of waiting about a week for history.
- Activity samples are recorded every poll interval, and while a job runs at most once per poll
  interval from the job's cancellation check, so the history has no gaps during long builds.

## Dashboards

Trend panels show only scheduled runs (`run.source = 'branch'`); manual and bisect runs of an
explicit commit (`source = 'commit'`) are stored but appear in no trend, annotation or regression
baseline. The engine module, total build wall and benchmark median panels draw a gray noise band for
the branch (and database) chosen in the "Noise band" variables: the median of the previous 10 runs
of the same segment +- max(3 x MAD, 3 % of the median, absolute floor), the same threshold the
regression detector uses (floors: test module 1 s, build wall 30 s, benchmark median 1 ms, operation
median 0.5 ms). The band starts once 5 earlier runs exist.

## Tests

Integration tests (`*IT`) use Testcontainers (`postgres:17`) and run in `mvn verify`.
The failsafe plugin sets the Colima-specific Testcontainers variables; you only need
`DOCKER_HOST` pointing at the Colima socket.

```bash
mvn -pl runner verify
```
