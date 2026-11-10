package com.example.medibook.repositories;

import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.utils.DoctorScheduleValidation;
import com.google.firebase.Timestamp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Six-field schema mapping. IDs are path identities; times are integer Asia/Dhaka minutes. */
public final class DoctorScheduleMapper {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("dayOfWeek", "startTime",
            "endTime", "slotDurationMinutes", "active", "updatedAt"));

    private DoctorScheduleMapper() { }

    public static DoctorSchedule fromMap(String id, Map<String, Object> data) {
        if (!DoctorScheduleValidation.validId(id) || data == null || !data.keySet().equals(FIELDS)
                || !(data.get("active") instanceof Boolean) || !(data.get("updatedAt") instanceof Timestamp)) {
            throw invalid();
        }
        int day = integer(data.get("dayOfWeek"));
        int start = integer(data.get("startTime"));
        int end = integer(data.get("endTime"));
        int slot = integer(data.get("slotDurationMinutes"));
        if (!DoctorScheduleValidation.validate(day, start, end, Integer.toString(slot)).isValid()) throw invalid();
        return new DoctorSchedule(id, day, start, end, slot, (Boolean) data.get("active"),
                (Timestamp) data.get("updatedAt"));
    }

    public static Map<String, Object> writeMap(DoctorSchedule draft, Object serverTimestamp) {
        if (!DoctorScheduleValidation.isValid(draft)) throw invalid();
        Map<String, Object> data = statusMap(draft.isActive(), serverTimestamp);
        data.put("dayOfWeek", (long) draft.getDayOfWeek());
        data.put("startTime", (long) draft.getStartMinute());
        data.put("endTime", (long) draft.getEndMinute());
        data.put("slotDurationMinutes", (long) draft.getSlotMinutes());
        return data;
    }

    public static Map<String, Object> statusMap(boolean active, Object serverTimestamp) {
        Map<String, Object> data = new HashMap<>();
        data.put("active", active);
        data.put("updatedAt", serverTimestamp);
        return data;
    }

    public static boolean sameVersion(DoctorSchedule first, DoctorSchedule second) {
        return first != null && second != null && first.getId().equals(second.getId())
                && first.getUpdatedAt() != null && first.getUpdatedAt().equals(second.getUpdatedAt());
    }

    private static int integer(Object value) {
        if (!(value instanceof Long) && !(value instanceof Integer)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw invalid();
        return (int) number;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid schedule data"); }
}
