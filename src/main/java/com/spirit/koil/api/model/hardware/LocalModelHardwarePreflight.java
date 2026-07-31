package com.spirit.koil.api.model.hardware;

import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.provider.colibri.ColibriConfiguration;
import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Conservative filesystem/system preflight. It never predicts generation
 * speed and never runs on the render thread.
 */
public final class LocalModelHardwarePreflight {
    private LocalModelHardwarePreflight() {
    }

    public static HardwareCapabilityReport scan(ColibriConfiguration configuration) {
        return scan(
                configuration != null && configuration.enabled(),
                configuration == null ? null : configuration.executable(),
                configuration == null ? null : configuration.modelDirectory()
        );
    }

    public static HardwareCapabilityReport scan(LocalModelSelection selection) {
        return scan(
                selection != null && selection.complete(),
                selection == null ? null : selection.runtimeExecutable(),
                selection == null || selection.modelFile() == null ? null : selection.modelFile().getParent()
        );
    }

    private static HardwareCapabilityReport scan(boolean enabled, Path executable, Path modelDirectory) {
        String os = System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "");
        String architecture = System.getProperty("os.arch", "unknown");
        int logicalCpuCount = Runtime.getRuntime().availableProcessors();
        long installedMemory = installedMemory();
        long availableMemory = availableMemory();
        boolean metal = os.toLowerCase(Locale.ROOT).contains("mac");
        boolean supportedArchitecture = isSupportedArchitecture(architecture);
        boolean runtimePresent = executable != null
                && Files.isRegularFile(executable)
                && Files.isExecutable(executable);
        boolean modelDirectoryPresent = modelDirectory != null && Files.isDirectory(modelDirectory);
        DirectoryMeasurement model = modelDirectoryPresent ? measureDirectory(modelDirectory) : new DirectoryMeasurement(0L, 0L);
        StorageMeasurement storage = measureStorage(modelDirectory);
        List<String> limitations = new ArrayList<>();
        limitations.add("physical CPU count is not available from the portable JVM preflight");
        limitations.add("SIMD support is not inferred from the architecture name");
        limitations.add("GPU model and VRAM require runtime/platform evidence and are currently unknown");
        limitations.add("drive type is not inferred; use the opt-in disk benchmark for measured throughput");
        limitations.add("runtime/model completeness remains subject to the provider's own validation");
        if (!runtimePresent) {
            limitations.add("configured runtime executable is missing or not executable");
        }
        if (!modelDirectoryPresent || model.fileCount == 0L) {
            limitations.add("configured model directory is missing or empty");
        }

        ModelHardwareTier tier;
        if (!enabled) {
            tier = ModelHardwareTier.NOT_CONFIGURED;
        } else if (!supportedArchitecture) {
            tier = ModelHardwareTier.UNSUPPORTED;
        } else if (!runtimePresent || !modelDirectoryPresent || model.fileCount == 0L) {
            tier = ModelHardwareTier.INCOMPLETE_INSTALLATION;
        } else if (model.bytes <= Math.max(1L, availableMemory) * 8L / 10L) {
            tier = ModelHardwareTier.FULLY_RESIDENT;
        } else if (model.bytes <= Math.max(1L, installedMemory) * 8L / 10L) {
            tier = ModelHardwareTier.MOSTLY_RESIDENT;
        } else if (model.bytes <= Math.max(1L, installedMemory) * 2L) {
            tier = ModelHardwareTier.HYBRID_RESIDENCY;
        } else {
            tier = ModelHardwareTier.DISK_STREAMING_MINIMUM;
        }

        return new HardwareCapabilityReport(
                Instant.now(),
                tier,
                os.trim(),
                architecture,
                logicalCpuCount,
                0,
                "not measured",
                installedMemory,
                availableMemory,
                "not measured",
                0L,
                false,
                metal,
                storage.path,
                "not detected",
                storage.freeBytes,
                model.bytes,
                model.fileCount,
                runtimePresent,
                false,
                modelDirectoryPresent,
                true,
                null,
                limitations
        );
    }

    private static DirectoryMeasurement measureDirectory(Path root) {
        AtomicLong bytes = new AtomicLong();
        AtomicLong files = new AtomicLong();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                files.incrementAndGet();
                try {
                    bytes.addAndGet(Files.size(path));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return new DirectoryMeasurement(bytes.get(), files.get());
    }

    private static StorageMeasurement measureStorage(Path configuredPath) {
        Path cursor = configuredPath == null ? Path.of(".").toAbsolutePath().normalize() : configuredPath.toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor)) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return new StorageMeasurement("unknown", 0L);
        }
        try {
            FileStore store = Files.getFileStore(cursor);
            return new StorageMeasurement(store.name() + " (" + store.type() + ")", store.getUsableSpace());
        } catch (IOException exception) {
            return new StorageMeasurement(cursor.toString(), 0L);
        }
    }

    private static long installedMemory() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean operatingSystem) {
                return Math.max(0L, operatingSystem.getTotalMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return Runtime.getRuntime().maxMemory();
    }

    private static long availableMemory() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean operatingSystem) {
                return Math.max(0L, operatingSystem.getFreeMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return Runtime.getRuntime().freeMemory();
    }

    private static boolean isSupportedArchitecture(String architecture) {
        String normalized = architecture == null ? "" : architecture.toLowerCase(Locale.ROOT);
        return normalized.equals("x86_64")
                || normalized.equals("amd64")
                || normalized.equals("aarch64")
                || normalized.equals("arm64");
    }

    private record DirectoryMeasurement(long bytes, long fileCount) {
    }

    private record StorageMeasurement(String path, long freeBytes) {
    }
}
