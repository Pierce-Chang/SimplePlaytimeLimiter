package dev.chang.spl;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/*
  time helpers for scheduling tasks on a paper server
  provides a conversion from wall clock duration to server ticks
*/
public final class TimeUtil {

    private TimeUtil() {
    }

    // returns the number of server ticks until the next midnight in the given timezone
    // one tick is 50ms on a 20tps server
    public static long ticksUntilNextMidnight(ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.plusDays(1).toLocalDate().atStartOfDay(zone);

        long millis = Duration.between(now, next).toMillis();
        long ticks = Math.max(1L, millis / 50L);

        // cap to avoid extremely large delays if time calculations go wrong
        return Math.min(ticks, 20L * 60L * 60L * 26L);
    }
}
