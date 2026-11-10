package com.example.medibook.models;

import com.google.firebase.Timestamp;

/** Immutable read of the twelve-field Admin-published inventory document. */
public final class InventorySlot {
    public final String doctorId, id, scheduleId, status, appointmentId;
    public final Timestamp appointmentDate, startAt, endAt, createdAt, updatedAt;
    public final int dayOfWeek, startMinute, endMinute;
    public final long generation;
    public final int slotSerial;

    public InventorySlot(String doctorId, String id, String scheduleId, Timestamp appointmentDate,
                         int dayOfWeek, int startMinute, int endMinute, Timestamp startAt,
                         Timestamp endAt, String status, long generation, String appointmentId,
                         Timestamp createdAt, Timestamp updatedAt) {
        this(doctorId, id, scheduleId, appointmentDate, dayOfWeek, startMinute, endMinute, startAt,
                endAt, status, generation, appointmentId, createdAt, updatedAt, 0);
    }
    private InventorySlot(String doctorId, String id, String scheduleId, Timestamp appointmentDate,
                          int dayOfWeek, int startMinute, int endMinute, Timestamp startAt,
                          Timestamp endAt, String status, long generation, String appointmentId,
                          Timestamp createdAt, Timestamp updatedAt, int slotSerial) {
        this.doctorId = doctorId; this.id = id; this.scheduleId = scheduleId;
        this.appointmentDate = appointmentDate; this.dayOfWeek = dayOfWeek;
        this.startMinute = startMinute; this.endMinute = endMinute;
        this.startAt = startAt; this.endAt = endAt; this.status = status;
        this.generation = generation; this.appointmentId = appointmentId;
        this.createdAt = createdAt; this.updatedAt = updatedAt;
        this.slotSerial = slotSerial;
    }
    public InventorySlot withSlotSerial(int serial) {
        return new InventorySlot(doctorId, id, scheduleId, appointmentDate, dayOfWeek, startMinute,
                endMinute, startAt, endAt, status, generation, appointmentId, createdAt, updatedAt, serial);
    }

    public AppointmentSlot interval() {
        return new AppointmentSlot(scheduleId, startMinute, endMinute,
                startAt.toDate().getTime(), endAt.toDate().getTime());
    }
}
