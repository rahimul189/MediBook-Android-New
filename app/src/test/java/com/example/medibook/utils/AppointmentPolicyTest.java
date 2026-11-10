package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.repositories.AppointmentMapper;
import com.example.medibook.repositories.AppointmentWrites;
import com.example.medibook.repositories.InventorySlotMapper;
import com.google.firebase.Timestamp;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class AppointmentPolicyTest {
    private final long midnight = DhakaDateTime.localDateAtMidnight(2026, 11, 9);
    private final Timestamp stamp = new Timestamp(1, 0);
    private Doctor doctor(String fee) { return new Doctor("doctor", "Doctor", "Medicine", "MBBS", new BigDecimal(fee), "", true); }
    private InventorySlot slot() {
        AppointmentSlot slot = new AppointmentSlot("schedule", 540, 560, midnight + 540 * 60000L, midnight + 560 * 60000L);
        return InventorySlotMapper.fromMap("doctor", Long.toString(midnight), ReservationIdentity.slotId(slot),
                InventorySlotMapper.publishMap(slot, midnight, 1, stamp));
    }
    private Appointment appointment(String status) {
        Map<String, Object> map = AppointmentWrites.confirmed("appointment", "patient", "Patient", "1234567", doctor("500"), slot(), stamp);
        map.put("status", status); return AppointmentMapper.fromMap("appointment", map);
    }
    @Test public void cancellationIsStrictlyBeforeStartAndCancelledIsTerminal() {
        Appointment a = appointment("Confirmed"); long start = a.getStartAt().toDate().getTime();
        assertTrue(AppointmentPolicy.canCancel(a, start - 1));
        assertFalse(AppointmentPolicy.canCancel(a, start)); assertFalse(AppointmentPolicy.canCancel(a, start + 1));
        assertFalse(AppointmentPolicy.canCancel(appointment("Cancelled"), start - 1));
    }
    @Test public void unchangedConfirmedReadDoesNotProveCancellationSucceeded() {
        assertFalse(AppointmentPolicy.reconciles(appointment("Confirmed"), true));
        assertTrue(AppointmentPolicy.reconciles(appointment("Cancelled"), true));
        assertTrue(AppointmentPolicy.reconciles(appointment("Confirmed"), false));
        assertFalse(AppointmentPolicy.reconciles(null, false));
    }
    @Test public void currentSchedulesMustMatchWindowDurationAlignmentAndStatus() {
        assertTrue(AppointmentPolicy.fits(slot(), new DoctorSchedule("schedule", 1, 540, 660, 20, true, stamp)));
        assertFalse(AppointmentPolicy.fits(slot(), new DoctorSchedule("schedule", 1, 540, 660, 20, false, stamp)));
        assertFalse(AppointmentPolicy.fits(slot(), new DoctorSchedule("schedule", 2, 540, 660, 20, true, stamp)));
        assertFalse(AppointmentPolicy.fits(slot(), new DoctorSchedule("schedule", 1, 530, 660, 20, true, stamp)));
        assertFalse(AppointmentPolicy.fits(slot(), new DoctorSchedule("schedule", 1, 540, 660, 30, true, stamp)));
    }
    @Test public void changedPriceOrInactiveDoctorRequiresNewConfirmation() {
        assertTrue(AppointmentPolicy.sameQuote(doctor("500.00"), doctor("500")));
        assertFalse(AppointmentPolicy.sameQuote(doctor("500"), doctor("501")));
        assertFalse(AppointmentPolicy.sameQuote(doctor("500"), doctor("500").withActive(false)));
    }
    @Test public void queueTokensAreStableOrderedAndNotDependentOnDeviceTimezone() {
        assertEquals("Q0001", AppointmentPolicy.queueToken(0));
        assertEquals("Q0541", AppointmentPolicy.queueToken(540));
        assertTrue(AppointmentPolicy.queueToken(540).compareTo(AppointmentPolicy.queueToken(560)) < 0);
        TimeZone old = TimeZone.getDefault();
        try { TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); assertEquals("Q0541", AppointmentPolicy.queueToken(540)); }
        finally { TimeZone.setDefault(old); }
        assertEquals("Q1440", AppointmentPolicy.queueToken(1439));
        assertThrows(IllegalArgumentException.class, () -> AppointmentPolicy.queueToken(-1));
    }
    @Test public void publisherReservesBookedBlockedAndOldIntervalsWithoutReopening() {
        DoctorSchedule schedule = new DoctorSchedule("schedule", 1, 540, 600, 20, true, stamp);
        AppointmentSlot existing = slot().interval();
        java.util.List<AppointmentSlot> planned = SlotInventory.generate(Collections.singletonList(schedule), 2026, 11, 9, 0,
                Collections.singletonList(existing));
        assertEquals(2, planned.size()); assertEquals(560, planned.get(0).getStartMinute());
        assertTrue(SlotInventory.generate(Collections.singletonList(schedule), 2026, 11, 9, 0,
                Arrays.asList(existing, planned.get(0), planned.get(1))).isEmpty());
    }
}
