package com.example.medibook.models;

import org.junit.Test;
import com.google.firebase.Timestamp;

import java.math.BigDecimal;

import static org.junit.Assert.*;

/** Synthetic local model values; never used as application records. */
public class DoctorTest {
    private Doctor preview(boolean active) {
        return new Doctor("preview-1", "Preview Name", "Preview specialty", "Preview qualification",
                new BigDecimal("50.25"), "Preview biography", active);
    }

    @Test
    public void modelRetainsAllFormFields() {
        Doctor doctor = preview(true);
        assertEquals("preview-1", doctor.getId());
        assertEquals("Preview Name", doctor.getName());
        assertEquals("Preview specialty", doctor.getSpecialization());
        assertEquals("Preview qualification", doctor.getQualifications());
        assertEquals(new BigDecimal("50.25"), doctor.getConsultationFee());
        assertEquals("Preview biography", doctor.getBiography());
        assertTrue(doctor.isActive());
    }

    @Test
    public void changingStatusCreatesANewPreviewAndPreservesAllOtherFields() {
        Doctor original = preview(true);
        Doctor changed = original.withActive(false);
        assertNotSame(original, changed);
        assertTrue(original.isActive());
        assertFalse(changed.isActive());
        assertEquals(original.getId(), changed.getId());
        assertEquals(original.getName(), changed.getName());
        assertEquals(original.getSpecialization(), changed.getSpecialization());
        assertEquals(original.getQualifications(), changed.getQualifications());
        assertEquals(original.getConsultationFee(), changed.getConsultationFee());
        assertEquals(original.getBiography(), changed.getBiography());
    }

    @Test
    public void inactivePreviewCanBeActivatedWithoutChangingTheOriginal() {
        Doctor original = preview(false);
        assertTrue(original.withActive(true).isActive());
        assertFalse(original.isActive());
    }

    @Test
    public void draftDoesNotFabricateServerTimestamps() {
        assertNull(preview(false).getCreatedAt());
        assertNull(preview(false).getUpdatedAt());
    }

    @Test
    public void storedDoctorRetainsIdentityAndTimestampsInImmutableCopies() {
        Timestamp created = new Timestamp(100, 123);
        Timestamp updated = new Timestamp(200, 456);
        Doctor stored = new Doctor("doctor-1", "Fixture Name", "Fixture specialty", "Fixture qualification",
                new BigDecimal("10.25"), "", true, created, updated);
        Doctor inactive = stored.withActive(false);
        assertEquals("doctor-1", inactive.getId());
        assertEquals(created, inactive.getCreatedAt());
        assertEquals(updated, inactive.getUpdatedAt());
    }
}
