package com.example.medibook.utils;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.DoctorSchedule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Deterministic schedule-derived slot tests; no appointment occupancy is queried. */
public class AppointmentSlotsTest {
    private static final int MONDAY = 1;
    private static final int TUESDAY = 2;
    private static final int THURSDAY = 4;
    private static final int FRIDAY = 5;
    private static final int SUNDAY = 7;

    private static final long NOV_7_2026_MIDNIGHT_MILLIS = 1_793_988_000_000L;
    private static final long DEC_31_2026_MIDNIGHT_MILLIS = 1_798_653_600_000L;
    private static final long MONDAY_2026_11_09_MIDNIGHT_MILLIS = 1_794_160_800_000L;
    private static final long MONDAY_0900_MILLIS = 1_794_193_200_000L;
    private static final long MONDAY_0915_MILLIS = 1_794_194_100_000L;
    private static final long MONDAY_0930_MILLIS = 1_794_195_000_000L;
    private static final long MONDAY_0945_MILLIS = 1_794_195_900_000L;
    private static final long MONDAY_1000_MILLIS = 1_794_196_800_000L;
    private static final long FEB_29_2024_0600_MILLIS = 1_709_164_800_000L;
    private static final long FEB_29_2024_0630_MILLIS = 1_709_166_600_000L;
    private static final long FEB_29_2024_0700_MILLIS = 1_709_168_400_000L;
    private static final long BEFORE_SELECTED_WEEK = NOV_7_2026_MIDNIGHT_MILLIS - 1;

    @Test
    public void exactMultipleWindowStartsAtScheduleStartAndKeepsFullSlots() {
        DoctorSchedule schedule = schedule("monday-1", MONDAY, 540, 600, 15, true);
        List<AppointmentSlot> slots = AppointmentSlots.generate(
                Collections.singletonList(schedule), 2026, 11, 9,
                MONDAY_2026_11_09_MIDNIGHT_MILLIS - 1);

        assertEquals(4, slots.size());
        assertSlot(slots.get(0), "monday-1", 540, 555);
        assertSlot(slots.get(1), "monday-1", 555, 570);
        assertSlot(slots.get(2), "monday-1", 570, 585);
        assertSlot(slots.get(3), "monday-1", 585, 600);
        assertEquals(MONDAY_0900_MILLIS, slots.get(0).getStartAtMillis());
        assertEquals(MONDAY_0915_MILLIS, slots.get(0).getEndAtMillis());
        assertEquals(MONDAY_0915_MILLIS, slots.get(1).getStartAtMillis());
        assertEquals(MONDAY_1000_MILLIS, slots.get(3).getEndAtMillis());
        assertThrows(UnsupportedOperationException.class, () -> slots.add(null));
    }

    @Test
    public void unalignedStartsAndPartialFinalSlotsAreHandled() {
        DoctorSchedule unaligned = schedule("unaligned", MONDAY, 545, 615, 15, true);
        List<AppointmentSlot> slots = AppointmentSlots.generate(
                Collections.singletonList(unaligned), 2026, 11, 9,
                MONDAY_2026_11_09_MIDNIGHT_MILLIS - 1);
        assertEquals(4, slots.size());
        assertSlot(slots.get(0), "unaligned", 545, 560);
        assertSlot(slots.get(1), "unaligned", 560, 575);
        assertSlot(slots.get(2), "unaligned", 575, 590);
        assertSlot(slots.get(3), "unaligned", 590, 605);
        assertTrue(slots.get(3).getEndMinute() < 615);

        DoctorSchedule partial = schedule("partial", MONDAY, 540, 610, 20, true);
        List<AppointmentSlot> partialSlots = AppointmentSlots.generate(
                Collections.singletonList(partial), 2026, 11, 9,
                MONDAY_2026_11_09_MIDNIGHT_MILLIS - 1);
        assertEquals(3, partialSlots.size());
        assertSlot(partialSlots.get(0), "partial", 540, 560);
        assertSlot(partialSlots.get(1), "partial", 560, 580);
        assertSlot(partialSlots.get(2), "partial", 580, 600);
        assertTrue(partialSlots.get(2).getEndMinute() < 610);
    }

