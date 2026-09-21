package com.spirit.koil.api.model.skill;

import com.spirit.koil.api.model.retrieval.KnowledgeEntry;
import com.spirit.koil.api.model.retrieval.KnowledgeFilter;
import com.spirit.koil.api.model.retrieval.KnowledgeQuery;
import com.spirit.koil.api.model.retrieval.KnowledgeSourceSnapshot;
import com.spirit.koil.api.model.retrieval.KnowledgeTrust;
import com.spirit.koil.api.model.retrieval.KnowledgeType;
import com.spirit.koil.api.model.retrieval.KoilRetrievalEngine;

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
 * Koil's procedural guidance registry.
 *
 * <p>Bundled and imported Agent Skills are selected before provider inference,
 * so skill use does not depend on a model supporting native tool calls. Skills
 * remain procedural context only: they cannot grant tools, bypass approvals, or
 * directly execute bundled scripts.</p>
 */
public final class KoilSkillRegistry {
    private static final int MAXIMUM_SELECTIONS = 4;
    private static final int MAXIMUM_GUIDANCE_CHARACTERS = 4_800;
    private static final int MAXIMUM_STARTER_CHARACTERS_PER_SKILL = 1_050;
    private static final long FILESYSTEM_REFRESH_MILLIS = 3_000L;

    private static final List<KoilSkillDefinition> BUILT_INS = List.of(
            skill("koil.systematic-debugging", "Diagnose a failure from evidence before changing behavior.",
                    "root cause debugging failures crashes diagnostics recovery", List.of("debug", "debugging", "diagnose", "diagnostic", "failure", "crash", "root", "cause"),
                    "Systematic debugging: gather current evidence; compare a working pattern; state one hypothesis; run the smallest test; apply one root-cause repair; fresh observed verification decides recovery."),
            skill("koil.verification-before-completion", "Require fresh observed evidence before completion.",
                    "verification completion expected observed state evidence", List.of("verify", "verification", "complete", "completion", "validate", "validation", "test", "tests"),
                    "Completion gate: tool transport success is not objective success. Define expected state, obtain fresh observed evidence, compare it, and report VERIFIED, FAILED, or UNVERIFIED truthfully."),
            skill("koil.code-economy", "Use existing Koil systems before adding code or dependencies.",
                    "code fix implementation refactor dependency reuse root cause", List.of("implement", "implementation", "refactor", "fix", "code", "dependency", "dependencies", "review"),
                    "Code economy: trace the real flow; search existing Koil and platform capabilities; repair the shared root cause; add the minimum correct code; retain safety and runnable verification."),
            skill("koil.planning", "Turn a non-trivial objective into bounded executable steps.",
                    "plan planning design dependencies preconditions verification", List.of("plan", "planning", "design", "architecture", "steps", "workflow"),
                    "Planning: state the objective, available capabilities, preconditions, expected intermediate state, verification, and bounded recovery before execution. KTL and the Executor remain the action authority."),
            skill("koil.internet-research", "Collect only the smallest sufficient external evidence.",
                    "internet research search fetch scrape crawl evidence", List.of("research", "search", "fetch", "scrape", "crawl", "source", "sources"),
                    "Research: discover narrowly, fetch the smallest sufficient source, preserve provenance, use scraping only for structured or difficult pages, and stop when the evidence is sufficient.")
    );

    private static volatile List<KoilSkillDefinition> cachedDefinitions = BUILT_INS;
    private static volatile long filesystemLoadedAtMillis;

    private KoilSkillRegistry() {
    }

    /** Complete live registry including user/imported SKILL.md folders. */
    public static List<KoilSkillDefinition> definitions() {
        long now = System.currentTimeMillis();
        List<KoilSkillDefinition> snapshot = cachedDefinitions;
        if (now - filesystemLoadedAtMillis < FILESYSTEM_REFRESH_MILLIS) return snapshot;
        synchronized (KoilSkillRegistry.class) {
            now = System.currentTimeMillis();
            if (now - filesystemLoadedAtMillis < FILESYSTEM_REFRESH_MILLIS) return cachedDefinitions;
            LinkedHashMap<String, KoilSkillDefinition> merged = new LinkedHashMap<>();
            BUILT_INS.forEach(skill -> merged.put(skill.id(), skill));
            for (KoilSkillDefinition skill : KoilSkillFilesystem.load()) {
                merged.putIfAbsent(skill.id(), skill);
            }
            cachedDefinitions = List.copyOf(merged.values());
            filesystemLoadedAtMillis = now;
            return cachedDefinitions;
        }
    }

