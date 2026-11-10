package com.example.medibook.reminders;

import com.example.medibook.reminders.ReminderSchedule.CustomRecord;
import com.example.medibook.reminders.ReminderSchedule.DefaultRecord;
import org.junit.Test;

import static org.junit.Assert.*;

public class ReminderScheduleTest {
    private final long now = 1_800_000_000_000L;
    private final long start = now + 3 * ReminderSchedule.DAY;

    @Test public void defaultsAreTwentyFourAndSixHoursBefore() {
        ReminderSchedule.Defaults defaults = ReminderSchedule.defaults(start, now);
        assertEquals(start - 24 * ReminderSchedule.HOUR, defaults.first);
        assertEquals(start - 6 * ReminderSchedule.HOUR, defaults.second);
    }

    @Test public void alreadyPassedDefaultTimesAreSkippedNotFiredImmediately() {
        long soon = now + 8 * ReminderSchedule.HOUR;
        ReminderSchedule.Defaults defaults = ReminderSchedule.defaults(soon, now);
        assertEquals(0, defaults.first);
        assertEquals(soon - 6 * ReminderSchedule.HOUR, defaults.second);
        long verySoon = now + 4 * ReminderSchedule.HOUR;
        ReminderSchedule.Defaults late = ReminderSchedule.defaults(verySoon, now);
        assertEquals(0, late.first);
        assertEquals(0, late.second);
    }

    @Test public void aDefaultExactlyAtTheMinimumLeadIsNotScheduled() {
        long exactly = now + 24 * ReminderSchedule.HOUR + ReminderSchedule.MINIMUM_LEAD;
        assertEquals(0, ReminderSchedule.defaults(exactly, now).first);
        assertEquals(start, start);
    }

    @Test public void pastOrInvalidStartHasNoDefaults() {
        assertEquals(0, ReminderSchedule.defaults(now, now).first);
        assertEquals(0, ReminderSchedule.defaults(now - 1, now).first);
    }

    @Test public void customLeadTimeRequiresSomethingAndStaysWithinLimit() {
        assertFalse(ReminderSchedule.customValid(0, 0));
        assertTrue(ReminderSchedule.customValid(0, 10));
        assertTrue(ReminderSchedule.customValid(1, 30));
        assertTrue(ReminderSchedule.customValid(45, 0));
        assertFalse(ReminderSchedule.customValid(-1, 10));
        assertFalse(ReminderSchedule.customValid(1, 60));
        assertFalse(ReminderSchedule.customValid(73, 0));
    }

    @Test public void customTriggerIsTheExactInstantAndMustRemainFuture() {
        assertEquals(start - 90 * ReminderSchedule.MINUTE, ReminderSchedule.customTrigger(start, 1, 30));
        assertEquals(-1, ReminderSchedule.customTrigger(start, 0, 0));
        long trigger = ReminderSchedule.customTrigger(start, 1, 30);
        assertTrue(ReminderSchedule.customFuture(trigger, now));
        assertFalse(ReminderSchedule.customFuture(start, start));
        assertTrue(ReminderSchedule.customFuture(start - ReminderSchedule.MINUTE, start - 2 * ReminderSchedule.MINUTE));
    }

    @Test public void offsetLabelIsHumanReadable() {
        assertEquals("4 h", ReminderSchedule.offsetLabel(4, 0));
        assertEquals("1 h 30 min", ReminderSchedule.offsetLabel(1, 30));
        assertEquals("45 min", ReminderSchedule.offsetLabel(0, 45));
    }

    @Test public void defaultRecordRoundTripsAndSentFlagsPreventDuplicates() {
        long first = start - 24 * ReminderSchedule.HOUR, second = start - 6 * ReminderSchedule.HOUR;
        DefaultRecord record = DefaultRecord.of(start, first, second);
        assertEquals(record.encode(), DefaultRecord.decode(record.encode()).encode());
        assertFalse(record.firstSent);
        DefaultRecord once = record.markSent(ReminderSchedule.Kind.DEFAULT_24);
        assertTrue(once.firstSent);
        assertFalse(once.secondSent);
        assertTrue(once.markSent(ReminderSchedule.Kind.DEFAULT_24).firstSent);
    }

    @Test public void cancelledDefaultRecordKeepsNoTriggersAndDecodesSafely() {
        DefaultRecord cancelled = DefaultRecord.cancelled(start);
        assertEquals(cancelled.encode(), DefaultRecord.decode(cancelled.encode()).encode());
        assertTrue(DefaultRecord.decode(cancelled.encode()).cancelled);
        assertNull(DefaultRecord.decode("0:1:2:0:0:0"));
        assertNull(DefaultRecord.decode("1:1:2:0:0:0"));
        assertNull(DefaultRecord.decode("bad"));
    }

