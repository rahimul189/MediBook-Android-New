package com.example.medibook;

import android.app.ActivityManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.databinding.ActivityMainBinding;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.LaunchRouting;

/**
 * Launcher/router only. Every exit path leaves a real screen behind: when a verified task is still
 * retained it is brought back to the front, otherwise the correct destination is opened here. The
 * router must never finish with nothing to show, because a launcher tap starts it in a fresh task
 * while the authenticated screens live in a task of their own.
 */
public class MainActivity extends AppCompatActivity implements AuthRepository.Listener {

    private ActivityMainBinding binding;
    private AuthRepository repository;
    private boolean routed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = AuthRepository.getInstance();
        UserProfile verified = repository.verifiedUser();
        ActivityManager.AppTask retained = verified == null
                ? null : AuthNavigation.findDestinationTask(this, verified);
        LaunchRouting.Action action = LaunchRouting.actionFor(verified != null, retained != null);
        if (action == LaunchRouting.Action.RESUME_EXISTING_TASK && AuthNavigation.resumeTask(retained)) {
            // The retained task is in front again, showing the exact screen the patient left.
            finish();
            return;
        }
        if (action != LaunchRouting.Action.VERIFY_SESSION) {
            // No reusable authenticated task (first launch after update, or it was removed):
            // build the destination instead of finishing into an empty task.
            routed = true;
            AuthNavigation.openDestination(this, verified, true);
            return;
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (binding == null) return;
        repository.checkSession();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        if (binding != null) repository.removeListener(this);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (binding == null || state.loading || routed || isFinishing()) return;
        routed = true;
        AuthNavigation.openDestination(this, state.user);
    }
}
