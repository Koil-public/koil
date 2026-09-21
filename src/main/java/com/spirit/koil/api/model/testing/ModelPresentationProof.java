package com.spirit.koil.api.model.testing;

import com.spirit.koil.api.chat.ChatHudScrollbar;
import com.spirit.koil.api.chat.RichChatPreviewFormatter;
import com.spirit.koil.api.chat.RichChatRowType;
import com.spirit.koil.api.chat.RichChatSurfaceRenderer;
import com.spirit.koil.api.model.KoilLifetimeCounters;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.ModelDebugMode;
import com.spirit.koil.api.model.LocalModelSystemPrompt;
import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelAgentCapabilityProfile;
import com.spirit.koil.api.model.ModelSemanticPalette;
import com.spirit.koil.api.model.chat.ModelGenerationChatPanel;
import com.spirit.koil.api.model.chat.ModelChatIdentity;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;
import com.spirit.koil.api.chat.RichChatStructuralContinuation;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelPopupScrollbar;
import com.spirit.koil.api.model.chat.ModelRequestStatusPresentation;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.format.RichChatModelFinalFormatValidator;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.planning.ConversationalReasoningPolicy;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry;
import com.spirit.koil.api.model.tool.DatasetIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.BrowserIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.ContentIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.InternetResearchModelToolRegistry;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.tool.McpCatalogueModelToolRegistry;
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
import java.util.UUID;

/** Cross-surface contract proofs that do not require a running world. */
public final class ModelPresentationProof {
    private ModelPresentationProof() {
    }

