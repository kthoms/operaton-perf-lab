# Operaton Perf Lab — First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A daemon on this Mac that builds Operaton branches and runs the task-authorization benchmark whenever the machine is predicted to stay idle, stores test and benchmark timings in PostgreSQL, and shows trends and regression flags in Grafana.

**Architecture:** One Maven project with two modules: `runner` (Java CLI/daemon: job queue, idle gate, executors, extractors, regression detection) and `benchmarks` (benchmark code compiled against the Operaton engine version under test). PostgreSQL (results) and Grafana run via Docker Compose on Colima. `launchd` keeps the daemon alive. The database is the single source of truth.

**Tech Stack:** Java 25 (host JDK), Maven 3.9+, picocli 4.7.6, PostgreSQL JDBC 42.7.4, Flyway 11.x (`flyway-database-postgresql`), Jackson 2.18 (+ YAML), JUnit 5.11, AssertJ 3.26, Testcontainers 1.20 (`postgresql`), Docker Compose on Colima, PostgreSQL 17, Grafana 11.

**Spec:** `docs/superpowers/specs/2026-11-21-operaton-perf-lab-design.md`

## Global Constraints

- Repo root: `~/projects/operaton-perf-lab`. Java package root: `io.github.kthoms.perflab`.
- **The results DB is the single source of truth**; every result row carries the full Operaton commit SHA, branch, database, and environment fingerprint.
- Build scope (fixed): `clean install` with unit tests on H2, no frontend build, no `qa/*` app-server integration tests. The exact Maven command line lives in config, per branch, and is stored with every run.
- Only commits reachable from a branch of upstream `https://github.com/operaton/operaton.git` may be built. Fork/PR heads are rejected.
- No fixed schedule: a job starts only if the Mac is idle now **and** the predicted idle time ≥ the job's expected duration.
- User activity during a job → cancel, kill the whole process tree, discard partial results, requeue. Cancelled runs are never stored.
- All large data (Colima disk, Maven local repo, checkouts, logs) lives under the configured `dataDir` (the NVMe). If `dataDir` is missing, the daemon starts no jobs. It never falls back to the internal disk.
- Results PostgreSQL binds to `127.0.0.1:5433` only. Grafana binds to `0.0.0.0:3000` with login enabled (anonymous access off).
- Nothing triggered from GitHub executes on this Mac. Repo CI, if any, uses GitHub-hosted runners.
- Fingerprint change ⇒ new baseline segment. Regression detection never compares across segments.

## Review Focus

1. **Operaton build fails** (compile error or failing tests). Expected: the run is stored with status `FAILED`, the log path, and no measurements. The job is not requeued. The next daily job tries again. → test in Task 8.
2. **User becomes active mid-job.** Expected: Maven and all forked Surefire JVMs and any benchmark DB container are gone within 10 s, nothing is stored, and the job is `QUEUED` again. → test in Task 6 (process tree) and Task 8 (requeue).
3. **Mac slept, crashed or rebooted during a job** (row stuck in `RUNNING`). Expected: on daemon start, stale `RUNNING` jobs are requeued. → test in Task 3.
4. **NVMe unplugged / `dataDir` missing.** Expected: the daemon logs a clear message every tick and starts no jobs. → test in Task 2.
5. **Docker/Colima or the results DB down at daemon start.** Expected: the daemon retries with backoff (max 5 min) instead of exiting, so `launchd` doesn't spin. → test in Task 8.

---

### Task 1: Repository scaffold, infrastructure, and schema

**Files:**
- Create: `pom.xml` (parent, modules `runner`, `benchmarks`), `runner/pom.xml`, `.gitignore`, `README.md`
- Create: `infra/docker-compose.yml`, `infra/.env.example`
- Create: `runner/src/main/resources/db/migration/V1__init.sql`
- Create: `runner/src/main/java/io/github/kthoms/perflab/db/Database.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/db/DatabaseIT.java`

