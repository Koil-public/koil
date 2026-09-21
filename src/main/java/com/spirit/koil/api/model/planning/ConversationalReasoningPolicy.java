package com.spirit.koil.api.model.planning;

import com.spirit.koil.api.model.ModelAgentCapabilityProfile;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects a bounded /ask reasoning and response budget from the user's current
 * request, conversation pressure, evidence needs, and the selected model's
 * effective capability profile.
 *
 * <p>This policy only shapes reasoning depth and provider budgets. It never
 * exposes private reasoning text, grants tools, changes permissions, or turns
 * /ask into Automation. Read-only grounding remains owned by the tool selector
 * and the supplied tool schemas.</p>
 */
public final class ConversationalReasoningPolicy {
    private static final int DIRECT_OUTPUT_TOKENS = 128;
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 8_192;
    private static final int MINIMUM_CONTEXT_REVIEW_CHARACTERS = 4_096;
    private static final int MAXIMUM_CONTEXT_REVIEW_CHARACTERS = 131_072;

    private static final Pattern QUESTION_START = Pattern.compile(
            "^(?:what|which|who|whose|when|where|why|how|can|could|would|should|is|are|am|do|does|did|will|has|have|had)\\b"
    );
    private static final Pattern SOCIAL_TURN = Pattern.compile(
            "^(?:hi|hello|hey|yo|sup|thanks|thank you|thx|good morning|good afternoon|good evening|good night|how are you|what's up|whats up|okay|ok|cool|nice|got it|understood)[.!? ]*$"
    );
    private static final Pattern ANALYSIS_WORD = Pattern.compile(
            "\\b(?:analy[sz]e|analysis|evaluate|assess|explain|reason|reasoning|investigate|diagnose|derive|interpret|review|audit|critique|tradeoff|tradeoffs|implication|implications|cause|causes|why)\\b"
    );
    private static final Pattern COMPARISON_WORD = Pattern.compile(
            "\\b(?:compare|comparison|versus|vs|difference|differences|similarities|better|worse|pros|cons|advantage|advantages|disadvantage|disadvantages)\\b"
    );
    private static final Pattern DESIGN_WORD = Pattern.compile(
            "\\b(?:design|architecture|architect|system|framework|protocol|policy|strategy|approach|workflow|structure|refactor|redesign)\\b"
    );
    private static final Pattern DEBUG_WORD = Pattern.compile(
            "\\b(?:debug|bug|error|exception|crash|failure|failed|failing|broken|fix|repair|regression|stacktrace|stack-trace|compile|compiler|build)\\b"
    );
    private static final Pattern PROOF_WORD = Pattern.compile(
            "\\b(?:prove|proof|theorem|derive|derivation|equation|calculate|calculation|mathematical|mathematically|logic|logical|formal|invariant)\\b"
    );
    private static final Pattern RESEARCH_WORD = Pattern.compile(
            "\\b(?:research|source|sources|citation|citations|evidence|verify|verification|fact-check|factcheck|documentation|docs|reference|references)\\b"
    );
    private static final Pattern FRESHNESS_WORD = Pattern.compile(
            "\\b(?:latest|current|currently|today|tonight|now|recent|recently|newest|updated|update|still|version|release|status)\\b"
    );
    private static final Pattern EXACTNESS_WORD = Pattern.compile(
            "\\b(?:exact|exactly|syntax|valid|validity|exists|exist|identifier|identifiers|registry|registries|property|properties|tag|tags|signature|signatures|schema|schemas|line|lines|version|versions)\\b"
    );
    private static final Pattern TECHNICAL_WORD = Pattern.compile(
            "\\b(?:code|coding|java|kotlin|python|javascript|typescript|json|yaml|toml|api|class|method|function|field|record|enum|interface|repository|repo|file|files|tool|tools|model|provider|runtime|prompt|token|context|algorithm|database|network|protocol)\\b"
    );
    private static final Pattern MINECRAFT_WORD = Pattern.compile(
            "\\b(?:minecraft|command|commands|registry|registries|entity|entities|mob|mobs|item|items|block|blocks|recipe|recipes|craft|crafting|advancement|advancements|nbt|snbt|biome|biomes|dimension|dimensions|datapack|datapacks|modded|mod|mods|fabric|server|world|player|players)\\b"
    );
    private static final Pattern CONTEXT_REFERENCE = Pattern.compile(
            "\\b(?:earlier|previous|previously|above|before|again|continue|continuing|resume|same|that file|that code|that answer|that issue|we discussed|we talked|you said|you gave|you sent|i sent|i gave|from before|last time|prior)\\b"
    );
    private static final Pattern MULTI_PART_MARKER = Pattern.compile(
            "(?:\\b(?:and then|then|after that|afterwards|first|second|third|next|finally|also|additionally|as well as|plus)\\b|(?:^|\\s)\\d+[.)]\\s|(?:^|\\n)\\s*[-*]\\s+)"
    );
    private static final Pattern CONDITIONAL_MARKER = Pattern.compile(
            "\\b(?:if|unless|when|whenever|depending on|otherwise|either|neither|whether|in case|provided that)\\b"
    );
    private static final Pattern LONG_FORM_MARKER = Pattern.compile(
            "\\b(?:in depth|in-depth|detailed|deep dive|deep-dive|comprehensive|thorough|exhaustive|fully explain|full explanation|step by step|step-by-step|multiple approaches|all approaches|every case)\\b"
    );
    private static final Pattern EXPLICIT_LONG_WORD_COUNT = Pattern.compile(
            "\\b(?:[5-9]\\d{2}|[1-9]\\d{3,})\\s+words?\\b"
    );
    private static final Pattern DIRECT_LOOKUP_MARKER = Pattern.compile(
            "^(?:define|translate|convert|spell|name|list|give me|tell me)\\b"
    );

