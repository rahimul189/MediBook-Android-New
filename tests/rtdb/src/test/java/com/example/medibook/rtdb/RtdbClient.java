package com.example.medibook.rtdb;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** End-user REST/SSE assertion lane. The owner bypass exists ONLY in local setup methods. */
final class RtdbClient {
    static final String PROJECT = "demo-medibook";
    static final String NS = PROJECT + "-default-rtdb";
    static final String ORIGIN = "http://127.0.0.1:9000";
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return Collections.singletonList(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();
    final String token;
    private RtdbClient(String token) { this.token = token; }
    static RtdbClient anonymous() { return new RtdbClient(null); }
    static RtdbClient patient() { return actor("patient-a", map()); }
    static RtdbClient admin() { return actor("admin-a", map("admin", true)); }
    static RtdbClient actor(String uid, Map<String, Object> claims) {
        return actorProvider(uid, claims, "custom");
    }
    static RtdbClient actorProvider(String uid, Map<String, Object> claims, String provider) {
        if (!java.util.Arrays.asList("custom", "google.com", "password").contains(provider)) throw new IllegalArgumentException("Unsupported fixture provider");
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = map("iss", "https://securetoken.google.com/" + PROJECT, "aud", PROJECT,
                "iat", now, "exp", now + 3600, "auth_time", now, "sub", uid, "user_id", uid,
                "firebase", map("identities", map(), "sign_in_provider", provider));
        for (String key : claims.keySet()) { if (payload.containsKey(key)) throw new IllegalArgumentException("Reserved token field"); }
        payload.putAll(claims);
        return new RtdbClient(b64(JSON.toJson(map("alg", "none", "typ", "JWT"))) + "." + b64(JSON.toJson(payload)) + ".");
    }
    private static String b64(String text) { return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    static Map<String, Object> map(Object... args) {
        Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < args.length; i += 2) map.put((String) args[i], args[i + 1]); return map;
    }
    static Map<String, Object> event() { return map("doctorId", "doctor-a", "dateKey", "1794160800000", "queueToken", "Q0601", "status", "Confirmed", "changedAt", 1000L); }
    static Map<String, Object> queue() { return map("status", "Confirmed", "changedAt", 1000L); }

    static final class Reply {
        final int code; final String text;
        Reply(int code, String text) { this.code = code; this.text = text; }
        JsonElement json() { return JsonParser.parseString(text); }
    }
    Reply get(String path) { return exchange("GET", uri(path, ""), null, token); }
    Reply latest(String path, int limit) { return exchange("GET", uri(path, "&orderBy=%22changedAt%22&limitToLast=" + limit), null, token); }
    Reply put(String path, Object data) { return exchange("PUT", uri(path, ""), JSON.toJson(data), token); }
    Reply patch(String path, Object data) { return exchange("PATCH", uri(path, ""), JSON.toJson(data), token); }
    Reply delete(String path) { return exchange("DELETE", uri(path, ""), null, token); }
    HttpResponse<InputStream> listen(String path) throws Exception {
        HttpRequest.Builder builder = request("GET", uri(path, "&orderBy=%22changedAt%22&limitToLast=20"), null, token);
        return HTTP.send(builder.header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
    }
    HttpResponse<InputStream> listenValue(String path) throws Exception {
        return HTTP.send(request("GET", uri(path, ""), null, token).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
    }
    static final class Versioned {
        final String etag;
        Versioned(String etag) { this.etag = etag; }
    }
    Versioned version(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(request("GET", uri(path, ""), null, token).header("X-Firebase-ETag", "true").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(response.body(), 200, response.statusCode());
        return new Versioned(response.headers().firstValue("etag").orElseThrow());
    }
    Reply compareAndSet(String path, Object value, Versioned version) throws Exception {
        HttpResponse<String> response = HTTP.send(request("PUT", uri(path, ""), JSON.toJson(value), token).header("if-match", version.etag).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }
    private static URI uri(String path, String query) {
        if (path.startsWith("/") || path.contains("..") || path.contains("?") || path.contains("#")) throw new IllegalArgumentException("Unsafe path");
        StringBuilder encoded = new StringBuilder();
        if (!path.isEmpty()) for (String part : path.split("/")) {
            if (part.isEmpty()) throw new IllegalArgumentException("Empty path");
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(URLEncoder.encode(part, StandardCharsets.UTF_8));
        }
        return URI.create(ORIGIN + "/" + encoded + ".json?ns=" + NS + query);
    }
    static void requireLocal(URI uri) {
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() != 9000
                || uri.getUserInfo() != null || uri.getFragment() != null || !uri.normalize().equals(uri)
                || uri.getRawPath() == null || !uri.getRawPath().endsWith(".json") || uri.getRawPath().contains("..")
                || uri.getRawQuery() == null || !uri.getRawQuery().matches("ns=" + NS + "(&orderBy=%22changedAt%22&limitToLast=-?[0-9]+)?"))
            throw new IllegalArgumentException("Refusing non-approved RTDB emulator target");
    }
    private static HttpRequest.Builder request(String method, URI uri, String body, String token) {
        requireLocal(uri);
        // RTDB emulator accepts synthetic unsigned end-user tokens; real credentials are never read.
        String suffix = token == null ? "" : "&auth=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create(uri + suffix)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    }
    private static Reply exchange(String method, URI uri, String body, String token) {
        if ("owner".equals(token)) throw new AssertionError("Owner bypass forbidden in assertions");
        try {
            HttpResponse<String> response = HTTP.send(request(method, uri, body, token).build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (IOException error) { throw new AssertionError("RTDB transport failure, not a Rules denial", error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void control(String method, String path, String body) {
        URI uri = uri(path, ""); requireLocal(uri);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer owner")
                    .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new AssertionError("Local RTDB setup failed: " + response.body());
        } catch (Exception error) { throw new AssertionError(error); }
    }
    static void installRules(String json) { control("PUT", ".settings/rules", json); }
    static void clearFixtures() { control("DELETE", "", null); }
    static void seed(String path, Object data) { control("PUT", path, JSON.toJson(data)); }
    static JsonElement allowed(Reply reply) { assertEquals(reply.text, 200, reply.code); return reply.json(); }
    static void denied(Reply reply) {
        assertTrue("Not a Rules denial: " + reply.code + " " + reply.text, reply.code == 401 || reply.code == 403);
        assertTrue(reply.text, reply.text.toLowerCase().contains("permission denied"));
    }
}
