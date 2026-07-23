package com.spirit.koil.api.registry.definition;

/** Safety boundary reported for a definition edit on a specific Minecraft version. */
public enum ReloadClassification {
    HOT_RELOADABLE,
    RESTART_REQUIRED,
    UNSUPPORTED
}
