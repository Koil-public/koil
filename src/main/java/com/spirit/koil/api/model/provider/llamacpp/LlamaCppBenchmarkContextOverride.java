package com.spirit.koil.api.model.provider.llamacpp;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-local context target used only by the MAX benchmark harness.
 *
 * <p>The target is an upper bound fed through the normal startup memory policy. It never bypasses
 * llama.cpp's memory-fit logic. If the runtime cannot safely hold the requested context, startup
 * will clamp to a smaller context and the universal benchmark adapter rejects that candidate as a
 * context-regime mismatch rather than persisting misleading evidence.</p>
 */
public final class LlamaCppBenchmarkContextOverride {
    private static final AtomicInteger ACTIVE = new AtomicInteger(0);

    private LlamaCppBenchmarkContextOverride() {}

    public static void set(int contextTokens) {
        ACTIVE.set(Math.max(0, contextTokens));
    }

    public static void clear() {
        ACTIVE.set(0);
    }

    public static OptionalInt current() {
        int value = ACTIVE.get();
        return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
    }

    /** Apply the benchmark target without ever exceeding the model/configured context ceiling. */
    public static int applyConfiguredContext(int configuredContextTokens) {
        int configured = Math.max(512, configuredContextTokens);
        int requested = ACTIVE.get();
        if (requested <= 0) return configured;
        return Math.max(512, Math.min(configured, requested));
    }
}
