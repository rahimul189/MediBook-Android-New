package com.example.medibook.rules;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** Step 89 atomic Confirmed booking, cancellation, blocking, reads and concurrency policy. */
public class AppointmentRulesTest extends RulesFixtures {
    private static final LocalDate MONDAY = nextDhakaDate(DayOfWeek.MONDAY);
    private static final LocalDate SUNDAY = nextDhakaDate(DayOfWeek.SUNDAY);
    private static final LocalDate MONTH_BOUNDARY = LocalDate.now(HOSPITAL_ZONE)
            .with(TemporalAdjusters.firstDayOfNextMonth());
    private static final LocalDate YEAR_BOUNDARY = LocalDate.of(
            LocalDate.now(HOSPITAL_ZONE).getYear() + 1, 1, 1);
    private static final int START = 540;
    private static final int END = 560;

    private static RulesClient otherPatient() {
        return authenticated("patient-b", map("email", "patient-b@example.invalid"));
    }

    private static JsonObject savedFields(String path) {
        return fields(admin().get(path));
    }

    @Before public void seedAppointmentFixtures() {
        for (int day = 2; day <= 7; day++) {
            seed("doctors/active/schedules/weekday-" + day, schedule(day, true));
        }
        seed("users/patient-b", merge(profile(), map("email", "patient-b@example.invalid",
                "fullName", "Other patient", "phone", "7654321")));
    }

