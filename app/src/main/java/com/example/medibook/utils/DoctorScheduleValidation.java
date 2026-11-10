package com.example.medibook.utils;

import com.example.medibook.models.DoctorSchedule;

/** Deterministic form checks, also used by mapping; Firestore Rules independently enforce the schema. */
public final class DoctorScheduleValidation {
    public enum Error { NONE, DAY, START, END, ORDER, SLOT_REQUIRED, SLOT_INVALID, SLOT_WINDOW }

    public static final class Result {
        public final Error dayError;
        public final Error startError;
        public final Error endError;
        public final Error slotError;
        private final int day;
        private final int start;
        private final int end;
        private final int slot;

        private Result(int day, int start, int end, String slotText) {
            this.day = day;
            this.start = start;
            this.end = end;
            dayError = day >= 1 && day <= 7 ? Error.NONE : Error.DAY;
            startError = validTime(start) ? Error.NONE : Error.START;
            endError = !validTime(end) ? Error.END
                    : validTime(start) && end <= start ? Error.ORDER : Error.NONE;
            String value = slotText == null ? "" : slotText.trim();
            slot = value.matches("[0-9]{1,3}") ? Integer.parseInt(value) : -1;
            slotError = value.isEmpty() ? Error.SLOT_REQUIRED
                    : slot < 5 || slot > 180 || slot % 5 != 0 ? Error.SLOT_INVALID
                    : startError == Error.NONE && endError == Error.NONE && slot > end - start
                    ? Error.SLOT_WINDOW : Error.NONE;
        }

        public boolean isValid() {
            return dayError == Error.NONE && startError == Error.NONE
                    && endError == Error.NONE && slotError == Error.NONE;
        }

        public DoctorSchedule toSchedule(String id, boolean active) {
            if (!isValid() || !validId(id)) throw new IllegalStateException("Invalid schedule form");
            return new DoctorSchedule(id, day, start, end, slot, active);
        }
    }

    private DoctorScheduleValidation() { }

    public static Result validate(int day, int startMinute, int endMinute, String slotMinutes) {
        return new Result(day, startMinute, endMinute, slotMinutes);
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}");
    }

    public static boolean isValid(DoctorSchedule schedule) {
        return schedule != null && validId(schedule.getId())
                && validate(schedule.getDayOfWeek(), schedule.getStartMinute(), schedule.getEndMinute(),
                Integer.toString(schedule.getSlotMinutes())).isValid();
    }

    private static boolean validTime(int minute) { return minute >= 0 && minute < 24 * 60; }
}
