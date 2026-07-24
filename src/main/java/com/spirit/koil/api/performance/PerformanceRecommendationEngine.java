package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PerformanceRecommendationEngine {
    private PerformanceRecommendationEngine() {
    }

    public static List<PerformanceRecommendation> recommendFromBenchmark(MinecraftClient client, PerformanceProfileMode mode, PerformanceSnapshot snapshot, List<PerformanceBenchmarkPhaseResult> phases) {
        if (snapshot == null) {
            snapshot = PerformanceMonitor.latestSnapshot(client);
        }
        PerformanceHardwareAdvisor.HardwareClass hardware = PerformanceHardwareAdvisor.classify(client);
        List<PerformanceRecommendation> benchmark = benchmarkPowerPlan(mode, snapshot, hardware, phases);
        List<PerformanceRecommendation> normal = recommend(client, mode, snapshot);
        List<PerformanceRecommendation> merged = new ArrayList<>(benchmark);
        for (PerformanceRecommendation recommendation : normal) {
            if (merged.stream().noneMatch(existing -> existing.id().equals(recommendation.id()) || existing.settingKey().equals(recommendation.settingKey()))) {
                merged.add(recommendation);
            }
        }
        return PerformanceLearningService.adaptRecommendations(merged, snapshot);
    }

    public static List<PerformanceRecommendation> recommend(MinecraftClient client, PerformanceProfileMode mode, PerformanceSnapshot snapshot) {
        List<PerformanceRecommendation> recommendations = new ArrayList<>();
        if (snapshot == null) {
            snapshot = PerformanceMonitor.latestSnapshot(client);
        }
        int renderDistance = snapshot.renderDistance() <= 0 ? 12 : snapshot.renderDistance();
        int simulationDistance = snapshot.simulationDistance() <= 0 ? 8 : snapshot.simulationDistance();
        int maxFps = snapshot.maxFps() <= 0 ? 120 : snapshot.maxFps();
        List<PerformanceSettingDescriptor> providerSettings = PerformanceProviderRegistry.settings(client, snapshot).stream()
                .filter(setting -> !"vanilla".equals(setting.providerId()))
                .toList();
        Set<String> providerKeys = providerSettings.stream()
                .map(PerformanceSettingDescriptor::settingId)
                .collect(Collectors.toSet());
        Map<String, PerformanceSettingDescriptor> providerSettingMap = providerSettings.stream()
                .collect(Collectors.toMap(PerformanceSettingDescriptor::settingId, setting -> setting, (a, b) -> a, LinkedHashMap::new));
        PerformanceHardwareAdvisor.HardwareClass hardware = PerformanceHardwareAdvisor.classify(client);

        addHardwareProviderRecommendations(recommendations, providerSettingMap, hardware, snapshot);
        addChangedProviderRecommendations(recommendations, providerSettings, "sodium", PerformanceBottleneck.GPU,
                "Koil audited the installed Sodium config and found a performance setting that differs from the hardware-aware target.");
        addChangedProviderRecommendations(recommendations, providerSettings, "sodium-extra", PerformanceBottleneck.GPU,
                "Koil audited the installed Sodium Extra config and found a visual/update setting that can be reduced without disabling the optimization mod.");
        addChangedProviderRecommendations(recommendations, providerSettings, "entityculling", PerformanceBottleneck.ENTITY_TICK,
                "Koil audited EntityCulling with direction-aware rules so culling stays enabled instead of flipping skip flags incorrectly.");
        if (snapshot.shaderModInstalled() || providerSettings.stream().anyMatch(setting -> "shaderpack".equals(setting.providerId()))) {
            addChangedProviderRecommendations(recommendations, providerSettings, "shaderpack", PerformanceBottleneck.SHADER_RENDER,
                    "Koil found shaderpack option-file values that are expensive when maxed out, such as shadow distance, shadow resolution, reflections, clouds, or water effects.");
        }
        addProfileRecommendations(recommendations, mode, snapshot, hardware, renderDistance, simulationDistance, maxFps);

        if (snapshot.modLoadPressure() > 0.80D) {
            recommendations.add(new PerformanceRecommendation(
                    "modpack-load-review",
                    "Review modpack load pressure",
                    "The loaded mod count is high enough that startup, memory, and mixin/config load cost may be part of the slowdown. Koil will not disable mods automatically.",
                    PerformanceBottleneck.MOD_OVERLOAD,
                    PerformanceRecommendation.Severity.MANUAL_REVIEW,
                    false,
                    "loaded_mods",
                    String.valueOf(snapshot.loadedModCount()),
                    "manual review"
            ));
        }

        if (snapshot.memoryPressure() > 0.90D) {
            recommendations.add(new PerformanceRecommendation(
                    "memory-pressure-avoid-increase",
                    "Do not increase allocated memory yet",
                    "Java is already close to its memory limit and high allocation can increase garbage collection stutters. Reduce memory pressure first.",
                    PerformanceBottleneck.MEMORY,
                    PerformanceRecommendation.Severity.MANUAL_REVIEW,
                    false,
                    "memory_allocation",
                    snapshot.usedMemoryMb() + "/" + snapshot.maxMemoryMb() + " MB",
                    "manual review"
            ));
            recommendations.add(new PerformanceRecommendation(
                    "reduce-mipmaps-memory",
                    "Lower mipmaps one step",
                    "High memory pressure can come from texture and resourcepack load. Lowering mipmaps reduces texture memory without fully disabling visuals.",
                    PerformanceBottleneck.MEMORY,
                    PerformanceRecommendation.Severity.CAUTION,
                    true,
                    "mipmaps",
                    "current",
                    "one step lower"
            ));
        }

        if (snapshot.gcPressure() > 0.50D) {
            recommendations.add(new PerformanceRecommendation(
                    "gc-pressure-reduce-texture-load",
                    "Reduce texture and reload pressure",
                    "Garbage collection activity is high during the sample window. Reducing texture load and avoiding excessive memory allocation is safer than blindly increasing memory.",
                    PerformanceBottleneck.MEMORY,
                    PerformanceRecommendation.Severity.CAUTION,
                    true,
                    "mipmaps",
                    "current",
                    "one step lower"
            ));
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-animate-visible-textures",
                    "Enable Sodium visible-only texture animation",
                    "Garbage collection and memory pressure are elevated. Sodium can avoid updating animated textures that are not visible, reducing update and texture churn.",
                    PerformanceBottleneck.MEMORY,
                    PerformanceRecommendation.Severity.SAFE,
                    "performance.animate_only_visible_textures",
                    "current",
                    "true"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-extra-water-animation",
                    "Disable Sodium Extra water animation",
                    "Texture animation work can contribute to memory and frame-time pressure. This disables one high-frequency animation source while the game is struggling.",
                    PerformanceBottleneck.MEMORY,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    "animation_settings.water",
                    "current",
                    "false"
            );
            if (snapshot.smoothLighting()) {
                recommendations.add(new PerformanceRecommendation(
                        "gc-pressure-disable-smooth-lighting",
                        "Disable smooth lighting during GC pressure",
                        "Smooth lighting increases chunk rebuild and visual allocation pressure. Turning it off can reduce reload spikes when garbage collection is already active.",
                        PerformanceBottleneck.MEMORY,
                        PerformanceRecommendation.Severity.OPTIONAL,
                        true,
                        "smooth_lighting",
                        "true",
                        "false"
                ));
            }
        }

        if (snapshot.primaryBottleneck() == PerformanceBottleneck.ENTITY_TICK || snapshot.entityCount() > 180) {
            recommendations.add(new PerformanceRecommendation(
                    "lower-entity-distance",
                    "Lower entity distance",
                    "Entities are likely contributing to frame spikes or tick pressure. Lowering entity distance keeps nearby gameplay visible while reducing distant entity rendering.",
                    PerformanceBottleneck.ENTITY_TICK,
                    PerformanceRecommendation.Severity.SAFE,
                    true,
                    "entity_distance",
                    "current",
                    "0.75"
            ));
            recommendations.add(new PerformanceRecommendation(
                    "lower-simulation-distance",
                    "Lower simulation distance",
                    "The CPU appears to be struggling with active world simulation. Lower simulation distance reduces tick work before reducing visual render distance.",
                    PerformanceBottleneck.CPU,
                    PerformanceRecommendation.Severity.SAFE,
                    true,
                    "simulation_distance",
                    String.valueOf(simulationDistance),
                    String.valueOf(Math.max(4, simulationDistance - 2))
            ));
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-entity-culling",
                    "Enable Sodium entity culling",
                    "Entity pressure is visible. Sodium entity culling skips hidden entities before they cost render time.",
                    PerformanceBottleneck.ENTITY_TICK,
                    PerformanceRecommendation.Severity.SAFE,
                    "performance.use_entity_culling",
                    "current",
                    "true"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-extra-item-frame-rendering",
                    "Disable distant item frame rendering",
                    "Decoration-heavy areas can create entity-render spikes. Sodium Extra can turn off item frame rendering during optimization.",
                    PerformanceBottleneck.ENTITY_TICK,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    "render_settings.item_frame",
                    "current",
                    "false"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-extra-armor-stand-rendering",
                    "Disable armor stand rendering",
                    "Armor stands are common in entity-heavy decorative builds. Disabling them is reversible and can reduce entity render pressure.",
                    PerformanceBottleneck.ENTITY_TICK,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    "render_settings.armor_stand",
                    "current",
                    "false"
            );
            if (!snapshot.particlesMode().toLowerCase().contains("minimal")) {
                recommendations.add(new PerformanceRecommendation(
                        "reduce-particles-for-entity-pressure",
                        "Reduce particle density",
                        "High entity and gameplay pressure often overlaps with particles, effects, and combat visuals. Reducing particles lowers client-side render/update cost without changing world rules.",
                        PerformanceBottleneck.ENTITY_TICK,
                        PerformanceRecommendation.Severity.OPTIONAL,
                        true,
                        "particles",
                        snapshot.particlesMode(),
                        snapshot.entityCount() > 260 ? "minimal" : "decreased"
                ));
            }
        }

        boolean severeVisualPressure = snapshot.averageFps() < 35.0D
                || snapshot.onePercentLowFps() < 24.0D
                || snapshot.maxFrameTimeMs() > 110.0D
                || snapshot.shaderPressure() > 0.70D;
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.SHADER_RENDER || snapshot.primaryBottleneck() == PerformanceBottleneck.GPU || snapshot.shaderPressure() > 0.45D || snapshot.uiFramePressure() > 0.70D) {
            recommendations.add(new PerformanceRecommendation(
                    "lower-render-distance",
                    "Lower render distance",
                    "Rendering is the likely bottleneck. Lowering render distance reduces visible chunk load while preserving simulation behavior.",
                    snapshot.primaryBottleneck(),
                    PerformanceRecommendation.Severity.SAFE,
                    true,
                    "render_distance",
                    String.valueOf(renderDistance),
                    String.valueOf(renderReductionTarget(renderDistance, hardware, snapshot, 2))
            ));
            if (severeVisualPressure && !snapshot.cloudsMode().toLowerCase().contains("off")) {
                recommendations.add(new PerformanceRecommendation(
                        "disable-clouds",
                        "Disable clouds",
                        "Cloud rendering is optional visual work. Koil only disables it when measured render pressure is severe enough to justify a visible tradeoff.",
                        PerformanceBottleneck.GPU,
                        PerformanceRecommendation.Severity.OPTIONAL,
                        true,
                        "clouds",
                        snapshot.cloudsMode(),
                        "off"
                ));
            }
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-leaves-fast",
                    severeVisualPressure ? "Set Sodium leaves quality to fast" : "Set Sodium leaves quality to default",
                    "Koil prefers backend Sodium optimizations first and only moves leaves to fast when measured render pressure is severe.",
                    PerformanceBottleneck.GPU,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    "quality.leaves_quality",
                    "current",
                    severeVisualPressure ? "FAST" : "DEFAULT"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-weather-fast",
                    severeVisualPressure ? "Set Sodium weather quality to fast" : "Set Sodium weather quality to default",
                    "Weather rendering can become expensive during rain or snow. Koil keeps it at default unless the benchmark shows severe render pressure.",
                    PerformanceBottleneck.GPU,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    "quality.weather_quality",
                    "current",
                    severeVisualPressure ? "FAST" : "DEFAULT"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-advanced-staging-buffers",
                    "Enable Sodium advanced staging buffers",
                    "Sodium's advanced staging buffers improve GPU upload behavior on supported systems and can reduce render pipeline stalls.",
                    PerformanceBottleneck.GPU,
                    PerformanceRecommendation.Severity.SAFE,
                    "advanced.use_advanced_staging_buffers",
                    "current",
                    "true"
            );
            if (severeVisualPressure && snapshot.entityShadows()) {
                recommendations.add(new PerformanceRecommendation(
                        "disable-entity-shadows",
                        "Disable entity shadows",
                        "Entity shadows add extra render work. Koil only disables them when severe measured render pressure makes the visual tradeoff worthwhile.",
                        PerformanceBottleneck.GPU,
                        PerformanceRecommendation.Severity.OPTIONAL,
                        true,
                        "entity_shadows",
                        "true",
                        "false"
                ));
            }
            if (severeVisualPressure && !snapshot.graphicsMode().toLowerCase().contains("fast")) {
                recommendations.add(new PerformanceRecommendation(
                        "fast-graphics-mode",
                        "Use fast graphics mode",
                        "Fast graphics is a visible quality tradeoff, so Koil only recommends it when benchmark/render pressure is severe.",
                        PerformanceBottleneck.GPU,
                        PerformanceRecommendation.Severity.CAUTION,
                        true,
                        "graphics_mode",
                        snapshot.graphicsMode(),
                        "fast"
                ));
            }
            if (severeVisualPressure && snapshot.chunkStress() > 0.65D && snapshot.smoothLighting()) {
                recommendations.add(new PerformanceRecommendation(
                        "disable-smooth-lighting-render-pressure",
                        "Disable smooth lighting",
                        "Smooth lighting increases chunk lighting and visual rebuild work. Koil only disables it when chunk/render pressure is severe.",
                        PerformanceBottleneck.GPU,
                        PerformanceRecommendation.Severity.OPTIONAL,
                        true,
                        "smooth_lighting",
                        "true",
                        "false"
                ));
            }
            if (snapshot.shaderModInstalled()) {
                recommendations.add(new PerformanceRecommendation(
                        "shader-friendly-profile",
                        "Use Shader Friendly profile",
                        "A shader pipeline is installed and render pressure is visible. Shader Friendly keeps visual quality while targeting chunk distance, clouds, and FPS pacing first.",
                        PerformanceBottleneck.SHADER_RENDER,
                        PerformanceRecommendation.Severity.OPTIONAL,
                        false,
                        "profile",
                        mode.name(),
                        PerformanceProfileMode.SHADER_FRIENDLY.name()
                ));
                recommendations.add(new PerformanceRecommendation(
                        "lower-shader-shadow-distance",
                        "Lower shader shadow distance",
                        "Shader pipelines commonly spend heavily on shadow maps. Koil will apply this through detected shader/optimization config providers when a matching key exists.",
                        PerformanceBottleneck.SHADER_RENDER,
                        PerformanceRecommendation.Severity.CAUTION,
                        true,
                        "shadow_distance",
                        "current",
                        "0.75"
                ));
            }
        }

        boolean worldActive = "singleplayer".equals(snapshot.worldType()) || "server".equals(snapshot.worldType());
        if (worldActive && (snapshot.maxFrameTimeMs() > 90.0D || snapshot.primaryBottleneck() == PerformanceBottleneck.CHUNK_STORAGE || snapshot.chunkStress() > 0.50D)) {
            recommendations.add(new PerformanceRecommendation(
                    "chunk-stress-render-distance",
                    "Reduce render distance for chunk stability",
                    "Chunk loading is producing noticeable frame-time spikes. Reducing render distance lowers chunk streaming and mesh rebuild pressure.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.CAUTION,
                    true,
                    "render_distance",
                    String.valueOf(renderDistance),
                    String.valueOf(renderReductionTarget(renderDistance, hardware, snapshot, 3))
            ));
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-defer-chunk-updates",
                    "Enable Sodium deferred chunk updates",
                    "Chunk loading is spiking frame time. Sodium can defer chunk updates to reduce immediate render-thread stalls.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.SAFE,
                    "performance.always_defer_chunk_updates_v2",
                    "current",
                    "true"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-fog-occlusion",
                    "Enable Sodium fog occlusion",
                    "Fog occlusion can skip chunk rendering that is hidden by fog, reducing chunk/render cost.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.SAFE,
                    "performance.use_fog_occlusion",
                    "current",
                    "true"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-block-face-culling",
                    "Enable Sodium block face culling",
                    "Block face culling reduces chunk mesh work by skipping hidden faces.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.SAFE,
                    "performance.use_block_face_culling",
                    "current",
                    "true"
            );
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-chunk-builder-threads",
                    "Tune Sodium chunk builder threads",
                    "Chunk rebuild spikes are visible. Koil will let Sodium use auto thread selection or a bounded count depending on the measured spike pressure.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.CAUTION,
                    "performance.chunk_builder_threads",
                    "current",
                    snapshot.maxFrameTimeMs() > 80.0D || snapshot.chunkStress() > 0.50D ? "2" : "0"
            );
            if (simulationDistance > 6) {
                recommendations.add(new PerformanceRecommendation(
                        "chunk-stress-simulation-distance",
                        "Lower simulation distance for chunk stability",
                        "Chunk and frame-time pressure can come from loading, ticking, and meshing at the same time. Lowering simulation distance reduces CPU tick pressure while render distance handles visual chunk load.",
                        PerformanceBottleneck.CHUNK_STORAGE,
                        PerformanceRecommendation.Severity.SAFE,
                        true,
                        "simulation_distance",
                        String.valueOf(simulationDistance),
                        String.valueOf(Math.max(4, simulationDistance - 2))
                ));
            }
            recommendations.add(new PerformanceRecommendation(
                    "reduce-biome-blend",
                    "Reduce biome blend",
                    "Biome blending increases chunk color/render work. Reducing it helps chunk rebuild cost and is reversible.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    true,
                    "biome_blend",
                    String.valueOf(snapshot.biomeBlend()),
                    "0"
            ));
            addProviderRecommendation(recommendations, providerKeys,
                    "sodium-extra-light-updates",
                    "Reduce Sodium Extra light updates",
                    "Client light updates can add chunk rebuild pressure. Koil treats this as cautious because it may affect visual freshness in some areas.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.CAUTION,
                    "render_settings.light_updates",
                    "current",
                    "false"
            );
        }

        if (snapshot.averageFps() < 45.0D && renderDistance > 10 && recommendations.stream().noneMatch(recommendation -> "render_distance".equals(recommendation.settingKey()))) {
            recommendations.add(new PerformanceRecommendation(
                    "low-fps-render-distance-step",
                    "Lower render distance one safe step",
                    "Average FPS is low and render distance is still above a conservative range. Koil lowers it gradually so visual quality is preserved as much as possible.",
                    PerformanceBottleneck.GPU,
                    PerformanceRecommendation.Severity.SAFE,
                    true,
                    "render_distance",
                    String.valueOf(renderDistance),
                    String.valueOf(renderReductionTarget(renderDistance, hardware, snapshot, 2))
            ));
        }

        if (snapshot.maxFrameTimeMs() > 65.0D && maxFps > 120) {
            recommendations.add(new PerformanceRecommendation(
                    "cap-fps-for-frame-pacing",
                    "Cap FPS for steadier frame pacing",
                    "Frame-time spikes are high while the FPS cap is also high. A practical cap reduces wasted GPU/CPU scheduling pressure and can smooth the experience.",
                    PerformanceBottleneck.GPU,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    true,
                    "max_fps",
                    String.valueOf(maxFps),
                    "120"
            ));
        }

        if (renderDistance >= 24 && recommendations.stream().noneMatch(recommendation -> "render_distance".equals(recommendation.settingKey()))) {
            recommendations.add(new PerformanceRecommendation(
                    "maxed-render-distance-pressure",
                    "Lower maxed render distance",
                    "Render distance is set extremely high. Even if the short sample looked stable, this setting can dominate chunk rebuilds, memory, and world streaming as soon as you move into heavier terrain.",
                    PerformanceBottleneck.CHUNK_STORAGE,
                    PerformanceRecommendation.Severity.CAUTION,
                    true,
                    "render_distance",
                    String.valueOf(renderDistance),
                    String.valueOf(renderReductionTarget(renderDistance, hardware, snapshot, 8))
            ));
        }

        if (snapshot.resourcePackCount() > 8 && snapshot.memoryPressure() > 0.70D) {
            recommendations.add(new PerformanceRecommendation(
                    "resourcepack-impact-review",
                    "Review resourcepack stack",
                    "Many active resourcepacks can increase reload time, texture memory pressure, and VRAM pressure. Koil will report it but will not disable packs automatically.",
                    PerformanceBottleneck.VRAM,
                    PerformanceRecommendation.Severity.MANUAL_REVIEW,
                    false,
                    "resourcepacks",
                    String.valueOf(snapshot.resourcePackCount()),
                    "manual review"
            ));
        }

        if (snapshot.memoryPressure() > 0.82D && recommendations.stream().noneMatch(recommendation -> "mipmaps".equals(recommendation.settingKey()))) {
            recommendations.add(new PerformanceRecommendation(
                    "memory-pressure-lower-mipmaps",
                    "Lower mipmaps",
                    "Memory pressure is elevated. Lowering mipmaps can reduce texture memory and reload pressure while preserving most visual content.",
                    PerformanceBottleneck.MEMORY,
                    PerformanceRecommendation.Severity.CAUTION,
                    true,
                    "mipmaps",
                    "current",
                    "one step lower"
            ));
        }

        if (snapshot.maxFrameTimeMs() > 75.0D && snapshot.averageFps() > 90.0D) {
            recommendations.add(new PerformanceRecommendation(
                    "disable-vsync-for-spike-diagnosis",
                    "Disable VSync for spike diagnosis",
                    "Average FPS is high but frame-time spikes are visible. Disabling VSync can help Koil separate display pacing from actual render or tick stalls.",
                    PerformanceBottleneck.GPU,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    true,
                    "vsync",
                    "current",
                    "false"
            ));
        }

        if (mode == PerformanceProfileMode.HIGH_FPS && recommendations.stream().noneMatch(recommendation -> "max_fps".equals(recommendation.settingKey()))) {
            recommendations.add(new PerformanceRecommendation(
                    "cap-fps-high",
                    "Set max FPS to a stable high cap",
                    "A stable FPS cap can reduce frame pacing spikes compared to an unlimited cap.",
                    PerformanceBottleneck.HEALTHY,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    true,
                    "max_fps",
                    "current",
                    "144"
            ));
        } else if (mode == PerformanceProfileMode.LAPTOP_BATTERY_SAVER && recommendations.stream().noneMatch(recommendation -> "max_fps".equals(recommendation.settingKey()))) {
            recommendations.add(new PerformanceRecommendation(
                    "cap-fps-battery",
                    "Set max FPS to 60",
                    "Battery saver should reduce unnecessary GPU and CPU work while keeping the game responsive.",
                    PerformanceBottleneck.GPU,
                    PerformanceRecommendation.Severity.SAFE,
                    true,
                    "max_fps",
                    "current",
                    "60"
            ));
        }

        if (!snapshot.hasVerifiedVideoOptions()) {
            recommendations.removeIf(recommendation -> isVanillaVideoSetting(recommendation.settingKey()));
        }
        if (recommendations.isEmpty()) {
            recommendations.add(new PerformanceRecommendation(
                    "stable-no-change",
                    "No major change needed",
                    "The current sample looks stable. Koil will keep monitoring and avoid changing settings without a real bottleneck.",
                    PerformanceBottleneck.HEALTHY,
                    PerformanceRecommendation.Severity.OPTIONAL,
                    false,
                    "none",
                    "stable",
                    "no change"
            ));
        }
        recommendations = PerformanceLearningService.adaptRecommendations(recommendations, snapshot);
        PerformanceJsonStore.write(PerformancePaths.DETECTED_BOTTLENECKS, java.util.Map.of(
                "capturedAtMillis", System.currentTimeMillis(),
                "primary", snapshot.primaryBottleneck().name(),
                "likelyCause", snapshot.likelyCause(),
                "recommendations", recommendations.stream().map(PerformanceRecommendation::toJsonMap).toList()
        ));
        return recommendations;
    }

    private static List<PerformanceRecommendation> benchmarkPowerPlan(PerformanceProfileMode mode, PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, List<PerformanceBenchmarkPhaseResult> phases) {
        List<PerformanceRecommendation> recommendations = new ArrayList<>();
        boolean worldActive = "singleplayer".equals(snapshot.worldType()) || "server".equals(snapshot.worldType());
        boolean stable = snapshot.averageFps() >= 75.0D
                && snapshot.onePercentLowFps() >= 45.0D
                && snapshot.maxFrameTimeMs() < 55.0D
                && snapshot.memoryPressure() < 0.78D
                && snapshot.gcPressure() < 0.35D
                && snapshot.chunkStress() < 0.45D
                && snapshot.shaderPressure() < 0.45D;
        boolean stressed = snapshot.averageFps() < 45.0D
                || snapshot.onePercentLowFps() < 28.0D
                || snapshot.maxFrameTimeMs() > 90.0D
                || snapshot.memoryPressure() > 0.86D
                || snapshot.gcPressure() > 0.55D
                || snapshot.chunkStress() > 0.62D
                || snapshot.shaderPressure() > 0.62D;
        int currentRender = snapshot.renderDistance() <= 0 ? 12 : snapshot.renderDistance();
        int currentSimulation = snapshot.simulationDistance() <= 0 ? 8 : snapshot.simulationDistance();
        int targetRender = benchmarkRenderTarget(currentRender, snapshot, hardware, stable, stressed);
        int targetSimulation = benchmarkSimulationTarget(currentSimulation, snapshot, hardware, stable, stressed, worldActive);
        double targetEntityDistance = benchmarkEntityDistance(snapshot, stable, stressed, worldActive);
        int targetBiomeBlend = benchmarkBiomeBlend(snapshot, hardware, stable, stressed);
        String targetParticles = benchmarkParticles(snapshot, stable, stressed);
        String targetGraphics = benchmarkGraphics(snapshot, hardware, stable, stressed);
        boolean targetSmoothLighting = benchmarkSmoothLighting(snapshot, hardware, stable, stressed);
        boolean targetEntityShadows = benchmarkEntityShadows(snapshot, hardware, stable, stressed);
        String targetClouds = benchmarkClouds(snapshot, stable, stressed);

        addBenchmarkVanilla(recommendations, "benchmark-render-distance", "Benchmark render distance target", benchmarkReason("render distance", snapshot, stable, stressed, phases), PerformanceBottleneck.CHUNK_STORAGE, "render_distance", String.valueOf(currentRender), String.valueOf(targetRender), PerformanceRecommendation.Severity.SAFE);
        addBenchmarkVanilla(recommendations, "benchmark-simulation-distance", "Benchmark simulation distance target", benchmarkReason("simulation distance", snapshot, stable, stressed, phases), PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(currentSimulation), String.valueOf(targetSimulation), PerformanceRecommendation.Severity.SAFE);
        addBenchmarkVanilla(recommendations, "benchmark-entity-distance", "Benchmark entity distance target", benchmarkReason("entity distance", snapshot, stable, stressed, phases), PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), String.format(java.util.Locale.ROOT, "%.2f", targetEntityDistance), PerformanceRecommendation.Severity.SAFE);
        addBenchmarkVanilla(recommendations, "benchmark-particles", "Benchmark particle target", benchmarkReason("particles", snapshot, stable, stressed, phases), PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), targetParticles, PerformanceRecommendation.Severity.OPTIONAL);
        addBenchmarkVanilla(recommendations, "benchmark-graphics-mode", "Benchmark graphics target", benchmarkReason("graphics mode", snapshot, stable, stressed, phases), PerformanceBottleneck.GPU, "graphics_mode", snapshot.graphicsMode(), targetGraphics, stressed ? PerformanceRecommendation.Severity.CAUTION : PerformanceRecommendation.Severity.OPTIONAL);
        addBenchmarkVanilla(recommendations, "benchmark-clouds", "Benchmark cloud target", benchmarkReason("clouds", snapshot, stable, stressed, phases), PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), targetClouds, PerformanceRecommendation.Severity.OPTIONAL);
        addBenchmarkVanilla(recommendations, "benchmark-smooth-lighting", "Benchmark smooth lighting target", benchmarkReason("smooth lighting", snapshot, stable, stressed, phases), PerformanceBottleneck.GPU, "smooth_lighting", String.valueOf(snapshot.smoothLighting()), String.valueOf(targetSmoothLighting), stressed ? PerformanceRecommendation.Severity.OPTIONAL : PerformanceRecommendation.Severity.SAFE);
        addBenchmarkVanilla(recommendations, "benchmark-biome-blend", "Benchmark biome blend target", benchmarkReason("biome blend", snapshot, stable, stressed, phases), PerformanceBottleneck.CHUNK_STORAGE, "biome_blend", String.valueOf(snapshot.biomeBlend()), String.valueOf(targetBiomeBlend), PerformanceRecommendation.Severity.OPTIONAL);
        addBenchmarkVanilla(recommendations, "benchmark-entity-shadows", "Benchmark entity shadow target", benchmarkReason("entity shadows", snapshot, stable, stressed, phases), PerformanceBottleneck.GPU, "entity_shadows", String.valueOf(snapshot.entityShadows()), String.valueOf(targetEntityShadows), PerformanceRecommendation.Severity.OPTIONAL);
        addBenchmarkVanilla(recommendations, "benchmark-vsync", "Benchmark VSync target", "Benchmark mode lets Koil draw as much power as needed, so VSync is disabled to avoid display pacing hiding true render capacity.", PerformanceBottleneck.GPU, "vsync", String.valueOf(snapshot.vsync()), "false", PerformanceRecommendation.Severity.OPTIONAL);
        addBenchmarkVanilla(recommendations, "benchmark-max-fps", "Benchmark max FPS target", "Benchmark mode uses unlimited FPS so Koil can measure the machine's real available headroom before profile caps are considered.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(snapshot.maxFps()), "unlimited", PerformanceRecommendation.Severity.SAFE);
        if (!snapshot.hasVerifiedVideoOptions()) {
            recommendations.removeIf(recommendation -> isVanillaVideoSetting(recommendation.settingKey()));
        }
        return recommendations;
    }

    private static int benchmarkRenderTarget(int current, PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, boolean stable, boolean stressed) {
        int ceiling = visualRenderCeiling(hardware, snapshot);
        int floor = visualRenderFloor(hardware, snapshot);
        if (snapshot.shaderModInstalled() || snapshot.shaderPressure() > 0.35D) {
            ceiling = Math.min(ceiling, hardware.gpuTier() >= 3 ? 16 : hardware.gpuTier() >= 2 ? 12 : 9);
        }
        if (stressed) {
            int stepTarget = current - (snapshot.chunkStress() > 0.70D || snapshot.maxFrameTimeMs() > 115.0D ? 6 : snapshot.chunkStress() > 0.55D ? 4 : 2);
            return Math.max(floor, Math.min(stepTarget, ceiling));
        }
        if (stable) {
            return Math.max(current, ceiling);
        }
        return current > ceiling ? ceiling : Math.max(current, Math.min(ceiling, floor + 2));
    }

    private static int benchmarkSimulationTarget(int current, PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, boolean stable, boolean stressed, boolean worldActive) {
        if (!worldActive) {
            return Math.min(current, hardware.cpuTier() >= 2 ? 8 : 6);
        }
        if (stressed || snapshot.entityCount() > 180) {
            int floor = hardware.cpuTier() >= 2 ? 6 : 4;
            return Math.max(floor, Math.min(current, current - 2));
        }
        if (stable && hardware.cpuTier() >= 2 && snapshot.entityCount() < 120) {
            return Math.max(current, Math.min(12, hardware.cpuTier() >= 3 ? 10 : 8));
        }
        return Math.min(current, hardware.cpuTier() >= 2 ? 8 : 6);
    }

    private static double benchmarkEntityDistance(PerformanceSnapshot snapshot, boolean stable, boolean stressed, boolean worldActive) {
        if (!worldActive) {
            return Math.min(1.0D, Math.max(0.75D, snapshot.entityDistanceScale()));
        }
        if (stressed || snapshot.entityCount() > 220) {
            return 0.70D;
        }
        if (stable && snapshot.entityCount() < 140) {
            return 1.0D;
        }
        return 0.85D;
    }

    private static int benchmarkBiomeBlend(PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, boolean stable, boolean stressed) {
        if (stressed || snapshot.chunkStress() > 0.55D) {
            return hardware.overallTier() >= 2 ? 1 : 0;
        }
        if (stable && hardware.gpuTier() >= 2 && hardware.memoryTier() >= 2) {
            return Math.max(snapshot.biomeBlend(), 3);
        }
        return Math.max(1, Math.min(snapshot.biomeBlend(), 2));
    }

    private static String benchmarkParticles(PerformanceSnapshot snapshot, boolean stable, boolean stressed) {
        if ((stressed && (snapshot.onePercentLowFps() < 24.0D || snapshot.maxFrameTimeMs() > 125.0D)) || snapshot.entityCount() > 280) {
            return "minimal";
        }
        if (stressed || snapshot.entityCount() > 220 || snapshot.uiFramePressure() > 0.70D) {
            return "decreased";
        }
        if (stable) {
            return "all";
        }
        return "decreased";
    }

    private static String benchmarkGraphics(PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, boolean stable, boolean stressed) {
        boolean severe = snapshot.averageFps() < 30.0D || snapshot.onePercentLowFps() < 20.0D || snapshot.maxFrameTimeMs() > 130.0D;
        if ((severe && (hardware.gpuTier() <= 1 || hardware.integratedGpu())) || snapshot.shaderPressure() > 0.78D) {
            return "fast";
        }
        if (hardware.gpuTier() >= 2 && !snapshot.graphicsMode().toLowerCase(java.util.Locale.ROOT).contains("fancy")) {
            return "fancy";
        }
        return stable ? "fancy" : snapshot.graphicsMode();
    }

    private static boolean benchmarkSmoothLighting(PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, boolean stable, boolean stressed) {
        boolean severe = snapshot.maxFrameTimeMs() > 120.0D || snapshot.onePercentLowFps() < 22.0D;
        if ((severe && (hardware.gpuTier() <= 1 || hardware.integratedGpu())) || snapshot.chunkStress() > 0.78D) {
            return false;
        }
        return hardware.gpuTier() >= 2 || stable || snapshot.smoothLighting();
    }

    private static boolean benchmarkEntityShadows(PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, boolean stable, boolean stressed) {
        if ((stressed && snapshot.entityCount() > 260) || hardware.gpuTier() <= 1 || hardware.integratedGpu()) {
            return false;
        }
        return hardware.gpuTier() >= 2 || stable || snapshot.entityShadows();
    }

    private static String benchmarkClouds(PerformanceSnapshot snapshot, boolean stable, boolean stressed) {
        if (snapshot.shaderPressure() > 0.70D || (stressed && snapshot.shaderModInstalled())) {
            return "off";
        }
        if (stressed || snapshot.shaderModInstalled() || snapshot.shaderPressure() > 0.35D) {
            return "fast";
        }
        return stable ? "fancy" : snapshot.cloudsMode();
    }

    private static String benchmarkReason(String setting, PerformanceSnapshot snapshot, boolean stable, boolean stressed, List<PerformanceBenchmarkPhaseResult> phases) {
        String phaseText = phases == null || phases.isEmpty() ? "live sample" : phases.size() + " benchmark phase(s)";
        if (stable) {
            return "Benchmark found headroom across " + phaseText + ", so Koil keeps " + setting + " visually strong while applying backend optimizations.";
        }
        if (stressed) {
            return "Benchmark found pressure across " + phaseText + " (" + snapshot.likelyCause() + "), so Koil tunes " + setting + " to preserve smoothness without blindly turning everything down.";
        }
        return "Benchmark found mixed pressure across " + phaseText + ", so Koil chooses a balanced " + setting + " target from the measured stats.";
    }

    private static void addBenchmarkVanilla(List<PerformanceRecommendation> recommendations, String id, String title, String reason, PerformanceBottleneck bottleneck, String key, String before, String after, PerformanceRecommendation.Severity severity) {
        if (sameValue(before, after)) {
            return;
        }
        recommendations.add(new PerformanceRecommendation(id, title, reason, bottleneck, severity, true, key, before, after));
    }

    private static int visualRenderCeiling(PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot) {
        int ceiling = switch (hardware.overallTier()) {
            case 0 -> 8;
            case 1 -> 12;
            case 2 -> 18;
            default -> 24;
        };
        if (hardware.highResolutionDisplay()) {
            ceiling -= 2;
        }
        if (snapshot != null && snapshot.shaderModInstalled()) {
            ceiling = Math.min(ceiling, hardware.gpuTier() >= 3 ? 16 : hardware.gpuTier() >= 2 ? 12 : 9);
        }
        return Math.max(6, ceiling);
    }

    private static int visualRenderFloor(PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot) {
        int floor = switch (hardware.overallTier()) {
            case 0 -> 6;
            case 1 -> 8;
            case 2 -> 10;
            default -> 12;
        };
        if (snapshot != null && snapshot.shaderModInstalled()) {
            floor = Math.min(floor, hardware.gpuTier() >= 2 ? 9 : 6);
        }
        return Math.max(4, floor);
    }

    private static int renderReductionTarget(int current, PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot, int step) {
        int ceiling = visualRenderCeiling(hardware, snapshot);
        int floor = visualRenderFloor(hardware, snapshot);
        int cappedCurrent = Math.min(current, ceiling);
        int reduced = Math.max(floor, current - Math.max(1, step));
        return Math.min(current, Math.min(cappedCurrent, reduced));
    }

    private static int profileRenderTarget(PerformanceProfileMode mode, PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot) {
        int ceiling = visualRenderCeiling(hardware, snapshot);
        return switch (mode) {
            case QUALITY -> ceiling;
            case BALANCED -> Math.max(10, Math.min(ceiling, hardware.overallTier() >= 2 ? 14 : 10));
            case HIGH_FPS -> Math.max(9, Math.min(ceiling, hardware.gpuTier() >= 3 ? 14 : hardware.gpuTier() >= 2 ? 12 : 9));
            case SHADER_FRIENDLY -> Math.max(8, Math.min(ceiling, hardware.gpuTier() >= 3 ? 14 : 10));
            default -> ceiling;
        };
    }

    private static int profileSimulationTarget(PerformanceProfileMode mode, PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot) {
        boolean entityHeavy = snapshot != null && snapshot.entityCount() > 180;
        return switch (mode) {
            case HIGH_FPS -> hardware.cpuTier() >= 2 && !entityHeavy ? 8 : 6;
            case BALANCED -> hardware.cpuTier() >= 3 && !entityHeavy ? 10 : hardware.cpuTier() >= 2 ? 8 : 6;
            case QUALITY -> hardware.cpuTier() >= 3 && !entityHeavy ? 10 : 8;
            case SHADER_FRIENDLY -> hardware.cpuTier() >= 2 ? 8 : 6;
            default -> hardware.cpuTier() >= 2 ? 8 : 6;
        };
    }

    private static void addProfileRecommendations(List<PerformanceRecommendation> recommendations, PerformanceProfileMode mode, PerformanceSnapshot snapshot, PerformanceHardwareAdvisor.HardwareClass hardware, int renderDistance, int simulationDistance, int maxFps) {
        switch (mode) {
            case LOW_END -> {
                addVanillaIfChanged(recommendations, "profile-low-render-distance", "Low End render distance", "Low End prioritizes stable chunk rendering on weaker systems.", PerformanceBottleneck.CHUNK_STORAGE, "render_distance", String.valueOf(renderDistance), String.valueOf(Math.min(renderDistance, 8)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-simulation-distance", "Low End simulation distance", "Low End reduces active simulation so CPU-heavy worlds stay responsive.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(Math.min(simulationDistance, 4)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-entity-distance", "Low End entity distance", "Low End reduces distant entity rendering before disabling major gameplay visuals.", PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), "0.65", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-particles", "Low End particles", "Low End reduces effect update and render pressure.", PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), "minimal", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-graphics", "Low End graphics mode", "Low End uses fast graphics to preserve framerate first.", PerformanceBottleneck.GPU, "graphics_mode", snapshot.graphicsMode(), "fast", PerformanceRecommendation.Severity.CAUTION);
                addVanillaIfChanged(recommendations, "profile-low-clouds", "Low End clouds", "Low End disables clouds because they are optional visual work on weaker machines.", PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), "off", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-smooth-lighting", "Low End smooth lighting", "Low End disables smooth lighting to reduce chunk lighting cost.", PerformanceBottleneck.GPU, "smooth_lighting", String.valueOf(snapshot.smoothLighting()), "false", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-biome-blend", "Low End biome blend", "Low End removes biome blending to reduce chunk color work.", PerformanceBottleneck.CHUNK_STORAGE, "biome_blend", String.valueOf(snapshot.biomeBlend()), "0", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-entity-shadows", "Low End entity shadows", "Low End disables entity shadows for cheaper rendering.", PerformanceBottleneck.GPU, "entity_shadows", String.valueOf(snapshot.entityShadows()), "false", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-mipmaps", "Low End mipmaps", "Low End keeps mipmaps modest to reduce texture memory pressure.", PerformanceBottleneck.MEMORY, "mipmaps", String.valueOf(snapshot.mipmapLevels()), "1", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-low-max-fps", "Low End FPS cap", "A modest cap can reduce wasted work and smooth frame pacing.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(maxFps), "75", PerformanceRecommendation.Severity.SAFE);
            }
            case BALANCED -> {
                addVanillaIfChanged(recommendations, "profile-balanced-render-distance", "Balanced render distance", "Balanced uses a hardware-aware visual cap instead of forcing a low preset.", PerformanceBottleneck.CHUNK_STORAGE, "render_distance", String.valueOf(renderDistance), String.valueOf(profileRenderTarget(PerformanceProfileMode.BALANCED, hardware, snapshot)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-balanced-simulation-distance", "Balanced simulation distance", "Balanced keeps simulation useful while bounding CPU-heavy worlds.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(profileSimulationTarget(PerformanceProfileMode.BALANCED, hardware, snapshot)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-balanced-particles", "Balanced particles", "Balanced keeps effects visible unless live pressure proves they are part of the stutter.", PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), hardware.overallTier() >= 2 && snapshot.entityCount() < 180 ? "all" : "decreased", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-graphics", "Balanced graphics mode", "Balanced keeps richer graphics on capable hardware and relies on backend culling/provider settings first.", PerformanceBottleneck.GPU, "graphics_mode", snapshot.graphicsMode(), hardware.gpuTier() >= 2 ? "fancy" : "fast", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-biome-blend", "Balanced biome blend", "Balanced keeps a small visual blend radius without heavy chunk color cost.", PerformanceBottleneck.CHUNK_STORAGE, "biome_blend", String.valueOf(snapshot.biomeBlend()), String.valueOf(hardware.overallTier() >= 2 ? 2 : 1), PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-clouds", "Balanced clouds", "Balanced keeps clouds visible on capable hardware and uses fast clouds on weaker or high-pressure systems.", PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), hardware.gpuTier() >= 2 && snapshot.shaderPressure() < 0.45D ? "fancy" : "fast", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-smooth-lighting", "Balanced smooth lighting", "Balanced keeps smooth lighting on capable hardware for visual quality.", PerformanceBottleneck.GPU, "smooth_lighting", String.valueOf(snapshot.smoothLighting()), String.valueOf(hardware.gpuTier() >= 2), PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-entity-distance", "Balanced entity distance", "Balanced keeps entity visibility high without forcing maximum distant entity work.", PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), hardware.cpuTier() >= 2 ? "0.90" : "0.75", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-entity-shadows", "Balanced entity shadows", "Balanced keeps entity shadows when the GPU tier supports them.", PerformanceBottleneck.GPU, "entity_shadows", String.valueOf(snapshot.entityShadows()), String.valueOf(hardware.gpuTier() >= 2), PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-mipmaps", "Balanced mipmaps", "Balanced keeps texture filtering good while leaving memory headroom.", PerformanceBottleneck.MEMORY, "mipmaps", String.valueOf(snapshot.mipmapLevels()), hardware.memoryTier() >= 2 ? "3" : "2", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-balanced-max-fps", "Balanced FPS cap", "Balanced caps very high framerates to reduce frame pacing spikes.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(maxFps), "120", PerformanceRecommendation.Severity.OPTIONAL);
            }
            case QUALITY -> {
                addVanillaIfChanged(recommendations, "profile-quality-render-distance", "Quality render distance", "Quality uses available hardware for stronger visuals while staying inside a safe optimized range.", PerformanceBottleneck.GPU, "render_distance", String.valueOf(renderDistance), String.valueOf(profileRenderTarget(PerformanceProfileMode.QUALITY, hardware, snapshot)), PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-simulation-distance", "Quality simulation distance", "Quality raises simulation where CPU headroom supports richer active worlds.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(profileSimulationTarget(PerformanceProfileMode.QUALITY, hardware, snapshot)), PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-entity-distance", "Quality entity distance", "Quality keeps distant entities visible when the system can handle it.", PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), hardware.cpuTier() >= 2 && hardware.gpuTier() >= 2 ? "1.00" : "0.85", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-particles", "Quality particles", "Quality keeps full particles unless benchmark pressure later proves they are causing stutter.", PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), "all", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-graphics", "Quality graphics", "Quality keeps richer graphics while backend optimization mods handle hidden render cost.", PerformanceBottleneck.GPU, "graphics_mode", snapshot.graphicsMode(), "fancy", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-clouds", "Quality clouds", "Quality uses fancy clouds unless shader pressure requires the Shader Friendly profile.", PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), snapshot.shaderPressure() > 0.55D ? "fast" : "fancy", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-smooth-lighting", "Quality smooth lighting", "Quality keeps smooth lighting when no benchmark pressure says otherwise.", PerformanceBottleneck.GPU, "smooth_lighting", String.valueOf(snapshot.smoothLighting()), "true", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-biome-blend", "Quality biome blend", "Quality keeps biome transitions visually clean while benchmark mode can still lower this if chunk pressure appears.", PerformanceBottleneck.CHUNK_STORAGE, "biome_blend", String.valueOf(snapshot.biomeBlend()), hardware.memoryTier() >= 2 ? "5" : "3", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-entity-shadows", "Quality entity shadows", "Quality keeps entity shadows for richer visuals unless benchmark pressure later disables them.", PerformanceBottleneck.GPU, "entity_shadows", String.valueOf(snapshot.entityShadows()), "true", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-mipmaps", "Quality mipmaps", "Quality uses high mipmaps when memory headroom supports sharper texture filtering.", PerformanceBottleneck.MEMORY, "mipmaps", String.valueOf(snapshot.mipmapLevels()), hardware.memoryTier() >= 2 ? "4" : "3", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-quality-max-fps", "Quality unlimited FPS", "Quality is Koil's best-visuals profile. It keeps safe backend optimizations enabled while leaving FPS uncapped for maximum visual responsiveness.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(maxFps), "unlimited", PerformanceRecommendation.Severity.OPTIONAL);
            }
            case SHADER_FRIENDLY -> {
                addVanillaIfChanged(recommendations, "profile-shader-render-distance", "Shader Friendly render distance", "Shader Friendly protects the render pipeline before lowering visual effects too aggressively.", PerformanceBottleneck.SHADER_RENDER, "render_distance", String.valueOf(renderDistance), String.valueOf(profileRenderTarget(PerformanceProfileMode.SHADER_FRIENDLY, hardware, snapshot)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-shader-simulation-distance", "Shader Friendly simulation distance", "Shader Friendly keeps CPU simulation bounded so shader rendering gets more frame budget.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(profileSimulationTarget(PerformanceProfileMode.SHADER_FRIENDLY, hardware, snapshot)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-shader-entity-distance", "Shader Friendly entity distance", "Shader Friendly keeps entity visibility useful while avoiding runaway render cost.", PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), hardware.gpuTier() >= 2 ? "0.85" : "0.70", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-clouds", "Shader Friendly clouds", "Clouds stack with shader cost, so Koil uses fast clouds before fully disabling them.", PerformanceBottleneck.SHADER_RENDER, "clouds", snapshot.cloudsMode(), snapshot.shaderPressure() > 0.65D ? "off" : "fast", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-graphics", "Shader Friendly graphics", "Shader Friendly keeps vanilla graphics rich while shader-specific cost is handled through distance, clouds, and provider settings.", PerformanceBottleneck.SHADER_RENDER, "graphics_mode", snapshot.graphicsMode(), "fancy", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-particles", "Shader Friendly particles", "Shader Friendly keeps effects visible but reduced so shader lighting and particles do not stack too hard.", PerformanceBottleneck.SHADER_RENDER, "particles", snapshot.particlesMode(), "decreased", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-smooth-lighting", "Shader Friendly smooth lighting", "Shader Friendly keeps vanilla lighting quality because shader-specific costs are handled by shader/provider settings.", PerformanceBottleneck.SHADER_RENDER, "smooth_lighting", String.valueOf(snapshot.smoothLighting()), "true", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-biome-blend", "Shader Friendly biome blend", "Shader Friendly keeps a modest biome blend so shader scenes still look clean.", PerformanceBottleneck.CHUNK_STORAGE, "biome_blend", String.valueOf(snapshot.biomeBlend()), hardware.memoryTier() >= 2 ? "2" : "1", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-entity-shadows", "Shader Friendly entity shadows", "Shader Friendly disables vanilla entity shadows only on weaker GPUs because shader shadows may already cover the scene.", PerformanceBottleneck.SHADER_RENDER, "entity_shadows", String.valueOf(snapshot.entityShadows()), String.valueOf(hardware.gpuTier() >= 2), PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-mipmaps", "Shader Friendly mipmaps", "Shader Friendly keeps texture filtering useful while limiting VRAM pressure from shader/resourcepack stacks.", PerformanceBottleneck.VRAM, "mipmaps", String.valueOf(snapshot.mipmapLevels()), hardware.memoryTier() >= 2 ? "3" : "2", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-shader-max-fps", "Shader Friendly FPS cap", "Shader Friendly uses a practical cap so shader pipelines keep frame pacing stable instead of queueing runaway GPU work.", PerformanceBottleneck.SHADER_RENDER, "max_fps", String.valueOf(maxFps), "90", PerformanceRecommendation.Severity.OPTIONAL);
            }
            case SERVER_FRIENDLY -> {
                addVanillaIfChanged(recommendations, "profile-server-render-distance", "Server Friendly render distance", "Server Friendly keeps enough visibility for play while reducing client chunk/render spikes on busy servers.", PerformanceBottleneck.CHUNK_STORAGE, "render_distance", String.valueOf(renderDistance), String.valueOf(Math.min(renderDistance, hardware.gpuTier() >= 2 ? 12 : 9)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-server-simulation-distance", "Server Friendly simulation distance", "Server Friendly lowers local simulation pressure while keeping server play stable.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(Math.min(simulationDistance, 6)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-server-entity-distance", "Server Friendly entity distance", "Server Friendly reduces distant entity rendering on busy servers.", PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), "0.75", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-server-particles", "Server Friendly particles", "Server Friendly lowers effect pressure in multiplayer fights and hubs.", PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), "decreased", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-server-clouds", "Server Friendly clouds", "Server Friendly uses fast clouds to keep readability without wasting render budget.", PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), "fast", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-server-max-fps", "Server Friendly FPS cap", "Server Friendly caps runaway FPS so networking/server play has stable frame pacing.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(maxFps), "120", PerformanceRecommendation.Severity.OPTIONAL);
            }
            case STREAMING_RECORDING -> {
                addVanillaIfChanged(recommendations, "profile-streaming-render-distance", "Streaming render distance", "Streaming/Recording leaves headroom for capture encoding while keeping the game readable.", PerformanceBottleneck.GPU, "render_distance", String.valueOf(renderDistance), String.valueOf(Math.min(renderDistance, hardware.gpuTier() >= 2 ? 12 : 8)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-streaming-simulation-distance", "Streaming simulation distance", "Streaming/Recording bounds CPU simulation so recording software has stable headroom.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(hardware.cpuTier() >= 2 ? 6 : 4), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-streaming-particles", "Streaming particles", "Streaming/Recording reduces transient particle spikes that can hurt capture smoothness.", PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), "decreased", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-streaming-clouds", "Streaming clouds", "Streaming/Recording uses fast clouds to preserve visuals with less GPU cost.", PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), "fast", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-streaming-entity-shadows", "Streaming entity shadows", "Streaming/Recording disables entity shadows to reserve GPU time for capture.", PerformanceBottleneck.GPU, "entity_shadows", String.valueOf(snapshot.entityShadows()), "false", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-streaming-max-fps", "Streaming FPS cap", "Streaming/Recording reserves GPU/CPU headroom for capture software.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(maxFps), "60", PerformanceRecommendation.Severity.SAFE);
            }
            case HIGH_FPS -> {
                addVanillaIfChanged(recommendations, "profile-high-render-distance", "High FPS render distance", "High FPS keeps chunk visibility useful while reducing render-thread cost.", PerformanceBottleneck.GPU, "render_distance", String.valueOf(renderDistance), String.valueOf(profileRenderTarget(PerformanceProfileMode.HIGH_FPS, hardware, snapshot)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-high-simulation-distance", "High FPS simulation distance", "High FPS lowers active simulation enough to protect frame pacing in busy worlds.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(profileSimulationTarget(PerformanceProfileMode.HIGH_FPS, hardware, snapshot)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-high-entity-distance", "High FPS entity distance", "High FPS reduces distant entity rendering while keeping nearby gameplay clear.", PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), hardware.cpuTier() >= 2 ? "0.80" : "0.65", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-high-particles", "High FPS particles", "High FPS reduces effect work before cutting major visuals.", PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), "decreased", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-high-graphics", "High FPS graphics", "High FPS keeps fancy graphics on capable hardware and relies on distance/effect limits for speed.", PerformanceBottleneck.GPU, "graphics_mode", snapshot.graphicsMode(), hardware.gpuTier() >= 2 ? "fancy" : "fast", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-high-clouds", "High FPS clouds", "High FPS uses fast clouds instead of disabling clouds outright.", PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), "fast", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-high-biome-blend", "High FPS biome blend", "High FPS keeps biome blending low for cheaper chunk rendering.", PerformanceBottleneck.CHUNK_STORAGE, "biome_blend", String.valueOf(snapshot.biomeBlend()), "1", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-high-entity-shadows", "High FPS entity shadows", "High FPS disables entity shadows to reduce per-entity render cost.", PerformanceBottleneck.GPU, "entity_shadows", String.valueOf(snapshot.entityShadows()), "false", PerformanceRecommendation.Severity.OPTIONAL);
                addVanillaIfChanged(recommendations, "profile-high-max-fps", "High FPS uncapped target", "High FPS leaves the cap unlimited so the game can use available headroom.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(maxFps), "unlimited", PerformanceRecommendation.Severity.OPTIONAL);
            }
            case LAPTOP_BATTERY_SAVER -> {
                addVanillaIfChanged(recommendations, "profile-battery-render-distance", "Battery saver render distance", "Battery saver lowers chunk rendering to reduce power draw and heat.", PerformanceBottleneck.GPU, "render_distance", String.valueOf(renderDistance), String.valueOf(Math.min(renderDistance, 8)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-simulation-distance", "Battery saver simulation distance", "Battery saver reduces CPU-side world work.", PerformanceBottleneck.CPU, "simulation_distance", String.valueOf(simulationDistance), String.valueOf(Math.min(simulationDistance, 5)), PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-entity-distance", "Battery saver entity distance", "Battery saver reduces distant entity rendering to save CPU/GPU time.", PerformanceBottleneck.ENTITY_TICK, "entity_distance", String.valueOf(snapshot.entityDistanceScale()), "0.65", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-particles", "Battery saver particles", "Battery saver reduces particle/effect updates.", PerformanceBottleneck.GPU, "particles", snapshot.particlesMode(), "minimal", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-graphics", "Battery saver graphics", "Battery saver uses fast graphics to reduce power draw.", PerformanceBottleneck.GPU, "graphics_mode", snapshot.graphicsMode(), "fast", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-clouds", "Battery saver clouds", "Clouds are extra visual work and battery saver disables them first.", PerformanceBottleneck.GPU, "clouds", snapshot.cloudsMode(), "off", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-smooth-lighting", "Battery saver smooth lighting", "Battery saver disables smooth lighting to reduce chunk lighting cost.", PerformanceBottleneck.GPU, "smooth_lighting", String.valueOf(snapshot.smoothLighting()), "false", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-biome-blend", "Battery saver biome blend", "Battery saver disables biome blending to reduce chunk color work.", PerformanceBottleneck.CHUNK_STORAGE, "biome_blend", String.valueOf(snapshot.biomeBlend()), "0", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-entity-shadows", "Battery saver entity shadows", "Battery saver disables entity shadows to reduce GPU load.", PerformanceBottleneck.GPU, "entity_shadows", String.valueOf(snapshot.entityShadows()), "false", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-mipmaps", "Battery saver mipmaps", "Battery saver keeps mipmaps low to reduce texture memory and reload pressure.", PerformanceBottleneck.MEMORY, "mipmaps", String.valueOf(snapshot.mipmapLevels()), "1", PerformanceRecommendation.Severity.SAFE);
                addVanillaIfChanged(recommendations, "profile-battery-max-fps", "Battery saver FPS cap", "Battery saver caps FPS to reduce power draw and heat.", PerformanceBottleneck.GPU, "max_fps", String.valueOf(maxFps), "60", PerformanceRecommendation.Severity.SAFE);
            }
            default -> {
            }
        }
    }

    private static void addVanillaIfChanged(List<PerformanceRecommendation> recommendations, String id, String title, String reason, PerformanceBottleneck bottleneck, String key, String before, String after, PerformanceRecommendation.Severity severity) {
        if (sameValue(before, after) || recommendations.stream().anyMatch(recommendation -> recommendation.id().equals(id) || recommendation.settingKey().equals(key))) {
            return;
        }
        recommendations.add(new PerformanceRecommendation(id, title, reason, bottleneck, severity, true, key, before, after));
    }

    private static void addHardwareProviderRecommendations(List<PerformanceRecommendation> recommendations, Map<String, PerformanceSettingDescriptor> providerSettings, PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot) {
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-defer-chunk-updates",
                "Match Sodium chunk deferral to hardware",
                "Koil detected " + hardware.profile().cpuThreads() + " CPU threads, " + hardware.profile().systemMemoryMb() + " MB RAM, and GPU tier " + hardware.gpuTier() + ". Deferred chunk updates are a safe baseline for smoother chunk rebuild pacing.",
                PerformanceBottleneck.CHUNK_STORAGE,
                PerformanceRecommendation.Severity.SAFE,
                "performance.always_defer_chunk_updates_v2",
                "current",
                "true"
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-entity-culling",
                "Match Sodium entity culling to hardware",
                "Hidden entity rendering should be culled on all hardware tiers because it reduces render cost without lowering visible quality.",
                PerformanceBottleneck.ENTITY_TICK,
                PerformanceRecommendation.Severity.SAFE,
                "performance.use_entity_culling",
                "current",
                "true"
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-fog-occlusion",
                "Match Sodium fog occlusion to hardware",
                "Fog occlusion is a safe baseline for chunk/render pressure and helps lower-end GPUs avoid drawing chunks hidden by fog.",
                PerformanceBottleneck.CHUNK_STORAGE,
                PerformanceRecommendation.Severity.SAFE,
                "performance.use_fog_occlusion",
                "current",
                "true"
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-block-face-culling",
                "Match Sodium block face culling to hardware",
                "Block face culling reduces hidden chunk mesh work and should stay enabled for every performance profile.",
                PerformanceBottleneck.CHUNK_STORAGE,
                PerformanceRecommendation.Severity.SAFE,
                "performance.use_block_face_culling",
                "current",
                "true"
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-visible-texture-animation",
                "Enable Sodium visible-only texture animation",
                "This is a safe default performance setting. Sodium will only animate visible textures, reducing texture update work without lowering visible quality.",
                PerformanceBottleneck.MEMORY,
                PerformanceRecommendation.Severity.SAFE,
                "performance.animate_only_visible_textures",
                "current",
                "true"
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-advanced-staging-buffers",
                "Enable Sodium advanced staging buffers",
                "This is a safe default on supported hardware because it improves GPU upload behavior and can reduce render pipeline stalls.",
                PerformanceBottleneck.GPU,
                PerformanceRecommendation.Severity.SAFE,
                "advanced.use_advanced_staging_buffers",
                "current",
                "true"
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-no-error-gl",
                "Enable Sodium no-error GL context",
                "This reduces graphics driver validation overhead. Koil treats it as optional because driver support can vary, but it is a direct Sodium performance setting.",
                PerformanceBottleneck.GPU,
                PerformanceRecommendation.Severity.OPTIONAL,
                "performance.use_no_error_g_l_context",
                "current",
                "true"
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-render-ahead",
                "Set Sodium render-ahead from hardware",
                "Koil chose this from CPU/GPU tier and live frame spikes. Lower values favor latency/stability; higher values can keep stronger GPUs fed.",
                PerformanceBottleneck.GPU,
                PerformanceRecommendation.Severity.CAUTION,
                "advanced.cpu_render_ahead_limit",
                "current",
                PerformanceHardwareAdvisor.sodiumRenderAhead(hardware, snapshot)
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-chunk-builder-threads",
                "Set Sodium chunk builder threads from hardware",
                "Koil chose this from CPU thread count and live chunk/frame spikes instead of using a fixed preset.",
                PerformanceBottleneck.CHUNK_STORAGE,
                PerformanceRecommendation.Severity.CAUTION,
                "performance.chunk_builder_threads",
                "current",
                PerformanceHardwareAdvisor.sodiumChunkBuilderThreads(hardware, snapshot)
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-leaves-quality",
                "Set Sodium leaves quality from GPU/display",
                "Koil only lowers leaves quality when hardware or measured render pressure justifies a visible tradeoff. Otherwise it prefers backend culling and upload optimizations first.",
                PerformanceBottleneck.GPU,
                PerformanceRecommendation.Severity.OPTIONAL,
                "quality.leaves_quality",
                "current",
                PerformanceHardwareAdvisor.sodiumLeavesQuality(hardware, snapshot)
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-weather-quality",
                "Set Sodium weather quality from GPU/display",
                "Koil only lowers weather quality when render pressure is visible or the GPU profile needs the cheaper path; invisible backend optimizations are preferred first.",
                PerformanceBottleneck.GPU,
                PerformanceRecommendation.Severity.OPTIONAL,
                "quality.weather_quality",
                "current",
                PerformanceHardwareAdvisor.sodiumWeatherQuality(hardware, snapshot)
        );
        addProviderRecommendation(recommendations, providerSettings,
                "hardware-sodium-extra-macos-resolution",
                "Set Sodium Extra macOS resolution reduction",
                "This is only enabled when the OS and display/GPU profile indicate it is useful. Non-macOS systems keep it disabled.",
                PerformanceBottleneck.GPU,
                PerformanceRecommendation.Severity.SAFE,
                "extra_settings.reduce_resolution_on_mac",
                "current",
                String.valueOf(PerformanceHardwareAdvisor.reduceResolutionOnMac(hardware))
        );
    }

    private static void addProviderRecommendation(List<PerformanceRecommendation> recommendations, Set<String> providerKeys, String id, String title, String reason, PerformanceBottleneck bottleneck, PerformanceRecommendation.Severity severity, String settingKey, String beforeValue, String afterValue) {
        if (!providerKeys.contains(settingKey) || recommendations.stream().anyMatch(recommendation -> recommendation.id().equals(id) || recommendation.settingKey().equals(settingKey))) {
            return;
        }
        recommendations.add(new PerformanceRecommendation(id, title, reason, bottleneck, severity, true, settingKey, beforeValue, afterValue));
    }

    private static void addProviderRecommendation(List<PerformanceRecommendation> recommendations, Map<String, PerformanceSettingDescriptor> providerSettings, String id, String title, String reason, PerformanceBottleneck bottleneck, PerformanceRecommendation.Severity severity, String settingKey, String beforeValue, String afterValue) {
        PerformanceSettingDescriptor setting = providerSettings.get(settingKey);
        if (setting == null || sameValue(setting.currentValue(), afterValue) || recommendations.stream().anyMatch(recommendation -> recommendation.id().equals(id) || recommendation.settingKey().equals(settingKey))) {
            return;
        }
        recommendations.add(new PerformanceRecommendation(id, title, reason, bottleneck, severity, true, settingKey, setting.currentValue() == null ? beforeValue : setting.currentValue(), afterValue));
    }

    private static void addChangedProviderRecommendations(List<PerformanceRecommendation> recommendations, List<PerformanceSettingDescriptor> providerSettings, String providerId, PerformanceBottleneck bottleneck, String reasonPrefix) {
        for (PerformanceSettingDescriptor setting : providerSettings) {
            if (!providerId.equals(setting.providerId()) || sameValue(setting.currentValue(), setting.recommendedValue())) {
                continue;
            }
            if (recommendations.stream().anyMatch(recommendation -> setting.settingId().equals(recommendation.settingKey()))) {
                continue;
            }
            PerformanceRecommendation.Severity severity = severityFromRisk(setting.risk());
            boolean safeAutoFix = severity != PerformanceRecommendation.Severity.MANUAL_REVIEW && setting.liveApplySupported();
            recommendations.add(new PerformanceRecommendation(
                    "provider-" + providerId + "-" + sanitizeId(setting.settingId()),
                    "Optimize " + setting.label(),
                    reasonPrefix + " " + setting.description(),
                    bottleneck,
                    severity,
                    safeAutoFix,
                    setting.settingId(),
                    setting.currentValue(),
                    setting.recommendedValue()
            ));
        }
    }

    private static PerformanceRecommendation.Severity severityFromRisk(String risk) {
        String lower = risk == null ? "" : risk.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("manual")) {
            return PerformanceRecommendation.Severity.MANUAL_REVIEW;
        }
        if (lower.contains("risky")) {
            return PerformanceRecommendation.Severity.RISKY;
        }
        if (lower.contains("caution")) {
            return PerformanceRecommendation.Severity.CAUTION;
        }
        if (lower.contains("safe")) {
            return PerformanceRecommendation.Severity.SAFE;
        }
        return PerformanceRecommendation.Severity.OPTIONAL;
    }

    private static String sanitizeId(String value) {
        String normalized = value == null ? "setting" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        if (normalized.length() > 80) {
            return normalized.substring(0, 80);
        }
        return normalized;
    }

    private static boolean sameValue(String currentValue, String afterValue) {
        if (currentValue == null || afterValue == null) {
            return false;
        }
        return currentValue.trim().equalsIgnoreCase(afterValue.trim());
    }

    private static boolean isVanillaVideoSetting(String settingKey) {
        String key = PerformanceSettingKeyMatcher.canonical(settingKey);
        return key.equals("renderdistance")
                || key.equals("simulationdistance")
                || key.equals("entitydistance")
                || key.equals("maxfps")
                || key.equals("clouds")
                || key.equals("mipmaps")
                || key.equals("particles")
                || key.equals("graphicsmode")
                || key.equals("smoothlighting")
                || key.equals("biomeblend")
                || key.equals("entityshadows")
                || key.equals("vsync");
    }
}
