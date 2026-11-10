package com.example.medibook.reminders;

/**
 * Pure reminder arithmetic: the two default offsets, exact custom instants and the small
 * durable records stored per appointment. No Android or timezone-dependent midnight arithmetic.
 */
public final class ReminderSchedule {
    public static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    public static final long FIRST_BEFORE = DAY;
    public static final long SECOND_BEFORE = 6 * HOUR;
    public static final int MAX_CUSTOM_HOURS = 72;
    public static final int MAX_CUSTOM_MINUTES = MAX_CUSTOM_HOURS * 60;
    /** A reminder must stay at least this far in the future to be worth scheduling. */
    public static final long MINIMUM_LEAD = MINUTE;
    public static final String SCHEDULED = "scheduled", SENT = "sent", CANCELLED = "cancelled";

    public enum Kind {
        DEFAULT_24("default_24"), DEFAULT_6("default_6"), CUSTOM("custom");
        public final String wire;
        Kind(String wire) { this.wire = wire; }
        public static Kind from(String value) {
            if (value == null) return null;
            for (Kind kind : values()) if (kind.wire.equals(value)) return kind;
            return null;
        }
    }

    private ReminderSchedule() { }

    /** Triggers still in the future; 0 means that default does not apply to this booking. */
    public static final class Defaults {
        public final long first, second;
        Defaults(long first, long second) { this.first = first; this.second = second; }
    }

    public static Defaults defaults(long start, long now) {
        if (start <= now || now < 0) return new Defaults(0, 0);
        long first = start - FIRST_BEFORE, second = start - SECOND_BEFORE;
        return new Defaults(first > now + MINIMUM_LEAD ? first : 0,
                second > now + MINIMUM_LEAD ? second : 0);
    }

    public static boolean customValid(int hours, int minutes) {
        return hours >= 0 && minutes >= 0 && minutes < 60
                && hours <= MAX_CUSTOM_HOURS && !(hours == 0 && minutes == 0)
                && (long) hours * 60 + minutes <= MAX_CUSTOM_MINUTES;
    }

    /** Exact trigger instant, or -1 when the lead time itself is invalid. */
    public static long customTrigger(long start, int hours, int minutes) {
        if (!customValid(hours, minutes) || start <= 0) return -1;
        return start - ((long) hours * 60 + minutes) * MINUTE;
    }

    public static boolean customFuture(long trigger, long now) {
        return trigger > 0 && now >= 0 && trigger > now;
    }

    /** Exact reminder instant: future, with the whole appointment still ahead of it. */
    public static boolean customTriggerValid(long start, long trigger, long now) {
        return start > 0 && trigger > 0 && start > now && trigger < start
                && customFuture(trigger, now);
    }

    /** e.g. 4 h, 1 h 30 min, 45 min. */
    public static String offsetLabel(int hours, int minutes) {
        if (hours > 0 && minutes > 0) return hours + " h " + minutes + " min";
        if (hours > 0) return hours + " h";
        return minutes + " min";
    }

    /** Per-appointment default ledger. Sent flags prevent duplicates across relaunch/reconcile. */
    public static final class DefaultRecord {
        public final long start, first, second;
        public final boolean firstSent, secondSent, cancelled;

        public DefaultRecord(long start, long first, long second,
                             boolean firstSent, boolean secondSent, boolean cancelled) {
            this.start = start; this.first = first; this.second = second;
            this.firstSent = firstSent; this.secondSent = secondSent; this.cancelled = cancelled;
        }

        public static DefaultRecord of(long start, long first, long second) {
            return new DefaultRecord(start, first, second, false, false, false);
        }

        public static DefaultRecord cancelled(long start) {
            return new DefaultRecord(start, 0, 0, true, true, true);
        }

        public DefaultRecord markSent(Kind kind) {
            return new DefaultRecord(start, first, second,
                    firstSent || kind == Kind.DEFAULT_24, secondSent || kind == Kind.DEFAULT_6, cancelled);
        }

