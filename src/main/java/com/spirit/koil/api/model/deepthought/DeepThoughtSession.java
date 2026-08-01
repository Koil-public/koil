package com.spirit.koil.api.model.deepthought;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persisted structured investigation state. It intentionally contains no private chain-of-thought. */
public final class DeepThoughtSession {
    public static final int FORMAT_VERSION = 1;

    public final String deepThoughtSessionId;
    public final String requestId;
    public final String conversationId;
    public final String objectiveId;
    public final String originalQuestion;
    public final String normalizedObjective;
    public final long createdAtMillis;
    public long updatedAtMillis;
    public long activeMillis;
    public Phase phase = Phase.DEFINE;
    public Lifecycle lifecycle = Lifecycle.ACTIVE;
    public String completionStandard = "Evidence-backed answer or honest documented uncertainty";
    public String confidence = "unresolved";
    public int evidenceCoveragePercent;
    public String lastMeaningfulDiscovery = "";
    public String finalConclusion = "";
    /** Non-zero only after the completed conclusion was inserted into the player's chat. */
    public long finalPresentedAtMillis;
    public String progressFingerprint = "";
    public int stagnantRounds;
    public int investigationRound;
    public final List<Claim> claims = new ArrayList<>();
    public final List<Evidence> evidence = new ArrayList<>();
    public final List<Hypothesis> hypotheses = new ArrayList<>();
    public final List<TestRecord> tests = new ArrayList<>();
    public final List<Contradiction> contradictions = new ArrayList<>();
    public final List<String> assumptions = new ArrayList<>();
    public final List<String> unresolvedQuestions = new ArrayList<>();
    public final List<String> limitations = new ArrayList<>();

    public DeepThoughtSession(String requestId, String conversationId, String question) {
        this("dt-" + UUID.randomUUID(), requestId, conversationId, question,
                question == null ? "" : question.replaceAll("\\s+", " ").strip(), System.currentTimeMillis());
    }

    private DeepThoughtSession(String id, String requestId, String conversationId,
                               String originalQuestion, String normalizedObjective, long createdAtMillis) {
        this.deepThoughtSessionId = clean(id);
        this.requestId = clean(requestId);
        this.conversationId = clean(conversationId);
        this.objectiveId = "objective-" + shortId(this.deepThoughtSessionId);
        this.originalQuestion = originalQuestion == null ? "" : originalQuestion;
        this.normalizedObjective = normalizedObjective == null ? "" : normalizedObjective;
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = createdAtMillis;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("deepThoughtSessionId", deepThoughtSessionId);
        root.addProperty("requestId", requestId);
        root.addProperty("conversationId", conversationId);
        root.addProperty("objectiveId", objectiveId);
        root.addProperty("originalQuestion", originalQuestion);
        root.addProperty("normalizedObjective", normalizedObjective);
        root.addProperty("completionStandard", completionStandard);
        root.addProperty("phase", phase.name());
        root.addProperty("lifecycle", lifecycle.name());
        root.addProperty("confidence", confidence);
        root.addProperty("evidenceCoveragePercent", evidenceCoveragePercent);
        root.addProperty("lastMeaningfulDiscovery", lastMeaningfulDiscovery);
        root.addProperty("finalConclusion", finalConclusion);
        root.addProperty("finalPresentedAtMillis", finalPresentedAtMillis);
        root.addProperty("progressFingerprint", progressFingerprint);
        root.addProperty("stagnantRounds", stagnantRounds);
        root.addProperty("investigationRound", investigationRound);
        root.addProperty("createdAtMillis", createdAtMillis);
        root.addProperty("updatedAtMillis", updatedAtMillis);
        root.addProperty("activeMillis", activeMillis);
        root.add("assumptions", strings(assumptions));
        root.add("unresolvedQuestions", strings(unresolvedQuestions));
        root.add("limitations", strings(limitations));
        root.add("claims", objects(claims.stream().map(Claim::toJson).toList()));
        root.add("evidence", objects(evidence.stream().map(Evidence::toJson).toList()));
        root.add("hypotheses", objects(hypotheses.stream().map(Hypothesis::toJson).toList()));
        root.add("tests", objects(tests.stream().map(TestRecord::toJson).toList()));
        root.add("contradictions", objects(contradictions.stream().map(Contradiction::toJson).toList()));
        return root;
    }

