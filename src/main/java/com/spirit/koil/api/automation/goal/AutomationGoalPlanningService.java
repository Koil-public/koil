package com.spirit.koil.api.automation.goal;

import com.spirit.koil.api.minecraft.MinecraftKnowledgeService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Authoritative planning facade for high-level automation goals.
 *
 * <p>Facts and the synchronized crafting dependency catalog are captured through
 * the existing Minecraft knowledge service. Callers receive one immutable plan
 * result and do not rescan recipe state independently.</p>
 */
public final class AutomationGoalPlanningService {
    private static final int CATALOG_DEPTH = 10;
    private static final int CATALOG_ITEMS = 96;

    private AutomationGoalPlanningService() {
    }

    public static CompletableFuture<Plan> plan(AutomationGoal goal) {
        if (goal == null) {
            return CompletableFuture.completedFuture(Plan.blocked(
                    null,
                    AutomationGoalFailureCode.RECIPE_UNSUPPORTED,
                    "A goal is required."
            ));
        }
        return AutomationFactResolver.resolve(goal).thenCompose(facts -> {
            AutomationGoalCompiler.Result compiled = new AutomationGoalCompiler().compile(goal, facts);
            if (compiled.executable()) {
                return CompletableFuture.completedFuture(new Plan(
                        goal,
                        facts,
                        compiled,
                        null,
                        true,
                        false,
                        0,
                        0,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        compiled.detail()
                ));
            }
            if (compiled.failureCode() != AutomationGoalFailureCode.RECIPE_UNSUPPORTED
                    && compiled.failureCode() != AutomationGoalFailureCode.RECIPE_UNKNOWN) {
                return CompletableFuture.completedFuture(new Plan(
                        goal,
                        facts,
                        compiled,
                        null,
                        false,
                        false,
                        0,
                        0,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        compiled.failureCode(),
                        compiled.detail()
                ));
            }
            return MinecraftKnowledgeService.plannerRecipeCatalogSnapshot(goal.targetId(), CATALOG_DEPTH, CATALOG_ITEMS)
                    .thenApply(snapshot -> expand(goal, facts, compiled, snapshot));
        });
    }

    private static Plan expand(
            AutomationGoal goal,
            AutomationFactGraph facts,
            AutomationGoalCompiler.Result compiled,
            MinecraftKnowledgeService.PlannerRecipeCatalogSnapshot snapshot
    ) {
        if (snapshot == null || !snapshot.available()) {
            return new Plan(
                    goal,
                    facts,
                    compiled,
                    null,
                    false,
                    false,
                    0,
                    0,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE,
                    "Synchronized planner recipe data is unavailable."
            );
        }

        Map<String, List<RecipeDependencyPlanner.Recipe>> catalog = new LinkedHashMap<>();
        snapshot.catalog().forEach((item, recipes) -> catalog.put(item, convert(recipes)));
        Map<String, RecipeDependencyPlanner.AcquisitionSource> acquisitionSources = new LinkedHashMap<>();
        snapshot.blockSources().forEach((itemId, source) -> acquisitionSources.put(
                itemId,
                RecipeDependencyPlanner.AcquisitionSource.worldBlock(
                        "blocks/core/mine_block_until_count",
                        source.blockId(),
                        source.observedCount(),
                        source.radius(),
                        2.35D,
                        Math.max(40, (int) Math.ceil(source.nearestDistance() * 8.0D)),
                        25,
                        "AUTHORITATIVE_RUNTIME"
                )
        ));
        snapshot.openContainerSources().forEach((itemId, source) -> {
            RecipeDependencyPlanner.AcquisitionSource container = RecipeDependencyPlanner.AcquisitionSource.openContainer(
                    "goal/container/take_item_from_open_container",
                    itemId,
                    source.observedCount(),
                    8,
                    1,
                    "SERVER_SYNCHRONIZED"
            );
            RecipeDependencyPlanner.AcquisitionSource existing = acquisitionSources.get(itemId);
            if (existing == null || container.score() < existing.score()) acquisitionSources.put(itemId, container);
        });
        if (catalog.getOrDefault(goal.targetId(), List.of()).isEmpty()
                && !acquisitionSources.containsKey(goal.targetId())) {
            return new Plan(
                    goal,
                    facts,
                    compiled,
                    null,
                    false,
                    snapshot.truncated(),
                    snapshot.capturedItems(),
                    snapshot.maxDepth(),
                    snapshot.inventoryCounts(),
                    Map.copyOf(catalog),
                    Map.copyOf(acquisitionSources),
                    AutomationGoalFailureCode.RECIPE_UNSUPPORTED,
                    "No synchronized crafting recipe or authoritative nearby acquisition source is available for " + goal.targetId() + "."
            );
        }
        RecipeDependencyPlanner.Result dependencyPlan = new RecipeDependencyPlanner().plan(
                goal,
                Map.copyOf(catalog),
                snapshot.inventoryCounts(),
                Map.copyOf(acquisitionSources)
        );
        AutomationGoalFailureCode failure = dependencyPlan.unresolvedItems().isEmpty()
                ? null
                : AutomationGoalFailureCode.RESOURCE_MISSING;
        String detail = failure == null
                ? "A fully resolved synchronized production dependency graph is available."
                : "The synchronized goal graph still requires unresolved resources: "
                + String.join(", ", dependencyPlan.unresolvedItems());
        if (snapshot.truncated() && failure != null) {
            detail += " The bounded recipe catalog reached its capture limit, so unresolved reasons include that planning boundary where applicable.";
        }
        return new Plan(
                goal,
                facts,
                compiled,
                dependencyPlan,
                false,
                snapshot.truncated(),
                snapshot.capturedItems(),
                snapshot.maxDepth(),
                snapshot.inventoryCounts(),
                Map.copyOf(catalog),
                Map.copyOf(acquisitionSources),
                failure,
                detail
        );
    }

