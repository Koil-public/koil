package com.spirit.koil.api.model.reasoning;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelObjectiveLedger;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.planning.ValidatedAutomationPlan;

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
 * Authoritative, request-local agent state shared by reasoning, planning,
 * execution, and verification.
 *
 * <p>The older specialist systems still parse objectives, validate plans, and
 * execute tools. They publish their observations into this state rather than
 * requiring later model rounds to reconstruct truth from several unrelated
 * fields. The state intentionally stores only observable facts and explicit
 * decisions. It never stores or exposes hidden chain-of-thought.</p>
 */
public final class AgentState {
    private static final int MAX_FACTS = 96;
    private static final int MAX_DECISIONS = 48;
    private static final int MAX_UNKNOWNS = 48;
    private static final int MAX_VERIFICATION_TARGETS = 64;
    private static final int MAX_TOOL_EVIDENCE = 64;
    private static final int MAX_ARGUMENT_BINDINGS = 128;
    private static final int MAX_CLAIMS = 64;

    private final String requestId;
    private final String objective;
    private final AgentReasoningController.Decision reasoningDecision;
    private final LinkedHashMap<String, Fact> facts = new LinkedHashMap<>();
    private final LinkedHashMap<String, Unknown> unknowns = new LinkedHashMap<>();
    private final LinkedHashMap<String, Decision> decisions = new LinkedHashMap<>();
    private final LinkedHashMap<String, VerificationTarget> verificationTargets = new LinkedHashMap<>();
    private final LinkedHashMap<String, ToolEvidence> toolEvidence = new LinkedHashMap<>();
    private final LinkedHashMap<String, ToolArgumentState> argumentBindings = new LinkedHashMap<>();
    private final LinkedHashMap<String, Claim> claims = new LinkedHashMap<>();
    private PlanState plan = PlanState.none();
    private long revision;
    private long evidenceEpoch;

    public AgentState(
            UUID requestId,
            String objective,
            AgentReasoningController.Decision reasoningDecision,
            List<ModelObjectiveLedger.Objective> objectives
    ) {
        this.requestId = requestId == null ? "" : requestId.toString();
        this.objective = clean(objective);
        this.reasoningDecision = reasoningDecision;
        seedReasoningTargets(reasoningDecision);
        seedObjectives(objectives);
    }

    private void seedReasoningTargets(AgentReasoningController.Decision controllerDecision) {
        if (controllerDecision == null) return;
        for (AgentReasoningController.Target target : controllerDecision.targets()) {
            String key = "reasoning:" + target.name().toLowerCase(Locale.ROOT);
            addUnknownInternal(new Unknown(
                    key,
                    describeTarget(target),
                    target.name().toLowerCase(Locale.ROOT),
                    UnknownStatus.OPEN,
                    "reasoning_controller",
                    "",
                    revision
            ));
            if (target == AgentReasoningController.Target.VERIFICATION_REQUIRED) {
                putVerification(new VerificationTarget(
                        "verify:reasoning-target",
                        "Verify the consequential result with observable evidence",
                        "reasoning_controller",
                        VerificationStatus.PENDING,
                        "",
                        revision
                ));
            }
        }
        addDecisionInternal(new Decision(
                "reasoning-mode",
                "reasoning_mode",
                controllerDecision.mode().name().toLowerCase(Locale.ROOT),
                1.0D,
                Set.of("reasoning_controller"),
                true,
                revision
        ));
    }

    private void seedObjectives(List<ModelObjectiveLedger.Objective> objectives) {
        if (objectives == null) return;
        for (ModelObjectiveLedger.Objective objective : objectives) {
            if (objective == null) continue;
            String key = "objective:" + objective.id();
            addUnknownInternal(new Unknown(
                    key,
                    clean(objective.text()),
                    "objective",
                    objective.state() == ModelObjectiveLedger.State.COMPLETED
                            ? UnknownStatus.RESOLVED : UnknownStatus.OPEN,
                    objective.toolId(),
                    clean(objective.requiredEvidence()),
                    revision
            ));
            if (!clean(objective.requiredEvidence()).isBlank()) {
                putVerification(new VerificationTarget(
                        "verify:" + objective.id(),
                        clean(objective.requiredEvidence()),
                        objective.toolId(),
                        VerificationStatus.PENDING,
                        "",
                        revision
                ));
            }
        }
    }

