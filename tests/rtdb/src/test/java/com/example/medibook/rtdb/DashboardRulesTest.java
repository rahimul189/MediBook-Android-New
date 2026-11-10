package com.example.medibook.rtdb;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.example.medibook.rtdb.RtdbClient.*;
import static org.junit.Assert.*;

/** End-user REST/CAS/SSE assertions. No live Firebase project or real credentials are used. */
public class DashboardRulesTest {
    @ClassRule public static final LocalDatabaseEmulator EMULATOR = new LocalDatabaseEmulator();
    private static final String SELF = "dashboards/patients/patient-a", OTHER = "dashboards/patients/patient-b", ADMIN = "dashboards/admin";
    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    private static Map<String, Object> time() { return map(".sv", "timestamp"); }
    private static Map<String, Object> next(long revision, String id) {
        long start = System.currentTimeMillis() + 3_600_000L;
        return map("revision", revision, "checkedAt", time(), "appointmentId", id,
                "startAt", id.isEmpty() ? 0L : start, "endAt", id.isEmpty() ? 0L : start + 1_200_000L);
    }
    private static Map<String, Object> analytics(long revision, int count) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate saturday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        long todayStart = today.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long weekStart = saturday.atStartOfDay(ZONE).toInstant().toEpochMilli();
        Map<String, Object> days = map();
        for (int i = 0; i < 7; i++) days.put("d" + i, i == (todayStart - weekStart) / 86_400_000L ? count : 0);
        return map("revision", revision, "checkedAt", time(), "weekStart", weekStart, "todayStart", todayStart,
                "days", days, "todayTotal", count, "weekTotal", count,
                "confirmedToday", count, "cancelledToday", 0, "completedToday", 0);
    }
    private static Map<String, Object> node(long revision, Map<String, Object> summary) { return map("revision", revision, "summary", summary); }
    @Before public void clear() { clearFixtures(); }

    @Test public void googleAndPasswordProvidersShareOnlyTheSameUidPrivateSummary() {
        RtdbClient google = actorProvider("patient-a", map("email", "same@example.invalid", "email_verified", true), "google.com");
        allowed(google.put(SELF, node(1, next(1, "owned"))));
        allowed(actorProvider("patient-a", map(), "password").get(SELF));
        denied(actorProvider("patient-b", map("email", "same@example.invalid", "email_verified", true), "google.com").get(SELF));
        denied(anonymous().get(SELF));
    }
    @Test public void googleProviderAndEditableRoleDoNotGrantGlobalAnalyticsPrivileges() {
        RtdbClient google = actorProvider("patient-a", map("email_verified", true, "role", "Admin"), "google.com");
        denied(google.get(ADMIN)); denied(google.put(ADMIN, node(1, analytics(1, 99))));
        allowed(actorProvider("admin-a", map("admin", true), "google.com").put(ADMIN, node(1, analytics(1, 2))));
    }
    @Test public void googleProviderRetainsExistingPrivateTokenAndSharedQueuePermissions() {
        RtdbClient google = actorProvider("patient-a", map("email_verified", true), "google.com");
        allowed(google.put("display/status/patient-a/owned", event()));
        denied(google.put("display/status/patient-b/foreign", event()));
        denied(google.put("display/queues/doctor-a/1794160800000/Q0601", queue()));
        denied(google.get("publishedAnnouncements")); denied(google.get("draftAnnouncements"));
    }

    @Test public void patientOwnSummaryIsPrivateAndAncestorsNeverGrantAccess() {
        allowed(patient().put(SELF, node(1, next(1, "booking-a")))); allowed(patient().get(SELF));
        for (RtdbClient client : new RtdbClient[]{patient(), anonymous(), admin(), actor("patient-b", map())}) {
            denied(client.get("")); denied(client.get("dashboards")); denied(client.get("dashboards/patients"));
        }
        denied(actor("patient-b", map()).get(SELF)); denied(actor("patient-b", map()).put(SELF, node(2, next(2, "foreign"))));
        denied(patient().get(OTHER)); denied(patient().put(OTHER, node(1, next(1, "foreign"))));
        denied(admin().get(SELF)); denied(admin().put(SELF, node(2, next(2, "foreign"))));
        denied(anonymous().get(SELF)); denied(anonymous().put(SELF, node(2, next(2, "foreign"))));
    }
    @Test public void onlyTrustedBooleanAdminsReadAndWriteGlobalAnalytics() {
        allowed(admin().put(ADMIN, node(1, analytics(1, 3)))); allowed(admin().get(ADMIN));
        for (RtdbClient client : new RtdbClient[]{patient(), anonymous(), actor("fake", map("role", "Admin")),
                actor("bad", map("admin", false)), actor("bad", map("admin", "true")), actor("bad", map("admin", 1))}) {
            denied(client.get(ADMIN)); denied(client.put(ADMIN, node(2, analytics(2, 9))));
            denied(client.patch(ADMIN, map("revision", 2))); denied(client.delete(ADMIN));
        }
    }
    @Test public void patientReservationThenPublicationMatchesAndroidTransactionShape() {
        allowed(patient().put(SELF, map("revision", 1)));
        allowed(patient().put(SELF + "/summary", next(1, "booking-a")));
        allowed(patient().patch(SELF, map("revision", 2)));
        allowed(patient().put(SELF + "/summary", next(2, "booking-b")));
        assertEquals("booking-b", allowed(patient().get(SELF + "/summary/appointmentId")).getAsString());
        allowed(patient().patch(SELF, map("revision", 3)));
        allowed(patient().put(SELF + "/summary", next(3, "")));
        assertEquals("", allowed(patient().get(SELF + "/summary/appointmentId")).getAsString());
    }
    @Test public void adminReservationAndImmutableExistingSummaryAllowRolloverRefresh() {
        allowed(admin().put(ADMIN, map("revision", 1)));
        allowed(admin().put(ADMIN + "/summary", analytics(1, 2)));
        allowed(admin().patch(ADMIN, map("revision", 2)));
        allowed(admin().put(ADMIN + "/summary", analytics(2, 4)));
        assertEquals(4, allowed(admin().get(ADMIN + "/summary/weekTotal")).getAsInt());
    }
    @Test public void priorDayProjectionCanBeReservedButNotRepublishedAsCurrent() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        Map<String, Object> old = analytics(1, 0);
        old.put("todayStart", yesterday.atStartOfDay(ZONE).toInstant().toEpochMilli());
        old.put("weekStart", yesterday.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)).atStartOfDay(ZONE).toInstant().toEpochMilli());
        old.put("checkedAt", System.currentTimeMillis() - 86_400_000L);
        seed(ADMIN, node(1, old));
        allowed(admin().patch(ADMIN, map("revision", 2)));
        old.put("revision", 2); old.put("checkedAt", time()); denied(admin().put(ADMIN + "/summary", old));
        allowed(admin().put(ADMIN + "/summary", analytics(2, 0)));
    }
    @Test public void sameRevisionCannotChangeGlobalCountsEvenWithAnOtherwiseValidPayload() {
        allowed(admin().put(ADMIN, node(1, analytics(1, 1))));
        denied(admin().put(ADMIN + "/summary", analytics(1, 5)));
    }
    @Test public void olderAsyncPublicationAndRevisionRollbackAreDenied() {
        allowed(patient().put(SELF, node(1, next(1, "first"))));
        allowed(patient().patch(SELF, map("revision", 2))); // Client A reserved before reading Firestore.
        allowed(patient().patch(SELF, map("revision", 3))); // Client B reserved later.
        denied(patient().put(SELF + "/summary", next(2, "stale-a")));
        allowed(patient().put(SELF + "/summary", next(3, "latest-b")));
        denied(patient().put(SELF, node(2, next(2, "rollback"))));
        denied(patient().put(SELF + "/summary", next(3, "same-revision-tamper")));
        assertEquals("latest-b", allowed(patient().get(SELF + "/summary/appointmentId")).getAsString());
    }
    @Test public void conditionalTransactionCannotOverwriteAChangedCursor() throws Exception {
        allowed(admin().put(ADMIN, node(1, analytics(1, 1))));
        Versioned before = admin().version(ADMIN);
        allowed(admin().patch(ADMIN, map("revision", 2)));
        assertEquals(412, admin().compareAndSet(ADMIN, node(2, analytics(2, 9)), before).code);
        Versioned current = admin().version(ADMIN);
        allowed(admin().compareAndSet(ADMIN, node(2, analytics(2, 2)), current));
    }
    @Test public void patientPayloadRejectsExtrasTypesMissingFieldsAndUnsafeIdentity() {
        for (Map<String, Object> patch : Arrays.asList(map("appointmentId", "bad/path"), map("appointmentId", true),
                map("startAt", -1), map("startAt", 1.5), map("endAt", 0), map("checkedAt", 1),
                map("patientPhone", "1234567"), map("revision", "1"))) {
            Map<String, Object> value = next(1, "booking"); value.putAll(patch); denied(patient().put(SELF, node(1, value)));
        }
        for (String key : next(1, "booking").keySet()) {
            Map<String, Object> value = next(1, "booking"); value.remove(key); denied(patient().put(SELF, node(1, value)));
        }
        denied(patient().put(SELF, map("revision", 1, "summary", next(1, "a"), "admin", true)));
        denied(patient().put(SELF, node(0, next(0, "a")))); denied(patient().put(SELF, node(2, next(2, "a"))));
    }
    @Test public void emptyPointerMustHaveZeroTimesAndPastPointerCannotBePublished() {
        Map<String, Object> wrongEmpty = next(1, "booking"); wrongEmpty.put("appointmentId", "");
        denied(patient().put(SELF, node(1, wrongEmpty)));
        Map<String, Object> past = next(1, "booking"); past.put("startAt", System.currentTimeMillis() - 1000);
        denied(patient().put(SELF, node(1, past)));
        allowed(patient().put(SELF, node(1, next(1, ""))));
        denied(patient().delete(SELF)); denied(patient().delete(SELF + "/summary"));
    }
    @Test public void analyticsSchemaRejectsWrongPeriodCountsUnknownFieldsAndFractionalData() {
        for (Map<String, Object> patch : Arrays.asList(map("todayTotal", 99), map("weekTotal", 99),
                map("confirmedToday", -1), map("cancelledToday", 1.5), map("completedToday", 1),
                map("weekStart", 0), map("todayStart", 0), map("weekTotal", "3"), map("patientUid", "private"), map("checkedAt", 1))) {
            Map<String, Object> value = analytics(1, 3); value.putAll(patch); denied(admin().put(ADMIN, node(1, value)));
        }
        for (String key : analytics(1, 0).keySet()) {
            Map<String, Object> value = analytics(1, 0); value.remove(key); denied(admin().put(ADMIN, node(1, value)));
        }
        Map<String, Object> missingDay = analytics(1, 0); ((Map<?, ?>) missingDay.get("days")).remove("d6");
        denied(admin().put(ADMIN, node(1, missingDay)));
        allowed(admin().put(ADMIN, node(1, analytics(1, 0)))); denied(admin().delete(ADMIN));
    }
    @Test public void obsoleteAnnouncementsAreDeniedWithoutDeletingTheirStoredData() {
        seed("publishedAnnouncements/old", map("title", "Archived notice"));
        seed("draftAnnouncements/old", map("title", "Archived draft"));
        for (RtdbClient client : new RtdbClient[]{patient(), admin(), anonymous()}) {
            for (String path : new String[]{"publishedAnnouncements", "draftAnnouncements", "publishedAnnouncements/old", "draftAnnouncements/old"}) {
                denied(client.get(path)); denied(client.put(path, map("title", "retired"))); denied(client.delete(path));
            }
        }
    }
    @Test public void privatePointerIsExplicitlyUntrustedBecauseRulesCannotReadFirestore() {
        // Allowed only within the caller's own private node. Android rejects this nonexistent ID
        // against the scoped, nearest-future Firestore query before it renders any appointment.
        allowed(patient().put(SELF, node(1, next(1, "not-a-real-firestore-booking"))));
        denied(patient().put(ADMIN, node(1, analytics(1, 99)))); denied(actor("patient-b", map()).get(SELF));
    }
    @Test public void liveAdminListenerReceivesSummaryChangesWithoutManualReload() throws Exception {
        allowed(admin().put(ADMIN, node(1, analytics(1, 2))));
        java.net.http.HttpResponse<java.io.InputStream> response = admin().listenValue(ADMIN);
        assertEquals(200, response.statusCode());
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dashboard-sse-test"); t.setDaemon(true); return t; });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertTrue(worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS).contains("weekTotal"));
            allowed(admin().patch(ADMIN, map("revision", 2)));
            worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS);
            allowed(admin().put(ADMIN + "/summary", analytics(2, 5)));
            String changed = worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS);
            assertTrue(changed, changed.contains("\"weekTotal\":5"));
        } finally { worker.shutdownNow(); }
    }
    @Test public void livePatientListenerReceivesThePromotedPointerAfterCancellation() throws Exception {
        allowed(patient().put(SELF, node(1, next(1, "first"))));
        java.net.http.HttpResponse<java.io.InputStream> response = patient().listenValue(SELF);
        assertEquals(200, response.statusCode());
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "patient-dashboard-sse"); t.setDaemon(true); return t; });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertTrue(worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS).contains("first"));
            allowed(patient().patch(SELF, map("revision", 2)));
            worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS);
            allowed(patient().put(SELF + "/summary", next(2, "second")));
            assertTrue(worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS).contains("second"));
        } finally { worker.shutdownNow(); }
    }
    private static String nextData(BufferedReader reader) throws Exception {
        String line; while ((line = reader.readLine()) != null) if (line.startsWith("data:") && !line.equals("data: null")) return line;
        throw new AssertionError("SSE ended before update");
    }
}
