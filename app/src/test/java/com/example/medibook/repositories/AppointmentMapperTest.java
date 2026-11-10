package com.example.medibook.repositories;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;

import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Offline mapping only. Fixtures are never sent to Firebase or included as application data. */
public class AppointmentMapperTest {
    private static final long MIDNIGHT = 1_793_988_000_000L; // 2026-11-07 00:00 Asia/Dhaka
    private static final long START_AT = MIDNIGHT + 540L * 60_000L;
    private static final long END_AT = MIDNIGHT + 570L * 60_000L;

    private static Timestamp timestamp(long millis) {
        return new Timestamp(millis / 1000L, (int) ((millis % 1000L) * 1_000_000L));
    }

    private Map<String, Object> data() {
        Map<String, Object> data = new HashMap<>();
        data.put("patientUid", "patient-1");
        data.put("patientName", "Patient Fixture");
        data.put("patientPhone", "+8801712345678");
        data.put("doctorId", "doctor-1");
        data.put("doctorName", "Doctor Fixture");
        data.put("doctorSpecialization", "Cardiology");
        data.put("consultationFee", 50025L);
        data.put("scheduleId", "schedule-1");
        data.put("dayOfWeek", 6L);
        data.put("appointmentDate", timestamp(MIDNIGHT));
        data.put("startMinute", 540L);
        data.put("endMinute", 570L);
        data.put("startAt", timestamp(START_AT));
        data.put("endAt", timestamp(END_AT));
        data.put("status", "Confirmed");
        data.put("createdAt", new Timestamp(100, 0));
        data.put("updatedAt", new Timestamp(200, 0));
        return data;
    }

    private Map<String, Object> dataForMinutes(int startMinute, int endMinute) {
        Map<String, Object> data = data();
        data.put("startMinute", (long) startMinute);
        data.put("endMinute", (long) endMinute);
        data.put("startAt", timestamp(MIDNIGHT + startMinute * 60_000L));
        data.put("endAt", timestamp(MIDNIGHT + endMinute * 60_000L));
        return data;
    }

    private void invalid(Map<String, Object> data) {
        assertThrows(IllegalArgumentException.class,
                () -> AppointmentMapper.fromMap("appointment-1", data));
    }

    @Test
    public void mapsAllSeventeenFieldsAndTheSeparateDocumentIdentity() {
        Appointment appointment = AppointmentMapper.fromMap("appointment-1", data());
        assertEquals("appointment-1", appointment.getId());
        assertEquals("patient-1", appointment.getPatientUid());
        assertEquals("Patient Fixture", appointment.getPatientName());
        assertEquals("+8801712345678", appointment.getPatientPhone());
        assertEquals("doctor-1", appointment.getDoctorId());
        assertEquals("Doctor Fixture", appointment.getDoctorName());
        assertEquals("Cardiology", appointment.getDoctorSpecialization());
        assertEquals(50025L, appointment.getConsultationFee());
        assertEquals("schedule-1", appointment.getScheduleId());
        assertEquals(6, appointment.getDayOfWeek());
        assertEquals(timestamp(MIDNIGHT), appointment.getAppointmentDate());
        assertEquals(540, appointment.getStartMinute());
        assertEquals(570, appointment.getEndMinute());
        assertEquals(timestamp(START_AT), appointment.getStartAt());
        assertEquals(timestamp(END_AT), appointment.getEndAt());
        assertEquals(AppointmentStatus.CONFIRMED, appointment.getStatus());
        assertEquals(new Timestamp(100, 0), appointment.getCreatedAt());
        assertEquals(new Timestamp(200, 0), appointment.getUpdatedAt());
        assertFalse(data().containsKey("id"));
    }

    @Test
    public void acceptsIntegerAndLongButRejectsFractionalAndOtherNumericTypes() {
        for (String field : new String[]{"consultationFee", "dayOfWeek", "startMinute", "endMinute"}) {
            Map<String, Object> data = data();
            long stored = (Long) data.get(field);
            data.put(field, (int) stored);
            AppointmentMapper.fromMap("appointment-1", data);

            for (Object wrong : new Object[]{(double) stored, (float) stored, (short) stored,
                    (byte) stored, Long.toString(stored), true, new Object(),
                    Double.NaN, Double.POSITIVE_INFINITY}) {
                data.put(field, wrong);
                invalid(data);
            }
            data.put(field, Long.MAX_VALUE);
            invalid(data);
            data.put(field, Long.MIN_VALUE);
            invalid(data);
        }
    }

    @Test
    public void rejectsEveryMissingField() {
        for (String field : data().keySet()) {
            Map<String, Object> data = data();
            data.remove(field);
            invalid(data);
        }
    }

