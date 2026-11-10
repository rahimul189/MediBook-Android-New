package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;

/**
 * Patient/Admin-facing appointment state derived from the persisted status and the scheduled end.
 *
 * <p>Completion is never stored. A persisted Confirmed appointment is displayed as Completed once
 * its exact {@code endAt} instant is reached; at that boundary the booked interval is over. A
 * Cancelled appointment stays Cancelled at every instant, including after {@code endAt}, and is
 * never converted to Completed. Because {@code endAt} is an absolute UTC instant, the comparison
 * needs no device-timezone or Dhaka-calendar conversion.</p>
 */
public enum AppointmentDisplayStatus {
    CONFIRMED,
    COMPLETED,
    CANCELLED;

    /**
     * Derives the display state for a persisted status and end instant. The boundary instant is
     * inclusive: {@code nowMillis >= endAtMillis} means the slot has ended.
     */
    public static AppointmentDisplayStatus of(AppointmentStatus persistedStatus,
                                              long endAtMillis, long nowMillis) {
        if (persistedStatus == null) {
            throw new IllegalArgumentException("Appointment status is required");
        }
        if (persistedStatus == AppointmentStatus.CANCELLED) {
            return CANCELLED;
        }
        return nowMillis >= endAtMillis ? COMPLETED : CONFIRMED;
    }

    /** Convenience overload using the appointment's stored {@code endAt} instant. */
    public static AppointmentDisplayStatus of(Appointment appointment, long nowMillis) {
        if (appointment == null) {
            throw new IllegalArgumentException("Appointment is required");
        }
        long endAtMillis = appointment.getEndAt().getSeconds() * 1000L
                + appointment.getEndAt().getNanoseconds() / 1_000_000L;
        return of(appointment.getStatus(), endAtMillis, nowMillis);
    }
}
