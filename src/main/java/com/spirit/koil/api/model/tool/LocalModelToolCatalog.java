package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.automation.capability.AutomationCapabilityRegistry;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One cached model-facing catalog composed from reusable capability
 * registries. Runtime execution remains owned by each registry.
 */
public final class LocalModelToolCatalog {
    private static final Pattern NAMESPACED_ID = Pattern.compile("(?:^|\\s)[a-z0-9_.-]+:[a-z0-9_./-]+(?:$|\\s)");
    private static final List<ModelToolDefinition> AUTOMATION_MODE_TOOLS = build();
    private static final String VERSION = AutomationCapabilityRegistry.version()
            + "|" + ModelWorkspaceToolRegistry.version()
            + "|" + MinecraftKnowledgeModelToolRegistry.version()
            + "|" + AutomationPlanModelToolRegistry.version()
            + "|" + AutomationKtlSkillModelToolRegistry.version()
            + "|" + ProjectValidationModelToolRegistry.version()
            + "|internet-research-v1|intent-selector-v11";

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
            add(selected, "movement.move_to", MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID);
        }
        if (containsAny(normalized, "jump", "jumping", "hop", "hopping", "leap")) {
            add(selected, "player.jump");
        }
        if (containsAny(normalized, "right click", "left click", "press key", "tap key", "press e", "press t",
                "press w", "press a", "press s", "press d", "raw input", "keyboard input", "mouse input")) {
            add(selected, "input.tap", "input.release", "input.release_all");
        }
        if (containsAny(normalized, "mouse delta", "mouse look", "move the mouse", "move mouse",
                "raw camera", "camera delta", "turn camera by")) {
            add(selected, "input.mouse_delta", "input.release_all");
        }
        if (containsAny(normalized, "hold w", "hold a", "hold s", "hold d", "hold key", "hold input")) {
            add(selected, "input.hold", "input.release", "input.release_all");
        }
        if (containsAny(normalized, "release w", "release a", "release s", "release d", "release key", "release input")) {
            add(selected, "input.release", "input.release_all");
        }
        if (containsAny(normalized, "look at", "face the", "turn toward", "turn to face", "aim at")
                && (containsAny(normalized, "entity", "mob", "creature", "sheep", "cow", "pig", "villager", "player")
                || containsNamespacedId(normalized))) {
            add(selected, "entity.look_at", MinecraftKnowledgeModelToolRegistry.ENTITY_TOOL_ID);
        }
        boolean entityInteraction = requestsEntityInteraction(normalized);
        boolean entityMount = containsAny(normalized, "mount", "ride the", "get on", "board the");
        if (requestsBlockInteraction(normalized, entityInteraction)) {
            add(selected, "block.interact");
        }
        if (entityInteraction) {
            add(selected, entityMount ? "entity.mount" : "entity.interact");
        }
        if (containsAny(normalized, "dismount", "get off", "leave the boat")) add(selected, "player.dismount");
        if (containsAny(normalized, "place my boat", "place a boat", "deploy boat", "launch boat", "use a boat", "boat nearby")) {
            add(selected, "transport.boat_deploy", MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID,
                    MinecraftKnowledgeModelToolRegistry.TARGET_TOOL_ID, "world.inspect_surroundings");
        }
        if (containsAny(normalized, "elytra", "glide", "fly to", "elytra flight")) {
            add(selected, "transport.elytra_flight", MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID);
        }
        if (containsAny(normalized, "swim", "transport", "travel method")) {
            add(selected, MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID, "movement.move_to");
        }
        if (containsAny(normalized, "inspect surroundings", "inspect around me", "scan surroundings",
                "scan around me", "nearby terrain", "nearby hazards")) {
            add(selected, "world.inspect_surroundings");
        }
        if (requestsBlockMining(normalized)) {
            add(selected, "block.mine");
        }
        if (containsAny(normalized, "place block", "place a block", "put down a block")
                || containsAny(normalized, "place", "put down") && containsNamespacedId(normalized)) {
            add(selected, "block.place");
        }
        if (containsAny(normalized, "line of blocks", "block line", "build a line", "build a square", "square of blocks",
                "build a platform", "block platform", "build a perimeter", "bridge", "bridging")
                || containsAny(normalized, "build", "place")
                && containsAny(normalized, "square", "perimeter", "platform")) {
            add(selected, "block.build_pattern", "block.place", "movement.move_to");
        }
        if (containsAny(normalized, "container", "chest", "barrel", "shulker", "take item", "store item",
                "put item", "open inventory")) {
            add(selected, "container.open", "container.take_item", "container.store_item");
        }
        if (containsAny(normalized, "inventory", "use item", "hold item", "eat", "drink", "consume", "food")) {
            add(selected, "inventory.use_item", "inventory.eat_item");
        }
        if (containsAny(normalized, "attack", "hit", "fight", "combat", "kill", "defeat", "slay")) {
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
            add(selected, MinecraftKnowledgeModelToolRegistry.COMMAND_TOOL_ID, "minecraft.command");
        }
        if (!grantAllAdvancements && containsAny(normalized,
                "show a title", "display a title", "title on my screen", "actionbar",
                "give me", "give item", "grant item",
                "remove item", "clear item", "take item from inventory", "remove from inventory")) {
            // These player-requested Minecraft actions currently have no
            // narrower typed Koil capability. Keep them on the normal
            // current-player command path with the existing approval policy.
            add(selected, MinecraftKnowledgeModelToolRegistry.COMMAND_TOOL_ID, "minecraft.command");
        }
        if (containsAny(normalized, "recipe", "recipes", "craft", "crafting", "smelt", "smelting", "cook", "cooking"))
            add(selected, MinecraftKnowledgeModelToolRegistry.RECIPE_TOOL_ID);
        if (containsAny(normalized, "advancement", "advancements"))
            add(selected, MinecraftKnowledgeModelToolRegistry.ADVANCEMENT_TOOL_ID);
        if (containsAny(normalized, "structure", "structures"))
            add(selected, MinecraftKnowledgeModelToolRegistry.STRUCTURE_TOOL_ID);
        if (containsAny(normalized, "biome", "biomes", "registry", "registries", "modded item"))
            add(selected, MinecraftKnowledgeModelToolRegistry.REGISTRY_TOOL_ID);
        if (containsAny(normalized, "dimension", "dimensions", "nether", "overworld", "the end"))
            add(selected, MinecraftKnowledgeModelToolRegistry.DIMENSION_TOOL_ID);
        if (containsAny(normalized, "what am i looking at", "what i am looking at", "looking at", "crosshair target"))
            add(selected, MinecraftKnowledgeModelToolRegistry.TARGET_TOOL_ID);
        if (containsAny(normalized, "where am i", "player data", "player info", "player information", "what block am i on", "standing on"))
            add(selected, MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID);
        if (containsAny(normalized, "block info", "block information", "about this block", "modded block"))
            add(selected, MinecraftKnowledgeModelToolRegistry.BLOCK_TOOL_ID);
        if (containsAny(normalized, "item info", "item information", "about this item", "modded item"))
            add(selected, MinecraftKnowledgeModelToolRegistry.ITEM_TOOL_ID, MinecraftKnowledgeModelToolRegistry.REGISTRY_TOOL_ID);
        if (containsAny(normalized, "entity info", "entity information", "creature info", "about this creature", "about this entity"))
            add(selected, MinecraftKnowledgeModelToolRegistry.ENTITY_TOOL_ID);
        if (containsAny(normalized, "effect info", "effect information", "status effect", "potion effect"))
            add(selected, MinecraftKnowledgeModelToolRegistry.EFFECT_TOOL_ID, MinecraftKnowledgeModelToolRegistry.REGISTRY_TOOL_ID);
        if (containsAny(normalized, "enchantment info", "enchantment information", "about this enchantment"))
            add(selected, MinecraftKnowledgeModelToolRegistry.ENCHANTMENT_TOOL_ID, MinecraftKnowledgeModelToolRegistry.REGISTRY_TOOL_ID);
        if (containsAny(normalized, "nbt", "snbt", "item data", "item tag"))
            add(selected, MinecraftKnowledgeModelToolRegistry.NBT_TOOL_ID);
        boolean workspaceTopic = containsAny(normalized, "workspace", "file", "files", "folder", "folders", "source", "code", "coding", "java",
                "json", "json5", "yaml", "toml", "mcfunction", "mcmeta", "lang file", "properties", "config",
                "resource pack", "datapack", "data pack", "ktl", "script", "read", "search", "edit", "write",
                "create", "delete", "replace", "rename", "move", "copy", "duplicate", "directory");
        if (workspaceTopic) {
            boolean selectedWorkspaceOperation = false;
            if (containsAny(normalized, "list directory", "list folder", "list files", "show files", "what files", "browse folder")) {
                add(selected, "workspace.list");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "path type", "path exists", "file exists", "folder exists", "directory exists", "stat file", "file size")) {
                add(selected, "workspace.stat");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "search", "find in", "look for in", "grep", "exact word", "keyword")) {
                add(selected, "workspace.search");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "read", "inspect file", "view file", "show file", "file contents", "source code")) {
                add(selected, "workspace.read");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "create directory", "create a directory", "create folder", "create a folder",
                    "make directory", "make a directory", "make folder", "make a folder", "new folder")) {
                add(selected, "workspace.mkdir", "workspace.stat");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "create file", "create a file", "new file")) {
                add(selected, "workspace.create", "workspace.stat");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "edit file", "change file", "modify file", "replace", "replace in", "fix code", "update file")) {
                add(selected, "workspace.search", "workspace.read", "workspace.replace", "workspace.stat");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "write file", "rewrite file", "overwrite file")) {
                add(selected, "workspace.read", "workspace.write", "workspace.stat");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "delete file", "remove file")) {
                add(selected, "workspace.stat", "workspace.delete");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "restore file", "recover file")) {
                add(selected, "workspace.restore", "workspace.stat");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "copy file", "copy a file", "copy the file", "duplicate file", "duplicate a file")) {
                add(selected, "workspace.stat", "workspace.copy");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "move file", "move a file", "move the file", "rename file", "rename a file", "rename the file")) {
                add(selected, "workspace.stat", "workspace.move");
                selectedWorkspaceOperation = true;
            }
            if (containsAny(normalized, "ktl") && containsAny(normalized,
                    "create", "write", "edit", "change", "modify", "fix", "update", "replace")) {
                add(selected, "automation.ktl_apply");
                selectedWorkspaceOperation = true;
            }
            if (!selectedWorkspaceOperation) {
                add(selected, "workspace.roots", "workspace.list", "workspace.stat", "workspace.search", "workspace.read");
            }
        }
        if (containsAny(normalized, "compile", "compilation", "build", "gradle", "test", "tests", "proof", "proofs", "verify build")) {
            add(selected, ProjectValidationModelToolRegistry.LIST_TOOL_ID, ProjectValidationModelToolRegistry.RUN_TOOL_ID);
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
        if (containsAny(normalized, "internet", "web search", "search online", "look online", "latest",
                "documentation", "wiki", "release notes", "public source")) {
            add(selected, InternetResearchModelToolRegistry.SEARCH, InternetResearchModelToolRegistry.FETCH);
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
        if (containsAny(normalized, "right click", "left click", "press key", "tap key", "press e", "press t",
                "press w", "press a", "press s", "press d", "raw input", "keyboard input")) {
            required.add("input.tap");
        }
        if (containsAny(normalized, "hold w", "hold a", "hold s", "hold d", "hold key", "hold input")) {
            required.add("input.hold");
        }
        if (containsAny(normalized, "release w", "release a", "release s", "release d", "release key", "release input")) {
            required.add("input.release");
        }
        if (containsAny(normalized, "mouse delta", "mouse look", "move the mouse", "move mouse",
                "raw camera", "camera delta", "turn camera by")) {
            required.add("input.mouse_delta");
        }
        if (containsAny(normalized, "move to", "navigate", "travel to", "go to")) {
            required.add("movement.move_to");
        }
        if (containsAny(normalized, "place my boat", "place a boat", "deploy boat", "launch boat", "use a boat", "boat nearby")) {
            required.add("transport.boat_deploy");
        }
        if (containsAny(normalized, "elytra", "glide", "fly to", "elytra flight")) {
            required.add("transport.elytra_flight");
        }
        if (containsAny(normalized, "inspect surroundings", "inspect around me", "scan surroundings",
                "scan around me", "nearby terrain", "nearby hazards")) {
            required.add("world.inspect_surroundings");
        }
        if (grantAllAdvancements) {
            required.add("player.grant_advancements");
        } else if (containsAny(normalized,
                "show a title", "display a title", "title on my screen", "actionbar",
                "give me", "give item", "grant item",
                "remove item", "clear item", "take item from inventory", "remove from inventory")) {
            required.add("minecraft.command");
        }
        if (containsAny(normalized, "read file", "read the file", "inspect file", "reread", "re read")) {
            required.add("workspace.read");
        }
        if (containsAny(normalized, "search file", "search files", "find in file", "look for in", "grep")) {
            required.add("workspace.search");
        }
        if (containsAny(normalized, "create file", "new file")) {
            required.add("workspace.create");
        }
        if (containsAny(normalized, "edit file", "change file", "modify file", "replace", "replace in", "fix code", "update file")) {
            required.add("workspace.replace");
        }
        if (containsAny(normalized, "write file", "rewrite file", "overwrite file")) {
            required.add("workspace.write");
        }
        if (containsAny(normalized, "delete file", "remove file")) {
            required.add("workspace.delete");
        }
        if (containsAny(normalized, "restore file", "recover file")) {
            required.add("workspace.restore");
        }
        if (containsAny(normalized, "create directory", "create a directory", "create folder", "create a folder",
                "make directory", "make a directory", "make folder", "make a folder", "new folder")) {
            required.add("workspace.mkdir");
        }
        if (containsAny(normalized, "copy file", "copy a file", "copy the file", "duplicate file", "duplicate a file")) {
            required.add("workspace.copy");
        }
        if (containsAny(normalized, "move file", "move a file", "move the file", "rename file", "rename a file", "rename the file")) {
            required.add("workspace.move");
        }
        if (containsAny(normalized, "run ktl", "execute ktl", "use ktl skill", "run skill")) {
            required.add(AutomationKtlSkillModelToolRegistry.RUN_TOOL_ID);
        }
        if (containsAny(normalized, "compile", "run test", "run tests", "run proof", "run proofs", "verify build", "validate project")) {
            required.add(ProjectValidationModelToolRegistry.RUN_TOOL_ID);
        }
        if (requestsBlockMining(normalized)) {
            required.add("block.mine");
        }
        if (containsAny(normalized, "place block", "place a block", "put down a block")
                || containsAny(normalized, "place", "put down") && containsNamespacedId(normalized)) {
            required.add("block.place");
        }
        if (containsAny(normalized, "line of blocks", "block line", "build a line", "build a square", "square of blocks",
                "build a platform", "block platform", "build a perimeter", "bridge", "bridging")
                || containsAny(normalized, "build", "place")
                && containsAny(normalized, "square", "perimeter", "platform")) {
            required.add("block.build_pattern");
        }
        if (containsAny(normalized, "look at", "face the", "turn toward", "turn to face", "aim at")
                && (containsAny(normalized, "entity", "mob", "creature", "sheep", "cow", "pig", "villager", "player")
                || containsNamespacedId(normalized))) {
            required.add("entity.look_at");
        }
        if (containsAny(normalized, "attack", "hit entity", "fight")) required.add("entity.attack");
        if (containsAny(normalized, "kill entity", "kill mob")) required.add("entity.kill");
        boolean entityInteraction = requestsEntityInteraction(normalized);
        if (requestsBlockInteraction(normalized, entityInteraction)) required.add("block.interact");
        if (entityInteraction) {
            required.add(containsAny(normalized, "mount", "ride the", "get on", "board the")
                    ? "entity.mount"
                    : "entity.interact");
        }
        if (containsAny(normalized, "dismount", "get off", "leave the boat")) required.add("player.dismount");
        if (containsAny(normalized, "open container", "open chest", "open barrel")) required.add("container.open");
        return Set.copyOf(required);
    }

    public static List<ModelToolDefinition> toolsForRound(
            String prompt,
            boolean includePlanningTool,
            boolean stagedExecution,
            boolean hasObservation
    ) {
        List<ModelToolDefinition> selected = toolsForPrompt(prompt, includePlanningTool);
        if (!stagedExecution || selected.size() <= 8) {
            return selected;
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (!hasObservation) {
            add(allowed, "workspace.roots", "workspace.list", "workspace.stat", "workspace.read", "workspace.search",
                    MinecraftKnowledgeModelToolRegistry.TOOL_ID,
                    MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID,
                    MinecraftKnowledgeModelToolRegistry.TARGET_TOOL_ID,
                    AutomationKtlSkillModelToolRegistry.CATALOG_TOOL_ID,
                    ProjectValidationModelToolRegistry.LIST_TOOL_ID,
                    AutomationPlanModelToolRegistry.TOOL_ID,
                    "automation.cancel");
        } else {
            selected.stream().map(ModelToolDefinition::id).forEach(allowed::add);
        }
        List<ModelToolDefinition> staged = selected.stream().filter(tool -> allowed.contains(tool.id())).toList();
        return staged.isEmpty() ? selected.stream().limit(8).toList() : staged;
    }

    public static String version() {
        return VERSION;
    }

    private static List<ModelToolDefinition> build() {
        List<ModelToolDefinition> tools = new ArrayList<>(AutomationCapabilityRegistry.modelTools());
        tools.addAll(MinecraftKnowledgeModelToolRegistry.modelTools());
        tools.addAll(ModelWorkspaceToolRegistry.modelTools());
        tools.addAll(ProjectValidationModelToolRegistry.modelTools());
        tools.addAll(AutomationPlanModelToolRegistry.modelTools());
        tools.addAll(AutomationKtlSkillModelToolRegistry.modelTools());
        tools.addAll(InternetResearchModelToolRegistry.modelTools());
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

    private static boolean containsNamespacedId(String normalized) {
        return normalized != null && NAMESPACED_ID.matcher(normalized).find();
    }

    private static boolean requestsEntityInteraction(String normalized) {
        return containsAny(normalized, "interact with entity", "right click entity", "use entity", "mount", "trade with", "feed the")
                || containsAny(normalized, "interact", "right click", "use")
                && containsAny(normalized, "entity", "mob", "creature", "sheep", "cow", "pig", "villager", "player");
    }

    private static boolean requestsBlockInteraction(String normalized, boolean entityInteraction) {
        return containsAny(normalized, "use block", "right click block", "interact with block", "lever", "button", "door", "chest", "barrel")
                || !entityInteraction && containsAny(normalized, "interact", "right click");
    }

    private static boolean requestsBlockMining(String normalized) {
        boolean action = containsAny(normalized, "mine", "mining", "dig", "digging", "break", "breaking", "harvest");
        boolean target = containsAny(normalized,
                "block", "below me", "under me", "beneath me", "above me", "over my head", "looking at",
                "stone", "dirt", "sand", "gravel", "ore", "log", "wood", "plank", "glass", "deepslate"
        ) || containsNamespacedId(normalized);
        return action && target;
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
