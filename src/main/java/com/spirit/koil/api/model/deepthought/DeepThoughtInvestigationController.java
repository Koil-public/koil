package com.spirit.koil.api.model.deepthought;

import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.retrieval.DeepThoughtKnowledgeAdapter;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded controller for Koil Deep Thought investigations.
 *
 * <p>The provider receives one narrow investigation task per round. This
 * controller owns the durable public investigation ledger: claims, evidence,
 * hypotheses, tests, contradictions, assumptions, limitations, progress,
 * phase transitions, confidence updates, lifecycle state, and checkpoints.
 * It intentionally never stores or requests private chain-of-thought.</p>
 *
 * <p>The controller is deliberately conservative about epistemic promotion.
 * A successful tool call becomes evidence, not automatically a proven claim.
 * A claim is promoted only when a visible investigation artifact explicitly
 * associates available evidence with it. Independent verification requires a
 * distinct evidence source, not repeated use of the same observation.</p>
 */
public final class DeepThoughtInvestigationController {
    public static final int MAXIMUM_SUMMARY_CHARACTERS = 6_000;
    public static final int MAXIMUM_EVIDENCE_DATA_CHARACTERS = 12_000;
    public static final int MAXIMUM_DETAIL_CHARACTERS = 1_200;
    public static final int MAXIMUM_FINAL_CONCLUSION_CHARACTERS = 64_000;

    public static final int MAXIMUM_CLAIMS = 48;
    public static final int MAXIMUM_EVIDENCE = 96;
    public static final int MAXIMUM_HYPOTHESES = 24;
    public static final int MAXIMUM_TESTS = 40;
    public static final int MAXIMUM_CONTRADICTIONS = 32;
    public static final int MAXIMUM_ASSUMPTIONS = 48;
    public static final int MAXIMUM_OPEN_QUESTIONS = 48;
    public static final int MAXIMUM_LIMITATIONS = 64;

    private static final int STAGNATION_CHALLENGE_ROUND = 2;
    private static final int STAGNATION_REPLAN_ROUND = 4;
    private static final int STAGNATION_FINALIZE_ROUND = 6;

    private static final Pattern SOURCE_LOCATOR = Pattern.compile(
            "\"(?:url|sourceUrl|source_url|source|documentId|document_id|path|resource|resourceId|resource_id)\"\\s*:\\s*\"([^\"]{1,600})\""
    );

    private static final Set<String> SUPPORTED_CLAIM_STATES = Set.of(
            "supported",
            "independently_verified"
    );

    private final DeepThoughtSession session;
    private final String scope;
    private long phaseStartedAt = System.currentTimeMillis();

    /**
     * Ephemeral diagnostics only. Durable behavior is reconstructed from the
     * session ledger, so losing these values across restart is harmless.
     */
    private String lastRecordedEvidenceId = "";
    private String lastToolStatus = "";
    private int checkpointFailureCount;

    public DeepThoughtInvestigationController(String scope, DeepThoughtSession session) {
        this.scope = clean(scope, 200).isBlank() ? "global" : clean(scope, 200);
        if (session == null) {
            throw new IllegalArgumentException("Deep Thought session is required.");
        }
        this.session = session;
        normalizePersistedState();
        updateConfidence();
        refreshProgressFingerprintIfMissing();
        checkpoint();
    }

    public DeepThoughtSession session() {
        return session;
    }

    /**
     * Returns a compact public diagnostic view of the current investigation.
     * This is safe for UI/tests because it contains ledger state only, never
     * private provider reasoning.
     */
    public InvestigationStatus status() {
        DeepThoughtConfidenceEngine.Result confidence = safeConfidence();
        DeepThoughtSession.Claim focus = focusClaim();
        DeepThoughtSession.Hypothesis hypothesis = focusHypothesis();

        long requiredClaims = session.claims.stream()
                .filter(DeepThoughtSession.Claim::required)
                .count();
        long supportedClaims = session.claims.stream()
                .filter(DeepThoughtSession.Claim::required)
                .filter(claim -> SUPPORTED_CLAIM_STATES.contains(normalizeState(claim.state())))
                .count();
        long independentlyVerified = session.claims.stream()
                .filter(DeepThoughtSession.Claim::required)
                .filter(claim -> "independently_verified".equals(normalizeState(claim.state())))
                .count();
        long unresolvedContradictions = session.contradictions.stream()
                .filter(value -> !"resolved".equals(normalizeState(value.state())))
                .count();

        return new InvestigationStatus(
                session.phase,
                session.lifecycle,
                session.investigationRound,
                session.stagnantRounds,
                (int) requiredClaims,
                (int) supportedClaims,
                (int) independentlyVerified,
                session.evidence.size(),
                distinctEvidenceSources(),
                session.hypotheses.size(),
                session.tests.size(),
                (int) unresolvedContradictions,
                session.unresolvedQuestions.size(),
                session.limitations.size(),
                focus == null ? "" : focus.id(),
                focus == null ? "" : clean(focus.text(), 500),
                hypothesis == null ? "" : hypothesis.id(),
                confidence.classification(),
                confidence.coveragePercent(),
                phaseGate().reason()
        );
    }

    /**
     * Provider-facing next-task contract.
     *
     * <p>The task is intentionally narrow. It gives enough durable ledger
     * context to resume safely after compaction/restart without replaying a
     * hidden reasoning transcript.</p>
     */
    public String instruction() {
        updateConfidence();
        InvestigationStatus status = status();
        String objective = clean(session.normalizedObjective, 1_000);
        if (objective.isBlank()) {
            objective = clean(session.originalQuestion, 1_000);
        }

        StringBuilder out = new StringBuilder(4_096);
        out.append("Deep Thought investigation task. ");
        out.append("Return only a concise public reasoning artifact or one registered read-only tool call. ");
        out.append("Never expose private chain-of-thought.\n");
        out.append("Objective: ").append(objective.isBlank() ? "unspecified" : objective).append('\n');
        out.append("Phase: ").append(session.phase.name().toLowerCase(Locale.ROOT)).append(". ");
        out.append(phaseInstruction()).append('\n');
        out.append("Ledger: requiredClaims=").append(status.requiredClaims())
                .append(", supported=").append(status.supportedRequiredClaims())
                .append(", independentlyVerified=").append(status.independentlyVerifiedRequiredClaims())
                .append(", evidence=").append(status.evidenceCount())
                .append(", distinctSources=").append(status.distinctEvidenceSources())
                .append(", hypotheses=").append(status.hypothesisCount())
                .append(", tests=").append(status.testCount())
                .append(", openContradictions=").append(status.unresolvedContradictions())
                .append(", openQuestions=").append(status.openQuestions())
                .append(", confidence=").append(status.confidenceClassification())
                .append(".\n");

        DeepThoughtSession.Claim focus = focusClaim();
        if (focus != null) {
            out.append("Focus claim: ")
                    .append(focus.id())
                    .append(" [")
                    .append(normalizeState(focus.state()))
                    .append("] ")
                    .append(clean(focus.text(), 700))
                    .append('\n');
        }

        DeepThoughtSession.Hypothesis hypothesis = focusHypothesis();
        if (hypothesis != null && phaseUsesHypothesis(session.phase)) {
            out.append("Focus hypothesis: ")
                    .append(hypothesis.id())
                    .append(" [")
                    .append(normalizeState(hypothesis.state()))
                    .append("] ")
                    .append(clean(hypothesis.statement(), 600));
            if (!clean(hypothesis.falsificationTest(), 500).isBlank()) {
                out.append(" | falsifier=").append(clean(hypothesis.falsificationTest(), 500));
            }
            out.append('\n');
        }

        DeepThoughtSession.Evidence recent = newestEvidence();
        if (recent != null && phaseUsesEvidenceDigest(session.phase)) {
            out.append("Newest evidence: ")
                    .append(recent.id())
                    .append(" source=")
                    .append(clean(recent.sourceIdentifier(), 300))
                    .append(" reproducible=")
                    .append(recent.reproducible())
                    .append(" claims=")
                    .append(recent.claimIds().isEmpty() ? "none" : String.join(",", recent.claimIds()))
                    .append('\n');
        }

        if (session.phase != DeepThoughtSession.Phase.FINALIZE) {
            out.append(artifactProtocol());
        }

        return bounded(out.toString().stripTrailing(), 6_000);
    }

    /**
     * Records one provider-visible investigation artifact and chooses the next
     * phase from ledger gates rather than blindly incrementing the enum.
     *
     * @return true when the next provider round should finalize the answer.
     */
    public boolean acceptRoundSummary(String summary) {
        if (terminalLifecycle()) {
            return session.phase == DeepThoughtSession.Phase.FINALIZE;
        }

        session.investigationRound++;
        String before = semanticFingerprint();

        ParsedArtifacts artifacts = parseArtifacts(summary);
        applyArtifacts(artifacts);

        String safeSummary = clean(summary, MAXIMUM_SUMMARY_CHARACTERS);
        if (!safeSummary.isBlank()) {
            session.lastMeaningfulDiscovery = safeSummary;
        }

        applyPhaseGate();
        updateProgress(before);

        applyStagnationRecovery();
        updateConfidence();
        checkpoint();
        return session.phase == DeepThoughtSession.Phase.FINALIZE;
    }

