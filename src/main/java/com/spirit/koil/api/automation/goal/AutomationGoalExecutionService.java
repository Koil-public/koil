package com.spirit.koil.api.automation.goal;

import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRequest;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResult;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResults;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Executes fully resolved goal-graph nodes through verified internal KTL routes. */
public final class AutomationGoalExecutionService {
    private static final int MAXIMUM_REPLANS = 2;
    private static final String CRAFT_SKILL = "crafting/craft/item";
    private static final String MINE_BLOCK_SKILL = "blocks/core/mine_block_until_count";
    private static final String OPEN_CONTAINER_SKILL = "goal/container/take_item_from_open_container";
    private static final String PROCESS_SKILL = "processing/cook/item";
    private static final String OPEN_PROCESSOR_SKILL = "goal/processor/open_nearby";
    private static final Set<String> EXECUTABLE_SKILLS = Set.of(
            CRAFT_SKILL, MINE_BLOCK_SKILL, OPEN_CONTAINER_SKILL, PROCESS_SKILL, OPEN_PROCESSOR_SKILL
    );

    private AutomationGoalExecutionService() {
    }

    public static CompletableFuture<Result> execute(AutomationGoal goal, RecipeDependencyPlanner.Result plan) {
        if (goal == null || plan == null || plan.graph() == null) {
            return CompletableFuture.completedFuture(Result.blocked(
                    AutomationGoalFailureCode.RECIPE_UNSUPPORTED,
                    "No executable goal graph is available."
            ));
        }
        if (!AutomationModeController.isAutomationMode()) {
            return CompletableFuture.completedFuture(Result.blocked(
                    AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE,
                    "Automation Mode is no longer active."
            ));
        }
        if (!plan.unresolvedItems().isEmpty()) {
            return CompletableFuture.completedFuture(Result.blocked(
                    AutomationGoalFailureCode.RESOURCE_MISSING,
                    "The goal graph still has unresolved resources: " + String.join(", ", plan.unresolvedItems())
            ));
        }

        PlanValidation validation = validatePlan(plan);
        if (!validation.valid()) {
            return CompletableFuture.completedFuture(Result.blocked(
                    AutomationGoalFailureCode.RECIPE_UNSUPPORTED,
                    validation.detail()
            ));
        }

        String goalId = "kag-exec-" + UUID.randomUUID().toString().substring(0, 12);
        List<String> executed = new ArrayList<>();
        List<AutomationGoalTrace> traces = new ArrayList<>();
        return executeNext(goal, validation.ordered(), 0, executed, traces, goalId, MAXIMUM_REPLANS, 0);
    }

