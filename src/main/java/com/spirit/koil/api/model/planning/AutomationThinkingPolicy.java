package com.spirit.koil.api.model.planning;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects a bounded Automation reasoning budget from the objective itself.
 *
 * <p>The policy is intentionally deterministic and provider-neutral. It does
 * not decide whether a tool is legal, approved, or available. Koil's tool
 * registry, Planning Mode authorization, executor, KTL runtime, verification,
 * cancellation, and server/player permissions remain authoritative.</p>
 *
 * <p>Deep Thinking is opportunistic rather than mandatory. Enabling it allows
 * genuinely difficult objectives to receive a larger inspect/plan/act/verify
 * budget, while greetings and self-contained single actions stay cheap.</p>
 */
public final class AutomationThinkingPolicy {
    private static final int DIRECT_TOOL_CALLS = 8;
    private static final int DIRECT_PROVIDER_ROUNDS = 12;
    private static final int DIRECT_CONTINUATION_CORRECTIONS = 2;

    private static final int PLANNED_MIN_TOOL_CALLS = 10;
    private static final int PLANNED_MAX_TOOL_CALLS = 18;
    private static final int PLANNED_MIN_PROVIDER_ROUNDS = 16;
    private static final int PLANNED_MAX_PROVIDER_ROUNDS = 28;
    private static final int PLANNED_MIN_CONTINUATION_CORRECTIONS = 3;
    private static final int PLANNED_MAX_CONTINUATION_CORRECTIONS = 4;

    private static final int DEEP_MIN_TOOL_CALLS = 16;
    private static final int DEEP_MAX_TOOL_CALLS = 28;
    private static final int DEEP_MIN_PROVIDER_ROUNDS = 24;
    private static final int DEEP_MAX_PROVIDER_ROUNDS = 40;
    private static final int DEEP_MIN_CONTINUATION_CORRECTIONS = 4;
    private static final int DEEP_MAX_CONTINUATION_CORRECTIONS = 6;

    private static final int PLAN_SCORE_THRESHOLD = 7;
    private static final int DEEP_SCORE_THRESHOLD = 13;
    private static final int EXTREME_SCORE_THRESHOLD = 22;

    /**
     * User-visible actions and mutations that often correspond to distinct
     * Koil tools. Counting these is only a complexity signal. Tool selection
     * remains owned by LocalModelToolCatalog and the supplied schemas.
     */
    private static final Pattern ACTION_WORD = Pattern.compile(
            "\\b(?:walk|move|navigate|travel|pathfind|jump|look|interact|open|close|take|loot|store|stash|use|eat|equip|drop|throw|attack|kill|mine|break|place|farm|craft|smelt|give|grant|run|execute|read|search|find|fetch|inspect|create|edit|write|replace|delete|remove|copy|rename|build|compile|test|validate|verify|debug|fix|repair|refactor|improve|update|download|install|configure|plan|recover|retry|resume)\\b"
    );

    /** Explicit ordering or multi-stage language. */
    private static final Pattern SEQUENCE_SIGNAL = Pattern.compile(
            "\\b(?:then|next|afterwards|finally|subsequently|followed\\s+by)\\b"
                    + "|\\bfirst\\b.*\\b(?:then|next|finally)\\b"
                    + "|(?:^|\\s)\\d+[.)]\\s+"
    );

    /** Prerequisite, conditional, or state-dependent work. */
    private static final Pattern DEPENDENCY_SIGNAL = Pattern.compile(
            "\\b(?:if|unless|until|while|when|once|before|after|depending|depends|requires|required|prerequisite|only\\s+after|as\\s+soon\\s+as)\\b"
    );

    /** Branching/alternative decisions are more expensive than a linear list. */
    private static final Pattern BRANCH_SIGNAL = Pattern.compile(
            "\\b(?:otherwise|else|either|whichever|fallback|alternative|alternatively|depending\\s+on|based\\s+on|choose|best|safest|nearest)\\b"
    );

    /** The user explicitly asks for planning or decomposition. */
    private static final Pattern PLAN_SIGNAL = Pattern.compile(
            "\\b(?:plan|planning|strategy|strategize|workflow|roadmap|task\\s+graph|step[- ]by[- ]step|break\\s+down|decompose|sequence|orchestrate)\\b"
    );

