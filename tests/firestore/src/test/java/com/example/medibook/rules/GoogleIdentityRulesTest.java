package com.example.medibook.rules;

import org.junit.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** Synthetic Firebase ID-token provider metadata, not real Google OAuth/device validation. */
public class GoogleIdentityRulesTest extends RulesFixtures {
    private static RulesClient google(String uid) {
        return authenticatedProvider(uid, map("email", "patient@example.invalid", "email_verified", true), "google.com");
    }
    private static Map<String, Object> complete() {
        return merge(profile(), map("dateOfBirth", "2000-02-29", "gender", "Female", "createdAt", Special.SERVER_TIME));
    }
    @Test public void googlePatientMustCreateAnOwnProfileBeforeProtectedScheduleAccess() {
        RulesClient fresh = google("new-google");
        missing(fresh.get("users/new-google")); denied(fresh.get(ENABLED));
        allowed(fresh.set("users/new-google", complete())); allowed(fresh.get(ENABLED));
        assertEquals(7, fields(fresh.get("users/new-google")).size());
    }
    @Test public void onboardingCannotChooseUidEmailOrAdminRole() {
        RulesClient fresh = google("new-google");
        denied(fresh.set("users/somebody-else", complete()));
        denied(fresh.set("users/new-google", merge(complete(), map("email", "other@example.invalid"))));
        denied(fresh.set("users/new-google", merge(complete(), map("role", "Admin"))));
        denied(fresh.set("users/new-google", merge(complete(), map("uid", "patient-a"))));
    }
    @Test public void providerAndVerifiedEmailNeverSubstituteForTrustedAdminClaim() {
        RulesClient fake = authenticatedProvider("patient-a", map("email", "patient@example.invalid", "email_verified", true, "role", "Admin"), "google.com");
        denied(fake.set("doctors/forged", newDoctor())); denied(fake.update("users/patient-a", map("role", "Admin")));
        allowed(authenticatedProvider("admin-a", map("admin", true), "google.com").get("doctors/inactive"));
    }
    @Test public void linkedProviderKeepsOriginalAppointmentAndContactOwnershipByUid() {
        LocalDate day = nextDhakaDate(DayOfWeek.MONDAY);
        seedBooking("owned", day, 540, 560, "patient-a", appointmentRecord(day, 540, 560, "Confirmed"));
        seed("users/patient-a/emergencyContacts/list", map("contacts", map(), "phoneKeys", map(), "updatedAt", OLD_TIME));
        for (String provider : new String[]{"password", "google.com"}) {
            RulesClient owner = authenticatedProvider("patient-a", map("email", "patient@example.invalid", "email_verified", true), provider);
            allowed(owner.get("appointments/owned")); allowed(owner.get("users/patient-a/emergencyContacts/list"));
        }
        // Same email on a DIFFERENT UID does not move/copy/authorize either record.
        denied(google("different-uid").get("appointments/owned"));
        denied(google("different-uid").get("users/patient-a/emergencyContacts/list"));
    }
    @Test public void googlePatientUsesTheUnchangedAtomicBookingAndCancellationContracts() {
        LocalDate day = nextDhakaDate(DayOfWeek.MONDAY); seedAvailableSlot(day, 540, 560);
        allowed(bookingBatch(google("patient-a"), "patient-a", "google-booking", day, 540, 560, 1, newAppointment(day, 540, 560)));
        allowed(cancellationBatch(google("patient-a"), "google-booking", day, 540, 560, 1, "available"));
        assertEquals("Cancelled", text(fields(google("patient-a").get("appointments/google-booking")), "status"));
    }
    @Test public void googleProfileCompletionPreservesExistingImmutableMetadata() {
        allowed(google("patient-a").update("users/patient-a", map("dateOfBirth", "2000-02-29", "gender", "Female")));
        assertEquals(java.time.Instant.parse(OLD_TIME.value), time(fields(google("patient-a").get("users/patient-a")), "createdAt"));
        denied(google("patient-a").update("users/patient-a", map("createdAt", Special.SERVER_TIME)));
        denied(google("patient-a").update("users/patient-a", map("email", "replacement@example.invalid")));
    }
    @Test public void credentialAndDerivedAgeFieldsCannotBeStoredInPatientProfile() {
        for (String field : new String[]{"idToken", "googleIdToken", "accessToken", "refreshToken", "password", "age"})
            denied(google("new-google").set("users/new-google", merge(complete(), map(field, "not-allowed"))));
    }
    @Test public void googleProviderCannotAccessRetiredHistoryOrOtherProfiles() {
        denied(google("patient-a").get("users/fake-admin"));
        denied(google("patient-a").get("users/patient-a/notifications/old"));
        denied(google("patient-a").queryPatientProfiles());
    }
}
