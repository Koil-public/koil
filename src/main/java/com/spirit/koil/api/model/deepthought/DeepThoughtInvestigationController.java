package com.spirit.koil.api.model.deepthought;

import com.spirit.koil.api.model.ModelToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Deterministic bounded controller; the provider receives one narrow investigation task per round. */
public final class DeepThoughtInvestigationController {
    private final DeepThoughtSession session;
    private final String scope;
    private long phaseStartedAt = System.currentTimeMillis();

    public DeepThoughtInvestigationController(String scope, DeepThoughtSession session) {
        this.scope = scope == null ? "global" : scope;
        this.session = session;
        checkpoint();
    }

    public DeepThoughtSession session() { return session; }

    public String instruction() {
        return switch (session.phase) {
            case DEFINE -> "Define the exact answer required, scope, assumptions, and testable claims. Do not answer yet.";
            case DECOMPOSE -> "Decompose the objective into required claims and identify what evidence would verify each claim.";
            case DISCOVER -> "Choose only the relevant available read-only capability needed to collect the next missing evidence.";
            case COLLECT -> "Collect or interpret one concrete evidence result. Distinguish returned data from your interpretation.";
            case HYPOTHESIZE -> "Generate at least two competing hypotheses that explain the current evidence and state how each could be falsified.";
            case TEST -> "Select one decisive supported test for the leading material claim. Never claim a test ran without a tool result.";
            case CHALLENGE -> "Actively seek a counterexample or evidence against the leading hypothesis. Do not defend it by default.";
            case RECONCILE -> "Reconcile conflicts by version, scope, date, environment, definition, authority, and independence; preserve unresolved conflict.";
            case VERIFY -> "Independently verify the required conclusion using a different method or source type when possible.";
            case SCORE -> "Summarize evidence coverage and unresolved checks. Do not invent or self-report a confidence percentage.";
            case DECIDE -> "Decide whether acceptance rules are met or whether supported investigation paths are exhausted.";
            case FINALIZE -> "Produce the direct answer with confidence classification, verified facts, strongest evidence, assumptions, contradictions, tests, limitations, and what could improve confidence. Keep it compact.";
        };
    }

    public boolean acceptRoundSummary(String summary) {
        session.investigationRound++;
        String safe = clean(summary, 2_000);
        if (!safe.isBlank()) {
            if (session.phase == DeepThoughtSession.Phase.DEFINE || session.phase == DeepThoughtSession.Phase.DECOMPOSE) {
                session.claims.add(new DeepThoughtSession.Claim("claim-" + UUID.randomUUID(), safe, "proposed", true, List.of(), List.of()));
            } else if (session.phase == DeepThoughtSession.Phase.HYPOTHESIZE || session.phase == DeepThoughtSession.Phase.CHALLENGE) {
                session.hypotheses.add(new DeepThoughtSession.Hypothesis("hypothesis-" + UUID.randomUUID(), safe, "proposed", List.of(), List.of(), "pending"));
            }
            session.lastMeaningfulDiscovery = safe;
        }
        return advanceWithProgressCheck();
    }

    public void recordToolResult(ModelToolResult result) {
        String exact = result == null ? "" : clean(result.output().toString(), 8_000);
        session.evidence.add(new DeepThoughtSession.Evidence(
                "evidence-" + UUID.randomUUID(), "registered_tool_result",
                result == null ? "unknown" : result.toolId(), exact,
                "direct_runtime", true, result != null && result.completedAndValidated(),
                System.currentTimeMillis(), List.of()
        ));
        if (result != null && !result.completedAndValidated()) {
            session.limitations.add(result.status() + ":" + result.failureCode() + " " + clean(result.detail(), 400));
        }
        session.lastMeaningfulDiscovery = result == null ? "No tool result." : clean(result.detail(), 500);
        session.phase = DeepThoughtSession.Phase.COLLECT;
        updateConfidence();
        checkpoint();
    }

    public void pause() { accrue(); session.lifecycle = DeepThoughtSession.Lifecycle.PAUSED; checkpoint(); }
    public void resume() { phaseStartedAt = System.currentTimeMillis(); session.lifecycle = DeepThoughtSession.Lifecycle.ACTIVE; checkpoint(); }
    public void cancel() { accrue(); session.lifecycle = DeepThoughtSession.Lifecycle.CANCELLED; checkpoint(); }
    public void answerNow() { session.lifecycle = DeepThoughtSession.Lifecycle.FINALIZING; session.phase = DeepThoughtSession.Phase.FINALIZE; checkpoint(); }
    public void complete(String conclusion) { accrue(); session.finalConclusion = conclusion == null ? "" : conclusion; session.phase = DeepThoughtSession.Phase.FINALIZE; session.lifecycle = DeepThoughtSession.Lifecycle.COMPLETED; updateConfidence(); checkpoint(); }
    public void markFinalPresented() { DeepThoughtSessionStore.markFinalPresented(scope, session); }

    private boolean advanceWithProgressCheck() {
        String nextFingerprint = fingerprint();
        if (nextFingerprint.equals(session.progressFingerprint)) session.stagnantRounds++;
        else { session.stagnantRounds = 0; session.progressFingerprint = nextFingerprint; }
        if (session.stagnantRounds >= 5) {
            session.limitations.add("Investigation stopped after repeated rounds produced no new evidence, test, claim, contradiction resolution, or hard limitation.");
            session.phase = DeepThoughtSession.Phase.FINALIZE;
        } else if (session.stagnantRounds == 3) {
            session.phase = DeepThoughtSession.Phase.CHALLENGE;
        } else {
            DeepThoughtSession.Phase[] phases = DeepThoughtSession.Phase.values();
            session.phase = phases[Math.min(phases.length - 1, session.phase.ordinal() + 1)];
        }
        updateConfidence(); checkpoint();
        return session.phase == DeepThoughtSession.Phase.FINALIZE;
    }

    private void updateConfidence() { DeepThoughtConfidenceEngine.Result result = DeepThoughtConfidenceEngine.calculate(session); session.confidence = result.classification(); session.evidenceCoveragePercent = result.coveragePercent(); }
    private void accrue() { long now = System.currentTimeMillis(); session.activeMillis += Math.max(0L, now - phaseStartedAt); session.updatedAtMillis = now; phaseStartedAt = now; }
    private void checkpoint() { try { accrue(); DeepThoughtSessionStore.save(scope, session); } catch (Exception failure) { session.limitations.add("Checkpoint failed: " + clean(failure.getMessage(), 300)); } }
    private String fingerprint() { return hash(session.claims.size()+"|"+session.evidence.size()+"|"+session.hypotheses.size()+"|"+session.tests.size()+"|"+session.contradictions.size()+"|"+session.limitations.size()+"|"+session.lastMeaningfulDiscovery); }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception ignored) { return value; } }
    private static String clean(String value, int max) { String safe=value==null?"":value.replaceAll("\\s+"," ").strip(); return safe.length()<=max?safe:safe.substring(0,max-1)+"…"; }
}
