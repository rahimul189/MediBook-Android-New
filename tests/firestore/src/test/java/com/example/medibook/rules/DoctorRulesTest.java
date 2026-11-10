package com.example.medibook.rules;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** One-for-one replacement of the 15 Doctor/Patient policy cases, with all invalid-value loops. */
public class DoctorRulesTest extends RulesFixtures {
    @Test public void optionalHttpsDoctorImagePreservesLegacyAndAccessControls() {
        String url = "https://res.cloudinary.com/demo/image/upload/w_200,c_fill/doctor.jpg";
        allowed(admin().set("doctors/photo", merge(newDoctor(), map("imageUrl", url))));
        assertEquals(url, fields(patient().get("doctors/photo")).getAsJsonObject("imageUrl").get("stringValue").getAsString());
        allowed(patient().get("doctors/active")); // Legacy eight-field document remains readable.
        allowed(admin().update("doctors/photo", map("imageUrl", "", "updatedAt", Special.SERVER_TIME)));
        allowed(admin().update("doctors/photo", map("imageUrl", Special.DELETE_FIELD, "updatedAt", Special.SERVER_TIME)));
        denied(patient().update("doctors/active", map("imageUrl", url, "updatedAt", Special.SERVER_TIME)));
        denied(anonymous().get("doctors/photo"));
        denied(admin().update("doctors/photo", map("createdAt", Special.SERVER_TIME, "imageUrl", url, "updatedAt", Special.SERVER_TIME)));
    }
    @Test public void doctorImageUrlRejectsInvalidSchemesTypesAndUnboundedInput() {
        for (Object value : new Object[]{null, 123, false, "http://example.org/a.jpg", "data:image/png;base64,AAAA",
                "https://user:secret@example.org/photo", "https://example.org:443/photo", "https://example.org/photo#fragment",
                "https://example.org/bad image", "https://example.org/\nphoto", "https://", "https://example.org/" + "a".repeat(2048)}) {
            denied(admin().set("doctors/bad-photo", merge(newDoctor(), map("imageUrl", value))));
        }
        allowed(admin().set("doctors/blank-photo", merge(newDoctor(), map("imageUrl", ""))));
        allowed(admin().set("doctors/query-photo", merge(newDoctor(), map("imageUrl", "https://images.example.org/doctor%20one.jpg?width=200&height=200"))));
    }
    @Test public void patientProfileQueryUsesActiveStatusAndStableDocumentId() {
        assertEquals(1, documents(patient().activeDoctorById("active")).size());
        // Exact-name queries can evaluate the actual resource: unavailable paths are denied,
        // not guaranteed to return an empty query. The Patient screen must clear its profile.
        denied(patient().activeDoctorById("inactive"));
        denied(patient().activeDoctorById("missing"));
        denied(anonymous().activeDoctorById("active"));
        allowed(admin().update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(patient().activeDoctorById("active"));
    }
    @Test public void anonymousDoctorAccessDenied() {
        RulesClient client = anonymous();
        denied(client.get("doctors/active"));
        denied(client.query("doctors", null));
        denied(client.query("doctors", true));
        denied(client.set("doctors/new", newDoctor()));
    }

    @Test public void patientReadsActiveDoctorsWithFilteredQueriesOnly() {
        RulesClient client = patient();
        allowed(client.get("doctors/active"));
        denied(client.get("doctors/inactive"));
        assertEquals(1, documents(client.query("doctors", true)).size());
        denied(client.query("doctors", null));
        denied(client.query("doctors", false));
    }

    @Test public void missingProfilesAndProfileOnlyAdminsDenied() {
        for (String uid : new String[]{"no-profile", "fake-admin"}) {
            RulesClient client = authenticated(uid, map());
            denied(client.get("doctors/active"));
            denied(client.set("doctors/new", newDoctor()));
        }
    }

    @Test public void malformedAdminClaimsGrantNeitherRole() {
        for (Object claim : new Object[]{"true", 1, false, null}) {
            RulesClient client = authenticated("patient-a", map("admin", claim));
            denied(client.get("doctors/active"));
            denied(client.set("doctors/new", newDoctor()));
            denied(client.update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        }
    }

    @Test public void booleanClaimAdminReadsBothStatusesAndLists() {
        RulesClient client = admin();
        allowed(client.get("doctors/active"));
        allowed(client.get("doctors/inactive"));
        assertEquals(2, documents(client.query("doctors", null)).size());
    }

    @Test public void patientDoctorWritesDenied() {
        RulesClient client = patient();
        denied(client.set("doctors/new", newDoctor()));
        denied(client.update("doctors/active", map("name", "Changed", "updatedAt", Special.SERVER_TIME)));
        denied(client.update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(client.update("doctors/inactive", map("active", true, "updatedAt", Special.SERVER_TIME)));
        denied(client.delete("doctors/active"));
    }

    @Test public void adminCreatesEightFieldSchemaWithServerTimestamps() {
        RulesClient client = admin();
        allowed(client.set("doctors/new", newDoctor()));
        JsonObject data = fields(client.get("doctors/new"));
        assertEquals(newDoctor().keySet(), data.keySet());
        assertEquals(50025L, integer(data, "consultationFee"));
        assertEquals(time(data, "createdAt"), time(data, "updatedAt"));
        assertTrue(time(data, "createdAt").isAfter(java.time.Instant.parse(OLD_TIME.value)));
    }

    @Test public void adminEditsAndChangesStatusWithoutChangingIdentity() {
        RulesClient client = admin();
        allowed(client.update("doctors/active", map("name", "Edited fixture", "updatedAt", Special.SERVER_TIME)));
        allowed(client.update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        allowed(client.update("doctors/active", map("active", true, "updatedAt", Special.SERVER_TIME)));
        JsonObject saved = allowed(client.get("doctors/active")).getAsJsonObject();
        JsonObject data = saved.getAsJsonObject("fields");
        assertTrue(saved.get("name").getAsString().endsWith("/doctors/active"));
        assertEquals(java.time.Instant.parse(OLD_TIME.value), time(data, "createdAt"));
        assertTrue(time(data, "updatedAt").isAfter(time(data, "createdAt")));
        denied(client.delete("doctors/active"));
    }

    @Test public void requiredAndAllowedDoctorFields() {
        RulesClient client = admin();
        for (String field : newDoctor().keySet()) {
            Map<String, Object> invalid = newDoctor(); invalid.remove(field);
            denied(client.set("doctors/candidate", invalid));
        }
        for (String field : new String[]{"id", "role", "ownerUid", "schedule"}) {
            denied(client.set("doctors/candidate", merge(newDoctor(), map(field, "unexpected"))));
        }
    }

    @Test public void doctorTypesTextControlsLengthsAndFeeBounds() {
        List<Map<String, Object>> invalid = Arrays.asList(
                map("name", 12), map("specialization", 12), map("qualifications", null), map("biography", false), map("active", "true"),
                map("name", ""), map("name", "  "), map("name", "\u00a0\u2003"), map("specialization", "\n"),
                map("qualifications", "Q\u0000Q"), map("name", "A\nB"), map("biography", "line one\nline\u0007two"),
                map("name", "N".repeat(101)), map("specialization", "S".repeat(101)), map("qualifications", "Q".repeat(201)),
                map("biography", "B".repeat(1001)), map("consultationFee", -1), map("consultationFee", 100000000),
                map("consultationFee", 1.5), map("consultationFee", "50025"), map("consultationFee", Double.NaN),
                map("consultationFee", Double.POSITIVE_INFINITY));
        for (Map<String, Object> patch : invalid) denied(admin().set("doctors/candidate", merge(newDoctor(), patch)));
    }

    @Test public void doctorValidBoundaryValues() {
        RulesClient client = admin();
        allowed(client.set("doctors/boundary", merge(newDoctor(), map("name", "N".repeat(100),
                "specialization", "S".repeat(100), "qualifications", "Q".repeat(200), "biography", "B".repeat(1000), "consultationFee", 99999999))));
        allowed(client.set("doctors/free", merge(newDoctor(), map("consultationFee", 0, "biography", "First\r\nSecond"))));
    }

    @Test public void doctorTimestampAndImmutableFieldValidation() {
        RulesClient client = admin();
        denied(client.set("doctors/new", merge(newDoctor(), map("createdAt", OLD_TIME))));
        denied(client.set("doctors/new", merge(newDoctor(), map("updatedAt", OLD_TIME))));
        denied(client.update("doctors/active", map("createdAt", Special.SERVER_TIME, "updatedAt", Special.SERVER_TIME)));
        denied(client.update("doctors/active", map("name", "Changed")));
        denied(client.update("doctors/active", map("updatedAt", OLD_TIME)));
        denied(client.update("doctors/active", map("biography", Special.DELETE_FIELD, "updatedAt", Special.SERVER_TIME)));
        denied(client.update("doctors/active", map("id", "other", "updatedAt", Special.SERVER_TIME)));
        denied(client.update("doctors/active", map("active", 1, "updatedAt", Special.SERVER_TIME)));
    }

    @Test public void patientProfileOwnershipAndRoleProtection() {
        RulesClient client = authenticated("new-patient", map("email", "new@example.invalid"));
        Map<String, Object> data = merge(profile(), map("email", "new@example.invalid", "createdAt", Special.SERVER_TIME));
        allowed(client.set("users/new-patient", data));
        allowed(client.get("users/new-patient"));
        denied(client.get("users/patient-a"));
        denied(client.query("users", null));
        denied(client.update("users/new-patient", map("role", "Admin")));
        denied(client.delete("users/new-patient"));
        denied(client.set("users/someone-else", data));
    }

    @Test public void patientProfileValidationAndAdminWriteDenial() {
        RulesClient client = authenticated("fresh", map("email", "fresh@example.invalid"));
        Map<String, Object> data = merge(profile(), map("email", "fresh@example.invalid", "createdAt", Special.SERVER_TIME));
        for (Map<String, Object> patch : Arrays.asList(map("role", "Admin"), map("admin", true), map("email", "wrong@example.invalid"),
                map("phone", "short"), map("fullName", "  "), map("fullName", "N".repeat(101)), map("createdAt", OLD_TIME))) {
            denied(client.set("users/fresh", merge(data, patch)));
        }
        // Email/profile fields are otherwise valid, isolating the !isAdmin restriction on self-registration.
        RulesClient adminWithEmail = authenticated("admin-a", map("admin", true, "email", "admin@example.invalid"));
        denied(adminWithEmail.set("users/admin-a", merge(data, map("email", "admin@example.invalid"))));
        denied(admin().update("users/patient-a", map("role", "Admin")));
        denied(admin().delete("users/patient-a"));
    }

    @Test public void unrelatedCollectionsAndDoctorSubcollectionsDenied() {
        // Top-level appointments now have dedicated Rules coverage in AppointmentRulesTest.
        for (RulesClient client : new RulesClient[]{patient(), admin(), anonymous()}) {
            for (String path : new String[]{"schedules/fixture", "unrelated/fixture", "doctors/active/private/fixture"}) {
                denied(client.get(path));
                denied(client.set(path, map("value", "fixture")));
            }
        }
    }
}
