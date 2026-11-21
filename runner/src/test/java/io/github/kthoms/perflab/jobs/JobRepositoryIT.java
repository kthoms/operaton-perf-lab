package io.github.kthoms.perflab.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.db.Database;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JobRepositoryIT {

  @Container
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  private Database db;
  private JobRepository repo;

  @BeforeEach
  void setUp() throws SQLException {
    db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table job restart identity cascade");
    }
    repo = new JobRepository(db.dataSource());
  }

  private long enqueueBuild(String branch, int priority) {
    return repo.enqueue(JobKind.BUILD, branch, "sha-" + branch, null, null, priority);
  }

  @Test
  void queuedOrdersByPriorityThenAge() {
    long bench = repo.enqueue(JobKind.BENCHMARK, "a", "s1", "b1", "h2", Job.PRIORITY_BENCHMARK);
    long build1 = enqueueBuild("b", Job.PRIORITY_BUILD);
    long manual = enqueueBuild("c", Job.PRIORITY_MANUAL);
    long build2 = enqueueBuild("d", Job.PRIORITY_BUILD);

    assertThat(repo.queued()).extracting(Job::id).containsExactly(manual, build1, build2, bench);
    var first = repo.queued().get(0);
    assertThat(first.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(first.attempts()).isZero();
    assertThat(first.kind()).isEqualTo(JobKind.BUILD);
  }

  @Test
  void markRunningIsAtomic() {
    long id = enqueueBuild("a", Job.PRIORITY_BUILD);

    assertThat(repo.markRunning(id)).isTrue();
    assertThat(repo.markRunning(id)).isFalse();
    assertThat(repo.queued()).isEmpty();
  }

  @Test
  void requeueStaleRecoversRunningJobs() {
    long id = enqueueBuild("a", Job.PRIORITY_BUILD);
    repo.markRunning(id);
    long done = enqueueBuild("b", Job.PRIORITY_BUILD);
    repo.markRunning(done);
    repo.finish(done, JobStatus.DONE);

    assertThat(repo.requeueStale()).isEqualTo(1);

    assertThat(repo.queued()).extracting(Job::id).containsExactly(id);
    assertThat(repo.queued().get(0).attempts()).isEqualTo(1);
    assertThat(repo.markRunning(id)).isTrue();
  }

  @Test
  void requestCancelFlagsOnlyRunningJob() {
    long running = enqueueBuild("a", Job.PRIORITY_BUILD);
    long queued = enqueueBuild("b", Job.PRIORITY_BUILD);
    repo.markRunning(running);

    repo.requestCancel();

    assertThat(repo.cancelRequested(running)).isTrue();
    assertThat(repo.cancelRequested(queued)).isFalse();
  }

  @Test
  void requeueClearsCancelFlag() {
    long id = enqueueBuild("a", Job.PRIORITY_BUILD);
    repo.markRunning(id);
    repo.requestCancel();

    repo.requeue(id);

    assertThat(repo.cancelRequested(id)).isFalse();
    assertThat(repo.queued()).extracting(Job::id).containsExactly(id);
  }

  @Test
  void hasOpenCoversQueuedAndRunningOnly() {
    assertThat(repo.hasOpen(JobKind.BUILD, "a", null, null)).isFalse();
    long id = enqueueBuild("a", Job.PRIORITY_BUILD);
    assertThat(repo.hasOpen(JobKind.BUILD, "a", null, null)).isTrue();
    assertThat(repo.hasOpen(JobKind.BUILD, "other", null, null)).isFalse();
    assertThat(repo.hasOpen(JobKind.BENCHMARK, "a", null, null)).isFalse();
    repo.markRunning(id);
    assertThat(repo.hasOpen(JobKind.BUILD, "a", null, null)).isTrue();
    repo.finish(id, JobStatus.DONE);
    assertThat(repo.hasOpen(JobKind.BUILD, "a", null, null)).isFalse();
  }
}
