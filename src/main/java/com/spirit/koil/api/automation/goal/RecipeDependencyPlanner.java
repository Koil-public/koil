package com.spirit.koil.api.automation.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded deterministic planner for exact synchronized crafting recipes.
 *
 * <p>The planner backtracks across recipe and ingredient alternatives, consumes
 * initial inventory exactly once, and tracks crafted surplus with the graph node
 * that produced it. This prevents planned intermediate output from being
 * mistaken for pre-existing inventory and gives every dependency a truthful
 * provenance edge.</p>
 */
public final class RecipeDependencyPlanner {
    private static final int MAXIMUM_DEPTH = 12;
    private static final int MAXIMUM_NODES = 160;
    private static final int MAXIMUM_BRANCHES_PER_ITEM = 24;
    private static final int MAXIMUM_ALTERNATIVES_PER_INGREDIENT = 24;

    public Result plan(AutomationGoal goal, Map<String, List<Recipe>> recipesByOutput, Map<String, Integer> inventory) {
        return plan(goal, recipesByOutput, inventory, Map.of());
    }

    public Result plan(
            AutomationGoal goal,
            Map<String, List<Recipe>> recipesByOutput,
            Map<String, Integer> inventory,
            Map<String, AcquisitionSource> acquisitionSources
    ) {
        if (goal == null) throw new IllegalArgumentException("goal is required");
        State state = new State(recipesByOutput, inventory, acquisitionSources);
        state.require(goal.targetId(), goal.count(), 0, new LinkedHashSet<>());
        return new Result(
                AutomationGoalGraph.of(goal, state.nodes.values()),
                List.copyOf(state.unresolved),
                Map.copyOf(state.unresolvedReasons)
        );
    }

    private static final class State {
        private final Map<String, List<Recipe>> recipes;
        private final Map<String, Integer> inventoryRemaining;
        private final Map<String, AcquisitionSource> acquisitionSources;
        private final Map<String, Integer> acquisitionRemaining;
        private final Map<String, List<ProducedLot>> producedLots;
        private final Map<String, AutomationGoalGraph.Node> nodes;
        private final Set<String> unresolved;
        private final Map<String, String> unresolvedReasons;
        private int ingredientDemand;

        private State(
                Map<String, List<Recipe>> recipesByOutput,
                Map<String, Integer> inventory,
                Map<String, AcquisitionSource> acquisitionSources
        ) {
            this.recipes = recipesByOutput == null ? Map.of() : recipesByOutput;
            this.acquisitionSources = Map.copyOf(acquisitionSources == null ? Map.of() : acquisitionSources);
            this.acquisitionRemaining = new LinkedHashMap<>();
            this.acquisitionSources.forEach((item, source) ->
                    this.acquisitionRemaining.put(item, Math.max(0, source.availableCount())));
            this.inventoryRemaining = new LinkedHashMap<>();
            this.producedLots = new LinkedHashMap<>();
            this.nodes = new LinkedHashMap<>();
            this.unresolved = new LinkedHashSet<>();
            this.unresolvedReasons = new LinkedHashMap<>();
            if (inventory != null) {
                inventory.forEach((item, count) -> {
                    if (item != null && !item.isBlank()) {
                        inventoryRemaining.put(item, Math.max(0, count == null ? 0 : count));
                    }
                });
            }
        }

        private State(State source) {
            this.recipes = source.recipes;
            this.acquisitionSources = source.acquisitionSources;
            this.acquisitionRemaining = new LinkedHashMap<>(source.acquisitionRemaining);
            this.inventoryRemaining = new LinkedHashMap<>(source.inventoryRemaining);
            this.producedLots = new LinkedHashMap<>();
            source.producedLots.forEach((item, lots) -> this.producedLots.put(item, new ArrayList<>(lots)));
            this.nodes = new LinkedHashMap<>(source.nodes);
            this.unresolved = new LinkedHashSet<>(source.unresolved);
            this.unresolvedReasons = new LinkedHashMap<>(source.unresolvedReasons);
            this.ingredientDemand = source.ingredientDemand;
        }

