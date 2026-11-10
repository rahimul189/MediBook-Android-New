package com.example.medibook.utils;

import com.example.medibook.models.DoctorSchedule;

/** Checks half-open windows [start, end), regardless of slot duration. Not a concurrency guarantee. */
public final class DoctorScheduleConflicts {
    private DoctorScheduleConflicts() { }

    public static boolean overlaps(DoctorSchedule candidate, Iterable<DoctorSchedule> existing) {
        if (!DoctorScheduleValidation.isValid(candidate)) throw new IllegalArgumentException("Invalid schedule");
        if (!candidate.isActive()) return false;
        for (DoctorSchedule other : existing) {
            if (!DoctorScheduleValidation.isValid(other)) throw new IllegalArgumentException("Invalid schedule");
            if (!other.getId().equals(candidate.getId()) && other.isActive()
                    && other.getDayOfWeek() == candidate.getDayOfWeek()
                    && candidate.getStartMinute() < other.getEndMinute()
                    && candidate.getEndMinute() > other.getStartMinute()) return true;
        }
        return false;
    }
}
