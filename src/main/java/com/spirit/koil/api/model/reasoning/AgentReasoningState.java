package com.spirit.koil.api.model.reasoning;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Session-local convergence guard for model-native reasoning continuations.
 * It does not inspect hidden provider state. It only evaluates reasoning text
 * that the provider explicitly returned to Koil and changes in observable tool
 * evidence supplied by the surrounding generation session.
 */
public final class AgentReasoningState {
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_:./-]{3,}");
    private static final Pattern PRESENTATION_DEBATE = Pattern.compile(
            "\\b(?:too short|too long|wording|phrase|phrasing|tone|formal|informal|casual|polite|verbosity|how should i say|how to phrase)\\b"
    );
    private static final Set<String> NOISE = Set.of(
            "wait", "actually", "maybe", "perhaps", "however", "though", "think", "thinking",
            "could", "would", "should", "answer", "response", "user", "need", "just", "also",
            "this", "that", "with", "from", "have", "what", "when", "where", "which", "then"
    );

    private final AgentReasoningController.Decision decision;
    private final AgentState agentState;
    private Set<String> previousConcepts = Set.of();
    private int previousEvidenceEpoch;
    private int continuationCount;

    public AgentReasoningState(AgentReasoningController.Decision decision) {
        this(decision, null);
    }

    public AgentReasoningState(AgentReasoningController.Decision decision, AgentState agentState) {
        this.decision = decision;
        this.agentState = agentState;
    }

    public synchronized ContinuationDecision observeReasoning(String reasoning, int evidenceEpoch) {
        continuationCount++;
        AgentReasoningController.Budget budget = decision.budget();
        if (!budget.nativeReasoningEnabled()) {
            return ContinuationDecision.finalizeNow("native_reasoning_disabled");
        }
        if (continuationCount > budget.maximumReasoningContinuations()) {
            return ContinuationDecision.finalizeNow("reasoning_budget_converged");
        }
        if (continuationCount > 1 && structuredStateResolved()) {
            return ContinuationDecision.finalizeNow("structured_agent_state_resolved");
        }

        String normalized = normalize(reasoning);
        boolean newEvidence = evidenceEpoch != previousEvidenceEpoch;
        Set<String> concepts = concepts(normalized);
        double novelty = novelty(previousConcepts, concepts);
        boolean presentationDebate = !decision.styleRequested() && PRESENTATION_DEBATE.matcher(normalized).find();
        boolean noSubstantiveDelta = !newEvidence && !previousConcepts.isEmpty() && novelty < 0.18D;

        previousEvidenceEpoch = evidenceEpoch;
        previousConcepts = concepts;

        if (presentationDebate) {
            return ContinuationDecision.finalizeNow("presentation_debate_is_not_reasoning_work");
        }
        if (noSubstantiveDelta) {
            return ContinuationDecision.finalizeNow("no_new_reasoning_delta");
        }
        return ContinuationDecision.continueReasoning(newEvidence ? "new_evidence" : "substantive_delta", novelty);
    }

    /**
     * Opens a fresh bounded reasoning phase after observable external progress,
     * such as a completed tool result. This is not called for another internal
     * thought pass, so unchanged self-dialogue cannot reset its own budget.
     */
    public synchronized void observeExternalProgress(int evidenceEpoch) {
        if (evidenceEpoch == this.previousEvidenceEpoch) return;
        this.previousEvidenceEpoch = evidenceEpoch;
        this.previousConcepts = Set.of();
        this.continuationCount = 0;
    }

    public AgentReasoningController.Decision decision() {
        return decision;
    }

    public synchronized int continuationCount() {
        return continuationCount;
    }

    private boolean structuredStateResolved() {
        if (this.agentState == null) return false;
        AgentState.Snapshot snapshot = this.agentState.snapshot();
        if (snapshot.hasOpenUnknowns() || snapshot.hasPendingVerification()) return false;
        AgentState.PlanStatus status = snapshot.plan().status();
        return status == AgentState.PlanStatus.NONE
                || status == AgentState.PlanStatus.COMPLETED
                || status == AgentState.PlanStatus.REJECTED;
    }

    private static Set<String> concepts(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        var matcher = TOKEN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (NOISE.contains(token)) continue;
            out.add(token);
            if (out.size() >= 192) break;
        }
        return Set.copyOf(out);
    }

    private static double novelty(Set<String> previous, Set<String> current) {
        if (current.isEmpty()) return 0.0D;
        if (previous == null || previous.isEmpty()) return 1.0D;
        int newConcepts = 0;
        for (String token : current) if (!previous.contains(token)) newConcepts++;
        return (double) newConcepts / (double) current.size();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    public record ContinuationDecision(boolean continueReasoning, String reason, double novelty) {
        static ContinuationDecision continueReasoning(String reason, double novelty) {
            return new ContinuationDecision(true, reason, novelty);
        }

        static ContinuationDecision finalizeNow(String reason) {
            return new ContinuationDecision(false, reason, 0.0D);
        }
    }
}
