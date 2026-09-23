package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceAdmission;
import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceKind;

import java.util.Map;

/** Dependency-light proof for live revocation of optional llama.cpp cache growth. */
public final class LlamaCppOptionalCacheAdmissionProof {
    private static final long MIB = 1024L * 1024L;

    private LlamaCppOptionalCacheAdmissionProof() {}

    public static void main(String[] args) {
        Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> launch = Map.of(
                KoilOptionalResourceKind.CACHE_GROWTH,
                new KoilOptionalResourceAdmission(KoilOptionalResourceKind.CACHE_GROWTH, true, 128L * MIB, "launch admitted")
        );
        Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> liveDenied = Map.of(
                KoilOptionalResourceKind.CACHE_GROWTH,
                new KoilOptionalResourceAdmission(KoilOptionalResourceKind.CACHE_GROWTH, false, 0L, "live critical pressure")
        );
        if (LlamaCppOptionalCacheAdmission.allowed(launch, liveDenied)) {
            throw new AssertionError("live denial must override launch-time cache-growth admission");
        }
        if (LlamaCppOptionalCacheAdmission.ceilingBytes(launch, liveDenied) != 0L) {
            throw new AssertionError("revoked live cache growth must expose a zero ceiling");
        }
        if (!LlamaCppOptionalCacheAdmission.allowed(launch, Map.of())) {
            throw new AssertionError("launch admission should be used before live evidence exists");
        }
        if (LlamaCppOptionalCacheAdmission.ceilingBytes(launch, Map.of()) != 128L * MIB) {
            throw new AssertionError("launch cache ceiling was not preserved");
        }
        System.out.println("llama.cpp optional cache admission proof passed");
    }
}
