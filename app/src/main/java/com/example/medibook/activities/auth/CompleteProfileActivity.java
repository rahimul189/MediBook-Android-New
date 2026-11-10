package com.example.medibook.activities.auth;

import android.os.Bundle;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.example.medibook.R;
import com.example.medibook.databinding.ActivityCompleteProfileBinding;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthErrorMessages;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.BirthDatePicker;
import com.example.medibook.utils.PatientAge;
import com.example.medibook.utils.PatientGender;
import com.example.medibook.utils.PatientProfileValidation;

/** Onboarding is a gated session, not a temporary Patient role. No Intent identity is accepted. */
public final class CompleteProfileActivity extends AppCompatActivity implements AuthRepository.Listener {
    private ActivityCompleteProfileBinding binding;
    private AuthRepository repository;
    private String owner, dateOfBirth;
    private Bundle draft;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCompleteProfileBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot()); repository = AuthRepository.getInstance(); draft = savedInstanceState;
        BirthDatePicker.reconnect(getSupportFragmentManager(), this::birthDateSelected);
        binding.genderInput.setSimpleItems(PatientGender.OPTIONS);
        binding.dobInput.setOnClickListener(view -> BirthDatePicker.show(getSupportFragmentManager(), dateOfBirth, this::birthDateSelected));
        binding.saveButton.setOnClickListener(view -> save());
        binding.signOutButton.setOnClickListener(view -> repository.signOut());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { repository.signOut(); }
        });
    }
    @Override protected void onStart() { super.onStart(); repository.checkSession(); repository.addListener(this); }
    @Override protected void onStop() { repository.removeListener(this); super.onStop(); }

    @Override public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        binding.loadingIndicator.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.loadingLabel.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        if (state.loading) binding.loadingLabel.setText(state.pendingProfile == null ? R.string.checking_session : R.string.profile_saving);
        binding.saveButton.setEnabled(!state.loading); binding.fullNameLayout.setEnabled(!state.loading);
        binding.phoneLayout.setEnabled(!state.loading); binding.dobLayout.setEnabled(!state.loading); binding.genderLayout.setEnabled(!state.loading);
        binding.status.setVisibility(state.error == AuthRepository.Error.NONE ? View.GONE : View.VISIBLE);
        if (state.error != AuthRepository.Error.NONE) binding.status.setText(AuthErrorMessages.forError(state.error));
        AuthRepository.PendingProfile pending = repository.pendingProfile();
        if (pending == null) {
            binding.form.setVisibility(View.GONE);
            if (!state.loading) AuthNavigation.openDestination(this, state.user);
            return;
        }
        binding.form.setVisibility(View.VISIBLE);
        binding.emailValue.setText(getString(R.string.profile_edit_email_value, pending.email));
        if (!pending.uid.equals(owner)) {
            owner = pending.uid;
            boolean restore = draft != null && owner.equals(draft.getString("profileOwner"));
            binding.fullNameInput.setText(restore ? draft.getString("name") : pending.fullName);
            binding.phoneInput.setText(restore ? draft.getString("phone") : pending.phone);
            dateOfBirth = restore ? draft.getString("dob") : pending.dateOfBirth;
            String gender = restore ? draft.getString("gender") : PatientGender.display(pending.gender);
            binding.genderInput.setText(gender == null ? "" : gender, false); renderBirthDate(); draft = null;
        }
    }
    private void renderBirthDate() {
        binding.dobInput.setText(PatientAge.formatDisplay(dateOfBirth));
        Integer age = PatientAge.years(dateOfBirth, System.currentTimeMillis());
        binding.ageValue.setText(age == null ? getString(R.string.complete_profile_age_empty)
                : getResources().getQuantityString(R.plurals.complete_profile_age, age, age));
    }
    private void birthDateSelected(String iso) {
        dateOfBirth = iso;
        if (draft != null) draft.putString("dob", iso);
        renderBirthDate(); binding.dobLayout.setError(null);
    }
    private void save() {
        binding.fullNameLayout.setError(null); binding.phoneLayout.setError(null);
        binding.dobLayout.setError(null); binding.genderLayout.setError(null);
        String name = binding.fullNameInput.getText().toString().trim();
        String phone = PatientProfileValidation.normalizePhone(binding.phoneInput.getText().toString());
        String gender = binding.genderInput.getText().toString(); View invalid = null;
        if (!PatientProfileValidation.isValidName(name)) { binding.fullNameLayout.setError(getString(R.string.error_name_invalid)); invalid = binding.fullNameInput; }
        if (!PatientProfileValidation.isValidPhone(phone)) { binding.phoneLayout.setError(getString(R.string.error_phone_invalid)); if (invalid == null) invalid = binding.phoneInput; }
        if (!PatientAge.isValidIsoDate(dateOfBirth) || !PatientAge.isValidBirthDate(dateOfBirth, System.currentTimeMillis())) {
            binding.dobLayout.setError(getString(dateOfBirth == null ? R.string.error_dob_required : R.string.error_dob_invalid)); if (invalid == null) invalid = binding.dobInput;
        }
        if (!PatientGender.isWireValue(gender)) { binding.genderLayout.setError(getString(R.string.error_gender_required)); if (invalid == null) invalid = binding.genderInput; }
        if (invalid != null) { invalid.requestFocus(); return; }
        repository.completeGoogleProfile(name, phone, dateOfBirth, gender);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("profileOwner", owner); state.putString("name", binding.fullNameInput.getText().toString());
        state.putString("phone", binding.phoneInput.getText().toString()); state.putString("dob", dateOfBirth);
        state.putString("gender", binding.genderInput.getText().toString()); super.onSaveInstanceState(state);
    }
}
