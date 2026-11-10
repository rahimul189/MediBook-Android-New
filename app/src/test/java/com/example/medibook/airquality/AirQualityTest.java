package com.example.medibook.airquality;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class AirQualityTest {
    private static final long FETCHED = 1_794_049_200_000L;
    private String json(String measurements) {
        return "{\"current_units\":{\"time\":\"unixtime\",\"us_aqi\":\"USAQI\",\"pm2_5\":\"μg/m³\",\"pm10\":\"μg/m³\",\"uv_index\":\"\"},"
                + "\"current\":{\"time\":1794049200," + measurements + "}}";
    }
    @Test public void actualApiShapeParsesAllFourMeasurements() throws Exception {
        AirQualityReport report = AirQualityParser.parse(json("\"us_aqi\":165,\"pm2_5\":77.5,\"pm10\":88.9,\"uv_index\":0.00"), FETCHED);
        assertEquals(165, report.usAqi, 0); assertEquals(77.5, report.pm25, 0); assertEquals(88.9, report.pm10, 0); assertEquals(0, report.uv, 0);
        assertEquals(FETCHED, report.estimatedAt); assertTrue(report.hasMeasurements());
    }
    @Test public void nullAndMissingValuesStayUnavailableNotFakeZeros() throws Exception {
        AirQualityReport report = AirQualityParser.parse(json("\"us_aqi\":null,\"pm2_5\":null"), FETCHED);
        assertNull(report.usAqi); assertNull(report.pm25); assertNull(report.pm10); assertNull(report.uv); assertFalse(report.hasMeasurements());
    }
    @Test public void partialReportStillShowsAvailableMeasurements() throws Exception {
        AirQualityReport report = AirQualityParser.parse(json("\"pm10\":12"), FETCHED);
        assertTrue(report.hasMeasurements()); assertNull(report.uv); assertEquals(12, report.pm10, 0);
    }
    @Test public void negativeStringsAndOutOfRangeMeasurementsAreUnavailable() throws Exception {
        AirQualityReport report = AirQualityParser.parse(json("\"us_aqi\":-1,\"pm2_5\":\"12\",\"pm10\":{},\"uv_index\":99"), FETCHED);
        assertFalse(report.hasMeasurements());
    }
    @Test public void zeroIsAValidValue() throws Exception { assertEquals(0, AirQualityParser.parse(json("\"us_aqi\":0"), FETCHED).usAqi, 0); }
    @Test public void malformedJsonHasExplicitFormatFailure() {
        for (String raw : new String[]{null, "", "not json", "[]", "{}", "{\"current\":null}", "{\"error\":true,\"reason\":\"bad query\"}"})
            assertEquals(AirQualityException.Kind.FORMAT, assertThrows(AirQualityException.class, () -> AirQualityParser.parse(raw, FETCHED)).kind);
    }
    @Test public void epochIsNotDoubleOffsetByDhakaTimeZone() throws Exception {
        String raw = json("\"us_aqi\":42").replace("{\"current_units\"", "{\"utc_offset_seconds\":21600,\"timezone\":\"Asia/Dhaka\",\"current_units\"");
        assertEquals(FETCHED, AirQualityParser.parse(raw, FETCHED).estimatedAt);
    }
    @Test public void malformedMissingFutureAndOverflowTimestampAreRejected() {
        for (String value : new String[]{"null", "\"1794049200\"", "-1", "1794049200.5", "99999999999999", "1794056400"}) {
            String raw = json("\"us_aqi\":1").replace("1794049200", value);
            assertThrows(AirQualityException.class, () -> AirQualityParser.parse(raw, FETCHED));
        }
    }
    @Test public void unexpectedTimeUnitsCannotBeTreatedAsEpochSeconds() {
        assertThrows(AirQualityException.class, () -> AirQualityParser.parse(json("\"us_aqi\":1").replace("unixtime", "iso8601"), FETCHED));
    }
    @Test public void cacheIsFreshOnlyForFifteenMinutesAndPlausibleClock() throws Exception {
        AirQualityReport report = AirQualityParser.parse(json("\"us_aqi\":1"), FETCHED);
        assertTrue(report.cacheFresh(FETCHED + 14 * 60_000)); assertFalse(report.cacheFresh(FETCHED + 15 * 60_000)); assertFalse(report.cacheFresh(FETCHED - 1));
    }
    @Test public void oldOrFutureEstimatesCannotBeLabelledCurrent() {
        assertTrue(new AirQualityReport(FETCHED - 4 * 60 * 60_000L, FETCHED, 1d, null, null, null).staleEstimate(FETCHED));
        assertTrue(new AirQualityReport(FETCHED + 11 * 60_000L, FETCHED, 1d, null, null, null).staleEstimate(FETCHED));
    }
    @Test public void httpErrorsAreNotParsedAsSuccessfulData() {
        AirQualityHttpClient.Response response = new AirQualityHttpClient.Response() {
            @Override public int status() { return 503; }
            @Override public InputStream body() { fail("Error body must not be used as measurements"); return null; }
        };
        assertEquals(AirQualityException.Kind.HTTP, assertThrows(AirQualityException.class, () -> AirQualityHttpClient.readResponse(response)).kind);
    }
    @Test public void boundedUtf8BodyIsClosed() throws Exception {
        boolean[] closed = {false};
        byte[] body = json("\"pm10\":12").getBytes(StandardCharsets.UTF_8);
        AirQualityHttpClient.Response response = response(new ByteArrayInputStream(body) {
            @Override public void close() throws IOException { closed[0] = true; super.close(); }
        });
        String raw = AirQualityHttpClient.readResponse(response); assertTrue(raw.contains("μg/m³")); assertTrue(closed[0]);
        assertEquals(12, AirQualityParser.parse(raw, FETCHED).pm10, 0);
    }
    @Test public void oversizedBodyFailsWithoutUnboundedAllocation() {
        assertEquals(AirQualityException.Kind.FORMAT, assertThrows(AirQualityException.class,
                () -> AirQualityHttpClient.readResponse(response(new ByteArrayInputStream(new byte[AirQualityHttpClient.MAX_BYTES + 1])))).kind);
    }
    @Test public void emptyOrNullBodyIsNotPretendSuccess() throws Exception {
        String empty = AirQualityHttpClient.readResponse(response(new ByteArrayInputStream(new byte[0])));
        assertThrows(AirQualityException.class, () -> AirQualityParser.parse(empty, FETCHED));
        assertThrows(AirQualityException.class, () -> AirQualityHttpClient.readResponse(response(null)));
    }
    @Test public void timeoutPropagatesToRepositoryErrorState() {
        assertThrows(SocketTimeoutException.class, () -> AirQualityHttpClient.readResponse(new AirQualityHttpClient.Response() {
            @Override public int status() throws IOException { throw new SocketTimeoutException(); }
            @Override public InputStream body() { return null; }
        }));
    }
    @Test public void endpointUsesTheRequestedCoordinatesAndActualFields() {
        String endpoint = AirQualityHttpClient.endpoint(22.8456, 89.5403);
        assertTrue(endpoint.startsWith("https://air-quality-api.open-meteo.com/v1/air-quality?"));
        assertTrue(endpoint.contains("latitude=22.8456&longitude=89.5403"));
        assertTrue(endpoint.contains("current=us_aqi,pm2_5,pm10,uv_index"));
        assertTrue(endpoint.contains("timeformat=unixtime")); assertFalse(endpoint.contains("apikey"));
    }
    @Test public void outOfRangeCoordinatesAreRejectedInsteadOfQueried() {
        assertThrows(IllegalArgumentException.class, () -> { AirQualityHttpClient.endpoint(91, 0); });
        assertThrows(IllegalArgumentException.class, () -> { AirQualityHttpClient.endpoint(0, 181); });
        assertThrows(IllegalArgumentException.class, () -> { AirQualityHttpClient.endpoint(Double.NaN, 0); });
    }
    @Test public void deviceCoordinatesChangeTheRequestLocation() {
        String endpoint = AirQualityHttpClient.endpoint(23.8103, 90.4125);
        assertTrue(endpoint.contains("latitude=23.8103&longitude=90.4125"));
        assertFalse(endpoint.contains("22.8456"));
    }
    private AirQualityHttpClient.Response response(InputStream body) {
        return new AirQualityHttpClient.Response() {
            @Override public int status() { return 200; }
            @Override public InputStream body() { return body; }
        };
    }
}
