package com.example.medibook.rules;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** Step 89 Admin inventory publication, immutable locks, date blocks and the overlap limitation. */
public class InventoryRulesTest extends RulesFixtures {
    private static final LocalDate MONDAY = nextDhakaDate(DayOfWeek.MONDAY);
    private static final LocalDate OTHER_MONDAY = MONDAY.plusWeeks(1);
    private static final int START = 540;
    private static final int END = 560;

    private static JsonObject savedFields(String path) {
        return fields(admin().get(path));
    }

    private static String mismatchedDatePath(LocalDate date, int startMinute, int endMinute) {
        long shifted = Long.parseLong(dhakaDateKey(date)) + 1;
        return "doctorSlotInventory/" + BOOKING_DOCTOR + "/dates/" + shifted
                + "/slots/" + inventorySlotId(date, startMinute, endMinute);
    }

    @Test public void adminPublishesExactTwelveFieldInventory() {
        String path = inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END);
        allowed(admin().set(path, newInventorySlot(MONDAY, START, END, "available")));

        JsonObject slot = savedFields(path);
        assertEquals(12, slot.size());
        assertEquals("enabled", text(slot, "scheduleId"));
        assertEquals(Instant.parse(dhakaMidnight(MONDAY).value), time(slot, "appointmentDate"));
        assertEquals(1L, integer(slot, "dayOfWeek"));
        assertEquals((long) START, integer(slot, "startMinute"));
        assertEquals((long) END, integer(slot, "endMinute"));
        assertEquals(Instant.parse(dhakaTimeAt(MONDAY, START).value), time(slot, "startAt"));
        assertEquals(Instant.parse(dhakaTimeAt(MONDAY, END).value), time(slot, "endAt"));
        assertEquals("available", text(slot, "status"));
        assertEquals(0L, integer(slot, "generation"));
        assertTrue(nullField(slot, "appointmentId"));
        assertEquals(time(slot, "createdAt"), time(slot, "updatedAt"));

