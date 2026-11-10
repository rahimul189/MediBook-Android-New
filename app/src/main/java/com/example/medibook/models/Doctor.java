package com.example.medibook.models;

import com.google.firebase.Timestamp;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable doctor data. A validated, not-yet-saved form has null server timestamps. */
public final class Doctor {
    private final String id;
    private final String name;
    private final String specialization;
    private final String qualifications;
    private final BigDecimal consultationFee;
    private final String biography;
    private final String imageUrl;
    private final boolean active;
    private final Timestamp createdAt;
    private final Timestamp updatedAt;

    public Doctor(String id, String name, String specialization, String qualifications,
                  BigDecimal consultationFee, String biography, boolean active) {
        this(id, name, specialization, qualifications, consultationFee, biography, active, null, null);
    }

    public Doctor(String id, String name, String specialization, String qualifications,
                  BigDecimal consultationFee, String biography, boolean active,
                  Timestamp createdAt, Timestamp updatedAt) {
        this(id, name, specialization, qualifications, consultationFee, biography, active, "", createdAt, updatedAt);
    }

    public Doctor(String id, String name, String specialization, String qualifications,
                  BigDecimal consultationFee, String biography, boolean active, String imageUrl,
                  Timestamp createdAt, Timestamp updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.specialization = Objects.requireNonNull(specialization);
        this.qualifications = Objects.requireNonNull(qualifications);
        this.consultationFee = Objects.requireNonNull(consultationFee);
        this.biography = Objects.requireNonNull(biography);
        this.imageUrl = Objects.requireNonNull(imageUrl);
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** The doctors/{doctorId} path identity; never stored as an editable document field. */
    public String getId() { return id; }
    public String getName() { return name; }
    public String getSpecialization() { return specialization; }
    public String getQualifications() { return qualifications; }
    public BigDecimal getConsultationFee() { return consultationFee; }
    public String getBiography() { return biography; }
    public String getImageUrl() { return imageUrl; }
    public boolean isActive() { return active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }

    public Doctor withActive(boolean active) {
        return new Doctor(id, name, specialization, qualifications,
                consultationFee, biography, active, imageUrl, createdAt, updatedAt);
    }

    public Doctor withImageUrl(String url) {
        return new Doctor(id, name, specialization, qualifications, consultationFee, biography,
                active, url, createdAt, updatedAt);
    }
}
