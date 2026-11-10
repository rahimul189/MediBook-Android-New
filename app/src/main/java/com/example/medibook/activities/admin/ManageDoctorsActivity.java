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
import com.example.medibook.adapters.DoctorAdapter;
import com.example.medibook.databinding.ActivityManageDoctorsBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DoctorScheduleContract;
import com.example.medibook.viewmodels.DoctorListViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/** Admin-gated server reads and server-confirmed status changes. No local preview records. */
public class ManageDoctorsActivity extends AppCompatActivity implements AuthRepository.Listener,
        DoctorListViewModel.Listener, DoctorAdapter.Listener {
    private ActivityManageDoctorsBinding binding;
    private AuthRepository authRepository;
    private DoctorListViewModel model;
    private DoctorAdapter adapter;
    private ActivityResultLauncher<Intent> editorLauncher;
    private boolean adminVerified;
    private boolean openingEditor;
    private int pendingResult;
    private AlertDialog statusDialog;
    private Snackbar message;
    private final OnBackPressedCallback blockPendingBack = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { showMessage(R.string.doctor_wait_for_write); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityManageDoctorsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        authRepository = AuthRepository.getInstance();
        model = new ViewModelProvider(this).get(DoctorListViewModel.class);
        if (savedInstanceState != null) pendingResult = savedInstanceState.getInt("pendingResult");
        adapter = new DoctorAdapter(this);
        binding.adminContent.setLayoutManager(new LinearLayoutManager(this));
        binding.adminContent.setAdapter(adapter);
        getOnBackPressedDispatcher().addCallback(this, blockPendingBack);
        binding.toolbar.setNavigationOnClickListener(view -> {
            if (model.getState().writing) showMessage(R.string.doctor_wait_for_write);
            else finish();
        });
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.actionLogout) return false;
            authRepository.signOut();
            return true;
        });
        editorLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                pendingResult = result.getData().getIntExtra(AddEditDoctorActivity.EXTRA_RESULT, 0);
                if (adminVerified) { model.refresh(); onDoctorsChanged(model.getState()); }
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
        authRepository.removeListener(this);
        model.removeListener(this);
        adminVerified = false;
        binding.adminContent.setVisibility(View.GONE);
        if (statusDialog != null) statusDialog.dismiss();
        if (message != null) message.dismiss();
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        adminVerified = !state.loading && state.user != null
                && state.user.getRole() == UserProfile.Role.ADMIN;
        binding.sessionLoading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.adminContent.setVisibility(adminVerified ? View.VISIBLE : View.GONE);
        if (state.loading) return;
        if (!adminVerified) {
            model.endSession();
            AuthNavigation.openDestination(this, state.user);
        } else {
            model.startSession(DoctorRepository.currentUserId());
            if (pendingResult != 0) model.refresh();
            onDoctorsChanged(model.getState());
        }
    }

    @Override
    public void onDoctorsChanged(DoctorListViewModel.State state) {
        if (!adminVerified || isFinishing()) return;
        blockPendingBack.setEnabled(state.writing);
        adapter.submitState(state.doctors, state.loading, state.writing, state.error);
        if (state.writing) showMessage(R.string.doctor_status_updating);
        if (model.takeStatusChanged()) showMessage(R.string.doctor_status_saved);
        if (pendingResult == AddEditDoctorActivity.RESULT_ADDED) showMessage(R.string.doctor_added);
        else if (pendingResult == AddEditDoctorActivity.RESULT_EDITED) showMessage(R.string.doctor_saved);
        pendingResult = 0;
    }

    private boolean canAct() {
        DoctorListViewModel.State state = model.getState();
        return adminVerified && !openingEditor && !state.loading && !state.writing
                && state.error == DoctorRepository.Error.NONE;
    }

    @Override
    public void onAddDoctor() {
        if (!canAct()) return;
        openingEditor = true;
        editorLauncher.launch(new Intent(this, AddEditDoctorActivity.class));
    }

    @Override
    public void onEditDoctor(Doctor doctor) {
        if (!canAct()) return;
        openingEditor = true;
        Intent intent = new Intent(this, AddEditDoctorActivity.class);
        // Only a path identity is passed. The editor independently fetches its server version.
        intent.putExtra(AddEditDoctorActivity.EXTRA_DOCTOR_ID, doctor.getId());
        editorLauncher.launch(intent);
    }

    @Override
    public void onManageSchedule(Doctor doctor) {
        if (!canAct()) return;
        openingEditor = true;
        Intent intent = new Intent(this, ManageDoctorScheduleActivity.class);
        DoctorScheduleContract.putContext(intent, doctor.getId(), doctor.getName(),
                DoctorRepository.currentUserId());
        // Schedule results cannot produce a doctor-save result or mutate a doctor record.
        startActivity(intent);
    }

    @Override
    public void onToggleActive(Doctor doctor) {
        if (!canAct() || (statusDialog != null && statusDialog.isShowing())) return;
        statusDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(doctor.isActive() ? R.string.doctor_deactivate_title : R.string.doctor_activate_title)
                .setMessage(R.string.doctor_status_message)
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(doctor.isActive() ? R.string.deactivate_doctor_button
                        : R.string.activate_doctor_button, (dialog, which) -> {
                    if (canAct()) model.setActive(doctor, !doctor.isActive());
                }).show();
    }

    @Override
    public void onRetry() {
        if (adminVerified) model.refresh();
    }

    private void showMessage(int resource) {
        if (message != null) message.dismiss();
        message = Snackbar.make(binding.getRoot(), resource, Snackbar.LENGTH_LONG);
        message.show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("pendingResult", pendingResult);
        super.onSaveInstanceState(outState);
    }
}
