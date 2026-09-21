package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.ModelPerformanceBenchmarkResult;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionEvidence;
import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Persistent machine + model + runtime specific winner for MAX performance. */
public final class LlamaCppMaxTuningStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int FORMAT_VERSION = 4;
    private static final int MAX_SAVED_PROFILES = 24;
    private static final AtomicReference<LlamaCppMaxRuntimeProfile> ACTIVE_CANDIDATE = new AtomicReference<>();

    private LlamaCppMaxTuningStore() {
    }

    public static Path path() {
        return KoilInstancePaths.modelRoot().resolve("llama-max-tuning.json");
    }

    /** Temporary process-wide override used only while the autotuner evaluates a candidate. */
    public static void setActiveCandidate(LlamaCppMaxRuntimeProfile profile) {
        ACTIVE_CANDIDATE.set(profile);
    }

    public static void clearActiveCandidate() {
        ACTIVE_CANDIDATE.set(null);
    }

    public static Optional<LlamaCppMaxRuntimeProfile> activeCandidate() {
        return Optional.ofNullable(ACTIVE_CANDIDATE.get());
    }

    public static Optional<TunedProfile> find(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime
    ) {
        return find(configuration, deviceId, deviceDetail, contextRegime, KoilStatePrecisionEvidence.LEGACY_FP16);
    }

    public static Optional<TunedProfile> find(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime,
            String statePrecisionRegime
    ) {
        if (configuration == null) return Optional.empty();
        String identity = identity(configuration, deviceId, deviceDetail, contextRegime, statePrecisionRegime);
        synchronized (LlamaCppMaxTuningStore.class) {
            JsonObject root = readRoot();
            JsonObject profiles = profiles(root);
            JsonElement element = profiles.get(identity);
            if (element == null || !element.isJsonObject()) return Optional.empty();
            try {
                return Optional.of(readProfile(identity, element.getAsJsonObject()));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
    }

    public static TunedProfile save(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime,
            LlamaCppMaxRuntimeProfile profile,
            ModelPerformanceBenchmarkResult benchmark,
            double score,
            int candidatesTested
    ) {
        return save(configuration, deviceId, deviceDetail, contextRegime, KoilStatePrecisionEvidence.LEGACY_FP16,
                profile, benchmark, score, candidatesTested);
    }

    public static TunedProfile save(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime,
            String statePrecisionRegime,
            LlamaCppMaxRuntimeProfile profile,
            ModelPerformanceBenchmarkResult benchmark,
            double score,
            int candidatesTested
    ) {
        if (configuration == null) throw new IllegalArgumentException("llama.cpp configuration is required");
        if (profile == null) throw new IllegalArgumentException("MAX tuning profile is required");
        if (benchmark == null || !benchmark.valid()) throw new IllegalArgumentException("valid MAX benchmark result is required");
        String normalizedPrecision = KoilStatePrecisionEvidence.normalizeRegime(statePrecisionRegime);
        String identity = identity(configuration, deviceId, deviceDetail, contextRegime, normalizedPrecision);
        TunedProfile tuned = new TunedProfile(
                identity,
                Instant.now(),
                configuration.modelId(),
                modelIdentity(configuration),
                runtimeIdentity(configuration.executable()),
                normalized(deviceId),
                normalized(deviceDetail),
                normalized(contextRegime),
                normalizedPrecision,
                profile,
                benchmark,
                Math.max(0.0D, score),
                Math.max(1, candidatesTested)
        );
        synchronized (LlamaCppMaxTuningStore.class) {
            JsonObject root = readRoot();
            root.addProperty("version", FORMAT_VERSION);
            JsonObject profiles = profiles(root);
            profiles.add(identity, writeProfile(tuned));
            trim(profiles);
            root.add("profiles", profiles);
            writeRoot(root);
        }
        return tuned;
    }

    public static void remove(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime
    ) {
        remove(configuration, deviceId, deviceDetail, contextRegime, KoilStatePrecisionEvidence.LEGACY_FP16);
    }

    public static void remove(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime,
            String statePrecisionRegime
    ) {
        if (configuration == null) return;
        String identity = identity(configuration, deviceId, deviceDetail, contextRegime, statePrecisionRegime);
        synchronized (LlamaCppMaxTuningStore.class) {
            JsonObject root = readRoot();
            JsonObject profiles = profiles(root);
            if (profiles.remove(identity) != null) {
                root.add("profiles", profiles);
                writeRoot(root);
            }
        }
    }

    /**
     * Returns the highest-scoring saved winner for this exact model artifact/runtime pair.
     * Unlike latestForModel(), this cannot accidentally select a newer CPU calibration from a
     * different runtime/device identity merely because it was written last.
     */
    public static Optional<TunedProfile> bestForConfiguration(
            LlamaCppConfiguration configuration,
            String preferredDeviceId,
            String contextRegime
    ) {
        return bestForConfiguration(configuration, preferredDeviceId, contextRegime, KoilStatePrecisionEvidence.LEGACY_FP16);
    }

    public static Optional<TunedProfile> bestForConfiguration(
            LlamaCppConfiguration configuration,
            String preferredDeviceId,
            String contextRegime,
            String statePrecisionRegime
    ) {
        if (configuration == null) return Optional.empty();
        String expectedModelIdentity = modelIdentity(configuration);
        String expectedRuntimeIdentity = runtimeIdentity(configuration.executable());
        String normalizedPreferredDevice = normalized(preferredDeviceId);
        final String expectedContextRegime = normalized(contextRegime);
        final String expectedStatePrecisionRegime = KoilStatePrecisionEvidence.normalizeRegime(statePrecisionRegime);
        final String preferredDevice = (normalizedPreferredDevice.equalsIgnoreCase("automatic")
                || normalizedPreferredDevice.equalsIgnoreCase("auto")
                || normalizedPreferredDevice.equalsIgnoreCase("default"))
                ? ""
                : normalizedPreferredDevice;
        synchronized (LlamaCppMaxTuningStore.class) {
            return profiles(readRoot()).entrySet().stream()
                    .filter(entry -> entry.getValue().isJsonObject())
                    .map(entry -> {
                        try {
                            return readProfile(entry.getKey(), entry.getValue().getAsJsonObject());
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(profile -> profile != null
                            && profile.modelIdentity().equals(expectedModelIdentity)
                            && profile.runtimeIdentity().equals(expectedRuntimeIdentity)
                            && profile.contextRegime().equals(expectedContextRegime)
                            && profile.statePrecisionRegime().equals(expectedStatePrecisionRegime))
                    .filter(profile -> preferredDevice.isBlank()
                            || normalized(profile.deviceId()).equals(preferredDevice))
                    .max(Comparator.comparingDouble(TunedProfile::score)
                            .thenComparing(TunedProfile::tunedAt));
        }
    }

    public static Optional<TunedProfile> latestForModel(String modelId) {
        String expected = normalized(modelId);
        synchronized (LlamaCppMaxTuningStore.class) {
            return profiles(readRoot()).entrySet().stream()
                    .filter(entry -> entry.getValue().isJsonObject())
                    .map(entry -> {
                        try {
                            return readProfile(entry.getKey(), entry.getValue().getAsJsonObject());
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(profile -> profile != null && normalized(profile.modelId()).equals(expected))
                    .max(Comparator.comparing(TunedProfile::tunedAt));
        }
    }

    public static String identity(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime
    ) {
        return identity(configuration, deviceId, deviceDetail, contextRegime, KoilStatePrecisionEvidence.LEGACY_FP16);
    }

    public static String identity(
            LlamaCppConfiguration configuration,
            String deviceId,
            String deviceDetail,
            String contextRegime,
            String statePrecisionRegime
    ) {
        String material = "koil-llama-max-v2\n"
                + System.getProperty("os.name", "") + "\n"
                + System.getProperty("os.version", "") + "\n"
                + System.getProperty("os.arch", "") + "\n"
                + Runtime.getRuntime().availableProcessors() + "\n"
                + normalized(deviceId) + "\n"
                + normalized(deviceDetail) + "\n"
                + normalized(contextRegime) + "\n"
                + KoilStatePrecisionEvidence.normalizeRegime(statePrecisionRegime) + "\n"
                + modelIdentity(configuration) + "\n"
                + runtimeIdentity(configuration == null ? null : configuration.executable());
        return sha256(material);
    }

    private static String modelIdentity(LlamaCppConfiguration configuration) {
        if (configuration == null) return "model:none";
        if (!configuration.modelFingerprintHint().isBlank()) {
            return "sha256:" + configuration.modelFingerprintHint();
        }
        Path model = configuration.modelFile();
        return "file:" + fileIdentity(model) + ":" + normalized(configuration.modelId());
    }

    private static String runtimeIdentity(Path executable) {
        return "runtime:" + fileIdentity(executable);
    }

    private static String fileIdentity(Path path) {
        if (path == null) return "none";
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized + ":" + Files.size(normalized) + ":" + Files.getLastModifiedTime(normalized).toMillis();
        } catch (IOException ignored) {
            return normalized.toString();
        }
    }

    private static JsonObject readRoot() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.add("profiles", new JsonObject());
            return root;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("MAX tuning root is not an object");
            JsonObject root = parsed.getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version == 3) {
                JsonObject migrated = migrateV3(root);
                try { writeRoot(migrated); } catch (Exception ignored) { }
                return migrated;
            }
            if (version != FORMAT_VERSION) {
                JsonObject fresh = new JsonObject();
                fresh.addProperty("version", FORMAT_VERSION);
                fresh.add("profiles", new JsonObject());
                return fresh;
            }
            if (!root.has("profiles") || !root.get("profiles").isJsonObject()) root.add("profiles", new JsonObject());
            return root;
        } catch (Exception ignored) {
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.add("profiles", new JsonObject());
            return root;
        }
    }

    private static JsonObject migrateV3(JsonObject legacyRoot) {
        JsonObject migrated = new JsonObject();
        migrated.addProperty("version", FORMAT_VERSION);
        JsonObject migratedProfiles = new JsonObject();
        JsonObject legacyProfiles = profiles(legacyRoot);
        for (java.util.Map.Entry<String, JsonElement> entry : legacyProfiles.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            try {
                TunedProfile legacy = readProfile(entry.getKey(), entry.getValue().getAsJsonObject());
                TunedProfile upgraded = new TunedProfile(
                        legacy.identity(), legacy.tunedAt(), legacy.modelId(), legacy.modelIdentity(),
                        legacy.runtimeIdentity(), legacy.deviceId(), legacy.deviceDetail(), legacy.contextRegime(),
                        KoilStatePrecisionEvidence.LEGACY_FP16, legacy.profile(), legacy.benchmark(),
                        legacy.score(), legacy.candidatesTested());
                String newIdentity = identityFromStored(upgraded);
                TunedProfile rekeyed = new TunedProfile(
                        newIdentity, upgraded.tunedAt(), upgraded.modelId(), upgraded.modelIdentity(),
                        upgraded.runtimeIdentity(), upgraded.deviceId(), upgraded.deviceDetail(), upgraded.contextRegime(),
                        upgraded.statePrecisionRegime(), upgraded.profile(), upgraded.benchmark(),
                        upgraded.score(), upgraded.candidatesTested());
                migratedProfiles.add(newIdentity, writeProfile(rekeyed));
            } catch (Exception ignored) { }
        }
        migrated.add("profiles", migratedProfiles);
        return migrated;
    }

    private static String identityFromStored(TunedProfile profile) {
        String material = "koil-llama-max-v2\n"
                + System.getProperty("os.name", "") + "\n"
                + System.getProperty("os.version", "") + "\n"
                + System.getProperty("os.arch", "") + "\n"
                + Runtime.getRuntime().availableProcessors() + "\n"
                + normalized(profile.deviceId()) + "\n"
                + normalized(profile.deviceDetail()) + "\n"
                + normalized(profile.contextRegime()) + "\n"
                + KoilStatePrecisionEvidence.normalizeRegime(profile.statePrecisionRegime()) + "\n"
                + profile.modelIdentity() + "\n"
                + profile.runtimeIdentity();
        return sha256(material);
    }

    private static JsonObject profiles(JsonObject root) {
        if (root.has("profiles") && root.get("profiles").isJsonObject()) {
            return root.getAsJsonObject("profiles");
        }
        return new JsonObject();
    }

    private static JsonObject writeProfile(TunedProfile tuned) {
        JsonObject root = new JsonObject();
        root.addProperty("tunedAt", tuned.tunedAt().toString());
        root.addProperty("modelId", tuned.modelId());
        root.addProperty("modelIdentity", tuned.modelIdentity());
        root.addProperty("runtimeIdentity", tuned.runtimeIdentity());
        root.addProperty("deviceId", tuned.deviceId());
        root.addProperty("deviceDetail", tuned.deviceDetail());
        root.addProperty("contextRegime", tuned.contextRegime());
        root.addProperty("statePrecisionRegime", tuned.statePrecisionRegime());
        root.addProperty("score", tuned.score());
        root.addProperty("candidatesTested", tuned.candidatesTested());

        LlamaCppMaxRuntimeProfile profile = tuned.profile();
        JsonObject profileJson = new JsonObject();
        profileJson.addProperty("placement", profile.placement().name().toLowerCase(java.util.Locale.ROOT));
        profileJson.addProperty("gpuLayers", profile.gpuLayers());
        profileJson.addProperty("generationThreads", profile.generationThreads());
        profileJson.addProperty("batchThreads", profile.batchThreads());
        profileJson.addProperty("poll", profile.poll());
        profileJson.addProperty("pollBatch", profile.pollBatch());
        profileJson.addProperty("batchSize", profile.batchSize());
        profileJson.addProperty("ubatchSize", profile.ubatchSize());
        profileJson.addProperty("label", profile.label());
        root.add("profile", profileJson);

        ModelPerformanceBenchmarkResult benchmark = tuned.benchmark();
        JsonObject metrics = new JsonObject();
        metrics.addProperty("promptTokens", benchmark.promptTokens());
        metrics.addProperty("generatedTokens", benchmark.generatedTokens());
        metrics.addProperty("promptTokensPerSecond", benchmark.promptTokensPerSecond());
        metrics.addProperty("generationTokensPerSecond", benchmark.generationTokensPerSecond());
        metrics.addProperty("promptMillis", benchmark.promptMillis());
        metrics.addProperty("generationMillis", benchmark.generationMillis());
        metrics.addProperty("timeToFirstTokenMillis", benchmark.timeToFirstTokenMillis());
        metrics.addProperty("wallMillis", benchmark.wallMillis());
        metrics.addProperty("detail", benchmark.detail());
        root.add("benchmark", metrics);
        return root;
    }

    private static TunedProfile readProfile(String identity, JsonObject root) {
        JsonObject profileJson = root.getAsJsonObject("profile");
        LlamaCppMaxRuntimeProfile profile = new LlamaCppMaxRuntimeProfile(
                LlamaCppMaxRuntimeProfile.Placement.valueOf(profileJson.get("placement").getAsString().toUpperCase(java.util.Locale.ROOT)),
                profileJson.get("gpuLayers").getAsInt(),
                profileJson.get("generationThreads").getAsInt(),
                profileJson.get("batchThreads").getAsInt(),
                profileJson.get("poll").getAsInt(),
                profileJson.get("pollBatch").getAsInt(),
                profileJson.get("batchSize").getAsInt(),
                profileJson.get("ubatchSize").getAsInt(),
                profileJson.has("label") ? profileJson.get("label").getAsString() : ""
        );
        JsonObject metrics = root.getAsJsonObject("benchmark");
        ModelPerformanceBenchmarkResult benchmark = new ModelPerformanceBenchmarkResult(
                metrics.get("promptTokens").getAsInt(),
                metrics.get("generatedTokens").getAsInt(),
                metrics.get("promptTokensPerSecond").getAsDouble(),
                metrics.get("generationTokensPerSecond").getAsDouble(),
                metrics.get("promptMillis").getAsDouble(),
                metrics.get("generationMillis").getAsDouble(),
                metrics.get("timeToFirstTokenMillis").getAsDouble(),
                metrics.get("wallMillis").getAsLong(),
                metrics.has("detail") ? metrics.get("detail").getAsString() : ""
        );
        return new TunedProfile(
                identity,
                Instant.parse(root.get("tunedAt").getAsString()),
                root.has("modelId") ? root.get("modelId").getAsString() : "",
                root.has("modelIdentity") ? root.get("modelIdentity").getAsString() : "",
                root.has("runtimeIdentity") ? root.get("runtimeIdentity").getAsString() : "",
                root.has("deviceId") ? root.get("deviceId").getAsString() : "",
                root.has("deviceDetail") ? root.get("deviceDetail").getAsString() : "",
                root.has("contextRegime") ? root.get("contextRegime").getAsString() : "",
                root.has("statePrecisionRegime")
                        ? root.get("statePrecisionRegime").getAsString()
                        : KoilStatePrecisionEvidence.LEGACY_FP16,
                profile,
                benchmark,
                root.has("score") ? root.get("score").getAsDouble() : 0.0D,
                root.has("candidatesTested") ? root.get("candidatesTested").getAsInt() : 1
        );
    }

    private static void trim(JsonObject profiles) {
        while (profiles.size() > MAX_SAVED_PROFILES) {
            String oldestKey = profiles.entrySet().stream()
                    .filter(entry -> entry.getValue().isJsonObject())
                    .min(Comparator.comparing(entry -> profileTime(entry.getValue().getAsJsonObject())))
                    .map(java.util.Map.Entry::getKey)
                    .orElse(null);
            if (oldestKey == null) return;
            profiles.remove(oldestKey);
        }
    }

    private static Instant profileTime(JsonObject profile) {
        try {
            return Instant.parse(profile.get("tunedAt").getAsString());
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private static void writeRoot(JsonObject root) {
        Path absolute = path().toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute.getParent());
            Path temporary = Files.createTempFile(absolute.getParent(), "llama-max-tuning-", ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to save llama.cpp MAX tuning profile: " + failure.getMessage(), failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public record TunedProfile(
            String identity,
            Instant tunedAt,
            String modelId,
            String modelIdentity,
            String runtimeIdentity,
            String deviceId,
            String deviceDetail,
            String contextRegime,
            String statePrecisionRegime,
            LlamaCppMaxRuntimeProfile profile,
            ModelPerformanceBenchmarkResult benchmark,
            double score,
            int candidatesTested
    ) {
        public TunedProfile {
            identity = normalized(identity);
            tunedAt = tunedAt == null ? Instant.EPOCH : tunedAt;
            modelId = normalized(modelId);
            modelIdentity = normalized(modelIdentity);
            runtimeIdentity = normalized(runtimeIdentity);
            deviceId = normalized(deviceId);
            deviceDetail = normalized(deviceDetail);
            contextRegime = normalized(contextRegime);
            statePrecisionRegime = KoilStatePrecisionEvidence.normalizeRegime(statePrecisionRegime);
            profile = profile == null ? LlamaCppMaxRuntimeProfile.forcedFallback() : profile;
            benchmark = benchmark == null
                    ? new ModelPerformanceBenchmarkResult(0, 0, 0, 0, 0, 0, 0, 0, "")
                    : benchmark;
            score = Double.isFinite(score) ? Math.max(0.0D, score) : 0.0D;
            candidatesTested = Math.max(1, candidatesTested);
        }
    }
}
