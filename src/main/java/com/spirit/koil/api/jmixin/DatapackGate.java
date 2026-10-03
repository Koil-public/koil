package com.spirit.koil.api.jmixin;

import net.minecraft.server.MinecraftServer;

import java.util.Set;

public final class DatapackGate {

    private static volatile Set<String> enabled = Set.of();

    private DatapackGate() {
    }

    public static void update(MinecraftServer server) {
        enabled = Set.copyOf(
            server.getDataPackManager().getEnabledNames()
        );
    }

    public static void clear() {
        enabled = Set.of();
    }

//    public static boolean isEnabled(String id) {
//        return enabled.contains(id);
//    }

    public static boolean isEnabled(String id) {
        return true;
    }
}
