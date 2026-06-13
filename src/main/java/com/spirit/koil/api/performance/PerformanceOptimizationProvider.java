package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.List;

public interface PerformanceOptimizationProvider {
    String id();

    String label();

    boolean installed();

    boolean configAvailable();

    String status();

    List<PerformanceSettingDescriptor> settings(MinecraftClient client, PerformanceSnapshot snapshot);

    PerformanceProviderApplyResult apply(MinecraftClient client, PerformanceSettingDescriptor setting);
}