    /**
     * Re-publishes the current ordered frontier into bounded AgentState. Long
     * objectives may contain far more task occurrences than MAX_UNKNOWNS; as
     * the ledger advances, this guarantees the newly-current task is present
     * even if its original seed entry was trimmed long ago.
     */
    public synchronized void focusOrderedObjectives(List<ModelObjectiveLedger.Objective> objectives) {
        if (objectives == null || objectives.isEmpty()) return;
        revision++;
        for (ModelObjectiveLedger.Objective objective : objectives) {
            if (objective == null) continue;
            String key = "objective:" + objective.id();
            unknowns.put(key, new Unknown(
                    key, clean(objective.text()), "objective", UnknownStatus.OPEN,
                    objective.toolId(), clean(objective.requiredEvidence()), revision
            ));
            if (!clean(objective.requiredEvidence()).isBlank()) {
                putVerification(new VerificationTarget(
                        "verify:" + objective.id(), clean(objective.requiredEvidence()), objective.toolId(),
                        VerificationStatus.PENDING, "", revision
                ));
            }
        }
        trim();
    }

    public synchronized void observeToolResult(ModelToolResult result) {
        observeToolResult(result, true);
    }

    public synchronized void observeToolResult(ModelToolResult result, boolean satisfiesOrderedObjective) {
        if (result == null) return;
        evidenceEpoch++;
        revision++;

        String source = clean(result.toolId());
        String status = clean(result.status()).toLowerCase(Locale.ROOT);
        boolean success = result.completedAndValidated() || "already_satisfied".equals(status);
        String factId = "tool:" + clean(result.callId()) + ":" + evidenceEpoch;
        String statement = toolObservation(result);
        String evidenceId = "evidence:" + clean(result.callId()) + ":" + evidenceEpoch;
        toolEvidence.put(evidenceId, new ToolEvidence(
                evidenceId, clean(result.callId()), source,
                result.output() == null ? new JsonObject() : result.output().deepCopy(),
                success, evidenceEpoch, revision
        ));
        putFact(new Fact(
                factId,
                statement,
                source,
                success ? 1.0D : 0.9D,
                success ? FactKind.OBSERVATION : FactKind.FAILURE,
                evidenceEpoch,
                revision
        ));

        if (success) {
            resolveUnknownsSupportedBy(source, factId, satisfiesOrderedObjective);
            addDecisionInternal(new Decision(
                    "tool-result:" + clean(result.callId()),
                    "tool_result:" + source,
                    "completed",
                    1.0D,
                    Set.of(factId),
                    "passed".equalsIgnoreCase(clean(result.validationStatus())),
                    revision
            ));
        } else {
            String failureKey = "failure:" + clean(result.callId());
            addUnknownInternal(new Unknown(
                    failureKey,
                    result.detail().isBlank() ? source + " did not complete" : clean(result.detail()),
                    "failure_recovery",
                    result.cancelled() ? UnknownStatus.BLOCKED : UnknownStatus.OPEN,
                    source,
                    clean(result.failureCode()),
                    revision
            ));
        }

        VerificationStatus verification = switch (clean(result.validationStatus()).toLowerCase(Locale.ROOT)) {
            case "passed" -> VerificationStatus.PASSED;
            case "failed" -> VerificationStatus.FAILED;
            case "not_required" -> success ? VerificationStatus.NOT_REQUIRED : VerificationStatus.PENDING;
            default -> VerificationStatus.PENDING;
        };
        putVerification(new VerificationTarget(
                "tool-validation:" + clean(result.callId()),
                "Validate result from " + source,
                source,
                verification,
                factId,
                revision
        ));

        if (success) resolveReasoningTargetsForTool(source, factId);
        trim();
    }

