package com.example.medibook.utils;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Deterministic Asia/Dhaka date arithmetic using {@link Calendar}. App code deliberately avoids
 * java.time because minSdk 24 has no core-library desugaring.
 *
 * <p>Public month values are 1-12 (January is 1). Epoch milliseconds are UTC instants; all
 * calendar interpretation uses the hospital timezone, never the device default.</p>
 */
public final class DhakaDateTime {
    private DhakaDateTime() { }

    /** True only for a real calendar date with a positive year. */
    public static boolean isValidDate(int year, int month, int day) {
        if (year < 1 || month < 1 || month > 12 || day < 1 || day > 31) return false;
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setLenient(false);
        calendar.clear();
        calendar.set(year, month - 1, day);
        try {
            calendar.getTimeInMillis();
            return true;
        } catch (IllegalArgumentException invalidDate) {
            return false;
        }
    }

    /** Dhaka local midnight for a validated date, expressed as a UTC epoch millisecond value. */
    public static long localDateAtMidnight(int year, int month, int day) {
        return toEpochMillis(year, month, day, 0);
    }

    /** Converts a Dhaka local date and minute-of-day to its exact UTC epoch millisecond value. */
    public static long toEpochMillis(int year, int month, int day, int minuteOfDay) {
        if (minuteOfDay < 0 || minuteOfDay > 1439) {
            throw new IllegalArgumentException("Invalid minute of day");
        }
        return toEpochMillis(year, month, day, minuteOfDay / 60, minuteOfDay % 60);
    }

    /** Converts a Dhaka local date and 24-hour time to its exact UTC epoch millisecond value. */
    public static long toEpochMillis(int year, int month, int day, int hourOfDay, int minute) {
        if (hourOfDay < 0 || hourOfDay > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Invalid Dhaka time");
        }
        Calendar calendar = calendarFor(year, month, day);
        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** Year, month (1-12), day, hour (0-23), minute in Asia/Dhaka for an epoch instant. */
    public static int[] localDateTime(long epochMillis) {
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setTimeInMillis(epochMillis);
        return new int[]{calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE)};
    }

    /** ISO weekday: Monday = 1 through Sunday = 7. */
    public static int isoDayOfWeek(int year, int month, int day) {
        int calendarDay = calendarFor(year, month, day).get(Calendar.DAY_OF_WEEK);
        return ((calendarDay + 5) % 7) + 1;
    }

    /** True only when the selected Dhaka date is strictly after the reference Dhaka date. */
    public static boolean isFutureDate(int year, int month, int day, long referenceNowMillis) {
        if (!isValidDate(year, month, day)) return false;
        return localDateAtMidnight(year, month, day) > referenceMidnight(referenceNowMillis);
    }

    /** True when the selected Dhaka date is today or later; invalid/past dates are rejected. */
    public static boolean isDateBookable(int year, int month, int day, long referenceNowMillis) {
        if (!isValidDate(year, month, day)) return false;
        return localDateAtMidnight(year, month, day) >= referenceMidnight(referenceNowMillis);
    }

    /** A slot is future only when its exact start is strictly after the reference instant. */
    public static boolean isFutureSlot(long startAtMillis, long referenceNowMillis) {
        return startAtMillis > referenceNowMillis;
    }

    /** Patient-facing 12-hour time, e.g. 540 -> "9:00 AM" and 720 -> "12:00 PM". */
    public static String formatTime12Hour(int minuteOfDay) {
        return PatientScheduleDisplay.time(minuteOfDay);
    }

    private static Calendar calendarFor(int year, int month, int day) {
        if (!isValidDate(year, month, day)) throw new IllegalArgumentException("Invalid Dhaka date");
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setLenient(false);
        calendar.clear();
        calendar.set(year, month - 1, day, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static long referenceMidnight(long referenceNowMillis) {
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setTimeInMillis(referenceNowMillis);
        return localDateAtMidnight(calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static TimeZone zone() {
        return TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID);
    }
}
