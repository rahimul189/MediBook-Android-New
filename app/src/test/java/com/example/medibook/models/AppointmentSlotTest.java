package com.example.medibook.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Immutable generated-slot value tests; no scheduling or availability claims. */
public class AppointmentSlotTest {
    private static final long START_AT = 1_000_000_000L;
    private static final long THIRTY_MINUTES = 30L * 60_000L;

    @Test
    public void retainsScheduleOwnershipMinutesAndExactInstants() {
        AppointmentSlot slot = new AppointmentSlot(
                "schedule-1", 540, 570, START_AT, START_AT + THIRTY_MINUTES);
        assertEquals("schedule-1", slot.getScheduleId());
        assertEquals(540, slot.getStartMinute());
        assertEquals(570, slot.getEndMinute());
        assertEquals(START_AT, slot.getStartAtMillis());
        assertEquals(START_AT + THIRTY_MINUTES, slot.getEndAtMillis());
    }

    @Test
    public void acceptsStartOfDayAndEndOfDayBoundaries() {
        AppointmentSlot first = new AppointmentSlot("first", 0, 30, START_AT,
                START_AT + THIRTY_MINUTES);
        AppointmentSlot last = new AppointmentSlot("last", 1410, 1440, START_AT,
                START_AT + THIRTY_MINUTES);
        assertEquals(0, first.getStartMinute());
        assertEquals(30, first.getEndMinute());
        assertEquals(1410, last.getStartMinute());
        assertEquals(1440, last.getEndMinute());
    }

    @Test
    public void rejectsInvalidMinuteRanges() {
        int[][] invalid = {
                {-1, 30}, {540, 540}, {570, 540}, {1439, 1439},
                {1440, 1441}, {0, 1441}, {1430, 1500}
        };
        for (int[] range : invalid) {
            assertThrows(range[0] + "-" + range[1], IllegalArgumentException.class,
                    () -> new AppointmentSlot("schedule-1", range[0], range[1],
                            START_AT, START_AT + THIRTY_MINUTES));
        }
    }

    @Test
    public void rejectsMismatchedOrNonPositiveInstants() {
        assertThrows(IllegalArgumentException.class, () -> new AppointmentSlot(
                "schedule-1", 540, 570, START_AT, START_AT));
        assertThrows(IllegalArgumentException.class, () -> new AppointmentSlot(
                "schedule-1", 540, 570, START_AT, START_AT - 1));
        assertThrows(IllegalArgumentException.class, () -> new AppointmentSlot(
                "schedule-1", 540, 570, START_AT, START_AT + THIRTY_MINUTES - 1));
        assertThrows(IllegalArgumentException.class, () -> new AppointmentSlot(
                "schedule-1", 540, 570, START_AT, START_AT + THIRTY_MINUTES + 1));
    }

    @Test
    public void requiresScheduleOwnership() {
        assertThrows(NullPointerException.class, () -> new AppointmentSlot(
                null, 540, 570, START_AT, START_AT + THIRTY_MINUTES));
    }
}