**Interfaces:**
- Produces: `Database.connect(String jdbcUrl, String user, String password): Database` (runs Flyway migrate), `Database.dataSource(): javax.sql.DataSource`.
- Produces schema (V1):
  - `job(id bigserial pk, kind text check in ('BUILD','BENCHMARK'), branch text null, commit_sha text null, benchmark text null, db text null, priority int, status text check in ('QUEUED','RUNNING','DONE','FAILED','NOT_APPLICABLE'), cancel_requested bool default false, attempts int default 0, created_at timestamptz, started_at timestamptz null, finished_at timestamptz null)`
  - `run(id bigserial pk, job_id bigint fk, kind text, branch text, commit_sha text not null, db text, benchmark text null, status text check in ('SUCCESS','FAILED'), started_at, finished_at, fingerprint jsonb, segment text not null, command_line text, log_path text)`
  - `measurement(id bigserial pk, run_id bigint fk, kpi text, value double precision, unit text, dims jsonb)` + index on `(kpi)` and GIN on `dims`
  - `activity_sample(ts timestamptz pk, user_idle_seconds int, load1 double precision)`
  - `regression(id bigserial pk, run_id fk, series_key text, kpi text, dims jsonb, value double precision, baseline_median double precision, last_good_sha text, compare_url text, created_at)`
  - `backup_log(ts timestamptz pk, path text)`

- [ ] **Step 1: Write the failing test** — Testcontainers `postgres:17`:

```java
@Test void migratesSchema() {
  var db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  assertThat(tables(db)).contains("job","run","measurement","activity_sample","regression","backup_log");
}
```

- [ ] **Step 2: Run** `mvn -pl runner verify -Dit.test=DatabaseIT` → FAIL (class missing).
- [ ] **Step 3: Implement** `Database` (HikariCP not needed; `PGSimpleDataSource`) and `V1__init.sql` as above. Compose: services `results-db` (`postgres:17`, `127.0.0.1:5433:5432`, volume `${PERFLAB_DATA_DIR}/pgdata`) and `grafana` (`grafana/grafana-oss:11.3.0`, `3000:3000`, `GF_AUTH_ANONYMOUS_ENABLED=false`, volume `${PERFLAB_DATA_DIR}/grafana`, mounts `../grafana/provisioning`). Credentials from `infra/.env` (git-ignored; `.env.example` committed).
- [ ] **Step 4: Run** the test → PASS. Run `docker compose -f infra/docker-compose.yml config` → valid.
- [ ] **Step 5: Commit** `git init` (first task only), `git add -A && git commit -m "feat: scaffold perf lab, infra and schema"`.

### Task 2: Configuration and data-dir guard

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/config/PerfLabConfig.java`, `ConfigLoader.java`, `DataDirGuard.java`
- Create: `config/perflab.example.yml`
- Test: `runner/src/test/java/io/github/kthoms/perflab/config/ConfigLoaderTest.java`, `DataDirGuardTest.java`

**Interfaces:**
- Produces: `record PerfLabConfig(Path dataDir, Path backupDir, int backupRetentionDays, String upstreamUrl, Db resultsDb, List<BranchConfig> branches, List<BenchmarkConfig> benchmarks, IdleConfig idle)`
  - `record BranchConfig(String name, Path javaHome, List<String> mavenArgs)`; default `mavenArgs` for `main` in the example: `["-Pdistro,distro-webjar-neo,h2-in-memory","-Dskip.frontend.build=true","clean","install"]`. This mirrors Operaton's own `fast` build profile; Task 13 verifies it matches the agreed scope.
  - `record BenchmarkConfig(String id, List<String> databases, List<String> branches, int repetitions)`; example: `task-authorization`, `["h2","postgresql"]`, `["main","release/2.2.x"]`, 5.
  - `record IdleConfig(Duration minUserIdle /*15m*/, double maxLoad1 /*2.0*/, List<String> heavyProcessNames /*["java","mvn","gradle","idea"] excluding own children*/, Duration pollInterval /*1m*/, Duration activityCheckInterval /*5s*/)`
- Produces: `ConfigLoader.load(Path yml): PerfLabConfig`; `DataDirGuard.check(PerfLabConfig): Optional<String>` (empty = OK, otherwise the reason).
- Derived paths (helpers on `PerfLabConfig`): `mavenRepo() = dataDir/m2`, `checkout() = dataDir/work/operaton`, `logs() = dataDir/logs`.

- [ ] **Step 1: Write failing tests**

```java
@Test void loadsExample() { var c = ConfigLoader.load(Path.of("../config/perflab.example.yml"));
  assertThat(c.branches()).extracting(BranchConfig::name).containsExactly("main","release/2.2.x");
  assertThat(c.idle().minUserIdle()).isEqualTo(Duration.ofMinutes(15)); }
