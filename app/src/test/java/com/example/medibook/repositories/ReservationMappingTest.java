package com.example.medibook.repositories;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.BookingLock;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.ReservationIdentity;
import com.google.firebase.Timestamp;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Strict reads and the exact network write masks, without connecting to Firebase. */
public class ReservationMappingTest {
    private static final long MIDNIGHT = DhakaDateTime.localDateAtMidnight(2026, 11, 9);
    private static final Timestamp STAMP = new Timestamp(1, 0);
    private static final AppointmentSlot INTERVAL = new AppointmentSlot("schedule", 540, 560,
            MIDNIGHT + 540 * 60000L, MIDNIGHT + 560 * 60000L);
    private static final String ID = ReservationIdentity.slotId(INTERVAL);

    private Map<String, Object> slotMap() { return InventorySlotMapper.publishMap(INTERVAL, MIDNIGHT, 1, STAMP); }
    private InventorySlot slot() { return InventorySlotMapper.fromMap("doctor", Long.toString(MIDNIGHT), ID, slotMap()); }
    private InventorySlot read(Map<String, Object> map) { return InventorySlotMapper.fromMap("doctor", Long.toString(MIDNIGHT), ID, map); }
    private BookingLock lock() { return new BookingLock("appointment", "patient", "schedule", slot().startAt, slot().endAt, STAMP); }
    private Doctor doctor() { return new Doctor("doctor", "Doctor", "Medicine", "MBBS", new BigDecimal("500.25"), "", true); }

