package com.spirit.koil.api.performance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerformanceLearningService {
    private PerformanceLearningService() {
    }

    public static void recordBenchmark(PerformanceBenchmarkResult result) {
        if (result == null) {
            return;
        }
        Map<String, Object> event = baseEvent("benchmark", result.snapshot());
        event.put("durationMillis", result.durationMillis());
        event.put("requestedMode", result.requestedMode().name());
        event.put("worldUiHidden", result.worldUiHidden());
        event.put("automationProbeUsed", result.automationProbeUsed());
        event.put("testNotes", result.testNotes());
        event.put("phaseResults", result.phaseResults().stream().map(PerformanceBenchmarkPhaseResult::toJsonMap).toList());
        event.put("recommendationIds", result.recommendations().stream().map(PerformanceRecommendation::id).toList());
        PerformanceJsonStore.append(PerformancePaths.LEARNING_OBSERVATIONS, event);
    }

    public static void recordApply(MinecraftClient client, PerformanceSnapshot before, List<PerformanceRecommendation> recommendations, PerformanceConfigApplier.ApplyResult vanillaResult, List<PerformanceProviderApplyResult> providerResults) {
        PerformanceSnapshot after = PerformanceMonitor.latestSnapshot(client);
        Map<String, Object> event = baseEvent("apply", before == null ? after : before);
        event.put("after", snapshotFeatures(after));
        event.put("vanillaChanged", vanillaResult != null && vanillaResult.changed());
        event.put("vanillaAppliedRecommendationIds", vanillaResult == null ? List.of() : vanillaResult.appliedRecommendationIds());
        event.put("providerResults", providerResults == null ? List.of() : providerResults);
        event.put("recommendations", recommendations == null ? List.of() : recommendations.stream().map(PerformanceRecommendation::toJsonMap).toList());
        PerformanceJsonStore.append(PerformancePaths.LEARNING_OBSERVATIONS, event);
    }

    public static List<PerformanceRecommendation> adaptRecommendations(List<PerformanceRecommendation> recommendations, PerformanceSnapshot snapshot) {
        if (recommendations == null || recommendations.isEmpty()) {
            return recommendations == null ? List.of() : recommendations;
        }
        Map<String, LearningSignal> signals = loadSignals(snapshot);
        if (signals.isEmpty()) {
            return recommendations;
        }
        List<PerformanceRecommendation> adapted = new ArrayList<>();
        for (PerformanceRecommendation recommendation : recommendations) {
            LearningSignal signal = signals.get(recommendation.settingKey());
            if (signal == null || signal.total() < 2 || "none".equals(recommendation.settingKey())) {
                adapted.add(recommendation);
                continue;
            }
            String reason = recommendation.reason() + " Koil learning: " + signal.summary() + ".";
            PerformanceRecommendation.Severity severity = recommendation.severity();
            boolean safeAutoFix = recommendation.safeAutoFix();
            if (signal.score() <= -2) {
                severity = moreCareful(severity);
                safeAutoFix = false;
                reason += " This setting has not helped this machine enough yet, so Koil will ask before applying it.";
            } else if (signal.score() >= 3) {
                reason += " Similar previous applies improved this profile, so Koil will prioritize it.";
            }
            adapted.add(new PerformanceRecommendation(
                    recommendation.id(),
                    recommendation.title(),
                    reason,
                    recommendation.bottleneck(),
                    severity,
                    safeAutoFix,
                    recommendation.settingKey(),
                    recommendation.beforeValue(),
                    recommendation.afterValue()
            ));
        }
        return adapted;
    }

    public static Map<String, Object> snapshotFeatures(PerformanceSnapshot snapshot) {
        Map<String, Object> features = new LinkedHashMap<>();
        if (snapshot == null) {
            return features;
        }
        features.put("fps", snapshot.fps());
        features.put("averageFps", snapshot.averageFps());
        features.put("onePercentLowFps", snapshot.onePercentLowFps());
        features.put("frameTimeMs", snapshot.frameTimeMs());
        features.put("maxFrameTimeMs", snapshot.maxFrameTimeMs());
        features.put("memoryPressure", snapshot.memoryPressure());
        features.put("gcPressure", snapshot.gcPressure());
        features.put("chunkStress", snapshot.chunkStress());
        features.put("shaderPressure", snapshot.shaderPressure());
        features.put("uiFramePressure", snapshot.uiFramePressure());
        features.put("modLoadPressure", snapshot.modLoadPressure());
        features.put("resourcePackPressure", snapshot.resourcePackPressure());
        features.put("entityCount", snapshot.entityCount());
        features.put("renderDistance", snapshot.renderDistance());
        features.put("simulationDistance", snapshot.simulationDistance());
        features.put("maxFps", snapshot.maxFps());
        features.put("entityDistanceScale", snapshot.entityDistanceScale());
        features.put("mipmapLevels", snapshot.mipmapLevels());
        features.put("particlesMode", snapshot.particlesMode());
        features.put("graphicsMode", snapshot.graphicsMode());
        features.put("cloudsMode", snapshot.cloudsMode());
        features.put("smoothLighting", snapshot.smoothLighting());
        features.put("biomeBlend", snapshot.biomeBlend());
        features.put("entityShadows", snapshot.entityShadows());
        features.put("vsync", snapshot.vsync());
        features.put("worldType", snapshot.worldType());
        features.put("primaryBottleneck", snapshot.primaryBottleneck().name());
        return features;
    }

    private static Map<String, Object> baseEvent(String type, PerformanceSnapshot snapshot) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("capturedAtMillis", System.currentTimeMillis());
        event.put("type", type);
        event.put("features", snapshotFeatures(snapshot));
        return event;
    }

    private static Map<String, LearningSignal> loadSignals(PerformanceSnapshot current) {
        Map<String, LearningSignal> signals = new HashMap<>();
        JsonArray observations = PerformanceJsonStore.readArray(PerformancePaths.LEARNING_OBSERVATIONS);
        for (JsonElement element : observations) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject event = element.getAsJsonObject();
            if (!"apply".equals(text(event, "type"))) {
                continue;
            }
            JsonObject before = object(event, "features");
            JsonObject after = object(event, "after");
            if (before == null || after == null || !similarContext(current, before)) {
                continue;
            }
            int outcome = outcomeScore(before, after);
            if (outcome == 0) {
                continue;
            }
            for (String settingKey : appliedSettingKeys(event)) {
                signals.computeIfAbsent(settingKey, ignored -> new LearningSignal()).add(outcome);
            }
        }
        return signals;
    }

    private static boolean similarContext(PerformanceSnapshot current, JsonObject before) {
        if (current == null) {
            return true;
        }
        String bottleneck = text(before, "primaryBottleneck");
        String worldType = text(before, "worldType");
        return current.primaryBottleneck().name().equals(bottleneck) || current.worldType().equals(worldType);
    }

    private static List<String> appliedSettingKeys(JsonObject event) {
        List<String> appliedIds = new ArrayList<>();
        JsonArray vanilla = array(event, "vanillaAppliedRecommendationIds");
        if (vanilla != null) {
            for (JsonElement id : vanilla) {
                appliedIds.add(id.getAsString());
            }
        }
        List<String> settingKeys = new ArrayList<>();
        JsonArray recommendations = array(event, "recommendations");
        if (recommendations != null) {
            for (JsonElement element : recommendations) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject recommendation = element.getAsJsonObject();
                String id = text(recommendation, "id");
                String settingKey = text(recommendation, "settingKey");
                if (!settingKey.isEmpty() && appliedIds.contains(id)) {
                    settingKeys.add(settingKey);
                }
            }
        }
        JsonArray providerResults = array(event, "providerResults");
        if (providerResults != null) {
            for (JsonElement element : providerResults) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject result = element.getAsJsonObject();
                if (bool(result, "changed")) {
                    String settingId = normalizeSettingKey(text(result, "settingId"));
                    if (!settingId.isEmpty()) {
                        settingKeys.add(settingId);
                    }
                }
            }
        }
        return settingKeys.stream().distinct().toList();
    }

    private static int outcomeScore(JsonObject before, JsonObject after) {
        double fpsDelta = number(after, "averageFps") - number(before, "averageFps");
        double lowDelta = number(after, "onePercentLowFps") - number(before, "onePercentLowFps");
        double spikeDelta = number(before, "maxFrameTimeMs") - number(after, "maxFrameTimeMs");
        double memoryDelta = number(before, "memoryPressure") - number(after, "memoryPressure");
        int score = 0;
        if (fpsDelta > 3.0D) score++;
        if (lowDelta > 3.0D) score++;
        if (spikeDelta > 8.0D) score++;
        if (memoryDelta > 0.03D) score++;
        if (fpsDelta < -4.0D) score--;
        if (lowDelta < -4.0D) score--;
        if (spikeDelta < -10.0D) score--;
        if (memoryDelta < -0.04D) score--;
        return Integer.compare(score, 0);
    }

    private static PerformanceRecommendation.Severity moreCareful(PerformanceRecommendation.Severity severity) {
        return switch (severity) {
            case SAFE -> PerformanceRecommendation.Severity.CAUTION;
            case OPTIONAL -> PerformanceRecommendation.Severity.CAUTION;
            case CAUTION -> PerformanceRecommendation.Severity.RISKY;
            default -> severity;
        };
    }

    private static String normalizeSettingKey(String settingId) {
        String normalized = settingId == null ? "" : settingId.toLowerCase().replace("-", "_").replace(".", "_");
        if (normalized.contains("render") && normalized.contains("distance")) return "render_distance";
        if (normalized.contains("simulation") && normalized.contains("distance")) return "simulation_distance";
        if (normalized.contains("shadow") && normalized.contains("distance")) return "shadow_distance";
        if (normalized.contains("entity") && normalized.contains("distance")) return "entity_distance";
        return normalized;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    private static double number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0.0D : element.getAsDouble();
    }

    private static final class LearningSignal {
        private int positive;
        private int negative;

        private void add(int outcome) {
            if (outcome > 0) {
                positive++;
            } else if (outcome < 0) {
                negative++;
            }
        }

        private int score() {
            return positive - negative;
        }

        private int total() {
            return positive + negative;
        }

        private String summary() {
            return positive + " helpful / " + negative + " harmful similar result(s)";
        }
    }
}
