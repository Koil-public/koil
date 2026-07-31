package com.spirit.koil.api.automation.capability;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResult;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResults;
import com.spirit.koil.api.automation.runtime.AutomationPositionSnapshot;
import com.spirit.koil.api.automation.ktl.AutomationKtlSkillRegistry;
import com.spirit.koil.api.command.MinecraftCommandFeedbackTracker;
import com.spirit.koil.api.command.MinecraftCommandInspector;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.tool.MinecraftCommandModelToolRegistry;
import com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry;
import com.spirit.koil.api.model.tool.ModelWorkspaceToolRegistry;
import com.spirit.koil.api.model.tool.AutomationPlanModelToolRegistry;
import com.spirit.koil.api.model.tool.AutomationKtlSkillModelToolRegistry;
import net.minecraft.client.MinecraftClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Validates model calls against the shared capability registry and hands the
 * resulting typed plan to the existing automation planner on the client thread.
 */
public final class AutomationToolCoordinator {
    private AutomationToolCoordinator() {
    }

    public static CompletableFuture<ModelToolResult> execute(UUID displayRequestId, ModelToolCall call) {
        return execute(displayRequestId, call, false);
    }

    public static CompletableFuture<ModelToolResult> execute(
            UUID displayRequestId,
            ModelToolCall call,
            boolean preapproved
    ) {
        if (!AutomationModeController.isAutomationMode()) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "automation_disabled",
                    "Automation Mode must be enabled before the model can use automation capabilities."
            ));
        }
        if (call == null || call.toolId().isBlank()) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Tool name is missing."));
        }
        if (MinecraftCommandModelToolRegistry.supports(call.toolId())) {
            return MinecraftCommandModelToolRegistry.execute(call);
        }
        if (MinecraftKnowledgeModelToolRegistry.supports(call.toolId())) {
            return MinecraftKnowledgeModelToolRegistry.execute(call);
        }
        if (AutomationPlanModelToolRegistry.supports(call.toolId())) {
            return AutomationPlanModelToolRegistry.execute(call);
        }
        if (AutomationKtlSkillModelToolRegistry.supportsCatalog(call.toolId())) {
            return AutomationKtlSkillModelToolRegistry.executeCatalog(call);
        }
        if (AutomationKtlSkillModelToolRegistry.supportsRun(call.toolId())) {
            return executeKtlSkill(displayRequestId, call, preapproved);
        }
        if (ModelWorkspaceToolRegistry.supports(call.toolId())) {
            return ModelWorkspaceToolRegistry.execute(displayRequestId, call, preapproved);
        }
        AutomationCapabilityDefinition definition = AutomationCapabilityRegistry.definitions().get(call.toolId());
        if (definition == null) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown automation capability: " + call.toolId()));
        }
        UUID executionId = UUID.randomUUID();
        AutomationCapabilityPlan plan;
        try {
            plan = AutomationCapabilityRegistry.validateAndCompile(call.toolId(), call.arguments(), executionId);
        } catch (AutomationCapabilityException exception) {
            return CompletableFuture.completedFuture(failure(call, exception.code(), exception.getMessage()));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(call, "invalid_arguments", message(exception)));
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null || client.getNetworkHandler() == null) {
            return CompletableFuture.completedFuture(failure(call, "world_unavailable", "A loaded world and player connection are required."));
        }
        if (plan.action() == AutomationCapabilityPlan.Action.SUBMIT_COMMAND) {
            return submitCommand(client, definition, plan, call, displayRequestId, preapproved);
        }
        if (plan.action() == AutomationCapabilityPlan.Action.CANCEL_CURRENT) {
            if (!AutomationRouter.isTaskRunning()) {
                return CompletableFuture.completedFuture(new ModelToolResult(
                        call.id(), call.toolId(), "not_running", new JsonObject(), "", "No automation task is running."
                ));
            }
            client.execute(() -> AutomationRouter.cancelCurrentTask("cancelled by model tool"));
            return CompletableFuture.completedFuture(new ModelToolResult(
                    call.id(), call.toolId(), "completed", new JsonObject(), "", "The current automation task was cancelled."
            ));
        }
        if (AutomationRouter.isTaskRunning()) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "automation_conflict",
                    "Another automation task currently owns movement or input."
            ));
        }

        CompletableFuture<AutomationExecutionResult> execution = AutomationExecutionResults.register(executionId);
        client.execute(() -> {
            try {
                AutomationRouter.handleInput(plan.request(), "local-model");
            } catch (RuntimeException exception) {
                AutomationExecutionResults.publish(new AutomationExecutionResult(
                        executionId,
                        "failed",
                        "submission_failed",
                        message(exception),
                        "",
                        Map.of(),
                        null,
                        null,
                        null,
                        null
                ));
            }
        });

        Duration timeout = definition.timeout();
        return execution
                .orTimeout(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS)
                .handle((result, error) -> {
                    if (error != null) {
                        client.execute(() -> AutomationRouter.cancelCurrentTask("model tool timed out"));
                        return failure(call, "tool_timed_out", "Automation tool timed out after " + timeout.toSeconds() + " seconds.");
                    }
                    return toToolResult(call, result);
                });
    }

    private static CompletableFuture<ModelToolResult> executeKtlSkill(
            UUID displayRequestId,
            ModelToolCall call,
            boolean preapproved
    ) {
        JsonObject arguments = call.arguments();
        JsonObject parameters = arguments.has("parameters") && arguments.get("parameters").isJsonObject()
                ? arguments.getAsJsonObject("parameters")
                : new JsonObject();
        AutomationKtlSkillRegistry.PreparedSkill prepared;
        try {
            prepared = AutomationKtlSkillRegistry.prepare(
                    arguments.has("skill") ? arguments.get("skill").getAsString() : "",
                    parameters,
                    UUID.randomUUID()
            );
        } catch (AutomationCapabilityException exception) {
            return CompletableFuture.completedFuture(failure(call, exception.code(), exception.getMessage()));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(call, "invalid_ktl_skill", message(exception)));
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null || client.getNetworkHandler() == null) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "world_unavailable",
                    "A loaded world and player connection are required to run a KTL skill."
            ));
        }
        if (AutomationRouter.isTaskRunning()) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "automation_conflict",
                    "Another automation task currently owns movement or input."
            ));
        }
        if (preapproved || AutomationModeController.isYoloMode()) {
            return submitKtlSkill(client, call, prepared);
        }
        if (displayRequestId == null) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "approval_unavailable",
                    "The KTL skill request has no chat-panel approval surface."
            ));
        }
        String detail = "The model requested registered KTL skill "
                + prepared.descriptor().id()
                + (prepared.parameters().isEmpty() ? "" : "\nParameters: " + prepared.parameters())
                + "\n\nThe skill may compose other KTL tasks and control the player or game UI.";
        ModelGenerationHudState.state(
                displayRequestId,
                com.spirit.koil.api.model.ModelRequestState.EXECUTING_TOOL,
                "waiting for player approval"
        );
        return ModelGenerationHudState.requestApproval(
                        displayRequestId,
                        "KTL skill approval",
                        detail,
                        "Run Skill",
                        "Deny"
                )
                .thenCompose(approved -> approved
                        ? submitKtlSkill(client, call, prepared)
                        : CompletableFuture.completedFuture(new ModelToolResult(
                        call.id(),
                        call.toolId(),
                        "rejected",
                        new JsonObject(),
                        "user_declined",
                        "The player declined the registered KTL skill."
                )));
    }

    private static CompletableFuture<ModelToolResult> submitKtlSkill(
            MinecraftClient client,
            ModelToolCall call,
            AutomationKtlSkillRegistry.PreparedSkill prepared
    ) {
        UUID executionId = prepared.request().executionId();
        CompletableFuture<AutomationExecutionResult> execution =
                AutomationExecutionResults.register(executionId);
        client.execute(() -> {
            if (!AutomationModeController.isAutomationMode()) {
                AutomationExecutionResults.publish(new AutomationExecutionResult(
                        executionId,
                        "failed",
                        "automation_disabled",
                        "Automation Mode was disabled before the KTL skill started.",
                        prepared.descriptor().id(),
                        Map.of(),
                        null,
                        null,
                        null,
                        null
                ));
                return;
            }
            try {
                AutomationRouter.handleInput(prepared.request(), "local-model-skill");
            } catch (RuntimeException exception) {
                AutomationExecutionResults.publish(new AutomationExecutionResult(
                        executionId,
                        "failed",
                        "submission_failed",
                        message(exception),
                        prepared.descriptor().id(),
                        Map.of(),
                        null,
                        null,
                        null,
                        null
                ));
            }
        });
        Duration timeout = Duration.ofMinutes(10);
        return execution
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((result, error) -> {
                    if (error != null) {
                        client.execute(() -> AutomationRouter.cancelCurrentTask("model KTL skill timed out"));
                        return failure(
                                call,
                                "tool_timed_out",
                                "Registered KTL skill timed out after " + timeout.toMinutes() + " minutes."
                        );
                    }
                    return toToolResult(call, result);
                });
    }

    private static CompletableFuture<ModelToolResult> submitCommand(
            MinecraftClient client,
            AutomationCapabilityDefinition definition,
            AutomationCapabilityPlan plan,
            ModelToolCall call,
            UUID displayRequestId,
            boolean preapproved
    ) {
        String raw = plan.request() == null ? "" : plan.request().rawInput().strip();
        String command = raw.startsWith("/") ? raw.substring(1).stripLeading() : raw;
        if (command.isBlank()) {
            return CompletableFuture.completedFuture(failure(call, "invalid_command", "The model produced an empty Minecraft command."));
        }
        return MinecraftCommandInspector.inspect(command).thenCompose(inspection -> {
            if (!inspection.executable()) {
                JsonObject output = commandInspectionOutput(inspection);
                return CompletableFuture.completedFuture(new ModelToolResult(
                        call.id(),
                        call.toolId(),
                        "failed",
                        output,
                        "invalid_command_syntax",
                        "The command was not sent. Repair it using the active command-tree problem and suggestions."
                ));
            }
            if (preapproved || !definition.confirmationRequired() || AutomationModeController.isYoloMode()) {
                return submitCurrentPlayerCommand(client, call, command);
            }
            if (displayRequestId == null) {
                return CompletableFuture.completedFuture(failure(
                        call,
                        "approval_unavailable",
                        "The model request has no chat-panel approval surface."
                ));
            }
            String detail = "The model requested /" + command
                    + "\n\nIt will use your current player permissions and may change the player, inventory, world, or server.";
            ModelGenerationHudState.state(
                    displayRequestId,
                    com.spirit.koil.api.model.ModelRequestState.EXECUTING_TOOL,
                    "waiting for player approval"
            );
            return ModelGenerationHudState.requestApproval(
                            displayRequestId,
                            "Model command approval",
                            detail,
                            "Run Command",
                            "Deny"
                    )
                    .thenCompose(approved -> {
                        if (!approved) {
                            return CompletableFuture.completedFuture(new ModelToolResult(
                                    call.id(),
                                    call.toolId(),
                                    "rejected",
                                    new JsonObject(),
                                    "user_declined",
                                    "The player declined the Minecraft command."
                            ));
                        }
                        return submitCurrentPlayerCommand(client, call, command);
                    });
        });
    }

    private static CompletableFuture<ModelToolResult> submitCurrentPlayerCommand(
            MinecraftClient client,
            ModelToolCall call,
            String command
    ) {
        CompletableFuture<ModelToolResult> result = new CompletableFuture<>();
        client.execute(() -> {
            if (!AutomationModeController.isAutomationMode()) {
                result.complete(failure(
                        call,
                        "automation_disabled",
                        "Automation Mode was disabled before the command could be submitted."
                ));
                return;
            }
            if (client.player == null || client.getNetworkHandler() == null) {
                result.complete(failure(call, "world_unavailable", "The player connection closed before command submission."));
                return;
            }
            UUID feedbackId = UUID.randomUUID();
            try {
                MinecraftCommandFeedbackTracker.begin(feedbackId, command);
                AutomationRouter.sendRawCommand(command);
                MinecraftCommandFeedbackTracker.await(feedbackId, 750L).thenAccept(feedback -> {
                    JsonObject output = commandFeedbackOutput(command, feedback);
                    if ("failed".equals(feedback.assessment())) {
                        result.complete(new ModelToolResult(
                                call.id(),
                                call.toolId(),
                                "failed",
                                output,
                                "server_rejected_command",
                                "Minecraft returned command failure feedback."
                        ));
                    } else if ("succeeded".equals(feedback.assessment())) {
                        result.complete(new ModelToolResult(
                                call.id(),
                                call.toolId(),
                                "completed",
                                output,
                                "",
                                "Minecraft returned command output confirming the submitted action."
                        ));
                    } else {
                        result.complete(new ModelToolResult(
                                call.id(),
                                call.toolId(),
                                "submitted",
                                output,
                                "",
                                "Koil submitted the command through the current player's normal command path, but no correlated feedback was observed."
                        ));
                    }
                });
            } catch (RuntimeException exception) {
                MinecraftCommandFeedbackTracker.finish(feedbackId);
                result.complete(failure(call, "command_submission_failed", message(exception)));
            }
        });
        return result;
    }

    private static JsonObject commandInspectionOutput(MinecraftCommandInspector.Inspection inspection) {
        JsonObject output = new JsonObject();
        output.addProperty("command", inspection.normalizedCommand().isBlank()
                ? ""
                : "/" + inspection.normalizedCommand());
        output.addProperty("valid", inspection.executable());
        output.addProperty("cursor", inspection.cursor());
        output.addProperty("problem", inspection.problem());
        com.google.gson.JsonArray suggestions = new com.google.gson.JsonArray();
        inspection.suggestions().forEach(suggestions::add);
        output.add("suggestions", suggestions);
        return output;
    }

    private static JsonObject commandFeedbackOutput(
            String command,
            MinecraftCommandFeedbackTracker.Result feedback
    ) {
        JsonObject output = new JsonObject();
        output.addProperty("command", "/" + command);
        output.addProperty("permissionSource", "current_player");
        output.addProperty(
                "approvalPolicy",
                AutomationModeController.approvalPolicy().name().toLowerCase(java.util.Locale.ROOT)
        );
        output.addProperty("feedbackAssessment", feedback.assessment());
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (MinecraftCommandFeedbackTracker.Feedback row : feedback.feedback()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("type", row.type());
            encoded.addProperty("text", row.text());
            rows.add(encoded);
        }
        output.add("feedback", rows);
        return output;
    }

    private static ModelToolResult toToolResult(ModelToolCall call, AutomationExecutionResult result) {
        JsonObject output = new JsonObject();
        output.addProperty("executionId", result.executionId().toString());
        output.addProperty("template", result.templateId());
        output.addProperty("durationMs", Math.max(0L,
                java.time.Duration.between(result.startedAt(), result.finishedAt()).toMillis()));
        addPosition(output, "initialPosition", result.initialPosition());
        addPosition(output, "finalPosition", result.finalPosition());
        if (result.initialPosition() != null && result.finalPosition() != null) {
            double dx = result.finalPosition().x() - result.initialPosition().x();
            double dy = result.finalPosition().y() - result.initialPosition().y();
            double dz = result.finalPosition().z() - result.initialPosition().z();
            output.addProperty("actualDistance", Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        for (Map.Entry<String, Object> entry : result.state().entrySet()) {
            if (entry.getKey().startsWith("result.")) {
                addPrimitive(output, entry.getKey(), entry.getValue());
            }
        }
        String status = switch (result.status().toLowerCase(java.util.Locale.ROOT)) {
            case "success" -> "completed";
            case "cancelled", "canceled" -> "cancelled";
            case "blocked" -> "blocked";
            default -> "failed";
        };
        return new ModelToolResult(
                call.id(),
                call.toolId(),
                status,
                output,
                result.failureCode(),
                result.detail()
        );
    }

    private static void addPosition(JsonObject output, String key, AutomationPositionSnapshot position) {
        if (position == null) {
            return;
        }
        JsonObject value = new JsonObject();
        value.addProperty("x", position.x());
        value.addProperty("y", position.y());
        value.addProperty("z", position.z());
        value.addProperty("dimension", position.dimension());
        output.add(key, value);
    }

    private static void addPrimitive(JsonObject output, String key, Object value) {
        if (value instanceof Number number) {
            output.addProperty(key, number);
        } else if (value instanceof Boolean bool) {
            output.addProperty(key, bool);
        } else if (value != null) {
            output.addProperty(key, value.toString());
        }
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(
                call == null ? "" : call.id(),
                call == null ? "" : call.toolId(),
                "failed",
                new JsonObject(),
                code,
                detail
        );
    }

    private static String message(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null || cursor.getMessage().isBlank()
                ? cursor.getClass().getSimpleName()
                : cursor.getMessage();
    }
}
