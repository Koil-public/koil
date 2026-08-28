package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.automation.capability.AutomationCapabilityRegistry;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.spirit.koil.api.model.tool.LocalModelToolVocabulary.*;

/**
 * One cached model-facing catalog composed from reusable capability
 * registries. Runtime execution remains owned by each registry.
 *
 * <p>Intent routing uses one-word vocabulary tokens. Phrases are composed from
 * independent words only where disambiguation is required. This keeps lookup
 * bounded, reduces provider tool-schema noise, and makes shorthand/slang easy
 * to extend without adding sentence templates.</p>
 */
public final class LocalModelToolCatalog {
    private static final List<ModelToolDefinition> AUTOMATION_MODE_TOOLS = build();
    private static final String VERSION = AutomationCapabilityRegistry.version()
        + "|" + ModelWorkspaceToolRegistry.version()
        + "|" + MinecraftKnowledgeModelToolRegistry.version()
        + "|" + AutomationPlanModelToolRegistry.version()
        + "|" + AutomationKtlSkillModelToolRegistry.version()
        + "|" + ProjectValidationModelToolRegistry.version()
        + "|" + KoilDocumentationModelToolRegistry.version()
        + "|internet-research-v1|" + LocalModelToolVocabulary.VERSION
        + "|intent-selector-v13";

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
        IntentSelection intent = classify(prompt);
        LinkedHashSet<String> selected = new LinkedHashSet<>(intent.selected());

        if (includePlanningTool) {
            add(selected, AutomationPlanModelToolRegistry.TOOL_ID);
        }

        if (selected.isEmpty()) {
            if (isConversation(intent.prompt())) {
                return List.of();
            }
            return AUTOMATION_MODE_TOOLS;
        }

