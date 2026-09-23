package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelTensorDescriptor;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded tensor-layout summary recovered from artifact headers only. No tensor payload is loaded.
 */
public record KoilTensorManifestSummary(
        long tensorCount,
        long parameterCount,
        long storageBytes,
        int shardCount,
        Map<String, Long> tensorsByStorageType,
        Map<String, Long> parametersByStorageType,
        List<ModelTensorDescriptor> tensors,
        boolean complete,
        String evidence
) {
    public KoilTensorManifestSummary {
        tensorCount = Math.max(0L, tensorCount);
        parameterCount = Math.max(0L, parameterCount);
        storageBytes = Math.max(0L, storageBytes);
        shardCount = Math.max(0, shardCount);
        tensorsByStorageType = tensorsByStorageType == null ? Map.of() : Map.copyOf(tensorsByStorageType);
        parametersByStorageType = parametersByStorageType == null ? Map.of() : Map.copyOf(parametersByStorageType);
        tensors = tensors == null ? List.of() : List.copyOf(tensors);
        evidence = evidence == null ? "" : evidence.strip();
    }

    public static KoilTensorManifestSummary from(ModelArtifactInspection inspection) {
        if (inspection == null || !inspection.present()) {
            return new KoilTensorManifestSummary(0, 0, 0, 0, Map.of(), Map.of(), List.of(), false,
                    "artifact inspection unavailable");
        }
        Map<String, String> metadata = inspection.metadata();
        Map<String, Long> tensors = new LinkedHashMap<>();
        Map<String, Long> parameters = new LinkedHashMap<>();
        String prefix = "tensor_manifest.type.";
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) continue;
            String suffix;
            boolean tensorMetric;
            if (key.endsWith(".tensors")) {
                suffix = key.substring(prefix.length(), key.length() - ".tensors".length());
                tensorMetric = true;
            } else if (key.endsWith(".parameters")) {
                suffix = key.substring(prefix.length(), key.length() - ".parameters".length());
                tensorMetric = false;
            } else {
                continue;
            }
            long value = parseLong(entry.getValue());
            String type = suffix.toUpperCase(Locale.ROOT);
            if (tensorMetric) tensors.put(type, value);
            else parameters.put(type, value);
        }
        long manifestTensorCount = parseLong(metadata.get("tensor_manifest.tensor_count"));
        return new KoilTensorManifestSummary(
                manifestTensorCount > 0 ? manifestTensorCount : inspection.tensorCount(),
                parseLong(metadata.get("tensor_manifest.parameter_count")),
                parseLong(metadata.get("tensor_manifest.storage_bytes")),
                (int)Math.min(Integer.MAX_VALUE, parseLong(metadata.get("tensor_manifest.shard_count"))),
                tensors,
                parameters,
                inspection.tensors(),
                Boolean.parseBoolean(metadata.getOrDefault("tensor_manifest.complete", "false")),
                metadata.getOrDefault("tensor_manifest.evidence", inspection.evidence())
        );
    }

    public String dominantStorageType() {
        return parametersByStorageType.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseGet(() -> tensorsByStorageType.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("UNKNOWN"));
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try { return Math.max(0L, Long.parseLong(value.strip())); }
        catch (NumberFormatException ignored) { return 0L; }
    }
}
