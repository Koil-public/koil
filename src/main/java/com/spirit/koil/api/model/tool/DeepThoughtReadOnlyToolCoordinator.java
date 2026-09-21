package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.context.ContextIntelligenceService;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;

import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.UUID;

/** Read-only tool boundary used exclusively by conversational Deep Thought. */
public final class DeepThoughtReadOnlyToolCoordinator {
    private DeepThoughtReadOnlyToolCoordinator() {}

    public static boolean supports(String toolId) {
        if (toolId == null || toolId.isBlank()) return false;
        return (MinecraftKnowledgeModelToolRegistry.supports(toolId)
                && !MinecraftKnowledgeModelToolRegistry.COMMAND_TOOL_ID.equals(toolId))
                || READ_ONLY_WORKSPACE_TOOLS.contains(toolId)
                || InternetResearchModelToolRegistry.supports(toolId)
                || DatasetIntelligenceModelToolRegistry.supports(toolId)
                || (BrowserIntelligenceModelToolRegistry.supports(toolId) && BrowserIntelligenceModelToolRegistry.readOnly(toolId))
                || ContentIntelligenceModelToolRegistry.supports(toolId)
                || McpCatalogueModelToolRegistry.supports(toolId)
                || AutomationTimerModelToolRegistry.STATUS_TOOL_ID.equals(toolId)
                || AutomationTimerModelToolRegistry.LIST_TOOL_ID.equals(toolId)
                || (WorkspaceProcessModelToolRegistry.supports(toolId) && !WorkspaceProcessModelToolRegistry.STOP.equals(toolId))
                || WorkspacePackageModelToolRegistry.INSPECT.equals(toolId)
                || (WorkspaceDatabaseModelToolRegistry.supports(toolId) && !WorkspaceDatabaseModelToolRegistry.EXECUTE.equals(toolId))
                || (WorkspaceGitArchiveModelToolRegistry.supports(toolId) && !Set.of(WorkspaceGitArchiveModelToolRegistry.GIT_STAGE, WorkspaceGitArchiveModelToolRegistry.GIT_COMMIT, WorkspaceGitArchiveModelToolRegistry.ARC_CREATE, WorkspaceGitArchiveModelToolRegistry.ARC_EXTRACT, WorkspaceGitArchiveModelToolRegistry.ARC_PATCH).contains(toolId))
                || SystemNetworkModelToolRegistry.supports(toolId)
                || (DataContextIndexModelToolRegistry.supports(toolId) && !DataContextIndexModelToolRegistry.DATA_CONVERT.equals(toolId))
                || Set.of(BackgroundAutomationModelToolRegistry.WORKFLOW_LIST, BackgroundAutomationModelToolRegistry.SCHEDULE_STATUS, BackgroundAutomationModelToolRegistry.SCHEDULE_LIST).contains(toolId)
                || AgentSkillModelToolRegistry.supports(toolId)
                || ToolDiscoveryModelToolRegistry.supports(toolId)
                || KoilDocumentationModelToolRegistry.supports(toolId)
                || CodeIntelligenceModelToolRegistry.supports(toolId)
                || ContextIntelligenceService.supports(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        return execute(null, call);
    }

    public static CompletableFuture<ModelToolResult> execute(UUID sessionId, ModelToolCall call) {
        AutomationChatHudState.toolStarted(call);
        CompletableFuture<ModelToolResult> execution;
        try {
            execution = executeInternal(sessionId, call);
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

    private static CompletableFuture<ModelToolResult> recordExternalEvidence(
            UUID sessionId, ModelToolCall call, CompletableFuture<ModelToolResult> execution
    ) {
        return execution.whenComplete((result, failure) -> {
            if (failure == null && result != null && sessionId != null) {
                KoilKnowledgeRuntime.research().ifPresent(evidence -> evidence.record(sessionId.toString(), call, result));
            }
        });
    }

    private static CompletableFuture<ModelToolResult> executeInternal(UUID sessionId, ModelToolCall call) {
        if (call == null || !supports(call.toolId())) return unsupported(call);
        if (MinecraftKnowledgeModelToolRegistry.supports(call.toolId())) {
            return MinecraftKnowledgeModelToolRegistry.execute(call);
        }
        if (READ_ONLY_WORKSPACE_TOOLS.contains(call.toolId())) {
            return ModelWorkspaceToolRegistry.executeReadOnly(call);
        }
        if (InternetResearchModelToolRegistry.supports(call.toolId())) {
            return recordExternalEvidence(sessionId, call, InternetResearchModelToolRegistry.execute(call));
        }
        if (DatasetIntelligenceModelToolRegistry.supports(call.toolId())) {
            return recordExternalEvidence(sessionId, call, DatasetIntelligenceModelToolRegistry.execute(call));
        }
        if (BrowserIntelligenceModelToolRegistry.supports(call.toolId()) && BrowserIntelligenceModelToolRegistry.readOnly(call.toolId())) {
            return recordExternalEvidence(sessionId, call, BrowserIntelligenceModelToolRegistry.execute(call));
        }
        if (ContentIntelligenceModelToolRegistry.supports(call.toolId())) {
            return recordExternalEvidence(sessionId, call, ContentIntelligenceModelToolRegistry.execute(call));
        }
        if (McpCatalogueModelToolRegistry.supports(call.toolId())) {
            return McpCatalogueModelToolRegistry.execute(call);
        }
        if (AutomationTimerModelToolRegistry.STATUS_TOOL_ID.equals(call.toolId())
                || AutomationTimerModelToolRegistry.LIST_TOOL_ID.equals(call.toolId())) {
            return CompletableFuture.completedFuture(AutomationTimerModelToolRegistry.execute(call));
        }
        if (WorkspaceProcessModelToolRegistry.supports(call.toolId()) && !WorkspaceProcessModelToolRegistry.STOP.equals(call.toolId())) {
            return WorkspaceProcessModelToolRegistry.execute(null, call, false);
        }
        if (WorkspacePackageModelToolRegistry.INSPECT.equals(call.toolId())) {
            return WorkspacePackageModelToolRegistry.execute(null, call, false);
        }
        if (WorkspaceDatabaseModelToolRegistry.supports(call.toolId()) && !WorkspaceDatabaseModelToolRegistry.EXECUTE.equals(call.toolId())) {
            return WorkspaceDatabaseModelToolRegistry.execute(null, call, false);
        }
        if (WorkspaceGitArchiveModelToolRegistry.supports(call.toolId()) && !Set.of(WorkspaceGitArchiveModelToolRegistry.GIT_STAGE, WorkspaceGitArchiveModelToolRegistry.GIT_COMMIT, WorkspaceGitArchiveModelToolRegistry.ARC_CREATE, WorkspaceGitArchiveModelToolRegistry.ARC_EXTRACT, WorkspaceGitArchiveModelToolRegistry.ARC_PATCH).contains(call.toolId())) {
            return WorkspaceGitArchiveModelToolRegistry.execute(null, call, false);
        }
        if (SystemNetworkModelToolRegistry.supports(call.toolId())) {
            return SystemNetworkModelToolRegistry.execute(call);
        }
        if (DataContextIndexModelToolRegistry.supports(call.toolId()) && !DataContextIndexModelToolRegistry.DATA_CONVERT.equals(call.toolId())) {
            return DataContextIndexModelToolRegistry.execute(null, call, false);
        }
        if (Set.of(BackgroundAutomationModelToolRegistry.WORKFLOW_LIST, BackgroundAutomationModelToolRegistry.SCHEDULE_STATUS, BackgroundAutomationModelToolRegistry.SCHEDULE_LIST).contains(call.toolId())) {
            return BackgroundAutomationModelToolRegistry.execute(null, call, false);
        }
        if (ToolDiscoveryModelToolRegistry.supports(call.toolId())) {
            return ToolDiscoveryModelToolRegistry.execute(call);
        }
        if (AgentSkillModelToolRegistry.supports(call.toolId())) {
            return AgentSkillModelToolRegistry.execute(call);
        }
        if (KoilDocumentationModelToolRegistry.supports(call.toolId())) {
            return KoilDocumentationModelToolRegistry.execute(call);
        }
        if (CodeIntelligenceModelToolRegistry.supports(call.toolId())) {
            return CodeIntelligenceModelToolRegistry.execute(sessionId, call);
        }
        if (ContextIntelligenceService.supports(call.toolId())) {
            return ContextIntelligenceService.execute(sessionId == null ? "local" : sessionId.toString(), call);
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

    private static final Set<String> READ_ONLY_WORKSPACE_TOOLS = Set.of(
        "workspace.roots", "workspace.list", "workspace.stat", "workspace.read", "workspace.search"
    );
}
