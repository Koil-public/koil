package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

final class VanillaPerformanceProvider implements PerformanceOptimizationProvider {
    @Override
    public String id() {
        return "vanilla";
    }

    @Override
    public String label() {
        return "Minecraft Options";
    }

    @Override
    public boolean installed() {
        return true;
    }

    @Override
    public boolean configAvailable() {
        return true;
    }

    @Override
    public String status() {
        return "live options available";
    }

    @Override
    public List<PerformanceSettingDescriptor> settings(MinecraftClient client, PerformanceSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasVerifiedVideoOptions()) {
            return List.of();
        }
        List<PerformanceSettingDescriptor> settings = new ArrayList<>();
        settings.add(setting("render_distance", "Render distance", "chunk/render", String.valueOf(snapshot.renderDistance()), String.valueOf(Math.max(5, snapshot.renderDistance() - 2)), "safe", "Reduces chunk rendering and chunk mesh pressure."));
        settings.add(setting("simulation_distance", "Simulation distance", "cpu/tick", String.valueOf(snapshot.simulationDistance()), String.valueOf(Math.max(4, snapshot.simulationDistance() - 2)), "safe", "Reduces active simulation and tick work."));
        settings.add(setting("entity_distance", "Entity distance", "entity/render", format(snapshot.entityDistanceScale()), "0.75", "safe", "Reduces distant entity rendering pressure."));
        settings.add(setting("max_fps", "Max FPS", "frame pacing", maxFps(snapshot.maxFps()), snapshot.worldType().equals("server") ? "120" : "144", "optional", "Caps frame output for steadier frame pacing."));
        settings.add(setting("clouds", "Clouds", "render", snapshot.cloudsMode(), "off", "optional", "Removes optional cloud rendering cost."));
        settings.add(setting("mipmaps", "Mipmaps", "memory/vram", String.valueOf(snapshot.mipmapLevels()), String.valueOf(Math.max(0, snapshot.mipmapLevels() - 1)), snapshot.memoryPressure() > 0.80D ? "caution" : "optional", "Lowers texture memory and reload pressure."));
        settings.add(setting("particles", "Particles", "effects/render", snapshot.particlesMode(), snapshot.entityCount() > 260 ? "minimal" : "decreased", "optional", "Reduces effect rendering and update cost during combat, entities, and particle-heavy scenes."));
        settings.add(setting("graphics_mode", "Graphics mode", "render", snapshot.graphicsMode(), "fast", "caution", "Reduces expensive visual features when GPU or shader pressure is detected."));
        settings.add(setting("smooth_lighting", "Smooth lighting", "chunk/render", String.valueOf(snapshot.smoothLighting()), "false", "optional", "Reduces chunk rebuild and lighting cost."));
        settings.add(setting("biome_blend", "Biome blend", "chunk/render", String.valueOf(snapshot.biomeBlend()), "0", "optional", "Reduces per-chunk color blending work."));
        settings.add(setting("entity_shadows", "Entity shadows", "entity/render", String.valueOf(snapshot.entityShadows()), "false", "optional", "Disables small per-entity shadow rendering cost."));
        settings.add(setting("vsync", "VSync", "frame pacing", String.valueOf(snapshot.vsync()), "false", "optional", "Helps separate display pacing from actual render/tick stalls during diagnostics."));
        return settings;
    }

    @Override
    public PerformanceProviderApplyResult apply(MinecraftClient client, PerformanceSettingDescriptor setting) {
        if (client == null) {
            return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, "No client available.");
        }
        PerformanceRecommendation recommendation = new PerformanceRecommendation(
                "provider-" + setting.settingId(),
                setting.label(),
                setting.description(),
                PerformanceBottleneck.UNKNOWN,
                PerformanceRecommendation.Severity.OPTIONAL,
                true,
                setting.settingId(),
                setting.currentValue(),
                setting.recommendedValue()
        );
        PerformanceConfigApplier.ApplyResult result = PerformanceConfigApplier.applySafe(client, List.of(recommendation));
        return new PerformanceProviderApplyResult(id(), setting.settingId(), result.changed(), result.changed(), false, result.message());
    }

    private PerformanceSettingDescriptor setting(String id, String label, String category, String current, String recommended, String risk, String description) {
        return new PerformanceSettingDescriptor(id(), id, label, category, current, recommended, risk, true, false, "options.txt", description);
    }

    private String maxFps(int value) {
        return value >= 260 ? "unlimited" : String.valueOf(value);
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
