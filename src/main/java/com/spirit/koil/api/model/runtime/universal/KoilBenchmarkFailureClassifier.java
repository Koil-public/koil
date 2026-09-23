package com.spirit.koil.api.model.runtime.universal;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Stable adapter-neutral failure taxonomy for benchmark/autotune evidence. */
public final class KoilBenchmarkFailureClassifier {
    private KoilBenchmarkFailureClassifier() {}

    public enum Kind {
        OUT_OF_MEMORY("out_of_memory"),
        TIMEOUT("timeout"),
        CANCELED("canceled"),
        BACKEND_ALLOCATION("backend_allocation"),
        BACKEND_FAILURE("backend_failure"),
        PROCESS_EXIT("process_exit"),
        RUNTIME_VALIDATION("runtime_validation"),
        INTEGRITY("integrity"),
        INVALID_MEASUREMENT("invalid_measurement"),
        UNKNOWN("unknown");

        private final String id;
        Kind(String id) { this.id = id; }
        public String id() { return id; }
    }

    public static Kind classify(Throwable failure) {
        if (failure == null) return Kind.UNKNOWN;
        Throwable root = root(failure);
        if (root instanceof CancellationException || root instanceof InterruptedException) return Kind.CANCELED;
        if (root instanceof TimeoutException) return Kind.TIMEOUT;
        String text = (root.getClass().getName() + " " + safe(root.getMessage())).toLowerCase(Locale.ROOT);
        if (containsAny(text, "outofmemory", "out of memory", "oom", "cannot allocate memory", "memory allocation failed")) {
            return Kind.OUT_OF_MEMORY;
        }
        if (containsAny(text, "timeout", "timed out", "deadline")) return Kind.TIMEOUT;
        if (containsAny(text, "integrity", "canary", "corrupt")) return Kind.INTEGRITY;
        if (containsAny(text, "allocation", "alloc failed", "failed to allocate", "vram")) return Kind.BACKEND_ALLOCATION;
        if (containsAny(text, "vulkan", "cuda", "rocm", "hip", "metal", "backend")) return Kind.BACKEND_FAILURE;
        if (containsAny(text, "exit code", "process exited", "sigterm", "sigkill", "native process")) return Kind.PROCESS_EXIT;
        if (containsAny(text, "validation", "placement", "gpu layers")) return Kind.RUNTIME_VALIDATION;
        if (containsAny(text, "invalid benchmark", "invalid sample", "invalid measurement")) return Kind.INVALID_MEASUREMENT;
        return Kind.UNKNOWN;
    }

    public static String id(Throwable failure) {
        return classify(failure).id();
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
