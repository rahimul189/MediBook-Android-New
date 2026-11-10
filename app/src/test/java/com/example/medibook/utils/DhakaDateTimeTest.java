package com.example.medibook.utils;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Deterministic tests for Asia/Dhaka date arithmetic; no Android or Firestore dependencies. */
public class DhakaDateTimeTest {
    private static final long NOV_7_2026_MIDNIGHT_MILLIS = 1_793_988_000_000L;
    private static final long NOV_7_2026_0930_MILLIS = 1_794_022_200_000L;
    private static final long DEC_31_2026_MIDNIGHT_MILLIS = 1_798_653_600_000L;
    private static final long DEC_31_2026_2345_MILLIS = 1_798_739_100_000L;
    private static final long JAN_1_2027_MIDNIGHT_MILLIS = 1_798_740_000_000L;
    private static final long FEB_29_2024_MIDNIGHT_MILLIS = 1_709_143_200_000L;
    private static final long FEB_29_2024_0600_MILLIS = 1_709_164_800_000L;

    @Test
    public void realGregorianDatesAndLeapDaysAreAccepted() {
        assertTrue(DhakaDateTime.isValidDate(2026, 11, 7));
        assertTrue(DhakaDateTime.isValidDate(2026, 1, 31));
        assertTrue(DhakaDateTime.isValidDate(2026, 12, 31));
        assertTrue(DhakaDateTime.isValidDate(2024, 2, 29));
        assertTrue(DhakaDateTime.isValidDate(2000, 2, 29));
    }

    @Test
    public void impossibleDatesAreRejected() {
        int[][] invalid = {
                {2023, 2, 29}, {1900, 2, 29}, {2026, 2, 30}, {2026, 4, 31},
                {2026, 0, 1}, {2026, 13, 1}, {2026, 1, 0}, {2026, 1, 32},
                {0, 1, 1}, {-1, 1, 1}
        };
        for (int[] date : invalid) {
            assertFalse(date[0] + "-" + date[1] + "-" + date[2],
                    DhakaDateTime.isValidDate(date[0], date[1], date[2]));
        }
    }

