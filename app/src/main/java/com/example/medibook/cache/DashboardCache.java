package com.example.medibook.cache;

import com.example.medibook.models.Appointment;
import com.example.medibook.utils.NextAppointment;
import com.example.medibook.utils.WeeklyAppointmentStats;

/** Short-lived, in-process display cache. Never authorizes access or replaces live verification. */
public final class DashboardCache {
    public static final long MAX_AGE_MS = 120_000L;
    private static final DashboardCache INSTANCE = new DashboardCache();
    public static DashboardCache get() { return INSTANCE; }
    public static final class Next {
        public final Appointment appointment;
        private Next(Appointment appointment) { this.appointment = appointment; }
    }
    private String owner;
    private Next next;
    private WeeklyAppointmentStats analytics;
    private long nextAt, analyticsAt;

    public synchronized void bindOwner(String uid) {
        if (uid == null || uid.isEmpty()) { clear(); return; }
        if (!uid.equals(owner)) { clear(); owner = uid; }
    }
    public synchronized void clear() { owner = null; next = null; analytics = null; nextAt = analyticsAt = 0; }
    public synchronized void invalidateAppointments(String uid) {
        if (owns(uid)) { next = null; analytics = null; nextAt = analyticsAt = 0; }
    }
    private boolean owns(String uid) { return uid != null && uid.equals(owner); }
    private static boolean recent(long saved, long now) { return saved > 0 && now >= saved && now - saved < MAX_AGE_MS; }

    /** A known empty result is cached too; an expired appointment is not proof of an empty list. */
    public synchronized Next next(String uid, long now) {
        return owns(uid) && next != null && recent(nextAt, now)
                && (next.appointment == null || NextAppointment.eligible(uid, next.appointment, now)) ? next : null;
    }
    public synchronized void putNext(String uid, Appointment appointment, long now) {
        if (!owns(uid) || appointment != null && !uid.equals(appointment.getPatientUid())) return;
        next = new Next(appointment); nextAt = now;
    }
    public synchronized WeeklyAppointmentStats analytics(String uid, long now) {
        if (!owns(uid) || analytics == null || !recent(analyticsAt, now)
                || analytics.dayStarts[analytics.todayIndex] != WeeklyAppointmentStats.dayStart(now)) return null;
        return copy(analytics, now);
    }
    public synchronized void putAnalytics(String uid, WeeklyAppointmentStats stats, long now) {
        if (!owns(uid) || stats == null || stats.dayStarts[stats.todayIndex] != WeeklyAppointmentStats.dayStart(now)) return;
        analytics = copy(stats, now); analyticsAt = now;
    }
    private static WeeklyAppointmentStats copy(WeeklyAppointmentStats stats, long now) {
        return WeeklyAppointmentStats.fromCounts(now, stats.counts, stats.confirmedToday, stats.cancelledToday, stats.completedToday);
    }
}
