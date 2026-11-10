package com.example.medibook.airquality;

import org.json.JSONObject;

/** Android's JSONObject parser is used in production, with the same API supplied to JVM tests. */
public final class AirQualityParser {
    private AirQualityParser() { }
    public static AirQualityReport parse(String json, long fetchedAt) throws AirQualityException {
        try {
            if (json == null || json.length() > AirQualityHttpClient.MAX_BYTES || fetchedAt <= 0) throw new IllegalArgumentException();
            JSONObject root = new JSONObject(json);
            if (root.optBoolean("error", false)) throw new IllegalArgumentException();
            JSONObject current = root.optJSONObject("current"), units = root.optJSONObject("current_units");
            if (current == null || units == null || !"unixtime".equals(units.optString("time"))) throw new IllegalArgumentException();
            Object timestamp = current.opt("time");
            if (!(timestamp instanceof Number)) throw new IllegalArgumentException();
            double seconds = ((Number) timestamp).doubleValue();
            if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 4_102_444_800L || seconds != Math.floor(seconds)) throw new IllegalArgumentException();
            long estimatedAt = ((Number) timestamp).longValue() * 1000;
            if (estimatedAt > fetchedAt + 60 * 60_000L) throw new IllegalArgumentException();
            return new AirQualityReport(estimatedAt, fetchedAt, measurement(current, "us_aqi", 10_000),
                    measurement(current, "pm2_5", 100_000), measurement(current, "pm10", 100_000), measurement(current, "uv_index", 30));
        } catch (Exception malformed) { throw new AirQualityException(AirQualityException.Kind.FORMAT); }
    }
    private static Double measurement(JSONObject json, String key, double maximum) {
        Object value = json.opt(key);
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number >= 0 && number <= maximum ? number : null;
    }
}
