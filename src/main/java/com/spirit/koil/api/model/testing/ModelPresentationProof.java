package com.spirit.koil.api.model.testing;

import com.spirit.koil.api.chat.ChatHudScrollbar;
import com.spirit.koil.api.model.KoilLifetimeCounters;
import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelAgentCapabilityProfile;
import com.spirit.koil.api.model.ModelSemanticPalette;
import com.spirit.koil.api.model.chat.ModelGenerationChatPanel;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelPopupScrollbar;
import com.spirit.koil.api.model.chat.ModelRequestStatusPresentation;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.format.RichChatModelFinalFormatValidator;
import com.spirit.koil.api.model.planning.ConversationalReasoningPolicy;
import com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry;
import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.automation.cli.AutomationStateColors;
import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.model.presence.ModelPresenceState;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ModelUsage;
import com.google.gson.JsonObject;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.List;

/** Cross-surface contract proofs that do not require a running world. */
public final class ModelPresentationProof {
    private ModelPresentationProof() {
    }

    public static void main(String[] args) {
        provesSemanticPalette();
        provesStatusGeometry();
        provesScrollbarGeometry();
        provesLifetimeCounters();
        provesExecutorToolStatusStyling();
        provesCompleteStructuredThoughtEvidence();
        provesGroundedAskBoundary();
        provesFinalFormatting();
        System.out.println("Model presentation proof passed");
    }

    private static void provesSemanticPalette() {
        for (ModelActivityState state : ModelActivityState.values()) {
            require((ModelSemanticPalette.color(state) & 0x00FFFFFF) != 0, "missing semantic color: " + state);
        }
        Map<ModelActivityState, Integer> expected = Map.ofEntries(
                Map.entry(ModelActivityState.THINKING, 0xFFD75A),
                Map.entry(ModelActivityState.INSPECTING, 0x52C7D6),
                Map.entry(ModelActivityState.SEARCHING, 0x5F9EFF),
                Map.entry(ModelActivityState.PLANNING, 0x6574D9),
                Map.entry(ModelActivityState.AWAITING_APPROVAL, 0xF2B84B),
                Map.entry(ModelActivityState.EXECUTING, 0xF08A45),
                Map.entry(ModelActivityState.NAVIGATING, 0x4FA6D8),
                Map.entry(ModelActivityState.EATING, 0x9FCB5C),
                Map.entry(ModelActivityState.REPLANNING, 0xA879E0),
                Map.entry(ModelActivityState.WRITING, 0x87AEEA),
                Map.entry(ModelActivityState.VALIDATING, 0x63C17A),
                Map.entry(ModelActivityState.COMPLETE, 0x67C879),
                Map.entry(ModelActivityState.FAILED, 0xE0525C)
        );
        expected.forEach((state, color) -> require(
                (ModelSemanticPalette.color(state) & 0x00FFFFFF) == color,
                state + " palette mismatch"));
        require(ModelActivityState.fromLegacy("waiting") == ModelActivityState.OBSERVING,
                "generic waiting was misclassified as approval");
        require(ModelActivityState.fromLegacy("waiting for approval") == ModelActivityState.AWAITING_APPROVAL,
                "explicit approval wait lost its approval meaning");
        require(ModelActivityState.fromLegacy("already_satisfied") == ModelActivityState.ALREADY_SATISFIED,
                "already-satisfied result was collapsed into generic completion");
        require(ModelActivityState.fromLegacy("examining inventory") == ModelActivityState.INSPECTING,
                "examining state did not use the inspecting semantic color");
        require(ModelActivityState.fromLegacy("moving") == ModelActivityState.NAVIGATING
                        && (AutomationStateColors.color("moving") & 0x00FFFFFF) == 0x4FA6D8,
                "Moving did not normalize to blue Navigating");
        require(ModelRequestStatusPresentation.forState(ModelRequestState.WAITING_FOR_DATA).activityState()
                        == ModelActivityState.OBSERVING,
                "ordinary data waiting was misclassified as approval");
    }

    private static void provesStatusGeometry() {
        require(ModelGenerationChatPanel.statusHighlightPixelOffset("Starting", true) == -1,
                "empty Starting status did not shift left");
        require(ModelGenerationChatPanel.statusHighlightPixelOffset("Thinking", true) == -1,
                "empty Thinking status did not shift left");
        require(ModelGenerationChatPanel.statusHighlightPixelOffset("Thinking", false) == 0,
                "hierarchy Thinking geometry changed");
        require(ModelGenerationChatPanel.statusHighlightPixelOffset("Inspecting", true) == 0,
                "unrelated status geometry changed");
    }

