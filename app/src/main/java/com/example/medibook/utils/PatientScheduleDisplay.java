package com.example.medibook.utils;

import com.example.medibook.models.DoctorSchedule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Weekly wall-clock values: no device timezone conversion or appointment availability inference. */
public final class PatientScheduleDisplay {
    private PatientScheduleDisplay() { }
    public static List<DoctorSchedule> sorted(List<DoctorSchedule> source) {
        List<DoctorSchedule> result = new ArrayList<>();
        for (DoctorSchedule value : source) {
            if (!DoctorScheduleValidation.isValid(value)) throw new IllegalArgumentException("Invalid schedule");
            if (value.isActive()) result.add(value);
        }
        result.sort(Comparator.comparingInt(DoctorSchedule::getDayOfWeek)
                .thenComparingInt(DoctorSchedule::getStartMinute).thenComparingInt(DoctorSchedule::getEndMinute)
                .thenComparing(DoctorSchedule::getId));
        return Collections.unmodifiableList(result);
    }
    public static String time(int minute) {
        if (minute < 0 || minute > 1439) throw new IllegalArgumentException("Invalid minute");
        int hour = minute / 60;
        return String.format(Locale.US, "%d:%02d %s", hour % 12 == 0 ? 12 : hour % 12, minute % 60, hour < 12 ? "AM" : "PM");
    }
}