    @Test
    public void inactiveAndOtherWeekdaySchedulesAreExcluded() {
        List<DoctorSchedule> schedules = Arrays.asList(
                schedule("sunday", SUNDAY, 540, 570, 30, true),
                schedule("tuesday", TUESDAY, 540, 570, 30, true),
                schedule("inactive", MONDAY, 600, 630, 30, false),
                schedule("monday", MONDAY, 540, 570, 30, true));

        List<AppointmentSlot> mondaySlots = AppointmentSlots.generate(
                schedules, 2026, 11, 9, BEFORE_SELECTED_WEEK);
        assertEquals(1, mondaySlots.size());
        assertSlot(mondaySlots.get(0), "monday", 540, 570);

        List<AppointmentSlot> sundaySlots = AppointmentSlots.generate(
                schedules, 2026, 11, 8, BEFORE_SELECTED_WEEK);
        assertEquals(1, sundaySlots.size());
        assertSlot(sundaySlots.get(0), "sunday", 540, 570);

        List<AppointmentSlot> tuesdaySlots = AppointmentSlots.generate(
                schedules, 2026, 11, 10, BEFORE_SELECTED_WEEK);
        assertEquals(1, tuesdaySlots.size());
        assertSlot(tuesdaySlots.get(0), "tuesday", 540, 570);
    }

    @Test
    public void slotsAreSortedByStartTimeRegardlessOfInputOrder() {
        DoctorSchedule late = schedule("late", MONDAY, 600, 630, 30, true);
        DoctorSchedule early = schedule("early", MONDAY, 540, 570, 30, true);
        DoctorSchedule middle = schedule("middle", MONDAY, 570, 600, 30, true);

        List<AppointmentSlot> slots = AppointmentSlots.generate(
                Arrays.asList(late, middle, early), 2026, 11, 9,
                MONDAY_2026_11_09_MIDNIGHT_MILLIS - 1);
        assertEquals(3, slots.size());
        assertSlot(slots.get(0), "early", 540, 570);
        assertSlot(slots.get(1), "middle", 570, 600);
        assertSlot(slots.get(2), "late", 600, 630);
    }

    @Test
    public void pastCurrentAndFutureSlotsUseStrictStartComparison() {
        DoctorSchedule schedule = schedule("monday-1", MONDAY, 540, 600, 15, true);
        List<DoctorSchedule> schedules = Collections.singletonList(schedule);

        assertEquals(Arrays.asList(540, 555, 570, 585), startMinutes(
                AppointmentSlots.generate(schedules, 2026, 11, 9, MONDAY_0900_MILLIS - 1)));
        // A slot starting exactly now is excluded.
        assertEquals(Arrays.asList(555, 570, 585), startMinutes(
                AppointmentSlots.generate(schedules, 2026, 11, 9, MONDAY_0900_MILLIS)));
        assertEquals(Arrays.asList(570, 585), startMinutes(
                AppointmentSlots.generate(schedules, 2026, 11, 9, MONDAY_0915_MILLIS)));
        assertTrue(AppointmentSlots.generate(schedules, 2026, 11, 9, MONDAY_0945_MILLIS).isEmpty());
        assertTrue(AppointmentSlots.generate(schedules, 2026, 11, 9, MONDAY_1000_MILLIS).isEmpty());

        List<AppointmentSlot> future = AppointmentSlots.generate(
                schedules, 2026, 11, 9, BEFORE_SELECTED_WEEK);
        assertEquals(4, future.size());
        for (AppointmentSlot slot : future) {
            assertTrue(slot.getStartAtMillis() > BEFORE_SELECTED_WEEK);
        }
    }

