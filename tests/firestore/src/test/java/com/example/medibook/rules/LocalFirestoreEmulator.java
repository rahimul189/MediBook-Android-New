package com.example.medibook.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.rules.ExternalResource;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Owns one pinned Java emulator process. Never attaches to or clears an already-running emulator. */
public final class LocalFirestoreEmulator extends ExternalResource {
    private static final String JAR = "cloud-firestore-emulator-v1.22.0.jar";
    private static final String JAR_SHA256 = "9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c";
    private Process process;
    private Thread shutdownHook;
    private Path rules;
    private String rulesHash;
    private Path reports;

    @Override protected void before() throws Throwable {
        try {
            if (Runtime.version().feature() < 21) throw new IllegalStateException("Emulator tests require Java 21+");
            String host = System.getenv("FIRESTORE_EMULATOR_HOST");
            if (host != null && !host.isEmpty() && !host.equals("127.0.0.1:8080")) {
                throw new IllegalStateException("Refusing a non-approved FIRESTORE_EMULATOR_HOST");
            }
            Path root = Path.of(System.getProperty("medibook.projectRoot"));
            JsonObject configuration = JsonParser.parseString(Files.readString(root.resolve("firebase.emulators.json"))).getAsJsonObject();
            JsonObject emulators = configuration.getAsJsonObject("emulators");
            JsonObject firestore = emulators.getAsJsonObject("firestore");
            if (!"firestore.rules".equals(configuration.getAsJsonObject("firestore").get("rules").getAsString())
                    || !RulesClient.HOST.equals(firestore.get("host").getAsString()) || firestore.get("port").getAsInt() != RulesClient.PORT
                    || !emulators.get("singleProjectMode").getAsBoolean() || emulators.getAsJsonObject("ui").get("enabled").getAsBoolean()) {
                throw new IllegalStateException("Emulator configuration differs from the approved isolated configuration");
            }
            rules = root.resolve("firestore.rules");
            rulesHash = sha256(rules);
            reports = Path.of(System.getProperty("medibook.rulesTestBuild")).resolve("emulator");
            Files.createDirectories(reports);
            String suppliedJar = System.getenv("MEDIBOOK_FIRESTORE_EMULATOR_JAR");
            Path jar = suppliedJar == null || suppliedJar.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".cache", "firebase", "emulators", JAR) : Path.of(suppliedJar);
            if (!Files.isRegularFile(jar)) throw new IllegalStateException("Missing pinned emulator JAR; see tests/firestore/README.md: " + jar);
            if (!sha256(jar).equals(JAR_SHA256)) throw new IllegalStateException("Emulator JAR checksum mismatch");
            try (ServerSocket probe = new ServerSocket()) {
                probe.bind(new InetSocketAddress(RulesClient.HOST, RulesClient.PORT));
            }
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Duser.language=en", "-jar", jar.toString(),
                    "--host", RulesClient.HOST, "--port", Integer.toString(RulesClient.PORT),
                    "--project_id", RulesClient.PROJECT, "--single_project_mode", "true", "--rules", rules.toString());
            builder.directory(reports.toFile()).redirectErrorStream(true).redirectOutput(reports.resolve("firestore-emulator.log").toFile());
            // No credentials are needed or forwarded to the child server.
            builder.environment().remove("GOOGLE_APPLICATION_CREDENTIALS");
            builder.environment().put("GCLOUD_PROJECT", RulesClient.PROJECT);
            builder.environment().put("GOOGLE_CLOUD_PROJECT", RulesClient.PROJECT);
            process = builder.start();
            shutdownHook = new Thread(this::stop, "medibook-owned-emulator-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IllegalStateException("Emulator exited; inspect " + reports.resolve("firestore-emulator.log"));
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(RulesClient.HOST, RulesClient.PORT), 200);
                    return;
                } catch (java.io.IOException notReady) { Thread.sleep(100); }
            }
            throw new IllegalStateException("Timed out starting owned emulator; inspect " + reports);
        } catch (Throwable failure) { stop(); throw failure; }
    }

    @Override protected void after() {
        try {
            if (process != null && process.isAlive()) RulesClient.exportCoverage(reports.resolve("rule-coverage.json"));
            if (!sha256(rules).equals(rulesHash)) throw new AssertionError("Local firestore.rules changed during tests");
        } catch (Exception error) { throw new AssertionError("Could not verify rules/coverage", error); }
        finally {
            stop();
            if (shutdownHook != null) Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
    }

    private synchronized void stop() {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }
}
