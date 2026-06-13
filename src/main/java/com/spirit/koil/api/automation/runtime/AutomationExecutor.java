package com.spirit.koil.api.automation.runtime;

import com.spirit.koil.api.automation.AutomationReporter;
import com.spirit.koil.api.automation.AutomationRequest;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class AutomationExecutor {
    private static final AtomicInteger FRAME_SEQUENCE = new AtomicInteger();
    private static final double ENTITY_LOOKUP_RADIUS = 96.0D;
    private static final double ENTITY_LOOKUP_FALLBACK_RADIUS = 256.0D;
    private static final double NAV_PROGRESS_EPSILON = 0.03D;
    private static final int NAV_STUCK_TICKS = 22;
    private static final int NAV_ESCAPE_TICKS = 10;
    private static final int NAV_RECOVERY_LIMIT = 18;
    private static final int NAV_PERSISTENT_RECOVERY_LIMIT = 28;
    private static final int NAV_TRACE_WINDOW = 12;
    private static final int NAV_WAYPOINT_RADIUS = 8;
    private static final int NAV_ROUTE_RADIUS = 24;
    private static final int NAV_ROUTE_MAX_EXPANSIONS = 900;
    private static final int NAV_STEER_LOCK_TICKS = 10;
    private static final int NAV_WAYPOINT_VERTICAL_UP = 3;
    private static final int NAV_WAYPOINT_VERTICAL_DOWN = 5;
    private static final int NAV_DECISION_INTERVAL_TICKS = 8;
    private static final int NAV_AVOID_MEMORY_TICKS = 80;
    private static final int NAV_ROUTE_LOOKAHEAD_SHORT = 3;
    private static final int NAV_ROUTE_LOOKAHEAD_LONG = 5;
    private static final double NAV_POCKET_LIMIT = 0.78D;
    private static final double NAV_CORRIDOR_POCKET_LIMIT = 0.52D;
    private static final double NAV_HUMAN_STEERING_BLEND = 0.74D;
    private static final double NAV_HUMAN_SPRINT_STEERING_BLEND = 0.82D;
    private static final double NAV_MIN_COMPLETION_DISPLACEMENT = 0.35D;
    private static final double AUTOMATION_RAY_DISTANCE = 6.0D;
    private static final double AUTOMATION_RAY_STEP = 0.08D;
    private static final double NAV_PRECISE_STOP_DISTANCE = 0.28D;
    private static final double NAV_PRECISE_DIRECT_DISTANCE = 2.25D;
    private static final double NAV_PRECISE_SLOW_DISTANCE = 0.55D;
    private static final float NAV_LOOK_MAX_YAW_STEP = 52.0F;
    private static final float LOOK_MAX_YAW_STEP = 14.0F;
    private static final float LOOK_MAX_PITCH_STEP = 9.0F;
    private static final float LOOK_SNAP_EPSILON = 0.35F;
    private static final double PARKOUR_SINGLE_MAX_HORIZONTAL = 2.85D;
    private static final double PARKOUR_SPRINT_MAX_HORIZONTAL = 4.35D;
    private static final double PARKOUR_ADVANCED_MAX_HORIZONTAL = 5.05D;
    private static final double PARKOUR_ALIGN_DISTANCE = 0.34D;
    private static final double PARKOUR_LANDING_DISTANCE = 0.85D;
    private static final int PARKOUR_ALIGN_TICKS = 3;
    private static final int PARKOUR_SPRINT_CHARGE_TICKS = 5;
    private static final int PARKOUR_SINGLE_CHARGE_TICKS = 1;
    private static final int PARKOUR_AIR_TIMEOUT_TICKS = 34;
    private static final int PARKOUR_APPROACH_TIMEOUT_TICKS = 90;

    private final KtlCompilerService compilerService;
    private final Deque<ActiveExecution> executionStack = new ArrayDeque<>();
    private final Map<String, HeldInput> heldInputs = new LinkedHashMap<>();
    private final Map<String, CachedEntityRef> cachedEntities = new LinkedHashMap<>();

    public AutomationExecutor(KtlCompilerService compilerService) {
        this.compilerService = compilerService;
    }

    public void execute(ExecutionPlan plan, InterpretationResult interpretationResult) {
        AutomationRuntimeStatus.running(interpretationResult.selectedTemplateId());
        startExecution(plan, interpretationResult, null, null, null);
    }

    public boolean hasActiveExecutions() {
        return !this.executionStack.isEmpty();
    }

    public void cancel(String reason) {
        boolean hadActiveExecution = !this.executionStack.isEmpty();
        String detail = reason == null || reason.isBlank() ? "canceled" : reason;
        this.executionStack.clear();
        releaseAllInputs();
        this.cachedEntities.clear();
        AutomationRuntimeStatus.canceled(detail);
        AutomationCliViewModel.activeState("failed", "", detail);
        AutomationReporter.fail("[fail]", "automation canceled: " + detail);
        if (hadActiveExecution) {
            AutomationCliViewModel.offerFeedbackPrompt("task stopped: " + detail);
        }
    }

    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        ClientWorld world = client == null ? null : client.world;

        updateHeldInputs();
        updateTapInputs();
        updateMouseLook(player);

        if (this.executionStack.isEmpty()) {
            releaseAllInputs();
            AutomationRuntimeStatus.idle("no active task");
            AutomationCliViewModel.activeState("idle", "", "no active task");
            return;
        }

        ActiveExecution current = this.executionStack.peek();
        AutomationCliViewModel.activeState("thinking", current.frameId, "tick");

        updateActiveConsumption(current);
        updateActiveAttack(current);
        updateActiveMining(current);
        updateRelativeMovement(current, player);
        updateTargetMovement(current, player, world);
        updateWorldDiagnostics(current, client, player, world);
        updateActiveContainerTransfer(current, player);

        if (Boolean.TRUE.equals(current.state.get("consume.failed"))) {
            String reason = stringParam(current.state, "consume.failure_reason", "consume_failed");
            AutomationReporter.block("[block]", "consume -> " + reason);
            AutomationCliViewModel.blocked(current.frameId, "consume", reason);
            current.state.remove("consume.failed");
            current.state.remove("consume.failure_reason");
            finishCurrentExecution("blocked");
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("attack.failed"))) {
            String reason = stringParam(current.state, "attack.failure_reason", "attack_failed");
            AutomationReporter.block("[block]", "attack -> " + reason);
            AutomationCliViewModel.blocked(current.frameId, "attack", reason);
            current.state.remove("attack.failed");
            current.state.remove("attack.failure_reason");
            finishCurrentExecution("blocked");
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("mine.failed"))) {
            String reason = stringParam(current.state, "mine.failure_reason", "mine_failed");
            AutomationReporter.block("[block]", "mine -> " + reason);
            AutomationCliViewModel.blocked(current.frameId, "mine", reason);
            current.state.remove("mine.failed");
            current.state.remove("mine.failure_reason");
            finishCurrentExecution("blocked");
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("container.transfer.failed"))) {
            String reason = stringParam(current.state, "container.transfer.failure_reason", "transfer_failed");
            AutomationReporter.block("[block]", "container -> " + reason);
            AutomationCliViewModel.blocked(current.frameId, "container.transfer", reason);
            current.state.remove("container.transfer.failed");
            current.state.remove("container.transfer.failure_reason");
            finishCurrentExecution("blocked");
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("move.failed"))) {
            String reason = stringParam(current.state, "move.failure_reason", "movement_failed");
            AutomationReporter.block("[block]", "movement -> " + reason);
            AutomationCliViewModel.blocked(current.frameId, "movement", reason);
            current.state.remove("move.failed");
            current.state.remove("move.failure_reason");
            finishCurrentExecution("blocked");
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("consume.active"))) {
            AutomationCliViewModel.activeState("using", current.frameId, stringParam(current.state, "consume.phase", "consume"));
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("attack.active"))) {
            AutomationCliViewModel.activeState("attacking", current.frameId, stringParam(current.state, "attack.phase", "attack"));
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("mine.active"))) {
            AutomationCliViewModel.activeState("mining", current.frameId, stringParam(current.state, "mine.phase", "mine"));
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("container.transfer.active"))) {
            AutomationCliViewModel.activeState("using", current.frameId, stringParam(current.state, "container.transfer.phase", "container"));
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("move.relative.active"))) {
            AutomationCliViewModel.activeState("moving", current.frameId, stringParam(current.state, "move.relative.direction", "relative"));
            return;
        }

        if (Boolean.TRUE.equals(current.state.get("move.target.active"))) {
            AutomationCliViewModel.activeState("moving", current.frameId, stringParam(current.state, "move.nav.phase", "target"));
            return;
        }

        if (intState(current.state, "wait.ticks", 0) > 0) {
            int remaining = intState(current.state, "wait.ticks", 0) - 1;
            current.state.put("wait.ticks", remaining);
            AutomationReporter.mem("wait.ticks", remaining);
            if (current.activeNodeId != null) {
                AutomationCliViewModel.runtimeFlow(current.frameId, current.activeNodeId, "wait.phase", "flow_phase", "[wait]", "wait.phase", "countdown");
                AutomationCliViewModel.runtimeFlow(current.frameId, current.activeNodeId, "wait.remaining", "flow_metric", "[info]", "wait.remaining_ticks", remaining);
            }
            AutomationCliViewModel.activeState("waiting", current.frameId, remaining + " ticks");
            return;
        }

        if (current.index >= current.steps.size()) {
            finishCurrentExecution("success");
            return;
        }

        KtlCompilerService.CompiledStep step = current.steps.get(current.index);
        String label = step.label().isBlank() ? step.type() : step.label();
        String nodeId = label + "#" + current.index;

        AutomationReporter.mem("current.step", label);
        String source = step.sourcePath().isBlank() ? "" : "  source.file=" + step.sourcePath() + "  source.line=" + step.sourceLine();
        AutomationCliViewModel.enterNode(current.frameId, nodeId, label, step.type() + (step.action().isBlank() ? "" : " -> " + step.action()) + source);
        current.activeNodeId = nodeId;
        current.activeNodeLabel = label;
        current.activeAction = step.action();

        switch (step.type()) {
            case "run_primitive" -> {
                AutomationReporter.run("[run ]", label + " -> " + step.action());
                Map<String, Object> beforeState = new LinkedHashMap<>(current.state);
                PrimitiveRunResult primitiveResult = runPrimitive(step.action(), step.params(), current.state);
                AutomationCliViewModel.primitiveCall(current.frameId, nodeId, step.action(), primitiveResult.resolvedParams());
                emitPrimitiveStateDiff(current.frameId, nodeId, beforeState, current.state);
                AutomationCliViewModel.primitiveResult(current.frameId, nodeId, step.action(), primitiveResult.success(), stringParam(current.state, "result.action", ""));
                if (!primitiveResult.success()) {
                    AutomationReporter.block("[block]", label + " -> " + step.action());
                    AutomationCliViewModel.blocked(current.frameId, nodeId, step.action());
                    finishCurrentExecution("blocked");
                    return;
                }
                AutomationReporter.ok("[ok  ]", label);
                if (!holdsRuntimePhase(current.state)) {
                    current.activeNodeId = null;
                    current.activeNodeLabel = null;
                    current.activeAction = null;
                }
                current.index++;
            }
            case "branch" -> {
                String conditionKey = resolveString(step.condition(), current.state);
                boolean condition = evaluateCondition(conditionKey, current.state);
                AutomationReporter.pipeline("[branch]", conditionKey + " = " + condition);
                String delegatedInput = condition ? step.thenInput() : step.elseInput();
                AutomationReporter.info("[info]", "path = " + (condition ? "THEN" : "ELSE"));
                AutomationCliViewModel.branch(current.frameId, nodeId, conditionKey, condition ? "THEN" : "ELSE");
                AutomationCliViewModel.branchCandidates(current.frameId, nodeId, step.thenInput(), step.elseInput(), condition ? "THEN" : "ELSE");

                current.index++;

                if (delegatedInput != null && !delegatedInput.isBlank()) {
                    String resolved = resolveString(delegatedInput, current.state);
                    InterpretationResult child = this.compilerService.interpret(new AutomationRequest(resolved, false, resolved.endsWith(".ktl") || resolved.contains(".ktl ")));
                    String childFrameId = startExecution(child.plan(), child, current.frameId, label, current.state);
                    AutomationCliViewModel.delegate(current.frameId, nodeId, childFrameId, resolved);
                    return;
                }

                AutomationReporter.ok("[ok  ]", label);
            }
            case "goto" -> {
                String scope = step.scope() == null || step.scope().isBlank() ? "local" : step.scope();
                if ("parent".equalsIgnoreCase(scope)) {
                    if (this.executionStack.size() < 2) {
                        throw new IllegalStateException("goto scope=parent used without a parent frame");
                    }
                    ActiveExecution child = this.executionStack.pop();
                    ActiveExecution parent = this.executionStack.peek();
                    parent.state.putAll(child.state);
                    parent.index = gotoIndex(parent.steps, step.gotoLabel());
                    AutomationCliViewModel.returned(child.frameId, parent.frameId, "goto_parent", child.resumeLabel);
                    AutomationReporter.ok("[goto ]", "parent -> " + step.gotoLabel());
                    return;
                }
                current.index = gotoIndex(current.steps, step.gotoLabel());
            }
            case "delegate" -> {
                String resolved = resolveString(step.delegate(), current.state);
                AutomationReporter.run("[delegate]", label + " -> " + resolved);
                current.index++;
                InterpretationResult child = this.compilerService.interpret(new AutomationRequest(resolved, false, resolved.endsWith(".ktl") || resolved.contains(".ktl ")));
                String childFrameId = startExecution(child.plan(), child, current.frameId, label, current.state);
                AutomationCliViewModel.delegate(current.frameId, nodeId, childFrameId, resolved);
                return;
            }
            case "return" -> finishCurrentExecution("success");
            default -> throw new IllegalStateException("Unsupported step type: " + step.type());
        }
    }

    private String startExecution(ExecutionPlan plan, InterpretationResult interpretationResult, String parentFrameId, String resumeLabel, Map<String, Object> inheritedState) {
        String frameId = String.format("frame-%04d", FRAME_SEQUENCE.incrementAndGet());
        Map<String, Object> state = new LinkedHashMap<>();
        if (inheritedState != null) {
            state.putAll(inheritedState);
        }
        state.putAll(plan.params());

        AutomationReporter.run("[run ]", "objective -> " + interpretationResult.selectedTemplateId());
        AutomationReporter.mem("active.template", interpretationResult.selectedTemplateId());
        AutomationCliViewModel.beginFrame(frameId, parentFrameId, interpretationResult.selectedTemplateId(), interpretationResult.semanticOperationId());
        AutomationCliViewModel.frameContext(frameId, parentFrameId, resumeLabel, interpretationResult.semanticOperationId(), interpretationResult.boundParams(), interpretationResult.diagnostics());

        this.executionStack.push(new ActiveExecution(frameId, parentFrameId, resumeLabel, interpretationResult, plan.template().steps(), state));
        return frameId;
    }

    private void finishCurrentExecution(String status) {
        if (this.executionStack.isEmpty()) {
            return;
        }

        ActiveExecution finished = this.executionStack.pop();

        if (finished.state.containsKey("state.counter") && finished.state.containsKey("count.value")) {
            AutomationReporter.info("[info]", "final.progress = [ " + finished.state.get("state.counter") + " / " + finished.state.get("count.value") + " ]");
        }

        AutomationReporter.mem("result.frame", finished.frameId);
        AutomationReporter.mem("result.template", finished.interpretationResult.selectedTemplateId());
        AutomationReporter.mem("result.state", status);
        for (Map.Entry<String, Object> entry : finished.state.entrySet()) {
            if (entry.getKey().startsWith("result.")) {
                AutomationCliViewModel.frameResult(finished.frameId, entry.getKey(), entry.getValue());
            }
        }
        AutomationCliViewModel.frameStateSnapshot(finished.frameId, finished.state, "final");
        AutomationCliViewModel.completeFrame(finished.frameId, status);
        AutomationReporter.done("[done]", "state = " + status);

        if (!this.executionStack.isEmpty()) {
            ActiveExecution parent = this.executionStack.peek();
            parent.state.putAll(finished.state);
            AutomationCliViewModel.returned(finished.frameId, parent.frameId, status, finished.resumeLabel);
            if (finished.resumeLabel != null && !finished.resumeLabel.isBlank()) {
                AutomationReporter.ok("[return]", "return_to = " + finished.resumeLabel);
            }
            if (!"success".equals(status)) {
                parent.state.put("child.failed", true);
                parent.state.put("child.failure_status", status);
                parent.state.put("child.failure_frame", finished.frameId);
                parent.state.put("child.failure_template", finished.interpretationResult.selectedTemplateId());
                finishCurrentExecution(status);
            }
        } else {
            runCompletionCommand(status, finished.state);
            AutomationRuntimeStatus.idle(status);
            AutomationCliViewModel.offerFeedbackPrompt();
        }
    }

    private void runCompletionCommand(String status, Map<String, Object> state) {
        if (state == null) {
            return;
        }
        String key = "success".equalsIgnoreCase(status) ? "completion.success.command" : "completion.failure.command";
        String command = stringParam(state, key, "");
        if (command.isBlank()) {
            return;
        }
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isBlank()) {
            return;
        }
        AutomationReporter.run("[cmd ]", key + " -> /" + command);
        AutomationRouter.sendRawCommand(command);
    }

    private boolean holdsRuntimePhase(Map<String, Object> state) {
        return Boolean.TRUE.equals(state.get("consume.active"))
                || Boolean.TRUE.equals(state.get("attack.active"))
                || Boolean.TRUE.equals(state.get("mine.active"))
                || Boolean.TRUE.equals(state.get("move.relative.active"))
                || Boolean.TRUE.equals(state.get("move.target.active"))
                || Boolean.TRUE.equals(state.get("container.transfer.active"))
                || intState(state, "wait.ticks", 0) > 0;
    }

    private PrimitiveRunResult runPrimitive(String action, Map<String, Object> params, Map<String, Object> state) {
        Map<String, Object> resolvedParams = resolveParams(params, state);
        AutomationReporter.info("[info]", "primitive = " + action);
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        ClientWorld world = client == null ? null : client.world;
        ClientPlayerInteractionManager interactionManager = client == null ? null : client.interactionManager;

        boolean success = switch (action) {
            case "cap.command.execute_raw" -> {
                String rawCommand = stringParam(resolvedParams, "raw.command", "");
                if (rawCommand.isBlank()) {
                    yield false;
                }
                AutomationRouter.sendRawCommand(rawCommand);
                yield true;
            }
            case "cap.report.say" -> {
                String message = stringParam(resolvedParams, "message", "");
                if (message.isBlank()) {
                    yield false;
                }
                AutomationRouter.sendChatMessage(message);
                yield true;
            }
            case "cap.report.status_line" -> {
                String message = stringParam(resolvedParams, "message", "");
                if (message.isBlank()) {
                    yield false;
                }
                AutomationReporter.info("[stat]", message);
                yield true;
            }
            case "cap.report.error_line" -> {
                String message = stringParam(resolvedParams, "message", "");
                if (message.isBlank()) {
                    yield false;
                }
                AutomationReporter.info("[fail]", message);
                yield true;
            }
            case "cap.state.write_memory" -> {
                for (Map.Entry<String, Object> entry : resolvedParams.entrySet()) {
                    state.put(entry.getKey(), entry.getValue());
                    AutomationReporter.mem(entry.getKey(), entry.getValue());
                }
                yield true;
            }
            case "cap.state.remove_memory" -> {
                String key = stringParam(resolvedParams, "state.key", "");
                if (key.isBlank()) {
                    yield false;
                }
                state.remove(key);
                AutomationReporter.mem(key, null);
                yield true;
            }
            case "cap.state.copy_value" -> {
                String fromKey = stringParam(resolvedParams, "from.key", "");
                String toKey = stringParam(resolvedParams, "to.key", "");
                if (fromKey.isBlank() || toKey.isBlank() || !state.containsKey(fromKey)) {
                    yield false;
                }
                Object value = state.get(fromKey);
                state.put(toKey, value);
                AutomationReporter.mem(toKey, value);
                yield true;
            }
            case "cap.state.increment_counter" -> {
                String key = stringParam(resolvedParams, "state.key", "state.counter");
                int current = intState(state, key, 0);
                int next = current + intParam(resolvedParams, "delta", 1);
                state.put(key, next);
                AutomationReporter.mem(key, next);
                if ("state.counter".equals(key) && state.containsKey("count.value")) {
                    AutomationReporter.info("[prog]", "[ " + next + " / " + state.get("count.value") + " ]");
                }
                yield true;
            }
            case "cap.state.capture_player_position" -> {
                if (player == null) {
                    yield false;
                }
                String prefix = stringParam(resolvedParams, "state.prefix", "player.position");
                state.put(prefix + ".x", player.getX());
                state.put(prefix + ".y", player.getY());
                state.put(prefix + ".z", player.getZ());
                AutomationReporter.mem(prefix + ".x", player.getX());
                AutomationReporter.mem(prefix + ".y", player.getY());
                AutomationReporter.mem(prefix + ".z", player.getZ());
                yield true;
            }
            case "cap.state.decrement_counter" -> {
                String key = stringParam(resolvedParams, "state.key", "state.counter");
                int current = intState(state, key, 0);
                int next = current - intParam(resolvedParams, "delta", 1);
                state.put(key, next);
                AutomationReporter.mem(key, next);
                yield true;
            }
            case "cap.state.set_counter" -> {
                String key = stringParam(resolvedParams, "state.key", "state.counter");
                int value = intParam(resolvedParams, "value", 0);
                state.put(key, value);
                AutomationReporter.mem(key, value);
                yield true;
            }
            case "cap.state.read_stat" -> {
                if (player == null) {
                    yield false;
                }
                String statRef = stringParam(resolvedParams, "stat.ref", "");
                String resultKey = stringParam(resolvedParams, "result.key", "result.value");
                Object value = readStatValue(player, statRef);
                if (value == null) {
                    yield false;
                }
                state.put(resultKey, value);
                AutomationReporter.mem(resultKey, value);
                yield true;
            }

            case "cap.input.press_key" -> {
                String keyId = stringParam(resolvedParams, "input.key", "");
                if (keyId.isBlank()) {
                    yield false;
                }
                setHeldInput(keyId, true);
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }
            case "cap.input.release_key" -> {
                String keyId = stringParam(resolvedParams, "input.key", "");
                if (keyId.isBlank()) {
                    yield false;
                }
                setHeldInput(keyId, false);
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }
            case "cap.input.tap_key" -> {
                String keyId = stringParam(resolvedParams, "input.key", "");
                int ticks = Math.max(1, intParam(resolvedParams, "count.value", 2));
                if (keyId.isBlank()) {
                    yield false;
                }
                tapInput(keyId, ticks);
                performImmediateTapAction(keyId, client, player);
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }
            case "cap.input.release_all" -> {
                releaseAllInputs();
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }
            case "cap.movement.release_all" -> {
                releaseMovementInputs();
                writeMovementResult(state, true, "success", "movement_inputs_released");
                yield true;
            }
            case "cap.movement.set_forward" -> {
                boolean pressed = booleanParam(resolvedParams, "pressed", true);
                setHeldInput("forward", pressed);
                writeMovementResult(state, true, pressed ? "running" : "success", "forward_" + (pressed ? "held" : "released"));
                yield true;
            }
            case "cap.movement.set_backward" -> {
                boolean pressed = booleanParam(resolvedParams, "pressed", true);
                setHeldInput("back", pressed);
                writeMovementResult(state, true, pressed ? "running" : "success", "backward_" + (pressed ? "held" : "released"));
                yield true;
            }
            case "cap.movement.set_left_strafe" -> {
                boolean pressed = booleanParam(resolvedParams, "pressed", true);
                setHeldInput("left", pressed);
                writeMovementResult(state, true, pressed ? "running" : "success", "left_strafe_" + (pressed ? "held" : "released"));
                yield true;
            }
            case "cap.movement.set_right_strafe" -> {
                boolean pressed = booleanParam(resolvedParams, "pressed", true);
                setHeldInput("right", pressed);
                writeMovementResult(state, true, pressed ? "running" : "success", "right_strafe_" + (pressed ? "held" : "released"));
                yield true;
            }
            case "cap.movement.set_sprint" -> {
                boolean pressed = booleanParam(resolvedParams, "pressed", true);
                setHeldInput("sprint", pressed);
                if (player != null) {
                    player.setSprinting(pressed);
                }
                writeMovementResult(state, true, pressed ? "running" : "success", "sprint_" + (pressed ? "held" : "released"));
                yield true;
            }
            case "cap.movement.set_sneak" -> {
                boolean pressed = booleanParam(resolvedParams, "pressed", true);
                setHeldInput("sneak", pressed);
                writeMovementResult(state, true, pressed ? "running" : "success", "sneak_" + (pressed ? "held" : "released"));
                yield true;
            }
            case "cap.movement.set_jump" -> {
                boolean pressed = booleanParam(resolvedParams, "pressed", true);
                setHeldInput("jump", pressed);
                writeMovementResult(state, true, pressed ? "running" : "success", "jump_" + (pressed ? "held" : "released"));
                yield true;
            }
            case "cap.movement.timed_jump" -> {
                int ticks = Math.max(1, intParam(resolvedParams, "ticks", 4));
                tapInput("jump", ticks);
                if (player != null && player.isOnGround()) {
                    player.jump();
                }
                writeMovementResult(state, true, "running", "timed_jump");
                yield true;
            }
            case "cap.input.mouse_delta" -> {
                if (player == null) {
                    yield false;
                }
                float yawDelta = (float) doubleParam(resolvedParams, "yaw.delta", 0.0D);
                float pitchDelta = (float) doubleParam(resolvedParams, "pitch.delta", 0.0D);
                queueMouseDelta(yawDelta, pitchDelta);
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }

            case "cap.inventory.open_inventory_screen" -> {
                if (client == null || player == null) {
                    yield false;
                }
                if (client.currentScreen != null) {
                    yield true;
                }
                tapInput("inventory", 2);
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }
            case "cap.container.find_item_in_open_screen" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(resolvedParams, "item.id", "");
                String resultKey = stringParam(resolvedParams, "result.key", "result.container.slot");
                if (itemId.isBlank()) {
                    yield false;
                }
                int slotIndex = findMatchingOpenScreenSlot(player, itemId, Boolean.TRUE.equals(resolvedParams.get("container.only")));
                state.put(resultKey, slotIndex);
                state.put("result.found", slotIndex >= 0);
                AutomationReporter.mem(resultKey, slotIndex);
                AutomationReporter.mem("result.found", slotIndex >= 0);
                yield slotIndex >= 0;
            }
            case "cap.container.quick_move_slot" -> {
                if (player == null || interactionManager == null) {
                    yield false;
                }
                int slotIndex = intParam(resolvedParams, "slot.index", -1);
                if (slotIndex < 0 || slotIndex >= player.currentScreenHandler.slots.size()) {
                    yield false;
                }
                yield startContainerTransfer(interactionManager, player, slotIndex, resolvedParams, state, "take");
            }
            case "cap.container.quick_move_selected_hotbar" -> {
                if (player == null || interactionManager == null) {
                    yield false;
                }
                int selectedSlot = openScreenHotbarSlot(player);
                if (selectedSlot < 0 || selectedSlot >= player.currentScreenHandler.slots.size()) {
                    yield false;
                }
                yield startContainerTransfer(interactionManager, player, selectedSlot, resolvedParams, state, "store");
            }

            case "cap.inventory.count_item" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(resolvedParams, "item.id", "");
                if (itemId.isBlank()) {
                    yield false;
                }
                int count = countInventory(player.getInventory(), itemId);
                String resultKey = stringParam(resolvedParams, "result.key", "result.count");
                state.put(resultKey, count);
                AutomationReporter.mem(resultKey, count);
                yield true;
            }
            case "cap.inventory.has_item" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(resolvedParams, "item.id", "");
                if (itemId.isBlank()) {
                    yield false;
                }
                boolean hasItem = countInventory(player.getInventory(), itemId) > 0;
                String resultKey = stringParam(resolvedParams, "result.key", "result.present");
                state.put(resultKey, hasItem);
                AutomationReporter.mem(resultKey, hasItem);
                yield true;
            }
            case "cap.inventory.require_count" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(resolvedParams, "item.id", "");
                if (itemId.isBlank()) {
                    yield false;
                }
                int count = countInventory(player.getInventory(), itemId);
                int threshold = intParam(resolvedParams, "count.value", 1);
                String op = stringParam(resolvedParams, "condition.op", "gte");
                boolean matched = compareNumbers(count, threshold, op);
                String resultKey = stringParam(resolvedParams, "result.key", "result.inventory_ok");
                state.put(resultKey, matched);
                state.put("result.action", matched ? "SUCCESS" : "FAIL");
                AutomationReporter.mem(resultKey, matched);
                AutomationReporter.mem("result.action", matched ? "SUCCESS" : "FAIL");
                yield matched;
            }
            case "cap.inventory.consume_item" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(resolvedParams, "item.id", "");
                int amount = Math.max(1, intParam(resolvedParams, "count.value", 1));
                if (itemId.isBlank()) {
                    yield false;
                }
                boolean removed = removeInventoryItems(player.getInventory(), itemId, amount);
                if (!removed) {
                    yield false;
                }
                String resultKey = stringParam(resolvedParams, "result.key", "result.consumed");
                state.put(resultKey, amount);
                AutomationReporter.mem(resultKey, amount);
                yield true;
            }
            case "cap.inventory.select_hotbar_item" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(resolvedParams, "item.id", "");
                if (itemId.isBlank()) {
                    yield false;
                }
                int slot = findHotbarSlot(player.getInventory(), itemId);
                if (slot < 0) {
                    slot = moveInventoryItemToSelectedHotbar(player.getInventory(), itemId);
                }
                if (slot < 0) {
                    yield false;
                }
                player.getInventory().selectedSlot = slot;
                state.put("selected.slot", slot);
                state.put("selected.item.id", itemId);
                AutomationReporter.mem("selected.slot", slot);
                AutomationReporter.mem("selected.item.id", itemId);
                yield true;
            }
            case "cap.inventory.drop_selected_item" -> {
                if (player == null) {
                    yield false;
                }
                boolean all = Boolean.parseBoolean(stringParam(resolvedParams, "drop.all", "false"));
                boolean dropped = player.dropSelectedItem(all);
                state.put("result.dropped", dropped);
                state.put("result.action", dropped ? "SUCCESS" : "FAIL");
                AutomationReporter.mem("result.dropped", dropped);
                AutomationReporter.mem("result.action", dropped ? "SUCCESS" : "FAIL");
                yield dropped;
            }
            case "cap.inventory.equip_item" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(resolvedParams, "item.id", "");
                if (itemId.isBlank()) {
                    yield false;
                }
                int slot = findHotbarSlot(player.getInventory(), itemId);
                if (slot < 0) {
                    yield false;
                }
                player.getInventory().selectedSlot = slot;
                state.put("equipped.item.id", itemId);
                AutomationReporter.mem("equipped.item.id", itemId);
                yield true;
            }

            case "cap.world.scan_entities" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String entityId = stringParam(resolvedParams, "target.id", "");
                double radius = doubleParam(resolvedParams, "radius", 24.0D);
                if (entityId.isBlank()) {
                    yield false;
                }
                writeScanTelemetry(state, "entity", entityId, "nearest", radius, countMatchingEntities(player, entityId, radius));
                Entity nearest = findEntityBySelector(player, entityId, radius, "nearest");
                if (nearest == null) {
                    state.put("scan.failure_reason", "no_matching_entity");
                    yield false;
                }
                writeEntityState(state, nearest, entityId);
                writeSelectedTargetTelemetry(state, player, nearest.getPos());
                yield true;
            }
            case "cap.world.scan_players" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String name = stringParam(resolvedParams, "player.name", "");
                if (name.isBlank()) {
                    yield false;
                }
                double radius = doubleParam(resolvedParams, "radius", 64.0D);
                writeScanTelemetry(state, "player", name, "nearest", radius, countMatchingPlayers(player, name, radius));
                PlayerEntity nearest = findPlayerByName(player, name, radius, "nearest");
                if (nearest == null) {
                    state.put("scan.failure_reason", "no_matching_player");
                    yield false;
                }
                state.put("current.target.kind", "player");
                state.put("current.target.id", nearest.getUuidAsString());
                state.put("current.target.uuid", nearest.getUuidAsString());
                state.put("current.target.name", nearest.getName().getString());
                state.put("current.target.x", nearest.getX());
                state.put("current.target.y", nearest.getY());
                state.put("current.target.z", nearest.getZ());
                AutomationReporter.mem("current.target.name", nearest.getName().getString());
                AutomationReporter.mem("current.target.uuid", nearest.getUuidAsString());
                writeSelectedTargetTelemetry(state, player, nearest.getPos());
                yield true;
            }
            case "cap.world.scan_blocks" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String blockId = stringParam(resolvedParams, "target.id", "");
                int radius = Math.max(1, intParam(resolvedParams, "radius", 6));
                if (blockId.isBlank()) {
                    yield false;
                }
                writeScanTelemetry(state, "block", blockId, "nearest", radius, countMatchingBlocks(player, blockId, radius));
                BlockPos pos = findNearestBlock(player, blockId, radius);
                if (pos == null) {
                    state.put("scan.failure_reason", "no_matching_block");
                    yield false;
                }
                BlockState blockState = world.getBlockState(pos);
                state.put("current.target.kind", "block");
                state.put("current.target.id", blockId);
                state.put("current.target.x", pos.getX());
                state.put("current.target.y", pos.getY());
                state.put("current.target.z", pos.getZ());
                state.put("current.target.block_state", blockState.getBlock().toString());
                AutomationReporter.mem("current.target.id", blockId);
                AutomationReporter.mem("current.target.x", pos.getX());
                AutomationReporter.mem("current.target.y", pos.getY());
                AutomationReporter.mem("current.target.z", pos.getZ());
                writeSelectedTargetTelemetry(state, player, Vec3d.ofCenter(pos));
                yield true;
            }
            case "cap.world.scan_target" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String targetKind = stringParam(resolvedParams, "target.kind", "").toLowerCase(Locale.ROOT);
                String selectorId = stringParam(resolvedParams, "target.selector", "nearest").toLowerCase(Locale.ROOT);
                String targetId = stringParam(resolvedParams, "target.id", "");
                if (canReuseCurrentTarget(player, world, state, targetKind, targetId)) {
                    state.put("scan.cache_hit", true);
                    AutomationReporter.mem("scan.cache_hit", true);
                    yield true;
                }
                state.put("scan.cache_hit", false);
                yield switch (targetKind) {
                    case "player" -> {
                        String name = targetId;
                        if (name.isBlank()) {
                            yield false;
                        }
                        double radius = doubleParam(resolvedParams, "radius", 64.0D);
                        writeScanTelemetry(state, "player", name, selectorId, radius, countMatchingPlayers(player, name, radius));
                        PlayerEntity nearest = findPlayerByName(player, name, radius, selectorId);
                        if (nearest == null) {
                            state.put("scan.failure_reason", "no_matching_player");
                            yield false;
                        }
                        state.put("current.target.kind", "player");
                        state.put("current.target.id", nearest.getUuidAsString());
                        state.put("current.target.uuid", nearest.getUuidAsString());
                        state.put("current.target.name", nearest.getName().getString());
                        state.put("current.target.x", nearest.getX());
                        state.put("current.target.y", nearest.getY());
                        state.put("current.target.z", nearest.getZ());
                        AutomationReporter.mem("current.target.name", nearest.getName().getString());
                        AutomationReporter.mem("current.target.uuid", nearest.getUuidAsString());
                        writeSelectedTargetTelemetry(state, player, nearest.getPos());
                        yield true;
                    }
                    case "block" -> {
                        String blockId = targetId;
                        int radius = Math.max(1, intParam(resolvedParams, "radius", 6));
                        if (blockId.isBlank()) {
                            yield false;
                        }
                        writeScanTelemetry(state, "block", blockId, selectorId, radius, countMatchingBlocks(player, blockId, radius));
                        BlockPos pos = findNearestBlock(player, blockId, radius);
                        if (pos == null) {
                            state.put("scan.failure_reason", "no_matching_block");
                            yield false;
                        }
                        BlockState blockState = world.getBlockState(pos);
                        state.put("current.target.kind", "block");
                        state.put("current.target.id", blockId);
                        state.put("current.target.x", pos.getX());
                        state.put("current.target.y", pos.getY());
                        state.put("current.target.z", pos.getZ());
                        state.put("current.target.block_state", blockState.getBlock().toString());
                        AutomationReporter.mem("current.target.id", blockId);
                        AutomationReporter.mem("current.target.x", pos.getX());
                        AutomationReporter.mem("current.target.y", pos.getY());
                        AutomationReporter.mem("current.target.z", pos.getZ());
                        writeSelectedTargetTelemetry(state, player, Vec3d.ofCenter(pos));
                        yield true;
                    }
                    case "item" -> {
                        String itemId = targetId;
                        double radius = doubleParam(resolvedParams, "radius", 24.0D);
                        if (itemId.isBlank()) {
                            yield false;
                        }
                        writeScanTelemetry(state, "item", itemId, selectorId, radius, countMatchingItems(player, itemId, radius));
                        ItemEntity nearest = findItemEntityBySelector(player, itemId, radius, selectorId);
                        if (nearest == null) {
                            state.put("scan.failure_reason", "no_matching_item");
                            yield false;
                        }
                        writeEntityState(state, nearest, itemId);
                        state.put("current.target.kind", "item");
                        writeSelectedTargetTelemetry(state, player, nearest.getPos());
                        yield true;
                    }
                    default -> {
                        String entityId = targetId;
                        double radius = doubleParam(resolvedParams, "radius", 24.0D);
                        if (entityId.isBlank()) {
                            yield false;
                        }
                        writeScanTelemetry(state, "entity", entityId, selectorId, radius, countMatchingEntities(player, entityId, radius));
                        Entity nearest = findEntityBySelector(player, entityId, radius, selectorId);
                        if (nearest == null) {
                            state.put("scan.failure_reason", "no_matching_entity");
                            yield false;
                        }
                        writeEntityState(state, nearest, entityId);
                        writeSelectedTargetTelemetry(state, player, nearest.getPos());
                        yield true;
                    }
                };
            }
            case "cap.world.validate_target" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String targetKind = stringParam(resolvedParams, "target.kind", stringParam(state, "current.target.kind", "")).toLowerCase(Locale.ROOT);
                boolean valid = switch (targetKind) {
                    case "block" -> {
                        if (!hasTargetPosition(state)) {
                            yield false;
                        }
                        String expected = stringParam(resolvedParams, "target.id", stringParam(state, "current.target.id", ""));
                        Identifier actual = Registries.BLOCK.getId(world.getBlockState(targetBlockPos(state)).getBlock());
                        yield actual != null && expected.equals(actual.toString());
                    }
                    case "entity", "player", "item" -> {
                        Entity entity = resolveTargetEntity(world, resolvedParams, state);
                        yield entity != null && entity.isAlive();
                    }
                    case "location" -> hasTargetPosition(state);
                    default -> false;
                };
                String resultKey = stringParam(resolvedParams, "result.key", "result.target.valid");
                state.put(resultKey, valid);
                AutomationReporter.mem(resultKey, valid);
                yield valid;
            }
            case "cap.world.target_in_range" -> {
                if (player == null || !hasTargetPosition(state)) {
                    yield false;
                }
                double distance = player.getPos().distanceTo(new Vec3d(
                        doubleState(state, "current.target.x", player.getX()),
                        doubleState(state, "current.target.y", player.getY()),
                        doubleState(state, "current.target.z", player.getZ())
                ));
                double threshold = doubleParam(resolvedParams, "distance", doubleParam(resolvedParams, "stop.distance", 3.0D));
                boolean inRange = distance <= threshold;
                String resultKey = stringParam(resolvedParams, "result.key", "result.target.in_range");
                state.put(resultKey, inRange);
                AutomationReporter.mem(resultKey, inRange);
                yield inRange;
            }

            case "cap.look.face_target" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String uuid = stringParam(state, "current.target.uuid", "");
                Entity entity = findEntityByUuid(world, uuid);
                if (entity != null) {
                    facePosition(player, entity.getEyePos());
                    yield true;
                }
                if (hasTargetPosition(state)) {
                    facePosition(player, new Vec3d(doubleState(state, "current.target.x", player.getX()) + 0.5D, doubleState(state, "current.target.y", player.getEyeY()), doubleState(state, "current.target.z", player.getZ()) + 0.5D));
                    yield true;
                }
                yield false;
            }
            case "cap.look.face_target_horizontal" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String uuid = stringParam(state, "current.target.uuid", "");
                Entity entity = findEntityByUuid(world, uuid);
                if (entity != null) {
                    facePositionHorizontal(player, entity.getPos());
                    yield true;
                }
                if (hasTargetPosition(state)) {
                    facePositionHorizontal(player, new Vec3d(
                            doubleState(state, "current.target.x", player.getX()) + 0.5D,
                            doubleState(state, "current.target.y", player.getY()),
                            doubleState(state, "current.target.z", player.getZ()) + 0.5D
                    ));
                    yield true;
                }
                yield false;
            }
            case "cap.look.face_position" -> {
                if (player == null) {
                    yield false;
                }
                double x = resolvedParams.containsKey("target.x") ? doubleParam(resolvedParams, "target.x", player.getX()) : doubleState(state, "current.target.x", player.getX());
                double y = resolvedParams.containsKey("target.y") ? doubleParam(resolvedParams, "target.y", player.getY()) : doubleState(state, "current.target.y", player.getY());
                double z = resolvedParams.containsKey("target.z") ? doubleParam(resolvedParams, "target.z", player.getZ()) : doubleState(state, "current.target.z", player.getZ());
                facePosition(player, new Vec3d(x + 0.5D, y, z + 0.5D));
                yield true;
            }
            case "cap.look.set_yaw" -> {
                if (player == null) {
                    yield false;
                }
                player.setYaw((float) doubleParam(resolvedParams, "yaw", player.getYaw()));
                writeMovementResult(state, true, "success", "yaw_set");
                yield true;
            }
            case "cap.look.set_pitch" -> {
                if (player == null) {
                    yield false;
                }
                player.setPitch(MathHelper.clamp((float) doubleParam(resolvedParams, "pitch", player.getPitch()), -90.0F, 90.0F));
                writeMovementResult(state, true, "success", "pitch_set");
                yield true;
            }
            case "cap.look.face_block_center" -> {
                if (player == null) {
                    yield false;
                }
                BlockPos pos = resolveTargetBlockPos(resolvedParams, state);
                if (pos == null) {
                    yield false;
                }
                facePosition(player, Vec3d.ofCenter(pos));
                writeMovementResult(state, true, "success", "look_block_center");
                yield true;
            }
            case "cap.look.face_block_face" -> {
                if (player == null) {
                    yield false;
                }
                BlockHitResult hit = resolveBlockHitResult(player, resolvedParams, state);
                if (hit == null) {
                    yield false;
                }
                facePosition(player, hit.getPos());
                writeMovementResult(state, true, "success", "look_block_face");
                yield true;
            }
            case "cap.look.face_movement_direction" -> {
                if (player == null) {
                    yield false;
                }
                Vec3d velocity = player.getVelocity();
                Vec3d direction = new Vec3d(velocity.x, 0.0D, velocity.z);
                if (direction.lengthSquared() < 1.0E-4D) {
                    direction = movementDirectionFromState(state);
                }
                if (direction.lengthSquared() >= 1.0E-4D) {
                    facePositionHorizontal(player, player.getPos().add(direction.normalize()));
                }
                smoothPitchToward(player, MathHelper.clamp(player.getPitch(), -20.0F, 20.0F), LOOK_MAX_PITCH_STEP);
                writeMovementResult(state, true, "success", "look_movement_direction");
                yield true;
            }
            case "cap.look.face_parkour_landing" -> {
                if (player == null) {
                    yield false;
                }
                Vec3d landing = resolveTargetVector(world, resolvedParams, state);
                if (landing == null) {
                    yield false;
                }
                facePosition(player, landing.add(0.0D, 0.35D, 0.0D));
                smoothPitchToward(player, MathHelper.clamp(player.getPitch(), -35.0F, 20.0F), LOOK_MAX_PITCH_STEP);
                writeMovementResult(state, true, "success", "look_parkour_landing");
                yield true;
            }
            case "cap.look.turn_yaw" -> {
                if (player == null) {
                    yield false;
                }
                float amount = (float) doubleParam(resolvedParams, "count.value", 90.0D);
                String direction = stringParam(resolvedParams, "direction.id", "left").toLowerCase(Locale.ROOT);
                player.setYaw(player.getYaw() + ("left".equals(direction) ? amount : -amount));
                yield true;
            }
            case "cap.look.turn_pitch" -> {
                if (player == null) {
                    yield false;
                }
                float amount = (float) doubleParam(resolvedParams, "count.value", 20.0D);
                String direction = stringParam(resolvedParams, "direction.id", "up").toLowerCase(Locale.ROOT);
                float next = player.getPitch() + ("up".equals(direction) ? -amount : amount);
                player.setPitch(MathHelper.clamp(next, -90.0F, 90.0F));
                yield true;
            }
            case "cap.look.turn_relative" -> {
                if (player == null) {
                    yield false;
                }
                float amount = (float) doubleParam(resolvedParams, "count.value", 90.0D);
                String direction = stringParam(resolvedParams, "direction.id", "left").toLowerCase(Locale.ROOT);
                switch (direction) {
                    case "up", "down" -> {
                        float next = player.getPitch() + ("up".equals(direction) ? -amount : amount);
                        player.setPitch(MathHelper.clamp(next, -90.0F, 90.0F));
                    }
                    default -> player.setYaw(player.getYaw() + ("left".equals(direction) ? amount : -amount));
                }
                yield true;
            }

            case "cap.movement.walk_relative" -> {
                if (player == null) {
                    yield false;
                }
                String direction = stringParam(resolvedParams, "direction.id", "forward");
                int ticks = Math.max(1, intParam(resolvedParams, "ticks", blocksToTicks(doubleParam(resolvedParams, "count.value", 1.0D))));
                beginRelativeMovement(state, player, direction, ticks, booleanParam(resolvedParams, "sprint", false));
                yield true;
            }
            case "cap.path.compute_relative_target" -> {
                if (player == null) {
                    yield false;
                }
                writeRelativeTargetState(state, player, resolvedParams);
                yield true;
            }
            case "cap.path.move_relative_verified" -> {
                if (player == null || world == null) {
                    yield false;
                }
                Vec3d target = writeRelativeTargetState(state, player, resolvedParams);
                Map<String, Object> navParams = new LinkedHashMap<>(resolvedParams);
                navParams.putIfAbsent("movement.precise", true);
                navParams.putIfAbsent("movement.precision.distance", Math.max(NAV_PRECISE_DIRECT_DISTANCE, doubleState(state, "current.target.relative_distance", NAV_PRECISE_DIRECT_DISTANCE)));
                navParams.putIfAbsent("movement.precision.slow_distance", NAV_PRECISE_SLOW_DISTANCE);
                double stopDistance = navigationStopDistance(navParams, state, NAV_PRECISE_STOP_DISTANCE);
                boolean useY = Boolean.TRUE.equals(state.get("current.target.use_y")) || "true".equalsIgnoreCase(stringParam(state, "current.target.use_y", "false"));
                beginPointNavigation(state, player, target, stopDistance, booleanParam(navParams, "movement.allow_sprint", true), useY, navParams);
                state.put("move.target.precise", booleanParam(navParams, "movement.precise", true));
                state.put("move.target.precision_distance", doubleParam(navParams, "movement.precision.distance", Math.max(NAV_PRECISE_DIRECT_DISTANCE, doubleState(state, "current.target.relative_distance", NAV_PRECISE_DIRECT_DISTANCE))));
                state.put("move.target.slow_distance", doubleParam(navParams, "movement.precision.slow_distance", NAV_PRECISE_SLOW_DISTANCE));
                writeStateValue(state, "path.status", "relative_navigating");
                yield true;
            }
            case "cap.movement.stop" -> {
                clearMovementState(state);
                releaseMovementInputs();
                if (player != null) {
                    player.setSprinting(false);
                }
                writeMovementResult(state, true, "success", "movement_stopped");
                yield true;
            }
            case "cap.movement.snapshot" -> {
                if (player == null || world == null) {
                    yield false;
                }
                writeMovementSnapshot(state, player, world);
                writeMovementResult(state, true, "success", "snapshot");
                yield true;
            }
            case "cap.movement.check_progress" -> {
                if (player == null) {
                    yield false;
                }
                MovementProgress progress = evaluateMovementProgress(state, player, doubleParam(resolvedParams, "target.distance", doubleState(state, "path.distance", Double.MAX_VALUE)));
                writeProgressState(state, progress);
                yield progress.ok();
            }
            case "cap.movement.check_safety" -> {
                if (player == null || world == null) {
                    yield false;
                }
                MovementSafety safety = movementSafetyAt(world, player.getBlockPos(), 3);
                writeSafetyState(state, safety);
                writeMovementResult(state, safety.safe(), safety.safe() ? "success" : "unsafe", safety.reason());
                yield safety.safe();
            }
            case "cap.movement.choose_recovery" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String tactic = chooseRecoveryTactic(state, player, world);
                state.put("move.recovery.tactic", tactic);
                state.put("move.recovery.mode", tactic);
                AutomationReporter.info("[move]", "recovery selected: " + tactic);
                writeMovementResult(state, !"fail".equals(tactic), "needs_recovery", tactic);
                yield !"fail".equals(tactic);
            }
            case "cap.movement.run_recovery" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String tactic = stringParam(resolvedParams, "recovery.tactic", stringParam(state, "move.recovery.tactic", "auto"));
                boolean recovered = runRecoveryTactic(state, player, world, tactic);
                writeMovementResult(state, recovered, recovered ? "running" : "failed", stringParam(state, "move.recovery.reason", tactic));
                yield recovered;
            }
            case "cap.path.resolve_target" -> {
                if (player == null || world == null) {
                    yield false;
                }
                boolean resolved = resolveMovementTarget(world, player, resolvedParams, state);
                if (!resolved && (hasResolvedParam(resolvedParams, "target.x") || hasResolvedParam(resolvedParams, "target.z"))) {
                    double x = hasResolvedParam(resolvedParams, "target.x") ? doubleParam(resolvedParams, "target.x", player.getX()) : doubleState(state, "current.target.x", player.getX());
                    double y = hasResolvedParam(resolvedParams, "target.y") ? doubleParam(resolvedParams, "target.y", player.getY()) : player.getY();
                    double z = hasResolvedParam(resolvedParams, "target.z") ? doubleParam(resolvedParams, "target.z", player.getZ()) : doubleState(state, "current.target.z", player.getZ());
                    state.put("current.target.kind", "location");
                    state.put("current.target.id", "coordinate");
                    state.put("current.target.x", x);
                    state.put("current.target.y", y);
                    state.put("current.target.z", z);
                    state.put("current.target.use_y", hasResolvedParam(resolvedParams, "target.y"));
                    state.put("movement.resolve.reason", "coordinate_forced_live");
                    writeMovementTargetDetails(state, "coordinate", new Vec3d(x, y, z), new Vec3d(x, y + player.getEyeHeight(player.getPose()), z), doubleParam(resolvedParams, "stop.distance", 1.0D), hasResolvedParam(resolvedParams, "target.y") ? 0.86D : 0.72D, 0.0D, "coordinate_forced_live");
                    resolved = true;
                }
                state.put("movement.resolve.params", resolvedParams.toString());
                AutomationReporter.mem("movement.resolve.params", resolvedParams);
                writeMovementResult(state, resolved, resolved ? "success" : "target_unreachable", stringParam(state, "movement.resolve.reason", "unresolved"));
                yield resolved;
            }
            case "cap.path.plan_local" -> {
                if (player == null || world == null || !hasTargetPosition(state)) {
                    yield false;
                }
                MovementPlan plan = planLocalMovement(world, player, new Vec3d(doubleState(state, "current.target.x", player.getX()), doubleState(state, "current.target.y", player.getY()), doubleState(state, "current.target.z", player.getZ())), resolvedParams, state);
                writePlanState(state, plan);
                yield true;
            }
            case "cap.path.replan_local_segment" -> {
                if (player == null || world == null || !hasTargetPosition(state)) {
                    yield false;
                }
                incrementStateCounter(state, "move.replan.count");
                MovementPlan plan = planLocalMovement(world, player, new Vec3d(doubleState(state, "current.target.x", player.getX()), doubleState(state, "current.target.y", player.getY()), doubleState(state, "current.target.z", player.getZ())), resolvedParams, state);
                writePlanState(state, plan);
                writeMovementResult(state, plan.ok(), plan.ok() ? "needs_replan" : "target_unreachable", plan.reason());
                yield plan.ok();
            }
            case "cap.path.verify_relative_arrival" -> {
                if (player == null) {
                    yield false;
                }
                boolean ok = verifyRelativeArrival(player, resolvedParams, state);
                yield ok;
            }
            case "cap.path.verify_target_arrival" -> {
                if (player == null) {
                    yield false;
                }
                boolean ok = verifyTargetArrival(player, resolvedParams, state);
                yield ok;
            }
            case "cap.path.move_to_target" -> {
                if (player == null || world == null) {
                    yield false;
                }
                Vec3d target = resolveTargetVector(world, resolvedParams, state);
                if (target == null && resolveMovementTarget(world, player, resolvedParams, state)) {
                    target = new Vec3d(
                            doubleState(state, "current.target.x", player.getX()),
                            doubleState(state, "current.target.y", player.getY()),
                            doubleState(state, "current.target.z", player.getZ())
                    );
                }
                if (target == null) {
                    writeMovementResult(state, false, "failed", stringParam(state, "movement.resolve.reason", "target_unresolved"));
                    yield false;
                }
                double stopDistance = navigationStopDistance(resolvedParams, state, 1.5D);
                boolean useY = Boolean.parseBoolean(stringParam(resolvedParams, "path.use_y", stringParam(state, "current.target.use_y", hasResolvedParam(resolvedParams, "target.y") ? "true" : "false")));
                if (hasResolvedParam(resolvedParams, "target.x") && hasResolvedParam(resolvedParams, "target.z")) {
                    state.put("current.target.kind", "location");
                    state.put("current.target.id", "coordinate");
                    state.put("current.target.x", target.x);
                    state.put("current.target.y", target.y);
                    state.put("current.target.z", target.z);
                    state.put("current.target.use_y", useY);
                }
                MovementPlan plan = planLocalMovement(world, player, target, resolvedParams, state);
                beginPointNavigation(state, player, target, stopDistance, booleanParam(resolvedParams, "sprint", plan.sprint()), useY, resolvedParams);
                boolean precise = booleanParam(resolvedParams, "movement.precise", isCoordinateTarget(state, resolvedParams));
                state.put("move.target.precise", precise);
                state.put("move.target.precision_distance", doubleParam(resolvedParams, "movement.precision.distance", precise ? NAV_PRECISE_DIRECT_DISTANCE : NAV_PRECISE_SLOW_DISTANCE));
                state.put("move.target.slow_distance", doubleParam(resolvedParams, "movement.precision.slow_distance", NAV_PRECISE_SLOW_DISTANCE));
                writePlanState(state, plan);
                yield true;
            }
            case "cap.path.follow_target" -> {
                if (player == null || world == null) {
                    yield false;
                }
                Entity entity = resolveTargetEntity(world, resolvedParams, state);
                if (entity == null) {
                    yield false;
                }
                double stopDistance = doubleParam(resolvedParams, "stop.distance", 1.8D);
                beginFollowNavigation(state, player, entity, stopDistance, booleanParam(resolvedParams, "sprint", false), resolvedParams);
                yield true;
            }
            case "cap.parkour.analyze_jump" -> {
                if (player == null || world == null) {
                    yield false;
                }
                Vec3d target = resolveTargetVector(world, resolvedParams, state);
                if (target == null) {
                    yield false;
                }
                ParkourAnalysis analysis = analyzeParkourJump(world, player, target, resolvedParams);
                writeParkourAnalysis(state, analysis);
                yield analysis.ok();
            }
            case "cap.parkour.execute_jump" -> {
                if (player == null || world == null) {
                    yield false;
                }
                Vec3d target = resolveTargetVector(world, resolvedParams, state);
                if (target == null) {
                    yield false;
                }
                ParkourAnalysis analysis = analyzeParkourJump(world, player, target, resolvedParams);
                writeParkourAnalysis(state, analysis);
                if (!analysis.ok()) {
                    yield false;
                }
                Vec3d landing = normalizeParkourLanding(world, target);
                Vec3d takeoff = findParkourTakeoff(world, player, landing, analysis);
                if (takeoff == null) {
                    writeMovementResult(state, false, "failed", "no_parkour_takeoff");
                    yield false;
                }
                beginPointNavigation(state, player, landing, doubleParam(resolvedParams, "stop.distance", PARKOUR_LANDING_DISTANCE), analysis.sprint(), true, resolvedParams);
                state.put("move.target.mode", "parkour");
                state.put("parkour.active", true);
                state.put("parkour.phase", "approach_takeoff");
                state.put("parkour.takeoff.x", takeoff.x);
                state.put("parkour.takeoff.y", takeoff.y);
                state.put("parkour.takeoff.z", takeoff.z);
                state.put("parkour.landing.x", landing.x);
                state.put("parkour.landing.y", landing.y);
                state.put("parkour.landing.z", landing.z);
                state.put("parkour.sprint", analysis.sprint());
                state.put("parkour.charge_ticks", 0);
                state.put("parkour.air_ticks", 0);
                state.put("parkour.approach_ticks", 0);
                state.put("parkour.attempt", intState(state, "parkour.attempt", 0) + 1);
                writeStateValue(state, "parkour.takeoff", round(takeoff.x) + "," + round(takeoff.y) + "," + round(takeoff.z));
                writeStateValue(state, "parkour.landing", round(landing.x) + "," + round(landing.y) + "," + round(landing.z));
                writeMovementResult(state, true, "running", "parkour_jump_started");
                yield true;
            }
            case "cap.goal.execute_named" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String goalName = normalizeGoalName(stringParam(resolvedParams, "goal.name", stringParam(resolvedParams, "target.name", "")));
                if (goalName.isBlank()) {
                    yield false;
                }
                state.put("goal.name", goalName);
                AutomationReporter.mem("goal.name", goalName);
                boolean goalStarted = executeNamedGoal(goalName, resolvedParams, state, client, player, world);
                state.put("result.action", goalStarted ? "STARTED" : "FAIL");
                AutomationReporter.mem("result.action", goalStarted ? "STARTED" : "FAIL");
                yield goalStarted;
            }
            case "cap.player.jump" -> {
                if (player == null) {
                    yield false;
                }
                player.jump();
                yield true;
            }
            case "cap.player.dismount" -> {
                if (player == null) {
                    yield false;
                }
                if (player.hasVehicle()) {
                    player.dismountVehicle();
                }
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }
            case "cap.player.crouch" -> {
                setHeldInput("sneak", true);
                yield true;
            }
            case "cap.player.uncrouch" -> {
                setHeldInput("sneak", false);
                yield true;
            }
            case "cap.player.sprint" -> {
                setHeldInput("sprint", true);
                yield true;
            }
            case "cap.player.unsprint" -> {
                setHeldInput("sprint", false);
                yield true;
            }

            case "cap.interaction.use_item" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                Hand hand = resolveHand(resolvedParams);
                ActionResult result = interactionManager.interactItem(player, hand);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.use_main_hand_item" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                ActionResult result = interactionManager.interactItem(player, Hand.MAIN_HAND);
                player.swingHand(Hand.MAIN_HAND);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.use_off_hand_item" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                ActionResult result = interactionManager.interactItem(player, Hand.OFF_HAND);
                player.swingHand(Hand.OFF_HAND);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.use_selected_item" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                Hand hand = resolveHand(resolvedParams);
                ActionResult result = interactionManager.interactItem(player, hand);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.use_item_on_block_target" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                BlockHitResult hitResult = resolveBlockHitResult(player, resolvedParams, state);
                if (hitResult == null) {
                    yield false;
                }
                Hand hand = resolveHand(resolvedParams);
                ActionResult result = interactionManager.interactBlock(player, hand, hitResult);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.use_item_on_current_block" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                if (!hasTargetPosition(state)) {
                    yield false;
                }
                BlockHitResult hitResult = createCenterHit(player, targetBlockPos(state));
                Hand hand = resolveHand(resolvedParams);
                ActionResult result = interactionManager.interactBlock(player, hand, hitResult);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.interact_entity_target" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                Entity entity = resolveTargetEntity(world, resolvedParams, state);
                if (entity == null) {
                    yield false;
                }
                Hand hand = resolveHand(resolvedParams);
                ActionResult result = interactionManager.interactEntity(player, entity, hand);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.interact_target" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                String targetKind = stringParam(state, "current.target.kind", stringParam(resolvedParams, "target.kind", ""));
                Hand hand = resolveHand(resolvedParams);
                if ("block".equalsIgnoreCase(targetKind) && hasTargetPosition(state)) {
                    BlockHitResult hitResult = resolveBlockHitResult(player, resolvedParams, state);
                    if (hitResult == null) {
                        yield false;
                    }
                    ActionResult result = interactionManager.interactBlock(player, hand, hitResult);
                    player.swingHand(hand);
                    writeActionResult(state, "result.action", result);
                    yield result != ActionResult.FAIL;
                }
                Entity entity = resolveTargetEntity(world, resolvedParams, state);
                if (entity == null) {
                    yield false;
                }
                ActionResult result = interactionManager.interactEntity(player, entity, hand);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.use_item_on_entity_target" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                Entity entity = resolveTargetEntity(world, resolvedParams, state);
                if (entity == null) {
                    yield false;
                }
                Hand hand = resolveHand(resolvedParams);
                ActionResult result = interactionManager.interactEntity(player, entity, hand);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.open_target" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                if (!hasTargetPosition(state)) {
                    yield false;
                }
                BlockPos pos = targetBlockPos(state);
                BlockHitResult hitResult = createCenterHit(player, pos);
                Hand hand = resolveHand(resolvedParams);
                ActionResult result = interactionManager.interactBlock(player, hand, hitResult);
                player.swingHand(hand);
                writeActionResult(state, "result.action", result);
                yield result != ActionResult.FAIL;
            }
            case "cap.interaction.close_screen" -> {
                if (player == null) {
                    yield false;
                }
                player.closeHandledScreen();
                yield true;
            }
            case "cap.interaction.attack_target" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                Entity entity = resolveTargetEntity(world, resolvedParams, state);
                if (entity == null) {
                    yield false;
                }
                interactionManager.attackEntity(player, entity);
                player.swingHand(Hand.MAIN_HAND);
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }
            case "cap.interaction.break_block_target" -> {
                if (player == null || interactionManager == null || world == null) {
                    yield false;
                }
                BlockPos pos = resolveTargetBlockPos(resolvedParams, state);
                if (pos == null) {
                    yield false;
                }
                String expectedId = stringParam(resolvedParams, "target.id", stringParam(state, "current.target.id", ""));
                state.put("mine.active", true);
                state.put("mine.target.x", pos.getX());
                state.put("mine.target.y", pos.getY());
                state.put("mine.target.z", pos.getZ());
                state.put("mine.target.id", expectedId);
                state.put("mine.ticks", 0);
                state.put("mine.started", false);
                state.put("mine.failure_reason", "");
                state.put("result.action", "STARTED");
                AutomationReporter.mem("mine.active", true);
                AutomationReporter.mem("mine.target.x", pos.getX());
                AutomationReporter.mem("mine.target.y", pos.getY());
                AutomationReporter.mem("mine.target.z", pos.getZ());
                AutomationReporter.mem("mine.target.id", expectedId);
                AutomationReporter.mem("result.action", "STARTED");
                yield true;
            }
            case "cap.interaction.stop_using_item" -> {
                if (player == null) {
                    yield false;
                }
                player.stopUsingItem();
                state.put("result.action", "SUCCESS");
                AutomationReporter.mem("result.action", "SUCCESS");
                yield true;
            }

            case "cap.combat.attack_target", "cap.combat.attack_until_dead" -> {
                if (player == null || world == null || interactionManager == null) {
                    yield false;
                }
                Entity entity = resolveTargetEntity(world, resolvedParams, state);
                if (entity == null) {
                    yield false;
                }
                double distance = player.getPos().distanceTo(entity.getPos());
                double maxDistance = doubleParam(resolvedParams, "attack.range", 3.4D);
                if (distance > maxDistance) {
                    state.put("attack.failed", true);
                    state.put("attack.failure_reason", "target_out_of_range");
                    yield true;
                }
                state.put("attack.active", true);
                state.put("attack.target.uuid", entity.getUuidAsString());
                state.put("attack.phase", "prepare");
                state.put("attack.ticks", 0);
                state.put("attack.crit.enabled", Boolean.parseBoolean(stringParam(resolvedParams, "crit.enabled", "true")));
                state.put("attack.crit.jump_wait_ticks", intParam(resolvedParams, "crit.jump_wait_ticks", 3));
                state.put("attack.range", maxDistance);
                state.put("attack.until_dead", "cap.combat.attack_until_dead".equals(action));
                AutomationReporter.mem("attack.active", true);
                AutomationReporter.mem("attack.phase", "prepare");
                yield true;
            }
            case "cap.interaction.consume_selected_item" -> {
                if (player == null || world == null || interactionManager == null) {
                    yield false;
                }
                ItemStack stack = player.getMainHandStack();
                if (stack.isEmpty()) {
                    yield false;
                }
                String requiredItemId = stringParam(resolvedParams, "item.id", "");
                if (!requiredItemId.isBlank() && !itemMatches(stack, requiredItemId)) {
                    yield false;
                }
                if (!stack.isFood()) {
                    yield false;
                }
                if (player.isCreative()) {
                    state.put(stringParam(resolvedParams, "result.key", "result.consumed"), 1);
                    state.put("result.action", "SUCCESS");
                    AutomationReporter.mem(stringParam(resolvedParams, "result.key", "result.consumed"), 1);
                    AutomationReporter.mem("result.action", "SUCCESS");
                    yield true;
                }
                int beforeCount = stack.getCount();
                int beforeHunger = player.getHungerManager().getFoodLevel();
                float beforeSaturation = player.getHungerManager().getSaturationLevel();

                ActionResult result = interactionManager.interactItem(player, Hand.MAIN_HAND);
                if (result == ActionResult.FAIL) {
                    yield false;
                }

                state.put("consume.active", true);
                state.put("consume.item.id", requiredItemId.isBlank() ? String.valueOf(Registries.ITEM.getId(stack.getItem())) : requiredItemId);
                state.put("consume.before_count", beforeCount);
                state.put("consume.before_total_count", countInventory(player.getInventory(), requiredItemId.isBlank() ? String.valueOf(Registries.ITEM.getId(stack.getItem())) : requiredItemId));
                state.put("consume.before_hunger", beforeHunger);
                state.put("consume.before_saturation", beforeSaturation);
                state.put("consume.result.key", stringParam(resolvedParams, "result.key", "result.consumed"));
                state.put("consume.started", player.isUsingItem());
                state.put("consume.ticks", 0);
                state.put("consume.max_ticks", Math.max(40, stack.getMaxUseTime() + 20));
                state.put("consume.failure_reason", "");
                state.put("consume.hold_use", true);
                setHeldInput("use", true);

                AutomationReporter.mem("consume.active", true);
                AutomationReporter.mem("consume.item.id", state.get("consume.item.id"));
                AutomationReporter.mem("consume.before_count", beforeCount);
                yield true;
            }
            case "cap.combat.confirm_target_death" -> {
                if (player == null || world == null) {
                    yield false;
                }
                String uuid = stringParam(state, "current.target.uuid", stringParam(state, "attack.target.uuid", ""));
                Entity entity = findEntityByUuid(world, uuid);
                boolean dead = entity == null || !entity.isAlive();
                String resultKey = stringParam(resolvedParams, "result.key", "result.dead");
                state.put(resultKey, dead);
                AutomationReporter.mem(resultKey, dead);
                yield true;
            }
            case "cap.wait.ticks" -> {
                int ticks = Math.max(0, intParam(resolvedParams, "count.value", 0));
                state.put("wait.ticks", ticks);
                AutomationReporter.mem("wait.ticks", ticks);
                yield true;
            }
            default -> false;
        };
        return new PrimitiveRunResult(success, resolvedParams);
    }

    private Hand resolveHand(Map<String, Object> params) {
        String handId = stringParam(params, "hand.id", "main").toLowerCase(Locale.ROOT);
        return switch (handId) {
            case "off", "off_hand", "offhand", "secondary" -> Hand.OFF_HAND;
            default -> Hand.MAIN_HAND;
        };
    }

    private void writeActionResult(Map<String, Object> state, String key, ActionResult result) {
        String value = result == null ? "FAIL" : result.name();
        state.put(key, value);
        AutomationReporter.mem(key, value);
    }

    private Entity resolveTargetEntity(World world, Map<String, Object> params, Map<String, Object> state) {
        String requestedKind = stringParam(params, "target.kind", stringParam(state, "current.target.kind", "")).toLowerCase(Locale.ROOT);
        if ("block".equals(requestedKind) || "location".equals(requestedKind)) {
            return null;
        }
        String explicitUuid = stringParam(params, "target.uuid", "");
        if (!explicitUuid.isBlank()) {
            Entity entity = findEntityByUuid(world, explicitUuid);
            if (entity != null) {
                return entity;
            }
        }

        String stateUuid = stringParam(state, "current.target.uuid", "");
        if (!stateUuid.isBlank()) {
            Entity entity = findEntityByUuid(world, stateUuid);
            if (entity != null) {
                return entity;
            }
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player == null) {
            return null;
        }

        String targetId = stringParam(params, "target.id", stringParam(state, "current.target.id", ""));
        double radius = doubleParam(params, "radius", 6.0D);
        if (!targetId.isBlank()) {
            return findEntityBySelector(player, targetId, radius, stringParam(params, "target.selector", "nearest"));
        }

        return null;
    }

    private boolean resolveMovementTarget(ClientWorld world, ClientPlayerEntity player, Map<String, Object> params, Map<String, Object> state) {
        String targetKind = stringParam(params, "target.kind", stringParam(state, "current.target.kind", "")).toLowerCase(Locale.ROOT);
        boolean coordinateParams = hasResolvedParam(params, "target.x") || hasResolvedParam(params, "target.z");
        if (coordinateParams && (targetKind.isBlank() || "block".equals(targetKind) || "item".equals(targetKind))) {
            targetKind = "location";
        }
        String targetId = "location".equals(targetKind) ? "" : stringParam(params, "target.id", "");
        boolean allowStatePosition = targetId.isBlank() || "location".equals(targetKind) || state.containsKey("current.target.x") && state.containsKey("current.target.z");
        boolean hasX = hasResolvedParam(params, "target.x") || state.containsKey("current.target.x");
        boolean hasZ = hasResolvedParam(params, "target.z") || state.containsKey("current.target.z");
        if (hasX && hasZ) {
            double x = hasResolvedParam(params, "target.x") ? doubleParam(params, "target.x", player.getX()) : doubleState(state, "current.target.x", player.getX());
            double z = hasResolvedParam(params, "target.z") ? doubleParam(params, "target.z", player.getZ()) : doubleState(state, "current.target.z", player.getZ());
            boolean explicitY = hasResolvedParam(params, "target.y");
            boolean hasY = explicitY || state.containsKey("current.target.y");
            double y = hasY ? (explicitY ? doubleParam(params, "target.y", player.getY()) : doubleState(state, "current.target.y", player.getY())) : player.getY();
            Vec3d resolved = null;
            String reason = "coordinate_resolved";
            if (explicitY) {
                BlockPos feet = BlockPos.ofFloored(x, y, z);
                if (canStandAt(world, feet)) {
                    resolved = new Vec3d(x, y, z);
                } else {
                    resolved = resolveStandablePositionNearY(world, player, x, y, z, booleanParam(params, "movement.allow_swim", false));
                    reason = resolved == null ? "coordinate_explicit_y_unverified" : "coordinate_near_y_resolved";
                }
            } else {
                resolved = resolveStandablePosition(world, player, x, z, booleanParam(params, "movement.allow_swim", false));
                reason = resolved == null ? "coordinate_xz_unverified" : "coordinate_resolved";
            }
            if (resolved == null) {
                resolved = new Vec3d(x, y, z);
            }
            state.put("current.target.kind", targetKind.isBlank() ? "location" : targetKind);
            state.put("current.target.id", targetId.isBlank() ? "coordinate" : targetId);
            state.put("current.target.x", resolved.x);
            state.put("current.target.y", resolved.y);
            state.put("current.target.z", resolved.z);
            state.put("current.target.use_y", hasY);
            state.put("movement.resolve.reason", reason);
            writeMovementTargetDetails(state, "coordinate", resolved, resolved.add(0.0D, player.getEyeHeight(player.getPose()), 0.0D), doubleParam(params, "stop.distance", 1.0D), hasY ? 0.86D : 0.72D, pocketRisk(world, BlockPos.ofFloored(resolved)), reason);
            return true;
        }

        Entity entity = resolveTargetEntity(world, params, state);
        if (entity != null) {
            writeEntityState(state, entity, stringParam(params, "target.id", stringParam(state, "current.target.id", entity.getUuidAsString())));
            writeMovementTargetDetails(state, "entity", entity.getPos(), entity.getEyePos(), doubleParam(params, "stop.distance", 1.8D), 0.95D, 0.0D, "entity_resolved");
            return true;
        }

        BlockPos blockPos = resolveTargetBlockPos(params, state);
        if (targetId.isBlank()) {
            targetId = stringParam(state, "current.target.id", "");
        }
        if (blockPos == null && "block".equals(targetKind) && !targetId.isBlank()) {
            blockPos = findNearestBlock(player, targetId, Math.max(6, intParam(params, "radius", 32)));
            if (blockPos != null) {
                state.put("current.target.id", targetId);
            }
        }
        if (blockPos != null) {
            Vec3d approach = resolveApproachPosition(world, player, blockPos, booleanParam(params, "movement.allow_swim", false));
            if (approach == null) {
                state.put("movement.resolve.reason", "no_block_approach");
                return false;
            }
            state.put("current.target.kind", "block");
            state.put("current.target.x", approach.x);
            state.put("current.target.y", approach.y);
            state.put("current.target.z", approach.z);
            writeMovementTargetDetails(state, "block", approach, Vec3d.ofCenter(blockPos), doubleParam(params, "stop.distance", 2.2D), 0.82D, pocketRisk(world, BlockPos.ofFloored(approach)), "block_approach_resolved");
            return true;
        }

        state.put("movement.resolve.reason", "missing_target");
        return false;
    }

    private BlockPos resolveTargetBlockPos(Map<String, Object> params, Map<String, Object> state) {
        if (hasResolvedParam(params, "target.x") && hasResolvedParam(params, "target.y") && hasResolvedParam(params, "target.z")) {
            return new BlockPos(
                    (int) Math.floor(doubleParam(params, "target.x", 0.0D)),
                    (int) Math.floor(doubleParam(params, "target.y", 0.0D)),
                    (int) Math.floor(doubleParam(params, "target.z", 0.0D))
            );
        }
        if (hasTargetPosition(state)) {
            String requestedId = stringParam(params, "target.id", "");
            String currentId = stringParam(state, "current.target.id", "");
            if (requestedId.isBlank() || requestedId.equals(currentId)) {
                return targetBlockPos(state);
            }
        }
        return null;
    }

    private Vec3d resolveTargetVector(World world, Map<String, Object> params, Map<String, Object> state) {
        Entity entity = resolveTargetEntity(world, params, state);
        if (entity != null) {
            return entity.getPos();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player != null && world instanceof ClientWorld clientWorld && (hasResolvedParam(params, "target.x") || hasResolvedParam(params, "target.z")) && !hasResolvedParam(params, "target.y")) {
            double x = doubleParam(params, "target.x", doubleState(state, "current.target.x", player.getX()));
            double z = doubleParam(params, "target.z", doubleState(state, "current.target.z", player.getZ()));
            Vec3d standable = resolveStandablePosition(clientWorld, player, x, z, booleanParam(params, "movement.allow_swim", false));
            if (standable != null) {
                state.put("current.target.x", standable.x);
                state.put("current.target.y", standable.y);
                state.put("current.target.z", standable.z);
                state.put("current.target.use_y", false);
                return standable;
            }
            state.put("current.target.x", x);
            state.put("current.target.y", player.getY());
            state.put("current.target.z", z);
            state.put("current.target.use_y", false);
            return new Vec3d(x, player.getY(), z);
        }
        String targetKind = stringParam(params, "target.kind", stringParam(params, "target.type", stringParam(state, "current.target.kind", ""))).toLowerCase(Locale.ROOT);
        if ((hasResolvedParam(params, "target.x") || state.containsKey("current.target.x"))
                && (hasResolvedParam(params, "target.y") || state.containsKey("current.target.y"))
                && (hasResolvedParam(params, "target.z") || state.containsKey("current.target.z"))
                && (targetKind.equals("location") || targetKind.equals("coordinate") || targetKind.equals("landing") || targetKind.equals("parkour"))) {
            double x = hasResolvedParam(params, "target.x") ? doubleParam(params, "target.x", doubleState(state, "current.target.x", 0.0D)) : doubleState(state, "current.target.x", 0.0D);
            double y = hasResolvedParam(params, "target.y") ? doubleParam(params, "target.y", doubleState(state, "current.target.y", 0.0D)) : doubleState(state, "current.target.y", 0.0D);
            double z = hasResolvedParam(params, "target.z") ? doubleParam(params, "target.z", doubleState(state, "current.target.z", 0.0D)) : doubleState(state, "current.target.z", 0.0D);
            return new Vec3d(x, y, z);
        }

        BlockPos pos = resolveTargetBlockPos(params, state);
        if (pos != null) {
            if (world instanceof ClientWorld clientWorld && player != null) {
                Vec3d approach = resolveApproachPosition(clientWorld, player, pos, booleanParam(params, "movement.allow_swim", false));
                return approach == null ? Vec3d.ofCenter(pos) : approach;
            }
            return Vec3d.ofCenter(pos);
        }
        return null;
    }

    private boolean verifyTargetArrival(ClientPlayerEntity player, Map<String, Object> params, Map<String, Object> state) {
        Vec3d target = new Vec3d(
                doubleState(state, "current.target.x", doubleState(state, "move.target.x", player.getX())),
                doubleState(state, "current.target.y", doubleState(state, "move.target.y", player.getY())),
                doubleState(state, "current.target.z", doubleState(state, "move.target.z", player.getZ()))
        );
        boolean useY = Boolean.parseBoolean(stringParam(state, "current.target.use_y", stringParam(state, "move.target.use_y", "false")));
        double stopDistance = doubleParam(params, "stop.distance", doubleState(state, "path.stop_distance", useY ? 1.0D : NAV_PRECISE_STOP_DISTANCE));
        double distance = navigationDistance(player.getPos(), target, useY);
        writeStateValue(state, "move.verify.distance", round(distance));
        AutomationCliViewModel.runtimeFlow("", "verify_target_arrival", "move.verify.target", "flow_metric", "[debug]", "move.verify.target", round(target.x) + "," + round(target.y) + "," + round(target.z));
        AutomationCliViewModel.runtimeFlow("", "verify_target_arrival", "move.verify.pos", "flow_metric", "[debug]", "move.verify.pos", round(player.getX()) + "," + round(player.getY()) + "," + round(player.getZ()));
        AutomationCliViewModel.runtimeFlow("", "verify_target_arrival", "move.verify.distance", "flow_metric", "[debug]", "move.verify.distance", round(distance));
        if (distance > Math.max(stopDistance, useY ? 1.0D : NAV_PRECISE_STOP_DISTANCE)) {
            writeMovementResult(state, false, "failed", "target_not_reached");
            return false;
        }
        writeMovementResult(state, true, "success", "target_arrival_verified");
        return true;
    }

    private BlockHitResult resolveBlockHitResult(ClientPlayerEntity player, Map<String, Object> params, Map<String, Object> state) {
        BlockPos pos = resolveTargetBlockPos(params, state);
        if (pos == null) {
            return null;
        }

        Direction side = resolveBlockSide(params);
        boolean insideBlock = false;
        double hitX = params.containsKey("hit.x") ? doubleParam(params, "hit.x", pos.getX() + 0.5D) : pos.getX() + 0.5D;
        double hitY = params.containsKey("hit.y") ? doubleParam(params, "hit.y", pos.getY() + 0.5D) : pos.getY() + 0.5D;
        double hitZ = params.containsKey("hit.z") ? doubleParam(params, "hit.z", pos.getZ() + 0.5D) : pos.getZ() + 0.5D;
        return new BlockHitResult(new Vec3d(hitX, hitY, hitZ), side, pos, insideBlock);
    }

    private Direction resolveBlockSide(Map<String, Object> params) {
        String sideId = stringParam(params, "target.face", "up").toLowerCase(Locale.ROOT);
        return switch (sideId) {
            case "down", "bottom" -> Direction.DOWN;
            case "north", "front" -> Direction.NORTH;
            case "south", "back" -> Direction.SOUTH;
            case "west", "left" -> Direction.WEST;
            case "east", "right" -> Direction.EAST;
            default -> Direction.UP;
        };
    }

    private boolean evaluateCondition(String condition, Map<String, Object> state) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        ClientWorld world = client == null ? null : client.world;

        return switch (condition) {
            case "cond.self_health_below" -> {
                if (player == null) {
                    yield false;
                }
                float threshold = (float) doubleState(state, "condition.threshold", 0.0D);
                yield player.getHealth() < threshold;
            }
            case "cond.self_health_above" -> {
                if (player == null) {
                    yield false;
                }
                float threshold = (float) doubleState(state, "condition.threshold", 0.0D);
                yield player.getHealth() > threshold;
            }
            case "cond.target_exists", "eval.target_exists" -> {
                if (world == null) {
                    yield false;
                }
                String uuid = stringParam(state, "current.target.uuid", "");
                if (!uuid.isBlank()) {
                    Entity entity = findEntityByUuid(world, uuid);
                    yield entity != null && entity.isAlive();
                }
                if (hasTargetPosition(state)) {
                    BlockPos pos = targetBlockPos(state);
                    yield !world.isAir(pos);
                }
                yield false;
            }
            case "eval.inventory_count_compare" -> {
                if (player == null) {
                    yield false;
                }
                String itemId = stringParam(state, "item.id", "");
                if (itemId.isBlank()) {
                    yield false;
                }
                int count = countInventory(player.getInventory(), itemId);
                int threshold = intState(state, "condition.threshold", 0);
                String op = stringParam(state, "condition.op", "gte");
                yield compareNumbers(count, threshold, op);
            }
            case "counter_lt_target" -> {
                int current = intState(state, "state.counter", 0);
                int target = intState(state, "count.value", 0);
                yield current < target;
            }
            case "eval.compare_numbers" -> {
                double left = numberFromStateOrLiteral(state, "condition.left", 0.0D);
                double right = numberFromStateOrLiteral(state, "condition.right", 0.0D);
                String op = stringParam(state, "condition.op", "eq");
                yield compareNumbers(left, right, op);
            }
            case "eval.compare_stat" -> {
                if (player == null) {
                    yield false;
                }
                String statRef = stringParam(state, "stat.ref", "");
                Object rawValue = readStatValue(player, statRef);
                if (!(rawValue instanceof Number number)) {
                    yield false;
                }
                double left = number.doubleValue();
                double right = numberFromStateOrLiteral(state, "condition.right", doubleState(state, "condition.threshold", 0.0D));
                String op = stringParam(state, "condition.op", "eq");
                yield compareNumbers(left, right, op);
            }
            case "eval.time_of_day_compare" -> {
                if (world == null) {
                    yield false;
                }
                long timeOfDay = world.getTimeOfDay() % 24000L;
                String mode = stringParam(state, "condition.mode", "day");
                yield switch (mode) {
                    case "night" -> timeOfDay >= 13000L && timeOfDay < 23000L;
                    case "sunrise" -> timeOfDay >= 0L && timeOfDay < 1000L;
                    case "sunset" -> timeOfDay >= 12000L && timeOfDay < 13000L;
                    default -> timeOfDay < 13000L;
                };
            }
            case "eval.block_matches" -> {
                if (world == null || !hasTargetPosition(state)) {
                    yield false;
                }
                String expectedId = stringParam(state, "target.id", stringParam(state, "condition.expected.block.id", ""));
                if (expectedId.isBlank()) {
                    yield false;
                }
                BlockPos pos = targetBlockPos(state);
                Identifier foundId = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
                yield foundId != null && expectedId.equals(foundId.toString());
            }
            case "eval.automation_task_running", "cond.automation_task_running" -> AutomationRuntimeStatus.isTaskRunning();
            default -> false;
        };
    }

    private void setHeldInput(String keyId, boolean pressed) {
        HeldInput input = this.heldInputs.computeIfAbsent(normalizeInputKey(keyId), ignored -> new HeldInput());
        input.pressed = pressed;
        input.tapTicks = 0;
    }

    private void tapInput(String keyId, int ticks) {
        HeldInput input = this.heldInputs.computeIfAbsent(normalizeInputKey(keyId), ignored -> new HeldInput());
        input.pressed = true;
        input.tapTicks = ticks;
    }

    private void performImmediateTapAction(String keyId, MinecraftClient client, ClientPlayerEntity player) {
        if (client == null || player == null) {
            return;
        }
        switch (normalizeInputKey(keyId)) {
            case "inventory", "e" -> {
                if (client.currentScreen == null) {
                    client.setScreen(new InventoryScreen(player));
                } else {
                    player.closeHandledScreen();
                }
            }
            case "swap_hands", "swap", "f" -> {
                if (player.networkHandler != null) {
                    player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
                }
            }
            case "drop", "q" -> player.dropSelectedItem(false);
            case "perspective", "third_person", "camera" -> {
                Perspective current = client.options.getPerspective();
                client.options.setPerspective(current.next());
            }
            default -> {
            }
        }
    }

    private void queueMouseDelta(float yawDelta, float pitchDelta) {
        HeldInput mouse = this.heldInputs.computeIfAbsent("__mouse__", ignored -> new HeldInput());
        mouse.yawDelta += yawDelta;
        mouse.pitchDelta += pitchDelta;
    }

    private void updateHeldInputs() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        applyKeyState(resolveKeyBinding(client, "forward"), isInputPressed("forward"));
        applyKeyState(resolveKeyBinding(client, "back"), isInputPressed("back"));
        applyKeyState(resolveKeyBinding(client, "left"), isInputPressed("left"));
        applyKeyState(resolveKeyBinding(client, "right"), isInputPressed("right"));
        applyKeyState(resolveKeyBinding(client, "jump"), isInputPressed("jump"));
        applyKeyState(resolveKeyBinding(client, "sneak"), isInputPressed("sneak"));
        applyKeyState(resolveKeyBinding(client, "sprint"), isInputPressed("sprint"));
        applyKeyState(resolveKeyBinding(client, "attack"), isInputPressed("attack"));
        applyKeyState(resolveKeyBinding(client, "use"), isInputPressed("use"));
        applyKeyState(resolveKeyBinding(client, "inventory"), isInputPressed("inventory"));
        applyKeyState(resolveKeyBinding(client, "swap_hands"), isInputPressed("swap_hands"));
        applyKeyState(resolveKeyBinding(client, "drop"), isInputPressed("drop"));
        applyKeyState(resolveKeyBinding(client, "pick_block"), isInputPressed("pick_block"));
        applyKeyState(resolveKeyBinding(client, "perspective"), isInputPressed("perspective"));
        applyPlayerInputState(client.player);
    }

    private void applyPlayerInputState(ClientPlayerEntity player) {
        if (player == null || player.input == null) {
            return;
        }
        boolean forward = isInputPressed("forward");
        boolean back = isInputPressed("back");
        boolean left = isInputPressed("left");
        boolean right = isInputPressed("right");
        boolean jump = isInputPressed("jump");
        boolean sneak = isInputPressed("sneak");
        player.input.pressingForward = forward;
        player.input.pressingBack = back;
        player.input.pressingLeft = left;
        player.input.pressingRight = right;
        player.input.jumping = jump;
        player.input.sneaking = sneak;
        player.input.movementForward = forward == back ? 0.0F : forward ? 1.0F : -1.0F;
        player.input.movementSideways = left == right ? 0.0F : left ? 1.0F : -1.0F;
    }

    private void updateTapInputs() {
        for (Map.Entry<String, HeldInput> entry : this.heldInputs.entrySet()) {
            HeldInput input = entry.getValue();
            if (input.tapTicks > 0) {
                input.tapTicks--;
                if (input.tapTicks == 0) {
                    input.pressed = false;
                }
            }
        }
    }

    private void updateMouseLook(ClientPlayerEntity player) {
        if (player == null) {
            return;
        }
        HeldInput mouse = this.heldInputs.get("__mouse__");
        if (mouse == null) {
            return;
        }
        if (Math.abs(mouse.yawDelta) > LOOK_SNAP_EPSILON) {
            float applied = MathHelper.clamp(mouse.yawDelta, -LOOK_MAX_YAW_STEP, LOOK_MAX_YAW_STEP);
            player.setYaw(player.getYaw() + applied);
            mouse.yawDelta -= applied;
        } else {
            mouse.yawDelta = 0.0F;
        }
        if (Math.abs(mouse.pitchDelta) > LOOK_SNAP_EPSILON) {
            float applied = MathHelper.clamp(mouse.pitchDelta, -LOOK_MAX_PITCH_STEP, LOOK_MAX_PITCH_STEP);
            player.setPitch(MathHelper.clamp(player.getPitch() + applied, -90.0F, 90.0F));
            mouse.pitchDelta -= applied;
        } else {
            mouse.pitchDelta = 0.0F;
        }
    }

    private boolean isInputPressed(String keyId) {
        HeldInput input = this.heldInputs.get(normalizeInputKey(keyId));
        return input != null && input.pressed;
    }

    private void applyKeyState(KeyBinding keyBinding, boolean pressed) {
        if (keyBinding != null) {
            keyBinding.setPressed(pressed);
        }
    }

    private KeyBinding resolveKeyBinding(MinecraftClient client, String keyId) {
        return switch (normalizeInputKey(keyId)) {
            case "forward", "straight", "w" -> client.options.forwardKey;
            case "back", "backward", "s" -> client.options.backKey;
            case "left", "a" -> client.options.leftKey;
            case "right", "d" -> client.options.rightKey;
            case "jump", "space" -> client.options.jumpKey;
            case "sneak", "shift", "crouch" -> client.options.sneakKey;
            case "sprint", "ctrl" -> client.options.sprintKey;
            case "attack", "left_click", "leftclick" -> client.options.attackKey;
            case "use", "right_click", "rightclick" -> client.options.useKey;
            case "swap_hands", "swap", "f" -> client.options.swapHandsKey;
            case "drop", "q" -> client.options.dropKey;
            case "pick_block", "pick", "middle_click" -> client.options.pickItemKey;
            case "perspective", "third_person", "camera" -> client.options.togglePerspectiveKey;
            case "inventory", "e" -> client.options.inventoryKey;
            default -> null;
        };
    }

    private String normalizeInputKey(String keyId) {
        return keyId == null ? "" : keyId.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isGameplayInput(String keyId) {
        return switch (normalizeInputKey(keyId)) {
            case "forward", "straight", "w", "back", "backward", "s", "left", "a", "right", "d",
                 "jump", "space", "sneak", "shift", "crouch", "sprint", "ctrl",
                 "attack", "left_click", "leftclick", "use", "right_click", "rightclick" -> true;
            default -> false;
        };
    }

    private void releaseAllInputs() {
        for (HeldInput input : this.heldInputs.values()) {
            input.pressed = false;
            input.tapTicks = 0;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            applyKeyState(resolveKeyBinding(client, "forward"), false);
            applyKeyState(resolveKeyBinding(client, "back"), false);
            applyKeyState(resolveKeyBinding(client, "left"), false);
            applyKeyState(resolveKeyBinding(client, "right"), false);
            applyKeyState(resolveKeyBinding(client, "jump"), false);
            applyKeyState(resolveKeyBinding(client, "sneak"), false);
            applyKeyState(resolveKeyBinding(client, "sprint"), false);
            applyKeyState(resolveKeyBinding(client, "attack"), false);
            applyKeyState(resolveKeyBinding(client, "use"), false);
            applyKeyState(resolveKeyBinding(client, "inventory"), false);
            applyKeyState(resolveKeyBinding(client, "swap_hands"), false);
            applyKeyState(resolveKeyBinding(client, "drop"), false);
            applyKeyState(resolveKeyBinding(client, "pick_block"), false);
            applyKeyState(resolveKeyBinding(client, "perspective"), false);
            applyPlayerInputState(client.player);
        }
    }

    private void releaseMovementInputs() {
        setHeldInput("forward", false);
        setHeldInput("back", false);
        setHeldInput("left", false);
        setHeldInput("right", false);
        setHeldInput("jump", false);
        setHeldInput("sprint", false);
        setHeldInput("sneak", false);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            applyKeyState(resolveKeyBinding(client, "forward"), false);
            applyKeyState(resolveKeyBinding(client, "back"), false);
            applyKeyState(resolveKeyBinding(client, "left"), false);
            applyKeyState(resolveKeyBinding(client, "right"), false);
            applyKeyState(resolveKeyBinding(client, "jump"), false);
            applyKeyState(resolveKeyBinding(client, "sprint"), false);
            applyKeyState(resolveKeyBinding(client, "sneak"), false);
            applyPlayerInputState(client.player);
        }
    }

    private void beginRelativeMovement(Map<String, Object> state, ClientPlayerEntity player, String direction, int ticks, boolean sprint) {
        clearMovementState(state);
        state.put("move.relative.active", true);
        state.put("move.relative.direction", direction);
        state.put("move.relative.ticks", ticks);
        state.put("move.relative.start_x", player == null ? 0.0D : player.getX());
        state.put("move.relative.start_z", player == null ? 0.0D : player.getZ());
        state.put("move.relative.sprint", sprint);
        AutomationReporter.mem("move.relative.active", true);
        AutomationReporter.mem("move.relative.direction", direction);
        AutomationReporter.mem("move.relative.ticks", ticks);
    }

    private void beginTargetMovement(Map<String, Object> state, Vec3d target, double stopDistance, boolean sprint) {
        clearMovementState(state);
        state.put("move.target.active", true);
        state.put("move.target.x", target.x);
        state.put("move.target.y", target.y);
        state.put("move.target.z", target.z);
        state.put("move.target.stop_distance", stopDistance);
        state.put("move.target.sprint", sprint);
        AutomationReporter.mem("move.target.active", true);
        AutomationReporter.mem("move.target.x", target.x);
        AutomationReporter.mem("move.target.y", target.y);
        AutomationReporter.mem("move.target.z", target.z);
        writeStateValue(state, "path.status", "tracking");
        writeStateValue(state, "path.stop_distance", stopDistance);
    }

    private void beginFollowTargetMovement(Map<String, Object> state, Entity entity, double stopDistance, boolean sprint) {
        clearMovementState(state);
        state.put("move.target.active", true);
        state.put("move.target.follow", true);
        state.put("move.target.uuid", entity.getUuidAsString());
        state.put("move.target.x", entity.getX());
        state.put("move.target.y", entity.getY());
        state.put("move.target.z", entity.getZ());
        state.put("move.target.stop_distance", stopDistance);
        state.put("move.target.sprint", sprint);
        AutomationReporter.mem("move.target.active", true);
        AutomationReporter.mem("move.target.follow", true);
        AutomationReporter.mem("move.target.uuid", entity.getUuidAsString());
        AutomationReporter.mem("move.target.x", entity.getX());
        AutomationReporter.mem("move.target.y", entity.getY());
        AutomationReporter.mem("move.target.z", entity.getZ());
        writeStateValue(state, "path.status", "following");
        writeStateValue(state, "path.stop_distance", stopDistance);
    }

    private void clearMovementState(Map<String, Object> state) {
        state.remove("move.relative.active");
        state.remove("move.relative.direction");
        state.remove("move.relative.ticks");
        state.remove("move.relative.start_x");
        state.remove("move.relative.start_z");
        state.remove("move.relative.sprint");
        state.remove("move.target.active");
        state.remove("move.target.mode");
        state.remove("move.target.uuid");
        state.remove("move.target.x");
        state.remove("move.target.y");
        state.remove("move.target.z");
        state.remove("move.target.start_x");
        state.remove("move.target.start_z");
        state.remove("move.target.start_distance");
        state.remove("move.target.displacement");
        state.remove("move.target.required_displacement");
        state.remove("move.target.relative");
        state.remove("move.target.use_y");
        state.remove("move.target.stop_distance");
        state.remove("move.target.sprint");
        state.remove("move.target.precise");
        state.remove("move.target.precision_distance");
        state.remove("move.target.slow_distance");
        state.remove("move.nav.phase");
        state.remove("move.nav.blocked_ticks");
        state.remove("move.nav.last_distance");
        state.remove("move.nav.strafe_mode");
        state.remove("move.nav.steer_x");
        state.remove("move.nav.steer_z");
        state.remove("move.nav.steer_lock_ticks");
        state.remove("move.nav.policy");
        state.remove("move.nav.persistent");
        state.remove("move.nav.allow_break_blocks");
        state.remove("move.nav.allow_place_blocks");
        state.remove("move.nav.allow_combat_clear");
        state.remove("move.nav.jump_window");
        state.remove("move.nav.swim");
        state.remove("move.nav.avoid_drop");
        state.remove("move.nav.fail_reason");
        state.remove("move.failed");
        state.remove("move.failure_reason");
        state.remove("move.nav.escape_ticks");
        state.remove("move.nav.escape_side");
        state.remove("move.nav.waypoint.active");
        state.remove("move.nav.waypoint.x");
        state.remove("move.nav.waypoint.y");
        state.remove("move.nav.waypoint.z");
        state.remove("move.nav.waypoint.reason");
        state.remove("move.nav.waypoint.score");
        state.remove("move.nav.path_style");
        state.remove("move.nav.last_x");
        state.remove("move.nav.last_z");
        state.remove("move.nav.stall_ticks");
        state.remove("move.nav.decision_ticks");
        state.remove("move.nav.decision_x");
        state.remove("move.nav.decision_z");
        state.remove("move.nav.decision_look_x");
        state.remove("move.nav.decision_look_y");
        state.remove("move.nav.decision_look_z");
        state.remove("move.nav.decision_phase");
        state.remove("move.nav.decision_strafe");
        state.remove("move.nav.decision_sprint");
        state.remove("move.nav.clear_block");
        state.remove("move.nav.clear_block.x");
        state.remove("move.nav.clear_block.y");
        state.remove("move.nav.clear_block.z");
        state.remove("move.nav.clear_block.started");
        state.remove("move.nav.clear_block.ticks");
        state.remove("move.nav.route.active");
        state.remove("move.nav.route.x");
        state.remove("move.nav.route.y");
        state.remove("move.nav.route.z");
        state.remove("move.nav.route.nodes");
        state.remove("move.nav.route.expansions");
        state.remove("move.nav.route.reason");
        state.remove("move.recovery.mode");
        state.remove("move.recovery.tactic");
        state.remove("move.recovery.reason");
        state.remove("move.recovery.attempts");
        state.remove("parkour.active");
        state.remove("parkour.phase");
        state.remove("parkour.takeoff.x");
        state.remove("parkour.takeoff.y");
        state.remove("parkour.takeoff.z");
        state.remove("parkour.landing.x");
        state.remove("parkour.landing.y");
        state.remove("parkour.landing.z");
        state.remove("parkour.sprint");
        state.remove("parkour.charge_ticks");
        state.remove("parkour.air_ticks");
        state.remove("parkour.approach_ticks");
        state.remove("parkour.jump_started");
        state.remove("parkour.align_ticks");
        state.remove("path.lookups");
    }

    private void beginPointNavigation(Map<String, Object> state, ClientPlayerEntity player, Vec3d target, double stopDistance, boolean sprint, boolean useY, Map<String, Object> params) {
        boolean relativeTarget = Boolean.TRUE.equals(state.get("current.target.relative")) || "relative_target".equals(stringParam(state, "current.target.id", ""));
        double requiredDisplacement = relativeTarget ? doubleState(state, "current.target.relative_distance", 0.0D) : 0.0D;
        clearMovementState(state);
        releaseMovementInputs();
        state.put("move.target.active", true);
        state.put("move.target.mode", "point");
        state.put("move.target.x", target.x);
        state.put("move.target.y", target.y);
        state.put("move.target.z", target.z);
        state.put("move.target.start_x", player == null ? target.x : player.getX());
        state.put("move.target.start_z", player == null ? target.z : player.getZ());
        state.put("move.target.use_y", useY);
        state.put("move.target.stop_distance", stopDistance);
        state.put("move.target.start_distance", player == null ? 0.0D : navigationDistance(player.getPos(), target, useY));
        if (relativeTarget) {
            state.put("move.target.relative", true);
            state.put("move.target.required_displacement", requiredDisplacement);
        }
        state.put("move.target.sprint", sprint);
        state.put("move.target.precise", false);
        state.put("move.nav.phase", "navigating");
        state.put("move.nav.blocked_ticks", 0);
        state.put("move.nav.last_distance", Double.MAX_VALUE);
        state.put("move.nav.strafe_mode", "none");
        state.put("move.nav.steer_x", 0.0D);
        state.put("move.nav.steer_z", 0.0D);
        state.put("move.nav.steer_lock_ticks", 0);
        installMovementPolicy(state, params);
        state.put("move.nav.jump_window", 0);
        state.put("move.nav.swim", false);
        state.put("move.nav.avoid_drop", true);
        state.put("move.nav.escape_ticks", 0);
        state.put("move.nav.escape_side", "none");
        state.put("move.nav.stall_ticks", 0);
        state.put("move.nav.decision_ticks", 0);
        AutomationReporter.mem("move.target.active", true);
        AutomationReporter.mem("move.target.mode", "point");
        AutomationReporter.mem("move.target.x", target.x);
        AutomationReporter.mem("move.target.y", target.y);
        AutomationReporter.mem("move.target.z", target.z);
        AutomationReporter.mem("move.target.use_y", useY);
        writeStateValue(state, "path.status", "navigating");
        writeStateValue(state, "path.stop_distance", stopDistance);
    }

    private void beginFollowNavigation(Map<String, Object> state, ClientPlayerEntity player, Entity entity, double stopDistance, boolean sprint, Map<String, Object> params) {
        clearMovementState(state);
        state.put("move.target.active", true);
        state.put("move.target.mode", "follow");
        state.put("move.target.uuid", entity.getUuidAsString());
        state.put("move.target.x", entity.getX());
        state.put("move.target.y", entity.getY());
        state.put("move.target.z", entity.getZ());
        state.put("move.target.start_x", player == null ? entity.getX() : player.getX());
        state.put("move.target.start_z", player == null ? entity.getZ() : player.getZ());
        state.put("move.target.use_y", false);
        state.put("move.target.stop_distance", stopDistance);
        state.put("move.target.start_distance", player == null ? 0.0D : navigationDistance(player.getPos(), entity.getPos(), false));
        state.put("move.target.sprint", sprint);
        state.put("move.nav.phase", "following");
        state.put("move.nav.blocked_ticks", 0);
        state.put("move.nav.last_distance", Double.MAX_VALUE);
        state.put("move.nav.strafe_mode", "none");
        state.put("move.nav.steer_x", 0.0D);
        state.put("move.nav.steer_z", 0.0D);
        state.put("move.nav.steer_lock_ticks", 0);
        installMovementPolicy(state, params);
        state.put("move.nav.jump_window", 0);
        state.put("move.nav.swim", false);
        state.put("move.nav.avoid_drop", true);
        state.put("move.nav.escape_ticks", 0);
        state.put("move.nav.escape_side", "none");
        state.put("move.nav.stall_ticks", 0);
        state.put("move.nav.decision_ticks", 0);
        AutomationReporter.mem("move.target.active", true);
        AutomationReporter.mem("move.target.mode", "follow");
        AutomationReporter.mem("move.target.uuid", entity.getUuidAsString());
        AutomationReporter.mem("move.target.x", entity.getX());
        AutomationReporter.mem("move.target.y", entity.getY());
        AutomationReporter.mem("move.target.z", entity.getZ());
        writeStateValue(state, "path.status", "following");
        writeStateValue(state, "path.stop_distance", stopDistance);
    }

    private void installMovementPolicy(Map<String, Object> state, Map<String, Object> params) {
        String policy = stringParam(params, "movement.policy", stringParam(params, "style.id", "smart")).toLowerCase(Locale.ROOT);
        boolean persistent = booleanParam(params, "movement.persistent", false)
                || policy.contains("persistent")
                || policy.contains("unstoppable")
                || policy.contains("everything");
        state.put("move.nav.policy", policy);
        state.put("move.nav.persistent", persistent);
        state.put("move.nav.allow_break_blocks", persistent || booleanParam(params, "movement.allow_break_blocks", false));
        state.put("move.nav.allow_place_blocks", persistent || booleanParam(params, "movement.allow_place_blocks", false));
        state.put("move.nav.allow_combat_clear", persistent || booleanParam(params, "movement.allow_combat_clear", false));
        writeStateValue(state, "move.nav.policy", policy);
        writeStateValue(state, "move.nav.persistent", persistent);
    }

    private boolean executeNamedGoal(String goalName, Map<String, Object> params, Map<String, Object> state, MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        String normalized = normalizeGoalName(goalName);
        if (normalized.equals("wander") || normalized.equals("wandergoal") || normalized.equals("wanderaroundgoal")) {
            String[] directions = {"forward", "left", "right", "back"};
            int index = (int) Math.floorMod(world.getTime() + player.getBlockPos().asLong(), directions.length);
            int ticks = Math.max(6, intParam(params, "goal.ticks", 42));
            beginRelativeMovement(state, player, directions[index], ticks, Boolean.TRUE.equals(params.get("sprint")));
            writeStateValue(state, "goal.adapter", "minecraft.wander");
            writeStateValue(state, "goal.phase", "moving");
            return true;
        }

        Entity entity = resolveTargetEntity(world, params, state);
        if (entity == null) {
            entity = crosshairEntity(client);
        }
        if (entity == null) {
            writeStateValue(state, "goal.failure_reason", "missing_target_entity");
            return false;
        }
        state.put("current.target.uuid", entity.getUuidAsString());
        state.put("current.target.name", entity.getName().getString());
        state.put("current.target.x", entity.getX());
        state.put("current.target.y", entity.getY());
        state.put("current.target.z", entity.getZ());
        AutomationReporter.mem("current.target.uuid", entity.getUuidAsString());
        AutomationReporter.mem("current.target.name", entity.getName().getString());

        if (normalized.equals("follow") || normalized.equals("followgoal") || normalized.equals("followownergoal")) {
            double stopDistance = doubleParam(params, "stop.distance", 2.0D);
            beginFollowNavigation(state, player, entity, stopDistance, Boolean.TRUE.equals(params.get("sprint")), params);
            writeStateValue(state, "goal.adapter", "minecraft.follow");
            writeStateValue(state, "goal.phase", "following");
            return true;
        }
        if (normalized.equals("attack") || normalized.equals("attackgoal") || normalized.equals("meleeattackgoal")) {
            double stopDistance = doubleParam(params, "stop.distance", 2.0D);
            double attackRange = doubleParam(params, "attack.range", 3.4D);
            beginFollowNavigation(state, player, entity, stopDistance, true, params);
            state.put("attack.active", true);
            state.put("attack.target.uuid", entity.getUuidAsString());
            state.put("attack.phase", "prepare");
            state.put("attack.ticks", 0);
            state.put("attack.crit.enabled", true);
            state.put("attack.crit.jump_wait_ticks", 3);
            state.put("attack.range", attackRange);
            AutomationReporter.mem("attack.active", true);
            AutomationReporter.mem("attack.phase", "prepare");
            writeStateValue(state, "goal.adapter", "minecraft.attack");
            writeStateValue(state, "goal.phase", "attack_and_follow");
            return true;
        }
        writeStateValue(state, "goal.failure_reason", "unknown_goal_" + normalized);
        return false;
    }

    private static String normalizeGoalName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace("_", "").replace(" ", "");
    }

    private static Entity crosshairEntity(MinecraftClient client) {
        if (client == null || !(client.crosshairTarget instanceof EntityHitResult hitResult)) {
            return null;
        }
        return hitResult.getEntity();
    }

    private void writeMovementResult(Map<String, Object> state, boolean ok, String status, String reason) {
        writeStateValue(state, "result.ok", ok);
        writeStateValue(state, "result.status", status);
        writeStateValue(state, "result.reason", reason);
        writeStateValue(state, "result.retryable", Set.of("blocked", "stuck", "unsafe", "needs_recovery", "needs_replan", "running").contains(status));
        writeStateValue(state, "result.debug_summary", status + ":" + reason);
    }

    private void writeMovementTargetDetails(Map<String, Object> state, String type, Vec3d stand, Vec3d look, double radius, double confidence, double danger, String reason) {
        writeStateValue(state, "movement.target.type", type);
        writeStateValue(state, "movement.target.stand.x", round(stand.x));
        writeStateValue(state, "movement.target.stand.y", round(stand.y));
        writeStateValue(state, "movement.target.stand.z", round(stand.z));
        writeStateValue(state, "movement.target.look.x", round(look.x));
        writeStateValue(state, "movement.target.look.y", round(look.y));
        writeStateValue(state, "movement.target.look.z", round(look.z));
        writeStateValue(state, "movement.target.approach_radius", radius);
        writeStateValue(state, "movement.target.confidence", round(confidence));
        writeStateValue(state, "movement.target.danger_score", round(danger));
        writeStateValue(state, "movement.resolve.reason", reason);
        AutomationReporter.info("[move]", "target resolved: " + type + " danger=" + round(danger) + " confidence=" + round(confidence));
    }

    private void writeMovementSnapshot(Map<String, Object> state, ClientPlayerEntity player, ClientWorld world) {
        BlockPos feet = player.getBlockPos();
        writeStateValue(state, "player.x", round(player.getX()));
        writeStateValue(state, "player.y", round(player.getY()));
        writeStateValue(state, "player.z", round(player.getZ()));
        writeStateValue(state, "player.velocity.x", round(player.getVelocity().x));
        writeStateValue(state, "player.velocity.y", round(player.getVelocity().y));
        writeStateValue(state, "player.velocity.z", round(player.getVelocity().z));
        writeStateValue(state, "player.yaw", round(player.getYaw()));
        writeStateValue(state, "player.pitch", round(player.getPitch()));
        writeStateValue(state, "player.on_ground", player.isOnGround());
        writeStateValue(state, "player.in_water", player.isTouchingWater());
        writeStateValue(state, "player.submerged", player.isSubmergedInWater());
        writeStateValue(state, "player.climbing", player.isClimbing());
        writeStateValue(state, "player.fall_distance", round(player.fallDistance));
        writeStateValue(state, "player.block.feet", blockId(world, feet));
        writeStateValue(state, "player.block.head", blockId(world, feet.up()));
        writeStateValue(state, "player.block.under", blockId(world, feet.down()));
        writeStateValue(state, "player.pocket_risk", round(pocketRisk(world, feet)));
    }

    private String blockId(ClientWorld world, BlockPos pos) {
        Identifier id = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
        return id == null ? "unknown" : id.toString();
    }

    private MovementProgress evaluateMovementProgress(Map<String, Object> state, ClientPlayerEntity player, double distance) {
        double lastDistance = doubleState(state, "move.progress.last_distance", Double.MAX_VALUE);
        double lastX = doubleState(state, "move.progress.last_x", player.getX());
        double lastY = doubleState(state, "move.progress.last_y", player.getY());
        double lastZ = doubleState(state, "move.progress.last_z", player.getZ());
        double moved = player.getPos().distanceTo(new Vec3d(lastX, lastY, lastZ));
        boolean distanceImproved = distance < lastDistance - NAV_PROGRESS_EPSILON;
        boolean positionChanged = moved > 0.025D;
        boolean oscillating = isOscillating(state, player.getPos());
        int noProgress = (distanceImproved || positionChanged) && !oscillating ? 0 : intState(state, "move.progress.no_progress_ticks", 0) + 1;
        writeStateValue(state, "move.progress.last_distance", distance);
        writeStateValue(state, "move.progress.last_x", player.getX());
        writeStateValue(state, "move.progress.last_y", player.getY());
        writeStateValue(state, "move.progress.last_z", player.getZ());
        writeStateValue(state, "move.progress.no_progress_ticks", noProgress);
        if (player.isOnGround() && !player.horizontalCollision && !player.isTouchingWater()) {
            writeStateValue(state, "move.last_safe.x", player.getX());
            writeStateValue(state, "move.last_safe.y", player.getY());
            writeStateValue(state, "move.last_safe.z", player.getZ());
        }
        boolean ok = noProgress < NAV_STUCK_TICKS && !oscillating;
        String reason = ok ? "progressing" : oscillating ? "oscillating" : "no_progress";
        return new MovementProgress(ok, reason, distanceImproved, positionChanged, oscillating, noProgress);
    }

    private boolean isOscillating(Map<String, Object> state, Vec3d pos) {
        String key = "move.trace." + (intState(state, "move.trace.index", 0) % NAV_TRACE_WINDOW);
        String current = Math.round(pos.x * 8.0D) + ":" + Math.round(pos.y * 8.0D) + ":" + Math.round(pos.z * 8.0D);
        int matches = 0;
        for (int i = 0; i < NAV_TRACE_WINDOW; i++) {
            if (current.equals(state.get("move.trace." + i))) {
                matches++;
            }
        }
        state.put(key, current);
        state.put("move.trace.index", intState(state, "move.trace.index", 0) + 1);
        return matches >= 4;
    }

    private void recordMovementTrace(Map<String, Object> state, ClientPlayerEntity player, double distance) {
        MovementProgress progress = evaluateMovementProgress(state, player, distance);
        writeProgressState(state, progress);
    }

    private void writeProgressState(Map<String, Object> state, MovementProgress progress) {
        writeStateValue(state, "move.progress.ok", progress.ok());
        writeStateValue(state, "move.progress.reason", progress.reason());
        writeStateValue(state, "move.progress.distance_improved", progress.distanceImproved());
        writeStateValue(state, "move.progress.position_changed", progress.positionChanged());
        writeStateValue(state, "move.progress.oscillating", progress.oscillating());
        writeStateValue(state, "move.progress.no_progress_ticks", progress.noProgressTicks());
        writeMovementResult(state, progress.ok(), progress.ok() ? "running" : "needs_recovery", progress.reason());
    }

    private MovementSafety movementSafetyAt(ClientWorld world, BlockPos feet, int maxDrop) {
        if (isDangerBlock(world, feet) || isDangerBlock(world, feet.down())) {
            return new MovementSafety(false, "danger_block", true, false, true, pocketRisk(world, feet));
        }
        if (!canPassThrough(world, feet) || !canPassThrough(world, feet.up())) {
            return new MovementSafety(false, "body_blocked", false, false, false, 1.0D);
        }
        boolean water = isWaterBlock(world, feet) || isWaterBlock(world, feet.down());
        boolean drop = isUnsafeDrop(world, feet.down(), maxDrop);
        double pocket = pocketRisk(world, feet);
        boolean safe = !drop && pocket < 0.9D;
        String reason = safe ? "safe" : drop ? "unsafe_drop" : "pocket";
        return new MovementSafety(safe, reason, false, drop, water, pocket);
    }

    private void writeSafetyState(Map<String, Object> state, MovementSafety safety) {
        writeStateValue(state, "move.safety.safe", safety.safe());
        writeStateValue(state, "move.safety.reason", safety.reason());
        writeStateValue(state, "move.safety.danger", safety.danger());
        writeStateValue(state, "move.safety.drop", safety.drop());
        writeStateValue(state, "move.safety.water", safety.water());
        writeStateValue(state, "move.safety.pocket_risk", round(safety.pocketRisk()));
    }

    private MovementPlan planLocalMovement(ClientWorld world, ClientPlayerEntity player, Vec3d target, Map<String, Object> params, Map<String, Object> state) {
        BlockPos feet = player.getBlockPos();
        BlockPos targetFeet = BlockPos.ofFloored(target);
        MovementSafety targetSafety = movementSafetyAt(world, targetFeet, 4);
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = target.y - player.getY();
        boolean allowParkour = booleanParam(params, "movement.allow_parkour", booleanParam(params, "allow.parkour", true));
        boolean allowSprint = booleanParam(params, "movement.allow_sprint", true);
        String segment;
        boolean sprint = false;
        if (!targetSafety.safe()) {
            segment = targetSafety.drop() ? "CONTROLLED_DROP" : "WAIT_STABLE";
        } else if (player.isTouchingWater() || isWaterBlock(world, targetFeet)) {
            segment = "SWIM_TO";
        } else if (vertical > 0.75D && horizontal <= 1.8D) {
            segment = "JUMP_UP";
        } else if (vertical < -1.2D) {
            segment = "CONTROLLED_DROP";
        } else if (horizontal > 1.6D && horizontal <= 4.6D && allowParkour && !hasWalkableLine(world, feet, targetFeet)) {
            segment = horizontal > 2.4D ? "SPRINT_JUMP_GAP" : "JUMP_GAP";
            sprint = horizontal > 2.4D && allowSprint;
        } else if (isNearEdge(world, feet)) {
            segment = "SNEAK_EDGE";
        } else if (horizontal > 5.0D && allowSprint) {
            segment = "SPRINT_CLEAR";
            sprint = true;
        } else {
            segment = "WALK_TO";
        }
        boolean ok = targetSafety.safe() || "CONTROLLED_DROP".equals(segment) || "SWIM_TO".equals(segment);
        double danger = targetSafety.pocketRisk() + (targetSafety.danger() ? 1.0D : 0.0D) + (targetSafety.drop() ? 0.5D : 0.0D);
        return new MovementPlan(ok, segment, sprint, "plan_" + segment.toLowerCase(Locale.ROOT), danger, horizontal, vertical);
    }

    private boolean hasWalkableLine(ClientWorld world, BlockPos from, BlockPos to) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (steps <= 0) {
            return true;
        }
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / (double) steps;
            int x = MathHelper.floor(MathHelper.lerp(t, from.getX(), to.getX()));
            int z = MathHelper.floor(MathHelper.lerp(t, from.getZ(), to.getZ()));
            BlockPos pos = new BlockPos(x, from.getY(), z);
            if (!canStandAt(world, pos) || isUnsafeDrop(world, pos.down(), 3)) {
                return false;
            }
        }
        return true;
    }

    private boolean isNearEdge(ClientWorld world, BlockPos feet) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (isUnsafeDrop(world, feet.offset(direction).down(), 3)) {
                return true;
            }
        }
        return false;
    }

    private void writePlanState(Map<String, Object> state, MovementPlan plan) {
        writeStateValue(state, "move.segment.type", plan.segment());
        writeStateValue(state, "move.segment.danger_score", round(plan.danger()));
        writeStateValue(state, "move.segment.horizontal_distance", round(plan.horizontal()));
        writeStateValue(state, "move.segment.vertical_delta", round(plan.vertical()));
        writeStateValue(state, "move.segment.sprint", plan.sprint());
        writeMovementResult(state, plan.ok(), plan.ok() ? "running" : "unsafe", plan.reason());
        AutomationReporter.info("[move]", "path planned: " + plan.segment() + " danger=" + round(plan.danger()));
    }

    private String chooseRecoveryTactic(Map<String, Object> state, ClientPlayerEntity player, ClientWorld world) {
        int attempts = intState(state, "move.recovery.attempts", 0);
        if (attempts >= NAV_RECOVERY_LIMIT) {
            return "fail";
        }
        BlockPos feet = player.getBlockPos();
        if (player.isTouchingWater() || player.isSubmergedInWater()) {
            return "swim_to_edge";
        }
        if (player.fallDistance > 2.5F || !player.isOnGround()) {
            return "wait_for_ground";
        }
        if (isTreePocket(world, feet)) {
            return "escape_tree_pocket";
        }
        if (pocketRisk(world, feet) >= 0.75D) {
            return "jump_out_of_pocket";
        }
        if (player.horizontalCollision) {
            return attempts % 2 == 0 ? "route_around_obstacle" : attempts % 3 == 0 ? "strafe_left" : "strafe_right";
        }
        if (Boolean.TRUE.equals(state.get("move.progress.oscillating"))) {
            return "step_backward";
        }
        return attempts % 3 == 0 ? "recenter" : attempts % 3 == 1 ? "strafe_left" : "jump_once";
    }

    private boolean runRecoveryTactic(Map<String, Object> state, ClientPlayerEntity player, ClientWorld world, String tactic) {
        String selected = "auto".equals(tactic) ? chooseRecoveryTactic(state, player, world) : tactic;
        writeStateValue(state, "move.recovery.mode", selected);
        writeStateValue(state, "move.recovery.attempts", intState(state, "move.recovery.attempts", 0) + 1);
        releaseMovementInputs();
        switch (selected) {
            case "swim_to_edge" -> {
                setHeldInput("jump", true);
                Vec3d edge = nearestSafeHorizontalDirection(world, player.getBlockPos());
                applySwimToward(player, edge, player.getPos().add(edge));
            }
            case "wait_for_ground" -> releaseMovementInputs();
            case "jump_out_of_pocket", "escape_tree_pocket", "jump_once" -> {
                if (player.isOnGround()) {
                    player.jump();
                }
                applyHorizontalMotion(player, bestRecoveryDirection(world, player, state), false);
            }
            case "route_around_obstacle" -> applyHorizontalMotion(player, bestRecoveryDirection(world, player, state), false);
            case "strafe_left" -> applyHorizontalMotion(player, rotateLeft(movementDirectionFromState(state)), false);
            case "strafe_right" -> applyHorizontalMotion(player, rotateRight(movementDirectionFromState(state)), false);
            case "step_backward" -> applyHorizontalMotion(player, movementDirectionFromState(state).multiply(-1.0D), false);
            case "recenter" -> {
                Vec3d center = Vec3d.ofCenter(player.getBlockPos());
                applyMoveToward(player, new Vec3d(center.x, player.getY(), center.z), false);
            }
            default -> {
                state.put("move.recovery.reason", "no_recovery_available");
                return false;
            }
        }
        state.put("move.recovery.reason", selected);
        AutomationReporter.info("[move]", "recovery running: " + selected);
        return true;
    }

    private Vec3d movementDirectionFromState(Map<String, Object> state) {
        Vec3d direction = new Vec3d(doubleState(state, "move.target.x", 0.0D) - doubleState(state, "move.progress.last_x", 0.0D), 0.0D, doubleState(state, "move.target.z", 0.0D) - doubleState(state, "move.progress.last_z", 0.0D));
        if (direction.lengthSquared() < 1.0E-4D) {
            return new Vec3d(0.0D, 0.0D, 1.0D);
        }
        return direction.normalize();
    }

    private Vec3d nearestSafeHorizontalDirection(ClientWorld world, BlockPos feet) {
        Vec3d best = Vec3d.ZERO;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            Vec3d vec = Vec3d.of(direction.getVector());
            CandidateSafety safety = candidateSafety(world, feet, vec);
            if (!safety.safe()) {
                continue;
            }
            double score = safety.clearance() * 2.0D - safety.pocketRisk() * 8.0D - naturalPocketPressure(world, feet.offset(direction)) * 1.5D;
            if (score > bestScore) {
                bestScore = score;
                best = vec;
            }
        }
        return best.lengthSquared() < 1.0E-4D ? new Vec3d(0.0D, 0.0D, 1.0D) : best.normalize();
    }

    private Vec3d bestRecoveryDirection(ClientWorld world, ClientPlayerEntity player, Map<String, Object> state) {
        BlockPos feet = player.getBlockPos();
        Vec3d targetDirection = movementDirectionFromState(state);
        List<Vec3d> options = List.of(
                rotateLeft(targetDirection),
                rotateRight(targetDirection),
                targetDirection.multiply(-1.0D),
                rotateYaw(targetDirection, 135.0D),
                rotateYaw(targetDirection, -135.0D),
                nearestSafeHorizontalDirection(world, feet)
        );
        Vec3d best = Vec3d.ZERO;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Vec3d option : options) {
            if (option.lengthSquared() < 1.0E-4D) {
                continue;
            }
            Vec3d normalized = option.normalize();
            CandidateSafety safety = candidateSafety(world, feet, normalized);
            double score = safety.clearance() * 2.2D - safety.blocked() * 5.0D - safety.drop() * 8.0D - safety.pocketRisk() * 10.0D - safety.naturalBlockers() * 1.4D;
            if (safety.safe()) {
                score += 5.0D;
            }
            if (score > bestScore) {
                bestScore = score;
                best = normalized;
            }
        }
        return best.lengthSquared() < 1.0E-4D ? nearestSafeHorizontalDirection(world, feet) : best.normalize();
    }

    private ParkourAnalysis analyzeParkourJump(ClientWorld world, ClientPlayerEntity player, Vec3d landing, Map<String, Object> params) {
        Vec3d normalizedLanding = normalizeParkourLanding(world, landing);
        BlockPos landingFeet = BlockPos.ofFloored(normalizedLanding);
        MovementSafety safety = movementSafetyAt(world, landingFeet, 5);
        double horizontal = horizontalDistance(player.getPos(), normalizedLanding);
        double vertical = normalizedLanding.y - player.getY();
        boolean allowSprint = booleanParam(params, "movement.allow_sprint", booleanParam(params, "parkour.allow_sprint_jump", true));
        boolean advancedAllowed = booleanParam(params, "parkour.allow_advanced", true);
        boolean sprint = allowSprint && horizontal > PARKOUR_SINGLE_MAX_HORIZONTAL - 0.25D;
        boolean headHitAssist = hasParkourHeadHitAssist(world, player.getBlockPos(), normalizedLanding);
        boolean downwardAssist = vertical < -0.75D;
        double maxHorizontal = allowSprint ? PARKOUR_SPRINT_MAX_HORIZONTAL : PARKOUR_SINGLE_MAX_HORIZONTAL;
        if (allowSprint && advancedAllowed && (headHitAssist || downwardAssist)) {
            maxHorizontal = PARKOUR_ADVANCED_MAX_HORIZONTAL;
        }
        boolean distancePossible = horizontal <= maxHorizontal && horizontal >= 0.65D;
        boolean verticalPossible = vertical <= 1.25D && vertical >= -4.0D;
        boolean ceilingClear = canPassThrough(world, player.getBlockPos().up(2)) || headHitAssist;
        boolean landingClear = canStandAt(world, landingFeet);
        boolean flightClear = isParkourFlightPathClear(world, player.getPos(), normalizedLanding);
        boolean possible = safety.safe() && landingClear && distancePossible && verticalPossible && ceilingClear && flightClear;
        String reason = possible ? (headHitAssist ? "advanced_headhit_jump_possible" : downwardAssist ? "downward_sprint_jump_possible" : "jump_possible") : !landingClear ? "landing_not_standable" : !safety.safe() ? safety.reason() : !distancePossible ? (horizontal < 0.65D ? "landing_too_close" : "gap_too_far") : !verticalPossible ? (vertical > 1.25D ? "landing_too_high" : "landing_too_low") : !ceilingClear ? "ceiling_blocked" : "flight_path_blocked";
        return new ParkourAnalysis(possible, reason, sprint, horizontal, vertical, safety.pocketRisk());
    }

    private Vec3d normalizeParkourLanding(ClientWorld world, Vec3d target) {
        BlockPos base = BlockPos.ofFloored(target);
        if (canStandAt(world, base)) {
            return new Vec3d(base.getX() + 0.5D, base.getY(), base.getZ() + 0.5D);
        }
        if (canStandAt(world, base.up())) {
            return new Vec3d(base.getX() + 0.5D, base.getY() + 1.0D, base.getZ() + 0.5D);
        }
        if (canStandAt(world, base.down())) {
            return new Vec3d(base.getX() + 0.5D, base.getY() - 1.0D, base.getZ() + 0.5D);
        }
        return new Vec3d(target.x, target.y, target.z);
    }

    private Vec3d findParkourTakeoff(ClientWorld world, ClientPlayerEntity player, Vec3d landing, ParkourAnalysis analysis) {
        Vec3d horizontal = new Vec3d(landing.x - player.getX(), 0.0D, landing.z - player.getZ());
        if (horizontal.lengthSquared() < 1.0E-4D) {
            return null;
        }
        Vec3d direction = horizontal.normalize();
        BlockPos playerFeet = player.getBlockPos();
        if (canUseParkourTakeoff(world, playerFeet, landing, direction, analysis)) {
            return new Vec3d(player.getX(), player.getY(), player.getZ());
        }
        double maxDistance = analysis.sprint() ? PARKOUR_SPRINT_MAX_HORIZONTAL : PARKOUR_SINGLE_MAX_HORIZONTAL;
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (double travel = Math.min(maxDistance, Math.max(1.15D, analysis.horizontal() - 0.2D)); travel >= 1.0D; travel -= 0.25D) {
            Vec3d sample = landing.subtract(direction.multiply(travel));
            BlockPos base = BlockPos.ofFloored(sample);
            for (int dy = 1; dy >= -1; dy--) {
                BlockPos feet = base.add(0, dy, 0);
                if (!canUseParkourTakeoff(world, feet, landing, direction, analysis)) {
                    continue;
                }
                Vec3d candidate = new Vec3d(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
                double jumpDistance = horizontalDistance(candidate, landing);
                if (jumpDistance > maxDistance || jumpDistance < 0.85D) {
                    continue;
                }
                double score = player.getPos().squaredDistanceTo(candidate) + Math.abs(jumpDistance - Math.min(analysis.horizontal(), maxDistance - 0.25D));
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean canUseParkourTakeoff(ClientWorld world, BlockPos feet, Vec3d landing, Vec3d direction, ParkourAnalysis analysis) {
        if (!canStandAt(world, feet)) {
            return false;
        }
        if (!canPassThrough(world, feet.up(2))) {
            return false;
        }
        if (movementSafetyAt(world, feet, 3).danger()) {
            return false;
        }
        Vec3d takeoff = new Vec3d(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        double horizontal = horizontalDistance(takeoff, landing);
        double maxHorizontal = analysis.sprint() && analysis.reason().contains("advanced") ? PARKOUR_ADVANCED_MAX_HORIZONTAL : analysis.sprint() ? PARKOUR_SPRINT_MAX_HORIZONTAL : PARKOUR_SINGLE_MAX_HORIZONTAL;
        if (horizontal < 0.85D || horizontal > maxHorizontal) {
            return false;
        }
        return isParkourFlightPathClear(world, takeoff, landing);
    }

    private boolean isParkourFlightPathClear(ClientWorld world, Vec3d from, Vec3d landing) {
        Vec3d delta = new Vec3d(landing.x - from.x, 0.0D, landing.z - from.z);
        double horizontal = delta.length();
        if (horizontal < 0.5D) {
            return true;
        }
        Vec3d direction = delta.normalize();
        int samples = Math.max(2, (int) Math.ceil(horizontal / 0.35D));
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            Vec3d sample = from.add(direction.multiply(horizontal * t));
            double y = MathHelper.lerp(t, from.y, landing.y);
            double arcHeight = Math.sin(Math.PI * t) * 0.95D;
            BlockPos body = BlockPos.ofFloored(sample.x, y + 0.65D + arcHeight * 0.35D, sample.z);
            BlockPos head = BlockPos.ofFloored(sample.x, y + 1.65D + arcHeight, sample.z);
            if (!canPassThrough(world, body) || !canPassThrough(world, head)) {
                return false;
            }
        }
        return true;
    }

    private void updateParkourPhase(Map<String, Object> state, ClientPlayerEntity player, ClientWorld world, Vec3d target) {
        Vec3d landing = new Vec3d(doubleState(state, "parkour.landing.x", target.x), doubleState(state, "parkour.landing.y", target.y), doubleState(state, "parkour.landing.z", target.z));
        Vec3d takeoff = new Vec3d(doubleState(state, "parkour.takeoff.x", player.getX()), doubleState(state, "parkour.takeoff.y", player.getY()), doubleState(state, "parkour.takeoff.z", player.getZ()));
        boolean sprint = Boolean.TRUE.equals(state.get("parkour.sprint"));
        String phase = stringParam(state, "parkour.phase", "approach_takeoff");
        double landingDistance = horizontalDistance(player.getPos(), landing);
        double takeoffDistance = horizontalDistance(player.getPos(), takeoff);
        writeStateValue(state, "parkour.distance_remaining", round(landingDistance));
        writeStateValue(state, "parkour.takeoff_distance", round(takeoffDistance));

        if ("approach_takeoff".equals(phase)) {
            int ticks = intState(state, "parkour.approach_ticks", 0) + 1;
            writeStateValue(state, "parkour.approach_ticks", ticks);
            if (takeoffDistance > PARKOUR_ALIGN_DISTANCE) {
                NavigatorDecision decision = buildNavigationDecision(player, world, takeoff, state, false, 0.25D, 0, 0, 0, "none");
                facePositionHorizontal(player, takeoffDistance > 1.4D ? takeoff : landing);
                applyNavigationDecision(player, decision, precisionSpeedScale(takeoffDistance, 0.18D));
                if (ticks > PARKOUR_APPROACH_TIMEOUT_TICKS) {
                    parkourFail(state, "takeoff_unreachable");
                }
                return;
            }
            releaseMovementInputs();
            writeStateValue(state, "parkour.phase", "align");
            writeStateValue(state, "parkour.align_ticks", PARKOUR_ALIGN_TICKS);
            return;
        }

        if ("align".equals(phase)) {
            facePosition(player, landing.add(0.0D, 0.35D, 0.0D));
            releaseMovementInputs();
            int alignTicks = intState(state, "parkour.align_ticks", PARKOUR_ALIGN_TICKS);
            if (alignTicks > 0) {
                writeStateValue(state, "parkour.align_ticks", alignTicks - 1);
                return;
            }
            writeStateValue(state, "parkour.phase", "charge");
            writeStateValue(state, "parkour.charge_ticks", 0);
            return;
        }

        if ("charge".equals(phase)) {
            Vec3d direction = horizontalDirection(player.getPos(), landing);
            if (direction.lengthSquared() < 1.0E-4D) {
                parkourFail(state, "bad_jump_direction");
                return;
            }
            facePosition(player, landing.add(0.0D, 0.35D, 0.0D));
            int chargeTicks = intState(state, "parkour.charge_ticks", 0) + 1;
            writeStateValue(state, "parkour.charge_ticks", chargeTicks);
            applyHorizontalMotion(player, direction, sprint, sprint ? 1.0D : 0.92D);
            int requiredCharge = sprint ? PARKOUR_SPRINT_CHARGE_TICKS : PARKOUR_SINGLE_CHARGE_TICKS;
            if (player.isOnGround() && chargeTicks >= requiredCharge) {
                player.jump();
                setHeldInput("jump", true);
                writeStateValue(state, "parkour.phase", "air_control");
                writeStateValue(state, "parkour.air_ticks", 0);
                writeStateValue(state, "parkour.jump_started", true);
                AutomationReporter.info("[move]", "parkour jump committed sprint=" + sprint);
            }
            return;
        }

        if ("air_control".equals(phase)) {
            Vec3d direction = horizontalDirection(player.getPos(), landing);
            int airTicks = intState(state, "parkour.air_ticks", 0) + 1;
            writeStateValue(state, "parkour.air_ticks", airTicks);
            facePosition(player, landing.add(0.0D, 0.25D, 0.0D));
            applyHorizontalMotion(player, direction, sprint, sprint ? 1.0D : 0.95D);
            if (airTicks > 4) {
                setHeldInput("jump", false);
            }
            if (player.isOnGround() && airTicks > 5) {
                writeStateValue(state, "parkour.phase", "landing_verify");
            } else if (airTicks > PARKOUR_AIR_TIMEOUT_TICKS) {
                parkourFail(state, "air_timeout");
            }
            return;
        }

        if ("landing_verify".equals(phase)) {
            releaseMovementInputs();
            BlockPos feet = player.getBlockPos();
            if (landingDistance <= PARKOUR_LANDING_DISTANCE + 0.25D && movementSafetyAt(world, feet, 3).safe()) {
                writeStateValue(state, "parkour.phase", "landed");
                state.put("parkour.active", false);
                state.put("move.target.active", false);
                writeMovementResult(state, true, "landed", "parkour_landing_verified");
                AutomationReporter.ok("[move]", "parkour landed");
                return;
            }
            parkourFail(state, "landing_missed");
        }
    }

    private void writeParkourAnalysis(Map<String, Object> state, ParkourAnalysis analysis) {
        writeStateValue(state, "parkour.ok", analysis.ok());
        writeStateValue(state, "parkour.reason", analysis.reason());
        writeStateValue(state, "parkour.sprint", analysis.sprint());
        writeStateValue(state, "parkour.horizontal_distance", round(analysis.horizontal()));
        writeStateValue(state, "parkour.vertical_delta", round(analysis.vertical()));
        writeStateValue(state, "parkour.landing_risk", round(analysis.landingRisk()));
        writeMovementResult(state, analysis.ok(), analysis.ok() ? "needs_parkour" : "failed", analysis.reason());
        AutomationReporter.info("[move]", "parkour analyzed: " + analysis.reason() + " sprint=" + analysis.sprint());
    }

    private void parkourFail(Map<String, Object> state, String reason) {
        releaseMovementInputs();
        writeStateValue(state, "parkour.phase", "failed");
        state.put("parkour.active", false);
        state.put("move.target.active", false);
        state.put("move.recovery.reason", reason);
        writeMovementResult(state, false, "failed", reason);
        AutomationReporter.block("[move]", "parkour -> " + reason);
    }

    private double horizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Vec3d horizontalDirection(Vec3d from, Vec3d to) {
        Vec3d direction = new Vec3d(to.x - from.x, 0.0D, to.z - from.z);
        return direction.lengthSquared() < 1.0E-4D ? Vec3d.ZERO : direction.normalize();
    }

    private void updateRelativeMovement(ActiveExecution execution, ClientPlayerEntity player) {
        Map<String, Object> state = execution.state;
        if (!Boolean.TRUE.equals(state.get("move.relative.active")) || player == null) {
            return;
        }

        int remaining = intState(state, "move.relative.ticks", 0);
        String direction = stringParam(state, "move.relative.direction", "forward");
        boolean sprint = Boolean.TRUE.equals(state.get("move.relative.sprint"));

        applyRelativeMotion(player, direction, sprint);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.relative.phase", "flow_phase", "[run ]", "move.relative.phase", "stepping");
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.relative.direction", "flow_metric", "[info]", "move.relative.direction", direction);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.relative.remaining", "flow_metric", "[info]", "move.relative.remaining_ticks", remaining);

        remaining--;
        state.put("move.relative.ticks", remaining);
        AutomationReporter.mem("move.relative.ticks", remaining);

        if (remaining <= 0) {
            double startX = doubleState(state, "move.relative.start_x", player.getX());
            double startZ = doubleState(state, "move.relative.start_z", player.getZ());
            double displacement = Math.sqrt(Math.pow(player.getX() - startX, 2.0D) + Math.pow(player.getZ() - startZ, 2.0D));
            state.remove("move.relative.active");
            state.remove("move.relative.direction");
            state.remove("move.relative.ticks");
            state.remove("move.relative.sprint");
            state.remove("move.relative.start_x");
            state.remove("move.relative.start_z");
            releaseMovementInputs();
            player.setSprinting(false);
            AutomationReporter.mem("move.relative.active", false);
            writeStateValue(state, "move.relative.displacement", round(displacement));
            if (displacement < NAV_MIN_COMPLETION_DISPLACEMENT) {
                state.put("move.failed", true);
                state.put("move.failure_reason", "no_player_displacement");
                writeStateValue(state, "path.status", "failed");
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.relative.phase", "flow_decision", "[fail]", "move.relative.phase", "no_player_displacement");
            } else {
                writeStateValue(state, "path.status", "idle");
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.relative.phase", "flow_decision", "[done]", "move.relative.phase", "complete");
            }
            execution.activeNodeId = null;
            execution.activeNodeLabel = null;
            execution.activeAction = null;
        }
    }

    private Vec3d writeRelativeTargetState(Map<String, Object> state, ClientPlayerEntity player, Map<String, Object> params) {
        releaseMovementInputs();
        String direction = stringParam(params, "direction.id", "forward").toLowerCase(Locale.ROOT);
        double distance = Math.max(0.25D, doubleParam(params, "count.value", 1.0D));
        Vec3d origin = player.getPos();
        Vec3d forward = Vec3d.fromPolar(0.0F, player.getYaw()).normalize();
        Vec3d side = new Vec3d(forward.z, 0.0D, -forward.x).normalize();
        Vec3d delta = switch (direction) {
            case "back", "backward", "backwards" -> forward.multiply(-distance);
            case "left" -> side.multiply(distance);
            case "right" -> side.multiply(-distance);
            case "north" -> new Vec3d(0.0D, 0.0D, -distance);
            case "south" -> new Vec3d(0.0D, 0.0D, distance);
            case "east" -> new Vec3d(distance, 0.0D, 0.0D);
            case "west" -> new Vec3d(-distance, 0.0D, 0.0D);
            case "up" -> new Vec3d(0.0D, distance, 0.0D);
            case "down" -> new Vec3d(0.0D, -distance, 0.0D);
            default -> forward.multiply(distance);
        };
        Vec3d target = origin.add(delta);
        boolean useY = "up".equals(direction) || "down".equals(direction);
        state.put("current.target.kind", "location");
        state.put("current.target.id", "relative_target");
        state.put("current.target.x", target.x);
        state.put("current.target.y", target.y);
        state.put("current.target.z", target.z);
        state.put("current.target.use_y", useY);
        state.put("current.target.relative", true);
        state.put("current.target.relative_direction", direction);
        state.put("current.target.relative_distance", distance);
        state.put("current.target.start_x", origin.x);
        state.put("current.target.start_z", origin.z);
        AutomationReporter.mem("current.target.kind", "location");
        AutomationReporter.mem("current.target.id", "relative_target");
        AutomationReporter.mem("current.target.x", target.x);
        AutomationReporter.mem("current.target.y", target.y);
        AutomationReporter.mem("current.target.z", target.z);
        AutomationReporter.mem("current.target.use_y", useY);
        AutomationReporter.mem("current.target.relative_distance", distance);
        AutomationCliViewModel.runtimeFlow("", "relative_target", "move.relative.start", "flow_metric", "[info]", "move.relative.start", round(origin.x) + "," + round(origin.y) + "," + round(origin.z));
        AutomationCliViewModel.runtimeFlow("", "relative_target", "move.relative.target", "flow_metric", "[info]", "move.relative.target", round(target.x) + "," + round(target.y) + "," + round(target.z));
        AutomationCliViewModel.runtimeFlow("", "relative_target", "move.relative.yaw", "flow_metric", "[info]", "move.relative.yaw", round(player.getYaw()));
        AutomationCliViewModel.runtimeFlow("", "relative_target", "move.relative.forward", "flow_metric", "[debug]", "move.relative.forward", round(forward.x) + "," + round(forward.z));
        AutomationCliViewModel.runtimeFlow("", "relative_target", "move.relative.request", "flow_metric", "[info]", "move.relative.request", direction + " " + round(distance));
        return target;
    }

    private void updateTargetMovement(ActiveExecution execution, ClientPlayerEntity player, ClientWorld world) {
        Map<String, Object> state = execution.state;
        if (!Boolean.TRUE.equals(state.get("move.target.active")) || player == null || world == null) {
            return;
        }

        String mode = stringParam(state, "move.target.mode", "point");
        Entity followedEntity = null;

        if ("follow".equals(mode)) {
            String uuid = stringParam(state, "move.target.uuid", "");
            followedEntity = findEntityByUuid(world, uuid);
            if (followedEntity == null || !followedEntity.isAlive()) {
                state.put("move.target.active", false);
                state.put("move.nav.fail_reason", "target_lost");
                writeStateValue(state, "path.status", "target_lost");
                AutomationReporter.mem("move.target.active", false);
                releaseMovementInputs();
                execution.activeNodeId = null;
                execution.activeNodeLabel = null;
                execution.activeAction = null;
                return;
            }
            writeStateValue(state, "move.target.x", followedEntity.getX());
            writeStateValue(state, "move.target.y", followedEntity.getY());
            writeStateValue(state, "move.target.z", followedEntity.getZ());
            writeStateValue(state, "current.target.x", followedEntity.getX());
            writeStateValue(state, "current.target.y", followedEntity.getY());
            writeStateValue(state, "current.target.z", followedEntity.getZ());
            writeStateValue(state, "current.target.uuid", followedEntity.getUuidAsString());
            writeStateValue(state, "current.target.name", followedEntity.getName().getString());
        }

        Vec3d target = new Vec3d(
                doubleState(state, "move.target.x", player.getX()),
                doubleState(state, "move.target.y", player.getY()),
                doubleState(state, "move.target.z", player.getZ())
        );

        double stopDistance = doubleState(state, "move.target.stop_distance", 1.5D);
        boolean sprint = Boolean.TRUE.equals(state.get("move.target.sprint"));
        boolean useY = Boolean.TRUE.equals(state.get("move.target.use_y")) || "true".equalsIgnoreCase(stringParam(state, "move.target.use_y", "false"));
        double distance = navigationDistance(player.getPos(), target, useY);
        boolean precise = Boolean.TRUE.equals(state.get("move.target.precise")) || "true".equalsIgnoreCase(stringParam(state, "move.target.precise", "false"));
        double precisionDistance = doubleState(state, "move.target.precision_distance", NAV_PRECISE_DIRECT_DISTANCE);
        double slowDistance = doubleState(state, "move.target.slow_distance", NAV_PRECISE_SLOW_DISTANCE);
        recordMovementTrace(state, player, distance);
        int avoidTicks = intState(state, "move.nav.avoid.ticks", 0);
        if (avoidTicks > 0) {
            writeStateValue(state, "move.nav.avoid.ticks", avoidTicks - 1);
        }

        writeStateValue(state, "path.status", "follow".equals(mode) ? "following" : "navigating");
        writeStateValue(state, "path.distance", round(distance));
        writeStateValue(state, "path.stop_distance", stopDistance);
        incrementStateCounter(state, "path.lookups");

        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.phase", "flow_phase", "[run ]", "move.target.phase", stringParam(state, "move.nav.phase", "navigating"));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.distance", "flow_metric", "[info]", "move.target.distance", round(distance));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.stop", "flow_metric", "[info]", "move.target.stop_distance", stopDistance);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.use_y", "flow_metric", "[info]", "move.target.use_y", useY);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.precise", "flow_metric", "[info]", "move.target.precise", precise);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.lookups", "flow_metric", "[info]", "move.target.lookups", intState(state, "path.lookups", 0));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.nav.phase", "flow_metric", "[info]", "move.nav.phase", stringParam(state, "move.nav.phase", "navigating"));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.nav.stall", "flow_metric", "[info]", "move.nav.stall_ticks", intState(state, "move.nav.stall_ticks", 0));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.nav.escape", "flow_metric", "[info]", "move.nav.escape", stringParam(state, "move.nav.escape_side", "none") + " / " + intState(state, "move.nav.escape_ticks", 0));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.recovery.mode", "flow_metric", "[info]", "move.recovery.mode", stringParam(state, "move.recovery.mode", "none"));
        emitMovementDebug(execution, player, target, state, distance);

        if (distance <= stopDistance) {
            double startX = doubleState(state, "move.target.start_x", player.getX());
            double startZ = doubleState(state, "move.target.start_z", player.getZ());
            double displacement = Math.sqrt(Math.pow(player.getX() - startX, 2.0D) + Math.pow(player.getZ() - startZ, 2.0D));
            double startDistance = doubleState(state, "move.target.start_distance", distance);
            double requiredDisplacement = doubleState(state, "move.target.required_displacement", 0.0D);
            boolean startedNearTarget = startDistance <= stopDistance + NAV_MIN_COMPLETION_DISPLACEMENT;
            writeStateValue(state, "move.target.displacement", round(displacement));
            if (requiredDisplacement > 0.0D) {
                writeStateValue(state, "move.target.required_displacement", round(requiredDisplacement));
            }
            if (!startedNearTarget && displacement < NAV_MIN_COMPLETION_DISPLACEMENT) {
                state.put("move.target.active", false);
                state.put("move.failed", true);
                state.put("move.failure_reason", "no_player_displacement");
                writeStateValue(state, "path.status", "failed");
                writeStateValue(state, "path.distance", round(distance));
                releaseMovementInputs();
                AutomationReporter.mem("move.target.active", false);
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.phase", "flow_decision", "[fail]", "move.target.phase", "no_player_displacement");
                execution.activeNodeId = null;
                execution.activeNodeLabel = null;
                execution.activeAction = null;
                return;
            }
            if (requiredDisplacement > 0.0D && displacement < Math.max(NAV_MIN_COMPLETION_DISPLACEMENT, requiredDisplacement - Math.max(0.35D, stopDistance))) {
                state.put("move.target.active", false);
                state.put("move.failed", true);
                state.put("move.failure_reason", "relative_arrival_unproven");
                writeStateValue(state, "path.status", "failed");
                writeStateValue(state, "path.distance", round(distance));
                releaseMovementInputs();
                AutomationReporter.mem("move.target.active", false);
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.phase", "flow_decision", "[fail]", "move.target.phase", "relative_arrival_unproven");
                execution.activeNodeId = null;
                execution.activeNodeLabel = null;
                execution.activeAction = null;
                return;
            }
            if (precise) {
                player.setSprinting(false);
            }
            clearMovementState(state);
            releaseMovementInputs();
            state.remove("parkour.active");
            state.remove("parkour.phase");
            AutomationReporter.mem("move.target.active", false);
            writeStateValue(state, "path.status", "complete");
            writeStateValue(state, "path.distance", round(distance));
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.phase", "flow_decision", "[done]", "move.target.phase", "arrived");
            execution.activeNodeId = null;
            execution.activeNodeLabel = null;
            execution.activeAction = null;
            return;
        }

        double lastX = doubleState(state, "move.nav.last_x", player.getX());
        double lastZ = doubleState(state, "move.nav.last_z", player.getZ());
        double movedSquared = Math.pow(player.getX() - lastX, 2.0D) + Math.pow(player.getZ() - lastZ, 2.0D);
        boolean groundedOrSwimming = player.isOnGround() || player.isTouchingWater() || player.isSubmergedInWater();
        if (!groundedOrSwimming) {
            writeStateValue(state, "move.nav.stall_ticks", 0);
        } else if (movedSquared < 0.0025D) {
            writeStateValue(state, "move.nav.stall_ticks", intState(state, "move.nav.stall_ticks", 0) + 1);
        } else {
            writeStateValue(state, "move.nav.stall_ticks", 0);
        }
        writeStateValue(state, "move.nav.last_x", player.getX());
        writeStateValue(state, "move.nav.last_z", player.getZ());

        double lastDistance = doubleState(state, "move.nav.last_distance", Double.MAX_VALUE);
        if (!groundedOrSwimming) {
            writeStateValue(state, "move.nav.blocked_ticks", 0);
        } else if (distance >= lastDistance - NAV_PROGRESS_EPSILON && movedSquared < 0.04D) {
            writeStateValue(state, "move.nav.blocked_ticks", intState(state, "move.nav.blocked_ticks", 0) + 1);
        } else {
            writeStateValue(state, "move.nav.blocked_ticks", 0);
        }
        writeStateValue(state, "move.nav.last_distance", distance);

        int blockedTicks = intState(state, "move.nav.blocked_ticks", 0);
        int stallTicks = intState(state, "move.nav.stall_ticks", 0);
        int escapeTicks = intState(state, "move.nav.escape_ticks", 0);
        boolean persistent = Boolean.TRUE.equals(state.get("move.nav.persistent"));
        if ((blockedTicks >= NAV_STUCK_TICKS || stallTicks >= NAV_STUCK_TICKS) && escapeTicks <= 0) {
            writeStateValue(state, "move.nav.escape_ticks", NAV_ESCAPE_TICKS);
            writeStateValue(state, "move.nav.escape_side", chooseEscapeSide(player, world, target, stringParam(state, "move.nav.escape_side", "none")));
            rememberNavigationAvoidance(state, player, world, target);
            String tactic = chooseRecoveryTactic(state, player, world);
            writeStateValue(state, "move.recovery.mode", tactic);
            writeStateValue(state, "move.recovery.attempts", intState(state, "move.recovery.attempts", 0) + 1);
            AutomationReporter.info("[move]", "progress stalled, recovery=" + tactic);
        }
        if ("wait_for_ground".equals(stringParam(state, "move.recovery.mode", "none")) && !groundedOrSwimming) {
            releaseMovementInputs();
            player.setSprinting(false);
            writeStateValue(state, "move.nav.phase", "wait_for_ground");
            writeStateValue(state, "move.nav.airborne_wait", true);
            return;
        }
        if (escapeTicks > 0) {
            writeStateValue(state, "move.nav.escape_ticks", escapeTicks - 1);
        }
        int recoveryLimit = persistent ? NAV_PERSISTENT_RECOVERY_LIMIT : NAV_RECOVERY_LIMIT;
        if (intState(state, "move.recovery.attempts", 0) > recoveryLimit) {
            clearMovementState(state);
            releaseMovementInputs();
            state.put("move.target.active", false);
            writeStateValue(state, "path.status", "failed");
            writeStateValue(state, "move.nav.fail_reason", "recovery_limit_reached");
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.target.phase", "flow_decision", "[fail]", "move.target.phase", "recovery_limit_reached");
            execution.activeNodeId = null;
            execution.activeNodeLabel = null;
            execution.activeAction = null;
            return;
        }

        if (Boolean.TRUE.equals(state.get("move.nav.allow_break_blocks")) && (blockedTicks >= NAV_STUCK_TICKS / 2 || stallTicks >= NAV_STUCK_TICKS / 2)) {
            if (tryBreakNavigationBlock(player, world, target, state)) {
                writeStateValue(state, "move.nav.phase", "clear_block");
                releaseMovementInputs();
                facePositionHorizontal(player, target, NAV_LOOK_MAX_YAW_STEP);
                return;
            }
        }

        if ("parkour".equals(mode)) {
            updateParkourPhase(state, player, world, target);
            return;
        }

        boolean precisionApproach = precise && distance <= precisionDistance;
        boolean precisionSlow = precise && distance <= Math.max(stopDistance + 0.25D, slowDistance);
        boolean sprintNow = sprint && !(precise && distance <= Math.max(stopDistance + 0.45D, 0.65D));
        NavigatorDecision decision = intervalNavigationDecision(player, world, target, state, sprintNow, stopDistance, blockedTicks, stallTicks, intState(state, "move.nav.escape_ticks", 0), stringParam(state, "move.nav.escape_side", "none"), useY, precisionApproach, distance);
        writeStateValue(state, "move.nav.phase", decision.phase());
        writeStateValue(state, "move.nav.swim", decision.swim());
        writeStateValue(state, "move.nav.strafe_mode", decision.strafeMode());
        writeStateValue(state, "move.nav.steer_lock_ticks", intState(state, "move.nav.steer_lock_ticks", 0));

        if (intState(state, "move.nav.blocked_ticks", 0) >= NAV_STUCK_TICKS && player.isOnGround()) {
            player.jump();
            writeStateValue(state, "move.nav.jump_window", 4);
            writeStateValue(state, "move.nav.phase", "unstick_jump");
        }

        int jumpWindow = intState(state, "move.nav.jump_window", 0);
        if (jumpWindow > 0) {
            writeStateValue(state, "move.nav.jump_window", jumpWindow - 1);
        }

        faceNavigationLook(player, decision, distance, blockedTicks, stallTicks);
        smoothPitchToward(player, MathHelper.clamp(player.getPitch(), -12.0F, 12.0F), 4.0F);
        applyNavigationDecision(player, decision, precise ? precisionSpeedScale(distance, stopDistance) : 1.0D);
    }

    private NavigatorDecision intervalNavigationDecision(ClientPlayerEntity player, ClientWorld world, Vec3d target, Map<String, Object> state, boolean sprint, double stopDistance, int blockedTicks, int stallTicks, int escapeTicks, String escapeSide, boolean useY, boolean precisionApproach, double distance) {
        boolean forcedReplan = precisionApproach || blockedTicks >= NAV_STUCK_TICKS || stallTicks >= NAV_STUCK_TICKS || escapeTicks > 0;
        int decisionTicks = intState(state, "move.nav.decision_ticks", 0);
        Vec3d cachedDirection = new Vec3d(
                doubleState(state, "move.nav.decision_x", 0.0D),
                0.0D,
                doubleState(state, "move.nav.decision_z", 0.0D)
        );
        if (!forcedReplan && decisionTicks > 0 && cachedDirection.lengthSquared() > 1.0E-4D) {
            writeStateValue(state, "move.nav.decision_ticks", decisionTicks - 1);
            Vec3d lookTarget = player.getPos().add(cachedDirection.normalize().multiply(7.0D));
            return new NavigatorDecision(cachedDirection.normalize(), lookTarget, Boolean.TRUE.equals(state.get("move.nav.decision_sprint")), false, Boolean.TRUE.equals(state.get("move.nav.decision_jump")), stringParam(state, "move.nav.decision_phase", "advance_interval"), stringParam(state, "move.nav.decision_strafe", "none"));
        }

        Vec3d navigationTarget = precisionApproach ? target : chooseNavigationWaypoint(world, player, target, state, useY, blockedTicks, stallTicks, escapeTicks);
        NavigatorDecision decision = buildNavigationDecision(player, world, navigationTarget, state, sprint, stopDistance, blockedTicks, stallTicks, escapeTicks, escapeSide);
        if (precisionApproach && !decision.swim()) {
            Vec3d direct = new Vec3d(target.x - player.getX(), 0.0D, target.z - player.getZ());
            if (direct.lengthSquared() > 1.0E-4D) {
                boolean sprintPrecision = sprint && distance > Math.max(stopDistance + 0.75D, 1.15D);
                decision = new NavigatorDecision(direct.normalize(), target, sprintPrecision, false, false, distance <= 0.55D ? "precision_finish" : "precision_approach", "none");
            }
        }
        decision = stabilizeNavigationDecision(state, player, world, decision, blockedTicks, stallTicks, escapeTicks);
        if (!decision.swim() && decision.moveDirection().lengthSquared() > 1.0E-4D) {
            Vec3d normalized = decision.moveDirection().normalize();
            writeStateValue(state, "move.nav.decision_ticks", NAV_DECISION_INTERVAL_TICKS);
            writeStateValue(state, "move.nav.decision_x", normalized.x);
            writeStateValue(state, "move.nav.decision_z", normalized.z);
            writeStateValue(state, "move.nav.decision_look_x", decision.lookTarget().x);
            writeStateValue(state, "move.nav.decision_look_y", decision.lookTarget().y);
            writeStateValue(state, "move.nav.decision_look_z", decision.lookTarget().z);
            writeStateValue(state, "move.nav.decision_phase", decision.phase());
            writeStateValue(state, "move.nav.decision_strafe", decision.strafeMode());
            writeStateValue(state, "move.nav.decision_sprint", decision.sprint());
            writeStateValue(state, "move.nav.decision_jump", decision.jump());
        }
        return decision;
    }

    private void emitMovementDebug(ActiveExecution execution, ClientPlayerEntity player, Vec3d target, Map<String, Object> state, double distance) {
        double startX = doubleState(state, "move.target.start_x", player.getX());
        double startZ = doubleState(state, "move.target.start_z", player.getZ());
        double displacement = Math.sqrt(Math.pow(player.getX() - startX, 2.0D) + Math.pow(player.getZ() - startZ, 2.0D));
        double requiredDisplacement = doubleState(state, "move.target.required_displacement", 0.0D);
        ClientWorld world = MinecraftClient.getInstance() == null ? null : MinecraftClient.getInstance().world;
        BlockPos feet = player.getBlockPos();
        Vec3d decisionDirection = new Vec3d(doubleState(state, "move.nav.decision_x", doubleState(state, "move.nav.steer_x", 0.0D)), 0.0D, doubleState(state, "move.nav.decision_z", doubleState(state, "move.nav.steer_z", 0.0D)));
        BlockPos ahead = decisionDirection.lengthSquared() > 1.0E-4D
                ? feet.add((int) Math.signum(decisionDirection.x), 0, (int) Math.signum(decisionDirection.z))
                : feet;
        String blockSummary = world == null
                ? "world=null"
                : "feet=" + blockId(world, feet) + " head=" + blockId(world, feet.up()) + " under=" + blockId(world, feet.down()) + " ahead=" + blockId(world, ahead) + " ahead_under=" + blockId(world, ahead.down());
        String routeSummary = "phase=" + stringParam(state, "move.nav.phase", "none")
                + " style=" + stringParam(state, "move.nav.path_style", "none")
                + " route=" + state.getOrDefault("move.nav.route.active", false)
                + " waypoint=" + state.getOrDefault("move.nav.waypoint.active", false)
                + " blocked=" + intState(state, "move.nav.blocked_ticks", 0)
                + " stall=" + intState(state, "move.nav.stall_ticks", 0)
                + " recovery=" + stringParam(state, "move.recovery.mode", "none");
        String motionSummary = "on_ground=" + player.isOnGround()
                + " h_collision=" + player.horizontalCollision
                + " sprinting=" + player.isSprinting()
                + " vel=" + round(player.getVelocity().x) + "," + round(player.getVelocity().y) + "," + round(player.getVelocity().z)
                + " decision=" + round(decisionDirection.x) + "," + round(decisionDirection.z);
        String keySummary = "F=" + isInputPressed("forward") + " B=" + isInputPressed("back") + " L=" + isInputPressed("left") + " R=" + isInputPressed("right") + " J=" + isInputPressed("jump") + " S=" + isInputPressed("sprint");
        String inputSummary = player.input == null
                ? "input=null"
                : "forward=" + player.input.movementForward + " sideways=" + player.input.movementSideways + " pf=" + player.input.pressingForward + " pb=" + player.input.pressingBack + " pl=" + player.input.pressingLeft + " pr=" + player.input.pressingRight + " jump=" + player.input.jumping;
        int recorderTick = intState(state, "move.debug.recorder_tick", 0) + 1;
        int recorderSlot = recorderTick % 20;
        writeStateValue(state, "move.debug.recorder_tick", recorderTick);
        String recorderLine = "#" + recorderTick
                + " pos=" + round(player.getX()) + "," + round(player.getY()) + "," + round(player.getZ())
                + " target=" + round(target.x) + "," + round(target.y) + "," + round(target.z)
                + " dist=" + round(distance)
                + " yaw=" + round(player.getYaw())
                + " " + routeSummary
                + " " + motionSummary
                + " keys[" + keySummary + "]"
                + " input[" + inputSummary + "]"
                + " blocks[" + blockSummary + "]";
        writeStateValue(state, "move.debug.latest", recorderLine);
        writeStateValue(state, "move.debug.tick." + recorderSlot, recorderLine);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.pos", "flow_metric", "[debug]", "move.debug.pos", round(player.getX()) + "," + round(player.getY()) + "," + round(player.getZ()));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.target", "flow_metric", "[debug]", "move.debug.target", round(target.x) + "," + round(target.y) + "," + round(target.z));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.yaw", "flow_metric", "[debug]", "move.debug.yaw", round(player.getYaw()));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.distance", "flow_metric", "[debug]", "move.debug.distance", round(distance));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.displacement", "flow_metric", "[debug]", "move.debug.displacement", round(displacement) + " / " + round(requiredDisplacement));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.route", "flow_metric", "[debug]", "move.debug.route", routeSummary);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.motion", "flow_metric", "[debug]", "move.debug.motion", motionSummary);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.blocks", "flow_metric", "[debug]", "move.debug.blocks", blockSummary);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.keys", "flow_metric", "[debug]", "move.debug.keys", keySummary);
        if (player.input != null) {
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.input", "flow_metric", "[debug]", "move.debug.input", inputSummary);
        }
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "move.debug.latest", "flow_metric", "[debug]", "move.debug.latest", recorderLine);
    }

    private boolean verifyRelativeArrival(ClientPlayerEntity player, Map<String, Object> params, Map<String, Object> state) {
        if (!Boolean.TRUE.equals(state.get("current.target.relative")) && !"relative_target".equals(stringParam(state, "current.target.id", ""))) {
            writeMovementResult(state, true, "success", "not_relative_target");
            return true;
        }
        Vec3d target = new Vec3d(
                doubleState(state, "current.target.x", player.getX()),
                doubleState(state, "current.target.y", player.getY()),
                doubleState(state, "current.target.z", player.getZ())
        );
        double stopDistance = doubleParam(params, "stop.distance", doubleState(state, "path.stop_distance", NAV_PRECISE_STOP_DISTANCE));
        double required = doubleState(state, "current.target.relative_distance", 0.0D);
        double startX = doubleState(state, "current.target.start_x", player.getX());
        double startZ = doubleState(state, "current.target.start_z", player.getZ());
        double displacement = Math.sqrt(Math.pow(player.getX() - startX, 2.0D) + Math.pow(player.getZ() - startZ, 2.0D));
        double distance = navigationDistance(player.getPos(), target, false);
        double requiredMinimum = required <= 0.0D ? NAV_MIN_COMPLETION_DISPLACEMENT : Math.max(NAV_MIN_COMPLETION_DISPLACEMENT, required - Math.max(0.35D, stopDistance));
        writeStateValue(state, "move.verify.distance", round(distance));
        writeStateValue(state, "move.verify.displacement", round(displacement));
        writeStateValue(state, "move.verify.required_displacement", round(requiredMinimum));
        AutomationCliViewModel.runtimeFlow("", "verify_relative_arrival", "move.verify.target", "flow_metric", "[debug]", "move.verify.target", round(target.x) + "," + round(target.y) + "," + round(target.z));
        AutomationCliViewModel.runtimeFlow("", "verify_relative_arrival", "move.verify.pos", "flow_metric", "[debug]", "move.verify.pos", round(player.getX()) + "," + round(player.getY()) + "," + round(player.getZ()));
        AutomationCliViewModel.runtimeFlow("", "verify_relative_arrival", "move.verify.distance", "flow_metric", "[debug]", "move.verify.distance", round(distance));
        AutomationCliViewModel.runtimeFlow("", "verify_relative_arrival", "move.verify.displacement", "flow_metric", "[debug]", "move.verify.displacement", round(displacement) + " / " + round(requiredMinimum));
        if (distance > Math.max(stopDistance, NAV_PRECISE_STOP_DISTANCE)) {
            writeMovementResult(state, false, "failed", "relative_target_not_reached");
            return false;
        }
        if (displacement < requiredMinimum) {
            writeMovementResult(state, false, "failed", "relative_arrival_unproven");
            return false;
        }
        writeMovementResult(state, true, "success", "relative_arrival_verified");
        return true;
    }

    private void updateActiveAttack(ActiveExecution execution) {
        Map<String, Object> state = execution.state;
        if (!Boolean.TRUE.equals(state.get("attack.active"))) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        ClientWorld world = client == null ? null : client.world;
        ClientPlayerInteractionManager interactionManager = client == null ? null : client.interactionManager;

        if (player == null || world == null || interactionManager == null) {
            state.put("attack.failed", true);
            state.put("attack.failure_reason", "no_client_context");
            state.remove("attack.active");
            return;
        }

        Entity entity = findEntityByUuid(world, stringParam(state, "attack.target.uuid", ""));
        if (entity == null || !entity.isAlive()) {
            state.put("result.target.dead", true);
            state.put("attack.active", false);
            state.put("attack.phase", "done");
            state.remove("attack.until_dead");
            AutomationReporter.mem("result.target.dead", true);
            AutomationReporter.mem("attack.active", false);
            return;
        }

        double distance = player.getPos().distanceTo(entity.getPos());
        double maxDistance = doubleState(state, "attack.range", 3.4D);
        boolean critEnabled = Boolean.TRUE.equals(state.get("attack.crit.enabled")) || "true".equalsIgnoreCase(stringParam(state, "attack.crit.enabled", "false"));
        boolean attackUntilDead = Boolean.TRUE.equals(state.get("attack.until_dead")) || "true".equalsIgnoreCase(stringParam(state, "attack.until_dead", "false"));
        int jumpDelayTicks = intState(state, "attack.crit.jump_wait_ticks", 3);
        int ticks = intState(state, "attack.ticks", 0) + 1;
        String phase = stringParam(state, "attack.phase", "prepare");

        state.put("attack.ticks", ticks);
        facePosition(player, entity.getEyePos());
        writeStateValue(state, "current.target.distance", round(distance));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_phase", "[run ]", "attack.phase", phase);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.distance", "flow_metric", "[info]", "attack.distance", round(distance));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.range", "flow_metric", "[info]", "attack.range", maxDistance);

        if (distance > maxDistance) {
            state.put("attack.failed", true);
            state.put("attack.failure_reason", "target_out_of_range");
            state.remove("attack.active");
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_decision", "[block]", "attack.phase", "target_out_of_range");
            return;
        }

        switch (phase) {
            case "prepare" -> {
                if (critEnabled && player.isOnGround()) {
                    player.jump();
                    state.put("attack.phase", "jump_delay");
                    state.put("attack.ticks", 0);
                    AutomationReporter.mem("attack.phase", "jump_delay");
                    AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_phase", "[branch]", "attack.phase", "jump_delay");
                    return;
                }
                state.put("attack.phase", "strike");
                state.put("attack.ticks", 0);
                AutomationReporter.mem("attack.phase", "strike");
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_phase", "[branch]", "attack.phase", "strike");
            }
            case "jump_delay" -> {
                if (ticks >= Math.max(1, jumpDelayTicks)) {
                    state.put("attack.phase", "strike");
                    state.put("attack.ticks", 0);
                    AutomationReporter.mem("attack.phase", "strike");
                    AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_phase", "[branch]", "attack.phase", "strike");
                }
                return;
            }
            case "strike" -> {
                if (!isCrosshairOnEntity(client, entity)) {
                    state.put("attack.phase", "align");
                    state.put("attack.ticks", 0);
                    AutomationReporter.mem("attack.phase", "align");
                    AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_phase", "[info]", "attack.phase", "align");
                    return;
                }
                interactionManager.attackEntity(player, entity);
                player.swingHand(Hand.MAIN_HAND);
                state.put("result.action", "SUCCESS");
                state.put("attack.phase", "cooldown");
                state.put("attack.ticks", 0);
                AutomationReporter.mem("result.action", "SUCCESS");
                AutomationReporter.mem("attack.phase", "cooldown");
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_phase", "[run ]", "attack.phase", "cooldown");
                return;
            }
            case "align" -> {
                if (isCrosshairOnEntity(client, entity) || ticks >= 8) {
                    state.put("attack.phase", "strike");
                    state.put("attack.ticks", 0);
                    AutomationReporter.mem("attack.phase", "strike");
                    AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_phase", "[branch]", "attack.phase", "strike");
                }
                return;
            }
            case "cooldown" -> {
                if (entity == null || !entity.isAlive()) {
                    state.put("result.target.dead", true);
                    state.put("attack.active", false);
                    state.put("attack.phase", "done");
                    state.remove("attack.until_dead");
                    AutomationReporter.mem("result.target.dead", true);
                    AutomationReporter.mem("attack.active", false);
                    AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_decision", "[done]", "attack.phase", "target_dead");
                    execution.activeNodeId = null;
                    execution.activeNodeLabel = null;
                    execution.activeAction = null;
                    return;
                }
                if (ticks >= 4) {
                    if (attackUntilDead) {
                        state.put("attack.phase", "prepare");
                        state.put("attack.ticks", 0);
                        AutomationReporter.mem("attack.phase", "prepare");
                        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_decision", "[loop]", "attack.phase", "target_alive_continue");
                    } else {
                        state.put("result.target.dead", !entity.isAlive());
                        state.put("attack.active", false);
                        state.put("attack.phase", "done");
                        state.remove("attack.until_dead");
                        AutomationReporter.mem("result.target.dead", !entity.isAlive());
                        AutomationReporter.mem("attack.active", false);
                        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_decision", "[done]", "attack.phase", Boolean.TRUE.equals(state.get("result.target.dead")) ? "dead_confirmed" : "cooldown_complete");
                        execution.activeNodeId = null;
                        execution.activeNodeLabel = null;
                        execution.activeAction = null;
                    }
                }
                return;
            }
            default -> {
                state.put("attack.active", false);
                state.put("attack.phase", "done");
                state.remove("attack.until_dead");
                AutomationReporter.mem("attack.active", false);
                AutomationReporter.mem("attack.phase", "done");
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "attack.phase", "flow_decision", "[done]", "attack.phase", "done");
                execution.activeNodeId = null;
                execution.activeNodeLabel = null;
                execution.activeAction = null;
            }
        }
    }

    private void updateActiveMining(ActiveExecution execution) {
        Map<String, Object> state = execution.state;
        if (!Boolean.TRUE.equals(state.get("mine.active"))) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        ClientWorld world = client == null ? null : client.world;
        ClientPlayerInteractionManager interactionManager = client == null ? null : client.interactionManager;
        if (player == null || world == null || interactionManager == null) {
            state.put("mine.failed", true);
            state.put("mine.failure_reason", "no_client_context");
            state.remove("mine.active");
            return;
        }
        BlockPos pos = new BlockPos(intState(state, "mine.target.x", 0), intState(state, "mine.target.y", 0), intState(state, "mine.target.z", 0));
        String expectedId = stringParam(state, "mine.target.id", "");
        int ticks = intState(state, "mine.ticks", 0) + 1;
        state.put("mine.ticks", ticks);
        BlockState blockState = world.getBlockState(pos);
        Identifier currentId = Registries.BLOCK.getId(blockState.getBlock());
        boolean gone = world.isAir(pos) || (!expectedId.isBlank() && currentId != null && !expectedId.equals(currentId.toString()));
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "mine.phase", "flow_phase", "[run ]", "mine.phase", gone ? "complete" : "breaking");
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "mine.ticks", "flow_metric", "[info]", "mine.ticks", ticks);
        if (gone) {
            state.put("result.block.broken", true);
            state.put("result.action", "SUCCESS");
            state.remove("mine.active");
            AutomationReporter.mem("result.block.broken", true);
            AutomationReporter.mem("result.action", "SUCCESS");
            AutomationReporter.mem("mine.active", false);
            return;
        }
        double distance = player.getPos().distanceTo(Vec3d.ofCenter(pos));
        if (distance > 5.2D) {
            state.put("mine.failed", true);
            state.put("mine.failure_reason", "target_out_of_range");
            state.remove("mine.active");
            return;
        }
        facePosition(player, Vec3d.ofCenter(pos));
        if (!isCrosshairOnBlock(client, pos)) {
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "mine.phase", "flow_phase", "[info]", "mine.phase", "align");
            return;
        }
        boolean started = Boolean.TRUE.equals(state.get("mine.started"));
        if (!started) {
            boolean began = interactionManager.attackBlock(pos, Direction.UP);
            player.swingHand(Hand.MAIN_HAND);
            state.put("mine.started", began);
            if (!began && !player.isCreative()) {
                state.put("mine.failed", true);
                state.put("mine.failure_reason", "attack_block_failed");
                state.remove("mine.active");
            }
            return;
        }
        interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        if (ticks % 4 == 0) {
            player.swingHand(Hand.MAIN_HAND);
        }
        if (ticks >= 200) {
            interactionManager.cancelBlockBreaking();
            state.put("mine.failed", true);
            state.put("mine.failure_reason", "mine_timeout");
            state.remove("mine.active");
        }
    }


    private boolean isCrosshairOnEntity(MinecraftClient client, Entity entity) {
        if (client == null || entity == null) {
            return false;
        }
        HitResult hitResult = piercingRaycast(client, AUTOMATION_RAY_DISTANCE).hit();
        if (!(hitResult instanceof EntityHitResult entityHitResult)) {
            return false;
        }
        return entityHitResult.getEntity() != null && entityHitResult.getEntity().getId() == entity.getId();
    }

    private boolean isCrosshairOnBlock(MinecraftClient client, BlockPos pos) {
        if (client == null || pos == null) {
            return false;
        }
        HitResult hitResult = piercingRaycast(client, AUTOMATION_RAY_DISTANCE).hit();
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            return false;
        }
        return pos.equals(blockHitResult.getBlockPos());
    }

    private void updateWorldDiagnostics(ActiveExecution execution, MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        Map<String, Object> state = execution.state;
        if (client == null || player == null || world == null) {
            return;
        }

        PiercingRayResult rayResult = piercingRaycast(client, AUTOMATION_RAY_DISTANCE);
        HitResult hitResult = rayResult.hit();
        if (hitResult != null) {
            writeStateValue(state, "world.ray.kind", hitResult.getType().name().toLowerCase(Locale.ROOT));
            writeStateValue(state, "world.ray.distance", round(hitResult.getPos().distanceTo(player.getEyePos())));
            writeStateValue(state, "world.ray.pierced_blocks", rayResult.piercedBlocks());
            writeStateValue(state, "world.ray.last_pass_block", rayResult.lastPassThroughBlock());
            AutomationCliViewModel.worldProbe(execution.frameId, "ray.kind", "flow_phase", "[run ]", "world.ray.kind", hitResult.getType().name().toLowerCase(Locale.ROOT));
            AutomationCliViewModel.worldProbe(execution.frameId, "ray.distance", "flow_metric", "[info]", "world.ray.distance", round(hitResult.getPos().distanceTo(player.getEyePos())));
            AutomationCliViewModel.worldProbe(execution.frameId, "ray.pierced", "flow_metric", rayResult.piercedBlocks() > 0 ? "[ok  ]" : "[info]", "world.ray.pierced_blocks", rayResult.piercedBlocks());
            if (!rayResult.lastPassThroughBlock().isBlank()) {
                AutomationCliViewModel.worldProbe(execution.frameId, "ray.pass_block", "flow_metric", "[info]", "world.ray.pass_block", rayResult.lastPassThroughBlock());
            }
            switch (hitResult.getType()) {
                case BLOCK -> {
                    BlockHitResult blockHitResult = (BlockHitResult) hitResult;
                    BlockPos blockPos = blockHitResult.getBlockPos();
                    Identifier blockId = Registries.BLOCK.getId(world.getBlockState(blockPos).getBlock());
                    writeStateValue(state, "world.ray.face", blockHitResult.getSide().asString());
                    writeStateValue(state, "world.ray.block.id", blockId == null ? "unknown" : blockId.toString());
                    AutomationCliViewModel.worldProbe(execution.frameId, "ray.block", "flow_metric", "[info]", "world.ray.block", blockId == null ? "unknown" : blockId.toString());
                    AutomationCliViewModel.worldProbe(execution.frameId, "ray.face", "flow_metric", "[info]", "world.ray.face", blockHitResult.getSide().asString());
                }
                case ENTITY -> {
                    EntityHitResult entityHitResult = (EntityHitResult) hitResult;
                    Entity entity = entityHitResult.getEntity();
                    Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
                    writeStateValue(state, "world.ray.entity.id", entityId == null ? entity.getName().getString() : entityId.toString());
                    writeStateValue(state, "world.ray.entity.name", entity.getName().getString());
                    AutomationCliViewModel.worldProbe(execution.frameId, "ray.entity", "flow_metric", "[info]", "world.ray.entity", entityId == null ? entity.getName().getString() : entityId.toString());
                }
                case MISS -> {
                    writeStateValue(state, "world.ray.face", "none");
                    writeStateValue(state, "world.ray.block.id", "none");
                    writeStateValue(state, "world.ray.entity.id", "none");
                    AutomationCliViewModel.worldProbe(execution.frameId, "ray.miss", "flow_decision", "[info]", "world.ray.result", "miss");
                }
            }
        }

        if (hasTargetPosition(state)) {
            Vec3d target = new Vec3d(
                    doubleState(state, "current.target.x", player.getX()),
                    doubleState(state, "current.target.y", player.getEyeY()),
                    doubleState(state, "current.target.z", player.getZ())
            );
            double distance = player.getPos().distanceTo(target);
            PiercingVisibilityResult visibility = piercingVisibility(world, player, target);
            boolean visible = visibility.visible();
            writeStateValue(state, "current.target.distance", round(distance));
            writeStateValue(state, "current.target.visible", visible);
            writeStateValue(state, "current.target.visibility_blocker", visibility.blocker());
            writeStateValue(state, "current.target.visibility_pierced", visibility.piercedBlocks());
            writeStateValue(state, "current.target.reachable", distance <= 4.5D);
            AutomationCliViewModel.worldProbe(execution.frameId, "target.distance", "flow_metric", "[info]", "target.distance", round(distance));
            AutomationCliViewModel.worldProbe(execution.frameId, "target.visible", "flow_decision", visible ? "[ok  ]" : "[warn]", "target.visible", visible);
            AutomationCliViewModel.worldProbe(execution.frameId, "target.visibility_pierced", "flow_metric", visibility.piercedBlocks() > 0 ? "[ok  ]" : "[info]", "target.visibility_pierced", visibility.piercedBlocks());
            if (!visibility.blocker().isBlank()) {
                AutomationCliViewModel.worldProbe(execution.frameId, "target.visibility_blocker", "flow_metric", "[warn]", "target.visibility_blocker", visibility.blocker());
            }
            AutomationCliViewModel.worldProbe(execution.frameId, "target.reachable", "flow_decision", distance <= 4.5D ? "[ok  ]" : "[wait]", "target.reachable", distance <= 4.5D);
        } else {
            writeStateValue(state, "current.target.distance", "none");
            writeStateValue(state, "current.target.visible", false);
            writeStateValue(state, "current.target.reachable", false);
            AutomationCliViewModel.worldProbe(execution.frameId, "target.none", "flow_decision", "[info]", "target.probe", "no active target");
        }

        writeStateValue(state, "world.ui.screen", client.currentScreen == null ? "world" : client.currentScreen.getClass().getSimpleName());
        writeStateValue(state, "world.executor.frames", this.executionStack.size());
        writeStateValue(state, "world.executor.inputs_held", countHeldInputs());
        if (!state.containsKey("path.status")) {
            writeStateValue(state, "path.status", "idle");
        }
    }

    private void writeStateValue(Map<String, Object> state, String key, Object value) {
        Object existing = state.get(key);
        if (Objects.equals(existing, value)) {
            return;
        }
        state.put(key, value);
        AutomationReporter.mem(key, value);
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private void incrementStateCounter(Map<String, Object> state, String key) {
        writeStateValue(state, key, intState(state, key, 0) + 1);
    }

    private int countHeldInputs() {
        int count = 0;
        for (HeldInput input : this.heldInputs.values()) {
            if (input != null && input.pressed) {
                count++;
            }
        }
        return count;
    }

    private void applyRelativeMotion(ClientPlayerEntity player, String direction, boolean sprint) {
        float yawRadians = (float) Math.toRadians(player.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
        Vec3d side = new Vec3d(forward.z, 0.0D, -forward.x);
        Vec3d directionVector = switch (direction.toLowerCase(Locale.ROOT)) {
            case "back", "backward" -> forward.multiply(-1.0D);
            case "left" -> side;
            case "right" -> side.multiply(-1.0D);
            default -> forward;
        };
        applyHorizontalMotion(player, directionVector, sprint);
    }

    private void applyMoveToward(ClientPlayerEntity player, Vec3d target, boolean sprint) {
        Vec3d delta = new Vec3d(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (delta.lengthSquared() < 1.0E-4D) {
            player.setSprinting(false);
            return;
        }
        applyHorizontalMotion(player, delta.normalize(), sprint);
    }

    private void applyHorizontalMotion(ClientPlayerEntity player, Vec3d direction, boolean sprint) {
        applyHorizontalMotion(player, direction, sprint, 1.0D, true);
    }

    private void applyHorizontalMotion(ClientPlayerEntity player, Vec3d direction, boolean sprint, double speedScale) {
        applyHorizontalMotion(player, direction, sprint, speedScale, true);
    }

    private void applyHorizontalMotion(ClientPlayerEntity player, Vec3d direction, boolean sprint, double speedScale, boolean allowBackInput) {
        Vec3d normalized = direction.lengthSquared() < 1.0E-4D ? Vec3d.ZERO : direction.normalize();
        double scale = MathHelper.clamp(speedScale, 0.18D, 1.0D);
        boolean sprinting = sprint && scale > 0.85D;
        player.setSprinting(sprinting);
        applyLocomotionInputs(player, normalized, sprinting, allowBackInput);
        applyPlayerInputState(player);
    }

    private void applyLocomotionInputs(ClientPlayerEntity player, Vec3d desiredDirection, boolean sprint) {
        applyLocomotionInputs(player, desiredDirection, sprint, true);
    }

    private void applyLocomotionInputs(ClientPlayerEntity player, Vec3d desiredDirection, boolean sprint, boolean allowBackInput) {
        if (desiredDirection.lengthSquared() < 1.0E-4D) {
            releaseMovementInputs();
            return;
        }
        float yawRadians = (float) Math.toRadians(player.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
        Vec3d left = new Vec3d(forward.z, 0.0D, -forward.x);
        double forwardAmount = desiredDirection.dotProduct(forward);
        double leftAmount = desiredDirection.dotProduct(left);
        setHeldInput("forward", forwardAmount > 0.18D);
        setHeldInput("back", allowBackInput && forwardAmount < -0.35D);
        setHeldInput("left", leftAmount > 0.32D);
        setHeldInput("right", leftAmount < -0.32D);
        setHeldInput("sprint", sprint && forwardAmount > 0.62D);
    }

    private int blocksToTicks(double blocks) {
        return Math.max(1, (int) Math.round(blocks * 6.0D));
    }

    private String stringParam(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private int intParam(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleParam(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean booleanParam(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "1".equals(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text) || "0".equals(text)) {
                return false;
            }
        }
        return fallback;
    }

    private boolean hasResolvedParam(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            return false;
        }
        Object value = map.get(key);
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return !text.isBlank() && !(text.startsWith("${") && text.endsWith("}"));
    }

    private int intState(Map<String, Object> state, String key, int fallback) {
        Object value = state.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleState(Map<String, Object> state, String key, double fallback) {
        Object value = state.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean hasTargetPosition(Map<String, Object> state) {
        return state.containsKey("current.target.x") && state.containsKey("current.target.y") && state.containsKey("current.target.z");
    }

    private BlockPos targetBlockPos(Map<String, Object> state) {
        return new BlockPos(
                (int) Math.floor(doubleState(state, "current.target.x", 0.0D)),
                (int) Math.floor(doubleState(state, "current.target.y", 0.0D)),
                (int) Math.floor(doubleState(state, "current.target.z", 0.0D))
        );
    }

    private Vec3d resolveStandablePosition(ClientWorld world, ClientPlayerEntity player, double x, double z, boolean allowSwim) {
        int baseX = MathHelper.floor(x);
        int baseZ = MathHelper.floor(z);
        BlockPos playerPos = player.getBlockPos();
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 0; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int y = playerPos.getY() + 8; y >= playerPos.getY() - 16; y--) {
                        BlockPos feet = new BlockPos(baseX + dx, y, baseZ + dz);
                        if (!canStandAt(world, feet) && !(allowSwim && isWaterBlock(world, feet))) {
                            continue;
                        }
                        MovementSafety safety = movementSafetyAt(world, feet, 3);
                        if (!allowSwim && safety.water()) {
                            continue;
                        }
                        if (!safety.safe()) {
                            continue;
                        }
                        double elevationCost = Math.abs(y - playerPos.getY()) * 0.9D;
                        double offsetCost = Math.sqrt(dx * dx + dz * dz) * 2.0D;
                        double pocketCost = pocketRisk(world, feet) * 8.0D;
                        double score = elevationCost + offsetCost + pocketCost;
                        if (score < bestScore) {
                            bestScore = score;
                            best = radius == 0 ? new Vec3d(x, feet.getY(), z) : new Vec3d(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private Vec3d resolveStandablePositionNearY(ClientWorld world, ClientPlayerEntity player, double x, double y, double z, boolean allowSwim) {
        int baseX = MathHelper.floor(x);
        int baseY = MathHelper.floor(y);
        int baseZ = MathHelper.floor(z);
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 0; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -4; dy <= 4; dy++) {
                        BlockPos feet = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
                        if (!canStandAt(world, feet) && !(allowSwim && isWaterBlock(world, feet))) {
                            continue;
                        }
                        MovementSafety safety = movementSafetyAt(world, feet, 3);
                        if (!allowSwim && safety.water()) {
                            continue;
                        }
                        if (!safety.safe()) {
                            continue;
                        }
                        double verticalCost = Math.abs(dy) * 1.7D;
                        double offsetCost = Math.sqrt(dx * dx + dz * dz) * 2.0D;
                        double playerCost = player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(feet)) * 0.001D;
                        double score = verticalCost + offsetCost + playerCost + pocketRisk(world, feet) * 8.0D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = radius == 0 ? new Vec3d(x, feet.getY(), z) : new Vec3d(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private Vec3d resolveApproachPosition(ClientWorld world, ClientPlayerEntity player, BlockPos target, boolean allowSwim) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(target.north());
        candidates.add(target.south());
        candidates.add(target.east());
        candidates.add(target.west());
        candidates.add(target.north().east());
        candidates.add(target.north().west());
        candidates.add(target.south().east());
        candidates.add(target.south().west());
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            Vec3d stand = resolveStandablePosition(world, player, candidate.getX() + 0.5D, candidate.getZ() + 0.5D, allowSwim);
            if (stand == null) {
                continue;
            }
            double score = player.getPos().squaredDistanceTo(stand) + pocketRisk(world, BlockPos.ofFloored(stand)) * 16.0D;
            if (score < bestScore) {
                bestScore = score;
                best = stand;
            }
        }
        return best;
    }

    private boolean canStandAt(ClientWorld world, BlockPos feet) {
        BlockPos below = feet.down();
        return canPassThrough(world, feet)
                && canPassThrough(world, feet.up())
                && supportsStanding(world, below);
    }

    private boolean canPassThrough(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(world, pos).isEmpty() || !world.getFluidState(pos).isEmpty();
    }

    private boolean supportsStanding(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty() && !isDangerBlock(world, pos);
    }

    private double pocketRisk(ClientWorld world, BlockPos feet) {
        if (!canPassThrough(world, feet) || !canPassThrough(world, feet.up())) {
            return 1.0D;
        }
        int exits = safeExitCount(world, feet);
        int blocked = 4 - exits;
        boolean corridor = hasOpposingSafeExits(world, feet);
        boolean lowCeiling = !canPassThrough(world, feet.up(2));
        boolean deepDrop = isUnsafeDrop(world, feet.down(), 4);
        double risk = corridor ? 0.08D : blocked * 0.18D;
        if (exits == 0) {
            risk += 0.75D;
        } else if (exits == 1) {
            risk += 0.28D;
        } else if (exits >= 3) {
            risk -= 0.22D;
        }
        if (lowCeiling && exits <= 1) {
            risk += 0.25D;
        } else if (lowCeiling && !corridor) {
            risk += 0.08D;
        }
        if (deepDrop) {
            risk += 0.35D;
        }
        if (isWaterBlock(world, feet) && exits <= 2) {
            risk += 0.18D;
        }
        if (corridor) {
            risk = Math.min(risk, NAV_CORRIDOR_POCKET_LIMIT);
        }
        return MathHelper.clamp(risk, 0.0D, 1.0D);
    }

    private int safeExitCount(ClientWorld world, BlockPos feet) {
        int exits = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (isSafeExit(world, feet.offset(direction))) {
                exits++;
            }
        }
        return exits;
    }

    private boolean hasOpposingSafeExits(ClientWorld world, BlockPos feet) {
        boolean north = isSafeExit(world, feet.north());
        boolean south = isSafeExit(world, feet.south());
        boolean east = isSafeExit(world, feet.east());
        boolean west = isSafeExit(world, feet.west());
        return north && south || east && west;
    }

    private boolean isSafeExit(ClientWorld world, BlockPos feet) {
        if (canStandAt(world, feet) && !isUnsafeDrop(world, feet.down(), 4) && !isDangerBlock(world, feet) && !isDangerBlock(world, feet.down())) {
            return true;
        }
        BlockPos stepUp = feet.up();
        return canStandAt(world, stepUp) && canPassThrough(world, stepUp.up()) && !isUnsafeDrop(world, stepUp.down(), 4) && !isDangerBlock(world, stepUp);
    }

    private boolean isDangerBlock(ClientWorld world, BlockPos pos) {
        Identifier id = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
        if (id == null) {
            return false;
        }
        String value = id.toString();
        return value.contains("lava") || value.contains("fire") || value.contains("cactus") || value.contains("magma") || value.contains("campfire") || value.contains("powder_snow");
    }

    private Object readStatValue(ClientPlayerEntity player, String statRef) {
        String normalized = statRef == null ? "" : statRef.trim();
        if (normalized.startsWith("ref.stat.")) {
            normalized = normalized.substring("ref.stat.".length());
        }
        return switch (normalized) {
            case "self.health" -> player.getHealth();
            case "self.max_health" -> player.getMaxHealth();
            case "self.hunger" -> player.getHungerManager().getFoodLevel();
            case "self.saturation" -> player.getHungerManager().getSaturationLevel();
            case "self.air" -> player.getAir();
            case "self.x" -> player.getX();
            case "self.y" -> player.getY();
            case "self.z" -> player.getZ();
            case "self.yaw" -> player.getYaw();
            case "self.pitch" -> player.getPitch();
            case "self.velocity_x" -> player.getVelocity().x;
            case "self.velocity_y" -> player.getVelocity().y;
            case "self.velocity_z" -> player.getVelocity().z;
            case "self.speed_horizontal" -> Math.sqrt(player.getVelocity().x * player.getVelocity().x + player.getVelocity().z * player.getVelocity().z);
            case "self.fall_distance" -> player.fallDistance;
            case "self.fire_ticks" -> player.getFireTicks();
            case "self.on_ground" -> player.isOnGround();
            case "self.sneaking" -> player.isSneaking();
            case "self.sprinting" -> player.isSprinting();
            case "self.swimming" -> player.isSwimming();
            case "self.touching_water" -> player.isTouchingWater();
            case "self.submerged_water" -> player.isSubmergedInWater();
            case "self.in_lava" -> player.isInLava();
            case "self.has_vehicle" -> player.hasVehicle();
            case "self.creative" -> player.isCreative();
            case "self.spectator" -> player.isSpectator();
            case "self.using_item" -> player.isUsingItem();
            default -> null;
        };
    }

    private boolean removeInventoryItems(PlayerInventory inventory, String itemId, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !itemMatches(stack, itemId)) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
        }
        return remaining == 0;
    }

    private int findHotbarSlot(PlayerInventory inventory, String itemId) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && itemMatches(stack, itemId)) {
                return i;
            }
        }
        return -1;
    }

    private int moveInventoryItemToSelectedHotbar(PlayerInventory inventory, String itemId) {
        int selected = Math.max(0, Math.min(8, inventory.selectedSlot));
        for (int i = 9; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !itemMatches(stack, itemId)) {
                continue;
            }
            ItemStack selectedStack = inventory.getStack(selected).copy();
            inventory.setStack(selected, stack.copy());
            inventory.setStack(i, selectedStack);
            return selected;
        }
        return -1;
    }

    private boolean startContainerTransfer(ClientPlayerInteractionManager interactionManager, ClientPlayerEntity player, int slotIndex, Map<String, Object> params, Map<String, Object> state, String direction) {
        if (player == null || player.currentScreenHandler == null || interactionManager == null) {
            state.put("container.transfer.failure_reason", "no_open_container");
            return false;
        }
        Slot sourceSlot = player.currentScreenHandler.slots.get(slotIndex);
        if (sourceSlot == null || !sourceSlot.hasStack() || sourceSlot.getStack().isEmpty()) {
            state.put("container.transfer.failure_reason", "empty_source_slot");
            return false;
        }
        ItemStack sourceStack = sourceSlot.getStack().copy();
        String itemId = stringParam(params, "item.id", "");
        if (!itemId.isBlank() && !itemMatches(sourceStack, itemId)) {
            state.put("container.transfer.failure_reason", "source_item_mismatch");
            return false;
        }
        if (itemId.isBlank()) {
            Identifier sourceId = Registries.ITEM.getId(sourceStack.getItem());
            itemId = sourceId == null ? "" : sourceId.toString();
        }
        if (itemId.isBlank()) {
            state.put("container.transfer.failure_reason", "unknown_item_id");
            return false;
        }
        int totalSlots = player.currentScreenHandler.slots.size();
        int playerInventoryStart = Math.max(0, totalSlots - 36);
        boolean sourceIsPlayer = slotIndex >= playerInventoryStart;
        boolean destinationPlayer = !sourceIsPlayer;
        int requested = intParam(params, "count.value", 0);
        int counter = intParam(params, "state.counter", 0);
        int remaining = requested > 0 ? Math.max(1, requested - counter) : sourceStack.getCount();
        int transferCount = Math.min(sourceStack.getCount(), remaining);
        int beforeInventoryCount = countInventory(player.getInventory(), itemId);
        state.put("container.transfer.active", true);
        state.put("container.transfer.phase", "await_screen_sync");
        state.put("container.transfer.wait_ticks", 3);
        state.put("container.transfer.slot", slotIndex);
        state.put("container.transfer.sync_id", player.currentScreenHandler.syncId);
        state.put("container.transfer.item_id", itemId);
        state.put("container.transfer.source_count", sourceStack.getCount());
        state.put("container.transfer.expected_count", transferCount);
        state.put("container.transfer.direction", direction);
        state.put("container.transfer.before_inventory_count", beforeInventoryCount);
        state.put("container.transfer.source_region", sourceIsPlayer ? "player" : "container");
        state.put("result.action", "STARTED");
        AutomationReporter.mem("container.transfer.slot", slotIndex);
        AutomationReporter.mem("container.transfer.item_id", itemId);
        AutomationReporter.mem("container.transfer.expected_count", transferCount);
        AutomationReporter.mem("container.transfer.direction", direction);
        boolean precise = transferCount > 0 && transferCount < sourceStack.getCount();
        boolean clicked = precise
                ? clickExactContainerTransfer(interactionManager, player, slotIndex, itemId, transferCount, destinationPlayer)
                : clickQuickContainerTransfer(interactionManager, player, slotIndex);
        if (!clicked) {
            clearContainerTransferState(state);
            state.put("container.transfer.failure_reason", precise ? "no_precise_destination_slot" : "click_failed");
            return false;
        }
        state.put("container.transfer.mode", precise ? "precise_pickup" : "quick_move");
        return true;
    }

    private boolean clickQuickContainerTransfer(ClientPlayerInteractionManager interactionManager, ClientPlayerEntity player, int slotIndex) {
        interactionManager.clickSlot(player.currentScreenHandler.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, player);
        return true;
    }

    private boolean clickExactContainerTransfer(ClientPlayerInteractionManager interactionManager, ClientPlayerEntity player, int sourceSlot, String itemId, int transferCount, boolean destinationPlayer) {
        if (transferCount <= 0 || player.currentScreenHandler.getCursorStack() == null || !player.currentScreenHandler.getCursorStack().isEmpty()) {
            return false;
        }
        int destinationSlot = findTransferDestinationSlot(player, itemId, destinationPlayer, transferCount);
        if (destinationSlot < 0) {
            return false;
        }
        int syncId = player.currentScreenHandler.syncId;
        interactionManager.clickSlot(syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        for (int i = 0; i < transferCount; i++) {
            interactionManager.clickSlot(syncId, destinationSlot, 1, SlotActionType.PICKUP, player);
        }
        interactionManager.clickSlot(syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        return true;
    }

    private int findTransferDestinationSlot(ClientPlayerEntity player, String itemId, boolean playerInventory, int count) {
        int totalSlots = player.currentScreenHandler.slots.size();
        int playerInventoryStart = Math.max(0, totalSlots - 36);
        int start = playerInventory ? playerInventoryStart : 0;
        int end = playerInventory ? totalSlots : playerInventoryStart;
        for (int i = start; i < end; i++) {
            Slot slot = player.currentScreenHandler.slots.get(i);
            if (slot == null || !slot.hasStack()) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (itemMatches(stack, itemId) && stack.getMaxCount() - stack.getCount() >= count) {
                return i;
            }
        }
        for (int i = start; i < end; i++) {
            Slot slot = player.currentScreenHandler.slots.get(i);
            if (slot != null && !slot.hasStack()) {
                return i;
            }
        }
        return -1;
    }

    private int openScreenHotbarSlot(ClientPlayerEntity player) {
        if (player == null || player.currentScreenHandler == null) {
            return -1;
        }
        int totalSlots = player.currentScreenHandler.slots.size();
        return totalSlots - 9 + Math.max(0, Math.min(8, player.getInventory().selectedSlot));
    }

    private void updateActiveContainerTransfer(ActiveExecution execution, ClientPlayerEntity player) {
        Map<String, Object> state = execution.state;
        if (!Boolean.TRUE.equals(state.get("container.transfer.active"))) {
            return;
        }
        int remaining = intState(state, "container.transfer.wait_ticks", 0);
        if (remaining > 0) {
            remaining--;
            state.put("container.transfer.wait_ticks", remaining);
            if (execution.activeNodeId != null) {
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "container.transfer.phase", "flow_phase", "[wait]", "container.transfer.phase", "await_screen_sync");
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "container.transfer.wait", "flow_metric", "[info]", "container.transfer.wait_ticks", remaining);
            }
            return;
        }
        String itemId = stringParam(state, "container.transfer.item_id", "");
        int before = intState(state, "container.transfer.before_inventory_count", 0);
        int expected = intState(state, "container.transfer.expected_count", 1);
        String sourceRegion = stringParam(state, "container.transfer.source_region", "");
        int after = player == null || itemId.isBlank() ? before : countInventory(player.getInventory(), itemId);
        int moved = "player".equals(sourceRegion) ? Math.max(0, before - after) : Math.max(0, after - before);
        boolean success = moved >= expected;
        state.put("container.transfer.after_inventory_count", after);
        state.put("result.transfer.moved_count", moved);
        state.put("result.action", success ? "SUCCESS" : "FAIL");
        AutomationReporter.mem("container.transfer.after_inventory_count", after);
        AutomationReporter.mem("result.transfer.moved_count", moved);
        AutomationReporter.mem("result.action", success ? "SUCCESS" : "FAIL");
        if (execution.activeNodeId != null) {
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "container.transfer.before", "flow_metric", "[info]", "container.transfer.before_inventory_count", before);
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "container.transfer.after", "flow_metric", "[info]", "container.transfer.after_inventory_count", after);
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "container.transfer.moved", "flow_metric", success ? "[done]" : "[fail]", "container.transfer.moved_count", moved + " / " + expected);
        }
        clearContainerTransferState(state);
        if (!success) {
            state.put("container.transfer.failed", true);
            state.put("container.transfer.failure_reason", "server_inventory_delta_not_confirmed");
        }
        execution.activeNodeId = null;
        execution.activeNodeLabel = null;
        execution.activeAction = null;
    }

    private void clearContainerTransferState(Map<String, Object> state) {
        state.remove("container.transfer.active");
        state.remove("container.transfer.phase");
        state.remove("container.transfer.wait_ticks");
    }

    private boolean itemMatches(ItemStack stack, String itemId) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id != null && itemId.equals(id.toString());
    }

    private void writeEntityState(Map<String, Object> state, Entity entity, String entityId) {
        state.put("current.target.kind", entity instanceof ItemEntity ? "item" : entity instanceof PlayerEntity ? "player" : "entity");
        state.put("current.target.id", entityId);
        state.put("current.target.uuid", entity.getUuidAsString());
        state.put("current.target.name", entity.getName().getString());
        state.put("current.target.x", entity.getX());
        state.put("current.target.y", entity.getY());
        state.put("current.target.z", entity.getZ());
        AutomationReporter.mem("current.target.id", entityId);
        AutomationReporter.mem("current.target.uuid", entity.getUuidAsString());
        AutomationReporter.mem("current.target.name", entity.getName().getString());
        AutomationReporter.mem("current.target.x", entity.getX());
        AutomationReporter.mem("current.target.y", entity.getY());
        AutomationReporter.mem("current.target.z", entity.getZ());
    }

    private void writeScanTelemetry(Map<String, Object> state, String kind, String id, String selector, double radius, int candidates) {
        state.put("scan.kind", kind);
        state.put("scan.target.id", id);
        state.put("scan.selector", selector);
        state.put("scan.radius", radius);
        state.put("scan.candidates", candidates);
        state.remove("scan.failure_reason");
        AutomationReporter.mem("scan.kind", kind);
        AutomationReporter.mem("scan.target.id", id);
        AutomationReporter.mem("scan.selector", selector);
        AutomationReporter.mem("scan.radius", radius);
        AutomationReporter.mem("scan.candidates", candidates);
    }

    private void writeSelectedTargetTelemetry(Map<String, Object> state, ClientPlayerEntity player, Vec3d selectedPos) {
        double distance = round(player.getPos().distanceTo(selectedPos));
        state.put("scan.selected.distance", distance);
        state.put("scan.selected.x", selectedPos.x);
        state.put("scan.selected.y", selectedPos.y);
        state.put("scan.selected.z", selectedPos.z);
        AutomationReporter.mem("scan.selected.distance", distance);
        AutomationReporter.mem("scan.selected.x", selectedPos.x);
        AutomationReporter.mem("scan.selected.y", selectedPos.y);
        AutomationReporter.mem("scan.selected.z", selectedPos.z);
    }

    private Entity findEntityByUuid(World world, String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        CachedEntityRef cached = this.cachedEntities.get(uuid);
        if (cached != null && cached.matches(world)) {
            return cached.entity();
        }
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(uuid);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        Entity found = findEntityByUuidNear(world, targetUuid, ENTITY_LOOKUP_RADIUS);
        if (found == null) {
            found = findEntityByUuidNear(world, targetUuid, ENTITY_LOOKUP_FALLBACK_RADIUS);
        }
        if (found != null) {
            this.cachedEntities.put(uuid, new CachedEntityRef(found, world));
        } else {
            this.cachedEntities.remove(uuid);
        }
        return found;
    }

    private Entity findEntityByUuidNear(World world, UUID targetUuid, double radius) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player == null) {
            return null;
        }
        Box searchBox = player.getBoundingBox().expand(radius);
        for (Entity entity : world.getOtherEntities(player, searchBox)) {
            if (targetUuid.equals(entity.getUuid())) {
                return entity;
            }
        }
        for (PlayerEntity entity : world.getPlayers()) {
            if (targetUuid.equals(entity.getUuid())) {
                return entity;
            }
        }
        return null;
    }

    private boolean canReuseCurrentTarget(ClientPlayerEntity player, ClientWorld world, Map<String, Object> state, String targetKind, String targetId) {
        if (targetKind.isBlank() || targetId.isBlank() || !targetKind.equalsIgnoreCase(stringParam(state, "current.target.kind", ""))) {
            return false;
        }
        double radius = doubleState(state, "scan.cache.radius", 48.0D);
        if (hasTargetPosition(state)) {
            Vec3d target = new Vec3d(doubleState(state, "current.target.x", player.getX()), doubleState(state, "current.target.y", player.getY()), doubleState(state, "current.target.z", player.getZ()));
            if (player.getPos().squaredDistanceTo(target) > radius * radius) {
                return false;
            }
        }
        return switch (targetKind.toLowerCase(Locale.ROOT)) {
            case "block" -> {
                if (!hasTargetPosition(state)) {
                    yield false;
                }
                Identifier found = Registries.BLOCK.getId(world.getBlockState(targetBlockPos(state)).getBlock());
                yield found != null && targetId.equals(found.toString());
            }
            case "item" -> {
                Entity entity = findEntityByUuid(world, stringParam(state, "current.target.uuid", ""));
                yield entity instanceof ItemEntity itemEntity && entity.isAlive() && itemMatches(itemEntity.getStack(), targetId);
            }
            case "entity" -> {
                Entity entity = findEntityByUuid(world, stringParam(state, "current.target.uuid", ""));
                Identifier found = entity == null ? null : Registries.ENTITY_TYPE.getId(entity.getType());
                yield entity != null && entity.isAlive() && found != null && targetId.equals(found.toString());
            }
            case "player" -> {
                Entity entity = findEntityByUuid(world, stringParam(state, "current.target.uuid", ""));
                yield entity instanceof PlayerEntity && entity.isAlive() && (targetId.equalsIgnoreCase(stringParam(state, "current.target.name", "")) || targetId.equals(entity.getUuidAsString()));
            }
            default -> false;
        };
    }

    private PlayerEntity findPlayerByName(ClientPlayerEntity player, String name, double radius, String selectorId) {
        Box box = player.getBoundingBox().expand(radius);
        PlayerEntity best = null;
        boolean farthest = "farthest".equalsIgnoreCase(selectorId) || "furthest".equalsIgnoreCase(selectorId);
        double bestDistance = farthest ? Double.MIN_VALUE : Double.MAX_VALUE;
        for (PlayerEntity other : player.getWorld().getEntitiesByClass(PlayerEntity.class, box, entity -> entity != null && entity.isAlive() && entity.getName().getString().equalsIgnoreCase(name))) {
            double distance = player.squaredDistanceTo(other);
            if ((!farthest && distance < bestDistance) || (farthest && distance > bestDistance)) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    private BlockPos findNearestBlock(ClientPlayerEntity player, String blockId, int radius) {
        World world = player.getWorld();
        Identifier wanted = Identifier.tryParse(blockId);
        if (world == null || wanted == null) {
            return null;
        }
        BlockPos origin = player.getBlockPos();
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Identifier found = Registries.BLOCK.getId(state.getBlock());
                    if (found == null || !wanted.equals(found)) {
                        continue;
                    }
                    double distance = pos.getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPos = pos.toImmutable();
                    }
                }
            }
        }
        return bestPos;
    }

    private void facePosition(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        smoothYawToward(player, yaw, LOOK_MAX_YAW_STEP);
        smoothPitchToward(player, pitch, LOOK_MAX_PITCH_STEP);
    }

    private void facePositionHorizontal(ClientPlayerEntity player, Vec3d target) {
        facePositionHorizontal(player, target, LOOK_MAX_YAW_STEP);
    }

    private void facePositionHorizontal(ClientPlayerEntity player, Vec3d target, float maxYawStep) {
        Vec3d origin = player.getPos();
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        smoothYawToward(player, yaw, maxYawStep);
    }

    private void faceNavigationLook(ClientPlayerEntity player, NavigatorDecision decision, double distance, int blockedTicks, int stallTicks) {
        Vec3d target = decision.lookTarget();
        float desiredYaw = yawToHorizontal(player.getPos(), target);
        float yawDelta = Math.abs(MathHelper.wrapDegrees(desiredYaw - player.getYaw()));
        float maxStep = NAV_LOOK_MAX_YAW_STEP;
        if ("advance".equals(decision.phase()) || "step_up".equals(decision.phase()) || "advance_interval".equals(decision.phase())) {
            maxStep = yawDelta > 55.0F ? 58.0F : yawDelta > 22.0F ? 42.0F : 24.0F;
        }
        if (blockedTicks > NAV_STUCK_TICKS / 2 || stallTicks > NAV_STUCK_TICKS / 2) {
            maxStep = 60.0F;
        }
        if (distance < 1.4D) {
            maxStep = Math.min(maxStep, 26.0F);
        }
        smoothYawToward(player, desiredYaw, maxStep);
    }

    private float yawToHorizontal(Vec3d origin, Vec3d target) {
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    private void smoothYawToward(ClientPlayerEntity player, float targetYaw, float maxStep) {
        float delta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        if (Math.abs(delta) <= LOOK_SNAP_EPSILON) {
            player.setYaw(targetYaw);
            return;
        }
        player.setYaw(player.getYaw() + MathHelper.clamp(delta, -maxStep, maxStep));
    }

    private void smoothPitchToward(ClientPlayerEntity player, float targetPitch, float maxStep) {
        float clampedTarget = MathHelper.clamp(targetPitch, -90.0F, 90.0F);
        float delta = clampedTarget - player.getPitch();
        if (Math.abs(delta) <= LOOK_SNAP_EPSILON) {
            player.setPitch(clampedTarget);
            return;
        }
        player.setPitch(MathHelper.clamp(player.getPitch() + MathHelper.clamp(delta, -maxStep, maxStep), -90.0F, 90.0F));
    }

    private BlockHitResult createCenterHit(ClientPlayerEntity player, BlockPos pos) {
        Direction side = Direction.UP;
        Vec3d hitPos = Vec3d.ofCenter(pos);
        boolean insideBlock = false;
        return new BlockHitResult(hitPos, side, pos, insideBlock);
    }

    private void updateActiveConsumption(ActiveExecution execution) {
        Map<String, Object> state = execution.state;
        if (!Boolean.TRUE.equals(state.get("consume.active"))) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player == null) {
            setHeldInput("use", false);
            state.remove("consume.active");
            return;
        }

        ItemStack stack = player.getMainHandStack();
        String trackedItemId = stringParam(state, "consume.item.id", "");
        int beforeCount = intState(state, "consume.before_count", 0);
        int beforeTotalCount = intState(state, "consume.before_total_count", beforeCount);
        int beforeHunger = intState(state, "consume.before_hunger", player.getHungerManager().getFoodLevel());
        float beforeSaturation = floatState(state, "consume.before_saturation", player.getHungerManager().getSaturationLevel());
        boolean started = Boolean.TRUE.equals(state.get("consume.started"));
        int ticks = intState(state, "consume.ticks", 0) + 1;
        int maxTicks = intState(state, "consume.max_ticks", 80);
        state.put("consume.ticks", ticks);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "consume.phase", "flow_phase", "[run ]", "consume.phase", started ? "using_item" : "await_use_start");
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "consume.ticks", "flow_metric", "[info]", "consume.ticks", ticks);
        AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "consume.before_count", "flow_metric", "[info]", "consume.before_count", beforeCount);

        if (player.isUsingItem()) {
            if (!started) {
                state.put("consume.started", true);
                AutomationReporter.mem("consume.started", true);
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "consume.phase", "flow_phase", "[branch]", "consume.phase", "use_started");
            }
            return;
        }

        if (!started && ticks < 8) {
            return;
        }

        int afterCount = 0;
        if (!stack.isEmpty()) {
            Identifier currentId = Registries.ITEM.getId(stack.getItem());
            if (currentId != null && trackedItemId.equals(currentId.toString())) {
                afterCount = stack.getCount();
            }
        }

        int consumed = Math.max(0, beforeCount - afterCount);
        int afterTotalCount = trackedItemId.isBlank() ? afterCount : countInventory(player.getInventory(), trackedItemId);
        consumed = Math.max(consumed, Math.max(0, beforeTotalCount - afterTotalCount));
        boolean hungerChanged = player.getHungerManager().getFoodLevel() > beforeHunger || player.getHungerManager().getSaturationLevel() > beforeSaturation;

        if (consumed > 0 || hungerChanged) {
            String resultKey = stringParam(state, "consume.result.key", "result.consumed");
            if (consumed <= 0) {
                consumed = 1;
            }
            state.put(resultKey, Math.min(1, consumed));
            state.put("result.action", "SUCCESS");
            AutomationReporter.mem(resultKey, Math.min(1, consumed));
            AutomationReporter.mem("result.action", "SUCCESS");
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "consume.outcome", "flow_decision", "[done]", "consume.outcome", "consumed=1 hunger_changed=" + hungerChanged);
        } else {
            if (ticks < maxTicks) {
                setHeldInput("use", true);
                if (ticks % 4 == 0) {
                    if (client.interactionManager != null) {
                        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                    }
                }
                state.put("consume.started", started || player.isUsingItem());
                AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "consume.outcome", "flow_decision", "[wait]", "consume.outcome", "awaiting_vanilla_consume");
                return;
            }
            state.put(stringParam(state, "consume.result.key", "result.consumed"), 0);
            state.put("result.action", "FAIL");
            state.put("consume.failed", true);
            state.put("consume.failure_reason", started ? "item_not_consumed" : "item_use_never_started");
            AutomationReporter.mem("result.action", "FAIL");
            AutomationCliViewModel.runtimeFlow(execution.frameId, execution.activeNodeId, "consume.outcome", "flow_decision", "[fail]", "consume.outcome", stringParam(state, "consume.failure_reason", "fail"));
        }

        state.remove("consume.active");
        state.remove("consume.item.id");
        state.remove("consume.before_count");
        state.remove("consume.before_total_count");
        state.remove("consume.before_hunger");
        state.remove("consume.before_saturation");
        state.remove("consume.result.key");
        state.remove("consume.started");
        state.remove("consume.ticks");
        state.remove("consume.max_ticks");
        state.remove("consume.hold_use");
        setHeldInput("use", false);
        execution.activeNodeId = null;
        execution.activeNodeLabel = null;
        execution.activeAction = null;
    }

    private float floatState(Map<String, Object> state, String key, float fallback) {
        Object value = state.get(key);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private double numberFromStateOrLiteral(Map<String, Object> state, String key, double fallback) {
        Object value = state.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1.0D : 0.0D;
        }
        if (value instanceof String text) {
            Object indirect = state.get(text);
            if (indirect instanceof Number number) {
                return number.doubleValue();
            }
            if (indirect instanceof Boolean bool) {
                return bool ? 1.0D : 0.0D;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean compareNumbers(double left, double right, String op) {
        return switch (op) {
            case "lt" -> left < right;
            case "lte" -> left <= right;
            case "gt" -> left > right;
            case "gte" -> left >= right;
            case "neq" -> Double.compare(left, right) != 0;
            default -> Double.compare(left, right) == 0;
        };
    }

    private int gotoIndex(List<KtlCompilerService.CompiledStep> steps, String label) {
        for (int i = 0; i < steps.size(); i++) {
            if (label.equals(steps.get(i).label())) {
                return i;
            }
        }
        throw new IllegalStateException("Unknown goto label: " + label);
    }

    private void emitPrimitiveStateDiff(String frameId, String nodeId, Map<String, Object> beforeState, Map<String, Object> afterState) {
        Set<String> orderedKeys = new LinkedHashSet<>(beforeState.keySet());
        orderedKeys.addAll(afterState.keySet());
        for (String key : orderedKeys) {
            boolean hadBefore = beforeState.containsKey(key);
            boolean hasAfter = afterState.containsKey(key);
            Object before = beforeState.get(key);
            Object after = afterState.get(key);
            if (!hasAfter && hadBefore) {
                AutomationCliViewModel.primitiveStateRemove(frameId, nodeId, key, before);
                continue;
            }
            if (!Objects.equals(before, after)) {
                AutomationCliViewModel.primitiveStateWrite(frameId, nodeId, key, hadBefore ? before : null, after);
            }
        }
    }

    private static Map<String, Object> resolveParams(Map<String, Object> params, Map<String, Object> state) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof String stringValue) {
                String resolvedValue = resolveString(stringValue, state);
                if (resolvedValue.startsWith("${") && resolvedValue.endsWith("}")) {
                    continue;
                }
                resolved.put(entry.getKey(), resolvedValue);
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    private static String resolveString(String value, Map<String, Object> state) {
        String result = value;
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static int countInventory(PlayerInventory inventory, String itemId) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static Entity findEntityBySelector(ClientPlayerEntity player, String entityId, double range, String selectorId) {
        Entity best = null;
        boolean farthest = "farthest".equalsIgnoreCase(selectorId) || "furthest".equalsIgnoreCase(selectorId);
        double bestDistance = farthest ? Double.MIN_VALUE : Double.MAX_VALUE;
        for (Entity entity : player.getWorld().getOtherEntities(player, player.getBoundingBox().expand(range))) {
            if (entity == player || entity.isRemoved()) {
                continue;
            }
            Identifier currentId = Registries.ENTITY_TYPE.getId(entity.getType());
            if (currentId == null || !currentId.toString().equals(entityId)) {
                continue;
            }
            double distance = entity.squaredDistanceTo(player);
            if (distance <= range * range && ((!farthest && distance < bestDistance) || (farthest && distance > bestDistance))) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int countMatchingEntities(ClientPlayerEntity player, String entityId, double range) {
        int count = 0;
        for (Entity entity : player.getWorld().getOtherEntities(player, player.getBoundingBox().expand(range))) {
            if (entity == player || entity.isRemoved()) {
                continue;
            }
            Identifier currentId = Registries.ENTITY_TYPE.getId(entity.getType());
            if (currentId != null && currentId.toString().equals(entityId) && entity.squaredDistanceTo(player) <= range * range) {
                count++;
            }
        }
        return count;
    }

    private static int countMatchingPlayers(ClientPlayerEntity player, String name, double range) {
        int count = 0;
        Box box = player.getBoundingBox().expand(range);
        for (PlayerEntity other : player.getWorld().getEntitiesByClass(PlayerEntity.class, box, entity -> entity != null && entity.isAlive() && entity.getName().getString().equalsIgnoreCase(name))) {
            if (other.squaredDistanceTo(player) <= range * range) {
                count++;
            }
        }
        return count;
    }

    private ItemEntity findItemEntityBySelector(ClientPlayerEntity player, String itemId, double range, String selectorId) {
        if (player.getWorld() == null) {
            return null;
        }
        ItemEntity best = null;
        boolean farthest = "farthest".equalsIgnoreCase(selectorId) || "furthest".equalsIgnoreCase(selectorId);
        double bestDistance = farthest ? Double.MIN_VALUE : Double.MAX_VALUE;
        for (ItemEntity itemEntity : player.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(range), entity -> true)) {
            ItemStack stack = itemEntity.getStack();
            if (stack.isEmpty() || !itemMatches(stack, itemId)) {
                continue;
            }
            double distance = itemEntity.squaredDistanceTo(player);
            if (distance <= range * range && ((!farthest && distance < bestDistance) || (farthest && distance > bestDistance))) {
                best = itemEntity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private int countMatchingItems(ClientPlayerEntity player, String itemId, double range) {
        if (player.getWorld() == null) {
            return 0;
        }
        int count = 0;
        for (ItemEntity itemEntity : player.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(range), entity -> true)) {
            ItemStack stack = itemEntity.getStack();
            if (!stack.isEmpty() && itemMatches(stack, itemId) && itemEntity.squaredDistanceTo(player) <= range * range) {
                count++;
            }
        }
        return count;
    }

    private int countMatchingBlocks(ClientPlayerEntity player, String blockId, int radius) {
        World world = player.getWorld();
        Identifier wanted = Identifier.tryParse(blockId);
        if (world == null || wanted == null) {
            return 0;
        }
        BlockPos origin = player.getBlockPos();
        int count = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    Identifier found = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
                    if (found != null && wanted.equals(found)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int findMatchingOpenScreenSlot(ClientPlayerEntity player, String itemId, boolean containerOnly) {
        if (player == null || player.currentScreenHandler == null) {
            return -1;
        }
        int totalSlots = player.currentScreenHandler.slots.size();
        int playerInventoryStart = Math.max(0, totalSlots - 36);
        for (int i = 0; i < totalSlots; i++) {
            if (containerOnly && i >= playerInventoryStart) {
                continue;
            }
            Slot slot = player.currentScreenHandler.slots.get(i);
            if (slot == null || !slot.hasStack()) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (itemMatches(stack, itemId)) {
                return i;
            }
        }
        return -1;
    }

    private static final class ActiveExecution {
        private final String frameId;
        private final String parentFrameId;
        private final String resumeLabel;
        private final InterpretationResult interpretationResult;
        private final List<KtlCompilerService.CompiledStep> steps;
        private final Map<String, Object> state;
        private int index;
        private String activeNodeId;
        private String activeNodeLabel;
        private String activeAction;

        private ActiveExecution(String frameId, String parentFrameId, String resumeLabel, InterpretationResult interpretationResult, List<KtlCompilerService.CompiledStep> steps, Map<String, Object> state) {
            this.frameId = frameId;
            this.parentFrameId = parentFrameId;
            this.resumeLabel = resumeLabel;
            this.interpretationResult = interpretationResult;
            this.steps = steps;
            this.state = state;
            this.index = 0;
            this.activeNodeId = null;
            this.activeNodeLabel = null;
            this.activeAction = null;
        }
    }

    private static final class HeldInput {
        private boolean pressed;
        private int tapTicks;
        private float yawDelta;
        private float pitchDelta;
    }

    private Vec3d chooseNavigationWaypoint(ClientWorld world, ClientPlayerEntity player, Vec3d finalTarget, Map<String, Object> state, boolean useY, int blockedTicks, int stallTicks, int escapeTicks) {
        BlockPos feet = player.getBlockPos();
        BlockPos finalFeet = BlockPos.ofFloored(finalTarget);
        double finalDistance = navigationDistance(player.getPos(), finalTarget, useY);
        boolean escaping = escapeTicks > 0 || blockedTicks >= NAV_STUCK_TICKS || stallTicks >= NAV_STUCK_TICKS || Boolean.TRUE.equals(state.get("move.progress.oscillating"));
        boolean directSafe = hasSafeDirectCorridor(world, feet, finalFeet, finalDistance > 6.0D ? 6 : 4);
        if (!escaping && directSafe && finalDistance <= 7.0D) {
            writeStateValue(state, "move.nav.waypoint.active", false);
            writeStateValue(state, "move.nav.path_style", "direct");
            return finalTarget;
        }

        Vec3d targetDirection = new Vec3d(finalTarget.x - player.getX(), 0.0D, finalTarget.z - player.getZ());
        if (targetDirection.lengthSquared() < 1.0E-4D) {
            writeStateValue(state, "move.nav.waypoint.active", false);
            writeStateValue(state, "move.nav.path_style", "arrive");
            return finalTarget;
        }
        targetDirection = targetDirection.normalize();

        Vec3d routeWaypoint = chooseRoutePlannerWaypoint(world, player, finalTarget, state, useY, escaping);
        if (routeWaypoint != null) {
            writeStateValue(state, "move.nav.waypoint.active", true);
            writeStateValue(state, "move.nav.waypoint.x", round(routeWaypoint.x));
            writeStateValue(state, "move.nav.waypoint.y", round(routeWaypoint.y));
            writeStateValue(state, "move.nav.waypoint.z", round(routeWaypoint.z));
            writeStateValue(state, "move.nav.waypoint.reason", "route_planner");
            writeStateValue(state, "move.nav.path_style", "route_planner");
            return routeWaypoint;
        }

        Vec3d best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        String bestReason = "none";
        double currentDistance = navigationDistance(player.getPos(), finalTarget, useY);
        for (int radius = 1; radius <= NAV_WAYPOINT_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 || Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = feet.getX() + dx;
                    int z = feet.getZ() + dz;
                    Vec3d stand = resolveLocalStandable(world, feet, x, z);
                    if (stand == null) {
                        continue;
                    }
                    BlockPos standFeet = BlockPos.ofFloored(stand);
                    MovementSafety safety = movementSafetyAt(world, standFeet, 4);
                    if (safety.danger() || safety.drop() && !escaping) {
                        continue;
                    }
                    boolean tightPassage = isEfficientTightPassage(world, standFeet, targetDirection);
                    if (safety.pocketRisk() >= NAV_POCKET_LIMIT && !escaping && !tightPassage) {
                        continue;
                    }
                    if (isTreePocket(world, standFeet) && !escaping && !tightPassage) {
                        continue;
                    }
                    if (!canTraverseRouteStep(world, feet, standFeet, finalTarget, escaping)) {
                        continue;
                    }
                    if (!hasSafeDirectCorridor(world, feet, standFeet, Math.min(6, radius + 1))) {
                        continue;
                    }
                    double candidateDistance = navigationDistance(stand, finalTarget, useY);
                    double improvement = currentDistance - candidateDistance;
                    if (!escaping && improvement < -0.35D) {
                        continue;
                    }
                    Vec3d toCandidate = new Vec3d(stand.x - player.getX(), 0.0D, stand.z - player.getZ());
                    if (toCandidate.lengthSquared() < 1.0E-4D) {
                        continue;
                    }
                    CandidateSafety candidateSafety = candidateSafety(world, feet, toCandidate.normalize());
                    double forwardScore = toCandidate.normalize().dotProduct(targetDirection);
                    double distanceCost = player.getPos().distanceTo(stand) * 0.45D;
                    double score = improvement * 6.0D + forwardScore * 3.2D + candidateSafety.clearance() * 0.75D;
                    score -= distanceCost;
                    score -= navigationAvoidancePenalty(state, standFeet) * 1.25D;
                    score -= safety.pocketRisk() * (tightPassage ? 4.0D : 10.0D);
                    score -= candidateSafety.pocketRisk() * (tightPassage ? 3.0D : 8.0D);
                    score -= naturalPocketPressure(world, standFeet) * (tightPassage ? 0.45D : 1.2D);
                    score -= Math.abs(stand.y - player.getY()) * 0.35D;
                    score -= radius * 0.18D;
                    if (hasSafeDirectCorridor(world, standFeet, finalFeet, 5)) {
                        score += 3.0D;
                    }
                    if (tightPassage) {
                        score += 4.0D;
                    }
                    if (candidateSafety.stepUp() > 0 && candidateSafety.clearance() >= 2) {
                        score += 2.4D;
                    }
                    if (escaping) {
                        score += candidateSafety.safe() ? 2.5D : -2.0D;
                        score += improvement > -1.0D ? 1.25D : 0.0D;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = stand;
                        bestReason = escaping ? "recovery_waypoint" : directSafe ? "global_arc" : "obstacle_avoidance";
                    }
                }
            }
            if (best != null && bestScore > 2.5D) {
                break;
            }
        }

        if (best == null) {
            writeStateValue(state, "move.nav.waypoint.active", false);
            writeStateValue(state, "move.nav.path_style", escaping ? "escape_direct" : "direct_fallback");
            return finalTarget;
        }
        writeStateValue(state, "move.nav.waypoint.active", true);
        writeStateValue(state, "move.nav.waypoint.x", round(best.x));
        writeStateValue(state, "move.nav.waypoint.y", round(best.y));
        writeStateValue(state, "move.nav.waypoint.z", round(best.z));
        writeStateValue(state, "move.nav.waypoint.reason", bestReason);
        writeStateValue(state, "move.nav.waypoint.score", round(bestScore));
        writeStateValue(state, "move.nav.path_style", bestReason);
        return best;
    }

    private Vec3d resolveLocalStandable(ClientWorld world, BlockPos origin, int x, int z) {
        for (int y = origin.getY() + NAV_WAYPOINT_VERTICAL_UP; y >= origin.getY() - NAV_WAYPOINT_VERTICAL_DOWN; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!canStandAt(world, pos)) {
                continue;
            }
            MovementSafety safety = movementSafetyAt(world, pos, 4);
            if (safety.danger() || safety.drop() || safety.pocketRisk() >= 0.9D) {
                continue;
            }
            return new Vec3d(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        }
        return null;
    }

    private Vec3d chooseRoutePlannerWaypoint(ClientWorld world, ClientPlayerEntity player, Vec3d finalTarget, Map<String, Object> state, boolean useY, boolean escaping) {
        BlockPos start = player.getBlockPos();
        BlockPos targetFeet = BlockPos.ofFloored(finalTarget);
        double directDistance = navigationDistance(player.getPos(), finalTarget, useY);
        if (hasSafeDirectCorridor(world, start, targetFeet, directDistance > 8.0D ? 8 : 5)) {
            writeStateValue(state, "move.nav.route.active", false);
            return null;
        }

        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::fScore));
        Map<Long, Double> bestCost = new HashMap<>();
        Map<Long, Long> previous = new HashMap<>();
        Map<Long, BlockPos> positions = new HashMap<>();
        long startKey = start.asLong();
        double startHeuristic = routeHeuristic(start, finalTarget, useY);
        open.add(new RouteNode(start, 0.0D, startHeuristic));
        bestCost.put(startKey, 0.0D);
        positions.put(startKey, start);

        long bestKey = startKey;
        double bestHeuristic = startHeuristic;
        int expansions = 0;
        while (!open.isEmpty() && expansions++ < NAV_ROUTE_MAX_EXPANSIONS) {
            RouteNode current = open.poll();
            long currentKey = current.pos().asLong();
            double known = bestCost.getOrDefault(currentKey, Double.MAX_VALUE);
            if (current.gScore() > known + 0.001D) {
                continue;
            }
            double heuristic = routeHeuristic(current.pos(), finalTarget, useY);
            if (heuristic < bestHeuristic) {
                bestHeuristic = heuristic;
                bestKey = currentKey;
            }
            if (heuristic <= 1.35D || current.pos().equals(targetFeet)) {
                bestKey = currentKey;
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = current.pos().getX() + dx;
                    int nz = current.pos().getZ() + dz;
                    if (Math.abs(nx - start.getX()) > NAV_ROUTE_RADIUS || Math.abs(nz - start.getZ()) > NAV_ROUTE_RADIUS) {
                        continue;
                    }
                    Vec3d stand = resolveRouteStandable(world, current.pos(), nx, nz, finalTarget, escaping);
                    if (stand == null) {
                        continue;
                    }
                    BlockPos next = BlockPos.ofFloored(stand);
                    if (Math.abs(next.getY() - start.getY()) > NAV_WAYPOINT_VERTICAL_UP + NAV_WAYPOINT_VERTICAL_DOWN) {
                        continue;
                    }
                    if (!canTraverseRouteStep(world, current.pos(), next, finalTarget, escaping)) {
                        continue;
                    }
                    long nextKey = next.asLong();
                    double stepCost = dx != 0 && dz != 0 ? 1.42D : 1.0D;
                    double verticalCost = Math.max(0.0D, next.getY() - current.pos().getY()) * 0.55D + Math.max(0.0D, current.pos().getY() - next.getY()) * 0.32D;
                    double pocketCost = Math.max(0.0D, pocketRisk(world, next) - NAV_CORRIDOR_POCKET_LIMIT) * (isEfficientTightPassage(world, next, horizontalDirection(Vec3d.ofCenter(current.pos()), Vec3d.ofCenter(next))) ? 0.6D : 3.4D);
                    double turnCost = routeTurnCost(previous, positions, currentKey, current.pos(), next);
                    double clearanceCost = routeClearanceCost(world, current.pos(), next, finalTarget, escaping);
                    double avoidanceCost = navigationAvoidancePenalty(state, next);
                    double humanCost = routeHumanCost(world, current.pos(), next, finalTarget);
                    double newCost = current.gScore() + stepCost + verticalCost + pocketCost + turnCost + clearanceCost + avoidanceCost + humanCost;
                    if (newCost >= bestCost.getOrDefault(nextKey, Double.MAX_VALUE)) {
                        continue;
                    }
                    bestCost.put(nextKey, newCost);
                    previous.put(nextKey, currentKey);
                    positions.put(nextKey, next);
                    open.add(new RouteNode(next, newCost, newCost + routeHeuristic(next, finalTarget, useY)));
                }
            }
        }

        if (bestKey == startKey || !positions.containsKey(bestKey)) {
            writeStateValue(state, "move.nav.route.active", false);
            writeStateValue(state, "move.nav.route.expansions", expansions);
            return null;
        }
        List<BlockPos> path = new ArrayList<>();
        long key = bestKey;
        while (positions.containsKey(key)) {
            path.add(positions.get(key));
            Long parent = previous.get(key);
            if (parent == null) {
                break;
            }
            key = parent;
        }
        Collections.reverse(path);
        if (path.size() < 2) {
            writeStateValue(state, "move.nav.route.active", false);
            return null;
        }
        int waypointIndex = Math.min(path.size() - 1, Math.max(1, directDistance > 10.0D ? NAV_ROUTE_LOOKAHEAD_LONG : NAV_ROUTE_LOOKAHEAD_SHORT));
        BlockPos waypoint = path.get(waypointIndex);
        writeStateValue(state, "move.nav.route.active", true);
        writeStateValue(state, "move.nav.route.x", waypoint.getX());
        writeStateValue(state, "move.nav.route.y", waypoint.getY());
        writeStateValue(state, "move.nav.route.z", waypoint.getZ());
        writeStateValue(state, "move.nav.route.nodes", path.size());
        writeStateValue(state, "move.nav.route.expansions", expansions);
        writeStateValue(state, "move.nav.route.reason", bestHeuristic <= 1.35D ? "goal_reached" : "best_local_route");
        return Vec3d.ofCenter(waypoint);
    }

    private Vec3d resolveRouteStandable(ClientWorld world, BlockPos origin, int x, int z, Vec3d finalTarget, boolean escaping) {
        Vec3d direction = horizontalDirection(Vec3d.ofCenter(origin), finalTarget);
        for (int y = origin.getY() + 1; y >= origin.getY() - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            boolean standable = canStandAt(world, pos);
            boolean stepUp = canStandAt(world, pos.up()) && canPassThrough(world, pos.up(2));
            BlockPos feet = standable ? pos : stepUp ? pos.up() : null;
            if (feet == null) {
                continue;
            }
            MovementSafety safety = movementSafetyAt(world, feet, 4);
            boolean tight = isEfficientTightPassage(world, feet, direction);
            boolean trustedEdge = isTrustedEdgePass(world, feet, direction);
            if (safety.danger()) {
                continue;
            }
            if (safety.drop() && !trustedEdge && !escaping) {
                continue;
            }
            if (safety.pocketRisk() >= 0.92D && !tight && !escaping) {
                continue;
            }
            return Vec3d.ofCenter(feet);
        }
        return null;
    }

    private boolean canTraverseRouteStep(ClientWorld world, BlockPos from, BlockPos to, Vec3d finalTarget, boolean escaping) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx == 0 && dz == 0) {
            return true;
        }
        Vec3d stepDirection = horizontalDirection(Vec3d.ofCenter(from), Vec3d.ofCenter(to));
        if (isDangerBlock(world, to) || isDangerBlock(world, to.down())) {
            return false;
        }
        MovementSafety targetSafety = movementSafetyAt(world, to, 4);
        if (targetSafety.drop() && !isTrustedEdgePass(world, from, stepDirection) && !escaping) {
            return false;
        }
        if (dx != 0 && dz != 0) {
            BlockPos sideX = new BlockPos(from.getX() + dx, from.getY(), from.getZ());
            BlockPos sideZ = new BlockPos(from.getX(), from.getY(), from.getZ() + dz);
            boolean xTraversable = isRouteShoulderTraversable(world, sideX, stepDirection, escaping);
            boolean zTraversable = isRouteShoulderTraversable(world, sideZ, stepDirection, escaping);
            if (!xTraversable && !zTraversable) {
                return false;
            }
            if ((targetSafety.drop() || isUnsafeDrop(world, to.down(), 3)) && (!xTraversable || !zTraversable)) {
                return false;
            }
        }
        Vec3d goalDirection = horizontalDirection(Vec3d.ofCenter(to), finalTarget);
        return escaping || targetSafety.pocketRisk() < 0.96D || isEfficientTightPassage(world, to, goalDirection);
    }

    private boolean isRouteShoulderTraversable(ClientWorld world, BlockPos pos, Vec3d direction, boolean escaping) {
        if (canStandAt(world, pos) || canStandAt(world, pos.up())) {
            if (escaping) {
                return true;
            }
            return !isUnsafeDrop(world, pos.down(), 3) || isTrustedEdgePass(world, pos, direction);
        }
        if (!canPassThrough(world, pos) || !canPassThrough(world, pos.up()) || !canPassThrough(world, pos.up(2))) {
            return false;
        }
        if (escaping) {
            return true;
        }
        return !isUnsafeDrop(world, pos.down(), 3) && isEfficientTightPassage(world, pos, direction);
    }

    private double routeHeuristic(BlockPos pos, Vec3d target, boolean useY) {
        Vec3d center = Vec3d.ofCenter(pos);
        return navigationDistance(center, target, useY);
    }

    private double routeTurnCost(Map<Long, Long> previous, Map<Long, BlockPos> positions, long currentKey, BlockPos current, BlockPos next) {
        Long parentKey = previous.get(currentKey);
        if (parentKey == null || !positions.containsKey(parentKey)) {
            return 0.0D;
        }
        BlockPos parent = positions.get(parentKey);
        Vec3d before = horizontalDirection(Vec3d.ofCenter(parent), Vec3d.ofCenter(current));
        Vec3d after = horizontalDirection(Vec3d.ofCenter(current), Vec3d.ofCenter(next));
        if (before.lengthSquared() < 1.0E-4D || after.lengthSquared() < 1.0E-4D) {
            return 0.0D;
        }
        double dot = MathHelper.clamp(before.normalize().dotProduct(after.normalize()), -1.0D, 1.0D);
        return (1.0D - dot) * 0.35D;
    }

    private boolean hasSafeDirectCorridor(ClientWorld world, BlockPos from, BlockPos to, int maxSteps) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (steps <= 0) {
            return true;
        }
        steps = Math.min(Math.max(1, maxSteps), steps);
        BlockPos previous = from;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / (double) steps;
            int x = MathHelper.floor(MathHelper.lerp(t, from.getX(), to.getX()));
            int z = MathHelper.floor(MathHelper.lerp(t, from.getZ(), to.getZ()));
            Vec3d stand = resolveLocalStandable(world, previous, x, z);
            if (stand == null) {
                return false;
            }
            BlockPos pos = BlockPos.ofFloored(stand);
            MovementSafety safety = movementSafetyAt(world, pos, 4);
            Vec3d corridorDirection = horizontalDirection(Vec3d.ofCenter(previous), Vec3d.ofCenter(pos));
            boolean tight = isEfficientTightPassage(world, pos, corridorDirection);
            boolean trustedEdge = isTrustedEdgePass(world, previous, corridorDirection);
            if (safety.danger()) {
                return false;
            }
            if (safety.drop() && !trustedEdge) {
                return false;
            }
            if ((!safety.safe() || safety.pocketRisk() >= NAV_POCKET_LIMIT || isTreePocket(world, pos)) && !tight && !hasOpposingSafeExits(world, pos)) {
                return false;
            }
            previous = pos;
        }
        return true;
    }

    private boolean isEfficientTightPassage(ClientWorld world, BlockPos feet, Vec3d targetDirection) {
        if (!canStandAt(world, feet) && !isSafeExit(world, feet)) {
            return false;
        }
        Direction forward = Direction.getFacing(targetDirection.x, 0.0D, targetDirection.z);
        Direction back = forward.getOpposite();
        boolean forwardExit = isSafeExit(world, feet.offset(forward)) || canStandAt(world, feet.offset(forward).up());
        boolean backExit = isSafeExit(world, feet.offset(back)) || canStandAt(world, feet.offset(back).up());
        boolean throughLine = forwardExit && backExit;
        boolean sideBlocked = !isSafeExit(world, feet.offset(forward.rotateYClockwise())) || !isSafeExit(world, feet.offset(forward.rotateYCounterclockwise()));
        boolean headClear = canPassThrough(world, feet.up()) && canPassThrough(world, feet.up(2));
        boolean doorwayStep = forwardExit || canPassThrough(world, feet.offset(forward)) && canPassThrough(world, feet.offset(forward).up());
        return headClear && doorwayStep && (throughLine || sideBlocked && safeExitCount(world, feet) >= 1);
    }

    private boolean isTrustedEdgePass(ClientWorld world, BlockPos feet, Vec3d targetDirection) {
        if (targetDirection.lengthSquared() < 1.0E-4D) {
            return false;
        }
        if (isUnsafeDrop(world, feet.down(), 3) || isDangerBlock(world, feet) || isDangerBlock(world, feet.down())) {
            return false;
        }
        Direction forward = Direction.getFacing(targetDirection.x, 0.0D, targetDirection.z);
        BlockPos next = feet.offset(forward);
        BlockPos second = next.offset(forward);
        if (isDangerBlock(world, next) || isDangerBlock(world, next.down())) {
            return false;
        }
        boolean immediateStand = canStandAt(world, next) || canStandAt(world, next.up());
        boolean shortLanding = canStandAt(world, second) || canStandAt(world, second.up()) || canStandAt(world, second.down());
        boolean narrowButOpen = canPassThrough(world, next) && canPassThrough(world, next.up()) && safeExitCount(world, feet) >= 2;
        boolean wallRail = isSolidBlocking(world, feet.offset(forward.rotateYClockwise())) || isSolidBlocking(world, feet.offset(forward.rotateYCounterclockwise()));
        return immediateStand || shortLanding && narrowButOpen && wallRail;
    }

    private void rememberNavigationAvoidance(Map<String, Object> state, ClientPlayerEntity player, ClientWorld world, Vec3d target) {
        Vec3d direction = horizontalDirection(player.getPos(), target);
        BlockPos feet = player.getBlockPos();
        BlockPos avoid = feet;
        if (direction.lengthSquared() > 1.0E-4D) {
            Direction facing = Direction.getFacing(direction.x, 0.0D, direction.z);
            BlockPos ahead = feet.offset(facing);
            if (!canStandAt(world, ahead) || isSolidBlocking(world, ahead) || isSolidBlocking(world, ahead.up()) || isUnsafeDrop(world, ahead.down(), 3)) {
                avoid = ahead;
            }
        }
        state.put("move.nav.avoid.x", avoid.getX());
        state.put("move.nav.avoid.y", avoid.getY());
        state.put("move.nav.avoid.z", avoid.getZ());
        state.put("move.nav.avoid.ticks", NAV_AVOID_MEMORY_TICKS);
        writeStateValue(state, "move.nav.avoid", avoid.getX() + "," + avoid.getY() + "," + avoid.getZ());
    }

    private double navigationAvoidancePenalty(Map<String, Object> state, BlockPos pos) {
        int ticks = intState(state, "move.nav.avoid.ticks", 0);
        if (ticks <= 0 || !state.containsKey("move.nav.avoid.x")) {
            return 0.0D;
        }
        int x = intState(state, "move.nav.avoid.x", pos.getX());
        int y = intState(state, "move.nav.avoid.y", pos.getY());
        int z = intState(state, "move.nav.avoid.z", pos.getZ());
        double horizontal = Math.sqrt(Math.pow(pos.getX() - x, 2.0D) + Math.pow(pos.getZ() - z, 2.0D));
        double vertical = Math.abs(pos.getY() - y);
        if (horizontal > 3.5D || vertical > 2.0D) {
            return 0.0D;
        }
        return (3.5D - horizontal) * 2.0D + Math.max(0.0D, 2.0D - vertical);
    }

    private double routeClearanceCost(ClientWorld world, BlockPos from, BlockPos to, Vec3d finalTarget, boolean escaping) {
        Vec3d direction = horizontalDirection(Vec3d.ofCenter(from), Vec3d.ofCenter(to));
        MovementSafety safety = movementSafetyAt(world, to, 4);
        boolean tight = isEfficientTightPassage(world, to, horizontalDirection(Vec3d.ofCenter(to), finalTarget));
        double cost = 0.0D;
        if (!canPassThrough(world, to.up()) || !canPassThrough(world, to.up(2))) {
            cost += 8.0D;
        }
        if (isTreePocket(world, to) && !tight && !escaping) {
            cost += 6.0D;
        }
        if (safety.drop() && !isTrustedEdgePass(world, from, direction) && !escaping) {
            cost += 10.0D;
        }
        int exits = safeExitCount(world, to);
        if (exits <= 1 && !tight && !escaping) {
            cost += 3.0D;
        }
        return cost;
    }

    private double routeHumanCost(ClientWorld world, BlockPos from, BlockPos to, Vec3d finalTarget) {
        int vertical = to.getY() - from.getY();
        double cost = 0.0D;
        Vec3d direction = horizontalDirection(Vec3d.ofCenter(from), Vec3d.ofCenter(to));
        if (vertical > 0) {
            cost += vertical * 0.9D;
        }
        if (vertical < 0) {
            cost += Math.abs(vertical) * 0.35D;
        }
        if (direction.lengthSquared() > 1.0E-4D) {
            Direction facing = Direction.getFacing(direction.x, 0.0D, direction.z);
            boolean railLeft = isSolidBlocking(world, to.offset(facing.rotateYClockwise()));
            boolean railRight = isSolidBlocking(world, to.offset(facing.rotateYCounterclockwise()));
            if (railLeft || railRight) {
                cost -= 0.45D;
            }
            Vec3d goal = horizontalDirection(Vec3d.ofCenter(to), finalTarget);
            if (goal.lengthSquared() > 1.0E-4D) {
                cost += Math.max(0.0D, 1.0D - direction.dotProduct(goal)) * 0.4D;
            }
        }
        return cost;
    }

    private boolean hasParkourHeadHitAssist(ClientWorld world, BlockPos feet, Vec3d landing) {
        Vec3d direction = horizontalDirection(Vec3d.ofCenter(feet), landing);
        if (direction.lengthSquared() < 1.0E-4D) {
            return false;
        }
        Direction facing = Direction.getFacing(direction.x, 0.0D, direction.z);
        BlockPos head = feet.up(2);
        BlockPos aheadHead = feet.offset(facing).up(2);
        return isSolidBlocking(world, head) || isSolidBlocking(world, aheadHead);
    }

    private boolean tryBreakNavigationBlock(ClientPlayerEntity player, ClientWorld world, Vec3d target, Map<String, Object> state) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerInteractionManager interactionManager = client == null ? null : client.interactionManager;
        if (interactionManager == null || player == null || world == null) {
            return false;
        }
        Vec3d direction = horizontalDirection(player.getPos(), target);
        if (direction.lengthSquared() < 1.0E-4D) {
            return false;
        }
        Direction facing = Direction.getFacing(direction.x, 0.0D, direction.z);
        BlockPos feet = player.getBlockPos();
        BlockPos[] checks = {
                feet.offset(facing),
                feet.offset(facing).up(),
                feet.offset(facing).up(2)
        };
        BlockPos targetBlock = null;
        for (BlockPos pos : checks) {
            BlockState blockState = world.getBlockState(pos);
            if (!blockState.isAir() && !blockState.getCollisionShape(world, pos).isEmpty() && !isDangerBlock(world, pos)) {
                targetBlock = pos;
                break;
            }
        }
        if (targetBlock == null || player.getPos().distanceTo(Vec3d.ofCenter(targetBlock)) > 5.2D) {
            return false;
        }
        BlockPos active = state.containsKey("move.nav.clear_block.x")
                ? new BlockPos(intState(state, "move.nav.clear_block.x", targetBlock.getX()), intState(state, "move.nav.clear_block.y", targetBlock.getY()), intState(state, "move.nav.clear_block.z", targetBlock.getZ()))
                : null;
        if (active == null || !active.equals(targetBlock)) {
            state.put("move.nav.clear_block.x", targetBlock.getX());
            state.put("move.nav.clear_block.y", targetBlock.getY());
            state.put("move.nav.clear_block.z", targetBlock.getZ());
            state.put("move.nav.clear_block.started", false);
            state.put("move.nav.clear_block.ticks", 0);
        }
        facePosition(player, Vec3d.ofCenter(targetBlock));
        boolean started = Boolean.TRUE.equals(state.get("move.nav.clear_block.started"));
        if (!started) {
            boolean began = interactionManager.attackBlock(targetBlock, facing.getOpposite());
            player.swingHand(Hand.MAIN_HAND);
            state.put("move.nav.clear_block.started", began);
        } else {
            interactionManager.updateBlockBreakingProgress(targetBlock, facing.getOpposite());
            int ticks = intState(state, "move.nav.clear_block.ticks", 0) + 1;
            state.put("move.nav.clear_block.ticks", ticks);
            if (ticks % 4 == 0) {
                player.swingHand(Hand.MAIN_HAND);
            }
        }
        writeStateValue(state, "move.nav.clear_block", targetBlock.getX() + "," + targetBlock.getY() + "," + targetBlock.getZ());
        return true;
    }

    private boolean isTreePocket(ClientWorld world, BlockPos feet) {
        if (hasOpposingSafeExits(world, feet) || safeExitCount(world, feet) >= 3) {
            return false;
        }
        int natural = naturalPocketPressure(world, feet);
        int exits = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos next = feet.offset(direction);
            if (isSafeExit(world, next) && pocketRisk(world, next) < NAV_POCKET_LIMIT && naturalPocketPressure(world, next) < 7) {
                exits++;
            }
        }
        boolean lowNaturalCeiling = isNaturalBlocker(world, feet.up(2)) || isNaturalBlocker(world, feet.up());
        return natural >= 7 && exits <= 1 || natural >= 4 && lowNaturalCeiling && exits <= 1;
    }

    private int naturalPocketPressure(ClientWorld world, BlockPos center) {
        int pressure = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    if (isNaturalBlocker(world, center.add(dx, dy, dz))) {
                        pressure++;
                    }
                }
            }
        }
        return pressure;
    }

    private boolean isNaturalBlocker(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (id == null) {
            return false;
        }
        String value = id.toString();
        return value.contains("log") || value.contains("wood") || value.contains("leaves") || value.contains("root") || value.contains("vine") || value.contains("bamboo") || value.contains("mushroom") || value.contains("azalea");
    }


    private NavigatorDecision buildNavigationDecision(ClientPlayerEntity player, ClientWorld world, Vec3d target, Map<String, Object> state, boolean sprint, double stopDistance, int blockedTicks, int stallTicks, int escapeTicks, String escapeSide) {
        Vec3d playerPos = player.getPos();
        Vec3d horizontal = new Vec3d(target.x - playerPos.x, 0.0D, target.z - playerPos.z);
        Vec3d moveDirection = horizontal.lengthSquared() < 1.0E-4D ? Vec3d.ZERO : horizontal.normalize();

        boolean inWater = player.isTouchingWater() || player.isSubmergedInWater();
        boolean lava = player.isInLava();
        boolean swim = inWater || lava;

        BlockPos feet = player.getBlockPos();
        BlockPos ahead = feet.add((int) Math.signum(moveDirection.x), 0, (int) Math.signum(moveDirection.z));
        BlockPos aheadUp = ahead.up();
        BlockPos belowAhead = ahead.down();

        boolean stepUpAhead = canStandAt(world, ahead.up()) && canPassThrough(world, ahead.up(2));
        boolean blockedAhead = (isSolidBlocking(world, ahead) || isSolidBlocking(world, aheadUp)) && !stepUpAhead;
        boolean trustedEdge = isTrustedEdgePass(world, feet, moveDirection);
        boolean dropAhead = isUnsafeDrop(world, belowAhead, 3) && !trustedEdge;
        boolean waterAhead = isWaterBlock(world, ahead) || isWaterBlock(world, belowAhead);

        String phase = "advance";
        String strafeMode = "none";
        Vec3d lookTarget = target;
        Vec3d finalDirection = moveDirection;
        boolean jump = false;

        if (swim) {
            Vec3d swimDir = new Vec3d(target.x - playerPos.x, target.y - playerPos.y, target.z - playerPos.z);
            finalDirection = swimDir.lengthSquared() < 1.0E-4D ? Vec3d.ZERO : swimDir.normalize();
            phase = "swim";
            return new NavigatorDecision(finalDirection, target, false, true, false, phase, strafeMode);
        }

        NavigationCandidate best = bestNavigationCandidate(world, feet, moveDirection, state, blockedTicks, stallTicks, escapeTicks, escapeSide);
        if (best != null) {
            finalDirection = best.direction();
            phase = best.phase();
            strafeMode = best.strafeMode();
            jump = best.jump();
        } else if (stepUpAhead && player.isOnGround()) {
            jump = true;
            phase = "step_up";
        } else if (blockedAhead) {
            jump = player.isOnGround();
            phase = jump ? "jump_obstacle" : "blocked";
        } else if (dropAhead && !waterAhead) {
            phase = "drop_blocked";
            finalDirection = Vec3d.ZERO;
        }

        return new NavigatorDecision(finalDirection, lookTarget, sprint, false, jump, phase, strafeMode);
    }

    private NavigationCandidate bestNavigationCandidate(ClientWorld world, BlockPos feet, Vec3d targetDirection, Map<String, Object> state, int blockedTicks, int stallTicks, int escapeTicks, String escapeSide) {
        if (targetDirection.lengthSquared() < 1.0E-4D) {
            return null;
        }
        boolean escaping = escapeTicks > 0 || blockedTicks >= NAV_STUCK_TICKS || stallTicks >= NAV_STUCK_TICKS;
        String lockedStrafe = stringParam(state, "move.nav.strafe_mode", "none");
        Vec3d left = rotateLeft(targetDirection);
        Vec3d right = rotateRight(targetDirection);
        List<NavigationCandidate> candidates = new ArrayList<>();
        addNavigationCandidate(candidates, world, feet, targetDirection, targetDirection, 0.0D, escaping, escapeSide, lockedStrafe, "advance", "none", false);
        addNavigationCandidate(candidates, world, feet, rotateYaw(targetDirection, 18.0D), targetDirection, 18.0D, escaping, escapeSide, lockedStrafe, "arc_right", "right", false);
        addNavigationCandidate(candidates, world, feet, rotateYaw(targetDirection, -18.0D), targetDirection, -18.0D, escaping, escapeSide, lockedStrafe, "arc_left", "left", false);
        addNavigationCandidate(candidates, world, feet, rotateYaw(targetDirection, 38.0D), targetDirection, 38.0D, escaping, escapeSide, lockedStrafe, "wide_right", "right", false);
        addNavigationCandidate(candidates, world, feet, rotateYaw(targetDirection, -38.0D), targetDirection, -38.0D, escaping, escapeSide, lockedStrafe, "wide_left", "left", false);
        addNavigationCandidate(candidates, world, feet, right, targetDirection, 90.0D, escaping, escapeSide, lockedStrafe, "strafe_right", "right", false);
        addNavigationCandidate(candidates, world, feet, left, targetDirection, -90.0D, escaping, escapeSide, lockedStrafe, "strafe_left", "left", false);
        boolean severeEscape = blockedTicks >= NAV_STUCK_TICKS + 8 || stallTicks >= NAV_STUCK_TICKS + 8;
        if (escaping) {
            addNavigationCandidate(candidates, world, feet, rotateYaw(targetDirection, 130.0D), targetDirection, 130.0D, true, escapeSide, lockedStrafe, "escape_back_right", "right", true);
            addNavigationCandidate(candidates, world, feet, rotateYaw(targetDirection, -130.0D), targetDirection, -130.0D, true, escapeSide, lockedStrafe, "escape_back_left", "left", true);
            if (severeEscape) {
                addNavigationCandidate(candidates, world, feet, targetDirection.multiply(-1.0D), targetDirection, 180.0D, true, escapeSide, lockedStrafe, "escape_reverse", "back", true);
            }
        }
        return candidates.stream().max(Comparator.comparingDouble(NavigationCandidate::score)).orElse(null);
    }

    private void addNavigationCandidate(List<NavigationCandidate> candidates, ClientWorld world, BlockPos feet, Vec3d direction, Vec3d targetDirection, double yawOffset, boolean escaping, String escapeSide, String lockedStrafe, String phase, String strafeMode, boolean escapeMove) {
        if (direction.lengthSquared() < 1.0E-4D) {
            return;
        }
        Vec3d normalized = direction.normalize();
        CandidateSafety safety = candidateSafety(world, feet, normalized);
        if (!safety.safe() && !escapeMove) {
            return;
        }
        double score = normalized.dotProduct(targetDirection) * 5.0D;
        score += safety.clearance() * 1.6D;
        score += safety.stepUp() * 2.6D;
        score -= safety.blocked() * 5.0D;
        score -= safety.drop() * 8.0D;
        boolean tightPassage = isEfficientTightPassage(world, feet.offset(Direction.getFacing(normalized.x, 0.0D, normalized.z)), targetDirection);
        score -= safety.pocketRisk() * (tightPassage ? 3.0D : 9.0D);
        score -= safety.naturalBlockers() * (tightPassage ? 0.35D : 0.95D);
        score -= Math.abs(yawOffset) * 0.012D;
        if (tightPassage) {
            score += 3.4D;
        }
        if (!escaping && !lockedStrafe.isBlank() && !"none".equals(lockedStrafe)) {
            if (lockedStrafe.equals(strafeMode)) {
                score += 2.4D;
            } else if (!"none".equals(strafeMode)) {
                score -= 2.2D;
            }
        }
        if (escaping) {
            score += escapeMove ? 3.5D : 0.75D;
            if ("left".equals(escapeSide) && strafeMode.contains("left")) {
                score += 2.0D;
            }
            if ("right".equals(escapeSide) && strafeMode.contains("right")) {
                score += 2.0D;
            }
            if ("back".equals(strafeMode)) {
                score -= 4.5D;
            }
        }
        boolean jump = safety.blocked() > 0 && safety.headClear();
        candidates.add(new NavigationCandidate(normalized, score, phase, strafeMode, jump));
    }

    private CandidateSafety candidateSafety(ClientWorld world, BlockPos feet, Vec3d direction) {
        int blocked = 0;
        int drop = 0;
        int clearance = 0;
        int stepUpCount = 0;
        int naturalBlockers = 0;
        double maxPocketRisk = 0.0D;
        boolean headClear = true;
        Vec3d normalized = direction.lengthSquared() < 1.0E-4D ? Vec3d.ZERO : direction.normalize();
        Vec3d origin = Vec3d.ofCenter(feet);
        Set<BlockPos> visited = new LinkedHashSet<>();
        for (int step = 1; step <= 4; step++) {
            Vec3d sample = origin.add(normalized.multiply(step * 0.75D));
            BlockPos pos = new BlockPos(MathHelper.floor(sample.x), feet.getY(), MathHelper.floor(sample.z));
            if (!visited.add(pos)) {
                continue;
            }
            boolean standable = canStandAt(world, pos);
            boolean stepUp = canStandAt(world, pos.up()) && canPassThrough(world, pos.up(2));
            boolean bodyBlocked = !standable && !stepUp;
            boolean headBlocked = !canPassThrough(world, pos.up()) || !canPassThrough(world, pos.up(2));
            MovementSafety safety = movementSafetyAt(world, standable ? pos : stepUp ? pos.up() : pos, 3);
            maxPocketRisk = Math.max(maxPocketRisk, safety.pocketRisk());
            naturalBlockers += naturalPocketPressure(world, pos);
            if (bodyBlocked || headBlocked || safety.danger()) {
                blocked++;
            } else {
                clearance++;
                if (stepUp) {
                    stepUpCount++;
                }
            }
            if (headBlocked) {
                headClear = false;
            }
            if (safety.drop() && !safety.water()) {
                drop++;
            }
        }
        boolean safe = blocked == 0 && drop == 0 && (maxPocketRisk < NAV_POCKET_LIMIT || clearance >= 2 && stepUpCount > 0);
        return new CandidateSafety(safe, blocked, drop, clearance, stepUpCount, headClear, maxPocketRisk, naturalBlockers);
    }

    private String chooseEscapeSide(ClientPlayerEntity player, ClientWorld world, Vec3d target, String previous) {
        Vec3d horizontal = new Vec3d(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (horizontal.lengthSquared() < 1.0E-4D) {
            return "none";
        }
        Vec3d direction = horizontal.normalize();
        BlockPos feet = player.getBlockPos();
        CandidateSafety left = candidateSafety(world, feet, rotateLeft(direction));
        CandidateSafety right = candidateSafety(world, feet, rotateRight(direction));
        if (left.safe() && !right.safe()) {
            return "left";
        }
        if (right.safe() && !left.safe()) {
            return "right";
        }
        if (left.clearance() - left.naturalBlockers() > right.clearance() - right.naturalBlockers()) {
            return "left";
        }
        if (right.clearance() - right.naturalBlockers() > left.clearance() - left.naturalBlockers()) {
            return "right";
        }
        return "left".equals(previous) ? "right" : "left";
    }

    private NavigatorDecision stabilizeNavigationDecision(Map<String, Object> state, ClientPlayerEntity player, ClientWorld world, NavigatorDecision decision, int blockedTicks, int stallTicks, int escapeTicks) {
        if (decision.swim() || decision.moveDirection().lengthSquared() < 1.0E-4D) {
            writeStateValue(state, "move.nav.steer_x", 0.0D);
            writeStateValue(state, "move.nav.steer_z", 0.0D);
            writeStateValue(state, "move.nav.steer_lock_ticks", 0);
            return decision;
        }

        Vec3d requested = decision.moveDirection().normalize();
        Vec3d previous = new Vec3d(
                doubleState(state, "move.nav.steer_x", 0.0D),
                0.0D,
                doubleState(state, "move.nav.steer_z", 0.0D)
        );
        if (previous.lengthSquared() > 1.0E-4D) {
            previous = previous.normalize();
        }

        boolean escaping = escapeTicks > 0 || blockedTicks >= NAV_STUCK_TICKS || stallTicks >= NAV_STUCK_TICKS;
        int lockTicks = intState(state, "move.nav.steer_lock_ticks", 0);
        String previousStrafe = stringParam(state, "move.nav.strafe_mode", "none");
        String requestedStrafe = decision.strafeMode();
        boolean sideChanged = !previousStrafe.equals(requestedStrafe)
                && !"none".equals(previousStrafe)
                && !"none".equals(requestedStrafe);

        Vec3d stabilized = requested;
        if (previous.lengthSquared() > 1.0E-4D && !escaping) {
            double dot = previous.dotProduct(requested);
            if (sideChanged && lockTicks > 0 && candidateSafety(world, player.getBlockPos(), previous).safe()) {
                stabilized = previous.multiply(0.82D).add(requested.multiply(0.18D)).normalize();
                requestedStrafe = previousStrafe;
                lockTicks--;
            } else if (dot > 0.15D) {
                stabilized = previous.multiply(0.62D).add(requested.multiply(0.38D)).normalize();
                lockTicks = Math.max(lockTicks - 1, 0);
            } else if (lockTicks > 0 && candidateSafety(world, player.getBlockPos(), previous).safe()) {
                stabilized = previous;
                requestedStrafe = previousStrafe;
                lockTicks--;
            } else {
                lockTicks = NAV_STEER_LOCK_TICKS;
            }
        } else if (!"none".equals(requestedStrafe)) {
            lockTicks = NAV_STEER_LOCK_TICKS;
        }

        Vec3d lookTarget = player.getPos().add(stabilized.multiply(5.0D));
        writeStateValue(state, "move.nav.steer_x", stabilized.x);
        writeStateValue(state, "move.nav.steer_z", stabilized.z);
        writeStateValue(state, "move.nav.steer_lock_ticks", Math.max(0, lockTicks));
        return new NavigatorDecision(stabilized, lookTarget, decision.sprint(), decision.swim(), decision.jump(), decision.phase(), requestedStrafe);
    }

    private void applyNavigationDecision(ClientPlayerEntity player, NavigatorDecision decision) {
        applyNavigationDecision(player, decision, 1.0D);
    }

    private void applyNavigationDecision(ClientPlayerEntity player, NavigatorDecision decision, double speedScale) {
        if (decision.swim()) {
            applySwimToward(player, decision.moveDirection(), decision.lookTarget());
            return;
        }

        if (decision.jump() && player.isOnGround()) {
            player.jump();
        }

        if (decision.moveDirection().lengthSquared() < 1.0E-4D) {
            releaseMovementInputs();
            return;
        }

        applyHorizontalMotion(player, decision.moveDirection(), decision.sprint(), speedScale, false);
    }

    private void applySwimToward(ClientPlayerEntity player, Vec3d direction, Vec3d lookTarget) {
        Vec3d normalized = direction.lengthSquared() < 1.0E-4D ? Vec3d.ZERO : direction.normalize();
        player.setSprinting(false);
        setHeldInput("forward", normalized.horizontalLengthSquared() > 1.0E-4D);
        setHeldInput("sprint", false);
        facePosition(player, lookTarget);
        if (normalized.y > 0.08D) {
            setHeldInput("jump", true);
        } else {
            setHeldInput("jump", false);
        }
        setHeldInput("sneak", normalized.y < -0.08D);
        applyPlayerInputState(player);
    }

    private double navigationStopDistance(Map<String, Object> params, Map<String, Object> state, double fallback) {
        if (hasResolvedParam(params, "stop.distance")) {
            return Math.max(0.12D, doubleParam(params, "stop.distance", fallback));
        }
        return isCoordinateTarget(state, params) ? NAV_PRECISE_STOP_DISTANCE : fallback;
    }

    private boolean isCoordinateTarget(Map<String, Object> state, Map<String, Object> params) {
        String paramKind = stringParam(params, "target.kind", "");
        String stateKind = stringParam(state, "current.target.kind", "");
        String paramId = stringParam(params, "target.id", "");
        String stateId = stringParam(state, "current.target.id", "");
        boolean hasCoordinateParams = hasResolvedParam(params, "target.x") && hasResolvedParam(params, "target.z");
        return hasCoordinateParams || "location".equalsIgnoreCase(paramKind) || "location".equalsIgnoreCase(stateKind) || "coordinate".equalsIgnoreCase(paramId) || "coordinate".equalsIgnoreCase(stateId);
    }

    private double precisionSpeedScale(double distance, double stopDistance) {
        double activeDistance = Math.max(0.01D, distance - stopDistance);
        if (activeDistance <= 0.18D) {
            return 0.18D;
        }
        if (activeDistance <= 0.45D) {
            return 0.28D;
        }
        if (activeDistance <= 0.85D) {
            return 0.45D;
        }
        if (activeDistance <= NAV_PRECISE_SLOW_DISTANCE) {
            return 0.65D;
        }
        return 1.0D;
    }

    private double navigationDistance(Vec3d from, Vec3d to, boolean useY) {
        if (useY) {
            return from.distanceTo(to);
        }
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Vec3d rotateLeft(Vec3d vec) {
        return new Vec3d(-vec.z, 0.0D, vec.x).normalize();
    }

    private Vec3d rotateRight(Vec3d vec) {
        return new Vec3d(vec.z, 0.0D, -vec.x).normalize();
    }

    private Vec3d rotateYaw(Vec3d vec, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d(vec.x * cos - vec.z * sin, 0.0D, vec.x * sin + vec.z * cos).normalize();
    }

    private boolean canMoveThrough(ClientWorld world, BlockPos origin, Vec3d dir) {
        BlockPos next = origin.add((int) Math.signum(dir.x), 0, (int) Math.signum(dir.z));
        return !isSolidBlocking(world, next) && !isSolidBlocking(world, next.up());
    }

    private boolean isDirectionSafe(ClientWorld world, BlockPos origin, Vec3d dir) {
        BlockPos next = origin.add((int) Math.signum(dir.x), 0, (int) Math.signum(dir.z));
        return !isUnsafeDrop(world, next.down(), 3);
    }

    private boolean isSolidBlocking(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
    }

    private boolean isRayPassThrough(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(world, pos).isEmpty();
    }

    private PiercingRayResult piercingRaycast(MinecraftClient client, double maxDistance) {
        ClientPlayerEntity player = client == null ? null : client.player;
        ClientWorld world = client == null ? null : client.world;
        if (player == null || world == null) {
            return new PiercingRayResult(client == null ? null : client.crosshairTarget, 0, "");
        }
        Vec3d start = player.getEyePos();
        Vec3d direction = player.getRotationVec(1.0F).normalize();
        Vec3d end = start.add(direction.multiply(maxDistance));
        BlockTrace blockTrace = traceSolidBlockThroughPassables(world, player, start, direction, maxDistance);
        EntityHitResult entityHit = traceEntityAlongRay(world, player, start, end, blockTrace.distance());
        if (entityHit != null) {
            return new PiercingRayResult(entityHit, blockTrace.piercedBlocks(), blockTrace.lastPassThroughBlock());
        }
        HitResult hit = blockTrace.hit() == null ? BlockHitResult.createMissed(end, Direction.getFacing(direction.x, direction.y, direction.z), BlockPos.ofFloored(end)) : blockTrace.hit();
        return new PiercingRayResult(hit, blockTrace.piercedBlocks(), blockTrace.lastPassThroughBlock());
    }

    private BlockTrace traceSolidBlockThroughPassables(ClientWorld world, ClientPlayerEntity player, Vec3d start, Vec3d direction, double maxDistance) {
        Set<BlockPos> visited = new HashSet<>();
        int pierced = 0;
        String lastPass = "";
        for (double distance = 0.0D; distance <= maxDistance; distance += AUTOMATION_RAY_STEP) {
            Vec3d point = start.add(direction.multiply(distance));
            BlockPos pos = BlockPos.ofFloored(point);
            if (!visited.add(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (isRayPassThrough(world, pos)) {
                if (!state.isAir()) {
                    pierced++;
                    Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                    lastPass = blockId == null ? state.getBlock().toString() : blockId.toString();
                }
                continue;
            }
            VoxelShape shape = state.getCollisionShape(world, pos);
            BlockHitResult shapeHit = shape.raycast(start, start.add(direction.multiply(maxDistance)), pos);
            if (shapeHit != null) {
                return new BlockTrace(shapeHit, start.distanceTo(shapeHit.getPos()), pierced, lastPass);
            }
            Direction side = Direction.getFacing(direction.x, direction.y, direction.z).getOpposite();
            return new BlockTrace(new BlockHitResult(point, side, pos, false), distance, pierced, lastPass);
        }
        return new BlockTrace(null, maxDistance, pierced, lastPass);
    }

    private EntityHitResult traceEntityAlongRay(ClientWorld world, ClientPlayerEntity player, Vec3d start, Vec3d end, double blockDistance) {
        Entity best = null;
        Vec3d bestHit = null;
        double bestDistance = blockDistance * blockDistance;
        Box searchBox = player.getBoundingBox().stretch(end.subtract(start)).expand(1.0D);
        for (Entity entity : world.getOtherEntities(player, searchBox, entity -> entity != null && entity.isAlive() && !entity.isSpectator() && entity.canHit())) {
            Optional<Vec3d> hit = entity.getBoundingBox().expand(entity.getTargetingMargin()).raycast(start, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = start.squaredDistanceTo(hit.get());
            if (distance < bestDistance) {
                best = entity;
                bestHit = hit.get();
                bestDistance = distance;
            }
        }
        return best == null ? null : new EntityHitResult(best, bestHit);
    }

    private PiercingVisibilityResult piercingVisibility(ClientWorld world, ClientPlayerEntity player, Vec3d target) {
        Vec3d start = player.getEyePos();
        Vec3d delta = target.subtract(start);
        double distance = delta.length();
        if (distance <= 0.001D) {
            return new PiercingVisibilityResult(true, "", 0);
        }
        BlockTrace trace = traceSolidBlockThroughPassables(world, player, start, delta.normalize(), distance);
        if (trace.hit() == null || trace.distance() >= distance - 0.2D || trace.hit().getBlockPos().isWithinDistance(target, 1.1D)) {
            return new PiercingVisibilityResult(true, "", trace.piercedBlocks());
        }
        BlockState state = world.getBlockState(trace.hit().getBlockPos());
        Identifier blocker = Registries.BLOCK.getId(state.getBlock());
        return new PiercingVisibilityResult(false, blocker == null ? state.getBlock().toString() : blocker.toString(), trace.piercedBlocks());
    }

    private boolean isWaterBlock(ClientWorld world, BlockPos pos) {
        return !world.getFluidState(pos).isEmpty() && world.getFluidState(pos).isStill();
    }

    private boolean isUnsafeDrop(ClientWorld world, BlockPos start, int maxDepth) {
        BlockPos cursor = start;
        for (int i = 0; i < maxDepth; i++) {
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && state.isOpaqueFullCube(world, cursor)) {
                return false;
            }
            if (isWaterBlock(world, cursor)) {
                return false;
            }
            cursor = cursor.down();
        }
        return true;
    }

    private record CachedEntityRef(Entity entity, World world) {
        private boolean matches(World expectedWorld) {
            return this.entity != null && !this.entity.isRemoved() && this.entity.isAlive() && this.world == expectedWorld;
        }
    }

    private record PrimitiveRunResult(boolean success, Map<String, Object> resolvedParams) {
    }

    private record NavigatorDecision(Vec3d moveDirection, Vec3d lookTarget, boolean sprint, boolean swim, boolean jump,
                                     String phase, String strafeMode) {
    }

    private record NavigationCandidate(Vec3d direction, double score, String phase, String strafeMode, boolean jump) {
    }

    private record CandidateSafety(boolean safe, int blocked, int drop, int clearance, int stepUp, boolean headClear,
                                   double pocketRisk, int naturalBlockers) {
    }

    private record RouteNode(BlockPos pos, double gScore, double fScore) {
    }

    private record MovementProgress(boolean ok, String reason, boolean distanceImproved, boolean positionChanged,
                                    boolean oscillating, int noProgressTicks) {
    }

    private record MovementSafety(boolean safe, String reason, boolean danger, boolean drop, boolean water,
                                  double pocketRisk) {
    }

    private record MovementPlan(boolean ok, String segment, boolean sprint, String reason, double danger,
                                double horizontal, double vertical) {
    }

    private record ParkourAnalysis(boolean ok, String reason, boolean sprint, double horizontal, double vertical,
                                   double landingRisk) {
    }

    private record PiercingRayResult(HitResult hit, int piercedBlocks, String lastPassThroughBlock) {
    }

    private record PiercingVisibilityResult(boolean visible, String blocker, int piercedBlocks) {
    }

    private record BlockTrace(BlockHitResult hit, double distance, int piercedBlocks, String lastPassThroughBlock) {
    }
}
