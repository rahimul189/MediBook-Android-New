package com.example.medibook.rtdb;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.example.medibook.rtdb.RtdbClient.*;
import static org.junit.Assert.*;

public class RealtimeRulesTest {
    @ClassRule public static final LocalDatabaseEmulator EMULATOR = new LocalDatabaseEmulator();
    private static final String SELF = "display/status/patient-a/booking-a";
    private static final String BOOKINGS = "display/bookings/booking-a";
    private static final String QUEUE = "display/queues/doctor-a/1794160800000/Q0601";
    @Before public void clear() { clearFixtures(); }

    @Test public void anonymousReallyEncountersRules() {
        denied(anonymous().put(SELF, event())); denied(anonymous().get(SELF));
        denied(anonymous().put(BOOKINGS, event())); denied(anonymous().get("display/queues/doctor-a/1794160800000"));
    }
    @Test public void patientWritesAndReadsOnlyPrivateHints() {
        allowed(patient().put(SELF, event())); allowed(patient().get(SELF));
        denied(actor("patient-b", map()).get(SELF)); denied(actor("patient-b", map()).put(SELF, event()));
        denied(patient().put("display/status/patient-b/booking-a", event()));
    }
    @Test public void patientCannotPublishAdminAlertsOrSharedQueue() {
        denied(patient().put(BOOKINGS, event())); denied(patient().get(BOOKINGS));
        denied(patient().put(QUEUE, queue())); denied(patient().latest("display/bookings", 20));
    }
    @Test public void booleanAdminClaimIsAnEndUserNotOwnerBypass() {
        allowed(admin().put(BOOKINGS, event())); allowed(admin().get(BOOKINGS));
        allowed(admin().put(SELF, event())); allowed(admin().put(QUEUE, queue()));
        denied(admin().put("display/bookings/bad", map("admin", true))); denied(admin().put("unrelated", map("value", true)));
    }
    @Test public void clientRolesAndMalformedClaimsGrantNoPrivilege() {
        denied(actor("patient-a", map("role", "Admin")).put(BOOKINGS, event()));
        for (Object claim : new Object[]{"true", 1, false}) {
            RtdbClient bad = actor("patient-a", map("admin", claim));
            denied(bad.put(BOOKINGS, event())); denied(bad.put(SELF, event())); denied(bad.get("display/queues/doctor-a/1794160800000"));
        }
    }
    @Test public void boundedQueriesMatchTheAndroidListeners() {
        allowed(patient().put(SELF, event())); allowed(admin().put(BOOKINGS, event()));
        allowed(patient().latest("display/status/patient-a", 20)); allowed(admin().latest("display/bookings", 20));
        denied(patient().get("display/status/patient-a")); denied(admin().get("display/bookings"));
        denied(patient().latest("display/status/patient-a", 21)); denied(admin().latest("display/bookings", 21));
        denied(actor("patient-b", map()).latest("display/status/patient-a", 20));
    }
    @Test public void noParentOrGlobalReadsEvenForAdmin() {
        for (RtdbClient client : new RtdbClient[]{patient(), admin(), anonymous()}) {
            denied(client.get("")); denied(client.get("display")); denied(client.get("display/status")); denied(client.get("display/queues"));
        }
    }
    @Test public void statusSchemaRequiresAllFieldsAndRejectsExtras() {
        for (String key : event().keySet()) { Map<String, Object> invalid = event(); invalid.remove(key); denied(patient().put(SELF, invalid)); }
        Map<String, Object> invalid = event(); invalid.put("patientPhone", "01700000000"); denied(patient().put(SELF, invalid));
        invalid = event(); invalid.put("role", "Admin"); denied(patient().put(SELF, invalid));
    }
    @Test public void wrongTypesTokensStatusesAndTimestampsDenied() {
        for (Map<String, Object> patch : java.util.Arrays.asList(map("status", "Completed"), map("status", "Pending"), map("doctorId", "bad.id"),
                map("dateKey", "2026/11/09"), map("queueToken", "Q0000"), map("queueToken", "Q1441"), map("queueToken", "Q1"),
                map("changedAt", "1000"), map("changedAt", -1), map("changedAt", 1.5), map("changedAt", System.currentTimeMillis() + 120000))) {
            Map<String, Object> invalid = event(); invalid.putAll(patch); denied(patient().put(SELF, invalid));
        }
    }
    @Test public void pathKeysAndQueueBoundsAreStrict() {
        denied(admin().put("display/bookings/" + "x".repeat(129), event()));
        denied(admin().put("display/queues/doctor-a/bad-date/Q0601", queue()));
        denied(admin().put("display/queues/doctor-a/1794160800000/Q1441", queue()));
        allowed(admin().put("display/queues/doctor-a/1794160800000/Q0001", queue()));
        allowed(admin().put("display/queues/doctor-a/1794160800000/Q1440", queue()));
    }
    @Test public void statusCanAdvanceButIdentityAndTimestampCannotRewind() {
        allowed(patient().put(SELF, event()));
        allowed(admin().patch(SELF, map("status", "Cancelled", "changedAt", 2000L)));
        denied(patient().patch(SELF, map("changedAt", 999L)));
        denied(patient().patch(SELF, map("doctorId", "different")));
        denied(patient().patch(SELF, map("dateKey", "1"))); denied(patient().patch(SELF, map("queueToken", "Q0631")));
    }
    @Test public void bookingsRetainCreationIdentityAndCannotBecomeStatusEvents() {
        allowed(admin().put(BOOKINGS, event()));
        denied(admin().patch(BOOKINGS, map("status", "Cancelled"))); denied(admin().patch(BOOKINGS, map("changedAt", 2000L)));
    }
    @Test public void deletionAndRequiredChildRemovalDenied() {
        allowed(patient().put(SELF, event())); allowed(admin().put(BOOKINGS, event())); allowed(admin().put(QUEUE, queue()));
        denied(patient().delete(SELF)); denied(admin().delete(BOOKINGS)); denied(admin().delete(QUEUE));
        denied(patient().patch(SELF, map("status", null)));
    }
    @Test public void queueContainsOnlySanitizedTokenStatusAndTime() {
        allowed(admin().put(QUEUE, queue()));
        assertEquals(queue().keySet(), allowed(patient().get(QUEUE)).getAsJsonObject().keySet());
        Map<String, Object> extra = queue(); extra.put("patientUid", "patient-a"); denied(admin().put(QUEUE, extra));
        denied(admin().patch(QUEUE, map("changedAt", 999L)));
        allowed(admin().patch(QUEUE, map("status", "Cancelled", "changedAt", 2000L)));
    }
    @Test public void maliciousMultiLocationUpdateIsAtomicAndCannotReachOtherFeeds() {
        denied(patient().patch("", map(SELF, event(), BOOKINGS, event(), QUEUE, queue())));
        assertTrue(allowed(patient().get(SELF)).isJsonNull());
    }
    @Test public void rulesCannotProveFirestoreSoPrivateHintsAreExplicitlyUntrusted() {
        // Intentionally allowed in a PRIVATE branch even though no Firestore event exists.
        // The Android verifier rejects this; no shared/Admin branch can consume it as authority.
        allowed(patient().put(SELF, event()));
        denied(patient().put(BOOKINGS, event())); denied(actor("patient-b", map()).get(SELF));
    }
    @Test public void realSseListenerReceivesInitialAndCancellationUpdates() throws Exception {
        allowed(patient().put(SELF, event()));
        java.net.http.HttpResponse<java.io.InputStream> response = patient().listen("display/status/patient-a");
        assertEquals(200, response.statusCode());
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "rtdb-sse-test"); t.setDaemon(true); return t; });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String initial = worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS);
            assertTrue(initial, initial.contains("Confirmed"));
            allowed(admin().patch(SELF, map("status", "Cancelled", "changedAt", 2000L)));
            String changed = worker.submit(() -> nextData(reader)).get(10, TimeUnit.SECONDS);
            assertTrue(changed, changed.contains("Cancelled"));
        } finally { worker.shutdownNow(); }
    }
    private static String nextData(BufferedReader reader) throws Exception {
        String line; while ((line = reader.readLine()) != null) if (line.startsWith("data:") && !line.equals("data: null")) return line;
        throw new AssertionError("SSE ended before an update");
    }
    @Test public void transportRejectsProductionOtherNamespacesAndRedirectTargets() {
        for (String url : new String[]{"https://medibook-2c3a4-default-rtdb.firebaseio.com/.json?ns=" + NS,
                "http://127.0.0.1:9000/.json?ns=medibook-2c3a4", "http://localhost:9000/.json?ns=" + NS,
                ORIGIN + "/.json?ns=" + NS + "&auth=owner", ORIGIN + "/.json?ns=" + NS + "#redirect"}) {
            assertThrows(IllegalArgumentException.class, () -> requireLocal(URI.create(url)));
        }
    }
}
