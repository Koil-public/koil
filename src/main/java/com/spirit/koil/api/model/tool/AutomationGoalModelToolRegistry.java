package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.goal.AutomationFactGraph;
import com.spirit.koil.api.automation.goal.AutomationGoal;
import com.spirit.koil.api.automation.goal.AutomationGoalCompiler;
import com.spirit.koil.api.automation.goal.AutomationGoalFailureCode;
import com.spirit.koil.api.automation.goal.AutomationGoalExecutionService;
import com.spirit.koil.api.automation.goal.AutomationGoalVerifier;
import com.spirit.koil.api.automation.goal.AutomationGoalPlanningService;
import com.spirit.koil.api.automation.goal.RecipeDependencyPlanner;
import com.spirit.koil.api.automation.goal.SourceResolver;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Compact model boundary for deterministic high-level automation goals. */
public final class AutomationGoalModelToolRegistry {
    public static final String TOOL_ID = "automation.goal";
    private static final ModelToolDefinition DEFINITION = new ModelToolDefinition(
            TOOL_ID,
            "Plan or execute one high-level Minecraft automation goal from authoritative client facts. Plans combine synchronized crafting dependencies with bounded observed world sources, verified internal KTL transactions, graph traces, and bounded live replanning; Java primitives are never exposed to the model.",
            schema(),
            List.of("automation_mode_enabled", "world_loaded", "player_available"),
            Set.of("inventory"),
            false,
            Duration.ofMinutes(6),
            true,
            true,
            Set.of("completed", "planned", "already_satisfied", "blocked", "failed")
    );

    private AutomationGoalModelToolRegistry() {
    }

    public static String version() {
        return "automation-goal-tool-v4";
    }

    public static List<ModelToolDefinition> modelTools() {
        return List.of(DEFINITION);
    }

    public static boolean supports(String toolId) {
        return TOOL_ID.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        return execute(call, false);
    }

