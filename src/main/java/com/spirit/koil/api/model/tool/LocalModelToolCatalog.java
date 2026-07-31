package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.automation.capability.AutomationCapabilityRegistry;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One cached model-facing catalog composed from reusable capability
 * registries. Runtime execution remains owned by each registry.
 */
public final class LocalModelToolCatalog {
    private static final List<ModelToolDefinition> AUTOMATION_MODE_TOOLS = build();
    private static final String VERSION = AutomationCapabilityRegistry.version()
            + "|" + ModelWorkspaceToolRegistry.version()
            + "|" + MinecraftKnowledgeModelToolRegistry.version()
            + "|" + AutomationPlanModelToolRegistry.version()
            + "|" + AutomationKtlSkillModelToolRegistry.version()
            + "|intent-selector-v5";

    private LocalModelToolCatalog() {
    }

    public static List<ModelToolDefinition> automationModeTools() {
        return AUTOMATION_MODE_TOOLS;
    }

    /**
     * Returns the smallest confidently relevant tool set for an objective.
     * Unknown objectives retain the full registry so optimization can never
     * make a supported capability undiscoverable.
     */
    public static List<ModelToolDefinition> toolsForPrompt(String prompt) {
        return toolsForPrompt(prompt, false);
    }

    public static List<ModelToolDefinition> toolsForPrompt(String prompt, boolean includePlanningTool) {
        String normalized = normalize(prompt);
        Set<String> selected = new LinkedHashSet<>();
        boolean grantAllAdvancements = requestsAllAdvancements(normalized);

        if (containsAny(normalized, "walk", "walking", "forward", "backward", "left", "right")) {
            add(selected, "movement.walk_relative");
        }
        if (containsAny(normalized, "move to", "moving to", "navigate", "navigating",
                "travel to", "traveling to", "coordinate", "coordinates", "position", "go to")) {
            add(selected, "movement.move_to");
        }
        if (containsAny(normalized, "jump", "jumping", "hop", "hopping", "leap")) {
            add(selected, "player.jump");
        }
        if (containsAny(normalized, "interact", "right click", "use block", "lever", "button", "door")) {
            add(selected, "block.interact");
        }
        if (containsAny(normalized, "mine", "mining", "dig", "digging", "break block", "harvest block")) {
            add(selected, "block.mine");
        }
        if (containsAny(normalized, "container", "chest", "barrel", "shulker", "take item", "store item",
                "put item", "open inventory")) {
            add(selected, "container.open", "container.take_item", "container.store_item");
        }
        if (containsAny(normalized, "inventory", "use item", "hold item", "eat", "drink", "consume", "food")) {
            add(selected, "inventory.use_item", "inventory.eat_item");
        }
        if (containsAny(normalized, "attack", "hit", "fight", "combat", "kill", "mob", "entity")) {
            add(selected, "entity.attack", "entity.kill");
        }
        if (containsAny(normalized, "set time", "time of day", "daytime", "nighttime", "sunrise", "sunset")
                || normalized.matches(".*\\b(day|night|noon|midnight)\\b.*")) {
            add(selected, "world.set_time");
        }
        if (grantAllAdvancements) {
            add(selected, "player.grant_advancements");
        }
        if (startsWithCommand(prompt) || containsAny(
                normalized,
                "minecraft command",
                "slash command",
                "run command",
                "what command",
                "which command",
                "command syntax"
        )) {
            add(selected, MinecraftKnowledgeModelToolRegistry.TOOL_ID, "minecraft.command");
        }
        if (!grantAllAdvancements && containsAny(normalized,
                "show a title", "display a title", "title on my screen", "actionbar",
                "give me", "give item", "grant item",
                "remove item", "clear item", "take item from inventory", "remove from inventory")) {
            // These player-requested Minecraft actions currently have no
            // narrower typed Koil capability. Keep them on the normal
            // current-player command path with the existing approval policy.
            add(selected, MinecraftKnowledgeModelToolRegistry.TOOL_ID, "minecraft.command");
        }
        if (containsAny(
                normalized,
                "recipe", "recipes", "craft", "crafting", "smelt", "smelting", "cook", "cooking",
                "advancement", "advancements", "structure", "structures", "registry", "registries",
                "biome", "what am i looking at", "what i am looking at", "looking at",
                "where am i", "player data", "player info", "player information",
                "what block am i on", "standing on", "modded item", "modded block"
        )) {
            add(selected, MinecraftKnowledgeModelToolRegistry.TOOL_ID);
        }
        if (containsAny(normalized, "workspace", "file", "files", "folder", "folders", "source", "code", "coding", "java",
                "json", "json5", "yaml", "toml", "mcfunction", "mcmeta", "lang file", "properties", "config",
                "resource pack", "datapack", "data pack", "ktl", "script", "read", "search", "edit", "write",
                "create", "delete", "replace")) {
            add(selected,
                    "workspace.roots",
                    "workspace.list",
                    "workspace.read",
                    "workspace.search",
                    "workspace.create",
                    "workspace.write",
                    "workspace.replace",
                    "workspace.delete",
                    "automation.ktl_apply");
        }
        if (containsAny(normalized,
                "ktl", "skill", "skills", "task file", "automation file",
                "parkour", "follow", "chase", "orbit", "farming", "farm",
                "crafting workflow", "progression", "ender dragon")) {
            add(selected,
                    AutomationKtlSkillModelToolRegistry.CATALOG_TOOL_ID,
                    AutomationKtlSkillModelToolRegistry.RUN_TOOL_ID);
        }
        if (containsAny(normalized, "cancel", "stop automation", "abort")) {
            add(selected, "automation.cancel");
        }
        if (includePlanningTool) {
            add(selected, AutomationPlanModelToolRegistry.TOOL_ID);
        }

        if (selected.isEmpty()) {
            if (isConversation(prompt, normalized)) {
                return List.of();
            } else {
                return AUTOMATION_MODE_TOOLS;
            }
        } else {
            add(selected, "automation.cancel");
        }
        return AUTOMATION_MODE_TOOLS.stream()
                .filter(definition -> selected.contains(definition.id()))
                .toList();
    }

