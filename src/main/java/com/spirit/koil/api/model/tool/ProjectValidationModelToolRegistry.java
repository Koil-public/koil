package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Bounded development operations; model input can select only registered IDs. */
public final class ProjectValidationModelToolRegistry {
    public static final String LIST_TOOL_ID = "development.tasks";
    public static final String RUN_TOOL_ID = "development.run";
    private static final int MAXIMUM_OUTPUT_BYTES = 96 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(15);
    private static final Map<String, List<String>> OPERATIONS = operations();
    private static final Map<UUID, Process> ACTIVE = new ConcurrentHashMap<>();
    private static final List<ModelToolDefinition> TOOLS = List.of(
            new ModelToolDefinition(
                    LIST_TOOL_ID,
                    "List bounded Koil compilation, test, and proof operations available in the project workspace.",
                    objectSchema(Map.of(), List.of()), List.of("project_workspace_available"), Set.of(),
                    false, Duration.ofSeconds(5), false, false, Set.of("completed", "unsupported")
            ),
            new ModelToolDefinition(
                    RUN_TOOL_ID,
                    "Run one registered Koil compile, verification, test, or proof operation. Arbitrary commands and task names are rejected.",
                    objectSchema(Map.of("operation", stringSchema()), List.of("operation")),
                    List.of("project_workspace_available"), Set.of("runs_bounded_project_validation"),
                    true, TIMEOUT, false, true, Set.of("completed", "failed", "timed_out", "cancelled", "unsupported")
            )
    );

    private ProjectValidationModelToolRegistry() {}

    public static String version() { return "project-validation-tools-v1"; }

    public static List<ModelToolDefinition> modelTools() { return TOOLS; }

    public static boolean supports(String id) { return LIST_TOOL_ID.equals(id) || RUN_TOOL_ID.equals(id); }

