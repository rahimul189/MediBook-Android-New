package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.google.firebase.Timestamp;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Pure validation for the 17-field appointment schema. This class has no Android or Firebase
 * network dependency and performs no clock-based checks, so historical appointments remain valid.
 *
 * <p>Authorization, duplicate-booking checks, future-slot policy, and doctor/schedule active-state
 * checks deliberately belong to the repository and Firestore Rules. The timezone arithmetic here
 * mirrors the Dhaka calendar semantics that those later layers must enforce.</p>
 *
 * <p>An appointment is a same-day booking: {@code startMinute} and {@code endMinute} are Dhaka
 * wall-clock minutes after midnight, {@code endMinute} must not pass the final minute of that
 * day, and both stored instants must be exact minute boundaries with zero nanoseconds.</p>
 */
public final class AppointmentValidation {
    /** Maximum consultation fee in integer hundredths (999,999.99 in major units). */
    public static final long MAX_CONSULTATION_FEE_HUNDREDTHS = 99_999_999L;

    /** Longest patient name and doctor text fields in the reviewed schema. */
    public static final int MAX_TEXT_LENGTH = 100;

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,128}";

    private AppointmentValidation() { }

    /** Path/document identifiers use the same bounded pattern as the existing schema. */
    public static boolean validId(String id) {
        return id != null && id.matches(ID_PATTERN);
    }

    /** Validates every stored field plus the separate document identity. */
    public static boolean isValid(Appointment appointment) {
        if (appointment == null
                || !validId(appointment.getId())
                || !validId(appointment.getPatientUid())
                || !PatientProfileValidation.isValidName(appointment.getPatientName())
                || !PatientProfileValidation.isValidPhone(appointment.getPatientPhone())
                || !validId(appointment.getDoctorId())
                || !validDoctorText(appointment.getDoctorName())
                || !validDoctorText(appointment.getDoctorSpecialization())
                || !validFee(appointment.getConsultationFee())
                || !validId(appointment.getScheduleId())
                || appointment.getDayOfWeek() < 1 || appointment.getDayOfWeek() > 7
                || appointment.getStartMinute() < 0
                || appointment.getStartMinute() >= MINUTES_PER_DAY
                || appointment.getEndMinute() <= appointment.getStartMinute()
                || appointment.getEndMinute() >= MINUTES_PER_DAY
                || appointment.getStatus() == null
                || appointment.getAppointmentDate() == null
                || appointment.getStartAt() == null
                || appointment.getEndAt() == null
                || appointment.getCreatedAt() == null
                || appointment.getUpdatedAt() == null) {
            return false;
        }

        Timestamp appointmentDate = appointment.getAppointmentDate();
        if (!isExactMinute(appointmentDate)) return false;
        int[] date = dhakaDate(appointmentDate);
        long midnight = DhakaDateTime.localDateAtMidnight(date[0], date[1], date[2]);
        if (timestampMillis(appointmentDate) != midnight) return false;
        if (appointment.getDayOfWeek() != DhakaDateTime.isoDayOfWeek(date[0], date[1], date[2])) {
            return false;
        }

        long expectedStart = midnight + appointment.getStartMinute() * MILLIS_PER_MINUTE;
        long expectedEnd = midnight + appointment.getEndMinute() * MILLIS_PER_MINUTE;
        if (expectedEnd <= expectedStart) return false;
        if (!isExactMinute(appointment.getStartAt())
                || timestampMillis(appointment.getStartAt()) != expectedStart) {
            return false;
        }
        if (!isExactMinute(appointment.getEndAt())
                || timestampMillis(appointment.getEndAt()) != expectedEnd) {
            return false;
        }
        return appointment.getUpdatedAt().compareTo(appointment.getCreatedAt()) >= 0;
    }

    /** Throws the same explicit failure used by repository mappers when data is invalid. */
    public static void requireValid(Appointment appointment) {
        if (!isValid(appointment)) {
            throw new IllegalArgumentException("Invalid appointment data");
        }
    }

    private static boolean validFee(long hundredths) {
        return hundredths >= 0 && hundredths <= MAX_CONSULTATION_FEE_HUNDREDTHS;
    }

    /**
     * Mirrors the stored Doctor text rules: nonblank single-line text with no ISO control or
     * Unicode line/paragraph separators. Doctor names and specializations are snapshots of values
     * that the Doctor feature already validated and Rules already constrain.
     */
    private static boolean validDoctorText(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXT_LENGTH) return false;
        boolean hasNonSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    || character == '\u2028' || character == '\u2029') {
                return false;
            }
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                hasNonSpace = true;
            }
        }
        return hasNonSpace;
    }

    /** True only for a whole-second timestamp at a zero-second, exact-minute boundary. */
    private static boolean isExactMinute(Timestamp timestamp) {
        return timestamp.getNanoseconds() == 0 && timestamp.getSeconds() % 60 == 0;
    }

    private static long timestampMillis(Timestamp timestamp) {
        return timestamp.getSeconds() * 1000L;
    }

    /** Extracts the Dhaka calendar date from an instant without using the device timezone. */
    private static int[] dhakaDate(Timestamp timestamp) {
        Calendar calendar = Calendar.getInstance(
                TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        calendar.setTimeInMillis(timestampMillis(timestamp));
        return new int[]{
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
        };
    }
}
