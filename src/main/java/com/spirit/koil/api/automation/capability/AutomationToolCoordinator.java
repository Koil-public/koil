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
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolSchemaValidator;
import com.spirit.koil.api.model.ToolPreconditionEvaluator;
import com.spirit.koil.api.model.ToolExecutionPolicy;
import com.spirit.koil.api.model.ToolPreflight;
import com.spirit.koil.api.model.PreparedToolInvocation;
import com.spirit.koil.api.model.StagedToolPayload;
import com.spirit.koil.api.model.ToolPostconditionVerifier;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.telemetry.TelemetryCapabilityState;
import com.spirit.koil.api.telemetry.TelemetrySpanKind;
import com.spirit.koil.api.telemetry.TelemetryStore;
import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.model.tool.MinecraftCommandModelToolRegistry;
import com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry;
import com.spirit.koil.api.model.tool.ModelWorkspaceToolRegistry;
import com.spirit.koil.api.model.tool.AutomationPlanModelToolRegistry;
import com.spirit.koil.api.model.tool.AutomationGoalModelToolRegistry;
import com.spirit.koil.api.model.tool.AutomationKtlSkillModelToolRegistry;
import com.spirit.koil.api.model.tool.AgentSkillModelToolRegistry;
import com.spirit.koil.api.model.tool.ToolDiscoveryModelToolRegistry;
import com.spirit.koil.api.model.tool.ProjectValidationModelToolRegistry;
import com.spirit.koil.api.model.tool.InternetResearchModelToolRegistry;
import com.spirit.koil.api.model.tool.ContentIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.BrowserIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.DatasetIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.KoilDocumentationModelToolRegistry;
import com.spirit.koil.api.model.tool.CodeIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.DynamicMcpToolRegistry;
import com.spirit.koil.api.model.tool.McpCatalogueModelToolRegistry;
import com.spirit.koil.api.model.tool.AutomationTimerModelToolRegistry;
import com.spirit.koil.api.model.tool.WorkspaceExecutionModelToolRegistry;
import com.spirit.koil.api.model.tool.WorkspaceProcessModelToolRegistry;
import com.spirit.koil.api.model.tool.WorkspacePackageModelToolRegistry;
import com.spirit.koil.api.model.tool.WorkspaceDatabaseModelToolRegistry;
import com.spirit.koil.api.model.tool.WorkspaceGitArchiveModelToolRegistry;
import com.spirit.koil.api.model.tool.SystemNetworkModelToolRegistry;
import com.spirit.koil.api.model.tool.DataContextIndexModelToolRegistry;
import com.spirit.koil.api.model.tool.BackgroundAutomationModelToolRegistry;
import net.minecraft.client.MinecraftClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Validates model calls against the shared capability registry and hands the
 * resulting typed plan to the existing automation planner on the client thread.
 */
public final class AutomationToolCoordinator {
    private AutomationToolCoordinator() {
    }

    /**
     * Returns whether a registered model tool id has an authoritative execution
     * owner in the same dispatcher used by execute(). Keep runtime diagnostics
     * pointed at this method so catalog growth cannot drift away from verification.
     */
    public static boolean hasExecutableOwner(String toolId) {
        if (toolId == null || toolId.isBlank()) return false;
        return MinecraftCommandModelToolRegistry.supports(toolId)
                || MinecraftKnowledgeModelToolRegistry.supports(toolId)
                || InternetResearchModelToolRegistry.supports(toolId)
                || DatasetIntelligenceModelToolRegistry.supports(toolId)
                || BrowserIntelligenceModelToolRegistry.supports(toolId)
                || ContentIntelligenceModelToolRegistry.supports(toolId)
                || KoilDocumentationModelToolRegistry.supports(toolId)
                || CodeIntelligenceModelToolRegistry.supports(toolId)
                || com.spirit.koil.api.context.ContextIntelligenceService.supports(toolId)
                || McpCatalogueModelToolRegistry.supports(toolId)
                || AutomationTimerModelToolRegistry.supports(toolId)
                || WorkspaceExecutionModelToolRegistry.supports(toolId)
                || WorkspaceProcessModelToolRegistry.supports(toolId)
                || WorkspacePackageModelToolRegistry.supports(toolId)
                || WorkspaceDatabaseModelToolRegistry.supports(toolId)
                || WorkspaceGitArchiveModelToolRegistry.supports(toolId)
                || SystemNetworkModelToolRegistry.supports(toolId)
                || DataContextIndexModelToolRegistry.supports(toolId)
                || BackgroundAutomationModelToolRegistry.supports(toolId)
                || DynamicMcpToolRegistry.supports(toolId)
                || AutomationPlanModelToolRegistry.supports(toolId)
                || AutomationGoalModelToolRegistry.supports(toolId)
                || AutomationKtlSkillModelToolRegistry.supportsCatalog(toolId)
                || AutomationKtlSkillModelToolRegistry.supportsRun(toolId)
                || AgentSkillModelToolRegistry.supports(toolId)
                || ToolDiscoveryModelToolRegistry.supports(toolId)
                || ModelWorkspaceToolRegistry.supports(toolId)
                || ProjectValidationModelToolRegistry.supports(toolId)
                || AutomationCapabilityRegistry.definitions().containsKey(toolId);
    }

