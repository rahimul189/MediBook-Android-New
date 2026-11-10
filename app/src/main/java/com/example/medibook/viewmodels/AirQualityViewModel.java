package com.example.medibook.viewmodels;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.medibook.airquality.AirQualityException;
import com.example.medibook.airquality.AirQualityReport;
import com.example.medibook.airquality.AirQualityRepository;
import com.example.medibook.airquality.ApproximateLocation;
import com.google.android.gms.tasks.Tasks;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Locates once (coarse, bounded), then loads a location-aware estimate. Cached data is shown while
 * a quiet refresh runs; a denied/disabled/timeout location yields an explicitly labelled fallback.
 */
public final class AirQualityViewModel extends AndroidViewModel {
    public static final class State {
        public final AirQualityReport report;
        public final boolean loading, cached, fallback;
        public final AirQualityException.Kind error;
        public final String locationLabel;

        State(AirQualityReport report, boolean loading, boolean cached, AirQualityException.Kind error,
              String locationLabel, boolean fallback) {
            this.report = report; this.loading = loading; this.cached = cached;
            this.error = error; this.locationLabel = locationLabel; this.fallback = fallback;
        }
    }

    private final AirQualityRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(null, false, false, null, null, false));
    private Double latitude, longitude;
    private String locationLabel;
    private boolean fallback, locating, cleared;

    public AirQualityViewModel(@NonNull Application application) {
        super(application);
        repository = new AirQualityRepository(application);
    }

    public LiveData<State> state() { return state; }

    /** The screen calls this on every verified entry; a fresh 15-minute entry avoids a network read. */
    public void load(boolean force) {
        State current = state.getValue();
        if (current == null) current = new State(null, false, false, null, null, false);
        if (current.loading || locating || (latitude == null && permissionDenied)) return;
        if (latitude == null) {
            locating = true;
            state.setValue(new State(current.report, true, current.cached, null, null, false));
            ApproximateLocation.request(getApplication(), result -> {
                locating = false;
                if (cleared) return;
                latitude = result.latitude;
                longitude = result.longitude;
                locationLabel = result.label;
                fallback = result.fallback;
                fetch(force);
            });
            return;
        }
        fetch(force);
    }

    /** Avoids re-prompting or re-locating after the patient denied the permission. */
    private boolean permissionDenied;

    public void onPermissionDenied() {
        permissionDenied = true;
        locating = false;
        ApproximateLocation.Result result = ApproximateLocation.fallback(getApplication());
        latitude = result.latitude;
        longitude = result.longitude;
        locationLabel = result.label;
        fallback = true;
        fetch(false);
    }

    private void fetch(boolean force) {
        if (cleared || latitude == null || longitude == null) return;
        final State previous = state.getValue();
        final double lat = latitude, lon = longitude;
        final String label = locationLabel;
        final boolean isFallback = fallback;
        state.setValue(new State(previous == null ? null : previous.report, true,
                previous != null && previous.cached, null, label, isFallback));
        Tasks.call(executor, () -> {
            AirQualityReport cached = repository.cached(lat, lon);
            if (!force && cached != null && cached.cacheFresh(System.currentTimeMillis())) {
                return new State(cached, false, true, null, label, isFallback);
            }
            try {
                return new State(repository.fetch(lat, lon), false, false, null, label, isFallback);
            } catch (AirQualityException failure) {
                return new State(cached, false, cached != null, failure.kind, label, isFallback);
            }
        }).addOnCompleteListener(task -> {
            if (cleared) return;
            State fallbackState = new State(previous == null ? null : previous.report, false,
                    previous != null && previous.cached, AirQualityException.Kind.NETWORK, label, isFallback);
            state.setValue(task.isSuccessful() && task.getResult() != null ? task.getResult() : fallbackState);
        });
    }

    @Override protected void onCleared() {
        cleared = true;
        executor.shutdown();
    }
}
