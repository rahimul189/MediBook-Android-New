package com.example.medibook.rules;

import org.junit.Test;

import java.net.URI;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** These would fail with open rules, missing auth, an Admin SDK or accidental owner-token assertions. */
public class ClientRulesCanaryTest extends RulesFixtures {
    @Test public void retiredInAppHistoryIsDeniedWithoutAffectingAppointmentReads() {
        String path = "users/patient-a/notifications/booking_old";
        seed(path, map("title", "Archived history"));
        for (RulesClient client : new RulesClient[]{patient(), admin(), anonymous()}) {
            denied(client.get(path)); denied(client.set(path, map("title", "new")));
            denied(client.update(path, map("read", true))); denied(client.delete(path));
            denied(client.query("users/patient-a", "notifications", null, false));
        }
        allowed(patient().get("users/patient-a"));
    }
    @Test public void manualDeploymentAndEmulatorConfigsUseTheSameRulesButDifferentProjects() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("medibook.projectRoot"));
        com.google.gson.JsonObject deploy = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("firebase.json"))).getAsJsonObject();
        com.google.gson.JsonObject emulator = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("firebase.emulators.json"))).getAsJsonObject();
        com.google.gson.JsonObject aliases = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve(".firebaserc"))).getAsJsonObject();
        assertEquals("firestore.rules", deploy.getAsJsonObject("firestore").get("rules").getAsString());
        assertEquals(deploy.getAsJsonObject("firestore"), emulator.getAsJsonObject("firestore"));
        assertEquals("medibook-2c3a4", aliases.getAsJsonObject("projects").get("default").getAsString());
        assertEquals("demo-medibook", PROJECT); assertNotEquals(PROJECT, aliases.getAsJsonObject("projects").get("default").getAsString());
        assertEquals(java.util.Set.of("firestore", "database"), deploy.keySet());
        assertEquals("database.rules.json", deploy.getAsJsonObject("database").get("rules").getAsString());
    }
    @Test public void anonymousRequestsReallyEncounterRules() {
        denied(anonymous().get("doctors/active"));
        denied(anonymous().set("doctors/new", newDoctor()));
    }

    @Test public void customClaimAdminIsAnEndUserNotTheFixtureBypass() {
        allowed(admin().set("doctors/new", newDoctor()));
        denied(admin().set("unrelated/canary", map("value", "fixture")));
        denied(admin().delete("doctors/active"));
        denied(authenticated("patient-a", map("admin", "true")).set("doctors/forged", newDoctor()));
    }

    @Test public void patientIdentityIsEnforcedNotJustAnAuthenticatedFlag() {
        allowed(patient().get("users/patient-a"));
        allowed(patient().get("doctors/active"));
        denied(authenticated("different-patient", map("email", "different@example.invalid")).get("users/patient-a"));
        denied(patient().update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
    }

    @Test public void transportRejectsProductionDifferentDatabasesAndAlternateEndpointsBeforeSending() {
        for (String target : new String[]{
                "https://firestore.googleapis.com/v1/" + DOCUMENTS,
                "http://localhost:8080/v1/" + DOCUMENTS,
                "http://127.0.0.1:8081/v1/" + DOCUMENTS,
                ORIGIN + "/v1/projects/medibook-2c3a4/databases/(default)/documents",
                ORIGIN + "/v1/projects/demo-medibook/databases/other/documents",
                ORIGIN + "/v1/" + DOCUMENTS + "-other",
                ORIGIN + "/v1/" + DOCUMENTS + "/../other",
                ORIGIN + "/v1/" + DOCUMENTS + "?project=other"}) {
            assertThrows(IllegalArgumentException.class, () -> requireLocal(URI.create(target)));
        }
        requireLocal(URI.create(ORIGIN + "/v1/" + DOCUMENTS + ":commit"));
    }
}
