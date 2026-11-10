package com.example.medibook.repositories;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.ReservationIdentity;
import com.google.firebase.Timestamp;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public final class InventorySlotMapper {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("scheduleId", "appointmentDate",
            "dayOfWeek", "startMinute", "endMinute", "startAt", "endAt", "status", "generation",
            "appointmentId", "createdAt", "updatedAt"));
    private InventorySlotMapper() { }

    public static InventorySlot fromMap(String doctorId, String dateKey, String slotId, Map<String, Object> data) {
        if (!DoctorMapper.isValidId(doctorId) || data == null || !data.keySet().equals(FIELDS)) throw invalid();
        String schedule = text(data, "scheduleId"), status = text(data, "status");
        Timestamp date = time(data, "appointmentDate"), start = time(data, "startAt"), end = time(data, "endAt");
        Timestamp created = time(data, "createdAt"), updated = time(data, "updatedAt");
        long weekday = number(data, "dayOfWeek"), first = number(data, "startMinute"), last = number(data, "endMinute");
        long generation = number(data, "generation");
        Object owner = data.get("appointmentId");
        if (!DoctorMapper.isValidId(schedule) || first < 0 || last <= first || last > 1439
                || generation < 0 || generation > 9_999_999_999L
                || date.getNanoseconds() != 0 || start.getNanoseconds() != 0 || end.getNanoseconds() != 0
                || updated.compareTo(created) < 0) throw invalid();
        Calendar day = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka"));
        day.setTime(date.toDate());
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, d = day.get(Calendar.DAY_OF_MONTH);
        long midnight = DhakaDateTime.localDateAtMidnight(y, m, d);
        if (date.toDate().getTime() != midnight || !Long.toString(midnight).equals(dateKey)
                || weekday != DhakaDateTime.isoDayOfWeek(y, m, d)
                || start.toDate().getTime() != midnight + first * 60000L
                || end.toDate().getTime() != midnight + last * 60000L
                || !ReservationIdentity.slotId(start.toDate().getTime(), end.toDate().getTime()).equals(slotId)) throw invalid();
        if ("booked".equals(status)) {
            if (!(owner instanceof String) || !DoctorMapper.isValidId((String) owner) || generation < 1) throw invalid();
        } else if ((!"available".equals(status) && !"blocked".equals(status)) || owner != null) throw invalid();
        return new InventorySlot(doctorId, slotId, schedule, date, (int) weekday, (int) first, (int) last,
                start, end, status, generation, (String) owner, created, updated);
    }

    public static Map<String, Object> publishMap(AppointmentSlot slot, long midnight, int weekday, Object serverTime) {
        if (slot == null || serverTime == null) throw invalid();
        Map<String, Object> map = new HashMap<>();
        map.put("scheduleId", slot.getScheduleId()); map.put("appointmentDate", new Timestamp(new Date(midnight)));
        map.put("dayOfWeek", weekday); map.put("startMinute", slot.getStartMinute()); map.put("endMinute", slot.getEndMinute());
        map.put("startAt", new Timestamp(new Date(slot.getStartAtMillis()))); map.put("endAt", new Timestamp(new Date(slot.getEndAtMillis())));
        map.put("status", "available"); map.put("generation", 0L); map.put("appointmentId", null);
        Timestamp placeholder = new Timestamp(0, 0);
        map.put("createdAt", placeholder); map.put("updatedAt", placeholder);
        fromMap("validation-doctor", Long.toString(midnight), ReservationIdentity.slotId(slot), map);
        map.put("createdAt", serverTime); map.put("updatedAt", serverTime);
        return map;
    }

    static long number(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof Long) && !(value instanceof Integer)) throw invalid();
        return ((Number) value).longValue();
    }
    static String text(Map<String, Object> data, String field) {
        if (data == null) throw invalid();
        Object value = data.get(field); if (!(value instanceof String)) throw invalid(); return (String) value;
    }
    static Timestamp time(Map<String, Object> data, String field) {
        Object value = data.get(field); if (!(value instanceof Timestamp)) throw invalid(); return (Timestamp) value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid inventory slot"); }
}
