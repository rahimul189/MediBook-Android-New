package com.example.medibook.reminders;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;

/** Pure UTC-instant calculations; no device-timezone or midnight arithmetic. */
public final class ReminderPolicy {
    public static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    public static final String SCHEDULED = "scheduled", SENT = "sent", CANCELLED = "cancelled";
    private ReminderPolicy() { }

    public static boolean eligible(String uid, Appointment appointment, long now) {
        return uid != null && appointment != null && uid.equals(appointment.getPatientUid())
                && appointment.getStatus() == AppointmentStatus.CONFIRMED && appointment.getStartAt().toDate().getTime() > now;
    }

    /** 24h before; otherwise 1h before, or halfway through the remaining short interval.
     * Never schedule a new reminder with <20m remaining, or <5m from now. */
    public static long dueAt(long start, long now) {
        if (now < 0 || start <= now || start - now < 20 * MINUTE) return -1;
        long due = start - DAY;
        if (due < now + 5 * MINUTE) due = start - HOUR;
        if (due < now + 5 * MINUTE) due = now + (start - now) / 2;
        return due >= now + 5 * MINUTE && due <= start - 5 * MINUTE ? due : -1;
    }

    public static final class Record {
        public final long start, due;
        public final String state;
        public Record(long start, long due, String state) { this.start = start; this.due = due; this.state = state; }
        public String encode() { return start + ":" + due + ":" + state; }
        public static Record decode(String raw) {
            if (raw == null) return null;
            try {
                String[] parts = raw.split(":");
                if (parts.length != 3 || !(SCHEDULED.equals(parts[2]) || SENT.equals(parts[2]) || CANCELLED.equals(parts[2]))) return null;
                long start = Long.parseLong(parts[0]), due = Long.parseLong(parts[1]);
                boolean validDue = CANCELLED.equals(parts[2]) ? due == -1 : due > 0 && due < start;
                return start > 0 && validDue ? new Record(start, due, parts[2]) : null;
            } catch (NumberFormatException malformed) { return null; }
        }
    }

    /** Retain the original due instant on refresh. Cancellation and delivery are one-way locally. */
    public static Record reconcile(String uid, Appointment a, Record previous, long now) {
        if (a == null || uid == null || !uid.equals(a.getPatientUid())) return null;
        long start = a.getStartAt().toDate().getTime();
        if (a.getStatus() == AppointmentStatus.CANCELLED || previous != null && CANCELLED.equals(previous.state))
            return new Record(start, -1, CANCELLED);
        if (previous != null && previous.start == start && SENT.equals(previous.state)) return previous;
        if (!eligible(uid, a, now)) return null;
        if (previous != null && previous.start == start && SCHEDULED.equals(previous.state)) return previous;
        long due = dueAt(start, now);
        return due < 0 ? null : new Record(start, due, SCHEDULED);
    }

    public static boolean mayDeliver(Record record, long expectedStart, long expectedDue, long now) {
        return record != null && SCHEDULED.equals(record.state) && record.start == expectedStart
                && record.due == expectedDue && now >= expectedDue && now < expectedStart - 5 * MINUTE;
    }
}
