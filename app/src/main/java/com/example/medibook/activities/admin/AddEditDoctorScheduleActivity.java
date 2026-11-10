package com.example.medibook.activities.admin;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityAddEditDoctorScheduleBinding;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.repositories.DoctorScheduleRepository;
import com.example.medibook.utils.AppConstants;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DoctorScheduleContract;
import com.example.medibook.utils.DoctorScheduleErrorMessages;
import com.example.medibook.utils.DoctorScheduleUi;
import com.example.medibook.utils.DoctorScheduleValidation;
import com.example.medibook.viewmodels.DoctorScheduleEditorViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** One online Save, retained across rotation. Only an acknowledged transaction returns success. */
public class AddEditDoctorScheduleActivity extends AppCompatActivity implements AuthRepository.Listener,
        DoctorScheduleEditorViewModel.Listener {
    private static final String TIME_PICKER_TAG = "scheduleTimePicker";
    private ActivityAddEditDoctorScheduleBinding binding;
    private AuthRepository authRepository;
    private DoctorScheduleEditorViewModel model;
    private String doctorId;
    private String doctorName;
    private String owner;
    private boolean invalidContext;
    private boolean adminVerified;
    private int day = -1;
    private int startMinute = -1;
    private int endMinute = -1;
    private int populatedRevision;
    private DoctorScheduleRepository.Error displayedError = DoctorScheduleRepository.Error.NONE;
    private MaterialTimePicker timePicker;
    private AlertDialog discardDialog;
    private Snackbar message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // DialogFragments can restore before a fresh role check. Do not expose a restored picker.
        Fragment restored = getSupportFragmentManager().findFragmentByTag(TIME_PICKER_TAG);
        if (restored instanceof DialogFragment) {
            ((DialogFragment) restored).dismissAllowingStateLoss();
            getSupportFragmentManager().executePendingTransactions();
        }
        binding = ActivityAddEditDoctorScheduleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        authRepository = AuthRepository.getInstance();
        model = new ViewModelProvider(this).get(DoctorScheduleEditorViewModel.class);
        String scheduleId = null;
        boolean editing = false;
        try {
            doctorId = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_ID);
            doctorName = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_NAME);
            owner = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_OWNER);
            scheduleId = savedInstanceState == null
                    ? getIntent().getStringExtra(DoctorScheduleContract.EXTRA_SCHEDULE_ID)
                    : savedInstanceState.getString("scheduleId");
            editing = savedInstanceState == null ? getIntent().hasExtra(DoctorScheduleContract.EXTRA_SCHEDULE_ID)
                    : savedInstanceState.getBoolean("editing");
            invalidContext = !DoctorScheduleContract.validContext(doctorId, doctorName, owner)
                    || ((editing || savedInstanceState != null) && !DoctorScheduleValidation.validId(scheduleId));
        } catch (RuntimeException ignored) { invalidContext = true; }
        model.initialize(doctorId, scheduleId, editing, savedInstanceState != null);
        binding.dayInput.setSimpleItems(DoctorScheduleUi.days(this));
        if (savedInstanceState != null) {
            day = savedInstanceState.getInt("day", -1);
            startMinute = savedInstanceState.getInt("start", -1);
            endMinute = savedInstanceState.getInt("end", -1);
            binding.slotInput.setText(savedInstanceState.getString("slotText", ""));
            binding.activeSwitch.setChecked(savedInstanceState.getBoolean("active"));
            populatedRevision = savedInstanceState.getInt("populatedRevision");
        }
        if (day >= 1 && day <= 7) binding.dayInput.setText(DoctorScheduleUi.day(this, day), false);
        int title = editing ? R.string.schedule_edit_title : R.string.schedule_add_button;
        binding.toolbar.setTitle(title);
        if (!invalidContext) binding.doctorHeading.setText(getString(R.string.schedule_doctor_heading, doctorName));
        binding.timezoneHint.setText(getString(R.string.schedule_timezone_hint, AppConstants.HOSPITAL_TIME_ZONE_ID));
        renderTimes();

        binding.dayInput.setOnItemClickListener((parent, view, position, id) -> {
            if (canEdit()) { day = position + 1; binding.dayLayout.setError(null); }
        });
        binding.startTimeButton.setOnClickListener(view -> showTimePicker(true));
        binding.endTimeButton.setOnClickListener(view -> showTimePicker(false));
        binding.saveButton.setOnClickListener(view -> validateAndSave());
        binding.reloadButton.setOnClickListener(view -> requestReload());
        binding.cancelButton.setOnClickListener(view -> requestCancel());
        binding.backToDoctorsButton.setOnClickListener(view -> finish());
        binding.toolbar.setNavigationOnClickListener(view -> requestCancel());
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.actionLogout) return false;
            authRepository.signOut();
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { requestCancel(); }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        model.addListener(this);
        authRepository.checkSession();
        authRepository.addListener(this);
    }

    @Override
    protected void onStop() {
        authRepository.removeListener(this);
        model.removeListener(this);
        adminVerified = false;
        binding.adminContent.setVisibility(View.GONE);
        binding.dayInput.dismissDropDown();
        dismissPicker();
        if (discardDialog != null) discardDialog.dismiss();
        if (message != null) message.dismiss();
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        adminVerified = !state.loading && state.user != null && state.user.getRole() == UserProfile.Role.ADMIN;
        binding.adminContent.setVisibility(adminVerified ? View.VISIBLE : View.GONE);
        binding.sessionLoading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        if (state.loading) {
            binding.dayInput.dismissDropDown();
            dismissPicker();
            if (discardDialog != null) discardDialog.dismiss();
            return;
        }
        if (!adminVerified || (!invalidContext && !DoctorRepository.sameUser(owner))) {
            adminVerified = false;
            binding.adminContent.setVisibility(View.GONE);
            dismissPicker();
            model.endSession();
            AuthNavigation.openDestination(this, state.user);
            return;
        }
        binding.formContent.setVisibility(invalidContext ? View.GONE : View.VISIBLE);
        binding.errorContent.setVisibility(invalidContext ? View.VISIBLE : View.GONE);
        if (!invalidContext) {
            if (!model.startSession(owner)) {
                model.endSession();
                AuthNavigation.openDestination(this, state.user);
            } else onScheduleChanged(model.getState());
        }
    }

    private boolean canAccess() {
        return adminVerified && !invalidContext && DoctorRepository.sameUser(owner) && !isFinishing();
    }

    private boolean canEdit() {
        return canAccess() && model.getState().canInteract();
    }

    @Override
    public void onScheduleChanged(DoctorScheduleEditorViewModel.State state) {
        if (!canAccess()) return;
        int title = state.editing ? R.string.schedule_edit_title : R.string.schedule_add_button;
        binding.toolbar.setTitle(title);
        if (state.doctorName != null) {
            doctorName = state.doctorName;
            binding.doctorHeading.setText(getString(R.string.schedule_doctor_heading, doctorName));
        }
        if (state.original != null && state.loaded && state.error == DoctorScheduleRepository.Error.NONE
                && populatedRevision != state.revision) {
            populateForm(state.original);
            populatedRevision = state.revision;
        }
        boolean enabled = state.canInteract();
        binding.dayLayout.setEnabled(enabled);
        binding.startTimeButton.setEnabled(enabled);
        binding.endTimeButton.setEnabled(enabled);
        binding.slotLayout.setEnabled(enabled);
        binding.activeSwitch.setEnabled(enabled);
        binding.saveButton.setEnabled(state.canSave());
        binding.saveButton.setText(state.saving ? R.string.schedule_saving : R.string.schedule_save_button);
        binding.cancelButton.setEnabled(!state.saving);
        binding.operationProgress.setVisibility(state.loading || state.saving ? View.VISIBLE : View.GONE);
        boolean error = state.error != DoctorScheduleRepository.Error.NONE;
        binding.operationStatus.setVisibility(state.loading || state.saving || error ? View.VISIBLE : View.GONE);
        if (state.loading || state.saving || error) {
            binding.operationStatus.setText(state.saving ? R.string.schedule_saving_message
                    : state.loading ? R.string.schedule_loading_message : DoctorScheduleErrorMessages.forError(state.error));
        }
        binding.operationStatus.setTextColor(ContextCompat.getColor(this,
                error ? R.color.medibook_error : R.color.medibook_text_secondary));
        binding.reloadButton.setVisibility(error ? View.VISIBLE : View.GONE);
        binding.reloadButton.setEnabled(!state.loading && !state.saving && !state.completed);
        if (!enabled) { binding.dayInput.dismissDropDown(); dismissPicker(); }
        if (error && displayedError != state.error) {
            binding.operationStatus.post(() -> {
                if (!canAccess()) return;
                Rect bounds = new Rect();
                binding.operationStatus.getDrawingRect(bounds);
                binding.operationStatus.requestRectangleOnScreen(bounds, true);
            });
        }
        displayedError = state.error;
        if (state.completed) {
            Intent data = new Intent();
            DoctorScheduleContract.putContext(data, doctorId, doctorName, owner);
            data.putExtra(DoctorScheduleContract.EXTRA_SCHEDULE_ID, state.scheduleId);
            data.putExtra(DoctorScheduleContract.EXTRA_CHANGE,
                    state.editing ? DoctorScheduleContract.EDITED : DoctorScheduleContract.ADDED);
            setResult(RESULT_OK, data);
            finish();
        }
    }

    private void populateForm(DoctorSchedule original) {
        day = original.getDayOfWeek();
        startMinute = original.getStartMinute();
        endMinute = original.getEndMinute();
        binding.dayInput.setText(DoctorScheduleUi.day(this, day), false);
        NumberFormat inputFormat = NumberFormat.getIntegerInstance(Locale.ROOT);
        inputFormat.setGroupingUsed(false);
        binding.slotInput.setText(inputFormat.format(original.getSlotMinutes()));
        binding.activeSwitch.setChecked(original.isActive());
        binding.dayLayout.setError(null);
        binding.slotLayout.setError(null);
        showTimeError(binding.startError, null);
        showTimeError(binding.endError, null);
        renderTimes();
    }

    private void requestReload() {
        DoctorScheduleEditorViewModel.State state = model.getState();
        if (!canAccess() || state.loading || state.saving || state.completed
                || (discardDialog != null && discardDialog.isShowing())) return;
        discardDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.schedule_reload_title).setMessage(R.string.schedule_reload_message)
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(R.string.schedule_reload_button, (dialog, which) -> {
                    if (!canAccess()) return;
                    populatedRevision = -1;
                    model.reload();
                }).show();
    }

    private void showTimePicker(boolean start) {
        if (!canEdit() || timePicker != null || getSupportFragmentManager().isStateSaved()) return;
        int selected = start ? startMinute : endMinute;
        if (selected < 0 || selected >= 1440) {
            Calendar clock = Calendar.getInstance(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
            selected = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE);
        }
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setTitleText(start ? R.string.schedule_choose_start : R.string.schedule_choose_end)
                .setHour(selected / 60).setMinute(selected % 60).build();
        timePicker = picker;
        picker.addOnPositiveButtonClickListener(view -> {
            if (!canEdit()) return;
            int minute = picker.getHour() * 60 + picker.getMinute();
            if (start) { startMinute = minute; showTimeError(binding.startError, null); }
            else { endMinute = minute; showTimeError(binding.endError, null); }
            renderTimes();
        });
        picker.addOnDismissListener(dialog -> { if (timePicker == picker) timePicker = null; });
        picker.show(getSupportFragmentManager(), TIME_PICKER_TAG);
    }

    private void dismissPicker() {
        if (timePicker != null) { timePicker.dismissAllowingStateLoss(); timePicker = null; }
    }

    private void renderTimes() {
        binding.startTimeButton.setText(startMinute >= 0 && startMinute < 1440
                ? getString(R.string.schedule_start_value, DoctorScheduleUi.time(this, startMinute))
                : getString(R.string.schedule_choose_start));
        binding.endTimeButton.setText(endMinute >= 0 && endMinute < 1440
                ? getString(R.string.schedule_end_value, DoctorScheduleUi.time(this, endMinute))
                : getString(R.string.schedule_choose_end));
    }

    private void validateAndSave() {
        if (!canEdit() || !model.getState().canSave()) return;
        DoctorScheduleValidation.Result result = DoctorScheduleValidation.validate(day, startMinute, endMinute, slotText());
        binding.dayLayout.setError(result.dayError == DoctorScheduleValidation.Error.NONE
                ? null : getString(R.string.schedule_error_day));
        showTimeError(binding.startError, result.startError == DoctorScheduleValidation.Error.NONE
                ? null : getString(R.string.schedule_error_start));
        showTimeError(binding.endError, result.endError == DoctorScheduleValidation.Error.NONE ? null
                : getString(result.endError == DoctorScheduleValidation.Error.ORDER
                ? R.string.schedule_error_order : R.string.schedule_error_end));
        binding.slotLayout.setError(result.slotError == DoctorScheduleValidation.Error.NONE ? null
                : getString(result.slotError == DoctorScheduleValidation.Error.SLOT_REQUIRED
                ? R.string.schedule_error_slot_required : result.slotError == DoctorScheduleValidation.Error.SLOT_WINDOW
                ? R.string.schedule_error_slot_window : R.string.schedule_error_slot_invalid));
        View firstInvalid = result.dayError != DoctorScheduleValidation.Error.NONE ? binding.dayInput
                : result.startError != DoctorScheduleValidation.Error.NONE ? binding.startTimeButton
                : result.endError != DoctorScheduleValidation.Error.NONE ? binding.endTimeButton
                : result.slotError != DoctorScheduleValidation.Error.NONE ? binding.slotInput : null;
        if (firstInvalid != null) {
            firstInvalid.requestFocus();
            // Time buttons need explicit scrolling: unlike EditTexts, they may not take touch-mode focus.
            View field = firstInvalid == binding.dayInput ? binding.dayLayout
                    : firstInvalid == binding.slotInput ? binding.slotLayout : firstInvalid;
            field.post(() -> {
                if (!canEdit()) return;
                Rect bounds = new Rect();
                field.getDrawingRect(bounds);
                if (field == binding.startTimeButton) bounds.bottom += binding.startError.getHeight();
                if (field == binding.endTimeButton) bounds.bottom += binding.endError.getHeight();
                field.requestRectangleOnScreen(bounds, true);
            });
            return;
        }
        model.save(result.toSchedule(model.getState().scheduleId, binding.activeSwitch.isChecked()));
    }

    private void showTimeError(TextView view, String error) {
        view.setText(error);
        view.setVisibility(error == null ? View.GONE : View.VISIBLE);
    }

    private String slotText() { return binding.slotInput.getText() == null ? "" : binding.slotInput.getText().toString(); }

    private void requestCancel() {
        if (isFinishing()) return;
        if (model.getState().saving) {
            if (message != null) message.dismiss();
            message = Snackbar.make(binding.getRoot(), R.string.schedule_wait_for_write, Snackbar.LENGTH_LONG);
            message.show();
            return;
        }
        if (!canAccess() || !hasChanges()) { finish(); return; }
        if (discardDialog != null && discardDialog.isShowing()) return;
        discardDialog = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_MediBook_DestructiveDialog)
                .setTitle(R.string.schedule_discard_title).setMessage(R.string.schedule_discard_message)
                .setNegativeButton(R.string.schedule_keep_editing, null)
                .setPositiveButton(R.string.schedule_discard_button, (dialog, which) -> finish()).show();
    }

    private boolean hasChanges() {
        DoctorSchedule original = model.getState().original;
        return original == null ? day != -1 || startMinute != -1 || endMinute != -1
                || !slotText().isEmpty() || binding.activeSwitch.isChecked()
                : day != original.getDayOfWeek() || startMinute != original.getStartMinute()
                || endMinute != original.getEndMinute() || !slotText().equals(Integer.toString(original.getSlotMinutes()))
                || binding.activeSwitch.isChecked() != original.isActive();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("scheduleId", model.getState().scheduleId);
        outState.putBoolean("editing", model.getState().editing);
        outState.putInt("populatedRevision", populatedRevision);
        outState.putInt("day", day);
        outState.putInt("start", startMinute);
        outState.putInt("end", endMinute);
        outState.putString("slotText", slotText());
        outState.putBoolean("active", binding.activeSwitch.isChecked());
        super.onSaveInstanceState(outState);
    }
}
