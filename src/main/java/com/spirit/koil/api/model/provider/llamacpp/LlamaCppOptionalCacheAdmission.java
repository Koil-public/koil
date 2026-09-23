package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceAdmission;
import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceKind;

import java.util.Map;

/** Resolves Koil's optional cache-growth ceiling for llama.cpp background cache work. */
final class LlamaCppOptionalCacheAdmission {
    private LlamaCppOptionalCacheAdmission() {}

    static KoilOptionalResourceAdmission resolve(
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> launch,
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> live
    ) {
        KoilOptionalResourceAdmission liveAdmission = admission(live);
        if (liveAdmission != null) return liveAdmission;
        KoilOptionalResourceAdmission launchAdmission = admission(launch);
        if (launchAdmission != null) return launchAdmission;
        return new KoilOptionalResourceAdmission(
                KoilOptionalResourceKind.CACHE_GROWTH,
                false,
                0L,
                "Koil did not provide optional cache-growth admission"
        );
    }

    static boolean allowed(
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> launch,
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> live
    ) {
        KoilOptionalResourceAdmission admission = resolve(launch, live);
        return admission.allowed() && admission.ceilingBytes() > 0L;
    }

    static long ceilingBytes(
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> launch,
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> live
    ) {
        KoilOptionalResourceAdmission admission = resolve(launch, live);
        return admission.allowed() ? admission.ceilingBytes() : 0L;
    }

    private static KoilOptionalResourceAdmission admission(
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> values
    ) {
        return values == null ? null : values.get(KoilOptionalResourceKind.CACHE_GROWTH);
    }
}
