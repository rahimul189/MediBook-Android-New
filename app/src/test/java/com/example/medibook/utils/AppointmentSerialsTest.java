package com.example.medibook.utils;

import com.example.medibook.cache.PatientDataCache;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.google.firebase.Timestamp;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic lookup ordering and cached/partial-snapshot checks, without Firebase or clocks. */
public class AppointmentSerialsTest {
    private static final long DAY = DhakaDateTime.localDateAtMidnight(2026, 11, 9) / 1000L;

    private static Appointment appointment(String id, String owner, int offset, AppointmentStatus status) {
        return new Appointment(id, owner, "Patient", "01712345678", "doctor", "Doctor", "Medicine", 50000,
                "schedule", 1, new Timestamp(DAY, 0), 403 + offset, 423 + offset,
                new Timestamp(DAY + (403 + offset) * 60L, 0), new Timestamp(DAY + (423 + offset) * 60L, 0),
                status, new Timestamp(1, 0), new Timestamp(status == AppointmentStatus.CANCELLED ? 3 : 2, 0));
    }

    private static Appointment partial() { return appointment("booking", "patient", 0, AppointmentStatus.CONFIRMED); }
    private static String label(Appointment value) {
        return SlotSerials.label(value.getSlotSerial(), value.getStartMinute(), value.getEndMinute());
    }

    @Test public void assignedNumericSerialIsImmediatelyStableAndPadded() {
        assertEquals("#03", label(partial().withSlotSerial(3)));
        assertEquals("#05", label(partial().withSlotSerial(5)));
    }

    @Test public void missingInitialCachedSerialIsNeutralNotTheObservedTimeFallback() {
        Appointment initial = partial();
        assertEquals(0, initial.getSlotSerial());
        assertEquals(SlotSerials.UNAVAILABLE, label(initial));
        assertFalse(label(initial).contains("6:43"));
        assertFalse(label(initial).contains("7:03"));
    }

    @Test public void asynchronousLookupEnrichesOnlyTheSlotNumber() {
        Appointment initial = partial();
        Appointment result = AppointmentSerials.applyLookup(initial, initial, initial.withSlotSerial(3), 1, 1);
        assertEquals("#03", label(result));
        assertEquals(initial.getStartAt(), result.getStartAt());
        assertEquals(initial.getEndAt(), result.getEndAt());
    }

    @Test public void legacyAppointmentWithoutASidecarRemainsNeutral() {
        Appointment legacy = partial();
        assertSame(legacy, AppointmentSerials.applyLookup(legacy, legacy, legacy, 1, 1));
        assertEquals(SlotSerials.UNAVAILABLE, label(legacy));
    }

    @Test public void outOfOrderOlderLookupCannotReplaceTheNewerCorrectNumber() {
        Appointment requested = partial();
        Appointment latest = AppointmentSerials.applyLookup(requested, requested, requested.withSlotSerial(5), 2, 2);
        assertSame(latest, AppointmentSerials.applyLookup(latest, requested, requested.withSlotSerial(3), 1, 2));
        assertSame(latest, AppointmentSerials.applyLookup(latest, requested, requested, 1, 2));
        assertEquals("#05", label(latest));
    }

    @Test public void staleRequestIsRejectedEvenBeforeANumberIsKnown() {
        Appointment current = partial();
        assertSame(current, AppointmentSerials.applyLookup(current, current, current.withSlotSerial(3), 1, 2));
    }

    @Test public void assignedFixedNumberCannotBeDowngradedByMissingOrConflictingResults() {
        Appointment requested = partial(), current = requested.withSlotSerial(3);
        assertSame(current, AppointmentSerials.applyLookup(current, requested, requested, 2, 2));
        assertSame(current, AppointmentSerials.applyLookup(current, requested, requested.withSlotSerial(5), 2, 2));
    }

    @Test public void lateSerialResultDoesNotRestoreAnOlderAppointmentStatus() {
        Appointment requested = partial();
        Appointment cancelled = appointment("booking", "patient", 0, AppointmentStatus.CANCELLED);
        Appointment result = AppointmentSerials.applyLookup(cancelled, requested, requested.withSlotSerial(3), 2, 2);
        assertEquals(AppointmentStatus.CANCELLED, result.getStatus());
        assertEquals(cancelled.getUpdatedAt(), result.getUpdatedAt());
        assertEquals("#03", label(result));
    }