        private void adopt(State chosen) {
            inventoryRemaining.clear();
            inventoryRemaining.putAll(chosen.inventoryRemaining);
            acquisitionRemaining.clear();
            acquisitionRemaining.putAll(chosen.acquisitionRemaining);
            producedLots.clear();
            chosen.producedLots.forEach((item, lots) -> producedLots.put(item, new ArrayList<>(lots)));
            nodes.clear();
            nodes.putAll(chosen.nodes);
            unresolved.clear();
            unresolved.addAll(chosen.unresolved);
            unresolvedReasons.clear();
            unresolvedReasons.putAll(chosen.unresolvedReasons);
            ingredientDemand = chosen.ingredientDemand;
        }

        private int availableCount(String item) {
            int count = Math.max(0, inventoryRemaining.getOrDefault(item, 0));
            for (ProducedLot lot : producedLots.getOrDefault(item, List.of())) count += Math.max(0, lot.quantity());
            return count;
        }

        private String require(String item, int requestedCount, int depth, Set<String> ancestry) {
            int count = Math.max(1, requestedCount);
            if (item == null || item.isBlank()) {
                return unresolved("unknown:empty_item", count, "Recipe ingredient did not expose an exact item alternative.");
            }
            if (nodes.size() >= MAXIMUM_NODES) {
                return unresolved(item, count, "Planner node budget was exhausted before this dependency could be resolved.");
            }

            Consumption consumed = consumeAvailable(item, count);
            if (consumed.quantity() >= count) {
                return availabilityDependency(item, count, consumed.dependencies());
            }

            int missing = count - consumed.quantity();
            List<String> prefixDependencies = new ArrayList<>(consumed.dependencies());
            if (depth >= MAXIMUM_DEPTH) {
                prefixDependencies.add(unresolved(item, missing, "Planner dependency depth exceeded " + MAXIMUM_DEPTH + "."));
                return availabilityDependency(item, count, prefixDependencies);
            }
            if (ancestry.contains(item)) {
                prefixDependencies.add(unresolved(item, missing, "Recipe dependency cycle reached " + item + "."));
                return availabilityDependency(item, count, prefixDependencies);
            }

            List<Recipe> candidates = recipes.getOrDefault(item, List.of()).stream()
                    .filter(candidate -> candidate != null && candidate.outputCount() > 0)
                    .sorted(Comparator.comparing(Recipe::id))
                    .limit(MAXIMUM_BRANCHES_PER_ITEM)
                    .toList();
            if (candidates.isEmpty()) {
                AcquisitionSource source = acquisitionSources.get(item);
                if (source != null) {
                    prefixDependencies.addAll(acquisitionDependencies(item, missing, source));
                } else {
                    prefixDependencies.add(unresolved(item, missing, "No synchronized crafting recipe or authoritative acquisition source is available for " + item + "."));
                }
                return availabilityDependency(item, count, prefixDependencies);
            }

            Set<String> nextAncestry = new LinkedHashSet<>(ancestry);
            nextAncestry.add(item);
            Trial best = null;
            for (Recipe recipe : candidates) {
                State trial = new State(this);
                List<String> dependencies = new ArrayList<>(prefixDependencies);
                int batches = ceilDiv(missing, recipe.outputCount());
                if (batches > recipe.maxBatches()) continue;
                boolean branchValid = true;

                for (Ingredient ingredient : recipe.ingredients()) {
                    int demand = Math.max(1, ingredient.count()) * batches;
                    AlternativeTrial alternative = trial.resolveIngredientAlternative(
                            ingredient, demand, depth + 1, nextAncestry
                    );
                    if (alternative == null) {
                        branchValid = false;
                        break;
                    }
                    trial.adopt(alternative.state());
                    dependencies.add(alternative.dependencyNode());
                    trial.ingredientDemand += demand;
                    if (trial.nodes.size() >= MAXIMUM_NODES) {
                        branchValid = false;
                        break;
                    }
                }
                if (!branchValid) continue;

                if ("processing".equals(recipe.operationKind())) {
                    String fuelId = String.valueOf(recipe.executionArguments().getOrDefault("fuel.id", "")).strip();
                    int cookTicks = positiveArgument(recipe.executionArguments().get("cook.ticks"), 200);
                    int fuelBurnTicks = positiveArgument(recipe.executionArguments().get("fuel.burn_ticks"), 0);
                    if (fuelId.isBlank() || fuelBurnTicks <= 0) continue;
                    int fuelNeeded = ceilDivLong((long) batches * cookTicks, fuelBurnTicks);
                    String fuelDependency = trial.require(fuelId, fuelNeeded, depth + 1, nextAncestry);
                    dependencies.add(fuelDependency);
                    trial.ingredientDemand += fuelNeeded;
                }

                dependencies = distinctDependencies(dependencies);
                if ("processing".equals(recipe.operationKind())
                        && booleanArgument(recipe.executionArguments().get("processor.open_required"), false)) {
                    String blockId = String.valueOf(recipe.executionArguments().getOrDefault("processor.block_id", "")).strip();
                    if (blockId.isBlank()) continue;
                    String setupNode = trial.uniqueNodeId("open_processor:" + blockId);
                    trial.nodes.put(setupNode, processorSetupNode(
                            setupNode, blockId, recipe.executionArguments(), dependencies
                    ));
                    dependencies = List.of(setupNode);
                }
                String operationPrefix = "processing".equals(recipe.operationKind()) ? "process:" : "craft:";
                String productionNode = trial.uniqueNodeId(operationPrefix + item);
                trial.nodes.put(productionNode, productionNode(
                        productionNode, item, missing, recipe, dependencies
                ));
                int produced = batches * recipe.outputCount();
                int surplus = Math.max(0, produced - missing);
                if (surplus > 0) trial.addProduced(item, surplus, productionNode);

                Trial candidate = new Trial(
                        trial,
                        productionNode,
                        trial.unresolved.size() - unresolved.size(),
                        trial.nodes.size() - nodes.size(),
                        trial.ingredientDemand - ingredientDemand,
                        Math.max(0, trial.planningCost() - planningCost()),
                        recipe.id()
                );
                if (best == null || candidate.compareTo(best) < 0) best = candidate;
                if (best != null && best.newUnresolved() == 0 && best.newNodes() <= 2) break;
            }

            AcquisitionSource observedSource = acquisitionSources.get(item);
            if (observedSource != null) {
                State sourceTrial = new State(this);
                List<String> sourceDependencies = new ArrayList<>(prefixDependencies);
                sourceDependencies.addAll(sourceTrial.acquisitionDependencies(item, missing, observedSource));
                String availableNode = sourceTrial.availabilityDependency(item, count, sourceDependencies);
                Trial sourceCandidate = new Trial(
                        sourceTrial,
                        availableNode,
                        sourceTrial.unresolved.size() - unresolved.size(),
                        sourceTrial.nodes.size() - nodes.size(),
                        sourceTrial.ingredientDemand - ingredientDemand,
                        observedSource.score(),
                        "~acquire:" + observedSource.targetId()
                );
                if (best == null || sourceCandidate.compareTo(best) < 0) best = sourceCandidate;
            }

            if (best == null) {
                AcquisitionSource source = acquisitionSources.get(item);
                if (source != null) {
                    prefixDependencies.addAll(acquisitionDependencies(item, missing, source));
                } else {
                    prefixDependencies.add(unresolved(item, missing, "No bounded recipe branch or authoritative acquisition source could be constructed for " + item + "."));
                }
                return availabilityDependency(item, count, prefixDependencies);
            }
            adopt(best.state());
            List<String> complete = new ArrayList<>(prefixDependencies);
            complete.add(best.resultNode());
            return availabilityDependency(item, count, complete);
        }

