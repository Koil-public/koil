package com.spirit.koil.api.automation.goal;

import com.spirit.koil.api.model.tool.AutomationGoalModelToolRegistry;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.automation.runtime.AutomationResourceLockManager;
import com.spirit.koil.api.automation.runtime.CraftingTransaction;

import java.util.List;
import java.util.Set;

/** Focused contract proof for the high-level goal graph. */
public final class AutomationGoalProof {
    private AutomationGoalProof() {
    }

    public static boolean run() {
        AutomationGoal goal = AutomationGoal.obtain("minecraft:diamond_shovel", 1);
        AutomationGoalGraph graph = AutomationGoalGraph.of(goal, List.of(
                AutomationGoalGraph.node("verify", List.of("acquire")),
                AutomationGoalGraph.node("acquire", List.of())
        ));
        AutomationFactGraph facts = AutomationFactGraph.builder()
                .putInventoryCount("minecraft:diamond_shovel", 1, AutomationFactGraph.Provenance.AUTHORITATIVE_RUNTIME)
                .build();
        AutomationFactGraph resolved = AutomationFactResolver.fromSnapshot(goal,
                new AutomationFactResolver.Snapshot(true, true, true, 1, true, true, "minecraft:overworld"));
        AutomationGoalCompiler compiler = new AutomationGoalCompiler();
        AutomationGoalCompiler.Result compiled = compiler.compile(goal, resolved);
        AutomationGoalSimulation simulation = compiler.simulate(goal, AutomationFactResolver.fromSnapshot(goal,
                new AutomationFactResolver.Snapshot(true, true, true, 0, true, false, "minecraft:overworld")));
        AutomationGoalVerifier verifier = new AutomationGoalVerifier();
        AutomationGoalVerifier.Result verified = verifier.verify(goal, resolved);
        AutomationGoalVerifier.Result unverified = verifier.verify(goal, AutomationFactResolver.fromSnapshot(goal,
                new AutomationFactResolver.Snapshot(true, true, true, 0, true, false, "minecraft:overworld")));
        RecipeDependencyPlanner.Result recipePlan = new RecipeDependencyPlanner().plan(
                goal,
                java.util.Map.of("minecraft:diamond_shovel", List.of(new RecipeDependencyPlanner.Recipe(
                        "minecraft:diamond_shovel", 1, List.of(
                        new RecipeDependencyPlanner.Ingredient(List.of("minecraft:diamond"), 1),
                        new RecipeDependencyPlanner.Ingredient(List.of("minecraft:stick"), 2)
                )))),
                java.util.Map.of("minecraft:stick", 2)
        );
        AutomationGoalGraph.Node plannedCraft = recipePlan.graph().nodes().stream()
                .filter(node -> "craft:minecraft:diamond_shovel".equals(node.id()))
                .findFirst().orElseThrow();
        RecipeDependencyPlanner.Result alternativePlan = new RecipeDependencyPlanner().plan(
                AutomationGoal.obtain("minecraft:torch", 4),
                java.util.Map.of(
                        "minecraft:torch", List.of(new RecipeDependencyPlanner.Recipe(
                                "minecraft:torch", 4, List.of(
                                new RecipeDependencyPlanner.Ingredient(List.of("minecraft:coal", "minecraft:charcoal"), 1),
                                new RecipeDependencyPlanner.Ingredient(List.of("minecraft:stick"), 1)
                        ))),
                        "minecraft:stick", List.of(new RecipeDependencyPlanner.Recipe(
                                "minecraft:sticks", 4, List.of(
                                new RecipeDependencyPlanner.Ingredient(List.of("minecraft:oak_planks", "minecraft:spruce_planks"), 1),
                                new RecipeDependencyPlanner.Ingredient(List.of("minecraft:oak_planks", "minecraft:spruce_planks"), 1)
                        ))),
                        "minecraft:spruce_planks", List.of(new RecipeDependencyPlanner.Recipe(
                                "minecraft:spruce_planks", 4, List.of(
                                new RecipeDependencyPlanner.Ingredient(List.of("minecraft:spruce_log"), 1)
                        )))
                ),
                java.util.Map.of("minecraft:charcoal", 1, "minecraft:spruce_log", 1)
        );
        boolean alternativePlannerVerified = alternativePlan.unresolvedItems().isEmpty()
                && alternativePlan.graph().nodes().stream().anyMatch(node ->
                "craft:minecraft:spruce_planks".equals(node.id()))
                && alternativePlan.graph().nodes().stream()
                .filter(node -> "craft:minecraft:stick".equals(node.id()))
                .findFirst()
                .map(node -> node.dependencies().equals(List.of("craft:minecraft:spruce_planks")))
                .orElse(false);
        RecipeDependencyPlanner.AcquisitionSource observedLogSource = new RecipeDependencyPlanner.AcquisitionSource(
                "blocks/core/mine_block_until_count", "minecraft:oak_log", 4, 20, 2.35D, 40, 25, "AUTHORITATIVE_RUNTIME");
        RecipeDependencyPlanner.Result acquisitionPlan = new RecipeDependencyPlanner().plan(
                AutomationGoal.obtain("minecraft:oak_log", 2),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of("minecraft:oak_log", observedLogSource)
        );
        boolean acquisitionPlannerVerified = acquisitionPlan.unresolvedItems().isEmpty()
                && acquisitionPlan.graph().nodes().stream().anyMatch(node ->
                "blocks/core/mine_block_until_count".equals(node.skillId())
                        && "minecraft:oak_log".equals(node.arguments().get("target.id"))
                        && Integer.valueOf(2).equals(node.arguments().get("count.value")));
        RecipeDependencyPlanner.Result boundedAcquisitionPlan = new RecipeDependencyPlanner().plan(
                AutomationGoal.obtain("minecraft:oak_log", 5),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of("minecraft:oak_log", observedLogSource)
        );
        boolean acquisitionBudgetVerified = boundedAcquisitionPlan.unresolvedItems().equals(List.of("minecraft:oak_log"))
                && boundedAcquisitionPlan.unresolvedReasons().getOrDefault("minecraft:oak_log", "").contains("exposed only 4");
        RecipeDependencyPlanner.AcquisitionSource openContainerSource = RecipeDependencyPlanner.AcquisitionSource.openContainer(
                "goal/container/take_item_from_open_container", "minecraft:stick", 8, 8, 1, "SERVER_SYNCHRONIZED");
        RecipeDependencyPlanner.Result openContainerPlan = new RecipeDependencyPlanner().plan(
                AutomationGoal.obtain("minecraft:stick", 4),
                java.util.Map.of("minecraft:stick", List.of(new RecipeDependencyPlanner.Recipe(
                        "minecraft:sticks", 4, List.of(
                        new RecipeDependencyPlanner.Ingredient(List.of("minecraft:oak_planks"), 1),
                        new RecipeDependencyPlanner.Ingredient(List.of("minecraft:oak_planks"), 1)
                )))),
                java.util.Map.of("minecraft:oak_planks", 2),
                java.util.Map.of("minecraft:stick", openContainerSource)
        );
        boolean openContainerPlannerVerified = openContainerPlan.unresolvedItems().isEmpty()
                && openContainerPlan.graph().nodes().stream().anyMatch(node ->
                "goal/container/take_item_from_open_container".equals(node.skillId())
                        && "minecraft:stick".equals(node.arguments().get("item.id"))
                        && Integer.valueOf(4).equals(node.arguments().get("count.value")));
        RecipeDependencyPlanner.Result processingPlan = new RecipeDependencyPlanner().plan(
                AutomationGoal.obtain("minecraft:iron_ingot", 2),
                java.util.Map.of("minecraft:iron_ingot", List.of(RecipeDependencyPlanner.Recipe.processing(
                        "minecraft:iron_ingot_from_smelting", 1,
                        List.of(new RecipeDependencyPlanner.Ingredient(List.of("minecraft:raw_iron"), 1)),
                        java.util.Map.of(
                                "process.kind", "minecraft:smelting",
                                "fuel.id", "minecraft:coal",
                                "fuel.burn_ticks", 1600,
                                "cook.ticks", 200,
                                "processor.block_id", "minecraft:furnace",
                                "processor.radius", 20,
                                "processor.stop_distance", 2.35D,
                                "processor.open_required", true
                        ),
                        240,
                        8
                ))),
                java.util.Map.of("minecraft:raw_iron", 2, "minecraft:coal", 1)
        );
        boolean processingPlannerVerified = processingPlan.unresolvedItems().isEmpty()
                && processingPlan.graph().nodes().stream().anyMatch(node ->
                "processing/cook/item".equals(node.skillId())
                        && "minecraft:iron_ingot_from_smelting".equals(node.arguments().get("recipe.id"))
                        && "minecraft:smelting".equals(node.arguments().get("process.kind"))
                        && "minecraft:coal".equals(node.arguments().get("fuel.id"))
                        && Integer.valueOf(2).equals(node.arguments().get("count.value"))
                        && node.dependencies().stream().anyMatch(dep -> dep.startsWith("open_processor:minecraft:furnace")))
                && processingPlan.graph().nodes().stream().anyMatch(node ->
                "goal/processor/open_nearby".equals(node.skillId())
                        && "minecraft:furnace".equals(node.arguments().get("target.id")));
        SourceResolver.Candidate source = new SourceResolver().resolve("minecraft:stick", 2,
                java.util.Map.of("minecraft:stick", 2), java.util.Map.of()).get(0);
        SourceResolver.Candidate partial = new SourceResolver().resolve("minecraft:stick", 2,
                java.util.Map.of("minecraft:stick", 1), java.util.Map.of()).get(0);
        SourceResolver.Candidate worldSource = new SourceResolver().resolve(
                "minecraft:oak_log", 2, java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of("minecraft:oak_log", observedLogSource)
        ).get(0);
        AutomationResourceLockManager locks = new AutomationResourceLockManager();
        boolean firstLock = locks.acquire("craft", Set.of("screen", "cursor", "inventory"), 90).granted();
        boolean secondLock = !locks.acquire("move", Set.of("inventory"), 60).granted();
        CraftingTransaction.Result transaction = CraftingTransaction.begin("minecraft:torch", 1, 4, true, java.util.Map.of("minecraft:torch", 0));
        boolean transactionVerified = transaction.transaction().verify(4, true, java.util.Map.of("minecraft:torch", 1)).status().equals("completed");
        return graph.readyNodeIds(Set.of()).equals(List.of("acquire"))
                && "RECIPE_UNKNOWN".equals(AutomationGoalFailureCode.RECIPE_UNKNOWN.id())
                && facts.inventoryCount("minecraft:diamond_shovel") == 1
                && facts.fact("inventory.item_count:minecraft:diamond_shovel").authoritative()
                && resolved.fact("item.exists:minecraft:diamond_shovel").authoritative()
                && compiled.executable()
                && compiled.graph().nodes().size() == 1
                && simulation.blockedCode() == AutomationGoalFailureCode.RECIPE_UNKNOWN
                && AutomationGoalModelToolRegistry.supports("automation.goal")
                && LocalModelToolCatalog.automationModeTools().stream()
                .anyMatch(tool -> "automation.goal".equals(tool.id()))
                && firstLock
                && secondLock
                && verified.passed()
                && unverified.failed()
                && recipePlan.unresolvedItems().equals(List.of("minecraft:diamond"))
                && "crafting/craft/item".equals(plannedCraft.skillId())
                && "minecraft:diamond_shovel".equals(plannedCraft.arguments().get("item.id"))
                && "minecraft:diamond_shovel".equals(plannedCraft.arguments().get("recipe.id"))
                && Integer.valueOf(1).equals(plannedCraft.arguments().get("count.value"))
                && source.source().equals("inventory")
                && partial.source().equals("inventory")
                && partial.quantity() == 1
                && "world_block".equals(worldSource.source())
                && acquisitionPlannerVerified
                && acquisitionBudgetVerified
                && openContainerPlannerVerified
                && processingPlannerVerified
                && transactionVerified
                && alternativePlannerVerified;
    }
}
