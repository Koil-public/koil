package com.spirit.koil.api.model.runtime.universal;

/** Resolved adapter/backend support for one persistent model-state kind. */
public record KoilStateResolution(
        KoilModelStateKind stateKind,
        KoilCapabilitySupport adapterSupport,
        KoilCapabilitySupport backendSupport,
        String evidence
) {
    public KoilStateResolution {
        stateKind = stateKind == null ? KoilModelStateKind.ATTENTION_KV : stateKind;
        adapterSupport = adapterSupport == null ? KoilCapabilitySupport.UNKNOWN : adapterSupport;
        backendSupport = backendSupport == null ? KoilCapabilitySupport.UNKNOWN : backendSupport;
        evidence = evidence == null ? "" : evidence.strip();
    }

    public boolean executable() {
        return adapterSupport == KoilCapabilitySupport.SUPPORTED
                && backendSupport == KoilCapabilitySupport.SUPPORTED;
    }
}