        private Consumption consumeAvailable(String item, int requested) {
            int remaining = Math.max(1, requested);
            int consumed = 0;
            List<String> dependencies = new ArrayList<>();

            int inventory = Math.max(0, inventoryRemaining.getOrDefault(item, 0));
            if (inventory > 0 && remaining > 0) {
                int take = Math.min(inventory, remaining);
                inventoryRemaining.put(item, inventory - take);
                consumed += take;
                remaining -= take;
                String inventoryNode = uniqueNodeId("inventory:" + item);
                nodes.put(inventoryNode, node(
                        inventoryNode,
                        "inventory/verify_count",
                        item,
                        take,
                        List.of(),
                        Set.of("inventory"),
                        "inventory count available"
                ));
                dependencies.add(inventoryNode);
            }

            List<ProducedLot> lots = producedLots.get(item);
            if (lots != null && remaining > 0) {
                List<ProducedLot> updated = new ArrayList<>();
                for (ProducedLot lot : lots) {
                    if (remaining <= 0) {
                        updated.add(lot);
                        continue;
                    }
                    int take = Math.min(lot.quantity(), remaining);
                    if (take > 0) {
                        consumed += take;
                        remaining -= take;
                        dependencies.add(lot.producerNode());
                    }
                    int left = lot.quantity() - take;
                    if (left > 0) updated.add(new ProducedLot(left, lot.producerNode()));
                }
                if (updated.isEmpty()) producedLots.remove(item);
                else producedLots.put(item, updated);
            }

            return new Consumption(consumed, distinctDependencies(dependencies));
        }

