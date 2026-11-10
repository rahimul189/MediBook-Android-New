package com.example.medibook.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentDetailsActivity;
import com.example.medibook.activities.patient.EmergencyContactActivity;
import com.example.medibook.local.LocalPatientServices;
import com.example.medibook.local.PrivatePatientStore;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.DhakaDateTime;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Regression checks for the private account binding that both Emergency Contacts and the
 * appointment reminder card depend on ({@link LocalPatientServices#epoch(String)}).
 *
 * <p>Reproduces Logout → Login with the same account, a switch to another account and the state a
 * cold start restores. A sign-in's own Firebase UID event must never erase the freshly bound
 * account, otherwise contact sync reports "Could not sync contacts" with the add button disabled
 * and the reminder card is removed from Appointment Details.</p>
 *
 * <p>Requires local Auth at {@code 127.0.0.1:9099} and Firestore at {@code 127.0.0.1:8080}
 * reached through {@code adb reverse} (like {@link AuthenticatedAppointmentsTest}). Uses synthetic
 * {@code *.e2e@example.invalid} accounts only; never production data.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class ReloginLocalSessionTest {
    private static final String PASSWORD = "TestPass123!";
    private static final String PATIENT_EMAIL = "relogin.patient.e2e@example.invalid";
    private static final String OTHER_EMAIL = "relogin.other.e2e@example.invalid";
    private static final String PHONE = "01712345678";
    private static final long TIMEOUT = 45_000L;
    private static final String APPOINTMENT_A = "reloginAppointmentA";
    private static final String APPOINTMENT_B = "reloginAppointmentB";

    private static boolean emulatorsConfigured;
    private Instrumentation instrumentation;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        if (!emulatorsConfigured) {
            FirebaseAuth.getInstance().useEmulator("127.0.0.1", 9099);
            FirebaseFirestore.getInstance().useEmulator("127.0.0.1", 8080);
            emulatorsConfigured = true;
        }
        // A previous run may have left the client offline; these checks need authoritative reads.
        Tasks.await(FirebaseFirestore.getInstance().enableNetwork(), TIMEOUT, TimeUnit.MILLISECONDS);
    }

    @After public void tearDown() throws Exception {
        if (!emulatorsConfigured) return;
        instrumentation.runOnMainSync(() -> {
            AuthRepository.getInstance().signOut();
            FirebaseAuth.getInstance().signOut();
        });
        for (String id : new String[]{APPOINTMENT_A, APPOINTMENT_B}) {
            try {
                Tasks.await(FirebaseFirestore.getInstance().collection("appointments").document(id).delete(),
                        TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (RuntimeException | ExecutionException | InterruptedException | TimeoutException ignored) {
                // Cleanup only; a leftover synthetic document never fails the regression check.
            }
        }
    }

    /**
     * The reported device flow: launch, Logout, Login with the same account. Emergency Contacts must
     * reach a synced state (which enables Add) and the reminder card must stay available.
     */
    @Test public void sameAccountReLoginKeepsContactsSyncAndReminderCard() throws Exception {
        String uid = ensureAccount(PATIENT_EMAIL, "ReLogin Patient");
        seedAppointment(uid, APPOINTMENT_A, "Dr. ReLogin");

        logIn(PATIENT_EMAIL);
        assertEquals("Login must verify the synthetic patient", uid, signedInUid());
        assertNotNull("A verified login must have a bound local epoch",
                LocalPatientServices.get().epoch(uid));
        assertContactsReady();
        assertReminderCardVisible(APPOINTMENT_A, true);

        logOut();
        logIn(PATIENT_EMAIL);
        assertEquals("Re-login must verify the same account", uid, signedInUid());

        assertContactsReady();
        assertReminderCardVisible(APPOINTMENT_A, true);
        assertNotNull("Re-login must keep the local account binding",
                LocalPatientServices.get().epoch(uid));

        // Exactly the state Application.onCreate restores on a cold start: same owner and epoch, so
        // initialize() rebinds the same account and both features keep working after a restart.
        SharedPreferences persisted = context().getSharedPreferences(PrivatePatientStore.FILE, Context.MODE_PRIVATE);
        assertEquals("A cold start must restore the signed-in account", uid, persisted.getString("owner", null));
        assertEquals("A cold start must restore the live epoch",
                LocalPatientServices.get().epoch(uid), persisted.getString("epoch", null));
    }

    /** Logout then a different account: the new account binds, the old private data does not leak. */
    @Test public void accountSwitchBindsTheNewAccountOnly() throws Exception {
        String first = ensureAccount(PATIENT_EMAIL, "ReLogin Patient");
        String other = ensureAccount(OTHER_EMAIL, "Other Patient");
        seedAppointment(first, APPOINTMENT_A, "Dr. ReLogin");
        seedAppointment(other, APPOINTMENT_B, "Dr. Other");

        logIn(PATIENT_EMAIL);
        assertReminderCardVisible(APPOINTMENT_A, true);

        logOut();
        logIn(OTHER_EMAIL);
        assertEquals("The switched account must be verified", other, signedInUid());
        assertNotNull("The switched account must be bound", LocalPatientServices.get().epoch(other));
        assertNull("The previous account must not stay bound", LocalPatientServices.get().epoch(first));
        assertTrue("The previous account's contacts must be erased",
                LocalPatientServices.get().contacts(first).isEmpty());

        assertContactsReady();
        assertReminderCardVisible(APPOINTMENT_B, true);
    }

    // ---- Emergency Contacts screen -----------------------------------------------------------

    /**
     * The screen only enables Add and hides the retry row once the Firestore list read/migration
     * succeeded for the bound epoch; the reported failure shows the error row and a disabled Add.
     */
    private void assertContactsReady() {
        try (ActivityScenario<EmergencyContactActivity> scenario =
                     ActivityScenario.launch(EmergencyContactActivity.class)) {
            Activity screen = awaitContent(scenario, R.id.content, R.id.sessionLoading);
            boolean synced = await(screen, activity -> {
                View add = activity.findViewById(R.id.addButton);
                View retry = activity.findViewById(R.id.syncRetryButton);
                return add != null && add.isEnabled()
                        && (retry == null || retry.getVisibility() != View.VISIBLE);
            });
            if (!synced) {
                fail("Emergency Contacts did not reach a synced state after login: "
                        + describe(screen, R.id.syncStatus, R.id.syncRetryButton, R.id.addButton));
            }
            String status = text(screen, R.id.syncStatus);
            assertFalse("Contacts must not report the sync error: " + status,
                    context().getString(R.string.contact_sync_error).equals(status));
        }
    }

    // ---- Appointment Details reminder card ---------------------------------------------------

    private void assertReminderCardVisible(String appointmentId, boolean expected) {
        Intent intent = new Intent(context(), AppointmentDetailsActivity.class)
                .putExtra("appointmentId", appointmentId);
        try (ActivityScenario<AppointmentDetailsActivity> scenario = ActivityScenario.launch(intent)) {
            Activity screen = awaitContent(scenario, R.id.content, R.id.sessionLoading);
            boolean loaded = await(screen, activity -> {
                TextView details = activity.findViewById(R.id.details);
                return details != null && details.getText() != null && details.getText().length() > 0;
            });
            assertTrue("Appointment Details never rendered the appointment", loaded);
            boolean visible = await(screen, activity -> {
                View card = activity.findViewById(R.id.reminderCard);
                return card != null && card.getVisibility() == View.VISIBLE;
            });
            if (expected) {
                assertTrue("The reminder card must stay available after re-login: "
                        + describe(screen, R.id.reminderCard, R.id.message, R.id.appointmentStatus), visible);
                assertEquals("The custom reminder action must stay available",
                        context().getString(R.string.reminder_custom_set), text(screen, R.id.reminderButton));
            } else {
                assertFalse("The reminder card must not be offered here", visible);
            }
        }
    }

    // ---- Accounts and session driving --------------------------------------------------------

    /** Creates (or reuses) a synthetic patient and leaves Firebase signed out. */
    private String ensureAccount(String email, String name) throws Exception {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        signOutRaw();
        FirebaseUser user;
        try {
            user = Tasks.await(auth.createUserWithEmailAndPassword(email, PASSWORD),
                    TIMEOUT, TimeUnit.MILLISECONDS).getUser();
        } catch (ExecutionException duplicate) {
            user = Tasks.await(auth.signInWithEmailAndPassword(email, PASSWORD),
                    TIMEOUT, TimeUnit.MILLISECONDS).getUser();
        }
        assertNotNull("Synthetic account creation returned no user", user);
        Tasks.await(user.getIdToken(true), TIMEOUT, TimeUnit.MILLISECONDS);

        Map<String, Object> profile = new HashMap<>();
        profile.put("fullName", name);
        profile.put("email", email);
        profile.put("phone", PHONE);
        profile.put("role", "Patient");
        profile.put("dateOfBirth", "1990-01-15");
        profile.put("gender", "Male");
        profile.put("createdAt", FieldValue.serverTimestamp());
        Tasks.await(FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                .set(profile), TIMEOUT, TimeUnit.MILLISECONDS);

        signOutRaw();
        return user.getUid();
    }

    /** Drives the real login path (verification + local reminder reconciliation), not raw Auth. */
    private void logIn(String email) {
        instrumentation.runOnMainSync(() -> AuthRepository.getInstance().signIn(email, PASSWORD));
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] verified = {false};
            instrumentation.runOnMainSync(() -> verified[0] = AuthRepository.getInstance().verifiedUser() != null);
            if (verified[0]) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for a verified session for " + email);
    }

    private void logOut() {
        instrumentation.runOnMainSync(() -> AuthRepository.getInstance().signOut());
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] done = {false};
            instrumentation.runOnMainSync(() -> done[0] = FirebaseAuth.getInstance().getCurrentUser() == null
                    && !AuthRepository.getInstance().hasVerifiedUser());
            if (done[0]) return;
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for sign-out");
    }

    private void signOutRaw() {
        instrumentation.runOnMainSync(() -> {
            AuthRepository.getInstance().signOut();
            FirebaseAuth.getInstance().signOut();
        });
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for the synthetic account to sign out");
    }

    private String signedInUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    // ---- Emulator seed data -----------------------------------------------------------------

    private void seedAppointment(String patientUid, String id, String doctorName) throws Exception {
        long now = System.currentTimeMillis();
        long midnight = DhakaDateTime.localDateAtMidnight(
                DhakaDateTime.localDateTime(now + 2 * 86_400_000L)[0],
                DhakaDateTime.localDateTime(now + 2 * 86_400_000L)[1],
                DhakaDateTime.localDateTime(now + 2 * 86_400_000L)[2]);
        int startMinute = 600, endMinute = 630;
        int[] date = DhakaDateTime.localDateTime(midnight);
        Timestamp created = Timestamp.now();
        Map<String, Object> appointment = new HashMap<>();
        appointment.put("patientUid", patientUid);
        appointment.put("patientName", "ReLogin Patient");
        appointment.put("patientPhone", PHONE);
        appointment.put("doctorId", "doctorReloginE2e");
        appointment.put("doctorName", doctorName);
        appointment.put("doctorSpecialization", "General Medicine");
        appointment.put("consultationFee", 50_000L);
        appointment.put("scheduleId", "scheduleReloginE2e");
        appointment.put("dayOfWeek", DhakaDateTime.isoDayOfWeek(date[0], date[1], date[2]));
        appointment.put("appointmentDate", new Timestamp(midnight / 1000L, 0));
        appointment.put("startMinute", startMinute);
        appointment.put("endMinute", endMinute);
        appointment.put("startAt", new Timestamp(midnight / 1000L + startMinute * 60L, 0));
        appointment.put("endAt", new Timestamp(midnight / 1000L + endMinute * 60L, 0));
        appointment.put("status", "Confirmed");
        appointment.put("createdAt", created);
        appointment.put("updatedAt", created);
        // The local emulator can drop a client stream mid-suite; a full overwrite is idempotent, so
        // bring the network back and retry instead of failing the regression check in its setup.
        for (int attempt = 1; ; attempt++) {
            try {
                Tasks.await(FirebaseFirestore.getInstance().collection("appointments").document(id)
                        .set(appointment), 20_000L, TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException | TimeoutException transientFailure) {
                if (attempt >= 3) {
                    fail("Could not seed " + id + " into the Firestore emulator: " + transientFailure);
                }
                Tasks.await(FirebaseFirestore.getInstance().enableNetwork(),
                        20_000L, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ---- Small UI helpers -------------------------------------------------------------------

    private Activity awaitContent(ActivityScenario<? extends Activity> scenario, int contentId, int loadingId) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        final Activity[] found = {null};
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                View content = activity.findViewById(contentId);
                View loading = activity.findViewById(loadingId);
                if (content != null && content.getVisibility() == View.VISIBLE
                        && (loading == null || loading.getVisibility() != View.VISIBLE)) found[0] = activity;
            });
            if (found[0] != null) return found[0];
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for verified content " + contentId);
        return null;
    }

    private boolean await(Activity activity, Predicate<Activity> condition) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] met = {false};
            instrumentation.runOnMainSync(() -> met[0] = condition.test(activity));
            if (met[0]) return true;
            SystemClock.sleep(200);
        }
        return false;
    }

    private String text(Activity activity, int id) {
        final String[] value = {""};
        instrumentation.runOnMainSync(() -> {
            TextView text = activity.findViewById(id);
            value[0] = text == null || text.getText() == null ? "" : text.getText().toString();
        });
        return value[0];
    }

    private String describe(Activity activity, int... ids) {
        StringBuilder report = new StringBuilder();
        for (int id : ids) {
            if (report.length() > 0) report.append("; ");
            report.append(activity.getResources().getResourceEntryName(id)).append('=');
            if (id == R.id.reminderCard || id == R.id.syncRetryButton || id == R.id.addButton) {
                final int[] visibility = {View.GONE};
                instrumentation.runOnMainSync(() -> {
                    View view = activity.findViewById(id);
                    visibility[0] = view == null ? View.GONE : view.getVisibility();
                });
                report.append("visibility=").append(visibility[0]);
                if (id == R.id.addButton) {
                    final boolean[] enabled = {false};
                    instrumentation.runOnMainSync(() -> {
                        View view = activity.findViewById(id);
                        enabled[0] = view != null && view.isEnabled();
                    });
                    report.append(",enabled=").append(enabled[0]);
                }
            } else {
                report.append('\'').append(text(activity, id)).append('\'');
            }
        }
        return report.toString();
    }

    private Context context() { return instrumentation.getTargetContext(); }
}
