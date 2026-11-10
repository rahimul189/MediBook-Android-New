package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.models.InventorySlot;
import com.google.firebase.Timestamp;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.junit.Assert.*;

public class SlotSerialsTest {
    private static final long DAY = DhakaDateTime.localDateAtMidnight(2026, 11, 9);
    private InventorySlot slot(int start, int end) {
        return new InventorySlot("doctor", ReservationIdentity.slotId(DAY + start * 60000L, DAY + end * 60000L), "schedule",
                new Timestamp(new Date(DAY)), 1, start, end, new Timestamp(new Date(DAY + start * 60000L)),
                new Timestamp(new Date(DAY + end * 60000L)), "available", 0, null, new Timestamp(1, 0), new Timestamp(1, 0));
    }
    private Appointment appointment(AppointmentStatus status) {
        InventorySlot s = slot(600, 630);
        return new Appointment("booking", "patient", "Patient", "1234567", "doctor", "Doctor", "Medicine", 50000,
                "schedule", 1, s.appointmentDate, 600, 630, s.startAt, s.endAt, status, s.createdAt, s.updatedAt);
    }
    @Test public void numbersArePaddedNotClockMinuteTokens() {
        assertEquals("#01", SlotSerials.label(1, 600, 630)); assertEquals("#02", SlotSerials.label(2, 630, 660));
        assertEquals("#100", SlotSerials.label(100, 600, 630));
    }
    @Test public void formattingIsStableAcrossDeviceLocale() {
        Locale old = Locale.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("ar")); assertEquals("#01", SlotSerials.label(1, 600, 630)); }
        finally { Locale.setDefault(old); }
    }
    @Test public void legacyRecordsUseANeutralPlaceholderNeverATimeOrMutableRank() {
        String fallback = SlotSerials.label(0, 600, 630);
        assertEquals(SlotSerials.UNAVAILABLE, fallback);
        assertFalse(fallback.contains("Q0601")); assertFalse(fallback.contains("#"));
        assertEquals(fallback, SlotSerials.label(0, 600, 630));
        assertEquals(fallback, SlotSerials.label(-1, 403, 423));
        assertEquals(fallback, SlotSerials.label(SlotSerials.MAX + 1, 403, 423));
    }
    @Test public void initialPublicationIsChronologicalAndDoesNotMutateInput() {
        InventorySlot first = slot(540, 560), last = slot(600, 620), middle = slot(560, 580);
        List<InventorySlot> input = Arrays.asList(last, first, middle);
        List<InventorySlot> order = SlotSerials.publicationOrder(input);
        assertSame(first, order.get(0)); assertSame(middle, order.get(1)); assertSame(last, order.get(2)); assertSame(last, input.get(0));
    }
    @Test public void overlappingLegacyDefinitionsHaveDeterministicTieBreakButNoComputedPosition() {
        InventorySlot longSlot = slot(600, 640), shortSlot = slot(600, 620);
        assertSame(shortSlot, SlotSerials.publicationOrder(Arrays.asList(longSlot, shortSlot)).get(0));
        Map<String, Integer> map = new HashMap<>(); map.put(longSlot.id, 1); map.put(shortSlot.id, 2);
        assertEquals(SlotSerials.UNAVAILABLE, SlotSerials.legacyDisplay("Q0601", DAY, map));
    }
    @Test public void slotCopyKeepsReservationIdentityAndGenerationUntouched() {
        InventorySlot original = slot(600, 630), numbered = original.withSlotSerial(3);
        assertEquals(3, numbered.slotSerial); assertEquals(original.id, numbered.id);
        assertEquals(original.generation, numbered.generation); assertEquals(original.status, numbered.status);
        assertEquals(original.appointmentDate, numbered.appointmentDate); assertEquals(original.scheduleId, numbered.scheduleId);
    }
    @Test public void cancelledAndRebookedAppointmentsResolveTheSameSlotNumber() {
        Appointment cancelled = appointment(AppointmentStatus.CANCELLED).withSlotSerial(2);
        Appointment rebooked = appointment(AppointmentStatus.CONFIRMED).withSlotSerial(2);
        assertEquals(cancelled.getSlotSerial(), rebooked.getSlotSerial());
        assertEquals(ReservationIdentity.slotId(cancelled.getStartAt().toDate().getTime(), cancelled.getEndAt().toDate().getTime()),
                ReservationIdentity.slotId(rebooked.getStartAt().toDate().getTime(), rebooked.getEndAt().toDate().getTime()));
    }
    @Test public void completionDoesNotChangeTheAssignedNumber() {
        Appointment a = appointment(AppointmentStatus.CONFIRMED).withSlotSerial(5);
        assertEquals(AppointmentDisplayStatus.COMPLETED, AppointmentDisplayStatus.of(a, Long.MAX_VALUE)); assertEquals(5, a.getSlotSerial());
    }
    @Test public void oldRtdbKeysDisplayOnlyNumbersReadFromFirestore() {
        InventorySlot s = slot(600, 630);
        assertEquals("#07", SlotSerials.legacyDisplay("Q0601", DAY, Collections.singletonMap(s.id, 7)));
        assertEquals(SlotSerials.UNAVAILABLE, SlotSerials.legacyDisplay("Q0601", DAY, Collections.emptyMap()));
        assertFalse(SlotSerials.legacyDisplay("Q0601", DAY, Collections.emptyMap()).contains("#"));
    }
    @Test public void invalidLegacyKeysAndSerialCounterBoundsAreSafe() {
        assertEquals(SlotSerials.UNAVAILABLE, SlotSerials.legacyDisplay("Q0000", DAY, Collections.emptyMap()));
        assertEquals(SlotSerials.UNAVAILABLE, SlotSerials.legacyDisplay("bad", DAY, Collections.emptyMap()));
        assertEquals(1, SlotSerials.next(0)); assertEquals(2, SlotSerials.next(1));
        assertThrows(IllegalArgumentException.class, () -> SlotSerials.next(-1));
        assertThrows(IllegalArgumentException.class, () -> SlotSerials.next(SlotSerials.MAX));
    }
}
