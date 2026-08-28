package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.util.concurrent.CompletableFuture;

/** Read-only tool boundary used exclusively by conversational Deep Thought. */
public final class DeepThoughtReadOnlyToolCoordinator {
    private DeepThoughtReadOnlyToolCoordinator() {}

    public static boolean supports(String toolId) {
        return MinecraftKnowledgeModelToolRegistry.supports(toolId)
                || ModelWorkspaceToolRegistry.supports(toolId)
                || InternetResearchModelToolRegistry.supports(toolId)
                || KoilDocumentationModelToolRegistry.supports(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        AutomationChatHudState.toolStarted(call);
        CompletableFuture<ModelToolResult> execution;
        try {
            execution = executeInternal(call);
        } catch (RuntimeException exception) {
            execution = CompletableFuture.completedFuture(new ModelToolResult(
                    call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                    "failed", new JsonObject(), "read_only_tool_failed",
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
            ));
        }
        return execution.whenComplete((result, error) -> AutomationChatHudState.toolFinished(
                call,
                error == null ? result : new ModelToolResult(
                        call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                        "failed", new JsonObject(), "read_only_tool_failed",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()
                )
        ));
    }

    private static CompletableFuture<ModelToolResult> executeInternal(ModelToolCall call) {
        if (call == null) return unsupported(null);
        if (MinecraftKnowledgeModelToolRegistry.supports(call.toolId())) {
            return MinecraftKnowledgeModelToolRegistry.execute(call);
        }
        if (ModelWorkspaceToolRegistry.supports(call.toolId())) {
            return ModelWorkspaceToolRegistry.executeReadOnly(call);
        }
        if (InternetResearchModelToolRegistry.supports(call.toolId())) {
            return InternetResearchModelToolRegistry.execute(call);
        }
        if (KoilDocumentationModelToolRegistry.supports(call.toolId())) {
            return KoilDocumentationModelToolRegistry.execute(call);
        }
        return unsupported(call);
    }

    private static CompletableFuture<ModelToolResult> unsupported(ModelToolCall call) {
        return CompletableFuture.completedFuture(new ModelToolResult(
                call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                "unsupported", new JsonObject(), "deep_thought_read_only",
                "This capability is not available through conversational Deep Thought. Enter /automate for side effects."
        ));
    }
}
