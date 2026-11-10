package com.example.medibook.rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Official Firestore REST v1 client, NOT the Java server/Admin SDK. ID-token requests evaluate Rules. */
final class RulesClient {
    static final String PROJECT = "demo-medibook";
    static final String HOST = "127.0.0.1";
    static final int PORT = 8080;
    static final String DATABASE = "projects/" + PROJECT + "/databases/(default)";
    static final String DOCUMENTS = DATABASE + "/documents";
    static final String ORIGIN = "http://" + HOST + ":" + PORT;
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    enum Special { SERVER_TIME, DELETE_FIELD }
    static final class Time {
        final String value;
        Time(String value) { this.value = value; }
    }
    static final Time OLD_TIME = new Time("1970-01-01T00:00:01Z");
    static final Time ZERO_TIME = new Time("1970-01-01T00:00:00Z");

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return Collections.singletonList(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();

    static final class Reply {
        final int code;
        final JsonElement body;
        final String operation;
        private Reply(int code, String body, String operation) {
            this.code = code;
            this.body = body.isBlank() ? new JsonObject() : JsonParser.parseString(body);
            this.operation = operation;
        }
        String status() {
            if (!body.isJsonObject() || !body.getAsJsonObject().has("error")) return "";
            return body.getAsJsonObject().getAsJsonObject("error").get("status").getAsString();
        }
    }

    private final String actor;
    private final String idToken; // Synthetic, unsigned emulator token only. Never a real account/token.
    private RulesClient(String actor, String idToken) { this.actor = actor; this.idToken = idToken; }

    static RulesClient anonymous() { return new RulesClient("anonymous", null); }
    static RulesClient admin() { return authenticated("admin-a", map("admin", true)); }
    static RulesClient patient() { return authenticated("patient-a", map("email", "patient@example.invalid")); }
    static RulesClient authenticated(String uid, Map<String, Object> claims) {
        return authenticatedProvider(uid, claims, "custom");
    }
    static RulesClient authenticatedProvider(String uid, Map<String, Object> claims, String provider) {
        if (!java.util.Arrays.asList("custom", "google.com", "password").contains(provider)) throw new IllegalArgumentException("Unsupported fixture provider");
        long now = Instant.now().getEpochSecond();
        Map<String, Object> payload = map("iss", "https://securetoken.google.com/" + PROJECT,
                "aud", PROJECT, "iat", now, "exp", now + 3600, "auth_time", now,
                "sub", uid, "user_id", uid, "firebase", map("identities", map(), "sign_in_provider", provider));
        for (String key : claims.keySet()) {
            if (payload.containsKey(key)) throw new IllegalArgumentException("Reserved mock token field");
        }
        payload.putAll(claims);
        String token = base64(JSON.toJson(map("alg", "none", "typ", "JWT"))) + "." + base64(JSON.toJson(payload)) + ".";
        return new RulesClient(uid, token);
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    Reply get(String path) { return request("GET", "/v1/" + DOCUMENTS + "/" + encodedPath(path), null); }
    Reply delete(String path) { return request("DELETE", "/v1/" + DOCUMENTS + "/" + encodedPath(path), null); }
    Reply set(String path, Map<String, Object> data) { return commit(write(path, data, false)); }
    Reply update(String path, Map<String, Object> patch) { return commit(write(path, patch, true)); }
    Reply commit(JsonObject... writes) { return request("POST", "/v1/" + DOCUMENTS + ":commit", commitBody(writes)); }
    Reply beginTransaction() {
        return request("POST", "/v1/" + DOCUMENTS + ":beginTransaction", object("options", object("readWrite", object())));
    }
    Reply transactionRead(String transaction, String... paths) {
        JsonArray documents = new JsonArray();
        for (String path : paths) documents.add(DOCUMENTS + "/" + path);
        return request("POST", "/v1/" + DOCUMENTS + ":batchGet", object("documents", documents, "transaction", transaction));
    }
    Reply transactionCommit(String transaction, JsonObject... writes) {
        JsonObject body = commitBody(writes); body.addProperty("transaction", transaction);
        return request("POST", "/v1/" + DOCUMENTS + ":commit", body);
    }
    Reply query(String parent, String collection, Boolean active, boolean collectionGroup) {
        JsonObject from = object("collectionId", collection);
        if (collectionGroup) from.addProperty("allDescendants", true);
        JsonObject query = new JsonObject();
        JsonArray sources = new JsonArray(); sources.add(from); query.add("from", sources);
        if (active != null) query.add("where", object("fieldFilter", object("field", object("fieldPath", "active"),
                "op", "EQUAL", "value", value(active))));
        return request("POST", "/v1/" + DOCUMENTS + (parent.isEmpty() ? "" : "/" + encodedPath(parent)) + ":runQuery",
                object("structuredQuery", query));
    }
    Reply query(String collection, Boolean active) { return query("", collection, active, false); }

    /** Even a role-filtered patient directory must be denied: profile access is get-only. */
    Reply queryPatientProfiles() {
        JsonArray from = new JsonArray(); from.add(object("collectionId", "users"));
        return request("POST", "/v1/" + DOCUMENTS + ":runQuery", object("structuredQuery", object("from", from,
                "where", object("fieldFilter", object("field", object("fieldPath", "role"),
                        "op", "EQUAL", "value", value("Patient"))))));
    }

    /** Top-level appointment query; a Patient must pass its own uid, Admin may pass null. */
    Reply queryAppointments(String patientUid) {
        JsonArray from = new JsonArray(); from.add(object("collectionId", "appointments"));
        JsonObject query = object("from", from);
        if (patientUid != null) {
            query.add("where", object("fieldFilter", object("field", object("fieldPath", "patientUid"),
                    "op", "EQUAL", "value", value(patientUid))));
        }
        return request("POST", "/v1/" + DOCUMENTS + ":runQuery", object("structuredQuery", query));
    }

    Reply upcomingAppointments(String uid, Time after) {
        JsonArray filters = new JsonArray();
        filters.add(object("fieldFilter", object("field", object("fieldPath", "patientUid"), "op", "EQUAL", "value", value(uid))));
        filters.add(object("fieldFilter", object("field", object("fieldPath", "status"), "op", "EQUAL", "value", value("Confirmed"))));
        filters.add(object("fieldFilter", object("field", object("fieldPath", "startAt"), "op", "GREATER_THAN", "value", value(after))));
        JsonArray from = new JsonArray(); from.add(object("collectionId", "appointments"));
        JsonArray order = new JsonArray(); order.add(object("field", object("fieldPath", "startAt"), "direction", "ASCENDING"));
        return request("POST", "/v1/" + DOCUMENTS + ":runQuery", object("structuredQuery", object("from", from,
                "where", object("compositeFilter", object("op", "AND", "filters", filters)), "orderBy", order, "limit", 20)));
    }

    Reply scheduledWeek(Time start, Time end) {
        JsonArray filters = new JsonArray();
        filters.add(object("fieldFilter", object("field", object("fieldPath", "appointmentDate"), "op", "GREATER_THAN_OR_EQUAL", "value", value(start))));
        filters.add(object("fieldFilter", object("field", object("fieldPath", "appointmentDate"), "op", "LESS_THAN", "value", value(end))));
        JsonArray from = new JsonArray(); from.add(object("collectionId", "appointments"));
        return request("POST", "/v1/" + DOCUMENTS + ":runQuery", object("structuredQuery", object("from", from,
                "where", object("compositeFilter", object("op", "AND", "filters", filters)))));
    }

    /** Server-side shape of orderBy(updatedAt).limitToLast(20), used by the Admin live bridge. */
    Reply recentChangedAppointments() {
        JsonArray from = new JsonArray(); from.add(object("collectionId", "appointments"));
        JsonArray order = new JsonArray(); order.add(object("field", object("fieldPath", "updatedAt"), "direction", "DESCENDING"));
        return request("POST", "/v1/" + DOCUMENTS + ":runQuery",
                object("structuredQuery", object("from", from, "orderBy", order, "limit", 20)));
    }

    /** Exact Patient profile query shape: active=true AND document path identity. */
    Reply activeDoctorById(String id) {
        JsonArray filters = new JsonArray();
        filters.add(object("fieldFilter", object("field", object("fieldPath", "active"), "op", "EQUAL", "value", value(true))));
        filters.add(object("fieldFilter", object("field", object("fieldPath", "__name__"), "op", "EQUAL",
                "value", object("referenceValue", DOCUMENTS + "/doctors/" + id))));
        JsonArray from = new JsonArray(); from.add(object("collectionId", "doctors"));
        return request("POST", "/v1/" + DOCUMENTS + ":runQuery", object("structuredQuery", object("from", from,
                "where", object("compositeFilter", object("op", "AND", "filters", filters)))));
    }

    private Reply request(String method, String path, JsonElement body) {
        // This assertion lane cannot select the emulator owner credential, OAuth or ADC.
        if ("owner".equals(idToken)) throw new AssertionError("Fixture bypass is forbidden in client assertions");
        return exchange(method, path, body, idToken, actor);
    }

    private static Reply exchange(String method, String path, JsonElement body, String token, String actor) {
        URI uri = URI.create(ORIGIN + path);
        requireLocal(uri);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.toJson(body), StandardCharsets.UTF_8));
        try {
            // The pinned emulator rounds request.time to milliseconds. Keep sequential test
            // commits out of the same tick without weakening production's strict version rules.
            if (path.endsWith(":commit")) Thread.sleep(3);
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(), actor + " " + method + " " + path);
        } catch (IOException error) {
            throw new AssertionError("Emulator request failed (not a Rules denial): " + method + " " + path, error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Emulator request interrupted", interrupted);
        }
    }

