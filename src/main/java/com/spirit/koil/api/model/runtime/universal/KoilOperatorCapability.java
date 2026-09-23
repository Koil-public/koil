package com.spirit.koil.api.model.runtime.universal;

import java.util.Set;

/** Declared execution coverage for one operator on one internal adapter. */
public record KoilOperatorCapability(
        String adapterId,
        KoilOperatorKind operator,
        KoilCapabilitySupport support,
        Set<KoilRuntimeBackend> backends,
        String evidence
) {
    public KoilOperatorCapability {
        adapterId = adapterId == null ? "" : adapterId.strip().toLowerCase(java.util.Locale.ROOT);
        operator = operator == null ? KoilOperatorKind.GEMM : operator;
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
