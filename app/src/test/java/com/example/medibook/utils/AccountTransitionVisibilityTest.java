package com.example.medibook.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccountTransitionVisibilityTest {
    @Test public void warmVerificationKeepsOnlySameOwnersContent() {
        assertTrue(PatientScreenAccess.keepWhileChecking(true, "patient-a", "patient-a", false));
        assertFalse(PatientScreenAccess.keepWhileChecking(true, "patient-a", "patient-b", false));
    }
    @Test public void logoutAndOnboardingCannotRetainAProtectedScreen() {
        assertFalse(PatientScreenAccess.keepWhileChecking(true, "patient-a", null, false));
        assertFalse(PatientScreenAccess.keepWhileChecking(true, "patient-a", "patient-a", true));
    }
    @Test public void coldOrUnownedContentIsNeverMadeVisibleByVerification() {
        assertFalse(PatientScreenAccess.keepWhileChecking(false, "patient-a", "patient-a", false));
        assertFalse(PatientScreenAccess.keepWhileChecking(true, null, "patient-a", false));
    }
}