    @Test public void exactCustomRecordRoundTripsAndRejectsInvalidTriggers() {
        CustomRecord record = CustomRecord.of(start, start - 4 * ReminderSchedule.HOUR, ReminderSchedule.SCHEDULED);
        assertEquals(record.encode(), CustomRecord.decode(record.encode()).encode());
        assertTrue(record.active(now));
        assertEquals(ReminderMode.NOTIFICATION, record.mode);
        String tampered = record.encode().replace(":" + record.trigger + ":", ":" + (record.trigger + 1) + ":");
        assertEquals(record.trigger + 1, CustomRecord.decode(tampered).trigger);
        assertNull(CustomRecord.decode(start + ":" + start + ":scheduled:notification"));
        assertNull(CustomRecord.decode(start + ":0:scheduled:notification"));
        assertNull(CustomRecord.decode("1:2:0:0:sent"));
        assertEquals(ReminderSchedule.SENT, record.withState(ReminderSchedule.SENT).state);
        assertEquals(ReminderMode.NOTIFICATION, record.withState(ReminderSchedule.SENT).mode);
    }

    @Test public void alarmModeRoundTripsAndLegacyRecordsDefaultToNotificationOnly() {
        CustomRecord alarm = CustomRecord.of(start, start - 150 * ReminderSchedule.MINUTE, ReminderSchedule.SCHEDULED, ReminderMode.ALARM);
        CustomRecord decoded = CustomRecord.decode(alarm.encode());
        assertNotNull(decoded);
        assertEquals(ReminderMode.ALARM, decoded.mode);
        assertEquals(ReminderSchedule.SENT, alarm.withState(ReminderSchedule.SENT).state);
        assertEquals(ReminderMode.ALARM, alarm.withState(ReminderSchedule.SENT).mode);

        String legacy = start + ":" + ReminderSchedule.customTrigger(start, 4, 0) + ":4:0:"
                + ReminderSchedule.SCHEDULED;
        assertEquals(ReminderMode.NOTIFICATION, CustomRecord.decode(legacy).mode);
        assertEquals(ReminderSchedule.customTrigger(start, 4, 0), CustomRecord.decode(legacy).trigger);
        assertEquals(4, CustomRecord.decode(legacy).encode().split(":").length);
        assertEquals(ReminderMode.ALARM, CustomRecord.decode(legacy + ":alarm").mode);
        assertNull(CustomRecord.decode(legacy.replace(":4:0:", ":3:0:")));
        assertNull(CustomRecord.decode(alarm.encode().replace(":alarm", ":vibrate")));
        assertEquals(ReminderMode.NOTIFICATION, ReminderMode.orDefault(null));
    }

    @Test public void customReminderMustStillBeInTheFutureToBeActive() {
        CustomRecord record = CustomRecord.of(start, start - ReminderSchedule.HOUR, ReminderSchedule.SCHEDULED);
        assertFalse(record.active(start));
        assertTrue(record.active(record.trigger - 1));
    }

    @Test public void exactTriggerAcceptsEveryFutureInstantStrictlyBeforeAppointment() {
        assertTrue(ReminderSchedule.customTriggerValid(start, now + 1, now));
        assertTrue(ReminderSchedule.customTriggerValid(start, start - 1, now));
        assertFalse(ReminderSchedule.customTriggerValid(start, now, now));
        assertFalse(ReminderSchedule.customTriggerValid(start, start, now));
        assertFalse(ReminderSchedule.customTriggerValid(start, start + 1, now));
        assertFalse(ReminderSchedule.customTriggerValid(now, now - 1, now));
        assertFalse(ReminderSchedule.customTriggerValid(start, now + 1, -1));
        assertTrue(ReminderSchedule.customTriggerValid(now + 60 * ReminderSchedule.DAY, now + ReminderSchedule.DAY, now));
    }

    @Test public void malformedExactRecordsAndInvalidStatesNeverRevive() {
        for (String raw : new String[]{"", "bad", start + ":1:scheduled:alarm:", start + ":1:unknown:alarm",
                start + ":1:sent:bad-mode", start + ":-1:scheduled:alarm", "1:2:scheduled:alarm"}) {
            assertNull(raw, CustomRecord.decode(raw));
        }
        CustomRecord sent = CustomRecord.of(start, now + ReminderSchedule.HOUR, ReminderSchedule.SENT, ReminderMode.ALARM);
        assertFalse(CustomRecord.decode(sent.encode()).active(now));
        assertFalse(sent.withState(ReminderSchedule.CANCELLED).active(now));
    }

    @Test public void reminderKindsRoundTripForWorkManagerInput() {
        for (ReminderSchedule.Kind kind : ReminderSchedule.Kind.values()) {
            assertEquals(kind, ReminderSchedule.Kind.from(kind.wire));
        }
        assertNull(ReminderSchedule.Kind.from(null));
        assertNull(ReminderSchedule.Kind.from("unknown"));
    }
}