    public static void main(String[] args) {
        provesSemanticPalette();
        provesStatusGeometry();
        provesStatusHistoryDoesNotReplaceLiveState();
        provesStrictIngestionStatus();
        provesPopupReasoningContinuation();
        provesPopupTreeWrapContinuity();
        provesSharedRichChatSurfaceContract();
        provesHookAndTailEvidence();
        provesRequiredToolSurfaces();
        provesAdvancedToolRouting();
        provesScrollbarGeometry();
        provesLifetimeCounters();
        provesExecutorToolStatusStyling();
        provesCompleteStructuredThoughtEvidence();
        provesGroundedAskBoundary();
        provesFinalFormatting();
        provesThinkingTagContinuation();
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

    private static void provesStatusHistoryDoesNotReplaceLiveState() {
        UUID requestId = UUID.randomUUID();
        ModelRequestStatusPresentation.View preparing = ModelRequestStatusPresentation.forRequest(
                requestId, ModelRequestState.PREPARING_CONTEXT, "model context", "", "check project");
        require(!preparing.label().isBlank() && !preparing.completedLabel().isBlank(),
                "initial live status did not resolve an active/completed verb pair");
        ModelRequestStatusPresentation.View thinking = ModelRequestStatusPresentation.forRequest(
                requestId, ModelRequestState.THINKING, "thinking", "", "check project");
        require("Thinking".equals(thinking.label()),
                "completed transition replaced the current Thinking status");
        ModelRequestStatusPresentation.HistoryView history =
                ModelRequestStatusPresentation.recentCompleted(requestId);
        require(!history.visible(),
                "previous activity was retained instead of being replaced immediately");
    }

    private static void provesStrictIngestionStatus() {
        ModelRequestStatusPresentation.View ingesting = ModelRequestStatusPresentation.forActivity(
                ModelRequestState.PREFILLING, "processing prompt", "", "Never echo this exact user prompt");
        require("Ingesting".equals(ingesting.label()),
                "prompt processing was not presented as Ingesting");
        require(ingesting.activityState() != ModelActivityState.SEARCHING,
                "prompt ingestion was incorrectly classified as Searching");
        require(!ingesting.detail().contains("Never echo this exact user prompt"),
                "status detail echoed user prompt content");
        require("user prompt".equals(ingesting.detail()),
                "prompt ingestion did not use the literal user-prompt subject");

        ModelRequestStatusPresentation.View continuation = ModelRequestStatusPresentation.forActivity(
                ModelRequestState.PREFILLING, "model reasoning continuation", "", "Never echo this exact user prompt");
        require("Continuing".equals(continuation.label()),
                "provider continuation was incorrectly presented as a second user-prompt ingestion");
        require("model reasoning".equals(continuation.detail()),
                "provider continuation did not identify the model-authored state being resumed");

        ModelRequestStatusPresentation.View thinking = ModelRequestStatusPresentation.forActivity(
                ModelRequestState.THINKING, "thinking", "", "Never echo this exact user prompt");
        require(!thinking.detail().contains("Never echo this exact user prompt"),
                "thinking status echoed user prompt content");
    }

    private static void provesPopupReasoningContinuation() {
        JsonObject data = new JsonObject();
        data.addProperty("exposedTag", "REASONING");
        ModelGenerationHudState.ActivityEvent thought = new ModelGenerationHudState.ActivityEvent(
                ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY,
                ModelActivityState.THINKING, "First paragraph.\n\nSecond paragraph.",
                System.currentTimeMillis(), "thought-proof", data);
        String rendered = ModelActivityPresentation.timelineEvent(thought, true);
        require(rendered.contains("First paragraph."), "first reasoning paragraph disappeared");
        require(rendered.contains("Second paragraph."), "reasoning paragraph after blank line disappeared");
        require(rendered.indexOf(RichChatStructuralContinuation.SUBTEXT) < 0,
                "reasoning still emitted the private U+E380 subtext control");
        require(rendered.startsWith("-# ") && rendered.contains("\n-# "),
                "reasoning did not use parser-safe structural subtext rows");
        String legacyUnsafe = String.valueOf(RichChatStructuralContinuation.SUBTEXT)
                + "legacy" + '\uE350' + "bridge" + '\uFFFD';
        String safe = RichChatModelOutputSanitizer.sanitizeRendererControls(legacyUnsafe);
        require(safe.indexOf(RichChatStructuralContinuation.SUBTEXT) < 0
                        && safe.indexOf('\uE350') < 0
                        && safe.indexOf('\uFFFD') < 0
                        && safe.startsWith("-# legacy"),
                "renderer-control sanitizer allowed private/replacement glyphs into visible model text");
        require((ModelGenerationChatPanel.statusSeparatorColor() & 0x00FFFFFF) == 0x555555,
                "bottom-popup status separator is not neutral dark gray");
    }

    private static void provesPopupTreeWrapContinuity() {
        String root = ModelGenerationChatPanel.treeContinuationGlyphs(
                "-# §8├─§r §bReasoning§r | a deliberately long reasoning line"
        );
        require("│  ".equals(root), "root activity wrap did not preserve its tree rail");

        String nested = ModelGenerationChatPanel.treeContinuationGlyphs(
                "-# §8│  ├─§r §bRequest§r | a deliberately long tool request"
        );
        require(nested.startsWith("│  │"), "nested activity wrap cut the parent/child tree rails");

        String plain = ModelGenerationChatPanel.treeContinuationGlyphs("ordinary model response text");
        require(plain.isEmpty(), "ordinary response text incorrectly received a tree continuation gutter");

        int structuralWidth = ModelGenerationChatPanel.nativeWrapWidth(280, 8, 18, true, null, null);
        require(structuralWidth == 254,
                "structural tree row did not wrap against the real popup width minus gutter/cursor reserves");
        require(structuralWidth < 280,
                "structural tree row regained hidden-markup compensation and can overflow as one long line");

        ModelGenerationChatPanel.StructuralWrapLine privateMarker =
                ModelGenerationChatPanel.structuralWrapLine(
                        String.valueOf(RichChatStructuralContinuation.SUBTEXT) + "§8├─§r §7Reasoning§r | wrapped body"
                );
        require(privateMarker.subtext(), "private subtext row lost its structural semantics before wrapping");
        require(privateMarker.wrapSource().indexOf(RichChatStructuralContinuation.SUBTEXT) < 0,
                "private SUBTEXT control leaked into vanilla wrap measurement");
        require(!privateMarker.wrapSource().contains("-# "),
                "user-facing subtext syntax leaked into vanilla wrap measurement");

        ModelGenerationChatPanel.StructuralWrapLine authoredMarker =
                ModelGenerationChatPanel.structuralWrapLine("-# §8├─§r §7Tool§r | wrapped body");
        require(authoredMarker.subtext(), "-# activity row was not recognized as structural subtext");
        require(!authoredMarker.wrapSource().contains("-# "),
                "-# marker was still present while calculating wrapped visual rows");
    }

    private static void provesSharedRichChatSurfaceContract() {
        Text source = Text.literal(ModelChatIdentity.decorate(
                "## Heading\n**bold** and *italic* with `inline code` and [label](https://example.com)"
        ));
        Text preview = RichChatPreviewFormatter.format(source, RichChatRowType.MODEL_RESPONSE, 220);
        Text detached = RichChatSurfaceRenderer.format(source, RichChatRowType.MODEL_RESPONSE, 220);
        require(preview != null && detached != null,
                "shared Rich Chat surface formatter returned null for model output");
        require(preview.equals(detached),
                "RichChatPreviewFormatter diverged from the detached Rich Chat surface pipeline");
        require(detached.getString().contains(ModelChatIdentity.PREFIX),
                "shared Rich Chat pipeline lost the model response identity before wrapping/rendering");
    }

    private static void provesHookAndTailEvidence() {
        JsonObject data = new JsonObject();
        data.addProperty("hook", "MCP scraping / ready");
        com.google.gson.JsonArray tail = new com.google.gson.JsonArray();
        tail.add("provider started");
        tail.add("request completed");
        data.add("stderrTail", tail);
        ModelGenerationHudState.ActivityEvent result = new ModelGenerationHudState.ActivityEvent(
                ModelGenerationHudState.ActivityEventType.RESULT,
                ModelActivityState.OBSERVING, "internet.scrape — completed",
                System.currentTimeMillis(), "hook-tail-proof", data);
        String rendered = ModelActivityPresentation.timeline("proof", java.util.List.of(result));
        require(rendered.contains("Result"), "compact tool result disappeared from the normal activity trace");
        if (ModelDebugMode.enabled()) {
            require(rendered.contains("Hook") && rendered.contains("MCP scraping / ready"),
                    "debug mode did not surface runtime hook evidence");
            require(rendered.contains("Tail") && rendered.contains("provider started")
                            && rendered.contains("request completed"),
                    "debug mode did not surface runtime stderr-tail evidence");
        } else {
            require(!rendered.contains("MCP scraping / ready") && !rendered.contains("provider started"),
                    "verbose tool evidence leaked into normal model-chat output");
        }
    }


    private static void provesRequiredToolSurfaces() {
        require(InternetResearchModelToolRegistry.supports("internet.search")
                        && InternetResearchModelToolRegistry.supports("internet.fetch")
                        && InternetResearchModelToolRegistry.supports("internet.scrape")
                        && InternetResearchModelToolRegistry.supports("internet.crawl")
                        && InternetResearchModelToolRegistry.supports("internet.screenshot"),
                "internet tool family is incomplete");
        for (String id : List.of("dataset.search", "dataset.inspect", "dataset.query", "dataset.filter",
                "dataset.rows", "dataset.stats", "dataset.acquire", "dataset.release", "dataset.research"))
            require(DatasetIntelligenceModelToolRegistry.supports(id), "missing Dataset Intelligence tool: " + id);
        for (String id : List.of("browser.health", "browser.navigate", "browser.snapshot", "browser.capture",
                "browser.text", "browser.find", "browser.interact", "browser.wait", "browser.screenshot",
                "browser.pdf", "browser.tabs", "browser.network", "browser.dialog", "browser.cookies",
                "browser.audit", "browser.compare"))
            require(BrowserIntelligenceModelToolRegistry.supports(id), "missing Browser Intelligence tool: " + id);
        for (String id : List.of("content.inspect", "content.normalize", "content.extract",
                "content.sections", "content.search", "content.release"))
            require(ContentIntelligenceModelToolRegistry.supports(id), "missing Content Intelligence tool: " + id);
    }

    private static void provesAdvancedToolRouting() {
        require(LocalModelToolCatalog.toolsForPrompt("search datasets for minecraft telemetry").stream()
                        .anyMatch(tool -> DatasetIntelligenceModelToolRegistry.SEARCH.equals(tool.id())),
                "natural-language dataset routing did not expose dataset.search");
        require(LocalModelToolCatalog.toolsForPrompt("browser screenshot this page").stream()
                        .anyMatch(tool -> BrowserIntelligenceModelToolRegistry.SCREENSHOT.equals(tool.id())),
                "natural-language browser routing did not expose browser.screenshot");
        require(LocalModelToolCatalog.toolsForPrompt("inspect document content sections").stream()
                        .anyMatch(tool -> ContentIntelligenceModelToolRegistry.SECTIONS.equals(tool.id())),
                "natural-language content routing did not expose content.sections");
        require(LocalModelToolCatalog.toolsForPrompt("search the MCP catalogue for a server capability").stream()
                        .anyMatch(tool -> McpCatalogueModelToolRegistry.TOOL_ID.equals(tool.id())),
                "natural-language MCP catalogue routing did not expose mcp-catalogue");
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
        require(hello.maximumProviderRounds() >= 3,
                "direct chat could not recover from a thinking-only response");
        var story = ConversationalReasoningPolicy.evaluate(
                "Write a 1000 word story about a redstone engineer.", 0, profile, false);
        require(story.depth() == ConversationalReasoningPolicy.Depth.EXTENDED
                        && story.maximumOutputTokens() >= 1_400,
                "an explicit long story request was incorrectly capped as direct chat");
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
        ModelToolCall firstLookup = new ModelToolCall("first", "internet.search", new JsonObject());
        ModelToolCall laterLookup = new ModelToolCall("later", "internet.search", new JsonObject());
        require(LocalModelService.selectGroundedAskToolCall(List.of(firstLookup, laterLookup)) == firstLookup,
                "grounded /ask did not stage the first read-only lookup from a batched response");
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
        boolean debug = ModelDebugMode.enabled();
        require(timeline.contains("Structured Result / Metrics / Distance traveled")
                        && timeline.contains("12.5")
                        && timeline.contains("Structured Result / Before / Position")
                        && timeline.contains("Objective reached")
                        && timeline.contains("Content") && timeline.contains("characters")
                        && (debug
                        ? timeline.contains("Event") && timeline.contains("result-1")
                                && timeline.contains("Time") && timeline.contains("+600ms")
                        : !timeline.contains("Event") && !timeline.contains("result-1")
                                && !timeline.contains("Time") && !timeline.contains("+600ms")),
                "thought tree did not apply the debug boundary to event metadata");
        String requestMetrics = ModelActivityPresentation.requestMetrics(
                new ModelUsage(120, 30, 80, 40L, 250L, 15.25D),
                1_000L,
                3_000L
        );
        require(debug
                        ? requestMetrics.contains("Prompt tokens") && requestMetrics.contains("120")
                                && requestMetrics.contains("Average speed") && requestMetrics.contains("15.25")
                                && requestMetrics.contains("Elapsed") && requestMetrics.contains("2.000s")
                        : requestMetrics.isBlank(),
                "thought tree request metrics did not follow Koil debug visibility");

        JsonObject exposedData = new JsonObject();
        exposedData.addProperty("exposedTag", "REASONING");
        exposedData.addProperty("provider", "llama_cpp");
        exposedData.addProperty("modelId", "proof-model");
        exposedData.addProperty("rollingExposedCharacters", 128);
        exposedData.addProperty("modelFamily", "proof-family");
        exposedData.addProperty("reasoningAdapter", "proof-adapter");
        exposedData.addProperty("architecture", "proof-architecture");
        String exposedTimeline = ModelActivityPresentation.timeline(
                "Question",
                List.of(new ModelGenerationHudState.ActivityEvent(
                        ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY,
                        ModelActivityState.THINKING,
                        "Check the factual premise first.",
                        1_250L,
                        "reasoning-event",
                        exposedData
                )),
                1_000L
        );
        require(exposedTimeline.contains("Reasoning") && exposedTimeline.contains("Check the factual premise first."),
                "model-exposed reasoning text or semantic tag was hidden");
        require(debug
                        ? exposedTimeline.contains("Provider") && exposedTimeline.contains("Rolling Exposed Characters")
                                && exposedTimeline.contains("Model Family") && exposedTimeline.contains("Reasoning Adapter")
                                && exposedTimeline.contains("Architecture") && exposedTimeline.contains("Event")
                        : !exposedTimeline.contains("Provider") && !exposedTimeline.contains("proof-model")
                                && !exposedTimeline.contains("Rolling Exposed Characters")
                                && !exposedTimeline.contains("Model Family") && !exposedTimeline.contains("Reasoning Adapter")
                                && !exposedTimeline.contains("Architecture") && !exposedTimeline.contains("Event"),
                "model-exposed reasoning diagnostics did not follow Koil debug visibility");
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

    private static void provesThinkingTagContinuation() {
        require(!LocalModelSystemPrompt.directConversationPrompt().contains("start your response with \"<think>\""),
                "the direct chat contract asked the model to emit a thought tag");
        require(AutomationThinkingPolicy.evaluate("hello", false).maximumContinuationCorrections() >= 1,
                "a conversational think response had no final-answer continuation allowance");
        require(LocalModelService.requiresFinalAnswerContinuation("<think>work through the request"),
                "leading lowercase think markup did not request a final-answer continuation");
        require(LocalModelService.requiresFinalAnswerContinuation("  <Think>work through the request"),
                "leading mixed-case think markup did not request a final-answer continuation");
        require(LocalModelService.requiresFinalAnswerContinuation(
                        "<function name=\"internet_search\"><param name=\"query\">Qwen</param></function>"),
                "raw function markup was accepted as a user-facing answer");
        require(!LocalModelService.requiresFinalAnswerContinuation("<think>work through it</think>The answer is ready."),
                "closed reasoning plus a visible answer incorrectly requested another reasoning round");
        require(!LocalModelService.requiresFinalAnswerContinuation(
                        "Okay, let me work through this carefully. The answer is Paris, and here is why that is the correct result."),
                "reflective natural-language output was guessed to be hidden reasoning");
        require(!LocalModelService.requiresFinalAnswerContinuation("The answer is ready."),
                "ordinary final text incorrectly requested a continuation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
