package com.example.medibook.rtdb;

import org.junit.rules.ExternalResource;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Starts and stops ONLY our own loopback process. No production project or credential is used. */
public final class LocalDatabaseEmulator extends ExternalResource {
    private Process process;
    private Thread shutdown;
    private Path rules;
    private String ruleHash;
    @Override protected void before() throws Throwable {
        try {
            String configured = System.getenv("FIREBASE_DATABASE_EMULATOR_HOST");
            if (configured != null && !configured.isBlank() && !configured.equals("127.0.0.1:9000"))
                throw new IllegalStateException("Refusing a different database emulator target");
            rules = Path.of(System.getProperty("medibook.projectRoot"), "database.rules.json");
            ruleHash = hash(rules);
            Path jar = Path.of(System.getProperty("user.home"), ".cache", "firebase", "emulators", "firebase-database-emulator-v4.11.2.jar");
            if (!Files.isRegularFile(jar) || !hash(jar).equals("b70d99344caf17c98b6f910fa8f6edf32a7c016cb1035e8915f70d38901eb97f"))
                throw new IllegalStateException("Missing or invalid pinned RTDB emulator JAR; see tests/rtdb/README.md");
            try (ServerSocket probe = new ServerSocket()) { probe.bind(new InetSocketAddress("127.0.0.1", 9000)); }
            Path logs = Path.of(System.getProperty("medibook.rtdbTestBuild"), "emulator"); Files.createDirectories(logs);
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Duser.language=en", "-jar", jar.toString(), "--host", "127.0.0.1", "--port", "9000");
            builder.directory(logs.toFile()).redirectErrorStream(true).redirectOutput(logs.resolve("database-emulator.log").toFile());
            builder.environment().remove("GOOGLE_APPLICATION_CREDENTIALS");
            builder.environment().put("GCLOUD_PROJECT", RtdbClient.PROJECT);
            builder.environment().put("GOOGLE_CLOUD_PROJECT", RtdbClient.PROJECT);
            process = builder.start(); shutdown = new Thread(this::stop, "medibook-owned-rtdb-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IllegalStateException("RTDB emulator exited; inspect " + logs);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", 9000), 200);
                    RtdbClient.installRules(Files.readString(rules)); return;
                } catch (java.io.IOException starting) { Thread.sleep(100); }
            }
            throw new IllegalStateException("RTDB emulator start timed out");
        } catch (Throwable error) { stop(); throw error; }
    }
    @Override protected void after() {
        try { if (!hash(rules).equals(ruleHash)) throw new AssertionError("RTDB rules changed during tests"); }
        catch (Exception error) { throw new AssertionError(error); }
        finally { stop(); if (shutdown != null) Runtime.getRuntime().removeShutdownHook(shutdown); }
    }
    private synchronized void stop() {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    private static String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) != -1) digest.update(buf, 0, n); }
        StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255)); return hex.toString();
    }
}