    public static CompletableFuture<ModelToolResult> execute(UUID requestId, ModelToolCall call, boolean preapproved) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown project validation tool."));
        }
        if (!AutomationModeController.isAutomationMode()) {
            return CompletableFuture.completedFuture(failure(call, "automation_disabled", "Automation Mode is required."));
        }
        Path project = projectRoot();
        if (project == null) {
            return CompletableFuture.completedFuture(status(call, "unsupported", "project_workspace_unavailable",
                    "No valid Koil development checkout is available from this instance.", new JsonObject(), false));
        }
        if (LIST_TOOL_ID.equals(call.toolId())) {
            JsonArray values = new JsonArray();
            OPERATIONS.keySet().forEach(values::add);
            JsonObject output = new JsonObject();
            output.add("operations", values);
            return CompletableFuture.completedFuture(status(call, "completed", "", "Registered validation operations were listed.", output, false));
        }
        String operation = string(call.arguments(), "operation");
        List<String> tasks = OPERATIONS.get(operation);
        if (tasks == null) {
            return CompletableFuture.completedFuture(status(call, "unsupported", "unregistered_operation",
                    "The requested project operation is not registered.", new JsonObject(), false));
        }
        CompletableFuture<Boolean> approval = preapproved || AutomationModeController.isUnrestrictedMode()
                ? CompletableFuture.completedFuture(true)
                : requestId == null
                ? CompletableFuture.completedFuture(false)
                : ModelGenerationHudState.requestApproval(
                        requestId, "Project validation", "Run registered operation: " + operation,
                        "Run Validation", "Deny"
                );
        return approval.thenCompose(approved -> approved
                ? CompletableFuture.supplyAsync(() -> run(requestId, call, operation, project, tasks))
                : CompletableFuture.completedFuture(status(call, "rejected", "user_declined",
                        "The player declined the project validation.", new JsonObject(), false)));
    }

    public static boolean cancel(UUID requestId) {
        Process process = requestId == null ? null : ACTIVE.remove(requestId);
        if (process == null) return false;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return true;
    }

    private static ModelToolResult run(UUID requestId, ModelToolCall call, String operation, Path project, List<String> tasks) {
        long started = System.currentTimeMillis();
        Process process = null;
        try {
            Path wrapper = project.resolve(isWindows() ? "gradlew.bat" : "gradlew");
            if (!Files.isRegularFile(wrapper)) {
                return status(call, "unsupported", "gradle_wrapper_unavailable", "The project Gradle wrapper is unavailable.", new JsonObject(), false);
            }
            List<String> command = new java.util.ArrayList<>();
            command.add(wrapper.toAbsolutePath().toString());
            command.addAll(tasks);
            command.add("--console=plain");
            process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
            if (requestId != null) ACTIVE.put(requestId, process);
            Process activeProcess = process;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> consume(activeProcess.getInputStream(), captured), "koil-project-validation-output");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(2_000L);
                return result(call, "timed_out", "validation_timed_out", operation, -1, captured, started, true);
            }
            reader.join(2_000L);
            int exit = process.exitValue();
            return result(call, exit == 0 ? "completed" : "failed",
                    exit == 0 ? "" : "validation_failed", operation, exit, captured, started, exit != 0);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return result(call, "cancelled", "validation_cancelled", operation, -1,
                    new ByteArrayOutputStream(), started, true);
        } catch (Exception failure) {
            return status(call, "failed", "validation_start_failed", message(failure), new JsonObject(), true);
        } finally {
            if (requestId != null) ACTIVE.remove(requestId, process);
        }
    }

    private static ModelToolResult result(ModelToolCall call, String status, String code, String operation,
                                          int exit, ByteArrayOutputStream outputBytes, long started, boolean retryable) {
        JsonObject output = new JsonObject();
        output.addProperty("operation", operation);
        output.addProperty("exitCode", exit);
        output.addProperty("output", outputBytes.toString(StandardCharsets.UTF_8));
        output.addProperty("outputTruncated", outputBytes.size() >= MAXIMUM_OUTPUT_BYTES);
        output.add("affectedFiles", new JsonArray());
        output.addProperty("validationStatus", "completed".equals(status) ? "passed" : "failed");
        return new ModelToolResult(call.id(), call.toolId(), status, output, code,
                "completed".equals(status) ? "Project validation passed." : "Project validation did not pass.",
                started, System.currentTimeMillis(), "completed".equals(status) ? "passed" : "failed",
                List.of(), retryable, "cancelled".equals(status), "approved");
    }

    private static void consume(InputStream input, ByteArrayOutputStream output) {
        try (input; output) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = MAXIMUM_OUTPUT_BYTES - output.size();
                if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
            }
        } catch (IOException ignored) {}
    }

    private static Path projectRoot() {
        ModelWorkspaceRegistry.Workspace workspace = ModelWorkspaceRegistry.workspaces().get("project");
        return workspace == null ? null : workspace.root();
    }

    private static Map<String, List<String>> operations() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("compile", List.of("compileJava"));
        values.put("verify_client_classes", List.of("verifyRunClientClasses"));
        values.put("local_model_foundation", List.of("runLocalModelFoundationProof"));
        values.put("model_presence_planning", List.of("runModelPresencePlanningProof"));
        values.put("rich_chat_formatting", List.of("runRichChatSectionFormattingProof"));
        values.put("automation_capability", List.of("runAutomationCapabilityProof"));
        values.put("colibri_provider", List.of("runColibriProviderProof"));
        values.put("llama_cpp_provider", List.of("runLlamaCppProviderProof"));
        values.put("ktl_library", List.of("runKtlLibraryProof"));
        values.put("development_command_bridge", List.of("runDevelopmentCommandBridgeProof"));
        values.put("all_model_proofs", List.of("verifyRunClientClasses", "runLocalModelFoundationProof",
                "runModelPresencePlanningProof", "runRichChatSectionFormattingProof", "runAutomationCapabilityProof",
                "runColibriProviderProof", "runLlamaCppProviderProof"));
        return Map.copyOf(values);
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return status(call, "failed", code, detail, new JsonObject(), true);
    }

    private static ModelToolResult status(ModelToolCall call, String status, String code, String detail,
                                          JsonObject output, boolean retryable) {
        return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                status, output, code, detail, System.currentTimeMillis(), System.currentTimeMillis(),
                "completed".equals(status) ? "passed" : "not_required", List.of(), retryable,
                "cancelled".equals(status), "not_required");
    }

    private static JsonObject objectSchema(Map<String, JsonObject> properties, List<String> required) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false);
        JsonObject props = new JsonObject(); properties.forEach(props::add); schema.add("properties", props);
        JsonArray req = new JsonArray(); required.forEach(req::add); schema.add("required", req); return schema;
    }

    private static JsonObject stringSchema() { JsonObject value = new JsonObject(); value.addProperty("type", "string"); value.addProperty("maxLength", 64); return value; }
    private static String string(JsonObject root, String key) { try { return root.get(key).getAsString().strip(); } catch (Exception ignored) { return ""; } }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
    private static String message(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
}