    /**
     * Inspect a call using the same authoritative catalog and global execution
     * gates used by execute(). This method has no side effects and never grants
     * confirmation. Registry-specific semantic checks that cannot be proven
     * generically are retained as deferred preconditions instead of being
     * silently treated as satisfied.
     */
    public static ToolPreflight inspect(ModelToolCall call, long observationEpoch) {
        String toolId = call == null ? "" : call.toolId();
        java.util.ArrayList<String> blockers = new java.util.ArrayList<>();
        ModelToolDefinition definition = LocalModelToolCatalog.definition(toolId).orElse(null);
        if (definition == null) {
            blockers.add(toolId.isBlank() ? "tool_name_missing" : "unknown_tool");
            return new ToolPreflight(
                    toolId, false, false, false, false, false, false,
                    false, false, observationEpoch, blockers, java.util.List.of(),
                    java.util.List.of(), ToolExecutionPolicy.conservative()
            );
        }
        if (!AutomationModeController.isAutomationMode()) blockers.add("automation_disabled");
        var eligibility = LocalModelService.selectedAutomationEligibility();
        if (!eligibility.eligible() && !LocalModelService.experimentalAutomationAllowed()) {
            blockers.add("automation_model_complexity");
        }
        blockers.addAll(ModelToolSchemaValidator.validate(
                definition.inputSchema(),
                call == null ? null : call.arguments()
        ));
        ToolPreconditionEvaluator.Assessment preconditions = ToolPreconditionEvaluator.evaluate(definition, call);
        blockers.addAll(preconditions.blockers());

        ToolExecutionPolicy policy = definition.executionPolicy();
        boolean readOnly = definition.sideEffects().isEmpty();
        boolean speculativeRead = readOnly
                && !definition.confirmationRequired()
                && policy.allowsSpeculativeRead();
        boolean prepare = !readOnly && policy.allowsPreparation();
        return new ToolPreflight(
                toolId,
                true,
                blockers.isEmpty(),
                preconditions.fullyEvaluated(),
                readOnly,
                speculativeRead,
                prepare,
                definition.confirmationRequired(),
                definition.reversible(),
                observationEpoch,
                blockers,
                preconditions.deferred(),
                definition.preconditions(),
                policy
        );
    }

    /**
     * Performs side-effect-free validation/preparation of one exact mutating
     * invocation. VALIDATE_ONLY preparation is intentionally not described as a
     * staged transaction: no mutation payload is created and approval/commit
     * remain entirely in execute().
     */
    public static java.util.Optional<PreparedToolInvocation> prepare(ModelToolCall call, long observationEpoch) {
        ToolPreflight preflight = inspect(call, observationEpoch);
        if (preflight.blocked() || !preflight.fullyEvaluated() || !preflight.preparationAllowed()) {
            return java.util.Optional.empty();
        }
        StagedToolPayload staged = null;
        if (preflight.executionPolicy().preparation() == ToolExecutionPolicy.PreparationMode.STAGE_PAYLOAD) {
            try {
                if (ModelWorkspaceToolRegistry.supports(call.toolId())) {
                    staged = ModelWorkspaceToolRegistry.stageMutation(call).orElse(null);
                }
            } catch (Exception failure) {
                return java.util.Optional.empty();
            }
            if (staged == null) return java.util.Optional.empty();
        }
        String resourceFingerprint = staged == null
                ? ""
                : Integer.toHexString(staged.resourceFingerprints().hashCode());
        String fingerprint = observationEpoch + "|" + call.toolId() + "|" + call.arguments() + "|" + resourceFingerprint;
        return java.util.Optional.of(new PreparedToolInvocation(
                call, preflight, observationEpoch, System.currentTimeMillis(), fingerprint,
                staged, staged == null ? java.util.List.of() : staged.postconditions()
        ));
    }