    public static DeepThoughtSession fromJson(JsonObject root) {
        if (integer(root, "version") != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported Deep Thought checkpoint version.");
        DeepThoughtSession session = new DeepThoughtSession(
                string(root, "deepThoughtSessionId"), string(root, "requestId"), string(root, "conversationId"),
                string(root, "originalQuestion"), string(root, "normalizedObjective"), number(root, "createdAtMillis")
        );
        session.updatedAtMillis = number(root, "updatedAtMillis");
        session.activeMillis = number(root, "activeMillis");
        session.phase = enumValue(Phase.class, string(root, "phase"), Phase.DEFINE);
        session.lifecycle = enumValue(Lifecycle.class, string(root, "lifecycle"), Lifecycle.PAUSED);
        session.completionStandard = string(root, "completionStandard");
        session.confidence = string(root, "confidence");
        session.evidenceCoveragePercent = integer(root, "evidenceCoveragePercent");
        session.lastMeaningfulDiscovery = string(root, "lastMeaningfulDiscovery");
        session.finalConclusion = string(root, "finalConclusion");
        session.finalPresentedAtMillis = number(root, "finalPresentedAtMillis");
        session.progressFingerprint = string(root, "progressFingerprint");
        session.stagnantRounds = integer(root, "stagnantRounds");
        session.investigationRound = integer(root, "investigationRound");
        readStrings(root, "assumptions", session.assumptions);
        readStrings(root, "unresolvedQuestions", session.unresolvedQuestions);
        readStrings(root, "limitations", session.limitations);
        readObjects(root, "claims", object -> session.claims.add(Claim.fromJson(object)));
        readObjects(root, "evidence", object -> session.evidence.add(Evidence.fromJson(object)));
        readObjects(root, "hypotheses", object -> session.hypotheses.add(Hypothesis.fromJson(object)));
        readObjects(root, "tests", object -> session.tests.add(TestRecord.fromJson(object)));
        readObjects(root, "contradictions", object -> session.contradictions.add(Contradiction.fromJson(object)));
        return session;
    }

    public enum Phase { DEFINE, DECOMPOSE, DISCOVER, COLLECT, HYPOTHESIZE, TEST, CHALLENGE, RECONCILE, VERIFY, SCORE, DECIDE, FINALIZE }
    public enum Lifecycle { ACTIVE, PAUSED, WAITING_FOR_DATA, WAITING_FOR_APPROVAL, FINALIZING, COMPLETED, CANCELLED, BLOCKED }

    public record Claim(String id, String text, String state, boolean required, List<String> evidenceIds, List<String> contradictingEvidenceIds) {
        JsonObject toJson() { JsonObject o = base(id, text, state); o.addProperty("required", required); o.add("evidenceIds", strings(evidenceIds)); o.add("contradictingEvidenceIds", strings(contradictingEvidenceIds)); return o; }
        static Claim fromJson(JsonObject o) { return new Claim(string(o,"id"), string(o,"text"), string(o,"state"), bool(o,"required"), stringList(o,"evidenceIds"), stringList(o,"contradictingEvidenceIds")); }
    }
    public record Evidence(String id, String sourceType, String sourceIdentifier, String exactData, String authority, boolean independent, boolean reproducible, long retrievedAtMillis, List<String> claimIds) {
        JsonObject toJson() { JsonObject o = base(id, sourceIdentifier, sourceType); o.addProperty("exactData", exactData); o.addProperty("authority", authority); o.addProperty("independent", independent); o.addProperty("reproducible", reproducible); o.addProperty("retrievedAtMillis", retrievedAtMillis); o.add("claimIds", strings(claimIds)); return o; }
        static Evidence fromJson(JsonObject o) { return new Evidence(string(o,"id"), string(o,"state"), string(o,"text"), string(o,"exactData"), string(o,"authority"), bool(o,"independent"), bool(o,"reproducible"), number(o,"retrievedAtMillis"), stringList(o,"claimIds")); }
    }
    public record Hypothesis(String id, String statement, String state, List<String> supportingClaimIds, List<String> conflictingClaimIds, String falsificationTest) {
        JsonObject toJson() { JsonObject o = base(id, statement, state); o.add("supportingClaimIds", strings(supportingClaimIds)); o.add("conflictingClaimIds", strings(conflictingClaimIds)); o.addProperty("falsificationTest", falsificationTest); return o; }
        static Hypothesis fromJson(JsonObject o) { return new Hypothesis(string(o,"id"), string(o,"text"), string(o,"state"), stringList(o,"supportingClaimIds"), stringList(o,"conflictingClaimIds"), string(o,"falsificationTest")); }
    }
    public record TestRecord(String id, String description, String state, String result, String failureCode) {
        JsonObject toJson() { JsonObject o = base(id, description, state); o.addProperty("result", result); o.addProperty("failureCode", failureCode); return o; }
        static TestRecord fromJson(JsonObject o) { return new TestRecord(string(o,"id"), string(o,"text"), string(o,"state"), string(o,"result"), string(o,"failureCode")); }
    }
    public record Contradiction(String id, List<String> claimIds, List<String> evidenceIds, String state, String detail) {
        JsonObject toJson() { JsonObject o = base(id, detail, state); o.add("claimIds", strings(claimIds)); o.add("evidenceIds", strings(evidenceIds)); return o; }
        static Contradiction fromJson(JsonObject o) { return new Contradiction(string(o,"id"), stringList(o,"claimIds"), stringList(o,"evidenceIds"), string(o,"state"), string(o,"text")); }
    }

    private static JsonObject base(String id, String text, String state) { JsonObject o = new JsonObject(); o.addProperty("id", clean(id)); o.addProperty("text", text == null ? "" : text); o.addProperty("state", clean(state)); return o; }
    private static JsonArray strings(List<String> values) { JsonArray a = new JsonArray(); if (values != null) values.forEach(a::add); return a; }
    private static JsonArray objects(List<JsonObject> values) { JsonArray a = new JsonArray(); values.forEach(a::add); return a; }
    private static String clean(String v) { return v == null ? "" : v.strip(); }
    private static String shortId(String v) { String clean = clean(v); return clean.length() <= 12 ? clean : clean.substring(clean.length() - 12); }
    private static String string(JsonObject o, String k) { try { return o.get(k).getAsString(); } catch (Exception ignored) { return ""; } }
    private static long number(JsonObject o, String k) { try { return o.get(k).getAsLong(); } catch (Exception ignored) { return 0L; } }
    private static int integer(JsonObject o, String k) { try { return o.get(k).getAsInt(); } catch (Exception ignored) { return 0; } }
    private static boolean bool(JsonObject o, String k) { try { return o.get(k).getAsBoolean(); } catch (Exception ignored) { return false; } }
    private static List<String> stringList(JsonObject o, String k) { List<String> out = new ArrayList<>(); readStrings(o,k,out); return List.copyOf(out); }
    private static void readStrings(JsonObject o, String k, List<String> out) { if (o.has(k) && o.get(k).isJsonArray()) for (JsonElement e:o.getAsJsonArray(k)) if(e.isJsonPrimitive()) out.add(e.getAsString()); }
    private static void readObjects(JsonObject o, String k, java.util.function.Consumer<JsonObject> consumer) { if(o.has(k)&&o.get(k).isJsonArray()) for(JsonElement e:o.getAsJsonArray(k)) if(e.isJsonObject()) consumer.accept(e.getAsJsonObject()); }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) { try { return Enum.valueOf(type, value); } catch(Exception ignored) { return fallback; } }
}
