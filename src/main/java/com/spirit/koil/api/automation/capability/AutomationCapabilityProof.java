package com.spirit.koil.api.automation.capability;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResult;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResults;
import com.spirit.koil.api.automation.runtime.AutomationResultStatus;
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
        require(AutomationCapabilityRegistry.modelTools().stream().noneMatch(tool -> tool.id().contains("ktl")),
                "raw KTL execution was exposed to the model");
        for (String rawInput : java.util.List.of(
                "input.tap", "input.hold", "input.release", "input.release_all", "input.mouse_delta")) {
            AutomationCapabilityDefinition definition = AutomationCapabilityRegistry.definitions().get(rawInput);
            require(definition != null && definition.sideEffects().stream().anyMatch(value ->
                            value.contains("input") || value.contains("orientation")),
                    "bounded raw-input capability is missing side-effect metadata: " + rawInput);
        }
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
        require(AutomationCapabilityRegistry.definitions().containsKey("block.place")
                        && AutomationCapabilityRegistry.definitions().containsKey("block.build_pattern")
                        && AutomationCapabilityRegistry.definitions().containsKey("entity.look_at")
                        && AutomationCapabilityRegistry.definitions().containsKey("entity.interact"),
                "block placement/building or entity orientation/interaction capability was not registered");
        AutomationCapabilityDefinition boat = AutomationCapabilityRegistry.definitions().get("transport.boat_deploy");
        AutomationCapabilityDefinition elytra = AutomationCapabilityRegistry.definitions().get("transport.elytra_flight");
        AutomationCapabilityDefinition surroundings = AutomationCapabilityRegistry.definitions().get("world.inspect_surroundings");
        require(boat != null && elytra != null && boat.confirmationRequired() && elytra.confirmationRequired(),
                "boat or elytra transport was not explicit approval-gated");
        require(surroundings != null && !surroundings.confirmationRequired() && surroundings.sideEffects().isEmpty(),
                "bounded surroundings inspection was not registered as read-only");
        require(AutomationPrimitiveRegistry.contains("cap.build.resolve_pattern_target")
                        && AutomationPrimitiveRegistry.contains("cap.interaction.place_block_target")
                        && AutomationPrimitiveRegistry.contains("cap.world.verify_block_target")
                        && AutomationPrimitiveRegistry.contains("cap.look.verify_target"),
                "verified building primitives were not registered behind the model capability boundary");
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

        JsonObject heldInput = new JsonObject();
        heldInput.addProperty("key", "w");
        heldInput.addProperty("ticks", 80);
        AutomationCapabilityPlan heldInputPlan = AutomationCapabilityRegistry.validateAndCompile(
                "input.hold", heldInput, UUID.randomUUID());
        require(heldInputPlan.request().rawInput().contains("flow/core/hold_input.ktl")
                        && heldInputPlan.request().rawInput().contains("input.key=w")
                        && heldInputPlan.request().rawInput().contains("count.value=80"),
                "raw hold input did not compile through bounded KTL");
        JsonObject releaseInput = new JsonObject();
        releaseInput.addProperty("key", "w");
        require(AutomationCapabilityRegistry.validateAndCompile("input.release", releaseInput, UUID.randomUUID())
                        .request().rawInput().contains("flow/core/release_input.ktl"),
                "raw release input did not compile through KTL cleanup");

        JsonObject allBlocks = new JsonObject();
        allBlocks.addProperty("block", "minecraft:grass_block");
        allBlocks.addProperty("radius", 8);
        allBlocks.addProperty("quantity", "all");
        AutomationCapabilityPlan allBlocksPlan = AutomationCapabilityRegistry.validateAndCompile(
                "block.mine", allBlocks, UUID.randomUUID());
        require(allBlocksPlan.request().rawInput().contains("blocks/core/mine_all_matching.ktl")
                        && allBlocksPlan.request().rawInput().contains("quantity.mode=all"),
                "all-block collection semantics did not compile to the bounded snapshot KTL");
        JsonObject allEntities = new JsonObject();
        allEntities.addProperty("entity", "minecraft:chicken");
        allEntities.addProperty("radius", 16);
        allEntities.addProperty("quantity", "all");
        AutomationCapabilityPlan allEntitiesPlan = AutomationCapabilityRegistry.validateAndCompile(
                "entity.kill", allEntities, UUID.randomUUID());
        require(allEntitiesPlan.request().rawInput().contains("combat/core/kill_all_matching.ktl")
                        && allEntitiesPlan.request().rawInput().contains("quantity.mode=all"),
                "all-entity collection semantics did not compile to the bounded snapshot KTL");

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

        JsonObject look = new JsonObject();
        look.addProperty("entity", "dummmmmmy:target_dummy");
        look.addProperty("turn_speed", "slow");
        look.addProperty("maximum_degrees_per_tick", 2.0D);
        AutomationCapabilityPlan lookPlan = AutomationCapabilityRegistry.validateAndCompile(
                "entity.look_at", look, UUID.randomUUID());
        require(lookPlan.request().rawInput().contains("target.id=dummmmmmy:target_dummy")
                        && lookPlan.request().rawInput().contains("target.kind=entity")
                        && lookPlan.request().rawInput().contains("look.turn_speed=slow")
                        && lookPlan.request().rawInput().contains("look.maximum_degrees_per_tick=2"),
                "modded entity look-at did not compile to exact target resolution");

        JsonObject mount = new JsonObject();
        mount.addProperty("entity", "minecraft:horse");
        AutomationCapabilityPlan mountPlan = AutomationCapabilityRegistry.validateAndCompile(
                "entity.mount", mount, UUID.randomUUID());
        require(mountPlan.request().rawInput().contains("movement/transport/mount_entity.ktl")
                        && mountPlan.request().rawInput().contains("target.id=minecraft:horse")
                        && AutomationPrimitiveRegistry.contains("cap.transport.verify_mounted"),
                "verified rideable-entity mounting was not registered end to end");

        JsonObject boat = new JsonObject();
        boat.addProperty("boat", "minecraft:oak_boat");
        boat.addProperty("craft_if_missing", true);
        boat.addProperty("placement", "water");
        boat.addProperty("search_radius", 6);
        AutomationCapabilityPlan boatPlan = AutomationCapabilityRegistry.validateAndCompile(
                "transport.boat_deploy", boat, UUID.randomUUID());
        require(boatPlan.request().rawInput().contains("movement/transport/boat_deploy_smart.ktl")
                        && boatPlan.request().rawInput().contains("boat.item=minecraft:oak_boat")
                        && boatPlan.request().rawInput().contains("craft.if_missing=true")
                        && boatPlan.request().rawInput().contains("placement.preference=water")
                        && boatPlan.request().rawInput().contains("search.radius=6")
                        && !boatPlan.request().rawInput().contains("target.x=")
                        && AutomationPrimitiveRegistry.contains("cap.transport.resolve_boat_target")
                        && AutomationPrimitiveRegistry.contains("cap.transport.deploy_boat"),
                "coordinate-free boat crafting/deployment did not compile through verified smart transport primitives");

        JsonObject exactBoat = new JsonObject();
        exactBoat.addProperty("boat", "minecraft:oak_boat");
        exactBoat.addProperty("x", 10);
        exactBoat.addProperty("y", 63);
        exactBoat.addProperty("z", 12);
        AutomationCapabilityPlan exactBoatPlan = AutomationCapabilityRegistry.validateAndCompile(
                "transport.boat_deploy", exactBoat, UUID.randomUUID());
        require(exactBoatPlan.request().rawInput().contains("target.x=10")
                        && exactBoatPlan.request().rawInput().contains("target.y=63")
                        && exactBoatPlan.request().rawInput().contains("target.z=12"),
                "optional exact boat coordinates were not preserved");
        JsonObject partialBoat = new JsonObject();
        partialBoat.addProperty("boat", "minecraft:oak_boat");
        partialBoat.addProperty("x", 10);
        expectFailure("missing_coordinate", () -> AutomationCapabilityRegistry.validateAndCompile(
                "transport.boat_deploy", partialBoat, UUID.randomUUID()));

        JsonObject surroundings = new JsonObject();
        surroundings.addProperty("radius", 5);
        surroundings.addProperty("focus", "boat");
        AutomationCapabilityPlan surroundingsPlan = AutomationCapabilityRegistry.validateAndCompile(
                "world.inspect_surroundings", surroundings, UUID.randomUUID());
        require(surroundingsPlan.request().rawInput().contains("world/core/inspect_surroundings.ktl")
                        && surroundingsPlan.request().rawInput().contains("search.radius=5")
                        && surroundingsPlan.request().rawInput().contains("inspect.focus=boat")
                        && AutomationPrimitiveRegistry.contains("cap.world.inspect_surroundings"),
                "bounded surroundings inspection did not compile through its read-only primitive");

        JsonObject elytra = new JsonObject();
        elytra.addProperty("x", 120);
        elytra.addProperty("y", 80);
        elytra.addProperty("z", -40);
        AutomationCapabilityPlan elytraPlan = AutomationCapabilityRegistry.validateAndCompile(
                "transport.elytra_flight", elytra, UUID.randomUUID());
        require(elytraPlan.request().rawInput().contains("movement/transport/elytra_flight.ktl")
                        && elytraPlan.request().rawInput().contains("elytra.item=minecraft:elytra")
                        && elytraPlan.request().rawInput().contains("rocket.item=minecraft:firework_rocket")
                        && elytraPlan.request().rawInput().contains("flight.max_rockets=4")
                        && AutomationPrimitiveRegistry.contains("cap.transport.fly_elytra"),
                "bounded elytra execution did not compile through verified transport primitives");

        JsonObject smartMovement = new JsonObject();
        smartMovement.addProperty("x", 30);
        smartMovement.addProperty("z", 40);
        smartMovement.addProperty("allow_swim", true);
        smartMovement.addProperty("allow_parkour", true);
        AutomationCapabilityPlan smartMovementPlan = AutomationCapabilityRegistry.validateAndCompile(
                "movement.move_to", smartMovement, UUID.randomUUID());
        require(smartMovementPlan.request().rawInput().contains("movement.policy=human_smart")
                        && smartMovementPlan.request().rawInput().contains("movement.allow_swim=true")
                        && smartMovementPlan.request().rawInput().contains("movement.allow_parkour=true")
                        && smartMovementPlan.request().rawInput().contains("movement.allow_break_blocks=false"),
                "smart movement options were not compiled with safe mutation defaults");

        JsonObject below = new JsonObject();
        below.addProperty("block", "minecraft:stone");
        below.addProperty("selector", "below");
        AutomationCapabilityPlan belowPlan = AutomationCapabilityRegistry.validateAndCompile(
                "block.mine", below, UUID.randomUUID());
        require(belowPlan.request().rawInput().contains("blocks/core/mine_relative_block.ktl")
                        && belowPlan.request().rawInput().contains("target.selector=below"),
                "relative block mining did not compile to the exact no-navigation template");

        JsonObject shortBlockName = new JsonObject();
        shortBlockName.addProperty("block", "stone");
        shortBlockName.addProperty("selector", "below");
        AutomationCapabilityPlan shortBlockPlan = AutomationCapabilityRegistry.validateAndCompile(
                "block.mine", shortBlockName, UUID.randomUUID());
        require(shortBlockPlan.request().rawInput().contains("target.id=minecraft:stone"),
                "an unnamespaced vanilla block was not resolved before execution");

        JsonObject build = new JsonObject();
        build.addProperty("block", "minecraft:oak_planks");
        build.addProperty("shape", "perimeter");
        build.addProperty("length", 4);
        build.addProperty("width", 4);
        AutomationCapabilityPlan buildPlan = AutomationCapabilityRegistry.validateAndCompile(
                "block.build_pattern", build, UUID.randomUUID());
        require(buildPlan.request().rawInput().contains("pattern.id=perimeter")
                        && buildPlan.request().rawInput().contains("count.value=12")
                        && buildPlan.request().rawInput().contains("block.id=minecraft:oak_planks"),
                "square/perimeter build did not compile to twelve verified placements");

        JsonObject exactPlace = new JsonObject();
        exactPlace.addProperty("block", "minecraft:stone");
        exactPlace.addProperty("x", 12);
        exactPlace.addProperty("y", 64);
        exactPlace.addProperty("z", -8);
        AutomationCapabilityPlan exactPlacePlan = AutomationCapabilityRegistry.validateAndCompile(
                "block.place", exactPlace, UUID.randomUUID());
        require(exactPlacePlan.request().rawInput().contains("blocks/core/place_block_at.ktl")
                        && exactPlacePlan.request().rawInput().contains("target.x=12")
                        && exactPlacePlan.request().rawInput().contains("target.y=64")
                        && exactPlacePlan.request().rawInput().contains("target.z=-8")
                        && exactPlacePlan.request().rawInput().contains("block.id=minecraft:stone"),
                "coordinate-targeted block placement did not preserve the exact target and block ID");

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
        require(!AutomationModeController.isUnrestrictedMode(), "Automation Mode did not start with standard approval");
        require(!AutomationModeController.isDeepThinkingEnabled(),
                "Automation Mode did not start with Deep Thinking disabled");
        AutomationModeController.setDeepThinkingEnabled(true);
        require(AutomationModeController.isDeepThinkingEnabled(),
                "Deep Thinking could not be enabled for the session");
        AutomationModeController.enableUnrestrictedMode();
        require(AutomationModeController.isUnrestrictedMode(), "session Unrestricted policy did not activate");
        AutomationModeController.setAutomationMode(false);
        require(!AutomationModeController.isUnrestrictedMode(), "session Unrestricted policy survived Automation Mode shutdown");
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

        AutomationExecutionResult unknownTarget = new AutomationExecutionResult(
                UUID.randomUUID(), "failed", "unknown_block_id", "unknown block",
                "blocks/core/mine_relative_block",
                Map.of("result.retryable", false, "result.requested_target_id", "minecraft:titanium"),
                null, null, now, now
        );
        require(!AutomationToolCoordinator.isRetryableResult(unknownTarget, "failed", "unknown_block_id"),
                "unknown exact block ids were still eligible for substitute-target retries");

        AutomationExecutionResult partialMovement = new AutomationExecutionResult(
                UUID.randomUUID(), "partial", "path_blocked", "route obstructed",
                "movement/navigation/move_to_position",
                Map.ofEntries(
                        Map.entry("result.action_id", "movement.move_to"),
                        Map.entry("result.requested.target_x", 100.0D),
                        Map.entry("result.requested.target_y", 64.0D),
                        Map.entry("result.requested.target_z", 0.0D),
                        Map.entry("result.before.x", 0.0D),
                        Map.entry("result.before.y", 64.0D),
                        Map.entry("result.before.z", 0.0D),
                        Map.entry("result.after.x", 42.5D),
                        Map.entry("result.after.y", 64.0D),
                        Map.entry("result.after.z", 0.0D),
                        Map.entry("result.delta.x", 42.5D),
                        Map.entry("result.distance_remaining", 57.5D),
                        Map.entry("result.objective_reached", false),
                        Map.entry("result.state_changed", true),
                        Map.entry("result.continue_recommended", true),
                        Map.entry("result.replan_recommended", true)),
                null, null, now, now.plusMillis(250)
        );
        var structured = partialMovement.structured();
        require(structured.status() == AutomationResultStatus.PARTIAL
                        && !structured.objectiveReached()
                        && structured.stateChanged()
                        && structured.requested().containsKey("target_x")
                        && structured.before().containsKey("x")
                        && structured.after().containsKey("x")
                        && structured.delta().containsKey("x")
                        && structured.metrics().containsKey("distance_remaining"),
                "structured partial movement result lost its requested/before/after/delta evidence");

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
                RichChatModelFormattingContract.systemPrompt().contains("[label](/command arguments)"),
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
