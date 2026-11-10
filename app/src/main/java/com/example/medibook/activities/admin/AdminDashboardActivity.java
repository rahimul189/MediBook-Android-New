package com.example.medibook.activities.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityAdminDashboardBinding;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.repositories.AdminDashboardRepository;
import com.example.medibook.repositories.DashboardState;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.WeeklyAppointmentStats;
import com.example.medibook.utils.ViewUpdates;

/**
 * Verified Admin landing screen with secure Doctor Management navigation, the Appointment Overview
 * analytics. The existing chart renders the verified RTDB projection or Firestore fallback.
 *
 * <p>The obsolete RTDB live-status card is no longer shown; the RTDB display mirror still runs as a
 * hidden best-effort dependency of booking/cancellation and is untouched here.</p>
 */
public class AdminDashboardActivity extends AppCompatActivity implements AuthRepository.Listener {
    private ActivityAdminDashboardBinding binding;
    private AuthRepository repository;
    private boolean adminVerified;
    /** This instance already verified the Admin; a revalidation must not flash the loader. */
    private boolean verifiedOnce;
    private String verifiedOwner;
    private AdminDashboardRepository.Subscription analytics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        ViewCompat.setAccessibilityHeading(binding.dashboardHeading, true);
        binding.weeklyChart.setLabels(getResources().getStringArray(R.array.admin_week_day_labels));
        repository = AuthRepository.getInstance();
        binding.logoutButton.setOnClickListener(view -> repository.signOut());
        binding.manageDoctorsButton.setOnClickListener(view -> {
            if (adminVerified) startActivity(new Intent(this, ManageDoctorsActivity.class));
        });
        binding.manageAppointmentsButton.setOnClickListener(view -> {
            if (adminVerified) startActivity(new Intent(this, com.example.medibook.activities.AppointmentsActivity.class));
        });
        binding.analyticsRetry.setOnClickListener(view -> { if (adminVerified) startAnalytics(); });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Reload account, force-refresh claims and read the owner's profile from the server.
        // No Intent extra, saved role or profile-only decision may open Admin content.
        repository.checkSession();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        stopAnalytics();
        repository.removeListener(this);
        adminVerified = false;
        // Keep the verified snapshot for a smooth return transition; actions are gated above and
        // onStart synchronously checks the current UID/role before content can be used again.
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        boolean authorized = !state.loading && state.user != null
                && state.user.getRole() == UserProfile.Role.ADMIN;
        adminVerified = authorized;
        if (!DoctorRepository.sameUser(verifiedOwner)) { verifiedOnce = false; stopAnalytics(); }
        // A warm resume of an already-verified Admin keeps the dashboard on screen; only a cold
        // check (nothing verified yet) shows the "Signing you in..." card.
        boolean showLoader = state.loading && !verifiedOnce;
        binding.loadingIndicator.setVisibility(showLoader ? View.VISIBLE : View.GONE);
        binding.sessionStatus.setVisibility(showLoader ? View.VISIBLE : View.GONE);
        binding.adminContent.setVisibility(authorized || (state.loading && verifiedOnce)
                ? View.VISIBLE : View.GONE);
        // Logout stays available during a pending session check; late callbacks are ignored.
        if (state.loading) {
            stopAnalytics();
            if (verifiedOnce) { binding.analyticsMessage.setText(R.string.dashboard_cached); binding.analyticsMessage.setVisibility(View.VISIBLE); }
            return;
        }

        if (!authorized) {
            stopAnalytics();
            // An authenticated Patient is redirected to Patient Home without Admin content.
            // A failed verification already signed out in the repository and routes to Login.
            AuthNavigation.openDestination(this, state.user);
        } else {
            verifiedOnce = true;
            verifiedOwner = DoctorRepository.currentUserId();
            ViewUpdates.text(binding.welcomeMessage, getString(R.string.admin_welcome, state.user.getFullName()));
            if (analytics == null) startAnalytics();
        }
    }

    /** Cached counts appear immediately; the same bounded listener refreshes them quietly. */
    private void startAnalytics() {
        stopAnalytics();
        String owner = DoctorRepository.currentUserId();
        analytics = new AdminDashboardRepository().watch(owner, (stats, state) -> {
            if (!adminVerified || !DoctorRepository.sameUser(owner)) return;
            renderAnalytics(stats, state);
        });
    }

    private void renderAnalytics(WeeklyAppointmentStats stats, DashboardState state) {
        if (stats != null) {
            ViewUpdates.text(binding.todayCount, count(stats.todayTotal));
            ViewUpdates.text(binding.weekCount, count(stats.weekTotal));
            ViewUpdates.text(binding.confirmedCount, count(stats.confirmedToday));
            ViewUpdates.text(binding.cancelledCount, count(stats.cancelledToday));
            binding.weeklyChart.setStats(stats);
        } else {
            binding.todayCount.setText("—"); binding.weekCount.setText("—");
            binding.confirmedCount.setText("—"); binding.cancelledCount.setText("—");
        }
        binding.weeklyChart.setVisibility(stats == null ? View.INVISIBLE : View.VISIBLE);
        boolean empty = stats != null && stats.weekTotal == 0;
        binding.analyticsMessage.setVisibility(state != DashboardState.LIVE || empty ? View.VISIBLE : View.GONE);
        binding.analyticsMessage.setText(state == DashboardState.LOADING ? R.string.dashboard_loading
                : state == DashboardState.ERROR ? R.string.dashboard_error
                : state == DashboardState.CACHED ? R.string.dashboard_cached
                : state == DashboardState.FIRESTORE ? R.string.dashboard_firestore : R.string.admin_analytics_empty);
        binding.analyticsRetry.setVisibility(state == DashboardState.ERROR || state == DashboardState.CACHED ? View.VISIBLE : View.GONE);
        binding.analyticsMessage.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                state == DashboardState.ERROR ? R.color.medibook_error : state == DashboardState.CACHED
                        ? R.color.medibook_warning : R.color.medibook_text_secondary));
    }

    private void stopAnalytics() {
        if (analytics != null) analytics.close();
        analytics = null;
        if (binding != null) {
            binding.analyticsMessage.setVisibility(View.GONE);
            binding.analyticsRetry.setVisibility(View.GONE);
        }
    }

    private static String count(int value) { return String.format(java.util.Locale.getDefault(), "%d", value); }

}
