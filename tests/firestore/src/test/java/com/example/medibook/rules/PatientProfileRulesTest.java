package com.example.medibook.rules;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** Profile audit: actual REST requests evaluate Rules, not Android form validation or Admin bypass. */
public class PatientProfileRulesTest extends RulesFixtures {
    private static final String OWN = "users/patient-a";
    private static final LocalDate DAY = nextDhakaDate(DayOfWeek.MONDAY);

    private static Map<String, Object> details() { return map("dateOfBirth", "2000-02-29", "gender", "Female"); }
    private static RulesClient fresh(String uid) { return authenticated(uid, map("email", "fresh@example.invalid")); }
    private static Map<String, Object> registration() {
        return merge(profile(), map("email", "fresh@example.invalid", "createdAt", Special.SERVER_TIME));
    }
    private static void deniedOnCreateAndUpdate(Map<String, Object> patch) {
        denied(fresh("fresh").set("users/fresh", merge(registration(), patch)));
        denied(patient().update(OWN, patch));
    }

    @Test public void legacyFiveFieldRegistrationStillWorks() {
        allowed(fresh("fresh").set("users/fresh", registration()));
        assertEquals(5, fields(fresh("fresh").get("users/fresh")).size());
    }

    @Test public void completeRegistrationAcceptsDateAndGenderWithServerMetadata() {
        allowed(fresh("fresh").set("users/fresh", merge(registration(), details())));
        JsonObject saved = fields(fresh("fresh").get("users/fresh"));
        assertEquals(7, saved.size()); assertEquals("2000-02-29", text(saved, "dateOfBirth"));
        assertEquals("Female", text(saved, "gender")); assertEquals("Patient", text(saved, "role"));
        assertTrue(time(saved, "createdAt").isAfter(java.time.Instant.parse(OLD_TIME.value)));
    }

    @Test public void optionalFieldsAreIndependentOnRegistration() {
        allowed(fresh("dob-only").set("users/dob-only", merge(registration(), map("dateOfBirth", "1990-12-31"))));
        allowed(fresh("gender-only").set("users/gender-only", merge(registration(), map("gender", "Female"))));
    }

    @Test public void legacyOwnerCanEditNameAndPhoneWithoutAddingPersonalDetails() {
        allowed(patient().update(OWN, map("fullName", "Edited patient", "phone", "+8801712345678")));
        JsonObject saved = fields(patient().get(OWN));
        assertEquals(5, saved.size()); assertEquals("Edited patient", text(saved, "fullName"));
        assertEquals("patient@example.invalid", text(saved, "email"));
        assertEquals(java.time.Instant.parse(OLD_TIME.value), time(saved, "createdAt"));
    }

    @Test public void ownerCanEditExactlyTheFourDisplayFields() {
        allowed(patient().update(OWN, merge(details(), map("fullName", "Edited patient", "phone", "7654321"))));
        JsonObject saved = fields(patient().get(OWN));
        assertEquals(7, saved.size()); assertEquals("Edited patient", text(saved, "fullName"));
        assertEquals("7654321", text(saved, "phone")); assertEquals("Patient", text(saved, "role"));
        assertEquals(java.time.Instant.parse(OLD_TIME.value), time(saved, "createdAt"));
    }

    @Test public void ownerCanAddOrRemoveOptionalFieldsIndependently() {
        allowed(patient().update(OWN, map("dateOfBirth", "1990-01-01")));
        allowed(patient().update(OWN, map("gender", "Female")));
        allowed(patient().update(OWN, map("dateOfBirth", Special.DELETE_FIELD)));
        assertEquals("Female", text(fields(patient().get(OWN)), "gender"));
        allowed(patient().update(OWN, map("gender", Special.DELETE_FIELD)));
        assertEquals(5, fields(patient().get(OWN)).size());
    }

    @Test public void currentGenderWireValuesWorkOnCreateAndUpdate() {
        int index = 0;
        for (String gender : new String[]{"Male", "Female"}) {
            String uid = "gender-" + index++;
            allowed(fresh(uid).set("users/" + uid, merge(registration(), map("gender", gender))));
            allowed(patient().update(OWN, map("gender", gender)));
        }
    }