    /** Development/workspace work tends to require inspect-mutate-validate cycles. */
    private static final Pattern DEVELOPMENT_SIGNAL = Pattern.compile(
            "\\b(?:file|files|directory|directories|folder|folders|workspace|project|repo|repository|code|coding|source|java|json|json5|yaml|toml|xml|markdown|gradle|ktl|mcfunction|compile|build|test|tests|lint|debug|bug|fix|refactor|implementation|class|method|package|dependency|dependencies)\\b"
    );

    /** Mutating development work benefits from an inspect/change/check plan. */
    private static final Pattern DEVELOPMENT_MUTATION_SIGNAL = Pattern.compile(
            "\\b(?:create|edit|write|replace|delete|remove|copy|rename|build|compile|test|lint|debug|fix|repair|refactor|improve|update|install|configure)\\b"
    );

    /** Investigation can require multiple read-only rounds before an action. */
    private static final Pattern INVESTIGATION_SIGNAL = Pattern.compile(
            "\\b(?:investigate|diagnose|diagnostic|inspect|analyze|analyse|research|compare|trace|determine|figure\\s+out|find\\s+out|root\\s+cause|why\\s+is|why\\s+does|what\\s+changed)\\b"
    );

    /** Recovery language usually means prior evidence must be interpreted. */
    private static final Pattern RECOVERY_SIGNAL = Pattern.compile(
            "\\b(?:recover|recovery|retry|resume|continue|failed|failure|blocked|stuck|broken|again|replan|re-plan|fallback)\\b"
    );

    /** Large target sets should not be treated as one trivial action. */
    private static final Pattern BROAD_SCOPE_SIGNAL = Pattern.compile(
            "\\b(?:all|every|each|entire|whole|everything|everywhere|multiple|many|several|batch|bulk|recursive|recursively|project-wide|repo-wide|workspace-wide)\\b"
    );

    /** Long-horizon goals imply several latent sub-objectives. */
    private static final Pattern LONG_HORIZON_SIGNAL = Pattern.compile(
            "\\b(?:starting\\s+from\\s+nothing|from\\s+scratch|end[- ]to[- ]end|fully|complete(?:ly)?|finish|entire\\s+task|whole\\s+task|set\\s+up|setup|prepare|automate|automation|farm|farming|mine\\s+resources|ender\\s+dragon|beat\\s+the\\s+game|build\\s+a\\s+base)\\b"
    );

    /** Destructive/irreversible-looking work deserves planning pressure. */
    private static final Pattern DESTRUCTIVE_SIGNAL = Pattern.compile(
            "\\b(?:delete|remove|overwrite|replace|wipe|clear|destroy|kill|drop|discard|reset|revert|uninstall)\\b"
    );

    /** Exactness-sensitive requests punish substitution and guessing. */
    private static final Pattern EXACTNESS_SIGNAL = Pattern.compile(
            "\\b(?:exact|exactly|specific|precise|coordinate|coordinates|path|id|identifier|namespace|amount|quantity|count|version|same|only|without\\s+changing|preserve)\\b"
    );

    /** Natural-language forms that explicitly request the model to act. */
    private static final Pattern ACTION_REQUEST_SIGNAL = Pattern.compile(
            "^(?:please\\s+)?(?:can|could|would|will)\\s+you\\b"
                    + "|^(?:please\\s+)?(?:do|make|go|walk|move|jump|open|take|store|use|attack|kill|mine|craft|run|read|search|create|edit|write|replace|delete|build|fix|update|plan|verify)\\b"
                    + "|\\b(?:i\\s+want\\s+you\\s+to|i\\s+need\\s+you\\s+to|have\\s+you|go\\s+ahead\\s+and)\\b"
    );

    private static final Pattern GREETING = Pattern.compile(
            "^(?:hi|hello|hey|yo|hiya|thanks|thank\\s+you|thx|how\\s+are\\s+you|good\\s+morning|good\\s+afternoon|good\\s+evening)[.!? ]*$"
    );

