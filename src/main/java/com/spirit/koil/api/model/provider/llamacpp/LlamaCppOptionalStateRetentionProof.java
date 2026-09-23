package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceAdmission;
import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceKind;

import java.util.Map;

public final class LlamaCppOptionalStateRetentionProof {
    private LlamaCppOptionalStateRetentionProof() {}

    public static void main(String[] args) {
        if (LlamaCppOptionalStateRetention.shouldEvict(Map.of())) {
            throw new IllegalStateException("missing live evidence must not evict optional state");
        }
        Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> allowed = Map.of(
                KoilOptionalResourceKind.CACHE_GROWTH,
                new KoilOptionalResourceAdmission(KoilOptionalResourceKind.CACHE_GROWTH, true, 128L * 1024L * 1024L, "admitted")
        );
        if (LlamaCppOptionalStateRetention.shouldEvict(allowed)) {
            throw new IllegalStateException("admitted cache growth must retain optional seed state");
        }
        Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> denied = Map.of(
                KoilOptionalResourceKind.CACHE_GROWTH,
                new KoilOptionalResourceAdmission(KoilOptionalResourceKind.CACHE_GROWTH, false, 0L, "live memory constrained")
        );
        if (!LlamaCppOptionalStateRetention.shouldEvict(denied)) {
            throw new IllegalStateException("explicit live denial must evict optional seed state");
        }
        if (!"live memory constrained".equals(LlamaCppOptionalStateRetention.reason(denied))) {
            throw new IllegalStateException("retention reason must preserve universal policy evidence");
        }
        System.out.println("llama.cpp optional state retention proof passed");
    }
}
