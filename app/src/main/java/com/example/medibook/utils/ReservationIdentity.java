package com.example.medibook.utils;

import com.example.medibook.models.AppointmentSlot;

/**
 * Deterministic reservation identities and half-open interval rules for the Admin-published slot
 * inventory. This class has no Android/Firestore network dependency.
 *
 * <p>A slot document ID is derived from the exact UTC instants of its half-open interval. Every
 * successful booking of that slot creates an immutable reservation lock at the deterministic
 * generation path {@code .../slots/<slotId>/bookings/g<generation>}. The appointment itself keeps
 * a normal client-generated document ID; the lock stores that ID and is the uniqueness anchor.
 * The same formulas are intended to be reproduced in Firestore Rules with
 * {@code string(timestamp.toMillis())}; see the Step 88 reservation design.</p>
 *
 * <p>The Dhaka date key is the decimal epoch-millisecond value of local midnight. It is used as a
 * Firestore path segment so Rules can locate a date's slots, bookings and block document from the
 * appointment's stored {@code appointmentDate} without adding a schema field.</p>
 */
public final class ReservationIdentity {
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,128}";
    private static final String DATE_KEY_PATTERN = "-?[0-9]{1,16}";
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private ReservationIdentity() { }

    /** Slot document ID: {@code s<startAtMillis>-<endAtMillis>}. */
    public static String slotId(long startAtMillis, long endAtMillis) {
        if (startAtMillis < 0
                || endAtMillis <= startAtMillis
                || startAtMillis % MILLIS_PER_MINUTE != 0
                || endAtMillis % MILLIS_PER_MINUTE != 0) {
            throw new IllegalArgumentException("Invalid slot instants");
        }
        return "s" + startAtMillis + "-" + endAtMillis;
    }

    /** Convenience overload using an already-validated generated slot. */
    public static String slotId(AppointmentSlot slot) {
        if (slot == null) throw new IllegalArgumentException("Missing slot");
        return slotId(slot.getStartAtMillis(), slot.getEndAtMillis());
    }

    /**
     * Dhaka date path key: the decimal epoch-millisecond value of local midnight. Invalid dates are
     * rejected by {@link DhakaDateTime#localDateAtMidnight(int, int, int)}.
     */
    public static String dateKey(int year, int month, int day) {
        return Long.toString(DhakaDateTime.localDateAtMidnight(year, month, day));
    }

    /**
     * Immutable reservation-lock document ID for a slot generation:
     * {@code g<generation>}. Generation is one-based because the first booking moves a slot from
     * generation zero to generation one.
     */
    public static String bookingLockId(int generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("Invalid booking generation");
        }
        return "g" + generation;
    }

    /**
     * Logical path-independent key for one doctor/date/slot/generation reservation. Firestore
     * scopes the same slot ID by doctor and date path, so different doctors or dates never share
     * this key. Different generations are different locks for the same slot.
     */
    public static String reservationKey(String doctorId, String dateKey, String slotId,
                                        int generation) {
        if (doctorId == null || !doctorId.matches(ID_PATTERN)
                || dateKey == null || !dateKey.matches(DATE_KEY_PATTERN)
                || slotId == null || !slotId.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid reservation key");
        }
        return doctorId + "/" + dateKey + "/" + slotId + "/" + bookingLockId(generation);
    }

    /** Half-open overlap: {@code [startA, endA)} intersects {@code [startB, endB)}. */
    public static boolean overlaps(long startA, long endA, long startB, long endB) {
        if (startA >= endA || startB >= endB) {
            throw new IllegalArgumentException("Invalid interval");
        }
        return startA < endB && startB < endA;
    }

    /** Cancellation is allowed only strictly before the exact appointment start instant. */
    public static boolean isCancellable(long startAtMillis, long referenceNowMillis) {
        return startAtMillis > referenceNowMillis;
    }
}