        String blockedPath = inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580);
        allowed(admin().set(blockedPath, newInventorySlot(MONDAY, 560, 580, "blocked")));
        assertEquals("blocked", text(savedFields(blockedPath), "status"));
    }

    @Test public void adminPublicationTransactionCreatesMissingSlotAndSkipsBookedSlot() {
        String path = inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END);
        RulesClient client = admin();
        String transaction = allowed(client.beginTransaction()).getAsJsonObject().get("transaction").getAsString();
        // Admin may read missing inventory/block paths; Patients may not use private block reads.
        allowed(client.transactionRead(transaction, path, dateBlockPath(BOOKING_DOCTOR, MONDAY),
                "doctors/active", ENABLED));
        allowed(client.transactionCommit(transaction,
                write(path, newInventorySlot(MONDAY, START, END, "available"), false)));
        allowed(bookingBatch(patient(), "patient-a", "published-transaction-booking", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));
        String retry = allowed(client.beginTransaction()).getAsJsonObject().get("transaction").getAsString();
        allowed(client.transactionRead(retry, path));
        // A publisher retry finding an existing slot commits no writes, rather than using set().
        allowed(client.transactionCommit(retry));
        assertEquals("booked", text(savedFields(path), "status"));
        assertEquals(1L, integer(savedFields(path), "generation"));
        assertEquals("published-transaction-booking", text(savedFields(path), "appointmentId"));
        denied(client.set(path, newInventorySlot(MONDAY, START, END, "available")));
    }

    @Test public void inventorySchemaPathsScheduleAndStatusAreStrict() {
        String path = inventorySlotPath(BOOKING_DOCTOR, OTHER_MONDAY, START, END);
        Map<String, Object> valid = newInventorySlot(OTHER_MONDAY, START, END, "available");
        assertEquals(12, valid.size());

        for (String field : valid.keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            denied(admin().set(path, missing));
        }
        denied(admin().set(path, merge(valid, map("extra", "field"))));
        denied(admin().set(path, merge(valid, map("scheduleId", "missing"))));
        denied(admin().set(path, merge(valid, map("scheduleId", "disabled"))));
        denied(admin().set(mismatchedDatePath(OTHER_MONDAY, START, END), valid));
        denied(admin().set(inventorySlotPath(BOOKING_DOCTOR, OTHER_MONDAY, START, END)
                        .replace(inventorySlotId(OTHER_MONDAY, START, END),
                                inventorySlotId(OTHER_MONDAY, START, 580)), valid));
        denied(admin().set(path, merge(valid, map("appointmentDate", dhakaTimeAt(OTHER_MONDAY, 1)))));
        denied(admin().set(path, merge(valid, map("dayOfWeek", 2))));
        denied(admin().set(path, merge(valid, map("startMinute", 550))));
        denied(admin().set(path, merge(valid, map("endMinute", 540))));
        denied(admin().set(path, merge(valid,
                inventorySlot("enabled", OTHER_MONDAY, 550, 570, "available", 0, null,
                        Special.SERVER_TIME, Special.SERVER_TIME))));
        denied(admin().set(path, merge(valid,
                inventorySlot("enabled", OTHER_MONDAY, 520, 540, "available", 0, null,
                        Special.SERVER_TIME, Special.SERVER_TIME))));
        denied(admin().set(path, merge(valid,
                inventorySlot("enabled", OTHER_MONDAY, 540, 570, "available", 0, null,
                        Special.SERVER_TIME, Special.SERVER_TIME))));
        for (Object status : new Object[]{"booked", "unknown", 1, true, null}) {
            denied(admin().set(path, merge(valid, map("status", status))));
        }
        for (Object generation : new Object[]{1, -1, "0", 0.5, null}) {
            denied(admin().set(path, merge(valid, map("generation", generation))));
        }
        for (Object appointmentId : new Object[]{"booked", 1, map("value", "x")}) {
            denied(admin().set(path, merge(valid, map("appointmentId", appointmentId))));
        }
        denied(admin().set(path, merge(valid, map("createdAt", OLD_TIME))));
        denied(admin().set(path, merge(valid, map("updatedAt", OLD_TIME))));
        denied(admin().set(path, merge(valid, map("createdAt", ZERO_TIME))));
        denied(admin().set(path, merge(valid, map("updatedAt", ZERO_TIME))));

        String inactiveDoctorPath = "doctorSlotInventory/inactive/dates/" + dhakaDateKey(OTHER_MONDAY)
                + "/slots/" + inventorySlotId(OTHER_MONDAY, START, END);
        denied(admin().set(inactiveDoctorPath, valid));
        seed("doctors/active/schedules/weekday-2", schedule(2, true));
        denied(admin().set(path, merge(valid, map("scheduleId", "weekday-2"))));

        missing(admin().get(path));
    }

    @Test public void inventoryWritesAreAdminOnlyAndReadsAreRoleScoped() {
        String activePath = inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END);
        String inactivePath = "doctorSlotInventory/inactive/dates/" + dhakaDateKey(MONDAY)
                + "/slots/" + inventorySlotId(MONDAY, START, END);
        allowed(admin().set(activePath, newInventorySlot(MONDAY, START, END, "available")));
        seed(inactivePath, inventorySlot("enabled", MONDAY, START, END, "available", 0, null,
                OLD_TIME, OLD_TIME));

        allowed(patient().get(activePath));
        denied(patient().get(inactivePath));
        allowed(admin().get(activePath));
        allowed(admin().get(inactivePath));
        denied(anonymous().get(activePath));

        Map<String, Object> fresh = newInventorySlot(MONDAY, 560, 580, "available");
        denied(patient().set(inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580), fresh));
        denied(anonymous().set(inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580), fresh));
        denied(authenticated("string-admin", map("admin", "true"))
                .set(inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580), fresh));
        denied(admin().update(activePath, map("status", "blocked", "updatedAt", OLD_TIME)));
        denied(patient().update(activePath, map("status", "blocked", "updatedAt", Special.SERVER_TIME)));
        denied(admin().delete(activePath));
        denied(patient().delete(activePath));

        allowed(patient().query("doctorSlotInventory/" + BOOKING_DOCTOR + "/dates/"
                + dhakaDateKey(MONDAY), "slots", null, false));
        denied(patient().query("doctorSlotInventory/inactive/dates/"
                + dhakaDateKey(MONDAY), "slots", null, false));
        allowed(admin().query("doctorSlotInventory/inactive/dates/"
                + dhakaDateKey(MONDAY), "slots", null, false));
        denied(anonymous().query("doctorSlotInventory/" + BOOKING_DOCTOR + "/dates/"
                + dhakaDateKey(MONDAY), "slots", null, false));
    }

    @Test public void adminOnlyFlipsUnbookedSlotsAndCannotTouchBookedOnes() {
        String path = inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END);
        allowed(admin().set(path, newInventorySlot(MONDAY, START, END, "available")));
        allowed(admin().update(path, map("status", "blocked", "updatedAt", Special.SERVER_TIME)));
        assertEquals("blocked", text(savedFields(path), "status"));
        allowed(admin().update(path, map("status", "available", "updatedAt", Special.SERVER_TIME)));
        assertEquals("available", text(savedFields(path), "status"));

        denied(admin().update(path, map("status", "available", "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("status", "booked", "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("status", "unknown", "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("status", 1, "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("generation", 1, "updatedAt", Special.SERVER_TIME)));
        for (Map<String, Object> numericRetype : Arrays.asList(
                map("generation", 0.0), map("dayOfWeek", 1.0),
                map("startMinute", 540.0), map("endMinute", 560.0))) {
            denied(admin().update(path, merge(numericRetype,
                    map("status", "blocked", "updatedAt", Special.SERVER_TIME))));
        }
        denied(admin().update(path, map("appointmentId", "forged",
                "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("scheduleId", "disabled",
                "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("startMinute", 550, "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("createdAt", ZERO_TIME,
                "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, merge(newInventorySlot(MONDAY, START, END, "blocked"),
                map("extra", "field"))));
        denied(patient().update(path, map("status", "blocked", "updatedAt", Special.SERVER_TIME)));

        seedBooking("booked-slot", MONDAY, START, END, "patient-a",
                appointmentRecord(MONDAY, START, END, "Confirmed"));
        denied(admin().update(path, map("status", "blocked", "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("status", "available", "updatedAt", Special.SERVER_TIME)));
        denied(admin().delete(path));
        assertEquals("booked", text(savedFields(path), "status"));

        String blockedPath = inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580);
        allowed(admin().set(blockedPath, newInventorySlot(MONDAY, 560, 580, "blocked")));
        denied(bookingBatch(patient(), "patient-a", "blocked-slot-booking",
                MONDAY, 560, 580, 1, newAppointment(MONDAY, 560, 580)));
        allowed(admin().update(blockedPath,
                map("status", "available", "updatedAt", Special.SERVER_TIME)));
        allowed(bookingBatch(patient(), "patient-a", "opened-slot-booking",
                MONDAY, 560, 580, 1, newAppointment(MONDAY, 560, 580)));
        assertEquals("booked", text(savedFields(blockedPath), "status"));
    }

    @Test public void bookingLocksAreCreateOnlyImmutableHistory() {
        String slotPath = inventorySlotPath(BOOKING_DOCTOR, MONDAY, START, END);
        String lockPath = bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 1);
        seedAvailableSlot(MONDAY, START, END);
        Map<String, Object> lock = bookingLock("lock-only", "patient-a", "enabled",
                MONDAY, START, END, Special.SERVER_TIME);

        denied(patient().set(lockPath, lock));
        denied(patient().commit(
                write(lockPath, lock, false),
                write("appointments/lock-only", newAppointment(MONDAY, START, END), false)));
        missing(admin().get(lockPath));

        allowed(bookingBatch(patient(), "patient-a", "lock-owner", MONDAY, START, END, 1,
                newAppointment(MONDAY, START, END)));
        JsonObject saved = savedFields(lockPath);
        assertEquals(6, saved.size());
        assertEquals("lock-owner", text(saved, "appointmentId"));
        assertEquals("patient-a", text(saved, "patientUid"));

        for (RulesClient client : new RulesClient[]{patient(), admin(), anonymous()}) {
            denied(client.update(lockPath, map("patientUid", "patient-b")));
            denied(client.set(lockPath, merge(lock,
                    map("appointmentId", "lock-owner", "patientUid", "patient-b"))));
            denied(client.delete(lockPath));
        }
        denied(patient().set(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 2),
                merge(lock, map("appointmentId", "lock-owner"))));
        denied(patient().set(bookingLockPath(BOOKING_DOCTOR, MONDAY, START, END, 0), lock));
        assertEquals("lock-owner", text(savedFields(lockPath), "appointmentId"));
        assertEquals("booked", text(savedFields(slotPath), "status"));
        assertEquals(1L, integer(savedFields(slotPath), "generation"));

        seedAvailableSlot(MONDAY, 560, 580);
        String wrongGeneration = bookingLockPath(BOOKING_DOCTOR, MONDAY, 560, 580, 2);
        denied(patient().commit(
                write(inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580),
                        bookedInventorySlot(MONDAY, 560, 580, 2, "wrong-generation"), true),
                write(wrongGeneration, lock, false),
                write("appointments/wrong-generation",
                        newAppointment(MONDAY, 560, 580), false)));
        assertEquals("available", text(savedFields(
                inventorySlotPath(BOOKING_DOCTOR, MONDAY, 560, 580)), "status"));
        missing(admin().get(wrongGeneration));
    }

    @Test public void dateBlocksAreAdminOnlyFourFieldLifecycleDocuments() {
        String path = dateBlockPath(BOOKING_DOCTOR, MONDAY);
        Map<String, Object> valid = dateBlockRecord(MONDAY, "Maintenance",
                Special.SERVER_TIME, Special.SERVER_TIME);

        for (String field : valid.keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            denied(admin().set(path, missing));
        }
        denied(admin().set(path, merge(valid, map("extra", "field"))));
        denied(admin().set(dateBlockPath(BOOKING_DOCTOR, MONDAY.plusWeeks(2)), valid));
        denied(admin().set(path, merge(valid, map("date", dhakaTimeAt(MONDAY, 1)))));
        denied(admin().set(path, merge(valid, map("date", dhakaMidnight(MONDAY.plusDays(1))))));
        denied(admin().set(path, merge(valid, map("reason", "R".repeat(201)))));
        denied(admin().set(path, merge(valid, map("reason", 1))));
        denied(admin().set(path, merge(valid, map("createdAt", OLD_TIME))));
        denied(admin().set(path, merge(valid, map("updatedAt", OLD_TIME))));
        denied(patient().set(path, valid));
        denied(anonymous().set(path, valid));
        denied(authenticated("string-admin", map("admin", "true")).set(path, valid));
        missing(admin().get(path));

        allowed(admin().set(path, valid));
        JsonObject block = savedFields(path);
        assertEquals(4, block.size());
        assertEquals(Instant.parse(dhakaMidnight(MONDAY).value), time(block, "date"));
        assertEquals("Maintenance", text(block, "reason"));
        assertEquals(time(block, "createdAt"), time(block, "updatedAt"));
        denied(patient().get(path));
        denied(anonymous().get(path));
        allowed(admin().get(path));
        allowed(admin().query("doctorDateBlocks/" + BOOKING_DOCTOR,
                "dates", null, false));
        denied(patient().query("doctorDateBlocks/" + BOOKING_DOCTOR,
                "dates", null, false));

        denied(admin().set(path, dateBlockRecord(MONDAY, "Replacement",
                Special.SERVER_TIME, Special.SERVER_TIME)));
        denied(admin().update(path, map("date", dhakaMidnight(MONDAY.plusDays(1)),
                "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("createdAt", OLD_TIME,
                "updatedAt", Special.SERVER_TIME)));
        denied(admin().update(path, map("extra", "field", "updatedAt", Special.SERVER_TIME)));
        denied(patient().update(path, map("reason", "Patient reason",
                "updatedAt", Special.SERVER_TIME)));
        allowed(admin().update(path, map("reason", "Updated maintenance",
                "updatedAt", Special.SERVER_TIME)));
        assertEquals("Updated maintenance", text(savedFields(path), "reason"));
        assertEquals(Instant.parse(dhakaMidnight(MONDAY).value), time(savedFields(path), "date"));

        denied(patient().delete(path));
        allowed(admin().delete(path));
        missing(admin().get(path));
        allowed(admin().set(path, valid));
    }

    @Test public void inactiveDoctorDateBlockRemainsAllowedForAdmin() {
        String path = dateBlockPath("inactive", MONDAY);
        allowed(admin().set(path, dateBlockRecord(MONDAY, "Inactive doctor",
                Special.SERVER_TIME, Special.SERVER_TIME)));
        allowed(admin().get(path));
        allowed(admin().delete(path));
    }

    /**
     * Rules cannot query sibling slots. This test documents the explicit trust boundary: two
     * active schedules can publish overlapping intervals, and both reservations are accepted.
     * It is not a patient privilege escalation; the publisher is Admin.
     */
    @Test public void overlappingAdminInventoryAndBookingsRemainPossible() {
        seed("doctors/active/schedules/overlap-30", map("dayOfWeek", 1, "startTime", 540,
                "endTime", 660, "slotDurationMinutes", 30, "active", true, "updatedAt", OLD_TIME));
        String firstPath = inventorySlotPath(BOOKING_DOCTOR, MONDAY, 540, 560);
        String secondPath = inventorySlotPath(BOOKING_DOCTOR, MONDAY, 540, 570);
        allowed(admin().set(firstPath, newInventorySlot(MONDAY, 540, 560, "available")));
        allowed(admin().set(secondPath, inventorySlot("overlap-30", MONDAY, 540, 570,
                "available", 0, null, Special.SERVER_TIME, Special.SERVER_TIME)));
        assertEquals("available", text(savedFields(firstPath), "status"));
        assertEquals("available", text(savedFields(secondPath), "status"));

        allowed(bookingBatch(patient(), "patient-a", "overlap-short", MONDAY, 540, 560, 1,
                newAppointment(MONDAY, 540, 560)));
        Map<String, Object> longAppointment = merge(newAppointment(MONDAY, 540, 570),
                map("scheduleId", "overlap-30"));
        allowed(patient().commit(
                write(secondPath, map("status", "booked", "generation", 1,
                        "appointmentId", "overlap-long",
                        "updatedAt", Special.SERVER_TIME), true),
                write(bookingLockPath(BOOKING_DOCTOR, MONDAY, 540, 570, 1),
                        bookingLock("overlap-long", "patient-a", "overlap-30",
                                MONDAY, 540, 570, Special.SERVER_TIME), false),
                write("appointments/overlap-long", longAppointment, false)));

        assertEquals("Confirmed", text(savedFields("appointments/overlap-short"), "status"));
        assertEquals("Confirmed", text(savedFields("appointments/overlap-long"), "status"));
        assertEquals("booked", text(savedFields(firstPath), "status"));
        assertEquals("booked", text(savedFields(secondPath), "status"));
    }
}
