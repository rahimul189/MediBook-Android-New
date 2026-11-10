package com.example.medibook.utils;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.DoctorSchedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic planner for the Admin-published, pairwise non-overlapping slot inventory.
 *
 * <p>{@link AppointmentSlots#generate} deliberately retains overlapping intervals from overlapping
 * schedules. Firestore Rules cannot query or iterate sibling slot documents, so the non-overlap
 * invariant must be established before publication. This helper sorts candidates by start minute,
 * end minute and schedule ID, then greedily accepts a candidate only when its half-open interval
 * does not overlap an already accepted or reserved interval. Exact duplicates therefore collapse
 * to the first candidate in that deterministic order, and adjacent intervals remain valid.</p>
 *
 * <p>All candidates and reserved intervals must belong to one doctor and one Dhaka date. The
 * publisher must pass every existing slot interval for that date as reserved when adding to an
 * already-published day; otherwise a new slot could overlap an existing booking. Rules still
 * cannot prove that an Admin publisher followed this invariant; see the Step 88 design.</p>
 */
public final class SlotInventory {
    private SlotInventory() { }

    /** Generates a non-overlapping inventory for one Dhaka date. */
    public static List<AppointmentSlot> generate(List<DoctorSchedule> schedules,
                                                 int year, int month, int day,
                                                 long referenceNowMillis) {
        return nonOverlapping(
                AppointmentSlots.generate(schedules, year, month, day, referenceNowMillis),
                Collections.emptyList());
    }

    /** Generates candidates while excluding every interval already present for the date. */
    public static List<AppointmentSlot> generate(List<DoctorSchedule> schedules,
                                                 int year, int month, int day,
                                                 long referenceNowMillis,
                                                 List<AppointmentSlot> reserved) {
        return nonOverlapping(
                AppointmentSlots.generate(schedules, year, month, day, referenceNowMillis),
                reserved);
    }

    /** Returns a deterministic, unmodifiable pairwise non-overlapping subset of the candidates. */
    public static List<AppointmentSlot> nonOverlapping(List<AppointmentSlot> candidates) {
        return nonOverlapping(candidates, Collections.emptyList());
    }

    /**
     * Returns a deterministic, unmodifiable pairwise non-overlapping subset of the candidates,
     * additionally excluding candidates that overlap any already-published or booked interval.
     */
    public static List<AppointmentSlot> nonOverlapping(List<AppointmentSlot> candidates,
                                                       List<AppointmentSlot> reserved) {
        if (candidates == null) throw new IllegalArgumentException("Missing slots");
        if (reserved == null) throw new IllegalArgumentException("Missing reserved slots");

        List<AppointmentSlot> ordered = new ArrayList<>(candidates.size());
        for (AppointmentSlot candidate : candidates) {
            if (candidate == null) throw new IllegalArgumentException("Invalid slot");
            ordered.add(candidate);
        }
        List<AppointmentSlot> reservedCopy = new ArrayList<>(reserved.size());
        for (AppointmentSlot interval : reserved) {
            if (interval == null) throw new IllegalArgumentException("Invalid reserved slot");
            reservedCopy.add(interval);
        }
        ordered.sort(Comparator.comparingInt(AppointmentSlot::getStartMinute)
                .thenComparingInt(AppointmentSlot::getEndMinute)
                .thenComparing(AppointmentSlot::getScheduleId));

        List<AppointmentSlot> accepted = new ArrayList<>();
        for (AppointmentSlot candidate : ordered) {
            if (overlapsAny(candidate, reservedCopy) || overlapsAny(candidate, accepted)) {
                continue;
            }
            accepted.add(candidate);
        }
        return Collections.unmodifiableList(accepted);
    }

    private static boolean overlapsAny(AppointmentSlot candidate, List<AppointmentSlot> intervals) {
        for (AppointmentSlot interval : intervals) {
            if (ReservationIdentity.overlaps(
                    candidate.getStartAtMillis(), candidate.getEndAtMillis(),
                    interval.getStartAtMillis(), interval.getEndAtMillis())) {
                return true;
            }
        }
        return false;
    }
}
