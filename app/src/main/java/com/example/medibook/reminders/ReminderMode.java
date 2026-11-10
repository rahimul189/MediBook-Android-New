package com.example.medibook.reminders;

/** Durable delivery style for one custom reminder. Defaults stay notification-only. */
public enum ReminderMode {
    NOTIFICATION("notification"), ALARM("alarm");

    public final String wire;

    ReminderMode(String wire) { this.wire = wire; }

    public static ReminderMode from(String value) {
        if (value == null) return null;
        for (ReminderMode mode : values()) if (mode.wire.equals(value)) return mode;
        return null;
    }

    public static ReminderMode orDefault(ReminderMode mode) {
        return mode == null ? NOTIFICATION : mode;
    }
}