    /** Forces the next lookup to rescan the user Skill directory. */
    public static void refresh() {
        filesystemLoadedAtMillis = 0L;
        definitions();
    }

    public static java.nio.file.Path skillRoot() {
        return KoilSkillFilesystem.root();
    }

    public static List<KoilSkillSelection> resolve(String objective, KoilSkillMode mode) {
        if (objective == null || objective.isBlank() || mode == null) return List.of();
        Set<String> words = tokens(objective);
        List<Scored> candidates = new ArrayList<>();
        for (KoilSkillDefinition skill : definitions()) {
            if (!eligible(skill, mode)) continue;
            int score = lexicalScore(skill, words);
            if (score > 0) candidates.add(new Scored(skill, score));
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(candidate -> candidate.skill().id()))
                .limit(MAXIMUM_SELECTIONS)
                .map(candidate -> new KoilSkillSelection(candidate.skill(), "lexical:" + candidate.score()))
                .toList();
    }

    /** Synchronizes all current descriptors into the shared retrieval authority. */
    public static CompletableFuture<?> synchronize(KoilRetrievalEngine engine) {
        if (engine == null) return CompletableFuture.completedFuture(null);
        return engine.synchronizeSource(knowledgeSnapshot());
    }

    /**
     * Semantic retrieval compares multiple similar skills and returns a bounded
     * non-exclusive set. Exact lexical candidates are retained and semantic
     * candidates fill the remaining slots, allowing complementary Skills to be
     * active together without dumping the complete catalog into model context.
     */
    public static CompletableFuture<List<KoilSkillSelection>> resolve(
            KoilRetrievalEngine engine, String objective, KoilSkillMode mode, String requestId
    ) {
        if (engine == null || objective == null || objective.isBlank() || mode == null) {
            return CompletableFuture.completedFuture(resolve(objective, mode));
        }
        List<KoilSkillSelection> lexical = resolve(objective, mode);
        return engine.retrieve(new KnowledgeQuery(objective,
                        new KnowledgeFilter(Set.of(KnowledgeType.TASK), Set.of(), Set.of(), Map.of("sourceId", "koil.skills"), 0L),
                        Math.max(MAXIMUM_SELECTIONS * 3, 8), 384, requestId, KnowledgeTrust.HISTORICAL_CONTEXT))
                .thenApply(result -> {
                    LinkedHashMap<String, KoilSkillSelection> selected = new LinkedHashMap<>();
                    lexical.forEach(item -> selected.put(item.definition().id(), item));
                    for (var candidate : result.selected()) {
                        KoilSkillDefinition skill = definition(candidate.entry().metadata().get("skillId"));
                        if (!eligible(skill, mode)) continue;
                        selected.putIfAbsent(skill.id(), new KoilSkillSelection(skill, "semantic"));
                        if (selected.size() >= MAXIMUM_SELECTIONS) break;
                    }
                    return selected.values().stream().limit(MAXIMUM_SELECTIONS).toList();
                })
                .exceptionally(ignored -> lexical);
    }

    public static String compactGuidance(String objective, KoilSkillMode mode) {
        return compactGuidance(resolve(objective, mode));
    }

