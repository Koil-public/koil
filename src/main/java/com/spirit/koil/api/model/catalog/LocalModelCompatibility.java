package com.spirit.koil.api.model.catalog;

import com.spirit.koil.api.model.hardware.HardwareCapabilityReport;

public record LocalModelCompatibility(Level level, String label, String detail) {
    public LocalModelCompatibility {
        level = level == null ? Level.UNKNOWN : level;
        label = label == null ? "Compatibility unknown" : label;
        detail = detail == null ? "" : detail;
    }

    public static LocalModelCompatibility evaluate(
            LocalModelCatalogEntry entry,
            HardwareCapabilityReport report,
            long remainingDownloadBytes
    ) {
        if (entry == null) {
            return new LocalModelCompatibility(Level.UNKNOWN, "Unknown", "Run /model diagnostics.");
        }
        if (!entry.runnable()) {
            if (LocalModelCatalog.canResolveForInstall(entry)) {
                return new LocalModelCompatibility(
                        Level.UNKNOWN,
                        "Resolvable",
                        "Koil can resolve a verified GGUF implementation from Hugging Face when installation is requested."
                );
            }
            return new LocalModelCompatibility(
                    Level.UNAVAILABLE,
                    "Catalog only",
                    entry.canonical().unavailableReason()
            );
        }
        if (report == null) {
            return new LocalModelCompatibility(Level.UNKNOWN, "Unknown", "Run /model diagnostics.");
        }
        if (report.freeStorageBytes() > 0L && remainingDownloadBytes > report.freeStorageBytes()) {
            return new LocalModelCompatibility(
                    Level.STORAGE_BLOCKED,
                    "Insufficient storage",
                    "The remaining download is larger than measured free storage."
            );
        }
        if (report.installedMemoryBytes() <= 0L) {
            return new LocalModelCompatibility(Level.UNKNOWN, "Unknown", "Installed memory was not measured.");
        }
        if (report.installedMemoryBytes() < entry.estimatedMinimumMemoryBytes()) {
            return new LocalModelCompatibility(
                    Level.NOT_RECOMMENDED,
                    "Not recommended",
                    "Installed memory is below this model's minimum guidance."
            );
        }
        if (report.installedMemoryBytes() < entry.estimatedRecommendedMemoryBytes()) {
            return new LocalModelCompatibility(
                    Level.SUPPORTED_WITH_LIMITS,
                    "Supported",
                    "Installed memory meets minimum guidance but not recommended guidance."
            );
        }
        return new LocalModelCompatibility(
                Level.RECOMMENDED,
                "Recommended",
                "Installed memory meets the model's recommended guidance."
        );
    }

    public enum Level {
        RECOMMENDED,
        SUPPORTED_WITH_LIMITS,
        NOT_RECOMMENDED,
        STORAGE_BLOCKED,
        UNAVAILABLE,
        UNKNOWN
    }
}
