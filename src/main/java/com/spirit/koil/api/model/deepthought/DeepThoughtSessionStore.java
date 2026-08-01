package com.spirit.koil.api.model.deepthought;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

public final class DeepThoughtSessionStore {
    private static final Path ROOT = Path.of("koil/sys/model/deep-thought");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAXIMUM_SESSION_BYTES = 64L * 1024L * 1024L;

    private DeepThoughtSessionStore() {}

    public static void save(String scope, DeepThoughtSession session) throws IOException {
        Path directory = directory(scope);
        Files.createDirectories(directory);
        Path target = directory.resolve(session.deepThoughtSessionId + ".json");
        Path temporary = Files.createTempFile(directory, ".deep-thought-", ".tmp");
        byte[] bytes = GSON.toJson(session.toJson()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_SESSION_BYTES) throw new IOException("Deep Thought checkpoint exceeds 64 mb.");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static List<DeepThoughtSession> load(String scope) {
        Path directory = directory(scope);
        if (!Files.isDirectory(directory)) return List.of();
        List<DeepThoughtSession> sessions = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(value -> value.getFileName().toString().endsWith(".json")).limit(64).toList()) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    sessions.add(DeepThoughtSession.fromJson(root));
                } catch (Exception corrupt) {
                    try { Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING); }
                    catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return List.copyOf(sessions);
    }

    public static DeepThoughtSession newestRestorable(String scope) {
        return load(scope).stream()
                .filter(session -> session.lifecycle != DeepThoughtSession.Lifecycle.CANCELLED)
                .filter(session -> session.lifecycle != DeepThoughtSession.Lifecycle.COMPLETED
                        || session.finalPresentedAtMillis <= 0L)
                .max(Comparator.comparingLong(session -> session.updatedAtMillis))
                .orElse(null);
    }

    public static void markFinalPresented(String scope, DeepThoughtSession session) {
        if (session == null || session.finalPresentedAtMillis > 0L) return;
        long previousUpdated = session.updatedAtMillis;
        session.finalPresentedAtMillis = System.currentTimeMillis();
        session.updatedAtMillis = Math.max(session.updatedAtMillis, session.finalPresentedAtMillis);
        try {
            save(scope, session);
        } catch (IOException ignored) {
            session.finalPresentedAtMillis = 0L;
            session.updatedAtMillis = previousUpdated;
        }
    }

    public static boolean delete(String scope, String sessionId) {
        try { return Files.deleteIfExists(directory(scope).resolve(safe(sessionId) + ".json")); }
        catch (IOException ignored) { return false; }
    }

    private static Path directory(String scope) { return ROOT.resolve(safe(scope).isBlank() ? "global" : safe(scope)); }
    private static String safe(String value) { return value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "_"); }
}
