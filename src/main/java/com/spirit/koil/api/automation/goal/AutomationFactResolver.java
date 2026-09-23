package com.spirit.koil.api.automation.goal;

import com.spirit.koil.api.minecraft.MinecraftKnowledgeService;

import java.util.concurrent.CompletableFuture;

/** Resolves the bounded facts required by Phase 1 goal compilation. */
public final class AutomationFactResolver {
    private AutomationFactResolver() {
    }

    public static CompletableFuture<AutomationFactGraph> resolve(AutomationGoal goal) {
        if (goal == null) {
            return CompletableFuture.completedFuture(AutomationFactGraph.builder().build());
        }
        return MinecraftKnowledgeService.plannerSnapshot(goal.targetId())
                .thenApply(snapshot -> fromSnapshot(goal, new Snapshot(
                        snapshot.clientAvailable(),
                        snapshot.playerAvailable(),
                        snapshot.itemExists(),
                        snapshot.inventoryCount(),
                        snapshot.recipeDataAvailable(),
                        snapshot.recipeKnown(),
                        snapshot.dimension()
                )));
    }

    static AutomationFactGraph fromSnapshot(AutomationGoal goal, Snapshot snapshot) {
        if (goal == null || snapshot == null) return AutomationFactGraph.builder().build();
        AutomationFactGraph.Provenance runtime = AutomationFactGraph.Provenance.AUTHORITATIVE_RUNTIME;
        AutomationFactGraph.Provenance recipeProvenance = snapshot.recipeDataAvailable()
                ? AutomationFactGraph.Provenance.SERVER_SYNCHRONIZED
                : AutomationFactGraph.Provenance.UNVERIFIED;
        return AutomationFactGraph.builder()
                .putInventoryCount(goal.targetId(), snapshot.inventoryCount(), runtime)
                .put("item.exists:" + goal.targetId(), snapshot.itemExists(), runtime,
                        "active_item_registry", goal.targetId(), "", snapshot.dimension())
                .put("player.available", snapshot.playerAvailable(), runtime,
                        "active_client_player", "player", "", snapshot.dimension())
                .put("recipe.data_available", snapshot.recipeDataAvailable(), recipeProvenance,
                        "synchronized_recipe_manager", "recipes", "", snapshot.dimension())
                .put("recipe.exists:" + goal.targetId(), snapshot.recipeKnown(), recipeProvenance,
                        "synchronized_recipe_manager", goal.targetId(), "", snapshot.dimension())
                .build();
    }

    record Snapshot(
            boolean clientAvailable,
            boolean playerAvailable,
            boolean itemExists,
            int inventoryCount,
            boolean recipeDataAvailable,
            boolean recipeKnown,
            String dimension
    ) {
        Snapshot {
            inventoryCount = Math.max(0, inventoryCount);
            dimension = dimension == null ? "" : dimension;
        }
    }
}
