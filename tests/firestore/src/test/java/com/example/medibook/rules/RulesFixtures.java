package com.example.medibook.rules;

import org.junit.Before;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static com.example.medibook.rules.RulesClient.*;

/** Synthetic setup uses emulator bypass; ALL permission assertions use separate end-user clients. */
public abstract class RulesFixtures {
    static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Dhaka");
    static Map<String, Object> profile() {
        return map("fullName", "Patient fixture", "email", "patient@example.invalid", "phone", "1234567",
                "role", "Patient", "createdAt", OLD_TIME);
    }
    static Map<String, Object> doctor(boolean active) {
        return map("name", "Rules fixture", "specialization", "Fixture specialty", "qualifications", "Fixture qualification",
                "consultationFee", 50025, "biography", "", "active", active, "createdAt", OLD_TIME, "updatedAt", OLD_TIME);
    }
    static Map<String, Object> newDoctor() {
        return merge(doctor(true), map("createdAt", Special.SERVER_TIME, "updatedAt", Special.SERVER_TIME));
    }
    static Map<String, Object> schedule(boolean active) {
        return map("dayOfWeek", 1, "startTime", 540, "endTime", 660, "slotDurationMinutes", 20, "active", active, "updatedAt", OLD_TIME);
    }
    static Map<String, Object> schedule(int dayOfWeek, boolean active) {
        return merge(schedule(active), map("dayOfWeek", dayOfWeek));
    }
    static Map<String, Object> newSchedule() { return merge(schedule(true), map("updatedAt", Special.SERVER_TIME)); }
    static String schedulePath(String doctor, String id) { return "doctors/" + doctor + "/schedules/" + id; }
    static final String ENABLED = "doctors/active/schedules/enabled";
    static final String DISABLED = "doctors/active/schedules/disabled";

    /** Dhaka wall-clock minute on a calendar date, expressed as a UTC instant for REST. */
    static Time dhakaTimeAt(LocalDate date, int minuteOfDay) {
        return new Time(date.atStartOfDay(HOSPITAL_ZONE).plusMinutes(minuteOfDay).toInstant().toString());
    }
    static Time dhakaMidnight(LocalDate date) { return dhakaTimeAt(date, 0); }
    static int isoDayOfWeek(LocalDate date) { return date.getDayOfWeek().getValue(); }
    static LocalDate nextDhakaDate(DayOfWeek dayOfWeek) {
        return LocalDate.now(HOSPITAL_ZONE).with(TemporalAdjusters.next(dayOfWeek));
    }
    /** Monday reuses the shared fixture; other weekdays are seeded by AppointmentRulesTest. */
    static String appointmentScheduleId(int dayOfWeek) {
        return dayOfWeek == 1 ? "enabled" : "weekday-" + dayOfWeek;
    }

    /** Decimal epoch milliseconds of Dhaka midnight, matching the Rules dateKey path segment. */
    static String dhakaDateKey(LocalDate date) {
        return Long.toString(date.atStartOfDay(HOSPITAL_ZONE).toInstant().toEpochMilli());
    }
    static long dhakaMinuteMillis(LocalDate date, int minuteOfDay) {
        return date.atStartOfDay(HOSPITAL_ZONE).plusMinutes(minuteOfDay).toInstant().toEpochMilli();
    }
    static String inventorySlotId(LocalDate date, int startMinute, int endMinute) {
        return "s" + dhakaMinuteMillis(date, startMinute) + "-" + dhakaMinuteMillis(date, endMinute);
    }
    static String inventoryDatePath(String doctorId, LocalDate date) {
        return "doctorSlotInventory/" + doctorId + "/dates/" + dhakaDateKey(date);
    }
    static String inventorySlotPath(String doctorId, LocalDate date, int startMinute, int endMinute) {
        return inventoryDatePath(doctorId, date) + "/slots/" + inventorySlotId(date, startMinute, endMinute);
    }
    static String bookingLockPath(String doctorId, LocalDate date, int startMinute, int endMinute,
                                  int generation) {
        return inventorySlotPath(doctorId, date, startMinute, endMinute) + "/bookings/g" + generation;
    }
    static String dateBlockPath(String doctorId, LocalDate date) {
        return "doctorDateBlocks/" + doctorId + "/dates/" + dhakaDateKey(date);
    }

    /** Complete 12-field slot inventory record; timestamps are supplied by the caller. */
    static Map<String, Object> inventorySlot(String scheduleId, LocalDate date,
                                             int startMinute, int endMinute, String status,
                                             int generation, Object appointmentId,
                                             Object createdAt, Object updatedAt) {
        int dayOfWeek = isoDayOfWeek(date);
        return map("scheduleId", scheduleId, "appointmentDate", dhakaMidnight(date), "dayOfWeek", dayOfWeek,
                "startMinute", startMinute, "endMinute", endMinute,
                "startAt", dhakaTimeAt(date, startMinute), "endAt", dhakaTimeAt(date, endMinute),
                "status", status, "generation", generation, "appointmentId", appointmentId,
                "createdAt", createdAt, "updatedAt", updatedAt);
    }
    static Map<String, Object> newInventorySlot(LocalDate date, int startMinute, int endMinute,
                                                String status) {
        return inventorySlot(appointmentScheduleId(isoDayOfWeek(date)), date, startMinute, endMinute,
                status, 0, null, Special.SERVER_TIME, Special.SERVER_TIME);
    }
    static Map<String, Object> bookedInventorySlot(LocalDate date, int startMinute, int endMinute,
                                                   int generation, String appointmentId) {
        return inventorySlot(appointmentScheduleId(isoDayOfWeek(date)), date, startMinute, endMinute,
                "booked", generation, appointmentId, OLD_TIME, Special.SERVER_TIME);
    }
    static Map<String, Object> releasedInventorySlot(LocalDate date, int startMinute, int endMinute,
                                                     int generation, String status) {
        return inventorySlot(appointmentScheduleId(isoDayOfWeek(date)), date, startMinute, endMinute,
                status, generation, null, OLD_TIME, Special.SERVER_TIME);
    }

