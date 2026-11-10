package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Synthetic data only; these tests never initialize Firebase Auth or contact Firebase. */
public class SessionPolicyTest {
    private static final String EMAIL = "account@example.invalid";

    @Test
    public void absentAdminClaimWithPatientProfileRoutesToPatientOnly() {
        SessionPolicy.Result result = evaluate(Collections.emptyMap(), profile("Patient"));
        assertAccepted(result, UserProfile.Role.PATIENT);
        assertNotEquals(UserProfile.Role.ADMIN, result.user.getRole());
    }

    @Test
    public void booleanTrueClaimAndAdminProfileRouteToAdminOnly() {
        SessionPolicy.Result result = evaluate(adminClaims(), profile("Admin"));
        assertAccepted(result, UserProfile.Role.ADMIN);
        assertNotEquals(UserProfile.Role.PATIENT, result.user.getRole());
    }

    @Test
    public void adminProfileAloneNeverGrantsAdminAccess() {
        assertDenied(evaluate(Collections.emptyMap(), profile("Admin")), SessionPolicy.Failure.ROLE_MISMATCH);
    }

    @Test
    public void adminClaimWithPatientProfileIsDeniedNotDowngraded() {
        assertDenied(evaluate(adminClaims(), profile("Patient")), SessionPolicy.Failure.ROLE_MISMATCH);
    }

    @Test
    public void explicitFalseAdminClaimIsDeniedForBothRoles() {
        Map<String, Object> claims = Collections.singletonMap("admin", false);
        assertDenied(evaluate(claims, profile("Patient")), SessionPolicy.Failure.ROLE_MISMATCH);
        assertDenied(evaluate(claims, profile("Admin")), SessionPolicy.Failure.ROLE_MISMATCH);
    }

    @Test
    public void malformedAndNullAdminClaimValuesAreNeverTreatedAsAbsentOrTrue() {
        for (Object claim : new Object[]{"true", "false", 1, 0, null, Collections.emptyMap()}) {
            Map<String, Object> claims = Collections.singletonMap("admin", claim);
            assertDenied(evaluate(claims, profile("Admin")), SessionPolicy.Failure.ROLE_MISMATCH);
            assertDenied(evaluate(claims, profile("Patient")), SessionPolicy.Failure.ROLE_MISMATCH);
        }
    }

    @Test
    public void unknownOrIncorrectlyCasedRolesAreDenied() {
        for (Object role : new Object[]{"admin", "patient", "Doctor", "", " Admin ", 1}) {
            Map<String, Object> data = profile("Patient");
            data.put("role", role);
            assertDenied(evaluate(Collections.emptyMap(), data), SessionPolicy.Failure.ROLE_MISMATCH);
            assertDenied(evaluate(adminClaims(), data), SessionPolicy.Failure.ROLE_MISMATCH);
        }
    }

    @Test
    public void unverifiedClaimsAreDeniedEvenIfProfileSaysPatient() {
        assertDenied(evaluate(null, profile("Patient")), SessionPolicy.Failure.INVALID_PROFILE);
        assertDenied(evaluate(null, profile("Admin")), SessionPolicy.Failure.INVALID_PROFILE);
    }

    @Test
    public void missingProfileIsDeniedForEitherClaimState() {
        assertDenied(evaluate(Collections.emptyMap(), null), SessionPolicy.Failure.INVALID_PROFILE);
        assertDenied(evaluate(adminClaims(), null), SessionPolicy.Failure.INVALID_PROFILE);
    }

    @Test
    public void everyRequiredFieldMustExistForBothRoles() {
        for (String role : new String[]{"Patient", "Admin"}) {
            for (String field : profile(role).keySet()) {
                Map<String, Object> data = profile(role);
                data.remove(field);
                assertDenied(evaluate(claimsFor(role), data), SessionPolicy.Failure.INVALID_PROFILE);
            }
        }
    }

