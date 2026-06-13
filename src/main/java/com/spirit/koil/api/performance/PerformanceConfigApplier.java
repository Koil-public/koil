package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.ParticlesMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerformanceConfigApplier {
    private PerformanceConfigApplier() {
    }

    public static ApplyResult applySafe(MinecraftClient client, List<PerformanceRecommendation> recommendations) {
        if (client == null || recommendations == null || recommendations.isEmpty()) {
            return new ApplyResult(false, "No recommendations to apply.", List.of());
        }
        Path backup = backupOptions();
        List<String> applied = new ArrayList<>();
        List<PerformanceApplyEntryResult> entries = new ArrayList<>();
        for (PerformanceRecommendation recommendation : recommendations) {
            if (!recommendation.safeAutoFix()) {
                entries.add(new PerformanceApplyEntryResult(recommendation.id(), recommendation.settingKey(), "skipped", false, "Recommendation requires review."));
                continue;
            }
            if (!supportsVanillaSetting(recommendation.settingKey())) {
                entries.add(new PerformanceApplyEntryResult(recommendation.id(), recommendation.settingKey(), "provider-or-unsupported", false, "Not a vanilla GameOptions setting; provider apply may handle it."));
                continue;
            }
            if (applyOne(client, recommendation)) {
                applied.add(recommendation.id());
                entries.add(new PerformanceApplyEntryResult(recommendation.id(), recommendation.settingKey(), "applied", true, "Vanilla option updated."));
            } else {
                entries.add(new PerformanceApplyEntryResult(recommendation.id(), recommendation.settingKey(), "failed", false, "Vanilla option could not be updated."));
            }
        }
        try {
            client.options.write();
        } catch (Throwable ignored) {
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("appliedAtMillis", System.currentTimeMillis());
        history.put("backup", backup == null ? "" : backup.toString());
        history.put("appliedRecommendationIds", applied);
        history.put("entryResults", entries);
        history.put("recommendations", recommendations.stream().map(PerformanceRecommendation::toJsonMap).toList());
        PerformanceJsonStore.append(PerformancePaths.OPTIMIZATION_HISTORY, history);
        return new ApplyResult(!applied.isEmpty(), applied.isEmpty() ? "No safe vanilla recommendations were applied." : "Applied " + applied.size() + " safe vanilla recommendation(s).", applied, entries);
    }

    public static boolean revertLastBackup(MinecraftClient client) {
        try {
            Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
            if (!Files.exists(PerformancePaths.BACKUPS)) {
                return false;
            }
            Path latest = Files.list(PerformancePaths.BACKUPS)
                    .filter(path -> path.getFileName().toString().startsWith("options-"))
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
            if (latest == null) {
                return false;
            }
            Files.copy(latest, options, StandardCopyOption.REPLACE_EXISTING);
            PerformanceJsonStore.append(PerformancePaths.OPTIMIZATION_HISTORY, Map.of(
                    "revertedAtMillis", System.currentTimeMillis(),
                    "restoredBackup", latest.toString()
            ));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path backupOptions() {
        try {
            Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
            if (!Files.exists(options)) {
                return null;
            }
            Files.createDirectories(PerformancePaths.BACKUPS);
            Path backup = PerformancePaths.BACKUPS.resolve("options-" + System.currentTimeMillis() + ".txt");
            Files.copy(options, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean applyOne(MinecraftClient client, PerformanceRecommendation recommendation) {
        return switch (recommendation.settingKey()) {
            case "render_distance" -> setRenderDistance(client, parseInt(recommendation.afterValue(), 8));
            case "simulation_distance" -> setSimulationDistance(client, parseInt(recommendation.afterValue(), 6));
            case "max_fps" -> setMaxFps(client, recommendation.afterValue());
            case "clouds" -> setClouds(client, recommendation.afterValue());
            case "entity_distance" -> setEntityDistance(client, parseDouble(recommendation.afterValue(), 0.75D));
            case "mipmaps" -> setMipmaps(client, recommendation.afterValue());
            case "particles" -> setParticles(client, recommendation.afterValue());
            case "graphics_mode" -> setGraphicsMode(client, recommendation.afterValue());
            case "smooth_lighting" -> setSmoothLighting(client, Boolean.parseBoolean(recommendation.afterValue()));
            case "biome_blend" -> setBiomeBlend(client, parseInt(recommendation.afterValue(), 0));
            case "entity_shadows" -> setEntityShadows(client, Boolean.parseBoolean(recommendation.afterValue()));
            case "vsync" -> setVsync(client, false);
            default -> false;
        };
    }

    private static boolean supportsVanillaSetting(String key) {
        return switch (key) {
            case "render_distance", "simulation_distance", "max_fps", "clouds", "entity_distance", "mipmaps", "particles", "graphics_mode", "smooth_lighting", "biome_blend", "entity_shadows", "vsync" -> true;
            default -> false;
        };
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean setRenderDistance(MinecraftClient client, int value) {
        try {
            client.options.getViewDistance().setValue(Math.max(2, value));
            requestTerrainUpdate(client);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setSimulationDistance(MinecraftClient client, int value) {
        try {
            client.options.getSimulationDistance().setValue(Math.max(2, value));
            requestTerrainUpdate(client);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setMaxFps(MinecraftClient client, String value) {
        try {
            client.options.getMaxFps().setValue(resolveMaxFps(value));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int resolveMaxFps(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            if ("unlimited".equals(normalized) || "max".equals(normalized)) {
                return 260;
            }
        }
        return Math.max(10, parseInt(value, 120));
    }

    private static boolean setEntityDistance(MinecraftClient client, double value) {
        try {
            client.options.getEntityDistanceScaling().setValue(Math.max(0.5D, Math.min(1.0D, value)));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void requestTerrainUpdate(MinecraftClient client) {
        try {
            if (client.worldRenderer != null) {
                client.worldRenderer.reload();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean setClouds(MinecraftClient client, String value) {
        try {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            CloudRenderMode mode = switch (normalized) {
                case "fancy" -> CloudRenderMode.FANCY;
                case "fast" -> CloudRenderMode.FAST;
                default -> CloudRenderMode.OFF;
            };
            client.options.getCloudRenderMode().setValue(mode);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setMipmaps(MinecraftClient client, String value) {
        try {
            int current = client.options.getMipmapLevels().getValue();
            int target = value == null || value.toLowerCase(java.util.Locale.ROOT).contains("one step")
                    ? Math.max(0, current - 1)
                    : Math.max(0, Math.min(4, parseInt(value, current)));
            client.options.getMipmapLevels().setValue(target);
            client.reloadResources();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setParticles(MinecraftClient client, String value) {
        try {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            ParticlesMode mode = switch (normalized) {
                case "minimal" -> ParticlesMode.MINIMAL;
                case "all" -> ParticlesMode.ALL;
                default -> ParticlesMode.DECREASED;
            };
            client.options.getParticles().setValue(mode);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setGraphicsMode(MinecraftClient client, String value) {
        try {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            client.options.getGraphicsMode().setValue("fancy".equals(normalized) ? GraphicsMode.FANCY : GraphicsMode.FAST);
            requestTerrainUpdate(client);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setSmoothLighting(MinecraftClient client, boolean value) {
        try {
            client.options.getAo().setValue(value);
            requestTerrainUpdate(client);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setBiomeBlend(MinecraftClient client, int value) {
        try {
            client.options.getBiomeBlendRadius().setValue(Math.max(0, value));
            requestTerrainUpdate(client);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setEntityShadows(MinecraftClient client, boolean value) {
        try {
            client.options.getEntityShadows().setValue(value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setVsync(MinecraftClient client, boolean value) {
        try {
            client.options.getEnableVsync().setValue(value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public record ApplyResult(boolean changed, String message, List<String> appliedRecommendationIds, List<PerformanceApplyEntryResult> entryResults) {
        public ApplyResult(boolean changed, String message, List<String> appliedRecommendationIds) {
            this(changed, message, appliedRecommendationIds, List.of());
        }
    }
}
