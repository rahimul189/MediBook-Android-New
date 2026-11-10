package com.example.medibook.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.medibook.R;
import com.example.medibook.utils.AuthErrorMessages;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.views.AdaptiveColumnsLayout;
import com.example.medibook.wellness.WellnessCards;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Offline inflation/geometry checks with synthetic UI text, not authenticated screen E2E tests. */
@RunWith(AndroidJUnit4.class)
public class UiResourceTest {
    private static final int[] LAYOUTS = {
            R.layout.activity_main, R.layout.activity_login, R.layout.activity_register,
            R.layout.activity_complete_profile, R.layout.activity_edit_profile,
            R.layout.activity_patient_home, R.layout.activity_admin_dashboard,
            R.layout.activity_manage_doctors, R.layout.activity_add_edit_doctor,
            R.layout.activity_manage_doctor_schedule, R.layout.activity_add_edit_doctor_schedule,
            R.layout.activity_doctor_profile, R.layout.activity_book_appointment,
            R.layout.activity_appointments, R.layout.activity_appointment_details,
            R.layout.activity_emergency_contact, R.layout.activity_air_quality,
            R.layout.item_air_quality_header, R.layout.item_air_quality_metric,
            R.layout.item_appointment, R.layout.item_appointment_list_header, R.layout.item_available_slot,
            R.layout.item_doctor, R.layout.item_doctor_list_header, R.layout.item_doctor_list_state,
            R.layout.item_doctor_schedule, R.layout.item_doctor_schedule_header, R.layout.item_doctor_schedule_state,
            R.layout.item_emergency_contact, R.layout.item_patient_directory_header,
            R.layout.item_patient_directory_state, R.layout.item_patient_doctor, R.layout.item_wellness_card,
            R.layout.view_auth_brand, R.layout.view_patient_session_loading,
            R.layout.dialog_custom_reminder, R.layout.dialog_confirm_password
    };

