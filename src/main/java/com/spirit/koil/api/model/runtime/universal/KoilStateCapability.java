package com.spirit.koil.api.model.runtime.universal;

import java.util.Set;

/** Declared persistent-state support for one internal execution adapter. */
public record KoilStateCapability(
        String adapterId,
        KoilModelStateKind stateKind,
        KoilCapabilitySupport support,
        Set<KoilRuntimeBackend> backends,
        String evidence
) {
    public KoilStateCapability {
        adapterId = adapterId == null ? "" : adapterId.strip().toLowerCase(java.util.Locale.ROOT);
        stateKind = stateKind == null ? KoilModelStateKind.ATTENTION_KV : stateKind;
        support = support == null ? KoilCapabilitySupport.UNKNOWN : support;
        backends = backends == null ? Set.of() : Set.copyOf(backends);
        evidence = evidence == null ? "" : evidence.strip();
    }

    public KoilCapabilitySupport supportOn(KoilRuntimeBackend backend) {
        if (support != KoilCapabilitySupport.SUPPORTED) return support;
        if (backend == null || backend == KoilRuntimeBackend.UNKNOWN) return KoilCapabilitySupport.UNKNOWN;
        return backends.contains(backend) ? KoilCapabilitySupport.SUPPORTED : KoilCapabilitySupport.UNSUPPORTED;
    }
}