@Test void missingDataDirBlocks(@TempDir Path t) { var c = cfgWithDataDir(t.resolve("absent"));
  assertThat(DataDirGuard.check(c)).hasValueSatisfying(m -> assertThat(m).contains("absent")); }
@Test void dataDirMustBeWritable(@TempDir Path t) { /* read-only dir -> non-empty reason */ }
```

- [ ] **Step 2: Run** `mvn -pl runner test -Dtest='ConfigLoaderTest,DataDirGuardTest'` → FAIL.
- [ ] **Step 3: Implement** with Jackson YAML. Durations as ISO-8601 or `15m` style; pick one and use it in the example.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: config loading and data dir guard`.

### Task 3: Job queue

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/jobs/Job.java`, `JobKind.java`, `JobStatus.java`, `JobRepository.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/jobs/JobRepositoryIT.java`

**Interfaces:**
- Consumes: `Database` (Task 1).
- Produces: `record Job(long id, JobKind kind, String branch, String commitSha, String benchmark, String db, int priority, JobStatus status, int attempts)`
- Priorities (constants on `Job`): `PRIORITY_MANUAL = 0`, `PRIORITY_BUILD = 10`, `PRIORITY_BENCHMARK = 20` (lower runs first).
- `JobRepository`: `long enqueue(JobKind, String branch, String commitSha, String benchmark, String db, int priority)`, `List<Job> queued()` (ordered by priority, created_at), `boolean markRunning(long id)` (atomic `QUEUED→RUNNING`, increments attempts), `void finish(long id, JobStatus status)`, `void requeue(long id)` (`RUNNING→QUEUED`, clears `cancel_requested`), `int requeueStale()` (all `RUNNING` → `QUEUED`; called at daemon start), `void requestCancel()` (sets the flag on the `RUNNING` job), `boolean cancelRequested(long id)`, `boolean hasOpen(JobKind, String branch, String benchmark, String db)` (`QUEUED` or `RUNNING`).

- [ ] **Step 1: Write failing tests:** `queuedOrdersByPriorityThenAge`, `markRunningIsAtomic` (second call returns false), `requeueStaleRecoversRunningJobs` (Review Focus 3), `requestCancelFlagsOnlyRunningJob`, `requeueClearsCancelFlag`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** with plain JDBC. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: job queue`.

### Task 4: Results, fingerprint, segment

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/results/Measurement.java`, `RunRecord.java`, `ResultRepository.java`
- Create: `runner/src/main/java/io/github/kthoms/perflab/env/Fingerprint.java`, `FingerprintCollector.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/results/ResultRepositoryIT.java`, `env/FingerprintTest.java`

**Interfaces:**
- Produces: `record Measurement(String kpi, double value, String unit, Map<String,String> dims)`
- `record RunRecord(long jobId, JobKind kind, String branch, String commitSha, String db, String benchmark, String status, Instant startedAt, Instant finishedAt, Fingerprint fingerprint, String commandLine, Path logPath)`
- `ResultRepository.save(RunRecord, List<Measurement>): long runId` — one transaction.
- `ResultRepository.recentDurations(JobKind, String branch, String benchmark, String db, int limit): List<Duration>` (successful runs only, newest first).
- `record Fingerprint(Map<String,String> stable, Map<String,String> volatileInfo)`; `Fingerprint.segment(): String` = first 12 hex chars of SHA-256 over the sorted `stable` entries.
  - `stable` keys: `hw.model`, `hw.cpu`, `hw.memBytes`, `os.version`, `java.version` (of the branch's `javaHome`), `maven.version`, `colima.cpus`, `colima.memory`, `dataDir`, `mavenArgs`.
  - `volatileInfo` keys: `load1.start`, `userIdle.start`.
- `FingerprintCollector.collect(BranchConfig, PerfLabConfig): Fingerprint` (uses `sysctl`, `sw_vers`, `<javaHome>/bin/java -version`, `colima list --json`).

- [ ] **Step 1: Write failing tests:** `sameStableInfoSameSegment` (volatile values differ → equal segment), `changedJavaVersionNewSegment`, `saveStoresRunAndMeasurementsAtomically` (a failing measurement insert rolls back the run), `recentDurationsOnlySuccessful`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: result storage and environment fingerprint`.