    private ConversationalReasoningPolicy() {
    }

    public static Decision evaluate(
            String prompt,
            int conversationCharacters,
            ModelAgentCapabilityProfile profile,
            boolean deepThought
    ) {
        Assessment assessment = assess(prompt, conversationCharacters, profile);

        if (assessment.blank() || assessment.social()) {
            return direct(assessment);
        }

        // Deep Thought is explicit user intent, but a trivial social turn still
        // remains direct above. Substantive requests receive a larger bounded
        // investigation budget without changing /ask's read-only authority.
        if (deepThought) {
            return deepThought(assessment, profile);
        }

        if (assessment.complexity() == Complexity.EXTREME
                || assessment.complexity() == Complexity.COMPLEX
                || assessment.analytical()
                || assessment.comparison()
                || assessment.design()
                || assessment.debugging()
                || assessment.proof()
                || assessment.contextPressure()
                || assessment.longFormRequested()
                || assessment.research()
                && (assessment.freshnessSensitive()
                || assessment.exactnessSensitive()
                || assessment.multiPart())) {
            return extended(assessment, profile);
        }

        if (assessment.directCandidate()) {
            return direct(assessment);
        }

        return normal(assessment, profile);
    }

    /**
     * Returns the deterministic classification used to allocate the /ask
     * reasoning budget. This is intentionally inspectable for tests and UI
     * diagnostics without exposing model chain-of-thought.
     */
    public static Assessment assess(
            String prompt,
            int conversationCharacters,
            ModelAgentCapabilityProfile profile
    ) {
        String normalized = normalize(prompt);
        int safeConversationCharacters = Math.max(0, conversationCharacters);
        if (normalized.isBlank()) {
            return Assessment.blankAssessment(contextReviewThreshold(profile));
        }

        boolean social = SOCIAL_TURN.matcher(normalized).matches();
        boolean question = normalized.indexOf('?') >= 0 || QUESTION_START.matcher(normalized).find();
        boolean analytical = ANALYSIS_WORD.matcher(normalized).find();
        boolean comparison = COMPARISON_WORD.matcher(normalized).find();
        boolean design = DESIGN_WORD.matcher(normalized).find();
        boolean debugging = DEBUG_WORD.matcher(normalized).find();
        boolean proof = PROOF_WORD.matcher(normalized).find();
        boolean research = RESEARCH_WORD.matcher(normalized).find();
        boolean freshnessSensitive = FRESHNESS_WORD.matcher(normalized).find();
        boolean exactnessSensitive = EXACTNESS_WORD.matcher(normalized).find();
        boolean technical = TECHNICAL_WORD.matcher(normalized).find();
        boolean minecraftTopic = MINECRAFT_WORD.matcher(normalized).find();
        boolean contextReference = safeConversationCharacters > 0 && CONTEXT_REFERENCE.matcher(normalized).find();
        boolean multiPart = MULTI_PART_MARKER.matcher(normalized).find() || countQuestionMarks(normalized) > 1;
        boolean conditional = CONDITIONAL_MARKER.matcher(normalized).find();
        boolean longFormRequested = LONG_FORM_MARKER.matcher(normalized).find()
                || EXPLICIT_LONG_WORD_COUNT.matcher(normalized).find();

        int contextThreshold = contextReviewThreshold(profile);
        boolean contextPressure = safeConversationCharacters > contextThreshold;
        boolean heavyContextPressure = safeConversationCharacters > saturatingMultiply(contextThreshold, 2);
        boolean reviewContext = contextReference || contextPressure;

        int score = 0;
        if (normalized.length() > 180) score += 1;
        if (normalized.length() > 420) score += 1;
        if (normalized.length() > 900) score += 2;
        if (analytical) score += 2;
        if (comparison) score += 2;
        if (design) score += 3;
        if (debugging) score += 3;
        if (proof) score += 3;
        if (research) score += 2;
        if (freshnessSensitive) score += 1;
        if (exactnessSensitive) score += 1;
        if (technical) score += 1;
        if (multiPart) score += 2;
        if (conditional) score += 2;
        if (longFormRequested) score += 2;
        if (contextReference) score += 2;
        if (contextPressure) score += 2;
        if (heavyContextPressure) score += 2;
        if (countQuestionMarks(normalized) >= 3) score += 1;

        // Model profiles can recommend deeper reasoning, but profile metadata
        // never turns a genuinely simple conversational turn into a complex one.
        int recommendedDepth = profile == null ? 1 : Math.max(1, profile.recommendedReasoningDepth());
        if (!social && score > 0 && recommendedDepth >= 5) score += 1;
        if (!social && score >= 4 && recommendedDepth >= 7) score += 1;

        Complexity complexity = complexityFor(score);
        boolean groundedMinecraft = minecraftTopic
                && (question
                || analytical
                || research
                || freshnessSensitive
                || exactnessSensitive
                || debugging
                || complexity != Complexity.SIMPLE);

        boolean directLookup = DIRECT_LOOKUP_MARKER.matcher(normalized).find()
                && !multiPart
                && !conditional
                && !reviewContext
                && !groundedMinecraft
                && normalized.length() <= 120;
        boolean shortStatement = !question
                && normalized.length() < 100
                && !groundedMinecraft
                && !reviewContext
                && !analytical
                && !comparison
                && !design
                && !debugging
                && !proof
                && !research
                && !multiPart
                && !conditional;
        // Short, self-contained knowledge questions should not pay the full
        // conversational-agent prefill cost. This deliberately excludes live,
        // research, Minecraft-grounded, context-dependent, multi-part, and
        // analytical requests, all of which still receive the normal policy.
        boolean simpleKnowledgeQuestion = question
                && normalized.length() <= 180
                && !groundedMinecraft
                && !reviewContext
                && !analytical
                && !comparison
                && !design
                && !debugging
                && !proof
                && !research
                && !freshnessSensitive
                && !exactnessSensitive
                && !multiPart
                && !conditional
                && !longFormRequested;
        boolean directCandidate = social || directLookup || shortStatement || simpleKnowledgeQuestion;

        return new Assessment(
                normalized,
                false,
                social,
                question,
                analytical,
                comparison,
                design,
                debugging,
                proof,
                research,
                freshnessSensitive,
                exactnessSensitive,
                technical,
                minecraftTopic,
                groundedMinecraft,
                contextReference,
                contextPressure,
                heavyContextPressure,
                reviewContext,
                multiPart,
                conditional,
                longFormRequested,
                directCandidate,
                score,
                complexity,
                safeConversationCharacters,
                contextThreshold
        );
    }

