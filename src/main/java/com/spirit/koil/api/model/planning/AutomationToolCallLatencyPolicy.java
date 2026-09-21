package com.spirit.koil.api.model.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects Koil's compact first-provider-round path for one exact Automation
 * action.
 *
 * <p>This policy is deliberately conservative. A direct decision changes only
 * request shape: Koil may use a smaller system contract, omit unrelated
 * conversation history, expose the one registered action schema, and cap the
 * provider's output budget. It does not bypass approval, capability
 * validation, the executor, KTL, Minecraft permissions, cancellation,
 * objective tracking, result validation, Verification, or No-Fail recovery.</p>
 *
 * <p>The policy answers two separate questions:</p>
 * <ol>
 *     <li>Can the first provider round decide one tool call from the latest
 *     objective without consulting prior conversation or observing more
 *     state?</li>
 *     <li>After that path executes, can Koil use the compact verified-result
 *     response path without hiding retries, recovery, or unresolved work?</li>
 * </ol>
 *
 * <p>Use {@link #assess(String, AutomationThinkingPolicy.Decision, Set, List,
 * boolean, boolean)} when diagnostics need the reason and detected shape.
 * Existing callers can keep using {@link #evaluate(String,
 * AutomationThinkingPolicy.Decision, Set, List, boolean, boolean)}.</p>
 */
public final class AutomationToolCallLatencyPolicy {
    /** Existing zero-argument direct-action budget. Kept stable for proofs. */
    public static final int DIRECT_TOOL_OUTPUT_TOKENS = 96;

    /** Slightly larger budget for a simple schema with explicit arguments. */
    public static final int DIRECT_TOOL_ARGUMENT_OUTPUT_TOKENS = 128;

    /** Long objectives belong on the full Automation path even with one tool. */
    public static final int MAXIMUM_DIRECT_PROMPT_CHARACTERS = 240;

    /** Dense schemas are intentionally left to the full agent prompt. */
    public static final int MAXIMUM_DIRECT_REQUIRED_ARGUMENTS = 4;
    public static final int MAXIMUM_DIRECT_SCHEMA_PROPERTIES = 12;

    private static final Set<String> NON_ACTION_COMPANIONS = Set.of(
            "automation.cancel",
            "input.release",
            "input.release_all"
    );

    private static final Set<String> DIRECTION_WORDS = Set.of(
            "forward", "forwards", "back", "backward", "backwards",
            "left", "right", "north", "south", "east", "west",
            "up", "down", "above", "below"
    );

