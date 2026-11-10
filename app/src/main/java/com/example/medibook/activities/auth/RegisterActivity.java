package com.example.medibook.activities.auth;

import android.os.Bundle;
import android.util.Patterns;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityRegisterBinding;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthErrorMessages;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.BirthDatePicker;
import com.example.medibook.utils.PatientAge;
import com.example.medibook.utils.PatientGender;
import com.example.medibook.utils.PatientProfileValidation;

public class RegisterActivity extends AppCompatActivity implements AuthRepository.Listener {

    private ActivityRegisterBinding binding;
    private AuthRepository repository;
    private String dateOfBirth;
    private final OnBackPressedCallback blockBackWhileLoading = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            // Account creation/profile creation/cleanup must finish before leaving.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        getOnBackPressedDispatcher().addCallback(this, blockBackWhileLoading);

        binding.genderInput.setSimpleItems(PatientGender.OPTIONS);
        binding.dobInput.setOnClickListener(view -> pickBirthDate());
        binding.backToLoginButton.setOnClickListener(view -> finish());
        binding.registerButton.setOnClickListener(view -> validateForm());
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        repository.removeListener(this);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        binding.fullNameLayout.setEnabled(!state.loading);
        binding.emailLayout.setEnabled(!state.loading);
        binding.phoneLayout.setEnabled(!state.loading);
        binding.dobLayout.setEnabled(!state.loading);
        binding.genderLayout.setEnabled(!state.loading);
        binding.passwordLayout.setEnabled(!state.loading);
        binding.confirmPasswordLayout.setEnabled(!state.loading);
        binding.registerButton.setEnabled(!state.loading);
        binding.backToLoginButton.setEnabled(!state.loading);
        binding.registerButton.setText(state.loading ? R.string.creating_account : R.string.register_button);
        binding.loadingIndicator.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        blockBackWhileLoading.setEnabled(state.loading);
        binding.statusMessage.setVisibility(state.error == AuthRepository.Error.NONE ? View.GONE : View.VISIBLE);
        if (state.error != AuthRepository.Error.NONE) {
            binding.statusMessage.setText(AuthErrorMessages.forError(state.error));
        }
        if (state.user != null || state.pendingProfile != null) {
            binding.passwordInput.setText(null);
            binding.confirmPasswordInput.setText(null);
            AuthNavigation.openDestination(this, state.user);
        }
    }

    private void pickBirthDate() {
        BirthDatePicker.show(getSupportFragmentManager(), dateOfBirth, iso -> {
            dateOfBirth = iso;
            binding.dobInput.setText(PatientAge.formatDisplay(iso));
            binding.dobLayout.setError(null);
        });
    }

    private void validateForm() {
        binding.statusMessage.setVisibility(View.GONE);
        binding.fullNameLayout.setError(null);
        binding.emailLayout.setError(null);
        binding.phoneLayout.setError(null);
        binding.dobLayout.setError(null);
        binding.genderLayout.setError(null);
        binding.passwordLayout.setError(null);
        binding.confirmPasswordLayout.setError(null);

        String name = binding.fullNameInput.getText().toString().trim();
        String email = binding.emailInput.getText().toString().trim();
        String phone = PatientProfileValidation.normalizePhone(binding.phoneInput.getText().toString());
        String gender = binding.genderInput.getText().toString().trim();
        String password = binding.passwordInput.getText().toString();
        String confirmation = binding.confirmPasswordInput.getText().toString();
        View firstInvalid = null;

        if (!PatientProfileValidation.isValidName(name)) {
            binding.fullNameLayout.setError(getString(R.string.error_name_invalid));
            firstInvalid = binding.fullNameInput;
        }
        if (email.isEmpty() || email.length() > 254 || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.setError(getString(email.isEmpty()
                    ? R.string.error_email_required : R.string.error_email_invalid));
            if (firstInvalid == null) firstInvalid = binding.emailInput;
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
        if (password.length() < 6) {
            binding.passwordLayout.setError(getString(password.isEmpty()
                    ? R.string.error_password_required : R.string.error_password_short));
            if (firstInvalid == null) firstInvalid = binding.passwordInput;
        }
        if (confirmation.isEmpty() || !password.equals(confirmation)) {
            binding.confirmPasswordLayout.setError(getString(confirmation.isEmpty()
                    ? R.string.error_confirm_password_required : R.string.error_password_mismatch));
            if (firstInvalid == null) firstInvalid = binding.confirmPasswordInput;
        }

        if (firstInvalid != null) {
            firstInvalid.requestFocus();
        } else {
            repository.register(name, email, phone, dateOfBirth, gender, password);
        }
    }
}
