package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerformanceLiveApplyService {
    private PerformanceLiveApplyService() {
    }

    public static List<PerformanceProviderApplyResult> applyAllSupported(MinecraftClient client, List<PerformanceSettingDescriptor> settings, String filter) {
        String query = filter == null ? "" : filter.toLowerCase(java.util.Locale.ROOT).trim();
        List<PerformanceProviderApplyResult> results = new ArrayList<>();
        for (PerformanceOptimizationProvider provider : PerformanceProviderRegistry.providers()) {
            if ("vanilla".equals(provider.id())) {
                continue;
            }
            for (PerformanceSettingDescriptor setting : settings) {
                if (!setting.providerId().equals(provider.id()) || !setting.liveApplySupported()) {
                    continue;
                }
                if (!query.isEmpty() && !(setting.label().toLowerCase(java.util.Locale.ROOT).contains(query) || setting.category().toLowerCase(java.util.Locale.ROOT).contains(query) || setting.providerId().toLowerCase(java.util.Locale.ROOT).contains(query))) {
                    continue;
                }
                results.add(provider.apply(client, setting));
            }
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("appliedAtMillis", System.currentTimeMillis());
        history.put("source", "provider-live-apply");
        history.put("filter", filter == null ? "" : filter);
        history.put("results", results);
        PerformanceJsonStore.append(PerformancePaths.OPTIMIZATION_HISTORY, history);
        return results;
    }

    public static List<PerformanceProviderApplyResult> applyRecommendations(MinecraftClient client, List<PerformanceSettingDescriptor> settings, List<PerformanceRecommendation> recommendations) {
        if (settings == null || recommendations == null || settings.isEmpty() || recommendations.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> safeSettingKeys = recommendations.stream()
                .filter(PerformanceRecommendation::safeAutoFix)
                .map(PerformanceRecommendation::settingKey)
                .filter(key -> key != null && !key.isBlank() && !"none".equals(key))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (safeSettingKeys.isEmpty()) {
            return List.of();
        }
        Map<String, PerformanceRecommendation> recommendationByKey = recommendations.stream()
                .filter(PerformanceRecommendation::safeAutoFix)
                .filter(recommendation -> recommendation.settingKey() != null && !recommendation.settingKey().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        recommendation -> recommendation.settingKey().toLowerCase(java.util.Locale.ROOT).replace('-', '_'),
                        recommendation -> recommendation,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new
                ));
        List<PerformanceProviderApplyResult> results = new ArrayList<>();
        for (PerformanceOptimizationProvider provider : PerformanceProviderRegistry.providers()) {
            for (PerformanceSettingDescriptor setting : settings) {
                if (!setting.providerId().equals(provider.id()) || !setting.liveApplySupported()) {
                    continue;
                }
                if (!matchesSafeSetting(setting.settingId(), safeSettingKeys)) {
                    continue;
                }
                PerformanceRecommendation recommendation = matchingRecommendation(setting.settingId(), recommendationByKey);
                PerformanceSettingDescriptor targetedSetting = recommendation == null ? setting : new PerformanceSettingDescriptor(
                        setting.providerId(),
                        setting.settingId(),
                        setting.label(),
                        setting.category(),
                        setting.currentValue(),
                        recommendation.afterValue(),
                        setting.risk(),
                        setting.liveApplySupported(),
                        setting.requiresResourceReload(),
                        setting.configPath(),
                        setting.description()
                );
                results.add(provider.apply(client, targetedSetting));
            }
        }
        for (PerformanceRecommendation recommendation : recommendations) {
            if (!recommendation.safeAutoFix() || recommendation.settingKey() == null || recommendation.settingKey().isBlank()) {
                continue;
            }
            if (results.stream().anyMatch(result -> matchesSettingKey(result.settingId(), recommendation.settingKey()))) {
                continue;
            }
            PerformanceProviderApplyResult direct = applyDirectProviderRecommendation(client, recommendation);
            if (direct != null) {
                results.add(direct);
            }
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("appliedAtMillis", System.currentTimeMillis());
        history.put("source", "benchmark-recommendation-live-apply");
        history.put("safeSettingKeys", safeSettingKeys);
        history.put("results", results);
        PerformanceJsonStore.append(PerformancePaths.OPTIMIZATION_HISTORY, history);
        return results;
    }

    public static PerformanceProviderApplyResult applyDirectProviderRecommendation(MinecraftClient client, PerformanceRecommendation recommendation) {
        if (recommendation == null || recommendation.settingKey() == null || recommendation.settingKey().isBlank()) {
            return null;
        }
        PerformanceSnapshot snapshot = PerformanceMonitor.latestSnapshot(client);
        for (PerformanceOptimizationProvider provider : PerformanceProviderRegistry.providers()) {
            if ("vanilla".equals(provider.id()) || !provider.installed() || !provider.configAvailable()) {
                continue;
            }
            for (PerformanceSettingDescriptor setting : provider.settings(client, snapshot)) {
                if (!setting.liveApplySupported() || !matchesSettingKey(setting.settingId(), recommendation.settingKey())) {
                    continue;
                }
                PerformanceSettingDescriptor targetedSetting = new PerformanceSettingDescriptor(
                        setting.providerId(),
                        setting.settingId(),
                        setting.label(),
                        setting.category(),
                        setting.currentValue(),
                        recommendation.afterValue(),
                        setting.risk(),
                        setting.liveApplySupported(),
                        setting.requiresResourceReload(),
                        setting.configPath(),
                        setting.description()
                );
                return provider.apply(client, targetedSetting);
            }
        }
        return null;
    }

    private static PerformanceRecommendation matchingRecommendation(String settingId, Map<String, PerformanceRecommendation> recommendationByKey) {
        String normalized = settingId == null ? "" : settingId.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        PerformanceRecommendation direct = recommendationByKey.get(normalized);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, PerformanceRecommendation> entry : recommendationByKey.entrySet()) {
            String key = entry.getKey();
            if (normalized.equals(key) || normalized.contains(key)) {
                return entry.getValue();
            }
            if ("shadow_distance".equals(key) && normalized.contains("shadow") && normalized.contains("distance")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean matchesSafeSetting(String settingId, java.util.Set<String> safeSettingKeys) {
        String normalized = settingId == null ? "" : settingId.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        for (String key : safeSettingKeys) {
            String wanted = key.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
            if (normalized.equals(wanted) || normalized.contains(wanted)) {
                return true;
            }
            if ("shadow_distance".equals(wanted) && normalized.contains("shadow") && normalized.contains("distance")) {
                return true;
            }
            if ("render_distance".equals(wanted) && normalized.contains("render") && normalized.contains("distance")) {
                return true;
            }
            if ("simulation_distance".equals(wanted) && normalized.contains("simulation") && normalized.contains("distance")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSettingKey(String settingId, String recommendationKey) {
        String normalized = normalize(settingId);
        String wanted = normalize(recommendationKey);
        if (normalized.isBlank() || wanted.isBlank()) {
            return false;
        }
        if (normalized.equals(wanted) || normalized.contains(wanted) || wanted.contains(normalized)) {
            return true;
        }
        if ("shadow_distance".equals(wanted) && normalized.contains("shadow") && normalized.contains("distance")) {
            return true;
        }
        return ("quality_leaves_quality".equals(wanted) && normalized.contains("leaves") && normalized.contains("quality"))
                || ("quality_weather_quality".equals(wanted) && normalized.contains("weather") && normalized.contains("quality"));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace('.', '_').replace("::", "_");
    }
}
