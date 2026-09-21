package com.spirit.koil.api.automation.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Scores only sources Koil can currently prove from supplied runtime observations. */
public final class SourceResolver {
    public List<Candidate> resolve(
            String itemId,
            int count,
            Map<String, Integer> inventory,
            Map<String, List<RecipeDependencyPlanner.Recipe>> recipes
    ) {
        return resolve(itemId, count, inventory, recipes, Map.of());
    }

    public List<Candidate> resolve(
            String itemId,
            int count,
            Map<String, Integer> inventory,
            Map<String, List<RecipeDependencyPlanner.Recipe>> recipes,
            Map<String, RecipeDependencyPlanner.AcquisitionSource> acquisitionSources
    ) {
        int requested = Math.max(1, count);
        int held = inventory == null ? 0 : Math.max(0, inventory.getOrDefault(itemId, 0));
        List<Candidate> candidates = new ArrayList<>();
        if (held > 0) {
            candidates.add(new Candidate(
                    "inventory", itemId, Math.min(held, requested), 0, 0,
                    "AUTHORITATIVE_RUNTIME", List.of()
            ));
        }
        if (recipes != null && !recipes.getOrDefault(itemId, List.of()).isEmpty()) {
            RecipeDependencyPlanner.Recipe bestCraft = null;
            RecipeDependencyPlanner.Recipe bestProcessing = null;
            for (RecipeDependencyPlanner.Recipe recipe : recipes.getOrDefault(itemId, List.of())) {
                if (recipe == null) continue;
                if ("processing".equals(recipe.operationKind())) {
                    if (bestProcessing == null || recipe.estimatedTicks() < bestProcessing.estimatedTicks()) bestProcessing = recipe;
                } else {
                    if (bestCraft == null || recipe.estimatedTicks() < bestCraft.estimatedTicks()) bestCraft = recipe;
                }
            }
            if (bestCraft != null) {
                candidates.add(new Candidate(
                        "craft", itemId, Math.max(1, requested - held), bestCraft.estimatedTicks(), bestCraft.risk(),
                        "SERVER_SYNCHRONIZED", List.of("recipe:" + bestCraft.id())
                ));
            }
            if (bestProcessing != null) {
                candidates.add(new Candidate(
                        "processing", itemId, Math.max(1, requested - held), bestProcessing.estimatedTicks(), bestProcessing.risk(),
                        "SERVER_SYNCHRONIZED", List.of("recipe:" + bestProcessing.id(), "open_processor")
                ));
            }
        }
        RecipeDependencyPlanner.AcquisitionSource acquisition = acquisitionSources == null
                ? null
                : acquisitionSources.get(itemId);
        if (acquisition != null) {
            candidates.add(new Candidate(
                    acquisition.sourceKind(), itemId, Math.max(1, Math.min(Math.max(1, requested - held), acquisition.availableCount())),
                    acquisition.estimatedTicks(), acquisition.risk(), acquisition.confidence(),
                    List.of("observed_block:" + acquisition.targetId())
            ));
        }
        if (candidates.isEmpty()) {
            candidates.add(new Candidate(
                    "unresolved", itemId, requested, Integer.MAX_VALUE, 100,
                    "UNVERIFIED", List.of("source_observation")
            ));
        }
        candidates.sort(Comparator.comparingInt(Candidate::estimatedTicks).thenComparingInt(Candidate::risk));
        return List.copyOf(candidates);
    }

    public record Candidate(
            String source,
            String itemId,
            int quantity,
            int estimatedTicks,
            int risk,
            String confidence,
            List<String> prerequisites
    ) {
        public Candidate {
            source = source == null ? "unresolved" : source;
            itemId = itemId == null ? "" : itemId;
            quantity = Math.max(1, quantity);
            estimatedTicks = Math.max(0, estimatedTicks);
            risk = Math.max(0, risk);
            confidence = confidence == null ? "UNVERIFIED" : confidence;
            prerequisites = List.copyOf(prerequisites == null ? List.of() : prerequisites);
        }
    }
}
