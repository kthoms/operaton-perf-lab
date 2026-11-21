package io.github.kthoms.perflab.idle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kthoms.perflab.config.TimeWindow;
import io.github.kthoms.perflab.db.Database;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ActivityHistoryIT {

  @Container
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17");

  private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
  // Saturday 2026-11-21 00:00 local
  private static final LocalDateTime SAT_MIDNIGHT = LocalDateTime.of(2026, 11, 21, 0, 0);

  private ActivityHistory history;

  @BeforeEach
  void setUp() throws SQLException {
    var db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try (var c = db.dataSource().getConnection();
        var st = c.createStatement()) {
      st.execute("truncate table activity_sample");
    }
    history = new ActivityHistory(db, ZONE);
  }

  private Instant at(LocalDateTime t) {
    return t.atZone(ZONE).toInstant();
  }

  /** Seeds one sample per 5 minutes for the 7 weeks before the Saturday, idle for the window. */
  private void seed(LocalDateTime windowStart, LocalDateTime windowEnd) {
    for (int week = 1; week <= 7; week++) {
      for (int min = 0; min < 24 * 60; min += 5) {
        LocalDateTime t = SAT_MIDNIGHT.minusWeeks(week).plusMinutes(min);
        LocalDateTime inWeek = SAT_MIDNIGHT.plusMinutes(min);
        boolean idle = !inWeek.isBefore(windowStart) && inWeek.isBefore(windowEnd);
        history.record(at(t), Duration.ofSeconds(idle ? 3600 : 0), 0.1);
      }
    }
  }

  @Test
  void unknownSlotsOutsideTheColdStartWindowAreNotIdle() {
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(12)))).isEqualTo(Duration.ZERO);
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(21).plusMinutes(50))))
        .isEqualTo(Duration.ZERO);
  }

  @Test
  void unknownSlotsInsideTheDefaultColdStartWindowAreIdle() {
    // default window 22:00-07:00 local: from the 03:00 slot up to 07:00
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(3).plusMinutes(5))))
        .isEqualTo(Duration.ofHours(4));
    // from 22:00 across midnight up to 07:00 next morning
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(22)))).isEqualTo(Duration.ofHours(9));
  }

  @Test
  void coldStartWindowIsConfigurable() throws SQLException {
    var db = Database.connect(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    var custom = new ActivityHistory(db, ZONE, TimeWindow.parse("01:00-02:30"));
    assertThat(custom.predictedIdle(at(SAT_MIDNIGHT.plusHours(1)))).isEqualTo(Duration.ofMinutes(90));
    assertThat(custom.predictedIdle(at(SAT_MIDNIGHT.plusHours(3)))).isEqualTo(Duration.ZERO);
  }

  @Test
  void knownBusySlotsInsideTheWindowAreNotIdle() {
    // 7 weeks of samples, idle only 01:00-02:00: known active slots override the window
    seed(SAT_MIDNIGHT.plusHours(1), SAT_MIDNIGHT.plusHours(2));
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(1)))).isEqualTo(Duration.ofHours(1));
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(3)))).isEqualTo(Duration.ZERO);
  }

  @Test
  void predictsConsecutiveIdleSlots() {
    seed(SAT_MIDNIGHT.plusHours(1), SAT_MIDNIGHT.plusHours(5));
    // from 02:10 local (slot 02:00) up to 05:00 = 3h of slots, counted from the slot start
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(2).plusMinutes(10))))
        .isEqualTo(Duration.ofHours(3));
    assertThat(history.predictedIdle(at(SAT_MIDNIGHT.plusHours(6)))).isEqualTo(Duration.ZERO);
  }
}
