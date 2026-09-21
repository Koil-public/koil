package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.StreamingModelRequest;
import com.spirit.koil.api.model.cache.ModelCacheBudget;
import com.spirit.koil.api.model.cache.ModelFileFingerprint;
import com.spirit.koil.api.model.cache.ModelPromptCacheIdentity;
import com.spirit.koil.api.model.install.LlamaCppRuntimeCatalog;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns llama.cpp KV slots for Koil. Slot state is never shared implicitly:
 * every request is bound to one slot, one logical session, and one compatibility
 * identity. Persistent snapshots are restored only after exact compatibility.
 */
final class LlamaCppKvCacheManager {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String MANIFEST_SUFFIX = ".json";
    private static final String SNAPSHOT_SUFFIX = ".bin";

    private final LlamaCppConfiguration configuration;
    private final OkHttpClient http;
    private final ReentrantLock[] slotLocks;
    private final Map<Integer, SlotOwner> owners = new HashMap<>();
    private final ModelCacheBudget budget;
    private volatile boolean persistenceAvailable = true;
    private volatile String modelFingerprint = "";

    LlamaCppKvCacheManager(LlamaCppConfiguration configuration, OkHttpClient http) {
        this.configuration = configuration;
        this.http = http;
        this.slotLocks = new ReentrantLock[Math.max(1, configuration.kvSlots())];
        for (int i = 0; i < this.slotLocks.length; i++) this.slotLocks[i] = new ReentrantLock(true);
        this.budget = configuration.cacheBudget();
    }

    void initialize() {
        try {
            Files.createDirectories(this.configuration.slotSavePath());
        } catch (IOException exception) {
            this.persistenceAvailable = false;
            LocalModelRuntimeLog.write("llama_cache_persistence_disabled", "cannot create slot cache directory: " + exception.getMessage());
        }
        this.modelFingerprint = this.configuration.modelFingerprintHint().isBlank()
                ? ModelFileFingerprint.sha256(this.configuration.modelFile(), this.configuration.slotSavePath())
                : this.configuration.modelFingerprintHint();
        LocalModelRuntimeLog.write(
                "llama_cache_initialized",
                "profile=" + this.budget.profile().name().toLowerCase(java.util.Locale.ROOT)
                        + " | slots=" + this.slotLocks.length
                        + " | snapshot_budget_bytes=" + this.budget.diskSnapshotBudgetBytes()
                        + " | model_sha256=" + abbreviate(this.modelFingerprint)
        );
    }

