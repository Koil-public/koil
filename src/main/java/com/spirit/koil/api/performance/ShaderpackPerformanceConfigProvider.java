package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class ShaderpackPerformanceConfigProvider implements PerformanceOptimizationProvider {
    private final Path shaderpacksDir;

    ShaderpackPerformanceConfigProvider(Path shaderpacksDir) {
        this.shaderpacksDir = shaderpacksDir;
    }

    @Override
    public String id() {
        return "shaderpack";
    }

    @Override
    public String label() {
        return "Shaderpack Options";
    }

    @Override
    public boolean installed() {
        return FabricLoader.getInstance().isModLoaded("iris") || configAvailable();
    }

    @Override
    public boolean configAvailable() {
        return Files.isDirectory(shaderpacksDir) && !optionFiles().isEmpty();
    }

    @Override
    public String status() {
        if (!installed()) {
            return "not installed";
        }
        return configAvailable() ? "shaderpack option files editable; live resource reload requested after apply" : "installed; shaderpack option files missing";
    }

    @Override
    public List<PerformanceSettingDescriptor> settings(MinecraftClient client, PerformanceSnapshot snapshot) {
        List<PerformanceSettingDescriptor> settings = new ArrayList<>();
        if (!installed() || !configAvailable()) {
            return settings;
        }
        PerformanceHardwareAdvisor.HardwareClass hardware = PerformanceHardwareAdvisor.classify(client);
        for (Path file : optionFiles()) {
            try {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int split = trimmed.contains("=") ? trimmed.indexOf('=') : trimmed.indexOf(':');
                    if (split <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, split).trim();
                    String value = trimmed.substring(split + 1).trim();
                    if (!isRelevantShaderKey(key)) {
                        continue;
                    }
                    String recommended = recommendedValue(key, value, hardware, snapshot);
                    settings.add(new PerformanceSettingDescriptor(
                            id(),
                            file.getFileName() + "::" + key,
                            readableKey(file.getFileName().toString(), key),
                            category(key),
                            value,
                            recommended,
                            risk(key),
                            true,
                            true,
                            file.toString(),
                            description(key, value, recommended)
                    ));
                }
            } catch (Exception ignored) {
            }
        }
        return settings;
    }

    @Override
    public PerformanceProviderApplyResult apply(MinecraftClient client, PerformanceSettingDescriptor setting) {
        try {
            String[] parts = setting.settingId().split("::", 2);
            if (parts.length != 2 || parts[0].contains("/") || parts[0].contains("\\") || parts[1].isBlank()) {
                return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, "Invalid shaderpack setting id.");
            }
            Path file = shaderpacksDir.resolve(parts[0]).normalize();
            if (!file.startsWith(shaderpacksDir.normalize()) || !Files.exists(file)) {
                return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, "Shaderpack option file was not found.");
            }
            backup(file);
            List<String> lines = Files.readAllLines(file);
            List<String> output = new ArrayList<>();
            boolean changed = false;
            for (String line : lines) {
                String trimmed = line.trim();
                int split = trimmed.contains("=") ? trimmed.indexOf('=') : trimmed.indexOf(':');
                if (split > 0 && trimmed.substring(0, split).trim().equals(parts[1])) {
                    char separator = trimmed.contains("=") ? '=' : ':';
                    output.add(parts[1] + separator + setting.recommendedValue());
                    changed = true;
                } else {
                    output.add(line);
                }
            }
            if (!changed) {
                return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, "Shaderpack option key was not found.");
            }
            Files.write(file, output);
            if (client != null) {
                client.reloadResources();
            }
            return new PerformanceProviderApplyResult(id(), setting.settingId(), true, true, true, "Shaderpack option updated and resource reload requested.");
        } catch (Exception exception) {
            return new PerformanceProviderApplyResult(id(), setting.settingId(), false, false, false, exception.getMessage());
        }
    }

    private List<Path> optionFiles() {
        if (!Files.isDirectory(shaderpacksDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(shaderpacksDir)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean isRelevantShaderKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "shadow", "cloud", "reflection", "refraction", "water", "lightshaft", "light_sha",
                "ao", "sss", "lens", "bloom", "volumetric", "quality", "resolution", "distance",
                "wave", "caustic", "normal", "smoke", "curvature", "galaxy", "star", "skybox",
                "flicker", "colored_light");
    }

    private String recommendedValue(String key, String value, PerformanceHardwareAdvisor.HardwareClass hardware, PerformanceSnapshot snapshot) {
        String lower = key.toLowerCase(Locale.ROOT);
        boolean stressed = snapshot != null && (snapshot.primaryBottleneck() == PerformanceBottleneck.SHADER_RENDER
                || snapshot.primaryBottleneck() == PerformanceBottleneck.GPU
                || snapshot.shaderPressure() > 0.45D
                || snapshot.averageFps() < 55.0D
                || snapshot.maxFrameTimeMs() > 70.0D);
        if (lower.contains("shadowdistance") || (lower.contains("shadow") && lower.contains("distance"))) {
            return formatNumber(Math.min(number(value, hardware.gpuTier() >= 3 ? 128.0D : 96.0D), stressed ? 64.0D : hardware.gpuTier() >= 3 ? 128.0D : 96.0D), value);
        }
        if (lower.contains("shadowmapresolution") || (lower.contains("shadow") && lower.contains("resolution"))) {
            return String.valueOf(Math.min((int) number(value, 2048.0D), stressed || hardware.gpuTier() <= 1 ? 1024 : 2048));
        }
        if (lower.contains("shadow_quality")) {
            return String.valueOf(Math.min((int) number(value, 2.0D), stressed ? 1 : 2));
        }
        if (lower.contains("cloud_quality") || lower.contains("light_shaft_quality") || lower.contains("ao_quality") || lower.contains("sss_quality")) {
            return String.valueOf(Math.min((int) number(value, 2.0D), stressed ? 1 : 2));
        }
        if (isHeavyBooleanShaderFeature(lower) && isBoolean(value)) {
            return stressed || hardware.gpuTier() <= 1 || hardware.integratedGpu() ? "false" : value;
        }
        if (lower.contains("cloud_amount") || lower.contains("cloud_thickness") || lower.contains("cloud_opacity") || lower.contains("star_amount") || lower.contains("galaxy_brightness")) {
            return formatNumber(Math.max(0.0D, number(value, 1.0D) * (stressed ? 0.55D : 0.75D)), value);
        }
        if (lower.contains("curvature") && isBoolean(value)) {
            return stressed ? "false" : value;
        }
        if (isInteger(value) && (lower.contains("quality") || lower.contains("clouds") || lower.contains("water"))) {
            return String.valueOf(Math.max(0, Math.min(Integer.parseInt(value), stressed ? 1 : 2)));
        }
        return value;
    }

    private boolean isHeavyBooleanShaderFeature(String lower) {
        return containsAny(lower,
                "cloud_shadow", "reflection", "refraction", "water_waves", "lens_flare",
                "projected_caustics", "lightshaft", "smokey_water", "generated_normals",
                "colored_shadows", "colored_light", "vanilla_skybox", "random_blocklight", "blocklight_flicker");
    }

    private String category(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("shadow")) return "shaderpack/shadows";
        if (lower.contains("cloud")) return "shaderpack/clouds";
        if (lower.contains("water") || lower.contains("reflection") || lower.contains("refraction")) return "shaderpack/water-reflection";
        if (lower.contains("quality") || lower.contains("resolution")) return "shaderpack/quality";
        return "shaderpack/render";
    }

    private String risk(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("shadow") || lower.contains("reflection") || lower.contains("quality")) {
            return "caution";
        }
        return "optional";
    }

    private String readableKey(String fileName, String key) {
        return fileName.replace(".txt", "") + " - " + key.replace('_', ' ');
    }

    private String description(String key, String current, String recommended) {
        return "Shaderpack option " + key + " currently " + current + "; Koil recommends " + recommended + " to reduce shader/render pressure while keeping the pack enabled.";
    }

    private double number(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String formatNumber(double value, String original) {
        if (original.contains(".")) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.valueOf((int) Math.round(value));
    }

    private boolean isBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void backup(Path file) throws java.io.IOException {
        Files.createDirectories(PerformancePaths.BACKUPS);
        Files.copy(file, PerformancePaths.BACKUPS.resolve(file.getFileName() + "-" + System.currentTimeMillis() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
    }
}
