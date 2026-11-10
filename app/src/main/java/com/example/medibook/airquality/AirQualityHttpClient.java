package com.example.medibook.airquality;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** A real HTTPS GET, used only on the repository executor. No key and no stored coordinates. */
public final class AirQualityHttpClient {
    public static final String BASE_ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality";
    /** Khulna, used only for the explicitly labelled fallback. */
    public static final double FALLBACK_LATITUDE = 22.8456, FALLBACK_LONGITUDE = 89.5403;
    public static final int MAX_BYTES = 256 * 1024;

    public interface Response {
        int status() throws IOException;
        InputStream body() throws IOException;
    }

    /** Coordinate-bounded request; the rounded value is a request parameter only, never persisted. */
    public static String endpoint(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        return BASE_ENDPOINT + String.format(Locale.US,
                "?latitude=%.4f&longitude=%.4f&current=us_aqi,pm2_5,pm10,uv_index&timezone=auto&timeformat=unixtime",
                latitude, longitude);
    }

    /** Shared bounded-body and HTTP validation path is also exercised with fake responses in tests. */
    public static String readResponse(Response response) throws IOException {
        if (response.status() != 200) throw new AirQualityException(AirQualityException.Kind.HTTP);
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new AirQualityException(AirQualityException.Kind.FORMAT);
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new AirQualityException(AirQualityException.Kind.FORMAT);
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public String fetch(double latitude, double longitude) throws AirQualityException {
        final String address;
        try {
            address = endpoint(latitude, longitude);
        } catch (IllegalArgumentException invalid) {
            throw new AirQualityException(AirQualityException.Kind.FORMAT);
        }
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET"); connection.setConnectTimeout(10_000); connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept", "application/json");
            HttpsURLConnection active = connection;
            return readResponse(new Response() {
                @Override public int status() throws IOException { return active.getResponseCode(); }
                @Override public InputStream body() throws IOException { return active.getInputStream(); }
            });
        } catch (AirQualityException failure) { throw failure; }
        catch (IOException | RuntimeException network) { throw new AirQualityException(AirQualityException.Kind.NETWORK); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
