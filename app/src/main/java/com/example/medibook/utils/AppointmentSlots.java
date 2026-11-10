package com.example.medibook.utils;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.DoctorSchedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates schedule-derived slots for one Dhaka date.
 *
 * <p>Only active schedules matching the date's ISO weekday contribute. Slots start at each
 * schedule's start minute and advance by its slot duration; a trailing interval shorter than the
 * duration is omitted. Slots starting at or before the reference instant are excluded. Exact
 * duplicates (same start and end minute) collapse to the first slot in deterministic schedule
 * order (start, end, duration, id), so schedule ownership is stable. Overlapping slots of
 * different lengths are all retained. Results are sorted by start minute, end minute, and
 * owning schedule id.</p>
 *
 * <p>Existing appointment occupancy is not queried, so a returned slot is available by schedule
 * only, not guaranteed to be unbooked.</p>
 */
public final class AppointmentSlots {
    private AppointmentSlots() { }

    /**
     * Returns future schedule-derived slots for the selected Dhaka date, sorted deterministically.
     * Invalid dates and invalid schedules are rejected rather than silently skipped.
     */
    public static List<AppointmentSlot> generate(List<DoctorSchedule> schedules,
                                                 int year, int month, int day,
                                                 long referenceNowMillis) {
        if (schedules == null) throw new IllegalArgumentException("Missing schedules");
        if (!DhakaDateTime.isValidDate(year, month, day)) {
            throw new IllegalArgumentException("Invalid Dhaka date");
        }
        int selectedDay = DhakaDateTime.isoDayOfWeek(year, month, day);
        List<DoctorSchedule> ordered = new ArrayList<>();
        for (DoctorSchedule schedule : schedules) {
            if (!DoctorScheduleValidation.isValid(schedule)) {
                throw new IllegalArgumentException("Invalid schedule");
            }
            if (schedule.isActive() && schedule.getDayOfWeek() == selectedDay) {
                ordered.add(schedule);
            }
        }
        ordered.sort(Comparator.comparingInt(DoctorSchedule::getStartMinute)
                .thenComparingInt(DoctorSchedule::getEndMinute)
                .thenComparingInt(DoctorSchedule::getSlotMinutes)
                .thenComparing(DoctorSchedule::getId));

        List<AppointmentSlot> result = new ArrayList<>();
        Set<String> exactSlots = new HashSet<>();
        for (DoctorSchedule schedule : ordered) {
            int duration = schedule.getSlotMinutes();
            for (int start = schedule.getStartMinute();
                 start + duration <= schedule.getEndMinute(); start += duration) {
                int end = start + duration;
                long startAt = DhakaDateTime.toEpochMillis(year, month, day, start);
                long endAt = DhakaDateTime.toEpochMillis(year, month, day, end);
                if (startAt <= referenceNowMillis) continue;
                // Keep the first slot in the deterministic schedule order for exact duplicates.
                if (!exactSlots.add(start + ":" + end)) continue;
                result.add(new AppointmentSlot(schedule.getId(), start, end, startAt, endAt));
            }
        }
        result.sort(Comparator.comparingInt(AppointmentSlot::getStartMinute)
                .thenComparingInt(AppointmentSlot::getEndMinute)
                .thenComparing(AppointmentSlot::getScheduleId));
        return Collections.unmodifiableList(result);
    }
}
