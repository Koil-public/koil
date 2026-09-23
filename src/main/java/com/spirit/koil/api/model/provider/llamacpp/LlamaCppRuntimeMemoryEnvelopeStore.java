package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureSnapshot;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureStabilizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Persistent resident-memory evidence for llama.cpp launches.
 *
 * <p>The store learns only from post-load live memory, never from startup guesses. A context is
 * marked resident-unsafe only after repeated critical samples. Evidence is scoped by model
 * artifact, hardware fingerprint and state-precision regime so FP16 and Q8 launches cannot
 * contaminate each other.</p>
 */
final class LlamaCppRuntimeMemoryEnvelopeStore {
    private static final Object LOCK = new Object();
    private static final Path STORE = Path.of("koil", "sys", "model", "compute", "llama-runtime-memory-envelope.properties");
    private static final long MIB = 1024L * 1024L;
    private static final long EVIDENCE_TTL_MILLIS = Duration.ofHours(12).toMillis();
    private static final long RETEST_HEADROOM_DELTA_BYTES = 1536L * MIB;
    private static final long CONSECUTIVE_WINDOW_MILLIS = 20_000L;
    private static final int CRITICAL_SAMPLES_TO_BLOCK = 3;

    private LlamaCppRuntimeMemoryEnvelopeStore() {}

    static void record(
            Path modelFile,
            String hardwareFingerprint,
            String precisionRegime,
            int contextTokens,
            long launchAvailableBytes,
            KoilMemoryPressureStabilizer.Result observation
    ) {
        if (modelFile == null || contextTokens <= 0 || observation == null || observation.effective() == null
                || !observation.effective().known()) return;
        synchronized (LOCK) {
            Properties p = load();
            String prefix = prefix(modelFile, hardwareFingerprint, precisionRegime, contextTokens);
            long now = System.currentTimeMillis();
            long available = observation.effective().availableBytes();
            long previousLow = longValue(p.getProperty(prefix + "lowWaterBytes"), 0L);
            if (previousLow <= 0L || available < previousLow) {
                p.setProperty(prefix + "lowWaterBytes", Long.toString(available));
            }
            if (launchAvailableBytes > 0L) {
                p.setProperty(prefix + "launchAvailableBytes", Long.toString(launchAvailableBytes));
            }
            p.setProperty(prefix + "lastObserved", Long.toString(now));
            p.setProperty(prefix + "lastPressure", observation.effective().pressure().name().toLowerCase());
            p.setProperty(prefix + "lastTrend", observation.trend().name().toLowerCase());

            boolean critical = observation.effective().pressure() == KoilMemoryPressureSnapshot.Pressure.CRITICAL;
            long previousCriticalAt = longValue(p.getProperty(prefix + "lastCriticalAt"), 0L);
            int consecutive = integer(p.getProperty(prefix + "criticalSamples"), 0);
            if (critical) {
                consecutive = previousCriticalAt > 0L && now - previousCriticalAt <= CONSECUTIVE_WINDOW_MILLIS
                        ? consecutive + 1 : 1;
                p.setProperty(prefix + "lastCriticalAt", Long.toString(now));
                p.setProperty(prefix + "criticalSamples", Integer.toString(consecutive));
                if (contextTokens > 2048 && consecutive >= CRITICAL_SAMPLES_TO_BLOCK) {
                    p.setProperty(prefix + "residentUnsafe", "true");
                    p.setProperty(prefix + "unsafeSince", Long.toString(now));
                    p.setProperty(prefix + "unsafeReason", "sustained critical post-load memory pressure");
                }
            } else {
                p.setProperty(prefix + "criticalSamples", "0");
            }
            save(p);
        }
    }

