package io.github.kthoms.perflab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.config.PerfLabConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.BranchConfig;
import io.github.kthoms.perflab.config.PerfLabConfig.IdleConfig;
import io.github.kthoms.perflab.daemon.Daemon;
import io.github.kthoms.perflab.db.Database;
import io.github.kthoms.perflab.env.Fingerprint;
import io.github.kthoms.perflab.exec.CancellationToken;
import io.github.kthoms.perflab.idle.ActivityProbe;
import io.github.kthoms.perflab.jobs.BuildFakes;
import io.github.kthoms.perflab.jobs.Job;
import io.github.kthoms.perflab.jobs.JobExecutor;
import io.github.kthoms.perflab.jobs.JobKind;
import io.github.kthoms.perflab.results.Measurement;
import io.github.kthoms.perflab.results.RunRecord;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One real daemon tick through {@link DaemonWiring}: real PostgreSQL, Flyway, repositories,
 * scheduler, idle gate, activity history and regression detector; only the executor and the
 * activity probe are fakes.
 */
@Testcontainers
class DaemonWiringIT {

  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  static final Fingerprint FINGERPRINT = new Fingerprint(Map.of("hw.model", "Mac"), Map.of());
  static final String SHA = "c".repeat(40);
  /** 03:00 local: inside the default cold-start window, so a fresh history admits the build. */
  static final Instant NOW =
      LocalDateTime.of(2026, 11, 21, 3, 0).atZone(ZoneId.systemDefault()).toInstant();

  @TempDir Path dataDir;
  Database db;
  PerfLabConfig config;

