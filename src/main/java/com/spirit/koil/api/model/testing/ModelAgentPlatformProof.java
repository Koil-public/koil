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
import com.spirit.koil.api.model.planning.AutomationProgressGuard;
import com.spirit.koil.api.model.planning.ModelInformationRetrievalPolicy;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
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
        proveProgressAwareRepeatedTools();
        proveSelectiveInformationContract();
        proveWorkspaceRevisionAndDiff();
        proveDeepThoughtCheckpointAndConfidence();
        System.out.println("Model agent platform proof passed.");
    }

    private static void proveSelectiveInformationContract() {
        List<com.spirit.koil.api.model.ModelToolDefinition> searchTools =
                LocalModelToolCatalog.toolsForPrompt("Search files for the exact keyword sessionId");
        require(searchTools.stream().anyMatch(tool -> "workspace.search".equals(tool.id())),
                "explicit workspace search omitted the selective search capability");
        require(searchTools.stream().noneMatch(tool -> tool.id().equals("workspace.write")
                        || tool.id().equals("workspace.delete") || tool.id().equals("workspace.mkdir")),
                "read-only workspace search exposed unrelated mutation schemas");
        require(searchTools.stream().filter(tool -> "workspace.search".equals(tool.id()))
                        .noneMatch(tool -> tool.preconditions().contains("automation_mode_enabled")),
                "read-only workspace schema falsely required Automation Mode");
        String policy = ModelInformationRetrievalPolicy.promptFor(searchTools);
        require(policy.contains("outputMode=count") && policy.contains("exact words/columns")
                        && policy.contains("smallest missing fact"),
                "current tools did not produce the compact selective retrieval contract");
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

    private static void proveProgressAwareRepeatedTools() {
        AutomationProgressGuard guard = new AutomationProgressGuard();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("x", 100);
        arguments.addProperty("y", 64);
        arguments.addProperty("z", 0);
        ModelToolCall call = new ModelToolCall("move-one", "movement.move_to", arguments);
        require(guard.before(call).allowed(), "first movement call was rejected");
        guard.record(call, structuredResult("partial", false, true, 42.0D, 58.0D, 100L));
        require(guard.before(call).allowed(), "a repeated capability with progress was rejected");
        guard.record(call, structuredResult("partial", false, true, 42.0D, 58.0D, 900L));
        require(!guard.before(call).allowed(),
                "duration-only output changes bypassed the same-action/same-observation guard");

        JsonObject updatedArguments = arguments.deepCopy();
        updatedArguments.addProperty("x", 150);
        ModelToolCall changed = new ModelToolCall("move-two", "movement.move_to", updatedArguments);
        require(guard.before(changed).allowed(), "a changed movement target was treated as a blind replay");
    }

    private static ModelToolResult structuredResult(
            String status,
            boolean objectiveReached,
            boolean stateChanged,
            double endX,
            double remaining,
            long duration
    ) {
        JsonObject structured = new JsonObject();
        structured.addProperty("status", status.toUpperCase(java.util.Locale.ROOT));
        structured.addProperty("objectiveReached", objectiveReached);
        structured.addProperty("stateChanged", stateChanged);
        JsonObject after = new JsonObject();
        after.addProperty("x", endX);
        structured.add("after", after);
        structured.add("delta", new JsonObject());
        JsonObject metrics = new JsonObject();
        metrics.addProperty("distance_remaining", remaining);
        metrics.addProperty("duration_ms", duration);
        structured.add("metrics", metrics);
        JsonObject output = new JsonObject();
        output.add("structuredResult", structured);
        return new ModelToolResult("move", "movement.move_to", status, output, "path_blocked", "partial movement");
    }

    private static void proveWorkspaceRevisionAndDiff() throws Exception {
        AutomationModeController.setAutomationMode(true);
        AutomationModeController.enableUnrestrictedMode();
        require(!ModelWorkspaceRegistry.workspaces().get("instance").root()
                        .equals(ModelWorkspaceRegistry.workspaces().get("koil").root())
                        && ModelWorkspaceRegistry.workspaces().get("koil").root().getParent()
                        .equals(ModelWorkspaceRegistry.workspaces().get("instance").root()),
                "instance workspace and Koil-owned data directory were not represented as distinct roots");
        ModelToolResult defaultListing = execute("workspace.list", new JsonObject());
        require(defaultListing.completedAndValidated()
                        && "instance".equals(defaultListing.output().get("workspace").getAsString()),
                "workspace.list did not repair an omitted compact-model workspace to instance");
        String path = "proof/model-agent-" + UUID.randomUUID() + ".txt";
        JsonObject create = args("instance", path); create.addProperty("content", "alpha\nbeta\n");
        ModelToolResult created = execute("workspace.create", create);
        require(created.completedAndValidated(), "workspace create was not validated");
        require(created.output().get("linesAdded").getAsInt() > 0, "create omitted real added-line evidence");
        String firstHash = created.output().get("resultingContentHash").getAsString();

        JsonObject exactSearch = args("instance", "proof");
        exactSearch.addProperty("query", "alpha");
        exactSearch.addProperty("matchMode", "word");
        exactSearch.addProperty("outputMode", "matches");
        exactSearch.addProperty("fileGlob", path.substring("proof/".length()));
        exactSearch.addProperty("maxResults", 4);
        ModelToolResult exactMatches = execute("workspace.search", exactSearch);
        require(exactMatches.completedAndValidated()
                        && exactMatches.output().get("matchedFiles").getAsInt() == 1
                        && exactMatches.output().get("totalMatches").getAsInt() == 1
                        && exactMatches.output().getAsJsonArray("matches").get(0).getAsJsonObject()
                        .get("match").getAsString().equals("alpha")
                        && !exactMatches.output().getAsJsonArray("matches").get(0).getAsJsonObject().has("text"),
                "exact-match workspace search returned broad line content or incorrect evidence");

        JsonObject countSearch = args("instance", "proof");
        countSearch.addProperty("query", "a");
        countSearch.addProperty("outputMode", "count");
        countSearch.addProperty("fileGlob", path.substring("proof/".length()));
        ModelToolResult counts = execute("workspace.search", countSearch);
        require(counts.completedAndValidated()
                        && counts.output().get("totalMatches").getAsInt() == 3
                        && !counts.output().has("matches"),
                "count-only workspace search emitted unnecessary line payloads");

        JsonObject firstReadArgs = args("instance", path);
        firstReadArgs.addProperty("startLine", 1);
        firstReadArgs.addProperty("maxLines", 1);
        ModelToolResult firstRead = execute("workspace.read", firstReadArgs);
        require(firstRead.completedAndValidated()
                        && "1 | alpha\n".equals(firstRead.output().get("text").getAsString())
                        && firstRead.output().get("linesReturned").getAsInt() == 1
                        && firstRead.output().get("hasMore").getAsBoolean()
                        && firstRead.output().get("nextStartLine").getAsInt() == 2,
                "bounded file reading did not expose a truthful continuation cursor");
        JsonObject secondReadArgs = args("instance", path);
        secondReadArgs.addProperty("startLine", firstRead.output().get("nextStartLine").getAsInt());
        secondReadArgs.addProperty("maxLines", 8);
        ModelToolResult secondRead = execute("workspace.read", secondReadArgs);
        require(secondRead.completedAndValidated()
                        && "2 | beta\n".equals(secondRead.output().get("text").getAsString())
                        && secondRead.output().get("complete").getAsBoolean()
                        && !secondRead.output().get("hasMore").getAsBoolean(),
                "multi-line file continuation did not return the remaining content");

        JsonObject write = args("instance", path); write.addProperty("content", "alpha\ngamma\n"); write.addProperty("expectedHash", firstHash);
        ModelToolResult written = execute("workspace.write", write);
        require(written.completedAndValidated(), "hash-guarded write failed");
        require(written.output().getAsJsonArray("diffHunks").size() == 1, "write omitted a filesystem diff hunk");

        JsonObject stale = args("instance", path); stale.addProperty("find", "gamma"); stale.addProperty("replacement", "delta"); stale.addProperty("expectedHash", firstHash);
        ModelToolResult rejected = execute("workspace.replace", stale);
        require("stale".equals(rejected.status()) && rejected.retryable(), "stale mutation was not rejected structurally");

        String currentHash = written.output().get("resultingContentHash").getAsString();

        String directory = "proof/manage-" + UUID.randomUUID();
        ModelToolResult madeDirectory = execute("workspace.mkdir", args("instance", directory));
        require(madeDirectory.completedAndValidated()
                        && "directory".equals(madeDirectory.output().get("filesystemState").getAsString()),
                "workspace directory creation was not verified");
        JsonObject stat = args("instance", path);
        ModelToolResult inspected = execute("workspace.stat", stat);
        require(inspected.completedAndValidated()
                        && currentHash.equals(inspected.output().get("contentHash").getAsString()),
                "workspace stat did not return the current revision");
        String copiedPath = directory + "/copied.txt";
        JsonObject copy = args("instance", path);
        copy.addProperty("destinationWorkspace", "instance");
        copy.addProperty("destinationPath", copiedPath);
        copy.addProperty("expectedHash", currentHash);
        ModelToolResult copied = execute("workspace.copy", copy);
        require(copied.completedAndValidated()
                        && copied.output().get("sourceUnchanged").getAsBoolean()
                        && Files.isRegularFile(ModelWorkspaceRegistry.resolve("instance", copiedPath, false).path()),
                "revision-checked workspace copy was not verified");
        String movedPath = directory + "/renamed.txt";
        JsonObject move = args("instance", copiedPath);
        move.addProperty("destinationWorkspace", "instance");
        move.addProperty("destinationPath", movedPath);
        move.addProperty("expectedHash", copied.output().get("resultingContentHash").getAsString());
        ModelToolResult moved = execute("workspace.move", move);
        require(moved.completedAndValidated()
                        && moved.output().get("sourceRemoved").getAsBoolean()
                        && moved.changedTargets().size() == 2,
                "workspace move did not report and verify both filesystem targets");

        JsonObject remove = args("instance", path); remove.addProperty("expectedHash", currentHash);
        ModelToolResult deleted = execute("workspace.delete", remove);
        require(deleted.completedAndValidated() && deleted.output().get("recoverable").getAsBoolean(), "delete was not recoverable");
        JsonObject restore = args("instance", path); restore.addProperty("recoveryToken", deleted.output().get("recoveryToken").getAsString());
        ModelToolResult restored = execute("workspace.restore", restore);
        require(restored.completedAndValidated(), "recoverable restore failed");
        Files.deleteIfExists(ModelWorkspaceRegistry.resolve("instance", path, true).path());
        Files.deleteIfExists(ModelWorkspaceRegistry.resolve("instance", movedPath, true).path());
        Files.deleteIfExists(ModelWorkspaceRegistry.resolve("instance", directory, true).path());
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
