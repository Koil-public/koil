package com.spirit.koil.api.model.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.model.ModelObjectiveLedger;
import com.spirit.koil.api.model.ModelToolArgumentParser;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.deepthought.DeepThoughtConfidenceEngine;
import com.spirit.koil.api.model.deepthought.DeepThoughtInvestigationController;
import com.spirit.koil.api.model.deepthought.DeepThoughtSession;
import com.spirit.koil.api.model.deepthought.DeepThoughtSessionStore;
import com.spirit.koil.api.model.tool.ModelWorkspaceRegistry;
import com.spirit.koil.api.model.tool.ModelWorkspaceToolRegistry;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Deterministic proof for the provider-neutral agent contracts added above providers. */
public final class ModelAgentPlatformProof {
    private ModelAgentPlatformProof() {}

    public static void main(String[] args) throws Exception {
        proveArgumentRepair();
        proveObjectiveLedger();
        proveWorkspaceRevisionAndDiff();
        proveDeepThoughtCheckpointAndConfidence();
        System.out.println("Model agent platform proof passed.");
    }

    private static void proveArgumentRepair() {
        JsonObject repaired = ModelToolArgumentParser.parseObject("```json\n{\"path\":\"a.txt\",}\n```");
        require("a.txt".equals(repaired.get("path").getAsString()), "tool argument repair lost an exact value");
        JsonObject completed = ModelToolArgumentParser.parseObject("{\"path\":\"b.txt\"");
        require("b.txt".equals(completed.get("path").getAsString()), "tool argument repair did not close an unambiguous object");
    }

    private static void proveObjectiveLedger() {
        ModelObjectiveLedger ledger = ModelObjectiveLedger.parse("Walk forward, then walk backward, then jump.");
        require(ledger.snapshot().size() == 3, "distinct repeated imperative objectives were collapsed");
        ledger.record(completed("one", "movement.walk_relative", new JsonObject()));
        require(ledger.pendingToolIds().contains("movement.walk_relative"), "one result incorrectly satisfied two walk objectives");
        ledger.record(completed("two", "movement.walk_relative", new JsonObject()));
        ledger.record(completed("three", "player.jump", new JsonObject()));
        require(ledger.satisfied(), "objective ledger did not consume structured completion evidence");
    }

    private static void proveWorkspaceRevisionAndDiff() throws Exception {
        AutomationModeController.setAutomationMode(true);
        AutomationModeController.enableYoloMode();
        String path = "proof/model-agent-" + UUID.randomUUID() + ".txt";
        JsonObject create = args("instance", path); create.addProperty("content", "alpha\nbeta\n");
        ModelToolResult created = execute("workspace.create", create);
        require(created.completedAndValidated(), "workspace create was not validated");
        require(created.output().get("linesAdded").getAsInt() > 0, "create omitted real added-line evidence");
        String firstHash = created.output().get("resultingContentHash").getAsString();

        JsonObject write = args("instance", path); write.addProperty("content", "alpha\ngamma\n"); write.addProperty("expectedHash", firstHash);
        ModelToolResult written = execute("workspace.write", write);
        require(written.completedAndValidated(), "hash-guarded write failed");
        require(written.output().getAsJsonArray("diffHunks").size() == 1, "write omitted a filesystem diff hunk");

        JsonObject stale = args("instance", path); stale.addProperty("find", "gamma"); stale.addProperty("replacement", "delta"); stale.addProperty("expectedHash", firstHash);
        ModelToolResult rejected = execute("workspace.replace", stale);
        require("stale".equals(rejected.status()) && rejected.retryable(), "stale mutation was not rejected structurally");

        String currentHash = written.output().get("resultingContentHash").getAsString();
        JsonObject remove = args("instance", path); remove.addProperty("expectedHash", currentHash);
        ModelToolResult deleted = execute("workspace.delete", remove);
        require(deleted.completedAndValidated() && deleted.output().get("recoverable").getAsBoolean(), "delete was not recoverable");
        JsonObject restore = args("instance", path); restore.addProperty("recoveryToken", deleted.output().get("recoveryToken").getAsString());
        ModelToolResult restored = execute("workspace.restore", restore);
        require(restored.completedAndValidated(), "recoverable restore failed");
        Files.deleteIfExists(ModelWorkspaceRegistry.resolve("instance", path, true).path());
        AutomationModeController.setAutomationMode(false);
    }

    private static void proveDeepThoughtCheckpointAndConfidence() {
        String scope = "proof-" + UUID.randomUUID();
        DeepThoughtSession session = new DeepThoughtSession(UUID.randomUUID().toString(), "proof", "Verify a deterministic property");
        session.claims.add(new DeepThoughtSession.Claim("claim-1", "property is true", "independently_verified", true,
                List.of("evidence-1"), List.of()));
        session.evidence.add(new DeepThoughtSession.Evidence("evidence-1", "registered_tool_result", "proof",
                "exact result", "direct_runtime", true, true, System.currentTimeMillis(), List.of("claim-1")));
        DeepThoughtConfidenceEngine.Result confidence = DeepThoughtConfidenceEngine.calculate(session);
        require("verified".equals(confidence.classification()), "closed deterministic evidence did not reach verified");
        DeepThoughtInvestigationController controller = new DeepThoughtInvestigationController(scope, session);
        controller.pause();
        DeepThoughtSession cancelled = new DeepThoughtSession(UUID.randomUUID().toString(), "proof", "Cancelled newer session");
        cancelled.updatedAtMillis = session.updatedAtMillis + 10_000L;
        cancelled.lifecycle = DeepThoughtSession.Lifecycle.CANCELLED;
        try { DeepThoughtSessionStore.save(scope, cancelled); }
        catch (Exception failure) { throw new IllegalStateException("could not save lifecycle proof checkpoint", failure); }
        List<DeepThoughtSession> restored = DeepThoughtSessionStore.load(scope);
        require(restored.size() == 2 && restored.stream().anyMatch(value -> value.claims.size() == 1), "Deep Thought checkpoint lost its claim ledger");
        require(DeepThoughtSessionStore.newestRestorable(scope) != null
                        && DeepThoughtSessionStore.newestRestorable(scope).deepThoughtSessionId.equals(session.deepThoughtSessionId),
                "lifecycle restore selected a cancelled checkpoint");
        controller.complete("Verified result");
        controller.markFinalPresented();
        require(session.finalPresentedAtMillis > 0L,
                "completed Deep Thought result did not persist its presentation acknowledgement");
        require(DeepThoughtSessionStore.newestRestorable(scope) == null,
                "an already presented Deep Thought result was selected for another join restore");
        DeepThoughtSessionStore.delete(scope, session.deepThoughtSessionId);
        DeepThoughtSessionStore.delete(scope, cancelled.deepThoughtSessionId);
    }

    private static ModelToolResult execute(String id, JsonObject arguments) {
        return ModelWorkspaceToolRegistry.execute(UUID.randomUUID(), new ModelToolCall(UUID.randomUUID().toString(), id, arguments), true).join();
    }

    private static ModelToolResult completed(String call, String tool, JsonObject output) {
        return new ModelToolResult(call, tool, "completed", output, "", "done",
                1L, 2L, "passed", List.of(), false, false, "approved");
    }

    private static JsonObject args(String workspace, String path) {
        JsonObject value = new JsonObject(); value.addProperty("workspace", workspace); value.addProperty("path", path); return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
