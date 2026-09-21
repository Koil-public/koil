package com.spirit.koil.api.model.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.cache.ModelPromptCacheIdentity;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolEnvironmentFingerprint;
import com.spirit.koil.api.model.ToolExecutionPolicy;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.retrieval.SemanticReasoningKnowledgeAdapter;
import com.spirit.koil.api.util.text.FuzzyTextMatcher;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent memoization for verified products of model reasoning.
 *
 * <p>This deliberately does not persist raw chain-of-thought. It stores only
 * externally verifiable conclusions such as a successful tool choice and its
 * canonical arguments, together with the dependency scope that made the
 * conclusion valid. Reuse is cheap and inspectable without teaching the model
 * to blindly replay stale thoughts.</p>
 *
 * <p>The store has two matching layers. Literal/fuzzy cue matching may reuse
 * arguments when the dependency fingerprint is still valid. A broader intent
 * signature ignores conversational filler and quantities, allowing Koil to
 * reuse the <em>route</em> for paraphrases while forcing fresh argument
 * grounding. Conflicting routes never take the deterministic fast path.</p>
 */
public final class SemanticReasoningMemoStore {
    private static final int FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 384;
    private static final int MAX_PROMPT_MEMOS = 4;
    private static final int MAX_VARIANTS = 8;
    private static final int FAST_ROUTE_MIN_SCORE = 900;
    private static final int ARGUMENT_REUSE_MIN_LITERAL_SCORE = 930;
    private static final int ROUTE_CONFLICT_MARGIN = 55;
    private static final long LIVE_TTL_MILLIS = 2_500L;
    private static final long CONNECTION_TTL_MILLIS = 30L * 60_000L;
    private static final long REMOTE_TTL_MILLIS = 5L * 60_000L;
    private static final long WORKSPACE_TTL_MILLIS = 12L * 60L * 60_000L;
    private static final long SESSION_TTL_MILLIS = 24L * 60L * 60_000L;
    private static final long IMMUTABLE_TTL_MILLIS = 90L * 24L * 60L * 60_000L;
    private static final String JVM_SESSION = UUID.randomUUID().toString();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Memo> MEMOS = new LinkedHashMap<>();
    private static volatile boolean loaded;

