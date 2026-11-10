package com.example.medibook.models;

import com.google.firebase.Timestamp;

/** Six persisted fields; generation, doctor and date are identities in the document path only. */
public final class BookingLock {
    public final String appointmentId, patientUid, scheduleId;
    public final Timestamp startAt, endAt, createdAt;

    public BookingLock(String appointmentId, String patientUid, String scheduleId,
                       Timestamp startAt, Timestamp endAt, Timestamp createdAt) {
        this.appointmentId = appointmentId; this.patientUid = patientUid; this.scheduleId = scheduleId;
        this.startAt = startAt; this.endAt = endAt; this.createdAt = createdAt;
    }
}
