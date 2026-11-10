package com.example.medibook.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentDetailsActivity;
import com.example.medibook.activities.AppointmentsActivity;
import com.example.medibook.activities.admin.AdminDashboardActivity;
import com.example.medibook.activities.patient.PatientHomeActivity;
import com.example.medibook.activities.patient.DoctorProfileActivity;
import com.example.medibook.cache.PatientDataCache;
import com.example.medibook.cache.DashboardCache;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.repositories.DoctorScheduleMapper;
import com.example.medibook.repositories.LocalDatabaseHarness;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.DhakaDateTime;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.database.FirebaseDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Local Firebase-emulator navigation checks for the real Patient and Admin appointment screens.
 * Uses synthetic accounts/data only ({@code *.e2e@example.invalid}); never production accounts.
 *
 * <p>Requires local Auth at {@code 127.0.0.1:9099}, Firestore at {@code 127.0.0.1:8080},
 * and RTDB at {@code 127.0.0.1:9000},
 * reached through {@code adb reverse} so the device loopback carries the test traffic. Any uncaught
 * Activity/RecyclerView exception fails the instrumentation run, so a passing run is runtime
 * evidence for these navigation paths on the emulator (not a physical-device claim).</p>
 */
@RunWith(AndroidJUnit4.class)
public final class AuthenticatedAppointmentsTest {
    private static final String PASSWORD = "TestPass123!";
    private static final String PATIENT_EMAIL = "patient.e2e@example.invalid";
    private static final String ADMIN_EMAIL = "admin.e2e@example.invalid";
    private static final String PHONE = "01712345678";
    private static final int ROWS = 12;
    private static final long TIMEOUT = 45_000L;