    private static final Pattern INFORMATION_QUESTION = Pattern.compile(
            "^(?:what|who|whom|whose|where|when|why|how|which|is|are|was|were|does|do|did|can|could|would|should)\\b.*[?]$"
    );

    private AutomationThinkingPolicy() {
    }

    public static Decision evaluate(String prompt, boolean deepThinkingEnabled) {
        return evaluate(prompt, deepThinkingEnabled, false);
    }

    /**
     * Evaluates the objective and returns only bounded budgets. This method does
     * not inspect live game/workspace state because doing so here would make
     * reasoning selection depend on side effects or unavailable context.
     */
    public static Decision evaluate(
            String prompt,
            boolean deepThinkingEnabled,
            boolean planningModeEnabled
    ) {
        Assessment assessment = assess(prompt);
        if (assessment.conversation()) {
            return Decision.conversational();
        }

        boolean deepActive = deepThinkingEnabled && assessment.deepWorthy();
        boolean includePlanTool = planningModeEnabled || assessment.requiresPlan() || deepActive;

        if (deepActive) {
            return deepDecision(assessment);
        }
        if (includePlanTool) {
            return plannedDecision(assessment, planningModeEnabled);
        }
        return Decision.direct();
    }

    /**
     * Produces an inspectable, side-effect-free explanation of how difficult an
     * objective looks. This is useful for tests, diagnostics, and future UI,
     * while {@link #evaluate(String, boolean, boolean)} remains the stable
     * execution-facing API.
     */
    public static Assessment assess(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return Assessment.conversationAssessment();
        }

        int actions = count(ACTION_WORD, normalized);
        boolean actionRequest = ACTION_REQUEST_SIGNAL.matcher(normalized).find();
        if (isConversation(normalized, actions, actionRequest)) {
            return Assessment.conversationAssessment();
        }

        int sequences = count(SEQUENCE_SIGNAL, normalized);
        int dependencies = count(DEPENDENCY_SIGNAL, normalized);
        int branches = count(BRANCH_SIGNAL, normalized);
        boolean explicitPlanning = PLAN_SIGNAL.matcher(normalized).find();
        boolean developmentWork = DEVELOPMENT_SIGNAL.matcher(normalized).find();
        boolean developmentMutation = DEVELOPMENT_MUTATION_SIGNAL.matcher(normalized).find();
        boolean investigationWork = INVESTIGATION_SIGNAL.matcher(normalized).find();
        boolean recoveryWork = RECOVERY_SIGNAL.matcher(normalized).find();
        boolean broadScope = BROAD_SCOPE_SIGNAL.matcher(normalized).find();
        boolean longHorizon = LONG_HORIZON_SIGNAL.matcher(normalized).find();
        boolean destructiveWork = DESTRUCTIVE_SIGNAL.matcher(normalized).find();
        boolean exactnessSensitive = EXACTNESS_SIGNAL.matcher(normalized).find();

        int score = 0;
        score += Math.min(actions, 6) * 2;
        score += Math.min(sequences, 3) * 3;
        score += Math.min(dependencies, 3) * 2;
        score += Math.min(branches, 2) * 3;
        if (explicitPlanning) score += 4;
        if (developmentWork) score += 3;
        if (developmentMutation) score += 2;
        if (investigationWork) score += 2;
        if (recoveryWork) score += 2;
        if (broadScope) score += 3;
        if (longHorizon) score += 4;
        if (destructiveWork) score += 2;
        if (exactnessSensitive) score += 1;

        int length = normalized.length();
        if (length > 260) score += 2;
        if (length > 600) score += 2;
        if (length > 1_200) score += 2;

        // Compound development work almost always needs an inspect/change/check loop.
        if (developmentWork && (actions >= 2 || investigationWork || recoveryWork)) {
            score += 3;
        }

        // "All/every/each" plus a mutating action is materially larger than a
        // single-target request even when the prompt is short.
        if (broadScope && actions > 0) {
            score += 2;
        }

        boolean requiresPlan = explicitPlanning
                || sequences > 0
                || dependencies >= 2
                || branches > 0
                || actions >= 3
                || longHorizon
                || score >= PLAN_SCORE_THRESHOLD
                || developmentMutation
                || developmentWork && (actions >= 2 || recoveryWork || destructiveWork)
                || broadScope && actions > 0;

