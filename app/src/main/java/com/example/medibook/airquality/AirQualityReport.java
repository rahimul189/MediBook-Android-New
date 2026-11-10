package com.example.medibook.airquality;

/** Public, city-level CAMS estimates. Null means unavailable, NEVER zero. */
public final class AirQualityReport {
    public final long estimatedAt, fetchedAt;
    public final Double usAqi, pm25, pm10, uv;
    public AirQualityReport(long estimatedAt, long fetchedAt, Double usAqi, Double pm25, Double pm10, Double uv) {
        this.estimatedAt = estimatedAt; this.fetchedAt = fetchedAt; this.usAqi = usAqi; this.pm25 = pm25; this.pm10 = pm10; this.uv = uv;
    }
    public boolean hasMeasurements() { return usAqi != null || pm25 != null || pm10 != null || uv != null; }
    public boolean cacheFresh(long now) { return fetchedAt <= now && now - fetchedAt < 15 * 60_000L && !staleEstimate(now); }
    public boolean staleEstimate(long now) { return estimatedAt > now + 10 * 60_000L || now - estimatedAt > 3 * 60 * 60_000L; }
}
