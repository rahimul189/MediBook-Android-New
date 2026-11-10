package com.example.medibook.models;

import com.google.firebase.Timestamp;

import java.util.Objects;

/**
 * Immutable appointment snapshot. The appointment document ID is a path identity and is never
 * stored as one of the 17 document fields.
 *
 * <p>Timestamps are Firestore values: {@code appointmentDate} is the selected Asia/Dhaka local
 * midnight, while {@code startAt} and {@code endAt} are the exact UTC instants for the booked
 * minutes. The consultation fee is an integer amount in hundredths, never a floating-point or
 * major-unit value.</p>
 */
public final class Appointment {
    private final String id;
    private final String patientUid;
    private final String patientName;
    private final String patientPhone;
    private final String doctorId;
    private final String doctorName;
    private final String doctorSpecialization;
    private final long consultationFee;
    private final String scheduleId;
    private final int dayOfWeek;
    private final Timestamp appointmentDate;
    private final int startMinute;
    private final int endMinute;
    private final Timestamp startAt;
    private final Timestamp endAt;
    private final AppointmentStatus status;
    private final Timestamp createdAt;
    private final Timestamp updatedAt;
    private final int slotSerial;

    public Appointment(String id, String patientUid, String patientName, String patientPhone,
                       String doctorId, String doctorName, String doctorSpecialization,
                       long consultationFee, String scheduleId, int dayOfWeek,
                       Timestamp appointmentDate, int startMinute, int endMinute,
                       Timestamp startAt, Timestamp endAt, AppointmentStatus status,
                        Timestamp createdAt, Timestamp updatedAt) {
        this(id, patientUid, patientName, patientPhone, doctorId, doctorName, doctorSpecialization,
                consultationFee, scheduleId, dayOfWeek, appointmentDate, startMinute, endMinute,
                startAt, endAt, status, createdAt, updatedAt, 0);
    }

    private Appointment(String id, String patientUid, String patientName, String patientPhone,
                        String doctorId, String doctorName, String doctorSpecialization, long consultationFee,
                        String scheduleId, int dayOfWeek, Timestamp appointmentDate, int startMinute, int endMinute,
                        Timestamp startAt, Timestamp endAt, AppointmentStatus status, Timestamp createdAt,
                        Timestamp updatedAt, int slotSerial) {
        this.id = Objects.requireNonNull(id);
        this.patientUid = Objects.requireNonNull(patientUid);
        this.patientName = Objects.requireNonNull(patientName);
        this.patientPhone = Objects.requireNonNull(patientPhone);
        this.doctorId = Objects.requireNonNull(doctorId);
        this.doctorName = Objects.requireNonNull(doctorName);
        this.doctorSpecialization = Objects.requireNonNull(doctorSpecialization);
        this.consultationFee = consultationFee;
        this.scheduleId = Objects.requireNonNull(scheduleId);
        this.dayOfWeek = dayOfWeek;
        this.appointmentDate = Objects.requireNonNull(appointmentDate);
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.startAt = Objects.requireNonNull(startAt);
        this.endAt = Objects.requireNonNull(endAt);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.slotSerial = slotSerial;
    }

    /** The appointments/{appointmentId} path identity; never a stored document field. */
    public String getId() { return id; }
    public String getPatientUid() { return patientUid; }
    public String getPatientName() { return patientName; }
    public String getPatientPhone() { return patientPhone; }
    public String getDoctorId() { return doctorId; }
    public String getDoctorName() { return doctorName; }
    public String getDoctorSpecialization() { return doctorSpecialization; }

    /** Integer hundredths, for example 50025 means 500.25 in the UI's currency convention. */
    public long getConsultationFee() { return consultationFee; }

    public String getScheduleId() { return scheduleId; }
    public int getDayOfWeek() { return dayOfWeek; }
    public Timestamp getAppointmentDate() { return appointmentDate; }
    public int getStartMinute() { return startMinute; }
    public int getEndMinute() { return endMinute; }
    public Timestamp getStartAt() { return startAt; }
    public Timestamp getEndAt() { return endAt; }
    public AppointmentStatus getStatus() { return status; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public int getSlotSerial() { return slotSerial; }
    public Appointment withSlotSerial(int serial) {
        return new Appointment(id, patientUid, patientName, patientPhone, doctorId, doctorName,
                doctorSpecialization, consultationFee, scheduleId, dayOfWeek, appointmentDate,
                startMinute, endMinute, startAt, endAt, status, createdAt, updatedAt, serial);
    }
}