    private static CompletableFuture<Result> executeNext(
            AutomationGoal goal,
            List<AutomationGoalGraph.Node> ordered,
            int index,
            List<String> executed,
            List<AutomationGoalTrace> traces,
            String goalId,
            int replansRemaining,
            int retryIndex
    ) {
        if (index >= ordered.size()) {
            return AutomationFactResolver.resolve(goal).thenApply(facts -> {
                AutomationGoalVerifier.Result verification = new AutomationGoalVerifier().verify(goal, facts);
                traces.add(new AutomationGoalTrace(
                        goalId,
                        "verify_goal",
                        List.of(AutomationFactGraph.inventoryKey(goal.targetId())),
                        List.of("inventory"),
                        "final inventory count >= requested count",
                        verification.detail() + " observed=" + verification.observedCount() + " requested=" + verification.requestedCount(),
                        verification.failureCode(),
                        "",
                        retryIndex,
                        0L,
                        Instant.now()
                ));
                if (verification.failed()) {
                    return Result.blocked(verification.failureCode(), verification.detail(), executed, facts, traces);
                }
                return Result.completed(verification.detail(), executed, facts, traces);
            });
        }

        AutomationGoalGraph.Node node = ordered.get(index);
        if (node.skillId().isBlank() || "inventory/verify_count".equals(node.skillId())) {
            return executeNext(goal, ordered, index + 1, executed, traces, goalId, replansRemaining, retryIndex);
        }
        if (!EXECUTABLE_SKILLS.contains(node.skillId())) {
            return CompletableFuture.completedFuture(Result.blocked(
                    AutomationGoalFailureCode.RECIPE_UNSUPPORTED,
                    "Goal node " + node.id() + " is not executable by the current goal runtime.",
                    executed,
                    null,
                    traces
            ));
        }

        String acquiredItem = isAcquisitionSkill(node.skillId())
                ? safeOptionalToken(node.arguments().get("item.id"))
                : "";
        int beforeAcquisition = acquiredItem.isBlank() ? 0 : liveInventoryCount(acquiredItem);
        long startedAt = System.nanoTime();

        return submitInternalNode(node).thenCompose(result -> {
            long durationMillis = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            boolean succeeded = executionSucceeded(result);
            String code = result == null || result.failureCode().isBlank()
                    ? defaultFailureCode(node)
                    : result.failureCode();
            String detail = result == null || result.detail().isBlank()
                    ? defaultFailureDetail(node)
                    : result.detail();

            if (succeeded && isAcquisitionSkill(node.skillId())) {
                int afterAcquisition = liveInventoryCount(acquiredItem);
                int gained = Math.max(0, afterAcquisition - beforeAcquisition);
                int requested = positiveInt(node.arguments().get("count.value"), 1);
                if (gained <= 0) {
                    succeeded = false;
                    code = "acquisition_no_item_delta";
                    detail = "The observed block-source task completed, but inventory did not gain " + acquiredItem + ".";
                } else if (gained < requested) {
                    succeeded = false;
                    code = "acquisition_partial";
                    detail = "The observed block-source task gained " + gained + " of " + requested
                            + " planned " + acquiredItem + "; replanning is required.";
                } else {
                    detail = "Authoritative inventory verification observed +" + gained + " " + acquiredItem
                            + " after the acquisition task.";
                }
            }

            traces.add(new AutomationGoalTrace(
                    goalId,
                    node.id(),
                    node.requiredFacts(),
                    List.copyOf(node.resourceLocks()),
                    node.expectedObservation(),
                    detail,
                    null,
                    succeeded ? "" : code,
                    retryIndex,
                    durationMillis,
                    Instant.now()
            ));

            if (!succeeded) {
                if (replansRemaining > 0 && retryablePlanningFailure(code)) {
                    return replanAndResume(
                            goal,
                            executed,
                            traces,
                            goalId,
                            replansRemaining - 1,
                            retryIndex + 1,
                            code,
                            detail
                    );
                }
                return CompletableFuture.completedFuture(Result.runtimeFailure(code, detail, executed, traces));
            }

            executed.add(node.id());
            return executeNext(goal, ordered, index + 1, executed, traces, goalId, replansRemaining, retryIndex);
        });
    }

    private static CompletableFuture<Result> replanAndResume(
            AutomationGoal goal,
            List<String> executed,
            List<AutomationGoalTrace> traces,
            String goalId,
            int replansRemaining,
            int retryIndex,
            String priorCode,
            String priorDetail
    ) {
        long startedAt = System.nanoTime();
        return AutomationGoalPlanningService.plan(goal).thenCompose(fresh -> {
            long durationMillis = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            traces.add(new AutomationGoalTrace(
                    goalId,
                    "replan#" + retryIndex,
                    List.of(),
                    List.of(),
                    "fresh authoritative facts produce an executable remainder",
                    fresh.detail(),
                    fresh.failureCode(),
                    priorCode,
                    retryIndex,
                    durationMillis,
                    Instant.now()
            ));
            if (fresh.alreadySatisfied()) {
                return AutomationFactResolver.resolve(goal).thenApply(facts ->
                        Result.completed(
                                "The goal became satisfied after runtime progress and was verified during replanning.",
                                executed,
                                facts,
                                traces
                        )
                );
            }
            if (!fresh.executable() || fresh.dependencyPlan() == null) {
                String reason = fresh.detail().isBlank()
                        ? "Live replanning could not produce another executable goal graph."
                        : fresh.detail();
                return CompletableFuture.completedFuture(Result.runtimeFailure(
                        priorCode,
                        priorDetail + " Replan stopped: " + reason,
                        executed,
                        traces
                ));
            }
            PlanValidation validation = validatePlan(fresh.dependencyPlan());
            if (!validation.valid()) {
                return CompletableFuture.completedFuture(Result.runtimeFailure(
                        priorCode,
                        priorDetail + " Replan produced an invalid executable graph: " + validation.detail(),
                        executed,
                        traces
                ));
            }
            return executeNext(
                    goal,
                    validation.ordered(),
                    0,
                    executed,
                    traces,
                    goalId,
                    replansRemaining,
                    retryIndex
            );
        });
    }

