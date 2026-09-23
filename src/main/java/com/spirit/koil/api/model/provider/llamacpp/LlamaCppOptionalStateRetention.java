package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceAdmission;
import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceKind;

import java.util.Map;

/**
 * Decides whether optional native cache state may remain resident. Missing live
 * evidence is not a revocation; only an explicit live CACHE_GROWTH denial is.
 */
final class LlamaCppOptionalStateRetention {
    private LlamaCppOptionalStateRetention() {}

    static boolean shouldEvict(Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> liveAdmissions) {
        if (liveAdmissions == null || liveAdmissions.isEmpty()) return false;
        KoilOptionalResourceAdmission admission = liveAdmissions.get(KoilOptionalResourceKind.CACHE_GROWTH);
        return admission != null && !admission.allowed();
    }

    static String reason(Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> liveAdmissions) {
        if (liveAdmissions == null) return "live cache policy unavailable";
        KoilOptionalResourceAdmission admission = liveAdmissions.get(KoilOptionalResourceKind.CACHE_GROWTH);
        if (admission == null) return "live cache policy unavailable";
        return admission.reason();
    }
}
