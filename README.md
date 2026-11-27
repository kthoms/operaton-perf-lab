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

## First real start (on the NVMe)

- Colima must mount the NVMe, otherwise `PERFLAB_DATA_DIR` on `/Volumes/...` is invisible to the
  containers: `colima start --mount /Volumes/<nvme>:w` (or a `mounts:` entry with `writable: true`
  in `colima.yaml`). After `docker compose ... up -d` check that `$PERFLAB_DATA_DIR/pgdata` appears
  on the host (`ls $PERFLAB_DATA_DIR/pgdata`); if not, the database lives inside the VM only.
- Start Colima with the Mac: `brew services start colima` (the daemon waits with backoff while the
  results DB is down, but it never starts Colima itself).
- To put the Colima VM disk on the NVMe, prefer moving `~/.colima` and leaving a symlink
  (`colima stop; mv ~/.colima /Volumes/<nvme>/colima; ln -s /Volumes/<nvme>/colima ~/.colima`) over
  `COLIMA_HOME`: every shell, brew service and launchd job then finds it without extra setup. If you
  use `COLIMA_HOME` anyway, export it when running `ops/install-launchd.sh`: the script writes it
  into the plist and derives the default `DOCKER_HOST` (`unix://$COLIMA_HOME/default/docker.sock`).
- Benchmarks are compiled from `perfLabHome` (default: this checkout) only if it is a clean checkout
  of the local `main` branch (untracked `target/` and `.superpowers/` aside); otherwise benchmark jobs
  fail with a message naming the reason.
- Verify the benchmark prep on the first benchmark run: engine install of the checkout into the lab's
  Maven repo, then the benchmark compiled against that `-SNAPSHOT` version (see the job log in
  `$dataDir/logs`).
- `perflab status` run from a terminal reports "user active": you are typing in it. That is expected;
  the daemon's own decision is in its log.

## Database size (task count)

The task-authorization benchmark creates its own data in a fresh database and times authorized task
queries; the cost depends on how many tasks exist. Without `tasks` in the benchmark's config entry
it measures one size (5000 tasks). With `tasks: [1000, 5000, 20000]` every size is its own series
named `task-authorization@<tasks>`: its own jobs, its own entry in the Grafana `benchmark` variable
and its own regression baseline. Sizes are never mixed. Changing the list starts new series; old
series keep their history. `minInterval` (e.g. `6h`, default 20h) sets how soon a series is queued again; each run still waits for
the idle gate. Larger sizes take longer and fill the benchmark database.

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

## Benchmarks

The module `benchmarks/` holds every benchmark; the runner passes the job's id as `-Dbenchmark.id`
(`BenchmarkMain`). Each one runs embedded in a forked JVM on the branch JDK, against H2 in memory or a
fresh PostgreSQL 17 container, with an empty schema per run.

| Id | What it measures |
|---|---|
| `task-authorization` | task queries with authorization (operaton/operaton#3202) |
| `engine-startup` | startup race: fresh JVM to ready engine (schema, deployment, one verified instance) |
| `scenario-a` | scenario A throughput, history `full` |
| `scenario-a-history-none` | scenario A throughput, history `none` (the history switch) |
| `scenario-b` | scenario B throughput: a 100-rule DMN table, history `full` |

### Engine comparison scenarios

`engine-startup` and `scenario-*` follow the video
["BPMN Engine Benchmark: CIB seven, Operaton, Flowable, Kogito and Camunda 8 Speed Test"](https://www.youtube.com/watch?v=-7zLxZNt198)
and its repository [camunda7-alternatives-benchmark](https://github.com/Marcin-Pankowski/camunda7-alternatives-benchmark)
(Apache 2.0; the DMN table and its rules are copied from there, see
`benchmarks/src/main/resources/scenarios/NOTICE.md`).

- Scenario A: start, three service tasks with `${counter + 1}`, end. Every instance is started with
  `executeWithVariablesInReturn` and must return `counter = 3`.
- Scenario B: start, business rule task on the `Pricing` table (100 rules, COLLECT + SUM), end. Inputs are
  random in the ranges of the video's load script but with a fixed seed, so every run evaluates the same
  requests; each instance must return the fee computed from the same rules in Java.
- Engine as in the video's Spring Boot app: UUID ids, a pool of 10 connections, no job executor.
- One iteration runs a batch of `benchmark.instances` (2000) instances for each concurrency level of
  `benchmark.concurrency` (1, 10, 25, 50 threads, the video's virtual users); operation `concurrency-<n>` is
  the batch's wall time. Defaults: 2 warmup and 10 timed iterations (on PostgreSQL about 3–4 min per repetition). A single wrong result fails the run.
- Derived metrics per run (`benchmark.metric`, shown in the panels "Throughput per concurrency level" and
  "Latency and memory metrics", not part of the regression detection): `throughput.concurrency-<n>`
  (instances/s from the median batch time), `latencyP50/P95/P99.concurrency-<n>` of single instances, and for
  `engine-startup` `idleHeapAfterGc` and `idleRss` of the ready JVM.
- `engine-startup` forks `StartupProbe` per iteration (1 warmup, 10 timed) with JVM defaults; operations
  `jvmBoot`, `buildEngine` (incl. schema creation), `deploy`, `firstInstance`.

Not covered, because they need the video's Kubernetes setup rather than one engine under test: scaling over
several pods behind a load balancer, REST and network overhead, and the comparison with the other engines
(CIB seven could later run the same scenarios). The numbers are for trends of Operaton commits on this Mac,
not for comparison with the video's absolute values.

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