    private static boolean retryablePlanningFailure(String code) {
        if (code == null) return false;
        String normalized = code.toLowerCase(Locale.ROOT);
        return "verified_recipe_unavailable".equals(normalized)
                || "ingredients_exhausted".equals(normalized)
                || "verification_failed".equals(normalized)
                || "craft_execution_failed".equals(normalized)
                || "verified_processing_recipe_unavailable".equals(normalized)
                || "processing_input_exhausted".equals(normalized)
                || "fuel_exhausted".equals(normalized)
                || "processing_verification_failed".equals(normalized)
                || "processing_execution_failed".equals(normalized)
                || "processor_screen_required".equals(normalized)
                || "acquisition_partial".equals(normalized);
    }

    private static PlanValidation validatePlan(RecipeDependencyPlanner.Result plan) {
        if (plan == null || plan.graph() == null) {
            return new PlanValidation(false, List.of(), "No executable goal graph is available.");
        }
        if (!plan.unresolvedItems().isEmpty()) {
            return new PlanValidation(
                    false,
                    List.of(),
                    "The goal graph still has unresolved resources: " + String.join(", ", plan.unresolvedItems())
            );
        }
        List<AutomationGoalGraph.Node> ordered;
        try {
            ordered = topologicalOrder(plan.graph());
        } catch (IllegalStateException invalidGraph) {
            return new PlanValidation(false, List.of(), invalidGraph.getMessage());
        }
        for (AutomationGoalGraph.Node node : ordered) {
            if (node.skillId().isBlank() || "inventory/verify_count".equals(node.skillId())) continue;
            if (!EXECUTABLE_SKILLS.contains(node.skillId())) {
                return new PlanValidation(
                        false,
                        List.of(),
                        "Goal node " + node.id() + " has no verified executable KTL route: " + node.skillId()
                );
            }
        }
        return new PlanValidation(true, ordered, "");
    }

    private record PlanValidation(boolean valid, List<AutomationGoalGraph.Node> ordered, String detail) {
        private PlanValidation {
            ordered = List.copyOf(ordered == null ? List.of() : ordered);
            detail = detail == null ? "" : detail;
        }
    }

