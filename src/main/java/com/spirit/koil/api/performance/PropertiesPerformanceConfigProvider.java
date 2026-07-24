package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PropertiesPerformanceConfigProvider implements PerformanceOptimizationProvider {
    private final String modId;
    private final String label;
    private final Path configPath;
    private final List<String> relevantKeys;

    PropertiesPerformanceConfigProvider(String modId, String label, Path configPath, List<String> relevantKeys) {
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
        return configAvailable() ? "config editable; live reload requested after apply" : "installed; config missing";
    }

    @Override
    public List<PerformanceSettingDescriptor> settings(MinecraftClient client, PerformanceSnapshot snapshot) {
        List<PerformanceSettingDescriptor> settings = new ArrayList<>();
        if (!installed() || !configAvailable()) {
            return settings;
        }
        try {
            List<String> lines = Files.readAllLines(configPath);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) {
                    continue;
                }
                int split = trimmed.contains("=") ? trimmed.indexOf('=') : trimmed.indexOf(':');
                if (split <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, split).trim();
                String value = trimmed.substring(split + 1).trim();
                if (!isRelevant(key)) {
                    continue;
                }
                settings.add(new PerformanceSettingDescriptor(
                        id(),
                        key,
                        key,
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
        } catch (Exception ignored) {
        }
        return settings.stream().limit(24).toList();
    }

    @Override
    public PerformanceProviderApplyResult apply(MinecraftClient client, PerformanceSettingDescriptor setting) {
        try {
            backup();
            List<String> lines = Files.readAllLines(configPath);
            boolean changed = false;
            List<String> output = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                int split = trimmed.contains("=") ? trimmed.indexOf('=') : trimmed.indexOf(':');
                if (split > 0 && trimmed.substring(0, split).trim().equals(setting.settingId())) {
                    char separator = trimmed.contains("=") ? '=' : ':';
                    output.add(setting.settingId() + separator + setting.recommendedValue());
                    changed = true;
                } else {
                    output.add(line);
                }
            }
            if (!changed) {
                return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, "Setting key was not found.");
            }
            Files.write(configPath, output);
            if (client != null) {
                client.reloadResources();
            }
            return new PerformanceProviderApplyResult(id(), setting.settingId(), true, true, true, "Config updated and live reload requested.");
        } catch (Exception exception) {
            return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, exception.getMessage());
        }
    }

    private boolean isRelevant(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String relevant : relevantKeys) {
            if (lower.contains(relevant)) {
                return true;
            }
        }
        return false;
    }

    private void backup() throws java.io.IOException {
        Files.createDirectories(PerformancePaths.BACKUPS);
        Files.copy(configPath, PerformancePaths.BACKUPS.resolve(configPath.getFileName() + "-" + System.currentTimeMillis() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
    }
}