    static void requireLocal(URI uri) {
        String path = uri.getRawPath();
        if (path == null) throw new IllegalArgumentException("Missing emulator request path");
        String documents = "/v1/" + DOCUMENTS;
        boolean clientPath = path.equals(documents) || path.startsWith(documents + "/")
                || path.equals(documents + ":commit") || path.equals(documents + ":runQuery")
                || path.equals(documents + ":beginTransaction") || path.equals(documents + ":batchGet");
        if (!"http".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getPort() != PORT
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.normalize().equals(uri)
                || !(clientPath || path.equals("/emulator/v1/projects/" + PROJECT + "/databases/(default)/documents")
                || path.equals("/emulator/v1/projects/" + PROJECT + ":ruleCoverage"))) {
            throw new IllegalArgumentException("Refusing a non-approved emulator target");
        }
    }

    static String encodedPath(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Invalid document path");
            segments.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", segments);
    }

    // Privileged emulator control/setup ONLY. Returns no Reply, so it cannot satisfy an allow/deny assertion.
    static void clearFixtures() {
        Reply reply = exchange("DELETE", "/emulator/v1/projects/" + PROJECT + "/databases/(default)/documents", null, "owner", "fixture setup");
        if (reply.code != 200) throw new AssertionError("Fixture clear failed: " + reply.code);
    }
    static void seed(String path, Map<String, Object> data) {
        Reply reply = exchange("POST", "/v1/" + DOCUMENTS + ":commit", commitBody(write(path, data, false)), "owner", "fixture setup");
        if (reply.code != 200) throw new AssertionError("Fixture seed failed: " + reply.code + " " + reply.body);
    }
    /** Streams the potentially large coverage report without parsing it into memory. */
    static void exportCoverage(Path report) {
        URI uri = URI.create(ORIGIN + "/emulator/v1/projects/" + PROJECT + ":ruleCoverage");
        requireLocal(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new AssertionError("Emulator coverage report failed: " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                Files.copy(body, report, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new AssertionError("Emulator coverage request failed", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Emulator coverage request interrupted", interrupted);
        }
    }

    static JsonObject write(String path, Map<String, Object> data, boolean patch) {
        JsonObject fields = new JsonObject();
        JsonArray mask = new JsonArray();
        JsonArray transforms = new JsonArray();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            mask.add(entry.getKey());
            if (entry.getValue() == Special.SERVER_TIME) transforms.add(object("fieldPath", entry.getKey(), "setToServerValue", "REQUEST_TIME"));
            else if (entry.getValue() != Special.DELETE_FIELD) fields.add(entry.getKey(), value(entry.getValue()));
        }
        JsonObject write = object("update", object("name", DOCUMENTS + "/" + path, "fields", fields));
        if (patch) {
            write.add("updateMask", object("fieldPaths", mask));
            write.add("currentDocument", object("exists", true));
        }
        if (!transforms.isEmpty()) write.add("updateTransforms", transforms);
        return write;
    }
    static JsonObject deleteWrite(String path) { return object("delete", DOCUMENTS + "/" + path); }
    private static JsonObject commitBody(JsonObject... writes) {
        JsonArray array = new JsonArray(); for (JsonObject write : writes) array.add(write);
        return object("writes", array);
    }

    static JsonObject value(Object value) {
        if (value == null) return object("nullValue", null);
        if (value instanceof Time) return object("timestampValue", ((Time) value).value);
        if (value instanceof Boolean) return object("booleanValue", value);
        if (value instanceof Integer || value instanceof Long) return object("integerValue", value.toString());
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            return object("doubleValue", Double.isFinite(number) ? value : Double.isNaN(number) ? "NaN" : number > 0 ? "Infinity" : "-Infinity");
        }
        if (value instanceof String) return object("stringValue", value);
        if (value instanceof Map) {
            JsonObject fields = new JsonObject();
            ((Map<?, ?>) value).forEach((key, field) -> fields.add(key.toString(), value(field)));
            return object("mapValue", object("fields", fields));
        }
        throw new IllegalArgumentException("Unsupported test fixture value");
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put((String) pairs[index], pairs[index + 1]);
        return values;
    }
    static Map<String, Object> merge(Map<String, Object> original, Map<String, Object> patch) {
        Map<String, Object> result = new LinkedHashMap<>(original); result.putAll(patch); return result;
    }
    static JsonObject object(Object... pairs) { return JSON.toJsonTree(map(pairs)).getAsJsonObject(); }

    static JsonElement allowed(Reply reply) {
        assertEquals(reply.operation + ": " + reply.body, 200, reply.code);
        return reply.body;
    }
    static void denied(Reply reply) {
        assertEquals(reply.operation + ": " + reply.body, 403, reply.code);
        assertEquals(reply.operation, "PERMISSION_DENIED", reply.status());
    }
    static void missing(Reply reply) {
        assertEquals(reply.operation + ": " + reply.body, 404, reply.code);
        assertEquals("NOT_FOUND", reply.status());
    }
    static JsonObject fields(Reply reply) { return allowed(reply).getAsJsonObject().getAsJsonObject("fields"); }
    static List<JsonObject> documents(Reply reply) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : allowed(reply).getAsJsonArray()) {
            if (item.getAsJsonObject().has("document")) result.add(item.getAsJsonObject().getAsJsonObject("document"));
        }
        return result;
    }
    static long integer(JsonObject fields, String name) { return fields.getAsJsonObject(name).get("integerValue").getAsLong(); }
    static boolean bool(JsonObject fields, String name) { return fields.getAsJsonObject(name).get("booleanValue").getAsBoolean(); }
    static String text(JsonObject fields, String name) { return fields.getAsJsonObject(name).get("stringValue").getAsString(); }
    static boolean nullField(JsonObject fields, String name) {
        JsonElement value = fields.get(name);
        return value != null && value.isJsonObject()
                && value.getAsJsonObject().has("nullValue");
    }
    static Instant time(JsonObject fields, String name) { return Instant.parse(fields.getAsJsonObject(name).get("timestampValue").getAsString()); }
}
