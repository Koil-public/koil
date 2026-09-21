package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.automation.capability.AutomationCapabilityRegistry;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.context.ContextIntelligenceService;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.model.retrieval.SemanticToolCapabilityRetriever;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private static final List<ModelToolDefinition> READ_ONLY_INFORMATION_TOOLS = buildReadOnlyInformationTools();
    private static final String VERSION = AutomationCapabilityRegistry.version()
        + "|" + ModelWorkspaceToolRegistry.version()
        + "|" + MinecraftKnowledgeModelToolRegistry.version()
        + "|" + MinecraftCommandModelToolRegistry.version()
        + "|" + AutomationPlanModelToolRegistry.version()
        + "|" + AutomationGoalModelToolRegistry.version()
        + "|" + AutomationKtlSkillModelToolRegistry.version()
        + "|" + ProjectValidationModelToolRegistry.version()
        + "|" + KoilDocumentationModelToolRegistry.version()
        + "|" + CodeIntelligenceModelToolRegistry.version()
        + "|" + AgentSkillModelToolRegistry.version()
        + "|" + ToolDiscoveryModelToolRegistry.version()
        + "|internet-research-v3:" + InternetResearchModelToolRegistry.modelTools().size()
        + "|dataset-intelligence-v1:" + DatasetIntelligenceModelToolRegistry.modelTools().size()
        + "|browser-intelligence-v1:" + BrowserIntelligenceModelToolRegistry.modelTools().size()
        + "|content-intelligence-v1:" + ContentIntelligenceModelToolRegistry.modelTools().size()
        + "|mcp-catalogue-v2:" + McpCatalogueModelToolRegistry.modelTools().size()
        + "|" + AutomationTimerModelToolRegistry.version()
        + "|" + WorkspaceExecutionModelToolRegistry.version()
        + "|" + WorkspaceProcessModelToolRegistry.version()
        + "|" + WorkspacePackageModelToolRegistry.version()
        + "|" + WorkspaceDatabaseModelToolRegistry.version()
        + "|" + WorkspaceGitArchiveModelToolRegistry.version()
        + "|" + SystemNetworkModelToolRegistry.version()
        + "|" + DataContextIndexModelToolRegistry.version()
        + "|" + BackgroundAutomationModelToolRegistry.version()
        + "|context-intelligence-v1|" + LocalModelToolVocabulary.VERSION
        + "|intent-selector-v15|command-routing-v2|execution-intelligence-policy-v2|ask-information-v4";

    private LocalModelToolCatalog() {
    }

    public static List<ModelToolDefinition> automationModeTools() {
        return merge(AUTOMATION_MODE_TOOLS, DynamicMcpToolRegistry.modelTools());
    }

    /** Every bounded information capability permitted in conversational /ask. */
    public static List<ModelToolDefinition> readOnlyInformationTools() {
        return READ_ONLY_INFORMATION_TOOLS;
    }

    /** Complete deduplicated registry view for knowledge indexing; selection remains unchanged. */
    public static List<ModelToolDefinition> allRegisteredTools() {
        java.util.Map<String, ModelToolDefinition> byId = new java.util.TreeMap<>();
        for (ModelToolDefinition tool : AUTOMATION_MODE_TOOLS) byId.putIfAbsent(tool.id(), tool);
        for (ModelToolDefinition tool : READ_ONLY_INFORMATION_TOOLS) byId.putIfAbsent(tool.id(), tool);
        for (ModelToolDefinition tool : DynamicMcpToolRegistry.modelTools()) byId.putIfAbsent(tool.id(), tool);
        return List.copyOf(byId.values());
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
            // Unknown task intent receives the full executable catalog as before.
            // Discovery remains useful on later staged rounds and for very large dynamic registries.
            return AUTOMATION_MODE_TOOLS;
        }

        // Keep a tiny control plane visible without preloading every schema. These
        // capabilities search the live registries and can expand later rounds.
        add(selected, ToolDiscoveryModelToolRegistry.SEARCH, ToolDiscoveryModelToolRegistry.INSPECT,
                AgentSkillModelToolRegistry.SEARCH, AgentSkillModelToolRegistry.INSPECT);
        add(selected, "automation.cancel");
        return automationModeTools().stream()
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
     * Selects only the read-only evidence tools that are materially relevant
     * before inference. Stable learned-knowledge questions stay tool-free;
     * explicit fresh/external lookups receive a narrow evidence path.
     */
    public static List<ModelToolDefinition> informationToolsForPrompt(String prompt) {
        IntentSelection intent = classify(prompt);
        if (!isInformationRequest(intent)) return List.of();

        // Tool routing happens before inference. Do not make a model prefill the
        // entire read-only registry just because the user asked an ordinary fact.
        // Exact lexical/domain matches keep their narrow tools. Stable knowledge
        // questions remain tool-free, while explicitly fresh/external lookups get
        // a tiny evidence fallback that can be refined semantically off-thread.
        List<ModelToolDefinition> exact = READ_ONLY_INFORMATION_TOOLS.stream()
                .filter(tool -> intent.selected().contains(tool.id()))
                .toList();
        if (!exact.isEmpty()) return exact;
        if (!requiresExternalEvidence(intent.prompt())) return List.of();
        return fallbackInformationTools();
    }

    /**
     * Off-client-thread refinement for an external/fresh information request.
     * Exact routing remains first and semantic routing is bounded by the caller;
     * failure falls back to one safe search capability instead of the full catalog.
     */
    public static CompletableFuture<List<ModelToolDefinition>> resolveInformationToolsForPrompt(String prompt, String requestId) {
        IntentSelection intent = classify(prompt);
        if (!isInformationRequest(intent)) return CompletableFuture.completedFuture(List.of());
        List<ModelToolDefinition> exact = READ_ONLY_INFORMATION_TOOLS.stream()
                .filter(tool -> intent.selected().contains(tool.id()))
                .toList();
        if (!exact.isEmpty()) return CompletableFuture.completedFuture(exact);

        // Learned knowledge is the fastest and most accurate path for stable,
        // self-contained facts. Only explicit evidence/freshness intent enters
        // semantic tool retrieval. This prevents tiny models from receiving
        // dozens of irrelevant schemas for prompts such as "capital of France".
        if (!requiresExternalEvidence(intent.prompt())) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<ModelToolDefinition> fallback = fallbackInformationTools();
        return KoilKnowledgeRuntime.shared()
                .map(engine -> SemanticToolCapabilityRetriever.retrieve(engine, intent.prompt().raw(), READ_ONLY_INFORMATION_TOOLS, requestId)
                        .thenApply(selected -> selected.isEmpty() ? fallback : selected))
                .orElseGet(() -> CompletableFuture.completedFuture(fallback));
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
                WorkspaceExecutionModelToolRegistry.EXEC_TOOL_ID,
                WorkspaceExecutionModelToolRegistry.BUILD_TOOL_ID,
                WorkspaceExecutionModelToolRegistry.ENV_TOOL_ID,
                AutomationTimerModelToolRegistry.START_TOOL_ID,
                AutomationTimerModelToolRegistry.STATUS_TOOL_ID,
                AutomationPlanModelToolRegistry.TOOL_ID,
                InternetResearchModelToolRegistry.SEARCH,
                "code.architecture", "code.symbols", "code.search", "code.semantic_search", "code.trace", "code.snippet",
                "automation.cancel");
        } else {
            selected.stream().map(ModelToolDefinition::id).forEach(allowed::add);
        }

        List<ModelToolDefinition> staged = selected.stream().filter(tool -> allowed.contains(tool.id())).toList();
        return staged.isEmpty() ? selected.stream().limit(8).toList() : staged;
    }

    public static String version() {
        String dynamic = DynamicMcpToolRegistry.modelTools().stream()
                .map(tool -> tool.id() + '\u0000' + tool.description() + '\u0000' + tool.inputSchema())
                .collect(java.util.stream.Collectors.joining("\u0001"));
        return VERSION + "|dynamic=" + Integer.toUnsignedString(dynamic.hashCode(), 16);
    }

    /** Returns the authoritative model-facing definition for one registered tool. */
    public static java.util.Optional<ModelToolDefinition> definition(String toolId) {
        if (toolId == null || toolId.isBlank()) return java.util.Optional.empty();
        return automationModeTools().stream().filter(definition -> definition.id().equals(toolId)).findFirst();
    }

    /**
     * Authoritative speculation boundary. Registries opt in explicitly through
     * {@link com.spirit.koil.api.model.ToolExecutionPolicy}; naming conventions
     * never grant speculative execution rights.
     */
    public static boolean speculativeReadAllowed(String toolId) {
        return definition(toolId)
                .filter(definition -> definition.sideEffects().isEmpty())
                .filter(definition -> !definition.confirmationRequired())
                .map(definition -> definition.executionPolicy().allowsSpeculativeRead())
                .orElse(false);
    }

    /** Side-effect-free preparation is an explicit capability policy, not a
     * synonym for reversibility. */
    public static boolean preparationAllowed(String toolId) {
        return definition(toolId)
                .map(definition -> definition.executionPolicy().allowsPreparation())
                .orElse(false);
    }

    private static IntentSelection classify(String prompt) {
        PromptTerms terms = LocalModelToolVocabulary.parse(prompt);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        boolean namespacedId = containsNamespacedId(terms);
        boolean allAdvancements = requestsAllAdvancements(terms);

        if (namespacedId && any(terms, TAKE)) {
            action(selected, required, AutomationGoalModelToolRegistry.TOOL_ID);
        }

        selectMovement(terms, selected, required);
        selectRawInput(terms, selected, required);
        selectInteractionAndTransport(terms, namespacedId, selected, required);
        selectWorldActions(terms, namespacedId, allAdvancements, selected, required);
        selectInventoryAndContainers(terms, selected, required);
        selectCombat(terms, selected, required);
        selectMinecraftKnowledgeAndCommands(terms, namespacedId, allAdvancements, selected, required);
        selectCodeIntelligence(terms, selected);
        selectWorkspace(terms, selected, required);
        selectTimers(terms, selected, required);
        selectAdvancedTools(terms, selected, required);
        selectDevelopmentAndSkills(terms, selected, required);
        selectKoilDocumentation(terms, selected);
        selectInternet(terms, selected);
        selectAdvancedInformationTools(terms, selected, required);
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
        String worldRaw = terms.raw().toLowerCase(java.util.Locale.ROOT);
        boolean mineIsPurposeClause = worldRaw.matches(".*\\b(?:give|get|grab|find|choose|select|equip|craft|make)\\b.*\\bto\\s+(?:mine|break|dig|harvest)\\b.*")
                && !worldRaw.matches(".*\\b(?:then|and then|after that|next|finally)\\b.*\\b(?:mine|break|dig|harvest)\\b.*");
        if (any(terms, MINE) && mineTarget && !mineIsPurposeClause) {
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
            // entity.kill means embodied combat against world entities. A request
            // to kill the current player is command-native (/kill @s), not a
            // target-hunting automation. Keep those capability meanings distinct
            // so a small model is not asked to force self-state through combat.
            if (!isSelfTargetedCommandIntent(terms)) {
                action(selected, required, "entity.kill");
            }
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
            add(selected,
                    MinecraftCommandModelToolRegistry.HELP_TOOL_ID,
                    MinecraftCommandModelToolRegistry.INSPECT_TOOL_ID);
        }

        String raw = terms.raw().toLowerCase(java.util.Locale.ROOT);
        boolean gameModeAction = terms.words().contains("gamemode")
                || all(terms, "game", "mode")
                || terms.words().contains("creative") && (terms.words().contains("mode")
                    || raw.contains("make me creative") || raw.contains("put me in creative") || raw.contains("set me to creative"))
                || terms.words().contains("spectator") && (terms.words().contains("mode")
                    || raw.contains("make me spectator") || raw.contains("put me in spectator") || raw.contains("set me to spectator"))
                || terms.words().contains("adventure") && (terms.words().contains("mode")
                    || raw.contains("make me adventure") || raw.contains("put me in adventure") || raw.contains("set me to adventure"))
                || terms.words().contains("survival") && (terms.words().contains("mode")
                    || raw.contains("make me survival") || raw.contains("put me in survival") || raw.contains("set me to survival"));
        boolean selfTargetedCommand = isSelfTargetedCommandIntent(terms);
        boolean explicitCommandExecution = !isConversation(terms)
                && (raw.matches("^\\s*(?:run|execute|use|send)\\b.*")
                    && (terms.words().contains("command") || terms.words().contains("cmd")));
        boolean commandAction = !allAdvancements && (
            containsExplicitCommand(terms.raw())
                || explicitCommandExecution
                || selfTargetedCommand
                || terms.words().contains("give")
                || terms.words().contains("clear")
                || terms.words().contains("title")
                || terms.words().contains("actionbar")
                || terms.words().contains("summon")
                || terms.words().contains("teleport")
                || terms.words().contains("tp")
                || gameModeAction
                || terms.words().contains("gamerule")
                || terms.words().contains("difficulty")
                || terms.words().contains("weather")
                || terms.words().contains("locate")
                || any(terms, REMOVE_ITEM) && any(terms, INVENTORY)
        );
        if (commandAction && !isContainerOnlyTransfer(terms)) {
            action(selected, required, "minecraft.command");
            // Natural-language give requests often contain a display name, a
            // misspelling, or a functional description instead of an exact id.
            // Keep the semantic resolver available so the model can discover
            // the canonical item/enchantment facts before issuing the command.
            if (!namespacedId && (terms.words().contains("give") || terms.words().contains("grant")
                    || terms.words().contains("award"))) {
                add(selected, MinecraftKnowledgeModelToolRegistry.TOOL_ID);
            }
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
            || terms.words().contains("mv") || terms.words().contains("rm")
            || terms.words().contains("execute") || terms.words().contains("script") || terms.words().contains("python")
            || terms.words().contains("node") || terms.words().contains("shell") || terms.words().contains("venv")
            || terms.words().contains("virtualenv") || terms.words().contains("gradle") || terms.words().contains("gradlew")
            || terms.words().contains("maven") || terms.words().contains("mvn") || terms.words().contains("npm")
            || terms.words().contains("cargo");
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

        boolean executeProcess = terms.words().contains("execute") || terms.words().contains("run")
                || terms.words().contains("script") || terms.words().contains("python")
                || terms.words().contains("node") || terms.words().contains("shell");
        boolean buildProcess = terms.words().contains("build") || terms.words().contains("compile")
                || terms.words().contains("gradle") || terms.words().contains("gradlew")
                || terms.words().contains("maven") || terms.words().contains("mvn")
                || terms.words().contains("npm") || terms.words().contains("cargo");
        boolean environment = terms.words().contains("venv") || terms.words().contains("virtualenv")
                || (terms.words().contains("virtual") && terms.words().contains("environment"))
                || (terms.words().contains("python") && terms.words().contains("environment"));
        if (environment) {
            action(selected, required, WorkspaceExecutionModelToolRegistry.ENV_TOOL_ID);
            add(selected, WorkspaceExecutionModelToolRegistry.EXEC_TOOL_ID);
            selectedOperation = true;
        }
        if (buildProcess) {
            action(selected, required, WorkspaceExecutionModelToolRegistry.BUILD_TOOL_ID);
            add(selected, WorkspaceExecutionModelToolRegistry.EXEC_TOOL_ID);
            selectedOperation = true;
        } else if (executeProcess) {
            action(selected, required, WorkspaceExecutionModelToolRegistry.EXEC_TOOL_ID);
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

    private static void selectCodeIntelligence(PromptTerms terms, Set<String> selected) {
        if (!any(terms, CODE_INTELLIGENCE) && !terms.words().contains("code")) return;
        if (terms.words().contains("architecture") || terms.words().contains("structure") || terms.words().contains("cluster") || terms.words().contains("clusters") || terms.words().contains("hotspot") || terms.words().contains("hotspots")) add(selected, "code.architecture");
        if (terms.words().contains("symbol") || terms.words().contains("symbols") || terms.words().contains("implementation") || terms.words().contains("implementations") || terms.words().contains("type") || terms.words().contains("types") || terms.words().contains("class") || terms.words().contains("classes") || terms.words().contains("method") || terms.words().contains("methods") || terms.words().contains("function") || terms.words().contains("functions")) add(selected, "code.symbols", "code.snippet");
        if (terms.words().contains("semantic")) add(selected, "code.semantic_search");
        if (terms.words().contains("caller") || terms.words().contains("callers") || terms.words().contains("callee") || terms.words().contains("callees") || terms.words().contains("trace") || terms.words().contains("reference") || terms.words().contains("references") || terms.words().contains("dependency") || terms.words().contains("dependencies") || terms.words().contains("flow")) add(selected, "code.trace");
        if (terms.words().contains("impact") || terms.words().contains("blast")) add(selected, "code.impact");
        if (terms.words().contains("change") || terms.words().contains("changes") || terms.words().contains("changed")) add(selected, "code.changes");
        if (terms.words().contains("schema")) add(selected, "code.schema");
        if (terms.words().contains("graph") && terms.words().contains("query")) add(selected, "code.query");
        if (terms.words().contains("snippet")) add(selected, "code.snippet");
        if (terms.words().contains("search") || terms.words().contains("find")) add(selected, "code.search");
    }


    private static void selectAdvancedTools(PromptTerms terms, Set<String> selected, Set<String> required) {
        if (terms == null) return;
        Set<String> w = terms.words();
        String raw = terms.raw() == null ? "" : terms.raw().toLowerCase(java.util.Locale.ROOT);
        if (w.contains("process") || w.contains("pid") || raw.contains("background process") || raw.contains("dev server")) {
            add(selected, WorkspaceProcessModelToolRegistry.LIST, WorkspaceProcessModelToolRegistry.STATUS, WorkspaceProcessModelToolRegistry.OUTPUT);
            if (w.contains("stop") || w.contains("kill") || w.contains("terminate")) action(selected, required, WorkspaceProcessModelToolRegistry.STOP);
        }
        if (w.contains("dependency") || w.contains("dependencies") || w.contains("package") || w.contains("packages") || w.contains("pip") || w.contains("cargo") || w.contains("npm")) {
            add(selected, WorkspacePackageModelToolRegistry.INSPECT);
            if (w.contains("install") || w.contains("add")) action(selected, required, WorkspacePackageModelToolRegistry.INSTALL);
        }
        if (w.contains("database") || w.contains("sqlite") || w.contains("jdbc") || w.contains("sql")) {
            add(selected, WorkspaceDatabaseModelToolRegistry.INSPECT, WorkspaceDatabaseModelToolRegistry.QUERY, WorkspaceDatabaseModelToolRegistry.EXPLAIN);
            if (w.contains("insert") || w.contains("update") || w.contains("delete") || w.contains("alter") || w.contains("create")) action(selected, required, WorkspaceDatabaseModelToolRegistry.EXECUTE);
        }
        if (w.contains("git") || w.contains("commit") || w.contains("branch") || w.contains("diff") || w.contains("rebase") || w.contains("merge")) {
            add(selected, WorkspaceGitArchiveModelToolRegistry.GIT_STATUS, WorkspaceGitArchiveModelToolRegistry.GIT_DIFF, WorkspaceGitArchiveModelToolRegistry.GIT_LOG, WorkspaceGitArchiveModelToolRegistry.GIT_BRANCHES, WorkspaceGitArchiveModelToolRegistry.GIT_INSPECT);
            if (w.contains("stage") || raw.contains("git add")) action(selected, required, WorkspaceGitArchiveModelToolRegistry.GIT_STAGE);
            if (w.contains("commit") && (w.contains("make") || w.contains("create") || w.contains("do"))) action(selected, required, WorkspaceGitArchiveModelToolRegistry.GIT_COMMIT);
        }
        if (w.contains("archive") || w.contains("zip") || w.contains("tar") || w.contains("extract") || w.contains("unzip")) {
            add(selected, WorkspaceGitArchiveModelToolRegistry.ARC_INSPECT, WorkspaceGitArchiveModelToolRegistry.ARC_COMPARE);
            if (w.contains("extract") || w.contains("unzip")) action(selected, required, WorkspaceGitArchiveModelToolRegistry.ARC_EXTRACT);
            else if (w.contains("patch")) action(selected, required, WorkspaceGitArchiveModelToolRegistry.ARC_PATCH);
            else if (w.contains("create") || w.contains("compress") || w.contains("zip")) action(selected, required, WorkspaceGitArchiveModelToolRegistry.ARC_CREATE);
        }
        if (w.contains("dns")) add(selected, SystemNetworkModelToolRegistry.DNS);
        if (w.contains("network") || w.contains("tcp") || w.contains("port") || w.contains("reachable") || w.contains("latency") || w.contains("ping")) add(selected, SystemNetworkModelToolRegistry.TCP, SystemNetworkModelToolRegistry.LATENCY);
        if ((w.contains("http") || w.contains("url") || w.contains("endpoint")) && (w.contains("diagnostic") || w.contains("status") || w.contains("check"))) add(selected, SystemNetworkModelToolRegistry.HTTP);
        if (w.contains("cpu") || w.contains("ram") || w.contains("memory") || w.contains("gpu") || w.contains("storage") || raw.contains("system resources")) add(selected, SystemNetworkModelToolRegistry.RESOURCES);
        if (w.contains("json") || w.contains("csv") || w.contains("xml") || w.contains("yaml") || w.contains("toml") || raw.contains("structured data")) add(selected, DataContextIndexModelToolRegistry.DATA_INSPECT, DataContextIndexModelToolRegistry.DATA_QUERY);
        if (w.contains("convert") && (w.contains("json") || w.contains("csv") || w.contains("xml") || w.contains("yaml") || w.contains("toml"))) action(selected, required, DataContextIndexModelToolRegistry.DATA_CONVERT);
        if (w.contains("compress") && (w.contains("context") || w.contains("log") || w.contains("output"))) add(selected, DataContextIndexModelToolRegistry.CONTEXT_COMPRESS);
        if (w.contains("index") || raw.contains("local search") || raw.contains("workspace search")) add(selected, DataContextIndexModelToolRegistry.INDEX_BUILD, DataContextIndexModelToolRegistry.INDEX_QUERY, DataContextIndexModelToolRegistry.INDEX_STATUS);
        if (w.contains("schedule") || w.contains("scheduler") || w.contains("interval") || w.contains("recurring") || raw.contains("every ")) {
            add(selected, BackgroundAutomationModelToolRegistry.WORKFLOW_DEFINE, BackgroundAutomationModelToolRegistry.WORKFLOW_LIST, BackgroundAutomationModelToolRegistry.SCHEDULE_STATUS, BackgroundAutomationModelToolRegistry.SCHEDULE_LIST);
            action(selected, required, BackgroundAutomationModelToolRegistry.SCHEDULE_CREATE);
        }
        if (w.contains("workflow") || raw.contains("background automation") || w.contains("compose") || w.contains("composition")) {
            add(selected, BackgroundAutomationModelToolRegistry.WORKFLOW_DEFINE, BackgroundAutomationModelToolRegistry.WORKFLOW_LIST, BackgroundAutomationModelToolRegistry.SCHEDULE_STATUS, BackgroundAutomationModelToolRegistry.SCHEDULE_LIST);
            if (w.contains("run") || w.contains("execute")) action(selected, required, BackgroundAutomationModelToolRegistry.WORKFLOW_RUN);
        }
    }

    private static void selectTimers(PromptTerms terms, Set<String> selected, Set<String> required) {
        if (terms == null) return;
        String raw = terms.raw() == null ? "" : terms.raw().toLowerCase(java.util.Locale.ROOT);
        boolean timerWord = terms.words().contains("timer") || terms.words().contains("timers")
                || terms.words().contains("countdown") || terms.words().contains("deadline");
        boolean durationPhrase = raw.matches("(?s).*\\b\\d+(?:\\.\\d+)?\\s*(?:ms|milliseconds?|seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b.*");
        boolean remaining = raw.contains("time left") || raw.contains("remaining time") || raw.contains("how long left")
                || raw.contains("how much longer") || terms.words().contains("remaining");
        boolean cancel = terms.words().contains("cancel") || terms.words().contains("stop") || terms.words().contains("clear");

        if (timerWord || remaining) {
            if (remaining || terms.words().contains("status") || terms.words().contains("check")) {
                add(selected, AutomationTimerModelToolRegistry.STATUS_TOOL_ID, AutomationTimerModelToolRegistry.LIST_TOOL_ID);
            } else if (cancel) {
                action(selected, required, AutomationTimerModelToolRegistry.CANCEL_TOOL_ID);
            } else {
                action(selected, required, AutomationTimerModelToolRegistry.START_TOOL_ID);
                add(selected, AutomationTimerModelToolRegistry.STATUS_TOOL_ID);
            }
            return;
        }

        // A duration attached to an ongoing objective is a temporal constraint,
        // not an instruction to block the provider thread. Offer the timer as a
        // supporting capability while leaving the embodied action route intact.
        if (durationPhrase && (terms.words().contains("keep") || terms.words().contains("survive")
                || terms.words().contains("alive") || terms.words().contains("until")
                || terms.words().contains("for"))) {
            add(selected, AutomationTimerModelToolRegistry.START_TOOL_ID, AutomationTimerModelToolRegistry.STATUS_TOOL_ID);
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

        boolean agentSkillSpecific = terms.words().contains("skill") || terms.words().contains("skills");
        if (agentSkillSpecific) {
            add(selected, AgentSkillModelToolRegistry.SEARCH, AgentSkillModelToolRegistry.INSPECT,
                    AgentSkillModelToolRegistry.READ_RESOURCE);
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
        if (any(terms, INTERNET_CRAWL)) {
            add(selected, InternetResearchModelToolRegistry.CRAWL, InternetResearchModelToolRegistry.FETCH);
        } else if (any(terms, INTERNET_SCRAPE)) {
            add(selected, InternetResearchModelToolRegistry.SCRAPE, InternetResearchModelToolRegistry.FETCH);
        } else if (containsUrl(terms)) {
            add(selected, InternetResearchModelToolRegistry.FETCH);
        } else {
            add(selected, InternetResearchModelToolRegistry.SEARCH, InternetResearchModelToolRegistry.FETCH);
        }
    }

    private static void selectAdvancedInformationTools(
            PromptTerms terms, Set<String> selected, Set<String> required
    ) {
        if (terms == null) return;
        Set<String> words = terms.words();

        boolean dataset = words.contains("dataset") || words.contains("datasets")
                || words.contains("huggingface") || words.contains("hugging") && words.contains("face");
        if (dataset) {
            if (words.contains("stats") || words.contains("statistics") || words.contains("distribution"))
                add(selected, DatasetIntelligenceModelToolRegistry.STATS);
            else if (words.contains("row") || words.contains("rows") || words.contains("sample") || words.contains("samples"))
                add(selected, DatasetIntelligenceModelToolRegistry.ROWS);
            else if (words.contains("filter")) add(selected, DatasetIntelligenceModelToolRegistry.FILTER);
            else if (words.contains("query") || words.contains("sql")) add(selected, DatasetIntelligenceModelToolRegistry.QUERY);
            else if (words.contains("inspect") || words.contains("schema") || words.contains("metadata"))
                add(selected, DatasetIntelligenceModelToolRegistry.INSPECT);
            else if (words.contains("research") || words.contains("analyze") || words.contains("analyse"))
                add(selected, DatasetIntelligenceModelToolRegistry.RESEARCH);
            else add(selected, DatasetIntelligenceModelToolRegistry.SEARCH, DatasetIntelligenceModelToolRegistry.INSPECT);
            if (words.contains("acquire") || words.contains("load") || words.contains("open"))
                add(selected, DatasetIntelligenceModelToolRegistry.ACQUIRE);
            if (words.contains("release") || words.contains("close"))
                add(selected, DatasetIntelligenceModelToolRegistry.RELEASE);
        }

        boolean content = words.contains("content") || words.contains("markdown")
                || words.contains("document") || words.contains("documents")
                || words.contains("pdf") || words.contains("docx") || words.contains("pptx");
        if (content && (words.contains("extract") || words.contains("normalize") || words.contains("section")
                || words.contains("sections") || words.contains("inspect") || words.contains("search"))) {
            if (words.contains("normalize")) add(selected, ContentIntelligenceModelToolRegistry.NORMALIZE);
            if (words.contains("extract")) add(selected, ContentIntelligenceModelToolRegistry.EXTRACT);
            if (words.contains("section") || words.contains("sections")) add(selected, ContentIntelligenceModelToolRegistry.SECTIONS);
            if (words.contains("search") || words.contains("find")) add(selected, ContentIntelligenceModelToolRegistry.SEARCH);
            if (words.contains("inspect")) add(selected, ContentIntelligenceModelToolRegistry.INSPECT);
            if (words.contains("release")) add(selected, ContentIntelligenceModelToolRegistry.RELEASE);
        }

        boolean browser = words.contains("browser") || words.contains("webpage") || words.contains("page")
                && (words.contains("navigate") || words.contains("click") || words.contains("screenshot")
                || words.contains("tab") || words.contains("tabs"));
        if (browser) {
            if (words.contains("health")) add(selected, BrowserIntelligenceModelToolRegistry.HEALTH);
            if (words.contains("navigate") || words.contains("open") || containsUrl(terms)) add(selected, BrowserIntelligenceModelToolRegistry.NAVIGATE);
            if (words.contains("snapshot") || words.contains("dom")) add(selected, BrowserIntelligenceModelToolRegistry.SNAPSHOT);
            if (words.contains("capture")) add(selected, BrowserIntelligenceModelToolRegistry.CAPTURE);
            if (words.contains("text") || words.contains("read")) add(selected, BrowserIntelligenceModelToolRegistry.TEXT);
            if (words.contains("find") || words.contains("search")) add(selected, BrowserIntelligenceModelToolRegistry.FIND);
            if (words.contains("wait")) add(selected, BrowserIntelligenceModelToolRegistry.WAIT);
            if (words.contains("screenshot")) add(selected, BrowserIntelligenceModelToolRegistry.SCREENSHOT);
            if (words.contains("pdf")) add(selected, BrowserIntelligenceModelToolRegistry.PDF);
            if (words.contains("audit")) add(selected, BrowserIntelligenceModelToolRegistry.AUDIT);
            if (words.contains("compare")) add(selected, BrowserIntelligenceModelToolRegistry.COMPARE);
            if (words.contains("click") || words.contains("type") || words.contains("interact") || words.contains("submit"))
                action(selected, required, BrowserIntelligenceModelToolRegistry.INTERACT);
            if (words.contains("tab") || words.contains("tabs")) action(selected, required, BrowserIntelligenceModelToolRegistry.TABS);
            if (words.contains("network")) action(selected, required, BrowserIntelligenceModelToolRegistry.NETWORK);
            if (words.contains("dialog")) action(selected, required, BrowserIntelligenceModelToolRegistry.DIALOG);
            if (words.contains("cookie") || words.contains("cookies")) action(selected, required, BrowserIntelligenceModelToolRegistry.COOKIES);
            if (selected.stream().noneMatch(BrowserIntelligenceModelToolRegistry::supports))
                add(selected, BrowserIntelligenceModelToolRegistry.SNAPSHOT, BrowserIntelligenceModelToolRegistry.TEXT);
        }

        boolean mcp = words.contains("mcp") && (words.contains("catalog") || words.contains("catalogue")
                || words.contains("server") || words.contains("servers") || words.contains("capability") || words.contains("capabilities"));
        if (mcp) add(selected, McpCatalogueModelToolRegistry.TOOL_ID);
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
        for (ModelToolDefinition definition : automationModeTools()) {
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

    private static boolean isSelfTargetedCommandIntent(PromptTerms terms) {
        if (terms == null) return false;
        String raw = terms.raw() == null ? "" : terms.raw().toLowerCase(java.util.Locale.ROOT);
        boolean selfReference = terms.words().contains("me")
                || terms.words().contains("myself")
                || terms.words().contains("self")
                || raw.contains("current player")
                || raw.contains("this player");
        if (!selfReference) return false;

        // These are state/admin-style self effects where Minecraft commands are
        // the native primitive. The list identifies intent classes, never command
        // syntax, so modded syntax remains discovered from Brigadier at runtime.
        return any(terms, KILL)
                || terms.words().contains("gamemode")
                || terms.words().contains("creative")
                || terms.words().contains("survival")
                || terms.words().contains("spectator")
                || terms.words().contains("adventure")
                || terms.words().contains("give")
                || terms.words().contains("clear")
                || terms.words().contains("effect")
                || terms.words().contains("enchant")
                || terms.words().contains("xp")
                || terms.words().contains("experience")
                || terms.words().contains("teleport")
                || terms.words().contains("tp");
    }

    private static List<ModelToolDefinition> build() {
        List<ModelToolDefinition> tools = new ArrayList<>(AutomationCapabilityRegistry.modelTools());
        tools.addAll(MinecraftKnowledgeModelToolRegistry.modelTools());
        tools.addAll(MinecraftCommandModelToolRegistry.modelTools());
        tools.addAll(ModelWorkspaceToolRegistry.modelTools());
        tools.addAll(WorkspaceExecutionModelToolRegistry.modelTools());
        tools.addAll(WorkspaceProcessModelToolRegistry.modelTools());
        tools.addAll(WorkspacePackageModelToolRegistry.modelTools());
        tools.addAll(WorkspaceDatabaseModelToolRegistry.modelTools());
        tools.addAll(WorkspaceGitArchiveModelToolRegistry.modelTools());
        tools.addAll(SystemNetworkModelToolRegistry.modelTools());
        tools.addAll(DataContextIndexModelToolRegistry.modelTools());
        tools.addAll(BackgroundAutomationModelToolRegistry.modelTools());
        tools.addAll(AutomationTimerModelToolRegistry.modelTools());
        tools.addAll(ProjectValidationModelToolRegistry.modelTools());
        tools.addAll(AutomationPlanModelToolRegistry.modelTools());
        tools.addAll(AutomationGoalModelToolRegistry.modelTools());
        tools.addAll(AutomationKtlSkillModelToolRegistry.modelTools());
        tools.addAll(AgentSkillModelToolRegistry.modelTools());
        tools.addAll(ToolDiscoveryModelToolRegistry.modelTools());
        tools.addAll(InternetResearchModelToolRegistry.modelTools());
        tools.addAll(DatasetIntelligenceModelToolRegistry.modelTools());
        tools.addAll(BrowserIntelligenceModelToolRegistry.modelTools());
        tools.addAll(ContentIntelligenceModelToolRegistry.modelTools());
        tools.addAll(McpCatalogueModelToolRegistry.modelTools());
        tools.addAll(KoilDocumentationModelToolRegistry.modelTools());
        tools.addAll(CodeIntelligenceModelToolRegistry.modelTools());
        tools.addAll(ContextIntelligenceService.modelTools());
        return List.copyOf(tools);
    }

    private static List<ModelToolDefinition> merge(List<ModelToolDefinition> staticTools, List<ModelToolDefinition> dynamicTools) {
        if (dynamicTools.isEmpty()) return staticTools;
        java.util.LinkedHashMap<String, ModelToolDefinition> merged = new java.util.LinkedHashMap<>();
        for (ModelToolDefinition tool : staticTools) merged.put(tool.id(), tool);
        for (ModelToolDefinition tool : dynamicTools) merged.put(tool.id(), tool);
        return List.copyOf(merged.values());
    }

    private static List<ModelToolDefinition> buildReadOnlyInformationTools() {
        List<ModelToolDefinition> tools = new ArrayList<>();
        MinecraftKnowledgeModelToolRegistry.modelTools().stream()
            .filter(tool -> !MinecraftKnowledgeModelToolRegistry.COMMAND_TOOL_ID.equals(tool.id()))
            .forEach(tools::add);
        tools.addAll(MinecraftCommandModelToolRegistry.modelTools());
        tools.addAll(AgentSkillModelToolRegistry.modelTools());
        tools.addAll(ToolDiscoveryModelToolRegistry.modelTools());
        ModelWorkspaceToolRegistry.modelTools().stream()
            .filter(tool -> Set.of("workspace.roots", "workspace.list", "workspace.stat", "workspace.read", "workspace.search")
                .contains(tool.id()))
            .forEach(tools::add);
        AutomationTimerModelToolRegistry.modelTools().stream()
            .filter(tool -> Set.of(AutomationTimerModelToolRegistry.STATUS_TOOL_ID, AutomationTimerModelToolRegistry.LIST_TOOL_ID).contains(tool.id()))
            .forEach(tools::add);
        WorkspaceProcessModelToolRegistry.modelTools().stream().filter(tool -> !WorkspaceProcessModelToolRegistry.STOP.equals(tool.id())).forEach(tools::add);
        WorkspacePackageModelToolRegistry.modelTools().stream().filter(tool -> WorkspacePackageModelToolRegistry.INSPECT.equals(tool.id())).forEach(tools::add);
        WorkspaceDatabaseModelToolRegistry.modelTools().stream().filter(tool -> !WorkspaceDatabaseModelToolRegistry.EXECUTE.equals(tool.id())).forEach(tools::add);
        WorkspaceGitArchiveModelToolRegistry.modelTools().stream().filter(tool -> !Set.of(WorkspaceGitArchiveModelToolRegistry.GIT_STAGE, WorkspaceGitArchiveModelToolRegistry.GIT_COMMIT, WorkspaceGitArchiveModelToolRegistry.ARC_CREATE, WorkspaceGitArchiveModelToolRegistry.ARC_EXTRACT, WorkspaceGitArchiveModelToolRegistry.ARC_PATCH).contains(tool.id())).forEach(tools::add);
        tools.addAll(SystemNetworkModelToolRegistry.modelTools());
        DataContextIndexModelToolRegistry.modelTools().stream().filter(tool -> !DataContextIndexModelToolRegistry.DATA_CONVERT.equals(tool.id())).forEach(tools::add);
        BackgroundAutomationModelToolRegistry.modelTools().stream().filter(tool -> Set.of(BackgroundAutomationModelToolRegistry.WORKFLOW_LIST, BackgroundAutomationModelToolRegistry.SCHEDULE_STATUS, BackgroundAutomationModelToolRegistry.SCHEDULE_LIST).contains(tool.id())).forEach(tools::add);
        tools.addAll(InternetResearchModelToolRegistry.modelTools());
        tools.addAll(DatasetIntelligenceModelToolRegistry.modelTools());
        BrowserIntelligenceModelToolRegistry.modelTools().stream()
            .filter(tool -> BrowserIntelligenceModelToolRegistry.readOnly(tool.id()))
            .forEach(tools::add);
        tools.addAll(ContentIntelligenceModelToolRegistry.modelTools());
        tools.addAll(McpCatalogueModelToolRegistry.modelTools());
        tools.addAll(KoilDocumentationModelToolRegistry.modelTools());
        tools.addAll(CodeIntelligenceModelToolRegistry.modelTools());
        tools.addAll(ContextIntelligenceService.modelTools());
        return List.copyOf(tools);
    }

    private static boolean isInformationRequest(IntentSelection intent) {
        if (intent == null) return false;
        if (intent.selected().stream().anyMatch(id -> READ_ONLY_INFORMATION_TOOLS.stream()
                .anyMatch(tool -> tool.id().equals(id)))) return true;
        String value = intent.prompt().raw().strip().toLowerCase(java.util.Locale.ROOT);
        return value.endsWith("?")
            || value.startsWith("define ") || value.startsWith("explain ")
            || value.startsWith("tell me ") || value.startsWith("show me ")
            || value.startsWith("look up ") || value.startsWith("find ")
            || value.startsWith("search ") || value.startsWith("check ")
            || value.startsWith("read ") || value.startsWith("list ");
    }


    private static boolean requiresExternalEvidence(PromptTerms terms) {
        if (terms == null) return false;
        String raw = terms.raw() == null ? "" : terms.raw().strip().toLowerCase(java.util.Locale.ROOT);
        if (raw.contains("http://") || raw.contains("https://")) return true;
        Set<String> words = terms.words();
        return words.contains("latest") || words.contains("current") || words.contains("currently")
            || words.contains("today") || words.contains("tonight") || words.contains("now")
            || words.contains("recent") || words.contains("recently") || words.contains("updated")
            || words.contains("search") || words.contains("lookup") || words.contains("check")
            || words.contains("verify") || words.contains("web") || words.contains("website")
            || words.contains("internet") || words.contains("online") || words.contains("source")
            || words.contains("sources") || words.contains("citation") || words.contains("citations");
    }

    private static List<ModelToolDefinition> fallbackInformationTools() {
        return READ_ONLY_INFORMATION_TOOLS.stream()
                .filter(tool -> InternetResearchModelToolRegistry.SEARCH.equals(tool.id()))
                .limit(1)
                .toList();
    }

    public static boolean requiresFreshApproval(String toolId) {
        if (toolId == null || "automation.cancel".equals(toolId)) {
            return false;
        }

        return automationModeTools().stream()
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
