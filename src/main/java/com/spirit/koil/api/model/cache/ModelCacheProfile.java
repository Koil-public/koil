package com.spirit.koil.api.model.cache;

/** Operator-selectable policy for local-model prompt/KV persistence. */
public enum ModelCacheProfile {
    AUTO,
    LOW_MEMORY,
    BALANCED,
    AGGRESSIVE,
    MAXIMUM;

    public static ModelCacheProfile configured() {
        String configured = System.getProperty("koil.model.cache.profile", "AUTO");
        try {
            return valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) {
            return AUTO;
        }
    }
}
