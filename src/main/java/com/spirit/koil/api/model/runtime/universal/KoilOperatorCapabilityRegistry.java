package com.spirit.koil.api.model.runtime.universal;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Formal operator coverage registry. Architecture knowledge never implies execution support.
 * Entries are adapter declarations and should be upgraded only from verified runtime evidence.
 */
public final class KoilOperatorCapabilityRegistry {
    private final Map<String, Map<KoilOperatorKind, KoilOperatorCapability>> byAdapter = new LinkedHashMap<>();

    public KoilOperatorCapabilityRegistry register(KoilOperatorCapability capability) {
        if (capability == null || capability.adapterId().isBlank()) return this;
        byAdapter.computeIfAbsent(normalize(capability.adapterId()), ignored -> new LinkedHashMap<>())
                .put(capability.operator(), capability);
        return this;
    }

    public KoilOperatorCapability resolve(String adapterId, KoilOperatorKind operator) {
        String id = normalize(adapterId);
        Map<KoilOperatorKind, KoilOperatorCapability> capabilities = byAdapter.get(id);
        if (capabilities == null || operator == null) {
            return unknown(id, operator, "adapter/operator has no registered capability evidence");
        }
        return capabilities.getOrDefault(operator,
                unknown(id, operator, "operator has no registered capability evidence for adapter"));
    }

    public static KoilOperatorCapabilityRegistry defaultRegistry() {
        KoilOperatorCapabilityRegistry registry = new KoilOperatorCapabilityRegistry();
        Set<KoilRuntimeBackend> llamaBackends = EnumSet.of(
                KoilRuntimeBackend.CPU,
                KoilRuntimeBackend.CUDA,
                KoilRuntimeBackend.VULKAN,
                KoilRuntimeBackend.METAL,
                KoilRuntimeBackend.ROCM,
                KoilRuntimeBackend.HIP);

        // Conservative primitive coverage already exercised by Koil's llama.cpp adapter path.
        registerSupported(registry, "llama_cpp", llamaBackends,
                KoilOperatorKind.EMBEDDING,
                KoilOperatorKind.RMS_NORM,
                KoilOperatorKind.LAYER_NORM,
                KoilOperatorKind.GEMM,
                KoilOperatorKind.GEMV,
                KoilOperatorKind.ROPE,
                KoilOperatorKind.ATTENTION,
                KoilOperatorKind.GROUPED_QUERY_ATTENTION,
                KoilOperatorKind.MULTI_QUERY_ATTENTION,
                KoilOperatorKind.MULTI_HEAD_ATTENTION,
                KoilOperatorKind.SLIDING_WINDOW_ATTENTION,
                KoilOperatorKind.SHORT_CONVOLUTION,
                KoilOperatorKind.STATE_SPACE_SCAN,
                KoilOperatorKind.MOE_ROUTING,
                KoilOperatorKind.EXPERT_EXECUTION,
                KoilOperatorKind.GATING,
                KoilOperatorKind.ACTIVATION,
                KoilOperatorKind.OUTPUT_PROJECTION);

        // These remain intentionally unknown until Koil has adapter/backend evidence for them.
        registry.register(new KoilOperatorCapability("llama_cpp", KoilOperatorKind.MULTI_HEAD_LATENT_ATTENTION,
                KoilCapabilitySupport.UNKNOWN, Set.of(), "no Koil-verified per-backend coverage declaration yet"));
        registry.register(new KoilOperatorCapability("llama_cpp", KoilOperatorKind.LINEAR_ATTENTION,
                KoilCapabilitySupport.UNKNOWN, Set.of(), "no Koil-verified per-backend coverage declaration yet"));
        registry.register(new KoilOperatorCapability("llama_cpp", KoilOperatorKind.DELTA_NET,
                KoilCapabilitySupport.UNKNOWN, Set.of(), "no Koil-verified per-backend coverage declaration yet"));
        registry.register(new KoilOperatorCapability("llama_cpp", KoilOperatorKind.FLASH_ATTENTION,
                KoilCapabilitySupport.UNKNOWN, Set.of(), "optional kernel path is runtime/configuration dependent"));
        return registry;
    }

    private static void registerSupported(KoilOperatorCapabilityRegistry registry, String adapterId,
                                          Set<KoilRuntimeBackend> backends, KoilOperatorKind... operators) {
        for (KoilOperatorKind operator : operators) {
            registry.register(new KoilOperatorCapability(adapterId, operator, KoilCapabilitySupport.SUPPORTED, backends,
                    "declared primitive coverage for Koil's internal " + adapterId + " adapter"));
        }
    }

    private static KoilOperatorCapability unknown(String adapterId, KoilOperatorKind operator, String evidence) {
        return new KoilOperatorCapability(adapterId,
                operator == null ? KoilOperatorKind.GEMM : operator,
                KoilCapabilitySupport.UNKNOWN, Set.of(), evidence);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