    /**
     * Records exact structured tool output as evidence. A tool success is not
     * automatically a supported claim. The subsequent interpretation round
     * must explicitly associate that evidence with a claim via SUPPORTS.
     */
    public void recordToolResult(ModelToolResult result) {
        if (terminalLifecycle()) {
            return;
        }

        session.investigationRound++;
        DeepThoughtSession.Phase phaseAtCall = session.phase;
        String before = semanticFingerprint();

        if (result == null) {
            lastToolStatus = "missing";
            addLimitation("A Deep Thought evidence call returned no structured tool result.");
            session.lastMeaningfulDiscovery = "No structured tool result was returned.";
            routeAfterToolResult(phaseAtCall, false);
            updateProgress(before);
            updateConfidence();
            checkpoint();
            return;
        }

        lastToolStatus = normalizeState(result.status());
        DeepThoughtSession.Claim claim = focusClaimForEvidence(phaseAtCall);
        String exact = evidencePayload(result);
        String sourceType = sourceType(result.toolId());
        String sourceIdentifier = sourceIdentifier(result, exact);
        String evidenceId = stableId(
                "evidence",
                sourceType + "|" + sourceIdentifier + "|" + exact
        );

        boolean duplicate = evidenceById(evidenceId) != null;
        boolean acceptedResult = result.completedAndValidated();

        if (!duplicate && session.evidence.size() < MAXIMUM_EVIDENCE) {
            List<String> claimIds = claim == null ? List.of() : List.of(claim.id());
            DeepThoughtSession.Evidence evidence = new DeepThoughtSession.Evidence(
                    evidenceId,
                    sourceType,
                    sourceIdentifier,
                    exact,
                    authority(result.toolId(), sourceType),
                    independentEvidence(sourceType, sourceIdentifier),
                    acceptedResult,
                    effectiveRetrievedAt(result),
                    claimIds
            );
            session.evidence.add(evidence);
            lastRecordedEvidenceId = evidence.id();

            if (claim != null) {
                linkEvidenceToClaim(claim.id(), evidence.id(), false);
            }
        } else if (duplicate) {
            lastRecordedEvidenceId = evidenceId;
            addLimitationOnce(
                    "Repeated evidence from " + clean(sourceIdentifier, 220)
                            + " matched an existing observation and does not add independent support."
            );
        } else {
            addLimitationOnce(
                    "Evidence ledger reached its bounded " + MAXIMUM_EVIDENCE
                            + "-entry limit. Additional observations were not persisted."
            );
        }

        if (!acceptedResult) {
            String failure = normalizeState(result.status())
                    + (clean(result.failureCode(), 200).isBlank()
                    ? ""
                    : ":" + clean(result.failureCode(), 200));
            addLimitation(
                    "Evidence call " + clean(result.toolId(), 180)
                            + " returned " + failure
                            + (clean(result.detail(), 400).isBlank()
                            ? "."
                            : " - " + clean(result.detail(), 400))
            );
        }

        if (phaseAtCall == DeepThoughtSession.Phase.TEST) {
            recordObservedTest(result, claim);
        } else if (phaseAtCall == DeepThoughtSession.Phase.CHALLENGE) {
            recordChallengeAttempt(result, claim);
        }

        session.lastMeaningfulDiscovery = acceptedResult
                ? "Collected evidence from " + clean(sourceIdentifier, 300) + "."
                : "Evidence call returned " + normalizeState(result.status())
                + " from " + clean(sourceIdentifier, 260) + ".";

        routeAfterToolResult(phaseAtCall, acceptedResult);
        updateProgress(before);

        applyStagnationRecovery();
        updateConfidence();
        checkpoint();
    }

    public void pause() {
        if (session.lifecycle == DeepThoughtSession.Lifecycle.PAUSED
                || session.lifecycle == DeepThoughtSession.Lifecycle.COMPLETED
                || session.lifecycle == DeepThoughtSession.Lifecycle.CANCELLED) {
            return;
        }
        accrueActiveTime();
        session.lifecycle = DeepThoughtSession.Lifecycle.PAUSED;
        checkpoint();
    }

    public void resume() {
        if (session.lifecycle == DeepThoughtSession.Lifecycle.COMPLETED
                || session.lifecycle == DeepThoughtSession.Lifecycle.CANCELLED) {
            return;
        }
        phaseStartedAt = System.currentTimeMillis();
        session.lifecycle = session.phase == DeepThoughtSession.Phase.FINALIZE
                ? DeepThoughtSession.Lifecycle.FINALIZING
                : DeepThoughtSession.Lifecycle.ACTIVE;
        checkpoint();
    }

    public void cancel() {
        if (session.lifecycle == DeepThoughtSession.Lifecycle.CANCELLED
                || session.lifecycle == DeepThoughtSession.Lifecycle.COMPLETED) {
            return;
        }
        accrueActiveTime();
        addLimitationOnce("Deep Thought was cancelled before all optional investigation paths completed.");
        session.lifecycle = DeepThoughtSession.Lifecycle.CANCELLED;
        checkpoint();
    }

    /**
     * Stops optional investigation and moves directly to an evidence-bounded
     * answer. This does not promote unsupported claims.
     */
    public void answerNow() {
        if (session.lifecycle == DeepThoughtSession.Lifecycle.COMPLETED
                || session.lifecycle == DeepThoughtSession.Lifecycle.CANCELLED) {
            return;
        }
        if (session.phase != DeepThoughtSession.Phase.FINALIZE) {
            addLimitationOnce(
                    "Answer Now ended optional Deep Thought investigation during phase "
                            + session.phase.name().toLowerCase(Locale.ROOT)
                            + "; unresolved uncertainty must remain visible."
            );
        }
        session.lifecycle = DeepThoughtSession.Lifecycle.FINALIZING;
        session.phase = DeepThoughtSession.Phase.FINALIZE;
        updateConfidence();
        checkpoint();
    }

    public void complete(String conclusion) {
        if (session.lifecycle == DeepThoughtSession.Lifecycle.CANCELLED) {
            return;
        }
        accrueActiveTime();
        session.finalConclusion = boundedPreservingFormatting(
                conclusion == null ? "" : conclusion,
                MAXIMUM_FINAL_CONCLUSION_CHARACTERS
        );
        session.phase = DeepThoughtSession.Phase.FINALIZE;
        session.lifecycle = DeepThoughtSession.Lifecycle.COMPLETED;
        updateConfidence();
        checkpoint();
        java.util.concurrent.CompletableFuture.runAsync(() -> KoilKnowledgeRuntime.shared().ifPresent(engine ->
                new DeepThoughtKnowledgeAdapter(engine).recordCompleted(session).whenComplete((ignored, failure) -> {
                    if (failure != null) LocalModelRuntimeLog.write("deep_thought_knowledge_unavailable", failure.getClass().getSimpleName());
                })));
    }

    public void markFinalPresented() {
        DeepThoughtSessionStore.markFinalPresented(scope, session);
    }

    private String phaseInstruction() {
        return switch (session.phase) {
            case DEFINE -> """
                    Define the exact answer required, operational scope, terminology, assumptions, and falsifiable required claims. \
                    Do not answer the objective yet. Prefer 1-6 atomic REQUIRED_CLAIM lines rather than one vague umbrella claim.
                    """.strip();
            case DECOMPOSE -> """
                    Check whether the required claims cover every material part of the objective. Split compound claims, record necessary assumptions \
                    and open questions, and remove redundancy by restating only genuinely distinct claims. Do not collect evidence yet unless a supplied \
                    read-only tool is required to resolve an ambiguity that blocks decomposition.
                    """.strip();
            case DISCOVER -> """
                    Identify the single highest-value missing observation for the current focus claim. If a supplied read-only capability can obtain it, \
                    call exactly one narrow capability now instead of narrating an intended search. Prefer primary/direct runtime evidence over broad retrieval.
                    """.strip();
            case COLLECT -> """
                    Interpret the newest concrete evidence. Separate what the tool actually returned from inference. Use SUPPORTS only when the linked evidence \
                    materially supports the focus claim; use CONTRADICTION when it conflicts. If evidence is irrelevant or inconclusive, record a LIMITATION or \
                    OPEN_QUESTION rather than promoting the claim.
                    """.strip();
            case HYPOTHESIZE -> """
                    For an explanatory or ambiguous objective, produce competing hypotheses that could explain the evidence. Each HYPOTHESIS must include a \
                    concrete falsification test. Avoid cosmetic rewordings of the same explanation. For a purely deterministic lookup, state the narrow \
                    alternative interpretation that remains plausible, if any.
                    """.strip();
            case TEST -> """
                    Select the most discriminating pending falsification test. If a supplied read-only capability can execute the observation, call exactly one. \
                    Do not mark a test passed or failed until its concrete result has been observed and interpreted.
                    """.strip();
            case CHALLENGE -> """
                    Attack the current leading conclusion. Seek one counterexample, boundary condition, conflicting source, alternate interpretation, or failure \
                    case that would materially change the answer. If evidence is needed, use one narrow read-only tool. Do not defend the current conclusion by default.
                    """.strip();
            case RECONCILE -> """
                    Reconcile each material conflict by checking version, scope, date, environment, definitions, authority, and source independence. Resolve a \
                    contradiction only when the ledger contains a reason to do so. Preserve genuinely unresolved conflicts instead of averaging them away.
                    """.strip();
            case VERIFY -> """
                    Independently verify each required supported claim that is not yet independently verified. Prefer a different source identifier, source type, \
                    method, or deterministic runtime check. Repeating the same observation does not count as independent verification.
                    """.strip();
            case SCORE -> """
                    Audit coverage using the controller-maintained claim/evidence ledger. Identify unsupported required claims, unresolved contradictions, failed \
                    decisive tests, and source-independence gaps. Do not invent or self-report a confidence percentage; the controller computes classification.
                    """.strip();
            case DECIDE -> """
                    Decide whether the evidence is sufficient for an evidence-backed answer, or whether supported investigation paths are exhausted and the answer \
                    must explicitly preserve uncertainty. Do not add new optional branches unless they would change the conclusion materially.
                    """.strip();
            case FINALIZE -> """
                    Produce the direct answer now. State the controller confidence classification, verified facts, strongest evidence, material assumptions, \
                    contradictions, decisive tests, limitations, unresolved uncertainty, and what could improve confidence. Be concise relative to the investigation \
                    depth. Do not expose private chain-of-thought and do not claim stronger certainty than the ledger supports.
                    """.strip();
        };
    }

