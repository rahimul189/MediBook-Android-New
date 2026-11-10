package com.example.medibook.utils;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.DoctorSchedule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Deterministic non-overlapping slot inventory generation and reserved-interval filtering. */
public class SlotInventoryTest {
    private static final int MONDAY = 1;
    private static final int THURSDAY = 4;
    private static final int FRIDAY = 5;
    private static final long MONDAY_MIDNIGHT = 1_794_160_800_000L;
    private static final long DEC_31_2026_MIDNIGHT = 1_798_653_600_000L;
    private static final long BEFORE_SELECTED_WEEK = MONDAY_MIDNIGHT - 1;

    @Test
    public void overlappingSchedulesProduceOneDeterministicNonOverlappingInventory() {
        DoctorSchedule halfHour = schedule("half", MONDAY, 540, 600, 30, true);
        DoctorSchedule quarterHour = schedule("quarter", MONDAY, 540, 600, 15, true);

        List<AppointmentSlot> inventory = SlotInventory.generate(
                Arrays.asList(halfHour, quarterHour), 2026, 11, 9, BEFORE_SELECTED_WEEK);

        assertEquals(Arrays.asList(
                "quarter:540-555", "quarter:555-570",
                "quarter:570-585", "quarter:585-600"), ownership(inventory));
        assertPairwiseNonOverlapping(inventory);
        assertEquals(4, uniqueSlotIds(inventory).size());
    }

    @Test
    public void exactDuplicatesCollapseToFirstDeterministicScheduleId() {
        AppointmentSlot laterId = slot("z", MONDAY_MIDNIGHT, 540, 570);
        AppointmentSlot earlierId = slot("a", MONDAY_MIDNIGHT, 540, 570);

        List<AppointmentSlot> inventory = SlotInventory.nonOverlapping(
                Arrays.asList(laterId, earlierId));
        assertEquals(Collections.singletonList("a:540-570"), ownership(inventory));

        List<AppointmentSlot> sameId = SlotInventory.nonOverlapping(
                Arrays.asList(earlierId, earlierId));
        assertEquals(Collections.singletonList("a:540-570"), ownership(sameId));
    }

    @Test
    public void partialOverlapIsDroppedAndAdjacentIsKept() {
        AppointmentSlot baseline = slot("baseline", MONDAY_MIDNIGHT, 540, 600);
        AppointmentSlot overlap = slot("overlap", MONDAY_MIDNIGHT, 570, 630);
        AppointmentSlot adjacent = slot("adjacent", MONDAY_MIDNIGHT, 600, 630);
        AppointmentSlot next = slot("next", MONDAY_MIDNIGHT, 630, 660);
        AppointmentSlot contained = slot("contained", MONDAY_MIDNIGHT, 560, 575);

        List<AppointmentSlot> inventory = SlotInventory.nonOverlapping(
                Arrays.asList(baseline, overlap, adjacent, next, contained));

        assertEquals(Arrays.asList(
                "baseline:540-600", "adjacent:600-630", "next:630-660"), ownership(inventory));
        assertPairwiseNonOverlapping(inventory);
    }

    @Test
    public void arbitraryMinuteAlignmentAndFiveMinuteDurationsArePreserved() {
        List<AppointmentSlot> inventory = SlotInventory.nonOverlapping(Arrays.asList(
                slot("a", MONDAY_MIDNIGHT, 545, 560),
                slot("b", MONDAY_MIDNIGHT, 560, 575),
                slot("c", MONDAY_MIDNIGHT, 575, 590),
                slot("d", MONDAY_MIDNIGHT, 590, 605),
                slot("overlap", MONDAY_MIDNIGHT, 550, 555),
                slot("adjacent-five", MONDAY_MIDNIGHT, 605, 610)));

        assertEquals(Arrays.asList(
                "a:545-560", "b:560-575", "c:575-590",
                "d:590-605", "adjacent-five:605-610"), ownership(inventory));
        assertPairwiseNonOverlapping(inventory);
    }