    @Test public void legacyGenderValuesRemainReadableButCanNeverBeWritten() {
        for (String gender : new String[]{"Other", "Prefer not to say"}) {
            seed(OWN, merge(profile(), map("gender", gender)));
            allowed(patient().get(OWN));
            // Schedule/appointment/booking reads keep accepting a legacy stored profile.
            allowed(patient().get(ENABLED));
            assertEquals(gender, text(fields(patient().get(OWN)), "gender"));
            // Neither a new profile nor an update may introduce or retain a legacy value.
            denied(fresh("fresh").set("users/fresh", merge(registration(), map("gender", gender))));
            denied(patient().update(OWN, map("gender", gender)));
            denied(patient().update(OWN, map("fullName", "Edited patient")));
        }
    }

    @Test public void invalidGenderTypesCaseAndValuesAreDenied() {
        for (Object gender : new Object[]{null, true, 1, 1.5, OLD_TIME, map("value", "Male"), "", "male", "MALE",
                "Unknown", "Other ", " Male", "Prefer not to say ", "prefer not to say"})
            deniedOnCreateAndUpdate(map("gender", gender));
    }

    @Test public void birthDateMustBeAStringNotNullTimestampNumberOrMap() {
        for (Object dob : new Object[]{null, false, 20000101, 1.5, OLD_TIME, map("value", "2000-01-01")})
            deniedOnCreateAndUpdate(map("dateOfBirth", dob));
    }

    @Test public void birthDateRequiresTheExactIsoShape() {
        for (String dob : new String[]{"", "2000-1-01", "2000-01-1", "00-01-01", "2000/01/01", "2000-01-01T00:00:00Z",
                " 2000-01-01", "2000-01-01 ", "2000-01-01\n", "2000-01-01-extra"}) deniedOnCreateAndUpdate(map("dateOfBirth", dob));
    }

    @Test public void impossibleCalendarDatesAreDeniedNotNormalized() {
        for (String dob : new String[]{"2000-00-01", "2000-13-01", "2000-01-00", "2000-01-32", "2000-04-31", "2000-06-31",
                "2000-09-31", "2000-11-31", "2000-02-30", "2001-02-29", "1900-02-29", "2023-02-29", "2025-02-29"})
            deniedOnCreateAndUpdate(map("dateOfBirth", dob));
    }

    @Test public void validLeapDaysAndEarliestSupportedDateAreAccepted() {
        int index = 0;
        for (String dob : new String[]{"1900-01-01", "1900-02-28", "1996-02-29", "2000-02-29", "2024-02-29"}) {
            String uid = "valid-date-" + index++;
            allowed(fresh(uid).set("users/" + uid, merge(registration(), map("dateOfBirth", dob))));
            allowed(patient().update(OWN, map("dateOfBirth", dob)));
        }
    }

    @Test public void birthDatesBefore1900AreDenied() {
        for (String dob : new String[]{"0000-01-01", "0001-01-01", "1800-01-01", "1899-12-31"}) deniedOnCreateAndUpdate(map("dateOfBirth", dob));
    }

    @Test public void todayInDhakaIsValidButTomorrowAndFutureDatesAreDenied() {
        String today = LocalDate.now(HOSPITAL_ZONE).toString();
        allowed(fresh("today").set("users/today", merge(registration(), map("dateOfBirth", today))));
        allowed(patient().update(OWN, map("dateOfBirth", today)));
        deniedOnCreateAndUpdate(map("dateOfBirth", LocalDate.now(HOSPITAL_ZONE).plusDays(1).toString()));
        deniedOnCreateAndUpdate(map("dateOfBirth", "9999-12-31"));
    }

    @Test public void requiredCoreFieldsCannotBeMissingOrDeleted() {
        for (String key : profile().keySet()) {
            Map<String, Object> missing = registration(); missing.remove(key);
            denied(fresh("fresh").set("users/fresh", missing));
            denied(patient().update(OWN, map(key, Special.DELETE_FIELD)));
        }
    }

