package com.example.medibook.activities.patient;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.adapters.PatientDoctorAdapter;
import com.example.medibook.adapters.PatientHomeHeaderAdapter;
import com.example.medibook.databinding.ActivityPatientHomeBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.PatientDoctorProfileContract;
import com.example.medibook.utils.PatientScreenAccess;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/** Patient-only live directory. Reads start only after online session verification. */
public class PatientHomeActivity extends AppCompatActivity implements AuthRepository.Listener,
        PatientDoctorDirectoryViewModel.Listener, PatientHomeHeaderAdapter.Listener, PatientDoctorAdapter.Listener {

    private ActivityPatientHomeBinding binding;
    private AuthRepository repository;
    private PatientDoctorDirectoryViewModel model;
    private PatientHomeHeaderAdapter header;
    private PatientDoctorAdapter doctors;
    private boolean patientVerified;
    private boolean openingProfile;
    private Bundle pendingFilters;
    private final com.example.medibook.repositories.PatientDoctorRepository live = new com.example.medibook.repositories.PatientDoctorRepository();
    private com.google.firebase.firestore.ListenerRegistration subscription;
    private int generation;
    private com.example.medibook.system.PatientSystemNotices systemNotices;
    private com.example.medibook.system.SystemNoticeState noticeState;
    private static final String STATE_CAROUSEL_PAGE = "wellnessCarouselPage";
    private com.example.medibook.repositories.PatientDashboardRepository.Subscription nextAppointment;
    private com.example.medibook.models.Appointment next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPatientHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        noticeState = new ViewModelProvider(this).get(com.example.medibook.system.SystemNoticesViewModel.class).state;
        systemNotices = new com.example.medibook.system.PatientSystemNotices(this, noticeState,
                new com.example.medibook.system.PatientSystemNotices.Listener() {
                    @Override public void onAirplaneState(boolean enabled) { renderAirplane(); }
                    @Override public void onBatteryLow() {
                        if (patientVerified) com.google.android.material.snackbar.Snackbar.make(binding.getRoot(),
                                R.string.battery_low_notice, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                    }
                });
        model = new ViewModelProvider(this).get(PatientDoctorDirectoryViewModel.class);
        pendingFilters = savedInstanceState;
        header = new PatientHomeHeaderAdapter(this, model.getState());
        if (savedInstanceState != null) {
            // Keep the visible Health & Wellness card; the timer itself always restarts fresh.
            header.setCarouselPage(savedInstanceState.getInt(STATE_CAROUSEL_PAGE, 0));
        }
        doctors = new PatientDoctorAdapter(this, model.getState());
        binding.patientContent.setLayoutManager(new LinearLayoutManager(this));
        binding.patientContent.setAdapter(new ConcatAdapter(header, doctors));
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.actionEditProfile) {
                if (!patientVerified) return true;
                startActivity(new Intent(this, EditProfileActivity.class));
                return true;
            }
            if (item.getItemId() != R.id.actionLogout) return false;
            repository.signOut();
            return true;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        openingProfile = false;
        model.addListener(this);
        // Recheck on re-entry, not just after login. Never trust an Intent or cached role.
        repository.checkSession();
        repository.addListener(this);
        systemNotices.start();
        header.setCarouselActive(true);
    }

    @Override
    protected void onStop() {
        header.setCarouselActive(false);
        systemNotices.stop();
        binding.airplaneNotice.setVisibility(View.GONE);
        stopDirectory();
        stopNextAppointment();
        repository.removeListener(this);
        model.removeListener(this);
        patientVerified = false;
        // Keep the last verified directory on screen: a warm resume must not flash the session
        // loader or lose the search text and scroll position. Reads stop above; onStart revalidates.
        header.closeMenus();
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        String uid = currentUid();
        patientVerified = PatientScreenAccess.authorized(state.loading, state.user, uid);
        renderAirplane();
        if (state.loading) {
            if (!PatientScreenAccess.sameOwner(model.getOwner(), uid)) {
                next = null; header.setNextAppointment(null, com.example.medibook.repositories.DashboardState.LOADING);
            } else if (next != null) header.setNextAppointment(next, com.example.medibook.repositories.DashboardState.CACHED);
            boolean hasContent = PatientScreenAccess.keepWhileChecking(binding.patientContent.getVisibility() == View.VISIBLE,
                    model.getOwner(), uid, state.pendingProfile != null);
            binding.patientContent.setVisibility(hasContent ? View.VISIBLE : View.GONE);
            binding.sessionLoading.getRoot().setVisibility(hasContent ? View.GONE : View.VISIBLE);
            stopDirectory();
            stopNextAppointment();
            header.closeMenus();
            return;
        }
        binding.sessionLoading.getRoot().setVisibility(View.GONE);
        binding.patientContent.setVisibility(patientVerified ? View.VISIBLE : View.GONE);
        if (!patientVerified) {
            stopDirectory();
            stopNextAppointment();
            next = null;
            header.setNextAppointment(null, com.example.medibook.repositories.DashboardState.LOADING);
            model.endSession();
            pendingFilters = null;
            // A verified Admin belongs in the Admin flow, never in Patient Home.
            AuthNavigation.openDestination(this, uid == null ? null : state.user);
        } else {
            header.setPatientName(state.user.getFullName());
            com.example.medibook.cache.PatientDataCache.get().bindOwner(uid);
            if (!PatientScreenAccess.sameOwner(model.getOwner(), uid)) {
                stopDirectory(); stopNextAppointment(); next = null;
                header.setNextAppointment(null, com.example.medibook.repositories.DashboardState.LOADING);
            }
            model.bindSession(uid);
            if (pendingFilters != null) {
                // Saved search text is UI state, never a role or an authorization decision.
                if (PatientScreenAccess.sameOwner(pendingFilters.getString("filterOwner"), uid)) {
                    model.restoreFilters(pendingFilters.getString("query", ""), pendingFilters.getString("specialization", ""));
                }
                pendingFilters = null;
            }
            onDirectoryChanged(model.getState());
            if (subscription == null) startDirectory();
            if (nextAppointment == null) startNextAppointment();
        }
    }

    private String currentUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    private boolean canUseDirectory() {
        return patientVerified && PatientScreenAccess.sameOwner(model.getOwner(), currentUid()) && !isFinishing();
    }

    @Override public void onDirectoryChanged(PatientDoctorDirectoryViewModel.State state) {
        if (!canUseDirectory()) return;
        header.submit(state);
        doctors.submit(state);
    }

    @Override public void onSearchChanged(String query) { if (canUseDirectory()) model.setQuery(query); }
    @Override public void onOpenAppointments() {
        if (canUseDirectory()) startActivity(new Intent(this, com.example.medibook.activities.AppointmentsActivity.class));
    }
    @Override public void onViewNextAppointment() {
        if (canUseDirectory() && com.example.medibook.utils.NextAppointment.eligible(model.getOwner(), next, System.currentTimeMillis())) {
            startActivity(new Intent(this, com.example.medibook.activities.AppointmentDetailsActivity.class)
                    .putExtra("appointmentId", next.getId()));
        }
    }
    @Override public void onRetryNextAppointment() { if (canUseDirectory()) startNextAppointment(); }
    private void stopNextAppointment() {
        if (nextAppointment != null) nextAppointment.close();
        nextAppointment = null;
    }
    private void startNextAppointment() {
        stopNextAppointment();
        nextAppointment = new com.example.medibook.repositories.PatientDashboardRepository().watch(model.getOwner(), (value, state) -> {
            if (!canUseDirectory()) return;
            next = value; header.setNextAppointment(value, state);
        });
    }

    @Override public void onCallHospital() { if (canUseDirectory()) com.example.medibook.utils.DialIntents.hospital(this); }
    @Override public void onOpenEmergencyContact() { if (canUseDirectory()) startActivity(new Intent(this, EmergencyContactActivity.class)); }
    @Override public void onOpenAirQuality() { if (canUseDirectory()) startActivity(new Intent(this, AirQualityActivity.class)); }
    private void renderAirplane() { binding.airplaneNotice.setVisibility(patientVerified && noticeState.airplane() ? View.VISIBLE : View.GONE); }
    @Override public void onSpecializationChanged(String value) { if (canUseDirectory()) model.setSpecialization(value); }
    @Override public void onClearFilters() { if (canUseDirectory()) model.clearFilters(); }

    @Override public void onSearchSubmitted() {
        if (canUseDirectory()) WindowCompat.getInsetsController(getWindow(), binding.getRoot()).hide(WindowInsetsCompat.Type.ime());
    }

    @Override public void onRetryDirectory() {
        if (!canUseDirectory()) return;
        startDirectory();
    }

    private void stopDirectory() {
        generation++;
        if (subscription != null) subscription.remove();
        subscription = null;
    }

    private void startDirectory() {
        stopDirectory();
        final int request = generation;
        final String owner = model.getOwner();
        // Render the last server-confirmed directory immediately, then refresh quietly.
        if (model.getState().doctors.isEmpty()) {
            java.util.List<Doctor> cached = com.example.medibook.cache.PatientDataCache.get().directory(owner);
            if (cached != null) model.showDirectory(owner, cached);
            else model.showLoading(owner);
        }
        subscription = live.doctors(owner, (records, error) -> {
            if (request != generation || !canUseDirectory()) return;
            if (error == com.example.medibook.repositories.DoctorRepository.Error.NONE) {
                com.example.medibook.cache.PatientDataCache.get().putDirectory(owner, records);
                model.showDirectory(owner, records);
            } else if (error == com.example.medibook.repositories.DoctorRepository.Error.PERMISSION) {
                model.showPermission(owner);
            } else {
                model.showRefreshFailure(owner);
            }
        });
    }

    @Override public void onViewDoctor(Doctor selected) {
        if (!canUseDirectory() || openingProfile || selected == null) return;
        for (Doctor current : model.getState().doctors) {
            if (current.getId().equals(selected.getId())) { openProfile(current); return; }
        }
    }

    private void openProfile(Doctor doctor) {
        openingProfile = true;
        onSearchSubmitted();
        header.closeMenus();
        Intent intent = new Intent(this, DoctorProfileActivity.class);
        PatientDoctorProfileContract.putDoctorContext(intent, model.getOwner(), doctor);
        startActivity(intent);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        PatientDoctorDirectoryViewModel.State state = model.getState();
        outState.putString("filterOwner", model.getOwner());
        outState.putString("query", state.query);
        outState.putString("specialization", state.specialization);
        outState.putInt(STATE_CAROUSEL_PAGE, header.getCarouselPage());
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        header.releaseCarousel();
        super.onDestroy();
    }
}
