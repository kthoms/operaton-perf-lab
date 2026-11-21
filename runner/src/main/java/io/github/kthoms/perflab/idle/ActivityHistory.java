package io.github.kthoms.perflab.idle;

import io.github.kthoms.perflab.config.PerfLabConfig.IdleConfig;
import io.github.kthoms.perflab.config.TimeWindow;
import io.github.kthoms.perflab.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import javax.sql.DataSource;

/**
 * Records activity samples and predicts, from the last 8 weeks, how long the Mac stays idle. A
 * 15-minute slot of the week with fewer than 4 samples is unknown: it counts as idle when its local
 * start time lies in the cold-start window (default 22:00-07:00), otherwise as 50% active, so a fresh
 * install can run its first jobs at night.
 */
public class ActivityHistory {

  static final int SLOTS_PER_WEEK = 672;
  static final Duration SLOT = Duration.ofMinutes(15);
  private static final Duration ACTIVE_BELOW = Duration.ofMinutes(5);
  private static final int MIN_SAMPLES = 4;
  private static final double IDLE_BELOW_P_ACTIVE = 0.2;

  private final DataSource ds;
  private final ZoneId zone;
  private final TimeWindow coldStartWindow;

  public ActivityHistory(Database db) {
    this(db, ZoneId.systemDefault());
  }

  public ActivityHistory(Database db, ZoneId zone) {
    this(db, zone, IdleConfig.DEFAULT_COLD_START_WINDOW);
  }

  public ActivityHistory(Database db, TimeWindow coldStartWindow) {
    this(db, ZoneId.systemDefault(), coldStartWindow);
  }

  public ActivityHistory(Database db, ZoneId zone, TimeWindow coldStartWindow) {
    this.ds = db == null ? null : db.dataSource();
    this.zone = zone;
    this.coldStartWindow = coldStartWindow;
  }

  public void record(Instant ts, Duration userIdle, double load1) {
    String sql =
        "insert into activity_sample(ts, user_idle_seconds, load1) values (?,?,?)"
            + " on conflict (ts) do nothing";
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(ts));
      ps.setInt(2, (int) Math.min(Integer.MAX_VALUE, userIdle.toSeconds()));
      ps.setDouble(3, load1);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("record activity sample", e);
    }
  }

  /** 15 minutes times the consecutive likely-idle slots starting at now's slot. */
  public Duration predictedIdle(Instant now) {
    int[] total = new int[SLOTS_PER_WEEK];
    int[] active = new int[SLOTS_PER_WEEK];
    String sql =
        "select ts, user_idle_seconds from activity_sample"
            + " where ts >= ? and ts <= ? and user_idle_seconds is not null";
    ZonedDateTime from = now.atZone(zone).minusWeeks(8);
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(from.toInstant()));
      ps.setTimestamp(2, Timestamp.from(now));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          int slot = slotOf(rs.getTimestamp(1).toInstant());
          total[slot]++;
          if (rs.getInt(2) < ACTIVE_BELOW.toSeconds()) {
            active[slot]++;
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("read activity samples", e);
    }
    int start = slotOf(now);
    int idleSlots = 0;
    while (idleSlots < SLOTS_PER_WEEK) {
      int s = (start + idleSlots) % SLOTS_PER_WEEK;
      double pActive;
      if (total[s] >= MIN_SAMPLES) {
        pActive = (double) active[s] / total[s];
      } else {
        pActive = coldStartWindow.contains(startOf(s)) ? 0.0 : 0.5;
      }
      if (pActive >= IDLE_BELOW_P_ACTIVE) {
        break;
      }
      idleSlots++;
    }
    return SLOT.multipliedBy(idleSlots);
  }

  /** Local start time of a slot of the week. */
  private static LocalTime startOf(int slot) {
    int ofDay = slot % 96;
    return LocalTime.of(ofDay / 4, (ofDay % 4) * 15);
  }

  int slotOf(Instant ts) {
    ZonedDateTime z = ts.atZone(zone);
    return (z.getDayOfWeek().getValue() - 1) * 96 + z.getHour() * 4 + z.getMinute() / 15;
  }
}