    @Test public void inventoryHasExactTwelveFieldsAndDhakaPath() {
        assertEquals(12, slotMap().size());
        InventorySlot slot = slot(); assertEquals("available", slot.status); assertNull(slot.appointmentId);
        assertEquals(0, slot.generation); assertEquals(MIDNIGHT, slot.appointmentDate.toDate().getTime());
        assertEquals(540, slot.startMinute); assertEquals(1, slot.dayOfWeek);
        assertThrows(IllegalArgumentException.class, () -> InventorySlotMapper.fromMap("doctor", "wrong", ID, slotMap()));
        assertThrows(IllegalArgumentException.class, () -> InventorySlotMapper.fromMap("doctor", Long.toString(MIDNIGHT), "wrong", slotMap()));
    }
    @Test public void inventoryRejectsEveryMissingFieldAndExtraField() {
        for (String field : slotMap().keySet()) {
            Map<String, Object> missing = slotMap(); missing.remove(field);
            assertThrows(field, IllegalArgumentException.class, () -> read(missing));
        }
        Map<String, Object> extra = slotMap(); extra.put("doctorId", "doctor");
        assertThrows(IllegalArgumentException.class, () -> read(extra));
    }
    @Test public void inventoryRejectsIntegralDoublesAndInvalidStates() {
        for (String field : new String[]{"generation", "dayOfWeek", "startMinute", "endMinute"}) {
            Map<String, Object> map = slotMap(); map.put(field, ((Number) map.get(field)).doubleValue());
            assertThrows(field, IllegalArgumentException.class, () -> read(map));
        }
        Map<String, Object> map = slotMap(); map.put("status", "booked");
        assertThrows(IllegalArgumentException.class, () -> read(map));
        map.put("appointmentId", "appointment"); map.put("generation", 1L);
        assertEquals("appointment", read(map).appointmentId);
        map.put("status", "available"); assertThrows(IllegalArgumentException.class, () -> read(map));
    }
    @Test public void inventoryRejectsWrongDateMinuteWeekdayAndTimestamp() {
        for (String field : new String[]{"appointmentDate", "startAt", "endAt"}) {
            Map<String, Object> map = slotMap(); Timestamp original = (Timestamp) map.get(field);
            map.put(field, new Timestamp(original.getSeconds(), 1000));
            assertThrows(IllegalArgumentException.class, () -> read(map));
        }
        Map<String, Object> map = slotMap(); map.put("dayOfWeek", 2);
        assertThrows(IllegalArgumentException.class, () -> read(map));
        map.put("dayOfWeek", 1); map.put("startMinute", 541);
        assertThrows(IllegalArgumentException.class, () -> read(map));
    }
    @Test public void publisherRejectsMinute1440AndMismatchedDate() {
        AppointmentSlot midnightEnd = new AppointmentSlot("schedule", 1420, 1440, MIDNIGHT + 1420 * 60000L, MIDNIGHT + 1440 * 60000L);
        assertThrows(IllegalArgumentException.class, () -> InventorySlotMapper.publishMap(midnightEnd, MIDNIGHT, 1, STAMP));
        assertThrows(IllegalArgumentException.class, () -> InventorySlotMapper.publishMap(INTERVAL, MIDNIGHT + 60000L, 1, STAMP));
    }
    @Test public void lockHasSixFieldsAndGenerationScopedPath() {
        Map<String, Object> map = BookingLockMapper.writeMap(lock(), STAMP); assertEquals(6, map.size());
        assertEquals("patient", BookingLockMapper.fromMap("g2", 2, ID, map).patientUid);
        assertThrows(IllegalArgumentException.class, () -> BookingLockMapper.fromMap("g1", 2, ID, map));
        assertThrows(IllegalArgumentException.class, () -> BookingLockMapper.fromMap("g0", 0, ID, map));
        assertThrows(IllegalArgumentException.class, () -> BookingLockMapper.fromMap("g2", 2, "wrong", map));
    }
    @Test public void lockRejectsEveryMissingFieldAndUnexpectedIdentity() {
        Map<String, Object> original = BookingLockMapper.writeMap(lock(), STAMP);
        for (String field : original.keySet()) {
            Map<String, Object> map = new HashMap<>(original); map.remove(field);
            assertThrows(IllegalArgumentException.class, () -> BookingLockMapper.fromMap("g1", 1, ID, map));
        }
        original.put("generation", 1L);
        assertThrows(IllegalArgumentException.class, () -> BookingLockMapper.fromMap("g1", 1, ID, original));
    }
    @Test public void bookingWriteMatchesExistingSeventeenFieldContract() {
        Object transform = new Object();
        Map<String, Object> map = AppointmentWrites.confirmed("appointment", "patient", "Patient", "1234567", doctor(), slot(), transform);
        assertEquals(17, map.size()); assertEquals("Confirmed", map.get("status")); assertEquals(50025L, map.get("consultationFee"));
        assertSame(transform, map.get("createdAt")); assertSame(transform, map.get("updatedAt"));
        assertFalse(map.containsKey("generation")); assertFalse(map.containsKey("id"));
        map.put("createdAt", STAMP); map.put("updatedAt", STAMP);
        assertEquals("doctor", AppointmentMapper.fromMap("appointment", map).getDoctorId());
    }
    @Test public void bookingOnlyPatchesFourMutableInventoryFields() {
        Map<String, Object> map = AppointmentWrites.reserve(slot(), "appointment", STAMP);
        assertEquals(4, map.size()); assertEquals(1L, map.get("generation")); assertEquals("booked", map.get("status"));
        assertFalse(map.containsKey("createdAt")); assertFalse(map.containsKey("startAt"));
        Map<String, Object> blocked = slotMap(); blocked.put("status", "blocked");
        assertThrows(IllegalArgumentException.class, () -> AppointmentWrites.reserve(read(blocked), "appointment", STAMP));
    }
    @Test public void cancellationPreservesGenerationAndImmutableHistory() {
        assertEquals(2, AppointmentWrites.cancel(STAMP).size());
        Map<String, Object> release = AppointmentWrites.release(false, STAMP);
        assertEquals(3, release.size()); assertNull(release.get("appointmentId"));
        assertFalse(release.containsKey("generation")); assertFalse(release.containsKey("createdAt"));
        assertEquals("available", release.get("status"));
        assertEquals("blocked", AppointmentWrites.release(true, STAMP).get("status"));
    }
    @Test public void repositoryErrorsKeepDomainFailuresAndRejectMalformedData() {
        assertEquals(AppointmentRepository.Error.SLOT_TAKEN, AppointmentRepository.errorFor(
                new RuntimeException(new AppointmentRepository.Failure(AppointmentRepository.Error.SLOT_TAKEN))));
        assertEquals(AppointmentRepository.Error.INVALID_DATA, AppointmentRepository.errorFor(new IllegalArgumentException()));
        assertEquals(AppointmentRepository.Error.UNKNOWN, AppointmentRepository.errorFor(new RuntimeException()));
    }
}
