# Operaton Perf Lab — Design

Date: 2026-11-21 · Status: draft for approval · Owner: Karsten Thoms

## Intent and why

Use a well-equipped, mostly idle Mac (nights, and working hours when the owner works on other machines) to
measure the performance and scalability of [operaton/operaton](https://github.com/operaton/operaton) **continuously
and comparably over time**. Because the hardware stays the same, numbers from different months can be compared, so
trends and regressions become visible instead of anecdotal.

The perf lab itself is built in a new **public** GitHub repository under the `kthoms` account (working name
`operaton-perf-lab`). It is developed with the [humanmaxxing](https://humanmaxxing.ai) framework: AI agents take
tickets such as "add benchmark X", "extract KPI Y", "add database Z", plan them, implement them, and open PRs, with the
owner approving plans and sign-offs. humanmaxxing runs the **development process**; it does not execute the
measurements.

## Goals

1. Measure scheduled builds of `main` and every live `release/*` branch (currently `release/2.2.x`) on this machine.
   **First KPI: test execution time, per Maven module**, and inside the dominating engine module per test class.
2. Run engine benchmarks on a schedule against the supported databases (H2, PostgreSQL first; later MySQL, MariaDB,
   SQL Server, Oracle, DB2 as far as they run on arm64/containers). First benchmark: the task authorization benchmark
   from [operaton/operaton#3202](https://github.com/operaton/operaton/pull/3202). Operaton's existing
   `qa/performance-tests-engine` `benchmark` profile (JSON results per thread count = scale-up indicator) is the next
   obvious candidate.
3. Store every result in a database together with the exact Operaton commit SHA, branch, database, and environment
   fingerprint.
4. Show trends in Grafana dashboards, with noise bands, and flag regressions so the owner notices them. A flagged point
   links to the GitHub compare range since the last good run.
5. Make it easy for agents to add benchmarks and KPI extractors one ticket at a time.

## Non-goals (for now)

- Public dashboard or publishing results. Excerpts may be published later once data exists; decided then.
- Committing results to git. Optional, later.
- Notifying the Operaton project (issues, PR comments).
- Automatic root-cause analysis. **But the design must allow it later**: an agent will bisect a regression by queueing
  "benchmark commit X" jobs (see Job queue).
- Multiple measurement machines. The perf lab may later move to dedicated hardware. Then the environment fingerprint
  starts a new baseline segment, and the macOS-specific parts (`launchd`, HID idle detection, `caffeinate`) stay behind
  a small interface so they can be swapped out.
- Other development work (including the humanmaxxing agents) running on this Mac. It isn't expected; the idle check
  still looks at heavy processes as a safeguard.

## Anti-goals (failure even if it works)

- **False alarms.** Regression flags driven by noise (a single slow night, the owner working on the Mac, thermal or
  background effects) would make the dashboard useless.
- **Disturbing the owner.** Runs must not compete with interactive use of the Mac.
- **Opening the Mac to outside code.** Nothing triggered from the public repo (forks, PRs) may execute on this machine.
- **Losing data.** The database is the single source of truth, so it must be backed up.

## Constraints

- Machine: Apple M4 (10 cores), 32 GB RAM, macOS 27, internal disk nearly full (~5 GB free).
- Storage: a 1 TB Samsung 990 NVMe in a 40 Gbps hub (on order) holds Colima/Docker data, the Maven repository
  (currently 12 GB in `~/.m2/repository`), Operaton checkouts, and working data. Additional slower SSDs (like the attached
  backup SSD) hold archives (raw logs, DB backups). **Measurement runs start only after the NVMe is in place**;
  development can start earlier.
- Containers: Colima (Docker runtime) is installed, currently 6 CPU / 8 GB VM on the internal disk; it moves to the NVMe.
- Operaton: Maven multi-module build (`./mvnw`), database profiles `h2`, `postgresql`, `mysql`, `mariadb`, `sqlserver`,
  `oracle`, `db2` (+ `mt-*` variants); Surefire/Failsafe XML reports give per-test-class times.
- Operaton's own perf README states the intent this lab shares: relative numbers compared over time, not absolute
  "how fast is it" numbers.

## Approaches considered

| | Approach | Trade-off |
|---|---|---|
| A | Local `launchd` runner, results as JSON in git, static dashboard on GitHub Pages | Public and versioned; dashboard less flexible |
| B | Self-hosted GitHub Actions runner on the Mac | Free scheduling/UI, but GitHub advises against self-hosted runners on public repos (fork PRs could run code on the Mac); cron can't see whether the Mac is idle |
| **C** | **Database + Grafana in containers on the Mac, local runner** | **Chosen by the owner.** Strongest dashboards and ad-hoc queries; data not public by default |

Within C, the owner decided: **the database is the single source of truth**; git storage and publishing are optional
and postponed. The local runner (scheduler + job queue) from A is kept, because Grafana only visualizes.

## Decisions

### Components

1. **Results database — PostgreSQL** (container, own volume on the NVMe). Chosen over a NoSQL/time-series store
   because: comparisons across branches, databases, and commits are relational questions; JSONB columns hold
   benchmark-specific payloads flexibly (the "NoSQL" part); Grafana's PostgreSQL data source is mature; data volume is
   tiny (years of nightly runs fit easily). This instance is **separate** from any PostgreSQL instance under test.
2. **Grafana** (container), dashboards and data source provisioned as code from the repo. The owner uses it on this
   Mac and from other machines in the local network (owner decision). Grafana listens on the LAN with its own login
   enabled, and the PostgreSQL port stays bound to `localhost`. Opening it on the Mac counts as using the machine, so a
   running job gets cancelled and requeued, which is intended. Alerting (e.g. by email) is optional and comes later.
3. **Runner** — a CLI/daemon on the host (not in a container, so builds use the host JDK and native performance),
   written in **Java 21+** so Operaton contributors and the agents work in one language. Started by a `launchd`
   agent. Responsibilities: pick jobs, check idleness, prepare checkout, run build/benchmark, extract KPIs, write results.
4. **KPI extractors** — pluggable, one per data source (Surefire/Failsafe XML, Maven reactor summary, benchmark JSON
   output, …). Each turns raw output into measurements.
5. **Benchmarks** — defined in the perf-lab repo. A benchmark definition says what to run (Maven module/profile/class
   and properties), which databases it supports, how many repetitions, and which extractor reads the output.

### Data model (outline)

- `run`: id, job id, kind (build / benchmark), Operaton repo, branch, **commit SHA**, database, started/finished,
  status, environment fingerprint (JSONB: hardware, macOS version, JDK, Maven, Maven command line, Colima VM size, load average and user
  idle time before and during the run). Cancelled runs are not stored.
- `measurement`: run id, KPI name (e.g. `test.duration`), value, unit, dimensions (JSONB: module, test class,
  thread count, repetition index, …).
- `job`: queued/running/done/failed jobs (see Job queue).
- Raw logs and reports are archived as files (slower SSD), referenced from `run`.

### Scheduling and idleness

- **No fixed schedule times** (owner decision). `launchd` keeps the runner alive. The runner wakes up periodically and
  starts a job only when it expects the machine to stay idle for the job's whole run:
  - the Mac is idle now: no user input for a configurable time (macOS HID idle time), low load, no other heavy
    processes, on power;
  - the **predicted idle time ahead is at least the job's expected duration**. Expected duration comes from past runs
    of the same job (a default for new jobs). Predicted idle time comes from the recorded history of user activity by
    weekday and hour, which the runner logs itself.
- `caffeinate` prevents sleep during a run.
- **If the machine is needed, the run is cancelled** (owner decision): when user input arrives, or on an explicit
  `cancel` command. Partial results are discarded and the job goes back to the queue to be picked up later. So stored
  runs are always undisturbed.
- Desired frequency: every live branch built about once a day, and each benchmark × database combination run about once
  a day, as idle capacity allows.
- *(Builder's call, decided)* **Live branches** are a list in config. The runner reports new `release/*` branches on
  the remote, but never adds them on its own.
- *(Builder's call, decided)* **Priority when the window is too short:** manual/bisect jobs → scheduled branch builds →
  benchmark matrix. A job that is interrupted is discarded (partial results are never stored) and retried in the next
  window. Each series records its own sampling dates, so regression logic counts runs, not nights.

### Environment stability

*(Builder's call, decided)* The JDK used per Operaton branch is pinned in config (a specific Temurin version, not the
host default). Upgrades of JDK, macOS, Colima/Docker, VM size, or storage are made on purpose. Every run stores its
environment fingerprint. A fingerprint change starts a **new baseline segment**: regression detection never compares
across segments, and Grafana marks the change with an annotation. The runner warns, but still runs, when the
fingerprint differs from the last run.

### Job queue

Jobs live in the database. Sources: the schedule, and **manual or agent requests** via the runner CLI
(`enqueue --commit <sha> --benchmark <id> --db <db>`). This one-off path is what a future bisecting agent uses.

*(Builder's call, decided)* The runner fetches only from `operaton/operaton` upstream and accepts only commits that a
branch there can reach. Fork and PR heads are rejected. The only code from outside Operaton that runs is this
perf-lab repo's own benchmark code.

### Measuring the first KPIs

- **Build scope (owner decision, fixed so data stays comparable):** `./mvnw clean install` with unit tests on H2,
  without the frontend/webapp build and without the app-server integration tests under `qa/*`. Per-module and
  per-test-class durations come from the Surefire XML reports; total wall time and per-module wall time come from the
  Maven reactor summary. The exact Maven command line and flags are kept in config and stored with every run.
- Running Operaton's test suite on PostgreSQL (and other databases) is a separate, less frequent job type. It comes
  after the first slice.
- Benchmark from PR 3202 on H2 and PostgreSQL. *(Builder's call, decided)* The perf lab carries benchmarks as its own
  code, compiled against the Operaton engine artifacts the checkout under test has installed (its SNAPSHOT version),
  not as patches to Operaton. They run on `main` and the live `release/*` branches. If a benchmark doesn't compile
  against an older commit, it is recorded as "not applicable" for that commit rather than failing. This keeps
  bisecting back through history possible.

### Regression detection

- Each benchmark runs several repetitions per session; build test times are taken from repeated nightly runs.
- A point is flagged only when it falls clearly outside the noise band of a rolling baseline (e.g. median ± k·MAD over
  the last N undisturbed runs) for consecutive runs — not on a single outlier.
- Grafana shows the band and the flags; a flag links to `https://github.com/operaton/operaton/compare/<lastGood>...<sha>`.

### Backup

Nightly `pg_dump` of the results database to the backup SSD, with retention. Because the DB is the single source of
truth, this is part of the first version.

### Development process

- New public repo `kthoms/operaton-perf-lab` (name TBD by owner), humanmaxxing installed there, agents generated from
  the repo. Work is tracked as GitHub issues and lands via PRs that the owner approves.
- No GitHub-triggered code runs on the Mac. CI for the repo (tests, lint) runs on GitHub-hosted runners only.

## First slice (definition of done)

1. Postgres + Grafana containers running on the NVMe, provisioned from the repo.
2. Runner executes, under `launchd` and the idle-prediction rules, builds of `main` and `release/2.2.x` and stores per-module
   and per-test-class test durations.
3. PR 3202 benchmark runs against H2 and PostgreSQL and stores its timings.
4. Grafana dashboard: test time per module per branch over time (engine module separately, top test classes),
   benchmark per database over time, noise bands, regression flags with compare links.
5. Nightly backup of the results DB.

## Left to the builder

- Exact table/column design, migrations tool, Grafana panel layout.
- Regression statistics parameters (N, k, consecutive count).
- Idle thresholds, polling interval, and the idle-prediction model (start simple, e.g. activity histogram by weekday/hour).
- How the runner isolates Operaton builds (separate checkout per job vs. reused worktrees), Maven settings pointing
  the local repository to the NVMe, and which Maven flags keep builds comparable (e.g. fixed `-T`, offline after
  dependency resolution).
- Repository layout and module structure of the perf-lab repo.