### Task 5: KPI extractors for builds

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/extract/SurefireExtractor.java`, `ReactorSummaryExtractor.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/extract/SurefireExtractorTest.java`, `ReactorSummaryExtractorTest.java` + fixtures under `runner/src/test/resources/fixtures/`

**Interfaces:**
- Produces: `SurefireExtractor.extract(Path checkoutRoot): List<Measurement>` — scans `**/target/surefire-reports/TEST-*.xml` (skip `node_modules`). Module = path of the report relative to `checkoutRoot`, minus `/target/...` (e.g. `engine`).
  - `test.class.duration` (s), dims `{module, class}` from `<testsuite name time>`
  - `test.module.duration` (s), dims `{module}` = sum over classes
  - `test.module.count`, dims `{module}` = sum of `tests`
- Produces: `ReactorSummaryExtractor.extract(Path buildLog): List<Measurement>`
  - `build.module.wall` (s), dims `{module: <reactor display name>}` from lines like `[INFO] Operaton - Engine ..... SUCCESS [01:02 min]`
  - `build.total.wall` (s) from `[INFO] Total time:  1:02:03 h`
  - Time formats to parse: `1.234 s`, `01:02 min`, `1:02:03 h`.

- [ ] **Step 1: Write failing tests** with small fixture trees and a fixture log:

```java
assertThat(SurefireExtractor.extract(fixtureRoot)).contains(
  new Measurement("test.module.duration", 3.5, "s", Map.of("module","engine")),
  new Measurement("test.class.duration", 2.0, "s", Map.of("module","engine","class","org.x.ATest")));
assertThat(ReactorSummaryExtractor.extract(log)).contains(
  new Measurement("build.module.wall", 62.0, "s", Map.of("module","Operaton - Engine")),
  new Measurement("build.total.wall", 3723.0, "s", Map.of()));
```

  Also `ignoresUnparseableLines` and `nestedModulePath` (e.g. `engine-plugins/spin-plugin`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (StAX for XML, regex for the log). **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: surefire and reactor summary extractors`.

### Task 6: Process execution and Operaton checkout

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/exec/ProcessRunner.java`, `CancellationToken.java`
- Create: `runner/src/main/java/io/github/kthoms/perflab/git/OperatonCheckout.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/exec/ProcessRunnerTest.java`, `git/OperatonCheckoutTest.java`

**Interfaces:**
- Produces: `interface CancellationToken { boolean isCancelled(); }`
- `ProcessRunner.run(List<String> cmd, Path workDir, Map<String,String> env, Path logFile, CancellationToken token, Duration checkEvery): Result` where `record Result(int exitCode, boolean cancelled, Duration elapsed)`. Wraps the command in `caffeinate -i`. On cancel it destroys the **whole descendant tree** (`ProcessHandle.descendants()`, then the root; forcibly after 10 s).
- `OperatonCheckout(Path dir, String upstreamUrl, ProcessRunner)`: `void ensureCloned()`, `void fetch()`, `String resolveBranch(String branch)` (→ SHA of `origin/<branch>`), `boolean isReachableFromUpstream(String sha)` (`git branch -r --contains <sha>` non-empty, after fetch), `void checkout(String sha)` (`checkout --detach`, `clean -ffdx`, `reset --hard`).

- [ ] **Step 1: Write failing tests:**
  - `cancelKillsDescendants` — runs `sh -c 'sleep 300 & sleep 300 & wait'`, cancels after 1 s, asserts `cancelled` and no descendant PIDs alive within 10 s (Review Focus 2).
  - `logsCombinedOutput`, `returnsExitCode`.
  - Checkout tests against a local bare repo fixture created in `@TempDir`: `rejectsUnreachableCommit` (a commit only on a ref outside `refs/heads`), `checkoutLeavesCleanTree`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: process runner with tree cancellation and checkout`.

