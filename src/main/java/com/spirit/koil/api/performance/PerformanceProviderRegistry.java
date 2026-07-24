package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerformanceProviderRegistry {
    private PerformanceProviderRegistry() {
    }

    public static List<PerformanceOptimizationProvider> providers() {
        Path config = FabricLoader.getInstance().getConfigDir();
        List<PerformanceOptimizationProvider> providers = new ArrayList<>();
        providers.add(new VanillaPerformanceProvider());
        providers.add(new TargetedJsonPerformanceConfigProvider("sodium", "Sodium", config.resolve("sodium-options.json"), sodiumRules()));
        providers.add(new TargetedJsonPerformanceConfigProvider("sodium-extra", "Sodium Extra", config.resolve("sodium-extra-options.json"), sodiumExtraRules()));
        providers.add(new PropertiesPerformanceConfigProvider("iris", "Iris", config.resolve("iris.properties"), List.of("shader", "shadow", "quality", "performance")));
        providers.add(new ShaderpackPerformanceConfigProvider(FabricLoader.getInstance().getGameDir().resolve("shaderpacks")));
        providers.add(new TargetedJsonPerformanceConfigProvider("entityculling", "EntityCulling", config.resolve("entityculling.json"), entityCullingRules()));
        providers.add(new JsonPerformanceConfigProvider("immediatelyfast", "ImmediatelyFast", config.resolve("immediatelyfast.json"), List.of("hud", "batch", "map", "text", "buffer")));
        providers.add(new PropertiesPerformanceConfigProvider("ferritecore", "FerriteCore", config.resolve("ferritecore.mixin.properties"), List.of("mixin", "cache", "memory")));
        providers.add(new PropertiesPerformanceConfigProvider("modernfix", "ModernFix", config.resolve("modernfix-mixins.properties"), List.of("mixin", "perf", "bugfix", "feature")));
        providers.add(new PropertiesPerformanceConfigProvider("c2me", "C2ME", config.resolve("c2me.toml"), List.of("chunk", "thread", "async", "io", "worldgen")));
        return providers;
    }

    private static List<TargetedJsonPerformanceConfigProvider.JsonRule> sodiumRules() {
        return List.of(
                new TargetedJsonPerformanceConfigProvider.JsonRule("quality.weather_quality", "Weather quality", "sodium/render/weather", "FAST", "optional", true, true, "Lowers rain/snow rendering cost through Sodium."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("quality.leaves_quality", "Leaves quality", "sodium/render/foliage", "FAST", "optional", true, true, "Reduces foliage overdraw and chunk mesh pressure."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("quality.enable_vignette", "Vignette", "sodium/ui/render", "false", "optional", true, true, "Removes a small fullscreen post-process cost."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("advanced.enable_memory_tracing", "Memory tracing", "sodium/debug", "false", "safe", true, true, "Keeps Sodium debug memory tracing disabled outside diagnostics."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("advanced.use_advanced_staging_buffers", "Advanced staging buffers", "sodium/gpu/upload", "true", "safe", true, true, "Keeps Sodium's advanced GPU upload path enabled."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("advanced.cpu_render_ahead_limit", "CPU render ahead limit", "sodium/frame-pacing", "2", "caution", true, true, "Reduces queued render work when frame pacing or input latency is unstable."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("performance.chunk_builder_threads", "Chunk builder threads", "sodium/chunk", "0", "caution", true, true, "Uses Sodium's auto thread count normally, or a bounded count when chunk rebuild spikes dominate."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("performance.always_defer_chunk_updates_v2", "Defer chunk updates", "sodium/chunk", "true", "safe", true, true, "Defers chunk rebuild work to smooth frame-time spikes."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("performance.animate_only_visible_textures", "Animate only visible textures", "sodium/texture", "true", "safe", true, true, "Avoids updating animations that are not visible."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("performance.use_entity_culling", "Entity culling", "sodium/entity", "true", "safe", true, true, "Skips rendering entities hidden from view."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("performance.use_fog_occlusion", "Fog occlusion", "sodium/chunk", "true", "safe", true, true, "Uses fog to cull hidden chunk rendering."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("performance.use_block_face_culling", "Block face culling", "sodium/chunk", "true", "safe", true, true, "Skips hidden block faces in chunk meshes."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("performance.use_no_error_g_l_context", "No-error GL context", "sodium/gpu", "true", "optional", true, true, "Reduces graphics driver validation overhead where supported.")
        );
    }

    private static List<TargetedJsonPerformanceConfigProvider.JsonRule> sodiumExtraRules() {
        return List.of(
                new TargetedJsonPerformanceConfigProvider.JsonRule("animation_settings.animation", "Master texture animation", "sodium-extra/animation", "true", "optional", true, true, "Keeps normal animation enabled unless individual expensive animation sources are disabled."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("animation_settings.water", "Water animation", "sodium-extra/animation", "false", "optional", true, true, "Disables water texture animation during texture/update pressure."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("animation_settings.lava", "Lava animation", "sodium-extra/animation", "false", "optional", true, true, "Disables lava texture animation during texture/update pressure."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("animation_settings.fire", "Fire animation", "sodium-extra/animation", "false", "optional", true, true, "Reduces animated texture work in effect-heavy scenes."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("animation_settings.portal", "Portal animation", "sodium-extra/animation", "false", "optional", true, true, "Reduces animated texture work for portal-heavy scenes."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("animation_settings.block_animations", "Block animations", "sodium-extra/animation", "false", "optional", true, true, "Reduces block animation update work in busy scenes."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("animation_settings.sculk_sensor", "Sculk sensor animation", "sodium-extra/animation", "false", "optional", true, true, "Reduces rare but animated block update work."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("particle_settings.particles", "Sodium Extra particles", "sodium-extra/particles", "true", "optional", true, true, "Keeps global particle rendering available while Koil can disable specific high-frequency particle sources."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("particle_settings.rain_splash", "Rain splash particles", "sodium-extra/particles", "false", "safe", true, true, "Reduces weather particle cost."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("particle_settings.block_break", "Block break particles", "sodium-extra/particles", "false", "optional", true, true, "Reduces block-break particle cost."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("particle_settings.block_breaking", "Block breaking particles", "sodium-extra/particles", "false", "optional", true, true, "Reduces block interaction particle cost."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("detail_settings.rain_snow", "Rain and snow detail", "sodium-extra/weather", "false", "optional", true, true, "Reduces weather rendering cost."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.light_updates", "Light updates", "sodium-extra/chunk-light", "false", "caution", true, true, "Reduces client light update pressure when chunk rebuilds are spiking."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.item_frame", "Item frame rendering", "sodium-extra/entity", "false", "optional", true, true, "Reduces item frame render cost in decoration-heavy areas."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.armor_stand", "Armor stand rendering", "sodium-extra/entity", "false", "optional", true, true, "Reduces armor stand render cost in entity-heavy areas."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.painting", "Painting rendering", "sodium-extra/entity", "false", "optional", true, true, "Reduces decorative entity rendering in builds with many paintings."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.piston", "Piston rendering", "sodium-extra/block-entity", "false", "optional", true, true, "Reduces animated piston render work in redstone-heavy areas."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.beacon_beam", "Beacon beam rendering", "sodium-extra/render", "false", "optional", true, true, "Disables beacon beam rendering when visual effects are expensive."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.enchanting_table_book", "Enchanting book rendering", "sodium-extra/block-entity", "false", "optional", true, true, "Reduces animated enchanting-table render work."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.item_frame_name_tag", "Item frame name tags", "sodium-extra/entity", "false", "optional", true, true, "Reduces name-tag rendering over item frames."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("render_settings.player_name_tag", "Player name tags", "sodium-extra/entity", "true", "manual-review", true, true, "Keeps player name tags enabled by default because hiding them can affect multiplayer readability."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("extra_settings.reduce_resolution_on_mac", "Reduce resolution on macOS", "sodium-extra/os-display", "false", "safe", true, true, "Enables Sodium Extra's macOS resolution reduction only on macOS hardware where display/GPU pressure makes it useful."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("extra_settings.cloud_distance", "Cloud distance", "sodium-extra/render/clouds", "64", "optional", true, true, "Bounds Sodium Extra cloud distance from maxed values while keeping clouds readable."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("extra_settings.toasts", "Sodium Extra toasts", "sodium-extra/ui", "false", "optional", true, true, "Reduces toast/UI popup work while diagnosing UI or frame spikes.")
        );
    }

    private static List<TargetedJsonPerformanceConfigProvider.JsonRule> entityCullingRules() {
        return List.of(
                new TargetedJsonPerformanceConfigProvider.JsonRule("tracingDistance", "Tracing distance", "entityculling/raycast", "96", "optional", true, true, "Bounds EntityCulling tracing distance from maxed values so visibility checks stay cheaper."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("hitboxLimit", "Hitbox limit", "entityculling/entity", "40", "optional", true, true, "Reduces culling hitbox work in entity-heavy scenes."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("tickCulling", "Tick culling", "entityculling/tick", "true", "safe", true, true, "Keeps EntityCulling tick culling enabled."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("skipMarkerArmorStands", "Skip marker armor stands", "entityculling/entity", "true", "safe", true, true, "Keeps marker armor stands out of expensive culling checks."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("skipEntityCulling", "Skip entity culling", "entityculling/entity", "false", "safe", true, true, "Must stay false so EntityCulling actually culls entities."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("skipBlockEntityCulling", "Skip block entity culling", "entityculling/block-entity", "false", "safe", true, true, "Must stay false so block entities can be culled."),
                new TargetedJsonPerformanceConfigProvider.JsonRule("renderNametagsThroughWalls", "Name tags through walls", "entityculling/entity", "false", "optional", true, true, "Avoids rendering hidden name tags through walls.")
        );
    }

    public static List<PerformanceSettingDescriptor> settings(MinecraftClient client, PerformanceSnapshot snapshot) {
        Map<String, PerformanceSettingDescriptor> settings = new LinkedHashMap<>();
        for (PerformanceOptimizationProvider provider : providers()) {
            try {
                for (PerformanceSettingDescriptor setting : provider.settings(client, snapshot)) {
                    if (setting == null) {
                        continue;
                    }
                    if (!setting.hasVerifiedCurrentValue()) {
                        continue;
                    }
                    String key = setting.providerId() + "\u0000" + setting.settingId();
                    settings.putIfAbsent(key, setting);
                }
            } catch (Throwable ignored) {
                // A broken optional provider must not remove verified values from
                // Minecraft or the other installed providers.
            }
        }
        return List.copyOf(settings.values());
    }
}
