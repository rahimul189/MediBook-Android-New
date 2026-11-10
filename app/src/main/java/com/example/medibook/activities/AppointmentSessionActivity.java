package com.example.medibook.activities;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.cache.PatientDataCache;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.AuthNavigation;
import com.google.firebase.auth.FirebaseAuth;

/** Shared online role gate for the appointment screens, never an Intent-supplied role. */
public abstract class AppointmentSessionActivity extends AppCompatActivity implements AuthRepository.Listener {
    protected String owner;
    protected UserProfile session;
    protected int requestVersion;
    private boolean started;
    /** This screen already verified {@link #owner}; a revalidation must never wipe it. */
    private boolean verifiedOnce;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        owner = savedInstanceState == null ? (FirebaseAuth.getInstance().getCurrentUser() == null ? null
                : FirebaseAuth.getInstance().getCurrentUser().getUid()) : savedInstanceState.getString("appointmentOwner");
    }
    protected abstract View protectedContent();
    protected abstract View sessionLoading();
    protected abstract void onVerifiedSession();
    protected abstract void clearSessionViews();
    protected UserProfile.Role requiredRole() { return null; }
    protected final boolean canAccess() { return started && session != null && DoctorRepository.sameUser(owner) && !isFinishing(); }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        AuthRepository repository = AuthRepository.getInstance();
        repository.checkSession();
        // addListener publishes the current state synchronously, so actions stay gated while the
        // previous screen content remains visible during a warm resume.
        repository.addListener(this);
    }
    @Override protected void onStop() {
        started = false; session = null; requestVersion++;
        AuthRepository.getInstance().removeListener(this);
        super.onStop();
    }
    @Override public final void onAuthStateChanged(AuthRepository.State state) {
        if (!started || isFinishing()) return;
        boolean allowed = !state.loading && state.user != null && DoctorRepository.sameUser(owner)
                && (requiredRole() == null || requiredRole() == state.user.getRole());
        if (state.loading) {
            // A screen that already verified this owner keeps its content and never falls back to
            // the "Signing you in..." loader, so an in-progress operation is never hidden.
            boolean keep = com.example.medibook.utils.PatientScreenAccess.keepWhileChecking(
                    verifiedOnce || protectedContent().getVisibility() == View.VISIBLE, owner,
                    DoctorRepository.currentUserId(), state.pendingProfile != null);
            if (session != null && !DoctorRepository.sameUser(owner)) session = null;
            protectedContent().setVisibility(keep ? View.VISIBLE : View.GONE);
            if (!keep) clearSessionViews();
            sessionLoading().setVisibility(keep ? View.GONE : View.VISIBLE);
            requestVersion++;
            return;
        }
        session = allowed ? state.user : null;
        // A null user while the SAME Firebase account is still signed in is an unverified
        // revalidation result, not a sign-out and not a role change (AuthRepository keeps the
        // session in that case). Keep the verified screen instead of redirecting to the launcher.
        boolean sameAccountStillSignedIn = state.user == null && state.pendingProfile == null && owner != null
                && DoctorRepository.sameUser(owner);
        if (!allowed && verifiedOnce && sameAccountStillSignedIn) {
            sessionLoading().setVisibility(View.GONE);
            protectedContent().setVisibility(View.VISIBLE);
            return;
        }
        sessionLoading().setVisibility(View.GONE);
        protectedContent().setVisibility(allowed ? View.VISIBLE : View.GONE);
        if (!allowed) {
            clearSessionViews(); AuthNavigation.openDestination(this, state.user); return;
        }
        verifiedOnce = true;
        PatientDataCache.get().bindOwner(owner);
        onVerifiedSession();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("appointmentOwner", owner); super.onSaveInstanceState(state);
    }
}
