package com.example.medibook.cache;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.WeeklyAppointmentStats;
import com.google.firebase.Timestamp;
import java.util.Date;
import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardCacheTest {
    private static final long NOW = DhakaDateTime.toEpochMillis(2026, 11, 9, 8, 0);
    private static final long DAY = WeeklyAppointmentStats.dayStart(NOW);
    private static Appointment next(String owner, long start) {
        return new Appointment("booking", owner, "Patient", "01712345678", "doctor", "Doctor", "Medicine", 100,
                "schedule", 1, new Timestamp(new Date(DAY)), 540, 560, new Timestamp(new Date(start)),
                new Timestamp(new Date(start + 1_200_000L)), AppointmentStatus.CONFIRMED, new Timestamp(1, 0), new Timestamp(2, 0));
    }
    private DashboardCache cache() { DashboardCache cache = new DashboardCache(); cache.bindOwner("self"); return cache; }

    @Test public void unknownAndVerifiedEmptyAreDifferentAndEmptyCanRenderOnWarmReturn() {
        DashboardCache cache = cache(); assertNull(cache.next("self", NOW));
        cache.putNext("self", null, NOW);
        assertNotNull(cache.next("self", NOW + 1)); assertNull(cache.next("self", NOW + 1).appointment);
    }
    @Test public void nextNumberAndIdentitySurviveSafeWarmRendering() {
        DashboardCache cache = cache(); Appointment value = next("self", NOW + 3_600_000L).withSlotSerial(3);
        cache.putNext("self", value, NOW);
        assertSame(value, cache.next("self", NOW + 1).appointment);
        assertEquals(3, cache.next("self", NOW + 1).appointment.getSlotSerial());
    }
    @Test public void expiredOrStartedAppointmentsDoNotInventAnEmptyResult() {
        DashboardCache cache = cache(); cache.putNext("self", next("self", NOW + 1000), NOW);
        assertNull(cache.next("self", NOW + 1000));
        cache.putNext("self", null, NOW);
        assertNull(cache.next("self", NOW + DashboardCache.MAX_AGE_MS));
        assertNull(cache.next("self", NOW - 1));
    }
    @Test public void anotherAccountCannotReadOrSeedPatientCache() {
        DashboardCache cache = cache(); cache.putNext("other", null, NOW); assertNull(cache.next("self", NOW));
        cache.putNext("self", next("other", NOW + 3_600_000), NOW); assertNull(cache.next("self", NOW));
        cache.putNext("self", null, NOW); assertNull(cache.next("other", NOW));
        cache.bindOwner("other"); cache.bindOwner("self"); assertNull(cache.next("self", NOW));
    }
    @Test public void adminCountsAreImmediatelyAvailableButBoundedByAgeAndPeriod() {
        DashboardCache cache = cache();
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.builder(NOW).add(DAY, "Confirmed", NOW + 3_600_000).build();
        cache.putAnalytics("self", stats, NOW);
        assertEquals(1, cache.analytics("self", NOW + 1).todayTotal);
        assertNull(cache.analytics("self", NOW + DashboardCache.MAX_AGE_MS));
        assertNull(cache.analytics("self", WeeklyAppointmentStats.nextDay(DAY)));
        assertNull(cache.analytics("other", NOW));
    }
    @Test public void midnightRejectsEvenARecentPreviousDaySummary() {
        DashboardCache cache = cache(); long late = WeeklyAppointmentStats.nextDay(DAY) - 500;
        cache.putAnalytics("self", WeeklyAppointmentStats.builder(late).build(), late);
        assertNull(cache.analytics("self", late + 1000));
    }
    @Test public void cachedCountsAreDefensiveCopies() {
        DashboardCache cache = cache(); WeeklyAppointmentStats stats = WeeklyAppointmentStats.builder(NOW).build();
        cache.putAnalytics("self", stats, NOW); stats.counts[0] = 99;
        WeeklyAppointmentStats shown = cache.analytics("self", NOW); assertEquals(0, shown.counts[0]);
        shown.counts[0] = 88; assertEquals(0, cache.analytics("self", NOW).counts[0]);
    }
    @Test public void logoutAndWriteInvalidationDropBothReadModels() {
        DashboardCache cache = cache(); cache.putNext("self", null, NOW);
        cache.putAnalytics("self", WeeklyAppointmentStats.builder(NOW).build(), NOW);
        cache.invalidateAppointments("other"); assertNotNull(cache.next("self", NOW));
        cache.invalidateAppointments("self"); assertNull(cache.next("self", NOW)); assertNull(cache.analytics("self", NOW));
        cache.putNext("self", null, NOW); cache.clear(); cache.bindOwner("self"); assertNull(cache.next("self", NOW));
    }
}
