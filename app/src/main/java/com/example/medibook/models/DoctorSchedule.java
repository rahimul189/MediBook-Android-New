package com.example.medibook.models;

import com.google.firebase.Timestamp;

import java.util.Objects;

/** Immutable weekly schedule. Times are Asia/Dhaka wall-clock minutes, not dates or UTC instants. */
public final class DoctorSchedule {
    private final String id;
    private final int dayOfWeek; // ISO order: Monday = 1, Sunday = 7. Not java.util.Calendar numbering.
    private final int startMinute; // Hospital-local minutes after midnight; no date/UTC conversion.
    private final int endMinute;
    private final int slotMinutes;
    private final boolean active;
    private final Timestamp updatedAt;

    public DoctorSchedule(String id, int dayOfWeek, int startMinute, int endMinute,
                          int slotMinutes, boolean active) {
        this(id, dayOfWeek, startMinute, endMinute, slotMinutes, active, null);
    }

    public DoctorSchedule(String id, int dayOfWeek, int startMinute, int endMinute,
                          int slotMinutes, boolean active, Timestamp updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.dayOfWeek = dayOfWeek;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.slotMinutes = slotMinutes;
        this.active = active;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public int getDayOfWeek() { return dayOfWeek; }
    public int getStartMinute() { return startMinute; }
    public int getEndMinute() { return endMinute; }
    public int getSlotMinutes() { return slotMinutes; }
    public boolean isActive() { return active; }
    public Timestamp getUpdatedAt() { return updatedAt; }

    public DoctorSchedule withActive(boolean active) {
        return new DoctorSchedule(id, dayOfWeek, startMinute, endMinute, slotMinutes, active, updatedAt);
    }
}