    private static final Set<String> INTENT_FILLER = Set.of(
            "please", "pls", "kindly", "hey", "okay", "ok", "just",
            "can", "could", "would", "will", "you", "want", "wanna",
            "need", "like", "basically", "actually", "then", "next"
    );
    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry("zero", "#"), Map.entry("one", "#"), Map.entry("two", "#"),
            Map.entry("three", "#"), Map.entry("four", "#"), Map.entry("five", "#"),
            Map.entry("six", "#"), Map.entry("seven", "#"), Map.entry("eight", "#"),
            Map.entry("nine", "#"), Map.entry("ten", "#"), Map.entry("eleven", "#"),
            Map.entry("twelve", "#"), Map.entry("thirteen", "#"), Map.entry("fourteen", "#"),
            Map.entry("fifteen", "#"), Map.entry("sixteen", "#"), Map.entry("seventeen", "#"),
            Map.entry("eighteen", "#"), Map.entry("nineteen", "#"), Map.entry("twenty", "#")
    );

    private SemanticReasoningMemoStore() {}

    /** Candidate captured at dispatch time so later async completion cannot lose its original objective. */
    public record Candidate(String cue, ModelToolCall call, ModelToolDefinition definition, String dependencyFingerprint) {
        public Candidate {
            cue = clean(cue);
            dependencyFingerprint = clean(dependencyFingerprint);
        }
    }

    public record Match(String cue, String toolId, JsonObject arguments, int score, int confidence,
                        int successes, int failures, ToolExecutionPolicy.FreshnessMode freshness,
                        boolean argumentsReusable, boolean fastRouteEligible) {
        public Match {
            cue = clean(cue);
            toolId = clean(toolId);
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
            freshness = freshness == null ? ToolExecutionPolicy.FreshnessMode.SESSION : freshness;
            score = Math.max(0, Math.min(1000, score));
            confidence = Math.max(0, Math.min(1000, confidence));
            successes = Math.max(0, successes);
            failures = Math.max(0, failures);
        }
    }

    public record Lookup(List<Match> matches, String promptContext, Set<String> suggestedToolIds) {
        public Lookup {
            matches = matches == null ? List.of() : List.copyOf(matches);
            promptContext = clean(promptContext);
            suggestedToolIds = suggestedToolIds == null ? Set.of() : Set.copyOf(suggestedToolIds);
        }

        public static Lookup empty() {
            return new Lookup(List.of(), "", Set.of());
        }
    }

    public static Candidate candidate(String cue, ModelToolCall call, ModelToolDefinition definition) {
        if (call == null || definition == null || clean(cue).isBlank()) return null;
        ToolExecutionPolicy policy = definition.executionPolicy();
        return new Candidate(cue, call, definition, dependencyFingerprint(call, policy));
    }

    /**
     * Observe the final result of a memo candidate. Success teaches a reusable
     * conclusion. Structural/tool-selection failures lower confidence. Transient
     * world-state failures do not poison an otherwise correct route.
     */
    public static void observe(Candidate candidate, ModelToolResult result) {
        if (candidate == null || result == null) return;
        if (!candidate.call().id().equals(result.callId()) || !candidate.call().toolId().equals(result.toolId())) return;
        if (result.completedAndValidated()) {
            remember(candidate, result);
        } else if (shouldPenalize(result)) {
            penalize(candidate, result);
        }
    }

    /** Admit only successful, validated tool work. Failed guesses never become a positive memo. */
    public static void remember(Candidate candidate, ModelToolResult result) {
        if (candidate == null || result == null || !result.completedAndValidated()) return;
        if (!candidate.call().id().equals(result.callId()) || !candidate.call().toolId().equals(result.toolId())) return;
        ModelToolDefinition definition = candidate.definition();
        ToolExecutionPolicy policy = definition.executionPolicy();
        String cue = clean(candidate.cue());
        if (cue.isBlank()) return;

        long now = System.currentTimeMillis();
        String normalized = FuzzyTextMatcher.normalize(cue);
        if (normalized.isBlank()) return;
        String signature = intentSignature(cue);
        String argumentKey = canonicalArguments(candidate.call().arguments());
        String key = normalized + '\u0000' + candidate.call().toolId() + '\u0000' + argumentKey;
        String fingerprint = dependencyFingerprint(candidate.call(), policy);
        if (fingerprint.isBlank()) fingerprint = candidate.dependencyFingerprint();

        synchronized (LOCK) {
            ensureLoaded();
            Memo previous = findMergeCandidate(candidate.call().toolId(), argumentKey, cue, signature);
            if (previous != null) key = previous.key;
            int successes = previous == null ? 1 : Math.min(10_000, previous.successes + 1);
            int failures = previous == null ? 0 : Math.max(0, previous.failures - 1);
            List<String> variants = mergeVariants(previous == null ? List.of() : previous.variants, cue);
            String representative = chooseRepresentative(previous == null ? "" : previous.cue, cue);
            Memo stored = new Memo(
                    key,
                    representative,
                    FuzzyTextMatcher.normalize(representative),
                    signature.isBlank() ? intentSignature(representative) : signature,
                    variants,
                    candidate.call().toolId(),
                    candidate.call().arguments().deepCopy(),
                    evidenceSummary(result),
                    policy.freshness().name(),
                    fingerprint,
                    LocalModelToolCatalog.version(),
                    previous == null ? now : previous.createdAtMillis,
                    now,
                    successes,
                    failures,
                    previous == null ? 0L : previous.lastFailedAtMillis,
                    now
            );
            MEMOS.put(key, stored);
            trim(now);
            persist();
            SemanticReasoningKnowledgeAdapter.recordVerified(
                    stored.key, stored.cue, stored.semanticSignature, stored.toolId, stored.arguments,
                    stored.evidenceSummary, stored.freshness, stored.successes, stored.failures, stored.lastVerifiedAtMillis);
        }
        LocalModelRuntimeLog.write("reasoning_memo_store",
                "tool=" + candidate.call().toolId() + " | freshness=" + policy.freshness().name().toLowerCase(Locale.ROOT)
                        + " | cue=" + abbreviate(cue, 180));
    }

    private static void penalize(Candidate candidate, ModelToolResult result) {
        long now = System.currentTimeMillis();
        String cue = clean(candidate.cue());
        String signature = intentSignature(cue);
        synchronized (LOCK) {
            ensureLoaded();
            List<Memo> related = new ArrayList<>();
            for (Memo memo : MEMOS.values()) {
                if (!memo.toolId.equals(candidate.call().toolId())) continue;
                int score = Math.max(bestLiteralScore(cue, memo), signatureScore(signature, memo.semanticSignature));
                if (score >= 880) related.add(memo);
            }
            boolean dirty = false;
            for (Memo memo : related) {
                memo.failures = Math.min(10_000, memo.failures + 1);
                memo.lastFailedAtMillis = now;
                memo.lastUsedAtMillis = now;
                dirty = true;
                if (memo.failures >= 3 && memo.failures >= memo.successes + 1) {
                    MEMOS.remove(memo.key);
                    SemanticReasoningKnowledgeAdapter.remove(memo.key);
                }
            }
            if (dirty) persist();
        }
        LocalModelRuntimeLog.write("reasoning_memo_penalty",
                "tool=" + candidate.call().toolId() + " | failure=" + clean(result.failureCode())
                        + " | cue=" + abbreviate(cue, 160));
    }

    public static Match bestMatch(Lookup lookup, String cue) {
        if (lookup == null || cue == null || cue.isBlank()) return null;
        Match best = null;
        int bestScore = 0;
        for (Match match : lookup.matches()) {
            if (!match.fastRouteEligible()) continue;
            int score = Math.max(match.score(), semanticMatchScore(cue, match.cue()));
            if (score < FAST_ROUTE_MIN_SCORE || score <= bestScore) continue;
            best = new Match(match.cue(), match.toolId(), match.arguments(), score, match.confidence(),
                    match.successes(), match.failures(), match.freshness(),
                    match.argumentsReusable(), match.fastRouteEligible());
            bestScore = score;
        }
        return best;
    }

    /** Merge retrieval over an original request and its ordered clauses. */
    public static Lookup lookupAll(List<String> cues) {
        if (cues == null || cues.isEmpty()) return Lookup.empty();
        LinkedHashMap<String, Match> unique = new LinkedHashMap<>();
        LinkedHashSet<String> selectedTools = new LinkedHashSet<>();
        StringBuilder context = new StringBuilder();
        for (String cue : cues) {
            Lookup lookup = lookup(cue);
            if (!lookup.promptContext().isBlank()) {
                if (context.length() == 0) {
                    context.append("Verified reusable conclusions (memoized; use them as prior verified work, but re-check current evidence when marked route-only):");
                }
                for (Match match : lookup.matches()) {
                    String key = match.toolId() + '\u0000' + match.cue() + '\u0000' + canonicalArguments(match.arguments());
                    Match previous = unique.get(key);
                    if (previous == null || match.score() > previous.score()) unique.put(key, match);
                }
                selectedTools.addAll(lookup.suggestedToolIds());
            }
        }
        if (unique.isEmpty()) return Lookup.empty();
        List<Match> matches = unique.values().stream()
                .sorted(Comparator.comparingInt(Match::confidence).reversed()
                        .thenComparing(Comparator.comparingInt(Match::score).reversed()))
                .limit(MAX_PROMPT_MEMOS)
                .toList();
        for (Match match : matches) appendContextLine(context, match);
        LinkedHashSet<String> finalTools = new LinkedHashSet<>();
        for (Match match : matches) if (selectedTools.contains(match.toolId())) finalTools.add(match.toolId());
        return new Lookup(matches, context.toString(), finalTools);
    }

    /**
     * Retrieve only high-confidence, currently valid conclusions. Arguments are
     * reusable only for a strong literal/near-literal cue match. Intent-signature
     * matches are route-only, which prevents quantity/target changes from
     * replaying stale arguments.
     */
    public static Lookup lookup(String prompt) {
        String query = clean(prompt);
        if (query.isBlank()) return Lookup.empty();
        String querySignature = intentSignature(query);
        long now = System.currentTimeMillis();
        List<ScoredMemo> ranked = new ArrayList<>();

        synchronized (LOCK) {
            ensureLoaded();
            boolean dirty = false;
            for (Memo memo : new ArrayList<>(MEMOS.values())) {
                ModelToolDefinition definition = definition(memo.toolId);
                if (definition == null || !LocalModelToolCatalog.version().equals(memo.toolRegistryVersion)) {
                    MEMOS.remove(memo.key);
                    dirty = true;
                    continue;
                }
                ToolExecutionPolicy.FreshnessMode freshness = parseFreshness(memo.freshness);
                if (now - memo.lastVerifiedAtMillis > IMMUTABLE_TTL_MILLIS) {
                    MEMOS.remove(memo.key);
                    dirty = true;
                    continue;
                }
                ModelToolCall replay = new ModelToolCall("memo-probe", memo.toolId, memo.arguments.deepCopy());
                int literalScore = bestLiteralScore(query, memo);
                int intentScore = signatureScore(querySignature, memo.semanticSignature);
                int rawScore = Math.max(literalScore, intentScore);
                if (rawScore < threshold(definition)) continue;
                boolean dependenciesValid = !expired(memo, freshness, now)
                        && dependencyFingerprint(replay, definition.executionPolicy()).equals(memo.dependencyFingerprint);
                boolean argumentsReusable = dependenciesValid && literalScore >= ARGUMENT_REUSE_MIN_LITERAL_SCORE;
                int confidence = confidence(rawScore, memo, now);
                ranked.add(new ScoredMemo(memo, rawScore, literalScore, intentScore,
                        confidence, definition, argumentsReusable));
            }
            if (dirty) persist();
        }

        ranked.sort(Comparator.<ScoredMemo>comparingInt(value -> value.confidence).reversed()
                .thenComparing(Comparator.comparingInt((ScoredMemo value) -> value.score).reversed())
                .thenComparing(Comparator.comparingInt((ScoredMemo value) -> value.memo.successes).reversed())
                .thenComparing(Comparator.comparingLong((ScoredMemo value) -> value.memo.lastUsedAtMillis).reversed()));
        if (ranked.isEmpty()) return Lookup.empty();

        Set<String> conflictedTools = conflictingTools(ranked);
        List<Match> matches = new ArrayList<>();
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        StringBuilder context = new StringBuilder("Verified reusable conclusions (memoized; use them as prior verified work, but re-check current evidence when marked route-only):");
        int count = 0;
        synchronized (LOCK) {
            for (ScoredMemo scored : ranked) {
                if (count >= MAX_PROMPT_MEMOS) break;
                Memo memo = scored.memo;
                ToolExecutionPolicy.FreshnessMode freshness = parseFreshness(memo.freshness);
                JsonObject reusableArguments = scored.argumentsReusable ? memo.arguments : new JsonObject();
                boolean fastRouteEligible = scored.score >= FAST_ROUTE_MIN_SCORE
                        && scored.confidence >= 760
                        && !conflictedTools.contains(memo.toolId);
                Match match = new Match(memo.cue, memo.toolId, reusableArguments, scored.score, scored.confidence,
                        memo.successes, memo.failures, freshness, scored.argumentsReusable, fastRouteEligible);
                matches.add(match);
                if (!conflictedTools.contains(memo.toolId)) tools.add(memo.toolId);
                appendContextLine(context, match);
                memo.lastUsedAtMillis = now;
                count++;
            }
            if (count > 0) persist();
        }
        if (!conflictedTools.isEmpty()) {
            context.append("\n- Similar prior intents produced conflicting tool routes; do not fast-replay those routes. Choose from current evidence.");
            LocalModelRuntimeLog.write("reasoning_memo_conflict",
                    "query=" + abbreviate(query, 180) + " | tools=" + String.join(",", conflictedTools));
        }
        return new Lookup(matches, context.toString(), tools);
    }

    private static void appendContextLine(StringBuilder context, Match match) {
        if (context == null || match == null) return;
        context.append("\n- ").append(quote(abbreviate(match.cue(), 160)))
                .append(" previously succeeded with ").append(match.toolId());
        if (match.argumentsReusable() && !match.arguments().entrySet().isEmpty()) {
            context.append(' ').append(abbreviate(canonicalArguments(match.arguments()), 240));
        }
        Memo backing = findMemo(match);
        if (match.argumentsReusable() && backing != null && !clean(backing.evidenceSummary).isBlank()) {
            context.append(" => ").append(abbreviate(backing.evidenceSummary, 360));
        }
        context.append(" [confidence ").append(match.confidence()).append("/1000, verified x")
                .append(match.successes());
        if (match.failures() > 0) context.append(", corrected/failure x").append(match.failures());
        context.append(", ").append(match.freshness().name().toLowerCase(Locale.ROOT));
        if (!match.argumentsReusable()) context.append(", route-only; re-ground arguments");
        if (!match.fastRouteEligible()) context.append(", model review required");
        context.append(']');
    }

    private static Set<String> conflictingTools(List<ScoredMemo> ranked) {
        if (ranked == null || ranked.size() < 2) return Set.of();
        ScoredMemo top = ranked.get(0);
        if (top.score < FAST_ROUTE_MIN_SCORE) return Set.of();
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        for (int i = 1; i < ranked.size(); i++) {
            ScoredMemo other = ranked.get(i);
            if (other.score < FAST_ROUTE_MIN_SCORE) break;
            if (top.memo.toolId.equals(other.memo.toolId)) continue;
            if (top.confidence - other.confidence > ROUTE_CONFLICT_MARGIN) continue;
            conflicts.add(top.memo.toolId);
            conflicts.add(other.memo.toolId);
        }
        return conflicts;
    }

    private static Memo findMemo(Match match) {
        if (match == null) return null;
        synchronized (LOCK) {
            for (Memo memo : MEMOS.values()) {
                if (memo.toolId.equals(match.toolId()) && memo.cue.equals(match.cue())) return memo;
            }
        }
        return null;
    }

    private static Memo findMergeCandidate(String toolId, String canonicalArgs, String cue, String signature) {
        Memo best = null;
        int bestScore = 0;
        for (Memo memo : MEMOS.values()) {
            if (!memo.toolId.equals(toolId)) continue;
            if (!canonicalArguments(memo.arguments).equals(canonicalArgs)) continue;
            int score = Math.max(bestLiteralScore(cue, memo), signatureScore(signature, memo.semanticSignature));
            if (score >= 900 && score > bestScore) {
                best = memo;
                bestScore = score;
            }
        }
        return best;
    }

    private static List<String> mergeVariants(List<String> existing, String cue) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (existing != null) {
            for (String value : existing) if (!clean(value).isBlank()) variants.add(clean(value));
        }
        if (!clean(cue).isBlank()) variants.add(clean(cue));
        while (variants.size() > MAX_VARIANTS) {
            String first = variants.iterator().next();
            variants.remove(first);
        }
        return List.copyOf(variants);
    }

    private static String chooseRepresentative(String current, String incoming) {
        String a = clean(current);
        String b = clean(incoming);
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        // Shorter successful phrasings make the memo context cheaper while all
        // variants remain available for retrieval.
        return b.length() < a.length() ? b : a;
    }

    private static int bestLiteralScore(String query, Memo memo) {
        int best = FuzzyTextMatcher.score(query, memo.cue);
        if (memo.variants != null) {
            for (String variant : memo.variants) best = Math.max(best, FuzzyTextMatcher.score(query, variant));
        }
        return best;
    }

    private static int semanticMatchScore(String query, String candidate) {
        int literal = FuzzyTextMatcher.score(query, candidate);
        int intent = signatureScore(intentSignature(query), intentSignature(candidate));
        return Math.max(literal, intent);
    }

    private static int signatureScore(String left, String right) {
        String a = clean(left);
        String b = clean(right);
        if (a.isBlank() || b.isBlank()) return 0;
        return FuzzyTextMatcher.score(a, b);
    }

    /**
     * Produce a compact lexical intent signature. It intentionally removes only
     * conversational filler and normalizes quantities. Targets such as "me",
     * "zombie", "stone", dimensions, item names and command names remain, so
     * semantically different actions do not collapse into one route.
     */
    static String intentSignature(String cue) {
        String normalized = FuzzyTextMatcher.normalize(cue);
        if (normalized.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String token : normalized.split(" ")) {
            if (token.isBlank() || INTENT_FILLER.contains(token)) continue;
            String canonical = NUMBER_WORDS.getOrDefault(token, token.matches("\\d+") ? "#" : token);
            if (out.length() > 0) out.append(' ');
            out.append(canonical);
        }
        return out.toString();
    }

    private static int confidence(int score, Memo memo, long now) {
        int successBonus = Math.min(90, Math.max(0, memo.successes - 1) * 12);
        int failurePenalty = Math.min(300, memo.failures * 85);
        int agePenalty = 0;
        long age = Math.max(0L, now - memo.lastVerifiedAtMillis);
        if (age > 30L * 24L * 60L * 60_000L) agePenalty = 80;
        else if (age > 7L * 24L * 60L * 60_000L) agePenalty = 35;
        if (memo.lastFailedAtMillis > memo.lastVerifiedAtMillis && now - memo.lastFailedAtMillis < 10L * 60_000L) {
            failurePenalty += 120;
        }
        return Math.max(0, Math.min(1000, score + successBonus - failurePenalty - agePenalty));
    }

    private static boolean shouldPenalize(ModelToolResult result) {
        if (result == null || result.completedAndValidated() || result.cancelled()) return false;
        String code = clean(result.failureCode()).toLowerCase(Locale.ROOT);
        if (code.isBlank()) return false;
        // These generally indicate stale/transient world state rather than a bad
        // reasoning route, so they should not teach the cache that the tool was wrong.
        if (code.contains("world_unavailable") || code.contains("not_found") || code.contains("no_target")
                || code.contains("cancel") || code.contains("declin") || code.contains("permission")
                || code.contains("objective_already_reached") || code.contains("timeout")) {
            return false;
        }
        return code.contains("unknown_tool") || code.contains("unsupported") || code.contains("not_implemented")
                || code.contains("invalid") || code.contains("argument") || code.contains("schema")
                || code.contains("parse") || code.contains("validation") || code.contains("wrong_tool")
                || code.contains("capability");
    }

    private static String evidenceSummary(ModelToolResult result) {
        if (result == null || result.output() == null || result.output().entrySet().isEmpty()) return "";
        return abbreviate(result.output().toString(), 640);
    }

    private static ModelToolDefinition definition(String toolId) {
        if (toolId == null || toolId.isBlank()) return null;
        for (ModelToolDefinition definition : LocalModelToolCatalog.allRegisteredTools()) {
            if (toolId.equals(definition.id())) return definition;
        }
        return null;
    }

    private static int threshold(ModelToolDefinition definition) {
        boolean consequential = definition.confirmationRequired() || !definition.sideEffects().isEmpty();
        return consequential ? 875 : 790;
    }

    private static boolean expired(Memo memo, ToolExecutionPolicy.FreshnessMode freshness, long now) {
        long ttl = switch (freshness) {
            case LIVE -> LIVE_TTL_MILLIS;
            case CONNECTION -> CONNECTION_TTL_MILLIS;
            case REMOTE -> REMOTE_TTL_MILLIS;
            case WORKSPACE -> WORKSPACE_TTL_MILLIS;
            case SESSION -> SESSION_TTL_MILLIS;
            case IMMUTABLE -> IMMUTABLE_TTL_MILLIS;
        };
        return now - memo.lastVerifiedAtMillis > ttl;
    }

    private static String dependencyFingerprint(ModelToolCall call, ToolExecutionPolicy policy) {
        if (policy == null) return "unknown";
        return switch (policy.freshness()) {
            case IMMUTABLE -> "immutable|tools=" + LocalModelToolCatalog.version();
            case SESSION -> "session|" + JVM_SESSION;
            case REMOTE -> "remote";
            case WORKSPACE, CONNECTION, LIVE -> ToolEnvironmentFingerprint.capture(call, policy);
        };
    }

    private static ToolExecutionPolicy.FreshnessMode parseFreshness(String raw) {
        try {
            return ToolExecutionPolicy.FreshnessMode.valueOf(clean(raw));
        } catch (RuntimeException ignored) {
            return ToolExecutionPolicy.FreshnessMode.SESSION;
        }
    }

    private static String canonicalArguments(JsonObject arguments) {
        if (arguments == null || arguments.entrySet().isEmpty()) return "{}";
        return ModelPromptCacheIdentity.canonicalJson(ModelPromptCacheIdentity.canonicalObject(arguments));
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = path();
        if (!Files.isRegularFile(path)) return;
        try {
            DiskState state = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), DiskState.class);
            if (state == null || (state.formatVersion != FORMAT_VERSION && state.formatVersion != LEGACY_FORMAT_VERSION)
                    || state.entries == null) return;
            for (Memo memo : state.entries) {
                if (memo == null || clean(memo.key).isBlank() || clean(memo.toolId).isBlank()) continue;
                if (memo.arguments == null) memo.arguments = new JsonObject();
                memo.evidenceSummary = clean(memo.evidenceSummary);
                memo.normalizedCue = clean(memo.normalizedCue).isBlank()
                        ? FuzzyTextMatcher.normalize(memo.cue) : memo.normalizedCue;
                memo.semanticSignature = clean(memo.semanticSignature).isBlank()
                        ? intentSignature(memo.cue) : memo.semanticSignature;
                memo.variants = mergeVariants(memo.variants, memo.cue);
                if (memo.lastUsedAtMillis <= 0L) memo.lastUsedAtMillis = memo.lastVerifiedAtMillis;
                MEMOS.put(memo.key, memo);
            }
            trim(System.currentTimeMillis());
            if (state.formatVersion != FORMAT_VERSION) persist();
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("reasoning_memo_load_failed", concise(failure));
        }
    }

    private static void trim(long now) {
        MEMOS.values().removeIf(memo -> now - memo.lastVerifiedAtMillis > IMMUTABLE_TTL_MILLIS);
        if (MEMOS.size() <= MAX_ENTRIES) return;
        List<Memo> oldest = new ArrayList<>(MEMOS.values());
        oldest.sort(Comparator
                .comparingInt((Memo value) -> value.failures > value.successes ? 0 : 1)
                .thenComparingLong(value -> value.lastUsedAtMillis));
        for (int i = 0; i < oldest.size() - MAX_ENTRIES; i++) MEMOS.remove(oldest.get(i).key);
    }

    private static void persist() {
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            DiskState state = new DiskState();
            state.formatVersion = FORMAT_VERSION;
            state.entries = new ArrayList<>(MEMOS.values());
            Files.writeString(temp, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("reasoning_memo_save_failed", concise(failure));
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("koil").resolve("model-reasoning-memos.json");
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String quote(String value) {
        return '"' + clean(value).replace("\"", "'") + '"';
    }

    private static String abbreviate(String value, int max) {
        String safe = clean(value).replaceAll("\\s+", " ");
        return safe.length() <= max ? safe : safe.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static final class ScoredMemo {
        final Memo memo;
        final int score;
        final int literalScore;
        final int intentScore;
        final int confidence;
        final ModelToolDefinition definition;
        final boolean argumentsReusable;

        ScoredMemo(Memo memo, int score, int literalScore, int intentScore, int confidence,
                   ModelToolDefinition definition, boolean argumentsReusable) {
            this.memo = memo;
            this.score = score;
            this.literalScore = literalScore;
            this.intentScore = intentScore;
            this.confidence = confidence;
            this.definition = definition;
            this.argumentsReusable = argumentsReusable;
        }
    }

    private static final class Memo {
        String key;
        String cue;
        String normalizedCue;
        String semanticSignature;
        List<String> variants;
        String toolId;
        JsonObject arguments;
        String evidenceSummary;
        String freshness;
        String dependencyFingerprint;
        String toolRegistryVersion;
        long createdAtMillis;
        long lastVerifiedAtMillis;
        int successes;
        int failures;
        long lastFailedAtMillis;
        long lastUsedAtMillis;

        Memo(String key, String cue, String normalizedCue, String semanticSignature, List<String> variants,
             String toolId, JsonObject arguments, String evidenceSummary,
             String freshness, String dependencyFingerprint, String toolRegistryVersion,
             long createdAtMillis, long lastVerifiedAtMillis, int successes, int failures,
             long lastFailedAtMillis, long lastUsedAtMillis) {
            this.key = key;
            this.cue = cue;
            this.normalizedCue = normalizedCue;
            this.semanticSignature = semanticSignature;
            this.variants = variants == null ? List.of() : List.copyOf(variants);
            this.toolId = toolId;
            this.arguments = arguments;
            this.evidenceSummary = clean(evidenceSummary);
            this.freshness = freshness;
            this.dependencyFingerprint = dependencyFingerprint;
            this.toolRegistryVersion = toolRegistryVersion;
            this.createdAtMillis = createdAtMillis;
            this.lastVerifiedAtMillis = lastVerifiedAtMillis;
            this.successes = successes;
            this.failures = failures;
            this.lastFailedAtMillis = lastFailedAtMillis;
            this.lastUsedAtMillis = lastUsedAtMillis;
        }
    }

    private static final class DiskState {
        int formatVersion;
        List<Memo> entries;
    }
}
