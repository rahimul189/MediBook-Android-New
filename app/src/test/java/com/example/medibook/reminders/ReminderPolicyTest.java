package com.example.medibook.reminders;

import com.example.medibook.local.PrivatePatientStore;
import com.example.medibook.reminders.ReminderPolicy.Record;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.utils.DhakaDateTime;
import com.google.firebase.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.junit.Test;
import static com.example.medibook.reminders.ReminderPolicy.*;
import static org.junit.Assert.*;

public class ReminderPolicyTest {
    private final long now = DhakaDateTime.toEpochMillis(2026, 11, 7, 600);
    private Appointment appointment(String uid, long start, AppointmentStatus status) {
        Timestamp time = new Timestamp(new Date(start));
        return new Appointment("appointment", uid, "Patient", "1234567", "doctor", "Doctor", "Medicine", 50000,
                "schedule", 1, time, 600, 630, time, new Timestamp(new Date(start + 30 * MINUTE)), status, time, time);
    }
    @Test public void futureConfirmedOwnAppointmentIsEligible() { assertTrue(eligible("a", appointment("a", now + DAY, AppointmentStatus.CONFIRMED), now)); }
    @Test public void cancelledPastStartedNullAndForeignAreIneligible() {
        assertFalse(eligible("a", appointment("a", now + DAY, AppointmentStatus.CANCELLED), now));
        assertFalse(eligible("a", appointment("a", now, AppointmentStatus.CONFIRMED), now));
        assertFalse(eligible("a", appointment("a", now - 1, AppointmentStatus.CONFIRMED), now));
        assertFalse(eligible("b", appointment("a", now + DAY, AppointmentStatus.CONFIRMED), now));
        assertFalse(eligible(null, null, now));
    }
    @Test public void normalReminderIsExactlyOneDayBeforeTheInstant() { assertEquals(now + DAY, dueAt(now + 2 * DAY, now)); }
    @Test public void underOneDayUsesOneHourBeforeWhenSafelyFuture() { assertEquals(now + 9 * HOUR, dueAt(now + 10 * HOUR, now)); }
    @Test public void underOneHourUsesHalfwayNotImmediate() { assertEquals(now + 20 * MINUTE, dueAt(now + 40 * MINUTE, now)); }
    @Test public void lastMinuteBookingsHaveNoReminder() {
        assertEquals(-1, dueAt(now + 19 * MINUTE, now)); assertEquals(-1, dueAt(now, now)); assertEquals(-1, dueAt(now - 1, now));
    }
    @Test public void twentyMinuteBoundaryStillHasAFutureReminder() { assertEquals(now + 10 * MINUTE, dueAt(now + 20 * MINUTE, now)); }
    @Test public void justOverOneDayDoesNotFireAnImmediateIrrelevantNotification() {
        long start = now + DAY + MINUTE;
        assertEquals(start - HOUR, dueAt(start, now));
    }
    @Test public void defaultNearnessBoundaryHasAtLeastFiveMinutesLead() { assertEquals(now + 5 * MINUTE, dueAt(now + DAY + 5 * MINUTE, now)); }
    @Test public void calculationsIgnoreTheDeviceTimeZoneAtDhakaMidnight() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            long start = DhakaDateTime.toEpochMillis(2026, 11, 10, 5);
            assertEquals(DhakaDateTime.toEpochMillis(2026, 11, 9, 5), dueAt(start, now));
        } finally { TimeZone.setDefault(original); }
    }
    @Test public void refreshRetainsOriginalDueInsteadOfPostponingShortBooking() {
        Appointment a = appointment("a", now + 40 * MINUTE, AppointmentStatus.CONFIRMED);
        Record first = reconcile("a", a, null, now);
        assertEquals(first.encode(), reconcile("a", a, first, now + 10 * MINUTE).encode());
    }
    @Test public void explicitCancellationInvalidatesEvenAnOverdueScheduledReminder() {
        Record pending = new Record(now + DAY, now - MINUTE, SCHEDULED);
        Record cancelled = reconcile("a", appointment("a", now + DAY, AppointmentStatus.CANCELLED), pending, now);
        assertEquals(CANCELLED, cancelled.state); assertFalse(mayDeliver(cancelled, pending.start, pending.due, now));
    }
    @Test public void staleConfirmedReadCannotReverseKnownCancellation() {
        Record cancelled = new Record(now + DAY, -1, CANCELLED);
        assertEquals(CANCELLED, reconcile("a", appointment("a", now + DAY, AppointmentStatus.CONFIRMED), cancelled, now).state);
    }
    @Test public void sentReminderIsNotScheduledAgainByRefreshOrToggle() {
        Record sent = new Record(now + DAY, now - MINUTE, SENT);
        assertEquals(sent.encode(), reconcile("a", appointment("a", now + DAY, AppointmentStatus.CONFIRMED), sent, now).encode());
        assertFalse(mayDeliver(sent, sent.start, sent.due, now));
    }
    @Test public void workerWillNotDeliverEarlyOrAfterAppointmentStarts() {
        Record pending = new Record(now + HOUR, now + 10 * MINUTE, SCHEDULED);
        assertFalse(mayDeliver(pending, pending.start, pending.due, now));
        assertTrue(mayDeliver(pending, pending.start, pending.due, now + 10 * MINUTE));
        assertFalse(mayDeliver(pending, pending.start, pending.due, now + 55 * MINUTE));
        assertFalse(mayDeliver(pending, pending.start, pending.due, now + HOUR));
    }
    @Test public void obsoleteWorkInputCannotMatchAChangedAppointment() {
        Record pending = new Record(now + DAY, now + HOUR, SCHEDULED);
        assertFalse(mayDeliver(pending, pending.start + HOUR, pending.due, now + HOUR));
        assertFalse(mayDeliver(pending, pending.start, pending.due - 1, now + HOUR));
    }
    @Test public void malformedLedgerIsRejectedAndValidRecordRoundTrips() {
        for (String raw : new String[]{null, "", "1", "a:2:scheduled", "1:-2:scheduled", "1:2:unknown", "0:2:scheduled",
                "1:2:scheduled", "123:-1:scheduled", "123:123:sent", "123:100:cancelled"}) assertNull(Record.decode(raw));
        Record record = new Record(now + DAY, now + HOUR, SCHEDULED);
        assertEquals(record.encode(), Record.decode(record.encode()).encode());
    }
    @Test public void accountSwitchOrLogoutInvalidatePendingWorkerEpochAndConsent() {
        PrivatePatientStore store = new PrivatePatientStore(new PrivatePatientStore.Storage() {
            Map<String, String> disk = new HashMap<>();
            @Override public Map<String, String> read() { return disk; }
            @Override public void replace(Map<String, String> values) { disk = values; }
        });
        store.bindAccount("a"); String token = store.epoch(); store.enableReminders(true);
        store.putReminder("appointment", new Record(now + DAY, now + HOUR, SCHEDULED).encode());
        assertTrue(store.matches("a", token)); store.bindAccount("b");
        assertFalse(store.matches("a", token)); assertFalse(store.remindersEnabled()); assertTrue(store.reminderRecords().isEmpty());
        store.bindAccount(null); store.bindAccount("a"); assertFalse(store.matches("a", token));
    }
}
