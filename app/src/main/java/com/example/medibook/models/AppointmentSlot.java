package com.example.medibook.models;

import java.util.Objects;

/**
 * Immutable generated appointment slot. Times are Dhaka wall-clock minutes plus their exact UTC
 * instants. A slot is schedule-derived only; this model makes no claim that it is unbooked.
 */
public final class AppointmentSlot {
    private final String scheduleId;
    private final int startMinute;
    private final int endMinute;
    private final long startAtMillis;
    private final long endAtMillis;

    public AppointmentSlot(String scheduleId, int startMinute, int endMinute,
                           long startAtMillis, long endAtMillis) {
        if (startMinute < 0 || startMinute > 1439
                || endMinute <= startMinute || endMinute > 1440) {
            throw new IllegalArgumentException("Invalid slot minutes");
        }
        if (startAtMillis >= endAtMillis
                || endAtMillis - startAtMillis != (long) (endMinute - startMinute) * 60_000L) {
            throw new IllegalArgumentException("Invalid slot instants");
        }
        this.scheduleId = Objects.requireNonNull(scheduleId);
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.startAtMillis = startAtMillis;
        this.endAtMillis = endAtMillis;
    }

    public String getScheduleId() { return scheduleId; }
    public int getStartMinute() { return startMinute; }
    public int getEndMinute() { return endMinute; }
    public long getStartAtMillis() { return startAtMillis; }
    public long getEndAtMillis() { return endAtMillis; }
}