    @Test public void patientBooksAvailableSlotAsAtomicConfirmedReservation() {
        seedAvailableSlot(MONDAY, START, END);
        String appointmentId = "booking-confirmed";

        allowed(bookingBatch(patient(), "patient-a", appointmentId, MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));

        JsonObject appointment = savedFields("appointments/" + appointmentId);
        assertEquals(17, appointment.size());
        assertEquals("Confirmed", text(appointment, "patientUid").length() > 0
                ? text(appointment, "status") : "");
        assertEquals("patient-a", text(appointment, "patientUid"));
        assertEquals("Patient fixture", text(appointment, "patientName"));
        assertEquals("1234567", text(appointment, "patientPhone"));
        assertEquals("active", text(appointment, "doctorId"));
        assertEquals("Rules fixture", text(appointment, "doctorName"));
        assertEquals("Fixture specialty", text(appointment, "doctorSpecialization"));
        assertEquals(50025L, integer(appointment, "consultationFee"));
        assertEquals("enabled", text(appointment, "scheduleId"));
        assertEquals(1L, integer(appointment, "dayOfWeek"));
        assertEquals(Instant.parse(dhakaMidnight(MONDAY).value), time(appointment, "appointmentDate"));
        assertEquals((long) START, integer(appointment, "startMinute"));
        assertEquals((long) END, integer(appointment, "endMinute"));
        assertEquals(Instant.parse(dhakaTimeAt(MONDAY, START).value), time(appointment, "startAt"));
        assertEquals(Instant.parse(dhakaTimeAt(MONDAY, END).value), time(appointment, "endAt"));
        assertEquals(time(appointment, "createdAt"), time(appointment, "updatedAt"));
        assertTrue(time(appointment, "createdAt").isAfter(Instant.parse(OLD_TIME.value)));

        JsonObject slot = savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END));
        assertEquals(12, slot.size());
        assertEquals("booked", text(slot, "status"));
        assertEquals(1L, integer(slot, "generation"));
        assertEquals(appointmentId, text(slot, "appointmentId"));
        assertTrue(time(slot, "updatedAt").isAfter(Instant.parse(OLD_TIME.value)));

        JsonObject lock = savedFields(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1));
        assertEquals(6, lock.size());
        assertEquals(appointmentId, text(lock, "appointmentId"));
        assertEquals("patient-a", text(lock, "patientUid"));
        assertEquals("enabled", text(lock, "scheduleId"));
        assertEquals(Instant.parse(dhakaTimeAt(MONDAY, START).value), time(lock, "startAt"));
        assertEquals(Instant.parse(dhakaTimeAt(MONDAY, END).value), time(lock, "endAt"));
        assertTrue(time(lock, "createdAt").isAfter(Instant.parse(OLD_TIME.value)));
    }

    @Test public void incompleteBookingBatchIsDeniedWithoutPartialWrites() {
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> slot = bookedInventorySlot(MONDAY, START, END, 1, "partial-a");
        Map<String, Object> lock = bookingLock("partial-a", "patient-a", "enabled",
                MONDAY, START, END, Special.SERVER_TIME);

        denied(patient().commit(
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END), slot, true),
                write(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1), lock, false)));
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
        missing(admin().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        missing(admin().get("appointments/partial-a"));

        denied(patient().commit(
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END), slot, true),
                write("appointments/partial-a",
                        newAppointment(MONDAY, START, END), false)));
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
        missing(admin().get("appointments/partial-a"));

        denied(patient().commit(
                write(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1), lock, false),
                write("appointments/partial-a",
                        newAppointment(MONDAY, START, END), false)));
        missing(admin().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        missing(admin().get("appointments/partial-a"));
    }

    @Test public void duplicateAndUnlistedIntervalBookingsAreDenied() {
        seedAvailableSlot(MONDAY, START, END);
        seedAvailableSlot(MONDAY, 560, 580);
        allowed(bookingBatch(patient(), "patient-a", "first-booking", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));

        denied(bookingBatch(patient(), "patient-a", "second-booking", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));
        denied(patient().set("appointments/uncoordinated",
                newAppointment(MONDAY, START, END)));

        // The same appointment ID cannot be reused for another inventory slot.
        denied(bookingBatch(patient(), "patient-a", "first-booking", MONDAY, 560, 580, 1,
                newAppointment(MONDAY, 560, 580)));
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580)), "status"));
        missing(admin().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, 560, 580, 1)));

        // A different / partially overlapping interval has no Admin-published inventory path.
        denied(bookingBatch(patient(), "patient-a", "partial-interval", MONDAY, 550, 570, 1,
                newAppointment(MONDAY, 550, 570)));
        missing(admin().get("appointments/partial-interval"));

        allowed(bookingBatch(patient(), "patient-a", "second-slot-booking", MONDAY, 560, 580, 1,
                newAppointment(MONDAY, 560, 580)));
        assertEquals("first-booking", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "appointmentId"));
    }

    @Test public void patientCannotForgeIdentityOwnershipOrRole() {
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> valid = newAppointment(MONDAY, START, END);
        List<Map<String, Object>> forged = Arrays.asList(
                map("patientUid", "patient-b"),
                map("patientUid", "someone-else"),
                map("patientName", "Other patient"),
                map("patientPhone", "7654321"),
                map("patientName", 123),
                map("patientPhone", 123));
        int suffix = 0;
        for (Map<String, Object> patch : forged) {
            denied(bookingBatch(patient(), "patient-a", "forged-identity-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, patch)));
        }

        RulesClient noProfile = authenticated("no-profile", map("email", "patient@example.invalid"));
        denied(bookingBatch(noProfile, "no-profile", "no-profile-booking", MONDAY, START, END, 1,
                merge(valid, map("patientUid", "no-profile"))));
        denied(bookingBatch(authenticated("patient-a", map("email", "other@example.invalid")),
                "patient-a", "wrong-token-email", MONDAY, START, END, 1, valid));
        denied(bookingBatch(authenticated("fake-admin", map("email", "patient@example.invalid")),
                "fake-admin", "profile-admin", MONDAY, START, END, 1,
                merge(valid, map("patientUid", "fake-admin"))));
        denied(bookingBatch(authenticated("patient-a",
                map("admin", false, "email", "patient@example.invalid")),
                "patient-a", "malformed-admin", MONDAY, START, END, 1, valid));
        denied(bookingBatch(admin(), "patient-a", "admin-booking", MONDAY, START, END, 1, valid));
        denied(bookingBatch(anonymous(), "patient-a", "anonymous-booking",
                MONDAY, START, END, 1, valid));

        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
    }

    @Test public void incompleteOrInvalidPatientProfileBlocksBooking() {
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> valid = newAppointment(MONDAY, START, END);
        int suffix = 0;
        for (String field : profile().keySet()) {
            Map<String, Object> data = new LinkedHashMap<>(profile());
            data.remove(field);
            seed("users/patient-a", data);
            denied(bookingBatch(patient(), "patient-a", "profile-" + suffix++,
                    MONDAY, START, END, 1, valid));
        }
        for (Map<String, Object> patch : Arrays.asList(
                map("fullName", ""), map("fullName", "  "),
                map("fullName", "N".repeat(101)), map("fullName", 123),
                map("email", "other@example.invalid"), map("email", 123),
                map("phone", "short"), map("phone", 123),
                map("role", "Admin"), map("createdAt", "not-a-timestamp"),
                map("admin", true))) {
            seed("users/patient-a", merge(profile(), patch));
            denied(bookingBatch(patient(), "patient-a", "profile-" + suffix++,
                    MONDAY, START, END, 1, valid));
        }
        seed("users/patient-a", profile());
        denied(bookingBatch(authenticated("patient-a", map()),
                "patient-a", "missing-token-email", MONDAY, START, END, 1, valid));
        denied(bookingBatch(authenticated("patient-a", map("email", "other@example.invalid")),
                "patient-a", "wrong-token-email", MONDAY, START, END, 1, valid));
    }

    @Test public void inactiveOrStaleDoctorAndScheduleReferencesAreDenied() {
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> valid = newAppointment(MONDAY, START, END);
        int suffix = 0;
        for (Map<String, Object> patch : Arrays.asList(
                map("doctorName", "Other doctor"),
                map("doctorSpecialization", "Other specialty"),
                map("consultationFee", 50024),
                map("doctorId", "inactive"),
                map("doctorId", "missing"),
                map("scheduleId", "disabled"),
                map("scheduleId", "missing"),
                map("scheduleId", "orphan"),
                map("dayOfWeek", 2))) {
            denied(bookingBatch(patient(), "patient-a", "stale-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, patch)));
        }

        allowed(admin().update("doctors/active",
                map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(bookingBatch(patient(), "patient-a", "inactive-doctor", MONDAY, START, END, 1, valid));
        allowed(admin().update("doctors/active",
                map("active", true, "updatedAt", Special.SERVER_TIME)));
        allowed(bookingBatch(patient(), "patient-a", "reenabled-doctor", MONDAY, START, END, 1, valid));

        seedAvailableSlot(MONDAY, 560, 580);
        allowed(admin().update(ENABLED,
                map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(bookingBatch(patient(), "patient-a", "inactive-schedule",
                MONDAY, 560, 580, 1, newAppointment(MONDAY, 560, 580)));
        allowed(admin().update(ENABLED,
                map("active", true, "updatedAt", Special.SERVER_TIME)));
        allowed(bookingBatch(patient(), "patient-a", "reenabled-schedule",
                MONDAY, 560, 580, 1, newAppointment(MONDAY, 560, 580)));

        seedAvailableSlot(MONDAY, 580, 600);
        allowed(admin().update("doctors/active",
                map("name", "Renamed fixture", "updatedAt", Special.SERVER_TIME)));
        denied(bookingBatch(patient(), "patient-a", "stale-doctor-snapshot",
                MONDAY, 580, 600, 1, newAppointment(MONDAY, 580, 600)));
        allowed(admin().update("doctors/active",
                map("name", "Rules fixture", "updatedAt", Special.SERVER_TIME)));
        allowed(bookingBatch(patient(), "patient-a", "restored-doctor-snapshot",
                MONDAY, 580, 600, 1, newAppointment(MONDAY, 580, 600)));

        seedAvailableSlot(MONDAY, 600, 620);
        allowed(admin().update(ENABLED,
                map("startTime", 610, "updatedAt", Special.SERVER_TIME)));
        denied(bookingBatch(patient(), "patient-a", "stale-schedule-window",
                MONDAY, 600, 620, 1, newAppointment(MONDAY, 600, 620)));
        allowed(admin().update(ENABLED,
                map("startTime", 540, "updatedAt", Special.SERVER_TIME)));
        allowed(bookingBatch(patient(), "patient-a", "restored-schedule-window",
                MONDAY, 600, 620, 1, newAppointment(MONDAY, 600, 620)));
    }

    @Test public void exactSeventeenFieldSchemaAndConfirmedStatusRequired() {
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> valid = newAppointment(MONDAY, START, END);
        assertEquals(17, valid.size());
        int suffix = 0;
        for (String field : valid.keySet()) {
            Map<String, Object> missingField = new LinkedHashMap<>(valid);
            missingField.remove(field);
            denied(bookingBatch(patient(), "patient-a", "missing-" + suffix++,
                    MONDAY, START, END, 1, missingField));
        }
        for (String field : new String[]{"id", "appointmentId", "role", "ownerUid", "timezone", "slot"}) {
            denied(bookingBatch(patient(), "patient-a", "extra-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, map(field, "unexpected"))));
        }
        for (String status : new String[]{"Pending", "Completed", "Cancelled",
                "pending", "", "PENDING", " Unknown "}) {
            denied(bookingBatch(patient(), "patient-a", "status-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, map("status", status))));
        }
        for (Object status : new Object[]{1, true, map("value", "Confirmed")}) {
            denied(bookingBatch(patient(), "patient-a", "status-type-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, map("status", status))));
        }

        List<Map<String, Object>> invalid = Arrays.asList(
                map("patientUid", 123), map("patientUid", ""), map("patientUid", "bad.id"),
                map("patientUid", "x".repeat(129)),
                map("patientName", ""), map("patientName", "  "), map("patientName", "N".repeat(101)),
                map("patientPhone", "short"), map("patientPhone", "+1234567890123456"),
                map("doctorId", 123), map("doctorId", ""), map("doctorId", "bad.id"),
                map("doctorName", ""), map("doctorName", "  "), map("doctorName", "N".repeat(101)),
                map("doctorSpecialization", ""), map("doctorSpecialization", "S".repeat(101)),
                map("consultationFee", -1), map("consultationFee", 100000000),
                map("consultationFee", 50025.5), map("consultationFee", 50025.0),
                map("scheduleId", 123), map("scheduleId", ""), map("scheduleId", "bad.id"),
                map("dayOfWeek", 0), map("dayOfWeek", 8), map("dayOfWeek", -1),
                map("dayOfWeek", 1.5), map("dayOfWeek", 1.0), map("dayOfWeek", "1"),
                map("startMinute", -1), map("startMinute", 1440), map("startMinute", 540.5),
                map("startMinute", 540.0), map("startMinute", "540"),
                map("endMinute", -1), map("endMinute", 1440), map("endMinute", 540),
                map("endMinute", 560.5), map("endMinute", 560.0), map("endMinute", "560"),
                map("appointmentDate", "not-a-timestamp"), map("appointmentDate", 0),
                map("startAt", "not-a-timestamp"), map("startAt", 0),
                map("endAt", "not-a-timestamp"), map("endAt", 0),
                map("createdAt", "not-a-timestamp"), map("createdAt", 0),
                map("updatedAt", "not-a-timestamp"), map("updatedAt", 0));
        for (Map<String, Object> patch : invalid) {
            denied(bookingBatch(patient(), "patient-a", "value-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, patch)));
        }
        for (String field : valid.keySet()) {
            denied(bookingBatch(patient(), "patient-a", "null-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, map(field, null))));
            denied(bookingBatch(patient(), "patient-a", "map-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, map(field, map("invalid", true)))));
        }
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
    }

    @Test public void appointmentIdentityPatternIsBounded() {
        seedAvailableSlot(MONDAY, START, END);
        seedAvailableSlot(MONDAY, 560, 580);
        int suffix = 0;
        for (String id : new String[]{"invalid.id", "space id", "x".repeat(129), "বাংলা"}) {
            denied(bookingBatch(patient(), "patient-a", id, MONDAY, START, END, 1,
                    newAppointment(MONDAY, START, END)));
        }
        allowed(bookingBatch(patient(), "patient-a", "x".repeat(128), MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));
        allowed(bookingBatch(patient(), "patient-a", "Appointment_1-AbC", MONDAY, 560, 580, 1,
                newAppointment(MONDAY, 560, 580)));
    }

    @Test public void dateArithmeticAndDhakaWeekdayAreEnforced() {
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> valid = newAppointment(MONDAY, START, END);
        Instant midnight = Instant.parse(dhakaMidnight(MONDAY).value);
        Instant start = Instant.parse(dhakaTimeAt(MONDAY, START).value);
        Instant end = Instant.parse(dhakaTimeAt(MONDAY, END).value);
        List<Map<String, Object>> invalid = Arrays.asList(
                map("appointmentDate", new Time(midnight.plusSeconds(3600).toString())),
                map("appointmentDate", new Time(midnight.plusSeconds(60).toString())),
                map("appointmentDate", new Time(midnight.plusNanos(1000).toString())),
                map("appointmentDate", new Time(MONDAY + "T00:00:00Z")),
                map("startAt", new Time(start.plusMillis(1).toString())),
                map("startAt", new Time(start.plusSeconds(60).toString())),
                map("startAt", new Time(midnight.toString())),
                map("endAt", new Time(end.plusMillis(1).toString())),
                map("endAt", new Time(end.plusSeconds(60).toString())),
                map("endAt", new Time(start.toString())),
                map("createdAt", OLD_TIME), map("updatedAt", OLD_TIME),
                map("createdAt", ZERO_TIME), map("updatedAt", ZERO_TIME));
        int suffix = 0;
        for (Map<String, Object> patch : invalid) {
            denied(bookingBatch(patient(), "patient-a", "timestamp-" + suffix++,
                    MONDAY, START, END, 1, merge(valid, patch)));
        }

        seedAvailableSlot(SUNDAY, START, END);
        allowed(bookingBatch(patient(), "patient-a", "sunday-booking", SUNDAY, START, END, 1,
                newAppointment(SUNDAY, START, END)));
        assertEquals(7L, integer(savedFields("appointments/sunday-booking"), "dayOfWeek"));

        seedAvailableSlot(MONTH_BOUNDARY, START, END);
        allowed(bookingBatch(patient(), "patient-a", "month-boundary", MONTH_BOUNDARY,
                START, END, 1, newAppointment(MONTH_BOUNDARY, START, END)));
        seedAvailableSlot(YEAR_BOUNDARY, START, END);
        allowed(bookingBatch(patient(), "patient-a", "year-boundary", YEAR_BOUNDARY,
                START, END, 1, newAppointment(YEAR_BOUNDARY, START, END)));

        seedAvailableSlot(MONDAY.minusWeeks(2), START, END);
        denied(bookingBatch(patient(), "patient-a", "past-booking", MONDAY.minusWeeks(2),
                START, END, 1, newAppointment(MONDAY.minusWeeks(2), START, END)));
    }

    @Test public void forgedSlotAndLockContentsAreDenied() {
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> validAppointment = newAppointment(MONDAY, START, END);
        Map<String, Object> goodSlot = bookedInventorySlot(MONDAY, START, END, 1, "forged-target");
        Map<String, Object> goodLock = bookingLock("forged-target", "patient-a", "enabled",
                MONDAY, START, END, Special.SERVER_TIME);

        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));

        List<Map<String, Object>> badSlots = Arrays.asList(
                merge(goodSlot, map("generation", 2)),
                merge(goodSlot, map("appointmentId", "other-appointment")),
                merge(goodSlot, map("status", "blocked")),
                merge(goodSlot, map("scheduleId", "disabled")),
                merge(goodSlot, map("generation", 1.0)),
                merge(goodSlot, map("dayOfWeek", 1.0)),
                merge(goodSlot, map("startMinute", 540.0)),
                merge(goodSlot, map("endMinute", 560.0)),
                merge(goodSlot, map("createdAt", ZERO_TIME)),
                merge(goodSlot, map("unexpected", "field")));
        for (Map<String, Object> slot : badSlots) {
            denied(patient().commit(
                    write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END), slot, true),
                    write(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1),
                            goodLock, false),
                    write("appointments/forged-target", validAppointment, false)));
        }

        List<Map<String, Object>> badLocks = Arrays.asList(
                merge(goodLock, map("appointmentId", "other-appointment")),
                merge(goodLock, map("patientUid", "patient-b")),
                merge(goodLock, map("scheduleId", "disabled")),
                merge(goodLock, map("startAt", dhakaTimeAt(MONDAY, 560))),
                merge(goodLock, map("createdAt", OLD_TIME)),
                merge(goodLock, map("unexpected", "field")));
        for (Map<String, Object> lock : badLocks) {
            denied(patient().commit(
                    write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                            goodSlot, true),
                    write(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1), lock, false),
                    write("appointments/forged-target", validAppointment, false)));
        }
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
        missing(admin().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        missing(admin().get("appointments/forged-target"));
        // Positive control: this exact slot/lock/appointment identity succeeds without a forgery.
        allowed(bookingBatch(patient(), "patient-a", "forged-target", MONDAY, START, END, 1,
                validAppointment));
    }

    @Test public void patientCancelsOwnFutureBookingAndReleasesSlot() {
        seedBooking("cancel-own", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));

        allowed(cancellationBatch(patient(), "cancel-own", MONDAY, START, END, 1, "available"));

        JsonObject appointment = savedFields("appointments/cancel-own");
        assertEquals("Cancelled", text(appointment, "status"));
        assertEquals(Instant.parse(OLD_TIME.value), time(appointment, "createdAt"));
        assertTrue(time(appointment, "updatedAt").isAfter(Instant.parse(OLD_TIME.value)));

        JsonObject slot = savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END));
        assertEquals("available", text(slot, "status"));
        assertEquals(1L, integer(slot, "generation"));
        assertTrue(nullField(slot, "appointmentId"));
        JsonObject lock = savedFields(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1));
        assertEquals("cancel-own", text(lock, "appointmentId"));

        denied(cancellationBatch(patient(), "cancel-own", MONDAY, START, END, 1, "available"));
    }

    @Test public void cancellationRequiresBothWritesExactGenerationAndRelease() {
        seedBooking("cancel-shape", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));

        denied(patient().update("appointments/cancel-shape",
                map("status", "Cancelled", "updatedAt", Special.SERVER_TIME)));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));

        denied(patient().update(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                releasedInventorySlot(MONDAY, START, END, 1, "available")));
        assertEquals("Confirmed", text(savedFields("appointments/cancel-shape"), "status"));

        denied(cancellationBatch(patient(), "cancel-shape", MONDAY, START, END, 0, "available"));
        denied(cancellationBatch(patient(), "cancel-shape", MONDAY, START, END, 1, "booked"));
        denied(cancellationBatch(patient(), "cancel-shape", MONDAY, START, END, 1, "blocked"));
        assertEquals("Confirmed", text(savedFields("appointments/cancel-shape"), "status"));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));

        allowed(cancellationBatch(patient(), "cancel-shape", MONDAY, START, END, 1, "available"));
        assertEquals("Cancelled", text(savedFields("appointments/cancel-shape"), "status"));
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
    }

    @Test public void cancellationCannotChangeImmutableAppointmentOrSlotFields() {
        seedBooking("cancel-immutable", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));

        // diff() considers equal integer/double values unchanged; types must still be immutable.
        for (Map<String, Object> numericRetype : Arrays.asList(
                map("consultationFee", 50025.0), map("dayOfWeek", 1.0),
                map("startMinute", 540.0), map("endMinute", 560.0))) {
            denied(patient().commit(
                    write("appointments/cancel-immutable", merge(numericRetype,
                            map("status", "Cancelled", "updatedAt", Special.SERVER_TIME)), true),
                    write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                            releasedInventorySlot(MONDAY, START, END, 1, "available"), true)));
        }
        for (Map<String, Object> numericRetype : Arrays.asList(
                map("generation", 1.0), map("dayOfWeek", 1.0),
                map("startMinute", 540.0), map("endMinute", 560.0))) {
            denied(patient().commit(
                    write("appointments/cancel-immutable",
                            map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                    write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                            merge(releasedInventorySlot(MONDAY, START, END, 1, "available"),
                                    numericRetype), true)));
        }

        denied(patient().commit(
                write("appointments/cancel-immutable",
                        map("status", "Cancelled", "updatedAt", Special.SERVER_TIME,
                                "doctorName", "Other doctor"), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        releasedInventorySlot(MONDAY, START, END, 1, "available"), true)));
        denied(patient().commit(
                write("appointments/cancel-immutable",
                        map("status", "Cancelled", "updatedAt", OLD_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        releasedInventorySlot(MONDAY, START, END, 1, "available"), true)));
        denied(patient().commit(
                write("appointments/cancel-immutable",
                        map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        merge(releasedInventorySlot(MONDAY, START, END, 1, "available"),
                                map("scheduleId", "disabled")), true)));
        denied(patient().commit(
                write("appointments/cancel-immutable",
                        map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        merge(releasedInventorySlot(MONDAY, START, END, 1, "available"),
                                map("generation", 2)), true)));

        assertEquals("Confirmed", text(savedFields("appointments/cancel-immutable"), "status"));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
    }

    @Test public void crossPatientAndPastCancellationsAreDenied() {
        seedBooking("cancel-other", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        denied(cancellationBatch(otherPatient(), "cancel-other", MONDAY, START, END, 1, "available"));
        assertEquals("Confirmed", text(savedFields("appointments/cancel-other"), "status"));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));

        LocalDate past = MONDAY.minusWeeks(2);
        seedBooking("cancel-past", past, START, END, "patient-a",
                appointmentRecord(past, START, END, "Confirmed"));
        denied(cancellationBatch(patient(), "cancel-past", past, START, END, 1, "available"));
        assertEquals("Confirmed", text(savedFields("appointments/cancel-past"), "status"));
    }

    @Test public void releaseOfAnotherAppointmentsSlotIsDenied() {
        seedBooking("release-a", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        seedBooking("release-b", MONDAY, 560, 580, "patient-a",
                appointmentRecord(MONDAY, 560, 580, "Confirmed"));

        denied(patient().commit(
                write("appointments/release-a",
                        map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580),
                        releasedInventorySlot(MONDAY, 560, 580, 1, "available"), true)));
        assertEquals("Confirmed", text(savedFields("appointments/release-a"), "status"));
        assertEquals("Confirmed", text(savedFields("appointments/release-b"), "status"));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580)), "status"));
    }

    @Test public void adminCancelsFutureAppointmentButNotPastOne() {
        seedBooking("admin-cancel", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        allowed(cancellationBatch(admin(), "admin-cancel", MONDAY, START, END, 1, "blocked"));
        assertEquals("Cancelled", text(savedFields("appointments/admin-cancel"), "status"));
        assertEquals("blocked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));

        LocalDate past = MONDAY.minusWeeks(2);
        seedBooking("admin-past", past, START, END, "patient-a",
                appointmentRecord(past, START, END, "Confirmed"));
        denied(cancellationBatch(admin(), "admin-past", past, START, END, 1, "available"));
        assertEquals("Confirmed", text(savedFields("appointments/admin-past"), "status"));
    }

    @Test public void pendingCompletedAndTerminalWritesAreDenied() {
        seed("appointments/pending", appointmentRecord(MONDAY, START, END, "Pending"));
        seed("appointments/completed", appointmentRecord(MONDAY, START, END, "Completed"));
        seed("appointments/cancelled", appointmentRecord(MONDAY, START, END, "Cancelled"));
        for (String path : new String[]{"appointments/pending", "appointments/completed",
                "appointments/cancelled"}) {
            for (String status : new String[]{"Pending", "Confirmed", "Completed", "Cancelled"}) {
                denied(admin().update(path,
                        map("status", status, "updatedAt", Special.SERVER_TIME)));
                denied(patient().update(path,
                        map("status", status, "updatedAt", Special.SERVER_TIME)));
            }
        }

        seed("appointments/confirmed-status", appointmentRecord(MONDAY, START, END, "Confirmed"));
        for (String status : new String[]{"Pending", "Completed", "Confirmed", "Unknown"}) {
            denied(admin().update("appointments/confirmed-status",
                    map("status", status, "updatedAt", Special.SERVER_TIME)));
        }
        denied(patient().update("appointments/confirmed-status",
                map("status", "Completed", "updatedAt", Special.SERVER_TIME)));
    }

    @Test public void appointmentSlotAndLockDeletionIsDeniedForEveryone() {
        seedBooking("delete-target", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        String slotPath = inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END);
        String lockPath = bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1);
        for (RulesClient client : new RulesClient[]{patient(), otherPatient(), admin(), anonymous()}) {
            denied(client.delete("appointments/delete-target"));
            denied(client.delete(slotPath));
            denied(client.delete(lockPath));
        }
        allowed(admin().get("appointments/delete-target"));
        assertEquals("booked", text(savedFields(slotPath), "status"));
        assertEquals("delete-target", text(savedFields(lockPath), "appointmentId"));
    }

    @Test public void appointmentReadsAndQueriesRespectOwnershipAndAdminRole() {
        seed("appointments/read-a", appointmentRecord(MONDAY, START, END, "Confirmed"));
        seed("appointments/read-b", merge(
                appointmentRecord(MONDAY, START, END, "Confirmed"),
                map("patientUid", "patient-b", "patientName", "Other patient",
                        "patientPhone", "7654321")));

        allowed(patient().get("appointments/read-a"));
        denied(patient().get("appointments/read-b"));
        allowed(otherPatient().get("appointments/read-b"));
        denied(otherPatient().get("appointments/read-a"));
        allowed(admin().get("appointments/read-a"));
        allowed(admin().get("appointments/read-b"));
        denied(anonymous().get("appointments/read-a"));

        JsonObject adminView = savedFields("appointments/read-a");
        assertEquals("Patient fixture", text(adminView, "patientName"));
        assertEquals("1234567", text(adminView, "patientPhone"));
        // Intentional Batch 4 addition: one Patient profile get, never a user directory/list.
        allowed(admin().get("users/patient-a"));
        denied(admin().query("users", null));
        denied(patient().get("users/patient-b"));

        assertEquals(1, documents(patient().queryAppointments("patient-a")).size());
        assertEquals(1, documents(otherPatient().queryAppointments("patient-b")).size());
        denied(patient().queryAppointments(null));
        denied(patient().queryAppointments("patient-b"));
        denied(otherPatient().queryAppointments("patient-a"));
        denied(anonymous().queryAppointments("patient-a"));
        assertEquals(2, documents(admin().queryAppointments(null)).size());
        assertEquals(1, documents(admin().queryAppointments("patient-b")).size());
        assertEquals(2, documents(admin().recentChangedAppointments()).size());
        denied(patient().recentChangedAppointments());
        denied(anonymous().recentChangedAppointments());
    }

    @Test public void inventoryLocksAndBlocksHaveRoleScopedReads() {
        seedAvailableSlot(MONDAY, START, END);
        seed("doctorSlotInventory/inactive/dates/" + dhakaDateKey(MONDAY)
                        + "/slots/" + inventorySlotId(MONDAY, START, END),
                inventorySlot("enabled", MONDAY, START, END, "available", 0, null,
                        OLD_TIME, OLD_TIME));
        seedBooking("read-lock", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        seed(dateBlockPath(BOOKING_DOCTOR, MONDAY),
                dateBlockRecord(MONDAY, "Maintenance", OLD_TIME, OLD_TIME));

        allowed(patient().get(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)));
        denied(patient().get("doctorSlotInventory/inactive/dates/"
                + dhakaDateKey(MONDAY) + "/slots/" + inventorySlotId(MONDAY, START, END)));
        allowed(admin().get("doctorSlotInventory/inactive/dates/"
                + dhakaDateKey(MONDAY) + "/slots/" + inventorySlotId(MONDAY, START, END)));
        denied(patient().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        allowed(admin().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        denied(patient().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
        allowed(admin().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
        denied(anonymous().get(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)));
        denied(anonymous().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
    }

    @Test public void dateBlockPreventsBookingAndUsesExplicitReopen() {
        seedAvailableSlot(MONDAY, START, END);
        allowed(admin().set(dateBlockPath(BOOKING_DOCTOR, MONDAY),
                dateBlockRecord(MONDAY, "Maintenance", Special.SERVER_TIME, Special.SERVER_TIME)));
        denied(bookingBatch(patient(), "patient-a", "blocked-booking",
                MONDAY, START, END, 1, newAppointment(MONDAY, START, END)));
        denied(patient().commit(
                deleteWrite(dateBlockPath(BOOKING_DOCTOR, MONDAY)),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        bookedInventorySlot(MONDAY, START, END, 1, "delete-block-booking"), true),
                write(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1),
                        bookingLock("delete-block-booking", "patient-a", "enabled",
                                MONDAY, START, END, Special.SERVER_TIME), false),
                write("appointments/delete-block-booking", newAppointment(MONDAY, START, END), false)));
        allowed(admin().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
        missing(admin().get("appointments/delete-block-booking"));
        missing(admin().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));

        allowed(admin().update(dateBlockPath(BOOKING_DOCTOR, MONDAY),
                map("reason", "Updated maintenance", "updatedAt", Special.SERVER_TIME)));
        denied(bookingBatch(patient(), "patient-a", "blocked-after-update",
                MONDAY, START, END, 1, newAppointment(MONDAY, START, END)));

        allowed(admin().delete(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
        allowed(bookingBatch(patient(), "patient-a", "unblocked-booking",
                MONDAY, START, END, 1, newAppointment(MONDAY, START, END)));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
    }

    @Test public void blockedDateCancellationMustReleaseToBlocked() {
        seedBooking("blocked-cancel", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        allowed(admin().set(dateBlockPath(BOOKING_DOCTOR, MONDAY),
                dateBlockRecord(MONDAY, "Doctor absent", Special.SERVER_TIME, Special.SERVER_TIME)));

        denied(cancellationBatch(patient(), "blocked-cancel", MONDAY, START, END, 1, "available"));
        denied(cancellationBatch(admin(), "blocked-cancel", MONDAY, START, END, 1, "available"));
        assertEquals("Confirmed", text(savedFields("appointments/blocked-cancel"), "status"));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));

        allowed(cancellationBatch(patient(), "blocked-cancel", MONDAY, START, END, 1, "blocked"));
        assertEquals("Cancelled", text(savedFields("appointments/blocked-cancel"), "status"));
        JsonObject slot = savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END));
        assertEquals("blocked", text(slot, "status"));
        assertEquals(1L, integer(slot, "generation"));
        assertTrue(nullField(slot, "appointmentId"));
        allowed(admin().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));

        denied(bookingBatch(patient(), "patient-a", "blocked-rebook",
                MONDAY, START, END, 2, newAppointment(MONDAY, START, END)));
        allowed(admin().delete(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
        denied(bookingBatch(patient(), "patient-a", "still-blocked-slot",
                MONDAY, START, END, 2, newAppointment(MONDAY, START, END)));
        allowed(admin().update(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                map("status", "available", "updatedAt", Special.SERVER_TIME)));
        allowed(bookingBatch(patient(), "patient-a", "explicit-reopen-booking",
                MONDAY, START, END, 2, newAppointment(MONDAY, START, END)));
        assertEquals("booked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
    }

    @Test public void bulkCancellationDoesNotImplicitlyUnblockAnInactiveDoctor() {
        seedBooking("bulk-a", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        seedBooking("bulk-b", MONDAY, 560, 580, "patient-a",
                appointmentRecord(MONDAY, 560, 580, "Confirmed"));
        allowed(admin().set(dateBlockPath(BOOKING_DOCTOR, MONDAY),
                dateBlockRecord(MONDAY, "Bulk cancellation", Special.SERVER_TIME, Special.SERVER_TIME)));
        allowed(cancellationBatch(admin(), "bulk-a", MONDAY, START, END, 1, "blocked"));
        allowed(cancellationBatch(admin(), "bulk-b", MONDAY, 560, 580, 1, "blocked"));

        assertEquals("Cancelled", text(savedFields("appointments/bulk-a"), "status"));
        assertEquals("Cancelled", text(savedFields("appointments/bulk-b"), "status"));
        assertEquals("blocked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
        assertEquals("blocked", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580)), "status"));
        allowed(admin().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));

        allowed(admin().update("doctors/active",
                map("active", false, "updatedAt", Special.SERVER_TIME)));
        allowed(admin().delete(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
        allowed(admin().update(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                map("status", "available", "updatedAt", Special.SERVER_TIME)));
        denied(bookingBatch(patient(), "patient-a", "inactive-after-bulk",
                MONDAY, START, END, 2, newAppointment(MONDAY, START, END)));
        allowed(admin().update("doctors/active",
                map("active", true, "updatedAt", Special.SERVER_TIME)));
        allowed(bookingBatch(patient(), "patient-a", "reopened-after-bulk",
                MONDAY, START, END, 2, newAppointment(MONDAY, START, END)));
    }

    @Test public void rebookingAfterCancellationRequiresNextGenerationLock() {
        seedBooking("generation-one", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        allowed(cancellationBatch(patient(), "generation-one", MONDAY, START, END, 1, "available"));

        denied(bookingBatch(patient(), "patient-a", "generation-one-reuse",
                MONDAY, START, END, 1, newAppointment(MONDAY, START, END)));
        denied(patient().commit(
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        bookedInventorySlot(MONDAY, START, END, 2, "old-lock-reuse"), true),
                write(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1),
                        bookingLock("old-lock-reuse", "patient-a", "enabled", MONDAY,
                                START, END, Special.SERVER_TIME), false),
                write("appointments/old-lock-reuse",
                        newAppointment(MONDAY, START, END), false)));

        allowed(bookingBatch(patient(), "patient-a", "generation-two",
                MONDAY, START, END, 2, newAppointment(MONDAY, START, END)));
        JsonObject slot = savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END));
        assertEquals(2L, integer(slot, "generation"));
        assertEquals("generation-two", text(slot, "appointmentId"));
        assertEquals("generation-one", text(savedFields(
                bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)), "appointmentId"));
        assertEquals("generation-two", text(savedFields(
                bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 2)), "appointmentId"));
    }

    @Test public void retryAfterCommittedBookingDoesNotDuplicateState() {
        seedAvailableSlot(MONDAY, START, END);
        allowed(bookingBatch(patient(), "patient-a", "retry-once", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));
        denied(bookingBatch(patient(), "patient-a", "retry-once", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));
        denied(bookingBatch(patient(), "patient-a", "retry-new-id", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));

        assertEquals(1, documents(admin().queryAppointments(null)).size());
        JsonObject slot = savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END));
        assertEquals(1L, integer(slot, "generation"));
        assertEquals("retry-once", text(slot, "appointmentId"));
        assertEquals("retry-once", text(savedFields(
                bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)), "appointmentId"));
    }

    @Test public void concurrentSameSlotBookingsAllowExactlyOneWinner() throws Exception {
        seedAvailableSlot(MONDAY, START, END);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Reply> first = pool.submit(() -> {
                ready.countDown();
                start.await();
                return bookingBatch(patient(), "patient-a", "race-a", MONDAY, START, END, 1,
                        newAppointment(MONDAY, START, END));
            });
            Future<Reply> second = pool.submit(() -> {
                ready.countDown();
                start.await();
                return bookingBatch(patient(), "patient-a", "race-b", MONDAY, START, END, 1,
                        newAppointment(MONDAY, START, END));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Reply firstReply = first.get(30, TimeUnit.SECONDS);
            Reply secondReply = second.get(30, TimeUnit.SECONDS);
            int successes = (firstReply.code == 200 ? 1 : 0) + (secondReply.code == 200 ? 1 : 0);
            assertEquals("exactly one concurrent booking must commit", 1, successes);
            denied(firstReply.code == 200 ? secondReply : firstReply);

            JsonObject slot = savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END));
            assertEquals("booked", text(slot, "status"));
            assertEquals(1L, integer(slot, "generation"));
            String winner = text(slot, "appointmentId");
            assertTrue(winner.equals("race-a") || winner.equals("race-b"));
            assertEquals(winner, text(savedFields(
                    bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)), "appointmentId"));
            assertEquals(1, documents(admin().queryAppointments(null)).size());
            allowed(admin().get("appointments/" + winner));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void patientCancelsUsingMinimalReleasePatchAfterDoctorDeactivation() {
        seedBooking("inactive-cancel", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        allowed(admin().update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        allowed(admin().update(ENABLED, map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(patient().get(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)));
        denied(patient().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        allowed(patient().get("appointments/inactive-cancel"));
        allowed(patient().commit(
                write("appointments/inactive-cancel", map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        map("status", "available", "appointmentId", null, "updatedAt", Special.SERVER_TIME), true)));
        assertEquals("Cancelled", text(fields(patient().get("appointments/inactive-cancel")), "status"));
        assertEquals(1L, integer(savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "generation"));
        assertEquals("inactive-cancel", text(savedFields(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)), "appointmentId"));
    }

    @Test public void blockedDateMinimalPatchCanRetryBlockedWithoutReadingPrivateBlock() {
        seedBooking("minimal-block-cancel", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        allowed(admin().set(dateBlockPath(BOOKING_DOCTOR, MONDAY),
                dateBlockRecord(MONDAY, "Existing block", Special.SERVER_TIME, Special.SERVER_TIME)));
        denied(patient().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
        denied(patient().commit(
                write("appointments/minimal-block-cancel", map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        map("status", "available", "appointmentId", null, "updatedAt", Special.SERVER_TIME), true)));
        assertEquals("Confirmed", text(savedFields("appointments/minimal-block-cancel"), "status"));
        allowed(patient().commit(
                write("appointments/minimal-block-cancel", map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END),
                        map("status", "blocked", "appointmentId", null, "updatedAt", Special.SERVER_TIME), true)));
        assertEquals("blocked", text(savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "status"));
        assertEquals(1L, integer(savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END)), "generation"));
        allowed(admin().get(dateBlockPath(BOOKING_DOCTOR, MONDAY)));
    }

    @Test public void patientReconcilesByStableAppointmentIdNotPrivateLockOrMissingDocument() {
        denied(patient().get("appointments/stable-client-id"));
        seedAvailableSlot(MONDAY, START, END);
        allowed(bookingBatch(patient(), "patient-a", "stable-client-id", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));
        assertEquals("Confirmed", text(fields(patient().get("appointments/stable-client-id")), "status"));
        denied(otherPatient().get("appointments/stable-client-id"));
        denied(patient().get(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)));
        allowed(cancellationBatch(patient(), "stable-client-id", MONDAY, START, END, 1, "available"));
        assertEquals("Cancelled", text(fields(patient().get("appointments/stable-client-id")), "status"));
    }

    @Test public void clientReadWriteTransactionUsesRepositoryReadAndWriteShape() {
        seedAvailableSlot(MONDAY, START, END);
        RulesClient client = patient();
        String slot = inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END);
        String transaction = allowed(client.beginTransaction()).getAsJsonObject().get("transaction").getAsString();
        allowed(client.transactionRead(transaction, "users/patient-a", "doctors/active", ENABLED, slot));
        allowed(client.transactionCommit(transaction,
                write(slot, map("status", "booked", "generation", 1, "appointmentId", "transaction-booking", "updatedAt", Special.SERVER_TIME), true),
                write(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1),
                        bookingLock("transaction-booking", "patient-a", "enabled", MONDAY, START, END, Special.SERVER_TIME), false),
                write("appointments/transaction-booking", newAppointment(MONDAY, START, END), false)));
        allowed(admin().update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        String cancelTransaction = allowed(client.beginTransaction()).getAsJsonObject().get("transaction").getAsString();
        allowed(client.transactionRead(cancelTransaction, "appointments/transaction-booking"));
        allowed(client.transactionCommit(cancelTransaction,
                write("appointments/transaction-booking", map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(slot, map("status", "available", "appointmentId", null, "updatedAt", Special.SERVER_TIME), true)));
        assertEquals("Cancelled", text(fields(client.get("appointments/transaction-booking")), "status"));
        assertEquals(1L, integer(savedFields(slot), "generation"));
    }

    @Test public void concurrentDuplicateCancellationsAreSerialized() throws Exception {
        seedBooking("cancel-race", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Reply> first = pool.submit(() -> {
                ready.countDown();
                start.await();
                return cancellationBatch(patient(), "cancel-race", MONDAY, START, END, 1, "available");
            });
            Future<Reply> second = pool.submit(() -> {
                ready.countDown();
                start.await();
                return cancellationBatch(patient(), "cancel-race", MONDAY, START, END, 1, "available");
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Reply firstReply = first.get(30, TimeUnit.SECONDS);
            Reply secondReply = second.get(30, TimeUnit.SECONDS);
            int successes = (firstReply.code == 200 ? 1 : 0) + (secondReply.code == 200 ? 1 : 0);
            assertEquals("exactly one concurrent cancellation must commit", 1, successes);
            denied(firstReply.code == 200 ? secondReply : firstReply);

            assertEquals("Cancelled", text(savedFields("appointments/cancel-race"), "status"));
            JsonObject slot = savedFields(inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END));
            assertEquals("available", text(slot, "status"));
            assertEquals(1L, integer(slot, "generation"));
            assertTrue(nullField(slot, "appointmentId"));
            assertEquals("cancel-race", text(savedFields(
                    bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1)), "appointmentId"));
        } finally {
            pool.shutdownNow();
        }
    }
}