    public synchronized void adoptPlan(ValidatedAutomationPlan validatedPlan) {
        if (validatedPlan == null) {
            clearPlan("plan_removed");
            return;
        }
        revision++;
        List<PlanStepState> steps = new ArrayList<>();
        for (ValidatedAutomationPlan.Step step : validatedPlan.steps()) {
            steps.add(new PlanStepState(
                    step.id(), step.index(), step.toolId(), step.arguments(), clean(step.reason()),
                    clean(step.expectedObservation()), clean(step.validationRequirement()),
                    PlanStepStatus.PENDING, "", revision
            ));
            if (!clean(step.validationRequirement()).isBlank()) {
                putVerification(new VerificationTarget(
                        "plan-step:" + step.id(),
                        clean(step.validationRequirement()),
                        step.toolId(),
                        VerificationStatus.PENDING,
                        "",
                        revision
                ));
            }
        }
        this.plan = new PlanState(
                validatedPlan.id(), clean(validatedPlan.objective()), PlanStatus.REVIEW,
                List.copyOf(steps), "", revision
        );
        addDecisionInternal(new Decision(
                "plan:" + validatedPlan.id(), "active_plan", validatedPlan.id(), 1.0D,
                Set.of("validated_plan"), true, revision
        ));
        resolveUnknown("reasoning:plan_required", "validated_plan");
    }

    public synchronized void setPlanStatus(PlanStatus status, String detail) {
        revision++;
        if (this.plan.status() == PlanStatus.NONE && status != PlanStatus.NONE) return;
        this.plan = new PlanState(
                this.plan.id(), this.plan.objective(), status == null ? this.plan.status() : status,
                this.plan.steps(), clean(detail), revision
        );
    }

    public synchronized void markPlanStep(int index, PlanStepStatus status, String evidence) {
        if (this.plan.status() == PlanStatus.NONE) return;
        revision++;
        List<PlanStepState> updated = new ArrayList<>(this.plan.steps().size());
        for (PlanStepState step : this.plan.steps()) {
            if (step.index() != index) {
                updated.add(step);
                continue;
            }
            updated.add(new PlanStepState(
                    step.id(), step.index(), step.toolId(), step.arguments(), step.reason(), step.expectedObservation(),
                    step.validationRequirement(), status == null ? step.status() : status,
                    clean(evidence), revision
            ));
            VerificationTarget target = verificationTargets.get("plan-step:" + step.id());
            if (target != null) {
                VerificationStatus verificationStatus = switch (status == null ? step.status() : status) {
                    case COMPLETED -> VerificationStatus.PASSED;
                    case FAILED, BLOCKED -> VerificationStatus.FAILED;
                    default -> target.status();
                };
                putVerification(new VerificationTarget(
                        target.id(), target.requirement(), target.source(), verificationStatus,
                        clean(evidence), revision
                ));
            }
        }
        this.plan = new PlanState(
                this.plan.id(), this.plan.objective(), this.plan.status(), List.copyOf(updated),
                this.plan.detail(), revision
        );
    }

    public synchronized void clearPlan(String reason) {
        revision++;
        this.plan = new PlanState("", "", PlanStatus.NONE, List.of(), clean(reason), revision);
    }