    /** Execute is authorized only by the AutomationToolCoordinator approval boundary. */
    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call, boolean executionAuthorized) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown automation goal tool."));
        }
        try {
            JsonObject arguments = call.arguments() == null ? new JsonObject() : call.arguments();
            String operation = string(arguments, "operation");
            if (!"obtain".equals(operation)) {
                return CompletableFuture.completedFuture(failure(call, "unsupported_goal_operation", "The goal runtime currently supports only operation obtain."));
            }
            AutomationGoal goal = AutomationGoal.obtain(string(arguments, "target"), integer(arguments, "count", 1));
            String mode = string(arguments, "mode");
            if (mode.isBlank()) mode = "simulate";
            if (!"simulate".equals(mode) && !"execute".equals(mode)) {
                return CompletableFuture.completedFuture(failure(call, "invalid_goal_mode", "mode must be simulate or execute."));
            }
            String selectedMode = mode;
            return AutomationGoalPlanningService.plan(goal).thenCompose(planning -> {
                AutomationFactGraph facts = planning.facts();
                if (planning.alreadySatisfied()) {
                    return CompletableFuture.completedFuture(satisfiedResult(
                            call, goal, selectedMode, facts, planning.compilerResult()
                    ));
                }
                RecipeDependencyPlanner.Result plan = planning.dependencyPlan();
                if (!"execute".equals(selectedMode)) {
                    return CompletableFuture.completedFuture(plannedResult(
                            call, goal, selectedMode, planning
                    ));
                }
                if (!planning.executable()) {
                    return CompletableFuture.completedFuture(plannedResult(
                            call, goal, selectedMode, planning
                    ));
                }
                if (!executionAuthorized) {
                    return CompletableFuture.completedFuture(failure(
                            call,
                            "approval_required",
                            "Executing the resolved production graph requires the Automation Mode approval boundary."
                    ));
                }
                return AutomationGoalExecutionService.execute(goal, plan)
                        .thenApply(execution -> executionResult(call, goal, facts, plan, execution));
            });
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(failure(call, "invalid_goal", exception.getMessage()));
        }
    }

    public static boolean requestsExecution(ModelToolCall call) {
        return call != null && call.arguments() != null && "execute".equals(string(call.arguments(), "mode"));
    }

    private static ModelToolResult satisfiedResult(
            ModelToolCall call,
            AutomationGoal goal,
            String mode,
            AutomationFactGraph facts,
            AutomationGoalCompiler.Result compiled
    ) {
        JsonObject output = baseOutput(goal, mode, facts);
        output.addProperty("executed", false);
        JsonArray nodes = new JsonArray();
        compiled.graph().nodes().forEach(node -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", node.id());
            encoded.addProperty("expectedObservation", node.expectedObservation());
            nodes.add(encoded);
        });
        output.addProperty("planId", "kagp-" + Integer.toHexString(compiled.graph().hashCode()));
        output.add("nodes", nodes);
        AutomationGoalVerifier.Result verification = new AutomationGoalVerifier().verify(goal, facts);
        if (verification.failed()) {
            output.addProperty("failureCode", verification.failureCode().id());
            return new ModelToolResult(call.id(), call.toolId(), "blocked", output,
                    verification.failureCode().id(), verification.detail());
        }
        return new ModelToolResult(
                call.id(), call.toolId(), "already_satisfied", output, "",
                verification.detail() + " No action was required."
        );
    }

    private static ModelToolResult blockedResult(
            ModelToolCall call,
            AutomationGoal goal,
            String mode,
            AutomationFactGraph facts,
            AutomationGoalCompiler.Result compiled,
            RecipeDependencyPlanner.Result plan
    ) {
        JsonObject output = baseOutput(goal, mode, facts);
        output.addProperty("executed", false);
        if (plan != null) encodePlan(output, plan, null);
        String failureCode = compiled.failureCode() == null ? "goal_blocked" : compiled.failureCode().id();
        output.addProperty("failureCode", failureCode);
        return new ModelToolResult(call.id(), call.toolId(), "blocked", output, failureCode, compiled.detail());
    }

    private static ModelToolResult plannedResult(
            ModelToolCall call,
            AutomationGoal goal,
            String mode,
            AutomationGoalPlanningService.Plan planning
    ) {
        AutomationFactGraph facts = planning == null ? null : planning.facts();
        JsonObject output = baseOutput(goal, mode, facts);
        output.addProperty("executed", false);
        if (planning == null) {
            output.addProperty("failureCode", AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE.id());
            return new ModelToolResult(
                    call.id(), call.toolId(), "blocked", output,
                    AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE.id(),
                    "Planning did not produce an authoritative result."
            );
        }

        RecipeDependencyPlanner.Result plan = planning.dependencyPlan();
        if (plan != null) encodePlan(output, plan, planning);
        else {
            output.addProperty("recipeCatalogTruncated", planning.catalogTruncated());
            output.addProperty("recipeCatalogItems", planning.catalogItems());
            output.addProperty("recipeCatalogDepth", planning.catalogDepth());
        }

        if (planning.failureCode() != null) {
            String code = planning.failureCode().id();
            output.addProperty("failureCode", code);
            return new ModelToolResult(
                    call.id(), call.toolId(), "blocked", output, code, planning.detail()
            );
        }
        if (plan == null) {
            output.addProperty("failureCode", AutomationGoalFailureCode.RECIPE_UNSUPPORTED.id());
            return new ModelToolResult(
                    call.id(), call.toolId(), "blocked", output,
                    AutomationGoalFailureCode.RECIPE_UNSUPPORTED.id(),
                    planning.detail().isBlank() ? "Planning produced no executable dependency graph." : planning.detail()
            );
        }
        return new ModelToolResult(
                call.id(), call.toolId(), "planned", output, "",
                "A fully resolved synchronized production graph is available. No action was executed in simulate mode."
        );
    }

    private static ModelToolResult executionResult(
            ModelToolCall call,
            AutomationGoal goal,
            AutomationFactGraph initialFacts,
            RecipeDependencyPlanner.Result plan,
            AutomationGoalExecutionService.Result execution
    ) {
        AutomationFactGraph finalFacts = execution.finalFacts() == null ? initialFacts : execution.finalFacts();
        JsonObject output = baseOutput(goal, "execute", finalFacts);
        output.addProperty("executed", !execution.executedNodeIds().isEmpty());
        encodePlan(output, plan, null);
        JsonArray executedNodes = new JsonArray();
        execution.executedNodeIds().forEach(executedNodes::add);
        output.add("executedNodes", executedNodes);
        JsonArray traces = new JsonArray();
        execution.traces().forEach(trace -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("nodeId", trace.nodeId());
            encoded.addProperty("expectedObservation", trace.expectedObservation());
            encoded.addProperty("actualObservation", trace.actualObservation());
            encoded.addProperty("runtimeFailureCode", trace.runtimeFailureCode());
            encoded.addProperty("retry", trace.retry());
            encoded.addProperty("durationMillis", trace.durationMillis());
            if (trace.failureCode() != null) encoded.addProperty("failureCode", trace.failureCode().id());
            traces.add(encoded);
        });
        output.add("trace", traces);
        if (execution.completed()) {
            return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", execution.detail());
        }
        String code = execution.failureCode() != null
                ? execution.failureCode().id()
                : execution.runtimeFailureCode().isBlank() ? "goal_execution_failed" : execution.runtimeFailureCode();
        output.addProperty("failureCode", code);
        return new ModelToolResult(call.id(), call.toolId(), "blocked", output, code, execution.detail());
    }

    private static JsonObject baseOutput(AutomationGoal goal, String mode, AutomationFactGraph facts) {
        JsonObject output = new JsonObject();
        output.addProperty("goalId", "kag-" + UUID.randomUUID().toString().substring(0, 12));
        output.addProperty("operation", goal.operation().name().toLowerCase(java.util.Locale.ROOT));
        output.addProperty("target", goal.targetId());
        output.addProperty("count", goal.count());
        output.addProperty("mode", mode);
        output.addProperty("inventoryCount", facts == null ? 0 : facts.inventoryCount(goal.targetId()));
        output.addProperty("itemExists", facts != null && booleanFact(facts, "item.exists:" + goal.targetId()));
        output.addProperty("recipeDataAvailable", facts != null && booleanFact(facts, "recipe.data_available"));
        output.addProperty("recipeKnown", facts != null && booleanFact(facts, "recipe.exists:" + goal.targetId()));
        return output;
    }

    private static void encodePlan(JsonObject output, RecipeDependencyPlanner.Result plan, AutomationGoalPlanningService.Plan planning) {
        JsonArray nodes = new JsonArray();
        plan.graph().nodes().forEach(node -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", node.id());
            encoded.addProperty("skill", node.skillId());
            encoded.addProperty("expectedObservation", node.expectedObservation());
            if (node.arguments().containsKey("item.id")) encoded.addProperty("item", String.valueOf(node.arguments().get("item.id")));
            if (node.arguments().containsKey("count.value")) encoded.addProperty("quantity", Integer.parseInt(String.valueOf(node.arguments().get("count.value"))));
            if (node.arguments().containsKey("recipe.id")) encoded.addProperty("recipe", String.valueOf(node.arguments().get("recipe.id")));
            nodes.add(encoded);
        });
        output.addProperty("planId", "kagp-" + Integer.toHexString(plan.graph().hashCode()));
        output.add("plannedNodes", nodes);
        JsonArray unresolved = new JsonArray();
        plan.unresolvedItems().forEach(unresolved::add);
        output.add("unresolvedItems", unresolved);
        JsonObject unresolvedReasons = new JsonObject();
        plan.unresolvedReasons().forEach(unresolvedReasons::addProperty);
        output.add("unresolvedReasons", unresolvedReasons);
        if (planning == null) return;
        output.addProperty("recipeCatalogTruncated", planning.catalogTruncated());
        output.addProperty("recipeCatalogItems", planning.catalogItems());
        output.addProperty("recipeCatalogDepth", planning.catalogDepth());
        JsonArray acquisitionSources = new JsonArray();
        planning.acquisitionSources().forEach((itemId, source) -> {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("item", itemId);
            encoded.addProperty("skill", source.skillId());
            encoded.addProperty("sourceKind", source.sourceKind());
            encoded.addProperty("target", source.targetId());
            encoded.addProperty("availableCount", source.availableCount());
            encoded.addProperty("radius", source.radius());
            encoded.addProperty("estimatedTicks", source.estimatedTicks());
            encoded.addProperty("risk", source.risk());
            encoded.addProperty("score", source.score());
            encoded.addProperty("confidence", source.confidence());
            acquisitionSources.add(encoded);
        });
        output.add("acquisitionSources", acquisitionSources);
        JsonArray sources = new JsonArray();
        SourceResolver resolver = new SourceResolver();
        for (String itemId : plan.unresolvedItems()) {
            for (SourceResolver.Candidate candidate : resolver.resolve(itemId, 1, planning.inventoryCounts(), planning.recipeCatalog(), planning.acquisitionSources())) {
                JsonObject encoded = new JsonObject();
                encoded.addProperty("item", itemId);
                encoded.addProperty("source", candidate.source());
                encoded.addProperty("quantity", candidate.quantity());
                encoded.addProperty("estimatedTicks", candidate.estimatedTicks());
                encoded.addProperty("risk", candidate.risk());
                encoded.addProperty("confidence", candidate.confidence());
                sources.add(encoded);
            }
        }
        output.add("sourceCandidates", sources);
    }

    private static boolean booleanFact(AutomationFactGraph facts, String key) {
        return facts.has(key) && Boolean.TRUE.equals(facts.fact(key).value());
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        properties.add("operation", enumString("obtain"));
        properties.add("target", stringSchema(3, 128));
        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("minimum", 1);
        count.addProperty("maximum", 64);
        properties.add("count", count);
        properties.add("mode", enumString("simulate", "execute"));
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("operation");
        required.add("target");
        schema.add("required", required);
        return schema;
    }

    private static JsonObject enumString(String... values) {
        JsonObject schema = stringSchema(1, 32);
        JsonArray options = new JsonArray();
        for (String value : values) options.add(value);
        schema.add("enum", options);
        return schema;
    }

    private static JsonObject stringSchema(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("minLength", minimum);
        schema.addProperty("maxLength", maximum);
        return schema;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsString().strip() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(
                call == null ? "" : call.id(),
                call == null ? "" : call.toolId(),
                "failed",
                new JsonObject(),
                code,
                detail == null ? "" : detail
        );
    }
}
