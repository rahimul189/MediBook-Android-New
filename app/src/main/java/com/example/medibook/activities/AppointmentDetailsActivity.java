package com.example.medibook.activities;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.example.medibook.R;
import com.example.medibook.cache.PatientDataCache;
import com.example.medibook.databinding.ActivityAppointmentDetailsBinding;
import com.example.medibook.local.LocalPatientServices;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.UserProfile;
import com.example.medibook.reminders.ReminderMode;
import com.example.medibook.reminders.ReminderNotifications;
import com.example.medibook.reminders.ReminderSchedule;
import com.example.medibook.repositories.AppointmentRepository;
import com.example.medibook.repositories.AppointmentReadRepository;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.utils.AppConstants;
import com.example.medibook.utils.AppointmentPolicy;
import com.example.medibook.utils.AppointmentPresentation;
import com.example.medibook.utils.AppointmentSerials;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.SlotSerials;
import com.example.medibook.utils.ViewUpdates;
import com.example.medibook.viewmodels.AppointmentOperationViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class AppointmentDetailsActivity extends AppointmentSessionActivity {
    private ActivityAppointmentDetailsBinding binding;
    private final AppointmentRepository repository = new AppointmentRepository();
    private final AppointmentReadRepository reads = new AppointmentReadRepository();
    private final PatientDataCache cache = PatientDataCache.get();
    private AppointmentOperationViewModel operation;
    private Appointment current;
    /** Display only until the server get succeeds; never used by cancel/reminder actions. */
    private Appointment cachedDisplay;
    private boolean readStarted;
    private UserProfile.Role readRole;
    private String id;
    private boolean busy, observing;
    private androidx.appcompat.app.AlertDialog confirmation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override public void run() { if (canAccess()) render(); handler.postDelayed(this, 30_000); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityAppointmentDetailsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        // Opening a reminder also stops any alarm audio/vibration still running.
        ReminderNotifications.stopFeedback(this);
        AuthUi.applyInsets(this, binding.getRoot()); id = getIntent().getStringExtra("appointmentId");
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.details, true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.detailsScheduleHeading, true);
        operation = new ViewModelProvider(this).get(AppointmentOperationViewModel.class);
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.refreshButton.setOnClickListener(view -> { if (canAccess() && !busy) load(); });
        binding.reminderButton.setOnClickListener(view -> showCustomReminderDialog());
        binding.removeReminderButton.setOnClickListener(view -> {
            if (!canAccess() || !canCustomRemind()) return;
            final Appointment target = current;
            final String targetOwner = owner;
            confirmation = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_MediBook_DestructiveDialog)
                    .setTitle(R.string.reminder_remove_title).setMessage(R.string.reminder_remove_message)
                    .setNegativeButton(R.string.cancel_button, null)
                    .setPositiveButton(R.string.reminder_custom_remove, (dialog, which) -> {
                        if (!canAccess() || !canCustomRemind() || !targetOwner.equals(owner) || current != target) return;
                        if (LocalPatientServices.get().removeCustomReminder(owner, current)) {
                            binding.message.setText(R.string.reminder_custom_removed); render();
                        }
                    }).show();
        });
        binding.cancelButton.setOnClickListener(view -> {
            if (!canAccess() || busy || !AppointmentPolicy.canCancel(current, System.currentTimeMillis())) return;
            confirmation = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_MediBook_DestructiveDialog).setTitle(R.string.appointment_cancel_title)
                    .setMessage(R.string.appointment_cancel_message).setNegativeButton(R.string.cancel_button, null)
                    .setPositiveButton(R.string.appointment_cancel, (dialog, which) -> {
                        if (!canAccess() || busy || !AppointmentPolicy.canCancel(current, System.currentTimeMillis())) return;
                        requestVersion++; busy = true; render(); binding.message.setText(R.string.appointment_working);
                        operation.operation = repository.cancel(owner, id); observe();
                    }).show();
        });
    }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected void onVerifiedSession() {
        if (getIntent().hasExtra("reminderOwner") && !com.example.medibook.local.LocalPatientServices.get().matches(
                getIntent().getStringExtra("reminderOwner"), getIntent().getStringExtra("reminderEpoch"))) {
            // Stale notification taps after logout/account switching never load its appointment.
            finish(); return;
        }
        if (!DoctorMapper.isValidId(id)) { binding.message.setText(R.string.appointment_invalid); return; }
        if (readRole != null && readRole != session.getRole()) { requestVersion++; clearSessionViews(); readStarted = false; }
        readRole = session.getRole();
        if (operation.operation != null) { busy = true; render(); observe(); }
        else if (!readStarted) { readStarted = true; load(); }
    }
    @Override protected void clearSessionViews() {
        current = null; cachedDisplay = null; observing = false; binding.details.setText(null); binding.message.setText(null);
        binding.summaryCard.setVisibility(View.GONE);
        binding.detailsSpecialty.setText(null); binding.detailsDate.setText(null); binding.detailsTime.setText(null);
        binding.detailsSlot.setText(null); binding.detailsFee.setText(null); binding.appointmentStatus.setText(null);
        binding.cancelButton.setVisibility(View.GONE);
        binding.patientCard.setVisibility(View.GONE);
        binding.reminderCard.setVisibility(View.GONE);
        if (confirmation != null) confirmation.dismiss();
    }
    private void load() {
        if (current == null && session.getRole() == UserProfile.Role.PATIENT) cachedDisplay = cache.appointment(owner, id);
        binding.message.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.medibook_text_secondary));
        // A safe same-account refresh keeps the verified summary/number visible. A new snapshot
        // does not contain a serial; do not replace a known number while its sidecar is unresolved.
        busy = true; render();
        if (current == null) {
            binding.message.setText(cachedDisplay == null ? R.string.appointment_loading : R.string.dashboard_cached);
            binding.patientCard.setVisibility(View.GONE);
        } else binding.message.setText(null);
        int version = ++requestVersion;
        reads.get(owner, id, session.getRole()).addOnCompleteListener(this, task -> {
            if (!canAccess() || version != requestVersion) return;
            busy = false;
            if (task.isSuccessful()) {
                current = cache.withKnownAppointmentSerial(owner, AppointmentSerials.retain(task.getResult(), current));
                cachedDisplay = null;
                if (session.getRole() == UserProfile.Role.PATIENT) cache.putAppointment(owner, current);
                binding.message.setText(null);
                loadSerial(version); loadPatientProfile(version);
            }
            else {
                binding.message.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.medibook_error));
                binding.message.setText(AppointmentUi.errorText(this, task.getException()));
            }
            render();
        });
    }
    private void render() {
        Appointment shown = current == null ? cachedDisplay : current;
        binding.progress.setVisibility(busy && shown == null ? View.VISIBLE : View.GONE); binding.refreshButton.setEnabled(!busy);
        binding.summaryCard.setVisibility(shown == null ? View.GONE : View.VISIBLE);
        if (shown != null) {
            ViewUpdates.text(binding.details, shown.getDoctorName());
            ViewUpdates.text(binding.detailsSpecialty, shown.getDoctorSpecialization());
            ViewUpdates.text(binding.detailsDate, AppointmentUi.date(shown.getAppointmentDate().toDate().getTime()));
            ViewUpdates.text(binding.detailsTime, getString(R.string.next_time_range, DhakaDateTime.formatTime12Hour(shown.getStartMinute()), DhakaDateTime.formatTime12Hour(shown.getEndMinute())));
            AppointmentPresentation.bindSlot(binding.detailsSlot, shown);
            ViewUpdates.text(binding.detailsFee, getString(R.string.doctor_fee_amount, java.math.BigDecimal.valueOf(shown.getConsultationFee(), 2).toPlainString()));
            com.example.medibook.utils.AppointmentPresentation.bindStatus(binding.appointmentStatus, shown);
        }
        binding.cancelButton.setVisibility(!busy && AppointmentPolicy.canCancel(current, System.currentTimeMillis()) ? View.VISIBLE : View.GONE);
        renderReminder();
    }

    /** Reminder controls exist only for the verified Patient and a future confirmed appointment they own. */
    private boolean canCustomRemind() {
        return AppointmentPolicy.canCustomRemind(current, session == null ? null : session.getRole(), owner,
                System.currentTimeMillis());
    }

    private void renderReminder() {
        LocalPatientServices local = LocalPatientServices.get();
        boolean available = canCustomRemind() && local != null && local.epoch(owner) != null;
        binding.reminderCard.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available) return;
        long trigger = local.customReminderTime(owner, current);
        boolean custom = trigger > 0;
        ReminderMode mode = local.customReminderMode(owner, current);
        binding.reminderStatus.setText(custom
                ? getString(R.string.reminder_custom_status, reminderTime(trigger),
                    getString(ReminderMode.ALARM.equals(mode)
                            ? R.string.reminder_mode_alarm_short : R.string.reminder_mode_notification_short))
                : getString(R.string.reminder_defaults_status));
        binding.reminderButton.setText(custom ? R.string.reminder_custom_edit : R.string.reminder_custom_set);
        binding.removeReminderButton.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private String reminderTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault());
        format.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        return format.format(new Date(millis));
    }

    private void showCustomReminderDialog() {
        if (!canAccess() || !canCustomRemind()) return;
        final LocalPatientServices local = LocalPatientServices.get();
        final View content = getLayoutInflater().inflate(R.layout.dialog_custom_reminder, null);
        final TextView dateButton = content.findViewById(R.id.reminderDateButton);
        final TextView timeButton = content.findViewById(R.id.reminderTimeButton);
        final TextView preview = content.findViewById(R.id.reminderPreview);
        final TextView error = content.findViewById(R.id.reminderError);
        final android.widget.RadioGroup modeGroup = content.findViewById(R.id.reminderModeGroup);
        final TextView modeHelp = content.findViewById(R.id.reminderModeHelp);

        long start = current.getStartAt().toDate().getTime();
        long now = System.currentTimeMillis();
        long existing = local.customReminderTime(owner, current);
        final long[] selected = {existing > 0 ? existing : suggestedTrigger(start, now)};

        Runnable refresh = () -> {
            error.setVisibility(View.GONE);
            dateButton.setText(reminderDate(selected[0]));
            timeButton.setText(reminderClock(selected[0]));
            preview.setText(getString(R.string.reminder_preview, reminderTime(selected[0])));
        };
        dateButton.setOnClickListener(view -> {
            int[] currentFields = DhakaDateTime.localDateTime(selected[0]);
            DatePickerDialog picker = new DatePickerDialog(this, (dialog, year, month, day) -> {
                int[] time = DhakaDateTime.localDateTime(selected[0]);
                selected[0] = DhakaDateTime.toEpochMillis(year, month + 1, day, time[3], time[4]);
                refresh.run();
            }, currentFields[0], currentFields[1] - 1, currentFields[2]);
            picker.show();
        });
        timeButton.setOnClickListener(view -> {
            int[] currentFields = DhakaDateTime.localDateTime(selected[0]);
            TimePickerDialog picker = new TimePickerDialog(this, (dialog, hour, minute) -> {
                int[] date = DhakaDateTime.localDateTime(selected[0]);
                selected[0] = DhakaDateTime.toEpochMillis(date[0], date[1], date[2], hour, minute);
                refresh.run();
            }, currentFields[3], currentFields[4], false);
            picker.show();
        });

        modeGroup.check(local.customReminderMode(owner, current) == ReminderMode.ALARM
                ? R.id.modeAlarm : R.id.modeNotification);
        Runnable updateModeHelp = () -> modeHelp.setText(
                modeGroup.getCheckedRadioButtonId() == R.id.modeAlarm
                        && !ReminderNotifications.alarmStyleAvailable(this)
                        ? R.string.reminder_alarm_fallback : R.string.reminder_mode_help);
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> updateModeHelp.run());
        updateModeHelp.run();

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reminder_custom_title).setView(content)
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(R.string.reminder_custom_save, null).create();
        dialog.setOnShowListener(shown -> {
            refresh.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!canAccess() || !canCustomRemind()) { dialog.dismiss(); return; }
                if (!ReminderNotifications.allowed(this)) {
                    showDialogError(error, R.string.reminder_notifications_off);
                    return;
                }
                long reminderStart = current.getStartAt().toDate().getTime();
                if (!ReminderSchedule.customTriggerValid(reminderStart, selected[0], System.currentTimeMillis())) {
                    showDialogError(error, R.string.reminder_custom_time_invalid);
                    return;
                }
                ReminderMode mode = modeGroup.getCheckedRadioButtonId() == R.id.modeAlarm
                        ? ReminderMode.ALARM : ReminderMode.NOTIFICATION;
                if (!local.saveCustomReminder(owner, current, selected[0], mode)) {
                    showDialogError(error, R.string.reminder_custom_time_invalid);
                    return;
                }
                dialog.dismiss();
                render();
                binding.message.setText(R.string.reminder_custom_saved);
                if (!local.exactAlarmsAllowed()) {
                    Snackbar.make(binding.getRoot(), R.string.reminder_custom_exact_unavailable,
                            Snackbar.LENGTH_LONG).setAction(R.string.reminder_allow, action -> {
                                if (android.os.Build.VERSION.SDK_INT >= 31) {
                                    try {
                                        startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                android.net.Uri.parse("package:" + getPackageName())));
                                    } catch (android.content.ActivityNotFoundException | SecurityException unavailable) { }
                                }
                            }).show();
                }
            });
        });
        dialog.show();
    }

    /** A future instant strictly before the appointment, in Dhaka wall-clock terms. */
    private static long suggestedTrigger(long start, long now) {
        long[] candidates = {start - 4 * ReminderSchedule.HOUR, start - ReminderSchedule.HOUR,
                start - 15 * ReminderSchedule.MINUTE, now + 5 * ReminderSchedule.MINUTE};
        for (long candidate : candidates) {
            candidate -= Math.floorMod(candidate, ReminderSchedule.MINUTE);
            if (ReminderSchedule.customTriggerValid(start, candidate, now)) return candidate;
        }
        return start - Math.floorMod(start, ReminderSchedule.MINUTE) - ReminderSchedule.MINUTE;
    }

    private String reminderDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy", Locale.getDefault());
        format.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        return format.format(new Date(millis));
    }

    private String reminderClock(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("h:mm a", Locale.getDefault());
        format.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        return format.format(new Date(millis));
    }

    private void showDialogError(TextView error, int message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }
    /** Name, age, gender and mobile resolved from the owning profile; legacy gaps show placeholders. */
    private void loadPatientProfile(int version) {
        if (current == null) return;
        binding.patientCard.setVisibility(View.VISIBLE);
        binding.patientName.setText(getString(R.string.appointment_patient_name_value, current.getPatientName()));
        binding.patientPhone.setText(getString(R.string.appointment_patient_phone_value, current.getPatientPhone()));
        showPersonalDetails(null);
        com.google.firebase.auth.FirebaseUser self =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        com.example.medibook.models.UserProfile verified =
                com.example.medibook.repositories.AuthRepository.getInstance().verifiedUser();
        if (verified != null && self != null && self.getUid().equals(current.getPatientUid())) {
            showPersonalDetails(verified);
            return;
        }
        new com.example.medibook.repositories.PatientProfileRepository().get(current.getPatientUid(), profile -> {
            if (canAccess() && version == requestVersion) showPersonalDetails(profile);
        });
    }

    private void showPersonalDetails(com.example.medibook.models.UserProfile profile) {
        Integer age = profile == null ? null : com.example.medibook.utils.PatientAge.years(
                profile.getDateOfBirth(), System.currentTimeMillis());
        binding.patientAge.setText(age == null ? getString(R.string.profile_not_provided)
                : getString(R.string.profile_age_value, age));
        String gender = profile == null ? null
                : com.example.medibook.utils.PatientGender.display(profile.getGender());
        binding.patientGender.setText(gender == null ? getString(R.string.profile_not_provided)
                : getString(R.string.profile_gender_value, gender));
    }

    private void loadSerial(int version) {
        if (current == null) return;
        if (SlotSerials.isAssigned(current.getSlotSerial())) {
            cache.rememberAppointmentSerial(owner, current); return;
        }
        final Appointment target = current;
        new com.example.medibook.repositories.SlotSerialRepository().appointments(owner, java.util.Collections.singletonList(target))
                .addOnSuccessListener(this, values -> {
                    if (!canAccess() || busy || values.isEmpty()) return;
                    Appointment resolved = AppointmentSerials.applyLookup(current, target, values.get(0), version, requestVersion);
                    if (resolved == current) return;
                    current = resolved; cache.rememberAppointmentSerial(owner, current);
                    if (session.getRole() == UserProfile.Role.PATIENT) cache.putAppointment(owner, current);
                    // Only this field changed; do not rebind the schedule/cards or restart loading.
                    AppointmentPresentation.bindSlot(binding.detailsSlot, current);
                });
    }
    private void observe() {
        if (observing || operation.operation == null) return;
        observing = true;
        operation.operation.addOnCompleteListener(this, task -> {
            if (!canAccess()) return;
            observing = false; operation.operation = null; busy = false;
            binding.message.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    task.isSuccessful() ? R.color.medibook_success : R.color.medibook_error));
            if (task.isSuccessful()) {
                current = cache.withKnownAppointmentSerial(owner, AppointmentSerials.retain(task.getResult(), current));
                binding.message.setText(R.string.appointment_cancellation_saved); loadSerial(requestVersion);
            }
            else binding.message.setText(AppointmentUi.errorText(this, task.getException()));
            render();
        });
    }
    @Override protected void onStart() { super.onStart(); handler.post(clock); }
    @Override protected void onStop() { readStarted = false; handler.removeCallbacks(clock); super.onStop(); }
}