    public synchronized void decide(
            String id,
            String subject,
            String value,
            double confidence,
            Set<String> evidenceIds,
            boolean locked
    ) {
        revision++;
        addDecisionInternal(new Decision(
                clean(id).isBlank() ? "decision:" + revision : clean(id),
                clean(subject), clean(value), clamp(confidence),
                evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds), locked, revision
        ));
    }

    public synchronized void addFact(String statement, String source, double confidence) {
        if (clean(statement).isBlank()) return;
        revision++;
        evidenceEpoch++;
        putFact(new Fact(
                "fact:" + evidenceEpoch + ":" + Integer.toHexString(statement.hashCode()),
                clean(statement), clean(source), clamp(confidence), FactKind.FACT,
                evidenceEpoch, revision
        ));
    }

    public synchronized void addUnknown(String id, String question, String kind, String source) {
        revision++;
        addUnknownInternal(new Unknown(
                clean(id).isBlank() ? "unknown:" + revision : clean(id),
                clean(question), clean(kind), UnknownStatus.OPEN, clean(source), "", revision
        ));
        trim();
    }

    public synchronized void resolveUnknown(String id, String evidenceId) {
        Unknown value = unknowns.get(clean(id));
        if (value == null || value.status() == UnknownStatus.RESOLVED) return;
        revision++;
        unknowns.put(value.id(), new Unknown(
                value.id(), value.question(), value.kind(), UnknownStatus.RESOLVED,
                value.source(), clean(evidenceId), revision
        ));
    }

    public synchronized void recordToolArguments(
            String callId,
            String toolId,
            Map<String, ArgumentBinding> bindings
    ) {
        if (bindings == null || bindings.isEmpty()) return;
        revision++;
        for (Map.Entry<String, ArgumentBinding> entry : bindings.entrySet()) {
            ArgumentBinding binding = entry.getValue();
            if (binding == null) continue;
            String key = clean(callId) + ":" + clean(entry.getKey());
            argumentBindings.put(key, new ToolArgumentState(
                    clean(callId), clean(toolId), clean(entry.getKey()),
                    binding.value() == null ? com.google.gson.JsonNull.INSTANCE : binding.value().deepCopy(),
                    binding.source(), clean(binding.sourceId()), binding.confidence(), revision
            ));
            if (binding.source() != ArgumentSource.MODEL_DERIVED) {
                resolveUnknown("tool-argument:" + clean(callId) + ":" + clean(entry.getKey()), binding.sourceId());
            }
        }
        trim();
    }

    public synchronized void requireToolArgument(String callId, String toolId, String argument, String detail) {
        String id = "tool-argument:" + clean(callId) + ":" + clean(argument);
        addUnknownInternal(new Unknown(
                id,
                clean(detail).isBlank()
                        ? "Resolve required argument '" + clean(argument) + "' for " + clean(toolId)
                        : clean(detail),
                "tool_argument",
                UnknownStatus.OPEN,
                clean(toolId),
                "",
                ++revision
        ));
        trim();
    }

    public synchronized void resolveToolArgumentUnknown(String callId, String argument, String evidenceId) {
        resolveUnknown("tool-argument:" + clean(callId) + ":" + clean(argument), evidenceId);
    }

    public synchronized void registerClaim(
            String id, String subject, String value, String source,
            boolean consequential, String verifierTool, String evidenceId
    ) {
        String claimId = clean(id).isBlank() ? "claim:" + (++revision) : clean(id);
        revision++;
        ClaimStatus status = consequential ? ClaimStatus.PENDING : ClaimStatus.VERIFIED;
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        if (!clean(evidenceId).isBlank()) evidence.add(clean(evidenceId));
        Claim claim = new Claim(
                claimId, clean(subject), clean(value), clean(source), consequential,
                clean(verifierTool), status, Set.copyOf(evidence), "", revision
        );
        claims.put(claimId, claim);
        if (consequential) {
            putVerification(new VerificationTarget(
                    "claim-verify:" + claimId,
                    "Verify claim '" + claim.subject() + " = " + claim.value() + "' with observable evidence",
                    clean(verifierTool), VerificationStatus.PENDING, "", revision
            ));
        } else {
            lockDecisionFromClaim(claim, clean(evidenceId));
        }
        trim();
    }

    public synchronized void verifyClaim(String claimId, boolean passed, String evidenceId, String detail) {
        Claim existing = claims.get(clean(claimId));
        if (existing == null) return;
        revision++;
        LinkedHashSet<String> evidence = new LinkedHashSet<>(existing.evidenceIds());
        if (!clean(evidenceId).isBlank()) evidence.add(clean(evidenceId));
        ClaimStatus status = passed ? ClaimStatus.VERIFIED : ClaimStatus.REFUTED;
        Claim updated = new Claim(
                existing.id(), existing.subject(), existing.value(), existing.source(), existing.consequential(),
                existing.verifierTool(), status, Set.copyOf(evidence), clean(detail), revision
        );
        claims.put(existing.id(), updated);
        VerificationTarget target = verificationTargets.get("claim-verify:" + existing.id());
        if (target != null) {
            putVerification(new VerificationTarget(
                    target.id(), target.requirement(), target.source(),
                    passed ? VerificationStatus.PASSED : VerificationStatus.FAILED, clean(evidenceId), revision
            ));
        }
        if (passed) {
            unlockContradictedDecisions(updated.subject(), updated.value(), clean(evidenceId));
            lockDecisionFromClaim(updated, clean(evidenceId));
            resolveUnknown("reasoning:verification_required", clean(evidenceId));
        } else {
            addUnknownInternal(new Unknown(
                    "claim-recovery:" + existing.id(),
                    clean(detail).isBlank() ? "Resolve contradictory evidence for " + existing.subject() : clean(detail),
                    "failure_recovery", UnknownStatus.OPEN, existing.verifierTool(), clean(evidenceId), revision
            ));
            unlockConflictingDecision(existing.subject(), existing.value(), clean(evidenceId));
        }
        trim();
    }

    public synchronized void verifyClaimsForTool(String verifierTool, boolean passed, String evidenceId, String detail) {
        String tool = clean(verifierTool);
        if (tool.isBlank()) return;
        String matching = claims.values().stream()
                .filter(c -> c.status() == ClaimStatus.PENDING)
                .filter(c -> tool.equals(c.verifierTool()))
                .max(Comparator.comparingLong(Claim::revision))
                .map(Claim::id).orElse("");
        if (!matching.isBlank()) verifyClaim(matching, passed, evidenceId, detail);
    }

    private void lockDecisionFromClaim(Claim claim, String evidenceId) {
        if (claim == null || claim.subject().isBlank()) return;
        LinkedHashSet<String> evidence = new LinkedHashSet<>(claim.evidenceIds());
        if (!clean(evidenceId).isBlank()) evidence.add(clean(evidenceId));
        addDecisionInternal(new Decision(
                "verified-claim:" + claim.id(), claim.subject(), claim.value(), 1.0D,
                Set.copyOf(evidence), true, revision
        ));
    }

    private void unlockContradictedDecisions(String subject, String verifiedValue, String evidenceId) {
        for (Map.Entry<String, Decision> entry : new ArrayList<>(decisions.entrySet())) {
            Decision decision = entry.getValue();
            if (!decision.locked() || !decision.subject().equals(clean(subject))) continue;
            if (decision.value().equals(clean(verifiedValue))) continue;
            LinkedHashSet<String> evidence = new LinkedHashSet<>(decision.evidenceIds());
            if (!clean(evidenceId).isBlank()) evidence.add(clean(evidenceId));
            decisions.put(entry.getKey(), new Decision(
                    decision.id(), decision.subject(), decision.value(), Math.min(decision.confidence(), 0.5D),
                    Set.copyOf(evidence), false, revision
            ));
        }
    }

    private void unlockConflictingDecision(String subject, String value, String evidenceId) {
        for (Map.Entry<String, Decision> entry : new ArrayList<>(decisions.entrySet())) {
            Decision decision = entry.getValue();
            if (!decision.locked() || !decision.subject().equals(clean(subject))) continue;
            if (!decision.value().equals(clean(value))) continue;
            LinkedHashSet<String> evidence = new LinkedHashSet<>(decision.evidenceIds());
            if (!clean(evidenceId).isBlank()) evidence.add(clean(evidenceId));
            decisions.put(entry.getKey(), new Decision(
                    decision.id(), decision.subject(), decision.value(), decision.confidence(),
                    Set.copyOf(evidence), false, revision
            ));
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                requestId,
                objective,
                reasoningDecision == null ? AgentReasoningController.Mode.DIRECT : reasoningDecision.mode(),
                revision,
                evidenceEpoch,
                List.copyOf(facts.values()),
                List.copyOf(unknowns.values()),
                List.copyOf(decisions.values()),
                List.copyOf(verificationTargets.values()),
                List.copyOf(toolEvidence.values()),
                List.copyOf(argumentBindings.values()),
                List.copyOf(claims.values()),
                plan
        );
    }

    /**
     * Compact observable state for provider continuation. This is structured
     * evidence/state only, never hidden reasoning text.
     */
    public synchronized String promptSummary() {
        List<Unknown> open = unknowns.values().stream()
                .filter(value -> value.status() == UnknownStatus.OPEN)
                .limit(8).toList();
        List<VerificationTarget> pendingVerification = verificationTargets.values().stream()
                .filter(value -> value.status() == VerificationStatus.PENDING)
                .limit(8).toList();
        List<Decision> recentDecisions = decisions.values().stream()
                .filter(value -> !"reasoning-mode".equals(value.id()))
                .sorted(Comparator.comparingLong(Decision::revision).reversed())
                .limit(8).toList();
        List<Fact> recentFacts = facts.values().stream()
                .sorted(Comparator.comparingLong(Fact::revision).reversed())
                .limit(8).toList();
        List<Claim> activeClaims = claims.values().stream()
                .filter(value -> value.status() != ClaimStatus.VERIFIED)
                .sorted(Comparator.comparingLong(Claim::revision).reversed())
                .limit(6).toList();

        if (open.isEmpty() && pendingVerification.isEmpty() && recentDecisions.isEmpty()
                && recentFacts.isEmpty() && activeClaims.isEmpty() && plan.status() == PlanStatus.NONE) {
            return "";
        }
        StringBuilder out = new StringBuilder("Koil authoritative agent state (observable, not chain-of-thought):\n");
        if (!open.isEmpty()) {
            out.append("Open unknowns:\n");
            for (Unknown value : open) out.append("- ").append(value.id()).append(": ").append(value.question()).append('\n');
        }
        if (!recentDecisions.isEmpty()) {
            out.append("Decisions:\n");
            for (Decision value : recentDecisions) {
                out.append("- ").append(value.subject()).append(" = ").append(value.value());
                if (value.locked()) out.append(" [locked until contradictory evidence]");
                out.append('\n');
            }
        }
        if (!recentFacts.isEmpty()) {
            out.append("Evidence/facts:\n");
            for (Fact value : recentFacts) out.append("- [").append(value.source()).append("] ").append(value.statement()).append('\n');
        }
        if (!activeClaims.isEmpty()) {
            out.append("Claims awaiting resolution:\n");
            for (Claim value : activeClaims) {
                out.append("- ").append(value.subject()).append(" = ").append(value.value())
                        .append(" [").append(value.status().name().toLowerCase(Locale.ROOT)).append("]\n");
            }
        }
        if (!pendingVerification.isEmpty()) {
            out.append("Pending verification:\n");
            for (VerificationTarget value : pendingVerification) out.append("- ").append(value.requirement()).append('\n');
        }
        if (plan.status() != PlanStatus.NONE) {
            out.append("Plan: ").append(plan.id()).append(" | ").append(plan.status().name().toLowerCase(Locale.ROOT)).append('\n');
            for (PlanStepState step : plan.steps()) {
                out.append("- step ").append(step.index()).append(' ').append(step.toolId())
                        .append(" | ").append(step.status().name().toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        out.append("State rule: do not reopen a locked decision without new contradictory evidence. Resolve open unknowns or pending verification before optional reconsideration.");
        return out.toString().strip();
    }

    private void resolveUnknownsSupportedBy(String toolId, String evidenceId, boolean allowObjectiveOccurrence) {
        if (toolId.isBlank()) return;
        boolean objectiveOccurrenceResolved = false;
        for (Map.Entry<String, Unknown> entry : new ArrayList<>(unknowns.entrySet())) {
            Unknown unknown = entry.getValue();
            if (unknown.status() != UnknownStatus.OPEN) continue;
            if (!unknown.source().isBlank() && !unknown.source().equals(toolId)) continue;

            // Objective unknowns preserve multiplicity. One successful jump,
            // command, write, etc. may close exactly one requested occurrence,
            // not every future objective that happens to share the same tool.
            if (unknown.id().startsWith("objective:")) {
                if (!allowObjectiveOccurrence || objectiveOccurrenceResolved) continue;
                objectiveOccurrenceResolved = true;
            }
            unknowns.put(entry.getKey(), new Unknown(
                    unknown.id(), unknown.question(), unknown.kind(), UnknownStatus.RESOLVED,
                    unknown.source(), evidenceId, revision
            ));
        }
    }

    private void resolveReasoningTargetsForTool(String toolId, String evidenceId) {
        if (toolId.startsWith("minecraft.")) resolveUnknown("reasoning:identifier_resolution", evidenceId);
        if (toolId.startsWith("internet.") || toolId.startsWith("browser.") || toolId.startsWith("dataset.")
                || toolId.startsWith("content.") || toolId.startsWith("code.") || toolId.startsWith("koil.")) {
            resolveUnknown("reasoning:factual_uncertainty", evidenceId);
        }
        if (toolId.startsWith("automation.plan")) resolveUnknown("reasoning:plan_required", evidenceId);
        if (toolId.startsWith("context.") || toolId.startsWith("workspace.")) {
            resolveUnknown("reasoning:context_dependency", evidenceId);
        }
        resolveUnknown("reasoning:action_required", evidenceId);
        VerificationTarget validation = verificationTargets.values().stream()
                .filter(value -> value.evidenceId().equals(evidenceId)
                        && (value.status() == VerificationStatus.PASSED || value.status() == VerificationStatus.NOT_REQUIRED))
                .findFirst().orElse(null);
        if (validation != null) {
            resolveUnknown("reasoning:verification_required", evidenceId);
            satisfyVerification("verify:reasoning-target", evidenceId);
        }
        resolveUnknown("reasoning:failure_recovery", evidenceId);
    }

    private void satisfyVerification(String id, String evidenceId) {
        VerificationTarget target = verificationTargets.get(id);
        if (target == null) return;
        verificationTargets.put(id, new VerificationTarget(
                target.id(), target.requirement(), target.source(), VerificationStatus.PASSED,
                clean(evidenceId), revision
        ));
    }

    private void putFact(Fact value) {
        facts.put(value.id(), value);
    }

    private void addUnknownInternal(Unknown value) {
        if (value.question().isBlank()) return;
        unknowns.put(value.id(), value);
    }

    private void addDecisionInternal(Decision value) {
        if (value.subject().isBlank()) return;
        Decision existing = decisions.get(value.id());
        if (existing != null && existing.locked() && existing.value().equals(value.value())) return;
        decisions.put(value.id(), value);
    }

    private void putVerification(VerificationTarget value) {
        if (value.requirement().isBlank()) return;
        verificationTargets.put(value.id(), value);
    }

    private void trim() {
        trimMap(facts, MAX_FACTS);
        trimMap(decisions, MAX_DECISIONS);
        trimMap(unknowns, MAX_UNKNOWNS);
        trimMap(verificationTargets, MAX_VERIFICATION_TARGETS);
        trimMap(toolEvidence, MAX_TOOL_EVIDENCE);
        trimMap(argumentBindings, MAX_ARGUMENT_BINDINGS);
        trimMap(claims, MAX_CLAIMS);
    }

    private static <T> void trimMap(LinkedHashMap<String, T> map, int maximum) {
        while (map.size() > maximum) {
            String first = map.keySet().iterator().next();
            map.remove(first);
        }
    }

    private static String toolObservation(ModelToolResult result) {
        StringBuilder out = new StringBuilder(clean(result.toolId())).append(" -> ").append(clean(result.status()));
        if (!clean(result.detail()).isBlank()) out.append(": ").append(abbreviate(clean(result.detail()), 280));
        String compactOutput = compactOutput(result.output());
        if (!compactOutput.isBlank()) out.append(" | ").append(compactOutput);
        return out.toString();
    }

    private static String compactOutput(JsonObject output) {
        if (output == null || output.size() == 0) return "";
        List<String> values = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : output.entrySet()) {
            if (count++ >= 5) break;
            String rendered;
            try {
                rendered = entry.getValue().isJsonPrimitive()
                        ? entry.getValue().getAsString()
                        : entry.getValue().toString();
            } catch (RuntimeException ignored) {
                continue;
            }
            values.add(entry.getKey() + "=" + abbreviate(rendered, 100));
        }
        return String.join(", ", values);
    }

    private static String describeTarget(AgentReasoningController.Target target) {
        return switch (target) {
            case IDENTIFIER_RESOLUTION -> "Resolve the exact identifier intended by the request";
            case FACTUAL_UNCERTAINTY -> "Resolve the material factual uncertainty with evidence";
            case CONTEXT_DEPENDENCY -> "Recover the relevant prior/request context";
            case PLAN_REQUIRED -> "Produce and validate the concrete execution plan";
            case ACTION_REQUIRED -> "Choose and execute the next concrete action";
            case VERIFICATION_REQUIRED -> "Verify the consequential result with observable evidence";
            case FAILURE_RECOVERY -> "Diagnose the observed failure and establish a changed recovery path";
        };
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String abbreviate(String value, int maximum) {
        String safe = clean(value);
        if (safe.length() <= maximum) return safe;
        return safe.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    public enum FactKind { FACT, OBSERVATION, FAILURE }
    public enum UnknownStatus { OPEN, RESOLVED, BLOCKED }
    public enum VerificationStatus { PENDING, PASSED, FAILED, NOT_REQUIRED }
    public enum ClaimStatus { PENDING, VERIFIED, REFUTED }
    public enum PlanStatus { NONE, REVIEW, APPROVED, EXECUTING, COMPLETED, FAILED, REJECTED, REVISION_REQUIRED }
    public enum PlanStepStatus { PENDING, ACTIVE, COMPLETED, FAILED, BLOCKED, REVISED }
    public enum ArgumentSource { USER_REQUEST, STATE_FACT, STATE_DECISION, TOOL_RESULT, PLAN_STEP, MODEL_DERIVED, SCHEMA_DEFAULT }

    public record Fact(
            String id, String statement, String source, double confidence,
            FactKind kind, long evidenceEpoch, long revision
    ) {}

    public record Unknown(
            String id, String question, String kind, UnknownStatus status,
            String source, String resolutionEvidenceId, long revision
    ) {}

    public record Decision(
            String id, String subject, String value, double confidence,
            Set<String> evidenceIds, boolean locked, long revision
    ) {}

    public record VerificationTarget(
            String id, String requirement, String source,
            VerificationStatus status, String evidenceId, long revision
    ) {}

    public record Claim(
            String id, String subject, String value, String source, boolean consequential,
            String verifierTool, ClaimStatus status, Set<String> evidenceIds, String detail, long revision
    ) {
        public Claim {
            id = clean(id); subject = clean(subject); value = clean(value); source = clean(source);
            verifierTool = clean(verifierTool); status = status == null ? ClaimStatus.PENDING : status;
            evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds); detail = clean(detail);
        }
    }

    public record PlanStepState(
            String id, int index, String toolId, JsonObject arguments, String reason,
            String expectedObservation, String validationRequirement,
            PlanStepStatus status, String evidence, long revision
    ) {
        public PlanStepState {
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
        }
    }

    public record ToolEvidence(
            String id, String callId, String toolId, JsonObject output,
            boolean validated, long evidenceEpoch, long revision
    ) {
        public ToolEvidence {
            output = output == null ? new JsonObject() : output.deepCopy();
        }
    }

    public record ArgumentBinding(
            JsonElement value, ArgumentSource source, String sourceId, double confidence
    ) {
        public ArgumentBinding {
            value = value == null ? com.google.gson.JsonNull.INSTANCE : value.deepCopy();
            source = source == null ? ArgumentSource.MODEL_DERIVED : source;
            sourceId = clean(sourceId);
            confidence = clamp(confidence);
        }
    }

    public record ToolArgumentState(
            String callId, String toolId, String argument, JsonElement value,
            ArgumentSource source, String sourceId, double confidence, long revision
    ) {
        public ToolArgumentState {
            value = value == null ? com.google.gson.JsonNull.INSTANCE : value.deepCopy();
        }
    }

    public record PlanState(
            String id, String objective, PlanStatus status,
            List<PlanStepState> steps, String detail, long revision
    ) {
        public PlanState {
            id = clean(id);
            objective = clean(objective);
            status = status == null ? PlanStatus.NONE : status;
            steps = steps == null ? List.of() : List.copyOf(steps);
            detail = clean(detail);
        }

        static PlanState none() {
            return new PlanState("", "", PlanStatus.NONE, List.of(), "", 0L);
        }
    }

    public record Snapshot(
            String requestId,
            String objective,
            AgentReasoningController.Mode reasoningMode,
            long revision,
            long evidenceEpoch,
            List<Fact> facts,
            List<Unknown> unknowns,
            List<Decision> decisions,
            List<VerificationTarget> verificationTargets,
            List<ToolEvidence> toolEvidence,
            List<ToolArgumentState> argumentBindings,
            List<Claim> claims,
            PlanState plan
    ) {
        public boolean hasOpenUnknowns() {
            return unknowns.stream().anyMatch(value -> value.status() == UnknownStatus.OPEN);
        }

        public boolean hasPendingVerification() {
            return verificationTargets.stream().anyMatch(value -> value.status() == VerificationStatus.PENDING);
        }
    }
}
