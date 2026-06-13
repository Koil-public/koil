package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PerformanceProfileManager {
    private PerformanceProfileManager() {
    }

    public static Map<String, Object> saveProfileSuggestion(MinecraftClient client, PerformanceProfileMode mode, PerformanceSnapshot snapshot) {
        PerformanceRuntimeContext context = PerformanceRuntimeContextService.capture(client);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("updatedAtMillis", System.currentTimeMillis());
        profile.put("profileKey", context.profileKey());
        profile.put("activeMode", mode.name());
        profile.put("suggestedProfile", context.suggestedProfile());
        profile.put("worldType", context.worldType());
        profile.put("worldName", context.worldName());
        profile.put("serverAddress", context.serverAddress());
        profile.put("dimension", context.dimension());
        profile.put("primaryBottleneck", snapshot.primaryBottleneck().name());
        profile.put("renderDistance", snapshot.renderDistance());
        profile.put("simulationDistance", snapshot.simulationDistance());
        PerformanceJsonStore.write(PerformancePaths.PERFORMANCE_PROFILES, profile);
        return profile;
    }
}