    private Context themed(int widthDp, float fontScale) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.fontScale = fontScale;
        config.screenWidthDp = widthDp;
        return new ContextThemeWrapper(target.createConfigurationContext(config), R.style.Theme_MediBook);
    }

    private View inflate(Context context, int layout) {
        return LayoutInflater.from(context).inflate(layout, new LinearLayout(context), false);
    }

    private void measure(View view, int widthDp) {
        int width = Math.round(widthDp * view.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        assertTrue(view.getMeasuredWidth() > 0);
    }

    private void main(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { work.run(); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test public void all37LayoutsInflateAtThreeWidthsAndFourFontScales() {
        main(() -> {
            assertEquals(android.app.Application.class,
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext().getClass());
            for (int width : new int[]{320, 411, 600}) {
                for (float font : new float[]{1f, 1.3f, 1.5f, 2f}) {
                    Context context = themed(width, font);
                    for (int layout : LAYOUTS) {
                        View view = inflate(context, layout);
                        measure(view, width);
                    }
                }
            }
        });
    }

    @Test public void genderMatchesEditableFieldInsetsAndTextMetricsInEveryFormState() {
        main(() -> {
            for (float font : new float[]{1f, 1.5f, 2f}) {
                Context context = themed(320, font);
                for (int resource : new int[]{R.layout.activity_register, R.layout.activity_edit_profile, R.layout.activity_complete_profile}) {
                    View view = inflate(context, resource);
                    View content = view.findViewById(R.id.content); if (content != null) content.setVisibility(View.VISIBLE);
                    View form = view.findViewById(R.id.form); if (form != null) form.setVisibility(View.VISIBLE);
                    TextInputLayout name = view.findViewById(R.id.fullNameLayout);
                    TextInputLayout gender = view.findViewById(R.id.genderLayout);
                    MaterialAutoCompleteTextView input = (MaterialAutoCompleteTextView) gender.getEditText();
                    input.setSimpleItems(new String[]{"Male", "Female"});
                    for (String value : new String[]{"", "Male", "Female"}) {
                        input.setText(value, false); measure(view, 320);
                        assertEquals(name.getEditText().getPaddingStart(), input.getPaddingStart());
                        assertEquals(name.getEditText().getTextSize(), input.getTextSize(), 0.5f);
                        assertEquals(name.getEditText().getCurrentTextColor(), input.getCurrentTextColor());
                        assertTrue(input.getMeasuredHeight() >= 48 * context.getResources().getDisplayMetrics().density);
                    }
                    gender.setError("Select a gender. This longer validation message must wrap.");
                    measure(view, 320); assertNotNull(gender.getError());
                    gender.setEnabled(false); measure(view, 320); assertFalse(input.isEnabled());
                }
            }
        });
    }

    @Test public void slotUsesNativeCheckedSemanticsAndAtLeast48dpTarget() {
        main(() -> {
            Context context = themed(320, 2f);
            MaterialRadioButton slot = (MaterialRadioButton) inflate(context, R.layout.item_available_slot);
            slot.setText("10:30 AM – 11:00 AM\nAppointment Slot No.: #1234");
            measure(slot, 320);
            assertTrue(slot.getMeasuredHeight() >= 48 * context.getResources().getDisplayMetrics().density);
            assertTrue(slot.getAccessibilityClassName().toString().contains("RadioButton"));
            slot.setChecked(true); assertTrue(slot.isChecked());
            slot.setEnabled(false); assertFalse(slot.isEnabled());
        });
    }

    @Test public void adaptiveColumnsStackForLargeFontsAndRemainColumnsWhenWide() {
        main(() -> {
            for (float font : new float[]{1f, 2f}) {
                Context context = themed(600, font);
                AdaptiveColumnsLayout row = new AdaptiveColumnsLayout(context, null);
                for (int i = 0; i < 2; i++) {
                    TextView value = new TextView(context); value.setText("Today's total appointments: 12345");
                    row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                }
                measure(row, font == 1 ? 600 : 320);
                assertEquals(font == 1 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL, row.getOrientation());
            }
        });
    }

    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    public void slotSelectionOnlyUpdatesOldAndNewRowsAndBusyStateBlocksTaps() {
        main(() -> {
            Context context = themed(320, 1f);
            java.util.List<com.example.medibook.models.InventorySlot> slots = new java.util.ArrayList<>();
            com.google.firebase.Timestamp time = new com.google.firebase.Timestamp(0, 0);
            for (int index = 0; index < 6; index++) {
                slots.add(new com.example.medibook.models.InventorySlot("fixture-doctor", "slot-" + index, "fixture-schedule",
                        time, 1, 600 + index * 30, 630 + index * 30, time, time, "available", 0, null, time, time).withSlotSerial(index + 1));
            }
            int[] selections = {0}, changes = {0};
            com.example.medibook.adapters.AvailableSlotAdapter adapter = new com.example.medibook.adapters.AvailableSlotAdapter(slot -> selections[0]++);
            adapter.submit(slots);
            adapter.registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                @Override public void onItemRangeChanged(int start, int count) { changes[0] += count; }
            });
            androidx.recyclerview.widget.RecyclerView.Adapter raw = adapter;
            LinearLayout parent = new LinearLayout(context);
            androidx.recyclerview.widget.RecyclerView.ViewHolder first = raw.createViewHolder(parent, 0);
            androidx.recyclerview.widget.RecyclerView.ViewHolder second = raw.createViewHolder(parent, 0);
            raw.bindViewHolder(first, 0); raw.bindViewHolder(second, 1);
            first.itemView.performClick(); assertEquals(1, changes[0]);
            changes[0] = 0; second.itemView.performClick(); assertEquals(2, changes[0]);
            raw.bindViewHolder(first, 0); raw.bindViewHolder(second, 1);
            assertFalse(((MaterialRadioButton) first.itemView).isChecked());
            assertTrue(((MaterialRadioButton) second.itemView).isChecked());
            assertTrue(((MaterialRadioButton) second.itemView).getText().toString().contains("#02"));
            adapter.setBusy(true); raw.bindViewHolder(first, 0); first.itemView.performClick();
            assertEquals(2, selections[0]); assertFalse(first.itemView.isEnabled());
        });
    }

    @Test public void textAndStatusSourceColorPairsHaveReadableContrast() {
        main(() -> {
            Context context = themed(320, 1f);
            for (int[] pair : new int[][]{{R.color.white, R.color.medibook_blue},
                    {R.color.medibook_text_secondary, R.color.white},
                    {R.color.medibook_blue, R.color.medibook_blue_container},
                    {R.color.medibook_error, R.color.medibook_error_container},
                    {R.color.medibook_teal_dark, R.color.medibook_teal_container},
                    {R.color.medibook_warning, R.color.medibook_warning_container}}) {
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(context.getColor(pair[0]), context.getColor(pair[1])) >= 4.5);
            }
        });
    }

    @Test public void passwordDialogUsesOutlinedInputWithoutSavingPassword() {
        main(() -> {
            Context context = themed(320, 2f);
            View view = inflate(context, R.layout.dialog_confirm_password);
            TextInputLayout layout = view.findViewById(R.id.passwordLayout);
            assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, layout.getBoxBackgroundMode());
            assertEquals(TextInputLayout.END_ICON_PASSWORD_TOGGLE, layout.getEndIconMode());
            assertFalse(layout.getEditText().isSaveEnabled());
            measure(view, 320);
        });
    }

    @Test public void loginCopyExactlyMatchesApprovedMessageAndWraps() {
        main(() -> {
            Context context = themed(320, 2f);
            String expected = "Unable to sign in. Please check your email and password. You can also try Continue with Google if your account supports Google sign-in.";
            assertEquals(expected, context.getString(AuthErrorMessages.forLoginError(AuthRepository.Error.INVALID_CREDENTIALS)));
            View view = inflate(context, R.layout.activity_login);
            TextView error = view.findViewById(R.id.statusMessage);
            error.setVisibility(View.VISIBLE); error.setText(expected); measure(view, 320);
            assertTrue(error.getLineCount() > 1);
            assertEquals(context.getColor(R.color.medibook_error), error.getCurrentTextColor());
        });
    }

    @Test public void artworkDescriptionsContainTheActualExistingTitlesAndAdvice() {
        main(() -> {
            Context context = themed(320, 1f);
            assertEquals(11, WellnessCards.count());
            for (int index = 0; index < WellnessCards.count(); index++) {
                assertTrue(context.getString(WellnessCards.description(index)).length() > 70);
            }
            assertTrue(context.getString(WellnessCards.description(2)).contains("30 minutes"));
            assertTrue(context.getString(WellnessCards.description(3)).contains("7–9 hours"));
        });
    }

    @Test public void configuredProgressIndicatorsFitTheirHostsAndActionsRemainTouchable() {
        main(() -> {
            Context context = themed(320, 1f);
            for (int layout : LAYOUTS) verifyControls(inflate(context, layout), context);
        });
    }

    private void verifyControls(View view, Context context) {
        if (view instanceof CircularProgressIndicator) {
            CircularProgressIndicator progress = (CircularProgressIndicator) view;
            int host = view.getLayoutParams().width;
            assertTrue(progress.getIndicatorSize() + 2 * progress.getIndicatorInset() <= host);
        }
        if (view instanceof MaterialButton) {
            assertTrue(((MaterialButton) view).getMinHeight() >= 48 * context.getResources().getDisplayMetrics().density);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) verifyControls(group.getChildAt(index), context);
        }
    }

    @Test public void saveSyntheticOfflineReviewPreviews() {
        main(() -> {
            preview(R.layout.activity_login, "login-error", 320, 1f);
            preview(R.layout.activity_register, "gender-200pct", 320, 2f);
            preview(R.layout.item_patient_directory_header, "patient-header", 360, 1f);
            preview(R.layout.activity_admin_dashboard, "admin-200pct", 320, 2f);
            preview(R.layout.item_patient_doctor, "doctor-long-name", 320, 2f);
            preview(R.layout.activity_appointment_details, "appointment-details", 360, 1f);
            preview(R.layout.dialog_custom_reminder, "reminder-200pct", 320, 2f);
            preview(R.layout.item_appointment_list_header, "appointment-filters", 320, 2f);
            preview(R.layout.item_available_slot, "selected-slot", 320, 1f);
        });
    }

    @Test public void windowedGenderFieldsSettleWithMatchingInsetsAt200Percent() {
        UiFixtureActivity.fixtureFontScale = 2f;
        UiFixtureActivity.fixtureLayout = R.layout.activity_edit_profile;
        try (ActivityScenario<UiFixtureActivity> scenario = ActivityScenario.launch(UiFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                View view = activity.content;
                view.findViewById(R.id.sessionLoading).setVisibility(View.GONE);
                view.findViewById(R.id.content).setVisibility(View.VISIBLE);
                for (int id : new int[]{R.id.fullNameLayout, R.id.phoneLayout, R.id.genderLayout, R.id.dobLayout}) {
                    ((TextInputLayout) view.findViewById(id)).setHintAnimationEnabled(false);
                }
                fill(view, R.id.fullNameInput, "Patient Example");
                fill(view, R.id.phoneInput, "01700000000");
                ((MaterialAutoCompleteTextView) view.findViewById(R.id.genderInput)).setText("Male", false);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                TextView name = activity.content.findViewById(R.id.fullNameInput);
                TextView gender = activity.content.findViewById(R.id.genderInput);
                assertEquals(name.getPaddingStart(), gender.getPaddingStart());
                assertTrue(gender.isLaidOut());
            });
            captureWindow("windowed-gender-200pct");
        }
    }

    @Test public void windowedReminderDialogKeepsScrollableContentWithinDisplayAt200Percent() {
        UiFixtureActivity.fixtureFontScale = 2f;
        UiFixtureActivity.fixtureLayout = R.layout.activity_main;
        AtomicReference<androidx.appcompat.app.AlertDialog> shown = new AtomicReference<>();
        try (ActivityScenario<UiFixtureActivity> scenario = ActivityScenario.launch(UiFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                View content = activity.getLayoutInflater().inflate(R.layout.dialog_custom_reminder, null);
                fill(content, R.id.reminderDateButton, "09 November 2026");
                fill(content, R.id.reminderTimeButton, "10:30 AM");
                androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.reminder_custom_title).setView(content)
                        .setNegativeButton(R.string.cancel_button, null).setPositiveButton(R.string.reminder_custom_save, null).show();
                shown.set(dialog);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                androidx.appcompat.app.AlertDialog dialog = shown.get();
                assertTrue(dialog.isShowing());
                assertTrue(dialog.getWindow().getDecorView().getHeight() <= activity.getResources().getDisplayMetrics().heightPixels);
                assertTrue(dialog.findViewById(R.id.reminderDateButton).getMeasuredHeight()
                        >= 48 * activity.getResources().getDisplayMetrics().density);
            });
            captureWindow("windowed-reminder-200pct");
            scenario.onActivity(activity -> shown.get().dismiss());
        }
    }

    private void captureWindow(String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "ui-review");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("Cannot create UI review directory");
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (Exception error) { throw new AssertionError(error); }
        finally { image.recycle(); }
    }

    private void preview(int layout, String name, int width, float font) {
        Context context = themed(width, font);
        View view = inflate(context, layout);
        for (int id : new int[]{R.id.content, R.id.adminContent, R.id.form, R.id.nextInformation, R.id.summaryCard}) {
            View child = view.findViewById(id); if (child != null) child.setVisibility(View.VISIBLE);
        }
        for (int id : new int[]{R.id.sessionLoading, R.id.loadingIndicator}) {
            View child = view.findViewById(id); if (child != null) child.setVisibility(View.GONE);
        }
        fill(view, R.id.fullNameInput, "Patient Example"); fill(view, R.id.phoneInput, "01700000000");
        fill(view, R.id.doctorName, "Dr. Example Long Name for Responsive Layout Review");
        fill(view, R.id.specialization, "Medicine and specialist consultations");
        fill(view, R.id.qualifications, "Qualifications: MBBS, postgraduate specialist training");
        fill(view, R.id.consultationFee, "Consultation fee: ৳500.00");
        fill(view, R.id.welcomeMessage, "Welcome, Patient Example");
        fill(view, R.id.nextDoctor, "Dr. Example"); fill(view, R.id.nextSpecialty, "Medicine");
        fill(view, R.id.nextDate, "Monday, 09 Nov 2026"); fill(view, R.id.nextTime, "10:30 AM – 11:00 AM");
        fill(view, R.id.nextSlot, "Appointment Slot No.: #01"); fill(view, R.id.nextStatus, "Confirmed");
        fill(view, R.id.details, "Dr. Example"); fill(view, R.id.detailsSpecialty, "Medicine");
        fill(view, R.id.detailsDate, "Monday, 09 Nov 2026"); fill(view, R.id.detailsTime, "10:30 AM – 11:00 AM");
        fill(view, R.id.detailsSlot, "Appointment Slot No.: #01"); fill(view, R.id.detailsFee, "Consultation fee: ৳500.00");
        fill(view, R.id.appointmentStatus, "Confirmed"); fill(view, R.id.todayCount, "1234"); fill(view, R.id.weekCount, "12345");
        fill(view, R.id.confirmedCount, "1234"); fill(view, R.id.cancelledCount, "12");
        fill(view, R.id.reminderDateButton, "09 November 2026"); fill(view, R.id.reminderTimeButton, "10:30 AM");
        fill(view, R.id.statusFilter, "Upcoming / in progress");
        MaterialAutoCompleteTextView gender = view.findViewById(R.id.genderInput);
        if (gender != null) gender.setText("Male", false);
        if (layout == R.layout.activity_login) {
            TextView error = view.findViewById(R.id.statusMessage); error.setVisibility(View.VISIBLE);
            error.setText(R.string.login_error_credentials);
        }
        if (view instanceof MaterialRadioButton) {
            ((MaterialRadioButton) view).setText("10:30 AM – 11:00 AM\nAppointment Slot No.: #01");
            ((MaterialRadioButton) view).setChecked(true);
        }
        measure(view, width);
        int height = Math.max(100, Math.min(view.getMeasuredHeight(), 10000));
        Bitmap image = Bitmap.createBitmap(view.getMeasuredWidth(), height + 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE); view.draw(canvas);
        Paint note = new Paint(Paint.ANTI_ALIAS_FLAG); note.setColor(Color.DKGRAY); note.setTextSize(18);
        canvas.drawText("OFFLINE XML FIXTURE · synthetic data", 8, height + 30, note);
        File directory = new File(context.getFilesDir(), "ui-review");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("Cannot create UI review directory");
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (Exception error) { throw new AssertionError(error); }
        finally { image.recycle(); }
    }

    private void fill(View root, int id, String value) {
        TextView text = root.findViewById(id); if (text != null) text.setText(value);
    }
}