    private String artifactProtocol() {
        return """
                Ledger update protocol (only emit lines that genuinely update state):
                REQUIRED_CLAIM|<atomic testable claim>
                OPTIONAL_CLAIM|<useful nonessential claim>
                ASSUMPTION|<material assumption>
                OPEN_QUESTION|<unresolved question>
                HYPOTHESIS|<statement>|<falsification test>
                TEST_PROPOSAL|<decisive test description>
                SUPPORTS|<claim-id>
                CONTRADICTION|<claim-id>|<detail>
                RESOLVE|<contradiction-id>|<resolution>
                TEST_PASS|<test-id>|<observed interpretation>
                TEST_FAIL|<test-id>|<observed interpretation>
                LIMITATION|<hard limitation>
                SUMMARY|<one concise public finding>
                Never use SUPPORTS, TEST_PASS, TEST_FAIL, or RESOLVE without matching ledger evidence.
                """;
    }

    private void applyArtifacts(ParsedArtifacts artifacts) {
        for (String claim : artifacts.requiredClaims()) {
            addClaim(claim, true);
        }
        for (String claim : artifacts.optionalClaims()) {
            addClaim(claim, false);
        }
        for (String assumption : artifacts.assumptions()) {
            addBoundedUnique(session.assumptions, assumption, MAXIMUM_ASSUMPTIONS);
        }
        for (String question : artifacts.openQuestions()) {
            addBoundedUnique(session.unresolvedQuestions, question, MAXIMUM_OPEN_QUESTIONS);
        }
        for (HypothesisArtifact hypothesis : artifacts.hypotheses()) {
            addHypothesis(hypothesis.statement(), hypothesis.falsificationTest());
        }
        for (String test : artifacts.testProposals()) {
            addTestProposal(test);
        }
        for (String claimId : artifacts.supportedClaimIds()) {
            promoteSupportedClaim(claimId);
        }
        for (ContradictionArtifact contradiction : artifacts.contradictions()) {
            addContradiction(contradiction.claimId(), contradiction.detail());
        }
        for (ResolutionArtifact resolution : artifacts.resolutions()) {
            resolveContradiction(resolution.contradictionId(), resolution.detail());
        }
        for (TestOutcomeArtifact outcome : artifacts.testPasses()) {
            updateTestOutcome(outcome.testId(), "passed", outcome.detail());
        }
        for (TestOutcomeArtifact outcome : artifacts.testFailures()) {
            updateTestOutcome(outcome.testId(), "failed", outcome.detail());
        }
        for (String limitation : artifacts.limitations()) {
            addLimitation(limitation);
        }

        if (session.phase == DeepThoughtSession.Phase.DEFINE
                && session.claims.stream().noneMatch(DeepThoughtSession.Claim::required)) {
            String fallback = artifacts.summary().isBlank()
                    ? clean(session.normalizedObjective, 1_000)
                    : artifacts.summary();
            if (!fallback.isBlank()) {
                addClaim(fallback, true);
            }
        }

        if (session.phase == DeepThoughtSession.Phase.HYPOTHESIZE
                && artifacts.hypotheses().isEmpty()
                && !artifacts.summary().isBlank()
                && session.hypotheses.size() < MAXIMUM_HYPOTHESES) {
            addHypothesis(artifacts.summary(), "pending explicit falsification test");
        }
    }

    private ParsedArtifacts parseArtifacts(String summary) {
        if (summary == null || summary.isBlank()) {
            return ParsedArtifacts.empty();
        }

        List<String> requiredClaims = new ArrayList<>();
        List<String> optionalClaims = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<String> openQuestions = new ArrayList<>();
        List<HypothesisArtifact> hypotheses = new ArrayList<>();
        List<String> testProposals = new ArrayList<>();
        List<String> supportedClaimIds = new ArrayList<>();
        List<ContradictionArtifact> contradictions = new ArrayList<>();
        List<ResolutionArtifact> resolutions = new ArrayList<>();
        List<TestOutcomeArtifact> testPasses = new ArrayList<>();
        List<TestOutcomeArtifact> testFailures = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        String publicSummary = "";

        String normalized = summary.replace("\r\n", "\n").replace('\r', '\n');
        for (String rawLine : normalized.split("\n")) {
            String line = stripPresentationPrefix(rawLine);
            if (line.isBlank()) {
                continue;
            }

            int separator = line.indexOf('|');
            if (separator <= 0) {
                continue;
            }

            String tag = normalizeTag(line.substring(0, separator));
            String payload = clean(line.substring(separator + 1), 2_000);
            if (payload.isBlank()) {
                continue;
            }

            switch (tag) {
                case "REQUIRED_CLAIM", "CLAIM" -> requiredClaims.add(payload);
                case "OPTIONAL_CLAIM" -> optionalClaims.add(payload);
                case "ASSUMPTION" -> assumptions.add(payload);
                case "OPEN_QUESTION", "QUESTION" -> openQuestions.add(payload);
                case "HYPOTHESIS" -> {
                    String[] pieces = splitPayload(payload, 2);
                    hypotheses.add(new HypothesisArtifact(
                            pieces[0],
                            pieces.length > 1 ? pieces[1] : "pending explicit falsification test"
                    ));
                }
                case "TEST_PROPOSAL", "TEST" -> testProposals.add(payload);
                case "SUPPORTS", "CLAIM_SUPPORTED" -> supportedClaimIds.add(firstField(payload));
                case "CONTRADICTION" -> {
                    String[] pieces = splitPayload(payload, 2);
                    contradictions.add(new ContradictionArtifact(
                            pieces.length > 1 ? pieces[0] : "",
                            pieces.length > 1 ? pieces[1] : pieces[0]
                    ));
                }
                case "RESOLVE", "RESOLUTION" -> {
                    String[] pieces = splitPayload(payload, 2);
                    resolutions.add(new ResolutionArtifact(
                            pieces[0],
                            pieces.length > 1 ? pieces[1] : "resolved by investigation artifact"
                    ));
                }
                case "TEST_PASS", "TEST_PASSED" -> {
                    String[] pieces = splitPayload(payload, 2);
                    testPasses.add(new TestOutcomeArtifact(
                            pieces[0],
                            pieces.length > 1 ? pieces[1] : "passed"
                    ));
                }
                case "TEST_FAIL", "TEST_FAILED" -> {
                    String[] pieces = splitPayload(payload, 2);
                    testFailures.add(new TestOutcomeArtifact(
                            pieces[0],
                            pieces.length > 1 ? pieces[1] : "failed"
                    ));
                }
                case "LIMITATION" -> limitations.add(payload);
                case "SUMMARY" -> publicSummary = payload;
                default -> {
                    // Unknown tags are ignored rather than becoming authority.
                }
            }
        }

        if (publicSummary.isBlank()) {
            publicSummary = clean(summary, 1_500);
        }

        return new ParsedArtifacts(
                List.copyOf(requiredClaims),
                List.copyOf(optionalClaims),
                List.copyOf(assumptions),
                List.copyOf(openQuestions),
                List.copyOf(hypotheses),
                List.copyOf(testProposals),
                List.copyOf(supportedClaimIds),
                List.copyOf(contradictions),
                List.copyOf(resolutions),
                List.copyOf(testPasses),
                List.copyOf(testFailures),
                List.copyOf(limitations),
                publicSummary
        );
    }

    private void applyPhaseGate() {
        PhaseGate gate = phaseGate();
        if (gate.ready()) {
            session.phase = gate.next();
            if (session.phase == DeepThoughtSession.Phase.FINALIZE) {
                session.lifecycle = DeepThoughtSession.Lifecycle.FINALIZING;
            }
        }
    }

