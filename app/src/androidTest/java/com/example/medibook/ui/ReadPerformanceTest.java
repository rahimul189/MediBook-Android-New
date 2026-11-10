package com.example.medibook.ui;

import static org.junit.Assert.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.medibook.R;
import com.example.medibook.repositories.SingleFlightReads;
import com.example.medibook.utils.ViewUpdates;
import com.example.medibook.adapters.PatientHomeHeaderAdapter;
import com.example.medibook.adapters.PatientDoctorAdapter;
import com.example.medibook.models.Doctor;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Counts actual source starts and TextView writes; no production Firebase or timing assumptions. */
@RunWith(AndroidJUnit4.class)
public class ReadPerformanceTest {
    @Test public void concurrentIdenticalReadsStartExactlyOneSourceAndRefreshAfterCompletion() {
        SingleFlightReads reads = new SingleFlightReads(); AtomicInteger starts = new AtomicInteger();
        TaskCompletionSource<String> source = new TaskCompletionSource<>();
        Task<String> first = reads.run("self", "get/PATIENT/id", () -> { starts.incrementAndGet(); return source.getTask(); });
        Task<String> second = reads.run("self", "get/PATIENT/id", () -> { fail("Duplicate source read"); return null; });
        assertSame(first, second); assertEquals(1, starts.get());
        source.setResult("fresh"); assertEquals("fresh", second.getResult());
        TaskCompletionSource<String> refresh = new TaskCompletionSource<>();
        assertNotSame(first, reads.run("self", "get/PATIENT/id", () -> { starts.incrementAndGet(); return refresh.getTask(); }));
        assertEquals(2, starts.get());
    }
    @Test public void failedReadDoesNotPoisonARetry() {
        SingleFlightReads reads = new SingleFlightReads(); TaskCompletionSource<String> failed = new TaskCompletionSource<>();
        Task<String> first = reads.run("self", "list/false", failed::getTask);
        failed.setException(new IllegalStateException("synthetic network failure"));
        TaskCompletionSource<String> retry = new TaskCompletionSource<>();
        assertNotSame(first, reads.run("self", "list/false", retry::getTask));
    }
    @Test public void oldAccountCompletionCannotEvictAnotherAccountsNewRequest() {
        SingleFlightReads reads = new SingleFlightReads();
        TaskCompletionSource<String> old = new TaskCompletionSource<>(), other = new TaskCompletionSource<>(), latest = new TaskCompletionSource<>();
        reads.run("a", "details", old::getTask); reads.run("b", "details", other::getTask);
        Task<String> active = reads.run("a", "details", latest::getTask);
        old.setResult("late old account result"); other.setResult("late other account result");
        assertSame(active, reads.run("a", "details", () -> { fail("Late completion evicted current request"); return null; }));
    }
    @Test public void adminAndPatientQueriesAreNeverCoalescedTogether() {
        SingleFlightReads reads = new SingleFlightReads();
        TaskCompletionSource<String> admin = new TaskCompletionSource<>(), patient = new TaskCompletionSource<>();
        assertNotSame(reads.run("self", "list/true", admin::getTask), reads.run("self", "list/false", patient::getTask));
    }
    @Test public void oneHundredUnchangedUiEmissionsProduceOneTextWriteNotOneHundred() {
        UiFixtureActivity.fixtureFontScale = 1f; UiFixtureActivity.fixtureLayout = R.layout.activity_main;
        try (ActivityScenario<UiFixtureActivity> scenario = ActivityScenario.launch(UiFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                TextView baseline = new TextView(activity), optimized = new TextView(activity);
                AtomicInteger before = new AtomicInteger(), after = new AtomicInteger();
                baseline.addTextChangedListener(watcher(before)); optimized.addTextChangedListener(watcher(after));
                for (int i = 0; i < 100; i++) { baseline.setText("Synthetic count: 3"); ViewUpdates.text(optimized, "Synthetic count: 3"); }
                assertEquals(100, before.get()); assertEquals(1, after.get());
                ViewUpdates.text(optimized, "Synthetic count: 5"); assertEquals(2, after.get());
            });
        }
    }
    private static TextWatcher watcher(AtomicInteger changes) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changes.incrementAndGet(); }
            @Override public void afterTextChanged(Editable value) { }
        };
    }

    @Test public void directoryRefreshDoesNotInvalidateTheFocusedStaticHeader() {
        UiFixtureActivity.fixtureFontScale = 1f; UiFixtureActivity.fixtureLayout = R.layout.activity_main;
        try (ActivityScenario<UiFixtureActivity> scenario = ActivityScenario.launch(UiFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                PatientDoctorDirectoryViewModel model = new PatientDoctorDirectoryViewModel(); model.bindSession("self");
                Doctor doctor = new Doctor("fixture", "Synthetic Doctor", "Medicine", "MBBS", new java.math.BigDecimal("500"), "", true);
                model.showDirectory("self", java.util.Collections.singletonList(doctor));
                PatientHomeHeaderAdapter header = new PatientHomeHeaderAdapter(new PatientHomeHeaderAdapter.Listener() {
                    @Override public void onSearchChanged(String value) { model.setQuery(value); }
                    @Override public void onSpecializationChanged(String value) { model.setSpecialization(value); }
                    @Override public void onSearchSubmitted() { }
                    @Override public void onOpenAppointments() { }
                    @Override public void onCallHospital() { }
                    @Override public void onOpenEmergencyContact() { }
                    @Override public void onOpenAirQuality() { }
                    @Override public void onViewNextAppointment() { }
                    @Override public void onRetryNextAppointment() { }
                }, model.getState());
                PatientDoctorAdapter rows = new PatientDoctorAdapter(new PatientDoctorAdapter.Listener() {
                    @Override public void onViewDoctor(Doctor value) { }
                    @Override public void onClearFilters() { model.clearFilters(); }
                    @Override public void onRetryDirectory() { }
                }, model.getState());
                RecyclerView list = new RecyclerView(activity); list.setLayoutManager(new LinearLayoutManager(activity));
                list.setAdapter(new ConcatAdapter(header, rows)); activity.setContentView(list);
                int width = activity.getResources().getDisplayMetrics().widthPixels;
                list.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY));
                list.layout(0, 0, width, 1800);
                model.addListener(state -> { header.submit(state); rows.submit(state); });
                TextView input = list.findViewById(R.id.searchInput); assertNotNull(input);
                input.requestFocus(); input.setText("Synthetic");
                AtomicInteger invalidations = new AtomicInteger();
                header.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                    @Override public void onChanged() { invalidations.incrementAndGet(); }
                    @Override public void onItemRangeChanged(int start, int count) { invalidations.incrementAndGet(); }
                });
                for (int i = 0; i < 10; i++) model.showDirectory("self", java.util.Collections.singletonList(doctor));
                assertEquals(0, invalidations.get()); assertTrue(input.hasFocus());
                assertEquals("Synthetic", input.getText().toString()); header.releaseCarousel();
            });
        }
    }
}
