package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.repositories.DashboardState;
import com.google.firebase.Timestamp;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DashboardProjectionTest {
    private static final long NOW = DhakaDateTime.toEpochMillis(2026, 11, 8, 8, 0);
    private static final long DAY = DhakaDateTime.localDateAtMidnight(2026, 11, 8);
    private static Timestamp stamp(long value) { return new Timestamp(new Date(value)); }
    private static Appointment appointment(String id, String owner, int minute, AppointmentStatus status) {
        return new Appointment(id, owner, "Patient", "1234567", "doctor-a", "Doctor", "Medicine", 100,
                "schedule-a", 7, stamp(DAY), minute, minute + 20, stamp(DAY + minute * 60_000L),
                stamp(DAY + (minute + 20) * 60_000L), status, stamp(DAY - 1000), stamp(DAY - 1000));
    }
    private static Map<String, Object> summary(Map<String, Object> payload, long at) {
        Map<String, Object> result = new HashMap<>(payload); result.put("revision", 1L); result.put("checkedAt", at); return result;
    }
    @Test public void noAppointmentsAndOneFutureAppointment() {
        assertNull(NextAppointment.select("a", Collections.emptyList(), NOW));
        Appointment value = appointment("one", "a", 540, AppointmentStatus.CONFIRMED);
        assertSame(value, NextAppointment.select("a", Collections.singletonList(value), NOW));
    }
    @Test public void nearestUpcomingIsNotLastBookedAndTiesAreStable() {
        Appointment early = appointment("a", "self", 540, AppointmentStatus.CONFIRMED);
        Appointment tie = appointment("b", "self", 540, AppointmentStatus.CONFIRMED);
        Appointment later = appointment("latest-booked", "self", 600, AppointmentStatus.CONFIRMED);
        assertSame(early, NextAppointment.select("self", Arrays.asList(later, tie, early), NOW));
    }
    @Test public void cancellationPromotesNextAndBookingCanReplaceEmptySummary() {
        Map<String, Object> empty = DashboardPayload.next(null);
        Appointment first = appointment("first", "self", 540, AppointmentStatus.CONFIRMED);
        assertNotEquals(empty, DashboardPayload.next(NextAppointment.select("self", Collections.singletonList(first), NOW)));
        Appointment cancelled = appointment("first", "self", 540, AppointmentStatus.CANCELLED);
        Appointment second = appointment("second", "self", 600, AppointmentStatus.CONFIRMED);
        assertSame(second, NextAppointment.select("self", Arrays.asList(cancelled, second), NOW));
    }
    @Test public void foreignCancelledStartedAndCompletedRecordsNeverBecomeUpcoming() {
        assertNull(NextAppointment.select("self", Arrays.asList(appointment("foreign", "other", 540, AppointmentStatus.CONFIRMED),
                appointment("cancelled", "self", 550, AppointmentStatus.CANCELLED),
                appointment("completed", "self", 400, AppointmentStatus.CONFIRMED),
                appointment("started", "self", 480, AppointmentStatus.CONFIRMED)), NOW));
        assertFalse(NextAppointment.eligible(null, appointment("a", "a", 540, AppointmentStatus.CONFIRMED), NOW));
    }
    @Test public void rtdbAbsenceFailureAndMismatchUseAuthoritativeFallback() {
        Map<String, Object> truth = DashboardPayload.next(appointment("actual", "self", 540, AppointmentStatus.CONFIRMED));
        assertEquals(DashboardState.FIRESTORE, DashboardPayload.state(false, false, false, truth, null, NOW));
        Map<String, Object> forged = summary(DashboardPayload.next(appointment("fake", "self", 540, AppointmentStatus.CONFIRMED)), NOW);
        assertEquals(DashboardState.FIRESTORE, DashboardPayload.state(false, false, true, truth, forged, NOW));
        Map<String, Object> correct = summary(truth, NOW);
        assertEquals(DashboardState.LIVE, DashboardPayload.state(false, false, true, truth, correct, NOW));
        assertEquals(DashboardState.CACHED, DashboardPayload.state(true, false, true, truth, correct, NOW));
        assertEquals(DashboardState.CACHED, DashboardPayload.state(false, true, true, truth, correct, NOW));
        assertEquals(DashboardState.ERROR, DashboardPayload.state(false, true, true, null, correct, NOW));
    }
    @Test public void staleFutureAndExtraPayloadsAreNotConfirmedLive() {
        Map<String, Object> truth = DashboardPayload.next(null);
        assertFalse(DashboardPayload.fresh(summary(truth, NOW - DashboardPayload.FRESH_MS - 1), NOW));
        assertFalse(DashboardPayload.fresh(summary(truth, NOW + 60_001), NOW));
        Map<String, Object> extra = summary(truth, NOW); extra.put("patientPhone", "1234567");
        assertFalse(DashboardPayload.matches(truth, extra));
        assertEquals(3, truth.size()); assertEquals("", truth.get("appointmentId"));
    }
    @Test public void confirmedCompletedCancelledAndChartCountsAreConsistent() {
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.builder(NOW)
                .add(DAY, "Confirmed", NOW + 60_000)
                .add(DAY, "Confirmed", NOW)
                .add(DAY, "Completed", NOW - 1)
                .add(DAY, "Cancelled", NOW + 1)
                .add(DAY - 86_400_000L, "Confirmed", NOW - 1).build();
        assertEquals(3, stats.todayTotal); assertEquals(4, stats.weekTotal);
        assertEquals(1, stats.confirmedToday); assertEquals(2, stats.completedToday); assertEquals(1, stats.cancelledToday);
        int sum = 0; for (int count : stats.counts) sum += count;
        assertEquals(sum, stats.weekTotal); assertEquals(stats.confirmedToday + stats.completedToday, stats.todayTotal);
        Map<String, Object> payload = summary(DashboardPayload.analytics(stats), NOW);
        WeeklyAppointmentStats parsed = DashboardPayload.stats(payload, NOW);
        assertArrayEquals(stats.counts, parsed.counts); assertEquals(stats.todayTotal, parsed.todayTotal);
    }
    @Test public void midnightAndSaturdayRolloverRejectOldAggregatePeriods() {
        Map<String, Object> old = summary(DashboardPayload.analytics(WeeklyAppointmentStats.builder(NOW).build()), NOW);
        assertThrows(IllegalArgumentException.class, () -> DashboardPayload.stats(old, WeeklyAppointmentStats.nextDay(DAY)));
        long saturday = WeeklyAppointmentStats.weekStart(NOW) + 7 * 86_400_000L;
        assertThrows(IllegalArgumentException.class, () -> DashboardPayload.stats(old, saturday));
        assertEquals(saturday, WeeklyAppointmentStats.weekStart(saturday));
        assertEquals(saturday - 7 * 86_400_000L, WeeklyAppointmentStats.weekStart(saturday - 1));
    }
    @Test public void inconsistentAggregatesAndInvalidCountTypesAreRejected() {
        Map<String, Object> invalid = summary(DashboardPayload.analytics(WeeklyAppointmentStats.builder(NOW).build()), NOW);
        invalid.put("weekTotal", 1L);
        assertThrows(IllegalArgumentException.class, () -> DashboardPayload.stats(invalid, NOW));
        invalid.put("weekTotal", 0L); invalid.put("confirmedToday", 1.5);
        assertThrows(IllegalArgumentException.class, () -> DashboardPayload.stats(invalid, NOW));
    }
    @Test public void legacyMissingDoctorTextHasSafePresentationButOwnershipIsNeverInferred() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("patientUid", "self"); raw.put("patientName", "Patient"); raw.put("patientPhone", "1234567");
        raw.put("doctorId", "doctor-a"); raw.put("doctorName", ""); raw.put("doctorSpecialization", "");
        raw.put("consultationFee", 100L); raw.put("scheduleId", "schedule-a"); raw.put("dayOfWeek", 7L);
        raw.put("appointmentDate", stamp(DAY)); raw.put("startMinute", 540L); raw.put("endMinute", 560L);
        raw.put("startAt", stamp(DAY + 540 * 60_000L)); raw.put("endAt", stamp(DAY + 560 * 60_000L));
        raw.put("status", "Confirmed"); raw.put("createdAt", stamp(DAY - 1000)); raw.put("updatedAt", stamp(DAY - 1000));
        Appointment parsed = NextAppointment.from("legacy", raw);
        assertEquals("Doctor unavailable", parsed.getDoctorName()); assertTrue(NextAppointment.eligible("self", parsed, NOW));
        raw.remove("patientUid"); assertThrows(IllegalArgumentException.class, () -> NextAppointment.from("legacy", raw));
    }
    @Test public void sourceChangesAndTimeDerivedCompletionChangeProjectionWithoutChangingWeekTotal() {
        WeeklyAppointmentStats before = WeeklyAppointmentStats.builder(NOW).add(DAY, "Confirmed", NOW + 1).build();
        WeeklyAppointmentStats after = WeeklyAppointmentStats.builder(NOW + 1).add(DAY, "Confirmed", NOW + 1).build();
        assertEquals(before.weekTotal, after.weekTotal); assertEquals(1, before.confirmedToday); assertEquals(0, after.confirmedToday);
        assertEquals(1, after.completedToday);
        assertFalse(DashboardPayload.matches(DashboardPayload.analytics(after), summary(DashboardPayload.analytics(before), NOW)));
    }
}