    private PhaseGate phaseGate() {
        return switch (session.phase) {
            case DEFINE -> {
                boolean ready = session.claims.stream().anyMatch(DeepThoughtSession.Claim::required);
                yield new PhaseGate(
                        ready,
                        ready ? DeepThoughtSession.Phase.DECOMPOSE : DeepThoughtSession.Phase.DEFINE,
                        ready ? "required_claim_defined" : "missing_required_claim"
                );
            }
            case DECOMPOSE -> {
                long required = session.claims.stream().filter(DeepThoughtSession.Claim::required).count();
                boolean ready = required > 0;
                yield new PhaseGate(
                        ready,
                        ready ? DeepThoughtSession.Phase.DISCOVER : DeepThoughtSession.Phase.DECOMPOSE,
                        ready ? "claim_set_available" : "claim_set_empty"
                );
            }
            case DISCOVER -> {
                DeepThoughtSession.Claim focus = focusClaimForEvidence(session.phase);
                boolean ready = focus != null && hasAnyLinkedEvidence(focus);
                yield new PhaseGate(
                        ready,
                        ready ? DeepThoughtSession.Phase.COLLECT : DeepThoughtSession.Phase.DISCOVER,
                        ready ? "evidence_collected_for_focus" : "missing_focus_evidence"
                );
            }
            case COLLECT -> {
                DeepThoughtSession.Claim unsupported = firstRequiredClaimWithoutSupport();
                if (unsupported != null) {
                    boolean hasEvidence = hasAnyLinkedEvidence(unsupported);
                    yield new PhaseGate(
                            !hasEvidence,
                            hasEvidence ? DeepThoughtSession.Phase.COLLECT : DeepThoughtSession.Phase.DISCOVER,
                            hasEvidence ? "evidence_requires_interpretation" : "another_required_claim_needs_evidence"
                    );
                }

                if (requiresCompetingHypotheses()) {
                    yield new PhaseGate(true, DeepThoughtSession.Phase.HYPOTHESIZE, "required_claims_supported");
                }
                yield new PhaseGate(true, DeepThoughtSession.Phase.CHALLENGE, "deterministic_claims_supported");
            }
            case HYPOTHESIZE -> {
                int requiredHypotheses = requiresCompetingHypotheses() ? 2 : 1;
                boolean ready = distinctHypothesisCount() >= requiredHypotheses;
                yield new PhaseGate(
                        ready,
                        ready ? DeepThoughtSession.Phase.TEST : DeepThoughtSession.Phase.HYPOTHESIZE,
                        ready ? "competing_hypotheses_available" : "insufficient_distinct_hypotheses"
                );
            }
            case TEST -> {
                DeepThoughtSession.TestRecord test = focusTest();
                boolean observed = test != null && Set.of("observed", "passed", "failed", "inconclusive")
                        .contains(normalizeState(test.state()));
                yield new PhaseGate(
                        observed,
                        observed ? DeepThoughtSession.Phase.CHALLENGE : DeepThoughtSession.Phase.TEST,
                        observed ? "decisive_test_observed" : "test_needs_observation"
                );
            }
            case CHALLENGE -> {
                boolean challenged = session.tests.stream().anyMatch(test -> test.id().startsWith("challenge-"))
                        || session.contradictions.stream().anyMatch(value -> !normalizeState(value.state()).isBlank());
                yield new PhaseGate(
                        challenged,
                        challenged ? DeepThoughtSession.Phase.RECONCILE : DeepThoughtSession.Phase.CHALLENGE,
                        challenged ? "counterexample_attempt_recorded" : "challenge_not_attempted"
                );
            }
            case RECONCILE -> {
                long unresolved = session.contradictions.stream()
                        .filter(value -> !"resolved".equals(normalizeState(value.state())))
                        .count();
                boolean ready = unresolved == 0 || session.stagnantRounds >= STAGNATION_CHALLENGE_ROUND;
                yield new PhaseGate(
                        ready,
                        ready ? DeepThoughtSession.Phase.VERIFY : DeepThoughtSession.Phase.RECONCILE,
                        unresolved == 0 ? "conflicts_reconciled" : "conflicts_preserved_for_verification"
                );
            }
            case VERIFY -> {
                DeepThoughtSession.Claim unsupported = firstRequiredClaimWithoutSupport();
                if (unsupported != null) {
                    yield new PhaseGate(
                            true,
                            hasAnyLinkedEvidence(unsupported)
                                    ? DeepThoughtSession.Phase.COLLECT
                                    : DeepThoughtSession.Phase.DISCOVER,
                            "verification_found_unsupported_required_claim"
                    );
                }
                DeepThoughtSession.Claim unverified = firstRequiredClaimNeedingIndependentVerification();
                boolean ready = unverified == null;
                yield new PhaseGate(
                        ready,
                        ready ? DeepThoughtSession.Phase.SCORE : DeepThoughtSession.Phase.VERIFY,
                        ready ? "required_claims_independently_verified" : "independent_verification_needed"
                );
            }
            case SCORE -> new PhaseGate(
                    true,
                    DeepThoughtSession.Phase.DECIDE,
                    "confidence_and_coverage_computed"
            );
            case DECIDE -> new PhaseGate(
                    true,
                    DeepThoughtSession.Phase.FINALIZE,
                    decisionReason()
            );
            case FINALIZE -> new PhaseGate(
                    false,
                    DeepThoughtSession.Phase.FINALIZE,
                    "ready_to_finalize"
            );
        };
    }

    private String decisionReason() {
        DeepThoughtConfidenceEngine.Result result = safeConfidence();
        if ("verified".equals(result.classification())
                || "high confidence".equals(result.classification())) {
            return "acceptance_standard_met";
        }
        if (!session.evidence.isEmpty()) {
            return "answer_with_documented_uncertainty";
        }
        if (!session.limitations.isEmpty()) {
            return "investigation_paths_exhausted_without_sufficient_evidence";
        }
        return "bounded_investigation_complete";
    }

    private void routeAfterToolResult(DeepThoughtSession.Phase phaseAtCall, boolean acceptedResult) {
        session.phase = switch (phaseAtCall) {
            case DISCOVER -> acceptedResult
                    ? DeepThoughtSession.Phase.COLLECT
                    : DeepThoughtSession.Phase.DISCOVER;
            case COLLECT -> DeepThoughtSession.Phase.COLLECT;
            case TEST -> DeepThoughtSession.Phase.TEST;
            case CHALLENGE -> DeepThoughtSession.Phase.CHALLENGE;
            case VERIFY -> DeepThoughtSession.Phase.VERIFY;
            case RECONCILE -> DeepThoughtSession.Phase.RECONCILE;
            default -> acceptedResult
                    ? DeepThoughtSession.Phase.COLLECT
                    : phaseAtCall;
        };
    }

    private void applyStagnationRecovery() {
        if (session.phase == DeepThoughtSession.Phase.FINALIZE) {
            return;
        }

        if (session.stagnantRounds >= STAGNATION_FINALIZE_ROUND) {
            addLimitationOnce(
                    "Investigation stopped after repeated rounds produced no new durable claim, evidence, test, contradiction resolution, or hard limitation."
            );
            session.phase = DeepThoughtSession.Phase.FINALIZE;
            session.lifecycle = DeepThoughtSession.Lifecycle.FINALIZING;
            return;
        }

        if (session.stagnantRounds >= STAGNATION_REPLAN_ROUND) {
            DeepThoughtSession.Claim unsupported = firstRequiredClaimWithoutSupport();
            if (unsupported != null) {
                session.phase = hasAnyLinkedEvidence(unsupported)
                        ? DeepThoughtSession.Phase.COLLECT
                        : DeepThoughtSession.Phase.DISCOVER;
                addLimitationOnce(
                        "Deep Thought changed investigation strategy after repeated non-progress rounds."
                );
                return;
            }
            session.phase = DeepThoughtSession.Phase.VERIFY;
            addLimitationOnce(
                    "Deep Thought moved to independent verification after repeated non-progress rounds."
            );
            return;
        }

        if (session.stagnantRounds >= STAGNATION_CHALLENGE_ROUND
                && session.phase.ordinal() < DeepThoughtSession.Phase.CHALLENGE.ordinal()) {
            session.phase = DeepThoughtSession.Phase.CHALLENGE;
        }
    }

    private void updateProgress(String beforeFingerprint) {
        String after = semanticFingerprint();
        if (after.equals(beforeFingerprint)) {
            session.stagnantRounds++;
        } else {
            session.stagnantRounds = 0;
        }
        session.progressFingerprint = after;
    }

    private void refreshProgressFingerprintIfMissing() {
        if (clean(session.progressFingerprint, 256).isBlank()) {
            session.progressFingerprint = semanticFingerprint();
        }
    }

    /**
     * Fingerprints durable semantic investigation state rather than free-form
     * prose. Rewording the same summary therefore does not reset stagnation.
     */
    private String semanticFingerprint() {
        StringBuilder state = new StringBuilder(8_192);
        state.append(session.phase.name()).append('|');

        session.claims.stream()
                .sorted(Comparator.comparing(DeepThoughtSession.Claim::id))
                .forEach(claim -> state.append("C:")
                        .append(claim.id()).append(':')
                        .append(normalizeState(claim.state())).append(':')
                        .append(claim.required()).append(':')
                        .append(hash(clean(claim.text(), 2_000))).append(':')
                        .append(sortedJoined(claim.evidenceIds())).append(':')
                        .append(sortedJoined(claim.contradictingEvidenceIds()))
                        .append('|'));

        session.evidence.stream()
                .sorted(Comparator.comparing(DeepThoughtSession.Evidence::id))
                .forEach(evidence -> state.append("E:")
                        .append(evidence.id()).append(':')
                        .append(clean(evidence.sourceIdentifier(), 400)).append(':')
                        .append(evidence.independent()).append(':')
                        .append(evidence.reproducible()).append(':')
                        .append(hash(clean(evidence.exactData(), 4_000))).append(':')
                        .append(sortedJoined(evidence.claimIds()))
                        .append('|'));

        session.hypotheses.stream()
                .sorted(Comparator.comparing(DeepThoughtSession.Hypothesis::id))
                .forEach(hypothesis -> state.append("H:")
                        .append(hypothesis.id()).append(':')
                        .append(normalizeState(hypothesis.state())).append(':')
                        .append(hash(clean(hypothesis.statement(), 2_000))).append(':')
                        .append(hash(clean(hypothesis.falsificationTest(), 2_000)))
                        .append('|'));

        session.tests.stream()
                .sorted(Comparator.comparing(DeepThoughtSession.TestRecord::id))
                .forEach(test -> state.append("T:")
                        .append(test.id()).append(':')
                        .append(normalizeState(test.state())).append(':')
                        .append(hash(clean(test.result(), 2_000)))
                        .append('|'));

        session.contradictions.stream()
                .sorted(Comparator.comparing(DeepThoughtSession.Contradiction::id))
                .forEach(value -> state.append("X:")
                        .append(value.id()).append(':')
                        .append(normalizeState(value.state())).append(':')
                        .append(hash(clean(value.detail(), 2_000)))
                        .append('|'));

        session.assumptions.stream().map(value -> "A:" + hash(value)).sorted().forEach(state::append);
        session.unresolvedQuestions.stream().map(value -> "Q:" + hash(value)).sorted().forEach(state::append);
        session.limitations.stream().map(value -> "L:" + hash(value)).sorted().forEach(state::append);

        return hash(state.toString());
    }

