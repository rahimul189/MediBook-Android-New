package com.example.medibook.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.adapters.AppointmentAdapter;
import com.example.medibook.databinding.ActivityAppointmentsBinding;
import com.example.medibook.databinding.ItemAppointmentListHeaderBinding;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AppointmentReadRepository;
import com.example.medibook.utils.AppointmentDisplayStatus;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DhakaDateTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Own-only Patient history or Admin appointment list. Never queries a patient directory. */
public final class AppointmentsActivity extends AppointmentSessionActivity {
    public static final String EXTRA_BOOKING_RESULT = "bookingResultId";
    private ActivityAppointmentsBinding binding;
    private ItemAppointmentListHeaderBinding controls;
    private AppointmentAdapter adapter;
    private final AppointmentReadRepository repository = new AppointmentReadRepository();
    private List<Appointment> entries = Collections.emptyList();
    private boolean admin, initialized, loading;
    private boolean readStarted, snapshotKnown;
    private long date = -1;
    private int error;
    private int statusFilterIndex;
    private String[] statusLabels;
    private String bookingResultId;
    private androidx.appcompat.app.AlertDialog reminderDialog;
    private String permissionOwner, permissionEpoch;
    private Boolean permissionResult;
    private final androidx.activity.result.ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), granted -> {
                permissionResult = granted; consumePermissionResult();
            });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override public void run() { if (canAccess()) render(); handler.postDelayed(this, 30_000); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityAppointmentsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        adapter = new AppointmentAdapter(a -> {
            if (canAccess()) startActivity(new Intent(this, AppointmentDetailsActivity.class).putExtra("appointmentId", a.getId()));
        });
        // The header is inflated with this RecyclerView as its parent, so the LayoutManager must
        // exist first: RecyclerView.generateLayoutParams() throws otherwise on a real device.
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        controls = ItemAppointmentListHeaderBinding.inflate(getLayoutInflater(), binding.list, false);
        binding.list.setAdapter(new ConcatAdapter(new ControlsAdapter(), adapter));
        bookingResultId = getIntent().getStringExtra(EXTRA_BOOKING_RESULT);
        statusLabels = new String[]{
                getString(R.string.appointment_all), getString(R.string.appointment_upcoming),
                getString(R.string.appointment_completed), getString(R.string.appointment_cancelled)};
        controls.statusFilter.setSimpleItems(statusLabels);
        selectStatus(0);
        controls.statusFilter.setOnItemClickListener((parent, view, position, id) -> {
            if (!canAccess()) return;
            selectStatus(position); render();
        });
        controls.doctorFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (canAccess()) render(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        controls.refreshButton.setOnClickListener(view -> { if (canAccess() && !loading) refresh(); });
        controls.remindersButton.setOnClickListener(view -> manageReminders());
        controls.emptyAction.setOnClickListener(view -> {
            if (canAccess() && !admin) startActivity(new Intent(this, com.example.medibook.activities.patient.PatientHomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        });
        controls.dateButton.setOnClickListener(view -> {
            if (!canAccess() || !admin) return;
            AppointmentUi.pickDate(this, date < 0 ? AppointmentUi.today() : date, (y, m, d) -> {
                if (!canAccess()) return;
                date = DhakaDateTime.localDateAtMidnight(y, m, d); controls.dateButton.setText(AppointmentUi.date(date)); render();
            });
        });
        controls.clearDateButton.setOnClickListener(view -> {
            date = -1; controls.dateButton.setText(R.string.appointment_all_dates); if (canAccess()) render();
        });
        if (state != null) {
            permissionOwner = state.getString("permissionOwner"); permissionEpoch = state.getString("permissionEpoch");
            date = state.getLong("filterDate", -1);
            if (date >= 0) controls.dateButton.setText(AppointmentUi.date(date));
            controls.doctorFilter.setText(state.getString("filterDoctor", ""));
            selectStatus(state.getInt("filterStatus", 0)); initialized = true;
        }
    }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected void onVerifiedSession() {
        boolean nextAdmin = session.getRole() == UserProfile.Role.ADMIN;
        if (readStarted && admin != nextAdmin) {
            requestVersion++; entries = Collections.emptyList(); readStarted = false; snapshotKnown = false;
        }
        admin = nextAdmin;
        binding.toolbar.setTitle(admin ? R.string.appointment_manage : R.string.appointment_my);
        controls.doctorFilterLayout.setVisibility(admin ? View.VISIBLE : View.GONE);
        controls.dateFilters.setVisibility(admin ? View.VISIBLE : View.GONE);
        renderReminderButton(); consumePermissionResult();
        if (!initialized) { initialized = true; selectStatus(admin ? 0 : 1); }
        if (!readStarted) { readStarted = true; refresh(); }
    }
    @Override protected void clearSessionViews() {
        snapshotKnown = false; entries = Collections.emptyList(); if (adapter != null) adapter.submit(entries, false);
        controls.message.setText(null); controls.emptyAction.setVisibility(View.GONE);
        // Initial session verification also clears protected views. Keep the ID-only display hint
        // until the authorized own-list resolves it; it never grants access to an appointment.
        if (!com.example.medibook.repositories.DoctorRepository.sameUser(owner)) {
            bookingResultId = null; getIntent().removeExtra(EXTRA_BOOKING_RESULT);
        }
        if (reminderDialog != null) { reminderDialog.dismiss(); reminderDialog = null; }
    }
    private void renderReminderButton() {
        controls.remindersButton.setVisibility(admin ? View.GONE : View.VISIBLE);
        controls.remindersButton.setText(com.example.medibook.local.LocalPatientServices.get().remindersActive(owner)
                ? R.string.reminder_enabled : R.string.reminder_enable);
    }
    private void manageReminders() {
        if (!canAccess() || admin) return;
        com.example.medibook.local.LocalPatientServices local = com.example.medibook.local.LocalPatientServices.get();
        permissionOwner = owner; permissionEpoch = local.epoch(owner);
        boolean enabled = local.remindersActive(owner);
        reminderDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(enabled ? R.string.reminder_turn_off : R.string.reminder_permission_title)
                .setMessage(enabled ? R.string.reminder_off_message : R.string.reminder_explanation)
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(enabled ? R.string.reminder_turn_off : R.string.reminder_allow, (dialog, which) -> {
                    if (!canAccess() || !local.matches(permissionOwner, permissionEpoch)) return;
                    if (enabled) {
                        local.setRemindersActive(owner, false); renderReminderButton(); notice(R.string.reminder_disabled, false);
                    } else if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                            this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                    } else if (!com.example.medibook.reminders.ReminderNotifications.allowed(this)) {
                        reminderDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.reminder_settings).setMessage(R.string.reminder_settings_explanation)
                                .setNegativeButton(R.string.cancel_button, null)
                                .setPositiveButton(R.string.reminder_settings, (d, w) -> openNotificationSettings()).show();
                    } else enableReminders();
                }).show();
    }
    private void consumePermissionResult() {
        if (permissionResult == null || !canAccess() || admin) return;
        boolean granted = permissionResult; permissionResult = null;
        if (!com.example.medibook.local.LocalPatientServices.get().matches(permissionOwner, permissionEpoch)) return;
        if (granted && com.example.medibook.reminders.ReminderNotifications.allowed(this)) enableReminders();
        else { renderReminderButton(); notice(R.string.reminder_denied, true); }
    }
    private void enableReminders() {
        if (!canAccess() || admin || !com.example.medibook.local.LocalPatientServices.get().matches(permissionOwner, permissionEpoch)) return;
        com.example.medibook.local.LocalPatientServices.get().setRemindersActive(owner, true);
        renderReminderButton(); notice(R.string.reminder_on_message, false);
    }
    private void notice(int text, boolean settings) {
        com.google.android.material.snackbar.Snackbar bar = com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), text,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG);
        if (settings) bar.setAction(R.string.reminder_settings, view -> openNotificationSettings());
        bar.show();
    }
    private void openNotificationSettings() {
        if (!canAccess() || admin) return;
        Intent intent = android.os.Build.VERSION.SDK_INT >= 26
                ? new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()));
        try { startActivity(intent); } catch (android.content.ActivityNotFoundException unavailable) { notice(R.string.reminder_denied, false); }
    }
    private void refresh() {
        loading = true; error = 0;
        com.example.medibook.cache.PatientDataCache cache = com.example.medibook.cache.PatientDataCache.get();
        // Keep the previously loaded list visible; a warm return never flashes a full-screen loader.
        if (entries.isEmpty()) {
            java.util.List<Appointment> cached = com.example.medibook.cache.PatientDataCache.get().appointments(owner);
            if (cached != null) { entries = cached; snapshotKnown = true; }
        }
        render();
        int version = ++requestVersion;
        repository.list(owner, admin).addOnCompleteListener(this, task -> {
            if (!canAccess() || version != requestVersion) return;
            loading = false;
            if (task.isSuccessful()) {
                snapshotKnown = true;
                entries = new ArrayList<>();
                for (Appointment appointment : task.getResult()) {
                    entries.add(cache.withKnownAppointmentSerial(owner, appointment));
                }
                showBookingResult();
                if (!admin) com.example.medibook.cache.PatientDataCache.get().putAppointments(owner, entries);
                new com.example.medibook.repositories.SlotSerialRepository().appointments(owner, entries).addOnSuccessListener(this, values -> {
                    if (canAccess() && version == requestVersion) {
                        entries = values;
                        for (Appointment appointment : entries) cache.rememberAppointmentSerial(owner, appointment);
                        if (!admin) cache.putAppointments(owner, entries);
                        render();
                    }
                });
            } else { error = AppointmentUi.error(task.getException()); AppointmentUi.errorText(this, task.getException()); }
            render();
        });
    }
    private void render() {
        List<Appointment> shown = new ArrayList<>();
        int filter = statusFilterIndex;
        String doctor = controls.doctorFilter.getText() == null ? "" : controls.doctorFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (Appointment a : entries) {
            AppointmentDisplayStatus status = AppointmentDisplayStatus.of(a, System.currentTimeMillis());
            if (filter == 1 && status != AppointmentDisplayStatus.CONFIRMED
                    || filter == 2 && status != AppointmentDisplayStatus.COMPLETED
                    || filter == 3 && status != AppointmentDisplayStatus.CANCELLED) continue;
            if (admin && (!a.getDoctorName().toLowerCase(Locale.ROOT).contains(doctor)
                    || date >= 0 && a.getAppointmentDate().toDate().getTime() != date)) continue;
            shown.add(a);
        }
        adapter.submit(shown, admin); controls.refreshButton.setEnabled(!loading);
        // A warm list (including a verified empty result) refreshes without inserting a spinner
        // above the rows. Refresh stays disabled while the actual server request is in flight.
        controls.progress.setVisibility(loading && !snapshotKnown ? View.VISIBLE : View.GONE);
        int message = loading && !snapshotKnown ? R.string.appointment_loading : error != 0 ? error
                : shown.isEmpty() ? entries.isEmpty() ? admin ? R.string.appointment_admin_empty : R.string.appointment_list_empty
                : R.string.appointment_no_matches : 0;
        controls.message.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
        controls.message.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                error != 0 ? R.color.medibook_error : R.color.medibook_text_secondary));
        controls.emptyAction.setVisibility(!admin && !loading && error == 0 && entries.isEmpty() ? View.VISIBLE : View.GONE);
        if (message != 0) controls.message.setText(message);
    }

    private void selectStatus(int index) {
        statusFilterIndex = Math.max(0, Math.min(statusLabels.length - 1, index));
        controls.statusFilter.setText(statusLabels[statusFilterIndex], false);
    }

    private void showBookingResult() {
        if (admin || bookingResultId == null || !canAccess()) return;
        for (Appointment appointment : entries) {
            if (!bookingResultId.equals(appointment.getId()) || !owner.equals(appointment.getPatientUid())) continue;
            bookingResultId = null; getIntent().removeExtra(EXTRA_BOOKING_RESULT);
            String result = getString(appointment.getStatus() == com.example.medibook.models.AppointmentStatus.CONFIRMED
                    ? R.string.appointment_booking_saved : R.string.appointment_existing);
            String text = getString(R.string.appointment_result_feedback, result, appointment.getDoctorName(),
                    AppointmentUi.date(appointment.getAppointmentDate().toDate().getTime()),
                    getString(R.string.next_slot, com.example.medibook.utils.SlotSerials.label(appointment.getSlotSerial(), appointment.getStartMinute(), appointment.getEndMinute())));
            com.google.android.material.snackbar.Snackbar bar = com.google.android.material.snackbar.Snackbar.make(
                    binding.getRoot(), text, com.google.android.material.snackbar.Snackbar.LENGTH_LONG);
            android.widget.TextView label = bar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
            label.setMaxLines(8); bar.show();
            break;
        }
    }

    /** Existing controls scroll as one header; appointment rows retain RecyclerView recycling. */
    private final class ControlsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(controls.getRoot()) { };
            holder.setIsRecyclable(false);
            return holder;
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) { }
        @Override public int getItemCount() { return 1; }
    }
    @Override protected void onStart() { super.onStart(); handler.post(clock); }
    @Override protected void onStop() { readStarted = false; handler.removeCallbacks(clock); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("permissionOwner", permissionOwner); state.putString("permissionEpoch", permissionEpoch);
        state.putLong("filterDate", date);
        state.putString("filterDoctor", controls.doctorFilter.getText() == null ? "" : controls.doctorFilter.getText().toString());
        state.putInt("filterStatus", statusFilterIndex);
        super.onSaveInstanceState(state);
    }
}
