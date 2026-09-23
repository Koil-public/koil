package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionDecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionEvidence;

/** Exact precision compatibility gate for replaying measured launch geometry. */
final class LlamaCppStatePrecisionCompatibility {
    private LlamaCppStatePrecisionCompatibility() {}

    static String regime(KoilStatePrecisionDecision precision) {
        if (precision == null) return KoilStatePrecisionEvidence.LEGACY_FP16;
        return KoilStatePrecisionEvidence.regime(
                precision.keyPrecision().name(), precision.valuePrecision().name());
    }

    static boolean matches(String expectedRegime, KoilStatePrecisionDecision actual) {
        if (expectedRegime == null || expectedRegime.isBlank()) return true;
        return KoilStatePrecisionEvidence.normalizeRegime(expectedRegime).equals(regime(actual));
    }

    static boolean nativeStoreEligible(KoilStatePrecisionDecision actual) {
        String value = regime(actual);
        return KoilStatePrecisionEvidence.LEGACY_FP16.equals(value)
                || "k=q8_block,v=fp16".equals(value);
    }
}
