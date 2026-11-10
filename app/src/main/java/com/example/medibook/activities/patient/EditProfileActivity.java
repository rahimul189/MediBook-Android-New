package com.example.medibook.activities.patient;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityEditProfileBinding;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.BirthDatePicker;
import com.example.medibook.utils.PatientAge;
import com.example.medibook.utils.PatientGender;
import com.example.medibook.utils.PatientProfileValidation;
import com.example.medibook.utils.AuthErrorMessages;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.GoogleAuthFlow;
import com.example.medibook.auth.GoogleCredentials;
import com.example.medibook.repositories.DoctorRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import android.text.InputType;
import androidx.appcompat.app.AlertDialog;

/**
 * Patients may correct only their own display details. Email, role and Admin claims are never
 * editable here, and the repository re-verifies the session after the write.
 */
public final class EditProfileActivity extends AppCompatActivity implements AuthRepository.Listener, AuthRepository.GoogleListener {

    private ActivityEditProfileBinding binding;
    private AuthRepository repository;
    private String dateOfBirth;
    private boolean populated;
    private boolean saving;
    private boolean linking;
    private String owner;
    private GoogleCredentials google;
    private AlertDialog passwordDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEditProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        google = new GoogleCredentials(this);
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.genderInput.setSimpleItems(PatientGender.OPTIONS);
        binding.dobInput.setOnClickListener(view -> pickBirthDate());
        binding.saveButton.setOnClickListener(view -> save());
        binding.connectGoogleButton.setOnClickListener(view -> confirmPassword());
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.checkSession();
        repository.addListener(this);
        repository.addGoogleListener(this);
    }

    @Override
    protected void onStop() {
        repository.removeListener(this);
        repository.removeGoogleListener(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (passwordDialog != null) passwordDialog.dismiss();
        repository.cancelPendingGoogleLink();
        google.close(); super.onDestroy();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        if (owner != null && !DoctorRepository.sameUser(owner)) {
            binding.content.setVisibility(View.GONE); populated = false; owner = null;
            binding.fullNameInput.setText(null); binding.phoneInput.setText(null); binding.emailValue.setText(null);
            binding.dobInput.setText(null); binding.genderInput.setText(null); dateOfBirth = null;
            if (passwordDialog != null) passwordDialog.dismiss();
        }
        if (state.loading) {
            binding.sessionLoading.getRoot().setVisibility(
                    binding.content.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return;
        }
        binding.sessionLoading.getRoot().setVisibility(View.GONE);
        if (state.user == null || state.user.getRole() != UserProfile.Role.PATIENT) {
            // Signed out or not a patient: the launcher/router owns the destination.
            binding.content.setVisibility(View.GONE);
            AuthNavigation.openDestination(this, state.user);
            return;
        }
        binding.content.setVisibility(View.VISIBLE);
        if (!populated) {
            populate(state.user);
            owner = DoctorRepository.currentUserId();
            populated = true;
        }
        renderGoogleButton();
    }

    @Override public void onGoogleStateChanged(AuthRepository.GoogleState state) {
        if (isFinishing()) return;
        linking = state.busy;
        binding.saveButton.setEnabled(!linking && !saving);
        binding.fullNameLayout.setEnabled(!linking && !saving); binding.phoneLayout.setEnabled(!linking && !saving);
        binding.dobLayout.setEnabled(!linking && !saving); binding.genderLayout.setEnabled(!linking && !saving);
        renderGoogleButton();
        if (state.mode != GoogleAuthFlow.Mode.LINK) return;
        binding.googleStatus.setVisibility(state.error != AuthRepository.Error.NONE || state.stage == GoogleAuthFlow.Stage.SUCCEEDED ? View.VISIBLE : View.GONE);
        if (state.error != AuthRepository.Error.NONE) binding.googleStatus.setText(AuthErrorMessages.forError(state.error));
        else if (state.stage == GoogleAuthFlow.Stage.SUCCEEDED) binding.googleStatus.setText(R.string.google_connected);
        if (DoctorRepository.sameUser(owner)) google.launchIfNeeded(state);
    }

    private void renderGoogleButton() {
        boolean connected = repository.providers().contains("google.com");
        binding.connectGoogleButton.setText(linking ? R.string.google_connecting : connected ? R.string.google_already_linked : R.string.google_connect);
        binding.connectGoogleButton.setEnabled(!connected && !linking && !saving && DoctorRepository.sameUser(owner));
    }

    private void confirmPassword() {
        if (linking || saving || !DoctorRepository.sameUser(owner) || passwordDialog != null) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_confirm_password, null);
        TextInputEditText input = content.findViewById(R.id.passwordInput);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        passwordDialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.google_password_title)
                .setMessage(R.string.google_password_message).setView(content)
                .setNegativeButton(R.string.google_cancel, null)
                .setPositiveButton(R.string.google_password_confirm, (dialog, which) -> {
                    String password = input.getText() == null ? "" : input.getText().toString(); input.setText(null);
                    if (DoctorRepository.sameUser(owner)) repository.beginGoogleLink(password);
                }).create();
        passwordDialog.setOnDismissListener(dialog -> { input.setText(null); passwordDialog = null; });
        passwordDialog.show();
    }

    private void populate(UserProfile user) {
        binding.fullNameInput.setText(user.getFullName());
        binding.phoneInput.setText(user.getPhone());
        binding.emailValue.setText(getString(R.string.profile_edit_email_value, user.getEmail()));
        dateOfBirth = user.getDateOfBirth();
        binding.dobInput.setText(dateOfBirth == null ? null : PatientAge.formatDisplay(dateOfBirth));
        String gender = PatientGender.display(user.getGender());
        binding.genderInput.setText(gender == null ? "" : gender, false);
    }

    private void pickBirthDate() {
        BirthDatePicker.show(getSupportFragmentManager(), dateOfBirth, iso -> {
            dateOfBirth = iso;
            binding.dobInput.setText(PatientAge.formatDisplay(iso));
            binding.dobLayout.setError(null);
        });
    }

    private void save() {
        if (saving || linking || !DoctorRepository.sameUser(owner)) return;
        binding.status.setVisibility(View.GONE);
        binding.fullNameLayout.setError(null);
        binding.phoneLayout.setError(null);
        binding.dobLayout.setError(null);
        binding.genderLayout.setError(null);

        String name = binding.fullNameInput.getText().toString().trim();
        String phone = PatientProfileValidation.normalizePhone(binding.phoneInput.getText().toString());
        String gender = binding.genderInput.getText().toString().trim();
        View firstInvalid = null;

        if (!PatientProfileValidation.isValidName(name)) {
            binding.fullNameLayout.setError(getString(R.string.error_name_invalid));
            firstInvalid = binding.fullNameInput;
        }
        if (!PatientProfileValidation.isValidPhone(phone)) {
            binding.phoneLayout.setError(getString(R.string.error_phone_invalid));
            if (firstInvalid == null) firstInvalid = binding.phoneInput;
        }
        if (dateOfBirth == null) {
            binding.dobLayout.setError(getString(R.string.error_dob_required));
            if (firstInvalid == null) firstInvalid = binding.dobInput;
        } else if (!PatientAge.isValidBirthDate(dateOfBirth, System.currentTimeMillis())) {
            binding.dobLayout.setError(getString(R.string.error_dob_invalid));
            if (firstInvalid == null) firstInvalid = binding.dobInput;
        }
        if (!PatientGender.isWireValue(gender)) {
            binding.genderLayout.setError(getString(R.string.error_gender_required));
            if (firstInvalid == null) firstInvalid = binding.genderInput;
        }
        if (firstInvalid != null) {
            firstInvalid.requestFocus();
            return;
        }

        saving = true;
        binding.saveButton.setEnabled(false);
        binding.connectGoogleButton.setEnabled(false);
        binding.saveButton.setText(R.string.profile_saving);
        repository.updatePatientProfile(name, phone, dateOfBirth, gender, (success, error) -> {
            saving = false;
            if (isFinishing() || isDestroyed()) return;
            binding.saveButton.setEnabled(true);
            renderGoogleButton();
            binding.saveButton.setText(R.string.profile_edit_save);
            binding.status.setVisibility(View.VISIBLE);
            if (success) {
                binding.status.setText(R.string.profile_edit_saved);
                finish();
            } else {
                binding.status.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.medibook_error));
                binding.status.setText(R.string.profile_update_failed);
            }
        });
    }
}