    /**
     * Commits an invocation that was prepared against the same observation
     * epoch. Staged filesystem fingerprints are revalidated before approval,
     * and predicted postconditions are verified after the authoritative tool
     * executor returns. Preparation never bypasses the normal approval path.
     */
    public static CompletableFuture<ModelToolResult> executePrepared(
            UUID displayRequestId,
            ModelToolCall call,
            PreparedToolInvocation prepared,
            long currentEpoch,
            boolean preapproved
    ) {
        AutomationChatHudState.toolStarted(call);
        CompletableFuture<ModelToolResult> execution;
        try {
            ToolPreflight preflight = inspect(call, currentEpoch);
            if (prepared == null || !prepared.matches(call, currentEpoch)) {
                execution = CompletableFuture.completedFuture(failure(
                        call, "prepared_invocation_stale",
                        "Prepared invocation no longer matches the current observation state."
                ));
            } else if (preflight.blocked()) {
                execution = CompletableFuture.completedFuture(failure(
                        call,
                        preflight.blockers().isEmpty() ? "preflight_blocked" : preflight.blockers().get(0),
                        "Tool preflight rejected prepared execution: " + String.join(", ", preflight.blockers())
                ));
            } else if (prepared.staged() && ModelWorkspaceToolRegistry.supports(call.toolId())
                    && !ModelWorkspaceToolRegistry.stagedMutationStillFresh(prepared.stagedPayload(), call)) {
                LocalModelRuntimeLog.write(
                        "tool_prepared_state_changed",
                        call.toolId() + " | fingerprint=" + prepared.fingerprint()
                );
                execution = CompletableFuture.completedFuture(new ModelToolResult(
                        call.id(), call.toolId(), "stale", new JsonObject(),
                        "prepared_state_changed",
                        "The staged mutation's source state changed before commit; restage from current state.",
                        System.currentTimeMillis(), System.currentTimeMillis(), "failed",
                        java.util.List.of(), true, false, "not_required"
                ));
            } else {
                execution = executeWithStandardApprovalBoundary(
                        displayRequestId,
                        call,
                        preflight,
                        preapproved,
                        approved -> {
                            CompletableFuture<ModelToolResult> committed;
                            if (prepared.staged() && ModelWorkspaceToolRegistry.supports(call.toolId())) {
                                committed = ModelWorkspaceToolRegistry.execute(
                                        displayRequestId, call, approved, prepared.stagedPayload()
                                );
                            } else {
                                committed = executeInternal(displayRequestId, call, approved);
                            }
                            return committed.thenApply(result -> verifyPreparedPostconditions(prepared, result));
                        }
                );
            }
        } catch (RuntimeException exception) {
            execution = CompletableFuture.completedFuture(failure(call, "tool_execution_failed", message(exception)));
        }
        return execution.whenComplete((result, error) -> AutomationChatHudState.toolFinished(
                call,
                error == null ? result : failure(call, "tool_execution_failed", message(error))
        ));
    }

    private static ModelToolResult verifyPreparedPostconditions(
            PreparedToolInvocation prepared,
            ModelToolResult result
    ) {
        if (prepared == null || result == null || !result.completedAndValidated()
                || prepared.postconditions().isEmpty()) {
            return result;
        }
        ToolPostconditionVerifier.Verification verification =
                ToolPostconditionVerifier.verify(prepared.postconditions());
        if (verification.passed()) {
            LocalModelRuntimeLog.write(
                    "tool_postconditions_verified",
                    result.toolId() + " | count=" + prepared.postconditions().size()
            );
            return result;
        }
        LocalModelRuntimeLog.write(
                "tool_postconditions_failed",
                result.toolId() + " | failures=" + String.join("; ", verification.failures())
        );
        String detail = result.detail()
                + " Postcondition verification failed: "
                + String.join("; ", verification.failures());
        return new ModelToolResult(
                result.callId(), result.toolId(), result.status(), result.output(),
                "postcondition_failed", detail,
                result.startedAtMillis(), result.completedAtMillis(), "failed",
                result.changedTargets(), true, result.cancelled(), result.approvalStatus()
        );
    }

