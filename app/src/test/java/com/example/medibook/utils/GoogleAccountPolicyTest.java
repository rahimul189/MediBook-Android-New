package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;
import com.google.firebase.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.example.medibook.utils.GoogleAccountPolicy.Destination.*;

public class GoogleAccountPolicyTest {
    static final long NOW = DhakaDateTime.toEpochMillis(2026, 11, 9, 10, 0);
    static final String EMAIL = "patient@example.invalid";
    static Map<String, Object> profile(boolean complete) {
        Map<String, Object> result = new HashMap<>();
        result.put("fullName", "Test Patient"); result.put("email", EMAIL); result.put("phone", "1234567");
        result.put("role", "Patient"); result.put("createdAt", new Timestamp(1L, 0));
        if (complete) { result.put("dateOfBirth", "2000-02-29"); result.put("gender", "Female"); }
        return result;
    }
    private static GoogleAccountPolicy.Destination destination(boolean google, boolean verified, Map<String, Object> claims, Map<String, Object> profile) {
        return GoogleAccountPolicy.destination(google, verified, claims, profile != null, SessionPolicy.evaluate(claims, profile, EMAIL), NOW);
    }
    @Test public void newVerifiedGoogleUserMustCompleteProfileNotEnterDashboard() {
        assertEquals(COMPLETE_PROFILE, destination(true, true, Collections.emptyMap(), null));
    }
    @Test public void newUserWithoutVerifiedGoogleIdentityCannotCreatePatientSession() {
        assertEquals(DENIED, destination(false, true, Collections.emptyMap(), null));
        assertEquals(DENIED, destination(true, false, Collections.emptyMap(), null));
        assertEquals(DENIED, destination(true, true, null, null));
    }
    @Test public void existingCompletePatientRoutesWithoutProfileRecreation() {
        assertEquals(EXISTING_SESSION, destination(true, true, Collections.emptyMap(), profile(true)));
    }
    @Test public void googleIncompleteProfileAfterRestartStillRequiresCompletion() {
        for (int restart = 0; restart < 3; restart++) assertEquals(COMPLETE_PROFILE, destination(true, true, Collections.emptyMap(), profile(false)));
    }
    @Test public void passwordOnlyLegacyPatientRemainsCompatible() {
        assertEquals(EXISTING_SESSION, destination(false, false, Collections.emptyMap(), profile(false)));
    }
    @Test public void googleProviderCannotGrantAdminFromEmailOrProfileRole() {
        Map<String, Object> fake = profile(true); fake.put("role", "Admin");
        assertEquals(DENIED, destination(true, true, Collections.emptyMap(), fake));
    }
    @Test public void trustedAdminRequiresTheExistingClaimAndProfilePair() {
        Map<String, Object> admin = profile(false); admin.put("role", "Admin");
        assertEquals(EXISTING_SESSION, destination(true, true, Collections.singletonMap("admin", true), admin));
        assertEquals(DENIED, destination(true, true, Collections.singletonMap("admin", true), profile(true)));
        assertEquals(DENIED, destination(true, true, Collections.singletonMap("admin", true), null));
    }
    @Test public void malformedOrPresentAdminClaimNeverFallsThroughToOnboarding() {
        for (Object value : new Object[]{false, null, "true", 1, Collections.emptyMap()}) {
            assertEquals(DENIED, destination(true, true, Collections.singletonMap("admin", value), null));
            assertEquals(DENIED, destination(true, true, Collections.singletonMap("admin", value), profile(true)));
        }
    }
    @Test public void corruptOrForeignEmailProfileIsNotSilentlyRepaired() {
        Map<String, Object> invalid = profile(true); invalid.put("email", "other@example.invalid");
        assertEquals(DENIED, destination(true, true, Collections.emptyMap(), invalid));
        invalid = profile(true); invalid.put("admin", true);
        assertEquals(DENIED, destination(true, true, Collections.emptyMap(), invalid));
    }
    @Test public void unverifiedGoogleEmailCannotBecomeAnExistingPatientSession() {
        assertEquals(DENIED, destination(true, false, Collections.emptyMap(), profile(true)));
    }
    @Test public void legacyOtherGenderAndMissingDetailsRequireCompletionOnlyForGoogle() {
        Map<String, Object> legacy = profile(true); legacy.put("gender", "Other");
        assertEquals(COMPLETE_PROFILE, destination(true, true, Collections.emptyMap(), legacy));
        assertEquals(EXISTING_SESSION, destination(false, false, Collections.emptyMap(), legacy));
    }
    @Test public void linkIsOnlyForVerifiedPatientWithPasswordAndNoGoogleProvider() {
        UserProfile patient = SessionPolicy.evaluate(Collections.emptyMap(), profile(true), EMAIL).user;
        assertTrue(GoogleAccountPolicy.canLink(patient, Collections.singleton("password")));
        assertFalse(GoogleAccountPolicy.canLink(patient, new HashSet<>(Arrays.asList("password", "google.com"))));
        assertFalse(GoogleAccountPolicy.canLink(patient, Collections.singleton("google.com")));
        assertFalse(GoogleAccountPolicy.canLink(null, Collections.singleton("password")));
        assertFalse(GoogleAccountPolicy.canLink(new UserProfile("Admin", EMAIL, "1234567", UserProfile.Role.ADMIN), Collections.singleton("password")));
    }
    @Test public void sameEmailIsOnlyALinkPreconditionNeverAnOwnershipSubstitute() {
        assertTrue(GoogleAccountPolicy.sameEmail(EMAIL, EMAIL));
        assertFalse(GoogleAccountPolicy.sameEmail(EMAIL, "other@example.invalid"));
        assertFalse(GoogleAccountPolicy.sameEmail(null, EMAIL));
        assertFalse(GoogleAccountPolicy.sameEmail("", ""));
        assertFalse(GoogleAccountPolicy.sameEmail(EMAIL, EMAIL.toUpperCase(java.util.Locale.US)));
    }
    @Test public void linkingMustPreserveBothReturnedAndCurrentFirebaseUid() {
        assertTrue(GoogleAccountPolicy.preservedUid("original", "original", "original"));
        assertFalse(GoogleAccountPolicy.preservedUid("original", "other", "original"));
        assertFalse(GoogleAccountPolicy.preservedUid("original", "original", "other"));
        assertFalse(GoogleAccountPolicy.preservedUid(null, "original", "original"));
    }
    @Test public void linkingSuccessIncludingOnboardingRequiresBothProvidersAndOriginalUid() {
        java.util.Set<String> both = new HashSet<>(Arrays.asList("password", "google.com"));
        assertTrue(GoogleAccountPolicy.linkConfirmed("original", "original", "original", both));
        assertFalse(GoogleAccountPolicy.linkConfirmed("original", "other", "other", both));
        assertFalse(GoogleAccountPolicy.linkConfirmed("original", "original", "original", Collections.singleton("google.com")));
        assertFalse(GoogleAccountPolicy.linkConfirmed("original", "original", "original", Collections.singleton("password")));
    }
    @Test public void multipleFirebaseCollisionCodesUseTheSameSafeGuidance() {
        for (String code : new String[]{"ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_CREDENTIAL_ALREADY_IN_USE"}) assertTrue(GoogleAccountPolicy.conflictCode(code));
        assertFalse(GoogleAccountPolicy.conflictCode("ERROR_NETWORK_REQUEST_FAILED")); assertFalse(GoogleAccountPolicy.conflictCode(null));
    }
    @Test public void missingTokenIsRejectedLocallyButNonemptyTokensStillRequireFirebaseValidation() {
        assertFalse(GoogleAccountPolicy.hasIdToken(null)); assertFalse(GoogleAccountPolicy.hasIdToken(""));
        assertFalse(GoogleAccountPolicy.hasIdToken(" \n\t"));
        assertTrue(GoogleAccountPolicy.hasIdToken("synthetic-not-a-validated-token"));
    }
}
