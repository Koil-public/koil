package com.spirit.koil.api.automation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.ModelExperimentalFeatures;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Experimental persistent objective provider over the normal Automation agent. */
public final class AutomationCompletionModeController {
    private static final Path STATE = Path.of("koil", "sys", "model", "completion-mode-state.json");
    private static int tick;
    private static int cooldown;
    private static boolean wasDead;

    private AutomationCompletionModeController() {}

    public static void tick(MinecraftClient client) {
        if (++tick % 20 != 0 || !ModelExperimentalFeatures.snapshot().completionModeEnabled()) return;
        if (client == null || client.player == null || client.world == null || client.getNetworkHandler() == null) return;
        if (client.player.isDead() || client.player.getHealth() <= 0.0F) {
            if (!wasDead) record("death_observed", "waiting_for_respawn");
            wasDead = true;
            return;
        }
        if (cooldown > 0) { cooldown--; return; }
        if (AutomationRuntimeStatus.isTaskRunning() || LocalModelService.hasActiveWork()) return;
        if (!AutomationModeController.isAutomationMode()) AutomationModeController.setAutomationMode(true);

        boolean dragonComplete = advancementDone(client, "minecraft:end/kill_dragon");
        String recovery = wasDead
                ? "The player has respawned after a death. Re-inspect inventory, equipment, location, dimension, health, hunger, threats, and lost resources before choosing a recovery objective. "
                : "";
        wasDead = false;
        String objective = recovery + (dragonComplete
                ? "Completion Mode persistent objective: complete the remaining obtainable advancements. Inspect synchronized advancement progress and current player/world state, choose one useful unfinished advancement sub-objective, execute it through normal registered Automation tools and KTL, validate actual progress, and stop this iteration with structured evidence. Do not redo Free the End."
                : "Completion Mode persistent objective: defeat the Ender Dragon and obtain the Free the End progression state. Inspect current advancements, inventory, equipment, dimension, position, health, hunger, nearby resources and threats first; continue from current progress, choose one bounded next sub-objective, execute it through normal registered Automation tools and KTL, and validate actual progress. This is not a fresh-world script.");
        if (LocalModelService.automationPrompt(objective)) {
            cooldown = 10;
            record(dragonComplete ? "remaining_advancements" : "ender_dragon_progression", "agent_iteration_started");
        } else {
            cooldown = 5;
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean advancementDone(MinecraftClient client, String id) {
        return advancementProgressSnapshot(client).entrySet().stream()
                .anyMatch(entry -> id.equals(entry.getKey().getId().toString()) && entry.getValue().isDone());
    }

    @SuppressWarnings("unchecked")
    public static Map<Advancement, AdvancementProgress> advancementProgressSnapshot(MinecraftClient client) {
        try {
            var handler = client.getNetworkHandler().getAdvancementHandler();
            Map<Advancement, AdvancementProgress> progress = null;
            for (Field field : handler.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object candidate = field.get(handler);
                if (candidate instanceof Map<?, ?> map && (map.isEmpty()
                        || map.keySet().stream().anyMatch(Advancement.class::isInstance))) {
                    progress = (Map<Advancement, AdvancementProgress>) candidate;
                    break;
                }
            }
            return progress == null ? Map.of() : Map.copyOf(progress);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    public static void stoppedByUser() {
        if (ModelExperimentalFeatures.snapshot().completionModeEnabled()) {
            ModelExperimentalFeatures.set(ModelExperimentalFeatures.Feature.COMPLETION_MODE, false);
            record("disabled", "stopped_by_user");
        }
    }

    private static void record(String phase, String detail) {
        try {
            JsonObject root = Files.isRegularFile(STATE)
                    ? JsonParser.parseString(Files.readString(STATE, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            root.addProperty("phase", phase);
            root.addProperty("detail", detail);
            root.addProperty("updatedAtMillis", System.currentTimeMillis());
            if ("death_observed".equals(phase)) root.addProperty("deathsObserved", root.has("deathsObserved") ? root.get("deathsObserved").getAsInt() + 1 : 1);
            Path parent = STATE.toAbsolutePath().normalize().getParent(); Files.createDirectories(parent);
            Files.writeString(STATE, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
