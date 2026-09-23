package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilStatePrecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionDecision;

import java.util.ArrayList;
import java.util.List;

/** Maps universal state precision semantics to llama.cpp command-line arguments. */
final class LlamaCppStatePrecisionArguments {
    private LlamaCppStatePrecisionArguments() {}

    static List<String> forDecision(KoilStatePrecisionDecision decision) {
        if (decision == null) return List.of();
        List<String> args = new ArrayList<>(4);
        String keyType = nativeType(decision.keyPrecision());
        String valueType = nativeType(decision.valuePrecision());
        if (!keyType.isBlank()) {
            args.add("--cache-type-k");
            args.add(keyType);
        }
        if (!valueType.isBlank()) {
            args.add("--cache-type-v");
            args.add(valueType);
        }
        return List.copyOf(args);
    }

    private static String nativeType(KoilStatePrecision precision) {
        if (precision == null) return "";
        return switch (precision) {
            case FP32 -> "f32";
            case FP16 -> "f16";
            case BF16 -> "bf16";
            case Q8_BLOCK -> "q8_0";
            case Q4_BLOCK -> "q4_0";
            case AUTO -> "";
        };
    }
}