    @Test public void safeRefreshKeepsNumberButAcceptsFreshStatusAndFields() {
        Appointment fresh = appointment("booking", "patient", 0, AppointmentStatus.CANCELLED);
        Appointment merged = AppointmentSerials.retain(fresh, partial().withSlotSerial(3));
        assertEquals("#03", label(merged));
        assertEquals(AppointmentStatus.CANCELLED, merged.getStatus());
        assertEquals(fresh.getUpdatedAt(), merged.getUpdatedAt());
        Appointment numbered = fresh.withSlotSerial(5);
        assertSame(numbered, AppointmentSerials.retain(numbered, partial().withSlotSerial(3)));
    }

    @Test public void differentOwnerIdOrSlotCannotInheritOrResolveAnotherNumber() {
        Appointment previous = partial().withSlotSerial(3);
        Appointment[] others = {
                appointment("booking", "other", 0, AppointmentStatus.CONFIRMED),
                appointment("other", "patient", 0, AppointmentStatus.CONFIRMED),
                appointment("booking", "patient", 1, AppointmentStatus.CONFIRMED)
        };
        for (Appointment current : others) {
            assertSame(current, AppointmentSerials.retain(current, previous));
            assertSame(current, AppointmentSerials.applyLookup(current, previous, previous, 1, 1));
        }
    }

    @Test public void cacheReusesOnlyKnownSerialAndIgnoresPartialReplacement() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("patient");
        cache.rememberAppointmentSerial("patient", partial().withSlotSerial(3));
        cache.rememberAppointmentSerial("patient", partial());
        Appointment fresh = appointment("booking", "patient", 0, AppointmentStatus.CANCELLED);
        Appointment shown = cache.withKnownAppointmentSerial("patient", fresh);
        assertEquals("#03", label(shown));
        assertEquals(AppointmentStatus.CANCELLED, shown.getStatus());
        assertEquals(fresh.getUpdatedAt(), shown.getUpdatedAt());
    }

    @Test public void serialCacheIsOwnerScopedClearedOnLogoutAndInvalidatedWithAppointments() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("patient");
        cache.rememberAppointmentSerial("other", partial().withSlotSerial(5));
        assertEquals(0, cache.withKnownAppointmentSerial("patient", partial()).getSlotSerial());
        cache.rememberAppointmentSerial("patient", partial().withSlotSerial(3));
        assertEquals(0, cache.withKnownAppointmentSerial("other", partial()).getSlotSerial());
        cache.bindOwner("other"); cache.bindOwner("patient");
        assertEquals(0, cache.withKnownAppointmentSerial("patient", partial()).getSlotSerial());
        cache.rememberAppointmentSerial("patient", partial().withSlotSerial(3)); cache.clear(); cache.bindOwner("patient");
        assertEquals(0, cache.withKnownAppointmentSerial("patient", partial()).getSlotSerial());
        cache.rememberAppointmentSerial("patient", partial().withSlotSerial(3)); cache.invalidateAppointments("patient");
        assertEquals(0, cache.withKnownAppointmentSerial("patient", partial()).getSlotSerial());
    }

    @Test public void numberedListCacheSeedsDetailsWithoutAnUnnumberedIntermediateDisplay() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("patient");
        cache.putAppointments("patient", java.util.Collections.singletonList(partial().withSlotSerial(3)));
        assertEquals("#03", label(cache.withKnownAppointmentSerial("patient", partial())));
    }

    @Test public void serialCacheIsBoundedAndCannotMatchADifferentInterval() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("patient");
        for (int i = 0; i < 140; i++) cache.rememberAppointmentSerial("patient",
                appointment("booking" + i, "patient", 0, AppointmentStatus.CONFIRMED).withSlotSerial(3));
        assertEquals(0, cache.withKnownAppointmentSerial("patient", appointment("booking0", "patient", 0, AppointmentStatus.CONFIRMED)).getSlotSerial());
        assertEquals(3, cache.withKnownAppointmentSerial("patient", appointment("booking139", "patient", 0, AppointmentStatus.CONFIRMED)).getSlotSerial());
        assertEquals(0, cache.withKnownAppointmentSerial("patient", appointment("booking139", "patient", 1, AppointmentStatus.CONFIRMED)).getSlotSerial());
    }

    @Test public void slotNumberLabelsNeverContainScheduleTimesForAnyNumberState() {
        for (int number : new int[]{-1, 0, 3, 5, SlotSerials.MAX, SlotSerials.MAX + 1}) {
            String text = SlotSerials.label(number, 403, 423);
            assertFalse(text.contains("Time slot")); assertFalse(text.contains(":"));
            assertFalse(text.contains("AM")); assertFalse(text.contains("PM"));
            assertTrue(text.equals(SlotSerials.UNAVAILABLE) || text.matches("#[0-9]{2,7}"));
        }
    }
}