    @Test
    public void rejectsUnexpectedFieldsIncludingEditableIdentifiersAndTimestamps() {
        for (String field : new String[]{"id", "appointmentId", "role", "ownerUid",
                "createdBy", "notes", "timezone"}) {
            Map<String, Object> data = data();
            data.put(field, "unexpected");
            invalid(data);
        }
    }

    @Test
    public void rejectsNullDataAndNullOrWrongTypesForEveryField() {
        invalid(null);
        for (String field : data().keySet()) {
            Map<String, Object> data = data();
            data.put(field, null);
            invalid(data);

            data.put(field, new Object());
            invalid(data);
        }
    }

    @Test
    public void mapsEveryExactStatusAndRejectsUnknownOrCaseChangedValues() {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            Map<String, Object> data = data();
            data.put("status", status.getWireValue());
            assertEquals(status, AppointmentMapper.fromMap("appointment-1", data).getStatus());
        }
        for (String status : new String[]{"", " ", "pending", "PENDING", " Pending",
                "Pending ", "confirmed", "CONFIRMED", "Confirmed!", "cancelled", "CANCELLED",
                "Pending", "Completed", "completed", "COMPLETED", "Unknown", "0"}) {
            Map<String, Object> data = data();
            data.put("status", status);
            invalid(data);
        }
    }

    @Test
    public void enforcesFeeBoundsWithoutRoundingOrCoercion() {
        for (long fee : new long[]{0L, 50025L, 99_999_999L}) {
            Map<String, Object> data = data();
            data.put("consultationFee", fee);
            assertEquals(fee,
                    AppointmentMapper.fromMap("appointment-1", data).getConsultationFee());
        }
        for (Object fee : new Object[]{-1L, 100_000_000L, 50025.0, 50025.5,
                "50025", Double.NaN, Double.POSITIVE_INFINITY}) {
            Map<String, Object> data = data();
            data.put("consultationFee", fee);
            invalid(data);
        }
    }

    @Test
    public void validatesIsoWeekdayAndTheActualDhakaWeekday() {
        for (long day : new long[]{0L, 8L, -1L}) {
            Map<String, Object> data = data();
            data.put("dayOfWeek", day);
            invalid(data);
        }
        Map<String, Object> wrongDay = data();
        wrongDay.put("dayOfWeek", 5L); // Friday, but 2026-11-07 is Saturday.
        invalid(wrongDay);
    }

    @Test
    public void enforcesMinuteRangesOrderingAndSameDayDuration() {
        for (int[] range : new int[][]{{-1, 30}, {1440, 1441}, {1441, 1500},
                {0, 0}, {540, 539}, {570, 540}, {540, 1440}, {540, 1441}}) {
            invalid(dataForMinutes(range[0], range[1]));
        }
        assertEquals(0, AppointmentMapper.fromMap("appointment-1",
                dataForMinutes(0, 1)).getStartMinute());
        assertEquals(1439, AppointmentMapper.fromMap("appointment-1",
                dataForMinutes(1438, 1439)).getEndMinute());
    }

    @Test
    public void requiresExactDhakaMidnightAndMatchingWeekday() {
        Map<String, Object> oneMinuteLate = data();
        oneMinuteLate.put("appointmentDate", new Timestamp(MIDNIGHT / 1000L + 60, 0));
        invalid(oneMinuteLate);

        Map<String, Object> oneSecondLate = data();
        oneSecondLate.put("appointmentDate", new Timestamp(MIDNIGHT / 1000L + 1, 0));
        invalid(oneSecondLate);

        Map<String, Object> fractional = data();
        fractional.put("appointmentDate", new Timestamp(MIDNIGHT / 1000L, 1));
        invalid(fractional);
    }

    @Test
    public void requiresExactStartAndEndInstants() {
        for (String field : new String[]{"startAt", "endAt"}) {
            Map<String, Object> data = data();
            Timestamp stored = (Timestamp) data.get(field);
            data.put(field, new Timestamp(stored.getSeconds() + 60, 0));
            invalid(data);

            data.put(field, new Timestamp(stored.getSeconds() - 60, 0));
            invalid(data);

            data.put(field, new Timestamp(stored.getSeconds(), 1));
            invalid(data);
        }
    }

    @Test
    public void requiresTimestampTypesForEveryTimestampField() {
        for (String field : new String[]{"appointmentDate", "startAt", "endAt",
                "createdAt", "updatedAt"}) {
            for (Object wrong : new Object[]{123L, "timestamp", new Date(MIDNIGHT), new Object()}) {
                Map<String, Object> data = data();
                data.put(field, wrong);
                invalid(data);
            }
        }
    }

    @Test
    public void enforcesCreatedAtAndUpdatedAtChronology() {
        Map<String, Object> reversed = data();
        reversed.put("updatedAt", new Timestamp(99, 0));
        invalid(reversed);

        Map<String, Object> equal = data();
        equal.put("updatedAt", equal.get("createdAt"));
        assertEquals(new Timestamp(100, 0),
                AppointmentMapper.fromMap("appointment-1", equal).getUpdatedAt());
    }

    @Test
    public void validatesPatientDoctorAndRelationshipText() {
        Map<String, Object> invalidName = data();
        invalidName.put("patientName", "   ");
        invalid(invalidName);

        Map<String, Object> longName = data();
        longName.put("patientName", "x".repeat(101));
        invalid(longName);

        Map<String, Object> invalidPhone = data();
        invalidPhone.put("patientPhone", "017-1234567");
        invalid(invalidPhone);

        Map<String, Object> blankDoctor = data();
        blankDoctor.put("doctorName", "   ");
        invalid(blankDoctor);

        Map<String, Object> controlledDoctor = data();
        controlledDoctor.put("doctorSpecialization", "Cardio\nlogy");
        invalid(controlledDoctor);

        Map<String, Object> longDoctor = data();
        longDoctor.put("doctorName", "x".repeat(101));
        invalid(longDoctor);

        Map<String, Object> validBoundaries = data();
        validBoundaries.put("patientName", "x".repeat(100));
        validBoundaries.put("patientPhone", "1234567");
        validBoundaries.put("doctorName", "x".repeat(100));
        validBoundaries.put("doctorSpecialization", "x".repeat(100));
        assertEquals("x".repeat(100), AppointmentMapper.fromMap("appointment-1",
                validBoundaries).getDoctorName());
    }

    @Test
    public void validatesPatientDoctorAndScheduleIdentifiers() {
        for (String field : new String[]{"patientUid", "doctorId", "scheduleId"}) {
            for (String value : new String[]{"", "bad/id", "bad id", "dot.", "x".repeat(129)}) {
                Map<String, Object> data = data();
                data.put(field, value);
                invalid(data);
            }
            Map<String, Object> maximum = data();
            maximum.put(field, "x".repeat(128));
            Appointment appointment = AppointmentMapper.fromMap("appointment-1", maximum);
            switch (field) {
                case "patientUid":
                    assertEquals("x".repeat(128), appointment.getPatientUid());
                    break;
                case "doctorId":
                    assertEquals("x".repeat(128), appointment.getDoctorId());
                    break;
                default:
                    assertEquals("x".repeat(128), appointment.getScheduleId());
                    break;
            }
        }
    }

    @Test
    public void rejectsMalformedDocumentIds() {
        for (String id : new String[]{null, "", "bad/id", "bad id", "dot.", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AppointmentMapper.fromMap(id, data()));
            assertFalse(AppointmentMapper.isValidId(id));
        }
        assertEquals("x".repeat(128), AppointmentMapper.fromMap("x".repeat(128), data()).getId());
    }

    @Test
    public void acceptsHistoricalAppointmentsWithoutComparingTheCurrentClock() {
        // 2020-01-15 is a Wednesday (ISO 3), long before the current date.
        long midnight = 1_579_024_800_000L;
        Map<String, Object> data = data();
        data.put("dayOfWeek", 3L);
        data.put("appointmentDate", timestamp(midnight));
        data.put("startMinute", 600L);
        data.put("endMinute", 630L);
        data.put("startAt", timestamp(midnight + 600L * 60_000L));
        data.put("endAt", timestamp(midnight + 630L * 60_000L));
        assertEquals(3, AppointmentMapper.fromMap("appointment-1", data).getDayOfWeek());
    }

    @Test
    public void mappingDoesNotMutateTheSourceMap() {
        Map<String, Object> data = data();
        Map<String, Object> before = new HashMap<>(data);
        AppointmentMapper.fromMap("appointment-1", data);
        assertEquals(before, data);
        assertEquals(17, data.size());
        assertFalse(data.containsKey("id"));
    }

    @Test
    public void nullSnapshotIsRejectedWithTheUniformExplicitFailure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AppointmentMapper.fromSnapshot((DocumentSnapshot) null));
        assertEquals("Invalid appointment data", error.getMessage());
    }

    @Test
    public void eachMappingProducesANewAppointmentSnapshot() {
        Map<String, Object> data = data();
        data.put("status", "Confirmed");
        Appointment first = AppointmentMapper.fromMap("appointment-1", data);
        Appointment second = AppointmentMapper.fromMap("appointment-1", data);
        assertNotSame(first, second);
        assertEquals(first.getAppointmentDate(), second.getAppointmentDate());
    }
}