    Lease acquire(StreamingModelRequest request, int port) {
        int slot = slot(request);
        ReentrantLock lock = this.slotLocks[slot];
        lock.lock();
        String session = sessionKey(request);
        String compatibility = compatibility(request);
        try {
            SlotOwner current;
            synchronized (this.owners) {
                current = this.owners.get(slot);
            }

            // The live llama.cpp slot is the latency-critical cache. Keep the
            // same conversation on the same slot even when Koil changes the
            // selected tool schemas or dynamic prompt suffix. cache_prompt=true
            // lets llama.cpp retain the actual longest common token prefix and
            // re-evaluate only what changed. Erasing/restoring on every stable
            // fingerprint change destroys that optimization.
            if (current != null && current.sessionKey.equals(session)) {
                boolean exact = current.compatibility.equals(compatibility);
                current.compatibility = compatibility;
                LocalModelRuntimeLog.write(
                        "llama_cache_hit",
                        "request=" + request.id() + " | slot=" + slot
                                + " | source=" + (exact ? "active_slot" : "active_slot_prefix_reuse")
                );
                return new Lease(slot, session, compatibility, lock, false);
            }

            List<String> acceptedSeeds = seedFingerprints(request);
            if (current != null && !current.seedFingerprint.isBlank()
                    && acceptedSeeds.contains(current.seedFingerprint)) {
                current.sessionKey = session;
                current.compatibility = compatibility;
                current.seedFingerprint = "";
                current.seedBranch = "";
                LocalModelRuntimeLog.write(
                        "llama_cache_hit",
                        "request=" + request.id() + " | slot=" + slot
                                + " | source=startup_seed_active"
                );
                return new Lease(slot, session, compatibility, lock, false);
            }

            // Never serialize/restore large KV snapshots on the user request
            // critical path. Those operations can be hundreds of MiB or more on
            // long contexts and previously used an unbounded HTTP read timeout.
            // A mismatched live slot is still safe: llama.cpp's cache_prompt
            // machinery compares the new token prefix and rewrites the suffix.
            // Persistent snapshots remain a best-effort shutdown/startup
            // optimization, not a prerequisite for generation.
            if (current != null && current.dirty) {
                LocalModelRuntimeLog.write(
                        "llama_cache_checkpoint_deferred",
                        "request=" + request.id() + " | slot=" + slot
                                + " | previous_session=" + current.sessionKey
                                + " | reason=request_critical_path"
                );
            }

            SlotOwner replacement = new SlotOwner(session, compatibility);
            if (isSeedRequest(request)) {
                replacement.seedFingerprint = request.metadata().getOrDefault("cache_seed_fingerprint", "");
                replacement.seedBranch = request.metadata().getOrDefault("cache_seed_branch", "startup");
            }
            synchronized (this.owners) {
                this.owners.put(slot, replacement);
            }
            LocalModelRuntimeLog.write(
                    "llama_cache_miss",
                    "request=" + request.id() + " | slot=" + slot
                            + " | reason=no_live_session_match | disk_restore_deferred=true"
            );
            return new Lease(slot, session, compatibility, lock, false);
        } catch (RuntimeException exception) {
            lock.unlock();
            throw exception;
        }
    }

    void complete(Lease lease, int port, StreamingModelRequest request) {
        if (lease == null) return;
        try {
            synchronized (this.owners) {
                SlotOwner owner = this.owners.get(lease.slot);
                if (owner != null && owner.sessionKey.equals(lease.sessionKey)
                        && owner.compatibility.equals(lease.compatibility)) {
                    owner.requestFingerprint = request.metadata().getOrDefault("cache_request_fingerprint", "");
                    owner.requestId = request.id().toString();
                    boolean seedRequest = isSeedRequest(request);
                    if (seedRequest) {
                        owner.seedFingerprint = request.metadata().getOrDefault("cache_seed_fingerprint", "");
                        owner.seedBranch = request.metadata().getOrDefault("cache_seed_branch", "startup");
                    }
                    // Restored seeds are already durable. Newly-prefilled
                    // startup seeds remain hot in RAM and are intentionally NOT
                    // serialized here: slot snapshots can be large enough to turn
                    // startup into a multi-minute operation on constrained disks.
                    // flushAll() persists the final dirty seed during normal
                    // provider shutdown.
                    owner.dirty = !(seedRequest && lease.restored);
                }
            }
        } finally {
            lease.release();
        }
    }

    /** Flushes dirty active slots while llama.cpp is still alive. */
    void flushAll(int port) {
        if (!this.persistenceAvailable || port <= 0) return;
        for (int slot = 0; slot < this.slotLocks.length; slot++) {
            ReentrantLock lock = this.slotLocks[slot];
            lock.lock();
            try {
                SlotOwner owner;
                synchronized (this.owners) {
                    owner = this.owners.get(slot);
                }
                if (owner != null && owner.dirty) saveSnapshot(port, slot, owner);
            } finally {
                lock.unlock();
            }
        }
    }

