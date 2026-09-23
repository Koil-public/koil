package com.spirit.koil.api.model.reasoning;

import com.spirit.koil.api.model.ModelAgentCapabilityProfile;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.planning.ConversationalReasoningPolicy;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic controller for deciding when model-native reasoning is useful.
 *
 * <p>This deliberately sits above the legacy ask/automation budget policies.
 * Those policies still size the provider request and tool surface. This class
 * decides why another reasoning pass is allowed, whether native thinking is
 * useful at all, and what kind of progress can justify another pass.</p>
 */
public final class AgentReasoningController {
    private static final Pattern SOCIAL = Pattern.compile(
            "^(?:hi+|hello+|hey+(?:\\s+there)?|yo+|sup|hiya|howdy|thanks|thank you|thx|ok|okay|cool|nice|got it|good (?:morning|afternoon|evening|night))[.!?, ]*$"
    );
    private static final Pattern RECOVERY = Pattern.compile(
            "\\b(?:failed|failing|failure|error|exception|broken|stuck|retry|recover|recovery|resume|continue|regression|fix|repair)\\b"
    );
    private static final Pattern PLAN = Pattern.compile(
            "\\b(?:plan|planning|strategy|workflow|roadmap|step[- ]by[- ]step|decompose|orchestrate|architecture|redesign|refactor)\\b"
    );
    private static final Pattern VERIFY = Pattern.compile(
            "\\b(?:verify|verification|validate|validation|prove|proof|exact|exactly|correct|confirm|compile|build|test|check)\\b"
    );
    private static final Pattern RESEARCH = Pattern.compile(
            "\\b(?:research|investigate|analy[sz]e|diagnose|trace|compare|source|sources|documentation|current|latest|recent|today|why)\\b"
    );
    private static final Pattern MINECRAFT = Pattern.compile(
            "\\b(?:minecraft|item|block|entity|mob|biome|registry|recipe|enchantment|effect|dimension|structure|nbt|snbt|command)\\b"
    );
    private static final Pattern NAMESPACED_ID = Pattern.compile("\\b[a-z0-9_.-]+:[a-z0-9_./-]+\\b");
    private static final Pattern STYLE_REQUEST = Pattern.compile(
            "\\b(?:tone|formal|informal|casual|professional|wording|phrase|phrasing|rewrite|style|shorter|longer|concise|verbose)\\b"
    );

    private AgentReasoningController() {
    }

    public static Decision evaluate(
            String prompt,
            boolean automation,
            boolean deepThought,
            int conversationCharacters,
            ModelAgentCapabilityProfile profile,
            ConversationalReasoningPolicy.Decision askPolicy,
            AutomationThinkingPolicy.Decision automationPolicy,
            boolean hasTools,
            boolean minecraftEvidenceAvailable
    ) {
        String normalized = normalize(prompt);
        LinkedHashSet<Target> targets = new LinkedHashSet<>();
        boolean styleRequested = STYLE_REQUEST.matcher(normalized).find();

        if (normalized.isBlank() || SOCIAL.matcher(normalized).matches()) {
            return new Decision(
                    Mode.INSTANT,
                    Set.of(),
                    new Budget(0, 1, false, Verification.NONE),
                    styleRequested,
                    "trivial_conversation"
            );
        }

        if (RECOVERY.matcher(normalized).find()) targets.add(Target.FAILURE_RECOVERY);
        if (VERIFY.matcher(normalized).find()) targets.add(Target.VERIFICATION_REQUIRED);
        if (RESEARCH.matcher(normalized).find()) targets.add(Target.FACTUAL_UNCERTAINTY);
        if (conversationCharacters > 0 && containsContextReference(normalized)) targets.add(Target.CONTEXT_DEPENDENCY);

        boolean minecraft = MINECRAFT.matcher(normalized).find() || minecraftEvidenceAvailable;
        boolean explicitIdentifier = NAMESPACED_ID.matcher(normalized).find();
        if (minecraft && !explicitIdentifier && likelyIdentifierReference(normalized)) {
            targets.add(Target.IDENTIFIER_RESOLUTION);
        }

        if (automation) {
            if (automationPolicy != null && automationPolicy.includePlanTool() || PLAN.matcher(normalized).find()) {
                targets.add(Target.PLAN_REQUIRED);
            }
            if (hasTools) targets.add(Target.ACTION_REQUIRED);
            if (deepThought) targets.add(Target.FACTUAL_UNCERTAINTY);

            Mode mode;
            if (targets.contains(Target.PLAN_REQUIRED)) mode = Mode.PLAN;
            else if (targets.contains(Target.FAILURE_RECOVERY)) mode = Mode.RECOVER;
            else if (targets.contains(Target.VERIFICATION_REQUIRED)) mode = Mode.VERIFY;
            else if (targets.contains(Target.FACTUAL_UNCERTAINTY)) mode = Mode.INVESTIGATE;
            else mode = Mode.EXECUTE;

            int maximumContinuations = switch (mode) {
                case EXECUTE -> 1;
                case VERIFY, RESOLVE -> 2;
                case RECOVER -> 3;
                case INVESTIGATE -> deepThought ? 8 : 3;
                case PLAN -> deepThought ? 8 : 4;
                default -> 0;
            };
            boolean nativeReasoning = mode != Mode.EXECUTE || !hasTools;
            if (deepThought) nativeReasoning = true;
            return new Decision(
                    mode,
                    Set.copyOf(targets),
                    new Budget(maximumContinuations, legacyRounds(automationPolicy), nativeReasoning,
                            targets.contains(Target.VERIFICATION_REQUIRED) ? Verification.STRONG : Verification.EXECUTION),
                    styleRequested,
                    "automation_agent"
            );
        }

        boolean direct = askPolicy != null && askPolicy.depth() == ConversationalReasoningPolicy.Depth.DIRECT;
        Mode mode;
        if (targets.contains(Target.FAILURE_RECOVERY)) mode = Mode.RECOVER;
        else if (targets.contains(Target.IDENTIFIER_RESOLUTION)) mode = Mode.RESOLVE;
        else if (targets.contains(Target.VERIFICATION_REQUIRED)) mode = Mode.VERIFY;
        else if (targets.contains(Target.FACTUAL_UNCERTAINTY) || hasTools) mode = Mode.INVESTIGATE;
        else if (PLAN.matcher(normalized).find()) mode = Mode.PLAN;
        else mode = direct ? Mode.DIRECT : Mode.INVESTIGATE;

        int continuations = switch (mode) {
            case INSTANT, DIRECT -> 0;
            case RESOLVE, VERIFY -> 1;
            case RECOVER -> 2;
            case PLAN, INVESTIGATE -> deepThought ? 8 : 2;
            default -> 1;
        };
        boolean nativeReasoning = switch (mode) {
            case INSTANT, DIRECT -> false;
            // A supplied exact lookup is normally more useful than free-form
            // deliberation for identifier resolution.
            case RESOLVE -> !hasTools;
            default -> true;
        };
        if (deepThought) nativeReasoning = true;

        return new Decision(
                mode,
                Set.copyOf(targets),
                new Budget(continuations, askPolicy == null ? 1 : askPolicy.maximumProviderRounds(), nativeReasoning,
                        targets.contains(Target.VERIFICATION_REQUIRED) ? Verification.STRONG
                                : hasTools ? Verification.LIGHT : Verification.NONE),
                styleRequested,
                "conversational_agent"
        );
    }

