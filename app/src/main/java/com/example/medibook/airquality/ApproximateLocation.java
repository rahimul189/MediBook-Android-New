package com.example.medibook.airquality;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.core.os.CancellationSignal;

import com.example.medibook.R;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One approximate, recent fix with a bounded wait. No background tracking, no ACCESS_FINE_LOCATION,
 * no storage of coordinates, and no upload to Firestore/RTDB. The result is always explicit about
 * whether it is the device's current area or the labelled Khulna fallback.
 */
public final class ApproximateLocation {
    /** A cached fix this recent is accurate enough for city-level model data. */
    public static final long FRESH_MILLIS = 10 * 60_000L;
    public static final long TIMEOUT_MILLIS = 8_000L;
    private static final long GEOCODER_TIMEOUT_SECONDS = 3L;

    public static final class Result {
        public final double latitude, longitude;
        public final String label;
        public final boolean fallback;

        public Result(double latitude, double longitude, String label, boolean fallback) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.label = label;
            this.fallback = fallback;
        }
    }

    public interface Callback { void result(Result result); }

    private ApproximateLocation() { }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static Result fallback(Context context) {
        return new Result(22.8456, 89.5403, context.getString(R.string.environment_location_fallback), true);
    }

    /** Always calls back exactly once on the main thread, with either a fix or the labelled fallback. */
    public static void request(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        if (!hasPermission(app)) { callback.result(fallback(app)); return; }
        final LocationManager manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) { callback.result(fallback(app)); return; }

        Location recent = recentFix(manager);
        if (recent != null) { deliver(app, recent.getLatitude(), recent.getLongitude(), callback); return; }

        final String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                : manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) { callback.result(fallback(app)); return; }

        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean done = new AtomicBoolean();
        final CancellationSignal signal = new CancellationSignal();
        final Runnable timeout = () -> {
            if (!done.compareAndSet(false, true)) return;
            signal.cancel();
            callback.result(fallback(app));
        };
        main.postDelayed(timeout, TIMEOUT_MILLIS);
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, signal,
                    ContextCompat.getMainExecutor(app), location -> {
                        if (!done.compareAndSet(false, true)) return;
                        main.removeCallbacks(timeout);
                        if (location == null) callback.result(fallback(app));
                        else deliver(app, location.getLatitude(), location.getLongitude(), callback);
                    });
        } catch (SecurityException | IllegalArgumentException unavailable) {
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout);
                callback.result(fallback(app));
            }
        }
    }

    private static Location recentFix(LocationManager manager) {
        Location best = null;
        long now = System.currentTimeMillis();
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
                LocationManager.GPS_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) continue;
                long age = now - candidate.getTime();
                if (age < -60_000L || age > FRESH_MILLIS) continue;
                if (best == null || candidate.getTime() > best.getTime()) best = candidate;
            } catch (SecurityException | IllegalArgumentException unavailable) {
                // A provider can disappear between the check and the read.
            }
        }
        return best;
    }

    private static void deliver(Context app, double latitude, double longitude, Callback callback) {
        Thread worker = new Thread(() -> {
            String label = labelFor(app, latitude, longitude);
            new Handler(Looper.getMainLooper()).post(() ->
                    callback.result(new Result(latitude, longitude, label, false)));
        }, "medibook-location-label");
        worker.setDaemon(true);
        worker.start();
    }

    /** Bounded reverse geocoding; a missing or slow answer becomes "Current area", never a guess. */
    private static String labelFor(Context context, double latitude, double longitude) {
        if (!Geocoder.isPresent()) return context.getString(R.string.environment_location_current);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> lookup = executor.submit(() -> {
                List<Address> addresses = new Geocoder(context, Locale.getDefault())
                        .getFromLocation(latitude, longitude, 1);
                if (addresses == null || addresses.isEmpty()) return null;
                Address address = addresses.get(0);
                String area = address.getLocality();
                if (area == null || area.trim().isEmpty()) area = address.getSubAdminArea();
                if (area == null || area.trim().isEmpty()) area = address.getAdminArea();
                if (area == null || area.trim().isEmpty()) return null;
                String country = address.getCountryName();
                return country == null || country.trim().isEmpty() ? area : area + ", " + country;
            });
            String label = lookup.get(GEOCODER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return label == null ? context.getString(R.string.environment_location_current) : label;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return context.getString(R.string.environment_location_current);
        } catch (Exception unavailable) {
            return context.getString(R.string.environment_location_current);
        } finally {
            executor.shutdownNow();
        }
    }
}
