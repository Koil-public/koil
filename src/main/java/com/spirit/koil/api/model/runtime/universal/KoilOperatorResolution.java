package com.spirit.koil.api.model.runtime.universal;

/** Resolved adapter/backend status for one graph operator. */
public record KoilOperatorResolution(
        KoilOperatorKind operator,
        KoilCapabilitySupport adapterSupport,
        KoilCapabilitySupport backendSupport,
        String evidence
) {
    public KoilOperatorResolution {
        operator = operator == null ? KoilOperatorKind.GEMM : operator;
        adapterSupport = adapterSupport == null ? KoilCapabilitySupport.UNKNOWN : adapterSupport;
        backendSupport = backendSupport == null ? KoilCapabilitySupport.UNKNOWN : backendSupport;
        evidence = evidence == null ? "" : evidence.strip();
    }

    public boolean executable() {
        return adapterSupport == KoilCapabilitySupport.SUPPORTED
                && backendSupport == KoilCapabilitySupport.SUPPORTED;
    }
}
