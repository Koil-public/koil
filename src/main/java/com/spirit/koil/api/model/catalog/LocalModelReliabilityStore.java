package com.spirit.koil.api.model.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent per-model evidence used to prevent repeated catastrophic Automation failures. */
public final class LocalModelReliabilityStore {
    public static final Path PATH = Path.of("koil", "sys", "model", "runtime-reliability.json");
    private static final int PROTOCOL_FAILURE_QUARANTINE_THRESHOLD = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static boolean loaded;

    private LocalModelReliabilityStore() {
    }

    public static synchronized void recordCrash(String modelKey, String detail) {
        record(modelKey, "runtime_crash", detail, true);
    }

    public static synchronized void recordProtocolFailure(String modelKey, String code, String detail) {
        record(modelKey, code, detail, false);
    }

    public static synchronized boolean quarantined(LocalModelCatalogEntry entry) {
        if (entry == null) return false;
        return snapshot(entry.modelId()).quarantined() || snapshot(entry.id()).quarantined();
    }

    public static synchronized Snapshot snapshot(String modelKey) {
        load();
        Entry entry = ENTRIES.get(normalize(modelKey));
        return entry == null
                ? new Snapshot(false, 0, 0, "", "", "")
                : new Snapshot(entry.quarantined, entry.crashCount, entry.protocolFailureCount,
                        entry.lastCode, entry.lastDetail, entry.updatedAt);
    }

    public static synchronized boolean reset(String modelKey) {
        load();
        boolean removed = ENTRIES.remove(normalize(modelKey)) != null;
        if (removed) persist();
        return removed;
    }

    public static synchronized boolean reset(LocalModelCatalogEntry entry) {
        if (entry == null) return false;
        boolean first = reset(entry.id());
        boolean second = reset(entry.modelId());
        return first || second;
    }

    private static void record(String modelKey, String code, String detail, boolean crash) {
        load();
        String key = normalize(modelKey);
        if (key.isBlank()) return;
        Entry previous = ENTRIES.getOrDefault(key, new Entry());
        Entry next = new Entry();
        next.crashCount = previous.crashCount + (crash ? 1 : 0);
        next.protocolFailureCount = previous.protocolFailureCount + (crash ? 0 : 1);
        next.quarantined = previous.quarantined || next.crashCount > 0
                || next.protocolFailureCount >= PROTOCOL_FAILURE_QUARANTINE_THRESHOLD;
        next.lastCode = clean(code);
        next.lastDetail = clean(detail);
        next.updatedAt = Instant.now().toString();
        ENTRIES.put(key, next);
        persist();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(PATH)) return;
        try {
            State state = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), State.class);
            if (state != null && state.entries != null) ENTRIES.putAll(state.entries);
        } catch (Exception ignored) {
            // A corrupt reliability file must not prevent Koil/model startup.
        }
    }

    private static void persist() {
        try {
            Path parent = PATH.toAbsolutePath().normalize().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "model-reliability-", ".tmp");
            Files.writeString(temporary, GSON.toJson(new State(ENTRIES)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // In-memory protection remains active when persistence is unavailable.
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String clean(String value) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
        return clean.length() <= 500 ? clean : clean.substring(0, 499) + "…";
    }

    public record Snapshot(
            boolean quarantined,
            int crashCount,
            int protocolFailureCount,
            String lastCode,
            String lastDetail,
            String updatedAt
    ) {
    }

    private static final class Entry {
        private boolean quarantined;
        private int crashCount;
        private int protocolFailureCount;
        private String lastCode = "";
        private String lastDetail = "";
        private String updatedAt = "";
    }

    private record State(Map<String, Entry> entries) {
        private State {
            entries = entries == null ? Map.of() : Map.copyOf(entries);
        }
    }
}