        private void addProduced(String item, int quantity, String producerNode) {
            if (quantity <= 0 || producerNode == null || producerNode.isBlank()) return;
            producedLots.computeIfAbsent(item, ignored -> new ArrayList<>())
                    .add(new ProducedLot(quantity, producerNode));
        }

        private String availabilityDependency(String item, int count, List<String> rawDependencies) {
            List<String> dependencies = distinctDependencies(rawDependencies);
            if (dependencies.isEmpty()) {
                return unresolved(item, count, "No verified inventory, crafting, or acquisition dependency produced the requested item.");
            }
            if (dependencies.size() == 1) return dependencies.get(0);
            String join = uniqueNodeId("available:" + item);
            nodes.put(join, new AutomationGoalGraph.Node(
                    join,
                    "",
                    Map.of("item.id", item, "count.value", Math.max(1, count)),
                    dependencies,
                    List.of(),
                    Set.of("inventory"),
                    "planned item quantity available",
                    AutomationGoalGraph.Verification.none(),
                    0,
                    0,
                    0,
                    "replan",
                    ""
            ));
            return join;
        }

        private AlternativeTrial resolveIngredientAlternative(
                Ingredient ingredient,
                int demand,
                int depth,
                Set<String> ancestry
        ) {
            List<String> alternatives = ingredient.alternatives().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .sorted(Comparator
                            .<String>comparingInt(this::availableCount).reversed()
                            .thenComparing(Comparator.naturalOrder()))
                    .limit(MAXIMUM_ALTERNATIVES_PER_INGREDIENT)
                    .toList();
            if (alternatives.isEmpty()) return null;

            AlternativeTrial best = null;
            for (String alternative : alternatives) {
                State trial = new State(this);
                int unresolvedBefore = trial.unresolved.size();
                int nodesBefore = trial.nodes.size();
                int demandBefore = trial.ingredientDemand;
                int availableBefore = trial.availableCount(alternative);
                String dependency = trial.require(alternative, demand, depth, ancestry);
                AlternativeTrial candidate = new AlternativeTrial(
                        trial,
                        dependency,
                        trial.unresolved.size() - unresolvedBefore,
                        trial.nodes.size() - nodesBefore,
                        trial.ingredientDemand - demandBefore,
                        availableBefore >= demand ? 0 : 1,
                        alternative
                );
                if (best == null || candidate.compareTo(best) < 0) best = candidate;
                if (best.newUnresolved() == 0 && best.requiredCrafting() == 0) break;
            }
            return best;
        }

