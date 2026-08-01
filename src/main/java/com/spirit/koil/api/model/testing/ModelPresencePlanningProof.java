package com.spirit.koil.api.model.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationModeStatusChatPanel;
import com.spirit.koil.api.chat.ChatComposerMenuBridge;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.planning.ReviewedPlanAuthorization;
import com.spirit.koil.api.model.planning.ValidatedAutomationPlan;
import com.spirit.koil.api.model.presence.ModelPresenceLineGeometry;
import com.spirit.koil.api.model.presence.ModelPresenceState;
import com.spirit.koil.api.model.presence.ModelPresenceWireCodec;
import com.spirit.koil.api.model.tool.AutomationPlanModelToolRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class ModelPresencePlanningProof {
    private ModelPresencePlanningProof() {
    }

    public static void main(String[] args) {
        proveSessionPlanningDefaults();
        proveValidatedReviewedAuthorization();
        proveTypedTimelineAndPlanRows();
        provePresenceWirePrivacyAndGeometry();
        System.out.println("Model presence and reviewed-planning proof passed.");
    }

    private static void proveSessionPlanningDefaults() {
        AutomationModeController.setAutomationMode(true);
        require(!AutomationModeController.isPlanningModeEnabled(), "Planning Mode did not start off");
        require(!AutomationModeController.isDeepThinkingEnabled(), "Deep Thought did not start off");
        AutomationModeController.setPlanningModeEnabled(true);
        AutomationModeController.setDeepThinkingEnabled(true);
        AutomationModeController.setExperimentalCompactAgentEnabled(true);
        AutomationModeController.enableYoloMode();
        require(AutomationModeController.isPlanningModeEnabled(), "Unrestricted disabled Planning Mode");
        require(AutomationModeController.isDeepThinkingEnabled(), "Planning Mode disabled Deep Thought");
        var automationEntries = ChatComposerMenuBridge.childEntries(ChatComposerMenuBridge.AUTOMATION_SECTION, null);
        require(automationEntries.size() == 5
                        && "composer:automation_experimental".equals(automationEntries.get(4).id()),
                "Automation popup did not add Experimental as its fourth mode option");
        require("TEST | D-T | Plan".equals(AutomationModeStatusChatPanel.modeIndicatorLabels(
                        AutomationModeController.snapshot())),
                "Automation mode indicators were not ordered TEST before D-T before Plan");
        require(AutomationModeStatusChatPanel.experimentalIndicatorColor(0) == 0x55FF55,
                "TEST indicator did not use the configurable green color");
        AutomationModeController.setYoloModeEnabled(false);
        require(!AutomationModeController.isYoloMode(), "Unrestricted did not toggle back to standard approvals");
        AutomationThinkingPolicy.Decision forced =
                AutomationThinkingPolicy.evaluate("Jump", false, true);
        require(forced.includePlanTool(), "forced Planning Mode omitted automation.plan");
        AutomationThinkingPolicy.Decision autonomous =
                AutomationThinkingPolicy.evaluate("Walk 4 blocks, then jump", false, false);
        require(autonomous.includePlanTool(), "complex normal mode could not plan autonomously");
        AutomationModeController.setAutomationMode(false);
        require(!AutomationModeController.isPlanningModeEnabled(), "Planning Mode survived session shutdown");
        require(!AutomationModeController.isPlanningActive(), "active planning survived session shutdown");
    }

    private static void proveValidatedReviewedAuthorization() {
        JsonObject planArguments = new JsonObject();
        planArguments.addProperty("objective", "Walk four blocks, then jump");
        JsonArray steps = new JsonArray();
        JsonObject walk = new JsonObject();
        walk.addProperty("toolId", "movement.walk_relative");
        walk.addProperty("reason", "Move first");
        JsonObject walkArguments = new JsonObject();
        walkArguments.addProperty("direction", "forward");
        walkArguments.addProperty("distance", 4);
        walk.add("arguments", walkArguments);
        steps.add(walk);
        JsonObject jump = new JsonObject();
        jump.addProperty("toolId", "player.jump");
        jump.add("arguments", new JsonObject());
        steps.add(jump);
        planArguments.add("steps", steps);

        ModelToolResult result = AutomationPlanModelToolRegistry.execute(new ModelToolCall(
                "plan-proof",
                AutomationPlanModelToolRegistry.TOOL_ID,
                planArguments
        )).join();
        ValidatedAutomationPlan plan = ValidatedAutomationPlan.from(result);
        require(plan.id().startsWith("kap-"), "validated plan omitted a stable plan id");
        require(plan.steps().size() == 2, "validated plan lost ordered steps");

        JsonObject wireSafeArguments = planArguments.deepCopy();
        wireSafeArguments.getAsJsonArray("steps").get(0).getAsJsonObject()
                .addProperty("toolId", "movement_walk_relative");
        wireSafeArguments.getAsJsonArray("steps").get(1).getAsJsonObject()
                .addProperty("toolId", "player_jump");
        ModelToolResult wireSafeResult = AutomationPlanModelToolRegistry.execute(new ModelToolCall(
                "wire-safe-plan-proof",
                "automation.plan",
                wireSafeArguments
        )).join();
        ValidatedAutomationPlan wireSafePlan = ValidatedAutomationPlan.from(wireSafeResult);
        require(wireSafePlan.steps().size() == 2
                        && "movement.walk_relative".equals(wireSafePlan.steps().get(0).toolId())
                        && "player.jump".equals(wireSafePlan.steps().get(1).toolId()),
                "provider-safe nested plan tool ids were not restored to canonical Koil ids");

        ReviewedPlanAuthorization authorization = new ReviewedPlanAuthorization(plan);
        ModelToolCall exactWalk = plan.steps().get(0).asToolCall(plan.id());
        require(!authorization.authorizesExactStep(1, exactWalk),
                "unreviewed plan authorized an action");
        authorization.approve();
        require(authorization.authorizesExactStep(1, exactWalk),
                "approved exact plan step was rejected");
        JsonObject changedArguments = exactWalk.arguments().deepCopy();
        changedArguments.addProperty("distance", 5);
        require(!authorization.authorizesExactStep(1, new ModelToolCall(
                        "changed",
                        exactWalk.toolId(),
                        changedArguments
                )),
                "changed side effect was authorized by the old plan");
        require(!authorization.authorizesExactStep(2, exactWalk),
                "plan step order was not enforced");
        require(ReviewedPlanAuthorization.allowsUnplannedDiagnostic(new ModelToolCall(
                        "read",
                        "minecraft.knowledge",
                        new JsonObject()
                )),
                "read-only diagnostic was blocked");
        require(!ReviewedPlanAuthorization.allowsUnplannedDiagnostic(exactWalk),
                "unplanned side effect was treated as a diagnostic");
        authorization.reject();
        require(!authorization.authorizesExactStep(1, exactWalk),
                "rejected plan retained authorization");
    }

    private static void proveTypedTimelineAndPlanRows() {
        UUID requestId = UUID.randomUUID();
        ModelGenerationHudState.begin(requestId, "proof", true);
        ModelGenerationHudState.appendEvent(
                requestId,
                ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY,
                "Inspect the target safely."
        );
        ModelGenerationHudState.setPlan(requestId, "kap-proof", List.of(
                new ModelGenerationHudState.PlanStep(
                        1,
                        "player.jump",
                        "Jump once",
                        ModelGenerationHudState.PlanStepStatus.PENDING,
                        ""
                )
        ));
        ModelGenerationHudState.updatePlanStep(
                requestId,
                1,
                ModelGenerationHudState.PlanStepStatus.COMPLETED,
                "completed"
        );
        ModelGenerationHudState.Snapshot snapshot = ModelGenerationHudState.visibleSnapshot();
        require(snapshot != null && snapshot.events().size() == 1,
                "typed activity event was not retained");
        require(snapshot.activity().contains("┊"), "dotted thought connector was not rendered");
        require(snapshot.activity().contains("┬─ Goal") && snapshot.activity().contains("└─"),
                "plan goal and steps were not rendered as one connected tree");
        require(snapshot.activity().contains("§a✓ 1 completed"),
                "completed plan row did not use the shared success color");
        require(snapshot.plan() != null
                        && snapshot.plan().steps().get(0).status()
                        == ModelGenerationHudState.PlanStepStatus.COMPLETED,
                "plan row did not move to completed");
        ModelGenerationHudState.dismiss(requestId);
    }

    private static void provePresenceWirePrivacyAndGeometry() {
        ModelPresenceState.Snapshot source = new ModelPresenceState.Snapshot(
                ModelPresenceState.ActivityKind.ASK,
                "writing",
                1234L,
                true
        );
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        ModelPresenceWireCodec.write(buffer, source, 1234L);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes);
        String wire = new String(bytes, StandardCharsets.ISO_8859_1);
        require(!wire.contains("secret prompt"), "presence wire exposed prompt text");
        ModelPresenceWireCodec.Decoded decoded = ModelPresenceWireCodec.read(buffer);
        require(decoded.supported(), "presence protocol version was rejected");
        require(decoded.snapshot().kind() == ModelPresenceState.ActivityKind.ASK,
                "/ask presence kind was not serialized");
        require(decoded.snapshot().active(), "presence active flag was not serialized");

        ModelPresenceState.Snapshot completed = new ModelPresenceState.Snapshot(
                ModelPresenceState.ActivityKind.AUTOMATION,
                "completed",
                10_000L,
                false
        );
        require(completed.visibleAt(10_000L + ModelPresenceState.TERMINAL_VISIBILITY_MILLIS),
                "terminal presence expired too early");
        require(!completed.visibleAt(10_001L + ModelPresenceState.TERMINAL_VISIBILITY_MILLIS),
                "terminal presence did not expire");
        require(ModelPresenceState.color(completed) == 0xFF55FF55,
                "completed presence was not green");

        ModelPresenceLineGeometry.Bounds line =
                ModelPresenceLineGeometry.beneathName(7, 11, 42, 9);
        require(line.left() == 7 && line.right() == 49,
                "Tab/world status line width diverged from the name width");
        require(line.bottom() - line.top() == 1,
                "Tab/world status line was not one pixel high");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