    public static String promptDirective(Decision decision) {
        if (decision == null || decision.mode() == Mode.INSTANT || decision.mode() == Mode.DIRECT) return "";
        String targets = decision.targets().isEmpty()
                ? "substantive_answer"
                : decision.targets().stream().map(value -> value.name().toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + "," + b).orElse("substantive_answer");
        return """
                Koil agent reasoning control (runtime-enforced):
                - mode=%s; unresolved_targets=%s.
                - Spend another reasoning pass only to resolve one listed target, incorporate genuinely new evidence, choose a concrete tool/action, recover from an observed failure, or verify a consequential result.
                - Once a decision is supported, do not reopen it unless new evidence contradicts it.
                - Do not debate response length, tone, wording, politeness, or whether a simple answer is too short unless the user explicitly requested a style choice.
                - When a supplied read-only tool can settle a factual or identifier uncertainty, use that evidence instead of prolonged internal debate.
                - If no unresolved substantive target remains, answer now.
                """.formatted(decision.mode().name().toLowerCase(Locale.ROOT), targets).strip();
    }

    private static int legacyRounds(AutomationThinkingPolicy.Decision policy) {
        return policy == null ? 1 : Math.max(1, policy.maximumProviderRounds());
    }

    private static boolean containsContextReference(String value) {
        return value.matches(".*\\b(?:earlier|previous|before|again|continue|resume|same|that|prior|last time)\\b.*");
    }

    private static boolean likelyIdentifierReference(String value) {
        if (value.contains("registry") || value.contains("identifier") || value.contains(" id ")) return true;
        if (value.matches(".*\\b(?:item|block|entity|mob|biome|recipe|enchantment|effect|dimension|structure)\\b.*")) return true;
        // Common natural-language Minecraft action wording often contains the
        // registry subject without the words item/block, e.g. "golden apple".
        return value.matches(".*\\b(?:give|spawn|summon|place|craft|use|find|make|get)\\b.*");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    public enum Mode {
        INSTANT,
        DIRECT,
        RESOLVE,
        INVESTIGATE,
        PLAN,
        EXECUTE,
        VERIFY,
        RECOVER
    }

    public enum Target {
        IDENTIFIER_RESOLUTION,
        FACTUAL_UNCERTAINTY,
        CONTEXT_DEPENDENCY,
        PLAN_REQUIRED,
        ACTION_REQUIRED,
        VERIFICATION_REQUIRED,
        FAILURE_RECOVERY
    }

    public enum Verification {
        NONE,
        LIGHT,
        STRONG,
        EXECUTION
    }

    public record Budget(
            int maximumReasoningContinuations,
            int maximumProviderRounds,
            boolean nativeReasoningEnabled,
            Verification verification
    ) {
        public Budget {
            maximumReasoningContinuations = Math.max(0, maximumReasoningContinuations);
            maximumProviderRounds = Math.max(1, maximumProviderRounds);
            verification = verification == null ? Verification.NONE : verification;
        }
    }

    public record Decision(
            Mode mode,
            Set<Target> targets,
            Budget budget,
            boolean styleRequested,
            String reason
    ) {
        public Decision {
            mode = mode == null ? Mode.DIRECT : mode;
            targets = targets == null ? Set.of() : Set.copyOf(targets);
            budget = budget == null ? new Budget(0, 1, false, Verification.NONE) : budget;
            reason = reason == null ? "" : reason.strip();
        }
    }
}