    private static void provesScrollbarGeometry() {
        ModelPopupScrollbar.Metrics top = ModelPopupScrollbar.topDownRange(10, 20, 100, 20, 10, 1.0D / 3.0D);
        require(top != null && top.maxScroll() == 20, "model-popup scrollbar range mismatch");
        require(ModelPopupScrollbar.offsetFromThumbTop(top.y(), top) == 0, "model popup top drag did not select first row");
        require(ModelPopupScrollbar.offsetFromThumbTop(top.y() + top.height(), top) == 20,
                "bottom drag did not select final row");
        ChatHudScrollbar.Metrics bottom = ChatHudScrollbar.bottomUp(10, 20, 100, 30, 10, 0);
        require(bottom != null && bottom.thumbY() > top.y(), "chat scrollbar did not follow newest row");
        require(ModelPopupScrollbar.topDownRange(0, 0, 40, 0, 0, 1.0D) == null,
                "model popup scrollbar rendered for a fully visible request");
        require(!ChatHudScrollbar.class.isAssignableFrom(ModelPopupScrollbar.class),
                "chat and model popup scrollbars are not separate implementations");
    }

    private static void provesLifetimeCounters() {
        KoilLifetimeCounters.resetForProof();
        KoilLifetimeCounters.Snapshot first = KoilLifetimeCounters.modelRequestStarted();
        KoilLifetimeCounters.Snapshot second = KoilLifetimeCounters.automationSessionStarted();
        KoilLifetimeCounters.Snapshot third = KoilLifetimeCounters.automationSessionStarted();
        require(first.kms() == 1 && first.kes() == 0 && first.kts() == 1, "first kms snapshot mismatch");
        require(second.kms() == 1 && second.kes() == 1 && second.kts() == 2, "first kes snapshot mismatch");
        require(third.kms() == 1 && third.kes() == 2 && third.kts() == 3, "startup total mismatch");
        require(first.kts() == 1, "old snapshot was not immutable");
    }

    private static void provesExecutorToolStatusStyling() {
        ModelPresenceState.updateAutomation(true, "idle");
        ModelPresenceState.updateRequest(ModelPresenceState.ActivityKind.NONE, "idle", false);
        AutomationRuntimeStatus.active("moving", "moving toward target");
        Text executorStatus = AutomationChatHudState.executorStatusLine();
        require(executorStatus.getString().startsWith("@_: Navigating...")
                        && !executorStatus.getString().contains("Model")
                        && !executorStatus.getString().contains("Executor")
                        && executorStatus.getString().indexOf("@_:") == executorStatus.getString().lastIndexOf("@_:")
                        && !executorStatus.getString().contains("|---"),
                "Executor popup did not render one animated normalized status");
        AutomationChatHudState.toolFinished(
                new ModelToolCall("proof", "movement.move_to", new JsonObject()),
                new ModelToolResult("proof", "movement.move_to", "failed", new JsonObject(), "path_blocked", "blocked")
        );
        Text tool = AutomationChatHudState.tool();
        String json = Text.Serializer.toJson(tool);
        require(tool.getString().contains(" | FAILED"), "Executor tool result omitted its status");
        require(tool.getString().startsWith("├─ ") && !tool.getString().contains("@_:")
                        && !tool.getString().contains("|---"),
                "Executor tool activity did not use the shared thinking-tree branch");
        require(json.contains("\"text\":\" | \"") && json.contains("\"color\":\"dark_gray\""),
                "Executor tool separator inherited the failure color");
        AutomationRuntimeStatus.idle("");
        JsonObject searchArguments = new JsonObject();
        searchArguments.addProperty("query", "container transfer");
        AutomationChatHudState.toolStarted(new ModelToolCall(
                "workspace-proof", "workspace.search", searchArguments));
        require(AutomationChatHudState.executorStatusLine().getString().contains("Searching")
                        && AutomationChatHudState.executorStatusLine().getString().contains("Workspace Search"),
                "Executor popup did not expose the live semantic status of a non-KTL tool");
        AutomationChatHudState.toolFinished(
                new ModelToolCall("workspace-proof", "workspace.search", searchArguments),
                new ModelToolResult("workspace-proof", "workspace.search", "completed", new JsonObject(), "", "found 2 matches")
        );
        require(AutomationChatHudState.executorStatusLine().getString().isBlank(),
                "Executor popup retained a live status after the non-KTL tool finished");
        ModelPresenceState.updateAutomation(false, "idle");
        AutomationRuntimeStatus.idle("");
        require(AutomationChatHudState.executorStatusLine().getString().isBlank(),
                "Executor status remained visible after execution stopped");
    }

