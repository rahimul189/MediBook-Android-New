package com.example.medibook.activities.patient;

import android.Manifest;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentSessionActivity;
import com.example.medibook.adapters.AirQualityAdapter;
import com.example.medibook.airquality.AirQualityException;
import com.example.medibook.airquality.ApproximateLocation;
import com.example.medibook.databinding.ActivityAirQualityBinding;
import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.viewmodels.AirQualityViewModel;

/**
 * Environment &amp; Health. Coarse location is requested contextually on first verified entry; a
 * denial, disabled provider or timeout falls back to a clearly labelled city instead of pretending
 * the fallback is the patient's position.
 */
public final class AirQualityActivity extends AppointmentSessionActivity {
    private ActivityAirQualityBinding binding;
    private AirQualityViewModel model;
    private AirQualityAdapter adapter;
    private boolean permissionAsked;
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ActivityResultLauncher<String> locationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!canAccess()) return;
                if (granted) model.load(false);
                else { model.onPermissionDenied(); render(model.state().getValue()); }
            });
    private final Runnable freshness = new Runnable() {
        @Override public void run() {
            if (canAccess()) adapter.refreshFreshness();
            clock.postDelayed(this, 60_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityAirQualityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        adapter = new AirQualityAdapter();
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter);
        model = new ViewModelProvider(this).get(AirQualityViewModel.class);
        binding.retryButton.setOnClickListener(v -> { if (canAccess()) model.load(true); });
        model.state().observe(this, value -> { if (canAccess()) render(value); });
    }

    @Override protected UserProfile.Role requiredRole() { return UserProfile.Role.PATIENT; }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }

    @Override protected void onVerifiedSession() {
        if (!permissionAsked && !ApproximateLocation.hasPermission(this)) {
            permissionAsked = true;
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION);
            render(model.state().getValue());
            return;
        }
        model.load(false);
        render(model.state().getValue());
    }

    @Override protected void clearSessionViews() {
        adapter.submit(null, false, null, false);
        binding.message.setText(null);
    }

    @Override protected void onStart() { super.onStart(); clock.post(freshness); }
    @Override protected void onStop() { clock.removeCallbacks(freshness); super.onStop(); }

    private void render(AirQualityViewModel.State state) {
        if (state == null) return;
        adapter.submit(state.report, state.cached, state.locationLabel, state.fallback);
        binding.progress.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.retryButton.setEnabled(!state.loading);
        int text = state.loading ? R.string.air_quality_loading
                : state.error == AirQualityException.Kind.NETWORK ? R.string.air_quality_network_error
                : state.error == AirQualityException.Kind.HTTP ? R.string.air_quality_http_error
                : state.error == AirQualityException.Kind.FORMAT ? R.string.air_quality_format_error
                : state.report == null || !state.report.hasMeasurements() ? R.string.air_quality_empty : 0;
        binding.message.setVisibility(text == 0 ? View.GONE : View.VISIBLE);
        binding.message.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                !state.loading && state.error != null ? R.color.medibook_error : R.color.medibook_text_secondary));
        if (text != 0) binding.message.setText(text);
    }
}
