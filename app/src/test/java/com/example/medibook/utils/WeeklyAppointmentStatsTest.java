package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Appointment Overview counts: scheduled Dhaka dates, Saturday-to-Friday, Cancelled excluded. */
public class WeeklyAppointmentStatsTest {
    private static final ZoneId HOSPITAL = ZoneId.of("Asia/Dhaka");
    private static final long NOW = DhakaDateTime.toEpochMillis(2026, 11, 8, 12, 0);
    private static final long HOUR = 3_600_000L;

    private static Appointment appointment(String id, String doctorId, long dateMidnight, int startMinute,
                                           AppointmentStatus status) {
        long start = dateMidnight + startMinute * 60_000L;
        long end = start + 20 * 60_000L;
        return new Appointment(id, "patient-a", "Patient fixture", "1234567", doctorId,
                "Doctor " + doctorId, "Medicine", 50025, "schedule-a", 1,
                new Timestamp(new Date(dateMidnight)), startMinute, startMinute + 20,
                new Timestamp(new Date(start)), new Timestamp(new Date(end)), status,
                new Timestamp(new Date(start)), new Timestamp(new Date(start)));
    }

    private static long[] week() {
        return WeeklyAppointmentStats.builder(NOW).build().dayStarts;
    }

    @Test public void weekRunsFromSaturdayMidnightInDhaka() {
        long weekStart = WeeklyAppointmentStats.weekStart(NOW);
        LocalDate start = Instant.ofEpochMilli(weekStart).atZone(HOSPITAL).toLocalDate();
        assertEquals(DayOfWeek.SATURDAY, start.getDayOfWeek());
        assertEquals(DhakaDateTime.localDateAtMidnight(start.getYear(), start.getMonthValue(),
                start.getDayOfMonth()), weekStart);

        WeeklyAppointmentStats stats = WeeklyAppointmentStats.builder(NOW).build();
        assertEquals(7, stats.dayStarts.length);
        assertEquals(weekStart, stats.dayStarts[0]);
        for (int day = 1; day < WeeklyAppointmentStats.DAYS; day++) {
            assertEquals(WeeklyAppointmentStats.nextDay(stats.dayStarts[day - 1]), stats.dayStarts[day]);
        }
        LocalDate today = Instant.ofEpochMilli(NOW).atZone(HOSPITAL).toLocalDate();
        assertEquals(ChronoUnit.DAYS.between(start, today), stats.todayIndex);
    }

    @Test public void countsFollowTheScheduledDateAndExcludeCancelled() {
        long[] days = week();
        List<Appointment> rows = Arrays.asList(
                appointment("a1", "doctor-a", days[0], 540, AppointmentStatus.CONFIRMED),
                appointment("a2", "doctor-b", days[0], 600, AppointmentStatus.CONFIRMED),
                appointment("a3", "doctor-a", days[0], 660, AppointmentStatus.CONFIRMED),
                appointment("b1", "doctor-a", days[1], 540, AppointmentStatus.CONFIRMED),
                appointment("b2", "doctor-b", days[1], 560, AppointmentStatus.CONFIRMED),
                appointment("b3", "doctor-a", days[1], 580, AppointmentStatus.CANCELLED),
                appointment("c1", "doctor-a", days[2], 540, AppointmentStatus.CONFIRMED),
                appointment("c2", "doctor-b", days[2], 560, AppointmentStatus.CONFIRMED));
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.of(rows, NOW);
        assertEquals(3, stats.counts[0]);
        assertEquals(2, stats.counts[1]);
        assertEquals(2, stats.counts[2]);
        assertEquals(0, stats.counts[3]);
        assertEquals(7, stats.weekTotal);
    }

    @Test public void totalsStayConsistentWithTheChart() {
        long[] days = week();
        List<Appointment> rows = new ArrayList<>();
        for (int index = 0; index < WeeklyAppointmentStats.DAYS; index++) {
            for (int count = 0; count <= index; count++) {
                rows.add(appointment("d" + index + "-" + count, "doctor-a", days[index], 540 + count * 20,
                        AppointmentStatus.CONFIRMED));
            }
        }
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.of(rows, NOW);
        int sum = 0;
        for (int count : stats.counts) sum += count;
        assertEquals(28, stats.weekTotal);
        assertEquals(sum, stats.weekTotal);
        assertEquals(stats.counts[stats.todayIndex], stats.todayTotal);
    }

    @Test public void completedAppointmentsStillCount() {
        long[] days = week();
        // The first Dhaka day of the week is at or before "now", so this slot is in the past or now.
        assertTrue(days[0] <= NOW);
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.of(
                Arrays.asList(appointment("past", "doctor-a", days[0], 540, AppointmentStatus.CONFIRMED)), NOW);
        assertEquals(1, stats.counts[0]);
        assertEquals(1, stats.weekTotal);
    }

    @Test public void appointmentsOutsideTheWeekAreIgnored() {
        long[] days = week();
        long previousWeek = days[0] - 24 * HOUR;
        long nextWeek = WeeklyAppointmentStats.nextDay(days[6]);
        List<Appointment> rows = Arrays.asList(
                appointment("before", "doctor-a", previousWeek, 540, AppointmentStatus.CONFIRMED),
                appointment("after", "doctor-a", nextWeek, 540, AppointmentStatus.CONFIRMED),
                appointment("inside", "doctor-a", days[6], 540, AppointmentStatus.CONFIRMED));
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.of(rows, NOW);
        assertEquals(1, stats.weekTotal);
        assertEquals(1, stats.counts[6]);
    }

    @Test public void theWeekRollsOverAtDhakaSaturdayMidnight() {
        long[] days = week();
        long fridayLate = days[6] + 23 * HOUR + 59 * 60_000L;
        long nextSaturday = WeeklyAppointmentStats.nextDay(days[6]);
        assertEquals(days[0], WeeklyAppointmentStats.weekStart(fridayLate));
        assertEquals(nextSaturday, WeeklyAppointmentStats.weekStart(nextSaturday));
        assertEquals(nextSaturday, WeeklyAppointmentStats.dayStart(nextSaturday));
        assertEquals(days[6], WeeklyAppointmentStats.dayStart(nextSaturday - 1));
    }

    @Test public void everyDoctorContributesToTheSameDay() {
        long[] days = week();
        List<Appointment> rows = Arrays.asList(
                appointment("one", "doctor-a", days[3], 540, AppointmentStatus.CONFIRMED),
                appointment("two", "doctor-b", days[3], 560, AppointmentStatus.CONFIRMED),
                appointment("three", "doctor-c", days[3], 580, AppointmentStatus.CONFIRMED));
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.of(rows, NOW);
        assertEquals(3, stats.counts[3]);
        assertEquals(3, stats.weekTotal);
    }

    @Test public void onlyCancelledAppointmentsProduceAnEmptyWeek() {
        long[] days = week();
        List<Appointment> rows = Arrays.asList(
                appointment("x", "doctor-a", days[0], 540, AppointmentStatus.CANCELLED),
                appointment("y", "doctor-b", days[4], 560, AppointmentStatus.CANCELLED));
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.of(rows, NOW);
        assertEquals(0, stats.weekTotal);
        assertEquals(0, stats.todayTotal);
    }
}