    /**
     * Returns only capabilities that the objective explicitly names as
     * distinct required actions. This is intentionally narrower than tool
     * selection: it prevents a small model from silently dropping a second
     * action without turning broad topic words into mandatory execution.
     */
    public static Set<String> requiredToolIdsForPrompt(String prompt) {
        String normalized = normalize(prompt);
        LinkedHashSet<String> required = new LinkedHashSet<>();
        boolean grantAllAdvancements = requestsAllAdvancements(normalized);
        if (containsAny(normalized, "walk", "walking")) {
            required.add("movement.walk_relative");
        }
        if (containsAny(normalized, "jump", "jumping", "hop", "hopping", "leap")) {
            required.add("player.jump");
        }
        if (containsAny(normalized, "move to", "navigate", "travel to", "go to")) {
            required.add("movement.move_to");
        }
        if (grantAllAdvancements) {
            required.add("player.grant_advancements");
        } else if (containsAny(normalized,
                "show a title", "display a title", "title on my screen", "actionbar",
                "give me", "give item", "grant item",
                "remove item", "clear item", "take item from inventory", "remove from inventory")) {
            required.add("minecraft.command");
        }
        return Set.copyOf(required);
    }

    public static String version() {
        return VERSION;
    }

    private static List<ModelToolDefinition> build() {
        List<ModelToolDefinition> tools = new ArrayList<>(AutomationCapabilityRegistry.modelTools());
        tools.addAll(MinecraftKnowledgeModelToolRegistry.modelTools());
        tools.addAll(ModelWorkspaceToolRegistry.modelTools());
        tools.addAll(AutomationPlanModelToolRegistry.modelTools());
        tools.addAll(AutomationKtlSkillModelToolRegistry.modelTools());
        return List.copyOf(tools);
    }

    public static boolean requiresFreshApproval(String toolId) {
        if (toolId == null || "automation.cancel".equals(toolId)) {
            return false;
        }
        return AUTOMATION_MODE_TOOLS.stream()
                .filter(definition -> definition.id().equals(toolId))
                .findFirst()
                .map(definition -> definition.confirmationRequired() || !definition.sideEffects().isEmpty())
                .orElse(false);
    }

    private static void add(Set<String> selected, String... ids) {
        if (ids != null) {
            selected.addAll(List.of(ids));
        }
    }

    private static String normalize(String prompt) {
        return prompt == null
                ? ""
                : prompt.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_./:-]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean containsAny(String normalized, String... candidates) {
        if (normalized == null || normalized.isBlank() || candidates == null) {
            return false;
        }
        String padded = " " + keywordText(normalized) + " ";
        for (String candidate : candidates) {
            String clean = keywordText(normalize(candidate));
            if (!clean.isBlank() && padded.contains(" " + clean + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String keywordText(String value) {
        return value == null
                ? ""
                : value.replaceAll("[^a-z0-9_]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean startsWithCommand(String prompt) {
        return prompt != null && prompt.stripLeading().startsWith("/");
    }

    private static boolean requestsAllAdvancements(String normalized) {
        return containsAny(normalized, "all advancement", "all advancements", "all advancment", "all advancments")
                && containsAny(normalized, "give", "grant", "award", "unlock", "complete");
    }

    private static boolean isSimpleConversation(String normalized) {
        return normalized.matches("(hi|hello|hey|thanks|thank you|how are you|good morning|good evening)[.!? ]*");
    }

    private static boolean isConversation(String prompt, String normalized) {
        if (isSimpleConversation(normalized)) {
            return true;
        }
        String raw = prompt == null ? "" : prompt.strip();
        return raw.endsWith("?")
                || containsAny(normalized, "explain", "tell me", "what is", "what are", "who is", "why is",
                "how does", "how are", "help me understand");
    }
}
