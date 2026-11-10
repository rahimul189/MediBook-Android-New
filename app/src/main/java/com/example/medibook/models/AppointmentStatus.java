package com.example.medibook.models;

/**
 * Persisted appointment lifecycle status. Wire values are the exact strings stored in Firestore;
 * parsing is deliberately case-sensitive.
 *
 * <p>A successful booking is created directly as {@link #CONFIRMED}; there is no Pending state and
 * no Admin approval step. Completion is never persisted: it is a time-derived display state
 * supplied by {@code com.example.medibook.utils.AppointmentDisplayStatus}. This enum therefore has
 * no COMPLETED value, and no transition may target one.</p>
 *
 * <p>The transition matrix in this class is purely status-based. Time-dependent checks such as
 * "a Patient may cancel only before the slot starts" and "an Admin may cancel only an eligible
 * active appointment" belong to the repository and Firestore Rules.</p>
 */
public enum AppointmentStatus {
    CONFIRMED("Confirmed"),
    CANCELLED("Cancelled");

    private final String wireValue;

    AppointmentStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    /** Status assigned to every newly booked appointment; no confirmation step is required. */
    public static AppointmentStatus initial() {
        return CONFIRMED;
    }

    /** Exact, case-sensitive parsing. Missing, obsolete, unknown or malformed values are rejected. */
    public static AppointmentStatus fromWire(String wireValue) {
        if (wireValue == null) {
            throw new IllegalArgumentException("Appointment status is required");
        }
        for (AppointmentStatus status : values()) {
            if (status.wireValue.equals(wireValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid appointment status");
    }

    /** Cancelled is terminal and may never change again. Confirmed stays active until cancelled. */
    public boolean isTerminal() {
        return this == CANCELLED;
    }

    /**
     * Returns whether this status may move to {@code target} for the supplied role. Both roles may
     * cancel a Confirmed appointment; same-status and null inputs are never allowed. No transition
     * may produce a completed state because completion is display-derived. Time restrictions are
     * intentionally excluded and are enforced by the repository and Firestore Rules.
     */
    public boolean canTransitionTo(UserProfile.Role role, AppointmentStatus target) {
        if (role == null || target == null || this == target) {
            return false;
        }
        if (role != UserProfile.Role.PATIENT && role != UserProfile.Role.ADMIN) {
            return false;
        }
        return this == CONFIRMED && target == CANCELLED;
    }
}