    private void addClaim(String text, boolean required) {
        String safe = clean(text, 1_500);
        if (safe.isBlank()) {
            return;
        }

        for (int i = 0; i < session.claims.size(); i++) {
            DeepThoughtSession.Claim existing = session.claims.get(i);
            if (equivalentText(existing.text(), safe)) {
                if (required && !existing.required()) {
                    session.claims.set(i, new DeepThoughtSession.Claim(
                            existing.id(),
                            existing.text(),
                            existing.state(),
                            true,
                            existing.evidenceIds(),
                            existing.contradictingEvidenceIds()
                    ));
                }
                return;
            }
        }

        if (session.claims.size() >= MAXIMUM_CLAIMS) {
            addLimitationOnce(
                    "Claim ledger reached its bounded " + MAXIMUM_CLAIMS + "-claim limit."
            );
            return;
        }

        session.claims.add(new DeepThoughtSession.Claim(
                stableId("claim", safe),
                safe,
                "proposed",
                required,
                List.of(),
                List.of()
        ));
    }

    private void addHypothesis(String statement, String falsificationTest) {
        String safeStatement = clean(statement, 1_500);
        String safeTest = clean(falsificationTest, 1_000);
        if (safeStatement.isBlank()) {
            return;
        }

        for (DeepThoughtSession.Hypothesis existing : session.hypotheses) {
            if (equivalentText(existing.statement(), safeStatement)) {
                return;
            }
        }

        if (session.hypotheses.size() >= MAXIMUM_HYPOTHESES) {
            addLimitationOnce(
                    "Hypothesis ledger reached its bounded "
                            + MAXIMUM_HYPOTHESES
                            + "-hypothesis limit."
            );
            return;
        }

        DeepThoughtSession.Claim focus = focusClaim();
        List<String> supportingClaims = focus == null ? List.of() : List.of(focus.id());
        session.hypotheses.add(new DeepThoughtSession.Hypothesis(
                stableId("hypothesis", safeStatement),
                safeStatement,
                "proposed",
                supportingClaims,
                List.of(),
                safeTest.isBlank() ? "pending explicit falsification test" : safeTest
        ));
    }

    private void addTestProposal(String description) {
        String safe = clean(description, 1_200);
        if (safe.isBlank()) {
            return;
        }

        for (DeepThoughtSession.TestRecord existing : session.tests) {
            if (equivalentText(existing.description(), safe)) {
                return;
            }
        }

        if (session.tests.size() >= MAXIMUM_TESTS) {
            addLimitationOnce(
                    "Test ledger reached its bounded " + MAXIMUM_TESTS + "-test limit."
            );
            return;
        }

        session.tests.add(new DeepThoughtSession.TestRecord(
                stableId("test", safe),
                safe,
                "pending",
                "",
                ""
        ));
    }

    private void recordObservedTest(ModelToolResult result, DeepThoughtSession.Claim claim) {
        DeepThoughtSession.TestRecord test = focusTest();
        if (test == null) {
            String description = "Observe "
                    + (claim == null ? "the leading material claim" : claim.id())
                    + " with " + clean(result.toolId(), 180);
            addTestProposal(description);
            test = focusTest();
        }
        if (test == null) {
            return;
        }

        String state = result.completedAndValidated() ? "observed" : "inconclusive";
        replaceTest(test.id(), new DeepThoughtSession.TestRecord(
                test.id(),
                test.description(),
                state,
                resultSummary(result),
                clean(result.failureCode(), 300)
        ));
    }

    private void recordChallengeAttempt(ModelToolResult result, DeepThoughtSession.Claim claim) {
        if (session.tests.size() >= MAXIMUM_TESTS) {
            return;
        }

        String description = "Counterexample challenge for "
                + (claim == null ? "leading conclusion" : claim.id())
                + " using " + clean(result.toolId(), 180);
        String id = stableId(
                "challenge",
                description + "|" + lastRecordedEvidenceId
        );
        if (testById(id) != null) {
            return;
        }

        session.tests.add(new DeepThoughtSession.TestRecord(
                id,
                description,
                result.completedAndValidated() ? "observed" : "inconclusive",
                resultSummary(result),
                clean(result.failureCode(), 300)
        ));
    }

    private void promoteSupportedClaim(String claimId) {
        DeepThoughtSession.Claim claim = claimById(claimId);
        if (claim == null) {
            addLimitationOnce(
                    "An investigation artifact referenced unknown claim "
                            + clean(claimId, 160)
                            + "; no claim state changed."
            );
            return;
        }

        List<DeepThoughtSession.Evidence> linked = linkedEvidence(claim);
        boolean hasValidated = linked.stream().anyMatch(DeepThoughtSession.Evidence::reproducible);
        if (!hasValidated) {
            addLimitationOnce(
                    "Claim " + claim.id()
                            + " was not promoted because no validated linked evidence is recorded."
            );
            return;
        }

        String nextState = hasIndependentVerification(linked)
                ? "independently_verified"
                : "supported";
        replaceClaim(claim.id(), new DeepThoughtSession.Claim(
                claim.id(),
                claim.text(),
                nextState,
                claim.required(),
                claim.evidenceIds(),
                claim.contradictingEvidenceIds()
        ));
        removeEquivalentString(session.unresolvedQuestions, claim.text());
    }

    private void addContradiction(String requestedClaimId, String detail) {
        String safeDetail = clean(detail, 1_500);
        if (safeDetail.isBlank()) {
            return;
        }

        DeepThoughtSession.Claim claim = claimById(requestedClaimId);
        if (claim == null) {
            claim = focusClaim();
        }

        String evidenceId = lastRecordedEvidenceId;
        List<String> claimIds = claim == null ? List.of() : List.of(claim.id());
        List<String> evidenceIds = evidenceId.isBlank() ? List.of() : List.of(evidenceId);
        String id = stableId(
                "contradiction",
                String.join(",", claimIds) + "|" + String.join(",", evidenceIds) + "|" + safeDetail
        );

        if (contradictionById(id) != null) {
            return;
        }

        if (session.contradictions.size() >= MAXIMUM_CONTRADICTIONS) {
            addLimitationOnce(
                    "Contradiction ledger reached its bounded "
                            + MAXIMUM_CONTRADICTIONS
                            + "-entry limit."
            );
            return;
        }

        session.contradictions.add(new DeepThoughtSession.Contradiction(
                id,
                claimIds,
                evidenceIds,
                "unresolved",
                safeDetail
        ));

        if (claim != null && !evidenceId.isBlank()) {
            linkEvidenceToClaim(claim.id(), evidenceId, true);
        }
    }

    private void resolveContradiction(String contradictionId, String detail) {
        DeepThoughtSession.Contradiction existing = contradictionById(contradictionId);
        if (existing == null) {
            addLimitationOnce(
                    "An investigation artifact attempted to resolve unknown contradiction "
                            + clean(contradictionId, 180)
                            + "."
            );
            return;
        }

        if (existing.evidenceIds().isEmpty() && session.evidence.isEmpty()) {
            addLimitationOnce(
                    "Contradiction " + existing.id()
                            + " was not resolved because no evidence is recorded."
            );
            return;
        }

        replaceContradiction(existing.id(), new DeepThoughtSession.Contradiction(
                existing.id(),
                existing.claimIds(),
                existing.evidenceIds(),
                "resolved",
                clean(existing.detail() + " | resolution: " + clean(detail, 900), 1_800)
        ));
    }

    private void updateTestOutcome(String testId, String state, String detail) {
        DeepThoughtSession.TestRecord test = testById(testId);
        if (test == null) {
            addLimitationOnce(
                    "An investigation artifact referenced unknown test "
                            + clean(testId, 180)
                            + "; no test state changed."
            );
            return;
        }

        String current = normalizeState(test.state());
        if (!Set.of("observed", "passed", "failed").contains(current)) {
            addLimitationOnce(
                    "Test " + test.id()
                            + " cannot be judged " + state
                            + " before a concrete observation is recorded."
            );
            return;
        }

        replaceTest(test.id(), new DeepThoughtSession.TestRecord(
                test.id(),
                test.description(),
                state,
                clean(detail, 1_500),
                test.failureCode()
        ));
    }