    @Test
    public void dhakaMidnightAndMinuteOfDayMapToExactUtcInstants() {
        assertEquals(NOV_7_2026_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2026, 11, 7));
        assertEquals(NOV_7_2026_0930_MILLIS,
                DhakaDateTime.toEpochMillis(2026, 11, 7, 570));
        assertEquals(DEC_31_2026_2345_MILLIS,
                DhakaDateTime.toEpochMillis(2026, 12, 31, 1425));
        assertEquals(JAN_1_2027_MIDNIGHT_MILLIS,
                DhakaDateTime.toEpochMillis(2027, 1, 1, 0));
        assertEquals(FEB_29_2024_0600_MILLIS,
                DhakaDateTime.toEpochMillis(2024, 2, 29, 360));
    }

    @Test
    public void midnightNormalizationHandlesMonthYearAndLeapBoundaries() {
        // Dhaka is UTC+6 year-round, so local midnight is 18:00 UTC on the previous day.
        assertEquals(DEC_31_2026_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2026, 12, 31));
        assertEquals(JAN_1_2027_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2027, 1, 1));
        assertEquals(FEB_29_2024_MIDNIGHT_MILLIS,
                DhakaDateTime.localDateAtMidnight(2024, 2, 29));
        assertEquals(utc(2026, 12, 30, 18, 0),
                DhakaDateTime.localDateAtMidnight(2026, 12, 31));
        assertEquals(utc(2024, 2, 28, 18, 0),
                DhakaDateTime.localDateAtMidnight(2024, 2, 29));
    }

    @Test
    public void isoWeekdayMapsMondayThroughSunday() {
        int[] expected = {1, 2, 3, 4, 5, 6, 7};
        for (int day = 2; day <= 8; day++) {
            assertEquals("2026-11-" + day, expected[day - 2],
                    DhakaDateTime.isoDayOfWeek(2026, 11, day));
        }
        assertEquals(7, DhakaDateTime.isoDayOfWeek(2026, 11, 8));
        assertEquals(1, DhakaDateTime.isoDayOfWeek(2026, 11, 9));
    }

    @Test
    public void isoWeekdayRemainsCorrectAtMonthYearAndLeapBoundaries() {
        assertEquals(6, DhakaDateTime.isoDayOfWeek(2026, 1, 31));
        assertEquals(7, DhakaDateTime.isoDayOfWeek(2026, 2, 1));
        assertEquals(4, DhakaDateTime.isoDayOfWeek(2024, 2, 29));
        assertEquals(5, DhakaDateTime.isoDayOfWeek(2024, 3, 1));
        assertEquals(4, DhakaDateTime.isoDayOfWeek(2026, 12, 31));
        assertEquals(5, DhakaDateTime.isoDayOfWeek(2027, 1, 1));
    }

    @Test
    public void futureDateChecksUseTheDhakaCalendarDay() {
        // 23:30 on 7 November in Dhaka.
        long lateNov7 = utc(2026, 11, 7, 17, 30);
        assertFalse(DhakaDateTime.isFutureDate(2026, 11, 7, lateNov7));
        assertTrue(DhakaDateTime.isDateBookable(2026, 11, 7, lateNov7));
        assertTrue(DhakaDateTime.isFutureDate(2026, 11, 8, lateNov7));
        assertTrue(DhakaDateTime.isDateBookable(2026, 11, 8, lateNov7));
        assertFalse(DhakaDateTime.isFutureDate(2026, 11, 6, lateNov7));
        assertFalse(DhakaDateTime.isDateBookable(2026, 11, 6, lateNov7));

        // 02:00 on 7 November in Dhaka, even though UTC is still on 6 November.
        long earlyNov7 = utc(2026, 11, 6, 20, 0);
        assertFalse(DhakaDateTime.isFutureDate(2026, 11, 6, earlyNov7));
        assertFalse(DhakaDateTime.isDateBookable(2026, 11, 6, earlyNov7));
        assertFalse(DhakaDateTime.isFutureDate(2026, 11, 7, earlyNov7));
        assertTrue(DhakaDateTime.isDateBookable(2026, 11, 7, earlyNov7));

        assertFalse(DhakaDateTime.isFutureDate(2023, 2, 29, lateNov7));
        assertFalse(DhakaDateTime.isDateBookable(2023, 2, 29, lateNov7));
    }

    @Test
    public void futureSlotRequiresAStrictlyLaterStart() {
        long now = NOV_7_2026_0930_MILLIS;
        assertTrue(DhakaDateTime.isFutureSlot(now + 1, now));
        assertFalse(DhakaDateTime.isFutureSlot(now, now));
        assertFalse(DhakaDateTime.isFutureSlot(now - 1, now));
    }

    @Test
    public void twelveHourFormattingCoversMidnightNoonAndFinalMinute() {
        assertEquals("12:00 AM", DhakaDateTime.formatTime12Hour(0));
        assertEquals("9:05 AM", DhakaDateTime.formatTime12Hour(545));
        assertEquals("11:59 AM", DhakaDateTime.formatTime12Hour(719));
        assertEquals("12:00 PM", DhakaDateTime.formatTime12Hour(720));
        assertEquals("12:59 PM", DhakaDateTime.formatTime12Hour(779));
        assertEquals("1:00 PM", DhakaDateTime.formatTime12Hour(780));
        assertEquals("11:59 PM", DhakaDateTime.formatTime12Hour(1439));
        assertThrows(IllegalArgumentException.class, () -> DhakaDateTime.formatTime12Hour(-1));
        assertThrows(IllegalArgumentException.class, () -> DhakaDateTime.formatTime12Hour(1440));
    }

    @Test
    public void invalidDatesAndMinutesAreRejectedByConversionMethods() {
        int[][] invalidDates = {
                {2023, 2, 29}, {2026, 0, 1}, {2026, 13, 1},
                {2026, 11, 0}, {2026, 11, 32}
        };
        for (int[] date : invalidDates) {
            assertThrows(IllegalArgumentException.class,
                    () -> DhakaDateTime.localDateAtMidnight(date[0], date[1], date[2]));
            assertThrows(IllegalArgumentException.class,
                    () -> DhakaDateTime.toEpochMillis(date[0], date[1], date[2], 0));
            assertThrows(IllegalArgumentException.class,
                    () -> DhakaDateTime.isoDayOfWeek(date[0], date[1], date[2]));
        }
        for (int minute : new int[]{-1, 1440}) {
            assertThrows(IllegalArgumentException.class,
                    () -> DhakaDateTime.toEpochMillis(2026, 11, 7, minute));
        }
    }

    @Test
    public void deviceDefaultTimezoneDoesNotAffectDhakaArithmetic() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zoneId : new String[]{
                    "America/Los_Angeles", "Pacific/Kiritimati", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
                assertEquals(zoneId, NOV_7_2026_MIDNIGHT_MILLIS,
                        DhakaDateTime.localDateAtMidnight(2026, 11, 7));
                assertEquals(zoneId, NOV_7_2026_0930_MILLIS,
                        DhakaDateTime.toEpochMillis(2026, 11, 7, 570));
                assertEquals(zoneId, 1, DhakaDateTime.isoDayOfWeek(2026, 11, 9));
                assertEquals(zoneId, 7, DhakaDateTime.isoDayOfWeek(2026, 11, 8));
                assertTrue(zoneId, DhakaDateTime.isFutureDate(
                        2026, 11, 8, utc(2026, 11, 6, 20, 0)));
                assertEquals(zoneId, "9:00 AM", DhakaDateTime.formatTime12Hour(540));
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    private static long utc(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setLenient(false);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @Test public void exactDateTimeRoundTripsMidnightNoonAndYearBoundary() {
        for (int[] fields : new int[][]{{2026, 12, 31, 23, 59}, {2027, 1, 1, 0, 0},
                {2026, 11, 9, 12, 0}, {2026, 11, 9, 0, 5}, {2024, 2, 29, 13, 30}}) {
            long instant = DhakaDateTime.toEpochMillis(fields[0], fields[1], fields[2], fields[3], fields[4]);
            org.junit.Assert.assertArrayEquals(fields, DhakaDateTime.localDateTime(instant));
            assertEquals(utc(fields[0], fields[1], fields[2], fields[3], fields[4]) - 6 * 60 * 60_000L, instant);
        }
    }

    @Test public void exactDateTimeIgnoresDeviceTimezoneAndRejectsInvalidClockValues() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(JAN_1_2027_MIDNIGHT_MILLIS, DhakaDateTime.toEpochMillis(2027, 1, 1, 0, 0));
            assertEquals(JAN_1_2027_MIDNIGHT_MILLIS + 12 * 60 * 60_000L, DhakaDateTime.toEpochMillis(2027, 1, 1, 12, 0));
        } finally { TimeZone.setDefault(previous); }
        assertThrows(IllegalArgumentException.class, () -> DhakaDateTime.toEpochMillis(2027, 1, 1, 24, 0));
        assertThrows(IllegalArgumentException.class, () -> DhakaDateTime.toEpochMillis(2027, 1, 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> DhakaDateTime.toEpochMillis(2027, 1, 1, 12, 60));
        assertThrows(IllegalArgumentException.class, () -> DhakaDateTime.toEpochMillis(2027, 2, 29, 12, 0));
    }
}