        private List<String> acquisitionDependencies(String item, int requested, AcquisitionSource source) {
            int remaining = Math.max(1, requested);
            int available = Math.max(0, acquisitionRemaining.getOrDefault(item, source.availableCount()));
            List<String> dependencies = new ArrayList<>();
            int take = Math.min(available, remaining);
            if (take > 0) {
                dependencies.add(acquisition(item, take, source));
                acquisitionRemaining.put(item, available - take);
                remaining -= take;
            }
            if (remaining > 0) {
                dependencies.add(unresolved(
                        item,
                        remaining,
                        "The authoritative " + source.sourceKind() + " source exposed only " + available
                                + " usable " + item + " unit(s), below the remaining requested quantity."
                ));
            }
            return List.copyOf(dependencies);
        }

        private String acquisition(String item, int count, AcquisitionSource source) {
            String nodeId = uniqueNodeId("acquire:" + item);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("item.id", item);
            arguments.put("count.value", Math.max(1, count));
            arguments.put("state.counter", 0);

            Set<String> locks;
            String observation;
            if ("open_container".equals(source.sourceKind())) {
                locks = Set.of("screen", "cursor", "inventory");
                observation = "synchronized open-container item count decreased and player inventory count increased";
            } else {
                arguments.put("target.id", source.targetId());
                arguments.put("target.selector", "nearest");
                arguments.put("radius", source.radius());
                arguments.put("stop.distance", source.stopDistance());
                locks = Set.of("movement", "look", "input", "world", "inventory");
                observation = "authoritative nearby block source mined and player inventory count increased";
            }

            nodes.put(nodeId, new AutomationGoalGraph.Node(
                    nodeId,
                    source.skillId(),
                    Map.copyOf(arguments),
                    List.of(),
                    List.of(),
                    locks,
                    observation,
                    AutomationGoalGraph.Verification.none(),
                    source.estimatedTicks(),
                    source.risk(),
                    0,
                    "replan",
                    ""
            ));
            return nodeId;
        }

        private String unresolved(String item, int count, String reason) {
            String normalized = item == null || item.isBlank() ? "unknown:unresolved" : item;
            String nodeId = uniqueNodeId("acquire:" + normalized);
            unresolved.add(normalized);
            unresolvedReasons.putIfAbsent(normalized, reason == null ? "No verified source is available." : reason);
            nodes.put(nodeId, node(
                    nodeId,
                    "resources/acquire/item",
                    normalized,
                    Math.max(1, count),
                    List.of(),
                    Set.of(),
                    "resource acquired"
            ));
            return nodeId;
        }

        private int planningCost() {
            long total = 0L;
            for (AutomationGoalGraph.Node node : nodes.values()) {
                total += Math.max(0, node.estimatedTicks());
                total += (long) Math.max(0, node.risk()) * 12L;
                if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
            return (int) total;
        }

        private String uniqueNodeId(String base) {
            if (!nodes.containsKey(base)) return base;
            int suffix = 2;
            while (nodes.containsKey(base + "#" + suffix)) suffix++;
            return base + "#" + suffix;
        }
    }

    private static List<String> distinctDependencies(List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) return List.of();
        return List.copyOf(new LinkedHashSet<>(dependencies));
    }

    private record ProducedLot(int quantity, String producerNode) {
        private ProducedLot {
            quantity = Math.max(0, quantity);
            producerNode = producerNode == null ? "" : producerNode;
        }
    }

    private record Consumption(int quantity, List<String> dependencies) {
        private Consumption {
            quantity = Math.max(0, quantity);
            dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        }
    }

    private record Trial(
            State state,
            String resultNode,
            int newUnresolved,
            int newNodes,
            int demand,
            int estimatedCost,
            String recipeId
    ) implements Comparable<Trial> {
        @Override
        public int compareTo(Trial other) {
            int compare = Integer.compare(newUnresolved, other.newUnresolved);
            if (compare != 0) return compare;
            compare = Integer.compare(estimatedCost, other.estimatedCost);
            if (compare != 0) return compare;
            compare = Integer.compare(newNodes, other.newNodes);
            if (compare != 0) return compare;
            compare = Integer.compare(demand, other.demand);
            if (compare != 0) return compare;
            return recipeId.compareTo(other.recipeId);
        }
    }

