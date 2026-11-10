package com.example.medibook.reminders;

import java.util.Objects;

/** Monotonic, JVM-testable alarm lifetime; an older notification cannot stop a newer alarm. */
public final class AlarmFeedbackWindow {
    public static final long DURATION_MS = 30_000L;
    private String key;
    private long deadline;
    public void begin(String key, long elapsed) { this.key = key; deadline = elapsed + DURATION_MS; }
    public boolean active(long elapsed) { return key != null && elapsed < deadline; }
    public long remaining(long elapsed) { return key == null ? 0 : Math.max(0, deadline - elapsed); }
    public long remaining(String expected, long elapsed) {
        return Objects.equals(expected, key) ? remaining(elapsed) : 0;
    }
    public boolean stop(String expected) {
        if (expected != null && !Objects.equals(expected, key)) return false;
        key = null; deadline = 0; return true;
    }
}
