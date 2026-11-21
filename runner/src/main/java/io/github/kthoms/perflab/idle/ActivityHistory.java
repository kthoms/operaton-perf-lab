package io.github.kthoms.perflab.idle;

import io.github.kthoms.perflab.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import javax.sql.DataSource;

/** Records activity samples and predicts, from the last 8 weeks, how long the Mac stays idle. */
public class ActivityHistory {

  static final int SLOTS_PER_WEEK = 672;
  static final Duration SLOT = Duration.ofMinutes(15);
  private static final Duration ACTIVE_BELOW = Duration.ofMinutes(5);
  private static final int MIN_SAMPLES = 4;
  private static final double IDLE_BELOW_P_ACTIVE = 0.2;

  private final DataSource ds;
  private final ZoneId zone;

  public ActivityHistory(Database db) {
    this(db, ZoneId.systemDefault());
  }

  public ActivityHistory(Database db, ZoneId zone) {
    this.ds = db == null ? null : db.dataSource();
    this.zone = zone;
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
      double pActive = total[s] < MIN_SAMPLES ? 0.5 : (double) active[s] / total[s];
      if (pActive >= IDLE_BELOW_P_ACTIVE) {
        break;
      }
      idleSlots++;
    }
    return SLOT.multipliedBy(idleSlots);
  }

  int slotOf(Instant ts) {
    ZonedDateTime z = ts.atZone(zone);
    return (z.getDayOfWeek().getValue() - 1) * 96 + z.getHour() * 4 + z.getMinute() / 15;
  }
}
