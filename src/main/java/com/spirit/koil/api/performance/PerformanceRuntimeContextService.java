package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackProfile;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class PerformanceRuntimeContextService {
    private static long shaderDetectionAtMillis;
    private static ShaderDetection cachedShaderDetection = new ShaderDetection(false, false, "none detected");

    private PerformanceRuntimeContextService() {
    }

    public static PerformanceRuntimeContext capture(MinecraftClient client) {
        List<String> packs = enabledResourcePacks(client);
        List<String> optimizationConfigs = optimizationConfigNotes();
        String worldType = worldType(client);
        String worldName = worldName(client);
        String server = serverAddress(client);
        String dimension = dimension(client);
        ShaderDetection shader = shaderDetection();
        String suggested = suggestedProfile(worldType, packs, shader.active(), FabricLoader.getInstance().getAllMods().size());
        List<String> notes = new ArrayList<>();
        if (FabricLoader.getInstance().getAllMods().size() > 180) {
            notes.add("Large modpack detected. Mod count is a workload-size signal, not proof that mods are the active bottleneck.");
        }
        if (packs.size() > 6) {
            notes.add("Many enabled resource packs can increase texture reload and graphics-memory pressure.");
        }
        if (!optimizationConfigs.isEmpty()) {
            notes.add("Optimization config files detected. Provider recommendations can target verified values without disabling the mods.");
        }
        if (shader.active()) {
            notes.add("An active shader pack was detected. Shader pressure is reported as an estimate based on frame performance, not GPU utilization.");
        } else if (shader.installed()) {
            notes.add("A shader loader is installed, but an active shader pack was not verified.");
        }
        return new PerformanceRuntimeContext(
                System.currentTimeMillis(),
                worldType,
                worldName,
                server,
                dimension,
                profileKey(worldType, worldName, server, dimension),
                suggested,
                packs.size(),
                packs,
                shader.label(),
                optimizationConfigs,
                notes
        );
    }

    public static boolean shaderPipelineActive() {
        return shaderDetection().active();
    }

    private static String worldType(MinecraftClient client) {
        if (client == null || client.world == null) {
            return "menu";
        }
        if (client.getCurrentServerEntry() != null) {
            return "server";
        }
        return "singleplayer";
    }

    private static String worldName(MinecraftClient client) {
        try {
            if (client == null || client.world == null) {
                return "none";
            }
            if (client.isInSingleplayer()) {
                return client.getServer() != null ? client.getServer().getSaveProperties().getLevelName() : "singleplayer";
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String serverAddress(MinecraftClient client) {
        try {
            return client != null && client.getCurrentServerEntry() != null ? client.getCurrentServerEntry().address : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String dimension(MinecraftClient client) {
        try {
            return client != null && client.world != null ? client.world.getRegistryKey().getValue().toString() : "none";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static List<String> enabledResourcePacks(MinecraftClient client) {
        try {
            if (client == null || client.getResourcePackManager() == null) {
                return List.of();
            }
            return client.getResourcePackManager().getEnabledProfiles().stream()
                    .map(ResourcePackProfile::getName)
                    .toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static ShaderDetection shaderDetection() {
        long now = System.currentTimeMillis();
        if (now - shaderDetectionAtMillis < 1000L) {
            return cachedShaderDetection;
        }
        shaderDetectionAtMillis = now;
        boolean iris = FabricLoader.getInstance().isModLoaded("iris");
        boolean oculus = FabricLoader.getInstance().isModLoaded("oculus");
        if (iris || oculus) {
            ShaderDetection detected = detectIrisApi("net.irisshaders.iris.api.v0.IrisApi", iris ? "Iris" : "Oculus");
            if (detected != null) {
                cachedShaderDetection = detected;
                return detected;
            }
            detected = detectIrisApi("net.coderbot.iris.api.v0.IrisApi", iris ? "Iris" : "Oculus");
            if (detected != null) {
                cachedShaderDetection = detected;
                return detected;
            }
            cachedShaderDetection = new ShaderDetection(true, false, (iris ? "Iris" : "Oculus") + " installed; active pack state unavailable");
            return cachedShaderDetection;
        }
        Path shaderpacks = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
        try (Stream<Path> files = Files.exists(shaderpacks) ? Files.list(shaderpacks) : Stream.empty()) {
            if (files.findAny().isPresent()) {
                cachedShaderDetection = new ShaderDetection(false, false, "Shader packs available; no active shader loader detected");
                return cachedShaderDetection;
            }
        } catch (Exception ignored) {
        }
        cachedShaderDetection = new ShaderDetection(false, false, "none detected");
        return cachedShaderDetection;
    }

    private static ShaderDetection detectIrisApi(String className, String loaderName) {
        try {
            Class<?> apiClass = Class.forName(className);
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method inUse = apiClass.getMethod("isShaderPackInUse");
            boolean active = Boolean.TRUE.equals(inUse.invoke(api));
            String packName = shaderPackName(apiClass, api);
            if (active) {
                return new ShaderDetection(true, true, packName.isBlank() ? loaderName + " shader pack active" : loaderName + " active: " + packName);
            }
            return new ShaderDetection(true, false, loaderName + " installed, shaders off");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shaderPackName(Class<?> apiClass, Object api) {
        try {
            Method method = apiClass.getMethod("getCurrentShaderPackName");
            Object value = method.invoke(api);
            if (value instanceof Optional<?> optional) {
                return optional.map(Object::toString).orElse("");
            }
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<String> optimizationConfigNotes() {
        Path config = FabricLoader.getInstance().getConfigDir();
        List<String> notes = new ArrayList<>();
        addIfExists(notes, config.resolve("sodium-options.json"), "Sodium config");
        addIfExists(notes, config.resolve("iris.properties"), "Iris config");
        addIfExists(notes, config.resolve("ferritecore.mixin.properties"), "FerriteCore config");
        addIfExists(notes, config.resolve("immediatelyfast.json"), "ImmediatelyFast config");
        addIfExists(notes, config.resolve("entityculling.json"), "EntityCulling config");
        addIfExists(notes, config.resolve("modernfix-mixins.properties"), "ModernFix config");
        addIfExists(notes, config.resolve("c2me.toml"), "C2ME config");
        return notes;
    }

    private static void addIfExists(List<String> notes, Path path, String label) {
        if (Files.exists(path)) {
            notes.add(label + ": " + path.getFileName());
        }
    }

    private static String suggestedProfile(String worldType, List<String> packs, boolean shaderActive, int modCount) {
        if (shaderActive) {
            return PerformanceProfileMode.SHADER_FRIENDLY.name().toLowerCase(Locale.ROOT);
        }
        if ("server".equals(worldType)) {
            return PerformanceProfileMode.SERVER_FRIENDLY.name().toLowerCase(Locale.ROOT);
        }
        if (modCount > 180 || packs.size() > 8) {
            return "heavy_modded";
        }
        return PerformanceProfileMode.BALANCED.name().toLowerCase(Locale.ROOT);
    }

    private static String profileKey(String worldType, String worldName, String server, String dimension) {
        String target = "server".equals(worldType) ? server : worldName;
        if (target == null || target.isBlank()) {
            target = "default";
        }
        return (worldType + "/" + target + "/" + dimension).replaceAll("[^a-zA-Z0-9._/-]", "_");
    }

    private record ShaderDetection(boolean installed, boolean active, String label) {
    }
}
