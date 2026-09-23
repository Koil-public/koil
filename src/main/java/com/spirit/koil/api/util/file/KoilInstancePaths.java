package com.spirit.koil.api.util.file;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class KoilInstancePaths {
    private static final String INSTANCE_ROOT_PROPERTY = "koil.instanceRoot";

    private KoilInstancePaths() {
    }

    public static Path instanceRoot() {
        String configuredRoot = System.getProperty(INSTANCE_ROOT_PROPERTY, "").trim();
        if (!configuredRoot.isEmpty()) {
            return Path.of(configuredRoot).toAbsolutePath().normalize();
        }
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    }

    public static Path automationRoot() {
        return automationRoot(instanceRoot());
    }

    public static Path modelRoot() {
        return modelRoot(instanceRoot());
    }

    public static Path automationRoot(Path instanceRoot) {
        return instanceRoot.toAbsolutePath().normalize().resolve("koil/sys/automation");
    }

    public static Path modelRoot(Path instanceRoot) {
        return instanceRoot.toAbsolutePath().normalize().resolve("koil/sys/model");
    }
}