    private static List<RecipeDependencyPlanner.Recipe> convert(List<MinecraftKnowledgeService.PlannerRecipe> recipes) {
        return recipes.stream().map(recipe -> {
            List<RecipeDependencyPlanner.Ingredient> ingredients = recipe.ingredients().stream()
                    .map(ingredient -> new RecipeDependencyPlanner.Ingredient(
                            ingredient.alternatives(), ingredient.count()
                    ))
                    .toList();
            if ("processing".equals(recipe.operationKind())) {
                Map<String, Object> arguments = new LinkedHashMap<>();
                arguments.put("process.kind", recipe.processKind());
                arguments.put("fuel.id", recipe.fuelId());
                arguments.put("fuel.burn_ticks", recipe.fuelBurnTicks());
                arguments.put("cook.ticks", recipe.cookTicks());
                arguments.put("processor.block_id", recipe.processorBlockId());
                arguments.put("processor.radius", recipe.processorRadius());
                arguments.put("processor.stop_distance", recipe.processorStopDistance());
                arguments.put("processor.open_required", recipe.processorOpenRequired());
                return RecipeDependencyPlanner.Recipe.processing(
                        recipe.id(), recipe.outputCount(), ingredients, Map.copyOf(arguments),
                        recipe.cookTicks() + 40, recipe.maxBatches()
                );
            }
            return new RecipeDependencyPlanner.Recipe(recipe.id(), recipe.outputCount(), ingredients);
        }).toList();
    }

    public record Plan(
            AutomationGoal goal,
            AutomationFactGraph facts,
            AutomationGoalCompiler.Result compilerResult,
            RecipeDependencyPlanner.Result dependencyPlan,
            boolean alreadySatisfied,
            boolean catalogTruncated,
            int catalogItems,
            int catalogDepth,
            Map<String, Integer> inventoryCounts,
            Map<String, List<RecipeDependencyPlanner.Recipe>> recipeCatalog,
            Map<String, RecipeDependencyPlanner.AcquisitionSource> acquisitionSources,
            AutomationGoalFailureCode failureCode,
            String detail
    ) {
        public Plan {
            detail = detail == null ? "" : detail;
            catalogItems = Math.max(0, catalogItems);
            catalogDepth = Math.max(0, catalogDepth);
            inventoryCounts = Map.copyOf(inventoryCounts == null ? Map.of() : inventoryCounts);
            recipeCatalog = Map.copyOf(recipeCatalog == null ? Map.of() : recipeCatalog);
            acquisitionSources = Map.copyOf(acquisitionSources == null ? Map.of() : acquisitionSources);
        }

        static Plan blocked(AutomationGoal goal, AutomationGoalFailureCode code, String detail) {
            return new Plan(goal, null, null, null, false, false, 0, 0, Map.of(), Map.of(), Map.of(), code, detail);
        }

        public boolean executable() {
            return !alreadySatisfied
                    && failureCode == null
                    && dependencyPlan != null
                    && dependencyPlan.unresolvedItems().isEmpty();
        }
    }
}
