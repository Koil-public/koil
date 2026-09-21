package com.spirit.koil.api.model.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies small, explainable priors after hybrid fusion. This never hard-filters a domain and never turns
 * historical evidence into authority; it only helps similarly scored candidates line up with the user's intent.
 */
final class EvidenceAwareRetrievalReranker {
    private static final Set<String> REPAIR = Set.of(
            "error", "errors", "fail", "failed", "failure", "exception", "crash", "broken", "fix", "repair",
            "cannot", "missing", "invalid", "timeout", "timed", "compile", "compiler", "symbol", "stacktrace", "stack"
    );
    private static final Set<String> WORKFLOW = Set.of(
            "workflow", "sequence", "plan", "steps", "step", "repeat", "schedule", "scheduler", "automation", "automate", "recipe"
    );
    private static final Set<String> ENVIRONMENT = Set.of(
            "environment", "system", "runtime", "cpu", "gpu", "memory", "ram", "disk", "storage", "process", "dependency",
            "dependencies", "installed", "available", "version", "java", "python", "node", "gradle", "maven", "cargo"
    );
    private static final Set<String> PERFORMANCE = Set.of(
            "performance", "speed", "slow", "fast", "latency", "benchmark", "throughput", "duration", "timing", "tokens", "tps"
    );
    private static final Set<String> COMMAND = Set.of(
            "command", "commands", "syntax", "valid", "validate", "validation", "inspect", "inspector", "brigadier", "parser", "execute"
    );
    private static final Set<String> EXTERNAL = Set.of(
            "web", "website", "url", "article", "source", "search", "internet", "page", "dataset", "research"
    );
    private static final Set<String> CODE = Set.of(
            "code", "source", "java", "python", "class", "method", "compile", "compiler", "build", "gradle", "maven", "stacktrace"
    );
    private static final Set<String> MINECRAFT = Set.of(
            "minecraft", "block", "item", "entity", "advancement", "recipe", "enchantment", "biome", "command", "registry", "nbt"
    );
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "i", "me", "my", "you", "your",
            "it", "this", "that", "to", "of", "for", "from", "with", "in", "on", "at", "by", "and", "or", "but",
            "if", "then", "whether", "how", "what", "can", "could", "would", "should", "do", "does", "did", "please", "checking", "check"
    );

    private EvidenceAwareRetrievalReranker() {}

    static List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> queryTokens = tokens(query);
        Intent intent = new Intent(
                intersects(queryTokens, REPAIR), intersects(queryTokens, WORKFLOW), intersects(queryTokens, ENVIRONMENT),
                intersects(queryTokens, PERFORMANCE), intersects(queryTokens, COMMAND), intersects(queryTokens, EXTERNAL),
                intersects(queryTokens, CODE), intersects(queryTokens, MINECRAFT)
        );
        ArrayList<RetrievalCandidate> out = new ArrayList<>(candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            KnowledgeEntry entry = candidate.entry();
            double score = candidate.fusedScore() + typePrior(entry, intent) + conceptPrior(entry, queryTokens, intent);
            out.add(new RetrievalCandidate(entry, candidate.semanticScore(), candidate.exactScore(), score, candidate.estimatedTokens()));
        }
        out.sort(Comparator.comparingDouble(RetrievalCandidate::fusedScore).reversed()
                .thenComparingLong(candidate -> candidate.entry().id()));
        return List.copyOf(out);
    }

    private static double typePrior(KnowledgeEntry entry, Intent intent) {
        double boost = 0.0D;
        switch (entry.type()) {
            case REPAIR_EPISODE -> boost += intent.repair ? 0.0100D : 0.0010D;
            case COUNTEREXAMPLE -> boost += intent.repair ? 0.0075D : -0.0010D;
            case WORKFLOW_RECIPE -> boost += intent.workflow ? 0.0080D : 0.0005D;
            case ENVIRONMENT_FACT -> boost += intent.environment ? 0.0080D : -0.0020D;
            case PERFORMANCE_OBSERVATION -> boost += intent.performance ? 0.0090D : -0.0050D;
            case EXTERNAL_EVIDENCE -> boost += intent.external ? 0.0060D : 0.0010D;
            case SOURCE_CODE -> boost += intent.code ? 0.0050D : 0.0D;
            case MINECRAFT_KNOWLEDGE -> boost += intent.minecraft ? 0.0050D : 0.0D;
            case TOOL -> boost += intent.command && commandLike(entry) ? 0.0070D : 0.0D;
            case KOIL_DOCUMENTATION -> {
                if (intent.command && commandLike(entry)) boost += 0.0060D;
                if (intent.code) boost += 0.0020D;
            }
            default -> { }
        }
        if (entry.type() == KnowledgeType.CONVERSATION && "true".equals(entry.metadata().get("modelAuthored"))) boost -= 0.0025D;
        return boost;
    }

    private static double conceptPrior(KnowledgeEntry entry, Set<String> queryTokens, Intent intent) {
        Set<String> candidateTokens = tokens(entry.text() + " " + entry.source() + " " + entry.scope() + " "
                + entry.metadata().getOrDefault("toolId", "") + " "
                + entry.metadata().getOrDefault("failureSignature", "") + " "
                + entry.metadata().getOrDefault("evidenceKind", ""));
        int overlap = 0;
        for (String token : queryTokens) if (candidateTokens.contains(token)) overlap++;
        double boost = Math.min(0.0045D, overlap * 0.0015D);

        // Domain coherence is a soft prior only. It helps natural-language queries such as "is this Minecraft
        // command valid" prefer command inspection over an unrelated generic process status result.
        if (intent.command && commandLike(candidateTokens)) boost += 0.0050D;
        if (intent.repair && intersects(candidateTokens, REPAIR)) boost += 0.0035D;
        if (intent.performance && intersects(candidateTokens, PERFORMANCE)) boost += 0.0035D;
        if (intent.environment && intersects(candidateTokens, ENVIRONMENT)) boost += 0.0030D;
        return boost;
    }

    private static boolean commandLike(KnowledgeEntry entry) {
        return commandLike(tokens(entry.text() + " " + entry.source() + " " + entry.metadata().getOrDefault("toolId", "")));
    }

    private static boolean commandLike(Set<String> tokens) {
        return tokens.contains("command") || tokens.contains("brigadier") || tokens.contains("parser")
                || tokens.contains("syntax") || (tokens.contains("inspect") && tokens.contains("minecraft"))
                || (tokens.contains("inspector") && tokens.contains("minecraft"));
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        for (String value : left) if (right.contains(value)) return true;
        return false;
    }

    private static Set<String> tokens(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        for (String token : value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isBlank() && !STOPWORDS.contains(token)) out.add(token);
        }
        return out;
    }

    private record Intent(boolean repair, boolean workflow, boolean environment, boolean performance,
                          boolean command, boolean external, boolean code, boolean minecraft) {}
}
