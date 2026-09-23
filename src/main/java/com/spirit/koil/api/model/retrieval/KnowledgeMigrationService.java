package com.spirit.koil.api.model.retrieval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Imports the legacy associative-memory file once, retaining it until every readable row is durable. */
public final class KnowledgeMigrationService {
    private final KoilRetrievalEngine engine;
    private final Path legacyFile;

    public KnowledgeMigrationService(KoilRetrievalEngine engine, Path legacyFile) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.legacyFile = Objects.requireNonNull(legacyFile, "legacyFile").toAbsolutePath().normalize();
    }

    public MigrationReport migrate() {
        if (!Files.isRegularFile(this.legacyFile)) return new MigrationReport(0, 0, 0, List.of(), false);
        int scanned = 0;
        int created = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(this.legacyFile, StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) throw new IllegalArgumentException("legacy memory root is not an array");
            JsonArray rows = parsed.getAsJsonArray();
            for (int index = 0; index < rows.size(); index++) {
                scanned++;
                try {
                    JsonObject row = rows.get(index).getAsJsonObject();
                    String prompt = string(row, "prompt");
                    String answer = string(row, "answer");
                    long timestamp = number(row, "timestamp", System.currentTimeMillis());
                    if (prompt.isBlank() || answer.isBlank()) throw new IllegalArgumentException("prompt or answer is blank");
                    String hash = legacyHash(prompt, answer, timestamp);
                    if (this.engine.contains(new KnowledgeFilter(Set.of(KnowledgeType.CONVERSATION), Set.of(), Set.of(),
                            Map.of("legacyHash", hash), 0L)).join()) {
                        skipped++;
                        continue;
                    }
                    long id = this.engine.remember(new KnowledgeEntry(
                            this.engineId(), KnowledgeType.CONVERSATION, "model",
                            "User request:\n" + prompt.strip() + "\n\nKoil final response:\n" + answer.strip(),
                            "model.associative-memory", "", Math.max(0L, timestamp), 0.5D, 0.5D,
                            KnowledgeTrust.HISTORICAL_CONTEXT,
                            Map.of("migration", "associative-memory-v1", "legacyHash", hash))).join();
                    if (id <= 0L) throw new IllegalStateException("knowledge engine rejected migrated entry");
                    created++;
                } catch (RuntimeException exception) {
                    failures.add("row " + index + ": " + concise(exception));
                }
            }
        } catch (IOException | RuntimeException exception) {
            failures.add("read: " + concise(exception));
        }
        boolean archived = failures.isEmpty() && archive();
        if (failures.isEmpty() && !archived) failures.add("archive: unable to move legacy file");
        return new MigrationReport(scanned, created, skipped, List.copyOf(failures), archived);
    }

    private long engineId() {
        // The metadata store remains the sole ID allocator, reached through the engine's serial worker.
        return this.engine.allocateId().join();
    }

    private boolean archive() {
        Path archive = this.legacyFile.resolveSibling(this.legacyFile.getFileName() + ".migrated");
        try {
            try {
                Files.move(this.legacyFile, archive, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupported) {
                Files.move(this.legacyFile, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static String string(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : "";
    }

    private static long number(JsonObject row, String key, long fallback) {
        try {
            return row.has(key) ? row.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String legacyHash(String prompt, String answer, long timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prompt.strip().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(answer.strip().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public record MigrationReport(int scanned, int created, int skipped, List<String> failures, boolean archived) {
        public MigrationReport {
            failures = failures == null ? List.of() : List.copyOf(failures);
            if (scanned < 0 || created < 0 || skipped < 0) throw new IllegalArgumentException("migration counts must not be negative");
        }

        public boolean succeeded() {
            return this.failures.isEmpty();
        }
    }
}
