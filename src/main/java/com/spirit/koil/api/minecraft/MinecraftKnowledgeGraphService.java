package com.spirit.koil.api.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.util.text.FuzzyTextMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Semantic relationship layer over {@link MinecraftKnowledgeService}.
 *
 * <p>This class deliberately does not read Minecraft registries, recipes,
 * advancements, resources, player state, or Fabric metadata directly. The
 * authoritative extraction layer is {@code MinecraftKnowledgeService}; this
 * class consumes its normalized immutable facts and adds semantic resolution,
 * ranking, graph traversal, reverse relationships, recursive crafting
 * dependencies, and bounded dossiers.</p>
 *
 * <p>That separation prevents recipe, advancement, registry, item, block, tag,
 * and mod semantics from drifting between two independent Minecraft readers.
 * If the game-facing representation changes, the adapter is repaired once in
 * {@code MinecraftKnowledgeService}; the graph remains a consumer of facts.</p>
 */
public final class MinecraftKnowledgeGraphService {
    private static final int MAXIMUM_RESULTS = 32;
    private static final int MAXIMUM_INTERNAL_CANDIDATES = 160;
    private static final int MAXIMUM_RECIPES_PER_SECTION = 16;
    private static final int MAXIMUM_ADVANCEMENTS_PER_SECTION = 16;
    private static final int MAXIMUM_ENCHANTMENTS_PER_ITEM = 32;
    private static final int MAXIMUM_GRAPH_DEPTH = 4;
    private static final int MAXIMUM_CRAFT_TREE_NODES = 96;

    private static final Set<String> GRAPH_QUERIES = Set.of(
            "catalog",
            "capabilities",
            "search",
            "resolve",
            "explain",
            "relations",
            "uses",
            "sources",
            "crafting_path",
            "recipe_graph",
            "advancement_graph",
            "investigate",
            "expand",
            "references",
            "evidence",
            "recipe",
            "advancement"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "about", "all", "anything", "can", "could",
            "do", "does", "for", "from", "find", "get", "give", "how", "i",
            "in", "into", "is", "it", "make", "me", "minecraft", "need", "of",
            "on", "or", "part", "parts", "please", "show", "tell", "that", "the",
            "this", "to", "use", "used", "uses", "using", "what", "where",
            "which", "with", "would", "recipe", "recipes", "craft", "crafting",
            "advancement", "advancements", "knowledge", "info", "information"
    );

    private MinecraftKnowledgeGraphService() {
    }

    public static boolean supports(String query) {
        return GRAPH_QUERIES.contains(normalize(query));
    }

    /**
     * Conservative synchronous selector hint. Direct registry inspection lives
     * in the authoritative fact layer.
     */
    public static boolean likelyMinecraftSubject(String prompt) {
        return MinecraftKnowledgeService.likelyMinecraftSubjectFromFacts(prompt);
    }

