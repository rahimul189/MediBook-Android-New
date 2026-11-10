package com.example.medibook.airquality;

/**
 * Deterministic US AQI and UV categories with conservative, exposure-only advice.
 * No value is invented: a missing measurement yields {@code UNAVAILABLE}.
 */
public final class EnvironmentGuidance {
    public enum Aqi { UNAVAILABLE, GOOD, MODERATE, SENSITIVE, UNHEALTHY, VERY_UNHEALTHY, HAZARDOUS }

    public enum Uv { UNAVAILABLE, LOW, MODERATE, HIGH, VERY_HIGH, EXTREME }

    /** Category of the current PM reading; never claims a 24-hour average or a WHO guideline. */
    public enum Pm { UNAVAILABLE, LOW, ELEVATED, HIGH }

    private EnvironmentGuidance() { }

    /** US EPA AQI boundaries: 0-50, 51-100, 101-150, 151-200, 201-300, 301-500. */
    public static Aqi aqi(Double value) {
        if (!valid(value, 500)) return Aqi.UNAVAILABLE;
        if (value <= 50) return Aqi.GOOD;
        if (value <= 100) return Aqi.MODERATE;
        if (value <= 150) return Aqi.SENSITIVE;
        if (value <= 200) return Aqi.UNHEALTHY;
        if (value <= 300) return Aqi.VERY_UNHEALTHY;
        return Aqi.HAZARDOUS;
    }

    /** UV Index: 0-2, 3-5, 6-7, 8-10, 11+. */
    public static Uv uv(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0) return Uv.UNAVAILABLE;
        if (value < 3) return Uv.LOW;
        if (value < 6) return Uv.MODERATE;
        if (value < 8) return Uv.HIGH;
        if (value < 11) return Uv.VERY_HIGH;
        return Uv.EXTREME;
    }

    /** PM2.5 bands (micrograms per cubic metre) for the current reading. */
    public static Pm pm25(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0) return Pm.UNAVAILABLE;
        if (value <= 35.4) return Pm.LOW;
        if (value <= 55.4) return Pm.ELEVATED;
        return Pm.HIGH;
    }

    /** PM10 bands (micrograms per cubic metre) for the current reading. */
    public static Pm pm10(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0) return Pm.UNAVAILABLE;
        if (value <= 154) return Pm.LOW;
        if (value <= 254) return Pm.ELEVATED;
        return Pm.HIGH;
    }

    /**
     * Particle concentrations that warrant respirator wording, tied to the AQI 151+ band:
     * PM2.5 above 55.4 µg/m³ or PM10 above 254 µg/m³. AQI above 150 also qualifies.
     */
    public static boolean respiratorAdvised(Double aqi, Double pm25, Double pm10) {
        return exceeds(aqi, 150) || exceeds(pm25, 55.4) || exceeds(pm10, 254);
    }

    /** True when particle pollution is above the "Moderate" band and deserves a note. */
    public static boolean particleCareAdvised(Double pm25, Double pm10) {
        return exceeds(pm25, 35.4) || exceeds(pm10, 154);
    }

    private static boolean valid(Double value, double maximum) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= maximum;
    }

    private static boolean exceeds(Double value, double threshold) {
        return value != null && Double.isFinite(value) && value > threshold;
    }
}