### Task 7: Idle detection, prediction, and duration estimates

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/idle/ActivityProbe.java`, `MacActivityProbe.java`, `ActivityHistory.java`, `IdleGate.java`
- Create: `runner/src/main/java/io/github/kthoms/perflab/jobs/DurationEstimator.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/idle/IdleGateTest.java`, `ActivityHistoryIT.java`, `MacActivityProbeTest.java`, `jobs/DurationEstimatorTest.java`

**Interfaces:**
- Produces: `interface ActivityProbe { Duration userIdle(); double load1(); boolean onAcPower(); List<String> heavyForeignProcesses(); }` (the macOS-specific seam the spec requires).
- `MacActivityProbe`: `ioreg -c IOHIDSystem` → `HIDIdleTime` (ns), `sysctl -n vm.loadavg`, `pmset -g batt` (`AC Power`), `ps -Ao pid,ppid,comm` filtered by `heavyProcessNames` and excluding descendants of the daemon. Parsing is a pure static method per command, unit-tested against captured output.
- `ActivityHistory(Database)`: `void record(Instant ts, Duration userIdle, double load1)`; `Duration predictedIdle(Instant now)`. Algorithm:

  ```
  slot = 15-minute slot of the week (0..671) in the system time zone
  for each slot: pActive = share of samples in the last 8 weeks with userIdle < 5 min
  slots with < 4 samples count as pActive = 0.5 (unknown → not idle)
  predictedIdle = 15 min × number of consecutive slots starting at now's slot with pActive < 0.2
  ```
- `IdleGate(ActivityProbe, ActivityHistory, IdleConfig)`: `Decision mayStart(Duration expected, Instant now)`; `record Decision(boolean ok, String reason)`. Requires: `userIdle ≥ minUserIdle`, `load1 ≤ maxLoad1`, AC power, no heavy foreign processes, `predictedIdle ≥ expected`.
- `IdleGate.userActive(): boolean` = `userIdle < activityCheckInterval × 2` (used by the cancel watchdog).
- `DurationEstimator(ResultRepository)`: `Duration expected(Job)` = median of the last 5 successful runs of the same (kind, branch, benchmark, db); default 90 min for `BUILD`, 20 min for `BENCHMARK`.

- [ ] **Step 1: Write failing tests:** `blocksWhenPredictedIdleTooShort`, `blocksOnBattery`, `blocksOnForeignHeavyProcess`, `allowsWhenAllConditionsHold` (fake probe and fixed history); `unknownSlotsAreNotIdle`, `predictsConsecutiveIdleSlots` (seed 8 weeks of samples); `parsesHidIdleTime`, `parsesLoadAvg`, `parsesPmsetBattery`; `medianOfLastFive`, `defaultWithoutHistory`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: idle gate with activity-based prediction`.