        public DefaultRecord withTriggers(long newFirst, long newSecond) {
            return new DefaultRecord(start, newFirst, newSecond, firstSent, secondSent, cancelled);
        }

        public String encode() {
            return start + ":" + first + ":" + second + ":" + flag(firstSent) + ":" + flag(secondSent) + ":" + flag(cancelled);
        }

        public static DefaultRecord decode(String raw) {
            if (raw == null) return null;
            try {
                String[] parts = raw.split(":", -1);
                if (parts.length != 6) return null;
                long start = Long.parseLong(parts[0]), first = Long.parseLong(parts[1]), second = Long.parseLong(parts[2]);
                if (start <= 0 || first < 0 || second < 0 || first >= start || second >= start) return null;
                if (parts[3].length() != 1 || parts[4].length() != 1 || parts[5].length() != 1) return null;
                boolean firstSent = "1".equals(parts[3]), secondSent = "1".equals(parts[4]), cancelled = "1".equals(parts[5]);
                if (cancelled && (first != 0 || second != 0)) return null;
                return new DefaultRecord(start, first, second, firstSent, secondSent, cancelled);
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        private static String flag(boolean value) { return value ? "1" : "0"; }
    }

    /**
     * One custom reminder per appointment, stored as the exact absolute trigger instant in
     * Asia/Dhaka. The old five/six-field lead-time records are decoded once into this format so
     * reminders saved before the hotfix keep their original trigger instant.
     */
    public static final class CustomRecord {
        public final long start, trigger;
        public final String state;
        public final ReminderMode mode;

        public CustomRecord(long start, long trigger, String state) {
            this(start, trigger, state, ReminderMode.NOTIFICATION);
        }

        public CustomRecord(long start, long trigger, String state, ReminderMode mode) {
            this.start = start; this.trigger = trigger;
            this.state = state; this.mode = ReminderMode.orDefault(mode);
        }

        public static CustomRecord of(long start, long trigger, String state) {
            return of(start, trigger, state, ReminderMode.NOTIFICATION);
        }

        public static CustomRecord of(long start, long trigger, String state, ReminderMode mode) {
            return new CustomRecord(start, trigger, state, mode);
        }

        public CustomRecord withState(String newState) {
            return new CustomRecord(start, trigger, newState, mode);
        }

        public boolean active(long now) {
            return SCHEDULED.equals(state) && trigger > now && start > now && trigger < start;
        }

        /** Four fields: start:trigger:state:mode. */
        public String encode() {
            return start + ":" + trigger + ":" + state + ":" + mode.wire;
        }

        public static CustomRecord decode(String raw) {
            if (raw == null) return null;
            try {
                String[] parts = raw.split(":", -1);
                if (parts.length == 4) {
                    long start = Long.parseLong(parts[0]), trigger = Long.parseLong(parts[1]);
                    if (!validState(parts[2])) return null;
                    ReminderMode mode = ReminderMode.from(parts[3]);
                    if (mode == null || start <= 0 || trigger <= 0 || trigger >= start) return null;
                    return new CustomRecord(start, trigger, parts[2], mode);
                }
                // Five fields are the pre-mode lead-time format; six fields add the mode.
                if (parts.length != 5 && parts.length != 6) return null;
                long start = Long.parseLong(parts[0]), trigger = Long.parseLong(parts[1]);
                int hours = Integer.parseInt(parts[2]), minutes = Integer.parseInt(parts[3]);
                if (!validState(parts[4]) || start <= 0 || !customValid(hours, minutes)) return null;
                if (trigger <= 0 || trigger != customTrigger(start, hours, minutes) || trigger >= start) return null;
                ReminderMode mode = ReminderMode.NOTIFICATION;
                if (parts.length == 6) {
                    mode = ReminderMode.from(parts[5]);
                    if (mode == null) return null;
                }
                // Preserve the already-selected instant; the UI now edits absolute date/time.
                return new CustomRecord(start, trigger, parts[4], mode);
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        private static boolean validState(String value) {
            return SCHEDULED.equals(value) || SENT.equals(value) || CANCELLED.equals(value);
        }
    }
}