    @Test
    public void reservedIntervalsExcludeOverlapButAllowAdjacency() {
        AppointmentSlot before = slot("before", MONDAY_MIDNIGHT, 540, 570);
        AppointmentSlot crossing = slot("crossing", MONDAY_MIDNIGHT, 570, 600);
        AppointmentSlot after = slot("after", MONDAY_MIDNIGHT, 600, 630);
        AppointmentSlot reserved = slot("booked", MONDAY_MIDNIGHT, 555, 585);

        List<AppointmentSlot> filtered = SlotInventory.nonOverlapping(
                Arrays.asList(before, crossing, after), Collections.singletonList(reserved));
        assertEquals(Collections.singletonList("after:600-630"), ownership(filtered));

        List<AppointmentSlot> adjacent = SlotInventory.nonOverlapping(
                Collections.singletonList(crossing),
                Collections.singletonList(slot("existing", MONDAY_MIDNIGHT, 540, 570)));
        assertEquals(Collections.singletonList("crossing:570-600"), ownership(adjacent));
    }

    @Test
    public void generateWithReservedFiltersAgainstExistingInventory() {
        DoctorSchedule schedule = schedule("monday", MONDAY, 540, 600, 15, true);
        AppointmentSlot reserved = slot("existing", MONDAY_MIDNIGHT, 555, 570);

        List<AppointmentSlot> inventory = SlotInventory.generate(
                Collections.singletonList(schedule), 2026, 11, 9, BEFORE_SELECTED_WEEK,
                Collections.singletonList(reserved));

        assertEquals(Arrays.asList(540, 570, 585), startMinutes(inventory));
        assertPairwiseNonOverlapping(inventory);
        for (AppointmentSlot slot : inventory) {
            assertFalse(ReservationIdentity.overlaps(
                    slot.getStartAtMillis(), slot.getEndAtMillis(),
                    reserved.getStartAtMillis(), reserved.getEndAtMillis()));
        }
    }

    @Test
    public void outputIsSortedUnmodifiableAndPairwiseNonOverlapping() {
        AppointmentSlot late = slot("late", MONDAY_MIDNIGHT, 600, 630);
        AppointmentSlot early = slot("early", MONDAY_MIDNIGHT, 540, 570);
        AppointmentSlot middle = slot("middle", MONDAY_MIDNIGHT, 570, 600);
        AppointmentSlot overlapping = slot("overlapping", MONDAY_MIDNIGHT, 555, 585);

        List<AppointmentSlot> inventory = SlotInventory.nonOverlapping(
                Arrays.asList(late, overlapping, middle, early));

        assertEquals(Arrays.asList(
                "early:540-570", "middle:570-600", "late:600-630"), ownership(inventory));
        assertPairwiseNonOverlapping(inventory);
        assertThrows(UnsupportedOperationException.class, () -> inventory.add(null));
    }

    @Test
    public void differentDatesHaveDifferentReservationIdentities() {
        DoctorSchedule schedule = schedule("weekly", MONDAY, 540, 600, 30, true);
        long nextMondayMidnight = DhakaDateTime.localDateAtMidnight(2026, 11, 16);
        List<AppointmentSlot> firstMonday = SlotInventory.generate(
                Collections.singletonList(schedule), 2026, 11, 9, BEFORE_SELECTED_WEEK);
        List<AppointmentSlot> nextMonday = SlotInventory.generate(
                Collections.singletonList(schedule), 2026, 11, 16,
                nextMondayMidnight - 1);

        assertEquals(2, firstMonday.size());
        assertEquals(2, nextMonday.size());
        String firstId = ReservationIdentity.slotId(firstMonday.get(0));
        String nextId = ReservationIdentity.slotId(nextMonday.get(0));
        assertNotEquals(firstId, nextId);

        String firstKey = ReservationIdentity.reservationKey(
                "doctor-a", ReservationIdentity.dateKey(2026, 11, 9), firstId, 1);
        String nextKey = ReservationIdentity.reservationKey(
                "doctor-a", ReservationIdentity.dateKey(2026, 11, 16), nextId, 1);
        assertNotEquals(firstKey, nextKey);
    }