    @Test
    public void exactDuplicatesAreDeduplicatedWithDeterministicOwnership() {
        DoctorSchedule duplicateB = schedule("b", MONDAY, 540, 600, 30, true);
        DoctorSchedule quarter = schedule("c", MONDAY, 540, 600, 15, true);
        DoctorSchedule duplicateA = schedule("a", MONDAY, 540, 600, 30, true);
        List<DoctorSchedule> firstOrder = Arrays.asList(duplicateB, quarter, duplicateA);
        List<DoctorSchedule> secondOrder = Arrays.asList(duplicateA, duplicateB, quarter);

        List<String> expected = Arrays.asList(
                "c:540-555", "a:540-570", "c:555-570",
                "c:570-585", "a:570-600", "c:585-600");
        List<AppointmentSlot> first = AppointmentSlots.generate(
                firstOrder, 2026, 11, 9, MONDAY_2026_11_09_MIDNIGHT_MILLIS - 1);
        List<AppointmentSlot> second = AppointmentSlots.generate(
                secondOrder, 2026, 11, 9, MONDAY_2026_11_09_MIDNIGHT_MILLIS - 1);

        assertEquals(expected, ownership(first));
        assertEquals(expected, ownership(second));
        assertEquals(6, first.size());
        // Differently sized overlapping slots remain because only exact duplicates are removed.
        assertTrue(ownership(first).contains("c:540-555"));
        assertTrue(ownership(first).contains("a:540-570"));
        assertFalse(ownership(first).contains("b:540-570"));
        assertFalse(ownership(first).contains("b:570-600"));
    }

    @Test
    public void emptySchedulesReturnAnEmptyUnmodifiableList() {
        List<AppointmentSlot> slots = AppointmentSlots.generate(
                Collections.emptyList(), 2026, 11, 9, BEFORE_SELECTED_WEEK);
        assertTrue(slots.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> slots.add(null));

        List<DoctorSchedule> otherWeekday = Collections.singletonList(
                schedule("tuesday", TUESDAY, 540, 570, 15, true));
        assertTrue(AppointmentSlots.generate(
                otherWeekday, 2026, 11, 9, BEFORE_SELECTED_WEEK).isEmpty());
    }

