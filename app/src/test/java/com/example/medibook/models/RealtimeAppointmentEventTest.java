package com.example.medibook.models;

import com.example.medibook.utils.AppointmentDisplayStatus;
import com.example.medibook.utils.AppointmentPolicy;
import com.example.medibook.utils.DhakaDateTime;
import com.google.firebase.Timestamp;
import org.junit.Test;
import java.util.Date;
import java.util.Map;
import static org.junit.Assert.*;

public class RealtimeAppointmentEventTest {
    private Appointment appointment(AppointmentStatus status, int start) {
        long date = DhakaDateTime.localDateAtMidnight(2026, 11, 9);
        return new Appointment("booking", "patient", "Private name", "01700000000", "doctor", "Doctor", "Medicine", 50000,
                "schedule", 1, new Timestamp(new Date(date)), start, start + 30,
                new Timestamp(new Date(date + start * 60000L)), new Timestamp(new Date(date + (start + 30) * 60000L)),
                status, new Timestamp(100, 123456789), new Timestamp(200, 987654321));
    }
    private RealtimeAppointmentEvent event() { return RealtimeAppointmentEvent.fromAppointment(appointment(AppointmentStatus.CONFIRMED, 600), false); }
    @Test public void preservesExistingQueueAlgorithmExactly() {
        assertEquals("Q0601", event().queueToken);
        assertEquals("Q0631", RealtimeAppointmentEvent.fromAppointment(appointment(AppointmentStatus.CONFIRMED, 630), false).queueToken);
        for (int minute = 0; minute < 1440; minute++) assertTrue(RealtimeAppointmentEvent.validToken(AppointmentPolicy.queueToken(minute)));
    }
    @Test public void roundTripHasNoPatientIdentityOrContactFields() {
        RealtimeAppointmentEvent e = event();
        assertEquals(5, e.toMap().size()); assertEquals(2, e.queueMap().size());
        assertFalse(e.toMap().toString().contains("Private name")); assertFalse(e.toMap().containsKey("patientUid"));
        assertEquals(e.fingerprint(), RealtimeAppointmentEvent.fromMap(e.id, e.toMap()).fingerprint());
    }
    @Test public void patientMustOwnTheFirestoreAppointment() {
        Appointment a = appointment(AppointmentStatus.CONFIRMED, 600);
        assertTrue(event().matches(a, "patient", false)); assertFalse(event().matches(a, "other-patient", false));
    }
    @Test public void forgedStatusTimestampDoctorDateAndTokenAreNotDisplayed() {
        Appointment a = appointment(AppointmentStatus.CONFIRMED, 600);
        for (Object[] replacement : new Object[][]{{"status", "Cancelled"}, {"changedAt", 999L}, {"doctorId", "other-doctor"},
                {"dateKey", "1"}, {"queueToken", "Q0631"}}) {
            Map<String, Object> fake = event().toMap(); fake.put((String) replacement[0], replacement[1]);
            assertFalse(RealtimeAppointmentEvent.fromMap("booking", fake).matches(a, "patient", false));
        }
        assertFalse(RealtimeAppointmentEvent.fromMap("other-id", event().toMap()).matches(a, "patient", false));
    }
    @Test public void cancellationRequiresMatchingFirestoreStatusAndUpdatedTime() {
        Appointment cancelled = appointment(AppointmentStatus.CANCELLED, 600);
        assertFalse(event().matches(cancelled, "patient", false));
        RealtimeAppointmentEvent update = RealtimeAppointmentEvent.fromAppointment(cancelled, false);
        assertTrue(update.matches(cancelled, "patient", false));
        assertFalse(update.matches(appointment(AppointmentStatus.CONFIRMED, 600), "patient", false));
    }
    @Test public void adminBookingUsesCreatedAtNotEditableClientTime() {
        Appointment a = appointment(AppointmentStatus.CONFIRMED, 600);
        RealtimeAppointmentEvent booking = RealtimeAppointmentEvent.fromAppointment(a, true);
        assertEquals(100123L, booking.changedAt); assertEquals(200987L, event().changedAt);
        assertTrue(booking.matches(a, "admin", true)); assertFalse(event().matches(a, "admin", true));
    }
    @Test public void completedRemainsDerivedAndNeverBecomesRtdbWireStatus() {
        Appointment a = appointment(AppointmentStatus.CONFIRMED, 600);
        assertEquals(AppointmentDisplayStatus.COMPLETED, AppointmentDisplayStatus.of(a, Long.MAX_VALUE));
        assertEquals("Confirmed", RealtimeAppointmentEvent.fromAppointment(a, false).status);
        assertFalse(RealtimeAppointmentEvent.validStatus("Completed"));
    }
    @Test public void duplicateSnapshotsHaveStableFingerprint() {
        assertEquals(event().fingerprint(), event().fingerprint());
        assertNotEquals(event().fingerprint(), RealtimeAppointmentEvent.fromAppointment(appointment(AppointmentStatus.CANCELLED, 600), false).fingerprint());
    }
    @Test public void staleAndDuplicateWritesDoNotOverwriteNewerTokensButCancellationWinsATie() {
        Map<String, Object> old = event().queueMap();
        assertFalse(RealtimeAppointmentEvent.shouldReplace(old, event().queueMap()));
        Map<String, Object> next = event().queueMap(); next.put("changedAt", 1L);
        assertFalse(RealtimeAppointmentEvent.shouldReplace(old, next));
        next.put("changedAt", old.get("changedAt")); next.put("status", "Cancelled");
        assertTrue(RealtimeAppointmentEvent.shouldReplace(old, next));
        assertFalse(RealtimeAppointmentEvent.shouldReplace(next, old));
        // A later booking for the same clock-time token may legitimately become Confirmed again.
        old.put("changedAt", (Long) next.get("changedAt") + 1);
        assertTrue(RealtimeAppointmentEvent.shouldReplace(next, old));
        assertTrue(RealtimeAppointmentEvent.shouldReplace(null, old));
    }
    @Test public void malformedHintShapeAndTypesAreRejected() {
        for (String field : event().toMap().keySet()) {
            Map<String, Object> map = event().toMap(); map.remove(field);
            assertThrows(IllegalArgumentException.class, () -> RealtimeAppointmentEvent.fromMap("booking", map));
        }
        Map<String, Object> extra = event().toMap(); extra.put("role", "Admin");
        assertThrows(IllegalArgumentException.class, () -> RealtimeAppointmentEvent.fromMap("booking", extra));
        Map<String, Object> bad = event().toMap(); bad.put("changedAt", "1000");
        assertThrows(IllegalArgumentException.class, () -> RealtimeAppointmentEvent.fromMap("booking", bad));
    }
    @Test public void pathsAndTokenBoundsCannotBecomeArbitraryLocations() {
        for (String key : new String[]{"../other", "bad.key", "bad#key", "", "x".repeat(129)}) assertFalse(RealtimeAppointmentEvent.validKey(key));
        for (String token : new String[]{"Q0000", "Q1441", "Q0601/other", "Q1", "q0601"}) assertFalse(RealtimeAppointmentEvent.validToken(token));
        assertFalse(RealtimeAppointmentEvent.validDateKey("2026/11/09"));
    }
}