    public static CompletableFuture<Result> query(
            String query,
            String value,
            String registryHint,
            int requestedLimit,
            int requestedDepth,
            List<String> requestedFields
    ) {
        String kind = normalize(query);
        String subject = value == null ? "" : value.strip();
        String hint = canonicalKind(registryHint);
        int limit = Math.max(1, Math.min(MAXIMUM_RESULTS, requestedLimit));
        int depth = Math.max(0, Math.min(MAXIMUM_GRAPH_DEPTH, requestedDepth));
        List<String> fields = normalizeFields(requestedFields);

        return MinecraftKnowledgeService.withKnowledgeView(view -> {
            try {
                return switch (kind) {
                    case "catalog", "capabilities" -> catalog(view);
                    case "search" -> search(view, subject, hint, limit);
                    case "resolve" -> resolve(view, subject, hint, limit);
                    case "explain", "relations" -> explain(view, subject, hint, limit, depth, fields);
                    case "uses" -> usageGraph(view, subject, hint, limit, false);
                    case "sources" -> usageGraph(view, subject, hint, limit, true);
                    case "crafting_path" -> craftingPath(view, subject, hint, limit, depth);
                    case "recipe_graph", "recipe" -> recipeSearch(view, subject, limit);
                    case "advancement_graph", "advancement" -> advancementSearch(view, subject, limit, true);
                    case "investigate" -> investigate(view, subject, hint, limit, depth, fields, true);
                    case "expand" -> investigate(view, subject, hint, limit, Math.max(0, depth - 1), fields, false);
                    case "references", "evidence" -> evidenceReferences(view, subject, hint, limit);
                    default -> unavailable("Unsupported semantic Minecraft knowledge query: " + kind);
                };
            } catch (RuntimeException failure) {
                return unavailable(
                        "Minecraft semantic knowledge failed: "
                                + (failure.getMessage() == null
                                ? failure.getClass().getSimpleName()
                                : failure.getMessage())
                );
            }
        }).handle((result, failure) -> {
            if (failure == null) {
                return result;
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            return unavailable(
                    "Minecraft knowledge fact layer failed: "
                            + (cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage())
            );
        });
    }

    private static Result catalog(MinecraftKnowledgeService.KnowledgeView view) {
        JsonObject output = baseOutput("catalog", "");
        output.add("coverage", view.coverage());

        JsonArray operations = new JsonArray();
        for (String operation : List.of(
                "search",
                "resolve",
                "explain",
                "relations",
                "uses",
                "sources",
                "crafting_path",
                "recipe_graph",
                "advancement_graph",
                "investigate",
                "expand",
                "references",
                "evidence",
                "player",
                "target",
                "registry",
                "tag",
                "resource",
                "mod",
                "item",
                "block",
                "entity",
                "effect",
                "enchantment",
                "biome",
                "dimension",
                "structure",
                "nbt",
                "command"
        )) {
            operations.add(operation);
        }
        output.add("operations", operations);

        JsonArray relationships = new JsonArray();
        for (String relationship : List.of(
                "item_to_block_form",
                "block_to_item_form",
                "recipe_output_to_ingredients",
                "ingredient_to_recipes_using_it",
                "item_to_recipes_producing_it",
                "recipe_ingredient_slot_to_shared_tags",
                "item_or_block_to_tags",
                "item_to_applicable_enchantments",
                "enchantment_to_acceptable_items",
                "registry_subject_to_advancement_references",
                "advancement_to_parent",
                "advancement_to_children",
                "advancement_to_requirements",
                "advancement_to_criterion_conditions",
                "advancement_to_referenced_identifiers",
                "namespace_to_installed_mod",
                "subject_to_active_resource",
                "subject_to_packaged_mod_resource",
                "subject_to_bytecode_constant_reference",
                "artifact_reference_to_registry_subject",
                "iterative_frontier_expansion",
                "recursive_crafting_dependencies"
        )) {
            relationships.add(relationship);
        }
        output.add("relationships", relationships);

        Map<String, RegistrySummary> registrySummaries = registrySummaries(view.registryEntries());
        JsonArray staticRegistries = new JsonArray();
        JsonArray synchronizedRegistries = new JsonArray();
        registrySummaries.values().stream()
                .sorted(Comparator.comparing(RegistrySummary::registryId))
                .forEach(summary -> {
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("id", summary.registryId());
                    encoded.addProperty("kind", summary.kind());
                    encoded.addProperty("entryCount", summary.entryCount());
                    if (summary.synchronizedRegistry()) {
                        synchronizedRegistries.add(encoded);
                    } else {
                        staticRegistries.add(encoded);
                    }
                });
        output.add("staticRegistries", staticRegistries);
        output.add("synchronizedRegistries", synchronizedRegistries);

        JsonObject bounds = new JsonObject();
        bounds.addProperty("maximumResults", MAXIMUM_RESULTS);
        bounds.addProperty("maximumGraphDepth", MAXIMUM_GRAPH_DEPTH);
        bounds.addProperty("maximumCraftTreeNodes", MAXIMUM_CRAFT_TREE_NODES);
        bounds.addProperty("maximumRecipeAlternativesPerSlot", 16);
        bounds.addProperty("maximumCriterionJsonCharacters", 6_000);
        output.add("bounds", bounds);

        JsonObject architecture = new JsonObject();
        architecture.addProperty("factLayer", "MinecraftKnowledgeService");
        architecture.addProperty("semanticLayer", "MinecraftKnowledgeGraphService");
        architecture.addProperty("duplicateMinecraftReaders", false);
        output.add("architecture", architecture);

        return success(
                output,
                "Minecraft Knowledge enumerated semantic operations and relationships over the authoritative MinecraftKnowledgeService fact layer."
        );
    }

    private static Result search(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint,
            int limit
    ) {
        List<Candidate> candidates = resolveCandidates(view, query, registryHint);
        JsonObject output = baseOutput("search", query);
        output.addProperty("registryHint", registryHint);
        output.addProperty("matchCount", candidates.size());
        output.addProperty("truncated", candidates.size() > limit);

        JsonArray rows = new JsonArray();
        candidates.stream()
                .limit(limit)
                .map(MinecraftKnowledgeGraphService::candidateJson)
                .forEach(rows::add);
        output.add("matches", rows);
        output.add("coverage", view.coverage());

        return success(
                output,
                candidates.isEmpty()
                        ? "No client-visible Minecraft concept matched the semantic search."
                        : "Minecraft concepts were resolved across the authoritative normalized fact layer."
        );
    }

    private static Result resolve(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint,
            int limit
    ) {
        List<Candidate> candidates = resolveCandidates(view, query, registryHint);
        JsonObject output = baseOutput("resolve", query);
        output.addProperty("registryHint", registryHint);
        output.add("coverage", view.coverage());

        if (candidates.isEmpty()) {
            output.addProperty("resolved", false);
            output.add("alternatives", new JsonArray());
            return success(
                    output,
                    "No client-visible Minecraft concept could be resolved from the supplied subject."
            );
        }

        output.addProperty("resolved", true);
        output.add("best", candidateJson(candidates.get(0)));

        JsonArray alternatives = new JsonArray();
        candidates.stream()
                .skip(1)
                .limit(Math.max(0, limit - 1))
                .map(MinecraftKnowledgeGraphService::candidateJson)
                .forEach(alternatives::add);
        output.add("alternatives", alternatives);

        return success(
                output,
                "The subject was resolved against authoritative normalized Minecraft facts."
        );
    }

    private static Result explain(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint,
            int limit,
            int depth,
            List<String> fields
    ) {
        List<Candidate> candidates = resolveCandidates(view, query, registryHint);
        if (candidates.isEmpty()) {
            return search(view, query, registryHint, limit);
        }

        Candidate selected = candidates.get(0);
        JsonObject output = baseOutput("explain", query);
        output.addProperty("registryHint", registryHint);
        output.add("resolution", candidateJson(selected));
        output.add("coverage", view.coverage());

        JsonObject dossier = dossierForCandidate(view, selected, limit, depth);

        if (!fields.isEmpty()) {
            dossier = selectTopLevelFields(dossier, fields);
        }
        output.add("knowledge", dossier);

        JsonArray alternatives = new JsonArray();
        candidates.stream()
                .skip(1)
                .limit(Math.min(7, Math.max(0, limit - 1)))
                .map(MinecraftKnowledgeGraphService::candidateJson)
                .forEach(alternatives::add);
        output.add("otherMatches", alternatives);

        return success(
                output,
                "A bounded semantic dossier was built by traversing relationships over normalized Minecraft facts."
        );
    }


    /**
     * Deep subject investigation: combines the ordinary semantic dossier with
     * provenance-preserving active-resource and installed-mod implementation
     * evidence, then exposes a bounded frontier for iterative follow-up.
     *
     * <p>This is intentionally not a one-call "solve everything" endpoint.
     * Large modpacks are explored as a sequence of bounded graph expansions so
     * the model can prioritize high-value branches without flooding context.</p>
     */
    private static Result investigate(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint,
            int limit,
            int depth,
            List<String> fields,
            boolean includeDossier
    ) {
        List<Candidate> candidates = resolveCandidates(view, query, registryHint);
        if (candidates.isEmpty()) {
            return search(view, query, registryHint, limit);
        }

        Candidate selected = candidates.get(0);
        String ownerNamespace = namespace(selected.id());
        List<MinecraftKnowledgeService.ArtifactEvidenceFact> evidence =
                view.artifactEvidence(
                        selected.id(),
                        ownerNamespace,
                        Math.min(32, Math.max(8, limit * 2))
                );

        JsonObject output = baseOutput(includeDossier ? "investigate" : "expand", query);
        output.add("resolution", candidateJson(selected));
        output.add("coverage", view.coverage());
        output.addProperty(
                "evidenceBoundary",
                "runtime/synchronized facts are stronger evidence; packaged resources and bytecode constants are implementation clues that should be verified before behavioral claims"
        );

        if (includeDossier) {
            JsonObject dossier = dossierForCandidate(view, selected, limit, depth);
            if (!fields.isEmpty()) {
                dossier = selectTopLevelFields(dossier, fields);
            }
            output.add("knowledge", dossier);
        }

        JsonArray encodedEvidence = new JsonArray();
        evidence.forEach(value -> encodedEvidence.add(artifactEvidenceJson(value)));
        output.add("artifactEvidence", encodedEvidence);
        output.add("correlations", correlateEvidence(view, evidence, limit));

        Frontier frontier = buildFrontier(view, selected, evidence, limit);
        output.add("frontier", frontier.nodes());
        output.addProperty("frontierCount", frontier.totalCount());
        output.addProperty("frontierTruncated", frontier.truncated());

        JsonObject continuation = new JsonObject();
        continuation.addProperty(
                "strategy",
                "Expand unresolved/high-value frontier nodes iteratively; stop when the mechanic is explained, evidence becomes circular, or available sources are exhausted."
        );
        continuation.addProperty("suggestedQuery", "expand");
        JsonArray suggested = new JsonArray();
        for (String id : frontier.suggestedIds()) {
            JsonObject next = new JsonObject();
            next.addProperty("query", "expand");
            next.addProperty("value", id);
            suggested.add(next);
        }
        continuation.add("next", suggested);
        output.add("continuation", continuation);

        return success(
                output,
                "Minecraft Knowledge combined normalized game facts with bounded active-resource and installed-mod implementation evidence and returned an iterative investigation frontier."
        );
    }

    private static Result evidenceReferences(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint,
            int limit
    ) {
        List<Candidate> candidates = resolveCandidates(view, query, registryHint);
        Candidate selected = candidates.isEmpty() ? null : candidates.get(0);
        String subject = selected == null ? query : selected.id();
        String ownerNamespace = selected == null ? namespace(query) : namespace(selected.id());

        List<MinecraftKnowledgeService.ArtifactEvidenceFact> evidence =
                view.artifactEvidence(
                        subject,
                        ownerNamespace,
                        Math.min(32, Math.max(8, limit * 2))
                );

        JsonObject output = baseOutput("references", query);
        if (selected != null) {
            output.add("resolution", candidateJson(selected));
        }
        output.add("coverage", view.coverage());

        JsonArray rows = new JsonArray();
        evidence.forEach(value -> rows.add(artifactEvidenceJson(value)));
        output.add("artifactEvidence", rows);
        output.add("correlations", correlateEvidence(view, evidence, limit));
        output.addProperty(
                "evidenceBoundary",
                "Class constants and packaged files identify implementation relationships but do not alone prove runtime behavior."
        );

        return success(
                output,
                evidence.isEmpty()
                        ? "No bounded implementation/resource references were found for the subject."
                        : "Bounded resource and installed-mod references were collected for the subject."
        );
    }

    private static JsonObject dossierForCandidate(
            MinecraftKnowledgeService.KnowledgeView view,
            Candidate selected,
            int limit,
            int depth
    ) {
        return switch (selected.kind()) {
            case "item" -> itemDossier(view, selected.id(), limit, depth);
            case "block" -> blockDossier(view, selected.id(), limit, depth);
            case "entity_type", "status_effect" ->
                    subjectDossier(view, selected.kind(), selected.id(), limit);
            case "enchantment" -> enchantmentDossier(view, selected.id(), limit);
            case "advancement" -> advancementDossier(view, selected.id(), true);
            case "recipe" -> recipeDossier(view, selected.id());
            case "mod" -> modDossier(view, namespace(selected.id()));
            default -> registryNodeDossier(view, selected, limit);
        };
    }

    private static JsonObject artifactEvidenceJson(
            MinecraftKnowledgeService.ArtifactEvidenceFact fact
    ) {
        JsonObject out = new JsonObject();
        out.addProperty("sourceKind", fact.sourceKind());
        out.addProperty("owner", fact.owner());
        out.addProperty("location", fact.location());
        out.addProperty("contentKind", fact.contentKind());
        out.addProperty("authority", fact.authority());
        out.addProperty("score", fact.score());
        out.addProperty("active", fact.active());
        out.addProperty("truncated", fact.truncated());
        out.add("matchedTerms", stringArray(fact.matchedTerms()));
        out.add("identifierReferences", stringArray(fact.identifierReferences()));
        out.addProperty("excerpt", fact.excerpt());
        return out;
    }

    private static JsonArray correlateEvidence(
            MinecraftKnowledgeService.KnowledgeView view,
            List<MinecraftKnowledgeService.ArtifactEvidenceFact> evidence,
            int limit
    ) {
        LinkedHashMap<String, LinkedHashSet<String>> locationsByReference =
                new LinkedHashMap<>();
        for (MinecraftKnowledgeService.ArtifactEvidenceFact artifact : evidence) {
            for (String reference : artifact.identifierReferences()) {
                locationsByReference
                        .computeIfAbsent(reference, ignored -> new LinkedHashSet<>())
                        .add(artifact.location());
            }
        }

        JsonArray rows = new JsonArray();
        int emitted = 0;
        for (Map.Entry<String, LinkedHashSet<String>> entry : locationsByReference.entrySet()) {
            if (emitted >= Math.min(32, Math.max(8, limit * 2))) {
                break;
            }
            Candidate resolved = exactCandidate(view, entry.getKey());
            if (resolved == null) {
                continue;
            }

            JsonObject row = new JsonObject();
            row.addProperty("reference", entry.getKey());
            row.add("resolved", candidateJson(resolved));
            JsonArray locations = new JsonArray();
            entry.getValue().stream().limit(8).forEach(locations::add);
            row.add("evidenceLocations", locations);
            rows.add(row);
            emitted++;
        }
        return rows;
    }

    private static Candidate exactCandidate(
            MinecraftKnowledgeService.KnowledgeView view,
            String exactId
    ) {
        String normalized = normalizeText(exactId);
        if (normalized.isBlank()) {
            return null;
        }

        for (MinecraftKnowledgeService.RegistryEntryFact entry : view.registryEntries()) {
            if (normalizeText(entry.id()).equals(normalized)) {
                return new Candidate(
                        entry.kind(),
                        entry.id(),
                        entry.name(),
                        1_000,
                        entry.source()
                );
            }
        }
        for (MinecraftKnowledgeService.RecipeFact recipe : view.recipes()) {
            if (normalizeText(recipe.id()).equals(normalized)) {
                return new Candidate(
                        "recipe",
                        recipe.id(),
                        recipe.outputName().isBlank() ? idPath(recipe.id()) : recipe.outputName(),
                        1_000,
                        "synchronized_recipe"
                );
            }
        }
        for (MinecraftKnowledgeService.AdvancementFact advancement : view.advancements()) {
            if (normalizeText(advancement.id()).equals(normalized)) {
                return new Candidate(
                        "advancement",
                        advancement.id(),
                        advancement.title().isBlank() ? idPath(advancement.id()) : advancement.title(),
                        1_000,
                        "synchronized_advancement"
                );
            }
        }
        return null;
    }

    private static Frontier buildFrontier(
            MinecraftKnowledgeService.KnowledgeView view,
            Candidate selected,
            List<MinecraftKnowledgeService.ArtifactEvidenceFact> evidence,
            int limit
    ) {
        LinkedHashMap<String, LinkedHashSet<String>> reasons = new LinkedHashMap<>();

        MinecraftKnowledgeService.SubjectFact subject =
                view.subject(selected.kind(), selected.id());
        if (subject != null) {
            addFrontier(reasons, subject.itemFormId(), "item_form");
            addFrontier(reasons, subject.blockFormId(), "block_form");
            for (String tag : subject.tags()) {
                addFrontier(reasons, tag, "tag_membership");
            }
            for (MinecraftKnowledgeService.EnchantmentLinkFact enchantment
                    : subject.applicableEnchantments()) {
                addFrontier(reasons, enchantment.id(), "applicable_enchantment");
            }
            for (String accepted : subject.acceptedItemIds()) {
                addFrontier(reasons, accepted, "accepted_item");
            }
        }

        if ("item".equals(selected.kind()) || "block".equals(selected.kind())) {
            String itemId = itemIdFor(view, selected);
            if (!itemId.isBlank()) {
                for (MinecraftKnowledgeService.RecipeFact recipe
                        : recipesProducing(view.recipes(), itemId)) {
                    addFrontier(reasons, recipe.id(), "recipe_producing_subject");
                    for (MinecraftKnowledgeService.IngredientFact ingredient
                            : recipe.ingredients()) {
                        for (MinecraftKnowledgeService.ItemAlternativeFact alternative
                                : ingredient.alternatives()) {
                            addFrontier(reasons, alternative.id(), "crafting_dependency");
                        }
                        for (String tag : ingredient.sharedItemTags()) {
                            addFrontier(reasons, tag, "ingredient_tag");
                        }
                    }
                }
                for (MinecraftKnowledgeService.RecipeFact recipe
                        : recipesUsing(view.recipes(), itemId)) {
                    addFrontier(reasons, recipe.id(), "recipe_using_subject");
                    addFrontier(reasons, recipe.outputId(), "recipe_output_using_subject");
                }
            }
        }

        for (MinecraftKnowledgeService.AdvancementFact advancement : view.advancements()) {
            if (advancement.identifierReferences().contains(selected.id())) {
                addFrontier(reasons, advancement.id(), "advancement_reference");
                addFrontier(reasons, advancement.parentId(), "advancement_parent");
                advancement.childIds().forEach(
                        id -> addFrontier(reasons, id, "advancement_child")
                );
                advancement.identifierReferences().forEach(
                        id -> addFrontier(reasons, id, "advancement_condition_reference")
                );
            }
        }

        for (MinecraftKnowledgeService.ArtifactEvidenceFact artifact : evidence) {
            for (String reference : artifact.identifierReferences()) {
                addFrontier(
                        reasons,
                        reference,
                        "artifact_reference:" + artifact.contentKind()
                );
            }
        }

        reasons.remove(selected.id());
        int total = reasons.size();
        int maximum = Math.min(48, Math.max(12, limit * 3));
        JsonArray nodes = new JsonArray();
        List<String> suggested = new ArrayList<>();

        int emitted = 0;
        for (Map.Entry<String, LinkedHashSet<String>> entry : reasons.entrySet()) {
            if (emitted++ >= maximum) {
                break;
            }
            JsonObject node = new JsonObject();
            node.addProperty("id", entry.getKey());
            Candidate resolved = exactCandidate(view, entry.getKey());
            if (resolved != null) {
                node.addProperty("kind", resolved.kind());
                node.addProperty("name", resolved.name());
                node.addProperty("resolved", true);
            } else {
                node.addProperty("resolved", false);
            }
            JsonArray reasonArray = new JsonArray();
            entry.getValue().stream().limit(6).forEach(reasonArray::add);
            node.add("reasons", reasonArray);
            nodes.add(node);

            if (suggested.size() < 12 && resolved != null) {
                suggested.add(entry.getKey());
            }
        }

        return new Frontier(nodes, total, total > maximum, List.copyOf(suggested));
    }

    private static void addFrontier(
            Map<String, LinkedHashSet<String>> target,
            String id,
            String reason
    ) {
        if (id == null || id.isBlank()) {
            return;
        }
        target.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(reason);
    }

    private static Result usageGraph(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint,
            int limit,
            boolean producers
    ) {
        Candidate selected = bestItemOrBlock(view, query, registryHint);
        if (selected == null) {
            return unavailable(
                    "No item or block could be resolved for the requested recipe relationship."
            );
        }

        String itemId = itemIdFor(view, selected);
        if (itemId.isBlank()) {
            return unavailable(
                    "The resolved concept does not have an item form that can participate in synchronized recipes."
            );
        }

        JsonObject output = baseOutput(producers ? "sources" : "uses", query);
        output.add("resolution", candidateJson(selected));
        output.addProperty("item", itemId);
        output.add("coverage", view.coverage());

        List<MinecraftKnowledgeService.RecipeFact> recipes = producers
                ? recipesProducing(view.recipes(), itemId)
                : recipesUsing(view.recipes(), itemId);

        output.addProperty("matchCount", recipes.size());
        output.addProperty("truncated", recipes.size() > limit);

        JsonArray rows = new JsonArray();
        for (MinecraftKnowledgeService.RecipeFact recipe : recipes.stream().limit(limit).toList()) {
            rows.add(encodeRecipe(recipe, itemId));
        }
        output.add(producers ? "sources" : "uses", rows);

        return success(
                output,
                producers
                        ? "Synchronized recipe facts producing the resolved item were traversed."
                        : "Synchronized recipe facts consuming the resolved item were traversed."
        );
    }

    private static Result craftingPath(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint,
            int limit,
            int requestedDepth
    ) {
        Candidate selected = bestItemOrBlock(view, query, registryHint);
        if (selected == null) {
            return unavailable(
                    "No craftable item or block could be resolved for the requested crafting path."
            );
        }

        String itemId = itemIdFor(view, selected);
        if (itemId.isBlank()) {
            return unavailable(
                    "The resolved concept does not have an item form that can participate in recipes."
            );
        }

        int depth = requestedDepth <= 0 ? 2 : requestedDepth;
        CraftBudget budget = new CraftBudget(
                Math.min(MAXIMUM_CRAFT_TREE_NODES, Math.max(24, limit * 4))
        );

        JsonObject output = baseOutput("crafting_path", query);
        output.add("resolution", candidateJson(selected));
        output.addProperty("item", itemId);
        output.addProperty("requestedDepth", depth);
        output.add("coverage", view.coverage());
        output.add(
                "tree",
                buildCraftTree(
                        view.recipes(),
                        itemId,
                        depth,
                        new LinkedHashSet<>(),
                        budget
                )
        );
        output.addProperty("nodeBudget", budget.maximum());
        output.addProperty("nodesVisited", budget.used());
        output.addProperty("truncated", budget.exhausted());

        return success(
                output,
                "A bounded recursive crafting dependency tree was built from authoritative synchronized recipe facts."
        );
    }

    private static Result recipeSearch(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            int limit
    ) {
        if (!view.recipesAvailable()) {
            return unavailable("A connection with synchronized recipes is required.");
        }

        String needle = semanticNeedle(query);
        List<RecipeMatch> matches = new ArrayList<>();
        for (MinecraftKnowledgeService.RecipeFact recipe : view.recipes()) {
            RecipeMatch match = matchRecipe(recipe, needle);
            if (match.score() > 0) {
                matches.add(match);
            }
        }

        matches.sort(
                Comparator.comparingInt(RecipeMatch::score)
                        .reversed()
                        .thenComparing(match -> match.recipe().id())
        );

        JsonObject output = baseOutput("recipe", query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("coverage", view.coverage());

        JsonArray rows = new JsonArray();
        for (RecipeMatch match : matches.stream().limit(limit).toList()) {
            JsonObject encoded = encodeRecipe(match.recipe(), "");
            JsonArray reasons = new JsonArray();
            match.reasons().forEach(reasons::add);
            encoded.add("matchReasons", reasons);
            rows.add(encoded);
        }
        output.add("recipes", rows);

        return success(
                output,
                "Recipes were semantically matched over authoritative normalized recipe facts."
        );
    }

    private static Result advancementSearch(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            int limit,
            boolean includeCriteria
    ) {
        if (!view.advancementsAvailable()) {
            return unavailable("A connection with synchronized advancements is required.");
        }

        String needle = semanticNeedle(query);
        List<AdvancementMatch> matches = new ArrayList<>();
        for (MinecraftKnowledgeService.AdvancementFact advancement : view.advancements()) {
            AdvancementMatch match = matchAdvancement(advancement, needle);
            if (match.score() > 0) {
                matches.add(match);
            }
        }

        matches.sort(
                Comparator.comparingInt(AdvancementMatch::score)
                        .reversed()
                        .thenComparing(match -> match.advancement().id())
        );

        JsonObject output = baseOutput("advancement", query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("coverage", view.coverage());

        JsonArray rows = new JsonArray();
        for (AdvancementMatch match : matches.stream().limit(limit).toList()) {
            JsonObject encoded = encodeAdvancement(match.advancement(), includeCriteria);
            JsonArray reasons = new JsonArray();
            match.reasons().forEach(reasons::add);
            encoded.add("matchReasons", reasons);
            rows.add(encoded);
        }
        output.add("advancements", rows);

        return success(
                output,
                "Advancements were semantically matched over authoritative normalized advancement facts."
        );
    }

    private static List<Candidate> resolveCandidates(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint
    ) {
        String needle = semanticNeedle(query);
        if (needle.isBlank()) {
            return List.of();
        }

        List<Candidate> out = new ArrayList<>();

        for (MinecraftKnowledgeService.RegistryEntryFact entry : view.registryEntries()) {
            int score = score(
                    entry.kind(),
                    entry.id(),
                    entry.name(),
                    needle,
                    registryHint
            );
            if (score > 0) {
                out.add(new Candidate(
                        entry.kind(),
                        entry.id(),
                        entry.name(),
                        score,
                        entry.source()
                ));
            }
        }

        for (MinecraftKnowledgeService.AdvancementFact advancement : view.advancements()) {
            AdvancementMatch match = matchAdvancement(advancement, needle);
            if (match.score() > 0) {
                out.add(new Candidate(
                        "advancement",
                        advancement.id(),
                        advancement.title().isBlank()
                                ? idPath(advancement.id())
                                : advancement.title(),
                        match.score() + hintBonus("advancement", registryHint),
                        "synchronized_advancement"
                ));
            }
        }

        for (MinecraftKnowledgeService.RecipeFact recipe : view.recipes()) {
            RecipeMatch match = matchRecipe(recipe, needle);
            if (match.score() > 0) {
                out.add(new Candidate(
                        "recipe",
                        recipe.id(),
                        recipe.outputName().isBlank()
                                ? idPath(recipe.id())
                                : recipe.outputName(),
                        match.score() + hintBonus("recipe", registryHint),
                        "synchronized_recipe"
                ));
            }
        }

        for (MinecraftKnowledgeService.ModFact mod : view.mods()) {
            String syntheticId = mod.id() + ":mod";
            int score = score(
                    "mod",
                    syntheticId,
                    mod.name(),
                    needle,
                    registryHint
            );
            if (containsSemantic(mod.id(), needle)) {
                score = Math.max(score, 650);
            }
            if (score > 0) {
                out.add(new Candidate(
                        "mod",
                        syntheticId,
                        mod.name(),
                        score,
                        "fabric_loader"
                ));
            }
        }

        Map<String, Candidate> deduplicated = new LinkedHashMap<>();
        for (Candidate candidate : out) {
            String key = candidate.kind() + "|" + candidate.id();
            Candidate existing = deduplicated.get(key);
            if (existing == null || candidate.score() > existing.score()) {
                deduplicated.put(key, candidate);
            }
        }

        List<Candidate> candidates = new ArrayList<>(deduplicated.values());
        candidates.sort(
                Comparator.comparingInt(Candidate::score)
                        .reversed()
                        .thenComparingInt(candidate -> kindPriority(candidate.kind()))
                        .thenComparing(Candidate::id)
        );

        if (candidates.size() > MAXIMUM_INTERNAL_CANDIDATES) {
            return List.copyOf(candidates.subList(0, MAXIMUM_INTERNAL_CANDIDATES));
        }
        return List.copyOf(candidates);
    }

    private static int score(
            String kind,
            String id,
            String displayName,
            String needle,
            String registryHint
    ) {
        String idText = normalizeText(id);
        String path = normalizeText(idPath(id));
        String name = normalizeText(displayName);
        String normalizedNeedle = normalizeText(needle);

        int score = 0;
        if (normalizedNeedle.equals(idText)) score = 1_000;
        else if (normalizedNeedle.equals(path)) score = 940;
        else if (!name.isBlank() && normalizedNeedle.equals(name)) score = 900;
        else if (path.startsWith(normalizedNeedle)) score = 760;
        else if (!name.isBlank() && name.startsWith(normalizedNeedle)) score = 730;
        else if (idText.contains(normalizedNeedle)) score = 620;
        else if (!name.isBlank() && name.contains(normalizedNeedle)) score = 590;
        else {
            List<String> tokens = meaningfulTokens(normalizedNeedle);
            if (!tokens.isEmpty()) {
                int matched = 0;
                String haystack = idText + " " + name;
                for (String token : tokens) {
                    if (haystack.contains(token)) {
                        matched++;
                    }
                }
                if (matched > 0) {
                    score = 300 + matched * 70;
                    if (matched == tokens.size()) {
                        score += 80;
                    }
                }
            }
        }

        // Fall back to typo/phrase tolerant matching. This is deliberately
        // weaker than exact/substring evidence but lets small models recover
        // from forms such as "gold apple", "golden apple", "golden_apple",
        // or a lightly misspelled advancement title without special aliases.
        int fuzzy = Math.max(
                FuzzyTextMatcher.score(needle, id),
                Math.max(FuzzyTextMatcher.score(needle, idPath(id)),
                        FuzzyTextMatcher.score(needle, displayName))
        );
        if (fuzzy >= 560) {
            score = Math.max(score, 180 + (int) Math.round(fuzzy * 0.58D));
        }

        return score <= 0 ? 0 : score + hintBonus(kind, registryHint);
    }

    private static int hintBonus(String kind, String registryHint) {
        if (registryHint == null || registryHint.isBlank()) {
            return 0;
        }
        return canonicalKind(kind).equals(canonicalKind(registryHint)) ? 180 : -40;
    }

    private static int kindPriority(String kind) {
        return switch (canonicalKind(kind)) {
            case "item" -> 0;
            case "block" -> 1;
            case "recipe" -> 2;
            case "advancement" -> 3;
            case "entity_type" -> 4;
            case "enchantment" -> 5;
            case "status_effect" -> 6;
            case "structure" -> 7;
            case "biome" -> 8;
            case "dimension_type" -> 9;
            case "sound_event" -> 10;
            case "mod" -> 11;
            default -> 20;
        };
    }

    private static Candidate bestItemOrBlock(
            MinecraftKnowledgeService.KnowledgeView view,
            String query,
            String registryHint
    ) {
        return resolveCandidates(view, query, registryHint).stream()
                .filter(candidate -> "item".equals(candidate.kind())
                        || "block".equals(candidate.kind()))
                .findFirst()
                .orElse(null);
    }

    private static JsonObject itemDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            String id,
            int limit,
            int depth
    ) {
        MinecraftKnowledgeService.SubjectFact fact = view.subject("item", id);
        if (fact == null) {
            return new JsonObject();
        }

        JsonObject out = subjectFactJson(fact);
        out.addProperty("kind", "item");
        out.add("tags", stringArray(fact.tags()));

        if (!fact.blockFormId().isBlank()) {
            JsonObject link = new JsonObject();
            link.addProperty("relation", "places_block");
            link.addProperty("block", fact.blockFormId());
            out.add("blockForm", link);
        }

        List<MinecraftKnowledgeService.RecipeFact> sources =
                recipesProducing(view.recipes(), id);
        List<MinecraftKnowledgeService.RecipeFact> uses =
                recipesUsing(view.recipes(), id);

        out.add(
                "recipesProducing",
                recipeArray(sources, id, Math.min(limit, MAXIMUM_RECIPES_PER_SECTION))
        );
        out.add(
                "recipesUsing",
                recipeArray(uses, id, Math.min(limit, MAXIMUM_RECIPES_PER_SECTION))
        );
        out.addProperty("recipeProducerCount", sources.size());
        out.addProperty("recipeUseCount", uses.size());

        JsonObject enchantments = new JsonObject();
        JsonArray enchantmentEntries = new JsonArray();
        fact.applicableEnchantments().stream()
                .limit(Math.min(limit, MAXIMUM_ENCHANTMENTS_PER_ITEM))
                .forEach(enchantment -> {
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("id", enchantment.id());
                    encoded.addProperty("minimumLevel", enchantment.minimumLevel());
                    encoded.addProperty("maximumLevel", enchantment.maximumLevel());
                    encoded.addProperty("treasure", enchantment.treasure());
                    encoded.addProperty("cursed", enchantment.cursed());
                    encoded.add("conflictsWith", stringArray(enchantment.conflictsWith()));
                    enchantmentEntries.add(encoded);
                });
        enchantments.addProperty("count", fact.applicableEnchantments().size());
        enchantments.addProperty(
                "truncated",
                fact.applicableEnchantments().size() > Math.min(limit, MAXIMUM_ENCHANTMENTS_PER_ITEM)
        );
        enchantments.add("entries", enchantmentEntries);
        out.add("applicableEnchantments", enchantments);

        out.add(
                "advancementReferences",
                advancementReferences(
                        view.advancements(),
                        id,
                        Math.min(limit, MAXIMUM_ADVANCEMENTS_PER_SECTION)
                )
        );
        out.add("mod", modForNamespace(view, namespace(id)));

        if (depth > 0 && !sources.isEmpty()) {
            CraftBudget budget = new CraftBudget(Math.min(MAXIMUM_CRAFT_TREE_NODES, 64));
            out.add(
                    "craftingDependencies",
                    buildCraftTree(
                            view.recipes(),
                            id,
                            Math.min(depth, MAXIMUM_GRAPH_DEPTH),
                            new LinkedHashSet<>(),
                            budget
                    )
            );
        }

        return out;
    }

    private static JsonObject blockDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            String id,
            int limit,
            int depth
    ) {
        MinecraftKnowledgeService.SubjectFact fact = view.subject("block", id);
        if (fact == null) {
            return new JsonObject();
        }

        JsonObject out = subjectFactJson(fact);
        out.addProperty("kind", "block");
        out.add("tags", stringArray(fact.tags()));

        if (!fact.itemFormId().isBlank()) {
            JsonObject link = new JsonObject();
            link.addProperty("relation", "item_form");
            link.addProperty("item", fact.itemFormId());
            out.add("itemForm", link);

            List<MinecraftKnowledgeService.RecipeFact> sources =
                    recipesProducing(view.recipes(), fact.itemFormId());
            List<MinecraftKnowledgeService.RecipeFact> uses =
                    recipesUsing(view.recipes(), fact.itemFormId());

            out.add(
                    "recipesProducing",
                    recipeArray(
                            sources,
                            fact.itemFormId(),
                            Math.min(limit, MAXIMUM_RECIPES_PER_SECTION)
                    )
            );
            out.add(
                    "recipesUsing",
                    recipeArray(
                            uses,
                            fact.itemFormId(),
                            Math.min(limit, MAXIMUM_RECIPES_PER_SECTION)
                    )
            );
            out.addProperty("recipeProducerCount", sources.size());
            out.addProperty("recipeUseCount", uses.size());

            if (depth > 0 && !sources.isEmpty()) {
                CraftBudget budget = new CraftBudget(Math.min(MAXIMUM_CRAFT_TREE_NODES, 64));
                out.add(
                        "craftingDependencies",
                        buildCraftTree(
                                view.recipes(),
                                fact.itemFormId(),
                                Math.min(depth, MAXIMUM_GRAPH_DEPTH),
                                new LinkedHashSet<>(),
                                budget
                        )
                );
            }
        }

        out.add(
                "advancementReferences",
                advancementReferences(
                        view.advancements(),
                        id,
                        Math.min(limit, MAXIMUM_ADVANCEMENTS_PER_SECTION)
                )
        );
        out.add("mod", modForNamespace(view, namespace(id)));
        return out;
    }

    private static JsonObject subjectDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            String kind,
            String id,
            int limit
    ) {
        MinecraftKnowledgeService.SubjectFact fact = view.subject(kind, id);
        if (fact == null) {
            return new JsonObject();
        }

        JsonObject out = subjectFactJson(fact);
        out.addProperty("kind", canonicalKind(kind));
        out.add("tags", stringArray(fact.tags()));
        out.add(
                "advancementReferences",
                advancementReferences(
                        view.advancements(),
                        id,
                        Math.min(limit, MAXIMUM_ADVANCEMENTS_PER_SECTION)
                )
        );
        out.add("mod", modForNamespace(view, namespace(id)));
        return out;
    }

