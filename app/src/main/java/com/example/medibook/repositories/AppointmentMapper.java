package com.example.medibook.repositories;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.utils.AppointmentValidation;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Strict, offline-testable mapping for the 17-field appointment schema. The document ID is a path
 * identity and is never read from or written into the document body.
 *
 * <p>All malformed data fails with the same explicit {@link IllegalArgumentException} used by the
 * other repository mappers. No missing identifier, status, date, fee, or timestamp is inferred.</p>
 */
public final class AppointmentMapper {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "patientUid", "patientName", "patientPhone",
            "doctorId", "doctorName", "doctorSpecialization", "consultationFee",
            "scheduleId", "dayOfWeek", "appointmentDate", "startMinute", "endMinute",
            "startAt", "endAt", "status", "createdAt", "updatedAt"));

    private AppointmentMapper() { }

    public static boolean isValidId(String id) {
        return AppointmentValidation.validId(id);
    }

    /**
     * Maps an existing Firestore snapshot. Missing documents, null snapshots, absent data, and
     * malformed fields are rejected instead of producing a partial appointment.
     */
    public static Appointment fromSnapshot(DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) throw invalid();
        Map<String, Object> data = snapshot.getData();
        if (data == null) throw invalid();
        return fromMap(snapshot.getId(), data);
    }

    /** Core mapping used by tests and snapshots; the map must contain exactly the 17 fields. */
    public static Appointment fromMap(String id, Map<String, Object> data) {
        if (!AppointmentValidation.validId(id) || data == null || !data.keySet().equals(FIELDS)
                || !(data.get("patientUid") instanceof String)
                || !(data.get("patientName") instanceof String)
                || !(data.get("patientPhone") instanceof String)
                || !(data.get("doctorId") instanceof String)
                || !(data.get("doctorName") instanceof String)
                || !(data.get("doctorSpecialization") instanceof String)
                || !(data.get("scheduleId") instanceof String)
                || !(data.get("appointmentDate") instanceof Timestamp)
                || !(data.get("startAt") instanceof Timestamp)
                || !(data.get("endAt") instanceof Timestamp)
                || !(data.get("status") instanceof String)
                || !(data.get("createdAt") instanceof Timestamp)
                || !(data.get("updatedAt") instanceof Timestamp)) {
            throw invalid();
        }

        Appointment appointment = new Appointment(
                id,
                (String) data.get("patientUid"),
                (String) data.get("patientName"),
                (String) data.get("patientPhone"),
                (String) data.get("doctorId"),
                (String) data.get("doctorName"),
                (String) data.get("doctorSpecialization"),
                fee(data.get("consultationFee")),
                (String) data.get("scheduleId"),
                integer(data.get("dayOfWeek")),
                (Timestamp) data.get("appointmentDate"),
                integer(data.get("startMinute")),
                integer(data.get("endMinute")),
                (Timestamp) data.get("startAt"),
                (Timestamp) data.get("endAt"),
                status((String) data.get("status")),
                (Timestamp) data.get("createdAt"),
                (Timestamp) data.get("updatedAt"));
        if (!AppointmentValidation.isValid(appointment)) throw invalid();
        return appointment;
    }

    /** Firestore returns Long for integers; Integer is accepted so tests can use either exactly. */
    private static int integer(Object value) {
        if (!(value instanceof Long) && !(value instanceof Integer)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw invalid();
        return (int) number;
    }

    private static long fee(Object value) {
        if (!(value instanceof Long) && !(value instanceof Integer)) throw invalid();
        long hundredths = ((Number) value).longValue();
        if (hundredths < 0
                || hundredths > AppointmentValidation.MAX_CONSULTATION_FEE_HUNDREDTHS) {
            throw invalid();
        }
        return hundredths;
    }

    private static AppointmentStatus status(String value) {
        try {
            return AppointmentStatus.fromWire(value);
        } catch (IllegalArgumentException invalidStatus) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid appointment data");
    }
}
