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
import java.util.Locale;

final class JsonPerformanceConfigProvider implements PerformanceOptimizationProvider {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final String modId;
    private final String label;
    private final Path configPath;
    private final List<String> relevantKeys;

    JsonPerformanceConfigProvider(String modId, String label, Path configPath, List<String> relevantKeys) {
        this.modId = modId;
        this.label = label;
        this.configPath = configPath;
        this.relevantKeys = relevantKeys;
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
        return FabricLoader.getInstance().isModLoaded(modId);
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
        return configAvailable() ? "config editable; live reload requested after apply" : "installed; config missing";
    }

    @Override
    public List<PerformanceSettingDescriptor> settings(MinecraftClient client, PerformanceSnapshot snapshot) {
        List<PerformanceSettingDescriptor> settings = new ArrayList<>();
        if (!installed() || !configAvailable()) {
            return settings;
        }
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            collect(settings, "", root);
        } catch (Exception ignored) {
        }
        return settings.stream().limit(24).toList();
    }

    private void collect(List<PerformanceSettingDescriptor> settings, String path, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                collect(settings, path.isEmpty() ? key : path + "." + key, object.get(key));
            }
            return;
        }
        if (!isRelevant(path)) {
            return;
        }
        String value = element.isJsonPrimitive() ? element.getAsJsonPrimitive().getAsString() : element.toString();
        settings.add(new PerformanceSettingDescriptor(
                id(),
                path,
                path,
                "optimization mod",
                value,
                value,
                "manual-review",
                false,
                false,
                configPath.toString(),
                label + " config key. The current value is shown exactly as stored; Koil does not invent a target without a provider-specific rule."
        ));
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
            requestReload(client);
            return new PerformanceProviderApplyResult(id(), setting.settingId(), true, true, true, "Config updated and live reload requested.");
        } catch (Exception exception) {
            return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, exception.getMessage());
        }
    }

    private boolean setPath(JsonObject root, String path, String value) {
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
        if (existing != null && existing.isJsonPrimitive()) {
            if (existing.getAsJsonPrimitive().isBoolean()) {
                current.addProperty(leaf, Boolean.parseBoolean(value));
                return true;
            }
            if (existing.getAsJsonPrimitive().isNumber()) {
                try {
                    current.addProperty(leaf, Double.parseDouble(value));
                    return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        current.addProperty(leaf, value);
        return true;
    }

    private boolean isRelevant(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String key : relevantKeys) {
            if (lower.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private void backup() throws java.io.IOException {
        Files.createDirectories(PerformancePaths.BACKUPS);
        Files.copy(configPath, PerformancePaths.BACKUPS.resolve(configPath.getFileName() + "-" + System.currentTimeMillis() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
    }

    private void requestReload(MinecraftClient client) {
        if (client != null) {
            client.reloadResources();
        }
    }
}
