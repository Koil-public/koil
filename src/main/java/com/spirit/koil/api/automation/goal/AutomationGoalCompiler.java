package com.spirit.koil.api.automation.goal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles exact-item obtain goals and defers known recipes to bounded dependency expansion. */
public final class AutomationGoalCompiler {
    public Result compile(AutomationGoal goal, AutomationFactGraph facts) {
        if (goal == null || facts == null) {
            return Result.blocked(AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE, "Goal facts are unavailable.");
        }
        if (!booleanFact(facts, "item.exists:" + goal.targetId())) {
            return Result.blocked(AutomationGoalFailureCode.UNKNOWN_ITEM, "The active item registry does not contain " + goal.targetId() + ".");
        }
        if (!booleanFact(facts, "player.available")) {
            return Result.blocked(AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE, "A loaded player and world are required.");
        }
        if (facts.inventoryCount(goal.targetId()) >= goal.count()) {
            AutomationGoalGraph.Node verify = new AutomationGoalGraph.Node(
                    "verify_inventory",
                    "",
                    Map.of("item.id", goal.targetId(), "count.value", goal.count()),
                    List.of(),
                    List.of(AutomationFactGraph.inventoryKey(goal.targetId())),
                    Set.of("inventory"),
                    "inventory count >= requested count",
                    new AutomationGoalGraph.Verification(AutomationFactGraph.inventoryKey(goal.targetId()), "gte", goal.count()),
                    1,
                    0,
                    0,
                    "fail",
                    ""
            );
            return Result.compiled(AutomationGoalGraph.of(goal, List.of(verify)));
        }
        if (!booleanFact(facts, "recipe.data_available")) {
            return Result.blocked(AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE,
                    "The active connection does not expose synchronized recipe data.");
        }
        if (!booleanFact(facts, "recipe.exists:" + goal.targetId())) {
            return Result.blocked(AutomationGoalFailureCode.RECIPE_UNKNOWN,
                    "No synchronized recipe is known for " + goal.targetId() + ".");
        }
        return Result.blocked(AutomationGoalFailureCode.RECIPE_UNSUPPORTED,
                "A synchronized recipe exists for " + goal.targetId() + "; bounded dependency expansion is required before execution.");
    }

    public AutomationGoalSimulation simulate(AutomationGoal goal, AutomationFactGraph facts) {
        Result result = compile(goal, facts);
        return new AutomationGoalSimulation(goal, result.graph(), result.failureCode(), result.detail());
    }

    private static boolean booleanFact(AutomationFactGraph facts, String key) {
        return facts.has(key) && Boolean.TRUE.equals(facts.fact(key).value());
    }

    public record Result(AutomationGoalGraph graph, AutomationGoalFailureCode failureCode, String detail) {
        public Result {
            detail = detail == null ? "" : detail;
        }

        public static Result compiled(AutomationGoalGraph graph) {
            return new Result(graph, null, "Goal graph compiled from authoritative facts.");
        }

        public static Result blocked(AutomationGoalFailureCode code, String detail) {
            return new Result(null, code, detail);
        }

        public boolean executable() {
            return graph != null && failureCode == null;
        }
    }
}