    /** Complete six-field immutable booking lock; the path supplies doctor/date/slot/generation. */
    static Map<String, Object> bookingLock(String appointmentId, String patientUid, String scheduleId,
                                           LocalDate date, int startMinute, int endMinute,
                                           Object createdAt) {
        return map("appointmentId", appointmentId, "patientUid", patientUid, "scheduleId", scheduleId,
                "startAt", dhakaTimeAt(date, startMinute), "endAt", dhakaTimeAt(date, endMinute),
                "createdAt", createdAt);
    }

    /** Complete four-field date block; existence is the blocked state. */
    static Map<String, Object> dateBlockRecord(LocalDate date, String reason,
                                               Object createdAt, Object updatedAt) {
        return map("date", dhakaMidnight(date), "reason", reason,
                "createdAt", createdAt, "updatedAt", updatedAt);
    }

    /** A complete 17-field appointment owned by the shared Patient fixture. */
    static Map<String, Object> appointmentRecord(LocalDate date, int startMinute, int endMinute, String status) {
        int dayOfWeek = isoDayOfWeek(date);
        return map("patientUid", "patient-a", "patientName", "Patient fixture", "patientPhone", "1234567",
                "doctorId", "active", "doctorName", "Rules fixture", "doctorSpecialization", "Fixture specialty",
                "consultationFee", 50025, "scheduleId", appointmentScheduleId(dayOfWeek), "dayOfWeek", dayOfWeek,
                "appointmentDate", dhakaMidnight(date), "startMinute", startMinute, "endMinute", endMinute,
                "startAt", dhakaTimeAt(date, startMinute), "endAt", dhakaTimeAt(date, endMinute),
                "status", status, "createdAt", OLD_TIME, "updatedAt", OLD_TIME);
    }
    static Map<String, Object> newAppointment(LocalDate date, int startMinute, int endMinute) {
        return merge(appointmentRecord(date, startMinute, endMinute, "Confirmed"),
                map("createdAt", Special.SERVER_TIME, "updatedAt", Special.SERVER_TIME));
    }

    static final String BOOKING_DOCTOR = "active";

    static void seedAvailableSlot(LocalDate date, int startMinute, int endMinute) {
        seed(inventorySlotPath(BOOKING_DOCTOR, date, startMinute, endMinute),
                inventorySlot(appointmentScheduleId(isoDayOfWeek(date)), date, startMinute, endMinute,
                        "available", 0, null, OLD_TIME, OLD_TIME));
    }

    static void seedBooking(String appointmentId, LocalDate date, int startMinute, int endMinute,
                            String patientUid, Map<String, Object> appointment) {
        String scheduleId = appointmentScheduleId(isoDayOfWeek(date));
        seed(inventorySlotPath(BOOKING_DOCTOR, date, startMinute, endMinute),
                inventorySlot(scheduleId, date, startMinute, endMinute, "booked", 1,
                        appointmentId, OLD_TIME, OLD_TIME));
        seed(bookingLockPath(BOOKING_DOCTOR, date, startMinute, endMinute, 1),
                bookingLock(appointmentId, patientUid, scheduleId, date, startMinute, endMinute,
                        OLD_TIME));
        seed("appointments/" + appointmentId, appointment);
    }

    /** Exact three-write booking batch used by both success and denial assertions. */
    static RulesClient.Reply bookingBatch(RulesClient client, String patientUid,
                                          String appointmentId, LocalDate date,
                                          int startMinute, int endMinute, int generation,
                                          Map<String, Object> appointment) {
        String scheduleId = appointmentScheduleId(isoDayOfWeek(date));
        return client.commit(
                write(inventorySlotPath(BOOKING_DOCTOR, date, startMinute, endMinute),
                        map("status", "booked", "generation", generation,
                                "appointmentId", appointmentId,
                                "updatedAt", Special.SERVER_TIME), true),
                write(bookingLockPath(BOOKING_DOCTOR, date, startMinute, endMinute, generation),
                        bookingLock(appointmentId, patientUid, scheduleId, date,
                                startMinute, endMinute, Special.SERVER_TIME), false),
                write("appointments/" + appointmentId, appointment, false));
    }

    static RulesClient.Reply cancellationBatch(RulesClient client, String appointmentId,
                                               LocalDate date, int startMinute, int endMinute,
                                               int generation, String releaseStatus) {
        return client.commit(
                write("appointments/" + appointmentId,
                        map("status", "Cancelled", "updatedAt", Special.SERVER_TIME), true),
                write(inventorySlotPath(BOOKING_DOCTOR, date, startMinute, endMinute),
                        map("status", releaseStatus, "generation", generation,
                                "appointmentId", null, "updatedAt", Special.SERVER_TIME), true));
    }

    @Before public void seedOnlyTheOwnedDemoEmulator() {
        clearFixtures();
        seed("users/patient-a", profile());
        seed("users/fake-admin", merge(profile(), map("role", "Admin")));
        seed("doctors/active", doctor(true));
        seed("doctors/inactive", doctor(false));
        seed(ENABLED, schedule(true));
        seed(DISABLED, schedule(false));
        seed("doctors/inactive/schedules/enabled", schedule(true));
        seed("doctors/missing/schedules/orphan", schedule(true));
    }
}
