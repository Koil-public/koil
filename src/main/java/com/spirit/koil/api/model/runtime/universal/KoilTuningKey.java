package com.spirit.koil.api.model.runtime.universal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stable identity for measured execution evidence. State precision is part of the identity because
 * cache/state representation can materially change memory, throughput and backend behavior.
 */
public record KoilTuningKey(
        String hardwareFingerprint,
        String adapterId,
        String modelId,
        String architectureId,
        String sourceFormat,
        String quantization,
        String contextRegime,
        String statePrecisionRegime,
        KoilRuntimeBackend backend,
        String runtimeRevision
) {
    public KoilTuningKey {
        hardwareFingerprint = normalize(hardwareFingerprint);
        adapterId = normalize(adapterId);
        modelId = normalize(modelId);
        architectureId = normalize(architectureId);
        sourceFormat = normalize(sourceFormat);
        quantization = normalize(quantization);
        contextRegime = normalize(contextRegime);
        statePrecisionRegime = KoilStatePrecisionEvidence.normalizeRegime(statePrecisionRegime);
        backend = backend == null ? KoilRuntimeBackend.UNKNOWN : backend;
        runtimeRevision = normalize(runtimeRevision);
    }

    /** Compatibility constructor for evidence created before adaptive state precision existed. */
    public KoilTuningKey(
            String hardwareFingerprint,
            String adapterId,
            String modelId,
            String architectureId,
            String sourceFormat,
            String quantization,
            String contextRegime,
            KoilRuntimeBackend backend,
            String runtimeRevision
    ) {
        this(hardwareFingerprint, adapterId, modelId, architectureId, sourceFormat, quantization,
                contextRegime, KoilStatePrecisionEvidence.LEGACY_FP16, backend, runtimeRevision);
    }

    public static KoilTuningKey create(
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            String modelId,
            String quantization,
            int contextTokens,
            KoilRuntimeBackend backend,
            String runtimeRevision
    ) {
        return create(adapter, model, hardware, modelId, quantization, contextTokens,
                KoilStatePrecisionEvidence.LEGACY_FP16, backend, runtimeRevision);
    }

    public static KoilTuningKey create(
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            String modelId,
            String quantization,
            int contextTokens,
            String statePrecisionRegime,
            KoilRuntimeBackend backend,
            String runtimeRevision
    ) {
        return new KoilTuningKey(
                hardware == null ? "" : hardware.fingerprint(),
                adapter == null ? "" : adapter.id(),
                modelId,
                model == null ? "" : model.architectureId(),
                model == null || model.sourceFormat() == null ? "" : model.sourceFormat().name(),
                quantization,
                contextRegime(contextTokens),
                statePrecisionRegime,
                backend,
                runtimeRevision
        );
    }

    public static KoilTuningKey createWithContextRegime(
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            String modelId,
            String quantization,
            String contextRegime,
            KoilRuntimeBackend backend,
            String runtimeRevision
    ) {
        return createWithContextRegime(adapter, model, hardware, modelId, quantization, contextRegime,
                KoilStatePrecisionEvidence.LEGACY_FP16, backend, runtimeRevision);
    }

    public static KoilTuningKey createWithContextRegime(
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            String modelId,
            String quantization,
            String contextRegime,
            String statePrecisionRegime,
            KoilRuntimeBackend backend,
            String runtimeRevision
    ) {
        return new KoilTuningKey(
                hardware == null ? "" : hardware.fingerprint(),
                adapter == null ? "" : adapter.id(),
                modelId,
                model == null ? "" : model.architectureId(),
                model == null || model.sourceFormat() == null ? "" : model.sourceFormat().name(),
                quantization,
                contextRegime,
                statePrecisionRegime,
                backend,
                runtimeRevision
        );
    }

    public String identity() {
        String material = String.join("\n",
                "koil-universal-tuning-v2",
                hardwareFingerprint, adapterId, modelId, architectureId, sourceFormat,
                quantization, contextRegime, statePrecisionRegime, backend.name(), runtimeRevision);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toUnsignedString(material.hashCode(), 16);
        }
    }

    /** Query compatibility. UNKNOWN backend is a wildcard only on the query side. */
    public boolean matches(KoilTuningKey query) {
        if (query == null) return false;
        if (!same(hardwareFingerprint, query.hardwareFingerprint)) return false;
        if (!same(adapterId, query.adapterId)) return false;
        if (!same(modelId, query.modelId)) return false;
        if (!same(architectureId, query.architectureId)) return false;
        if (!same(sourceFormat, query.sourceFormat)) return false;
        if (!same(quantization, query.quantization)) return false;
        if (!same(contextRegime, query.contextRegime)) return false;
        if (!same(statePrecisionRegime, query.statePrecisionRegime)) return false;
        if (!same(runtimeRevision, query.runtimeRevision)) return false;
        return query.backend == KoilRuntimeBackend.UNKNOWN || backend == query.backend;
    }

    public static String contextRegime(int contextTokens) {
        int tokens = Math.max(0, contextTokens);
        if (tokens <= 2_048) return "ctx<=2k";
        if (tokens <= 4_096) return "ctx<=4k";
        if (tokens <= 8_192) return "ctx<=8k";
        if (tokens <= 16_384) return "ctx<=16k";
        if (tokens <= 32_768) return "ctx<=32k";
        if (tokens <= 65_536) return "ctx<=64k";
        if (tokens <= 131_072) return "ctx<=128k";
        return "ctx>128k";
    }

    private static boolean same(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