    @Test
    public void yearBoundaryKeepsHalfOpenIntervals() {
        DoctorSchedule endOfYear = schedule("dec31", THURSDAY, 1410, 1439, 15, true);
        List<AppointmentSlot> december = SlotInventory.generate(
                Collections.singletonList(endOfYear), 2026, 12, 31,
                DEC_31_2026_MIDNIGHT - 1);
        assertEquals(Collections.singletonList("dec31:1410-1425"), ownership(december));
        assertEquals(1_798_738_200_000L, december.get(0).getStartAtMillis());
        assertEquals(1_798_739_100_000L, december.get(0).getEndAtMillis());

        DoctorSchedule startOfYear = schedule("jan1", FRIDAY, 0, 60, 30, true);
        List<AppointmentSlot> january = SlotInventory.generate(
                Collections.singletonList(startOfYear), 2027, 1, 1,
                DEC_31_2026_MIDNIGHT - 1);
        assertEquals(Arrays.asList("jan1:0-30", "jan1:30-60"), ownership(january));
        assertFalse(ReservationIdentity.overlaps(
                december.get(0).getStartAtMillis(), december.get(0).getEndAtMillis(),
                january.get(0).getStartAtMillis(), january.get(0).getEndAtMillis()));
        assertFalse(ReservationIdentity.overlaps(
                december.get(0).getStartAtMillis(), december.get(0).getEndAtMillis(),
                january.get(1).getStartAtMillis(), january.get(1).getEndAtMillis()));
    }

    @Test
    public void nullAndInvalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SlotInventory.nonOverlapping(null));
        assertThrows(IllegalArgumentException.class,
                () -> SlotInventory.nonOverlapping(Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class,
                () -> SlotInventory.nonOverlapping(Collections.emptyList(), null));
        assertThrows(IllegalArgumentException.class,
                () -> SlotInventory.nonOverlapping(Collections.emptyList(),
                        Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class,
                () -> SlotInventory.generate(null, 2026, 11, 9, BEFORE_SELECTED_WEEK));
        assertThrows(IllegalArgumentException.class,
                () -> SlotInventory.generate(Collections.emptyList(),
                        2023, 2, 29, BEFORE_SELECTED_WEEK));
        assertThrows(IllegalArgumentException.class,
                () -> SlotInventory.generate(Collections.singletonList(
                        schedule("bad", MONDAY, 600, 540, 15, true)),
                        2026, 11, 9, BEFORE_SELECTED_WEEK));
    }

    @Test
    public void inactiveSchedulesAndPastSlotsAreNotPublished() {
        DoctorSchedule inactive = schedule("inactive", MONDAY, 540, 600, 15, false);
        assertTrue(SlotInventory.generate(
                Collections.singletonList(inactive), 2026, 11, 9,
                BEFORE_SELECTED_WEEK).isEmpty());

        DoctorSchedule active = schedule("active", MONDAY, 540, 600, 30, true);
        List<AppointmentSlot> future = SlotInventory.generate(
                Collections.singletonList(active), 2026, 11, 9,
                MONDAY_MIDNIGHT + 570 * 60_000L - 1);
        assertEquals(Collections.singletonList("active:570-600"), ownership(future));
    }

    private static DoctorSchedule schedule(String id, int day, int start, int end,
                                           int slotMinutes, boolean active) {
        return new DoctorSchedule(id, day, start, end, slotMinutes, active);
    }

    private static AppointmentSlot slot(String scheduleId, long midnight, int start, int end) {
        return new AppointmentSlot(scheduleId, start, end,
                midnight + start * 60_000L, midnight + end * 60_000L);
    }

    private static void assertPairwiseNonOverlapping(List<AppointmentSlot> slots) {
        for (int first = 0; first < slots.size(); first++) {
            for (int second = first + 1; second < slots.size(); second++) {
                assertFalse("Overlap at " + first + "," + second,
                        ReservationIdentity.overlaps(
                                slots.get(first).getStartAtMillis(),
                                slots.get(first).getEndAtMillis(),
                                slots.get(second).getStartAtMillis(),
                                slots.get(second).getEndAtMillis()));
            }
        }
    }

    private static List<String> ownership(List<AppointmentSlot> slots) {
        List<String> result = new ArrayList<>();
        for (AppointmentSlot slot : slots) {
            result.add(slot.getScheduleId() + ":" + slot.getStartMinute()
                    + "-" + slot.getEndMinute());
        }
        return result;
    }

    private static List<Integer> startMinutes(List<AppointmentSlot> slots) {
        List<Integer> result = new ArrayList<>();
        for (AppointmentSlot slot : slots) result.add(slot.getStartMinute());
        return result;
    }

    private static List<String> uniqueSlotIds(List<AppointmentSlot> slots) {
        List<String> result = new ArrayList<>();
        for (AppointmentSlot slot : slots) {
            result.add(ReservationIdentity.slotId(slot));
        }
        return result;
    }
}
