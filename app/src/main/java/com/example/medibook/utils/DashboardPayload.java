package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.repositories.DashboardState;
import java.util.HashMap;
import java.util.Map;

/** Compact read models contain no patient names, phones, doctor biographies or medical data. */
public final class DashboardPayload {
    public static final long FRESH_MS = 120_000L;
    private DashboardPayload() { }
    public static Map<String, Object> next(Appointment value) {
        Map<String, Object> result = new HashMap<>();
        result.put("appointmentId", value == null ? "" : value.getId());
        result.put("startAt", value == null ? 0L : value.getStartAt().toDate().getTime());
        result.put("endAt", value == null ? 0L : value.getEndAt().toDate().getTime());
        return result;
    }
    public static Map<String, Object> analytics(WeeklyAppointmentStats stats) {
        Map<String, Object> result = new HashMap<>(), days = new HashMap<>();
        result.put("weekStart", stats.dayStarts[0]); result.put("todayStart", stats.dayStarts[stats.todayIndex]);
        for (int i = 0; i < 7; i++) days.put("d" + i, (long) stats.counts[i]);
        result.put("days", days); result.put("todayTotal", (long) stats.todayTotal); result.put("weekTotal", (long) stats.weekTotal);
        result.put("confirmedToday", (long) stats.confirmedToday); result.put("cancelledToday", (long) stats.cancelledToday);
        result.put("completedToday", (long) stats.completedToday); return result;
    }
    public static boolean fresh(Map<String, Object> summary, long now) {
        if (summary == null || !(summary.get("checkedAt") instanceof Long)) return false;
        long time = (Long) summary.get("checkedAt");
        return time > 0 && time <= now + 60_000L && now - time <= FRESH_MS;
    }
    public static boolean matches(Map<String, Object> expected, Map<String, Object> summary) {
        if (expected == null || summary == null || summary.size() != expected.size() + 2
                || !(summary.get("revision") instanceof Long) || (Long) summary.get("revision") < 1) return false;
        Map<String, Object> actual = new HashMap<>(summary); actual.remove("revision"); actual.remove("checkedAt");
        return expected.equals(actual);
    }
    public static DashboardState state(boolean cached, boolean failed, boolean connected,
                                       Map<String, Object> truth, Map<String, Object> summary, long now) {
        if (truth == null) return failed ? DashboardState.ERROR : DashboardState.LOADING;
        if (cached || failed) return DashboardState.CACHED;
        return connected && fresh(summary, now) && matches(truth, summary) ? DashboardState.LIVE : DashboardState.FIRESTORE;
    }
    public static WeeklyAppointmentStats stats(Map<String, Object> summary, long now) {
        if (number(summary, "weekStart") != WeeklyAppointmentStats.weekStart(now)
                || number(summary, "todayStart") != WeeklyAppointmentStats.dayStart(now)) throw new IllegalArgumentException("Wrong period");
        Object raw = summary.get("days"); if (!(raw instanceof Map) || ((Map<?, ?>) raw).size() != 7) throw new IllegalArgumentException("Invalid days");
        int[] counts = new int[7];
        for (int i = 0; i < 7; i++) counts[i] = count(((Map<?, ?>) raw).get("d" + i));
        WeeklyAppointmentStats stats = WeeklyAppointmentStats.fromCounts(now, counts, count(summary.get("confirmedToday")),
                count(summary.get("cancelledToday")), count(summary.get("completedToday")));
        if (stats.todayTotal != number(summary, "todayTotal") || stats.weekTotal != number(summary, "weekTotal")) throw new IllegalArgumentException("Inconsistent totals");
        return stats;
    }
    private static int count(Object raw) {
        if (!(raw instanceof Long) || (Long) raw < 0 || (Long) raw > 1_000_000L) throw new IllegalArgumentException("Invalid count");
        return ((Long) raw).intValue();
    }
    private static long number(Map<String, Object> values, String key) {
        Object value = values.get(key); if (!(value instanceof Long)) throw new IllegalArgumentException("Invalid timestamp");
        return (Long) value;
    }
}