### Task 8: Build executor and daemon loop

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/jobs/JobExecutor.java`, `BuildJobExecutor.java`, `Scheduler.java`
- Create: `runner/src/main/java/io/github/kthoms/perflab/daemon/Daemon.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/jobs/BuildJobExecutorTest.java`, `SchedulerIT.java`, `daemon/DaemonTest.java`

**Interfaces:**
- Consumes: Tasks 2–7.
- Produces: `interface JobExecutor { Outcome execute(Job job, CancellationToken token); }`; `sealed interface Outcome permits Completed, Cancelled, NotApplicable` where `record Completed(RunRecord run, List<Measurement> measurements)` (status `SUCCESS` or `FAILED`).
- `BuildJobExecutor`: resolves the SHA (branch → `resolveBranch`, commit → `isReachableFromUpstream` or `NotApplicable`), checks out, runs `<checkout>/mvnw <branch.mavenArgs> -Dmaven.repo.local=<mavenRepo>` with `JAVA_HOME=<branch.javaHome>`, log at `logs/<jobId>-<sha>.log`. On exit 0: measurements = Surefire + reactor extractors. On non-zero: `FAILED`, no measurements (Review Focus 1). For a commit job without a branch, the branch config is the one whose `origin/<branch>` contains the SHA (main preferred).
- `Scheduler(JobRepository, ResultRepository, PerfLabConfig)`: `void ensureDueJobs(Instant now)`. For each configured branch build and each benchmark × db × branch: if no open job and the last stored run is older than 20 h (or none), enqueue it with its default priority.
- `Daemon(...)`: `void runForever()`. Startup: `requeueStale()`; DB connect with exponential backoff 5 s → 5 min (Review Focus 5). Each tick (`pollInterval`): record an activity sample, `DataDirGuard.check` (skip the tick with a log line if it fails), `Scheduler.ensureDueJobs`, then the first queued job whose `DurationEstimator.expected` passes `IdleGate.mayStart` → `markRunning` → execute with a token that is cancelled when `IdleGate.userActive()` or `cancelRequested(jobId)`. `Completed` → `save` + `finish(DONE|FAILED)`; `Cancelled` → `requeue`; `NotApplicable` → `finish(NOT_APPLICABLE)`. After a run: `RegressionDetector.analyze(runId)` (Task 12; no-op until then) and the backup check (Task 9).

- [ ] **Step 1: Write failing tests** (fakes for probe, checkout and process runner):
  - `failedBuildStoredWithoutMeasurementsAndNotRequeued` (Review Focus 1)
  - `userActivityCancelsAndRequeues` (Review Focus 2)
  - `missingDataDirStartsNoJob` (Review Focus 4)
  - `dbUnavailableRetriesWithBackoff` (Review Focus 5; injectable clock/sleeper, asserts backoff sequence capped at 5 min)
  - `schedulerEnqueuesDueBuildsOnce`, `unreachableCommitIsNotApplicable`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: build executor and daemon loop`.