    private static Decision direct(Assessment assessment) {
        return new Decision(
                Depth.DIRECT,
                false,
                assessment.reviewContext(),
                assessment.groundedMinecraft(),
                3,
                DIRECT_OUTPUT_TOKENS
        );
    }

    private static Decision normal(Assessment assessment, ModelAgentCapabilityProfile profile) {
        int rounds = assessment.groundedMinecraft() ? 4 : 2;
        if (assessment.reviewContext()) rounds = Math.max(rounds, 3);
        if (assessment.research() || assessment.freshnessSensitive() || assessment.exactnessSensitive()) {
            rounds = Math.max(rounds, 3);
        }
        if (staged(profile)) rounds = Math.min(5, rounds + 1);

        int tokens = 896;
        if (assessment.technical() || assessment.analytical()) tokens = 1_024;
        if (smallContext(profile)) tokens = Math.min(tokens, 768);

        return new Decision(
                Depth.NORMAL,
                false,
                assessment.reviewContext(),
                assessment.groundedMinecraft(),
                rounds,
                tokens
        );
    }

    private static Decision extended(Assessment assessment, ModelAgentCapabilityProfile profile) {
        int rounds = switch (assessment.complexity()) {
            case SIMPLE -> 3;
            case MODERATE -> 4;
            case COMPLEX -> 5;
            case EXTREME -> 7;
        };
        if (assessment.reviewContext()) rounds = Math.max(rounds, 4);
        if (staged(profile)) rounds += 1;
        if (weakPlanning(profile)) rounds += 1;
        rounds = clamp(rounds, 3, 9);

        int tokens = switch (assessment.complexity()) {
            case SIMPLE -> 1_024;
            case MODERATE -> 1_280;
            case COMPLEX -> 1_536;
            case EXTREME -> 2_048;
        };
        if (assessment.longFormRequested()) tokens += 384;
        if (assessment.proof() || assessment.design() || assessment.debugging()) tokens += 256;
        tokens = adaptOutputTokens(tokens, profile, 768, 2_560);

        return new Decision(
                Depth.EXTENDED,
                true,
                assessment.reviewContext(),
                assessment.groundedMinecraft(),
                rounds,
                tokens
        );
    }

