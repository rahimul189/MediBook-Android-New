package com.example.medibook.airquality;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Non-personal, lightweight disk cache keyed by rounded coordinates. Failed fetches never overwrite
 * the last valid response, and each approximate area keeps its own 15-minute entry.
 */
public final class AirQualityRepository {
    private final SharedPreferences cache;

    public AirQualityRepository(Context context) {
        cache = context.getSharedPreferences("air_quality_cache", Context.MODE_PRIVATE);
    }

    public AirQualityReport cached(double latitude, double longitude) {
        String key = key(latitude, longitude);
        String raw = cache.getString("json." + key, null);
        try {
            return raw == null ? null : AirQualityParser.parse(raw, cache.getLong("fetched." + key, 0));
        } catch (AirQualityException invalidCache) {
            return null;
        }
    }

    public AirQualityReport fetch(double latitude, double longitude) throws AirQualityException {
        String raw = new AirQualityHttpClient().fetch(latitude, longitude);
        long now = System.currentTimeMillis();
        AirQualityReport report = AirQualityParser.parse(raw, now);
        String key = key(latitude, longitude);
        cache.edit().putString("json." + key, raw).putLong("fetched." + key, now).apply();
        return report;
    }

    private static String key(double latitude, double longitude) {
        // Two decimals is roughly a kilometre: enough to reuse a city-level estimate across moves.
        return String.format(Locale.US, "%.2f_%.2f", latitude, longitude);
    }
}