    private static final Set<String> NUMBER_WORDS = Set.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
            "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
            "once", "twice", "thrice"
    );

    private static final Pattern NUMBER_LITERAL = Pattern.compile(
            "(?<![a-z0-9_])-?\\d+(?:\\.\\d+)?(?![a-z0-9_])"
    );
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "\\b[a-z0-9_.-]+:[a-z0-9_./-]+\\b"
    );
    private static final Pattern QUESTION_WORD = Pattern.compile(
            "^(?:what|which|who|where|when|why|how)\\b"
    );
    private static final Pattern POLITE_ACTION_QUESTION = Pattern.compile(
            "^(?:(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+).+\\?$"
    );
    private static final Pattern EXPLICIT_SEQUENCE = Pattern.compile(
            "\\b(?:then|afterwards|next)\\b|\\band\\s+then\\b|\\bafter\\s+that\\b"
    );
    private static final Pattern ORDER_DEPENDENCY = Pattern.compile(
            "\\b(?:before|after|while|until|once\\s+.+\\s+then|first|finally)\\b"
    );
    private static final Pattern CONDITIONAL = Pattern.compile(
            "\\b(?:if|unless|when|whenever|in case|depending on|only if|otherwise)\\b"
    );
    private static final Pattern REPETITION = Pattern.compile(
            "\\b(?:repeat|repeatedly|again and again|twice|thrice|several times|multiple times|"
                    + "\\d+\\s+(?:times|x))\\b"
    );
    private static final Pattern BROAD_QUANTITY = Pattern.compile(
            "\\b(?:all|every|each|everything|everyone|entire|whole|as many as possible)\\b"
    );
    private static final Pattern SELECTION_OR_OPTIMIZATION = Pattern.compile(
            "\\b(?:choose|pick|select|best|safest|fastest|shortest|closest|nearest|"
                    + "optimal|optimize|decide|prefer|better)\\b"
    );
    private static final Pattern EVIDENCE_OR_SEARCH = Pattern.compile(
            "\\b(?:verify|check|inspect|search|find|locate|discover|investigate|compare|"
                    + "analyze|measure|test|confirm)\\b"
    );
    private static final Pattern EXPLANATION_REQUEST = Pattern.compile(
            "\\b(?:explain|why|reason|show me how|tell me how)\\b"
    );
    private static final Pattern CONVERSATION_REFERENCE = Pattern.compile(
            "\\b(?:again|continue|resume|same|previous|earlier|last one|like before|"
                    + "do it|use it|that one|those|these|them|there|what i said|"
                    + "as before|the previous one)\\b"
    );
    private static final Pattern DEICTIC_REFERENCE = Pattern.compile(
            "\\b(?:this|that|here|there)\\b"
    );
    private static final Pattern PERCEPTUAL_SPATIAL = Pattern.compile(
            "\\b(?:nearby|near me|around me|closest|nearest|toward|towards|"
                    + "beside|across|through|in front of|behind|next to|over this|"
                    + "under this|the gap|the chest|the mob|the block)\\b"
    );
    private static final Pattern MULTI_CLAUSE_PUNCTUATION = Pattern.compile("[;\\n]|,(?=\\s*\\p{L})");

    private AutomationToolCallLatencyPolicy() {
    }

    /**
     * Backward-compatible fast-path selector used by LocalModelService.
     */
    public static Decision evaluate(
            String prompt,
            AutomationThinkingPolicy.Decision thinking,
            Set<String> requiredToolIds,
            List<ModelToolDefinition> roundTools,
            boolean planningModeEnabled,
            boolean firstProviderRound
    ) {
        return assess(
                prompt,
                thinking,
                requiredToolIds,
                roundTools,
                planningModeEnabled,
                firstProviderRound
        ).decision();
    }

    /**
     * Produces an inspectable classification of a potential compact tool round.
     * This is deterministic policy metadata, not model chain-of-thought.
     */
    public static Assessment assess(
            String prompt,
            AutomationThinkingPolicy.Decision thinking,
            Set<String> requiredToolIds,
            List<ModelToolDefinition> roundTools,
            boolean planningModeEnabled,
            boolean firstProviderRound
    ) {
        if (!firstProviderRound) {
            return Assessment.full("continuation_round");
        }
        if (planningModeEnabled) {
            return Assessment.full("planning_mode");
        }
        if (thinking == null) {
            return Assessment.full("missing_reasoning_policy");
        }
        if (thinking.depth() != AutomationThinkingPolicy.Depth.DIRECT
                || thinking.includePlanTool()
                || thinking.deepActive()) {
            return Assessment.full("non_direct_reasoning");
        }
        if (requiredToolIds == null || requiredToolIds.isEmpty()) {
            return Assessment.full("no_required_action");
        }
        if (requiredToolIds.size() != 1) {
            return Assessment.full("multiple_required_actions");
        }
        if (roundTools == null || roundTools.isEmpty()) {
            return Assessment.full("missing_tool");
        }

        String requiredId = cleanId(requiredToolIds.iterator().next());
        if (requiredId.isBlank()) {
            return Assessment.full("invalid_required_tool_id");
        }

        ToolSelection tools = inspectTools(requiredId, roundTools);
        if (tools.requiredTool() == null) {
            return Assessment.withTool("required_tool_not_supplied", requiredId, tools);
        }
        if (tools.duplicateIds()) {
            return Assessment.withTool("duplicate_tool_ids", requiredId, tools);
        }
        if (!tools.unrelatedIds().isEmpty()) {
            return Assessment.withTool("requires_companion_evidence_or_choice", requiredId, tools);
        }

        String normalized = normalize(prompt);
        PromptShape promptShape = analyzePrompt(normalized);

        if (normalized.isBlank()) {
            return Assessment.rejected("empty_objective", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (normalized.length() > MAXIMUM_DIRECT_PROMPT_CHARACTERS) {
            return Assessment.rejected("objective_too_long", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.question() && !promptShape.politeActionQuestion()) {
            return Assessment.rejected("information_question", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.explicitSequence() || promptShape.orderDependency()) {
            return Assessment.rejected("sequenced_or_dependent", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.conditional()) {
            return Assessment.rejected("conditional_action", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.repetition()) {
            return Assessment.rejected("repeated_action", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.broadQuantity()) {
            return Assessment.rejected("bounded_multi_target_action", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.selectionOrOptimization()) {
            return Assessment.rejected("requires_selection_or_optimization", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.evidenceOrSearch()) {
            return Assessment.rejected("requires_observation_or_evidence", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.explanationRequest()) {
            return Assessment.rejected("explanation_not_direct_action", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.conversationReference()) {
            return Assessment.rejected("conversation_reference", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.perceptualSpatialReference()) {
            return Assessment.rejected("world_context_required", requiredId, promptShape, tools, ToolShape.empty());
        }
        if (promptShape.multiClausePunctuation()) {
            return Assessment.rejected("multiple_clauses", requiredId, promptShape, tools, ToolShape.empty());
        }

        ToolShape toolShape = analyzeTool(tools.requiredTool());
        if (toolShape.branchingSchema()) {
            return Assessment.rejected("branching_tool_schema", requiredId, promptShape, tools, toolShape);
        }
        if (toolShape.requiredArguments() > MAXIMUM_DIRECT_REQUIRED_ARGUMENTS) {
            return Assessment.rejected("argument_dense_tool", requiredId, promptShape, tools, toolShape);
        }
        if (toolShape.schemaProperties() > MAXIMUM_DIRECT_SCHEMA_PROPERTIES) {
            return Assessment.rejected("large_tool_schema", requiredId, promptShape, tools, toolShape);
        }
        if (toolShape.deeplyNestedSchema()) {
            return Assessment.rejected("nested_tool_schema", requiredId, promptShape, tools, toolShape);
        }

        String schemaMismatch = schemaPromptMismatch(
                normalized,
                promptShape,
                toolShape.requiredArgumentNames()
        );
        if (!schemaMismatch.isBlank()) {
            return Assessment.rejected(schemaMismatch, requiredId, promptShape, tools, toolShape);
        }

        int outputTokens = directOutputTokens(toolShape);
        String reason = toolShape.requiredArguments() == 0
                ? "single_self_contained_action"
                : "single_self_contained_action_with_arguments";

        return new Assessment(
                true,
                reason,
                requiredId,
                true,
                outputTokens,
                promptShape,
                toolShape,
                tools.suppliedIds(),
                List.of()
        );
    }

    /**
     * The compact verified-result round is intentionally stricter than merely
     * "some success happened". It is only used when the compact session
     * produced exactly one tool result and that one action satisfied the full
     * objective. Retries and recoveries stay on the full agent path so their
     * outcome can be reported faithfully.
     */
    public static boolean useDirectVerifiedResultRound(
            boolean directToolDecisionSession,
            int toolResultsReceived,
            int successfulActionToolOutputs,
            boolean everyKnownObjectiveCompleted,
            boolean reviewedPlanPresent
    ) {
        return assessDirectVerifiedResultRound(
                directToolDecisionSession,
                toolResultsReceived,
                successfulActionToolOutputs,
                everyKnownObjectiveCompleted,
                reviewedPlanPresent
        ).eligible();
    }

    /**
     * Inspectable result-side decision for tests and runtime diagnostics.
     */
    public static DirectResultAssessment assessDirectVerifiedResultRound(
            boolean directToolDecisionSession,
            int toolResultsReceived,
            int successfulActionToolOutputs,
            boolean everyKnownObjectiveCompleted,
            boolean reviewedPlanPresent
    ) {
        if (!directToolDecisionSession) {
            return DirectResultAssessment.rejected("not_direct_tool_session");
        }
        if (reviewedPlanPresent) {
            return DirectResultAssessment.rejected("reviewed_plan_present");
        }
        if (!everyKnownObjectiveCompleted) {
            return DirectResultAssessment.rejected("objective_incomplete");
        }
        if (toolResultsReceived != 1) {
            return DirectResultAssessment.rejected(
                    toolResultsReceived < 1 ? "missing_tool_result" : "multiple_tool_results"
            );
        }
        if (successfulActionToolOutputs != 1) {
            return DirectResultAssessment.rejected(
                    successfulActionToolOutputs < 1
                            ? "missing_validated_action_success"
                            : "multiple_successful_actions"
            );
        }
        return new DirectResultAssessment(true, "single_verified_action_complete");
    }

    private static ToolSelection inspectTools(
            String requiredId,
            List<ModelToolDefinition> roundTools
    ) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        boolean duplicates = false;
        ModelToolDefinition required = null;
        List<String> unrelated = new ArrayList<>();

        for (ModelToolDefinition tool : roundTools) {
            if (tool == null || tool.id() == null || tool.id().isBlank()) {
                continue;
            }
            String id = tool.id().trim();
            if (!seen.add(id)) {
                duplicates = true;
            }
            ids.add(id);
            if (requiredId.equals(id)) {
                required = tool;
            } else if (!NON_ACTION_COMPANIONS.contains(id)) {
                unrelated.add(id);
            }
        }

        return new ToolSelection(
                required,
                Set.copyOf(ids),
                List.copyOf(unrelated),
                duplicates
        );
    }

    private static PromptShape analyzePrompt(String normalized) {
        if (normalized.isBlank()) {
            return PromptShape.empty();
        }

        boolean question = normalized.endsWith("?") || QUESTION_WORD.matcher(normalized).find();
        boolean politeQuestion = question && POLITE_ACTION_QUESTION.matcher(normalized).matches();

        return new PromptShape(
                question,
                politeQuestion,
                EXPLICIT_SEQUENCE.matcher(normalized).find(),
                ORDER_DEPENDENCY.matcher(normalized).find(),
                CONDITIONAL.matcher(normalized).find(),
                REPETITION.matcher(normalized).find(),
                BROAD_QUANTITY.matcher(normalized).find(),
                SELECTION_OR_OPTIMIZATION.matcher(normalized).find(),
                EVIDENCE_OR_SEARCH.matcher(normalized).find(),
                EXPLANATION_REQUEST.matcher(normalized).find(),
                hasConversationReference(normalized),
                hasPerceptualSpatialReference(normalized),
                MULTI_CLAUSE_PUNCTUATION.matcher(normalized).find(),
                numericLiteralCount(normalized),
                containsNumberWord(normalized),
                NAMESPACED_ID.matcher(normalized).find()
        );
    }

    private static boolean hasConversationReference(String normalized) {
        if (CONVERSATION_REFERENCE.matcher(normalized).find()) {
            return true;
        }

        Matcher matcher = DEICTIC_REFERENCE.matcher(normalized);
        while (matcher.find()) {
            String word = matcher.group();
            if ("this".equals(word) || "that".equals(word)) {
                return true;
            }
            // "there" and "here" are only context references when used as a
            // destination/source rather than inside an identifier.
            int start = Math.max(0, matcher.start() - 12);
            String prefix = normalized.substring(start, matcher.start());
            if (prefix.matches(".*\\b(?:go|move|walk|fly|teleport|return|back)\\s+(?:to\\s+)?$")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPerceptualSpatialReference(String normalized) {
        return PERCEPTUAL_SPATIAL.matcher(normalized).find();
    }

    private static ToolShape analyzeTool(ModelToolDefinition tool) {
        JsonObject schema = tool == null ? null : tool.inputSchema();
        if (schema == null) {
            return ToolShape.empty();
        }

        Set<String> requiredNames = requiredNames(schema);
        JsonObject properties = object(schema, "properties");
        int propertyCount = properties == null ? 0 : properties.size();
        boolean branching = containsSchemaKeyword(schema, "oneOf")
                || containsSchemaKeyword(schema, "anyOf")
                || containsSchemaKeyword(schema, "allOf");
        boolean nested = properties != null && hasDeepNestedInput(properties);

        return new ToolShape(
                requiredNames.size(),
                propertyCount,
                Set.copyOf(requiredNames),
                branching,
                nested,
                tool.confirmationRequired(),
                tool.reversible(),
                tool.cancellationSupported(),
                tool.preconditions() == null ? 0 : tool.preconditions().size(),
                tool.sideEffects() == null ? 0 : tool.sideEffects().size(),
                tool.resultStates() == null ? Set.of() : Set.copyOf(tool.resultStates())
        );
    }

    private static Set<String> requiredNames(JsonObject schema) {
        Set<String> names = new LinkedHashSet<>();
        JsonElement requiredElement = schema.get("required");
        if (requiredElement == null || !requiredElement.isJsonArray()) {
            return names;
        }
        JsonArray required = requiredElement.getAsJsonArray();
        for (JsonElement element : required) {
            if (element != null && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isString()) {
                String name = element.getAsString().trim();
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static boolean containsSchemaKeyword(JsonObject object, String key) {
        if (object == null) {
            return false;
        }
        if (object.has(key)) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) {
                if (value.isJsonArray()) {
                    return value.getAsJsonArray().size() > 0;
                }
                return true;
            }
        }
        for (String name : object.keySet()) {
            JsonElement child = object.get(name);
            if (child != null && child.isJsonObject()
                    && containsSchemaKeyword(child.getAsJsonObject(), key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeepNestedInput(JsonObject properties) {
        for (String name : properties.keySet()) {
            JsonElement element = properties.get(name);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject property = element.getAsJsonObject();
            String type = string(property, "type");
            if ("object".equals(type)) {
                JsonObject nestedProperties = object(property, "properties");
                if (nestedProperties != null && nestedProperties.size() > 0) {
                    return true;
                }
            }
            if ("array".equals(type)) {
                JsonObject items = object(property, "items");
                if (items != null && ("object".equals(string(items, "type"))
                        || items.has("properties")
                        || items.has("oneOf")
                        || items.has("anyOf"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rejects a few schema shapes where the provider would otherwise have to
     * invent a required value that is absent from a supposedly self-contained
     * objective. This is intentionally generic rather than tool-id specific.
     */
    private static String schemaPromptMismatch(
            String normalized,
            PromptShape promptShape,
            Set<String> required
    ) {
        if (required == null || required.isEmpty()) {
            return "";
        }

        if (hasAny(required, "x", "z")) {
            int coordinateFields = 0;
            if (required.contains("x")) coordinateFields++;
            if (required.contains("y")) coordinateFields++;
            if (required.contains("z")) coordinateFields++;
            if (promptShape.numericLiteralCount() < Math.min(2, coordinateFields)) {
                return "missing_explicit_coordinates";
            }
        }

        if (hasAny(required, "distance", "count", "quantity", "amount", "radius", "duration")
                && promptShape.numericLiteralCount() == 0
                && !promptShape.numberWord()) {
            return "missing_explicit_quantity";
        }

        if (required.contains("direction") && !containsDirection(normalized)) {
            return "missing_explicit_direction";
        }

        if (required.contains("command")
                && !normalized.contains("/")
                && !containsAnyWord(normalized, "command", "run", "execute")) {
            return "missing_explicit_command";
        }

        if (required.contains("path")
                && !looksLikePath(normalized)
                && !NAMESPACED_ID.matcher(normalized).find()) {
            return "missing_explicit_path";
        }

        return "";
    }

    private static int directOutputTokens(ToolShape toolShape) {
        if (toolShape.requiredArguments() == 0 && toolShape.schemaProperties() <= 2) {
            return DIRECT_TOOL_OUTPUT_TOKENS;
        }
        return DIRECT_TOOL_ARGUMENT_OUTPUT_TOKENS;
    }

    private static boolean containsDirection(String normalized) {
        for (String word : DIRECTION_WORDS) {
            if (containsWord(normalized, word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNumberWord(String normalized) {
        for (String word : NUMBER_WORDS) {
            if (containsWord(normalized, word)) {
                return true;
            }
        }
        return false;
    }

    private static int numericLiteralCount(String normalized) {
        int count = 0;
        Matcher matcher = NUMBER_LITERAL.matcher(normalized);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean looksLikePath(String normalized) {
        return normalized.contains("/")
                || normalized.contains("\\")
                || normalized.matches(".*\\b[a-z0-9_.-]+\\.(?:json|java|kt|ktl|txt|md|toml|yaml|yml|properties)\\b.*");
    }

    private static boolean hasAny(Set<String> values, String... names) {
        for (String name : names) {
            if (values.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyWord(String value, String... words) {
        for (String word : words) {
            if (containsWord(value, word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String value, String word) {
        return Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(word) + "(?![a-z0-9_])")
                .matcher(value)
                .find();
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return "";
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive()
                ? element.getAsString().trim().toLowerCase(Locale.ROOT)
                : "";
    }

    private static String cleanId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.ROOT)
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replaceAll("\\s+", " ")
                .strip();
    }

    public record Decision(
            boolean directToolDecision,
            String reason,
            int maximumOutputTokens,
            boolean freshConversationWindow
    ) {
        private static Decision full(String reason) {
            return new Decision(false, reason, 0, false);
        }
    }

    /**
     * Public diagnostics for why the compact tool path was accepted/rejected.
     */
    public record Assessment(
            boolean directToolDecision,
            String reason,
            String requiredToolId,
            boolean freshConversationWindow,
            int maximumOutputTokens,
            PromptShape promptShape,
            ToolShape toolShape,
            Set<String> suppliedToolIds,
            List<String> blockingToolIds
    ) {
        public Assessment {
            reason = reason == null ? "" : reason;
            requiredToolId = requiredToolId == null ? "" : requiredToolId;
            promptShape = promptShape == null ? PromptShape.empty() : promptShape;
            toolShape = toolShape == null ? ToolShape.empty() : toolShape;
            suppliedToolIds = suppliedToolIds == null ? Set.of() : Set.copyOf(suppliedToolIds);
            blockingToolIds = blockingToolIds == null ? List.of() : List.copyOf(blockingToolIds);
            maximumOutputTokens = Math.max(0, maximumOutputTokens);
        }

        public Decision decision() {
            return directToolDecision
                    ? new Decision(true, reason, maximumOutputTokens, freshConversationWindow)
                    : Decision.full(reason);
        }

        private static Assessment full(String reason) {
            return new Assessment(
                    false, reason, "", false, 0,
                    PromptShape.empty(), ToolShape.empty(), Set.of(), List.of()
            );
        }

        private static Assessment withTool(
                String reason,
                String requiredId,
                ToolSelection tools
        ) {
            return new Assessment(
                    false,
                    reason,
                    requiredId,
                    false,
                    0,
                    PromptShape.empty(),
                    ToolShape.empty(),
                    tools == null ? Set.of() : tools.suppliedIds(),
                    tools == null ? List.of() : tools.unrelatedIds()
            );
        }

        private static Assessment rejected(
                String reason,
                String requiredId,
                PromptShape promptShape,
                ToolSelection tools,
                ToolShape toolShape
        ) {
            return new Assessment(
                    false,
                    reason,
                    requiredId,
                    false,
                    0,
                    promptShape,
                    toolShape,
                    tools == null ? Set.of() : tools.suppliedIds(),
                    tools == null ? List.of() : tools.unrelatedIds()
            );
        }
    }

    public record PromptShape(
            boolean question,
            boolean politeActionQuestion,
            boolean explicitSequence,
            boolean orderDependency,
            boolean conditional,
            boolean repetition,
            boolean broadQuantity,
            boolean selectionOrOptimization,
            boolean evidenceOrSearch,
            boolean explanationRequest,
            boolean conversationReference,
            boolean perceptualSpatialReference,
            boolean multiClausePunctuation,
            int numericLiteralCount,
            boolean numberWord,
            boolean namespacedIdentifier
    ) {
        private static PromptShape empty() {
            return new PromptShape(
                    false, false, false, false, false, false, false, false,
                    false, false, false, false, false, 0, false, false
            );
        }
    }

    public record ToolShape(
            int requiredArguments,
            int schemaProperties,
            Set<String> requiredArgumentNames,
            boolean branchingSchema,
            boolean deeplyNestedSchema,
            boolean confirmationRequired,
            boolean reversible,
            boolean cancellationSupported,
            int preconditionCount,
            int sideEffectCount,
            Set<String> resultStates
    ) {
        public ToolShape {
            requiredArguments = Math.max(0, requiredArguments);
            schemaProperties = Math.max(0, schemaProperties);
            requiredArgumentNames = requiredArgumentNames == null
                    ? Set.of()
                    : Set.copyOf(requiredArgumentNames);
            resultStates = resultStates == null ? Set.of() : Set.copyOf(resultStates);
            preconditionCount = Math.max(0, preconditionCount);
            sideEffectCount = Math.max(0, sideEffectCount);
        }

        private static ToolShape empty() {
            return new ToolShape(
                    0, 0, Set.of(), false, false, false, false, false, 0, 0, Set.of()
            );
        }
    }

    public record DirectResultAssessment(
            boolean eligible,
            String reason
    ) {
        public DirectResultAssessment {
            reason = reason == null ? "" : reason;
        }

        private static DirectResultAssessment rejected(String reason) {
            return new DirectResultAssessment(false, reason);
        }
    }

    private record ToolSelection(
            ModelToolDefinition requiredTool,
            Set<String> suppliedIds,
            List<String> unrelatedIds,
            boolean duplicateIds
    ) {
    }
}
