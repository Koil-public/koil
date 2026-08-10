package com.spirit.koil.api.model.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationModeStatusChatPanel;
import com.spirit.koil.api.chat.ChatComposerMenuBridge;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelActivityTreeGlyphs;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.planning.NoFailExecutionPolicy;
import com.spirit.koil.api.model.planning.ReviewedPlanAuthorization;
import com.spirit.koil.api.model.planning.ValidatedAutomationPlan;
import com.spirit.koil.api.model.presence.ModelPresenceLineGeometry;
import com.spirit.koil.api.model.presence.ModelPresenceState;
import com.spirit.koil.api.model.presence.ModelPresenceWireCodec;
import com.spirit.koil.api.model.presence.ModelPresenceWorldLineStyle;
import com.spirit.koil.api.model.presence.CombinedModelExecutorStatus;
import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
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
        proveCombinedModelExecutorPresence();
        proveNoFailVerificationComposition();
        System.out.println("Model presence and reviewed-planning proof passed.");
    }

    private static void proveSessionPlanningDefaults() {
        AutomationModeController.setAutomationMode(true);
        require(!AutomationModeController.isPlanningModeEnabled(), "Planning Mode did not start off");
        require(!AutomationModeController.isDeepThinkingEnabled(), "Deep Thought did not start off");
        require(!AutomationModeController.isVerificationEnabled(), "Automation verification did not start off");
        AutomationModeController.setPlanningModeEnabled(true);
        AutomationModeController.setDeepThinkingEnabled(true);
        AutomationModeController.setExperimentalCompactAgentEnabled(true);
        AutomationModeController.enableUnrestrictedMode();
        require(AutomationModeController.isPlanningModeEnabled(), "Unrestricted disabled Planning Mode");
        require(AutomationModeController.isDeepThinkingEnabled(), "Planning Mode disabled Deep Thought");
        var automationEntries = ChatComposerMenuBridge.childEntries(ChatComposerMenuBridge.AUTOMATION_SECTION, null);
        require(automationEntries.size() == 5
                        && ChatComposerMenuBridge.EXPERIMENTAL_SELECTOR.equals(automationEntries.get(4).id())
                        && ChatComposerMenuBridge.isNestedSelector(automationEntries.get(4).id()),
                "Automation popup did not expose Experimental as a child selector");
        var experimentalEntries = ChatComposerMenuBridge.nestedEntries(
                ChatComposerMenuBridge.EXPERIMENTAL_SELECTOR, null);
        var experimentalIds = experimentalEntries.stream().map(entry -> entry.id()).collect(java.util.stream.Collectors.toSet());
        require(experimentalIds.containsAll(java.util.Set.of(
                        "composer:experimental_verification",
                        "composer:experimental_compact_context",
                        "composer:experimental_feature:persistent_history",
                        "composer:experimental_feature:associative_memory",
                        "composer:experimental_feature:gigatoken",
                        "composer:experimental_feature:expert_prefetch",
                        "composer:experimental_feature:completion_mode",
                        "composer:experimental_feature:no_fail"
                )),
                "Experimental popup did not contain every wired model/Automation experiment");
        require("TEST | D-T | Plan".equals(AutomationModeStatusChatPanel.modeIndicatorLabels(
                        AutomationModeController.snapshot())),
                "Automation mode indicators were not ordered TEST, D-T, Plan");
        AutomationModeController.setVerificationEnabled(false);
        require(!AutomationModeController.isVerificationEnabled()
                        && AutomationModeStatusChatPanel.modeIndicatorLabels(AutomationModeController.snapshot()).startsWith("TEST"),
                "Compact Context Agent did not retain the shared TEST cue");
        AutomationModeController.setExperimentalCompactAgentEnabled(false);
        AutomationModeController.setVerificationEnabled(true);
        require("TEST | D-T | Plan".equals(AutomationModeStatusChatPanel.modeIndicatorLabels(
                        AutomationModeController.snapshot()))
                        && AutomationModeController.snapshot().enabledExperimentalFeatures().equals(List.of("Verification")),
                "Verification alone did not use TEST with inspectable feature metadata");
        require(AutomationModeStatusChatPanel.experimentalIndicatorColor(0) == 0x55FF55,
                "TEST indicator did not use the configurable green color");
        AutomationModeController.setUnrestrictedModeEnabled(false);
        require(!AutomationModeController.isUnrestrictedMode(), "Unrestricted did not toggle back to standard approvals");
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

    private static void proveNoFailVerificationComposition() {
        require(!NoFailExecutionPolicy.evaluate(true, true, true, 0, false).allowFinalization(),
                "No-Fail allowed finalization without a successful tool output");
        require(!NoFailExecutionPolicy.evaluate(true, true, true, 1, false).allowFinalization(),
                "No-Fail allowed finalization with an incomplete known objective");
        require(NoFailExecutionPolicy.evaluate(true, true, true, 1, true).allowFinalization(),
                "No-Fail rejected completed objective evidence");

        ModelToolResult unverified = new ModelToolResult(
                "no-fail-unverified", "movement.walk_relative", "completed", new JsonObject(), "", "moved",
                1L, 2L, "not_required", List.of(), false, false, "approved"
        );
        ModelToolResult verified = new ModelToolResult(
                "no-fail-verified", "movement.walk_relative", "completed", new JsonObject(), "", "moved",
                1L, 2L, "passed", List.of(), false, false, "approved"
        );
        require(NoFailExecutionPolicy.accepts(unverified, false),
                "No-Fail rejected an intrinsically validated result while Verification was off");
        require(!NoFailExecutionPolicy.accepts(unverified, true),
                "No-Fail bypassed the enabled Verification result");
        require(NoFailExecutionPolicy.accepts(verified, true),
                "No-Fail did not compose with passed Verification evidence");
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
        require(snapshot.activity().startsWith("§fproof§r")
                        && snapshot.activity().contains("Thought§r | Inspect the target safely."),
                "objective-rooted thought tree was not rendered");
        require(snapshot.activity().contains("Plan§r | proof")
                        && !snapshot.activity().contains("Step 1/1")
                        && snapshot.activity().contains("Jump once")
                        && snapshot.activity().contains("Action§r | Player Jump")
                        && snapshot.activity().contains("Status§r | ✓ completed")
                        && snapshot.activity().contains("Process§r | completed"),
                "plan steps did not render the connected action/status/process hierarchy");
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
        require(ModelPresenceState.color(completed) == 0xFF67C879,
                "completed presence was not green");

        ModelPresenceLineGeometry.Bounds line =
                ModelPresenceLineGeometry.beneathName(7, 11, 42, 9);
        require(line.left() == 6 && line.right() == 50,
                "Tab/world status line did not extend one pixel beyond both name edges");
        require(line.bottom() - line.top() == 1,
                "Tab/world status line was not one pixel high");
        int daylightLevel = ModelPresenceWorldLineStyle.effectiveLightLevel(0, 15, 6_000L);
        int midnightLevel = ModelPresenceWorldLineStyle.effectiveLightLevel(0, 15, 18_000L);
        int torchAtMidnightLevel = ModelPresenceWorldLineStyle.effectiveLightLevel(12, 15, 18_000L);
        ModelPresenceWorldLineStyle.Colors daylight =
                ModelPresenceWorldLineStyle.colors(0xFF4FA6D8, daylightLevel);
        ModelPresenceWorldLineStyle.Colors night =
                ModelPresenceWorldLineStyle.colors(0xFF4FA6D8, midnightLevel);
        ModelPresenceWorldLineStyle.Colors torchAtNight =
                ModelPresenceWorldLineStyle.colors(0xFF4FA6D8, torchAtMidnightLevel);
        require(daylightLevel == 15 && midnightLevel == 4 && torchAtMidnightLevel == 12,
                "world underline did not combine block, sky, and solar light deterministically");
        require(daylight.visibleArgb() == 0xFF4FA6D8,
                "daylight world underline changed its semantic color");
        require((daylight.occludedArgb() & 0x00FFFFFF) < (daylight.visibleArgb() & 0x00FFFFFF)
                        && (night.visibleArgb() & 0x00FFFFFF) < (daylight.visibleArgb() & 0x00FFFFFF)
                        && (night.occludedArgb() & 0x00FFFFFF) < (night.visibleArgb() & 0x00FFFFFF)
                        && (torchAtNight.visibleArgb() & 0x00FFFFFF) > (night.visibleArgb() & 0x00FFFFFF),
                "world underline did not dim for occlusion and night lighting");
    }

    private static void proveCombinedModelExecutorPresence() {
        ModelPresenceState.updateAutomation(true, "idle");
        AutomationRuntimeStatus.idle("");
        ModelPresenceState.updateRequest(ModelPresenceState.ActivityKind.AUTOMATION, "thinking", true);
        CombinedModelExecutorStatus.Snapshot thinking = CombinedModelExecutorStatus.snapshot();
        require("thinking".equals(thinking.state())
                        && thinking.source() == CombinedModelExecutorStatus.Source.MODEL,
                "combined status did not expose active model thinking");
        require(AutomationChatHudState.executorStatusLine().getString().isBlank(),
                "Executor popup leaked model-only status while its runtime was inactive");

        AutomationRuntimeStatus.active("preparing", "waiting for action");
        CombinedModelExecutorStatus.Snapshot preparing = CombinedModelExecutorStatus.snapshot();
        require("thinking".equals(preparing.state())
                        && preparing.source() == CombinedModelExecutorStatus.Source.MODEL,
                "background Executor preparation hid active model thinking");

        AutomationRuntimeStatus.active("moving", "moving to target");
        CombinedModelExecutorStatus.Snapshot navigating = CombinedModelExecutorStatus.snapshot();
        require("navigating".equals(navigating.state())
                        && navigating.source() == CombinedModelExecutorStatus.Source.EXECUTOR,
                "combined status did not prioritize concrete Executor activity");
        require(ModelPresenceState.color(ModelPresenceState.localSnapshot()) == 0xFF4FA6D8,
                "local Tab/world underline did not consume the combined navigating color");
        require(AutomationChatHudState.executorStatusLine().getString().contains("Navigating"),
                "Executor popup did not sample the current runtime status immediately");
        require(AutomationChatHudState.executorStatusLine().getString().startsWith("@_: ")
                        && count(AutomationChatHudState.executorStatusLine().getString(), "@_:") == 1
                        && !AutomationChatHudState.executorStatusLine().getString().contains("|---")
                        && AutomationCliViewModel.promptLine("objective").getString()
                        .startsWith(ModelActivityTreeGlyphs.BRANCH + " "),
                "Executor popup identity/tree prefixes did not follow the shared activity grammar");

        AutomationRuntimeStatus.active("interacting", "opening container");
        require(AutomationChatHudState.executorStatusLine().getString().contains("Interacting"),
                "Executor popup status remained behind the latest runtime transition");

        AutomationRuntimeStatus.blocked("path blocked");
        require(AutomationChatHudState.executorStatusLine().getString().isBlank(),
                "Executor popup retained status after its runtime stopped");
        ModelPresenceState.updateRequest(ModelPresenceState.ActivityKind.AUTOMATION, "replanning", true);
        CombinedModelExecutorStatus.Snapshot replanning = CombinedModelExecutorStatus.snapshot();
        require("replanning".equals(replanning.state())
                        && replanning.source() == CombinedModelExecutorStatus.Source.MODEL,
                "active model replanning did not replace a terminal Executor result");
        ModelPresenceState.updateRequest(ModelPresenceState.ActivityKind.NONE, "idle", false);
        ModelPresenceState.updateAutomation(false, "idle");
        AutomationRuntimeStatus.idle("");
    }

    private static int count(String value, String needle) {
        int matches = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(needle, cursor)) >= 0) {
            matches++;
            cursor += needle.length();
        }
        return matches;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