    private static JsonObject enchantmentDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            String id,
            int limit
    ) {
        MinecraftKnowledgeService.SubjectFact fact = view.subject("enchantment", id);
        if (fact == null) {
            return new JsonObject();
        }

        JsonObject out = subjectFactJson(fact);
        out.addProperty("kind", "enchantment");
        out.add("tags", stringArray(fact.tags()));
        out.add(
                "advancementReferences",
                advancementReferences(
                        view.advancements(),
                        id,
                        Math.min(limit, MAXIMUM_ADVANCEMENTS_PER_SECTION)
                )
        );
        out.add("mod", modForNamespace(view, namespace(id)));

        JsonArray acceptedItems = new JsonArray();
        fact.acceptedItemIds().stream().limit(limit).forEach(acceptedItems::add);
        out.addProperty("acceptedItemCount", fact.acceptedItemCount());
        out.addProperty(
                "acceptedItemsTruncated",
                fact.acceptedItemsTruncated() || fact.acceptedItemIds().size() > limit
        );
        out.add("acceptedItems", acceptedItems);
        return out;
    }

    private static JsonObject registryNodeDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            Candidate candidate,
            int limit
    ) {
        MinecraftKnowledgeService.SubjectFact fact =
                view.subject(candidate.kind(), candidate.id());

        JsonObject out = fact == null ? new JsonObject() : subjectFactJson(fact);
        out.addProperty("kind", candidate.kind());
        out.addProperty("id", candidate.id());
        out.addProperty("name", candidate.name());
        out.addProperty("source", candidate.source());
        if (fact != null && !fact.tags().isEmpty()) {
            out.add("tags", stringArray(fact.tags()));
        }
        out.add(
                "advancementReferences",
                advancementReferences(
                        view.advancements(),
                        candidate.id(),
                        Math.min(limit, MAXIMUM_ADVANCEMENTS_PER_SECTION)
                )
        );
        out.add("mod", modForNamespace(view, namespace(candidate.id())));
        return out;
    }

    private static JsonObject recipeDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            String id
    ) {
        return view.recipes().stream()
                .filter(recipe -> recipe.id().equals(id))
                .findFirst()
                .map(recipe -> encodeRecipe(recipe, ""))
                .orElseGet(JsonObject::new);
    }

    private static JsonObject advancementDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            String id,
            boolean includeCriteria
    ) {
        return view.advancements().stream()
                .filter(advancement -> advancement.id().equals(id))
                .findFirst()
                .map(advancement -> encodeAdvancement(advancement, includeCriteria))
                .orElseGet(JsonObject::new);
    }

    private static JsonArray advancementReferences(
            List<MinecraftKnowledgeService.AdvancementFact> advancements,
            String subject,
            int limit
    ) {
        JsonArray rows = new JsonArray();
        if (subject == null || subject.isBlank()) {
            return rows;
        }

        String needle = normalizeText(subject);
        List<AdvancementMatch> matches = new ArrayList<>();
        for (MinecraftKnowledgeService.AdvancementFact advancement : advancements) {
            AdvancementMatch match = matchAdvancement(advancement, needle);
            if (match.score() > 0 && match.reasons().stream().anyMatch(reason ->
                    reason.startsWith("criteria")
                            || reason.startsWith("icon")
                            || reason.startsWith("id")
                            || reason.startsWith("display")
                            || reason.startsWith("identifier_reference"))) {
                matches.add(match);
            }
        }

        matches.sort(
                Comparator.comparingInt(AdvancementMatch::score)
                        .reversed()
                        .thenComparing(match -> match.advancement().id())
        );

        for (AdvancementMatch match : matches.stream().limit(limit).toList()) {
            JsonObject encoded = encodeAdvancement(match.advancement(), false);
            JsonArray reasons = new JsonArray();
            match.reasons().forEach(reasons::add);
            encoded.add("matchReasons", reasons);
            rows.add(encoded);
        }
        return rows;
    }

    private static JsonObject encodeRecipe(
            MinecraftKnowledgeService.RecipeFact recipe,
            String focusItem
    ) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", recipe.id());
        encoded.addProperty("type", recipe.type());
        encoded.addProperty("serializer", recipe.serializer());
        encoded.addProperty("group", recipe.group());
        encoded.addProperty("output", recipe.outputId());
        encoded.addProperty("outputName", recipe.outputName());
        encoded.addProperty("outputCount", recipe.outputCount());

        JsonArray ingredients = new JsonArray();
        boolean focusUsedAsIngredient = false;

        for (MinecraftKnowledgeService.IngredientFact ingredient : recipe.ingredients()) {
            JsonObject encodedIngredient = new JsonObject();
            encodedIngredient.addProperty("slot", ingredient.slot());
            encodedIngredient.addProperty("alternativeCount", ingredient.alternativeCount());
            encodedIngredient.addProperty("truncated", ingredient.truncated());

            JsonArray alternatives = new JsonArray();
            for (MinecraftKnowledgeService.ItemAlternativeFact alternative : ingredient.alternatives()) {
                JsonObject encodedAlternative = new JsonObject();
                encodedAlternative.addProperty("id", alternative.id());
                encodedAlternative.addProperty("name", alternative.name());
                encodedAlternative.addProperty("count", alternative.count());
                alternatives.add(encodedAlternative);
                if (!focusItem.isBlank() && focusItem.equals(alternative.id())) {
                    focusUsedAsIngredient = true;
                }
            }
            encodedIngredient.add("alternatives", alternatives);
            encodedIngredient.add("sharedItemTags", stringArray(ingredient.sharedItemTags()));
            ingredients.add(encodedIngredient);
        }

        encoded.add("ingredients", ingredients);
        encoded.addProperty("ingredientSlotCount", recipe.ingredients().size());
        encoded.addProperty(
                "exactIngredientTotalsComplete",
                recipe.exactIngredientTotalsComplete()
        );

        JsonArray totals = new JsonArray();
        recipe.exactIngredientTotals().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    JsonObject total = new JsonObject();
                    total.addProperty("id", entry.getKey());
                    total.addProperty("count", entry.getValue());
                    totals.add(total);
                });
        encoded.add("exactIngredientTotals", totals);

        if (!focusItem.isBlank()) {
            encoded.addProperty("focusItem", focusItem);
            encoded.addProperty("producesFocus", focusItem.equals(recipe.outputId()));
            encoded.addProperty("usesFocusAsIngredient", focusUsedAsIngredient);
        }

        return encoded;
    }

    private static JsonObject encodeAdvancement(
            MinecraftKnowledgeService.AdvancementFact advancement,
            boolean includeCriteria
    ) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", advancement.id());
        encoded.addProperty("criteriaCount", advancement.criteria().size());
        encoded.addProperty("requirementGroups", advancement.requirements().size());
        encoded.addProperty("requirementCount", advancement.requirementCount());

        if (!advancement.title().isBlank()) {
            encoded.addProperty("title", advancement.title());
            encoded.addProperty("description", advancement.description());
            encoded.addProperty("hidden", advancement.hidden());
            encoded.addProperty("frame", advancement.frame());
            encoded.addProperty("icon", advancement.icon());
            encoded.addProperty("showToast", advancement.showToast());
            encoded.addProperty("announceToChat", advancement.announceToChat());
            if (!advancement.background().isBlank()) {
                encoded.addProperty("background", advancement.background());
            }
        }

        encoded.addProperty("parent", advancement.parentId());
        encoded.add("children", stringArray(advancement.childIds()));

        JsonArray requirements = new JsonArray();
        for (List<String> group : advancement.requirements()) {
            requirements.add(stringArray(group));
        }
        encoded.add("requirements", requirements);
        encoded.addProperty("completed", advancement.completed());

        if (includeCriteria) {
            encoded.add("criteria", advancement.criteria().deepCopy());
        }

        encoded.add(
                "identifierReferences",
                stringArray(advancement.identifierReferences())
        );
        encoded.addProperty(
                "identifierReferencesTruncated",
                advancement.identifierReferencesTruncated()
        );
        return encoded;
    }

    private static RecipeMatch matchRecipe(
            MinecraftKnowledgeService.RecipeFact recipe,
            String query
    ) {
        if (query == null || query.isBlank()) {
            return new RecipeMatch(recipe, 100, List.of("all"));
        }

        int score = 0;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();

        if (equalsSemantic(recipe.id(), query)
                || equalsSemantic(idPath(recipe.id()), query)) {
            score = Math.max(score, 1_000);
            reasons.add("id_exact");
        } else if (containsSemantic(recipe.id(), query)) {
            score = Math.max(score, 700);
            reasons.add("id");
        }

        if (equalsSemantic(recipe.outputId(), query)
                || equalsSemantic(idPath(recipe.outputId()), query)
                || equalsSemantic(recipe.outputName(), query)) {
            score = Math.max(score, 950);
            reasons.add("output_exact");
        } else if (containsSemantic(recipe.outputId(), query)
                || containsSemantic(recipe.outputName(), query)) {
            score = Math.max(score, 760);
            reasons.add("output");
        }

        int fuzzyRecipe = Math.max(
                FuzzyTextMatcher.score(query, recipe.id()),
                Math.max(FuzzyTextMatcher.score(query, recipe.outputId()),
                        FuzzyTextMatcher.score(query, recipe.outputName()))
        );
        if (fuzzyRecipe >= 520) {
            score = Math.max(score, 360 + fuzzyRecipe / 2);
            reasons.add("fuzzy_recipe_metadata");
        }

        for (MinecraftKnowledgeService.IngredientFact ingredient : recipe.ingredients()) {
            for (MinecraftKnowledgeService.ItemAlternativeFact alternative : ingredient.alternatives()) {
                if (equalsSemantic(alternative.id(), query)
                        || equalsSemantic(idPath(alternative.id()), query)
                        || equalsSemantic(alternative.name(), query)) {
                    score = Math.max(score, 900);
                    reasons.add("ingredient_exact:slot=" + ingredient.slot());
                } else if (containsSemantic(alternative.id(), query)
                        || containsSemantic(alternative.name(), query)) {
                    score = Math.max(score, 680);
                    reasons.add("ingredient:slot=" + ingredient.slot());
                } else {
                    int fuzzy = Math.max(
                            FuzzyTextMatcher.score(query, alternative.id()),
                            FuzzyTextMatcher.score(query, alternative.name())
                    );
                    if (fuzzy >= 560) {
                        score = Math.max(score, 300 + fuzzy / 2);
                        reasons.add("fuzzy_ingredient:slot=" + ingredient.slot());
                    }
                }
            }

            for (String tag : ingredient.sharedItemTags()) {
                if (equalsSemantic(tag, query)
                        || containsSemantic(tag, query)) {
                    score = Math.max(score, 660);
                    reasons.add("ingredient_tag:slot=" + ingredient.slot());
                }
            }
        }

        return new RecipeMatch(recipe, score, List.copyOf(reasons));
    }

    private static AdvancementMatch matchAdvancement(
            MinecraftKnowledgeService.AdvancementFact advancement,
            String query
    ) {
        if (query == null || query.isBlank()) {
            return new AdvancementMatch(advancement, 100, List.of("all"));
        }

        int score = 0;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();

        if (equalsSemantic(advancement.id(), query)
                || equalsSemantic(idPath(advancement.id()), query)) {
            score = Math.max(score, 1_000);
            reasons.add("id_exact");
        } else if (containsSemantic(advancement.id(), query)) {
            score = Math.max(score, 760);
            reasons.add("id");
        }

        if (equalsSemantic(advancement.title(), query)) {
            score = Math.max(score, 940);
            reasons.add("display_title_exact");
        } else if (containsSemantic(advancement.title(), query)) {
            score = Math.max(score, 780);
            reasons.add("display_title");
        }

        if (containsSemantic(advancement.description(), query)) {
            score = Math.max(score, 620);
            reasons.add("display_description");
        }

        if (equalsSemantic(advancement.icon(), query)
                || equalsSemantic(idPath(advancement.icon()), query)) {
            score = Math.max(score, 820);
            reasons.add("icon_exact");
        } else if (containsSemantic(advancement.icon(), query)) {
            score = Math.max(score, 650);
            reasons.add("icon");
        }

        if (containsSemantic(advancement.parentId(), query)) {
            score = Math.max(score, 520);
            reasons.add("parent");
        }

        for (String child : advancement.childIds()) {
            if (containsSemantic(child, query)) {
                score = Math.max(score, 500);
                reasons.add("child");
            }
        }

        for (String reference : advancement.identifierReferences()) {
            if (equalsSemantic(reference, query)
                    || containsSemantic(reference, query)) {
                score = Math.max(score, 850);
                reasons.add("identifier_reference:" + reference);
            }
        }

        for (Map.Entry<String, JsonElement> entry : advancement.criteria().entrySet()) {
            if (containsSemantic(entry.getKey(), query)) {
                score = Math.max(score, 650);
                reasons.add("criteria_name:" + entry.getKey());
            }
            if (containsSemantic(entry.getValue().toString(), query)) {
                score = Math.max(score, 850);
                reasons.add("criteria_condition:" + entry.getKey());
            }
            int fuzzyCriterion = Math.max(
                    FuzzyTextMatcher.score(query, entry.getKey()),
                    FuzzyTextMatcher.score(query, entry.getValue().toString())
            );
            if (fuzzyCriterion >= 600) {
                score = Math.max(score, 300 + fuzzyCriterion / 2);
                reasons.add("fuzzy_criteria:" + entry.getKey());
            }
        }

        int fuzzyMetadata = 0;
        fuzzyMetadata = Math.max(fuzzyMetadata, FuzzyTextMatcher.score(query, advancement.id()));
        fuzzyMetadata = Math.max(fuzzyMetadata, FuzzyTextMatcher.score(query, advancement.title()));
        fuzzyMetadata = Math.max(fuzzyMetadata, FuzzyTextMatcher.score(query, advancement.description()));
        fuzzyMetadata = Math.max(fuzzyMetadata, FuzzyTextMatcher.score(query, advancement.icon()));
        fuzzyMetadata = Math.max(fuzzyMetadata, FuzzyTextMatcher.score(query, advancement.parentId()));
        for (String child : advancement.childIds()) fuzzyMetadata = Math.max(fuzzyMetadata, FuzzyTextMatcher.score(query, child));
        for (String reference : advancement.identifierReferences()) fuzzyMetadata = Math.max(fuzzyMetadata, FuzzyTextMatcher.score(query, reference));
        if (fuzzyMetadata >= 520) {
            score = Math.max(score, 360 + fuzzyMetadata / 2);
            reasons.add("fuzzy_advancement_metadata");
        }

        return new AdvancementMatch(advancement, score, List.copyOf(reasons));
    }

    private static List<MinecraftKnowledgeService.RecipeFact> recipesProducing(
            List<MinecraftKnowledgeService.RecipeFact> recipes,
            String itemId
    ) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        return recipes.stream()
                .filter(recipe -> itemId.equals(recipe.outputId()))
                .sorted(Comparator.comparing(MinecraftKnowledgeService.RecipeFact::id))
                .toList();
    }

    private static List<MinecraftKnowledgeService.RecipeFact> recipesUsing(
            List<MinecraftKnowledgeService.RecipeFact> recipes,
            String itemId
    ) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }

        List<MinecraftKnowledgeService.RecipeFact> matches = new ArrayList<>();
        for (MinecraftKnowledgeService.RecipeFact recipe : recipes) {
            boolean uses = recipe.ingredients().stream()
                    .flatMap(ingredient -> ingredient.alternatives().stream())
                    .anyMatch(alternative -> itemId.equals(alternative.id()));
            if (uses) {
                matches.add(recipe);
            }
        }
        matches.sort(Comparator.comparing(MinecraftKnowledgeService.RecipeFact::id));
        return List.copyOf(matches);
    }

    private static JsonArray recipeArray(
            List<MinecraftKnowledgeService.RecipeFact> recipes,
            String focus,
            int limit
    ) {
        JsonArray rows = new JsonArray();
        recipes.stream()
                .limit(limit)
                .map(recipe -> encodeRecipe(recipe, focus))
                .forEach(rows::add);
        return rows;
    }

    private static JsonObject buildCraftTree(
            List<MinecraftKnowledgeService.RecipeFact> recipes,
            String itemId,
            int depth,
            LinkedHashSet<String> path,
            CraftBudget budget
    ) {
        JsonObject node = new JsonObject();
        node.addProperty("item", itemId);

        if (!budget.consume()) {
            node.addProperty("truncated", true);
            node.addProperty("reason", "node_budget_exhausted");
            return node;
        }

        if (path.contains(itemId)) {
            node.addProperty("cycle", true);
            return node;
        }

        if (depth <= 0) {
            node.addProperty("depthLimit", true);
            return node;
        }

        LinkedHashSet<String> nextPath = new LinkedHashSet<>(path);
        nextPath.add(itemId);

        List<MinecraftKnowledgeService.RecipeFact> producers =
                recipesProducing(recipes, itemId);

        node.addProperty("recipeOptionCount", producers.size());
        JsonArray recipeOptions = new JsonArray();

        for (MinecraftKnowledgeService.RecipeFact recipe : producers.stream().limit(6).toList()) {
            if (budget.exhausted()) {
                break;
            }

            JsonObject recipeNode = new JsonObject();
            recipeNode.addProperty("recipe", recipe.id());
            recipeNode.addProperty("outputCount", recipe.outputCount());

            JsonArray slots = new JsonArray();
            for (MinecraftKnowledgeService.IngredientFact ingredient : recipe.ingredients()) {
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", ingredient.slot());
                slot.addProperty("alternativeCount", ingredient.alternativeCount());
                slot.addProperty("alternativesTruncated", ingredient.truncated());
                slot.add("sharedItemTags", stringArray(ingredient.sharedItemTags()));

                JsonArray alternativeNodes = new JsonArray();
                LinkedHashSet<String> seen = new LinkedHashSet<>();

                for (MinecraftKnowledgeService.ItemAlternativeFact alternative
                        : ingredient.alternatives().stream().limit(8).toList()) {
                    if (alternative.id().isBlank() || !seen.add(alternative.id())) {
                        continue;
                    }

                    JsonObject alternativeNode = new JsonObject();
                    alternativeNode.addProperty("item", alternative.id());
                    alternativeNode.addProperty("name", alternative.name());

                    if (depth > 1
                            && !nextPath.contains(alternative.id())
                            && !budget.exhausted()) {
                        List<MinecraftKnowledgeService.RecipeFact> childSources =
                                recipesProducing(recipes, alternative.id());
                        if (!childSources.isEmpty()) {
                            alternativeNode.add(
                                    "crafting",
                                    buildCraftTree(
                                            recipes,
                                            alternative.id(),
                                            depth - 1,
                                            nextPath,
                                            budget
                                    )
                            );
                        } else {
                            alternativeNode.addProperty(
                                    "hasSynchronizedRecipeSource",
                                    false
                            );
                        }
                    }
                    alternativeNodes.add(alternativeNode);
                }

                slot.add("alternatives", alternativeNodes);
                slots.add(slot);
            }

            recipeNode.add("ingredients", slots);
            recipeOptions.add(recipeNode);
        }

        node.add("recipes", recipeOptions);
        node.addProperty("recipesTruncated", producers.size() > 6);
        return node;
    }

    private static String itemIdFor(
            MinecraftKnowledgeService.KnowledgeView view,
            Candidate candidate
    ) {
        if (candidate == null) {
            return "";
        }
        if ("item".equals(candidate.kind())) {
            return candidate.id();
        }
        if ("block".equals(candidate.kind())) {
            MinecraftKnowledgeService.SubjectFact fact =
                    view.subject("block", candidate.id());
            return fact == null ? "" : fact.itemFormId();
        }
        return "";
    }

    private static JsonObject modForNamespace(
            MinecraftKnowledgeService.KnowledgeView view,
            String namespace
    ) {
        MinecraftKnowledgeService.ModFact mod = view.modForNamespace(namespace);
        if (mod == null) {
            JsonObject unresolved = new JsonObject();
            unresolved.addProperty("namespace", namespace == null ? "" : namespace);
            unresolved.addProperty("resolvedModContainer", false);
            return unresolved;
        }

        JsonObject out = new JsonObject();
        out.addProperty("id", mod.id());
        out.addProperty("name", mod.name());
        out.addProperty("version", mod.version());
        out.addProperty("environment", mod.environment());
        out.addProperty("description", mod.description());
        out.addProperty("resolvedModContainer", true);
        out.add("authors", stringArray(mod.authors()));
        out.add("licenses", stringArray(mod.licenses()));
        out.add("provides", stringArray(mod.provides()));
        return out;
    }

    private static JsonObject modDossier(
            MinecraftKnowledgeService.KnowledgeView view,
            String namespace
    ) {
        return modForNamespace(view, namespace);
    }

    private static JsonObject subjectFactJson(
            MinecraftKnowledgeService.SubjectFact fact
    ) {
        JsonObject out = new JsonObject();
        out.addProperty("id", fact.id());
        out.addProperty("name", fact.name());
        out.addProperty("namespace", fact.namespace());
        out.addProperty("source", fact.source());

        for (Map.Entry<String, JsonElement> entry : fact.attributes().entrySet()) {
            out.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return out;
    }

    private static Map<String, RegistrySummary> registrySummaries(
            List<MinecraftKnowledgeService.RegistryEntryFact> entries
    ) {
        LinkedHashMap<String, MutableRegistrySummary> grouped = new LinkedHashMap<>();

        for (MinecraftKnowledgeService.RegistryEntryFact entry : entries) {
            String source = entry.source();
            boolean synchronizedRegistry = source.startsWith("synchronized_registry:");
            boolean staticRegistry = source.startsWith("static_registry:");
            if (!synchronizedRegistry && !staticRegistry) {
                continue;
            }

            String registryId = source.substring(source.indexOf(':') + 1);
            String key = (synchronizedRegistry ? "dynamic|" : "static|") + registryId;
            MutableRegistrySummary summary = grouped.computeIfAbsent(
                    key,
                    ignored -> new MutableRegistrySummary(
                            registryId,
                            entry.kind(),
                            synchronizedRegistry
                    )
            );
            summary.increment();
        }

        LinkedHashMap<String, RegistrySummary> out = new LinkedHashMap<>();
        grouped.forEach((key, value) -> out.put(
                key,
                new RegistrySummary(
                        value.registryId,
                        value.kind,
                        value.count,
                        value.synchronizedRegistry
                )
        ));
        return Map.copyOf(out);
    }

    private static JsonObject baseOutput(String query, String value) {
        JsonObject out = new JsonObject();
        out.addProperty("query", query);
        out.addProperty("value", value == null ? "" : value);
        out.addProperty("semantic", true);
        out.addProperty("bounded", true);
        out.addProperty("factLayer", "MinecraftKnowledgeService");
        return out;
    }

    private static JsonObject candidateJson(Candidate candidate) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", candidate.kind());
        out.addProperty("id", candidate.id());
        out.addProperty("name", candidate.name());
        out.addProperty("namespace", namespace(candidate.id()));
        out.addProperty("score", candidate.score());
        out.addProperty("source", candidate.source());
        return out;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray out = new JsonArray();
        if (values != null) {
            values.forEach(out::add);
        }
        return out;
    }

    private static String semanticNeedle(String query) {
        String normalized = normalizeText(query);
        List<String> tokens = meaningfulTokens(normalized);
        if (tokens.isEmpty()) {
            return normalized;
        }
        return String.join(" ", tokens);
    }

    private static List<String> meaningfulTokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalizeText(value).split("[^a-z0-9_:.\\-/]+")) {
            String clean = token.strip();
            if (clean.length() < 2 || STOP_WORDS.contains(clean)) {
                continue;
            }
            tokens.add(clean);
        }
        return List.copyOf(tokens);
    }

    private static boolean equalsSemantic(String value, String query) {
        if (value == null || query == null) {
            return false;
        }

        String normalizedValue = normalizeText(value);
        String normalizedQuery = normalizeText(query);
        if (normalizedValue.equals(normalizedQuery)) {
            return true;
        }

        List<String> tokens = meaningfulTokens(normalizedQuery);
        return tokens.size() == 1
                && (normalizedValue.equals(tokens.get(0))
                || normalizeText(idPath(value)).equals(tokens.get(0)));
    }

    private static boolean containsSemantic(String value, String query) {
        if (value == null || query == null) {
            return false;
        }

        String normalizedValue = normalizeText(value);
        List<String> tokens = meaningfulTokens(query);
        if (tokens.isEmpty()) {
            return normalizedValue.contains(normalizeText(query));
        }

        for (String token : tokens) {
            if (!normalizedValue.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_')
                .strip();
    }

    private static String canonicalKind(String value) {
        return switch (normalize(value)) {
            case "items" -> "item";
            case "blocks" -> "block";
            case "entity", "entities" -> "entity_type";
            case "effect", "effects", "mob_effect" -> "status_effect";
            case "enchantments" -> "enchantment";
            case "recipes" -> "recipe";
            case "advancements" -> "advancement";
            case "structures" -> "structure";
            case "biomes" -> "biome";
            case "dimension", "dimensions", "dimension_types" -> "dimension_type";
            case "sound", "sounds" -> "sound_event";
            case "mods" -> "mod";
            default -> normalize(value);
        };
    }

    private static String idPath(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        int colon = id.indexOf(':');
        return colon >= 0 && colon + 1 < id.length()
                ? id.substring(colon + 1)
                : id;
    }

    private static String namespace(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "";
    }

    private static List<String> normalizeFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String clean = field.strip();
            if (clean.length() <= 64) {
                out.add(clean);
            }
            if (out.size() >= 24) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static JsonObject selectTopLevelFields(
            JsonObject source,
            List<String> fields
    ) {
        if (source == null || fields == null || fields.isEmpty()) {
            return source == null ? new JsonObject() : source;
        }

        JsonObject out = new JsonObject();
        for (String field : fields) {
            if (source.has(field)) {
                out.add(field, source.get(field).deepCopy());
            }
        }
        return out;
    }

    private static Result success(JsonObject output, String detail) {
        return new Result(true, output, detail);
    }

    private static Result unavailable(String detail) {
        return new Result(false, new JsonObject(), detail);
    }

    private record Frontier(
            JsonArray nodes,
            int totalCount,
            boolean truncated,
            List<String> suggestedIds
    ) {
        Frontier {
            nodes = nodes == null ? new JsonArray() : nodes.deepCopy();
            totalCount = Math.max(0, totalCount);
            suggestedIds = List.copyOf(suggestedIds == null ? List.of() : suggestedIds);
        }
    }

    public record Result(
            boolean available,
            JsonObject output,
            String detail
    ) {
        public Result {
            output = output == null ? new JsonObject() : output.deepCopy();
            detail = detail == null ? "" : detail;
        }
    }

    private record Candidate(
            String kind,
            String id,
            String name,
            int score,
            String source
    ) {
        Candidate {
            kind = kind == null ? "" : kind;
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            source = source == null ? "" : source;
        }
    }

    private record RecipeMatch(
            MinecraftKnowledgeService.RecipeFact recipe,
            int score,
            List<String> reasons
    ) {
    }

    private record AdvancementMatch(
            MinecraftKnowledgeService.AdvancementFact advancement,
            int score,
            List<String> reasons
    ) {
    }

    private record RegistrySummary(
            String registryId,
            String kind,
            int entryCount,
            boolean synchronizedRegistry
    ) {
    }

    private static final class MutableRegistrySummary {
        private final String registryId;
        private final String kind;
        private final boolean synchronizedRegistry;
        private int count;

        private MutableRegistrySummary(
                String registryId,
                String kind,
                boolean synchronizedRegistry
        ) {
            this.registryId = registryId;
            this.kind = kind;
            this.synchronizedRegistry = synchronizedRegistry;
        }

        private void increment() {
            count++;
        }
    }

    private static final class CraftBudget {
        private final int maximum;
        private int used;

        private CraftBudget(int maximum) {
            this.maximum = Math.max(1, maximum);
        }

        boolean consume() {
            if (used >= maximum) {
                return false;
            }
            used++;
            return true;
        }

        boolean exhausted() {
            return used >= maximum;
        }

        int used() {
            return used;
        }

        int maximum() {
            return maximum;
        }
    }
}