    static Recommendation recommend(
            Path modelFile,
            String hardwareFingerprint,
            String precisionRegime,
            int proposedContextTokens,
            long currentLaunchAvailableBytes
    ) {
        int proposed = Math.max(512, proposedContextTokens);
        if (modelFile == null || proposed <= 2048) {
            return new Recommendation(proposed, false, "no learned resident-memory cap");
        }
        synchronized (LOCK) {
            Properties p = load();
            int candidate = proposed;
            String lastReason = "";
            while (candidate > 2048) {
                String prefix = prefix(modelFile, hardwareFingerprint, precisionRegime, candidate);
                if (!Boolean.parseBoolean(p.getProperty(prefix + "residentUnsafe", "false"))) break;
                long unsafeSince = longValue(p.getProperty(prefix + "unsafeSince"), 0L);
                if (unsafeSince <= 0L || System.currentTimeMillis() - unsafeSince > EVIDENCE_TTL_MILLIS) break;
                long previousLaunch = longValue(p.getProperty(prefix + "launchAvailableBytes"), 0L);
                if (currentLaunchAvailableBytes > 0L && previousLaunch > 0L
                        && currentLaunchAvailableBytes >= previousLaunch + RETEST_HEADROOM_DELTA_BYTES) {
                    break;
                }
                long lowWater = longValue(p.getProperty(prefix + "lowWaterBytes"), 0L);
                lastReason = "resident low-water evidence blocked " + candidate
                        + " tokens"
                        + (lowWater > 0L ? " (low-water=" + (lowWater / MIB) + "MiB)" : "");
                candidate = lowerTier(candidate);
            }
            if (candidate < proposed) {
                return new Recommendation(candidate, true, lastReason);
            }
            return new Recommendation(proposed, false, "no matching resident-unsafe evidence");
        }
    }

    static Evidence evidence(
            Path modelFile,
            String hardwareFingerprint,
            String precisionRegime,
            int contextTokens
    ) {
        if (modelFile == null || contextTokens <= 0) return Evidence.empty();
        synchronized (LOCK) {
            Properties p = load();
            String prefix = prefix(modelFile, hardwareFingerprint, precisionRegime, contextTokens);
            return new Evidence(
                    Boolean.parseBoolean(p.getProperty(prefix + "residentUnsafe", "false")),
                    longValue(p.getProperty(prefix + "lowWaterBytes"), 0L),
                    integer(p.getProperty(prefix + "criticalSamples"), 0),
                    p.getProperty(prefix + "lastPressure", "unknown"),
                    p.getProperty(prefix + "lastTrend", "unknown"),
                    longValue(p.getProperty(prefix + "lastObserved"), 0L),
                    p.getProperty(prefix + "unsafeReason", "")
            );
        }
    }

    private static int lowerTier(int context) {
        if (context > 65536) return 65536;
        if (context > 32768) return 32768;
        if (context > 16384) return 16384;
        if (context > 8192) return 8192;
        if (context > 4096) return 4096;
        return 2048;
    }

    private static String prefix(Path modelFile, String hardwareFingerprint, String precisionRegime, int contextTokens) {
        String identity = artifactIdentity(modelFile) + "|hw=" + safe(hardwareFingerprint)
                + "|precision=" + safe(precisionRegime);
        return "entry." + sha256(identity) + ".ctx." + Math.max(512, contextTokens) + ".";
    }

    private static String artifactIdentity(Path file) {
        try {
            Path normalized = file.toAbsolutePath().normalize();
            long size = Files.isRegularFile(normalized) ? Files.size(normalized) : 0L;
            long modified = Files.exists(normalized) ? Files.getLastModifiedTime(normalized).toMillis() : 0L;
            return normalized + "|size=" + size + "|mtime=" + modified;
        } catch (Exception ignored) {
            return file.toAbsolutePath().normalize().toString();
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        if (!Files.isRegularFile(STORE)) return p;
        try (var in = Files.newInputStream(STORE)) {
            p.load(in);
        } catch (IOException ignored) {
        }
        return p;
    }

    private static void save(Properties p) {
        try {
            Path parent = STORE.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (var out = Files.newOutputStream(STORE)) {
                p.store(out, "Koil llama.cpp resident-memory envelope v1");
            }
        } catch (IOException ignored) {
        }
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value.strip()); }
        catch (Exception ignored) { return fallback; }
    }

    private static long longValue(String value, long fallback) {
        try { return Long.parseLong(value == null ? "" : value.strip()); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip().toLowerCase();
    }

    record Recommendation(int contextTokens, boolean learnedCapApplied, String reason) {}

    record Evidence(
            boolean residentUnsafe,
            long lowWaterBytes,
            int criticalSamples,
            String lastPressure,
            String lastTrend,
            long lastObservedEpochMillis,
            String reason
    ) {
        static Evidence empty() { return new Evidence(false, 0L, 0, "unknown", "unknown", 0L, ""); }
    }
}
