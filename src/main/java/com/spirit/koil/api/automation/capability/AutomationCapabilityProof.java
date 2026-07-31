package com.spirit.koil.api.automation.capability;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResult;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResults;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRole;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.format.RichChatModelFormattingContract;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.hardware.LocalModelHardwarePreflight;
import com.spirit.koil.api.model.hardware.ModelHardwareTier;
import com.spirit.koil.api.model.provider.colibri.ColibriConfiguration;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.command.MinecraftCommandFeedbackTracker;
import com.spirit.koil.api.chat.RichChatRowType;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class AutomationCapabilityProof {
    private AutomationCapabilityProof() {
    }

    public static void main(String[] args) {
        proveGeneratedRegistry();
        proveValidationAndCompilation();
        proveSessionApprovalPolicy();
        proveInlineApprovalState();
        proveToolConversationMessages();
        proveExecutionCorrelation();
        proveRichChatCommandLinks();
        proveCommandFeedbackAssessment();
        proveHonestDisabledHardwareReport();
        System.out.println("Automation capability proof passed.");
    }

    private static void proveGeneratedRegistry() {
        require(
                AutomationCapabilityRegistry.modelTools().size() == AutomationCapabilityRegistry.definitions().size(),
                "model schemas diverged from capability definitions"
        );
        require(
                AutomationPrimitiveRegistry.definitions().values().stream().noneMatch(AutomationPrimitiveDefinition::modelExposed),
                "a low-level KTL primitive was exposed directly to the model"
        );
        require(
                AutomationCapabilityRegistry.modelTools().stream().noneMatch(tool ->
                        tool.id().contains("key")
                                || tool.id().contains("mouse")
                                || tool.id().contains("ktl")),
                "an unrestricted execution mechanism was exposed"
        );
        AutomationCapabilityDefinition command = AutomationCapabilityRegistry.definitions().get("minecraft.command");
        require(command != null, "Minecraft command capability was not registered");
        require(command.confirmationRequired(), "Minecraft command capability bypassed confirmation");
        require(
                command.preconditions().contains("player_command_permission"),
                "Minecraft command capability did not preserve current-player permissions"
        );
        AutomationCapabilityDefinition advancements =
                AutomationCapabilityRegistry.definitions().get("player.grant_advancements");
        require(advancements != null, "typed all-advancements capability was not registered");
        require(advancements.confirmationRequired(), "advancement grant bypassed confirmation");
    }

    private static void proveValidationAndCompilation() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("direction", "forward");
        arguments.addProperty("distance", 50);
        UUID executionId = UUID.randomUUID();
        AutomationCapabilityPlan plan = AutomationCapabilityRegistry.validateAndCompile(
                "movement.walk_relative",
                arguments,
                executionId
        );
        require(plan.action() == AutomationCapabilityPlan.Action.EXECUTE_PLAN, "walk did not compile to a plan");
        require(plan.request().executionId().equals(executionId), "execution correlation id was not preserved");
        require(plan.request().directTemplate(), "walk was not compiled as a deterministic template");
        require(plan.request().rawInput().contains("count.value=50"), "walk distance was not bound");
        require(plan.request().rawInput().contains("direction.id=forward"), "walk direction was not bound");

        JsonObject injection = arguments.deepCopy();
        injection.addProperty("raw_command", "/op @s");
        expectFailure("invalid_arguments", () ->
                AutomationCapabilityRegistry.validateAndCompile("movement.walk_relative", injection, UUID.randomUUID()));

        JsonObject outOfRange = new JsonObject();
        outOfRange.addProperty("direction", "forward");
        outOfRange.addProperty("distance", 1_000_000);
        expectFailure("argument_out_of_range", () ->
                AutomationCapabilityRegistry.validateAndCompile("movement.walk_relative", outOfRange, UUID.randomUUID()));

        expectFailure("unknown_tool", () ->
                AutomationCapabilityRegistry.validateAndCompile("command.execute", new JsonObject(), UUID.randomUUID()));

        JsonObject commandArguments = new JsonObject();
        commandArguments.addProperty("command", "/give @s minecraft:Player_Head{display:{Name:'{\"text\":\"SpiritXIV\"}'}}");
        AutomationCapabilityPlan commandPlan = AutomationCapabilityRegistry.validateAndCompile(
                "minecraft.command",
                commandArguments,
                UUID.randomUUID()
        );
        require(
                commandPlan.action() == AutomationCapabilityPlan.Action.SUBMIT_COMMAND,
                "Minecraft command did not compile to the confirmation-gated action"
        );
        require(
                commandPlan.request().rawInput().contains("SpiritXIV"),
                "Minecraft command compilation changed case-sensitive arguments"
        );

        AutomationCapabilityPlan advancementPlan = AutomationCapabilityRegistry.validateAndCompile(
                "player.grant_advancements",
                new JsonObject(),
                UUID.randomUUID()
        );
        require(
                "/advancement grant @s everything".equals(advancementPlan.request().rawInput()),
                "all-advancements capability did not compile to the exact Minecraft 1.20.1 syntax"
        );

        JsonObject multilineCommand = new JsonObject();
        multilineCommand.addProperty("command", "/time set day\n/kill @s");
        expectFailure("invalid_command", () -> AutomationCapabilityRegistry.validateAndCompile(
                "minecraft.command",
                multilineCommand,
                UUID.randomUUID()
        ));

        JsonObject controlCommand = new JsonObject();
        controlCommand.addProperty("command", "/time\tset day");
        expectFailure("invalid_command", () -> AutomationCapabilityRegistry.validateAndCompile(
                "minecraft.command",
                controlCommand,
                UUID.randomUUID()
        ));
    }

    private static void proveToolConversationMessages() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("direction", "left");
        arguments.addProperty("distance", 3);
        ModelToolCall call = new ModelToolCall("tool-proof", "movement.walk_relative", arguments);
        ModelMessage assistant = ModelMessage.assistantToolCall("", call);
        require(assistant.role() == ModelRole.ASSISTANT, "tool use was not an assistant message");
        require("tool-proof".equals(assistant.toolCallId()), "tool use id was lost");
        require("movement.walk_relative".equals(assistant.metadata().get("tool_name")), "tool name was lost");

        JsonObject output = new JsonObject();
        output.addProperty("actualDistance", 2.98D);
        ModelMessage result = ModelMessage.toolResult(new ModelToolResult(
                "tool-proof", "movement.walk_relative", "failed", output, "path_blocked", "A wall blocked the route."
        ));
        require(result.role() == ModelRole.TOOL, "tool result role was incorrect");
        require(result.content().contains("\"actualDistance\":2.98"), "structured result was not serialized");
        require(result.content().contains("\"failureCode\":\"path_blocked\""), "failure code was not serialized");
        require(result.content().contains("A wall blocked the route."), "failure detail was not serialized");
    }

    private static void proveSessionApprovalPolicy() {
        AutomationModeController.setAutomationMode(true);
        require(!AutomationModeController.isYoloMode(), "Automation Mode did not start with standard approval");
        require(!AutomationModeController.isDeepThinkingEnabled(),
                "Automation Mode did not start with Deep Thinking disabled");
        AutomationModeController.setDeepThinkingEnabled(true);
        require(AutomationModeController.isDeepThinkingEnabled(),
                "Deep Thinking could not be enabled for the session");
        AutomationModeController.enableYoloMode();
        require(AutomationModeController.isYoloMode(), "session YOLO policy did not activate");
        AutomationModeController.setAutomationMode(false);
        require(!AutomationModeController.isYoloMode(), "session YOLO policy survived Automation Mode shutdown");
        require(!AutomationModeController.isDeepThinkingEnabled(),
                "session Deep Thinking setting survived Automation Mode shutdown");
    }

    private static void proveInlineApprovalState() {
        UUID requestId = UUID.randomUUID();
        ModelGenerationHudState.begin(requestId, "run a command");
        CompletableFuture<Boolean> decision = ModelGenerationHudState.requestApproval(
                requestId,
                "Model command approval",
                "Run /time set day with current player permissions?",
                "Run Command",
                "Deny"
        );
        ModelGenerationHudState.Snapshot snapshot = ModelGenerationHudState.visibleSnapshot();
        require(snapshot != null && snapshot.approval() != null, "inline approval did not become visible");
        require(
                snapshot.approval().message().contains("/time set day"),
                "inline approval lost the requested command"
        );
        require(ModelGenerationHudState.resolveApproval(requestId, true), "inline approval could not be accepted");
        require(decision.join(), "inline approval decision was not delivered");
        ModelGenerationHudState.dismiss(requestId);
    }

    private static void proveCommandFeedbackAssessment() {
        UUID successId = UUID.randomUUID();
        MinecraftCommandFeedbackTracker.begin(successId, "give @s minecraft:oak_sapling 1");
        MinecraftCommandFeedbackTracker.observe(
                Text.literal("Gave 1 [Oak Sapling] to SpiritXIV"),
                RichChatRowType.COMMAND_OUTPUT
        );
        require(
                "succeeded".equals(MinecraftCommandFeedbackTracker.finish(successId).assessment()),
                "positive command output was not returned to the model"
        );

        UUID failureId = UUID.randomUUID();
        MinecraftCommandFeedbackTracker.begin(failureId, "give 1 minecraft:oak_sapling");
        MinecraftCommandFeedbackTracker.observe(
                Text.literal("Unknown or incomplete command"),
                RichChatRowType.COMMAND_OUTPUT
        );
        require(
                "failed".equals(MinecraftCommandFeedbackTracker.finish(failureId).assessment()),
                "command syntax failure was misclassified as success"
        );
    }

    private static void proveExecutionCorrelation() {
        UUID executionId = UUID.randomUUID();
        CompletableFuture<AutomationExecutionResult> waiter = AutomationExecutionResults.register(executionId);
        Instant now = Instant.now();
        AutomationExecutionResults.publish(new AutomationExecutionResult(
                executionId,
                "success",
                "",
                "complete",
                "movement/navigation/move_relative",
                Map.of("result.ok", true),
                null,
                null,
                now,
                now
        ));
        require(waiter.join().executionId().equals(executionId), "execution result was not correlated");
        require(AutomationExecutionResults.pendingCount() == 0, "execution waiter leaked");

        CompletableFuture<AutomationExecutionResult> timedOut = AutomationExecutionResults
                .register(UUID.randomUUID())
                .orTimeout(1L, TimeUnit.MILLISECONDS);
        try {
            timedOut.join();
        } catch (RuntimeException expected) {
        }
        require(AutomationExecutionResults.pendingCount() == 0, "timed-out execution waiter leaked");
    }

    private static void proveRichChatCommandLinks() {
        String commandLink = "[Set day](/time set day)";
        RichChatModelOutputSanitizer.Result command = RichChatModelOutputSanitizer.sanitize(commandLink);
        require(commandLink.equals(command.text()), "Minecraft command link was removed");
        require(
                RichChatModelFormattingContract.systemPrompt().contains("[label](/example command)"),
                "model formatting contract omitted Minecraft command links"
        );
        require(
                RichChatModelFormattingContract.askPrompt().contains("[Set the time to day](/time set day)"),
                "/ask formatting contract omitted a concrete masked-command example"
        );
        require(
                RichChatModelFormattingContract.automationPrompt().contains("structured tool"),
                "Automation formatting contract did not prioritize tool execution"
        );
        String unsafe = RichChatModelOutputSanitizer.sanitize("[bad](javascript:alert)").text();
        require(!unsafe.contains("javascript:"), "unsupported masked URL scheme survived sanitization");
    }

    private static void proveHonestDisabledHardwareReport() {
        var report = LocalModelHardwarePreflight.scan(ColibriConfiguration.disabled());
        require(report.tier() == ModelHardwareTier.NOT_CONFIGURED, "disabled runtime received a compatibility claim");
        require(report.measuredSequentialReadMbPerSecond() == null, "unmeasured disk speed was invented");
        require(!report.runtimeArchitectureVerified(), "runtime architecture was claimed without validation");
        require(report.runtimeValidationRequired(), "runtime validation requirement was hidden");
    }

    private static void expectFailure(String code, Runnable action) {
        try {
            action.run();
            throw new IllegalStateException("expected capability failure " + code);
        } catch (AutomationCapabilityException expected) {
            require(code.equals(expected.code()), "wrong capability failure: " + expected.code());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
