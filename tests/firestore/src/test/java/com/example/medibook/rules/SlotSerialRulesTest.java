package com.example.medibook.rules;

import com.google.gson.JsonObject;
import org.junit.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** Display-only sidecars must never alter the working reservation contract. */
public class SlotSerialRulesTest extends RulesFixtures {
    private static final LocalDate DAY = nextDhakaDate(DayOfWeek.MONDAY);
    private static String counter(String doctor, LocalDate day) { return inventoryDatePath(doctor, day); }
    private static String mapping(String doctor, LocalDate day, int start, int end) {
        return counter(doctor, day) + "/serials/" + inventorySlotId(day, start, end);
    }
    private static Map<String, Object> serial(Object number) { return map("number", number, "createdAt", Special.SERVER_TIME); }
    private static Map<String, Object> cursor(LocalDate day, int start, int end, Object number) {
        return map("lastSerial", number, "lastSlotId", inventorySlotId(day, start, end), "updatedAt", Special.SERVER_TIME);
    }
    private static Reply allocate(RulesClient actor, String doctor, LocalDate day, int start, int end, int number) {
        return actor.commit(write(mapping(doctor, day, start, end), serial(number), false),
                write(counter(doctor, day), cursor(day, start, end, number), false));
    }
    private static long assigned(String doctor, LocalDate day, int start, int end) {
        return integer(fields(admin().get(mapping(doctor, day, start, end))), "number");
    }
    private static void ready() { seedAvailableSlot(DAY, 540, 560); seedAvailableSlot(DAY, 560, 580); }

