package com.spirit.koil.api.model.provider.llamacpp;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Persists observed hybrid-placement stability for one model/device pair.
 *
 * <p>Two different failure classes are intentionally represented separately:</p>
 * <ul>
 *   <li>startup memory pressure is treated as a monotonic ceiling under similar
 *   current memory headroom;</li>
 *   <li>inference-integrity failures are remembered as exact layer counts because
 *   backend/model bugs are not guaranteed to be monotonic.</li>
 * </ul>
 *
 * <p>The store is evidence, not a permanent hardware blacklist. More free memory,
 * a changed model artifact, or an expired pressure observation permits a fresh
 * bounded pressure retest. A successful exact placement clears a previous exact
 * integrity failure for that placement.</p>
 */
public final class LlamaCppComputeStabilityStore {
    private static final Object LOCK = new Object();
    private static final Path STORE = Path.of(
            "koil", "sys", "model", "compute", "llama-hybrid-stability.properties");
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long RETEST_HEADROOM_DELTA = 768L * MIB;
    private static final Duration UNSAFE_TTL = Duration.ofHours(12);

    private LlamaCppComputeStabilityStore() {
    }

    public static Envelope envelope(Path modelFile, String device) {
        synchronized (LOCK) {
            Properties properties = load();
            return read(properties, key(modelFile, device) + ".");
        }
    }

    public static void recordStable(
            Path modelFile,
            String device,
            int gpuLayers,
            long availableBytes,
            String detail
    ) {
        if (modelFile == null || gpuLayers <= 0) return;
        synchronized (LOCK) {
            Properties properties = load();
            String prefix = key(modelFile, device) + ".";
            Envelope current = read(properties, prefix);
            int stable = Math.max(current.highestStableLayers(), gpuLayers);
            int unsafe = current.firstUnsafeLayers();
            long unsafeStart = current.unsafeStartAvailableBytes();
            long unsafeFailure = current.unsafeFailureAvailableBytes();
            String reason = current.reason();
            Set<Integer> invalid = new TreeSet<>(current.integrityInvalidLayers());
            invalid.remove(gpuLayers);

            // A successful activation at or above a previously pressure-unsafe
            // boundary invalidates that pressure boundary. This can happen after
            // memory is freed or Koil adopts a lower-scratch runtime geometry.
            if (unsafe > 0 && gpuLayers >= unsafe) {
                unsafe = 0;
                unsafeStart = 0L;
                unsafeFailure = 0L;
                reason = "";
            }

            writeEnvelope(properties, prefix, stable, unsafe, unsafeStart, unsafeFailure,
                    invalid, reason.isBlank() ? text(detail) : reason);
            if (availableBytes > 0L) {
                properties.setProperty(prefix + "lastStableAvailable", Long.toString(availableBytes));
            }
            save(properties);
        }
    }

    /** Records a monotonic memory-pressure boundary for this model/device. */
    public static void recordPressureUnsafe(
            Path modelFile,
            String device,
            int gpuLayers,
            long startAvailableBytes,
            long failureAvailableBytes,
            String reason
    ) {
        if (modelFile == null || gpuLayers <= 0) return;
        synchronized (LOCK) {
            Properties properties = load();
            String prefix = key(modelFile, device) + ".";
            Envelope current = read(properties, prefix);
            int unsafe = current.firstUnsafeLayers() <= 0
                    ? gpuLayers
                    : Math.min(current.firstUnsafeLayers(), gpuLayers);
            writeEnvelope(properties, prefix,
                    current.highestStableLayers(),
                    unsafe,
                    Math.max(0L, startAvailableBytes),
                    Math.max(0L, failureAvailableBytes),
                    current.integrityInvalidLayers(),
                    text(reason));
            save(properties);
        }
    }

    /** Records an exact layer count whose output failed the inference-integrity canary. */
    public static void recordIntegrityUnsafe(
            Path modelFile,
            String device,
            int gpuLayers,
            String reason
    ) {
        if (modelFile == null || gpuLayers <= 0) return;
        synchronized (LOCK) {
            Properties properties = load();
            String prefix = key(modelFile, device) + ".";
            Envelope current = read(properties, prefix);
            Set<Integer> invalid = new TreeSet<>(current.integrityInvalidLayers());
            invalid.add(gpuLayers);
            writeEnvelope(properties, prefix,
                    current.highestStableLayers(),
                    current.firstUnsafeLayers(),
                    current.unsafeStartAvailableBytes(),
                    current.unsafeFailureAvailableBytes(),
                    invalid,
                    text(reason));
            save(properties);
        }
    }