    @Test
    public void nullValuesCannotProduceAVerifiedUser() {
        for (String field : profile("Admin").keySet()) {
            Map<String, Object> data = profile("Admin");
            data.put(field, null);
            SessionPolicy.Result result = evaluate(adminClaims(), data);
            assertNull(result.user);
            assertNotEquals(SessionPolicy.Failure.NONE, result.failure);
        }
    }

    @Test
    public void profileDataFieldsMustHaveTheExpectedTypes() {
        for (String field : new String[]{"fullName", "email", "phone", "createdAt"}) {
            Map<String, Object> data = profile("Admin");
            data.put(field, 123);
            assertDenied(evaluate(adminClaims(), data), SessionPolicy.Failure.INVALID_PROFILE);
        }
    }

    @Test
    public void blankOversizedAndMultilineNamesAreDenied() {
        for (String name : new String[]{"", "   ", "\t\n", "A".repeat(101), "Test\nUser"}) {
            Map<String, Object> data = profile("Admin");
            data.put("fullName", name);
            assertDenied(evaluate(adminClaims(), data), SessionPolicy.Failure.INVALID_PROFILE);
        }
    }

    @Test
    public void emailMustMatchAuthenticatedIdentityExactly() {
        Map<String, Object> data = profile("Admin");
        data.put("email", "different@example.invalid");
        assertDenied(evaluate(adminClaims(), data), SessionPolicy.Failure.INVALID_PROFILE);
        assertDenied(SessionPolicy.evaluate(adminClaims(), profile("Admin"), null), SessionPolicy.Failure.INVALID_PROFILE);
        assertDenied(SessionPolicy.evaluate(adminClaims(), profile("Admin"), "ACCOUNT@example.invalid"), SessionPolicy.Failure.INVALID_PROFILE);
    }

    @Test
    public void blankAndOversizedEmailAreDeniedEvenIfIdentityMatches() {
        for (String email : new String[]{"", "  ", "a".repeat(255)}) {
            Map<String, Object> data = profile("Patient");
            data.put("email", email);
            assertDenied(SessionPolicy.evaluate(Collections.emptyMap(), data, email), SessionPolicy.Failure.INVALID_PROFILE);
        }
    }

    @Test
    public void invalidPhonesAreDenied() {
        for (String phone : new String[]{"", "123456", "1234567890123456", "++1234567", "123 4567", "ABC1234"}) {
            Map<String, Object> data = profile("Patient");
            data.put("phone", phone);
            assertDenied(evaluate(Collections.emptyMap(), data), SessionPolicy.Failure.INVALID_PROFILE);
        }
    }

    @Test
    public void createdAtMustBeAFirestoreTimestampNotAStringOrDate() {
        for (Object time : new Object[]{"server timestamp", new Date(0), null, 1L}) {
            Map<String, Object> data = profile("Admin");
            data.put("createdAt", time);
            assertDenied(evaluate(adminClaims(), data), SessionPolicy.Failure.INVALID_PROFILE);
        }
    }

    @Test
    public void unexpectedFieldsAreDeniedRatherThanTreatedAsPrivileges() {
        for (String role : new String[]{"Patient", "Admin"}) {
            Map<String, Object> data = profile(role);
            data.put("admin", true);
            assertDenied(evaluate(claimsFor(role), data), SessionPolicy.Failure.INVALID_PROFILE);
        }
    }

    @Test
    public void legacyGenderValuesStillProduceAReadablePatientSession() {
        for (String gender : new String[]{"Male", "Female"}) {
            Map<String, Object> data = profile("Patient");
            data.put("gender", gender);
            SessionPolicy.Result result = evaluate(Collections.emptyMap(), data);
            assertAccepted(result, UserProfile.Role.PATIENT);
            assertEquals(gender, PatientGender.display(result.user.getGender()));
        }
        for (String gender : new String[]{"Other", "Prefer not to say"}) {
            Map<String, Object> data = profile("Patient");
            data.put("gender", gender);
            SessionPolicy.Result result = evaluate(Collections.emptyMap(), data);
            assertAccepted(result, UserProfile.Role.PATIENT);
            assertEquals(gender, result.user.getGender());
            assertNull(PatientGender.display(result.user.getGender()));
        }
        for (String gender : new String[]{"", "male", "MALE", "Unknown", "Other "}) {
            Map<String, Object> data = profile("Patient");
            data.put("gender", gender);
            assertDenied(evaluate(Collections.emptyMap(), data), SessionPolicy.Failure.INVALID_PROFILE);
        }
    }

