package com.example.medibook.repositories;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.utils.AppointmentPolicy;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.ReservationIdentity;
import com.google.firebase.Timestamp;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** Narrow regressions for the device workflow: dates, empty-state causes and real SDK diagnostics. */
public class AppointmentWorkflowTest {
    private static final long MONDAY = DhakaDateTime.localDateAtMidnight(2026, 11, 9);
    private static final Timestamp STAMP = new Timestamp(1, 0);
    private final Doctor doctor = new Doctor("doctor", "Doctor", "Medicine", "MBBS", new BigDecimal("500"), "", true);
    private final DoctorSchedule schedule = new DoctorSchedule("weekly", 1, 540, 580, 20, true, STAMP);

    private InventorySlot slot(String status) {
        AppointmentSlot interval = new AppointmentSlot("weekly", 540, 560, MONDAY + 540 * 60000L, MONDAY + 560 * 60000L);
        Map<String, Object> map = InventorySlotMapper.publishMap(interval, MONDAY, 1, STAMP);
        map.put("status", status);
        if ("booked".equals(status)) { map.put("generation", 1L); map.put("appointmentId", "booking"); }
        return InventorySlotMapper.fromMap("doctor", Long.toString(MONDAY), ReservationIdentity.slotId(interval), map);
    }
    private AppointmentRepository.Availability availability(List<InventorySlot> slots) {
        return AppointmentRepository.availabilityFrom(doctor, MONDAY, Collections.singletonList(schedule), slots, MONDAY);
    }
    @Test public void wrongWeekdayIsNotReportedAsSuccessfulZeroPublication() {
        AppointmentRepository.Failure failure = assertThrows(AppointmentRepository.Failure.class,
                () -> SlotInventoryPublisher.candidates(Collections.singletonList(schedule), 2026, 11, 10, MONDAY));
        assertEquals(AppointmentRepository.Error.NO_ACTIVE_SCHEDULE, failure.error);
        assertEquals(2, SlotInventoryPublisher.candidates(Collections.singletonList(schedule), 2026, 11, 9, MONDAY).size());
    }
    @Test public void disabledAndElapsedSchedulesHaveActionableFailures() {
        assertEquals(AppointmentRepository.Error.NO_ACTIVE_SCHEDULE, assertThrows(AppointmentRepository.Failure.class,
                () -> SlotInventoryPublisher.candidates(Collections.singletonList(schedule.withActive(false)), 2026, 11, 9, MONDAY)).error);
        assertEquals(AppointmentRepository.Error.NO_FUTURE_SLOTS, assertThrows(AppointmentRepository.Failure.class,
                () -> SlotInventoryPublisher.candidates(Collections.singletonList(schedule), 2026, 11, 9, MONDAY + 600 * 60000L)).error);
    }
    @Test public void defaultPublicationDateMatchesActiveWeekdayInDhaka() {
        long saturday = DhakaDateTime.localDateAtMidnight(2026, 11, 7);
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(MONDAY, AppointmentPolicy.nextBookingDate(Collections.singletonList(schedule), saturday));
            assertEquals(MONDAY + 7 * 86_400_000L, AppointmentPolicy.nextBookingDate(Collections.singletonList(schedule), MONDAY));
            assertEquals(-1, AppointmentPolicy.nextBookingDate(Collections.singletonList(schedule.withActive(false)), saturday));
        } finally { TimeZone.setDefault(previous); }
    }
    @Test public void unpublishedAndFullyBookedAreNotTheSameEmptyState() {
        assertEquals(AppointmentRepository.Error.NO_INVENTORY, availability(Collections.emptyList()).emptyReason);
        assertEquals(AppointmentRepository.Error.NO_AVAILABLE_SLOTS, availability(Collections.singletonList(slot("booked"))).emptyReason);
        assertEquals(AppointmentRepository.Error.NO_AVAILABLE_SLOTS, availability(Collections.singletonList(slot("blocked"))).emptyReason);
        AppointmentRepository.Availability available = availability(Collections.singletonList(slot("available")));
        assertNull(available.emptyReason); assertEquals(1, available.slots.size()); assertEquals(1, available.publishedCount);
    }
    @Test public void inactiveOrStaleScheduleDoesNotSilentlyLookFullyBooked() {
        AppointmentRepository.Availability result = AppointmentRepository.availabilityFrom(doctor, MONDAY,
                Collections.singletonList(schedule.withActive(false)), Collections.singletonList(slot("available")), MONDAY);
        assertEquals(AppointmentRepository.Error.NO_ACTIVE_SCHEDULE, result.emptyReason); assertTrue(result.slots.isEmpty());
        result = AppointmentRepository.availabilityFrom(doctor, MONDAY, Collections.singletonList(schedule),
                Collections.singletonList(slot("available")), MONDAY + 600 * 60000L);
        assertEquals(AppointmentRepository.Error.NO_FUTURE_SLOTS, result.emptyReason);
    }
    @Test public void publishingAndPatientReadUseIdenticalEpochMillisPath() {
        assertEquals("doctorSlotInventory/doctor/dates/1794160800000/slots", AppointmentRepository.inventoryPath("doctor", MONDAY));
        assertEquals(AppointmentRepository.inventoryPath("doctor", MONDAY), availability(Collections.singletonList(slot("available"))).path);
    }
    @Test public void canonicalFirestoreCodesAndOperationContextArePreserved() {
        assertEquals(AppointmentRepository.Error.PERMISSION, AppointmentRepository.errorForCode("PERMISSION_DENIED"));
        assertEquals(AppointmentRepository.Error.FAILED_PRECONDITION, AppointmentRepository.errorForCode("FAILED_PRECONDITION"));
        assertEquals(AppointmentRepository.Error.MISSING_DOCUMENT, AppointmentRepository.errorForCode("NOT_FOUND"));
        assertEquals(AppointmentRepository.Error.NETWORK, AppointmentRepository.errorForCode("UNAVAILABLE"));
        AppointmentRepository.OperationFailure wrapped = new AppointmentRepository.OperationFailure("Read published slots",
                "doctorSlotInventory/doctor/dates/date/slots", new AppointmentRepository.Failure(AppointmentRepository.Error.NO_INVENTORY));
        assertEquals("NO_INVENTORY", AppointmentRepository.errorCode(wrapped));
        assertEquals(AppointmentRepository.Error.NO_INVENTORY, AppointmentRepository.errorFor(wrapped));
        assertEquals("Read published slots", wrapped.operation);
        assertEquals("doctorSlotInventory/doctor/dates/date/slots", wrapped.path);
    }
}