    @Test public void invalidNamesAndPhonesCannotBeCreatedOrUpdated() {
        for (Map<String, Object> patch : Arrays.asList(map("fullName", null), map("fullName", true), map("fullName", 1),
                map("fullName", ""), map("fullName", "  "), map("fullName", "N".repeat(101)), map("phone", null),
                map("phone", 1234567), map("phone", "123456"), map("phone", "1234567890123456"), map("phone", "123-4567"),
                map("phone", "1234567;99"))) deniedOnCreateAndUpdate(patch);
    }

    @Test public void extraIdentifiersAuthorizationAgeAndMetadataFieldsAreRejected() {
        for (String key : new String[]{"uid", "userId", "patientUid", "id", "admin", "isAdmin", "claims", "customClaims", "permissions",
                "age", "createdBy", "updatedAt", "ownerUid", "unknown"}) deniedOnCreateAndUpdate(map(key, "forbidden"));
    }

    @Test public void emailIsImmutableEvenWhenTheTokenMatchesTheRequestedNewEmail() {
        denied(patient().update(OWN, map("email", "replacement@example.invalid")));
        denied(authenticated("patient-a", map("email", "replacement@example.invalid")).update(OWN, map("email", "replacement@example.invalid")));
        denied(authenticated("patient-a", map()).update(OWN, map("fullName", "Edited")));
        assertEquals("patient@example.invalid", text(fields(patient().get(OWN)), "email"));
    }

    @Test public void profileRoleCannotBePromotedOrDemotedByItsOwner() {
        for (Object role : new Object[]{"Admin", "Doctor", "patient", "", null, true, 1}) deniedOnCreateAndUpdate(map("role", role));
        denied(authenticated("fake-admin", map("email", "patient@example.invalid")).update("users/fake-admin", map("role", "Patient")));
        denied(authenticated("fake-admin", map("email", "patient@example.invalid")).update("users/fake-admin", map("fullName", "Edited")));
        assertEquals("Patient", text(fields(patient().get(OWN)), "role"));
    }

    @Test public void createdAtMustBeServerTimeOnCreateAndIsImmutableAfterward() {
        for (Object stamp : new Object[]{OLD_TIME, ZERO_TIME, null, "not-a-timestamp", 1})
            denied(fresh("fresh").set("users/fresh", merge(registration(), map("createdAt", stamp))));
        denied(patient().update(OWN, map("createdAt", Special.SERVER_TIME)));
        denied(patient().update(OWN, map("createdAt", ZERO_TIME)));
        assertEquals(java.time.Instant.parse(OLD_TIME.value), time(fields(patient().get(OWN)), "createdAt"));
    }

    @Test public void corruptProtectedMetadataCannotBeRetainedThroughAnOtherwiseValidEdit() {
        seed(OWN, merge(profile(), map("createdAt", "not-a-timestamp")));
        denied(patient().update(OWN, map("fullName", "Edited")));
    }

    @Test public void nonOwnersCannotReadCreateUpdateOrMoveAnotherPatientsProfile() {
        RulesClient other = authenticated("patient-b", map("email", "patient@example.invalid"));
        denied(other.get(OWN)); denied(other.update(OWN, details()));
        denied(other.set(OWN, merge(profile(), details())));
        denied(fresh("fresh").set("users/someone-else", registration()));
        denied(patient().commit(deleteWrite(OWN), write("users/moved", merge(profile(), details()), false)));
        allowed(patient().get(OWN));
    }

    @Test public void anonymousUsersCannotAccessOrChangeAnyProfile() {
        denied(anonymous().get(OWN)); denied(anonymous().set("users/fresh", registration()));
        denied(anonymous().update(OWN, details())); denied(anonymous().delete(OWN)); denied(anonymous().query("users", null));
    }