    private static boolean emulatorsConfigured;
    private static FirebaseDatabase localDatabase;
    private Instrumentation instrumentation;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        if (!emulatorsConfigured) {
            FirebaseAuth.getInstance().useEmulator("127.0.0.1", 9099);
            FirebaseFirestore.getInstance().useEmulator("127.0.0.1", 8080);
            localDatabase = FirebaseDatabase.getInstance(instrumentation.getTargetContext().getString(R.string.medibook_database_url));
            localDatabase.useEmulator("127.0.0.1", 9000);
            LocalDatabaseHarness.prime(localDatabase);
            emulatorsConfigured = true;
        }
    }

    @After public void tearDown() {
        if (!emulatorsConfigured) return;
        instrumentation.runOnMainSync(() -> {
            database().goOffline();
            AuthRepository.getInstance().signOut();
            FirebaseAuth.getInstance().signOut();
        });
    }

    @Test public void patientDashboardEntryLoadsListDetailsAndWarmReentryWithoutStuckLoader() throws Exception {
        FirebaseUser patient = signIn(PATIENT_EMAIL, "Patient");
        clearAppointments();

        try (ActivityScenario<PatientHomeActivity> home = ActivityScenario.launch(PatientHomeActivity.class)) {
            awaitVisible(home, R.id.patientContent, R.id.sessionLoading, TIMEOUT);
            awaitButton(home, R.id.appointmentsButton, TIMEOUT);
            home.onActivity(activity -> activity.findViewById(R.id.appointmentsButton).performClick());

            final AppointmentsActivity list = awaitResumed(AppointmentsActivity.class, TIMEOUT);
            awaitEmptyList(list, R.string.appointment_list_empty);
            assertEquals(View.VISIBLE, visibility(list, R.id.emptyAction));
            seedAppointments(patient.getUid(), "Patient");
            instrumentation.runOnMainSync(() -> list.findViewById(R.id.refreshButton).performClick());
            awaitList(list, ROWS + 1, TIMEOUT);
            assertEquals("Warm appointment list must not show the session loader",
                    View.GONE, visibility(list, R.id.sessionLoading));
            MaterialToolbar toolbar = list.findViewById(R.id.toolbar);
            assertEquals(list.getString(R.string.appointment_my), toolbar.getTitle().toString());

            // All synthetic rows are future Confirmed appointments: Cancelled has no matches.
            selectStatus(list, 3);
            awaitEmptyList(list, R.string.appointment_no_matches);
            selectStatus(list, 1);
            awaitList(list, ROWS + 1, TIMEOUT);

            // Scroll far enough to detach the one static filter header, then re-attach and notify.
            for (int pass = 0; pass < 3; pass++) {
                scrollTo(list, Integer.MAX_VALUE);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scrollTo(list, 0);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    RecyclerView view = list.findViewById(R.id.list);
                    if (view.getAdapter() != null) view.getAdapter().notifyDataSetChanged();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            assertTrue(adapterCount(list) >= ROWS + 1);

            // Open the first appointment row through the real adapter click path.
            scrollTo(list, 1);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                RecyclerView view = list.findViewById(R.id.list);
                RecyclerView.ViewHolder holder = view.findViewHolderForAdapterPosition(1);
                assertNotNull("First appointment row must be laid out", holder);
                holder.itemView.performClick();
            });

            AppointmentDetailsActivity details = awaitResumed(AppointmentDetailsActivity.class, TIMEOUT);
            awaitVisible(details, R.id.content, R.id.sessionLoading, TIMEOUT);
            awaitText(details, R.id.details, TIMEOUT);
            TextView doctor = details.findViewById(R.id.details);
            assertTrue("Details must show the seeded doctor", doctor.getText().toString().contains("Dr. E2E"));
            awaitVisibility(details, R.id.reminderCard, View.VISIBLE, TIMEOUT);
            assertEquals("The verified Patient must see reminders for their eligible future appointment",
                    View.VISIBLE, visibility(details, R.id.reminderCard));

            // Back to the list, then back to Patient Home.
            instrumentation.runOnMainSync(() -> details.getOnBackPressedDispatcher().onBackPressed());
            final AppointmentsActivity returned = awaitResumed(AppointmentsActivity.class, TIMEOUT);
            awaitList(returned, ROWS + 1, TIMEOUT);
            assertEquals("A warm list refresh must not insert a loading spinner", View.GONE, visibility(returned, R.id.progress));
            instrumentation.runOnMainSync(() -> returned.getOnBackPressedDispatcher().onBackPressed());
            PatientHomeActivity backHome = awaitResumed(PatientHomeActivity.class, TIMEOUT);
            assertEquals(View.VISIBLE, visibility(backHome, R.id.patientContent));
            awaitMessage(backHome, R.id.nextDoctor, "Dr. E2E 00");

            // Repeating the entry point must not strand the user on a second "Signing you in..." card.
            awaitButton(home, R.id.appointmentsButton, TIMEOUT);
            home.onActivity(activity -> activity.findViewById(R.id.appointmentsButton).performClick());
            AppointmentsActivity again = awaitResumed(AppointmentsActivity.class, TIMEOUT);
            awaitList(again, ROWS + 1, TIMEOUT);
            assertFalse("Repeated navigation must leave the session loader hidden",
                    visibility(again, R.id.sessionLoading) == View.VISIBLE);
            assertEquals("Repeated navigation must keep the protected list visible",
                    View.VISIBLE, visibility(again, R.id.content));
            assertTrue(adapterCount(again) >= ROWS + 1);

            // A rotation/recreate of the warm screen must not flash back to the full-screen loader
            // or lose the appointment rows.
            instrumentation.runOnMainSync(() -> again.recreate());
            AppointmentsActivity recreated = awaitResumed(AppointmentsActivity.class, TIMEOUT);
            awaitList(recreated, ROWS + 1, TIMEOUT);
            assertEquals("Recreated appointment screen must not be stuck on the loader",
                    View.GONE, visibility(recreated, R.id.sessionLoading));
        }
    }

    @Test public void adminDashboardEntryLoadsManageListFiltersAndWarmReentry() throws Exception {
        FirebaseUser admin = signIn(ADMIN_EMAIL, "Admin");
        clearAppointments();

        try (ActivityScenario<AdminDashboardActivity> dashboard = ActivityScenario.launch(AdminDashboardActivity.class)) {
            awaitVisible(dashboard, R.id.adminContent, R.id.loadingIndicator, TIMEOUT);
            AdminDashboardActivity firstDashboard = awaitResumed(AdminDashboardActivity.class, TIMEOUT);
            awaitAnalytics(admin.getUid());
            awaitProjection("dashboards/admin/summary");
            awaitMessage(firstDashboard, R.id.analyticsMessage, R.string.admin_analytics_empty);
            // RTDB failure must leave verified Firestore counts visible, not restart page loading.
            database().goOffline();
            awaitMessage(firstDashboard, R.id.analyticsMessage, R.string.dashboard_firestore);
            assertEquals(View.VISIBLE, visibility(firstDashboard, R.id.weeklyChart));
            database().goOnline();
            awaitButton(dashboard, R.id.manageAppointmentsButton, TIMEOUT);
            dashboard.onActivity(activity -> activity.findViewById(R.id.manageAppointmentsButton).performClick());

            AppointmentsActivity list = awaitResumed(AppointmentsActivity.class, TIMEOUT);
            awaitEmptyList(list, R.string.appointment_admin_empty);
            assertEquals(View.GONE, visibility(list, R.id.emptyAction));
            seedAppointments(admin.getUid(), "Patient");
            instrumentation.runOnMainSync(() -> list.findViewById(R.id.refreshButton).performClick());
            awaitList(list, ROWS + 1, TIMEOUT);
            assertFalse("Warm Admin appointment list must not show the session loader",
                    visibility(list, R.id.sessionLoading) == View.VISIBLE);
            MaterialToolbar toolbar = list.findViewById(R.id.toolbar);
            assertEquals(list.getString(R.string.appointment_manage), toolbar.getTitle().toString());
            assertEquals(View.VISIBLE, visibility(list, R.id.doctorFilterLayout));
            assertEquals(View.VISIBLE, visibility(list, R.id.dateFilters));

            // Admin details must never expose the patient-only reminder card or its action, even
            // though a local session epoch exists for the signed-in Admin account.
            scrollTo(list, 1);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                RecyclerView view = list.findViewById(R.id.list);
                RecyclerView.ViewHolder holder = view.findViewHolderForAdapterPosition(1);
                assertNotNull("First Admin appointment row must be laid out", holder);
                holder.itemView.performClick();
            });
            AppointmentDetailsActivity adminDetails = awaitResumed(AppointmentDetailsActivity.class, TIMEOUT);
            awaitVisible(adminDetails, R.id.content, R.id.sessionLoading, TIMEOUT);
            awaitText(adminDetails, R.id.details, TIMEOUT);
            assertEquals("Admin must never see the patient reminder card", View.GONE,
                    visibility(adminDetails, R.id.reminderCard));
            instrumentation.runOnMainSync(() -> adminDetails.findViewById(R.id.reminderButton).performClick());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            androidx.test.espresso.Espresso.onView(
                            androidx.test.espresso.matcher.ViewMatchers.withText(R.string.reminder_custom_title))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            instrumentation.runOnMainSync(() -> adminDetails.getOnBackPressedDispatcher().onBackPressed());
            awaitList(awaitResumed(AppointmentsActivity.class, TIMEOUT), ROWS + 1, TIMEOUT);

            // Doctor filter narrows the list while retaining the single static header row.
            instrumentation.runOnMainSync(() ->
                    ((TextView) list.findViewById(R.id.doctorFilter)).setText("Dr. E2E 03"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Header plus the single matching appointment", 2, adapterCount(list));
            instrumentation.runOnMainSync(() ->
                    ((TextView) list.findViewById(R.id.doctorFilter)).setText(""));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(ROWS + 1, adapterCount(list));

            // Scroll off/re-attach the header and recreate the warm screen.
            scrollTo(list, Integer.MAX_VALUE);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scrollTo(list, 0);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(adapterCount(list) >= ROWS + 1);

            instrumentation.runOnMainSync(() -> list.recreate());
            AppointmentsActivity recreated = awaitResumed(AppointmentsActivity.class, TIMEOUT);
            awaitList(recreated, ROWS + 1, TIMEOUT);
            assertEquals("Recreated Admin list must not be stuck on the loader",
                    View.GONE, visibility(recreated, R.id.sessionLoading));

            instrumentation.runOnMainSync(() -> recreated.getOnBackPressedDispatcher().onBackPressed());
            AdminDashboardActivity back = awaitResumed(AdminDashboardActivity.class, TIMEOUT);
            assertEquals(View.VISIBLE, visibility(back, R.id.adminContent));
            instrumentation.runOnMainSync(() -> assertNotEquals("Warm counts must not reset to a placeholder",
                    "—", ((TextView) back.findViewById(R.id.todayCount)).getText().toString()));
            int remainingThisWeek = 6 - com.example.medibook.utils.WeeklyAppointmentStats.builder(System.currentTimeMillis()).build().todayIndex;
            awaitWeekTotal(admin.getUid(), Math.min(ROWS, remainingThisWeek));

            // Re-open through the verified dashboard; the warm lease must not reintroduce a loader.
            awaitButton(dashboard, R.id.manageAppointmentsButton, TIMEOUT);
            dashboard.onActivity(activity -> activity.findViewById(R.id.manageAppointmentsButton).performClick());
            AppointmentsActivity again = awaitResumed(AppointmentsActivity.class, TIMEOUT);
            awaitList(again, ROWS + 1, TIMEOUT);
            assertEquals(View.GONE, visibility(again, R.id.sessionLoading));
            assertEquals(View.VISIBLE, visibility(again, R.id.content));
        }
    }

    @Test public void directoryProfileRendersCachedDoctorWhileNetworkRefreshIsPending() throws Exception {
        FirebaseUser patient = signIn(PATIENT_EMAIL, "Patient");
        String doctorId = "doctorPerformanceE2e";
        Doctor fixture = new Doctor(doctorId, "Dr. Performance E2E", "Medicine", "MBBS", new java.math.BigDecimal("500.00"), "", true);
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Tasks.await(db.collection("doctors").document(doctorId).set(DoctorMapper.createMap(fixture, FieldValue.serverTimestamp())), TIMEOUT, TimeUnit.MILLISECONDS);
        Tasks.await(db.collection("doctors").document(doctorId).collection("schedules").document("performanceSchedule")
                .set(DoctorScheduleMapper.writeMap(new DoctorSchedule("performanceSchedule", 1, 540, 600, 20, true), FieldValue.serverTimestamp())), TIMEOUT, TimeUnit.MILLISECONDS);
        try (ActivityScenario<PatientHomeActivity> home = ActivityScenario.launch(PatientHomeActivity.class)) {
            awaitVisible(home, R.id.patientContent, R.id.sessionLoading, TIMEOUT);
            long deadline = SystemClock.uptimeMillis() + TIMEOUT;
            while (PatientDataCache.get().doctor(patient.getUid(), doctorId) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            assertNotNull("Directory must seed the reusable doctor profile", PatientDataCache.get().doctor(patient.getUid(), doctorId));
            Tasks.await(db.disableNetwork(), TIMEOUT, TimeUnit.MILLISECONDS);
            home.onActivity(activity -> activity.onViewDoctor(fixture));
            DoctorProfileActivity profile = awaitResumed(DoctorProfileActivity.class, TIMEOUT);
            assertEquals("Cached profile must not wait for the network", View.VISIBLE, visibility(profile, R.id.profileContent));
            awaitMessage(profile, R.id.doctorName, fixture.getName());
            assertEquals(View.GONE, visibility(profile, R.id.sessionLoading));
            Tasks.await(db.enableNetwork(), TIMEOUT, TimeUnit.MILLISECONDS);
            deadline = SystemClock.uptimeMillis() + TIMEOUT;
            while (PatientDataCache.get().schedules(patient.getUid(), doctorId) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            assertNotNull("Schedule refresh must complete without re-entering the screen", PatientDataCache.get().schedules(patient.getUid(), doctorId));
        } finally {
            Tasks.await(db.enableNetwork(), TIMEOUT, TimeUnit.MILLISECONDS);
            Tasks.await(db.collection("doctors").document(doctorId).collection("schedules").document("performanceSchedule").delete(), TIMEOUT, TimeUnit.MILLISECONDS);
            Tasks.await(db.collection("doctors").document(doctorId).delete(), TIMEOUT, TimeUnit.MILLISECONDS);
        }
    }

    private FirebaseDatabase database() {
        if (localDatabase == null) throw new AssertionError("Local RTDB must be configured before any read");
        return localDatabase;
    }

    private void awaitAnalytics(String owner) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            if (DashboardCache.get().analytics(owner, System.currentTimeMillis()) != null) return;
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for verified analytics");
    }

    private void awaitWeekTotal(String owner, int total) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            com.example.medibook.utils.WeeklyAppointmentStats stats = DashboardCache.get().analytics(owner, System.currentTimeMillis());
            if (stats != null && stats.weekTotal == total) return;
            SystemClock.sleep(100);
        }
        fail("Background analytics refresh did not produce the current week total " + total);
    }

    private void awaitProjection(String path) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Tasks.await(database().getReference(path).get(), TIMEOUT, TimeUnit.MILLISECONDS).exists()) return;
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for local RTDB projection " + path);
    }

    private void awaitMessage(Activity activity, int id, int message) { awaitMessage(activity, id, activity.getString(message)); }
    private void awaitMessage(Activity activity, int id, String message) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                TextView text = activity.findViewById(id); ready[0] = text != null && message.contentEquals(text.getText());
            });
            if (ready[0]) return;
            SystemClock.sleep(100);
        }
        fail("Timed out waiting for text " + message);
    }

    private FirebaseUser signIn(String email, String role) throws Exception {
        // AuthRepository's public contract is main-thread only. Drain the previous emulator RTDB
        // connection before switching synthetic accounts, then reconnect with the new SDK token.
        instrumentation.runOnMainSync(() -> {
            database().goOffline();
            AuthRepository.getInstance().signOut();
            FirebaseAuth.getInstance().signOut();
        });
        FirebaseUser user = Tasks.await(FirebaseAuth.getInstance()
                        .signInWithEmailAndPassword(email, PASSWORD), TIMEOUT, TimeUnit.MILLISECONDS)
                .getUser();
        assertNotNull("Synthetic " + role + " sign-in returned no user", user);
        Tasks.await(user.getIdToken(true), TIMEOUT, TimeUnit.MILLISECONDS);

        Map<String, Object> profile = new HashMap<>();
        profile.put("fullName", "E2E " + role);
        profile.put("email", email);
        profile.put("phone", PHONE);
        profile.put("role", role);
        profile.put("dateOfBirth", "1990-01-15");
        profile.put("gender", "Male");
        profile.put("createdAt", FieldValue.serverTimestamp());
        Tasks.await(FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                .set(profile), TIMEOUT, TimeUnit.MILLISECONDS);
        instrumentation.runOnMainSync(() -> database().goOnline());
        return user;
    }

    private void clearAppointments() throws Exception {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        for (DocumentSnapshot document : Tasks.await(db.collection("appointments").get(),
                TIMEOUT, TimeUnit.MILLISECONDS).getDocuments()) {
            Tasks.await(document.getReference().delete(), TIMEOUT, TimeUnit.MILLISECONDS);
        }
    }

    private void seedAppointments(String patientUid, String patientName) throws Exception {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Timestamp created = Timestamp.now();
        long todayMidnight = DhakaDateTime.localDateAtMidnight(
                DhakaDateTime.localDateTime(System.currentTimeMillis())[0],
                DhakaDateTime.localDateTime(System.currentTimeMillis())[1],
                DhakaDateTime.localDateTime(System.currentTimeMillis())[2]);
        for (int index = 0; index < ROWS; index++) {
            long midnight = todayMidnight + (index + 1) * 86_400_000L;
            int[] date = DhakaDateTime.localDateTime(midnight);
            int startMinute = 540 + (index % 8) * 30;
            int endMinute = startMinute + 30;
            Map<String, Object> appointment = new HashMap<>();
            appointment.put("patientUid", patientUid);
            appointment.put("patientName", patientName);
            appointment.put("patientPhone", PHONE);
            appointment.put("doctorId", "doctorE2e");
            appointment.put("doctorName", String.format(java.util.Locale.ROOT, "Dr. E2E %02d", index));
            appointment.put("doctorSpecialization", "General Medicine");
            appointment.put("consultationFee", 50_000L);
            appointment.put("scheduleId", "scheduleE2e");
            appointment.put("dayOfWeek", DhakaDateTime.isoDayOfWeek(date[0], date[1], date[2]));
            appointment.put("appointmentDate", new Timestamp(midnight / 1000L, 0));
            appointment.put("startMinute", startMinute);
            appointment.put("endMinute", endMinute);
            appointment.put("startAt", new Timestamp(midnight / 1000L + startMinute * 60L, 0));
            appointment.put("endAt", new Timestamp(midnight / 1000L + endMinute * 60L, 0));
            appointment.put("status", "Confirmed");
            appointment.put("createdAt", created);
            appointment.put("updatedAt", created);
            Tasks.await(db.collection("appointments")
                    .document(String.format(java.util.Locale.ROOT, "e2eAppointment%02d", index))
                    .set(appointment), TIMEOUT, TimeUnit.MILLISECONDS);
        }
    }

    private void awaitVisible(ActivityScenario<? extends Activity> scenario, int contentId,
                              int loadingId, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = visible(activity, contentId, loadingId));
            if (ready[0]) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for content " + contentId);
    }

    private void awaitVisible(Activity activity, int contentId, int loadingId, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> ready[0] = visible(activity, contentId, loadingId));
            if (ready[0]) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for content " + contentId);
    }

    private static boolean visible(Activity activity, int contentId, int loadingId) {
        View content = activity.findViewById(contentId);
        View loading = activity.findViewById(loadingId);
        return content != null && content.getVisibility() == View.VISIBLE
                && (loading == null || loading.getVisibility() != View.VISIBLE);
    }

    private void awaitButton(ActivityScenario<? extends Activity> scenario, int id, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            scenario.onActivity(activity -> {
                View button = activity.findViewById(id);
                ready[0] = button != null && button.getVisibility() == View.VISIBLE;
            });
            if (ready[0]) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for button " + id);
    }

    private void awaitList(Activity activity, int minimumItems, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                RecyclerView list = activity.findViewById(R.id.list);
                ready[0] = list != null && list.getAdapter() != null
                        && list.getAdapter().getItemCount() >= minimumItems
                        && visible(activity, R.id.content, R.id.sessionLoading);
            });
            if (ready[0]) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for " + minimumItems + " appointment list items");
    }

    private void awaitEmptyList(Activity activity, int messageId) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                RecyclerView list = activity.findViewById(R.id.list);
                TextView message = activity.findViewById(R.id.message);
                View progress = activity.findViewById(R.id.progress);
                ready[0] = list != null && list.getAdapter() != null
                        && list.getAdapter().getItemCount() == 1
                        && visible(activity, R.id.content, R.id.sessionLoading)
                        && progress != null && progress.getVisibility() != View.VISIBLE
                        && message != null && message.getVisibility() == View.VISIBLE
                        && activity.getString(messageId).contentEquals(message.getText());
            });
            if (ready[0]) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for appointment empty state " + messageId);
    }

    private void selectStatus(Activity activity, int index) {
        instrumentation.runOnMainSync(() -> {
            android.widget.AutoCompleteTextView filter = activity.findViewById(R.id.statusFilter);
            assertNotNull("Status filter must have its selection listener", filter.getOnItemClickListener());
            filter.getOnItemClickListener().onItemClick(null, null, index, index);
        });
        instrumentation.waitForIdleSync();
    }

    private void awaitVisibility(Activity activity, int id, int expected, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (visibility(activity, id) == expected) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for view " + id + " visibility " + expected);
    }

    private void awaitText(Activity activity, int id, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                TextView text = activity.findViewById(id);
                ready[0] = text != null && text.getText() != null && text.getText().length() > 0;
            });
            if (ready[0]) return;
            SystemClock.sleep(200);
        }
        fail("Timed out waiting for text " + id);
    }

    private int adapterCount(Activity activity) {
        final int[] count = {0};
        instrumentation.runOnMainSync(() -> {
            RecyclerView list = activity.findViewById(R.id.list);
            count[0] = list == null || list.getAdapter() == null ? 0 : list.getAdapter().getItemCount();
        });
        return count[0];
    }

    private int visibility(Activity activity, int id) {
        final int[] value = {View.GONE};
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(id);
            value[0] = view == null ? View.GONE : view.getVisibility();
        });
        return value[0];
    }

    private void scrollTo(Activity activity, int position) {
        instrumentation.runOnMainSync(() -> {
            RecyclerView list = activity.findViewById(R.id.list);
            if (list == null || list.getAdapter() == null) return;
            int target = Math.min(position, list.getAdapter().getItemCount() - 1);
            list.scrollToPosition(Math.max(0, target));
        });
    }

    private <T extends Activity> T awaitResumed(Class<T> type, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            final Activity[] found = {null};
            instrumentation.runOnMainSync(() -> {
                Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for (Activity activity : resumed) {
                    if (type.isInstance(activity)) { found[0] = activity; break; }
                }
            });
            if (found[0] != null) return type.cast(found[0]);
            SystemClock.sleep(150);
        }
        fail("Timed out waiting for resumed " + type.getSimpleName());
        return null;
    }
}
