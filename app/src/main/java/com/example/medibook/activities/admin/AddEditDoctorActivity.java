package com.example.medibook.activities.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityAddEditDoctorBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DoctorErrorMessages;
import com.example.medibook.utils.DoctorFormValidation;
import com.example.medibook.viewmodels.DoctorEditorViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Form validation plus an online Firestore transaction. No success is returned before acknowledgement. */
public class AddEditDoctorActivity extends AppCompatActivity implements AuthRepository.Listener,
        DoctorEditorViewModel.Listener {
    public static final String EXTRA_DOCTOR_ID = "com.example.medibook.DOCTOR_ID";
    public static final String EXTRA_RESULT = "com.example.medibook.DOCTOR_CHANGE";
    public static final int RESULT_ADDED = 1;
    public static final int RESULT_EDITED = 2;

    private ActivityAddEditDoctorBinding binding;
    private AuthRepository authRepository;
    private DoctorEditorViewModel model;
    private boolean adminVerified;
    private int populatedRevision;
    private DoctorRepository.Error displayedError = DoctorRepository.Error.NONE;
    private AlertDialog activeDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddEditDoctorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        authRepository = AuthRepository.getInstance();
        model = new ViewModelProvider(this).get(DoctorEditorViewModel.class);
        String id = savedInstanceState == null ? getIntent().getStringExtra(EXTRA_DOCTOR_ID)
                : savedInstanceState.getString("doctorId");
        boolean editing = savedInstanceState == null ? getIntent().hasExtra(EXTRA_DOCTOR_ID)
                : savedInstanceState.getBoolean("editing");
        model.initialize(id, editing, savedInstanceState != null);
        if (savedInstanceState != null) populatedRevision = savedInstanceState.getInt("populatedRevision");

        binding.toolbar.setNavigationOnClickListener(view -> requestCancel());
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.actionLogout) return false;
            authRepository.signOut();
            return true;
        });
        binding.cancelButton.setOnClickListener(view -> requestCancel());
        binding.saveButton.setOnClickListener(view -> validateAndSave());
        binding.retryButton.setOnClickListener(view -> {
            if (!adminVerified) return;
            // Reload is explicit: replacing the form with server data must never be automatic on conflict.
            activeDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.doctor_reload_title)
                    .setMessage(R.string.doctor_reload_message)
                    .setNegativeButton(R.string.cancel_button, null)
                    .setPositiveButton(R.string.doctor_reload_button, (dialog, which) -> {
                        if (!adminVerified) return;
                        populatedRevision = -1;
                        model.reload();
                    }).show();
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
        if (activeDialog != null) activeDialog.dismiss();
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
        } else if (!model.startSession(DoctorRepository.currentUserId())) {
            model.endSession();
            AuthNavigation.openDestination(this, state.user);
        } else onDoctorChanged(model.getState());
    }

    @Override
    public void onDoctorChanged(DoctorEditorViewModel.State state) {
        if (!adminVerified || isFinishing()) return;
        int title = state.editing ? R.string.edit_doctor_title : R.string.add_doctor_button;
        binding.toolbar.setTitle(title);
        if (state.original != null && !state.loading && state.error == DoctorRepository.Error.NONE
                && state.revision != populatedRevision) {
            populateForm(state.original);
            populatedRevision = state.revision;
        }
        boolean enabled = !state.loading && !state.saving && !state.completed;
        binding.nameLayout.setEnabled(enabled);
        binding.specializationLayout.setEnabled(enabled);
        binding.qualificationsLayout.setEnabled(enabled);
        binding.feeLayout.setEnabled(enabled);
        binding.biographyLayout.setEnabled(enabled);
        binding.imageUrlLayout.setEnabled(enabled);
        binding.activeSwitch.setEnabled(enabled);
        binding.cancelButton.setEnabled(!state.saving);
        binding.saveButton.setEnabled(state.canSave());
        binding.saveButton.setText(state.saving ? R.string.doctor_saving : R.string.save_button);
        binding.operationProgress.setVisibility(state.loading || state.saving ? View.VISIBLE : View.GONE);
        boolean error = state.error != DoctorRepository.Error.NONE;
        binding.operationStatus.setVisibility(state.loading || state.saving || error ? View.VISIBLE : View.GONE);
        binding.operationStatus.setText(state.saving ? R.string.doctor_saving_message
                : state.loading ? R.string.doctor_loading_description : DoctorErrorMessages.forError(state.error));
        binding.operationStatus.setTextColor(ContextCompat.getColor(this,
                error ? R.color.medibook_error : R.color.medibook_text_secondary));
        binding.retryButton.setVisibility(error ? View.VISIBLE : View.GONE);
        binding.retryButton.setEnabled(!state.loading && !state.saving);
        if (error && displayedError != state.error) {
            binding.adminContent.post(() -> {
                if (adminVerified && !isFinishing()) binding.adminContent.smoothScrollTo(0, 0);
            });
        }
        displayedError = state.error;
        if (state.completed) {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT, state.editing ? RESULT_EDITED : RESULT_ADDED);
            setResult(RESULT_OK, data);
            finish();
        }
    }

    private void populateForm(Doctor doctor) {
        binding.nameInput.setText(doctor.getName());
        binding.specializationInput.setText(doctor.getSpecialization());
        binding.qualificationsInput.setText(doctor.getQualifications());
        binding.feeInput.setText(doctor.getConsultationFee().toPlainString());
        binding.biographyInput.setText(doctor.getBiography());
        binding.imageUrlInput.setText(doctor.getImageUrl());
        binding.imageUrlLayout.setError(null);
        binding.activeSwitch.setChecked(doctor.isActive());
        binding.nameLayout.setError(null);
        binding.specializationLayout.setError(null);
        binding.qualificationsLayout.setError(null);
        binding.feeLayout.setError(null);
        binding.biographyLayout.setError(null);
    }

    private void validateAndSave() {
        if (!adminVerified || !model.getState().canSave() || isFinishing()) return;
        DoctorFormValidation.Result result = DoctorFormValidation.validate(text(binding.nameInput),
                text(binding.specializationInput), text(binding.qualificationsInput), text(binding.feeInput),
                text(binding.biographyInput));
        String imageUrl = text(binding.imageUrlInput).trim();
        boolean validImage = com.example.medibook.utils.DoctorImageUrl.isValid(imageUrl);
        binding.imageUrlLayout.setError(validImage ? null : getString(R.string.doctor_image_url_error));
        showError(binding.nameLayout, result.nameError,
                R.string.doctor_error_name_required, R.string.doctor_error_name_length);
        showError(binding.specializationLayout, result.specializationError,
                R.string.doctor_error_specialization_required, R.string.doctor_error_specialization_length);
        showError(binding.qualificationsLayout, result.qualificationsError,
                R.string.doctor_error_qualifications_required, R.string.doctor_error_qualifications_length);
        showError(binding.feeLayout, result.feeError,
                R.string.doctor_error_fee_required, R.string.doctor_error_fee_invalid);
        showError(binding.biographyLayout, result.biographyError,
                R.string.doctor_error_biography_length, R.string.doctor_error_biography_length);
        View firstInvalid = result.nameError != DoctorFormValidation.Error.NONE ? binding.nameInput
                : result.specializationError != DoctorFormValidation.Error.NONE ? binding.specializationInput
                : result.qualificationsError != DoctorFormValidation.Error.NONE ? binding.qualificationsInput
                : result.feeError != DoctorFormValidation.Error.NONE ? binding.feeInput
                : result.biographyError != DoctorFormValidation.Error.NONE ? binding.biographyInput
                : !validImage ? binding.imageUrlInput : null;
        if (firstInvalid != null) firstInvalid.requestFocus();
        else model.save(result.toDoctor(model.getState().doctorId, binding.activeSwitch.isChecked()).withImageUrl(imageUrl));
    }

    private void showError(TextInputLayout layout, DoctorFormValidation.Error error, int required, int length) {
        if (error == DoctorFormValidation.Error.NONE) { layout.setError(null); return; }
        layout.setError(getString(error == DoctorFormValidation.Error.REQUIRED ? required
                : error == DoctorFormValidation.Error.TOO_LONG ? length
                : error == DoctorFormValidation.Error.INVALID_AMOUNT ? R.string.doctor_error_fee_invalid
                : R.string.doctor_error_text_invalid));
    }

    private String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    private void requestCancel() {
        if (isFinishing()) return;
        if (model.getState().saving) {
            Snackbar.make(binding.getRoot(), R.string.doctor_wait_for_write, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (!adminVerified || !hasChanges()) { finish(); return; }
        if (activeDialog != null && activeDialog.isShowing()) return;
        activeDialog = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_MediBook_DestructiveDialog)
                .setTitle(R.string.doctor_discard_title)
                .setMessage(R.string.doctor_discard_message)
                .setNegativeButton(R.string.doctor_keep_editing_button, null)
                .setPositiveButton(R.string.doctor_discard_button, (dialog, which) -> finish()).show();
    }

    private boolean hasChanges() {
        Doctor original = model.getState().original;
        if (original == null) {
            return !text(binding.nameInput).isEmpty() || !text(binding.specializationInput).isEmpty()
                    || !text(binding.qualificationsInput).isEmpty() || !text(binding.feeInput).isEmpty()
                    || !text(binding.biographyInput).isEmpty() || !text(binding.imageUrlInput).isEmpty() || binding.activeSwitch.isChecked();
        }
        return !text(binding.nameInput).equals(original.getName())
                || !text(binding.specializationInput).equals(original.getSpecialization())
                || !text(binding.qualificationsInput).equals(original.getQualifications())
                || !text(binding.feeInput).equals(original.getConsultationFee().toPlainString())
                || !text(binding.biographyInput).equals(original.getBiography())
                || !text(binding.imageUrlInput).equals(original.getImageUrl())
                || binding.activeSwitch.isChecked() != original.isActive();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString("doctorId", model.getState().doctorId);
        outState.putBoolean("editing", model.getState().editing);
        outState.putInt("populatedRevision", populatedRevision);
        super.onSaveInstanceState(outState);
    }
}
