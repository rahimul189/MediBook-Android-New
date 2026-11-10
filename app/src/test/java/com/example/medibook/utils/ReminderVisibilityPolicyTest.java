package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.models.UserProfile;
import com.google.firebase.Timestamp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression checks for the Appointment Details reminder gate: reminders belong only to the
 * verified Patient who owns an eligible future Confirmed appointment. A signed-in Admin has a
 * local session epoch too, so the epoch alone must never reveal the patient-only card or actions.
 */
public class ReminderVisibilityPolicyTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final String PATIENT_UID = "patient-uid";
    private static final String ADMIN_UID = "admin-uid";

    private Appointment appointment(String patientUid, AppointmentStatus status, long startOffset) {
        long start = NOW + startOffset;
        Timestamp stamp = new Timestamp(start / 1000L, 0);
        return new Appointment("appointment", patientUid, "Patient", "01712345678",
                "doctor", "Dr. MediBook", "Medicine", 50_000L, "schedule", 1,
                stamp, 540, 570, stamp, stamp, status, stamp, stamp);
    }

    @Test public void patientOwnerSeesRemindersForTheirEligibleFutureConfirmedAppointment() {
        Appointment own = appointment(PATIENT_UID, AppointmentStatus.CONFIRMED, 2 * HOUR);
        assertTrue(AppointmentPolicy.canCustomRemind(own, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
    }

    /** The reported Admin bug: opening any patient's appointment from Manage Appointments. */
    @Test public void adminViewingAPatientsAppointmentIsNeverOfferedReminders() {
        Appointment patients = appointment(PATIENT_UID, AppointmentStatus.CONFIRMED, 2 * HOUR);
        assertFalse(AppointmentPolicy.canCustomRemind(patients, UserProfile.Role.ADMIN, ADMIN_UID, NOW));
        // Even an Admin whose own UID somehow matches the appointment owner stays excluded.
        assertFalse(AppointmentPolicy.canCustomRemind(patients, UserProfile.Role.ADMIN, PATIENT_UID, NOW));
    }

    /** Ownership is checked independently of role, so a mismatched/stale context fails closed. */
    @Test public void patientCannotRemindForAnAppointmentTheyDoNotOwn() {
        Appointment foreign = appointment("other-patient", AppointmentStatus.CONFIRMED, 2 * HOUR);
        assertFalse(AppointmentPolicy.canCustomRemind(foreign, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
        assertFalse(AppointmentPolicy.canCustomRemind(foreign, UserProfile.Role.PATIENT, null, NOW));
    }

    @Test public void missingAppointmentRoleOrViewerUidNeverOffersReminders() {
        Appointment own = appointment(PATIENT_UID, AppointmentStatus.CONFIRMED, 2 * HOUR);
        assertFalse(AppointmentPolicy.canCustomRemind(null, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
        assertFalse(AppointmentPolicy.canCustomRemind(own, null, PATIENT_UID, NOW));
        assertFalse(AppointmentPolicy.canCustomRemind(own, UserProfile.Role.PATIENT, null, NOW));
    }

    /** Cancelled and time-expired (displayed completed) appointments must not offer reminders. */
    @Test public void cancelledOrExpiredAppointmentsNeverOfferReminders() {
        Appointment cancelled = appointment(PATIENT_UID, AppointmentStatus.CANCELLED, 2 * HOUR);
        Appointment expired = appointment(PATIENT_UID, AppointmentStatus.CONFIRMED, -HOUR);
        Appointment started = appointment(PATIENT_UID, AppointmentStatus.CONFIRMED, 0);
        assertFalse(AppointmentPolicy.canCustomRemind(cancelled, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
        assertFalse(AppointmentPolicy.canCustomRemind(expired, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
        assertFalse(AppointmentPolicy.canCustomRemind(started, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
    }

    /**
     * A session restart/re-login revalidates the role: the same patient appointment becomes visible
     * again for Patient and stays hidden for Admin, without depending on any cached local epoch.
     */
    @Test public void reloginRevalidatesPatientAndAdminVisibility() {
        Appointment own = appointment(PATIENT_UID, AppointmentStatus.CONFIRMED, 2 * HOUR);
        assertTrue("Patient before logout", AppointmentPolicy.canCustomRemind(own, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
        assertFalse("Unverified session while the screen revalidates",
                AppointmentPolicy.canCustomRemind(own, null, PATIENT_UID, NOW));
        assertTrue("Patient after re-login", AppointmentPolicy.canCustomRemind(own, UserProfile.Role.PATIENT, PATIENT_UID, NOW));
        assertFalse("Admin after re-login on the same appointment",
                AppointmentPolicy.canCustomRemind(own, UserProfile.Role.ADMIN, ADMIN_UID, NOW));
    }
}