    @Test
    public void unrelatedSignedClaimsDoNotChangePatientRouting() {
        assertAccepted(evaluate(Collections.singletonMap("other", true), profile("Patient")), UserProfile.Role.PATIENT);
    }

    @Test
    public void unrelatedSignedClaimsDoNotChangeAdminRouting() {
        Map<String, Object> claims = new HashMap<>(adminClaims());
        claims.put("other", "unrelated");
        assertAccepted(evaluate(claims, profile("Admin")), UserProfile.Role.ADMIN);
    }

    @Test
    public void returnedDisplayDataMatchesValidatedProfile() {
        SessionPolicy.Result result = evaluate(adminClaims(), profile("Admin"));
        assertEquals("Test User", result.user.getFullName());
        assertEquals(EMAIL, result.user.getEmail());
        assertEquals("1234567", result.user.getPhone());
    }

    @Test
    public void removingClaimOnRecheckDoesNotReuseEarlierAdminDecision() {
        assertAccepted(evaluate(adminClaims(), profile("Admin")), UserProfile.Role.ADMIN);
        assertDenied(evaluate(Collections.emptyMap(), profile("Admin")), SessionPolicy.Failure.ROLE_MISMATCH);
    }

    @Test
    public void profileRoleChangeOnRecheckDoesNotReuseEarlierAdminDecision() {
        assertAccepted(evaluate(adminClaims(), profile("Admin")), UserProfile.Role.ADMIN);
        assertDenied(evaluate(adminClaims(), profile("Patient")), SessionPolicy.Failure.ROLE_MISMATCH);
    }

    @Test
    public void publicPatientRegistrationStillProducesPatientSession() {
        assertAccepted(SessionPolicy.evaluatePatientRegistration(Collections.emptyMap(), profile("Patient"), EMAIL), UserProfile.Role.PATIENT);
    }

    @Test
    public void patientRegistrationCannotReturnAnAdminSessionEvenWithValidAdminPair() {
        assertDenied(SessionPolicy.evaluatePatientRegistration(adminClaims(), profile("Admin"), EMAIL), SessionPolicy.Failure.ROLE_MISMATCH);
    }

    @Test
    public void registrationAlsoRejectsMissingAndMismatchedProfiles() {
        assertDenied(SessionPolicy.evaluatePatientRegistration(Collections.emptyMap(), null, EMAIL), SessionPolicy.Failure.INVALID_PROFILE);
        assertDenied(SessionPolicy.evaluatePatientRegistration(adminClaims(), profile("Patient"), EMAIL), SessionPolicy.Failure.ROLE_MISMATCH);
    }

    private static SessionPolicy.Result evaluate(Map<String, Object> claims, Map<String, Object> profile) {
        return SessionPolicy.evaluate(claims, profile, EMAIL);
    }

    private static Map<String, Object> profile(String role) {
        Map<String, Object> data = new HashMap<>();
        data.put("fullName", "Test User");
        data.put("email", EMAIL);
        data.put("phone", "1234567");
        data.put("role", role);
        data.put("createdAt", new Timestamp(1L, 0));
        return data;
    }

    private static Map<String, Object> adminClaims() {
        return Collections.singletonMap("admin", true);
    }

    private static Map<String, Object> claimsFor(String role) {
        return "Admin".equals(role) ? adminClaims() : Collections.emptyMap();
    }

    private static void assertAccepted(SessionPolicy.Result result, UserProfile.Role role) {
        assertEquals(SessionPolicy.Failure.NONE, result.failure);
        assertNotNull(result.user);
        assertEquals(role, result.user.getRole());
    }

    private static void assertDenied(SessionPolicy.Result result, SessionPolicy.Failure failure) {
        assertEquals(failure, result.failure);
        assertNull(result.user);
    }
}
