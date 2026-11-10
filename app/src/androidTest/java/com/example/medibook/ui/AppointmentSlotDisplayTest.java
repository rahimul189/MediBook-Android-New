package com.example.medibook.ui;

import static org.junit.Assert.*;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.medibook.R;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.utils.AppointmentPresentation;
import com.example.medibook.utils.AppointmentSerials;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.SlotSerials;
import com.google.firebase.Timestamp;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Offline checks of the shared binding on the actual Details, Dashboard and appointment-card views. */
@RunWith(AndroidJUnit4.class)
public class AppointmentSlotDisplayTest {
    private static Appointment partial() {
        long day = DhakaDateTime.localDateAtMidnight(2026, 11, 9) / 1000L;
        return new Appointment("booking", "patient", "Synthetic Patient", "01712345678", "doctor", "Doctor",
                "Medicine", 50000, "schedule", 1, new Timestamp(day, 0), 403, 423,
                new Timestamp(day + 403 * 60L, 0), new Timestamp(day + 423 * 60L, 0),
                AppointmentStatus.CONFIRMED, new Timestamp(1, 0), new Timestamp(2, 0));
    }

    private void eachSlotView(Consumer<TextView> check) {
        UiFixtureActivity.fixtureFontScale = 1f;
        UiFixtureActivity.fixtureLayout = R.layout.activity_main;
        try (ActivityScenario<UiFixtureActivity> scenario = ActivityScenario.launch(UiFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                int[][] surfaces = {
                        {R.layout.activity_appointment_details, R.id.detailsSlot},
                        {R.layout.item_patient_directory_header, R.id.nextSlot},
                        {R.layout.item_appointment, R.id.slot}
                };
                for (int[] surface : surfaces) {
                    View root = activity.getLayoutInflater().inflate(surface[0], new FrameLayout(activity), false);
                    TextView slot = root.findViewById(surface[1]);
                    assertNotNull(slot); check.accept(slot);
                }
            });
        }
    }

    private static int height(TextView view) {
        int width = Math.round(240 * view.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }

    @Test public void knownNumbersBindImmediatelyOnAllThreeSurfaces() {
        eachSlotView(view -> {
            AppointmentPresentation.bindSlot(view, partial().withSlotSerial(3));
            assertEquals(view.getContext().getString(R.string.next_slot, "#03"), view.getText().toString());
            AppointmentPresentation.bindSlot(view, partial().withSlotSerial(5));
            assertTrue(view.getText().toString().endsWith("#05"));
        });
    }

    @Test public void partialAndLegacyModelsNeverPutATimeInTheSlotNumberRow() {
        eachSlotView(view -> {
            Appointment initial = partial();
            AppointmentPresentation.bindSlot(view, initial);
            String expected = view.getContext().getString(R.string.next_slot, SlotSerials.UNAVAILABLE);
            assertEquals(expected, view.getText().toString());
            Appointment legacy = AppointmentSerials.applyLookup(initial, initial, initial, 1, 1);
            AppointmentPresentation.bindSlot(view, legacy);
            assertEquals(expected, view.getText().toString());
            assertFalse(view.getText().toString().contains("Time slot"));
            assertFalse(view.getText().toString().contains("6:43"));
            assertFalse(view.getText().toString().contains("7:03"));
        });
    }

    @Test public void delayedAndOutOfOrderResultsDoNotRebindUnchangedTextOrJumpRowHeight() {
        eachSlotView(view -> {
            int[] changes = {0};
            view.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changes[0]++; }
                @Override public void afterTextChanged(Editable value) { }
            });
            Appointment initial = partial();
            AppointmentPresentation.bindSlot(view, initial);
            int placeholderHeight = height(view);
            assertEquals(1, changes[0]);
            AppointmentPresentation.bindSlot(view, initial);
            assertEquals("An unchanged clock/partial update must not call setText", 1, changes[0]);
            // Deliver request 2 first, then an older success and an older unresolved result.
            Appointment current = AppointmentSerials.applyLookup(initial, initial, initial.withSlotSerial(3), 2, 2);
            AppointmentPresentation.bindSlot(view, current);
            assertEquals(2, changes[0]);
            assertEquals(placeholderHeight, height(view));
            current = AppointmentSerials.applyLookup(current, initial, initial.withSlotSerial(5), 1, 2);
            AppointmentPresentation.bindSlot(view, current);
            current = AppointmentSerials.applyLookup(current, initial, initial, 1, 2);
            AppointmentPresentation.bindSlot(view, current);
            assertTrue(view.getText().toString().endsWith("#03"));
            assertEquals("Stale completions must not change the TextView", 2, changes[0]);
        });
    }

    @Test public void detailsScheduleTimeStaysSeparateDuringSerialResolution() {
        UiFixtureActivity.fixtureFontScale = 1f;
        UiFixtureActivity.fixtureLayout = R.layout.activity_appointment_details;
        try (ActivityScenario<UiFixtureActivity> scenario = ActivityScenario.launch(UiFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                TextView time = activity.findViewById(R.id.detailsTime);
                TextView slot = activity.findViewById(R.id.detailsSlot);
                String schedule = activity.getString(R.string.next_time_range,
                        DhakaDateTime.formatTime12Hour(403), DhakaDateTime.formatTime12Hour(423));
                time.setText(schedule);
                AppointmentPresentation.bindSlot(slot, partial());
                AppointmentPresentation.bindSlot(slot, partial().withSlotSerial(3));
                assertEquals(schedule, time.getText().toString());
                assertTrue(time.getText().toString().contains("6:43 AM"));
                assertTrue(time.getText().toString().contains("7:03 AM"));
                assertTrue(slot.getText().toString().endsWith("#03"));
            });
        }
    }
}