    private static Decision deepThought(Assessment assessment, ModelAgentCapabilityProfile profile) {
        int rounds = switch (assessment.complexity()) {
            case SIMPLE -> 12;
            case MODERATE -> 16;
            case COMPLEX -> 24;
            case EXTREME -> 32;
        };
        if (assessment.reviewContext()) rounds = Math.max(rounds, 18);
        if (staged(profile) && rounds < 32) rounds += 2;
        rounds = clamp(rounds, 12, 32);

        int tokens = switch (assessment.complexity()) {
            case SIMPLE -> 1_536;
            case MODERATE -> 1_920;
            case COMPLEX -> 2_560;
            case EXTREME -> 3_072;
        };
        if (assessment.longFormRequested()) tokens += 512;
        if (assessment.proof() || assessment.design() || assessment.debugging()) tokens += 256;
        tokens = adaptOutputTokens(tokens, profile, 1_024, 4_096);

        return new Decision(
                Depth.DEEP_THOUGHT,
                true,
                assessment.reviewContext(),
                assessment.groundedMinecraft(),
                rounds,
                tokens
        );
    }

    private static int adaptOutputTokens(
            int requested,
            ModelAgentCapabilityProfile profile,
            int minimum,
            int maximum
    ) {
        int resolved = requested;
        int contextTokens = contextWindowTokens(profile);
        if (contextTokens > 0 && contextTokens <= 4_096) {
            resolved = Math.min(resolved, 1_024);
        } else if (contextTokens >= 16_384 && longPromptReliable(profile)) {
            resolved += 256;
        }
        if (profile != null && profile.recommendedReasoningDepth() >= 7) {
            resolved += 128;
        }
        return clamp(resolved, minimum, maximum);
    }

