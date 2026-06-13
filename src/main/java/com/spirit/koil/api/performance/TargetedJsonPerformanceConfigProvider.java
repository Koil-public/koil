package com.spirit.koil.api.performance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class TargetedJsonPerformanceConfigProvider implements PerformanceOptimizationProvider {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final String modId;
    private final String label;
    private final Path configPath;
    private final List<JsonRule> rules;

    TargetedJsonPerformanceConfigProvider(String modId, String label, Path configPath, List<JsonRule> rules) {
        this.modId = modId;
        this.label = label;
        this.configPath = configPath;
        this.rules = rules;
    }

    @Override
    public String id() {
        return modId;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean installed() {
        return FabricLoader.getInstance().isModLoaded(modId) || configAvailable();
    }

    @Override
    public boolean configAvailable() {
        return Files.exists(configPath);
    }

    @Override
    public String status() {
        if (!installed()) {
            return "not installed";
        }
        return configAvailable() ? "targeted config editable; live reload requested after apply" : "installed; config missing";
    }

    @Override
    public List<PerformanceSettingDescriptor> settings(MinecraftClient client, PerformanceSnapshot snapshot) {
        List<PerformanceSettingDescriptor> settings = new ArrayList<>();
        if (!installed() || !configAvailable()) {
            return settings;
        }
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                return settings;
            }
            JsonObject object = root.getAsJsonObject();
            PerformanceHardwareAdvisor.HardwareClass hardware = PerformanceHardwareAdvisor.classify(client);
            for (JsonRule rule : rules) {
                JsonElement current = getPath(object, rule.path());
                if (current == null || current.isJsonNull()) {
                    continue;
                }
                String currentValue = primitiveValue(current);
                settings.add(new PerformanceSettingDescriptor(
                        id(),
                        rule.path(),
                        rule.label(),
                        rule.category(),
                        currentValue,
                        rule.recommendedValue(hardware, snapshot, currentValue),
                        rule.risk(hardware, snapshot),
                        rule.liveApplySupported(),
                        rule.requiresResourceReload(),
                        configPath.toString(),
                        rule.description()
                ));
            }
        } catch (Exception ignored) {
        }
        return settings;
    }

    @Override
    public PerformanceProviderApplyResult apply(MinecraftClient client, PerformanceSettingDescriptor setting) {
        try {
            backup();
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(configPath)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (!setPath(root, setting.settingId(), setting.recommendedValue())) {
                return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, "Setting path was not writable.");
            }
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(root, writer);
            }
            if (client != null && setting.requiresResourceReload()) {
                client.reloadResources();
            }
            return new PerformanceProviderApplyResult(id(), setting.settingId(), true, setting.liveApplySupported(), setting.requiresResourceReload(), "Targeted " + label + " config updated.");
        } catch (Exception exception) {
            return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, exception.getMessage());
        }
    }

    private JsonElement getPath(JsonObject root, String path) {
        JsonElement literal = root.get(path);
        if (literal != null) {
            return literal;
        }
        String[] parts = path.split("\\.");
        JsonElement current = root;
        for (String part : parts) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    private boolean setPath(JsonObject root, String path, String value) {
        JsonElement literal = root.get(path);
        if (literal != null) {
            writePrimitive(root, path, literal, value);
            return true;
        }
        String[] parts = path.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = current.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                return false;
            }
            current = child.getAsJsonObject();
        }
        String leaf = parts[parts.length - 1];
        JsonElement existing = current.get(leaf);
        writePrimitive(current, leaf, existing, value);
        return true;
    }

    private void writePrimitive(JsonObject object, String key, JsonElement existing, String value) {
        if (existing != null && existing.isJsonPrimitive()) {
            if (existing.getAsJsonPrimitive().isBoolean()) {
                object.addProperty(key, Boolean.parseBoolean(value));
                return;
            }
            if (existing.getAsJsonPrimitive().isNumber()) {
                try {
                    if (value.contains(".")) {
                        object.addProperty(key, Double.parseDouble(value));
                    } else {
                        object.addProperty(key, Integer.parseInt(value));
                    }
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        object.addProperty(key, value);
    }

    private String primitiveValue(JsonElement element) {
        return element.isJsonPrimitive() ? element.getAsJsonPrimitive().getAsString() : element.toString();
    }

    private void backup() throws java.io.IOException {
        Files.createDirectories(PerformancePaths.BACKUPS);
        Files.copy(configPath, PerformancePaths.BACKUPS.resolve(configPath.getFileName() + "-" + System.currentTimeMillis() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
    }

    record JsonRule(
            String path,
            String label,
            String category,
            String recommendedValue,
            String risk,
            boolean liveApplySupported,
            boolean requiresResourceReload,
            String description
    ) {
        String recommendedValue(PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot, String currentValue) {
            if ("performance.chunk_builder_threads".equals(path)) {
                return PerformanceHardwareAdvisor.sodiumChunkBuilderThreads(hardware, snapshot);
            }
            if ("advanced.cpu_render_ahead_limit".equals(path)) {
                return PerformanceHardwareAdvisor.sodiumRenderAhead(hardware, snapshot);
            }
            if ("quality.weather_quality".equals(path) || "quality.leaves_quality".equals(path)) {
                return "quality.weather_quality".equals(path)
                        ? PerformanceHardwareAdvisor.sodiumWeatherQuality(hardware, snapshot)
                        : PerformanceHardwareAdvisor.sodiumLeavesQuality(hardware, snapshot);
            }
            if ("extra_settings.cloud_distance".equals(path)) {
                return PerformanceHardwareAdvisor.sodiumExtraCloudDistance(hardware, snapshot);
            }
            if ("tracingDistance".equals(path)) {
                return PerformanceHardwareAdvisor.entityCullingTracingDistance(hardware, snapshot);
            }
            if ("hitboxLimit".equals(path)) {
                return PerformanceHardwareAdvisor.entityCullingHitboxLimit(hardware, snapshot);
            }
            if ("extra_settings.reduce_resolution_on_mac".equals(path)) {
                return String.valueOf(PerformanceHardwareAdvisor.reduceResolutionOnMac(hardware));
            }
            return recommendedValue;
        }

        String risk(PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot) {
            if ("performance.chunk_builder_threads".equals(path) && snapshot != null && snapshot.chunkStress() > 0.65D) {
                return "caution";
            }
            if ("extra_settings.reduce_resolution_on_mac".equals(path) && hardware.macOs()) {
                return "safe";
            }
            return risk;
        }
    }
}