    private void linkEvidenceToClaim(String claimId, String evidenceId, boolean contradicting) {
        DeepThoughtSession.Claim claim = claimById(claimId);
        DeepThoughtSession.Evidence evidence = evidenceById(evidenceId);
        if (claim == null || evidence == null) {
            return;
        }

        List<String> supporting = appendUnique(claim.evidenceIds(), contradicting ? null : evidenceId);
        List<String> conflicting = appendUnique(
                claim.contradictingEvidenceIds(),
                contradicting ? evidenceId : null
        );

        String nextState = claim.state();
        if ("proposed".equals(normalizeState(nextState))) {
            nextState = "evidence_collected";
        }

        replaceClaim(claim.id(), new DeepThoughtSession.Claim(
                claim.id(),
                claim.text(),
                nextState,
                claim.required(),
                supporting,
                conflicting
        ));

        if (!evidence.claimIds().contains(claim.id())) {
            replaceEvidence(evidence.id(), new DeepThoughtSession.Evidence(
                    evidence.id(),
                    evidence.sourceType(),
                    evidence.sourceIdentifier(),
                    evidence.exactData(),
                    evidence.authority(),
                    evidence.independent(),
                    evidence.reproducible(),
                    evidence.retrievedAtMillis(),
                    appendUnique(evidence.claimIds(), claim.id())
            ));
        }
    }

    private boolean hasIndependentVerification(List<DeepThoughtSession.Evidence> evidence) {
        Set<String> independentSources = new LinkedHashSet<>();
        for (DeepThoughtSession.Evidence item : evidence) {
            if (item.reproducible() && item.independent()) {
                independentSources.add(normalizedSourceKey(item));
            }
        }
        return independentSources.size() >= 2;
    }

    private boolean independentEvidence(String sourceType, String sourceIdentifier) {
        String sourceKey = normalizeState(sourceType) + "|" + clean(sourceIdentifier, 600);
        if (sourceKey.isBlank()) {
            return false;
        }

        if ("direct_runtime".equals(authorityFromType(sourceType))) {
            return true;
        }

        return session.evidence.stream()
                .noneMatch(existing -> sourceKey.equals(
                        normalizeState(existing.sourceType())
                                + "|" + clean(existing.sourceIdentifier(), 600)
                ));
    }

    private String authority(String toolId, String sourceType) {
        String direct = authorityFromType(sourceType);
        if (!direct.isBlank()) {
            return direct;
        }
        String normalized = normalizeState(toolId);
        if (normalized.startsWith("koil.documentation")) {
            return "project_documentation";
        }
        return "registered_read_only_tool";
    }

    private String authorityFromType(String sourceType) {
        return switch (normalizeState(sourceType)) {
            case "minecraft_client_state",
                 "workspace_state",
                 "development_state",
                 "command_tree" -> "direct_runtime";
            case "public_research" -> "retrieved_public_source";
            case "koil_documentation" -> "project_documentation";
            default -> "";
        };
    }

    private String sourceType(String toolId) {
        String id = normalizeState(toolId);
        if (id.startsWith("minecraft.")) {
            if (id.contains("command")) {
                return "command_tree";
            }
            return "minecraft_client_state";
        }
        if (id.startsWith("workspace.")) {
            return "workspace_state";
        }
        if (id.startsWith("development.")) {
            return "development_state";
        }
        if (id.startsWith("internet.") || id.contains("research") || id.contains("web")) {
            return "public_research";
        }
        if (id.startsWith("koil.documentation")) {
            return "koil_documentation";
        }
        if (id.startsWith("automation.skill")) {
            return "ktl_catalog";
        }
        return "registered_tool_result";
    }

    private String sourceIdentifier(ModelToolResult result, String exactData) {
        String tool = clean(result.toolId(), 240);
        Matcher matcher = SOURCE_LOCATOR.matcher(exactData);
        if (matcher.find()) {
            String locator = clean(unescapeJsonString(matcher.group(1)), 600);
            if (!locator.isBlank()) {
                return tool + ":" + locator;
            }
        }
        return tool;
    }

    private String evidencePayload(ModelToolResult result) {
        StringBuilder out = new StringBuilder(2_000);
        out.append("status=").append(clean(result.status(), 100));
        out.append("; validation=").append(clean(result.validationStatus(), 100));
        out.append("; approval=").append(clean(result.approvalStatus(), 100));
        out.append("; retryable=").append(result.retryable());
        out.append("; cancelled=").append(result.cancelled());
        out.append("; durationMillis=").append(result.durationMillis());

        if (!clean(result.failureCode(), 300).isBlank()) {
            out.append("; failureCode=").append(clean(result.failureCode(), 300));
        }
        if (!clean(result.detail(), MAXIMUM_DETAIL_CHARACTERS).isBlank()) {
            out.append("; detail=").append(clean(result.detail(), MAXIMUM_DETAIL_CHARACTERS));
        }
        if (result.changedTargets() != null && !result.changedTargets().isEmpty()) {
            out.append("; changedTargets=")
                    .append(clean(String.join(",", result.changedTargets()), 1_000));
        }
        if (result.output() != null && result.output().size() > 0) {
            out.append("; output=")
                    .append(clean(result.output().toString(), MAXIMUM_EVIDENCE_DATA_CHARACTERS));
        }

        return bounded(out.toString(), MAXIMUM_EVIDENCE_DATA_CHARACTERS);
    }

    private String resultSummary(ModelToolResult result) {
        if (result == null) {
            return "no_result";
        }
        String detail = clean(result.detail(), 700);
        return clean(
                "tool=" + result.toolId()
                        + " status=" + result.status()
                        + " validation=" + result.validationStatus()
                        + (detail.isBlank() ? "" : " detail=" + detail),
                1_200
        );
    }

    private long effectiveRetrievedAt(ModelToolResult result) {
        if (result == null) {
            return System.currentTimeMillis();
        }
        return result.completedAtMillis() > 0L
                ? result.completedAtMillis()
                : System.currentTimeMillis();
    }

    private DeepThoughtSession.Claim focusClaim() {
        DeepThoughtSession.Claim claim = firstRequiredClaimWithoutSupport();
        if (claim != null) {
            return claim;
        }
        claim = firstRequiredClaimNeedingIndependentVerification();
        if (claim != null) {
            return claim;
        }
        return session.claims.stream()
                .filter(DeepThoughtSession.Claim::required)
                .findFirst()
                .orElseGet(() -> session.claims.stream().findFirst().orElse(null));
    }

    private DeepThoughtSession.Claim focusClaimForEvidence(DeepThoughtSession.Phase phase) {
        if (phase == DeepThoughtSession.Phase.VERIFY) {
            DeepThoughtSession.Claim unverified = firstRequiredClaimNeedingIndependentVerification();
            if (unverified != null) {
                return unverified;
            }
        }

        if (phase == DeepThoughtSession.Phase.TEST
                || phase == DeepThoughtSession.Phase.CHALLENGE) {
            DeepThoughtSession.Hypothesis hypothesis = focusHypothesis();
            if (hypothesis != null) {
                for (String claimId : hypothesis.supportingClaimIds()) {
                    DeepThoughtSession.Claim claim = claimById(claimId);
                    if (claim != null) {
                        return claim;
                    }
                }
            }
        }

        DeepThoughtSession.Claim unsupported = firstRequiredClaimWithoutSupport();
        if (unsupported != null) {
            return unsupported;
        }
        return focusClaim();
    }

    private DeepThoughtSession.Claim firstRequiredClaimWithoutSupport() {
        return session.claims.stream()
                .filter(DeepThoughtSession.Claim::required)
                .filter(claim -> !SUPPORTED_CLAIM_STATES.contains(normalizeState(claim.state())))
                .findFirst()
                .orElse(null);
    }

    private DeepThoughtSession.Claim firstRequiredClaimNeedingIndependentVerification() {
        return session.claims.stream()
                .filter(DeepThoughtSession.Claim::required)
                .filter(claim -> "supported".equals(normalizeState(claim.state())))
                .findFirst()
                .orElse(null);
    }

    private DeepThoughtSession.Hypothesis focusHypothesis() {
        return session.hypotheses.stream()
                .filter(hypothesis -> !"rejected".equals(normalizeState(hypothesis.state())))
                .findFirst()
                .orElseGet(() -> session.hypotheses.stream().findFirst().orElse(null));
    }

    private DeepThoughtSession.TestRecord focusTest() {
        return session.tests.stream()
                .filter(test -> !test.id().startsWith("challenge-"))
                .filter(test -> "pending".equals(normalizeState(test.state()))
                        || "observed".equals(normalizeState(test.state())))
                .findFirst()
                .orElseGet(() -> session.tests.stream()
                        .filter(test -> !test.id().startsWith("challenge-"))
                        .findFirst()
                        .orElse(null));
    }

    private boolean hasAnyLinkedEvidence(DeepThoughtSession.Claim claim) {
        if (claim == null) {
            return false;
        }
        return claim.evidenceIds().stream().anyMatch(id -> evidenceById(id) != null)
                || claim.contradictingEvidenceIds().stream().anyMatch(id -> evidenceById(id) != null);
    }

    private List<DeepThoughtSession.Evidence> linkedEvidence(DeepThoughtSession.Claim claim) {
        if (claim == null) {
            return List.of();
        }
        List<DeepThoughtSession.Evidence> out = new ArrayList<>();
        for (String id : claim.evidenceIds()) {
            DeepThoughtSession.Evidence evidence = evidenceById(id);
            if (evidence != null) {
                out.add(evidence);
            }
        }
        return List.copyOf(out);
    }