    private static int contextReviewThreshold(ModelAgentCapabilityProfile profile) {
        int contextTokens = contextWindowTokens(profile);
        long scaled = (long) contextTokens * 2L;
        int threshold = (int) Math.min(Integer.MAX_VALUE, scaled);
        threshold = clamp(
                threshold,
                MINIMUM_CONTEXT_REVIEW_CHARACTERS,
                MAXIMUM_CONTEXT_REVIEW_CHARACTERS
        );
        if (!longPromptReliable(profile)) {
            threshold = Math.min(threshold, 16_384);
        }
        return threshold;
    }

    private static int contextWindowTokens(ModelAgentCapabilityProfile profile) {
        if (profile == null || profile.contextWindowTokens() <= 0) {
            return DEFAULT_CONTEXT_WINDOW_TOKENS;
        }
        return profile.contextWindowTokens();
    }

    private static boolean staged(ModelAgentCapabilityProfile profile) {
        return profile != null && profile.stagedExecution();
    }

    private static boolean longPromptReliable(ModelAgentCapabilityProfile profile) {
        return profile == null || profile.longPromptReliable();
    }

    private static boolean smallContext(ModelAgentCapabilityProfile profile) {
        return profile != null && profile.contextWindowTokens() > 0 && profile.contextWindowTokens() <= 4_096;
    }

    private static boolean weakPlanning(ModelAgentCapabilityProfile profile) {
        return profile != null
                && profile.planningReliability() == ModelAgentCapabilityProfile.PlanningReliability.WEAK;
    }

    private static Complexity complexityFor(int score) {
        if (score >= 11) return Complexity.EXTREME;
        if (score >= 7) return Complexity.COMPLEX;
        if (score >= 3) return Complexity.MODERATE;
        return Complexity.SIMPLE;
    }

    private static int countQuestionMarks(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '?') count++;
        }
        return count;
    }

    private static int saturatingMultiply(int value, int factor) {
        long product = (long) value * factor;
        return product > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    public enum Depth {
        DIRECT,
        NORMAL,
        EXTENDED,
        DEEP_THOUGHT
    }

    public enum Complexity {
        SIMPLE,
        MODERATE,
        COMPLEX,
        EXTREME
    }

    public record Assessment(
            String normalizedPrompt,
            boolean blank,
            boolean social,
            boolean question,
            boolean analytical,
            boolean comparison,
            boolean design,
            boolean debugging,
            boolean proof,
            boolean research,
            boolean freshnessSensitive,
            boolean exactnessSensitive,
            boolean technical,
            boolean minecraftTopic,
            boolean groundedMinecraft,
            boolean contextReference,
            boolean contextPressure,
            boolean heavyContextPressure,
            boolean reviewContext,
            boolean multiPart,
            boolean conditional,
            boolean longFormRequested,
            boolean directCandidate,
            int score,
            Complexity complexity,
            int conversationCharacters,
            int contextReviewThresholdCharacters
    ) {
        private static Assessment blankAssessment(int contextReviewThresholdCharacters) {
            return new Assessment(
                    "",
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    0,
                    Complexity.SIMPLE,
                    0,
                    contextReviewThresholdCharacters
            );
        }
    }

    public record Decision(
            Depth depth,
            boolean answerNowAvailable,
            boolean reviewContext,
            boolean groundedMinecraft,
            int maximumProviderRounds,
            int maximumOutputTokens
    ) {
    }
}
