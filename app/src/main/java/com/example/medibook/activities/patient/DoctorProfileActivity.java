package com.example.medibook.activities.patient;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.example.medibook.R;
import com.example.medibook.cache.PatientDataCache;
import com.example.medibook.databinding.ActivityDoctorProfileBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.repositories.PatientDoctorRepository;
import com.example.medibook.utils.AppConstants;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DoctorScheduleUi;
import com.example.medibook.utils.PatientDoctorDirectory;
import com.example.medibook.utils.PatientDoctorProfileContract;
import com.example.medibook.utils.PatientScheduleDisplay;
import com.example.medibook.utils.PatientScreenAccess;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.List;

/**
 * Live Patient profile and weekly schedules. Cached server data is rendered immediately on a warm
 * return; the live listener then refreshes quietly without a full-screen loader.
 */
public class DoctorProfileActivity extends AppCompatActivity implements AuthRepository.Listener {
    private ActivityDoctorProfileBinding binding;
    private AuthRepository repository;
    private String owner;
    private Doctor doctor;
    private String doctorId;
    private boolean verified;
    private int generation;
    private int scheduleGeneration;
    private ListenerRegistration doctorListener, scheduleListener;
    private final PatientDoctorRepository live = new PatientDoctorRepository();
    private final PatientDataCache cache = PatientDataCache.get();
    private boolean validContext;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDoctorProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        try {
            owner = getIntent().getStringExtra(PatientDoctorProfileContract.EXTRA_OWNER);
            doctorId = getIntent().getStringExtra(PatientDoctorProfileContract.EXTRA_ID);
            validContext = owner != null && !owner.isEmpty() && DoctorMapper.isValidId(doctorId);
        } catch (RuntimeException malformed) { validContext = false; }
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.actionAppointments) {
                if (canRead()) startActivity(new Intent(this, com.example.medibook.activities.AppointmentsActivity.class));
                return true;
            }
            if (item.getItemId() != R.id.actionLogout) return false;
            repository.signOut();
            return true;
        });
        binding.retryButton.setOnClickListener(view -> { if (canRead() && validContext) startProfile(); });
        binding.scheduleRetryButton.setOnClickListener(view -> { if (canRead() && doctor != null) startSchedules(); });
        binding.bookAppointmentButton.setOnClickListener(view -> {
            if (canRead() && doctor != null && doctor.isActive()) {
                Intent intent = new Intent(this, BookAppointmentActivity.class);
                PatientDoctorProfileContract.putDoctorContext(intent, owner, doctor);
                startActivity(intent);
            }
        });
        for (View heading : new View[]{binding.doctorName, binding.qualificationsHeading,
                binding.aboutHeading, binding.scheduleHeading, binding.errorHeading}) {
            ViewCompat.setAccessibilityHeading(heading, true);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        repository.checkSession();
        repository.addListener(this);
    }

    @Override protected void onStop() {
        verified = false;
        // Keep the last verified profile on screen; listeners are stopped to avoid background reads.
        stopProfileReads();
        repository.removeListener(this);
        super.onStop();
    }

    @Override public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        String uid = currentUid();
        boolean patient = PatientScreenAccess.authorized(state.loading, state.user, uid);
        verified = patient;
        if (state.loading) {
            boolean hasContent = PatientScreenAccess.keepWhileChecking(binding.patientContent.getVisibility() == View.VISIBLE,
                    owner, uid, state.pendingProfile != null);
            binding.patientContent.setVisibility(hasContent ? View.VISIBLE : View.GONE);
            binding.sessionLoading.getRoot().setVisibility(hasContent ? View.GONE : View.VISIBLE);
            stopProfileReads();
            return;
        }
        binding.sessionLoading.getRoot().setVisibility(View.GONE);
        binding.patientContent.setVisibility(patient ? View.VISIBLE : View.GONE);
        if (!patient || (validContext && !PatientScreenAccess.sameOwner(owner, uid))) {
            clearProfile();
            binding.patientContent.setVisibility(View.GONE);
            AuthNavigation.openDestination(this, uid == null ? null : state.user);
            return;
        }
        if (!validContext) { showFailure(R.string.patient_profile_error_message); return; }
        if (doctor == null) {
            Doctor cached = cache.doctor(owner, doctorId);
            if (cached != null && cached.isActive()) {
                doctor = cached;
                showProfileContent();
                renderProfile();
                binding.profileNotice.setText(R.string.dashboard_cached);
                binding.profileNotice.setVisibility(View.VISIBLE);
            }
        }
        if (doctorListener == null) startProfile();
    }

    private String currentUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    private void renderProfile() {
        com.example.medibook.utils.DoctorImages.load(binding.doctorImage, doctor.getImageUrl());
        binding.profileStatus.setText(R.string.doctor_active_status);
        binding.profileNotice.setVisibility(View.GONE);
        binding.doctorName.setText(doctor.getName());
        binding.specialization.setText(doctor.getSpecialization());
        binding.qualifications.setText(doctor.getQualifications());
        binding.consultationFee.setText(getString(R.string.doctor_fee_amount, PatientDoctorDirectory.feeText(doctor)));
        binding.biography.setText(doctor.getBiography().isEmpty()
                ? getString(R.string.patient_profile_biography_empty) : doctor.getBiography());
    }

    private boolean canRead() { return verified && !isFinishing() && PatientScreenAccess.sameOwner(owner, currentUid()); }

    private void stopScheduleRead() {
        scheduleGeneration++;
        if (scheduleListener != null) scheduleListener.remove();
        scheduleListener = null;
    }

    private void stopProfileReads() {
        generation++;
        stopScheduleRead();
        if (doctorListener != null) doctorListener.remove();
        doctorListener = null;
    }

    private void clearProfile() {
        stopProfileReads();
        com.example.medibook.utils.DoctorImages.clear(binding.doctorImage);
        doctor = null;
        binding.doctorName.setText(null);
        binding.specialization.setText(null);
        binding.qualifications.setText(null);
        binding.consultationFee.setText(null);
        binding.biography.setText(null);
        binding.scheduleText.setText(null);
    }

    private void showLoading() {
        binding.errorHeading.setText(R.string.patient_profile_loading_title);
        binding.profileContent.setVisibility(View.GONE);
        binding.errorContent.setVisibility(View.VISIBLE);
        binding.errorMessage.setText(R.string.patient_profile_loading_message);
        binding.retryButton.setVisibility(View.GONE);
    }

    private void showProfileContent() {
        binding.errorContent.setVisibility(View.GONE);
        binding.profileContent.setVisibility(View.VISIBLE);
    }

    private void showFailure(int message) {
        binding.errorHeading.setText(R.string.patient_profile_error_title);
        binding.profileContent.setVisibility(View.GONE);
        binding.errorContent.setVisibility(View.VISIBLE);
        binding.errorMessage.setText(message);
        binding.retryButton.setVisibility(validContext ? View.VISIBLE : View.GONE);
    }

    private void startProfile() {
        stopProfileReads();
        final int request = generation;
        if (doctor == null) showLoading();
        else {
            binding.profileNotice.setText(R.string.dashboard_cached);
            binding.profileNotice.setVisibility(View.VISIBLE);
        }
        doctorListener = live.doctor(owner, doctorId, (value, error) -> {
            if (request != generation || !canRead()) return;
            if (error != DoctorRepository.Error.NONE || value == null || !value.isActive()) {
                if (error == DoctorRepository.Error.PERMISSION || error == DoctorRepository.Error.NONE) {
                    clearProfile();
                    showFailure(error == DoctorRepository.Error.PERMISSION ? R.string.patient_live_permission : R.string.patient_live_unavailable);
                    return;
                }
                if (doctor == null) {
                    stopScheduleRead();
                    showFailure(error == DoctorRepository.Error.PERMISSION ? R.string.patient_live_permission
                            : error == DoctorRepository.Error.NONE ? R.string.patient_live_unavailable
                            : R.string.patient_live_error);
                }
                // Keep already-rendered server data visible when only the refresh failed.
                binding.profileNotice.setText(R.string.dashboard_cached);
                binding.profileNotice.setVisibility(View.VISIBLE);
                return;
            }
            doctor = value;
            cache.putDoctor(owner, value);
            showProfileContent();
            renderProfile();
            if (scheduleListener == null) startSchedules();
        });
        // Do not serialize schedules behind another doctor round trip when this doctor was already
        // server-confirmed in the directory/profile cache. Rules still authorize both live reads.
        if (doctor != null && scheduleListener == null) startSchedules();
    }

    private void startSchedules() {
        stopScheduleRead();
        final int request = scheduleGeneration;
        List<DoctorSchedule> cached = cache.schedules(owner, doctorId);
        if (cached == null) {
            binding.scheduleText.setText(R.string.patient_live_schedule_loading);
            binding.scheduleRetryButton.setVisibility(View.GONE);
        } else renderSchedules(cached);
        scheduleListener = live.schedules(owner, doctorId, (values, error) -> {
            if (request != scheduleGeneration || !canRead() || doctor == null) return;
            binding.scheduleRetryButton.setVisibility(error == DoctorRepository.Error.NONE ? View.GONE : View.VISIBLE);
            if (error != DoctorRepository.Error.NONE) {
                if (cache.schedules(owner, doctorId) == null) {
                    binding.scheduleText.setText(error == DoctorRepository.Error.PERMISSION
                            ? R.string.patient_live_permission : R.string.patient_live_error);
                }
                return;
            }
            cache.putSchedules(owner, doctorId, values);
            renderSchedules(values);
        });
    }

    private void renderSchedules(List<DoctorSchedule> schedules) {
        if (schedules.isEmpty()) {
            binding.scheduleText.setText(R.string.patient_live_schedule_empty);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (DoctorSchedule schedule : schedules) {
            if (text.length() > 0) text.append("\n\n");
            String interval = getResources().getQuantityString(R.plurals.patient_live_schedule_interval,
                    schedule.getSlotMinutes(), schedule.getSlotMinutes());
            text.append(getString(R.string.patient_live_schedule_row, DoctorScheduleUi.day(this, schedule.getDayOfWeek()),
                    PatientScheduleDisplay.time(schedule.getStartMinute()),
                    PatientScheduleDisplay.time(schedule.getEndMinute()), interval));
        }
        binding.scheduleText.setText(text.toString());
    }
}