    private record AlternativeTrial(
            State state,
            String dependencyNode,
            int newUnresolved,
            int newNodes,
            int demand,
            int requiredCrafting,
            String itemId
    ) implements Comparable<AlternativeTrial> {
        @Override
        public int compareTo(AlternativeTrial other) {
            int compare = Integer.compare(newUnresolved, other.newUnresolved);
            if (compare != 0) return compare;
            compare = Integer.compare(requiredCrafting, other.requiredCrafting);
            if (compare != 0) return compare;
            compare = Integer.compare(newNodes, other.newNodes);
            if (compare != 0) return compare;
            compare = Integer.compare(demand, other.demand);
            if (compare != 0) return compare;
            return itemId.compareTo(other.itemId);
        }
    }

    private static int ceilDiv(int numerator, int denominator) {
        int safeDenominator = Math.max(1, denominator);
        return Math.max(1, (numerator + safeDenominator - 1) / safeDenominator);
    }

    private static int ceilDivLong(long numerator, int denominator) {
        long safeNumerator = Math.max(1L, numerator);
        long safeDenominator = Math.max(1, denominator);
        long value = (safeNumerator + safeDenominator - 1L) / safeDenominator;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, value));
    }

    private static int positiveArgument(Object value, int fallback) {
        if (value == null) return Math.max(0, fallback);
        try {
            return Math.max(0, Integer.parseInt(value.toString()));
        } catch (NumberFormatException ignored) {
            return Math.max(0, fallback);
        }
    }

    private static boolean booleanArgument(Object value, boolean fallback) {
        if (value == null) return fallback;
        String normalized = value.toString().strip().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        return fallback;
    }

    private static AutomationGoalGraph.Node node(
            String id,
            String skill,
            String item,
            int count,
            List<String> dependencies,
            Set<String> locks,
            String observation
    ) {
        return new AutomationGoalGraph.Node(
                id,
                skill,
                Map.of("item.id", item, "count.value", Math.max(1, count)),
                dependencies,
                List.of(),
                locks,
                observation,
                AutomationGoalGraph.Verification.none(),
                0,
                0,
                0,
                "replan",
                ""
        );
    }

    private static AutomationGoalGraph.Node processorSetupNode(
            String id, String blockId, Map<String, Object> executionArguments, List<String> dependencies
    ) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target.id", blockId);
        arguments.put("target.selector", "nearest");
        arguments.put("radius", positiveArgument(executionArguments.get("processor.radius"), 20));
        Object stopDistance = executionArguments.get("processor.stop_distance");
        arguments.put("stop.distance", stopDistance == null ? 2.35D : stopDistance);
        return new AutomationGoalGraph.Node(
                id,
                "goal/processor/open_nearby",
                Map.copyOf(arguments),
                distinctDependencies(dependencies),
                List.of(),
                Set.of("movement", "look", "input", "world", "screen"),
                "observed nearby processor opened into a synchronized furnace-style handler",
                AutomationGoalGraph.Verification.none(),
                120,
                4,
                0,
                "replan",
                ""
        );
    }

    private static AutomationGoalGraph.Node productionNode(
            String id,
            String item,
            int count,
            Recipe recipe,
            List<String> dependencies
    ) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("item.id", item);
        arguments.put("count.value", Math.max(1, count));
        if (recipe.id() != null && !recipe.id().isBlank()) arguments.put("recipe.id", recipe.id());
        arguments.putAll(recipe.executionArguments());
        boolean processing = "processing".equals(recipe.operationKind());
        return new AutomationGoalGraph.Node(
                id,
                recipe.skillId(),
                Map.copyOf(arguments),
                dependencies,
                List.of(),
                Set.of("inventory", "screen", "cursor"),
                processing
                        ? "synchronized processor output increased player inventory count"
                        : "crafted item count increased",
                AutomationGoalGraph.Verification.none(),
                recipe.estimatedTicks(),
                recipe.risk(),
                0,
                "replan",
                ""
        );
    }

    public record AcquisitionSource(
            String skillId,
            String sourceKind,
            String targetId,
            int availableCount,
            int radius,
            double stopDistance,
            int estimatedTicks,
            int risk,
            int score,
            String confidence
    ) {
        public AcquisitionSource {
            skillId = skillId == null ? "" : skillId;
            sourceKind = sourceKind == null || sourceKind.isBlank() ? "world_block" : sourceKind;
            targetId = targetId == null ? "" : targetId;
            availableCount = Math.max(0, availableCount);
            radius = Math.max(1, radius);
            stopDistance = Math.max(0.5D, stopDistance);
            estimatedTicks = Math.max(0, estimatedTicks);
            risk = Math.max(0, risk);
            score = Math.max(0, score);
            confidence = confidence == null ? "UNVERIFIED" : confidence;
        }

        public AcquisitionSource(
                String skillId, String targetId, int availableCount, int radius, double stopDistance,
                int estimatedTicks, int risk, String confidence
        ) {
            this(
                    skillId, "world_block", targetId, availableCount, radius, stopDistance,
                    estimatedTicks, risk, Math.max(1, estimatedTicks) + Math.max(0, risk) * 12, confidence
            );
        }

        public static AcquisitionSource worldBlock(
                String skillId, String targetId, int availableCount, int radius, double stopDistance,
                int estimatedTicks, int risk, String confidence
        ) {
            int score = Math.max(1, estimatedTicks) + Math.max(0, risk) * 12;
            return new AcquisitionSource(
                    skillId, "world_block", targetId, availableCount, radius, stopDistance,
                    estimatedTicks, risk, score, confidence
            );
        }

        public static AcquisitionSource openContainer(
                String skillId, String itemId, int availableCount, int estimatedTicks, int risk, String confidence
        ) {
            int score = Math.max(1, estimatedTicks) + Math.max(0, risk) * 8;
            return new AcquisitionSource(
                    skillId, "open_container", itemId, availableCount, 1, 0.5D,
                    estimatedTicks, risk, score, confidence
            );
        }
    }

    public record Recipe(
            String id,
            int outputCount,
            List<Ingredient> ingredients,
            String skillId,
            String operationKind,
            Map<String, Object> executionArguments,
            int estimatedTicks,
            int risk,
            int maxBatches
    ) {
        public Recipe {
            id = id == null ? "" : id;
            outputCount = Math.max(1, outputCount);
            ingredients = List.copyOf(ingredients == null ? List.of() : ingredients);
            skillId = skillId == null || skillId.isBlank() ? "crafting/craft/item" : skillId;
            operationKind = operationKind == null || operationKind.isBlank() ? "crafting" : operationKind;
            executionArguments = Map.copyOf(executionArguments == null ? Map.of() : executionArguments);
            estimatedTicks = Math.max(1, estimatedTicks);
            risk = Math.max(0, risk);
            maxBatches = Math.max(1, maxBatches);
        }

        public Recipe(String id, int outputCount, List<Ingredient> ingredients) {
            this(id, outputCount, ingredients, "crafting/craft/item", "crafting", Map.of(), 20, 1, Integer.MAX_VALUE);
        }

        public static Recipe processing(
                String id, int outputCount, List<Ingredient> ingredients,
                Map<String, Object> executionArguments, int estimatedTicks, int maxBatches
        ) {
            return new Recipe(
                    id, outputCount, ingredients, "processing/cook/item", "processing",
                    executionArguments, Math.max(20, estimatedTicks), 2, maxBatches
            );
        }
    }

    public record Ingredient(List<String> alternatives, int count) {
        public Ingredient {
            alternatives = List.copyOf(alternatives == null ? List.of() : alternatives);
            count = Math.max(1, count);
        }
    }

    public record Result(
            AutomationGoalGraph graph,
            List<String> unresolvedItems,
            Map<String, String> unresolvedReasons
    ) {
        public Result {
            unresolvedItems = List.copyOf(unresolvedItems == null ? List.of() : unresolvedItems);
            unresolvedReasons = Map.copyOf(unresolvedReasons == null ? Map.of() : unresolvedReasons);
        }

        public Result(AutomationGoalGraph graph, List<String> unresolvedItems) {
            this(graph, unresolvedItems, Map.of());
        }
    }
}
