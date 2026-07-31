package com.spirit.koil.api.model.hardware;

import java.time.Instant;
import java.util.List;

public record HardwareCapabilityReport(
        Instant measuredAt,
        ModelHardwareTier tier,
        String operatingSystem,
        String architecture,
        int logicalCpuCount,
        int physicalCpuCount,
        String simdStatus,
        long installedMemoryBytes,
        long availableMemoryBytes,
        String gpuStatus,
        long gpuMemoryBytes,
        boolean cudaAvailable,
        boolean metalAvailable,
        String modelDrive,
        String driveType,
        long freeStorageBytes,
        long modelDirectoryBytes,
        long modelFileCount,
        boolean runtimeBinaryPresent,
        boolean runtimeArchitectureVerified,
        boolean modelDirectoryPresent,
        boolean runtimeValidationRequired,
        Double measuredSequentialReadMbPerSecond,
        List<String> limitations
) {
    public HardwareCapabilityReport {
        measuredAt = measuredAt == null ? Instant.now() : measuredAt;
        tier = tier == null ? ModelHardwareTier.UNSUPPORTED : tier;
        operatingSystem = value(operatingSystem);
        architecture = value(architecture);
        simdStatus = value(simdStatus);
        gpuStatus = value(gpuStatus);
        modelDrive = value(modelDrive);
        driveType = value(driveType);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