        add(selected, "automation.cancel");
        return AUTOMATION_MODE_TOOLS.stream()
            .filter(definition -> selected.contains(definition.id()))
            .toList();
    }

    /**
     * Returns only capabilities that the objective explicitly names as
     * distinct required actions. Read-only evidence helpers are intentionally
     * excluded so completion tracking follows user-requested effects rather
     * than every supporting observation.
     */
    public static Set<String> requiredToolIdsForPrompt(String prompt) {
        return Set.copyOf(classify(prompt).required());
    }

    /**
     * Shared read-only selector for normal model questions. It reuses the same
     * one-word automation vocabulary but strips every mutating capability.
     */
    public static List<ModelToolDefinition> informationToolsForPrompt(String prompt) {
        Set<String> selected = classify(prompt).selected();
        if (selected.isEmpty()) return List.of();
        return AUTOMATION_MODE_TOOLS.stream()
            .filter(definition -> selected.contains(definition.id()))
            .filter(definition -> !definition.confirmationRequired() && definition.sideEffects().isEmpty())
            .filter(definition -> definition.id().startsWith("minecraft.")
                || definition.id().startsWith("internet.")
                || definition.id().startsWith("koil.")
                || Set.of("workspace.roots", "workspace.list", "workspace.stat", "workspace.read", "workspace.search")
                .contains(definition.id()))
            .toList();
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
                MinecraftKnowledgeModelToolRegistry.REGISTRY_TOOL_ID,
                AutomationKtlSkillModelToolRegistry.CATALOG_TOOL_ID,
                ProjectValidationModelToolRegistry.LIST_TOOL_ID,
                AutomationPlanModelToolRegistry.TOOL_ID,
                InternetResearchModelToolRegistry.SEARCH,
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

    private static IntentSelection classify(String prompt) {
        PromptTerms terms = LocalModelToolVocabulary.parse(prompt);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        boolean namespacedId = containsNamespacedId(terms);
        boolean allAdvancements = requestsAllAdvancements(terms);

        selectMovement(terms, selected, required);
        selectRawInput(terms, selected, required);
        selectInteractionAndTransport(terms, namespacedId, selected, required);
        selectWorldActions(terms, namespacedId, allAdvancements, selected, required);
        selectInventoryAndContainers(terms, selected, required);
        selectCombat(terms, selected, required);
        selectMinecraftKnowledgeAndCommands(terms, namespacedId, allAdvancements, selected, required);
        selectWorkspace(terms, selected, required);
        selectDevelopmentAndSkills(terms, selected, required);
        selectKoilDocumentation(terms, selected);
        selectInternet(terms, selected);
        selectExplicitToolNameWords(terms, selected);

        return new IntentSelection(terms, selected, required);
    }

    private static void selectMovement(PromptTerms terms, Set<String> selected, Set<String> required) {
        boolean directionalClick = terms.words().contains("click")
            || terms.words().contains("rightclick")
            || terms.words().contains("leftclick")
            || terms.words().contains("rmb")
            || terms.words().contains("lmb");
        boolean movementVerb = terms.words().contains("walk")
            || terms.words().contains("walking")
            || terms.words().contains("walked")
            || terms.words().contains("walks")
            || terms.words().contains("step")
            || terms.words().contains("steps")
            || terms.words().contains("stepping")
            || terms.words().contains("stroll")
            || terms.words().contains("strolling")
            || terms.words().contains("pace")
            || terms.words().contains("pacing")
            || terms.words().contains("strafe")
            || terms.words().contains("strafing")
            || terms.words().contains("sidestep")
            || terms.words().contains("sidestepping");
        boolean directionOnly = any(terms, WALK) && terms.tokenCount() <= 3 && !directionalClick;
        boolean walk = movementVerb || directionOnly;

        boolean navigationVerb = terms.words().contains("move")
            || terms.words().contains("moving")
            || terms.words().contains("navigate")
            || terms.words().contains("navigating")
            || terms.words().contains("travel")
            || terms.words().contains("traveling")
            || terms.words().contains("travelling")
            || terms.words().contains("pathfind")
            || terms.words().contains("pathfinding")
            || terms.words().contains("goto")
            || terms.words().contains("head")
            || terms.words().contains("reach")
            || terms.words().contains("approach")
            || terms.words().contains("approaching");
        boolean navigation = navigationVerb
            || all(terms, "go", "to")
            || all(terms, "move", "to")
            || all(terms, "travel", "to");

        if (walk) {
            action(selected, required, "movement.walk_relative");
        }
        if (navigation && !isMouseMovement(terms)) {
            action(selected, required, "movement.move_to");
            add(selected, MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID);
        }
        if (any(terms, JUMP)) {
            action(selected, required, "player.jump");
        }
    }

    private static void selectRawInput(PromptTerms terms, Set<String> selected, Set<String> required) {
        boolean explicitKey = any(terms, INPUT_KEYS) || hasLiteralKeyboardKey(terms);
        boolean mouseButton = any(terms, MOUSE) && any(terms, TAP);
        boolean rightClick = all(terms, "right", "click") || terms.words().contains("rightclick") || terms.words().contains("rmb");
        boolean leftClick = all(terms, "left", "click") || terms.words().contains("leftclick") || terms.words().contains("lmb");
        boolean middleClick = all(terms, "middle", "click") || terms.words().contains("middleclick") || terms.words().contains("mmb");
        boolean semanticTarget = any(terms, ENTITY) || any(terms, BLOCK) || any(terms, BLOCK_INTERACTIVE);
        boolean explicitRaw = terms.words().contains("raw")
            || terms.words().contains("input")
            || terms.words().contains("key")
            || terms.words().contains("keys")
            || terms.words().contains("keyboard")
            || terms.words().contains("mouse")
            || terms.words().contains("hotkey")
            || terms.words().contains("hotkeys")
            || terms.words().contains("keystroke")
            || terms.words().contains("keystrokes");
        boolean physicalClick = rightClick || leftClick || middleClick;
        boolean rawInput = any(terms, TAP)
            && (explicitKey || mouseButton || physicalClick)
            && (!semanticTarget || explicitRaw)
            || terms.words().contains("keystroke")
            || terms.words().contains("hotkey");

        if (rawInput) {
            action(selected, required, "input.tap");
            add(selected, "input.release", "input.release_all");
        }
        if (any(terms, HOLD) && explicitKey) {
            action(selected, required, "input.hold");
            add(selected, "input.release", "input.release_all");
        }
        if ((terms.words().contains("sprint")
            || terms.words().contains("sneak")
            || terms.words().contains("crouch"))
            && terms.tokenCount() <= 3
            && !any(terms, RELEASE)) {
            action(selected, required, "input.hold");
            add(selected, "input.release", "input.release_all");
        }
        if (any(terms, RELEASE) && explicitKey) {
            action(selected, required, "input.release");
            add(selected, "input.release_all");
        } else if (exactSingleWord(terms, RELEASE) || any(terms, RELEASE) && any(terms, ALL)) {
            action(selected, required, "input.release_all");
        }
        if (isMouseMovement(terms)) {
            action(selected, required, "input.mouse_delta");
            add(selected, "input.release_all");
        }
    }

    private static void selectInteractionAndTransport(
        PromptTerms terms,
        boolean namespacedId,
        Set<String> selected,
        Set<String> required
    ) {
        boolean entityTarget = any(terms, ENTITY);
        boolean blockTarget = any(terms, BLOCK) || any(terms, BLOCK_INTERACTIVE);
        boolean containerTarget = any(terms, CONTAINER);
        boolean interaction = any(terms, INTERACT);
        boolean rightClick = all(terms, "right", "click") || terms.words().contains("rightclick") || terms.words().contains("rmb");
        boolean entityInteraction = interaction && entityTarget
            || rightClick && entityTarget;
        boolean mount = any(terms, MOUNT);

        if (any(terms, LOOK) && (entityTarget || namespacedId)) {
            action(selected, required, "entity.look_at");
            add(selected, MinecraftKnowledgeModelToolRegistry.ENTITY_TOOL_ID);
        }

        if (entityInteraction) {
            action(selected, required, mount ? "entity.mount" : "entity.interact");
        } else if (interaction && blockTarget && !containerTarget) {
            action(selected, required, "block.interact");
        }

        if (any(terms, DISMOUNT)) {
            action(selected, required, "player.dismount");
        }

        boolean boat = any(terms, BOAT);
        if (boat && (any(terms, BOAT_DEPLOY) || terms.tokenCount() <= 3)) {
            action(selected, required, "transport.boat_deploy");
            add(selected,
                MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID,
                MinecraftKnowledgeModelToolRegistry.TARGET_TOOL_ID,
                "world.inspect_surroundings");
        }
        if (boat && mount) {
            action(selected, required, "entity.mount");
        }

        if (any(terms, ELYTRA)) {
            action(selected, required, "transport.elytra_flight");
            add(selected, MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID);
        }
        if (any(terms, SWIM)) {
            add(selected, MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID, "movement.move_to");
        }
        if (any(terms, INSPECT) && any(terms, SURROUNDINGS)) {
            action(selected, required, "world.inspect_surroundings");
        }
    }

    private static void selectWorldActions(
        PromptTerms terms,
        boolean namespacedId,
        boolean allAdvancements,
        Set<String> selected,
        Set<String> required
    ) {
        boolean mineTarget = any(terms, BLOCK) || any(terms, RELATIVE_BLOCK) || namespacedId;
        if (any(terms, MINE) && mineTarget) {
            action(selected, required, "block.mine");
        }

        boolean boat = any(terms, BOAT);
        boolean placeTarget = any(terms, BLOCK) || namespacedId;
        if (!boat && any(terms, PLACE) && placeTarget) {
            action(selected, required, "block.place");
        }

        if ((any(terms, BUILD) || any(terms, PLACE)) && any(terms, PATTERN)) {
            action(selected, required, "block.build_pattern");
            add(selected, "block.place", "movement.move_to");
        }

        if (isTimeChange(terms)) {
            action(selected, required, "world.set_time");
        }

        if (allAdvancements) {
            action(selected, required, "player.grant_advancements");
        }
    }

    private static void selectInventoryAndContainers(PromptTerms terms, Set<String> selected, Set<String> required) {
        boolean container = any(terms, CONTAINER);
        boolean take = any(terms, TAKE);
        boolean store = any(terms, STORE);
        boolean open = any(terms, OPEN) || any(terms, INTERACT);

        if (container) {
            boolean selectedOperation = false;
            if (open) {
                action(selected, required, "container.open");
                selectedOperation = true;
            }
            if (take) {
                action(selected, required, "container.take_item");
                selectedOperation = true;
            }
            if (store) {
                action(selected, required, "container.store_item");
                selectedOperation = true;
            }
            if (!selectedOperation) {
                add(selected, "container.open", "container.take_item", "container.store_item");
            }
        }

        boolean inventory = any(terms, INVENTORY);
        if (terms.words().contains("inventory") && any(terms, OPEN)) {
            action(selected, required, "input.tap");
            add(selected, "input.release", "input.release_all");
        }
        if (any(terms, EAT)) {
            action(selected, required, "inventory.eat_item");
        } else if (any(terms, USE_ITEM) && (inventory
            || terms.words().contains("drink")
            || terms.words().contains("drinking")
            || terms.words().contains("equip")
            || terms.words().contains("equipping")
            || terms.words().contains("wield")
            || terms.words().contains("wielding")
            || terms.words().contains("consume")
            || terms.words().contains("consuming"))) {
            action(selected, required, "inventory.use_item");
        }
    }

    private static void selectCombat(PromptTerms terms, Set<String> selected, Set<String> required) {
        if (any(terms, KILL)) {
            action(selected, required, "entity.kill");
        } else if (any(terms, ATTACK)) {
            action(selected, required, "entity.attack");
        }
    }

    private static void selectMinecraftKnowledgeAndCommands(
        PromptTerms terms,
        boolean namespacedId,
        boolean allAdvancements,
        Set<String> selected,
        Set<String> required
    ) {
        boolean commandKnowledge = containsExplicitCommand(terms.raw())
            || terms.words().contains("command")
            || terms.words().contains("commands")
            || terms.words().contains("cmd")
            || terms.words().contains("slash")
            || terms.words().contains("syntax")
            || terms.words().contains("brigadier")
            || (terms.words().contains("run") || terms.words().contains("execute"))
            && (terms.words().contains("command") || terms.words().contains("cmd"));
        if (commandKnowledge) {
            add(selected, MinecraftKnowledgeModelToolRegistry.COMMAND_TOOL_ID);
        }

        boolean commandAction = !allAdvancements && (
            containsExplicitCommand(terms.raw())
                || terms.words().contains("give")
                || terms.words().contains("clear")
                || terms.words().contains("title")
                || terms.words().contains("actionbar")
                || terms.words().contains("summon")
                || terms.words().contains("teleport")
                || terms.words().contains("tp")
                || terms.words().contains("gamemode")
                || terms.words().contains("gamerule")
                || terms.words().contains("difficulty")
                || terms.words().contains("weather")
                || terms.words().contains("locate")
                || any(terms, REMOVE_ITEM) && any(terms, INVENTORY)
        );
        if (commandAction && !isContainerOnlyTransfer(terms)) {
            action(selected, required, "minecraft.command");
            add(selected, MinecraftKnowledgeModelToolRegistry.COMMAND_TOOL_ID);
        }

        if (any(terms, RECIPE)) add(selected, MinecraftKnowledgeModelToolRegistry.RECIPE_TOOL_ID);
        if (any(terms, ADVANCEMENT) && !allAdvancements) add(selected, MinecraftKnowledgeModelToolRegistry.ADVANCEMENT_TOOL_ID);
        if (any(terms, STRUCTURE)) add(selected, MinecraftKnowledgeModelToolRegistry.STRUCTURE_TOOL_ID);
        if (any(terms, DIMENSION)) add(selected, MinecraftKnowledgeModelToolRegistry.DIMENSION_TOOL_ID);
        if (any(terms, TARGET)) add(selected, MinecraftKnowledgeModelToolRegistry.TARGET_TOOL_ID);

        boolean playerState = any(terms, PLAYER)
            && (any(terms, INFO)
            || terms.words().contains("where")
            || terms.words().contains("my")
            || terms.words().contains("current")
            || terms.tokenCount() == 1);
        if (playerState) add(selected, MinecraftKnowledgeModelToolRegistry.PLAYER_TOOL_ID);

        boolean info = any(terms, INFO) || isConversation(terms) || terms.tokenCount() == 1;
        boolean koilSelfKnowledge = any(terms, SELF_REFERENCE)
            && (any(terms, SELF_DOCUMENTATION) || any(terms, KOIL_SELF));
        if (info && any(terms, BLOCK)) add(selected, MinecraftKnowledgeModelToolRegistry.BLOCK_TOOL_ID);
        if (!koilSelfKnowledge && info && any(terms, ITEM)) {
            add(selected, MinecraftKnowledgeModelToolRegistry.ITEM_TOOL_ID);
        }
        if (info && any(terms, ENTITY)) add(selected, MinecraftKnowledgeModelToolRegistry.ENTITY_TOOL_ID);
        if (info && any(terms, EFFECT)) add(selected, MinecraftKnowledgeModelToolRegistry.EFFECT_TOOL_ID);
        if (info && any(terms, ENCHANTMENT)) add(selected, MinecraftKnowledgeModelToolRegistry.ENCHANTMENT_TOOL_ID);
        if (any(terms, NBT)) add(selected, MinecraftKnowledgeModelToolRegistry.NBT_TOOL_ID);

        if (any(terms, REGISTRY)
            || namespacedId && (info || terms.words().contains("exists"))
            || terms.words().contains("modded")
            || terms.words().contains("datapack")) {
            add(selected, MinecraftKnowledgeModelToolRegistry.REGISTRY_TOOL_ID);
        }
        if (terms.words().contains("tag") || terms.words().contains("tags")) {
            add(selected, MinecraftKnowledgeModelToolRegistry.TAG_TOOL_ID);
        }
        if (terms.words().contains("json")
                || terms.words().contains("resource")
                || terms.words().contains("resources")
                || terms.words().contains("resourcepack")) {
            add(selected, MinecraftKnowledgeModelToolRegistry.RESOURCE_TOOL_ID);
        }
        if (terms.words().contains("mod") || terms.words().contains("mods")) {
            add(selected, MinecraftKnowledgeModelToolRegistry.MOD_TOOL_ID);
        }

        if (selected.isEmpty() && (terms.words().contains("minecraft") || terms.words().contains("vanilla"))) {
            add(selected, MinecraftKnowledgeModelToolRegistry.TOOL_ID);
        }
    }

    private static void selectWorkspace(PromptTerms terms, Set<String> selected, Set<String> required) {
        boolean workspaceTopic = any(terms, WORKSPACE) || any(terms, FILE_FORMAT)
            || terms.words().contains("ls") || terms.words().contains("grep") || terms.words().contains("rg")
            || terms.words().contains("cat") || terms.words().contains("mkdir") || terms.words().contains("cp")
            || terms.words().contains("mv") || terms.words().contains("rm");
        if (!workspaceTopic) return;

        boolean selectedOperation = false;
        if (terms.words().contains("root") || terms.words().contains("roots")) {
            add(selected, "workspace.roots");
            selectedOperation = true;
        }
        if (any(terms, LIST_FILES)) {
            add(selected, "workspace.list");
            required.add("workspace.list");
            selectedOperation = true;
        }
        if (any(terms, STAT)) {
            add(selected, "workspace.stat");
            selectedOperation = true;
        }
        if (any(terms, SEARCH_FILES)) {
            add(selected, "workspace.search");
            required.add("workspace.search");
            selectedOperation = true;
        }
        if (any(terms, READ_FILES)) {
            add(selected, "workspace.read");
            required.add("workspace.read");
            selectedOperation = true;
        }

        boolean directory = any(terms, DIRECTORY);
        boolean create = any(terms, CREATE_FILES);
        if ((terms.words().contains("mkdir") || create && directory)) {
            action(selected, required, "workspace.mkdir");
            add(selected, "workspace.stat");
            selectedOperation = true;
        }
        if (create && !directory && (terms.words().contains("file") || any(terms, FILE_FORMAT))) {
            action(selected, required, "workspace.create");
            add(selected, "workspace.stat");
            selectedOperation = true;
        }

        if (any(terms, EDIT_FILES) && !terms.words().contains("ktl")) {
            add(selected, "workspace.search", "workspace.read", "workspace.stat");
            action(selected, required, "workspace.replace");
            selectedOperation = true;
        }
        if (any(terms, WRITE_FILES)) {
            add(selected, "workspace.read", "workspace.stat");
            action(selected, required, "workspace.write");
            selectedOperation = true;
        }
        if (any(terms, APPEND_FILES) && !directory) {
            add(selected, "workspace.read", "workspace.stat");
            action(selected, required, "workspace.append");
            selectedOperation = true;
        }
        if (any(terms, DELETE_FILES) && !containerTransferContext(terms)) {
            add(selected, "workspace.stat");
            action(selected, required, "workspace.delete");
            selectedOperation = true;
        }
        if (any(terms, RESTORE_FILES)) {
            action(selected, required, "workspace.restore");
            add(selected, "workspace.stat");
            selectedOperation = true;
        }
        if (any(terms, COPY_FILES)) {
            add(selected, "workspace.stat");
            action(selected, required, "workspace.copy");
            selectedOperation = true;
        }
        if (any(terms, MOVE_FILES)) {
            add(selected, "workspace.stat");
            action(selected, required, "workspace.move");
            selectedOperation = true;
        }

        if (terms.words().contains("ktl") && (create || any(terms, EDIT_FILES) || any(terms, WRITE_FILES))) {
            action(selected, required, "automation.ktl_apply");
            selectedOperation = true;
        }

        if (!selectedOperation) {
            boolean validationOnly = any(terms, VALIDATE);
            boolean ktlOnly = terms.words().contains("ktl")
                || terms.words().contains("skill")
                || terms.words().contains("skills");
            if (!validationOnly && !ktlOnly) {
                add(selected, "workspace.roots", "workspace.list", "workspace.stat", "workspace.search", "workspace.read");
            }
        }
    }

    private static void selectDevelopmentAndSkills(PromptTerms terms, Set<String> selected, Set<String> required) {
        boolean strongValidation = terms.words().contains("compile")
            || terms.words().contains("compilation")
            || terms.words().contains("test")
            || terms.words().contains("tests")
            || terms.words().contains("testing")
            || terms.words().contains("proof")
            || terms.words().contains("proofs")
            || terms.words().contains("gradle")
            || terms.words().contains("gradlew")
            || terms.words().contains("javac")
            || terms.words().contains("junit")
            || terms.words().contains("lint");
        if (strongValidation || any(terms, VALIDATE) && any(terms, PROJECT)) {
            add(selected, ProjectValidationModelToolRegistry.LIST_TOOL_ID);
            action(selected, required, ProjectValidationModelToolRegistry.RUN_TOOL_ID);
        }

        boolean ktlSpecific = terms.words().contains("ktl")
            || terms.words().contains("skill")
            || terms.words().contains("skills")
            || terms.words().contains("parkour")
            || terms.words().contains("follow")
            || terms.words().contains("chase")
            || terms.words().contains("orbit")
            || terms.words().contains("farming")
            || terms.words().contains("enderdragon")
            || terms.words().contains("workflow") && terms.words().contains("automation");
        if (ktlSpecific || any(terms, KTL) && terms.words().contains("automation")) {
            add(selected, AutomationKtlSkillModelToolRegistry.CATALOG_TOOL_ID,
                AutomationKtlSkillModelToolRegistry.RUN_TOOL_ID);
            if (any(terms, RUN_SKILL)) {
                required.add(AutomationKtlSkillModelToolRegistry.RUN_TOOL_ID);
            }
        }

        if (any(terms, PLAN)) {
            add(selected, AutomationPlanModelToolRegistry.TOOL_ID);
        }
        if (any(terms, CANCEL)) {
            action(selected, required, "automation.cancel");
        }
    }

    private static void selectInternet(PromptTerms terms, Set<String> selected) {
        boolean online = any(terms, INTERNET)
            || containsUrl(terms)
            || terms.words().contains("current")
            && (terms.words().contains("version") || terms.words().contains("release") || terms.words().contains("documentation") || terms.words().contains("docs"));
        if (!online) return;
        if (containsUrl(terms)) {
            add(selected, InternetResearchModelToolRegistry.FETCH);
        } else {
            add(selected, InternetResearchModelToolRegistry.SEARCH, InternetResearchModelToolRegistry.FETCH);
        }
    }

    private static void selectKoilDocumentation(PromptTerms terms, Set<String> selected) {
        if (terms == null) return;
        boolean explicitDocsPath = terms.raw().toLowerCase(java.util.Locale.ROOT).contains("/docs/");
        boolean selfQuestion = any(terms, KOIL_SELF) && any(terms, SELF_DOCUMENTATION);
        boolean capabilityQuestion = any(terms, SELF_REFERENCE)
            && (any(terms, QUESTION) && any(terms, SELF_DOCUMENTATION)
            || terms.words().contains("what")
            && terms.words().contains("can")
            && terms.words().contains("do"));
        if (explicitDocsPath || selfQuestion || capabilityQuestion) {
            add(selected, KoilDocumentationModelToolRegistry.TOOL_ID);
        }
    }

    /**
     * Future-facing exact-name fallback. If a prompt independently contains
     * every lexical segment of a registered tool id, expose that tool without
     * needing a new phrase rule. Example: workspace + read maps to
     * workspace.read; container + take + item maps to container.take_item.
     */
    private static void selectExplicitToolNameWords(PromptTerms terms, Set<String> selected) {
        for (ModelToolDefinition definition : AUTOMATION_MODE_TOOLS) {
            String id = definition.id();
            if (id == null || id.isBlank()) continue;
            if (terms.normalized().contains(id)) {
                add(selected, id);
                continue;
            }
            String[] segments = id.toLowerCase(java.util.Locale.ROOT).split("[._-]+");
            boolean matched = segments.length > 1;
            for (String segment : segments) {
                if (segment.isBlank() || !terms.words().contains(segment)) {
                    matched = false;
                    break;
                }
            }
            if (matched) add(selected, id);
        }
    }

    private static List<ModelToolDefinition> build() {
        List<ModelToolDefinition> tools = new ArrayList<>(AutomationCapabilityRegistry.modelTools());
        tools.addAll(MinecraftKnowledgeModelToolRegistry.modelTools());
        tools.addAll(ModelWorkspaceToolRegistry.modelTools());
        tools.addAll(ProjectValidationModelToolRegistry.modelTools());
        tools.addAll(AutomationPlanModelToolRegistry.modelTools());
        tools.addAll(AutomationKtlSkillModelToolRegistry.modelTools());
        tools.addAll(InternetResearchModelToolRegistry.modelTools());
        tools.addAll(KoilDocumentationModelToolRegistry.modelTools());
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

    private static void action(Set<String> selected, Set<String> required, String id) {
        add(selected, id);
        required.add(id);
    }

    private static void add(Set<String> selected, String... ids) {
        if (selected == null || ids == null) return;
        for (String id : ids) {
            if (id != null && !id.isBlank()) selected.add(id);
        }
    }

    private static boolean hasLiteralKeyboardKey(PromptTerms terms) {
        if (terms == null) return false;
        for (String word : terms.words()) {
            if (word.length() == 1 && Character.isLetterOrDigit(word.charAt(0))) return true;
            if (word.matches("f(?:[1-9]|1[0-9]|2[0-5])")) return true;
        }
        return false;
    }

    private static boolean isMouseMovement(PromptTerms terms) {
        return any(terms, MOUSE) && any(terms, CAMERA)
            || terms.words().contains("yaw")
            || terms.words().contains("pitch")
            || terms.words().contains("delta") && any(terms, CAMERA);
    }

    private static boolean containerTransferContext(PromptTerms terms) {
        return any(terms, CONTAINER) && (any(terms, TAKE) || any(terms, STORE));
    }

    private static boolean isContainerOnlyTransfer(PromptTerms terms) {
        return containerTransferContext(terms)
            && !terms.words().contains("command")
            && !containsExplicitCommand(terms.raw());
    }

    private static boolean isTimeChange(PromptTerms terms) {
        if (!any(terms, TIME)) return false;
        return any(terms, TIME_ACTION)
            || exactSingleWord(terms, TIME)
            || terms.tokenCount() <= 2 && !isConversation(terms);
    }

    private static boolean requestsAllAdvancements(PromptTerms terms) {
        return any(terms, ADVANCEMENT) && any(terms, ALL) && any(terms, GRANT);
    }

    private static boolean containsExplicitCommand(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String normalized = prompt.replace('\n', ' ').replace('\r', ' ');
        if (normalized.stripLeading().startsWith("/")) return true;

        for (int index = 0; index < normalized.length(); index++) {
            if (normalized.charAt(index) != '/') continue;
            if (index > 0 && !Character.isWhitespace(normalized.charAt(index - 1))
                && "(`'[\"".indexOf(normalized.charAt(index - 1)) < 0) {
                continue;
            }
            int rootStart = index + 1;
            if (rootStart >= normalized.length() || !isCommandRootCharacter(normalized.charAt(rootStart))) continue;
            int cursor = rootStart + 1;
            while (cursor < normalized.length() && isCommandRootCharacter(normalized.charAt(cursor))) cursor++;
            if (cursor > rootStart) return true;
        }
        return false;
    }

    private static boolean isCommandRootCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == ':';
    }

    private static boolean isConversation(PromptTerms terms) {
        if (terms == null || terms.tokenCount() == 0) return true;
        if (terms.tokenCount() <= 3 && any(terms, GREETING)) return true;
        if (terms.raw().strip().endsWith("?")) return true;
        return any(terms, QUESTION);
    }

    private record IntentSelection(
        PromptTerms prompt,
        LinkedHashSet<String> selected,
        LinkedHashSet<String> required
    ) {
    }
}