  @BeforeEach
  void setUp() throws Exception {
    BuildFakes.quietLogs(); // the daemon's INFO lines are expected here
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job, activity_sample, backup_log restart identity cascade");
    }
    config =
        new PerfLabConfig(
            dataDir,
            Path.of("/nonexistent-perflab-volume/backups"), // backup hook skips: volume missing
            14,
            "https://example.invalid/operaton.git",
            new PerfLabConfig.Db(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()),
            List.of(new BranchConfig("main", Path.of("/opt/jdk21"), List.of("clean", "install"))),
            List.of(),
            new IdleConfig(
                Duration.ofMinutes(15), 4.0, List.of("java"), Duration.ofMinutes(1), Duration.ofSeconds(5)));
  }

  static class IdleMac implements ActivityProbe {
    @Override
    public Optional<Duration> userIdle() {
      return Optional.of(Duration.ofHours(2));
    }

    @Override
    public double load1() {
      return 0.3;
    }

    @Override
    public boolean onAcPower() {
      return true;
    }

    @Override
    public List<String> heavyForeignProcesses() {
      return List.of();
    }
  }

  /** Completes every job with an engine test time of {@code value} seconds. */
  static class FakeBuild implements JobExecutor {
    final double value;
    final List<Long> executed = new ArrayList<>();

    FakeBuild(double value) {
      this.value = value;
    }

    @Override
    public Outcome execute(Job job, CancellationToken token) {
      executed.add(job.id());
      RunRecord run =
          new RunRecord(
              job.id(), JobKind.BUILD, job.branch(), SHA, null, null, "SUCCESS", NOW, NOW.plusSeconds(60),
              FINGERPRINT, "mvnw clean install", Path.of("/logs/x.log"), RunRecord.sourceOf(job));
      return new Completed(
          run, List.of(new Measurement("test.module.duration", value, "s", Map.of("module", "engine"))));
    }
  }

  /** Runs one tick: the first poll sleep interrupts the loop. */
  void tickOnce(FakeBuild build) {
    Daemon daemon =
        DaemonWiring.create(
            config,
            Path.of("/nonexistent/docker-compose.yml"),
            new DaemonWiring.Overrides(
                Map.of(JobKind.BUILD, build), new IdleMac(), Clock.fixed(NOW, ZoneId.systemDefault()),
                d -> {
                  if (d.equals(config.idle().pollInterval())) {
                    throw new InterruptedException("one tick only");
                  }
                }));
    daemon.runForever();
    assertThat(Thread.interrupted()).isTrue(); // restored by runForever, cleared here
  }

  /** Earlier scheduled runs of main days ago, the newest one already slow (an outlier so far). */
  void seedHistory() throws Exception {
    double[] values = {100, 102, 98, 101, 99, 100, 103, 97, 100, 101, 200};
    for (int i = 0; i < values.length; i++) {
      Instant started = NOW.minus(Duration.ofDays(values.length + 1 - i));
      try (Connection c = db.dataSource().getConnection()) {
        long job;
        try (PreparedStatement ps =
            c.prepareStatement(
                "insert into job(kind, branch, priority, status, started_at, finished_at)"
                    + " values ('BUILD','main',10,'DONE',?,?) returning id")) {
          ps.setTimestamp(1, Timestamp.from(started));
          ps.setTimestamp(2, Timestamp.from(started.plusSeconds(60)));
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            job = rs.getLong(1);
          }
        }
        long run;
        try (PreparedStatement ps =
            c.prepareStatement(
                "insert into run(job_id, kind, branch, commit_sha, status, started_at, finished_at, segment)"
                    + " values (?, 'BUILD', 'main', ?, 'SUCCESS', ?, ?, ?) returning id")) {
          ps.setLong(1, job);
          ps.setString(2, "sha" + i);
          ps.setTimestamp(3, Timestamp.from(started));
          ps.setTimestamp(4, Timestamp.from(started.plusSeconds(60)));
          ps.setString(5, FINGERPRINT.segment());
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            run = rs.getLong(1);
          }
        }
        try (PreparedStatement ps =
            c.prepareStatement(
                "insert into measurement(run_id, kpi, value, unit, dims)"
                    + " values (?, 'test.module.duration', ?, 's', '{\"module\":\"engine\"}')")) {
          ps.setLong(1, run);
          ps.setDouble(2, values[i]);
          ps.executeUpdate();
        }
      }
    }
  }

  String one(String sql) throws Exception {
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  @Test
  void tickSchedulesRunsAndStoresTheBuild() throws Exception {
    FakeBuild build = new FakeBuild(100);

    tickOnce(build);

    assertThat(build.executed).hasSize(1);
    long jobId = build.executed.getFirst();
    assertThat(one("select status from job where id=" + jobId)).isEqualTo("DONE");
    assertThat(one("select kind || ' ' || branch || ' ' || coalesce(commit_sha, '-') from job where id=" + jobId))
        .isEqualTo("BUILD main -");
    assertThat(one("select status || ' ' || source || ' ' || commit_sha from run where job_id=" + jobId))
        .isEqualTo("SUCCESS branch " + SHA);
    assertThat(one("select count(*) from measurement m join run r on r.id=m.run_id where r.job_id=" + jobId))
        .isEqualTo("1");
    assertThat(one("select count(*) from activity_sample")).isEqualTo("1");
    assertThat(one("select count(*) from regression")).isEqualTo("0");
  }

  @Test
  void tickStoresARegressionAfterASeededHistory() throws Exception {
    seedHistory();
    FakeBuild build = new FakeBuild(210);

    tickOnce(build);

    assertThat(build.executed).hasSize(1);
    long jobId = build.executed.getFirst();
    assertThat(one("select status from job where id=" + jobId)).isEqualTo("DONE");
    assertThat(one("select g.kpi || ' ' || g.value || ' ' || g.last_good_sha from regression g"
            + " join run r on r.id=g.run_id where r.job_id=" + jobId))
        .isEqualTo("test.module.duration 210 sha9");
  }

  @Test
  void manualCommitJobRunsAndStaysOutOfTheSeries() throws Exception {
    seedHistory();
    long manual;
    try (var c = db.dataSource().getConnection();
        var ps = c.prepareStatement(
            "insert into job(kind, branch, commit_sha, priority, status) values"
                + " ('BUILD', 'main', ?, 0, 'QUEUED') returning id")) {
      ps.setString(1, SHA);
      try (var rs = ps.executeQuery()) {
        rs.next();
        manual = rs.getLong(1);
      }
    }
    FakeBuild build = new FakeBuild(210);

    tickOnce(build);

    assertThat(build.executed).containsExactly(manual); // priority 0 first
    assertThat(one("select source from run where job_id=" + manual)).isEqualTo("commit");
    assertThat(one("select count(*) from regression")).isEqualTo("0");
    // the daily series of main was still scheduled next to the manual job
    assertThat(one("select count(*) from job where status='QUEUED' and commit_sha is null and branch='main'"))
        .isEqualTo("1");
  }
}