    @Test
    public void nullSchedulesAndInvalidDatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AppointmentSlots.generate(
                null, 2026, 11, 9, BEFORE_SELECTED_WEEK));
        assertThrows(IllegalArgumentException.class, () -> AppointmentSlots.generate(
                Collections.emptyList(), 2023, 2, 29, BEFORE_SELECTED_WEEK));
        assertThrows(IllegalArgumentException.class, () -> AppointmentSlots.generate(
                Collections.emptyList(), 2026, 13, 1, BEFORE_SELECTED_WEEK));
    }

    @Test
    public void invalidSchedulesAreRejected() {
        List<List<DoctorSchedule>> invalid = Arrays.asList(
                Collections.singletonList(null),
                Collections.singletonList(schedule("bad-day", 8, 540, 600, 15, true)),
                Collections.singletonList(schedule("bad-order", MONDAY, 600, 540, 15, true)),
                Collections.singletonList(schedule("bad-slot", MONDAY, 540, 600, 0, true)),
                Collections.singletonList(schedule("too-long", MONDAY, 540, 600, 90, true)),
                Collections.singletonList(schedule("bad!", MONDAY, 540, 600, 15, true)));
        for (List<DoctorSchedule> schedules : invalid) {
            assertThrows(IllegalArgumentException.class, () -> AppointmentSlots.generate(
                    schedules, 2026, 11, 9, BEFORE_SELECTED_WEEK));
        }
    }

    @Test
    public void deviceDefaultTimezoneDoesNotChangeGeneratedInstants() {
        TimeZone previous = TimeZone.getDefault();
        DoctorSchedule schedule = schedule("monday-1", MONDAY, 540, 600, 30, true);
        try {
            for (String zoneId : new String[]{
                    "America/Los_Angeles", "Pacific/Kiritimati", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
                List<AppointmentSlot> slots = AppointmentSlots.generate(
                        Collections.singletonList(schedule), 2026, 11, 9,
                        MONDAY_2026_11_09_MIDNIGHT_MILLIS - 1);
                assertEquals(zoneId, 2, slots.size());
                assertEquals(zoneId, MONDAY_0900_MILLIS,
                        slots.get(0).getStartAtMillis());
                assertEquals(zoneId, MONDAY_0930_MILLIS,
                        slots.get(0).getEndAtMillis());
                assertEquals(zoneId, MONDAY_0930_MILLIS,
                        slots.get(1).getStartAtMillis());
                assertEquals(zoneId, MONDAY_1000_MILLIS,
                        slots.get(1).getEndAtMillis());
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    public void monthYearAndLeapBoundariesKeepDhakaInstants() {
        DoctorSchedule dec31 = schedule("dec31", THURSDAY, 1410, 1439, 15, true);
        List<AppointmentSlot> endOfYear = AppointmentSlots.generate(
                Collections.singletonList(dec31), 2026, 12, 31,
                DEC_31_2026_MIDNIGHT_MILLIS - 1);
        assertEquals(1, endOfYear.size());
        assertSlot(endOfYear.get(0), "dec31", 1410, 1425);
        assertEquals(1_798_738_200_000L, endOfYear.get(0).getStartAtMillis());
        assertEquals(1_798_739_100_000L, endOfYear.get(0).getEndAtMillis());

        DoctorSchedule jan1 = schedule("jan1", FRIDAY, 0, 60, 30, true);
        List<AppointmentSlot> startOfYear = AppointmentSlots.generate(
                Collections.singletonList(jan1), 2027, 1, 1,
                DEC_31_2026_MIDNIGHT_MILLIS - 1);
        assertEquals(2, startOfYear.size());
        assertSlot(startOfYear.get(0), "jan1", 0, 30);
        assertSlot(startOfYear.get(1), "jan1", 30, 60);
        assertEquals(1_798_740_000_000L, startOfYear.get(0).getStartAtMillis());
        assertEquals(1_798_741_800_000L, startOfYear.get(1).getStartAtMillis());
        assertEquals(1_798_743_600_000L, startOfYear.get(1).getEndAtMillis());

        DoctorSchedule leapDay = schedule("leap", THURSDAY, 360, 420, 30, true);
        List<AppointmentSlot> leap = AppointmentSlots.generate(
                Collections.singletonList(leapDay), 2024, 2, 29,
                DhakaDateTime.localDateAtMidnight(2024, 2, 28) - 1);
        assertEquals(2, leap.size());
        assertSlot(leap.get(0), "leap", 360, 390);
        assertSlot(leap.get(1), "leap", 390, 420);
        assertEquals(FEB_29_2024_0600_MILLIS, leap.get(0).getStartAtMillis());
        assertEquals(FEB_29_2024_0630_MILLIS, leap.get(0).getEndAtMillis());
        assertEquals(FEB_29_2024_0630_MILLIS, leap.get(1).getStartAtMillis());
        assertEquals(FEB_29_2024_0700_MILLIS, leap.get(1).getEndAtMillis());
    }

    private static DoctorSchedule schedule(String id, int day, int start, int end,
                                           int slotMinutes, boolean active) {
        return new DoctorSchedule(id, day, start, end, slotMinutes, active);
    }

    private static void assertSlot(AppointmentSlot slot, String scheduleId,
                                   int startMinute, int endMinute) {
        assertEquals(scheduleId, slot.getScheduleId());
        assertEquals(startMinute, slot.getStartMinute());
        assertEquals(endMinute, slot.getEndMinute());
        assertEquals((endMinute - startMinute) * 60_000L,
                slot.getEndAtMillis() - slot.getStartAtMillis());
    }

    private static List<Integer> startMinutes(List<AppointmentSlot> slots) {
        List<Integer> result = new ArrayList<>();
        for (AppointmentSlot slot : slots) result.add(slot.getStartMinute());
        return result;
    }

    private static List<String> ownership(List<AppointmentSlot> slots) {
        List<String> result = new ArrayList<>();
        for (AppointmentSlot slot : slots) {
            result.add(slot.getScheduleId() + ":" + slot.getStartMinute()
                    + "-" + slot.getEndMinute());
        }
        return result;
    }
}
