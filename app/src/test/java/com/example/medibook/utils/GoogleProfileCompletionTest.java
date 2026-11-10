package com.example.medibook.utils;

import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.example.medibook.utils.GoogleAccountPolicyTest.*;
import static com.example.medibook.utils.GoogleProfileCompletion.Invalid.*;
import static com.example.medibook.utils.GoogleProfileCompletion.Plan.*;

public class GoogleProfileCompletionTest {
    @Test public void validDetailsReuseExistingNamePhoneDobAndGenderContract() {
        assertEquals(NONE, GoogleProfileCompletion.validate(" Test Patient ", "+880 (1712) 345-678", "2000-02-29", "Female", NOW));
    }
    @Test public void missingBlankMultilineAndOversizedNamesAreRejected() {
        for (String name : new String[]{null, "", "   ", "Name\nOther", "x".repeat(101)}) assertEquals(NAME, GoogleProfileCompletion.validate(name, "1234567", "2000-01-01", "Male", NOW));
    }
    @Test public void missingAndInvalidPhoneAreRejected() {
        for (String phone : new String[]{null, "", "123456", "1234567890123456", "abc", "1234567;88"}) assertEquals(PHONE, GoogleProfileCompletion.validate("Name", phone, "2000-01-01", "Male", NOW));
    }
    @Test public void invalidFutureAndMissingBirthDatesAreRejected() {
        for (String dob : new String[]{null, "", "2000-1-01", "2001-02-29", "2026-11-10", "1899-12-31"}) assertEquals(BIRTH_DATE, GoogleProfileCompletion.validate("Name", "1234567", dob, "Male", NOW));
    }
    @Test public void onlyCurrentMaleFemaleGenderOptionsAreWritable() {
        for (String gender : new String[]{null, "", "Other", "male", "Prefer not to say"}) assertEquals(GENDER, GoogleProfileCompletion.validate("Name", "1234567", "2000-01-01", gender, NOW));
    }
    @Test public void ageUsesExistingDhakaCalendarAndIsNeverStored() {
        assertEquals(Integer.valueOf(26), PatientAge.years("2000-02-29", NOW));
        assertEquals(Integer.valueOf(25), PatientAge.years("2000-11-10", NOW));
        assertFalse(GoogleProfileCompletion.details("Name", "1234567", "2000-01-01", "Male", NOW).containsKey("age"));
    }
    @Test public void newProfilePlanIsCreateAndExistingIncompletePlanIsDisplayOnlyUpdate() {
        assertEquals(CREATE, GoogleProfileCompletion.plan(Collections.emptyMap(), null, EMAIL, NOW));
        assertEquals(UPDATE_DETAILS, GoogleProfileCompletion.plan(Collections.emptyMap(), profile(false), EMAIL, NOW));
    }
    @Test public void lostAcknowledgementRetryOrConcurrentCompletionIsNoOpNotOverwrite() {
        assertEquals(ALREADY_COMPLETE, GoogleProfileCompletion.plan(Collections.emptyMap(), profile(true), EMAIL, NOW));
        assertEquals(ALREADY_COMPLETE, GoogleProfileCompletion.plan(Collections.emptyMap(), profile(true), EMAIL, NOW));
    }
    @Test public void profileRoleEmailAndMetadataCannotBeRepairedThroughOnboarding() {
        Map<String, Object> invalid = profile(false); invalid.put("role", "Admin");
        assertEquals(DENY, GoogleProfileCompletion.plan(Collections.emptyMap(), invalid, EMAIL, NOW));
        invalid = profile(false); invalid.put("email", "foreign@example.invalid");
        assertEquals(DENY, GoogleProfileCompletion.plan(Collections.emptyMap(), invalid, EMAIL, NOW));
        invalid = profile(false); invalid.remove("createdAt");
        assertEquals(DENY, GoogleProfileCompletion.plan(Collections.emptyMap(), invalid, EMAIL, NOW));
    }
    @Test public void neitherAdminNorMalformedClaimsCanCreateAnOnboardingProfile() {
        for (Object admin : new Object[]{true, false, "true", null, 1}) assertEquals(DENY, GoogleProfileCompletion.plan(Collections.singletonMap("admin", admin), null, EMAIL, NOW));
    }
    @Test public void detailsPayloadContainsOnlyFourEditableFieldsAndIsIdempotent() {
        Map<String, Object> details = GoogleProfileCompletion.details(" Name ", "123-4567", "2000-01-01", "Female", NOW);
        assertEquals(4, details.size()); assertEquals("Name", details.get("fullName")); assertEquals("1234567", details.get("phone"));
        for (String field : new String[]{"uid", "email", "role", "createdAt", "googleIdToken", "accessToken"}) assertFalse(details.containsKey(field));
        assertEquals(details, GoogleProfileCompletion.details(" Name ", "123-4567", "2000-01-01", "Female", NOW));
    }
    @Test public void invalidInputNeverBuildsATransactionPayload() {
        assertThrows(IllegalArgumentException.class, () -> GoogleProfileCompletion.details("Name", "bad", "2000-01-01", "Male", NOW));
    }
}