    @Test public void falseMalformedOrPresentAdminClaimsDoNotGrantPatientProfileWrites() {
        for (Object claim : new Object[]{false, "true", 1, null, map("value", true)}) {
            RulesClient existing = authenticated("patient-a", map("email", "patient@example.invalid", "admin", claim));
            denied(existing.update(OWN, map("fullName", "Edited")));
            denied(authenticated("fresh", map("email", "fresh@example.invalid", "admin", claim)).set("users/fresh", registration()));
            denied(authenticated("other", map("admin", claim)).get(OWN));
        }
    }

    @Test public void profileOnlyAdminAndArbitraryRoleClaimsCannotReadOtherPatients() {
        for (RulesClient actor : new RulesClient[]{authenticated("fake-admin", map()), authenticated("no-profile", map()),
                authenticated("patient-b", map("role", "Admin")), authenticated("patient-b", map("isAdmin", true))}) {
            denied(actor.get(OWN)); denied(actor.update(OWN, details()));
        }
    }

    @Test public void trustedAdminCanGetLegacyAndExtendedPatientProfilesDirectly() {
        allowed(patient().update(OWN, details()));
        seed("users/patient-b", profile());
        assertEquals("2000-02-29", text(fields(admin().get(OWN)), "dateOfBirth"));
        assertEquals("Female", text(fields(admin().get(OWN)), "gender"));
        allowed(admin().get("users/patient-b"));
    }

    @Test public void trustedAdminCannotReadOtherAdminUnknownRoleOrMissingProfiles() {
        seed("users/admin-b", merge(profile(), map("role", "Admin")));
        seed("users/unknown-role", merge(profile(), map("role", "Doctor")));
        denied(admin().get("users/admin-b")); denied(admin().get("users/unknown-role")); denied(admin().get("users/missing"));
        Map<String, Object> noRole = profile(); noRole.remove("role"); seed("users/no-role", noRole);
        denied(admin().get("users/no-role"));
    }

    @Test public void ownProfileGetIncludingMissingProfileRemainsAvailableToSessionChecks() {
        seed("users/admin-a", merge(profile(), map("role", "Admin")));
        allowed(admin().get("users/admin-a")); allowed(authenticated("fake-admin", map()).get("users/fake-admin"));
        missing(authenticated("no-profile", map()).get("users/no-profile"));
    }

    @Test public void trustedAdminHasNoProfileCreateUpdateOrDeletePrivilege() {
        for (RulesClient actor : new RulesClient[]{admin(), authenticated("patient-a", map("admin", true, "email", "patient@example.invalid"))}) {
            denied(actor.update(OWN, map("fullName", "Admin edit"))); denied(actor.update(OWN, details()));
            denied(actor.set("users/new-patient", merge(registration(), details()))); denied(actor.delete(OWN));
        }
        denied(authenticated("fresh", map("admin", true, "email", "fresh@example.invalid")).set("users/fresh", registration()));
    }

    @Test public void profileListingAndDeletionRemainDeniedForEveryClientRole() {
        for (RulesClient actor : new RulesClient[]{patient(), admin(), anonymous()}) {
            denied(actor.query("users", null)); denied(actor.queryPatientProfiles()); denied(actor.query("", "users", null, true));
            denied(actor.delete(OWN));
        }
    }

    @Test public void noProfileSubcollectionOrUnrelatedCatchAllAccessIsGranted() {
        for (RulesClient actor : new RulesClient[]{patient(), admin(), anonymous()}) {
            for (String path : new String[]{"users/patient-a/private/metadata", "users/patient-a/claims/admin", "unrelated/profile"}) {
                denied(actor.get(path)); denied(actor.set(path, map("value", "forbidden")));
            }
        }
    }

    @Test public void otherwiseValidProfileEditCannotSmuggleAnUnauthorizedWriteInABatch() {
        denied(patient().commit(write(OWN, map("fullName", "Edited"), true),
                write("users/fake-admin", map("phone", "7654321"), true)));
        assertEquals("Patient fixture", text(fields(patient().get(OWN)), "fullName"));
    }

