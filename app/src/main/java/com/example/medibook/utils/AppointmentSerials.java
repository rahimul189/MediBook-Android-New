package com.example.medibook.utils;

import com.example.medibook.models.Appointment;

/** Presentation-only reconciliation for immutable Firestore slot-number sidecars. */
public final class AppointmentSerials {
    private AppointmentSerials() { }

    /** A serial can only be carried across snapshots of this exact appointment and inventory slot. */
    public static boolean sameSlot(Appointment left, Appointment right) {
        return left != null && right != null && left.getId().equals(right.getId())
                && left.getPatientUid().equals(right.getPatientUid())
                && left.getDoctorId().equals(right.getDoctorId())
                && left.getAppointmentDate().equals(right.getAppointmentDate())
                && left.getStartAt().equals(right.getStartAt()) && left.getEndAt().equals(right.getEndAt());
    }

    /** Keep only the known number, never an older status, schedule, or patient snapshot. */
    public static Appointment retain(Appointment fresh, Appointment previous) {
        if (sameSlot(fresh, previous) && !SlotSerials.isAssigned(fresh.getSlotSerial())
                && SlotSerials.isAssigned(previous.getSlotSerial())) {
            return fresh.withSlotSerial(previous.getSlotSerial());
        }
        return fresh;
    }

    /** Late lookups cannot replace a newer request/snapshot or downgrade an assigned fixed number. */
    public static Appointment applyLookup(Appointment current, Appointment requested, Appointment resolved,
                                          int request, int activeRequest) {
        if (request != activeRequest || !sameSlot(current, requested) || !sameSlot(requested, resolved)
                || SlotSerials.isAssigned(current.getSlotSerial())
                || !SlotSerials.isAssigned(resolved.getSlotSerial())) return current;
        return current.withSlotSerial(resolved.getSlotSerial());
    }
}
