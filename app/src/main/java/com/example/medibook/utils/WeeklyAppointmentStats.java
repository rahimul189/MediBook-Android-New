package com.example.medibook.utils;

import com.example.medibook.models.Appointment;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Appointment counts for the current Saturday-to-Friday week in Asia/Dhaka.
 *
 * <p>Every count comes from the scheduled {@code appointmentDate} (the Dhaka midnight of the day
 * the patient booked), never from the booking creation time. Cancelled appointments are excluded at
 * every instant; Confirmed appointments are included whether the slot is still upcoming or already
 * past its end time, because completion is a display state and is never persisted.</p>
 */
public final class WeeklyAppointmentStats {
    public static final int DAYS = 7;

    /** Dhaka midnight of each day in the week, Saturday first. */
    public final long[] dayStarts;
    public final int[] counts;
    public final int todayIndex;
    public final int todayTotal;
    public final int weekTotal;
    public final int confirmedToday, cancelledToday, completedToday;

    private WeeklyAppointmentStats(long[] dayStarts, int[] counts, int todayIndex,
                                   int confirmed, int cancelled, int completed) {
        this.dayStarts = dayStarts;
        this.counts = counts;
        this.todayIndex = todayIndex;
        this.confirmedToday = confirmed; this.cancelledToday = cancelled; this.completedToday = completed;
        int total = 0;
        for (int count : counts) total += count;
        this.weekTotal = total;
        this.todayTotal = todayIndex >= 0 && todayIndex < DAYS ? counts[todayIndex] : 0;
    }

    public static Builder builder(long nowMillis) {
        return new Builder(nowMillis);
    }

    /** Convenience mapping used by unit tests with complete appointment objects. */
    public static WeeklyAppointmentStats of(List<Appointment> appointments, long nowMillis) {
        Builder builder = builder(nowMillis);
        if (appointments != null) {
            for (Appointment appointment : appointments) {
                if (appointment == null) continue;
                builder.add(appointment.getAppointmentDate().toDate().getTime(),
                        appointment.getStatus().getWireValue(), appointment.getEndAt().toDate().getTime());
            }
        }
        return builder.build();
    }

    /** Dhaka midnight of the Saturday that starts the week containing the supplied instant. */
    public static long weekStart(long nowMillis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        calendar.setTimeInMillis(nowMillis);
        int weekday = calendar.get(Calendar.DAY_OF_WEEK);
        int offset = (weekday - Calendar.SATURDAY + 7) % 7;
        calendar.add(Calendar.DAY_OF_MONTH, -offset);
        return DhakaDateTime.localDateAtMidnight(calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** The next Dhaka midnight; the hospital timezone has no daylight saving transition. */
    public static long nextDay(long midnight) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        calendar.setTimeInMillis(midnight);
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return DhakaDateTime.localDateAtMidnight(calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** Dhaka midnight of the day containing the supplied instant. */
    public static long dayStart(long nowMillis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        calendar.setTimeInMillis(nowMillis);
        return DhakaDateTime.localDateAtMidnight(calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static final class Builder {
        private final long[] days = new long[DAYS];
        private final int[] counts = new int[DAYS];
        private final int todayIndex;
        private final long todayStart;
        private final long now;
        private int confirmed, cancelled, completed;

        private Builder(long nowMillis) {
            now = nowMillis;
            long weekStart = weekStart(nowMillis);
            days[0] = weekStart;
            for (int index = 1; index < DAYS; index++) days[index] = nextDay(days[index - 1]);
            todayStart = dayStart(nowMillis);
            int index = -1;
            for (int day = 0; day < DAYS; day++) if (days[day] == todayStart) index = day;
            todayIndex = index;
        }

        /** Ignores anything outside the current week, including cancelled and future weeks. */
        public Builder add(long appointmentDateMillis, boolean cancelled) {
            return add(appointmentDateMillis, cancelled ? "Cancelled" : "Confirmed", Long.MAX_VALUE);
        }

        /** Completed is normally derived from Confirmed/endAt; legacy Completed is also readable. */
        public Builder add(long appointmentDateMillis, String status, long endAt) {
            if (!"Confirmed".equals(status) && !"Cancelled".equals(status) && !"Completed".equals(status)) return this;
            if (appointmentDateMillis == todayStart) {
                if ("Cancelled".equals(status)) cancelled++;
                else if ("Completed".equals(status) || endAt <= now) completed++;
                else confirmed++;
            }
            if ("Cancelled".equals(status)) return this;
            for (int day = 0; day < DAYS; day++) {
                if (days[day] == appointmentDateMillis) { counts[day]++; break; }
            }
            return this;
        }

        public WeeklyAppointmentStats build() {
            return new WeeklyAppointmentStats(days.clone(), counts.clone(), todayIndex, confirmed, cancelled, completed);
        }
    }

    /** Validated RTDB counts use the very same chart representation as Firestore aggregation. */
    public static WeeklyAppointmentStats fromCounts(long now, int[] counts, int confirmed, int cancelled, int completed) {
        if (counts == null || counts.length != DAYS || confirmed < 0 || cancelled < 0 || completed < 0) throw new IllegalArgumentException("Invalid counts");
        Builder dates = builder(now);
        for (int count : counts) if (count < 0 || count > 1_000_000) throw new IllegalArgumentException("Invalid count");
        WeeklyAppointmentStats result = new WeeklyAppointmentStats(dates.days.clone(), counts.clone(), dates.todayIndex,
                confirmed, cancelled, completed);
        if ((long) confirmed + completed != result.todayTotal) throw new IllegalArgumentException("Inconsistent counts");
        return result;
    }
}