    @Test public void extendedProfilePreservesBookingCancellationsInventoryAndFixedSerials() {
        allowed(patient().update(OWN, details()));
        allowed(patient().get("doctors/active")); denied(patient().get("doctors/inactive"));
        allowed(patient().get(ENABLED)); denied(patient().get(DISABLED));
        assertEquals(1, documents(patient().query("doctors/active", "schedules", true, false)).size());
        seedAvailableSlot(DAY, 540, 560);
        String counter = inventoryDatePath("active", DAY), serial = counter + "/serials/" + inventorySlotId(DAY, 540, 560);
        allowed(admin().commit(write(serial, map("number", 1, "createdAt", Special.SERVER_TIME), false),
                write(counter, map("lastSerial", 1, "lastSlotId", inventorySlotId(DAY, 540, 560), "updatedAt", Special.SERVER_TIME), false)));
        allowed(patient().get(serial)); allowed(patient().get(inventorySlotPath("active", DAY, 540, 560)));
        allowed(bookingBatch(patient(), "patient-a", "profile-first", DAY, 540, 560, 1, newAppointment(DAY, 540, 560)));
        allowed(patient().get("appointments/profile-first")); assertEquals(1, documents(patient().queryAppointments("patient-a")).size());
        denied(authenticated("other", map("email", "patient@example.invalid")).get("appointments/profile-first"));
        allowed(cancellationBatch(patient(), "profile-first", DAY, 540, 560, 1, "available"));
        allowed(bookingBatch(patient(), "patient-a", "profile-second", DAY, 540, 560, 2, newAppointment(DAY, 540, 560)));
        allowed(cancellationBatch(admin(), "profile-second", DAY, 540, 560, 2, "available"));
        assertEquals(1, integer(fields(patient().get(serial)), "number"));
        assertEquals(17, fields(patient().get("appointments/profile-second")).size());
        assertEquals(12, fields(admin().get(inventorySlotPath("active", DAY, 540, 560))).size());
        assertEquals(6, fields(admin().get(bookingLockPath("active", DAY, 540, 560, 2))).size());
    }

    @Test public void editingProfileDoesNotRewriteHistoryOrBreakExistingCancellation() {
        seedAvailableSlot(DAY, 540, 560);
        allowed(bookingBatch(patient(), "patient-a", "before-edit", DAY, 540, 560, 1, newAppointment(DAY, 540, 560)));
        allowed(patient().update(OWN, merge(details(), map("fullName", "Updated patient", "phone", "7654321"))));
        assertEquals("Patient fixture", text(fields(patient().get("appointments/before-edit")), "patientName"));
        allowed(cancellationBatch(patient(), "before-edit", DAY, 540, 560, 1, "available"));
        denied(bookingBatch(patient(), "patient-a", "stale-profile", DAY, 540, 560, 2, newAppointment(DAY, 540, 560)));
        allowed(bookingBatch(patient(), "patient-a", "current-profile", DAY, 540, 560, 2,
                merge(newAppointment(DAY, 540, 560), map("patientName", "Updated patient", "patientPhone", "7654321"))));
    }

    @Test public void invalidOptionalDetailsCannotPassScheduleOrAppointmentProfileGates() {
        seedAvailableSlot(DAY, 540, 560);
        seed("appointments/existing", appointmentRecord(DAY, 540, 560, "Confirmed"));
        for (Map<String, Object> patch : Arrays.asList(map("dateOfBirth", "2001-02-29"), map("dateOfBirth", "9999-01-01"),
                map("gender", "unknown"), map("gender", null))) {
            seed(OWN, merge(profile(), patch));
            allowed(patient().get(OWN)); // Owner may inspect/correct invalid personal details, not protected metadata.
            denied(patient().get(ENABLED)); denied(patient().get("appointments/existing"));
            denied(patient().get(inventorySlotPath("active", DAY, 540, 560)));
            denied(bookingBatch(patient(), "patient-a", "bad-profile", DAY, 540, 560, 1, newAppointment(DAY, 540, 560)));
        }
        allowed(patient().update(OWN, details())); allowed(patient().get(ENABLED));
    }
}