    OptionalSeedEvictionResult evictOptionalSeedSlots(int port, String reason) {
        if (port <= 0) return new OptionalSeedEvictionResult(0, 0, 0, 0);
        int candidates = 0;
        int evicted = 0;
        int busy = 0;
        int failed = 0;
        for (int slot = 0; slot < this.slotLocks.length; slot++) {
            ReentrantLock lock = this.slotLocks[slot];
            if (!lock.tryLock()) {
                busy++;
                continue;
            }
            try {
                SlotOwner owner;
                synchronized (this.owners) {
                    owner = this.owners.get(slot);
                }
                if (owner == null || owner.seedFingerprint == null || owner.seedFingerprint.isBlank()) continue;
                candidates++;
                if (erase(port, slot)) {
                    synchronized (this.owners) {
                        if (this.owners.get(slot) == owner) this.owners.remove(slot);
                    }
                    evicted++;
                } else {
                    failed++;
                }
            } finally {
                lock.unlock();
            }
        }
        LocalModelRuntimeLog.write(
                "llama_cache_optional_state_eviction",
                "reason=" + (reason == null ? "memory_pressure" : reason)
                        + " | candidates=" + candidates
                        + " | evicted=" + evicted
                        + " | busy=" + busy
                        + " | failed=" + failed
        );
        return new OptionalSeedEvictionResult(candidates, evicted, busy, failed);
    }

    void fail(Lease lease) {
        if (lease != null) lease.release();
    }

    /**
     * Bounded recovery for a genuinely stalled prefill. The normal hot path never
     * erases a live slot, but after the provider reports zero prompt progress for
     * the watchdog interval we clear that one owned slot before a single cold
     * retry. The slot endpoint itself is bounded by postSlot()'s short call timeout.
     */
    void recoverStalledPrefill(int port, int slot) {
        if (slot < 0 || slot >= this.slotLocks.length || port <= 0) return;
        ReentrantLock lock = this.slotLocks[slot];
        lock.lock();
        try {
            erase(port, slot);
            synchronized (this.owners) {
                this.owners.remove(slot);
            }
            LocalModelRuntimeLog.write(
                    "llama_cache_prefill_recovered",
                    "slot=" + slot + " | action=erase_then_cold_retry"
            );
        } finally {
            lock.unlock();
        }
    }

    String providerCompatibility() {
        String raw = ModelPromptCacheIdentity.FORMAT_VERSION
                + "|runtime=" + LlamaCppRuntimeCatalog.VERSION
                + "|model=" + this.configuration.modelId()
                + "|sha256=" + this.modelFingerprint
                + "|ctx=" + this.configuration.contextTokens()
                + "|jinja=true"
                + "|kvUnified=false";
        return ModelPromptCacheIdentity.sha256(raw);
    }

    private boolean restoreBestSnapshot(int port, int slot, String session, String compatibility, String requestId) {
        if (!this.persistenceAvailable) return false;
        Snapshot snapshot = newestSnapshot(session, compatibility);
        if (snapshot == null) {
            LocalModelRuntimeLog.write("llama_cache_miss", "request=" + requestId + " | slot=" + slot + " | reason=no_compatible_snapshot");
            return false;
        }
        JsonObject body = new JsonObject();
        body.addProperty("filename", snapshot.filename);
        JsonObject result = postSlot(port, slot, "restore", body);
        if (result == null) return false;
        long restored = longValue(result, "n_restored");
        double restoreMs = timing(result, "restore_ms");
        LocalModelRuntimeLog.write(
                "llama_cache_restore",
                "request=" + requestId + " | slot=" + slot + " | tokens=" + restored
                        + " | restore_ms=" + restoreMs + " | file=" + snapshot.filename
        );
        touch(snapshot.manifest);
        return true;
    }