    private static void provesGroundedAskBoundary() {
        ModelAgentCapabilityProfile profile = new ModelAgentCapabilityProfile(
                "proof-model",
                "proof-provider",
                ModelAgentCapabilityProfile.ToolReliability.RELIABLE,
                true,
                false,
                4,
                ModelAgentCapabilityProfile.PlanningReliability.RELIABLE,
                8_192,
                true,
                true,
                false,
                4,
                "proof",
                true
        );
        var hello = ConversationalReasoningPolicy.evaluate("Hello", 0, profile, false);
        require(hello.depth() == ConversationalReasoningPolicy.Depth.DIRECT && !hello.groundedMinecraft(),
                "greeting did not stay direct");
        var grounded = ConversationalReasoningPolicy.evaluate(
                "What is the exact Minecraft command syntax for a modded entity id?", 0, profile, false);
        require(grounded.groundedMinecraft(), "complicated Minecraft question was not grounded");
        var tools = MinecraftKnowledgeModelToolRegistry.toolsForQuestion(
                "What is the exact Minecraft command syntax for a modded entity id?");
        require(!tools.isEmpty() && tools.size() <= 4, "grounded tool group is not bounded");
        tools.forEach(tool -> {
            require(tool.sideEffects().isEmpty(), "grounded /ask received side effects: " + tool.id());
            require(!tool.confirmationRequired(), "read-only grounded tool unexpectedly requires approval");
        });
    }

    private static void provesCompleteStructuredThoughtEvidence() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("target", "12 64 -8");
        JsonObject started = new JsonObject();
        started.addProperty("toolId", "movement.move_to");
        started.add("arguments", arguments);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("distance_traveled", 12.5D);
        metrics.addProperty("distance_remaining", 7.5D);
        JsonObject before = new JsonObject();
        before.addProperty("position", "0 64 0");
        JsonObject after = new JsonObject();
        after.addProperty("position", "12 64 -8");
        JsonObject structured = new JsonObject();
        structured.add("before", before);
        structured.add("after", after);
        structured.add("metrics", metrics);
        structured.addProperty("objective_reached", false);
        JsonObject finished = new JsonObject();
        finished.addProperty("toolId", "movement.move_to");
        finished.addProperty("status", "partial");
        finished.addProperty("detail", "movement progressed");
        finished.add("structuredResult", structured);
        finished.addProperty("content", "bounded provider payload that is summarized rather than dumped");
        String timeline = ModelActivityPresentation.timeline(
                "Reach the destination",
                List.of(
                        new ModelGenerationHudState.ActivityEvent(
                                ModelGenerationHudState.ActivityEventType.TOOL_START,
                                ModelActivityState.NAVIGATING,
                                "Move To",
                                1_100L,
                                "tool-1",
                                started
                        ),
                        new ModelGenerationHudState.ActivityEvent(
                                ModelGenerationHudState.ActivityEventType.RESULT,
                                ModelActivityState.OBSERVING,
                                "movement.move_to — partial",
                                1_600L,
                                "result-1",
                                finished
                        )
                ),
                1_000L
        );
        require(timeline.contains("Structured Result / Metrics / Distance traveled")
                        && timeline.contains("12.5")
                        && timeline.contains("Structured Result / Before / Position")
                        && timeline.contains("Objective reached")
                        && timeline.contains("Content") && timeline.contains("characters")
                        && timeline.contains("Event") && timeline.contains("result-1")
                        && timeline.contains("Time") && timeline.contains("+600ms"),
                "thought tree did not retain bounded structured tool evidence and timing");
        String requestMetrics = ModelActivityPresentation.requestMetrics(
                new ModelUsage(120, 30, 80, 40L, 250L, 15.25D),
                1_000L,
                3_000L
        );
        require(requestMetrics.contains("Prompt tokens") && requestMetrics.contains("120")
                        && requestMetrics.contains("Average speed") && requestMetrics.contains("15.25")
                        && requestMetrics.contains("Elapsed") && requestMetrics.contains("2.000s"),
                "thought tree request metrics did not explain the displayed numbers");
    }

    private static void provesFinalFormatting() {
        var recovered = RichChatModelFinalFormatValidator.validateAndRepair("# Result\n```latex\nx^2 + y^2 = z^2\n```");
        require(recovered.valid() && !recovered.text().startsWith("#") && recovered.text().contains("$$"),
                "heading/formula repair failed");
        var source = RichChatModelFinalFormatValidator.validateAndRepair("```java\n# literal source\nint x = y + 1;\n```");
        require(source.valid() && source.text().contains("# literal source"), "literal fenced source was altered");
        var document = RichChatModelFinalFormatValidator.validateAndRepair(
                "```latex\n\\documentclass{article}\n\\begin{document}\nx\n\\end{document}\n```");
        require(!document.valid(), "LaTeX document output was accepted");
        var command = RichChatModelFinalFormatValidator.validateAndRepair("Use `/give @s minecraft:stone`. ");
        require(command.valid() && !command.text().contains("`/give"), "inline command was left in code delimiters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