    private boolean requiresCompetingHypotheses() {
        String objective = normalizeState(session.normalizedObjective);
        if (objective.isBlank()) {
            objective = normalizeState(session.originalQuestion);
        }
        return containsAny(
                objective,
                " why ", "cause", "caused", "explain", "explanation",
                "diagnose", "debug", "root cause", "tradeoff", "trade-off",
                "compare", "best", "strategy", "architecture", "design",
                "likely", "hypothesis", "theory", "reason"
        );
    }

    private int distinctHypothesisCount() {
        return (int) session.hypotheses.stream()
                .map(value -> normalizeText(value.statement()))
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
    }

    private int distinctEvidenceSources() {
        return (int) session.evidence.stream()
                .filter(DeepThoughtSession.Evidence::independent)
                .map(this::normalizedSourceKey)
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
    }

    private String normalizedSourceKey(DeepThoughtSession.Evidence evidence) {
        if (evidence == null) {
            return "";
        }
        return normalizeState(evidence.sourceType())
                + "|"
                + normalizeState(evidence.sourceIdentifier());
    }

    private boolean phaseUsesHypothesis(DeepThoughtSession.Phase phase) {
        return phase == DeepThoughtSession.Phase.HYPOTHESIZE
                || phase == DeepThoughtSession.Phase.TEST
                || phase == DeepThoughtSession.Phase.CHALLENGE
                || phase == DeepThoughtSession.Phase.RECONCILE;
    }

    private boolean phaseUsesEvidenceDigest(DeepThoughtSession.Phase phase) {
        return phase == DeepThoughtSession.Phase.COLLECT
                || phase == DeepThoughtSession.Phase.TEST
                || phase == DeepThoughtSession.Phase.CHALLENGE
                || phase == DeepThoughtSession.Phase.RECONCILE
                || phase == DeepThoughtSession.Phase.VERIFY
                || phase == DeepThoughtSession.Phase.SCORE
                || phase == DeepThoughtSession.Phase.DECIDE;
    }

    private DeepThoughtSession.Evidence newestEvidence() {
        return session.evidence.stream()
                .max(Comparator.comparingLong(DeepThoughtSession.Evidence::retrievedAtMillis))
                .orElse(null);
    }

    private DeepThoughtSession.Claim claimById(String id) {
        String safe = clean(id, 200);
        if (safe.isBlank()) {
            return null;
        }
        return session.claims.stream()
                .filter(claim -> safe.equals(claim.id()))
                .findFirst()
                .orElse(null);
    }

    private DeepThoughtSession.Evidence evidenceById(String id) {
        String safe = clean(id, 200);
        if (safe.isBlank()) {
            return null;
        }
        return session.evidence.stream()
                .filter(evidence -> safe.equals(evidence.id()))
                .findFirst()
                .orElse(null);
    }

    private DeepThoughtSession.TestRecord testById(String id) {
        String safe = clean(id, 200);
        if (safe.isBlank()) {
            return null;
        }
        return session.tests.stream()
                .filter(test -> safe.equals(test.id()))
                .findFirst()
                .orElse(null);
    }

    private DeepThoughtSession.Contradiction contradictionById(String id) {
        String safe = clean(id, 200);
        if (safe.isBlank()) {
            return null;
        }
        return session.contradictions.stream()
                .filter(value -> safe.equals(value.id()))
                .findFirst()
                .orElse(null);
    }

    private void replaceClaim(String id, DeepThoughtSession.Claim replacement) {
        for (int i = 0; i < session.claims.size(); i++) {
            if (id.equals(session.claims.get(i).id())) {
                session.claims.set(i, replacement);
                return;
            }
        }
    }

    private void replaceEvidence(String id, DeepThoughtSession.Evidence replacement) {
        for (int i = 0; i < session.evidence.size(); i++) {
            if (id.equals(session.evidence.get(i).id())) {
                session.evidence.set(i, replacement);
                return;
            }
        }
    }

    private void replaceTest(String id, DeepThoughtSession.TestRecord replacement) {
        for (int i = 0; i < session.tests.size(); i++) {
            if (id.equals(session.tests.get(i).id())) {
                session.tests.set(i, replacement);
                return;
            }
        }
    }

    private void replaceContradiction(String id, DeepThoughtSession.Contradiction replacement) {
        for (int i = 0; i < session.contradictions.size(); i++) {
            if (id.equals(session.contradictions.get(i).id())) {
                session.contradictions.set(i, replacement);
                return;
            }
        }
    }

    private void addLimitation(String value) {
        addBoundedUnique(session.limitations, value, MAXIMUM_LIMITATIONS);
    }

    private void addLimitationOnce(String value) {
        String safe = clean(value, 1_500);
        if (safe.isBlank()) {
            return;
        }
        for (String existing : session.limitations) {
            if (equivalentText(existing, safe)) {
                return;
            }
        }
        addLimitation(safe);
    }

    private void normalizePersistedState() {
        deduplicateStrings(session.assumptions, MAXIMUM_ASSUMPTIONS);
        deduplicateStrings(session.unresolvedQuestions, MAXIMUM_OPEN_QUESTIONS);
        deduplicateStrings(session.limitations, MAXIMUM_LIMITATIONS);

        deduplicateClaims();
        deduplicateEvidence();
        deduplicateHypotheses();
        deduplicateTests();
        deduplicateContradictions();

        if (session.investigationRound < 0) {
            session.investigationRound = 0;
        }
        if (session.stagnantRounds < 0) {
            session.stagnantRounds = 0;
        }
        if (session.phase == null) {
            session.phase = DeepThoughtSession.Phase.DEFINE;
        }
        if (session.lifecycle == null) {
            session.lifecycle = DeepThoughtSession.Lifecycle.PAUSED;
        }
    }