    /**
     * Returns the first known pressure-unsafe layer count under the current
     * memory conditions, or {@code 0} when a pressure retest is permissible.
     */
    public static int blockedAtOrAbove(Path modelFile, String device, long currentAvailableBytes) {
        Envelope envelope = envelope(modelFile, device);
        if (envelope.firstUnsafeLayers() <= 0) return 0;
        if (envelope.updatedEpochMillis() <= 0L
                || System.currentTimeMillis() - envelope.updatedEpochMillis() > UNSAFE_TTL.toMillis()) {
            return 0;
        }
        long failedStart = envelope.unsafeStartAvailableBytes();
        if (currentAvailableBytes > 0L && failedStart > 0L
                && currentAvailableBytes >= failedStart + RETEST_HEADROOM_DELTA) {
            return 0;
        }
        return envelope.firstUnsafeLayers();
    }

    public static boolean exactIntegrityBlocked(Path modelFile, String device, int gpuLayers) {
        return gpuLayers > 0 && envelope(modelFile, device).integrityInvalidLayers().contains(gpuLayers);
    }

    public static int recommendedStableMaximum(Path modelFile, String device, long currentAvailableBytes) {
        Envelope envelope = envelope(modelFile, device);
        int blocked = blockedAtOrAbove(modelFile, device, currentAvailableBytes);
        if (blocked <= 0) return envelope.highestStableLayers();
        if (envelope.highestStableLayers() > 0 && envelope.highestStableLayers() < blocked) {
            return envelope.highestStableLayers();
        }
        return Math.max(0, blocked - 1);
    }

    /**
     * Conservative first-pass batch geometry for exact hybrid placement. The
     * defaults used by llama.cpp are 2048/512. On <=24 GiB UMA-class systems we
     * deliberately lower scratch pressure as layer residency rises. MAX tuning
     * may benchmark larger values only after this placement is proven healthy.
     */
    public static int[] conservativeHybridBatchGeometry(int gpuLayers) {
        return conservativeHybridBatchGeometry(totalPhysicalMemoryBytes(), gpuLayers);
    }

    static int[] conservativeHybridBatchGeometry(long totalMemoryBytes, int gpuLayers) {
        if (totalMemoryBytes <= 0L || totalMemoryBytes > 24L * GIB) {
            return new int[]{2048, 512};
        }
        int layers = Math.max(1, gpuLayers);
        if (layers <= 2) return new int[]{1024, 256};
        if (layers <= 4) return new int[]{512, 128};
        return new int[]{256, 64};
    }

    public static boolean lowMemoryUmaClassHost() {
        long installed = totalPhysicalMemoryBytes();
        return installed > 0L && installed <= 24L * GIB;
    }