    private static CompletableFuture<AutomationExecutionResult> submitInternalNode(AutomationGoalGraph.Node node) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null || client.getNetworkHandler() == null) {
            return CompletableFuture.completedFuture(new AutomationExecutionResult(
                    UUID.randomUUID(), "failed", "world_unavailable",
                    "A loaded world and player connection are required for goal execution.",
                    node.skillId(), Map.of(), null, null, null, null
            ));
        }
        if (AutomationRouter.isTaskRunning()) {
            return CompletableFuture.completedFuture(new AutomationExecutionResult(
                    UUID.randomUUID(), "blocked", "automation_conflict",
                    "Another automation task currently owns the executor.",
                    node.skillId(), Map.of(), null, null, null, null
            ));
        }

        UUID executionId = UUID.randomUUID();
        AutomationRequest request;
        try {
            request = new AutomationRequest(invocation(node), true, true, executionId);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(new AutomationExecutionResult(
                    executionId, "failed", "invalid_goal_node", invalid.getMessage(),
                    node.skillId(), Map.of(), null, null, null, null
            ));
        }
        CompletableFuture<AutomationExecutionResult> future = AutomationExecutionResults.register(executionId);
        client.execute(() -> {
            if (!AutomationModeController.isAutomationMode()) {
                AutomationExecutionResults.publish(new AutomationExecutionResult(
                        executionId, "failed", "automation_disabled",
                        "Automation Mode was disabled before the goal node started.",
                        node.skillId(), Map.of(), null, null, null, null
                ));
                return;
            }
            try {
                AutomationRouter.handleInput(request, "automation-goal");
            } catch (RuntimeException exception) {
                AutomationExecutionResults.publish(new AutomationExecutionResult(
                        executionId, "failed", "submission_failed",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                        node.skillId(), Map.of(), null, null, null, null
                ));
            }
        });
        return future;
    }

    private static String invocation(AutomationGoalGraph.Node node) {
        if (CRAFT_SKILL.equals(node.skillId())) {
            String itemId = safeToken(node.arguments().get("item.id"), "item.id");
            String count = safeToken(node.arguments().getOrDefault("count.value", 1), "count.value");
            StringBuilder invocation = new StringBuilder(CRAFT_SKILL).append(".ktl")
                    .append(" item.id=").append(itemId)
                    .append(" count.value=").append(count);
            Object recipeId = node.arguments().get("recipe.id");
            if (recipeId != null && !recipeId.toString().isBlank()) {
                invocation.append(" recipe.id=").append(safeToken(recipeId, "recipe.id"));
            }
            return invocation.toString();
        }
        if (OPEN_PROCESSOR_SKILL.equals(node.skillId())) {
            StringBuilder invocation = new StringBuilder(OPEN_PROCESSOR_SKILL).append(".ktl");
            appendArgument(invocation, node, "target.id", true);
            appendArgument(invocation, node, "target.selector", false);
            appendArgument(invocation, node, "radius", false);
            appendArgument(invocation, node, "stop.distance", false);
            return invocation.toString();
        }
        if (PROCESS_SKILL.equals(node.skillId())) {
            StringBuilder invocation = new StringBuilder(PROCESS_SKILL).append(".ktl");
            appendArgument(invocation, node, "item.id", true);
            appendArgument(invocation, node, "count.value", false);
            appendArgument(invocation, node, "recipe.id", true);
            appendArgument(invocation, node, "process.kind", true);
            appendArgument(invocation, node, "fuel.id", true);
            appendArgument(invocation, node, "fuel.burn_ticks", true);
            appendArgument(invocation, node, "cook.ticks", true);
            return invocation.toString();
        }
        if (MINE_BLOCK_SKILL.equals(node.skillId())) {
            StringBuilder invocation = new StringBuilder(MINE_BLOCK_SKILL).append(".ktl");
            appendArgument(invocation, node, "target.id", true);
            appendArgument(invocation, node, "target.selector", false);
            appendArgument(invocation, node, "count.value", false);
            appendArgument(invocation, node, "state.counter", false);
            appendArgument(invocation, node, "radius", false);
            appendArgument(invocation, node, "stop.distance", false);
            return invocation.toString();
        }
        if (OPEN_CONTAINER_SKILL.equals(node.skillId())) {
            StringBuilder invocation = new StringBuilder(OPEN_CONTAINER_SKILL).append(".ktl");
            appendArgument(invocation, node, "item.id", true);
            appendArgument(invocation, node, "count.value", false);
            appendArgument(invocation, node, "state.counter", false);
            return invocation.toString();
        }
        throw new IllegalArgumentException("Unsupported internal goal skill: " + node.skillId());
    }

    private static void appendArgument(StringBuilder invocation, AutomationGoalGraph.Node node, String name, boolean required) {
        Object value = node.arguments().get(name);
        if (value == null || value.toString().isBlank()) {
            if (required) throw new IllegalArgumentException("Goal node is missing required " + name + ".");
            return;
        }
        invocation.append(' ').append(name).append('=').append(safeToken(value, name));
    }

    private static String safeToken(Object value, String name) {
        String token = value == null ? "" : value.toString().strip();
        if (token.isBlank() || token.length() > 256
                || token.indexOf('"') >= 0 || token.indexOf('\\') >= 0
                || token.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new IllegalArgumentException("Goal node contains an unsafe " + name + " token.");
        }
        return token;
    }

    private static String safeOptionalToken(Object value) {
        if (value == null || value.toString().isBlank()) return "";
        try {
            return safeToken(value, "item.id");
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean executionSucceeded(AutomationExecutionResult result) {
        if (result == null) return false;
        String status = result.status().toLowerCase(Locale.ROOT);
        return "success".equals(status) || "completed".equals(status) || "already_satisfied".equals(status);
    }

    private static String defaultFailureCode(AutomationGoalGraph.Node node) {
        if (PROCESS_SKILL.equals(node.skillId())) return "processing_execution_failed";
        return isAcquisitionSkill(node.skillId()) ? "resource_acquisition_failed" : "craft_execution_failed";
    }

    private static String defaultFailureDetail(AutomationGoalGraph.Node node) {
        if (PROCESS_SKILL.equals(node.skillId())) return "The internal processing task did not complete successfully.";
        return isAcquisitionSkill(node.skillId())
                ? "The internal resource-acquisition task did not complete successfully."
                : "The internal crafting task did not complete successfully.";
    }

    private static boolean isAcquisitionSkill(String skillId) {
        return MINE_BLOCK_SKILL.equals(skillId) || OPEN_CONTAINER_SKILL.equals(skillId);
    }

    private static int liveInventoryCount(String itemId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || itemId == null || itemId.isBlank()) return 0;
        Identifier wanted = Identifier.tryParse(itemId);
        if (wanted == null) return 0;
        int total = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack == null || stack.isEmpty()) continue;
            Identifier found = Registries.ITEM.getId(stack.getItem());
            if (wanted.equals(found)) total += stack.getCount();
        }
        return total;
    }

    private static int positiveInt(Object value, int fallback) {
        if (value == null) return Math.max(1, fallback);
        try {
            return Math.max(1, Integer.parseInt(value.toString()));
        } catch (NumberFormatException ignored) {
            return Math.max(1, fallback);
        }
    }

    private static List<AutomationGoalGraph.Node> topologicalOrder(AutomationGoalGraph graph) {
        Map<String, AutomationGoalGraph.Node> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        List<AutomationGoalGraph.Node> ordered = new ArrayList<>(nodes.size());
        Set<String> completed = new LinkedHashSet<>();
        while (completed.size() < nodes.size()) {
            boolean progressed = false;
            for (AutomationGoalGraph.Node node : nodes.values()) {
                if (completed.contains(node.id()) || !completed.containsAll(node.dependencies())) continue;
                ordered.add(node);
                completed.add(node.id());
                progressed = true;
            }
            if (!progressed) throw new IllegalStateException("Goal graph cannot be scheduled because its dependencies made no progress.");
        }
        return List.copyOf(ordered);
    }

    public record Result(
            boolean completed,
            AutomationGoalFailureCode failureCode,
            String runtimeFailureCode,
            String detail,
            List<String> executedNodeIds,
            AutomationFactGraph finalFacts,
            List<AutomationGoalTrace> traces
    ) {
        public Result {
            detail = detail == null ? "" : detail;
            runtimeFailureCode = runtimeFailureCode == null ? "" : runtimeFailureCode;
            executedNodeIds = List.copyOf(executedNodeIds == null ? List.of() : executedNodeIds);
            traces = List.copyOf(traces == null ? List.of() : traces);
        }

        static Result completed(String detail, List<String> nodes, AutomationFactGraph facts, List<AutomationGoalTrace> traces) {
            return new Result(true, null, "", detail, nodes, facts, traces);
        }

        static Result blocked(AutomationGoalFailureCode code, String detail) {
            return blocked(code, detail, List.of(), null, List.of());
        }

        static Result blocked(
                AutomationGoalFailureCode code,
                String detail,
                List<String> nodes,
                AutomationFactGraph facts,
                List<AutomationGoalTrace> traces
        ) {
            return new Result(false, code, "", detail, nodes, facts, traces);
        }

        static Result runtimeFailure(String code, String detail, List<String> nodes, List<AutomationGoalTrace> traces) {
            return new Result(false, null, code, detail, nodes, null, traces);
        }
    }
}
