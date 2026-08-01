package com.spirit.koil.api.model.testing;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestions;
import com.spirit.koil.api.chat.input.CommandSuggestionFuturePoller;
import com.spirit.koil.api.chat.RichChatTableBridge;
import com.spirit.koil.api.chat.RichChatHeadingLayout;
import com.spirit.koil.api.chat.RichChatMaskedLinkBridge;
import com.spirit.koil.api.code.CodeLanguageDetector;
import com.spirit.koil.api.minecraft.MinecraftRegistrySuggestions;
import com.spirit.koil.api.minecraft.MinecraftNbtSuggestionService;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.chat.ModelChatIdentity;
import com.spirit.koil.api.model.voice.ModelVoiceExpression;
import com.spirit.koil.api.model.voice.ModelVoicePhrasePlanner;
import com.spirit.koil.api.model.voice.ModelVoiceRegistry;
import com.spirit.koil.api.model.voice.ModelVoiceService;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.tool.AutomationPlanModelToolRegistry;
import com.spirit.koil.api.model.tool.AutomationKtlSkillModelToolRegistry;
import com.spirit.koil.api.automation.ktl.AutomationKtlSkillRegistry;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.prompt.LocalModelAutomationPrompt;
import com.spirit.koil.api.model.tool.ModelWorkspaceRegistry;
import com.spirit.koil.api.model.tool.ModelWorkspaceToolRegistry;
import com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelRequestMetricsPresentation;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.LocalModelRuntimeManager;
import com.spirit.koil.api.model.ManagedModelRequest;
import com.spirit.koil.api.model.ModelConversation;
import com.spirit.koil.api.model.ModelContextWindowState;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelFinalizationHandle;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;
import com.spirit.koil.api.model.StreamingModelResponse;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class LocalModelFoundationProof {
    private LocalModelFoundationProof() {
    }

    public static void main(String[] args) throws Exception {
        proveModelPresentationContracts();
        proveVoiceCatalogAndSynthesis();
        proveWorkspaceToolContracts();
        provePromptToolSelection();
        proveRichChatTablesAndSharedRegistrySuggestions();
        proveConversationBounds();
        proveNonBlockingCommandSuggestionPolling();
        proveStreamingQueueAndToolEvents();
        proveCancellation();
        System.out.println("Local model foundation proof passed.");
    }

    private static void proveModelPresentationContracts() {
        require(">_: hello".equals(ModelChatIdentity.decorate("hello")),
                "model chat identity prefix was not applied");
        require(">_: hello".equals(ModelChatIdentity.decorate(">_: hello")),
                "model chat identity prefix was duplicated");
        require(ModelChatIdentity.alignedPrefixAdvance(18, 4) == 20
                        && ModelChatIdentity.alignmentPadding(18, 4) == 2,
                "model continuation alignment did not use a stable whole-space advance");
        require("00:00".equals(ModelRequestMetricsPresentation.formatElapsedMillis(1_000L, 1_999L))
                        && "01:05".equals(ModelRequestMetricsPresentation.formatElapsedMillis(1_000L, 66_000L))
                        && "1:01:01".equals(ModelRequestMetricsPresentation.formatElapsedMillis(1_000L, 3_662_000L)),
                "bottom model elapsed timer formatting was not stable across minute/hour boundaries");
        UUID elapsedRequest = UUID.randomUUID();
        ModelGenerationHudState.begin(elapsedRequest, "elapsed timer proof");
        ModelGenerationHudState.state(elapsedRequest, ModelRequestState.COMPLETED, "done");
        var elapsedSnapshot = ModelGenerationHudState.visibleSnapshot();
        require(elapsedSnapshot != null
                        && elapsedSnapshot.completedAtMillis() > 0L
                        && ModelRequestMetricsPresentation.elapsedLabel(
                                elapsedSnapshot,
                                elapsedSnapshot.completedAtMillis() + 120_000L
                        ).equals(ModelRequestMetricsPresentation.formatElapsedMillis(
                                elapsedSnapshot.createdAtMillis(),
                                elapsedSnapshot.completedAtMillis()
                        )),
                "bottom model elapsed timer did not freeze at the terminal timestamp");
        ModelGenerationHudState.dismiss(elapsedRequest);
        String compactAutomationPrompt = LocalModelAutomationPrompt.rules(false, false);
        require(compactAutomationPrompt.length() < 3_400,
                "Automation prompt contract grew beyond its compact prefill budget");
        require(compactAutomationPrompt.contains("\"completed\"")
                        && compactAutomationPrompt.contains("\"submitted\"")
                        && compactAutomationPrompt.contains("minecraft.knowledge")
                        && compactAutomationPrompt.contains("automation.skill_run")
                        && compactAutomationPrompt.contains("Policy: STANDARD")
                        && compactAutomationPrompt.contains("hidden reasoning"),
                "compact Automation prompt lost a safety or truthfulness boundary");
        require("world".equals(ModelVoiceService.finalPronounceableWord("Hello, **world!**")),
                "model voice did not select the final pronounceable word");
        require("".equals(ModelVoiceService.finalPronounceableWord("https://example.com/file")),
                "model voice attempted to pronounce a URL");
        require("Forest".equals(ModelVoiceService.finalPronounceableWord("\u00a7#04280DForest")),
                "model voice treated compact-hex source as pronounceable text");
        ModelVoicePhrasePlanner formattedSpeechPlanner = new ModelVoicePhrasePlanner();
        var formattedSpeech = formattedSpeechPlanner.accept(
                "\u00a7aDone \u00a7#04280Dforest text now "
        );
        require(formattedSpeech.size() == 1
                        && formattedSpeech.get(0).text().contains("Done forest text now")
                        && formattedSpeech.get(0).text().indexOf('\u00a7') < 0
                        && !formattedSpeech.get(0).text().contains("04280D"),
                "model voice retained a section/hex formatting control");
        ModelVoicePhrasePlanner planner = new ModelVoicePhrasePlanner();
        require(planner.accept("This ").isEmpty(),
                "model voice emitted an incomplete one-word stream fragment");
        var firstPhrases = planner.accept("arrives quickly for users ");
        require(firstPhrases.size() == 1 && firstPhrases.get(0).text().endsWith(","),
                "model voice did not emit its low-latency first phrase");
        require(planner.accept("stream deltas and sounds natural! ").stream()
                        .anyMatch(phrase -> phrase.expression() == ModelVoiceExpression.EXCITED),
                "model voice did not preserve expressive sentence punctuation");
        ModelVoicePhrasePlanner smoothClausePlanner = new ModelVoicePhrasePlanner();
        require(smoothClausePlanner.accept("First phrase now flows smoothly ").size() == 1,
                "model voice did not emit its smooth first phrase");
        require(smoothClausePlanner.accept("one, two, three, ").isEmpty(),
                "model voice emitted choppy tiny punctuation phrases");
        ModelVoicePhrasePlanner punctuationPlanner = new ModelVoicePhrasePlanner();
        var immediateSentence = punctuationPlanner.accept("Hello!");
        require(immediateSentence.isEmpty()
                        && punctuationPlanner.finish().stream().anyMatch(phrase -> "Hello!".equals(phrase.text())),
                "a tiny terminal phrase was lost instead of being held for smooth completion");
        ModelVoicePhrasePlanner splitWordPlanner = new ModelVoicePhrasePlanner();
        require(splitWordPlanner.accept("Hel").isEmpty(),
                "model voice spoke an incomplete streamed word");
        require(splitWordPlanner.accept("lo ").isEmpty(),
                "model voice emitted a one-word latency fragment");
        require(splitWordPlanner.accept("from Koil ").isEmpty(),
                "model voice emitted before a fluid first phrase was available");
        require(splitWordPlanner.flushForLatency().get(0).text().startsWith("Hello from Koil"),
                "model voice failed to reassemble a word split across stream deltas");
        ModelVoiceService.StreamingSpeech supersededSpeech = ModelVoiceService.beginStreaming();
        ModelVoiceService.stopSpeaking("foundation proof prompt");
        require(supersededSpeech.cancelled(),
                "a new prompt did not invalidate speech from the prior response");
        UUID cancelledRequest = UUID.randomUUID();
        ModelGenerationHudState.begin(cancelledRequest, "cancel voice proof");
        ModelGenerationHudState.bindCancellation(cancelledRequest, new ModelCancellationHandle() {
            private boolean cancelled;

            @Override
            public boolean cancel(String reason) {
                this.cancelled = true;
                return true;
            }

            @Override
            public boolean isCancellationRequested() {
                return this.cancelled;
            }

            @Override
            public String cancellationReason() {
                return this.cancelled ? "proof cancellation" : "";
            }
        });
        ModelVoiceService.StreamingSpeech popupSpeech = ModelVoiceService.beginStreaming();
        require(ModelGenerationHudState.cancelVisible(), "model popup cancel did not reach its request handle");
        require(popupSpeech.cancelled(), "model popup cancel did not invalidate queued/current voice");
        var cancelledSnapshot = ModelGenerationHudState.visibleSnapshot();
        require(cancelledSnapshot != null
                        && cancelledSnapshot.state() == ModelRequestState.CANCELLING
                        && cancelledSnapshot.activity().contains("Stopped thinking"),
                "model popup cancel did not publish a stopped-thinking output");
        ModelGenerationHudState.dismiss(cancelledRequest);
        UUID answerNowRequest = UUID.randomUUID();
        ModelGenerationHudState.begin(answerNowRequest, "answer now proof");
        ModelGenerationHudState.bindFinalization(answerNowRequest, new ModelFinalizationHandle() {
            private boolean requested;

            @Override
            public boolean requestAnswerNow() {
                this.requested = true;
                return true;
            }

            @Override
            public boolean isFinalizationRequested() {
                return this.requested;
            }
        });
        ModelGenerationHudState.setAnswerNowVisible(answerNowRequest, true);
        require(ModelGenerationHudState.answerNow(answerNowRequest),
                "Answer Now did not request finalization");
        var answerNowSnapshot = ModelGenerationHudState.visibleSnapshot();
        require(answerNowSnapshot != null
                        && answerNowSnapshot.state() == ModelRequestState.FINALIZING
                        && answerNowSnapshot.activity().contains("Stopped thinking")
                        && answerNowSnapshot.activity().contains("best complete answer"),
                "Answer Now did not publish its non-cancelling stopped-thinking output");
        ModelGenerationHudState.dismiss(answerNowRequest);
        UUID headerRequest = UUID.randomUUID();
        ModelGenerationHudState.begin(headerRequest, "header proof");
        Text bottomHeader = ModelRequestMetricsPresentation.bottomHeader(
                ModelGenerationHudState.visibleSnapshot(),
                "proof-model",
                0,
                32_768
        );
        require(bottomHeader.getString().startsWith("Model | session kts-")
                        && !bottomHeader.getString().contains("Local model"),
                "bottom model header did not combine its renamed title and metrics");
        require(bottomHeader.getStyle().getColor() != null
                        && bottomHeader.getStyle().getColor().getRgb() == 0xAAAAAA,
                "bottom Model title is not light gray");
        String oversizedModelId = "provider/this-model-id-is-deliberately-too-long-for-the-metrics-row";
        Text fittedBottomHeader = ModelRequestMetricsPresentation.bottomHeaderFitted(
                ModelGenerationHudState.visibleSnapshot(),
                oversizedModelId,
                0,
                32_768,
                58,
                text -> text.getString().length()
        );
        require(!fittedBottomHeader.getString().contains(oversizedModelId)
                        && fittedBottomHeader.getString().startsWith("Model | session kts-")
                        && fittedBottomHeader.getString().endsWith("| 0 | 0 | 0 | 0 |  --"),
                "narrow bottom metrics culled session/numeric values before the model id");
        Text fittedTopLine = ModelRequestMetricsPresentation.automationTopLineFitted(
                ModelGenerationHudState.visibleSnapshot(),
                oversizedModelId,
                0,
                32_768,
                Text.literal("D-T | Plan"),
                58,
                text -> text.getString().length()
        );
        require(!fittedTopLine.getString().contains(oversizedModelId)
                        && fittedTopLine.getString().startsWith("session ")
                        && fittedTopLine.getString().contains("D-T | Plan")
                        && fittedTopLine.getString().endsWith("| 0 | 0 | 0 | 0 |  --"),
                "narrow Automation metrics culled mode/numeric values before the model id");
        ModelGenerationHudState.dismiss(headerRequest);
        ModelContextWindowState context = ModelContextWindowState.from(
                new ModelUsage(24_000, 1_000, 12_000, 0L, 0L, 0.0D),
                100_000
        ).orElseThrow();
        require(context.usedTokens() == 25_000 && context.remainingPercent() == 75,
                "context-window percentage double-counted reused prefix tokens");
        require(ModelContextWindowState.from(ModelUsage.empty(), 100_000).isEmpty(),
                "context-window percentage fabricated usage before the provider reported it");
    }

    private static void proveVoiceCatalogAndSynthesis() throws Exception {
        var voices = ModelVoiceService.voices();
        require(voices.stream().anyMatch(voice -> "cyzon:default".equals(voice.id())),
                "Cyzon voice was not registered");
        if (!Files.isExecutable(Path.of("/usr/bin/say")) || !Files.isExecutable(Path.of("/usr/bin/afconvert"))) {
            return;
        }
        var localVoices = voices.stream().filter(voice -> "macos".equals(voice.providerId())).toList();
        require(localVoices.size() >= 10, "installed macOS voice discovery returned too few voices");
        var voice = localVoices.get(0);
        var provider = ModelVoiceRegistry.providerFor(voice).orElseThrow();
        Path directory = Files.createTempDirectory("koil-model-voice-proof");
        Path audio = provider.synthesize(voice.id(), "test", directory);
        byte[] header = Files.readAllBytes(audio);
        require(header.length >= 12
                        && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E',
                "macOS voice provider did not produce PCM WAV");
        Files.deleteIfExists(audio);
        Files.deleteIfExists(directory);
    }

    private static void proveWorkspaceToolContracts() throws Exception {
        require(ModelWorkspaceToolRegistry.supports("workspace.read"),
                "workspace read tool was not registered");
        require(ModelWorkspaceToolRegistry.supports("workspace.write"),
                "workspace write tool was not registered");
        require(ModelWorkspaceToolRegistry.supports("automation.ktl_apply"),
                "validated KTL apply tool was not registered");
        require(LocalModelToolCatalog.automationModeTools().stream()
                        .anyMatch(tool -> "workspace.read".equals(tool.id())
                                && !tool.confirmationRequired()),
                "read-only workspace tool incorrectly requires mutation approval");
        require(LocalModelToolCatalog.automationModeTools().stream()
                        .anyMatch(tool -> "workspace.write".equals(tool.id())
                                && tool.confirmationRequired()),
                "workspace mutation bypassed approval metadata");
        require(LocalModelToolCatalog.automationModeTools().stream()
                        .anyMatch(tool -> MinecraftKnowledgeModelToolRegistry.TOOL_ID.equals(tool.id())
                                && tool.sideEffects().isEmpty()
                                && !tool.confirmationRequired()),
                "read-only Minecraft knowledge tool was not safely registered");
        try {
            ModelWorkspaceRegistry.resolve("automation", "../outside.ktl", false);
            throw new IllegalStateException("workspace path escape was accepted");
        } catch (java.io.IOException expected) {
            require(expected.getMessage().contains("escapes workspace"),
                    "workspace escape returned an unclear failure");
        }
    }

    private static void provePromptToolSelection() {
        var all = LocalModelToolCatalog.automationModeTools();
        var movement = LocalModelToolCatalog.toolsForPrompt("Walk 10 blocks then jump");
        require(movement.size() < all.size(), "movement prompt retained the full tool catalog");
        require(movement.stream().anyMatch(tool -> "movement.walk_relative".equals(tool.id())),
                "movement prompt lost measured walking");
        require(movement.stream().anyMatch(tool -> "player.jump".equals(tool.id())),
                "sequenced movement prompt lost jumping");
        require(movement.stream().noneMatch(tool -> "workspace.write".equals(tool.id())),
                "movement prompt included unrelated coding tools");
        require(LocalModelToolCatalog.requiredToolIdsForPrompt("Walk 10 blocks then jump")
                        .containsAll(java.util.Set.of("movement.walk_relative", "player.jump")),
                "explicit multi-step objective did not retain both required capabilities");
        require(LocalModelToolCatalog.requiredToolIdsForPrompt(
                                "Check the block I am looking at, then jump.")
                        .contains("player.jump"),
                "knowledge-first objective did not retain its required jump capability");
        require(LocalModelToolCatalog.requiredToolIdsForPrompt("give me a stick with knockback 67")
                        .contains("minecraft.command"),
                "explicit item grant did not require a completed command capability");
        var advancements = LocalModelToolCatalog.toolsForPrompt("give me all advancments");
        require(advancements.stream().anyMatch(tool -> "player.grant_advancements".equals(tool.id())),
                "all-advancements objective lost its typed capability");
        require(advancements.stream().noneMatch(tool -> "minecraft.command".equals(tool.id())),
                "all-advancements objective still forced the model to invent raw command syntax");
        require(LocalModelToolCatalog.requiredToolIdsForPrompt("give me all advancements")
                        .equals(java.util.Set.of("player.grant_advancements")),
                "all-advancements objective retained the generic give-command requirement");
        var coding = LocalModelToolCatalog.toolsForPrompt("Read this Java file and replace one method");
        require(coding.stream().anyMatch(tool -> "workspace.read".equals(tool.id())),
                "coding prompt lost workspace inspection");
        require(coding.stream().anyMatch(tool -> "workspace.replace".equals(tool.id())),
                "coding prompt lost exact replacement");
        require(LocalModelToolCatalog.toolsForPrompt("hello!").isEmpty(),
                "simple Automation conversation still paid for an unrelated tool schema");
        require(LocalModelToolCatalog.toolsForPrompt("How are you?").isEmpty(),
                "conversational Automation question still paid for the full tool catalog");
        var conversationalThinking = AutomationThinkingPolicy.evaluate("hello", true);
        require(!conversationalThinking.deepActive() && !conversationalThinking.includePlanTool(),
                "a greeting incorrectly activated Deep Thinking");
        var directSequence = AutomationThinkingPolicy.evaluate("Walk 10 blocks then jump", false);
        require(!directSequence.deepActive() && directSequence.includePlanTool(),
                "complex normal-mode sequence could not choose autonomous reviewed planning");
        require(LocalModelToolCatalog.toolsForPrompt(
                        "Walk 10 blocks then jump",
                        directSequence.includePlanTool()
                ).stream().anyMatch(tool -> AutomationPlanModelToolRegistry.TOOL_ID.equals(tool.id())),
                "complex normal-mode sequence omitted the optional planning tool");
        var deepThinking = AutomationThinkingPolicy.evaluate(
                "Plan a KTL farming workflow, inspect the files, then verify the result",
                true
        );
        require(deepThinking.deepActive() && deepThinking.maximumToolCalls() > 8,
                "a complex objective did not receive the bounded Deep Thinking budget");
        require(LocalModelToolCatalog.toolsForPrompt(
                        "Plan a KTL farming workflow, inspect the files, then verify the result",
                        deepThinking.includePlanTool()
                ).stream().anyMatch(tool -> AutomationPlanModelToolRegistry.TOOL_ID.equals(tool.id())),
                "complex Deep Thinking objective did not expose the structured plan tool");
        var ktlPlanningTools = LocalModelToolCatalog.toolsForPrompt(
                "Plan a KTL parkour skill and run the existing task",
                true
        );
        require(ktlPlanningTools.stream().anyMatch(tool ->
                        AutomationKtlSkillModelToolRegistry.CATALOG_TOOL_ID.equals(tool.id())),
                "complex KTL objective did not expose registered skill discovery");
        require(ktlPlanningTools.stream().anyMatch(tool ->
                        AutomationKtlSkillModelToolRegistry.RUN_TOOL_ID.equals(tool.id())
                                && tool.confirmationRequired()),
                "registered KTL execution did not retain action approval");
        KtlCompilerService.CompiledAssets proofSkills = new KtlCompilerService.CompiledAssets();
        proofSkills.templates.put(
                "movement/parkour/parkour_route",
                new KtlCompilerService.CompiledTaskTemplate(
                        "movement/parkour/parkour_route",
                        "sem.task.parkour_target",
                        List.of("target.x", "target.y", "target.z"),
                        List.of(new KtlCompilerService.CompiledStep(
                                "delegate",
                                "",
                                "movement/core/movement_context.ktl",
                                "",
                                "",
                                "",
                                "",
                                "",
                                Map.of(),
                                Map.of(),
                                "",
                                "proof",
                                1
                        ))
                )
        );
        proofSkills.templateMetadata.put(
                "movement/parkour/parkour_route",
                new KtlCompilerService.CompiledTemplateMetadata(
                        "movement/parkour/parkour_route",
                        List.of("sem.task.parkour_target"),
                        List.of("location"),
                        List.of("target.x", "target.y", "target.z"),
                        List.of(),
                        List.of("movement", "parkour")
                )
        );
        require(AutomationKtlSkillRegistry.search(proofSkills, "parkour", 8).stream()
                        .anyMatch(skill -> skill.id().equals("movement/parkour/parkour_route")),
                "compiled parkour KTL skill was not discoverable");
        JsonObject skillParameters = new JsonObject();
        skillParameters.addProperty("target.x", 10);
        skillParameters.addProperty("target.y", 65);
        skillParameters.addProperty("target.z", 10);
        AutomationKtlSkillRegistry.PreparedSkill preparedSkill = AutomationKtlSkillRegistry.prepare(
                proofSkills,
                "movement/parkour/parkour_route.ktl",
                skillParameters,
                UUID.randomUUID()
        );
        require(preparedSkill.request().directTemplate(),
                "registered KTL skill did not compile as an explicit task invocation");
        require(preparedSkill.request().rawInput().startsWith("movement/parkour/parkour_route.ktl"),
                "registered KTL skill invocation changed its exact template id");
        try {
            AutomationKtlSkillRegistry.prepare(
                    proofSkills,
                    "cap.path.move_relative_verified",
                    new JsonObject(),
                    UUID.randomUUID()
            );
            throw new IllegalStateException("a Java run primitive was accepted as a KTL skill");
        } catch (com.spirit.koil.api.automation.capability.AutomationCapabilityException expected) {
            require("unknown_ktl_skill".equals(expected.code()),
                    "primitive rejection returned the wrong failure code");
        }
        var commandSequence = LocalModelToolCatalog.toolsForPrompt(
                "Show a title, give me diamonds, then remove an item from my inventory"
        );
        require(commandSequence.stream().anyMatch(tool -> "minecraft.command".equals(tool.id())),
                "Minecraft-only inventory/title actions did not expose the permission-bound command tool");
        require(commandSequence.stream().anyMatch(tool ->
                        MinecraftKnowledgeModelToolRegistry.TOOL_ID.equals(tool.id())),
                "Minecraft command objective did not expose live Minecraft knowledge");
        require(LocalModelToolCatalog.requiresFreshApproval("movement.walk_relative"),
                "side-effecting movement did not require fresh standard-mode approval");
        require(!LocalModelToolCatalog.requiresFreshApproval(
                        MinecraftKnowledgeModelToolRegistry.TOOL_ID),
                "read-only Minecraft knowledge incorrectly required action approval");
        var recipe = LocalModelToolCatalog.toolsForPrompt("How do I craft a modded weapon?");
        require(recipe.stream().anyMatch(tool -> MinecraftKnowledgeModelToolRegistry.TOOL_ID.equals(tool.id())),
                "recipe question did not expose synchronized Minecraft knowledge");
        require(LocalModelToolCatalog.toolsForPrompt("perform an unfamiliar supported objective").size() == all.size(),
                "unknown prompt did not retain the safe full-catalog fallback");

        JsonObject planArguments = new JsonObject();
        planArguments.addProperty("objective", "Walk four blocks, then jump");
        com.google.gson.JsonArray planSteps = new com.google.gson.JsonArray();
        JsonObject walkStep = new JsonObject();
        walkStep.addProperty("toolId", "movement.walk_relative");
        JsonObject walkArguments = new JsonObject();
        walkArguments.addProperty("direction", "forward");
        walkArguments.addProperty("distance", 4);
        walkStep.add("arguments", walkArguments);
        planSteps.add(walkStep);
        JsonObject jumpStep = new JsonObject();
        jumpStep.addProperty("toolId", "player.jump");
        jumpStep.add("arguments", new JsonObject());
        planSteps.add(jumpStep);
        planArguments.add("steps", planSteps);
        ModelToolResult planResult = AutomationPlanModelToolRegistry.execute(new ModelToolCall(
                "plan-proof",
                AutomationPlanModelToolRegistry.TOOL_ID,
                planArguments
        )).join();
        require("completed".equals(planResult.status())
                        && planResult.output().get("executed").getAsBoolean() == false
                        && planResult.output().get("stepCount").getAsInt() == 2
                        && planResult.output().get("planId").getAsString().startsWith("kap-"),
                "structured plan tool did not validate without executing");

        UUID hudRequest = UUID.randomUUID();
        ModelGenerationHudState.begin(hudRequest, "inspect command", true);
        ModelGenerationHudState.appendActivity(hudRequest, "**Thought Process**\nInspect the active command tree.");
        var snapshot = ModelGenerationHudState.visibleSnapshot();
        require(snapshot != null && snapshot.automationRequest(),
                "generation HUD lost the request mode");
        require(snapshot.activity().contains("Inspect the active command tree"),
                "generation HUD did not preserve visible activity");
        ModelGenerationHudState.messagePresented(hudRequest);
        require(ModelGenerationHudState.visibleSnapshot() == null,
                "model popup remained visible after the final response entered chat");
    }

    private static void proveRichChatTablesAndSharedRegistrySuggestions() {
        String maskedSource = "[Give Knockback 5 Stick](/give @s minecraft:stick{Enchantments:[{id:\"minecraft:knockback\",lvl:5s}]} 1)";
        Text compactMasked = RichChatMaskedLinkBridge.rewrite(Text.literal(maskedSource));
        require(RichChatMaskedLinkBridge.containsMarker(compactMasked.getString()),
                "masked command target was still exposed to native chat wrapping");
        require(maskedSource.equals(RichChatMaskedLinkBridge.logFriendlyText(compactMasked.getString())),
                "compact masked command did not reconstruct for logs");
        Text rewritten = RichChatTableBridge.rewrite(Text.literal("""
                | Item | Use |
                | --- | --- |
                | Furnace | Smelting |
                """.strip()));
        require(RichChatTableBridge.containsMarker(rewritten.getString()),
                "Markdown table was not rewritten into fixed Rich Chat rows");
        require(RichChatTableBridge.logFriendlyText(rewritten.getString()).contains("| Furnace | Smelting |"),
                "Rich Chat table could not be reconstructed for logs");

        MinecraftRegistrySuggestions.SearchResult suggestions = MinecraftRegistrySuggestions.search(
                List.of(
                        new Identifier("example", "ruby_sword"),
                        new Identifier("minecraft", "iron_sword"),
                        new Identifier("minecraft", "iron_ingot")
                ),
                "sword",
                8
        );
        require(suggestions.matchCount() == 2,
                "shared registry matching returned an incorrect match count");
        require("minecraft:iron_sword".equals(suggestions.candidates().get(0).identifier().toString()),
                "shared registry matching did not prefer the vanilla prefix match deterministically");

        var grounded = MinecraftNbtSuggestionService.groundedItemEnchantmentCommand(
                "What command gives me a stick with Knockback 5?",
                List.of(new Identifier("minecraft", "stick")),
                List.of(new Identifier("minecraft", "knockback"))
        ).orElseThrow(() -> new IllegalStateException("item/enchantment request was not grounded"));
        require(
                "/give @s minecraft:stick{Enchantments:[{id:\"minecraft:knockback\",lvl:5s}]} 1"
                        .equals(grounded.command()),
                "grounded Knockback stick command did not use Minecraft 1.20.1 item SNBT"
        );
        require(MinecraftNbtSuggestionService.nbtKnowledge("enchant", 8).templates().stream()
                        .anyMatch(template -> template.text().contains("Enchantments")),
                "shared item-NBT knowledge did not expose the enchantment template");
        require(CodeLanguageDetector.bestGuess("/give @s minecraft:stick").language()
                        == CodeLanguageDetector.CodeLanguage.MINECRAFT_COMMAND,
                "Minecraft command code block language was not detected");

        List<Identifier> proofItems = List.of(new Identifier("minecraft", "stick"));
        List<Identifier> proofEnchantments = List.of(
                new Identifier("minecraft", "knockback"),
                new Identifier("minecraft", "sharpness")
        );
        require(suggestionTexts(
                        "/give @s minecraft:stick{En",
                        proofItems,
                        proofEnchantments
                ).equals(List.of("Enchantments")),
                "item NBT completion did not suggest a structural root key");
        require(suggestionTexts(
                        "/give @s minecraft:stick{Enchantments",
                        proofItems,
                        proofEnchantments
                ).equals(List.of(":")),
                "item NBT completion did not advance from a key to its colon");
        require(suggestionTexts(
                        "/give @s minecraft:stick{Enchantments:",
                        proofItems,
                        proofEnchantments
                ).equals(List.of("[")),
                "item NBT completion did not advance into the enchantment list");
        require(suggestionTexts(
                        "/give @s minecraft:stick{Enchantments:[",
                        proofItems,
                        proofEnchantments
                ).equals(List.of("{")),
                "item NBT completion did not advance into one list entry");
        require(suggestionTexts(
                        "/give @s minecraft:stick{Enchantments:[{",
                        proofItems,
                        proofEnchantments
                ).equals(List.of("id", "lvl")),
                "item NBT completion did not offer entry fields");
        require(suggestionTexts(
                        "/give @s minecraft:stick{Enchantments:[{id:\"kn",
                        proofItems,
                        proofEnchantments
                ).equals(List.of("minecraft:knockback")),
                "item NBT completion did not resolve the active enchantment id");

        String normalizedModelText = RichChatModelOutputSanitizer.normalizeSoftLineBreaks("""
                This prose was hard wrapped
                by a model response.

                | Item | Count |
                | --- | --- |
                | Stick | 1 |
                """.strip());
        require(normalizedModelText.contains("hard wrapped by a model response."),
                "model prose hard wraps were not normalized");
        require(normalizedModelText.contains("| Item | Count |\n| --- | --- |"),
                "Rich Chat table structure was lost while normalizing prose wraps");

        String compactOutput = RichChatModelOutputSanitizer.sanitize("""
                # Compact heading

                A compact answer.


                ```markdown
                | Item | Count |
                | --- | --- |
                | Stick | 1 |
                ```

                ```
                [Set day](/time set day)
                ```
                """).text();
        require(compactOutput.startsWith("# Compact heading\nA compact answer."),
                "model output retained a blank line immediately after a heading");
        require(!compactOutput.contains("\n\n"),
                "model output retained an empty presentation row outside fenced code");
        require(compactOutput.contains("| Item | Count |\n| --- | --- |")
                        && !compactOutput.contains("```markdown"),
                "a fenced Markdown table was not restored to live Rich Chat");
        require(compactOutput.contains("[Set day](/time set day)")
                        && compactOutput.indexOf("```") < 0,
                "a fenced masked command link was not restored to live Rich Chat");
        RichChatHeadingLayout.Heading heading =
                RichChatHeadingLayout.detect("## A heading that wraps");
        require(heading != null && heading.level() == 2 && heading.scale() > 1.5F,
                "shared heading layout did not expose scaled wrap geometry");
    }

    private static List<String> suggestionTexts(
            String command,
            Iterable<Identifier> items,
            Iterable<Identifier> enchantments
    ) {
        return MinecraftNbtSuggestionService.suggest(
                        command,
                        command.length(),
                        24,
                        items,
                        enchantments
                )
                .stream()
                .map(com.mojang.brigadier.suggestion.Suggestion::getText)
                .toList();
    }

    private static void proveConversationBounds() {
        ModelConversation conversation = new ModelConversation("proof", 3, 256);
        conversation.add(ModelMessage.user("first message " + "a".repeat(80)));
        conversation.add(ModelMessage.assistant("second message " + "b".repeat(80)));
        conversation.add(ModelMessage.user("third message " + "c".repeat(80)));
        conversation.add(ModelMessage.assistant("fourth message " + "d".repeat(80)));
        require(conversation.snapshot().size() <= 3, "conversation message bound was not enforced");
        require(conversation.characterCount() <= 256, "conversation character bound was not enforced");
        require(
                conversation.snapshot().stream().noneMatch(message -> message.content().startsWith("first message")),
                "oldest conversation message was not trimmed"
        );
        ModelConversation windowed = new ModelConversation("windowed", 12, 4_096);
        windowed.add(ModelMessage.user("old context " + "a".repeat(300)));
        windowed.add(ModelMessage.assistant("old answer " + "b".repeat(300)));
        windowed.add(ModelMessage.user("latest objective"));
        List<ModelMessage> window = windowed.snapshotWithin(2, 128);
        require(window.size() == 1 && "latest objective".equals(window.get(0).content()),
                "bounded request window did not preserve the newest objective");
    }

    private static void proveNonBlockingCommandSuggestionPolling() {
        CompletableFuture<Suggestions> pending = new CompletableFuture<>();
        require(CommandSuggestionFuturePoller.readyOrNull(pending) == null,
                "pending command suggestions blocked or returned an incomplete result");
        Suggestions completed = new Suggestions(StringRange.at(0), List.of());
        pending.complete(completed);
        require(CommandSuggestionFuturePoller.readyOrNull(pending) == completed,
                "completed command suggestions were not returned");
        CompletableFuture<Suggestions> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("proof failure"));
        require(CommandSuggestionFuturePoller.readyOrNull(failed) == null,
                "failed command suggestions escaped into the UI path");
    }

    private static void proveStreamingQueueAndToolEvents() throws Exception {
        FakeLocalModelProvider provider = new FakeLocalModelProvider(2L);
        provider.fixedResponse("streamed model answer");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("distance", 50);
        provider.nextToolCall(new ModelToolCall("tool-proof", "movement.walk_relative", arguments));
        try (LocalModelRuntimeManager manager = new LocalModelRuntimeManager(4)) {
            manager.registerProvider(provider);
            Capture firstCapture = new Capture();
            Capture secondCapture = new Capture();
            ManagedModelRequest first = manager.submit(request("walk forward 50 blocks"), firstCapture);
            ManagedModelRequest second = manager.submit(request("explain the result"), secondCapture);
            StreamingModelResponse firstResponse = first.completion().get(5L, TimeUnit.SECONDS);
            StreamingModelResponse secondResponse = second.completion().get(5L, TimeUnit.SECONDS);

            require(firstCapture.states.contains(ModelRequestState.QUEUED), "queued state was not observed");
            require(firstCapture.states.contains(ModelRequestState.PREFILLING), "prefill state was not observed");
            require(firstCapture.states.contains(ModelRequestState.GENERATING), "generation state was not observed");
            require(firstCapture.states.contains(ModelRequestState.FINALIZING), "finalizing state was not observed");
            require(firstCapture.toolCalls.size() == 1, "tool-use event was not streamed");
            require(
                    "movement.walk_relative".equals(firstCapture.toolCalls.get(0).toolId()),
                    "wrong tool identifier was streamed"
            );
            require("streamed model answer".equals(firstCapture.text.toString()), "stream deltas were not preserved");
            require("streamed model answer".equals(firstResponse.text()), "first final response was incorrect");
            require("streamed model answer".equals(secondResponse.text()), "queued response was incorrect");
            long queueDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L);
            while (manager.queueDepth() != 0 && System.nanoTime() < queueDeadline) {
                Thread.sleep(5L);
            }
            require(manager.queueDepth() == 0, "queue did not drain");
        }
    }

    private static void proveCancellation() throws Exception {
        FakeLocalModelProvider provider = new FakeLocalModelProvider(30L);
        provider.fixedResponse("this response is intentionally long enough to cancel during streaming");
        try (LocalModelRuntimeManager manager = new LocalModelRuntimeManager(2)) {
            manager.registerProvider(provider);
            Capture capture = new Capture();
            ManagedModelRequest request = manager.submit(request("cancel this"), capture);
            Thread.sleep(40L);
            require(request.cancellation().cancel("proof cancellation"), "first cancellation request was rejected");
            try {
                request.completion().join();
                throw new IllegalStateException("cancelled request completed successfully");
            } catch (CompletionException expected) {
                require(
                        expected.getCause() instanceof LocalModelRuntimeManager.ModelRequestException exception
                                && ("cancelled".equals(exception.code()) || "fake_failed".equals(exception.code())),
                        "cancelled request returned the wrong failure"
                );
            }
            require(
                    capture.states.contains(ModelRequestState.CANCELLED),
                    "cancelled state was not observed"
            );
        }
    }

    private static StreamingModelRequest request(String prompt) {
        return new StreamingModelRequest(
                UUID.randomUUID(),
                "proof",
                "Proof system prompt",
                List.of(ModelMessage.user(prompt)),
                List.of(),
                128,
                Duration.ofSeconds(3),
                Map.of("source", "proof")
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class Capture implements StreamingModelObserver {
        private final List<ModelRequestState> states = new ArrayList<>();
        private final List<ModelToolCall> toolCalls = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public synchronized void onState(UUID requestId, ModelRequestState state, String detail) {
            this.states.add(state);
        }

        @Override
        public synchronized void onTextDelta(UUID requestId, String delta) {
            this.text.append(delta);
        }

        @Override
        public synchronized void onToolCall(UUID requestId, ModelToolCall call) {
            this.toolCalls.add(call);
        }
    }
}