    public static long currentAvailableMemoryBytes() {
        try {
            Path meminfo = Path.of("/proc/meminfo");
            if (Files.isRegularFile(meminfo)) {
                for (String line : Files.readAllLines(meminfo)) {
                    if (!line.startsWith("MemAvailable:")) continue;
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        long kib = Long.parseLong(parts[1]);
                        if (kib > 0L) return kib * 1024L;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                return Math.max(0L, os.getFreeMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static long totalPhysicalMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                return Math.max(0L, os.getTotalMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static Envelope read(Properties properties, String prefix) {
        return new Envelope(
                integer(properties.getProperty(prefix + "stable"), 0),
                integer(properties.getProperty(prefix + "unsafe"), 0),
                longValue(properties.getProperty(prefix + "unsafeStartAvailable"), 0L),
                longValue(properties.getProperty(prefix + "unsafeFailureAvailable"), 0L),
                longValue(properties.getProperty(prefix + "updated"), 0L),
                text(properties.getProperty(prefix + "reason")),
                parseLayerSet(properties.getProperty(prefix + "integrityInvalid"))
        );
    }

    private static void writeEnvelope(
            Properties properties,
            String prefix,
            int stable,
            int unsafe,
            long unsafeStart,
            long unsafeFailure,
            Set<Integer> invalid,
            String reason
    ) {
        properties.setProperty(prefix + "stable", Integer.toString(Math.max(0, stable)));
        properties.setProperty(prefix + "unsafe", Integer.toString(Math.max(0, unsafe)));
        properties.setProperty(prefix + "unsafeStartAvailable", Long.toString(Math.max(0L, unsafeStart)));
        properties.setProperty(prefix + "unsafeFailureAvailable", Long.toString(Math.max(0L, unsafeFailure)));
        properties.setProperty(prefix + "updated", Long.toString(System.currentTimeMillis()));
        properties.setProperty(prefix + "reason", text(reason));
        properties.setProperty(prefix + "integrityInvalid", layerSetText(invalid));
    }

    private static Properties load() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(STORE)) return properties;
        try (InputStream input = Files.newInputStream(STORE)) {
            properties.load(input);
        } catch (Exception ignored) {
        }
        return properties;
    }

    private static void save(Properties properties) {
        try {
            Path parent = STORE.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream output = Files.newOutputStream(
                    STORE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                properties.store(output, "Koil llama.cpp hybrid stability observations");
            }
        } catch (Exception ignored) {
            // Persistence must never make model startup fail. Live watchdog/canary
            // protections remain authoritative for the current activation.
        }
    }

    private static String key(Path modelFile, String device) {
        String normalizedModel = "unknown";
        long size = 0L;
        long modified = 0L;
        try {
            if (modelFile != null) {
                Path path = modelFile.toAbsolutePath().normalize();
                normalizedModel = path.toString();
                if (Files.isRegularFile(path)) {
                    size = Files.size(path);
                    modified = Files.getLastModifiedTime(path).toMillis();
                }
            }
        } catch (Exception ignored) {
        }
        String material = normalizedModel + "|" + size + "|" + modified + "|" + normalizedDevice(device);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private static String normalizedDevice(String value) {
        String clean = text(value).toLowerCase(java.util.Locale.ROOT);
        return clean.isBlank() ? "automatic" : clean;
    }

    private static Set<Integer> parseLayerSet(String value) {
        Set<Integer> layers = new TreeSet<>();
        for (String part : text(value).split(",")) {
            if (part.isBlank()) continue;
            int parsed = integer(part, 0);
            if (parsed > 0) layers.add(parsed);
        }
        return Set.copyOf(layers);
    }

    private static String layerSetText(Set<Integer> layers) {
        if (layers == null || layers.isEmpty()) return "";
        return layers.stream().filter(value -> value != null && value > 0)
                .sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static int integer(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(text(value)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long longValue(String value, long fallback) {
        try {
            return Math.max(0L, Long.parseLong(text(value)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    public record Envelope(
            int highestStableLayers,
            int firstUnsafeLayers,
            long unsafeStartAvailableBytes,
            long unsafeFailureAvailableBytes,
            long updatedEpochMillis,
            String reason,
            Set<Integer> integrityInvalidLayers
    ) {
        public Envelope {
            highestStableLayers = Math.max(0, highestStableLayers);
            firstUnsafeLayers = Math.max(0, firstUnsafeLayers);
            unsafeStartAvailableBytes = Math.max(0L, unsafeStartAvailableBytes);
            unsafeFailureAvailableBytes = Math.max(0L, unsafeFailureAvailableBytes);
            updatedEpochMillis = Math.max(0L, updatedEpochMillis);
            reason = text(reason);
            integrityInvalidLayers = integrityInvalidLayers == null
                    ? Set.of()
                    : Set.copyOf(integrityInvalidLayers);
        }

        public String summary() {
            String age = updatedEpochMillis <= 0L ? "unknown"
                    : Math.max(0L, Duration.between(
                    Instant.ofEpochMilli(updatedEpochMillis), Instant.now()).toSeconds()) + "s";
            return "stable_max=" + highestStableLayers
                    + " | pressure_unsafe_from=" + (firstUnsafeLayers > 0 ? Integer.toString(firstUnsafeLayers) : "unknown")
                    + " | integrity_invalid=" + layerSetText(integrityInvalidLayers)
                    + " | unsafe_start_available_mib=" + (unsafeStartAvailableBytes > 0L ? Long.toString(unsafeStartAvailableBytes / MIB) : "unknown")
                    + " | unsafe_failure_available_mib=" + (unsafeFailureAvailableBytes > 0L ? Long.toString(unsafeFailureAvailableBytes / MIB) : "unknown")
                    + " | age=" + age
                    + (reason.isBlank() ? "" : " | reason=" + reason);
        }
    }
}
