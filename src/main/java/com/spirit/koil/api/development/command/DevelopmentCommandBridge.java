package com.spirit.koil.api.development.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.util.console.log.SubFileLogger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Development-only, file-queued Minecraft command connector.
 *
 * <p>The bridge has one capability: submit validated slash commands through
 * {@link ClientPlayNetworkHandler#sendChatCommand(String)} on the client thread.
 * It has no input simulation, arbitrary packet, server-internal, Java, shell, or
 * network-listener surface.</p>
 */
public final class DevelopmentCommandBridge {
    private static final String LOGGER_ID = "developmentCommandBridgeLogger";
    private static final String LOG_THREAD = "Development command bridge";
    private static final int STATUS_INTERVAL_TICKS = 100;
    private static final int FEEDBACK_WINDOW_TICKS = 20;
    private static final long REQUEST_TIMEOUT_SECONDS = 300L;
    private static final int MAX_SCRIPT_STEPS = 128;
    private static final int MAX_WAIT_TICKS = 12_000;
    private static final Set<String> BASIC_FIELDS = Set.of("id", "command", "submittedBy", "createdAt");
    private static final Set<String> SCRIPT_FIELDS = Set.of(
            "id", "name", "steps", "stopOnRejectedRequest", "submittedBy", "createdAt"
    );

    private static DevelopmentCommandFileStore store;
    private static SubFileLogger logger;
    private static boolean initialized;
    private static boolean developmentEnvironment;
    private static boolean enabledByConfig;
    private static boolean storageAvailable;
    private static boolean stopping;
    private static long tickCounter;
    private static long nextInboxPollTick;
    private static long nextStatusTick;
    private static int minimumDelayTicks = 2;
    private static String lastStatusSignature = "";
    private static ActiveCommand activeCommand;
    private static ScriptExecution activeScript;

    private DevelopmentCommandBridge() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        developmentEnvironment = FabricLoader.getInstance().isDevelopmentEnvironment();
        Path gameDirectory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        store = new DevelopmentCommandFileStore(
                gameDirectory.resolve("koil/sys/development/command-bridge")
        );
        SubFileLogger.initialize(
                LOGGER_ID,
                gameDirectory.resolve("koil/logs/development-command-bridge").toString(),
                "development-command-bridge"
        );
        logger = SubFileLogger.getInstance(LOGGER_ID);
        refreshConfig();
        try {
            store.initialize();
            storageAvailable = true;
            recoverStaleProcessingFiles();
            logInfo("Bridge initialized at " + store.root()
                    + "; developmentEnvironment=" + developmentEnvironment
                    + ", enabled=" + enabledByConfig + ".");
        } catch (Exception exception) {
            storageAvailable = false;
            logError("Bridge storage is unavailable: " + safeMessage(exception));
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(DevelopmentCommandBridge::stop);
        writeStatus(MinecraftClient.getInstance(), true);
    }

    /** Runs from Koil's END_CLIENT_TICK callback and never from a watcher thread. */
    public static void tick(MinecraftClient client) {
        if (!initialized || stopping || client == null) {
            return;
        }
        tickCounter++;
        if (tickCounter >= nextStatusTick) {
            refreshConfig();
            nextStatusTick = tickCounter + STATUS_INTERVAL_TICKS;
            writeStatus(client, false);
        }
        if (!bridgeActive()) {
            if (activeCommand != null || activeScript != null) {
                abortActiveWork("Bridge was disabled while work was active.");
            }
            return;
        }
        if (!client.isOnThread()) {
            logError("Ignored a bridge tick outside Minecraft's client thread.");
            return;
        }
        if (activeCommand != null) {
            if (tickCounter >= activeCommand.feedbackDeadlineTick()) {
                finishActiveCommand(null);
            }
            return;
        }
        if (tickCounter < nextInboxPollTick) {
            return;
        }
        if (activeScript != null) {
            advanceScript(client);
            return;
        }
        if (claimOne(client, false)) {
            return;
        }
        claimOne(client, true);
    }

    private static boolean claimOne(MinecraftClient client, boolean scriptRequest) {
        try {
            List<Path> candidates = store.requestCandidates(scriptRequest);
            if (candidates.isEmpty()) {
                return false;
            }
            Path processingFile = store.claim(candidates.get(0));
            if (scriptRequest) {
                processScriptRequest(client, processingFile);
            } else {
                processCommandRequest(client, processingFile);
            }
            return true;
        } catch (Exception exception) {
            logError("Failed to claim a " + (scriptRequest ? "script" : "command")
                    + " request: " + safeMessage(exception));
            nextInboxPollTick = tickCounter + minimumDelayTicks;
            return false;
        }
    }

    private static void processCommandRequest(MinecraftClient client, Path processingFile) {
        String fallbackId = requestIdFromProcessingFile(processingFile, "command-");
        try {
            JsonObject request = store.readObject(processingFile);
            requireOnlyFields(request, BASIC_FIELDS);
            String id = requiredString(request, "id");
            validateIdentity(processingFile, "command-", id);
            validateSubmitter(request);
            String ageStatus = requestAgeStatus(request);
            if (store.resultExists(id)) {
                writeDuplicate(processingFile, id, stringValue(request, "command"), "Duplicate request identifier.");
                return;
            }
            if (ageStatus != null) {
                writeTerminalResult(processingFile, id, stringValue(request, "command"), ageStatus,
                        "Request expired before it could be processed.", null, List.of());
                return;
            }
            DevelopmentCommandValidation.Result validation = DevelopmentCommandValidation.validate(
                    requiredString(request, "command"),
                    root -> knownCommandRoot(client, root)
            );
            if (!validation.accepted()) {
                reject(processingFile, id, stringValue(request, "command"), validation.error());
                return;
            }
            logInfo("Accepted request " + id + "; normalized command=" + validation.normalizedCommand());
            if (!ready(client)) {
                writeTerminalResult(processingFile, id, stringValue(request, "command"), "not_ready",
                        readinessReason(client), validation.normalizedCommand(), List.of());
                return;
            }
            submit(client, processingFile, id, stringValue(request, "command"),
                    validation.normalizedCommand(), null, -1);
        } catch (Exception exception) {
            reject(processingFile, safeResultId(fallbackId), "", safeMessage(exception));
        }
    }

    private static void processScriptRequest(MinecraftClient client, Path processingFile) {
        String fallbackId = requestIdFromProcessingFile(processingFile, "script-");
        try {
            JsonObject request = store.readObject(processingFile);
            requireOnlyFields(request, SCRIPT_FIELDS);
            String id = requiredString(request, "id");
            validateIdentity(processingFile, "script-", id);
            validateSubmitter(request);
            if (store.resultExists(id)) {
                writeDuplicate(processingFile, id, "", "Duplicate request identifier.");
                return;
            }
            String ageStatus = requestAgeStatus(request);
            if (ageStatus != null) {
                writeTerminalResult(processingFile, id, "", ageStatus,
                        "Script expired before it could be processed.", null, List.of());
                return;
            }
            if (!request.has("steps") || !request.get("steps").isJsonArray()) {
                throw new IllegalArgumentException("Script steps must be a JSON array.");
            }
            JsonArray rawSteps = request.getAsJsonArray("steps");
            if (rawSteps.size() == 0 || rawSteps.size() > MAX_SCRIPT_STEPS) {
                throw new IllegalArgumentException("Scripts require 1-" + MAX_SCRIPT_STEPS + " steps.");
            }
            List<ScriptStep> steps = new ArrayList<>();
            for (int index = 0; index < rawSteps.size(); index++) {
                steps.add(parseScriptStep(client, rawSteps.get(index), index));
            }
            if (!ready(client)) {
                writeScriptResult(processingFile, id, stringValue(request, "name"), "not_ready",
                        readinessReason(client), new JsonArray());
                return;
            }
            activeScript = new ScriptExecution(
                    id,
                    stringValue(request, "name"),
                    processingFile,
                    List.copyOf(steps),
                    request.has("stopOnRejectedRequest") && request.get("stopOnRejectedRequest").getAsBoolean(),
                    new JsonArray()
            );
            logInfo("Accepted script " + id + " with " + steps.size() + " steps.");
            advanceScript(client);
        } catch (Exception exception) {
            reject(processingFile, safeResultId(fallbackId), "", safeMessage(exception));
        }
    }

    private static ScriptStep parseScriptStep(MinecraftClient client, JsonElement element, int index) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Script step " + index + " must be an object.");
        }
        JsonObject step = element.getAsJsonObject();
        String type = requiredString(step, "type");
        if ("command".equals(type)) {
            requireOnlyFields(step, Set.of("type", "command"));
            String raw = requiredString(step, "command");
            DevelopmentCommandValidation.Result validation = DevelopmentCommandValidation.validate(
                    raw,
                    root -> knownCommandRoot(client, root)
            );
            if (!validation.accepted()) {
                throw new IllegalArgumentException("Script step " + index + ": " + validation.error());
            }
            return ScriptStep.command(raw, validation.normalizedCommand());
        }
        if ("wait_ticks".equals(type)) {
            requireOnlyFields(step, Set.of("type", "ticks"));
            if (!step.has("ticks") || !step.get("ticks").isJsonPrimitive()) {
                throw new IllegalArgumentException("Script step " + index + " requires integer ticks.");
            }
            int ticks = step.get("ticks").getAsInt();
            if (ticks < 1 || ticks > MAX_WAIT_TICKS) {
                throw new IllegalArgumentException("Script waits must be between 1 and " + MAX_WAIT_TICKS + " ticks.");
            }
            return ScriptStep.waitTicks(ticks);
        }
        throw new IllegalArgumentException("Script step " + index + " has unsupported type '" + type
                + "'. Only command and wait_ticks are allowed.");
    }

    private static void advanceScript(MinecraftClient client) {
        if (activeScript == null || activeCommand != null) {
            return;
        }
        if (!ready(client)) {
            failActiveScript("not_ready", readinessReason(client));
            return;
        }
        if (activeScript.waitRemaining > 0) {
            activeScript.waitRemaining--;
            if (activeScript.waitRemaining == 0) {
                JsonObject result = new JsonObject();
                result.addProperty("index", activeScript.stepIndex);
                result.addProperty("type", "wait_ticks");
                result.addProperty("ticks", activeScript.steps.get(activeScript.stepIndex).ticks());
                result.addProperty("status", "completed");
                activeScript.stepResults.add(result);
                activeScript.stepIndex++;
                logInfo("Script " + activeScript.id + " completed wait step " + (activeScript.stepIndex - 1) + ".");
                nextInboxPollTick = tickCounter + minimumDelayTicks;
            }
            return;
        }
        if (activeScript.stepIndex >= activeScript.steps.size()) {
            ScriptExecution completed = activeScript;
            activeScript = null;
            writeScriptResult(completed.processingFile, completed.id, completed.name,
                    "sent", null, completed.stepResults);
            return;
        }
        ScriptStep step = activeScript.steps.get(activeScript.stepIndex);
        if ("wait_ticks".equals(step.type())) {
            activeScript.waitRemaining = step.ticks();
            logInfo("Script " + activeScript.id + " started wait step " + activeScript.stepIndex
                    + " for " + step.ticks() + " ticks.");
            return;
        }
        logInfo("Script " + activeScript.id + " submitting command step " + activeScript.stepIndex
                + "; normalized command=" + step.normalizedCommand());
        submit(client, activeScript.processingFile, activeScript.id + "-step-" + activeScript.stepIndex,
                step.rawCommand(), step.normalizedCommand(), activeScript, activeScript.stepIndex);
    }

    private static void submit(
            MinecraftClient client,
            Path processingFile,
            String requestId,
            String rawCommand,
            String normalizedCommand,
            ScriptExecution script,
            int scriptStepIndex
    ) {
        try {
            ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
            if (networkHandler == null) {
                throw new IllegalStateException("Client connection became unavailable.");
            }
            String sentAt = Instant.now().toString();
            DevelopmentCommandFeedbackCollector.begin(requestId);
            networkHandler.sendChatCommand(normalizedCommand);
            activeCommand = new ActiveCommand(
                    processingFile,
                    requestId,
                    rawCommand,
                    normalizedCommand,
                    sentAt,
                    tickCounter + FEEDBACK_WINDOW_TICKS,
                    script,
                    scriptStepIndex
            );
            logInfo("Submitted " + requestId + " through Minecraft's command path at " + sentAt + ".");
        } catch (Exception exception) {
            DevelopmentCommandFeedbackCollector.clear();
            if (script == null) {
                writeTerminalResult(processingFile, requestId, rawCommand, "failed",
                        safeMessage(exception), normalizedCommand, List.of());
            } else {
                appendScriptCommandResult(script, scriptStepIndex, rawCommand, normalizedCommand,
                        "failed", null, safeMessage(exception), List.of());
                failActiveScript("failed", "Command submission failed at step " + scriptStepIndex
                        + ": " + safeMessage(exception));
            }
        }
    }

    private static void finishActiveCommand(String warning) {
        ActiveCommand completed = activeCommand;
        if (completed == null) {
            return;
        }
        activeCommand = null;
        List<DevelopmentCommandFeedbackCollector.Feedback> feedback =
                DevelopmentCommandFeedbackCollector.finish(completed.requestId());
        if (completed.script() == null) {
            writeTerminalResult(completed.processingFile(), completed.requestId(), completed.rawCommand(),
                    "sent", warning, completed.normalizedCommand(), feedback, completed.sentAt());
        } else {
            appendScriptCommandResult(completed.script(), completed.scriptStepIndex(),
                    completed.rawCommand(), completed.normalizedCommand(), "sent",
                    completed.sentAt(), warning, feedback);
            completed.script().stepIndex++;
            logInfo("Script " + completed.script().id + " completed command step "
                    + completed.scriptStepIndex() + ".");
        }
        nextInboxPollTick = tickCounter + minimumDelayTicks;
    }

    private static void appendScriptCommandResult(
            ScriptExecution script,
            int index,
            String rawCommand,
            String normalizedCommand,
            String status,
            String sentAt,
            String error,
            List<DevelopmentCommandFeedbackCollector.Feedback> feedback
    ) {
        JsonObject result = new JsonObject();
        result.addProperty("index", index);
        result.addProperty("type", "command");
        result.addProperty("command", rawCommand);
        result.addProperty("normalizedCommand", normalizedCommand);
        result.addProperty("status", status);
        if (sentAt == null) {
            result.add("sentAt", null);
        } else {
            result.addProperty("sentAt", sentAt);
        }
        result.add("feedback", feedbackJson(feedback));
        if (error == null) {
            result.add("error", null);
        } else {
            result.addProperty("error", error);
        }
        script.stepResults.add(result);
    }

    private static void reject(Path processingFile, String id, String command, String reason) {
        logWarning("Rejected request " + id + ": " + reason);
        if (store.resultExists(id)) {
            writeDuplicate(processingFile, id, command, reason);
            return;
        }
        writeTerminalResult(processingFile, id, command, "rejected", reason, null, List.of());
    }

    private static void writeDuplicate(Path processingFile, String id, String command, String reason) {
        JsonObject result = resultBase(id, command, "rejected");
        result.addProperty("error", reason);
        result.add("feedback", new JsonArray());
        try {
            store.writeDuplicateResult(id, result);
            store.complete(processingFile);
            logWarning("Rejected duplicate request " + id + ".");
        } catch (Exception exception) {
            logError("Failed to preserve duplicate result " + id + ": " + safeMessage(exception));
        }
    }

    private static void writeTerminalResult(
            Path processingFile,
            String id,
            String command,
            String status,
            String error,
            String normalizedCommand,
            List<DevelopmentCommandFeedbackCollector.Feedback> feedback
    ) {
        writeTerminalResult(processingFile, id, command, status, error, normalizedCommand, feedback, null);
    }

    private static void writeTerminalResult(
            Path processingFile,
            String id,
            String command,
            String status,
            String error,
            String normalizedCommand,
            List<DevelopmentCommandFeedbackCollector.Feedback> feedback,
            String sentAt
    ) {
        JsonObject result = resultBase(id, command, status);
        if (normalizedCommand != null) {
            result.addProperty("normalizedCommand", normalizedCommand);
        }
        if (sentAt == null) {
            result.add("sentAt", null);
        } else {
            result.addProperty("sentAt", sentAt);
        }
        result.add("feedback", feedbackJson(feedback));
        if (error == null) {
            result.add("error", null);
        } else {
            result.addProperty("error", error);
        }
        try {
            store.writeResult(id, result);
            store.complete(processingFile);
            logInfo("Result " + id + " finished with status=" + status + ".");
        } catch (Exception exception) {
            logError("Failed to write result " + id + "; processing request was preserved: "
                    + safeMessage(exception));
        }
    }

    private static void writeScriptResult(
            Path processingFile,
            String id,
            String name,
            String status,
            String error,
            JsonArray stepResults
    ) {
        JsonObject result = resultBase(id, "", status);
        result.remove("command");
        result.addProperty("name", name == null ? "" : name);
        result.add("steps", stepResults);
        if (error == null) {
            result.add("error", null);
        } else {
            result.addProperty("error", error);
        }
        try {
            store.writeResult(id, result);
            store.complete(processingFile);
            logInfo("Script result " + id + " finished with status=" + status + ".");
        } catch (Exception exception) {
            logError("Failed to write script result " + id + "; processing request was preserved: "
                    + safeMessage(exception));
        }
    }

    private static JsonObject resultBase(String id, String command, String status) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("command", command == null ? "" : command);
        result.addProperty("status", status);
        result.addProperty("completedAt", Instant.now().toString());
        return result;
    }

    private static JsonArray feedbackJson(List<DevelopmentCommandFeedbackCollector.Feedback> feedback) {
        JsonArray values = new JsonArray();
        for (DevelopmentCommandFeedbackCollector.Feedback row : feedback) {
            JsonObject value = new JsonObject();
            value.addProperty("type", row.type());
            value.addProperty("text", row.text());
            value.addProperty("source", row.source());
            value.addProperty("observedAt", row.observedAt());
            value.addProperty("correlation", "supporting_evidence_only");
            values.add(value);
        }
        return values;
    }

    private static void failActiveScript(String status, String reason) {
        ScriptExecution failed = activeScript;
        activeScript = null;
        if (failed != null) {
            writeScriptResult(failed.processingFile, failed.id, failed.name, status, reason, failed.stepResults);
        }
    }

    private static void abortActiveWork(String reason) {
        if (activeCommand != null) {
            finishActiveCommand(reason);
        }
        if (activeScript != null) {
            failActiveScript("failed", reason);
        }
    }

    private static void stop(MinecraftClient client) {
        if (!initialized || stopping) {
            return;
        }
        stopping = true;
        abortActiveWork("Client stopped before bridge work completed.");
        writeStatus(client, true);
        DevelopmentCommandFeedbackCollector.clear();
        logInfo("Bridge stopped.");
    }

    private static void recoverStaleProcessingFiles() {
        try {
            for (Path stale : store.staleProcessingFiles()) {
                String fileName = stale.getFileName().toString();
                String prefix = fileName.startsWith("script-") ? "script-" : "command-";
                String fallbackId = safeResultId(requestIdFromProcessingFile(stale, prefix));
                try {
                    JsonObject request = store.readObject(stale);
                    String id = stringValue(request, "id");
                    if (DevelopmentCommandFileStore.SAFE_ID.matcher(id).matches() && !store.resultExists(id)) {
                        writeTerminalResult(stale, id, stringValue(request, "command"), "timed_out",
                                "Request was left in processing by a previous client run and was not executed again.",
                                null, List.of());
                    } else {
                        reject(stale, fallbackId, "", "Stale or duplicate processing request.");
                    }
                } catch (Exception exception) {
                    reject(stale, fallbackId, "", "Malformed stale processing request: " + safeMessage(exception));
                }
            }
        } catch (Exception exception) {
            logError("Failed to inspect stale processing requests: " + safeMessage(exception));
        }
    }

    private static boolean knownCommandRoot(MinecraftClient client, String root) {
        ClientPlayNetworkHandler networkHandler = client == null ? null : client.getNetworkHandler();
        return networkHandler != null
                && networkHandler.getCommandDispatcher() != null
                && networkHandler.getCommandDispatcher().getRoot().getChild(root) != null;
    }

    private static boolean ready(MinecraftClient client) {
        return bridgeActive()
                && !stopping
                && client != null
                && client.world != null
                && client.player != null
                && client.getNetworkHandler() != null;
    }

    private static boolean bridgeActive() {
        return developmentEnvironment && enabledByConfig && storageAvailable && !stopping;
    }

    private static String readinessReason(MinecraftClient client) {
        if (!developmentEnvironment) return "Bridge is restricted to Fabric development environments.";
        if (!enabledByConfig) return "Bridge is disabled by config.";
        if (!storageAvailable) return "Bridge storage is unavailable.";
        if (stopping) return "Client is stopping.";
        if (client == null) return "Minecraft client is unavailable.";
        if (client.world == null) return "No world is loaded.";
        if (client.player == null) return "Client player is unavailable.";
        if (client.getNetworkHandler() == null) return "Client connection is unavailable.";
        return "Bridge is ready.";
    }

    private static void writeStatus(MinecraftClient client, boolean force) {
        if (store == null || !storageAvailable) {
            return;
        }
        boolean worldLoaded = client != null && client.world != null;
        boolean connectionAvailable = client != null && client.getNetworkHandler() != null;
        boolean playerAvailable = client != null && client.player != null;
        boolean ready = ready(client);
        String serverType = !connectionAvailable ? "none"
                : client.isInSingleplayer() ? "integrated" : "remote";
        String signature = bridgeActive() + "|" + !stopping + "|" + worldLoaded + "|"
                + connectionAvailable + "|" + playerAvailable + "|" + ready + "|" + serverType
                + "|" + minimumDelayTicks + "|" + readinessReason(client);
        if (!force && signature.equals(lastStatusSignature)) {
            return;
        }
        JsonObject status = new JsonObject();
        status.addProperty("bridgeActive", bridgeActive());
        status.addProperty("clientRunning", !stopping);
        status.addProperty("worldLoaded", worldLoaded);
        status.addProperty("connectionAvailable", connectionAvailable);
        status.addProperty("playerAvailable", playerAvailable);
        status.addProperty("ready", ready);
        status.addProperty("serverType", serverType);
        status.addProperty("minimumCommandDelayTicks", minimumDelayTicks);
        status.addProperty("reason", readinessReason(client));
        status.addProperty("updatedAt", Instant.now().toString());
        try {
            store.writeStatus(status);
            if (!signature.equals(lastStatusSignature)) {
                logInfo("Readiness changed: ready=" + ready + ", reason=" + readinessReason(client));
            }
            lastStatusSignature = signature;
        } catch (Exception exception) {
            storageAvailable = false;
            logError("Failed to update status.json: " + safeMessage(exception));
        }
    }

    private static void refreshConfig() {
        enabledByConfig = readConfigBoolean("developmentCommandBridgeEnabled", true);
        minimumDelayTicks = Math.max(1, Math.min(200,
                readConfigInteger("developmentCommandBridgeMinimumDelayTicks", 2)));
    }

    private static boolean readConfigBoolean(String key, boolean fallback) {
        JsonElement value = readConfigValue(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int readConfigInteger(String key, int fallback) {
        JsonElement value = readConfigValue(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static JsonElement readConfigValue(String key) {
        Path config = FabricLoader.getInstance().getGameDir().resolve("koil/sys/config.json");
        if (!Files.isRegularFile(config)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root.isJsonObject() ? root.getAsJsonObject().get(key) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String requestAgeStatus(JsonObject request) {
        if (!request.has("createdAt")) {
            throw new IllegalArgumentException("Missing required string field 'createdAt'.");
        }
        try {
            Instant createdAt = Instant.parse(requiredString(request, "createdAt"));
            long age = Duration.between(createdAt, Instant.now()).getSeconds();
            if (age > REQUEST_TIMEOUT_SECONDS) {
                return "timed_out";
            }
            if (age < -REQUEST_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("createdAt is too far in the future.");
            }
            return null;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("createdAt must be an ISO-8601 timestamp.");
        }
    }

    private static void validateSubmitter(JsonObject request) {
        if (!"codex".equals(requiredString(request, "submittedBy"))) {
            throw new IllegalArgumentException("submittedBy must be 'codex'.");
        }
    }

    private static void validateIdentity(Path processingFile, String prefix, String id) {
        if (!DevelopmentCommandFileStore.SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Request id contains unsafe characters.");
        }
        String expected = id + ".json";
        String actual = processingFile.getFileName().toString();
        if (actual.startsWith(prefix)) {
            actual = actual.substring(prefix.length());
        }
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Request filename must match its id.");
        }
    }

    private static void requireOnlyFields(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unsupported field '" + key
                        + "'; alternate execution mechanisms are not accepted.");
            }
        }
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing required string field '" + key + "'.");
        }
        String value = object.get(key).getAsString();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Field '" + key + "' must not be blank.");
        }
        return value;
    }

    private static String stringValue(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String requestIdFromProcessingFile(Path file, String prefix) {
        String name = file.getFileName().toString();
        if (name.startsWith(prefix)) {
            name = name.substring(prefix.length());
        }
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        return name;
    }

    private static String safeResultId(String candidate) {
        if (candidate != null && DevelopmentCommandFileStore.SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return "rejected-" + System.currentTimeMillis();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "Unknown bridge error." : throwable.getClass().getSimpleName();
        }
        return throwable.getMessage();
    }

    private static void logInfo(String message) {
        if (logger != null) logger.logI(LOG_THREAD, message);
    }

    private static void logWarning(String message) {
        if (logger != null) logger.logW(LOG_THREAD, message);
    }

    private static void logError(String message) {
        if (logger != null) logger.logE(LOG_THREAD, message);
    }

    private record ActiveCommand(
            Path processingFile,
            String requestId,
            String rawCommand,
            String normalizedCommand,
            String sentAt,
            long feedbackDeadlineTick,
            ScriptExecution script,
            int scriptStepIndex
    ) {
    }

    private record ScriptStep(
            String type,
            String rawCommand,
            String normalizedCommand,
            int ticks
    ) {
        private static ScriptStep command(String rawCommand, String normalizedCommand) {
            return new ScriptStep("command", rawCommand, normalizedCommand, 0);
        }

        private static ScriptStep waitTicks(int ticks) {
            return new ScriptStep("wait_ticks", "", "", ticks);
        }
    }

    private static final class ScriptExecution {
        private final String id;
        private final String name;
        private final Path processingFile;
        private final List<ScriptStep> steps;
        @SuppressWarnings("unused")
        private final boolean stopOnRejectedRequest;
        private final JsonArray stepResults;
        private int stepIndex;
        private int waitRemaining;

        private ScriptExecution(
                String id,
                String name,
                Path processingFile,
                List<ScriptStep> steps,
                boolean stopOnRejectedRequest,
                JsonArray stepResults
        ) {
            this.id = id;
            this.name = name;
            this.processingFile = processingFile;
            this.steps = steps;
            this.stopOnRejectedRequest = stopOnRejectedRequest;
            this.stepResults = stepResults;
        }
    }
}
