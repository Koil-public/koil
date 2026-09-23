package com.spirit.koil.api.model.runtime.universal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Persistent runtime-neutral tuning database shared by all internal execution adapters.
 * Uses a versioned Properties container so persistence has no dependency on any model provider or
 * JSON library and remains available during early runtime bootstrap.
 */
public final class KoilUniversalTuningStore {
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_SAVED_PROFILES = 96;
    private static final String PREFIX = "profile.";

    private KoilUniversalTuningStore() {}

    public static Optional<StoredProfile> findBest(Path path, KoilTuningKey query) {
        if (path == null || query == null) return Optional.empty();
        synchronized (KoilUniversalTuningStore.class) {
            return readProfiles(path).stream()
                    .filter(value -> value.key().matches(query))
                    .max(Comparator.comparingDouble((StoredProfile value) -> value.profile().score())
                            .thenComparing(value -> value.profile().measuredAt()));
        }
    }

    public static void save(Path path, KoilTuningKey key, KoilMeasuredTuningProfile profile) {
        if (path == null || key == null || profile == null) return;
        synchronized (KoilUniversalTuningStore.class) {
            List<StoredProfile> profiles = new ArrayList<>(readProfiles(path));
            profiles.removeIf(value -> value.identity().equals(key.identity()));
            profiles.add(new StoredProfile(key.identity(), key, profile));
            profiles.sort(Comparator.comparing((StoredProfile value) -> value.profile().measuredAt()).reversed());
            if (profiles.size() > MAX_SAVED_PROFILES) {
                profiles = new ArrayList<>(profiles.subList(0, MAX_SAVED_PROFILES));
            }
            writeProfiles(path, profiles);
        }
    }

    /**
     * Saves the authoritative winner for one tuning family. Profiles that differ only by backend
     * or measurement implementation are replaced because they compete for the same automatic
     * policy slot. Adapter-private stores may retain richer native history independently.
     */
    public static void saveWinner(Path path, KoilTuningKey key, KoilMeasuredTuningProfile profile) {
        if (path == null || key == null || profile == null) return;
        synchronized (KoilUniversalTuningStore.class) {
            List<StoredProfile> profiles = new ArrayList<>(readProfiles(path));
            profiles.removeIf(value -> sameTuningFamily(value.key(), key));
            profiles.add(new StoredProfile(key.identity(), key, profile));
            profiles.sort(Comparator.comparing((StoredProfile value) -> value.profile().measuredAt()).reversed());
            if (profiles.size() > MAX_SAVED_PROFILES) {
                profiles = new ArrayList<>(profiles.subList(0, MAX_SAVED_PROFILES));
            }
            writeProfiles(path, profiles);
        }
    }

    public static void removeMeasurementSource(Path path, String source) {
        if (path == null || source == null || source.isBlank()) return;
        synchronized (KoilUniversalTuningStore.class) {
            List<StoredProfile> profiles = new ArrayList<>(readProfiles(path));
            boolean changed = profiles.removeIf(value -> value.profile().source().equalsIgnoreCase(source.strip()));
            if (changed) writeProfiles(path, profiles);
        }
    }

    public static int size(Path path) {
        synchronized (KoilUniversalTuningStore.class) {
            return readProfiles(path).size();
        }
    }