    @Test public void adminAllocatesAnImmutableSerialWithoutChangingSlotSchema() {
        ready(); allowed(allocate(admin(), "active", DAY, 540, 560, 1));
        assertEquals(1, assigned("active", DAY, 540, 560));
        assertEquals(2, fields(admin().get(mapping("active", DAY, 540, 560))).size());
        assertEquals(3, fields(admin().get(counter("active", DAY))).size());
        assertEquals(12, fields(admin().get(inventorySlotPath("active", DAY, 540, 560))).size());
    }
    @Test public void allocatorTransactionUsesExactClientReadAndWriteShape() {
        ready(); RulesClient client = admin();
        String transaction = allowed(client.beginTransaction()).getAsJsonObject().get("transaction").getAsString();
        allowed(client.transactionRead(transaction, mapping("active", DAY, 540, 560), counter("active", DAY), inventorySlotPath("active", DAY, 540, 560)));
        allowed(client.transactionCommit(transaction, write(mapping("active", DAY, 540, 560), serial(1), false),
                write(counter("active", DAY), cursor(DAY, 540, 560, 1), false)));
        assertEquals(1, assigned("active", DAY, 540, 560));
    }
    @Test public void assignmentRequiresBothWritesAndRealInventory() {
        ready();
        denied(admin().set(mapping("active", DAY, 540, 560), serial(1)));
        denied(admin().set(counter("active", DAY), cursor(DAY, 540, 560, 1)));
        denied(allocate(admin(), "active", DAY, 600, 620, 1));
        missing(admin().get(counter("active", DAY))); missing(admin().get(mapping("active", DAY, 540, 560)));
    }
    @Test public void patientsAnonymousAndMalformedAdminsCannotAllocate() {
        ready();
        for (RulesClient client : new RulesClient[]{patient(), anonymous(), authenticated("fake-admin", map()),
                authenticated("patient-a", map("admin", "true")), authenticated("patient-a", map("admin", false))}) {
            denied(allocate(client, "active", DAY, 540, 560, 1));
        }
        allowed(allocate(admin(), "active", DAY, 540, 560, 1));
        allowed(patient().get(mapping("active", DAY, 540, 560)));
        denied(patient().get(counter("active", DAY))); denied(anonymous().get(mapping("active", DAY, 540, 560)));
    }
    @Test public void serialsCannotBeChangedDeletedOrReusedIncludingByAdmin() {
        ready(); allowed(allocate(admin(), "active", DAY, 540, 560, 1));
        for (RulesClient client : new RulesClient[]{patient(), admin(), anonymous()}) {
            denied(client.update(mapping("active", DAY, 540, 560), map("number", 2)));
            denied(client.delete(mapping("active", DAY, 540, 560))); denied(client.delete(counter("active", DAY)));
        }
        denied(allocate(admin(), "active", DAY, 540, 560, 2));
        denied(allocate(admin(), "active", DAY, 560, 580, 1));
        denied(allocate(admin(), "active", DAY, 560, 580, 3));
        allowed(allocate(admin(), "active", DAY, 560, 580, 2));
    }
    @Test public void counterCannotBeResetSkippedOrAdvancedWithoutANewMapping() {
        ready(); allowed(allocate(admin(), "active", DAY, 540, 560, 1));
        denied(admin().update(counter("active", DAY), cursor(DAY, 560, 580, 0)));
        denied(admin().update(counter("active", DAY), cursor(DAY, 560, 580, 2)));
        denied(allocate(admin(), "active", DAY, 560, 580, 0));
        denied(allocate(admin(), "active", DAY, 560, 580, 4));
        assertEquals(1, assigned("active", DAY, 540, 560));
    }
    @Test public void incrementalEarlierSlotsAppendWithoutRenumberingExistingAssignments() {
        ready(); allowed(allocate(admin(), "active", DAY, 560, 580, 1));
        allowed(allocate(admin(), "active", DAY, 540, 560, 2));
        assertEquals(1, assigned("active", DAY, 560, 580)); assertEquals(2, assigned("active", DAY, 540, 560));
    }
    @Test public void doctorsAndDatesHaveIndependentSequences() {
        ready(); LocalDate other = DAY.plusWeeks(1); seedAvailableSlot(other, 540, 560);
        seed("doctors/other", doctor(true));
        seed(inventorySlotPath("other", DAY, 540, 560), inventorySlot("enabled", DAY, 540, 560, "available", 0, null, OLD_TIME, OLD_TIME));
        allowed(allocate(admin(), "active", DAY, 540, 560, 1));
        allowed(allocate(admin(), "active", other, 540, 560, 1)); allowed(allocate(admin(), "other", DAY, 540, 560, 1));
        assertEquals(1, assigned("active", other, 540, 560)); assertEquals(1, assigned("other", DAY, 540, 560));
    }
    @Test public void strictSchemaTypesTimestampsAndCompanionIdentityAreEnforced() {
        ready();
        for (Object value : new Object[]{0, -1, 1000001, 1.5, "1", null}) {
            denied(admin().commit(write(mapping("active", DAY, 540, 560), serial(value), false),
                    write(counter("active", DAY), cursor(DAY, 540, 560, value), false)));
        }
        denied(admin().commit(write(mapping("active", DAY, 540, 560), merge(serial(1), map("extra", true)), false),
                write(counter("active", DAY), cursor(DAY, 540, 560, 1), false)));
        denied(admin().commit(write(mapping("active", DAY, 540, 560), serial(1), false),
                write(counter("active", DAY), merge(cursor(DAY, 540, 560, 1), map("extra", true)), false)));
        denied(admin().commit(write(mapping("active", DAY, 540, 560), merge(serial(1), map("createdAt", OLD_TIME)), false),
                write(counter("active", DAY), cursor(DAY, 540, 560, 1), false)));
        denied(admin().commit(write(mapping("active", DAY, 540, 560), serial(1), false),
                write(counter("active", DAY), cursor(DAY, 560, 580, 1), false)));
    }
    @Test public void oneCounterAdvanceCannotCreateTwoMappings() {
        ready(); denied(admin().commit(write(mapping("active", DAY, 540, 560), serial(1), false),
                write(mapping("active", DAY, 560, 580), serial(1), false), write(counter("active", DAY), cursor(DAY, 560, 580, 1), false)));
        missing(admin().get(counter("active", DAY)));
    }
    @Test public void cancellationRebookingAndHistoryKeepTheSameSlotNumber() {
        ready(); allowed(allocate(admin(), "active", DAY, 540, 560, 1));
        allowed(bookingBatch(patient(), "patient-a", "first", DAY, 540, 560, 1, newAppointment(DAY, 540, 560)));
        allowed(cancellationBatch(patient(), "first", DAY, 540, 560, 1, "available"));
        assertEquals(1, assigned("active", DAY, 540, 560));
        allowed(bookingBatch(patient(), "patient-a", "second", DAY, 540, 560, 2, newAppointment(DAY, 540, 560)));
        allowed(cancellationBatch(admin(), "second", DAY, 540, 560, 2, "available"));
        assertEquals(1, assigned("active", DAY, 540, 560));
        assertEquals("Cancelled", text(fields(patient().get("appointments/first")), "status"));
        assertEquals(17, fields(patient().get("appointments/second")).size());
        assertEquals(6, fields(admin().get(bookingLockPath("active", DAY, 540, 560, 2))).size());
    }
    @Test public void legacyBookedSlotsCanBeNumberedWithoutTouchingAppointmentsOrLocks() {
        seedBooking("legacy", DAY, 540, 560, "patient-a", appointmentRecord(DAY, 540, 560, "Confirmed"));
        allowed(allocate(admin(), "active", DAY, 540, 560, 1));
        assertEquals("booked", text(fields(admin().get(inventorySlotPath("active", DAY, 540, 560))), "status"));
        assertEquals("Confirmed", text(fields(patient().get("appointments/legacy")), "status"));
        allowed(admin().update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        allowed(patient().get(mapping("active", DAY, 540, 560)));
        denied(patient().get(inventorySlotPath("active", DAY, 540, 560)));
        denied(patient().get(bookingLockPath("active", DAY, 540, 560, 1)));
    }
    @Test public void concurrentAllocationsCannotDuplicateASequenceNumber() throws Exception {
        ready(); CountDownLatch go = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Reply> first = () -> { go.await(); return allocate(admin(), "active", DAY, 540, 560, 1); };
            Callable<Reply> second = () -> { go.await(); return allocate(admin(), "active", DAY, 560, 580, 1); };
            Future<Reply> a = pool.submit(first), b = pool.submit(second); go.countDown();
            Reply one = a.get(), two = b.get();
            assertEquals(1, (one.code == 200 ? 1 : 0) + (two.code == 200 ? 1 : 0));
            if (one.code == 200) { denied(two); allowed(allocate(admin(), "active", DAY, 560, 580, 2)); }
            else { denied(one); allowed(allocate(admin(), "active", DAY, 540, 560, 2)); }
            assertNotEquals(assigned("active", DAY, 540, 560), assigned("active", DAY, 560, 580));
        } finally { pool.shutdownNow(); }
    }
}