    /**
     * Executes a coordinator-approved read-only speculative observation without
     * publishing a user-visible tool lifecycle event. The same executeInternal
     * path remains authoritative, so speculation cannot gain capabilities that
     * normal execution does not have. Mutating, confirmation-gated, or otherwise
     * unsafe calls are rejected by inspect() before reaching the runtime.
     */
    public static CompletableFuture<ModelToolResult> executeSpeculative(
            UUID displayRequestId,
            ModelToolCall call,
            long observationEpoch
    ) {
        ToolPreflight preflight = inspect(call, observationEpoch);
        if (preflight.blocked() || !preflight.fullyEvaluated()
                || !preflight.speculativeReadAllowed() || !preflight.readOnly()
                || preflight.confirmationRequired()) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "speculation_not_allowed",
                    "This capability is not eligible for speculative read execution."
            ));
        }
        try {
            return executeInternal(displayRequestId, call, false);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(call, "tool_execution_failed", message(exception)));
        }
    }

    public static CompletableFuture<ModelToolResult> execute(UUID displayRequestId, ModelToolCall call) {
        return execute(displayRequestId, call, false);
    }

    public static CompletableFuture<ModelToolResult> execute(
            UUID displayRequestId,
            ModelToolCall call,
            boolean preapproved
    ) {
        AutomationChatHudState.toolStarted(call);
        CompletableFuture<ModelToolResult> execution;
        try {
            ToolPreflight preflight = inspect(call, 0L);
            if (preflight.blocked()) {
                String code = preflight.blockers().isEmpty() ? "preflight_blocked" : preflight.blockers().get(0);
                execution = CompletableFuture.completedFuture(failure(
                        call,
                        code,
                        "Tool preflight rejected execution: " + String.join(", ", preflight.blockers())
                ));
            } else {
                execution = executeWithStandardApprovalBoundary(
                        displayRequestId,
                        call,
                        preflight,
                        preapproved,
                        approved -> executeInternal(displayRequestId, call, approved)
                );
            }
        } catch (RuntimeException exception) {
            execution = CompletableFuture.completedFuture(failure(call, "tool_execution_failed", message(exception)));
        }
        return execution.whenComplete((result, error) -> AutomationChatHudState.toolFinished(
                call,
                error == null ? result : failure(call, "tool_execution_failed", message(error))
        ));
    }


    /**
     * Global deny-by-default boundary for model-driven side effects.
     *
     * <p>The user-facing approval UI is owned by LocalModelService, which can
     * present the existing formatted single/batch Automation approval card.
     * This coordinator deliberately does not create a second approval surface.
     * Calls reaching this boundary in STANDARD mode must therefore already be
     * preapproved. Direct/nested callers that bypass the model service are
     * rejected rather than silently executing or opening a differently styled
     * popup. UNRESTRICTED remains the explicit session-wide bypass.</p>
     */
    private static CompletableFuture<ModelToolResult> executeWithStandardApprovalBoundary(
            UUID displayRequestId,
            ModelToolCall call,
            ToolPreflight preflight,
            boolean preapproved,
            java.util.function.Function<Boolean, CompletableFuture<ModelToolResult>> executor
    ) {
        if (preflight == null || preflight.readOnly() || preapproved || AutomationModeController.isUnrestrictedMode()) {
            return executor.apply(preapproved || AutomationModeController.isUnrestrictedMode());
        }
        return CompletableFuture.completedFuture(failure(
                call,
                "approval_required",
                "STANDARD Automation Mode requires this side-effecting action to be approved through the existing Automation approval flow before execution."
        ));
    }


    private static CompletableFuture<ModelToolResult> recordExternalEvidence(
            UUID displayRequestId, ModelToolCall call, CompletableFuture<ModelToolResult> execution
    ) {
        return execution.whenComplete((result, failure) -> {
            if (failure == null && result != null && displayRequestId != null) {
                com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime.research()
                        .ifPresent(evidence -> evidence.record(displayRequestId.toString(), call, result));
            }
        });
    }
    private static CompletableFuture<ModelToolResult> executeInternal(
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
        var eligibility = LocalModelService.selectedAutomationEligibility();
        if (!eligibility.eligible() && !LocalModelService.experimentalAutomationAllowed()) {
            LocalModelService.revokeIneligibleAutomation(eligibility, true);
            return CompletableFuture.completedFuture(failure(
                    call,
                    "automation_model_complexity",
                    eligibility.detail()
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
        if (InternetResearchModelToolRegistry.supports(call.toolId())) {
            return recordExternalEvidence(displayRequestId, call, InternetResearchModelToolRegistry.execute(call));
        }
        if (DatasetIntelligenceModelToolRegistry.supports(call.toolId())) {
            return recordExternalEvidence(displayRequestId, call, DatasetIntelligenceModelToolRegistry.execute(call));
        }
        if (BrowserIntelligenceModelToolRegistry.supports(call.toolId())) {
            return recordExternalEvidence(displayRequestId, call, BrowserIntelligenceModelToolRegistry.execute(call));
        }
        if (ContentIntelligenceModelToolRegistry.supports(call.toolId())) {
            return recordExternalEvidence(displayRequestId, call, ContentIntelligenceModelToolRegistry.execute(call));
        }
        if (KoilDocumentationModelToolRegistry.supports(call.toolId())) {
            return KoilDocumentationModelToolRegistry.execute(call);
        }
        if (CodeIntelligenceModelToolRegistry.supports(call.toolId())) {
            return CodeIntelligenceModelToolRegistry.execute(displayRequestId, call);
        }
        if (com.spirit.koil.api.context.ContextIntelligenceService.supports(call.toolId())) {
            return com.spirit.koil.api.context.ContextIntelligenceService.execute(
                    displayRequestId == null ? "local" : displayRequestId.toString(), call);
        }
        if (McpCatalogueModelToolRegistry.supports(call.toolId())) {
            return McpCatalogueModelToolRegistry.execute(call);
        }
        if (AutomationTimerModelToolRegistry.supports(call.toolId())) {
            return CompletableFuture.completedFuture(AutomationTimerModelToolRegistry.execute(call));
        }
        if (WorkspaceExecutionModelToolRegistry.supports(call.toolId())) {
            return WorkspaceExecutionModelToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (WorkspaceProcessModelToolRegistry.supports(call.toolId())) {
            return WorkspaceProcessModelToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (WorkspacePackageModelToolRegistry.supports(call.toolId())) {
            return WorkspacePackageModelToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (WorkspaceDatabaseModelToolRegistry.supports(call.toolId())) {
            return WorkspaceDatabaseModelToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (WorkspaceGitArchiveModelToolRegistry.supports(call.toolId())) {
            return WorkspaceGitArchiveModelToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (SystemNetworkModelToolRegistry.supports(call.toolId())) {
            return SystemNetworkModelToolRegistry.execute(call);
        }
        if (DataContextIndexModelToolRegistry.supports(call.toolId())) {
            return DataContextIndexModelToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (BackgroundAutomationModelToolRegistry.supports(call.toolId())) {
            return BackgroundAutomationModelToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (DynamicMcpToolRegistry.supports(call.toolId())) {
            return DynamicMcpToolRegistry.execute(call);
        }
        if (AutomationPlanModelToolRegistry.supports(call.toolId())) {
            return AutomationPlanModelToolRegistry.execute(call);
        }
        if (AutomationGoalModelToolRegistry.supports(call.toolId())) {
            return executeAutomationGoal(displayRequestId, call, preapproved);
        }
        if (AutomationKtlSkillModelToolRegistry.supportsCatalog(call.toolId())) {
            return AutomationKtlSkillModelToolRegistry.executeCatalog(call);
        }
        if (AutomationKtlSkillModelToolRegistry.supportsRun(call.toolId())) {
            return executeKtlSkill(displayRequestId, call, preapproved);
        }
        if (ToolDiscoveryModelToolRegistry.supports(call.toolId())) {
            return ToolDiscoveryModelToolRegistry.execute(call);
        }
        if (AgentSkillModelToolRegistry.supports(call.toolId())) {
            return AgentSkillModelToolRegistry.execute(call);
        }
        if (ModelWorkspaceToolRegistry.supports(call.toolId())) {
            return ModelWorkspaceToolRegistry.execute(displayRequestId, call, preapproved);
        }
        if (ProjectValidationModelToolRegistry.supports(call.toolId())) {
            return ProjectValidationModelToolRegistry.execute(displayRequestId, call, preapproved);
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
            String mainThreadSpan = beginMainThreadTelemetry(displayRequestId, call, "automation cancellation");
            long queuedAtMillis = System.currentTimeMillis();
            client.execute(() -> {
                markMainThreadStarted(displayRequestId, mainThreadSpan, queuedAtMillis);
                try {
                    AutomationRouter.cancelCurrentTask("cancelled by model tool");
                    finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.AVAILABLE,
                            "cancel_submitted", "Automation cancellation executed on the client thread.");
                } catch (RuntimeException exception) {
                    finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.FAILED,
                            "cancel_failed", message(exception));
                }
            });
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
        String mainThreadSpan = beginMainThreadTelemetry(displayRequestId, call, "automation dispatch");
        long queuedAtMillis = System.currentTimeMillis();
        client.execute(() -> {
            markMainThreadStarted(displayRequestId, mainThreadSpan, queuedAtMillis);
            try {
                String telemetryParent = TelemetryStore.latestSpan(displayRequestId,
                        TelemetrySpanKind.TOOL_INVOCATION, "call_id", call.id());
                AutomationRouter.handleInput(plan.request().withTelemetry(displayRequestId, telemetryParent), "local-model");
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.AVAILABLE, "submitted", "Automation request submitted to planner.");
            } catch (RuntimeException exception) {
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.FAILED, "submission_failed", message(exception));
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

        // The capability definition's duration remains operation guidance for
        // KTL primitives/watchdogs. It is deliberately not an overall agent
        // wall-clock deadline: persistent automation ends by verified outcome,
        // cancellation, or an observed unrecoverable condition.
        return execution.handle((result, error) -> error == null
                ? toToolResult(call, result)
                : failure(call, "tool_execution_failed", message(error)));
    }

    private static CompletableFuture<ModelToolResult> executeAutomationGoal(
            UUID displayRequestId,
            ModelToolCall call,
            boolean preapproved
    ) {
        if (!AutomationGoalModelToolRegistry.requestsExecution(call)) {
            return AutomationGoalModelToolRegistry.execute(call, false);
        }

        // Resolve facts and the bounded recipe graph before asking for approval.
        // Already-satisfied or still-blocked goals never need a mutation prompt.
        return AutomationGoalModelToolRegistry.execute(call, false).thenCompose(preview -> {
            if (!"approval_required".equals(preview.failureCode())) {
                return CompletableFuture.completedFuture(preview);
            }
            if (preapproved || AutomationModeController.isUnrestrictedMode()) {
                return AutomationGoalModelToolRegistry.execute(call, true);
            }
            if (displayRequestId == null) {
                return CompletableFuture.completedFuture(failure(
                        call,
                        "approval_unavailable",
                        "The high-level goal has no chat-panel approval surface for inventory-changing execution."
                ));
            }
            JsonObject arguments = call.arguments() == null ? new JsonObject() : call.arguments();
            String target = arguments.has("target") ? arguments.get("target").getAsString() : "";
            int count = arguments.has("count") ? arguments.get("count").getAsInt() : 1;
            String detail = "Koil resolved a high-level obtain goal for " + count + " x " + target
                    + ".\n\nExecution may craft synchronized recipes and change your inventory. "
                    + "Every internal craft remains guarded by screen, cursor, and inventory verification.";
            ModelGenerationHudState.state(
                    displayRequestId,
                    com.spirit.koil.api.model.ModelRequestState.EXECUTING_TOOL,
                    "waiting for goal execution approval"
            );
            return ModelGenerationHudState.requestApproval(
                            displayRequestId,
                            "Automation goal approval",
                            detail,
                            "Run Goal",
                            "Deny"
                    )
                    .thenCompose(approved -> approved
                            ? AutomationGoalModelToolRegistry.execute(call, true)
                            : CompletableFuture.completedFuture(new ModelToolResult(
                                    call.id(),
                                    call.toolId(),
                                    "rejected",
                                    new JsonObject(),
                                    "user_declined",
                                    "The player declined high-level goal execution."
                            )));
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
        if (preapproved || AutomationModeController.isUnrestrictedMode()) {
            return submitKtlSkill(client, displayRequestId, call, prepared);
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
                        ? submitKtlSkill(client, displayRequestId, call, prepared)
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
            UUID displayRequestId,
            ModelToolCall call,
            AutomationKtlSkillRegistry.PreparedSkill prepared
    ) {
        UUID executionId = prepared.request().executionId();
        CompletableFuture<AutomationExecutionResult> execution =
                AutomationExecutionResults.register(executionId);
        String mainThreadSpan = beginMainThreadTelemetry(displayRequestId, call, "KTL skill dispatch");
        long queuedAtMillis = System.currentTimeMillis();
        client.execute(() -> {
            markMainThreadStarted(displayRequestId, mainThreadSpan, queuedAtMillis);
            if (!AutomationModeController.isAutomationMode()) {
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.UNAVAILABLE, "automation_disabled", "Automation Mode was disabled before KTL dispatch.");
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
                String telemetryParent = TelemetryStore.latestSpan(displayRequestId,
                        TelemetrySpanKind.TOOL_INVOCATION, "call_id", call.id());
                AutomationRouter.handleInput(prepared.request().withTelemetry(displayRequestId, telemetryParent), "local-model-skill");
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.AVAILABLE, "submitted", "KTL skill submitted to AutomationRouter.");
            } catch (RuntimeException exception) {
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.FAILED, "submission_failed", message(exception));
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
        return execution.handle((result, error) -> error == null
                ? toToolResult(call, result)
                : failure(call, "ktl_execution_failed", message(error)));
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
            if (preapproved || !definition.confirmationRequired() || AutomationModeController.isUnrestrictedMode()) {
                return submitCurrentPlayerCommand(client, displayRequestId, call, command);
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
                        return submitCurrentPlayerCommand(client, displayRequestId, call, command);
                    });
        });
    }

    private static CompletableFuture<ModelToolResult> submitCurrentPlayerCommand(
            MinecraftClient client,
            UUID displayRequestId,
            ModelToolCall call,
            String command
    ) {
        CompletableFuture<ModelToolResult> result = new CompletableFuture<>();
        String mainThreadSpan = beginMainThreadTelemetry(displayRequestId, call, "command dispatch");
        long queuedAtMillis = System.currentTimeMillis();
        client.execute(() -> {
            markMainThreadStarted(displayRequestId, mainThreadSpan, queuedAtMillis);
            if (!AutomationModeController.isAutomationMode()) {
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.UNAVAILABLE, "automation_disabled", "Automation Mode disabled before command dispatch.");
                result.complete(failure(
                        call,
                        "automation_disabled",
                        "Automation Mode was disabled before the command could be submitted."
                ));
                return;
            }
            if (client.player == null || client.getNetworkHandler() == null) {
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.UNAVAILABLE, "world_unavailable", "Player connection closed before command dispatch.");
                result.complete(failure(call, "world_unavailable", "The player connection closed before command submission."));
                return;
            }
            UUID feedbackId = UUID.randomUUID();
            try {
                MinecraftCommandFeedbackTracker.begin(feedbackId, command);
                AutomationRouter.sendRawCommand(command);
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.AVAILABLE,
                        "submitted", "Minecraft command submitted on the client thread.");
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
                        // Some integrated/server command paths do not echo correlated
                        // feedback even when the command has already taken effect. For
                        // commands whose post-state is directly observable on the client,
                        // verify that state instead of leaving the durable objective stuck
                        // in "submitted" forever.
                        java.util.concurrent.CompletableFuture.delayedExecutor(
                                180L, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
                            ModelToolResult stateVerified = verifySubmittedCommandState(client, call, command, output);
                            if (stateVerified != null) {
                                result.complete(stateVerified);
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
                        }));
                    }
                });
            } catch (RuntimeException exception) {
                finishMainThreadTelemetry(displayRequestId, mainThreadSpan, TelemetryCapabilityState.FAILED,
                        "command_submission_failed", message(exception));
                MinecraftCommandFeedbackTracker.finish(feedbackId);
                result.complete(failure(call, "command_submission_failed", message(exception)));
            }
        });
        return result;
    }

    private static ModelToolResult verifySubmittedCommandState(
            MinecraftClient client,
            ModelToolCall call,
            String command,
            JsonObject output
    ) {
        if (client == null || call == null || command == null) return null;
        String normalized = command.strip().toLowerCase(java.util.Locale.ROOT);
        JsonObject verifiedOutput = output == null ? new JsonObject() : output.deepCopy();

        if ("world.set_time".equals(call.toolId()) && client.world != null && normalized.startsWith("time set ")) {
            String target = normalized.substring("time set ".length()).strip();
            long expected = switch (target) {
                case "day" -> 1000L;
                case "noon" -> 6000L;
                case "night" -> 13000L;
                case "midnight" -> 18000L;
                default -> -1L;
            };
            if (expected >= 0L) {
                long actual = Math.floorMod(client.world.getTimeOfDay(), 24000L);
                long distance = Math.min(Math.floorMod(actual - expected, 24000L), Math.floorMod(expected - actual, 24000L));
                if (distance <= 240L) {
                    verifiedOutput.addProperty("stateVerified", true);
                    verifiedOutput.addProperty("verificationKind", "client_world_time");
                    verifiedOutput.addProperty("observedTimeOfDay", actual);
                    verifiedOutput.add("structuredResult", commandStructuredResult(
                            "SUCCESS", call.toolId(), "client_state_verified", true, true,
                            command, 0, "state_verified"));
                    return new ModelToolResult(
                            call.id(), call.toolId(), "completed", verifiedOutput, "",
                            "Minecraft command feedback was not correlated, but the requested world time is observable in client state."
                    );
                }
            }
        }

        if ("minecraft.command".equals(call.toolId()) && client.interactionManager != null
                && normalized.startsWith("gamemode ")) {
            String requested = normalized.substring("gamemode ".length()).strip();
            int separator = requested.indexOf(' ');
            if (separator >= 0) requested = requested.substring(0, separator);
            String actual = client.interactionManager.getCurrentGameMode() == null
                    ? ""
                    : client.interactionManager.getCurrentGameMode().getName().toLowerCase(java.util.Locale.ROOT);
            if (!requested.isBlank() && requested.equals(actual)) {
                verifiedOutput.addProperty("stateVerified", true);
                verifiedOutput.addProperty("verificationKind", "client_gamemode");
                verifiedOutput.addProperty("observedGameMode", actual);
                verifiedOutput.add("structuredResult", commandStructuredResult(
                        "SUCCESS", call.toolId(), "client_state_verified", true, true,
                        command, 0, "state_verified"));
                return new ModelToolResult(
                        call.id(), call.toolId(), "completed", verifiedOutput, "",
                        "Minecraft command feedback was not correlated, but the requested game mode is observable in client state."
                );
            }
        }
        return null;
    }

    private static JsonObject commandInspectionOutput(MinecraftCommandInspector.Inspection inspection) {
        JsonObject output = new JsonObject();
        output.addProperty("command", inspection.normalizedCommand().isBlank()
                ? ""
                : "/" + inspection.normalizedCommand());
        output.addProperty("valid", inspection.executable());
        output.addProperty("cursor", inspection.cursor());
        output.addProperty("problem", inspection.problem());
        output.addProperty("rootAvailable", inspection.rootAvailable());
        com.google.gson.JsonArray roots = new com.google.gson.JsonArray();
        inspection.availableRoots().forEach(roots::add);
        output.add("availableRoots", roots);
        com.google.gson.JsonArray suggestions = new com.google.gson.JsonArray();
        inspection.suggestions().forEach(suggestions::add);
        output.add("suggestions", suggestions);
        output.add("structuredResult", commandStructuredResult(
                "FAILED",
                "minecraft.command",
                inspection.rootAvailable() ? "invalid_command_syntax" : "command_root_not_available",
                false,
                false,
                inspection.normalizedCommand(),
                0,
                inspection.problem()
        ));
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
        boolean succeeded = "succeeded".equals(feedback.assessment());
        boolean failed = "failed".equals(feedback.assessment());
        output.add("structuredResult", commandStructuredResult(
                succeeded ? "SUCCESS" : failed ? "FAILED" : "PARTIAL",
                "minecraft.command",
                succeeded ? "server_confirmed" : failed ? "server_rejected_command" : "feedback_unobserved",
                succeeded,
                succeeded,
                command,
                feedback.feedback().size(),
                feedback.assessment()
        ));
        return output;
    }

    private static JsonObject commandStructuredResult(
            String status,
            String action,
            String reason,
            boolean objectiveReached,
            boolean stateChanged,
            String command,
            int feedbackCount,
            String assessment
    ) {
        JsonObject structured = new JsonObject();
        structured.addProperty("status", status);
        structured.addProperty("action", action);
        structured.addProperty("reason", reason);
        structured.addProperty("objectiveReached", objectiveReached);
        structured.addProperty("stateChanged", stateChanged);
        structured.addProperty("retrySameAction", false);
        structured.addProperty("continueRecommended", "PARTIAL".equals(status));
        structured.addProperty("replanRecommended", !"SUCCESS".equals(status));
        JsonObject requested = new JsonObject();
        requested.addProperty("command", "/" + command);
        structured.add("requested", requested);
        structured.add("before", new JsonObject());
        JsonObject after = new JsonObject();
        after.addProperty("feedbackAssessment", assessment == null ? "" : assessment);
        structured.add("after", after);
        structured.add("delta", new JsonObject());
        JsonObject metrics = new JsonObject();
        metrics.addProperty("feedback_count", feedbackCount);
        structured.add("metrics", metrics);
        structured.add("failures", new com.google.gson.JsonArray());
        structured.add("recoveries", new com.google.gson.JsonArray());
        return structured;
    }

    private static ModelToolResult toToolResult(ModelToolCall call, AutomationExecutionResult result) {
        JsonObject output = new JsonObject();
        com.spirit.koil.api.automation.runtime.AutomationStructuredResult structured = result.structured();
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
        output.add("structuredResult", structured.toJson());
        output.addProperty("objectiveReached", structured.objectiveReached());
        output.addProperty("stateChanged", structured.stateChanged());
        output.addProperty("retrySameAction", structured.retrySameAction());
        output.addProperty("continueRecommended", structured.continueRecommended());
        output.addProperty("replanRecommended", structured.replanRecommended());
        String status = switch (structured.status()) {
            case SUCCESS -> "completed";
            case PARTIAL -> "partial";
            case BLOCKED -> "blocked";
            case CANCELLED -> "cancelled";
            case INTERRUPTED -> "interrupted";
            case NO_TARGET -> "no_target";
            case ALREADY_SATISFIED -> "already_satisfied";
            case FAILED -> "failed";
        };
        String validationStatus = result.state().getOrDefault("result.validation.status", "not_required").toString();
        if (AutomationModeController.isVerificationEnabled() && "completed".equals(status)) {
            output.addProperty("verification.enabled", true);
            if ("not_required".equals(validationStatus)) {
                validationStatus = "passed";
                output.addProperty("verification.kind", "structured_terminal_check");
                output.addProperty(
                        "verification.fact",
                        "Koil independently checked the registered executor's terminal status, failure code, and structured result state."
                );
            } else {
                output.addProperty("verification.kind", "capability_state_check");
            }
        } else {
            output.addProperty("verification.enabled", false);
        }
        if ("failed".equals(validationStatus) && "completed".equals(status)) {
            status = "failed";
        }
        String failureCode = "failed".equals(validationStatus) && result.failureCode().isBlank()
                ? "validation_failed"
                : result.failureCode();
        boolean retryable = structured.retrySameAction() || isRetryableResult(result, status, failureCode);
        return new ModelToolResult(
                call.id(),
                call.toolId(),
                status,
                output,
                failureCode,
                structured.conciseSummary(),
                result.startedAt() == null ? System.currentTimeMillis() : result.startedAt().toEpochMilli(),
                result.finishedAt() == null ? System.currentTimeMillis() : result.finishedAt().toEpochMilli(),
                validationStatus,
                java.util.List.of(),
                retryable,
                "cancelled".equals(status),
                "approved"
        );
    }

    static boolean isRetryableResult(AutomationExecutionResult result, String status, String failureCode) {
        Object declared = result.state().get("result.retryable");
        if (declared instanceof Boolean bool) return bool;
        if (declared != null) return Boolean.parseBoolean(declared.toString());
        String code = failureCode == null ? "" : failureCode.toLowerCase(java.util.Locale.ROOT);
        if (code.startsWith("unknown_") || code.contains("unsupported") || code.contains("permission")
                || code.contains("invalid_id")) {
            return false;
        }
        return "failed".equals(status) || "blocked".equals(status);
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

    private static String beginMainThreadTelemetry(UUID requestId, ModelToolCall call, String name) {
        if (requestId == null) return "";
        String parent = call == null ? "" : TelemetryStore.latestSpan(
                requestId, TelemetrySpanKind.TOOL_INVOCATION, "call_id", call.id());
        return TelemetryStore.beginSpan(requestId, parent, TelemetrySpanKind.MAIN_THREAD, name,
                call == null ? Map.of() : Map.of("call_id", call.id(), "tool_id", call.toolId()));
    }

    private static void markMainThreadStarted(UUID requestId, String spanId, long queuedAtMillis) {
        if (requestId == null || spanId == null || spanId.isBlank()) return;
        TelemetryStore.metric(requestId, spanId, "main_thread_queue_wait_ms",
                Math.max(0L, System.currentTimeMillis() - queuedAtMillis));
        TelemetryStore.event(requestId, spanId, "main_thread_started", "Client-thread dispatch began.");
    }

    private static void finishMainThreadTelemetry(
            UUID requestId, String spanId, TelemetryCapabilityState state, String reason, String detail
    ) {
        if (requestId == null || spanId == null || spanId.isBlank()) return;
        TelemetryStore.finishSpan(requestId, spanId, state, reason, detail);
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