    private static List<StoredProfile> readProfiles(Path path) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) return List.of();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return List.of();
        }
        String version = properties.getProperty("version", "");
        if (!(Integer.toString(FORMAT_VERSION).equals(version) || "1".equals(version))) return List.of();

        java.util.LinkedHashSet<String> identities = new java.util.LinkedHashSet<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(PREFIX)) continue;
            int next = name.indexOf('.', PREFIX.length());
            if (next > PREFIX.length()) identities.add(name.substring(PREFIX.length(), next));
        }
        List<StoredProfile> result = new ArrayList<>();
        for (String identity : identities) readStored(properties, identity).ifPresent(result::add);
        return List.copyOf(result);
    }

    private static Optional<StoredProfile> readStored(Properties p, String identity) {
        try {
            String base = PREFIX + identity + ".";
            KoilTuningKey key = new KoilTuningKey(
                    p.getProperty(base + "key.hardware", ""),
                    p.getProperty(base + "key.adapter", ""),
                    p.getProperty(base + "key.model", ""),
                    p.getProperty(base + "key.architecture", ""),
                    p.getProperty(base + "key.format", ""),
                    p.getProperty(base + "key.quantization", ""),
                    p.getProperty(base + "key.context", ""),
                    p.getProperty(base + "key.statePrecision", KoilStatePrecisionEvidence.LEGACY_FP16),
                    enumValue(KoilRuntimeBackend.class, p.getProperty(base + "key.backend", ""), KoilRuntimeBackend.UNKNOWN),
                    p.getProperty(base + "key.runtime", ""));
            Map<String, Double> metrics = readDoubleMap(p, base + "metric.");
            Map<String, String> decisions = readStringMap(p, base + "decision.");
            KoilMeasuredTuningProfile profile = new KoilMeasuredTuningProfile(
                    p.getProperty(base + "profile.identity", ""),
                    p.getProperty(base + "profile.source", ""),
                    Instant.parse(p.getProperty(base + "profile.measuredAt")),
                    p.getProperty(base + "profile.adapter", ""),
                    p.getProperty(base + "profile.model", ""),
                    p.getProperty(base + "profile.architecture", ""),
                    p.getProperty(base + "profile.runtime", ""),
                    p.getProperty(base + "profile.hardware", ""),
                    enumValue(KoilComputeMode.class, p.getProperty(base + "profile.computeMode", ""), KoilComputeMode.AUTOMATIC),
                    enumValue(KoilRuntimeBackend.class, p.getProperty(base + "profile.backend", ""), KoilRuntimeBackend.UNKNOWN),
                    Double.parseDouble(p.getProperty(base + "profile.score", "0")),
                    metrics,
                    decisions);
            return Optional.of(new StoredProfile(identity, key, profile));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void writeProfiles(Path path, List<StoredProfile> profiles) {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(FORMAT_VERSION));
        for (StoredProfile stored : profiles) {
            String base = PREFIX + stored.identity() + ".";
            KoilTuningKey key = stored.key();
            KoilMeasuredTuningProfile profile = stored.profile();
            properties.setProperty(base + "key.hardware", key.hardwareFingerprint());
            properties.setProperty(base + "key.adapter", key.adapterId());
            properties.setProperty(base + "key.model", key.modelId());
            properties.setProperty(base + "key.architecture", key.architectureId());
            properties.setProperty(base + "key.format", key.sourceFormat());
            properties.setProperty(base + "key.quantization", key.quantization());
            properties.setProperty(base + "key.context", key.contextRegime());
            properties.setProperty(base + "key.statePrecision", key.statePrecisionRegime());
            properties.setProperty(base + "key.backend", key.backend().name());
            properties.setProperty(base + "key.runtime", key.runtimeRevision());
            properties.setProperty(base + "profile.identity", profile.identity());
            properties.setProperty(base + "profile.source", profile.source());
            properties.setProperty(base + "profile.measuredAt", profile.measuredAt().toString());
            properties.setProperty(base + "profile.adapter", profile.adapterId());
            properties.setProperty(base + "profile.model", profile.modelId());
            properties.setProperty(base + "profile.architecture", profile.architectureId());
            properties.setProperty(base + "profile.runtime", profile.runtimeIdentity());
            properties.setProperty(base + "profile.hardware", profile.hardwareFingerprint());
            properties.setProperty(base + "profile.computeMode", profile.computeMode().name());
            properties.setProperty(base + "profile.backend", profile.primaryBackend().name());
            properties.setProperty(base + "profile.score", Double.toString(profile.score()));
            profile.metrics().forEach((name, value) -> properties.setProperty(
                    base + "metric." + encode(name), Double.toString(value)));
            profile.decisions().forEach((name, value) -> properties.setProperty(
                    base + "decision." + encode(name), value));
        }

        Path absolute = path.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent, "koil-universal-tuning-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(writer, "Koil universal measured tuning profiles");
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Tuning persistence is an optimization. Failure must never prevent model startup.
        }
    }

    private static Map<String, Double> readDoubleMap(Properties properties, String prefix) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            try { result.put(decode(name.substring(prefix.length())), Double.parseDouble(properties.getProperty(name))); }
            catch (Exception ignored) { }
        }
        return result;
    }

    private static Map<String, String> readStringMap(Properties properties, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            try { result.put(decode(name.substring(prefix.length())), properties.getProperty(name, "")); }
            catch (Exception ignored) { }
        }
        return result;
    }

    private static boolean sameTuningFamily(KoilTuningKey left, KoilTuningKey right) {
        if (left == null || right == null) return false;
        return left.hardwareFingerprint().equals(right.hardwareFingerprint())
                && left.adapterId().equals(right.adapterId())
                && left.modelId().equals(right.modelId())
                && left.architectureId().equals(right.architectureId())
                && left.sourceFormat().equals(right.sourceFormat())
                && left.quantization().equals(right.quantization())
                && left.contextRegime().equals(right.contextRegime())
                && left.statePrecisionRegime().equals(right.statePrecisionRegime())
                && left.runtimeRevision().equals(right.runtimeRevision());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value == null ? "" : value.strip().toUpperCase()); }
        catch (Exception ignored) { return fallback; }
    }

    public record StoredProfile(String identity, KoilTuningKey key, KoilMeasuredTuningProfile profile) {}
}
