package com.example.medibook.rules;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** One-for-one replacement of the 22 Schedule policy cases; no production accounts or SDK bypass assertions. */
public class ScheduleRulesTest extends RulesFixtures {
    private Reply querySchedules(RulesClient client, String doctor, Boolean active) {
        return client.query("doctors/" + doctor, "schedules", active, false);
    }

    @Test public void anonymousScheduleAccessDenied() {
        RulesClient client = anonymous();
        denied(client.get(ENABLED));
        denied(querySchedules(client, "active", null));
        denied(querySchedules(client, "active", true));
        denied(client.set(schedulePath("active", "new"), newSchedule()));
        denied(client.update(ENABLED, map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(client.delete(ENABLED));
    }

    @Test public void booleanClaimAdminReadsAllSchedules() {
        RulesClient client = admin();
        allowed(client.get(ENABLED));
        allowed(client.get(DISABLED));
        allowed(client.get(schedulePath("inactive", "enabled")));
        assertEquals(2, documents(querySchedules(client, "active", null)).size());
        assertEquals(1, documents(querySchedules(client, "inactive", null)).size());
    }

    @Test public void validPatientReadsOnlyActiveSchedulesOfActiveDoctor() {
        RulesClient client = patient();
        allowed(client.get(ENABLED));
        denied(client.get(DISABLED));
        List<JsonObject> result = documents(querySchedules(client, "active", true));
        assertEquals(1, result.size());
        assertTrue(result.get(0).get("name").getAsString().endsWith("/schedules/enabled"));
        denied(querySchedules(client, "active", null));
        denied(querySchedules(client, "active", false));
    }

    @Test public void patientCannotReadInactiveOrMissingParents() {
        RulesClient client = patient();
        denied(client.get(schedulePath("inactive", "enabled")));
        denied(querySchedules(client, "inactive", true));
        denied(client.get(schedulePath("missing", "orphan")));
        denied(querySchedules(client, "missing", true));
    }

    @Test public void patientAccessTracksDoctorAndScheduleStatus() {
        RulesClient admin = admin();
        allowed(admin.update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(patient().get(ENABLED));
        allowed(admin.update("doctors/active", map("active", true, "updatedAt", Special.SERVER_TIME)));
        allowed(patient().get(ENABLED));
        allowed(admin.update(ENABLED, map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(patient().get(ENABLED));
        assertTrue(documents(querySchedules(patient(), "active", true)).isEmpty());
    }

    @Test public void patientScheduleWritesDenied() {
        RulesClient client = patient();
        denied(client.set(schedulePath("active", "new"), newSchedule()));
        denied(client.update(ENABLED, map("startTime", 555, "updatedAt", Special.SERVER_TIME)));
        denied(client.update(ENABLED, map("active", false, "updatedAt", Special.SERVER_TIME)));
        denied(client.update(DISABLED, map("active", true, "updatedAt", Special.SERVER_TIME)));
        denied(client.delete(ENABLED));
    }

    @Test public void missingProfilesAndProfileOnlyAdminsDenied() {
        for (String uid : new String[]{"missing-profile", "fake-admin"}) {
            RulesClient client = authenticated(uid, map("email", "patient@example.invalid"));
            denied(client.get(ENABLED));
            denied(querySchedules(client, "active", true));
            denied(client.set(schedulePath("active", "new"), newSchedule()));
        }
    }

    @Test public void malformedAdminClaimsGrantNeitherRole() {
        for (Object claim : new Object[]{false, "true", 1, null, map("value", true)}) {
            RulesClient client = authenticated("patient-a", map("admin", claim, "email", "patient@example.invalid"));
            denied(client.get(ENABLED));
            denied(querySchedules(client, "active", true));
            denied(client.set(schedulePath("active", "new"), newSchedule()));
            denied(client.update(ENABLED, map("active", false, "updatedAt", Special.SERVER_TIME)));
            denied(client.delete(ENABLED));
        }
    }

    @Test public void patientProfileMustBeCompleteAndValid() {
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (String field : profile().keySet()) {
            Map<String, Object> data = profile(); data.remove(field); invalid.add(data);
        }
        for (Map<String, Object> patch : Arrays.asList(map("fullName", ""), map("fullName", "  "), map("fullName", "N".repeat(101)),
                map("fullName", 123), map("email", "other@example.invalid"), map("email", 123), map("phone", "short"),
                map("phone", 123), map("role", "Admin"), map("createdAt", "not-a-timestamp"), map("admin", true))) {
            invalid.add(merge(profile(), patch));
        }
        for (Map<String, Object> data : invalid) {
            seed("users/patient-a", data); // Corrupt legacy fixture setup only, never an allow/deny assertion.
            denied(patient().get(ENABLED));
        }
    }

    @Test public void tokenEmailMustMatchProfile() {
        denied(authenticated("patient-a", map("email", "other@example.invalid")).get(ENABLED));
        denied(authenticated("patient-a", map()).get(ENABLED));
    }

    @Test public void adminCreatesSixFieldMinuteSchema() {
        RulesClient client = admin();
        String path = schedulePath("active", "new-id");
        allowed(client.set(path, newSchedule()));
        JsonObject saved = allowed(client.get(path)).getAsJsonObject();
        JsonObject data = saved.getAsJsonObject("fields");
        assertTrue(saved.get("name").getAsString().endsWith("/schedules/new-id"));
        assertEquals(newSchedule().keySet(), data.keySet());
        assertEquals(1, integer(data, "dayOfWeek"));
        assertEquals(540, integer(data, "startTime"));
        assertEquals(660, integer(data, "endTime"));
        assertEquals(20, integer(data, "slotDurationMinutes"));
        assertTrue(time(data, "updatedAt").isAfter(java.time.Instant.parse(OLD_TIME.value)));
    }

    @Test public void adminEditsStatusesWithoutChangingParentOrIdentity() {
        RulesClient client = admin();
        JsonObject originalDoctor = allowed(client.get("doctors/active")).getAsJsonObject();
        allowed(client.update(ENABLED, map("dayOfWeek", 7, "startTime", 600, "endTime", 780,
                "slotDurationMinutes", 30, "updatedAt", Special.SERVER_TIME)));
        allowed(client.update(ENABLED, map("active", false, "updatedAt", Special.SERVER_TIME)));
        assertFalse(bool(fields(client.get(ENABLED)), "active"));
        allowed(client.update(ENABLED, map("active", true, "updatedAt", Special.SERVER_TIME)));
        JsonObject saved = allowed(client.get(ENABLED)).getAsJsonObject();
        assertTrue(saved.get("name").getAsString().endsWith("/schedules/enabled"));
        assertEquals(7, integer(saved.getAsJsonObject("fields"), "dayOfWeek"));
        assertTrue(bool(saved.getAsJsonObject("fields"), "active"));
        assertEquals(2, documents(querySchedules(client, "active", null)).size());
        assertEquals(originalDoctor, allowed(client.get("doctors/active")));
    }

    @Test public void adminCannotDeleteOrAtomicallyMoveSchedule() {
        RulesClient client = admin();
        denied(client.delete(ENABLED));
        denied(client.delete(DISABLED));
        String moved = schedulePath("active", "moved-id");
        denied(client.commit(deleteWrite(ENABLED), write(moved, newSchedule(), false)));
        allowed(client.get(ENABLED));
        missing(client.get(moved));
    }

    @Test public void requiredAndAllowedScheduleFields() {
        RulesClient client = admin();
        for (String field : newSchedule().keySet()) {
            Map<String, Object> invalid = newSchedule(); invalid.remove(field);
            denied(client.set(schedulePath("active", "candidate"), invalid));
        }
        for (String field : new String[]{"id", "doctorId", "scheduleId", "createdAt", "timezone", "role", "appointments"}) {
            denied(client.set(schedulePath("active", "candidate"), merge(newSchedule(), map(field, "unexpected"))));
            denied(client.update(ENABLED, map(field, "unexpected", "updatedAt", Special.SERVER_TIME)));
        }
    }

    @Test public void weekdayTimeTypeRangeAndOrderingValidation() {
        for (Map<String, Object> patch : Arrays.asList(map("dayOfWeek", 0), map("dayOfWeek", 8), map("dayOfWeek", -1),
                map("dayOfWeek", 1.5), map("dayOfWeek", "Monday"), map("startTime", -1), map("startTime", 1440),
                map("startTime", 540.5), map("startTime", "09:00"), map("endTime", 1440), map("endTime", -1),
                map("endTime", 660.5), map("endTime", "11:00"), map("startTime", 600, "endTime", 600),
                map("startTime", 1380, "endTime", 60))) {
            denied(admin().set(schedulePath("active", "candidate"), merge(newSchedule(), patch)));
        }
    }

    @Test public void durationPolicyAndWindowFit() {
        for (Object slot : new Object[]{0, -5, 4, 7, 181, 185, 999, 15.5, "15", Double.NaN, Double.POSITIVE_INFINITY}) {
            denied(admin().set(schedulePath("active", "candidate"), merge(newSchedule(), map("slotDurationMinutes", slot))));
        }
        denied(admin().set(schedulePath("active", "candidate"), merge(newSchedule(), map("startTime", 540, "endTime", 550, "slotDurationMinutes", 15))));
    }

    @Test public void validMinuteWeekdayDurationBoundariesAndRemainder() {
        List<Map<String, Object>> valid = Arrays.asList(
                map("dayOfWeek", 1, "startTime", 0, "endTime", 5, "slotDurationMinutes", 5),
                map("dayOfWeek", 7, "startTime", 1434, "endTime", 1439, "slotDurationMinutes", 5),
                map("startTime", 0, "endTime", 180, "slotDurationMinutes", 180),
                map("startTime", 540, "endTime", 605, "slotDurationMinutes", 30), map("active", false));
        for (int index = 0; index < valid.size(); index++) {
            allowed(admin().set(schedulePath("active", "boundary-" + index), merge(newSchedule(), valid.get(index))));
        }
    }

    @Test public void wrongTypesAndNullsForEveryScheduleField() {
        for (String field : newSchedule().keySet()) {
            for (Object invalid : new Object[]{null, map("invalid", true)}) {
                denied(admin().set(schedulePath("active", "candidate"), merge(newSchedule(), map(field, invalid))));
            }
        }
        for (Object active : new Object[]{"true", 1, 0}) {
            denied(admin().set(schedulePath("active", "candidate"), merge(newSchedule(), map("active", active))));
        }
    }

    @Test public void serverTimestampAndVersionValidation() {
        RulesClient client = admin();
        denied(client.set(schedulePath("active", "candidate"), merge(newSchedule(), map("updatedAt", OLD_TIME))));
        denied(client.update(ENABLED, map("active", false)));
        denied(client.update(ENABLED, map("updatedAt", OLD_TIME)));
        denied(client.update(ENABLED, map("updatedAt", ZERO_TIME)));
        for (String field : newSchedule().keySet()) {
            denied(client.update(ENABLED, merge(map("updatedAt", Special.SERVER_TIME), map(field, Special.DELETE_FIELD))));
        }
    }

    @Test public void parentExistenceAndSafeIdentifierBounds() {
        RulesClient client = admin();
        denied(client.set(schedulePath("missing", "new"), newSchedule()));
        denied(client.update(schedulePath("missing", "orphan"), map("active", false, "updatedAt", Special.SERVER_TIME)));
        for (String id : new String[]{"invalid.id", "space id", "x".repeat(129), "বাংলা"}) {
            denied(client.set(schedulePath("active", id), newSchedule()));
        }
        allowed(client.set(schedulePath("active", "x".repeat(128)), newSchedule()));
        allowed(client.set(schedulePath("active", "schedule_1-AbC"), newSchedule()));
        seed("doctors/invalid.id", doctor(true));
        denied(client.set(schedulePath("invalid.id", "new"), newSchedule()));
        allowed(client.set(schedulePath("inactive", "new"), newSchedule()));
    }

    @Test public void unrelatedCollectionsAndCollectionGroupDenied() {
        for (RulesClient client : new RulesClient[]{admin(), patient(), anonymous()}) {
            // Top-level appointments now have dedicated Rules coverage in AppointmentRulesTest.
            for (String path : new String[]{"schedules/fixture", "unrelated/fixture", "doctors/active/private/fixture",
                    "doctors/active/schedules/enabled/appointments/fixture"}) {
                denied(client.get(path));
                denied(client.set(path, map("value", "fixture")));
            }
            denied(client.query("", "schedules", true, true));
        }
    }

    @Test public void overlappingNewIdsRemainAllowedByRules() {
        RulesClient client = admin();
        String first = schedulePath("active", "race-a");
        String second = schedulePath("active", "race-b");
        allowed(client.commit(write(first, merge(newSchedule(), map("startTime", 900, "endTime", 1020, "slotDurationMinutes", 30)), false),
                write(second, merge(newSchedule(), map("startTime", 960, "endTime", 1080, "slotDurationMinutes", 20)), false)));
        assertTrue(bool(fields(client.get(first)), "active"));
        assertTrue(bool(fields(client.get(second)), "active"));
    }
}
