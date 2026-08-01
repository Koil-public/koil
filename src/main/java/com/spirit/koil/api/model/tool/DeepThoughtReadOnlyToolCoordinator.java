package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.util.concurrent.CompletableFuture;

/** Read-only tool boundary used exclusively by conversational Deep Thought. */
public final class DeepThoughtReadOnlyToolCoordinator {
    private DeepThoughtReadOnlyToolCoordinator() {}

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null) return unsupported(null);
        if (MinecraftKnowledgeModelToolRegistry.supports(call.toolId())) {
            return MinecraftKnowledgeModelToolRegistry.execute(call);
        }
        if (ModelWorkspaceToolRegistry.supports(call.toolId())) {
            return ModelWorkspaceToolRegistry.executeReadOnly(call);
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