    private void saveSnapshot(int port, int slot, SlotOwner owner) {
        if (!this.persistenceAvailable || owner == null || !owner.dirty) return;
        String requestFingerprint = owner.requestFingerprint == null ? "" : owner.requestFingerprint;
        String requestId = owner.requestId == null || owner.requestId.isBlank() ? "checkpoint" : owner.requestId;
        String basename = safeToken(owner.sessionKey, 32) + "-"
                + owner.compatibility.substring(0, Math.min(16, owner.compatibility.length()))
                + "-" + (requestFingerprint.isBlank()
                ? safeToken(requestId, 12)
                : requestFingerprint.substring(0, Math.min(12, requestFingerprint.length())));
        String filename = basename + SNAPSHOT_SUFFIX;
        JsonObject body = new JsonObject();
        body.addProperty("filename", filename);
        JsonObject result = postSlot(port, slot, "save", body);
        if (result == null) return;
        try {
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format", ModelPromptCacheIdentity.FORMAT_VERSION);
            manifest.addProperty("session", owner.sessionKey);
            manifest.addProperty("compatibility", owner.compatibility);
            manifest.addProperty("providerCompatibility", providerCompatibility());
            manifest.addProperty("requestFingerprint", requestFingerprint);
            manifest.addProperty("seedFingerprint", owner.seedFingerprint);
            manifest.addProperty("seedBranch", owner.seedBranch);
            manifest.addProperty("filename", filename);
            manifest.addProperty("savedAt", Instant.now().toString());
            manifest.addProperty("nSaved", longValue(result, "n_saved"));
            manifest.addProperty("nWritten", longValue(result, "n_written"));
            Path target = this.configuration.slotSavePath().resolve(basename + MANIFEST_SUFFIX);
            Path temp = Files.createTempFile(this.configuration.slotSavePath(), "manifest-", ".tmp");
            Files.writeString(temp, manifest.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            owner.dirty = false;
            LocalModelRuntimeLog.write(
                    "llama_cache_save",
                    "request=" + requestId + " | slot=" + slot + " | tokens=" + longValue(result, "n_saved")
                            + " | bytes=" + longValue(result, "n_written") + " | file=" + filename
            );
            evictSnapshots();
        } catch (Exception exception) {
            LocalModelRuntimeLog.write("llama_cache_manifest_failed", exception.getMessage());
        }
    }

    private boolean restoreSeedSnapshot(int port, int slot, String seedFingerprint, String requestId) {
        if (!this.persistenceAvailable || seedFingerprint == null || seedFingerprint.isBlank()) return false;
        Snapshot snapshot = newestSeedSnapshot(seedFingerprint);
        if (snapshot == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("filename", snapshot.filename);
        JsonObject result = postSlot(port, slot, "restore", body);
        if (result == null) return false;
        LocalModelRuntimeLog.write(
                "llama_cache_restore",
                "request=" + requestId + " | slot=" + slot + " | source=startup_seed"
                        + " | tokens=" + longValue(result, "n_restored")
                        + " | restore_ms=" + timing(result, "restore_ms")
                        + " | file=" + snapshot.filename
        );
        touch(snapshot.manifest);
        return true;
    }

    private Snapshot newestSeedSnapshot(String seedFingerprint) {
        try {
            if (!Files.isDirectory(this.configuration.slotSavePath())) return null;
            Snapshot best = null;
            try (var stream = Files.list(this.configuration.slotSavePath())) {
                for (Path path : stream.filter(value -> value.getFileName().toString().endsWith(MANIFEST_SUFFIX)).toList()) {
                    try {
                        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (!seedFingerprint.equals(string(root, "seedFingerprint"))) continue;
                        if (!providerCompatibility().equals(string(root, "providerCompatibility"))) continue;
                        String filename = string(root, "filename");
                        Path binary = this.configuration.slotSavePath().resolve(filename);
                        if (!Files.isRegularFile(binary)) continue;
                        long modified = Files.getLastModifiedTime(path).toMillis();
                        if (best == null || modified > best.modified) best = new Snapshot(path, filename, modified);
                    } catch (Exception ignored) {
                    }
                }
            }
            return best;
        } catch (Exception exception) {
            return null;
        }
    }

    private Snapshot newestSnapshot(String session, String compatibility) {
        try {
            if (!Files.isDirectory(this.configuration.slotSavePath())) return null;
            Snapshot best = null;
            try (var stream = Files.list(this.configuration.slotSavePath())) {
                for (Path path : stream.filter(value -> value.getFileName().toString().endsWith(MANIFEST_SUFFIX)).toList()) {
                    try {
                        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (!session.equals(string(root, "session")) || !compatibility.equals(string(root, "compatibility"))) continue;
                        if (!providerCompatibility().equals(string(root, "providerCompatibility"))) continue;
                        String filename = string(root, "filename");
                        Path binary = this.configuration.slotSavePath().resolve(filename);
                        if (!Files.isRegularFile(binary)) continue;
                        long modified = Files.getLastModifiedTime(path).toMillis();
                        if (best == null || modified > best.modified) best = new Snapshot(path, filename, modified);
                    } catch (Exception ignored) {
                    }
                }
            }
            return best;
        } catch (Exception exception) {
            return null;
        }
    }

    private void evictSnapshots() {
        try {
            List<SnapshotFiles> snapshots = new ArrayList<>();
            long total = 0L;
            try (var stream = Files.list(this.configuration.slotSavePath())) {
                for (Path manifest : stream.filter(value -> value.getFileName().toString().endsWith(MANIFEST_SUFFIX)).toList()) {
                    try {
                        JsonObject root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
                        Path binary = this.configuration.slotSavePath().resolve(string(root, "filename"));
                        if (!Files.isRegularFile(binary)) continue;
                        long bytes = Files.size(binary);
                        long modified = Math.max(Files.getLastModifiedTime(manifest).toMillis(), Files.getLastModifiedTime(binary).toMillis());
                        snapshots.add(new SnapshotFiles(manifest, binary, string(root, "session"), bytes, modified));
                        total += bytes;
                    } catch (Exception ignored) {
                    }
                }
            }
            snapshots.sort(Comparator.comparingLong(SnapshotFiles::modified).reversed());
            Map<String, Integer> keptBySession = new HashMap<>();
            long keptBytes = 0L;
            for (SnapshotFiles snapshot : snapshots) {
                int keptForSession = keptBySession.getOrDefault(snapshot.session, 0);
                boolean keep = keptForSession < this.budget.maximumSnapshotsPerSlot()
                        && keptBytes + snapshot.bytes <= this.budget.diskSnapshotBudgetBytes();
                if (keep) {
                    keptBySession.put(snapshot.session, keptForSession + 1);
                    keptBytes += snapshot.bytes;
                } else {
                    Files.deleteIfExists(snapshot.binary);
                    Files.deleteIfExists(snapshot.manifest);
                }
            }
        } catch (Exception exception) {
            LocalModelRuntimeLog.write("llama_cache_eviction_failed", exception.getMessage());
        }
    }

    private JsonObject postSlot(int port, int slot, String action, JsonObject body) {
        if (!this.persistenceAvailable) return null;
        Request request = authenticated(new Request.Builder()
                .url(baseUrl(port) + "/slots/" + slot + "?action=" + action)
                .post(RequestBody.create(body == null ? "{}" : body.toString(), JSON))).build();
        okhttp3.Call call = this.http.newCall(request);
        call.timeout().timeout(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                if (response.code() == 404 || response.code() == 400) disablePersistence("slot " + action + " endpoint unavailable (HTTP " + response.code() + ")");
                return null;
            }
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        } catch (Exception exception) {
            LocalModelRuntimeLog.write("llama_cache_" + action + "_failed", exception.getMessage());
            return null;
        }
    }

    private boolean erase(int port, int slot) {
        JsonObject result = postSlot(port, slot, "erase", new JsonObject());
        if (result != null) {
            LocalModelRuntimeLog.write("llama_cache_slot_erased", "slot=" + slot + " | tokens=" + longValue(result, "n_erased"));
            return true;
        }
        return false;
    }

    private void disablePersistence(String reason) {
        if (this.persistenceAvailable) {
            this.persistenceAvailable = false;
            LocalModelRuntimeLog.write("llama_cache_persistence_disabled", reason + "; active-slot cache reuse remains enabled");
        }
    }

    private int slot(StreamingModelRequest request) {
        String configured = request.metadata().get("cache_slot");
        if (configured != null) {
            try {
                return Math.max(0, Math.min(this.slotLocks.length - 1, Integer.parseInt(configured)));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.floorMod(sessionKey(request).hashCode(), this.slotLocks.length);
    }

    private String sessionKey(StreamingModelRequest request) {
        String explicit = request.metadata().get("cache_session_key");
        if (explicit != null && !explicit.isBlank()) return explicit;
        String mode = request.metadata().getOrDefault("mode", "default");
        return (request.conversationId().isBlank() ? "anonymous" : request.conversationId()) + ":" + mode;
    }

    private static boolean isSeedRequest(StreamingModelRequest request) {
        return request != null && Boolean.parseBoolean(request.metadata().getOrDefault("cache_seed", "false"));
    }

    private static List<String> seedFingerprints(StreamingModelRequest request) {
        if (request == null) return List.of();
        String encoded = request.metadata().getOrDefault("cache_seed_fingerprints", "");
        if (encoded.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : encoded.split(",")) {
            String cleaned = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (cleaned.matches("[0-9a-f]{64}") && !result.contains(cleaned)) result.add(cleaned);
        }
        return List.copyOf(result);
    }

    private String compatibility(StreamingModelRequest request) {
        String stable = request.metadata().getOrDefault("cache_stable_fingerprint", "");
        return ModelPromptCacheIdentity.sha256(providerCompatibility() + "|stable=" + stable);
    }

    private Request.Builder authenticated(Request.Builder builder) {
        return builder.header("Authorization", "Bearer " + this.configuration.apiKey());
    }

    private String baseUrl(int port) {
        return "http://" + this.configuration.host() + ":" + port;
    }

    private static String safeToken(String value, int limit) {
        String safe = value == null ? "session" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.length() > limit) safe = safe.substring(0, limit);
        return safe.isBlank() ? "session" : safe;
    }

    private static String string(JsonObject root, String key) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject root, String key) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static double timing(JsonObject root, String key) {
        try {
            return root != null && root.has("timings") && root.get("timings").isJsonObject()
                    && root.getAsJsonObject("timings").has(key)
                    ? root.getAsJsonObject("timings").get(key).getAsDouble() : 0.0D;
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    private static void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.now()));
        } catch (Exception ignored) {
        }
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 16 ? String.valueOf(value) : value.substring(0, 16);
    }

    private static final class SlotOwner {
        private String sessionKey;
        private String compatibility;
        private String requestFingerprint = "";
        private String requestId = "";
        private String seedFingerprint = "";
        private String seedBranch = "";
        private boolean dirty;

        private SlotOwner(String sessionKey, String compatibility) {
            this.sessionKey = sessionKey;
            this.compatibility = compatibility;
        }
    }
    private record Snapshot(Path manifest, String filename, long modified) {}
    private record SnapshotFiles(Path manifest, Path binary, String session, long bytes, long modified) {}
    record OptionalSeedEvictionResult(int candidates, int evicted, int busy, int failed) {}

    static final class Lease {
        private final int slot;
        private String sessionKey;
        private String compatibility;
        private final ReentrantLock lock;
        private final boolean restored;
        private boolean released;

        Lease(int slot, String sessionKey, String compatibility, ReentrantLock lock, boolean restored) {
            this.slot = slot;
            this.sessionKey = sessionKey;
            this.compatibility = compatibility;
            this.lock = lock;
            this.restored = restored;
        }

        int slot() { return this.slot; }
        boolean restored() { return this.restored; }

        void release() {
            if (!this.released) {
                this.released = true;
                this.lock.unlock();
            }
        }
    }
}