    private void deduplicateClaims() {
        List<DeepThoughtSession.Claim> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeepThoughtSession.Claim claim : session.claims) {
            if (claim == null || unique.size() >= MAXIMUM_CLAIMS) {
                continue;
            }
            String key = normalizeText(claim.text());
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            unique.add(new DeepThoughtSession.Claim(
                    clean(claim.id(), 200).isBlank()
                            ? stableId("claim", claim.text())
                            : clean(claim.id(), 200),
                    clean(claim.text(), 1_500),
                    normalizedClaimState(claim.state()),
                    claim.required(),
                    deduplicatedIds(claim.evidenceIds(), MAXIMUM_EVIDENCE),
                    deduplicatedIds(claim.contradictingEvidenceIds(), MAXIMUM_EVIDENCE)
            ));
        }
        session.claims.clear();
        session.claims.addAll(unique);
    }

    private void deduplicateEvidence() {
        List<DeepThoughtSession.Evidence> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeepThoughtSession.Evidence evidence : session.evidence) {
            if (evidence == null || unique.size() >= MAXIMUM_EVIDENCE) {
                continue;
            }
            String id = clean(evidence.id(), 200);
            if (id.isBlank()) {
                id = stableId(
                        "evidence",
                        evidence.sourceType() + "|" + evidence.sourceIdentifier() + "|" + evidence.exactData()
                );
            }
            if (!seen.add(id)) {
                continue;
            }
            unique.add(new DeepThoughtSession.Evidence(
                    id,
                    clean(evidence.sourceType(), 200),
                    clean(evidence.sourceIdentifier(), 700),
                    clean(evidence.exactData(), MAXIMUM_EVIDENCE_DATA_CHARACTERS),
                    clean(evidence.authority(), 200),
                    evidence.independent(),
                    evidence.reproducible(),
                    Math.max(0L, evidence.retrievedAtMillis()),
                    deduplicatedIds(evidence.claimIds(), MAXIMUM_CLAIMS)
            ));
        }
        session.evidence.clear();
        session.evidence.addAll(unique);
    }

    private void deduplicateHypotheses() {
        List<DeepThoughtSession.Hypothesis> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeepThoughtSession.Hypothesis hypothesis : session.hypotheses) {
            if (hypothesis == null || unique.size() >= MAXIMUM_HYPOTHESES) {
                continue;
            }
            String key = normalizeText(hypothesis.statement());
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            unique.add(new DeepThoughtSession.Hypothesis(
                    clean(hypothesis.id(), 200).isBlank()
                            ? stableId("hypothesis", hypothesis.statement())
                            : clean(hypothesis.id(), 200),
                    clean(hypothesis.statement(), 1_500),
                    normalizeState(hypothesis.state()).isBlank()
                            ? "proposed"
                            : normalizeState(hypothesis.state()),
                    deduplicatedIds(hypothesis.supportingClaimIds(), MAXIMUM_CLAIMS),
                    deduplicatedIds(hypothesis.conflictingClaimIds(), MAXIMUM_CLAIMS),
                    clean(hypothesis.falsificationTest(), 1_000)
            ));
        }
        session.hypotheses.clear();
        session.hypotheses.addAll(unique);
    }

    private void deduplicateTests() {
        List<DeepThoughtSession.TestRecord> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeepThoughtSession.TestRecord test : session.tests) {
            if (test == null || unique.size() >= MAXIMUM_TESTS) {
                continue;
            }
            String id = clean(test.id(), 200);
            if (id.isBlank()) {
                id = stableId("test", test.description());
            }
            if (!seen.add(id)) {
                continue;
            }
            unique.add(new DeepThoughtSession.TestRecord(
                    id,
                    clean(test.description(), 1_200),
                    normalizeState(test.state()).isBlank()
                            ? "pending"
                            : normalizeState(test.state()),
                    clean(test.result(), 1_500),
                    clean(test.failureCode(), 300)
            ));
        }
        session.tests.clear();
        session.tests.addAll(unique);
    }

    private void deduplicateContradictions() {
        List<DeepThoughtSession.Contradiction> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeepThoughtSession.Contradiction contradiction : session.contradictions) {
            if (contradiction == null || unique.size() >= MAXIMUM_CONTRADICTIONS) {
                continue;
            }
            String id = clean(contradiction.id(), 200);
            if (id.isBlank()) {
                id = stableId("contradiction", contradiction.detail());
            }
            if (!seen.add(id)) {
                continue;
            }
            unique.add(new DeepThoughtSession.Contradiction(
                    id,
                    deduplicatedIds(contradiction.claimIds(), MAXIMUM_CLAIMS),
                    deduplicatedIds(contradiction.evidenceIds(), MAXIMUM_EVIDENCE),
                    normalizeState(contradiction.state()).isBlank()
                            ? "unresolved"
                            : normalizeState(contradiction.state()),
                    clean(contradiction.detail(), 1_800)
            ));
        }
        session.contradictions.clear();
        session.contradictions.addAll(unique);
    }

    private String normalizedClaimState(String state) {
        String normalized = normalizeState(state);
        if (Set.of(
                "proposed",
                "evidence_collected",
                "supported",
                "independently_verified",
                "rejected",
                "unresolved"
        ).contains(normalized)) {
            return normalized;
        }
        return normalized.isBlank() ? "proposed" : normalized;
    }

    private void updateConfidence() {
        DeepThoughtConfidenceEngine.Result result = safeConfidence();
        session.confidence = result.classification();
        session.evidenceCoveragePercent = Math.max(0, Math.min(100, result.coveragePercent()));
    }

    private DeepThoughtConfidenceEngine.Result safeConfidence() {
        try {
            return DeepThoughtConfidenceEngine.calculate(session);
        } catch (RuntimeException failure) {
            return new DeepThoughtConfidenceEngine.Result(
                    "unresolved",
                    0,
                    true,
                    false
            );
        }
    }

    private void accrueActiveTime() {
        long now = System.currentTimeMillis();
        if (countsAsActive(session.lifecycle)) {
            session.activeMillis += Math.max(0L, now - phaseStartedAt);
        }
        session.updatedAtMillis = Math.max(session.updatedAtMillis, now);
        phaseStartedAt = now;
    }

    private boolean countsAsActive(DeepThoughtSession.Lifecycle lifecycle) {
        return lifecycle == DeepThoughtSession.Lifecycle.ACTIVE
                || lifecycle == DeepThoughtSession.Lifecycle.WAITING_FOR_DATA
                || lifecycle == DeepThoughtSession.Lifecycle.WAITING_FOR_APPROVAL
                || lifecycle == DeepThoughtSession.Lifecycle.FINALIZING;
    }

    private boolean terminalLifecycle() {
        return session.lifecycle == DeepThoughtSession.Lifecycle.COMPLETED
                || session.lifecycle == DeepThoughtSession.Lifecycle.CANCELLED;
    }

    private void checkpoint() {
        accrueActiveTime();
        try {
            DeepThoughtSessionStore.save(scope, session);
            checkpointFailureCount = 0;
        } catch (Exception failure) {
            checkpointFailureCount++;
            addLimitationOnce(
                    "Checkpoint failed"
                            + (checkpointFailureCount > 1 ? " (" + checkpointFailureCount + " consecutive failures)" : "")
                            + ": "
                            + clean(failure.getMessage(), 300)
            );
        }
    }

    private static void addBoundedUnique(List<String> target, String value, int maximum) {
        String safe = clean(value, 1_500);
        if (safe.isBlank()) {
            return;
        }
        for (String existing : target) {
            if (equivalentText(existing, safe)) {
                return;
            }
        }
        if (target.size() < maximum) {
            target.add(safe);
        }
    }

    private static void deduplicateStrings(List<String> values, int maximum) {
        List<String> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String safe = clean(value, 1_500);
            String key = normalizeText(safe);
            if (!safe.isBlank() && seen.add(key)) {
                unique.add(safe);
            }
            if (unique.size() >= maximum) {
                break;
            }
        }
        values.clear();
        values.addAll(unique);
    }

    private static void removeEquivalentString(List<String> values, String target) {
        String key = normalizeText(target);
        if (key.isBlank()) {
            return;
        }
        values.removeIf(value -> key.equals(normalizeText(value)));
    }

    private static List<String> appendUnique(List<String> source, String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (source != null) {
            for (String item : source) {
                String safe = clean(item, 200);
                if (!safe.isBlank()) {
                    out.add(safe);
                }
            }
        }
        String safeValue = clean(value, 200);
        if (!safeValue.isBlank()) {
            out.add(safeValue);
        }
        return List.copyOf(out);
    }

    private static List<String> deduplicatedIds(List<String> source, int maximum) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (source != null) {
            for (String item : source) {
                String safe = clean(item, 200);
                if (!safe.isBlank()) {
                    out.add(safe);
                }
                if (out.size() >= maximum) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static String sortedJoined(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(value -> clean(value, 200))
                .filter(value -> !value.isBlank())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static boolean equivalentText(String left, String right) {
        String a = normalizeText(left);
        String b = normalizeText(right);
        return !a.isBlank() && a.equals(b);
    }

    private static String normalizeText(String value) {
        return clean(value, 4_000)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}&&[^:_/-]]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String normalizeState(String value) {
        return clean(value, 300)
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
    }

    private static String normalizeTag(String value) {
        return clean(value, 100)
                .toUpperCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
    }

    private static String stripPresentationPrefix(String value) {
        String safe = value == null ? "" : value.strip();
        safe = safe.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
        while (safe.startsWith("- ") || safe.startsWith("* ") || safe.startsWith("> ")) {
            safe = safe.substring(2).stripLeading();
        }
        return safe;
    }

    private static String[] splitPayload(String payload, int maximumParts) {
        String[] raw = payload.split("\\|", maximumParts);
        List<String> cleaned = new ArrayList<>();
        for (String piece : raw) {
            String safe = clean(piece, 1_500);
            if (!safe.isBlank()) {
                cleaned.add(safe);
            }
        }
        return cleaned.toArray(String[]::new);
    }

    private static String firstField(String value) {
        String[] pieces = splitPayload(value, 2);
        return pieces.length == 0 ? "" : pieces[0];
    }

    private static boolean containsAny(String value, String... fragments) {
        String padded = " " + normalizeState(value).replace('_', ' ') + " ";
        for (String fragment : fragments) {
            if (padded.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String unescapeJsonString(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value == null ? "" : value;
        }
        return value
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String stableId(String prefix, String value) {
        String digest = hash(prefix + "|" + clean(value, 16_000));
        String shortHash = digest.length() > 16 ? digest.substring(0, 16) : digest;
        return prefix + "-" + shortHash;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception ignored) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    private static String clean(String value, int maximum) {
        String safe = value == null ? "" : value
                .replace('\u0000', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        if (maximum <= 0 || safe.length() <= maximum) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return "";
        }
        if (maximum <= 0 || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    private static String boundedPreservingFormatting(String value, int maximum) {
        if (value == null) {
            return "";
        }
        String safe = value.replace('\u0000', ' ').strip();
        if (maximum <= 0 || safe.length() <= maximum) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    public record InvestigationStatus(
            DeepThoughtSession.Phase phase,
            DeepThoughtSession.Lifecycle lifecycle,
            int investigationRound,
            int stagnantRounds,
            int requiredClaims,
            int supportedRequiredClaims,
            int independentlyVerifiedRequiredClaims,
            int evidenceCount,
            int distinctEvidenceSources,
            int hypothesisCount,
            int testCount,
            int unresolvedContradictions,
            int openQuestions,
            int limitationCount,
            String focusClaimId,
            String focusClaim,
            String focusHypothesisId,
            String confidenceClassification,
            int evidenceCoveragePercent,
            String gateReason
    ) {
    }

    private record PhaseGate(
            boolean ready,
            DeepThoughtSession.Phase next,
            String reason
    ) {
    }

    private record HypothesisArtifact(
            String statement,
            String falsificationTest
    ) {
    }

    private record ContradictionArtifact(
            String claimId,
            String detail
    ) {
    }

    private record ResolutionArtifact(
            String contradictionId,
            String detail
    ) {
    }

    private record TestOutcomeArtifact(
            String testId,
            String detail
    ) {
    }

    private record ParsedArtifacts(
            List<String> requiredClaims,
            List<String> optionalClaims,
            List<String> assumptions,
            List<String> openQuestions,
            List<HypothesisArtifact> hypotheses,
            List<String> testProposals,
            List<String> supportedClaimIds,
            List<ContradictionArtifact> contradictions,
            List<ResolutionArtifact> resolutions,
            List<TestOutcomeArtifact> testPasses,
            List<TestOutcomeArtifact> testFailures,
            List<String> limitations,
            String summary
    ) {
        static ParsedArtifacts empty() {
            return new ParsedArtifacts(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    ""
            );
        }
    }
}