    public static String compactGuidance(List<KoilSkillSelection> selections) {
        StringBuilder output = new StringBuilder();
        for (KoilSkillSelection selection : selections == null ? List.<KoilSkillSelection>of() : selections) {
            KoilSkillDefinition skill = selection.definition();
            String preview = starterPreview(skill);
            String item = "Starter Skill " + skill.id() + " [" + selection.activation() + "]"
                    + "\nSource: " + skill.source()
                    + "\nDescription: " + skill.description()
                    + (skill.declaredTools().isEmpty() ? "" : "\nDeclared tool hints: " + String.join(", ", skill.declaredTools()))
                    + "\nProcedure preview: " + preview
                    + "\nIf more detail is needed, call skill.inspect with this exact Skill id. "
                    + "The starter preview is intentionally bounded and is not the complete SKILL.md.";
            int remaining = MAXIMUM_GUIDANCE_CHARACTERS - output.length();
            if (remaining <= 0) break;
            if (output.length() > 0) output.append("\n\n");
            if (item.length() <= remaining) {
                output.append(item);
            } else {
                output.append(item, 0, Math.max(0, remaining));
                output.append("\n[Additional starter Skill context omitted by Koil context budget. Use skill.search/skill.inspect to expand deliberately.]");
                break;
            }
        }
        return output.toString();
    }

    private static String starterPreview(KoilSkillDefinition skill) {
        if (skill == null) return "";
        String guidance = skill.compactGuidance();
        if (guidance.length() <= MAXIMUM_STARTER_CHARACTERS_PER_SKILL) return guidance;
        int boundary = guidance.lastIndexOf('\n', MAXIMUM_STARTER_CHARACTERS_PER_SKILL);
        if (boundary < MAXIMUM_STARTER_CHARACTERS_PER_SKILL / 2) boundary = MAXIMUM_STARTER_CHARACTERS_PER_SKILL;
        return guidance.substring(0, boundary).strip()
                + "\n[Starter preview truncated. Inspect the Skill for the complete instructions.]";
    }

    public static KnowledgeSourceSnapshot knowledgeSnapshot() {
        List<KnowledgeEntry> entries = definitions().stream().map(skill -> new KnowledgeEntry(1L, KnowledgeType.TASK,
                "model", skill.id() + "\n" + skill.semanticSummary() + "\n" + skill.description(),
                "skill.registry", skill.id(), 0L, 0.7D, 0.9D, KnowledgeTrust.HISTORICAL_CONTEXT,
                Map.of("sourceId", "koil.skills", "sourceKey", "skill:" + skill.id(), "skillId", skill.id(),
                        "version", skill.version(), "trust", skill.trust().name(), "skillSource", skill.source()))).toList();
        return new KnowledgeSourceSnapshot("koil.skills", registryVersion(entries), entries);
    }

    private static String registryVersion(List<KnowledgeEntry> entries) {
        int hash = 1;
        for (KnowledgeEntry entry : entries) {
            hash = 31 * hash + Long.hashCode(entry.id());
            hash = 31 * hash + entry.text().hashCode();
        }
        return "skills-v2:" + entries.size() + ":" + Integer.toUnsignedString(hash, 36);
    }

    private static KoilSkillDefinition definition(String id) {
        if (id == null || id.isBlank()) return null;
        return definitions().stream().filter(skill -> skill.id().equals(id)).findFirst().orElse(null);
    }

    private static boolean eligible(KoilSkillDefinition skill, KoilSkillMode mode) {
        return skill != null && skill.modes().contains(mode);
    }

    private static int lexicalScore(KoilSkillDefinition skill, Set<String> words) {
        if (words.isEmpty()) return 0;
        int score = 0;
        Set<String> idTerms = tokens(skill.id());
        Set<String> descriptionTerms = tokens(skill.description());
        for (String word : words) {
            if (idTerms.contains(word)) score += 6;
            if (descriptionTerms.contains(word)) score += 4;
            if (skill.exactTerms().contains(word)) score += 5;
        }
        String objectivePhrase = String.join(" ", words);
        String normalizedId = skill.id().toLowerCase(Locale.ROOT).replace('-', ' ').replace('.', ' ');
        if (objectivePhrase.contains(normalizedId.strip())) score += 12;
        return score;
    }

    private static Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() >= 3)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static KoilSkillDefinition skill(String id, String description, String summary, List<String> terms, String guidance) {
        return new KoilSkillDefinition(id, "1", description, summary, terms,
                Set.of(KoilSkillMode.ASK, KoilSkillMode.DEEP_THOUGHT, KoilSkillMode.AUTOMATION),
                0, guidance, KoilSkillDefinition.Trust.KOIL_BUILT_IN);
    }

    private record Scored(KoilSkillDefinition skill, int score) {
    }
}
