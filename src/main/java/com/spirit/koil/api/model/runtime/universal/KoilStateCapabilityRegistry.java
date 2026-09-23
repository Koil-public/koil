package com.spirit.koil.api.model.runtime.universal;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Persistent-state execution coverage kept separate from operator coverage. */
public final class KoilStateCapabilityRegistry {
    private final Map<String, Map<KoilModelStateKind, KoilStateCapability>> byAdapter = new LinkedHashMap<>();

    public KoilStateCapabilityRegistry register(KoilStateCapability capability) {
        if (capability == null || capability.adapterId().isBlank()) return this;
        byAdapter.computeIfAbsent(normalize(capability.adapterId()), ignored -> new LinkedHashMap<>())
                .put(capability.stateKind(), capability);
        return this;
    }

    public KoilStateCapability resolve(String adapterId, KoilModelStateKind stateKind) {
        String id = normalize(adapterId);
        Map<KoilModelStateKind, KoilStateCapability> states = byAdapter.get(id);
        if (states == null || stateKind == null) return unknown(id, stateKind);
        return states.getOrDefault(stateKind, unknown(id, stateKind));
    }

    public static KoilStateCapabilityRegistry defaultRegistry() {
        KoilStateCapabilityRegistry registry = new KoilStateCapabilityRegistry();
        Set<KoilRuntimeBackend> llamaBackends = EnumSet.of(KoilRuntimeBackend.CPU, KoilRuntimeBackend.CUDA,
                KoilRuntimeBackend.VULKAN, KoilRuntimeBackend.METAL, KoilRuntimeBackend.ROCM, KoilRuntimeBackend.HIP);
        registerSupported(registry, llamaBackends, KoilModelStateKind.ATTENTION_KV, KoilModelStateKind.CONVOLUTION);
        for (KoilModelStateKind state : KoilModelStateKind.values()) {
            if (state == KoilModelStateKind.ATTENTION_KV || state == KoilModelStateKind.CONVOLUTION) continue;
            registry.register(new KoilStateCapability("llama_cpp", state, KoilCapabilitySupport.UNKNOWN, Set.of(),
                    "no Koil-verified persistent-state coverage declaration yet"));
        }
        return registry;
    }

    private static void registerSupported(KoilStateCapabilityRegistry registry, Set<KoilRuntimeBackend> backends,
                                          KoilModelStateKind... states) {
        for (KoilModelStateKind state : states) {
            registry.register(new KoilStateCapability("llama_cpp", state, KoilCapabilitySupport.SUPPORTED, backends,
                    "declared persistent-state coverage for Koil's internal llama_cpp adapter"));
        }
    }

    private static KoilStateCapability unknown(String adapterId, KoilModelStateKind stateKind) {
        return new KoilStateCapability(adapterId,
                stateKind == null ? KoilModelStateKind.ATTENTION_KV : stateKind,
                KoilCapabilitySupport.UNKNOWN, Set.of(), "state has no registered adapter capability evidence");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
