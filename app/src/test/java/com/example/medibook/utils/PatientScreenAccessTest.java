package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;

import org.junit.Test;

import static org.junit.Assert.*;

/** Verifies the presentation gate, not a substitute for the existing online AuthRepository checks. */
public class PatientScreenAccessTest {
    private UserProfile user(UserProfile.Role role) {
        return new UserProfile("Fixture user", "fixture@example.invalid", "1234567", role);
    }

    @Test public void verifiedPatientWithCurrentIdentityCanEnter() {
        assertTrue(PatientScreenAccess.authorized(false, user(UserProfile.Role.PATIENT), "patient-a"));
    }

    @Test public void adminCannotEnterPatientHomeOrProfile() {
        assertFalse(PatientScreenAccess.authorized(false, user(UserProfile.Role.ADMIN), "admin-a"));
    }

    @Test public void loadingStateCannotExposeEvenAPreviouslyVerifiedPatient() {
        assertFalse(PatientScreenAccess.authorized(true, user(UserProfile.Role.PATIENT), "patient-a"));
    }

    @Test public void missingUserIdentityOrRoleIsDenied() {
        assertFalse(PatientScreenAccess.authorized(false, null, "patient-a"));
        assertFalse(PatientScreenAccess.authorized(false, user(null), "patient-a"));
        assertFalse(PatientScreenAccess.authorized(false, user(UserProfile.Role.PATIENT), null));
        assertFalse(PatientScreenAccess.authorized(false, user(UserProfile.Role.PATIENT), ""));
    }

    @Test public void profileContextMustMatchTheCurrentAccountAndCannotUseAnEmptyOwner() {
        assertTrue(PatientScreenAccess.sameOwner("patient-a", "patient-a"));
        assertFalse(PatientScreenAccess.sameOwner("patient-a", "patient-b"));
        assertFalse(PatientScreenAccess.sameOwner("patient-a", null));
        assertFalse(PatientScreenAccess.sameOwner(null, "patient-a"));
        assertFalse(PatientScreenAccess.sameOwner("", ""));
    }

    @Test public void matchingContextDoesNotGrantAnAdminPatientAuthorization() {
        assertTrue(PatientScreenAccess.sameOwner("admin-a", "admin-a"));
        assertFalse(PatientScreenAccess.authorized(false, user(UserProfile.Role.ADMIN), "admin-a"));
    }
}