### Task 9: CLI, launchd, and backup

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/cli/PerfLabCli.java` (picocli, shaded jar `runner/target/perflab.jar`)
- Create: `runner/src/main/java/io/github/kthoms/perflab/backup/Backup.java`
- Create: `ops/launchd/io.github.kthoms.perflab.plist`, `ops/install-launchd.sh`
- Test: `runner/src/test/java/io/github/kthoms/perflab/cli/PerfLabCliTest.java`, `backup/BackupTest.java`

**Interfaces:**
- CLI (all take `--config <path>`, default `~/.config/perflab/perflab.yml`):
  - `daemon`
  - `enqueue (--branch <b> | --commit <sha>) [--benchmark <id> --db <db>]` → manual priority 0
  - `cancel` → `requestCancel()`
  - `status` → running job, queue, last run per series, idle decision with reason, and **`release/*` branches on upstream that aren't in config** (`OperatonCheckout.remoteReleaseBranches(): List<String>`, added in this task). The spec says new branches are reported, never added automatically.
  - `backup` → `Backup.run()`
- `Backup(PerfLabConfig)`: `Path run()` runs `docker compose exec -T results-db pg_dump -Fc` into `backupDir/perflab-<yyyyMMdd-HHmm>.dump`, deletes dumps older than `backupRetentionDays` (14), writes `backup_log`. `boolean due(Instant now)` = last backup > 24 h ago. The daemon calls it between jobs only.
- plist: `RunAtLoad` + `KeepAlive`, runs `java -jar <repo>/runner/target/perflab.jar daemon`, stdout/err to `~/Library/Logs/perflab/`. `install-launchd.sh` copies it to `~/Library/LaunchAgents` and runs `launchctl bootstrap gui/$UID`.

- [ ] **Step 1: Write failing tests:** `enqueueCommitUsesManualPriority`, `enqueueRequiresBranchOrCommit`, `backupRetentionDeletesOldDumps` (fake command runner, `@TempDir`), `backupDueAfter24h`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `plutil -lint ops/launchd/*.plist` → OK.
- [ ] **Step 5: Commit** `feat: CLI, launchd agent and backup`.

### Task 10: Grafana provisioning and build dashboard

**Files:**
- Create: `grafana/provisioning/datasources/perflab.yml` (PostgreSQL, `results-db:5432`, read-only user `grafana_ro` created in `V2__grafana_ro.sql`)
- Create: `grafana/provisioning/dashboards/perflab.yml`, `grafana/dashboards/build-tests.json`
- Create: `runner/src/main/resources/db/migration/V2__grafana_ro.sql`

**Interfaces:**
- Dashboard `build-tests` with variables `branch` (from `run.branch`) and `segment`:
  1. Test time per module over time (`test.module.duration`, one series per module, `kind='BUILD' and status='SUCCESS'`)
  2. Engine module alone (`dims->>'module'='engine'`)
  3. Top 20 slowest engine test classes, last run (table) + trend for a selected class
  4. Total build wall time (`build.total.wall`)
  5. Annotations from `regression` (Task 12) and from segment changes (a run whose segment differs from the previous run of the same branch)

- [ ] **Step 1:** Add the provisioning files and dashboard JSON; queries use `$__timeFilter(run.started_at)`.
- [ ] **Step 2: Verify:** `docker compose -f infra/docker-compose.yml up -d`, seed a few runs with `src/test/resources/seed.sql`, open `http://localhost:3000`, log in, and check that all panels render without query errors (also via `GET /api/dashboards/uid/build-tests` → 200).
- [ ] **Step 3: Commit** `feat: grafana provisioning and build test dashboard`.

### Task 11: Task-authorization benchmark

**Files:**
- Create: `benchmarks/pom.xml` (property `operaton.version`, deps `org.operaton.bpm:operaton-engine:${operaton.version}`, `com.h2database:h2`, `org.postgresql:postgresql`)
- Create: `benchmarks/src/main/java/io/github/kthoms/perflab/bench/TaskAuthorizationBenchmark.java`, `BenchmarkResult.java`
- Create: `runner/src/main/java/io/github/kthoms/perflab/jobs/BenchmarkJobExecutor.java`, `runner/src/main/java/io/github/kthoms/perflab/extract/BenchmarkJsonExtractor.java`
- Create: `grafana/dashboards/benchmarks.json`
- Test: `benchmarks/src/test/java/.../TaskAuthorizationBenchmarkTest.java`, `runner/src/test/java/.../extract/BenchmarkJsonExtractorTest.java`, `jobs/BenchmarkJobExecutorTest.java`

**Interfaces:**
- Port the logic of `TaskAuthorizationExistsBenchmark` from [operaton/operaton#3202](https://github.com/operaton/operaton/pull/3202) (`gh pr diff 3202 --repo operaton/operaton`). Keep its parameters (`tasks`, `groups`, `warmup`, `iterations`). Replace its output with a JSON file: `{"benchmark":"task-authorization","database":..,"params":{..},"iterationsMs":[..]}` written to the path given by `-Dbenchmark.output`.
- `BenchmarkJsonExtractor.extract(Path json): List<Measurement>`: `benchmark.iteration.duration` (ms, dims `{benchmark, iteration}`) and `benchmark.median.duration` (ms, dims `{benchmark}`).
- `BenchmarkJobExecutor`: resolves and checks out the SHA (as in Task 8). Untimed prep: `mvnw install -DskipTests -Pdistro -Dskip.frontend.build=true -pl engine -am`. Reads `operaton.version` with `help:evaluate`. Compiles `benchmarks` against that version; on compile failure → `NotApplicable`. For `postgresql`: `docker run --rm -d -p 127.0.0.1:5434:5432 postgres:17` with a fresh DB per run, removed afterwards and on cancel. Repeats `repetitions` times in one run; `dims.repetition` added to each measurement.

- [ ] **Step 1: Write failing tests:** `writesJsonResult` (H2 in-memory, tiny params, against the Operaton version pinned in `benchmarks/pom.xml` for tests); `extractsIterationsAndMedian`; `compileFailureIsNotApplicable`; `postgresContainerRemovedOnCancel` (fake command runner records `docker rm -f`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5:** Add the `benchmarks` dashboard: median per database per branch over time, iteration spread (box/min-max). Verify it renders with seeded data.
- [ ] **Step 6: Commit** `feat: task authorization benchmark`.

### Task 12: Regression detection

**Files:**
- Create: `runner/src/main/java/io/github/kthoms/perflab/analysis/RegressionDetector.java`
- Test: `runner/src/test/java/io/github/kthoms/perflab/analysis/RegressionDetectorIT.java`

**Interfaces:**
- `RegressionDetector(Database)`: `List<Regression> analyze(long runId)`. Series = (kpi, dims without `repetition`/`iteration`, branch, db, segment). Only for KPIs `test.module.duration`, `build.total.wall`, `benchmark.median.duration` (per-class is too noisy).
  - Baseline: the last 10 successful runs of the series **in the same segment**, before this run. At least 5 are needed, otherwise no verdict.
  - `threshold = median + max(3 × MAD, 0.03 × median)`
  - Flag when this run **and** the previous run both exceed the threshold. Store a `regression` row with `last_good_sha` = the newest baseline run under the threshold and `compare_url = https://github.com/operaton/operaton/compare/<last_good_sha>...<sha>`.

- [ ] **Step 1: Write failing tests:** `singleOutlierNotFlagged`, `twoConsecutiveOutliersFlagged` (asserts the compare URL), `noVerdictWithFewerThanFiveRuns`, `otherSegmentIgnored`, `smallMadUsesThreePercentFloor`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** and wire it into `Daemon` (Task 8). **Step 4: Run** → PASS. Check that the Grafana annotations from Task 10 show seeded regressions with clickable compare links.
- [ ] **Step 5: Commit** `feat: regression detection`.

### Task 13: First real run on the NVMe (manual, with the owner)

Blocked until the NVMe is attached.

- [ ] **Step 1:** Format/mount the NVMe (owner). Set `dataDir` to it. Move the Colima disk: `colima stop && colima delete && colima start --cpu 6 --memory 8 --disk 100` with `COLIMA_HOME` (or a symlink) on the NVMe.
- [ ] **Step 2:** Pick a JDK per branch (Temurin 21 for `release/2.2.x` and `main`, unless Operaton's CI uses something else). Write `~/.config/perflab/perflab.yml`.
- [ ] **Step 3:** Run the build command for `main` once by hand. Confirm that no `webapps` frontend build and no `qa/*` app-server integration tests ran, and that Surefire reports exist for `engine`. Adjust `mavenArgs` if needed. **This fixes the baseline; later changes start a new segment.**
- [ ] **Step 4:** `docker compose up -d`, `ops/install-launchd.sh`, `perflab status` shows an idle decision. Enqueue `main` manually and watch one full run land in Grafana.
- [ ] **Step 5:** Test cancellation for real: touch the mouse during a run → `perflab status` shows the job queued again and no Java process left over.

### Task 14: Publish the repo and set up humanmaxxing (ask the owner first)

- [ ] **Step 1:** With the owner's OK: `gh repo create kthoms/operaton-perf-lab --public --source . --push`.
- [ ] **Step 2:** Install humanmaxxing in the repo (paste `Read https://humanmaxxing.ai/start.md and follow it.` into Claude Code; the owner answers its interview). Let it propose agents. Make sure no agent workflow runs builds or benchmarks on this Mac outside the daemon.
- [ ] **Step 3:** Create GitHub issues for the next agent-driven work: `qa/performance-tests-engine` benchmark profile (scale-up across thread counts), Operaton unit tests on PostgreSQL as a separate job type, more databases (MySQL, MariaDB, SQL Server; Oracle/DB2 where arm64 images work), Grafana alert by email, optional publishing of excerpts.
