package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;

import org.junit.Test;

import static org.junit.Assert.*;

public class LaunchRoutingTest {
    @Test public void verifiedSessionAlwaysEndsOnAScreenNeverOnAnEmptyTask() {
        assertEquals(LaunchRouting.Action.RESUME_EXISTING_TASK,
                LaunchRouting.actionFor(true, true));
        assertEquals(LaunchRouting.Action.BUILD_DESTINATION,
                LaunchRouting.actionFor(true, false));
    }

    @Test public void unverifiedSessionGoesThroughSecureVerification() {
        assertEquals(LaunchRouting.Action.VERIFY_SESSION, LaunchRouting.actionFor(false, false));
        assertEquals(LaunchRouting.Action.VERIFY_SESSION, LaunchRouting.actionFor(false, true));
    }

    @Test public void destinationFollowsTheVerifiedRoleOnly() {
        assertEquals(LaunchRouting.Destination.PATIENT_HOME,
                LaunchRouting.destinationFor(UserProfile.Role.PATIENT));
        assertEquals(LaunchRouting.Destination.ADMIN_DASHBOARD,
                LaunchRouting.destinationFor(UserProfile.Role.ADMIN));
        assertEquals(LaunchRouting.Destination.LOGIN, LaunchRouting.destinationFor(null));
    }

    @Test public void taskRootMustMatchThisAppAndTheExpectedDestination() {
        assertTrue(LaunchRouting.taskRootsAt("com.example.medibook", "com.example.medibook",
                "com.example.medibook.activities.patient.PatientHomeActivity",
                "com.example.medibook.activities.patient.PatientHomeActivity"));
        assertFalse(LaunchRouting.taskRootsAt("com.example.medibook", "com.example.medibook",
                "com.example.medibook.activities.admin.AdminDashboardActivity",
                "com.example.medibook.activities.patient.PatientHomeActivity"));
        assertFalse(LaunchRouting.taskRootsAt("com.example.medibook", "com.other.app",
                "com.example.medibook.activities.patient.PatientHomeActivity",
                "com.example.medibook.activities.patient.PatientHomeActivity"));
        assertFalse(LaunchRouting.taskRootsAt(null, null, null, null));
        assertFalse(LaunchRouting.taskRootsAt("com.example.medibook", "com.example.medibook",
                null, "com.example.medibook.activities.patient.PatientHomeActivity"));
    }
}
