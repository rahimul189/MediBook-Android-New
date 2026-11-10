package com.example.medibook.activities.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.adapters.DoctorScheduleAdapter;
import com.example.medibook.databinding.ActivityManageDoctorScheduleBinding;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DoctorScheduleContract;
import com.example.medibook.utils.DoctorScheduleValidation;
import com.example.medibook.utils.AppointmentPolicy;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.repositories.AppointmentRepository;
import com.example.medibook.repositories.SlotInventoryPublisher;
import com.example.medibook.viewmodels.DoctorScheduleListViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/** Admin-gated server directory; no local schedule results or optimistic status changes. */
public class ManageDoctorScheduleActivity extends AppCompatActivity implements AuthRepository.Listener,
        DoctorScheduleListViewModel.Listener, DoctorScheduleAdapter.Listener {
    private ActivityManageDoctorScheduleBinding binding;
    private AuthRepository authRepository;
    private DoctorScheduleListViewModel model;
    private DoctorScheduleAdapter adapter;
    private ActivityResultLauncher<Intent> editorLauncher;
    private String doctorId;
    private String doctorName;
    private String owner;
    private int pendingChange;
    private String pendingSavedId;
    private boolean validContext;
    private boolean adminVerified;
    private boolean openingEditor;
    private AlertDialog statusDialog;
    private Snackbar message;
    private boolean watchingPublication;
    private final OnBackPressedCallback blockPendingBack = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { showMessage(R.string.schedule_wait_for_write); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityManageDoctorScheduleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        authRepository = AuthRepository.getInstance();
        try {
            doctorId = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_ID);
            doctorName = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_NAME);
            owner = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_OWNER);
            validContext = DoctorScheduleContract.validContext(doctorId, doctorName, owner);
        } catch (RuntimeException ignored) { validContext = false; }
        model = new ViewModelProvider(this).get(DoctorScheduleListViewModel.class);
        model.initialize(doctorId);
        if (model.publicationDate < 0) {
            model.publicationDate = savedInstanceState == null ? AppointmentUi.today() + 86_400_000L
                    : savedInstanceState.getLong("publicationDate", AppointmentUi.today() + 86_400_000L);
            model.publicationDateChosen = savedInstanceState != null && savedInstanceState.getBoolean("publicationDateChosen");
        }
        binding.publicationDateButton.setOnClickListener(view -> {
            if (!canPublish()) return;
            AppointmentUi.pickDate(this, model.publicationDate, (year, month, day) -> {
                if (!canPublish()) return;
                if (!DhakaDateTime.isDateBookable(year, month, day, System.currentTimeMillis())) {
                    model.publicationFeedback = getString(R.string.appointment_past_date); renderPublication(); return;
                }
                model.publicationDate = DhakaDateTime.localDateAtMidnight(year, month, day);
                model.publicationDateChosen = true; model.publicationFeedback = null; renderPublication();
            });
        });
        binding.publishSlotsButton.setOnClickListener(view -> publishBookingDate());
        if (savedInstanceState != null) {
            pendingChange = savedInstanceState.getInt("pendingChange");
            pendingSavedId = savedInstanceState.getString("pendingSavedId");
        }
        adapter = new DoctorScheduleAdapter(this);
        binding.adminContent.setLayoutManager(new LinearLayoutManager(this));
        binding.adminContent.setAdapter(adapter);
        getOnBackPressedDispatcher().addCallback(this, blockPendingBack);
        binding.toolbar.setNavigationOnClickListener(view -> {
            if (model.getState().writing) showMessage(R.string.schedule_wait_for_write);
            else finish();
        });
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.actionLogout) return false;
            authRepository.signOut();
            return true;
        });
        editorLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            openingEditor = false;
            if (result.getResultCode() != RESULT_OK || result.getData() == null || !validContext) return;
            try {
                Intent data = result.getData();
                String id = data.getStringExtra(DoctorScheduleContract.EXTRA_SCHEDULE_ID);
                int change = data.getIntExtra(DoctorScheduleContract.EXTRA_CHANGE, 0);
                if (doctorId.equals(data.getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_ID))
                        && owner.equals(data.getStringExtra(DoctorScheduleContract.EXTRA_OWNER))
                        && DoctorScheduleValidation.validId(id)
                        && (change == DoctorScheduleContract.ADDED || change == DoctorScheduleContract.EDITED)) {
                    pendingSavedId = id;
                    pendingChange = change;
                }
            } catch (RuntimeException ignored) { /* Malformed results never become records or success. */ }
            // A real server-confirmed editor result can arrive before the fresh Admin check completes.
            if (canAccess()) {
                model.refresh();
                onSchedulesChanged(model.getState());
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        openingEditor = false;
        model.addListener(this);
        authRepository.checkSession();
        authRepository.addListener(this);
    }

    @Override
    protected void onStop() {
        watchingPublication = false;
        authRepository.removeListener(this);
        model.removeListener(this);
        adminVerified = false;
        binding.adminPanel.setVisibility(View.GONE);
        binding.publicationStatus.setText(null);
        binding.adminContent.setVisibility(View.GONE);
        if (statusDialog != null) statusDialog.dismiss();
        if (message != null) message.dismiss();
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        adminVerified = !state.loading && state.user != null && state.user.getRole() == UserProfile.Role.ADMIN;
        binding.adminPanel.setVisibility(adminVerified ? View.VISIBLE : View.GONE);
        binding.adminContent.setVisibility(adminVerified ? View.VISIBLE : View.GONE);
        binding.sessionLoading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        if (state.loading) {
            if (statusDialog != null) statusDialog.dismiss();
            return;
        }
        if (!adminVerified || (validContext && !DoctorRepository.sameUser(owner))) {
            adminVerified = false;
            binding.adminPanel.setVisibility(View.GONE);
            binding.adminContent.setVisibility(View.GONE);
            model.endSession();
            pendingChange = 0;
            pendingSavedId = null;
            AuthNavigation.openDestination(this, state.user);
            return;
        }
        if (validContext && !model.startSession(owner)) {
            model.endSession();
            AuthNavigation.openDestination(this, state.user);
            return;
        }
        onSchedulesChanged(model.getState());
        observePublication();
    }

    private boolean canAccess() {
        return adminVerified && validContext && DoctorRepository.sameUser(owner) && !isFinishing();
    }

    private boolean canAct() {
        return canAccess() && !openingEditor && model.getState().canAct() && model.publication == null;
    }

    private void publishBookingDate() {
        if (!canPublish()) {
            if (canAccess()) { model.publicationFeedback = getString(R.string.appointment_publish_unready); renderPublication(); }
            return;
        }
        final long date = model.publicationDate;
        java.util.Calendar day = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Dhaka"));
        day.setTimeInMillis(date);
        statusDialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.appointment_publish_title)
                .setMessage(getString(R.string.appointment_publish_message, doctorName, AppointmentUi.date(date)))
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(R.string.appointment_publish_slots, (dialog, which) -> {
                    if (!canPublish()) return;
                    model.publicationFeedback = null;
                    model.publication = new SlotInventoryPublisher().publish(owner, doctorId,
                            day.get(java.util.Calendar.YEAR), day.get(java.util.Calendar.MONTH) + 1, day.get(java.util.Calendar.DAY_OF_MONTH));
                    renderPublication(); observePublication();
                }).show();
    }

    private boolean canPublish() {
        // Publication performs its own fresh server validation; a failed list load must not make
        // the publishing entry point silently unreachable.
        return canAccess() && !openingEditor && !model.getState().writing && model.publication == null;
    }

    private void renderPublication() {
        if (!adminVerified) return;
        boolean busy = model.publication != null;
        binding.publishSlotsButton.setEnabled(canPublish()); binding.publicationDateButton.setEnabled(canPublish());
        binding.publicationProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.publicationDateButton.setText(getString(R.string.appointment_choose_date, AppointmentUi.date(model.publicationDate)));
        binding.publicationStatus.setText(busy ? getString(R.string.appointment_publish_busy)
                : model.publicationFeedback == null ? "" : model.publicationFeedback);
        binding.publicationStatus.setVisibility(busy || model.publicationFeedback != null ? View.VISIBLE : View.GONE);
        if (validContext) android.util.Log.d("MediBookAppointments", AppointmentUi.projectId() + " · "
                + AppointmentRepository.inventoryPath(doctorId, model.publicationDate));
    }

    private void observePublication() {
        if (!canAccess() || model.publication == null || watchingPublication) return;
        watchingPublication = true;
        model.publication.addOnCompleteListener(this, task -> {
            if (!canAccess()) return;
            watchingPublication = false; model.publication = null;
            if (message != null) message.dismiss();
            model.publicationFeedback = task.isSuccessful()
                    ? (task.getResult() == 0 ? getString(R.string.appointment_publish_zero)
                    : getResources().getQuantityString(R.plurals.appointment_publish_success, task.getResult(), task.getResult()))
                    : getString(R.string.appointment_publish_failure) + "\n" + AppointmentUi.errorText(this, task.getException());
            renderPublication();
        });
    }

    @Override
    public void onSchedulesChanged(DoctorScheduleListViewModel.State state) {
        if (!adminVerified || isFinishing()) return;
        blockPendingBack.setEnabled(state.writing);
        if (state.doctorName != null) doctorName = state.doctorName;
        adapter.submit(doctorName, validContext, state.schedules, state.loading, state.writing, state.busyId, state.error);
        if (state.loaded && !model.publicationDateChosen && model.publication == null) {
            long next = AppointmentPolicy.nextBookingDate(state.schedules, System.currentTimeMillis());
            if (next >= 0) { model.publicationDate = next; model.publicationDateChosen = true; }
        }
        renderPublication();
        if (model.takeStatusSaved()) showMessage(R.string.schedule_status_saved);
        if (pendingChange != 0 && canAccess()) {
            if (pendingChange == DoctorScheduleContract.ADDED) showMessage(R.string.schedule_added);
            else if (pendingChange == DoctorScheduleContract.EDITED) showMessage(R.string.schedule_saved);
            pendingChange = 0;
        }
        if (!state.loading && state.loaded && pendingSavedId != null) {
            for (int index = 0; index < state.schedules.size(); index++) {
                if (state.schedules.get(index).getId().equals(pendingSavedId)) {
                    binding.adminContent.scrollToPosition(index + 1);
                    break;
                }
            }
            pendingSavedId = null;
        }
    }

    @Override
    public void onAddSchedule() {
        if (!canAct()) return;
        launchEditor(null);
    }

    @Override
    public void onEditSchedule(DoctorSchedule entry) {
        if (canAct()) launchEditor(entry);
    }

    private void launchEditor(DoctorSchedule entry) {
        openingEditor = true;
        Intent intent = new Intent(this, AddEditDoctorScheduleActivity.class);
        DoctorScheduleContract.putContext(intent, doctorId, doctorName, owner);
        // Only path identities cross Activities. The editor independently loads the server version.
        if (entry != null) intent.putExtra(DoctorScheduleContract.EXTRA_SCHEDULE_ID, entry.getId());
        editorLauncher.launch(intent);
    }

    @Override
    public void onToggleSchedule(DoctorSchedule entry) {
        if (!canAct() || (statusDialog != null && statusDialog.isShowing())) return;
        statusDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(entry.isActive() ? R.string.schedule_disable_title : R.string.schedule_enable_title)
                .setMessage(R.string.schedule_status_message)
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(entry.isActive() ? R.string.schedule_disable_button
                        : R.string.schedule_enable_button, (dialog, which) -> {
                    if (canAct()) model.setActive(entry, !entry.isActive());
                }).show();
    }

    @Override
    public void onRetrySchedules() {
        if (!adminVerified) return;
        if (!validContext) { finish(); return; }
        if (canAccess()) model.refresh();
    }

    private void showMessage(int resource) {
        if (message != null) message.dismiss();
        message = Snackbar.make(binding.getRoot(), resource,
                resource == R.string.appointment_publish_busy ? Snackbar.LENGTH_INDEFINITE : Snackbar.LENGTH_LONG);
        message.show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("pendingChange", pendingChange);
        outState.putString("pendingSavedId", pendingSavedId);
        outState.putLong("publicationDate", model.publicationDate);
        outState.putBoolean("publicationDateChosen", model.publicationDateChosen);
        super.onSaveInstanceState(outState);
    }
}