        boolean deepWorthy = score >= DEEP_SCORE_THRESHOLD
                || actions >= 5
                || branches >= 2
                || sequences >= 2 && dependencies >= 2
                || longHorizon && (developmentWork || actions >= 3)
                || developmentWork && investigationWork && recoveryWork
                || normalized.length() > 900 && requiresPlan;

        Complexity complexity = score >= EXTREME_SCORE_THRESHOLD
                ? Complexity.EXTREME
                : deepWorthy
                ? Complexity.COMPLEX
                : requiresPlan
                ? Complexity.MODERATE
                : Complexity.SIMPLE;

        return new Assessment(
                false,
                complexity,
                score,
                actions,
                sequences,
                dependencies,
                branches,
                explicitPlanning,
                developmentWork,
                developmentMutation,
                investigationWork,
                recoveryWork,
                broadScope,
                longHorizon,
                destructiveWork,
                exactnessSensitive,
                requiresPlan,
                deepWorthy
        );
    }

    private static Decision plannedDecision(Assessment assessment, boolean planningModeEnabled) {
        int score = assessment.score();
        int toolCalls = clamp(
                PLANNED_MIN_TOOL_CALLS + Math.max(0, score - PLAN_SCORE_THRESHOLD) / 2,
                PLANNED_MIN_TOOL_CALLS,
                PLANNED_MAX_TOOL_CALLS
        );
        int providerRounds = clamp(
                PLANNED_MIN_PROVIDER_ROUNDS + Math.max(0, score - PLAN_SCORE_THRESHOLD),
                PLANNED_MIN_PROVIDER_ROUNDS,
                PLANNED_MAX_PROVIDER_ROUNDS
        );
        int corrections = score >= DEEP_SCORE_THRESHOLD
                ? PLANNED_MAX_CONTINUATION_CORRECTIONS
                : PLANNED_MIN_CONTINUATION_CORRECTIONS;

        // Forced Planning Mode should retain enough budget even for a tiny
        // objective because the plan review itself adds provider/tool rounds.
        if (planningModeEnabled) {
            toolCalls = Math.max(toolCalls, 12);
            providerRounds = Math.max(providerRounds, 20);
        }

        return new Decision(
                Depth.PLANNED,
                false,
                true,
                toolCalls,
                providerRounds,
                corrections
        );
    }

    private static Decision deepDecision(Assessment assessment) {
        int score = assessment.score();
        int toolCalls = clamp(
                DEEP_MIN_TOOL_CALLS + Math.max(0, score - DEEP_SCORE_THRESHOLD),
                DEEP_MIN_TOOL_CALLS,
                DEEP_MAX_TOOL_CALLS
        );
        int providerRounds = clamp(
                DEEP_MIN_PROVIDER_ROUNDS + Math.max(0, score - DEEP_SCORE_THRESHOLD) * 2,
                DEEP_MIN_PROVIDER_ROUNDS,
                DEEP_MAX_PROVIDER_ROUNDS
        );
        int corrections = DEEP_MIN_CONTINUATION_CORRECTIONS;
        if (score >= 18) corrections++;
        if (score >= EXTREME_SCORE_THRESHOLD) corrections++;
        corrections = Math.min(corrections, DEEP_MAX_CONTINUATION_CORRECTIONS);

        return new Decision(
                Depth.DEEP,
                true,
                true,
                toolCalls,
                providerRounds,
                corrections
        );
    }

    private static boolean isConversation(String normalized, int actions, boolean actionRequest) {
        if (GREETING.matcher(normalized).matches()) {
            return true;
        }
        // A question is conversational only when it does not also contain a
        // recognizable requested action. "Can you jump?" therefore remains an
        // Automation action while "What is a beacon?" remains conversational.
        return !actionRequest
                && actions == 0
                && INFORMATION_QUESTION.matcher(normalized).matches();
    }

    private static int count(Pattern pattern, String normalized) {
        int count = 0;
        Matcher matcher = pattern.matcher(normalized);
        while (matcher.find()) {
            count++;
        }
        return count;
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
        CONVERSATIONAL,
        DIRECT,
        PLANNED,
        DEEP
    }

    /** Human-readable complexity band for diagnostics and tests. */
    public enum Complexity {
        SIMPLE,
        MODERATE,
        COMPLEX,
        EXTREME
    }

    /**
     * Side-effect-free classification details. The booleans are intentionally
     * descriptive rather than permissions: seeing "destructiveWork" here does
     * not authorize that work, and seeing "requiresPlan" does not approve it.
     */
    public record Assessment(
            boolean conversation,
            Complexity complexity,
            int score,
            int actionCount,
            int sequenceSignals,
            int dependencySignals,
            int branchSignals,
            boolean explicitPlanning,
            boolean developmentWork,
            boolean developmentMutation,
            boolean investigationWork,
            boolean recoveryWork,
            boolean broadScope,
            boolean longHorizon,
            boolean destructiveWork,
            boolean exactnessSensitive,
            boolean requiresPlan,
            boolean deepWorthy
    ) {
        public boolean compound() {
            return actionCount >= 2
                    || sequenceSignals > 0
                    || dependencySignals > 0
                    || branchSignals > 0
                    || broadScope
                    || longHorizon;
        }

        public String primaryReason() {
            if (conversation) return "conversation";
            if (explicitPlanning) return "explicit_planning";
            if (branchSignals > 0) return "branching";
            if (longHorizon) return "long_horizon";
            if (developmentMutation) return "development_mutation";
            if (recoveryWork) return "recovery";
            if (sequenceSignals > 0) return "sequence";
            if (dependencySignals > 0) return "dependency";
            if (broadScope) return "broad_scope";
            if (investigationWork) return "investigation";
            if (actionCount > 0) return "direct_action";
            return "general";
        }

        private static Assessment conversationAssessment() {
            return new Assessment(
                    true,
                    Complexity.SIMPLE,
                    0,
                    0,
                    0,
                    0,
                    0,
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
                    false
            );
        }
    }

    /**
     * Stable execution-facing result. Existing accessors are intentionally
     * preserved so LocalModelService and latency/planning policies remain
     * source-compatible.
     *
     * <p>{@code maximumToolCalls} is a historical/advisory depth estimate only.
     * It must never be used as a hard session tool-call ceiling. Progress and
     * no-progress/repetition guards are authoritative for continuation.</p>
     */
    public record Decision(
            Depth depth,
            boolean deepActive,
            boolean includePlanTool,
            int maximumToolCalls,
            int maximumProviderRounds,
            int maximumContinuationCorrections
    ) {
        /**
         * Provider output budget for a normal Automation round. Tool-specific
         * compact paths may choose a smaller cap, but never a larger one.
         * Keeping this policy-owned prevents the runtime from silently turning
         * every small request into a long free-form generation.
         */
        public int maximumOutputTokens(boolean stagedExecution) {
            return switch (depth) {
                case CONVERSATIONAL -> 192;
                case DIRECT -> 256;
                case PLANNED -> stagedExecution ? 512 : 640;
                case DEEP -> stagedExecution ? 768 : 896;
            };
        }

        /**
         * Hard wall-clock ceiling for one provider round. This is deliberately
         * much larger than a normal healthy round and is not a fast-path. It
         * prevents a local provider from occupying an Automation session
         * indefinitely when generation stops making actionable progress.
         */
        public java.time.Duration maximumProviderRoundDuration() {
            return switch (depth) {
                case CONVERSATIONAL -> java.time.Duration.ofMinutes(3);
                case DIRECT -> java.time.Duration.ofMinutes(5);
                case PLANNED -> java.time.Duration.ofMinutes(12);
                case DEEP -> java.time.Duration.ofMinutes(20);
            };
        }

        private static Decision conversational() {
            return new Decision(Depth.CONVERSATIONAL, false, false, 0, 2, 5);
        }

        private static Decision direct() {
            return new Decision(
                    Depth.DIRECT,
                    false,
                    false,
                    DIRECT_TOOL_CALLS,
                    DIRECT_PROVIDER_ROUNDS,
                    DIRECT_CONTINUATION_CORRECTIONS
            );
        }
    }
}
