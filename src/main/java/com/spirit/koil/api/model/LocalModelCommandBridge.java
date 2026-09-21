package com.spirit.koil.api.model;

import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelCompatibility;
import com.spirit.koil.api.model.catalog.LocalModelCatalogView;
import com.spirit.koil.api.model.catalog.LocalModelReliabilityStore;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceService;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.model.retrieval.KnowledgeFilter;
import com.spirit.koil.api.model.retrieval.KnowledgeQuery;
import com.spirit.koil.api.model.retrieval.KnowledgeTrust;
import com.spirit.koil.api.model.retrieval.KnowledgeType;
import com.spirit.koil.api.model.retrieval.RetrievalCandidate;
import com.spirit.koil.api.model.chat.LocalModelCatalogChatState;
import com.spirit.koil.api.model.chat.LocalModelControlChatFeedback;
import com.spirit.koil.api.model.hardware.HardwareCapabilityReport;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import com.spirit.koil.api.model.install.ModelInstallationSnapshot;
import com.spirit.koil.api.model.install.ModelInstallationState;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeMode;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeSettings;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppMaxTuningResult;
import com.spirit.koil.api.model.voice.ModelVoiceDefinition;
import com.spirit.koil.api.model.voice.ModelVoiceService;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.command.CommandSource;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class LocalModelCommandBridge {
    private LocalModelCommandBridge() {
    }

    /**
     * Executes Koil-owned chat commands directly on the client. This is used by
     * Rich Chat for oversized/multiline command submissions that cannot safely
     * travel through Minecraft's vanilla 256-character command packet.
     *
     * <p>The method deliberately recognizes only commands owned by this bridge.
     * Returning {@code true} means the text was a Koil client command and must
     * not be forwarded to the server, regardless of whether the model request
     * itself could be started.</p>
     */
    public static boolean tryExecuteRichClientCommand(String rawText) {
        if (rawText == null) return false;
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (!normalized.startsWith("/")) return false;

        String command = normalized.substring(1).stripLeading();
        if (!command.equals("ask") && !command.startsWith("ask ") && !command.startsWith("ask\n") && !command.startsWith("ask\t")) {
            return false;
        }

        String tail = command.length() <= 3 ? "" : command.substring(3).stripLeading();
        if (tail.equals("deep")) {
            LocalModelService.askDeep("");
            return true;
        }
        if (tail.startsWith("deep ") || tail.startsWith("deep\n") || tail.startsWith("deep\t")) {
            String deepTail = tail.substring(4).stripLeading();
            if (deepTail.startsWith("resume ") || deepTail.startsWith("resume\n") || deepTail.startsWith("resume\t")) {
                String sessionId = deepTail.substring(6).strip();
                if (!sessionId.isEmpty() && sessionId.indexOf(' ') < 0 && sessionId.indexOf('\n') < 0 && sessionId.indexOf('\t') < 0) {
                    LocalModelService.resumeDeepThought(sessionId);
                    return true;
                }
            }
            LocalModelService.askDeep(deepTail);
            return true;
        }

        LocalModelService.ask(tail);
        return true;
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("ask")
                        .executes(context -> LocalModelService.ask("") ? 1 : 0)
                        .then(argument("prompt", greedyString())
                                .executes(context -> LocalModelService.ask(getString(context, "prompt")) ? 1 : 0))
                        .then(literal("deep")
                                .then(literal("resume")
                                        .then(argument("session_id", word())
                                                .executes(context -> LocalModelService.resumeDeepThought(
                                                        getString(context, "session_id")) ? 1 : 0)))
                                .then(argument("prompt", greedyString())
                                        .executes(context -> LocalModelService.askDeep(getString(context, "prompt")) ? 1 : 0)))
                )
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(modelCommand())
        );
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> modelCommand() {
        return literal("model")
                        .executes(context -> {
                            showStatus();
                            return 1;
                        })
                        .then(literal("status").executes(context -> {
                            showStatus();
                            return 1;
                        }))
                        .then(literal("tools")
                                .then(literal("verify").executes(context -> {
                                    verifyTools();
                                    return 1;
                                })))
                        .then(literal("turbovec")
                                .then(literal("status").executes(context -> {
                                    showTurboVecStatus();
                                    return 1;
                                }))
                                .then(literal("query")
                                        .then(argument("query", greedyString())
                                                .executes(context -> {
                                                    queryTurboVec(getString(context, "query"));
                                                    return 1;
                                                })))
                                .then(literal("repairs")
                                        .then(argument("query", greedyString())
                                                .executes(context -> {
                                                    queryTurboVecRepairs(getString(context, "query"));
                                                    return 1;
                                                }))))
                        .then(literal("info").executes(context -> {
                            showStatus();
                            showSelectedModel();
                            return 1;
                        }))
                        .then(literal("help").executes(context -> {
                            showHelp();
                            return 1;
                        }))
                        .then(literal("list")
                                .executes(context -> {
                                    showCatalog(1, "model");
                                    return 1;
                                })
                                .then(argument("page", integer(1))
                                        .executes(context -> {
                                            showCatalog(getInteger(context, "page"), "model");
                                            return 1;
                                        })))
                        .then(literal("catalog").executes(context -> {
                            showCatalog(1, "model");
                            return 1;
                        })
                                .then(literal("refresh").executes(context -> {
                                    refreshModelCatalog();
                                    return 1;
                                }))
                                .then(literal("search")
                                        .then(argument("query", greedyString())
                                                .executes(context -> {
                                                    searchModelCatalog(getString(context, "query"), 1, true);
                                                    return 1;
                                                })))
                                .then(literal("search-page")
                                        .then(argument("page", integer(1))
                                                .then(argument("query", greedyString())
                                                        .executes(context -> {
                                                            searchModelCatalog(
                                                                    getString(context, "query"),
                                                                    getInteger(context, "page"),
                                                                    false
                                                            );
                                                            return 1;
                                                        })))))
                        .then(literal("installed").executes(context -> {
                            showInstalled();
                            return 1;
                        }))
                        .then(literal("install")
                                .then(argument("catalog_id", word())
                                        .suggests((context, builder) -> suggestCompatibleModels(builder, SuggestionMode.INSTALL))
                                        .executes(context -> {
                                    installModel(getString(context, "catalog_id"), false);
                                    return 1;
                                })))
                        .then(literal("install-url")
                                .then(argument("url", greedyString())
                                        .executes(context -> {
                                            installDirectUrl(getString(context, "url"));
                                            return 1;
                                        })))
                        .then(literal("use")
                                .then(argument("catalog_id", word())
                                        .suggests((context, builder) -> suggestInstalledModels(builder, false))
                                        .executes(context -> {
                                    useInstalledModel(getString(context, "catalog_id"));
                                    return 1;
                                })))
                        .then(literal("switch")
                                .then(argument("catalog_id", word())
                                        .suggests((context, builder) -> suggestCompatibleModels(builder, SuggestionMode.SWITCH))
                                        .executes(context -> {
                                    installModel(getString(context, "catalog_id"), true);
                                    return 1;
                                })))
                        .then(literal("uninstall")
                                .then(argument("catalog_id", word())
                                        .suggests((context, builder) -> suggestInstalledModels(builder, true))
                                        .executes(context -> {
                                    uninstallModel(getString(context, "catalog_id"));
                                    return 1;
                                })))
                        .then(literal("start").executes(context -> {
                            startRuntime();
                            return 1;
                        }))
                        .then(literal("stop").executes(context -> {
                            stopRuntime();
                            return 1;
                        }))
                        .then(literal("restart").executes(context -> {
                            restartRuntime();
                            return 1;
                        }))
                        .then(literal("compute")
                                .executes(context -> {
                                    showComputeStatus();
                                    return 1;
                                })
                                .then(literal("cpu").executes(context -> {
                                    setComputeMode(LlamaCppComputeMode.CPU, null);
                                    return 1;
                                }))
                                .then(literal("max")
                                        .executes(context -> {
                                            setComputeMode(LlamaCppComputeMode.MAX, null);
                                            return 1;
                                        })
                                        .then(literal("tune")
                                                .executes(context -> {
                                                    tuneMaxCompute();
                                                    return 1;
                                                })
                                                .then(argument("context", word())
                                                        .executes(context -> {
                                                            tuneMaxCompute(getString(context, "context"));
                                                            return 1;
                                                        }))))
                                .then(literal("tune").executes(context -> {
                                    tuneMaxCompute();
                                    return 1;
                                }))
                                .then(literal("auto").executes(context -> {
                                    setComputeMode(LlamaCppComputeMode.MAX, null);
                                    return 1;
                                }))
                                .then(literal("gpu").executes(context -> {
                                    setComputeMode(LlamaCppComputeMode.GPU, null);
                                    return 1;
                                }))
                                .then(literal("hybrid")
                                        .executes(context -> {
                                            setComputeMode(LlamaCppComputeMode.HYBRID, null);
                                            return 1;
                                        })
                                        .then(argument("gpu_layers", integer(1, LlamaCppComputeSettings.MAX_GPU_LAYERS))
                                                .executes(context -> {
                                                    setComputeMode(
                                                            LlamaCppComputeMode.HYBRID,
                                                            getInteger(context, "gpu_layers")
                                                    );
                                                    return 1;
                                                }))))
                        .then(literal("cancel").executes(context -> {
                            boolean cancelled = LocalModelService.cancelActiveWork();
                            if (LocalModelService.cancelActiveRuntimeSetup()) {
                                cancelled = true;
                            }
                            chat(cancelled
                                    ? "Cancelling active local model, runtime setup, or automation work."
                                    : "No active local model, runtime setup, or automation work to cancel.");
                            return 1;
                        }))
                        .then(literal("queue")
                                .executes(context -> {
                                    showQueue();
                                    return 1;
                                })
                                .then(literal("list").executes(context -> {
                                    showQueue();
                                    return 1;
                                }))
                                .then(literal("edit")
                                        .then(argument("request_id", word())
                                                .then(argument("revision", word())
                                                        .then(argument("prompt", greedyString())
                                                                .executes(context -> {
                                                                    editQueuedPrompt(
                                                                            getString(context, "request_id"),
                                                                            getString(context, "revision"),
                                                                            getString(context, "prompt")
                                                                    );
                                                                    return 1;
                                                                }))))))
                        .then(literal("probe").then(argument("case", word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        List.of("short", "structured", "repetition", "exact", "code", "long"), builder))
                                .executes(context -> {
                                    try {
                                        String name = getString(context, "case");
                                        int limit = LocalModelService.selectedAgentProfile().contextWindowTokens();
                                        String prompt = com.spirit.koil.api.model.testing.ModelValidationPrompts.prompt(name, limit);
                                        LocalModelRuntimeLog.write("model_probe", "case=" + name
                                                + " inputChars=" + prompt.length() + " rawTokens=unknown contextLimit=" + limit);
                                        return LocalModelService.ask(prompt) ? 1 : 0;
                                    } catch (IllegalArgumentException invalid) {
                                        chat(invalid.getMessage());
                                        return 0;
                                    }
                                })))
                        .then(literal("diagnostics").executes(context -> {
                            showHardware(false);
                            return 1;
                        }))
                        .then(literal("rescan").executes(context -> {
                            showHardware(true);
                            return 1;
                        }))
                        .then(literal("reset").executes(context -> {
                            LocalModelService.resetGeneralConversation();
                            chat("Local model general conversation cleared.");
                            return 1;
                        })
                                .then(literal("general").executes(context -> {
                                    LocalModelService.resetGeneralConversation();
                                    chat("Local model general conversation cleared.");
                                    return 1;
                                }))
                                .then(literal("automation").executes(context -> {
                                    LocalModelService.resetAutomationConversation();
                                    chat("Local model automation conversation cleared.");
                                    return 1;
                                }))
                                .then(literal("all").executes(context -> {
                                    LocalModelService.resetAllConversations();
                                    chat("All local model conversations cleared.");
                                    return 1;
                                })))
                        .then(literal("logs").executes(context -> {
                            info("Local model and Automation log: koil/logs/latest.log | Automation Thread");
                            return 1;
                        }))
                        .then(literal("reliability")
                                .executes(context -> {
                                    showReliability(LocalModelService.selectedCatalogId());
                                    return 1;
                                })
                                .then(literal("reset")
                                        .executes(context -> {
                                            resetReliability(LocalModelService.selectedCatalogId());
                                            return 1;
                                        })
                                        .then(argument("catalog_id", word())
                                                .suggests((context, builder) -> suggestInstalledModels(builder, true))
                                                .executes(context -> {
                                                    resetReliability(getString(context, "catalog_id"));
                                                    return 1;
                                                }))))
                        .then(literal("prompt").executes(context -> {
                            showPromptLocation();
                            return 1;
                        }))
                        .then(literal("voice")
                                .executes(context -> {
                                    showVoiceStatus();
                                    return 1;
                                })
                                .then(literal("true").executes(context -> {
                                    setVoiceEnabled(true);
                                    return 1;
                                }))
                                .then(literal("false").executes(context -> {
                                    setVoiceEnabled(false);
                                    return 1;
                                }))
                                .then(literal("list").executes(context -> {
                                    showVoices();
                                    return 1;
                                }))
                                .then(literal("set")
                                        .then(argument("voice_id", word())
                                                .suggests((context, builder) -> suggestVoices(builder))
                                                .executes(context -> {
                                                    setVoice(getString(context, "voice_id"));
                                                    return 1;
                                                }))))
                ;
    }

    private static CompletableFuture<Suggestions> suggestInstalledModels(
            SuggestionsBuilder builder,
            boolean includeSelected
    ) {
        String selectedId = LocalModelService.selectedCatalogId();
        List<String> ids = LocalModelInstallationService.instance().installedEntries().stream()
                .map(LocalModelCatalogEntry::id)
                .filter(id -> includeSelected || !id.equals(selectedId))
                .sorted()
                .toList();
        return CommandSource.suggestMatching(ids, builder);
    }

    private static CompletableFuture<Suggestions> suggestCompatibleModels(
            SuggestionsBuilder builder,
            SuggestionMode mode
    ) {
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        String selectedId = LocalModelService.selectedCatalogId();
        return LocalModelService.hardwareReport(false)
                .handle((report, failure) -> LocalModelCatalog.generationEntries().stream()
                        .filter(entry -> mode != SuggestionMode.INSTALL || !installer.installed(entry))
                        .filter(entry -> mode != SuggestionMode.SWITCH || !entry.id().equals(selectedId))
                        .filter(entry -> report == null || supportedForCurrentComputer(entry, report, installer))
                        .map(LocalModelCatalogEntry::id)
                        .sorted()
                        .toList())
                .thenCompose(ids -> CommandSource.suggestMatching(ids, builder));
    }

    private static boolean supportedForCurrentComputer(
            LocalModelCatalogEntry entry,
            HardwareCapabilityReport report,
            LocalModelInstallationService installer
    ) {
        LocalModelCompatibility compatibility = LocalModelCompatibility.evaluate(
                entry,
                report,
                installer.storagePlan(entry).remainingDownloadBytes()
        );
        return compatibility.level() == LocalModelCompatibility.Level.RECOMMENDED
                || compatibility.level() == LocalModelCompatibility.Level.SUPPORTED_WITH_LIMITS;
    }

    public static void showCatalogPopup() {
        showCatalog(1, "model");
    }

    private static void verifyTools() {
        info("Tool verification started: non-destructive runtime probes, MCP/Code Intelligence retest, chaining, and knowledge population.");
        ModelToolRuntimeDiagnostics.run().whenComplete((report, failure) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            Runnable publish = () -> {
                if (failure != null || report == null) {
                    chat("Tool verification failed: " + conciseFailure(failure));
                    return;
                }
                LocalModelControlChatFeedback.header("Model Tool Verification");
                info("Registry: " + report.dispatchCoveredTools() + "/" + report.registeredTools() + " tool ids have executable dispatch coverage.");
                for (ModelToolRuntimeDiagnostics.Check check : report.checks()) {
                    info((check.passed() ? "PASS" : "FAIL") + " | " + check.name() + " | " + check.detail());
                }
                info("Overall: " + (report.passed() ? "healthy" : "attention required")
                        + " | checks=" + report.passedChecks() + "/" + report.checks().size());
            };
            if (client != null) client.execute(publish); else publish.run();
        });
    }

    private static void showTurboVecStatus() {
        // This is an explicit retrieval diagnostic command, so it should initialize the knowledge runtime
        // instead of reporting "not initialized" while a query issued seconds later initializes it anyway.
        KoilKnowledgeRuntime.shared();
        String status = KoilKnowledgeRuntime.status();
        LocalModelControlChatFeedback.header("TurboVec Retrieval");
        info(status);
        info("Use /model turbovec query <text> to inspect hybrid ranking, or /model turbovec repairs <error> for learned repair evidence.");
    }

    private static void queryTurboVec(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty()) {
            chat("TurboVec query requires text.");
            return;
        }
        var engineOptional = KoilKnowledgeRuntime.shared();
        if (engineOptional.isEmpty()) {
            chat("TurboVec query unavailable: Koil knowledge runtime is not active.");
            return;
        }
        info("TurboVec query started: " + query);
        String requestId = "manual-turbovec-" + Long.toUnsignedString(System.nanoTime());
        KnowledgeQuery retrieval = new KnowledgeQuery(query, KnowledgeFilter.any(), 12, 1_200,
                requestId, KnowledgeTrust.CURRENT_OBSERVATION);
        engineOptional.get().retrieve(retrieval).whenComplete((result, failure) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            Runnable publish = () -> {
                if (failure != null || result == null) {
                    chat("TurboVec query failed: " + conciseFailure(failure));
                    return;
                }
                LocalModelControlChatFeedback.header("TurboVec Query Results");
                info("Query: " + query + " | selected=" + result.selected().size()
                        + " | context≈" + result.contextTokens() + " tokens");
                if (result.selected().isEmpty()) {
                    info("No retrieval candidates were selected.");
                    return;
                }
                int rank = 1;
                for (RetrievalCandidate candidate : result.selected()) {
                    var entry = candidate.entry();
                    String source = entry.source();
                    String preview = entry.text().replaceAll("\s+", " ").strip();
                    if (preview.length() > 180) preview = preview.substring(0, 177) + "...";
                    info(String.format(java.util.Locale.ROOT,
                            "#%d dense=%.3f exact=%.3f fused=%.5f | %s/%s | %s | %s",
                            rank++, candidate.semanticScore(), candidate.exactScore(), candidate.fusedScore(),
                            entry.type().name().toLowerCase(java.util.Locale.ROOT),
                            entry.trust().name().toLowerCase(java.util.Locale.ROOT),
                            source == null || source.isBlank() ? "unknown-source" : source,
                            preview));
                }
            };
            if (client != null) client.execute(publish); else publish.run();
        });
    }

    private static void queryTurboVecRepairs(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty()) {
            chat("TurboVec repair query requires error or failure text.");
            return;
        }
        var engineOptional = KoilKnowledgeRuntime.shared();
        if (engineOptional.isEmpty()) {
            chat("TurboVec repair query unavailable: Koil knowledge runtime is not active.");
            return;
        }
        KnowledgeFilter filter = new KnowledgeFilter(
                java.util.Set.of(KnowledgeType.REPAIR_EPISODE, KnowledgeType.COUNTEREXAMPLE, KnowledgeType.WORKFLOW_RECIPE),
                java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), 0L);
        KnowledgeQuery retrieval = new KnowledgeQuery(query, filter, 12, 1_200,
                "manual-repair-" + Long.toUnsignedString(System.nanoTime()), KnowledgeTrust.CURRENT_OBSERVATION);
        info("TurboVec repair query started: " + query);
        engineOptional.get().retrieve(retrieval).whenComplete((result, failure) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            Runnable publish = () -> {
                if (failure != null || result == null) {
                    chat("TurboVec repair query failed: " + conciseFailure(failure));
                    return;
                }
                LocalModelControlChatFeedback.header("TurboVec Repair Evidence");
                info("Query: " + query + " | selected=" + result.selected().size());
                if (result.selected().isEmpty()) {
                    info("No learned repair/counterexample/workflow evidence matched yet.");
                    return;
                }
                int rank = 1;
                for (RetrievalCandidate candidate : result.selected()) {
                    var entry = candidate.entry();
                    String preview = entry.text().replaceAll("\\s+", " ").strip();
                    if (preview.length() > 220) preview = preview.substring(0, 217) + "...";
                    info(String.format(java.util.Locale.ROOT,
                            "#%d dense=%.3f exact=%.3f fused=%.5f | %s | outcome=%s | %s",
                            rank++, candidate.semanticScore(), candidate.exactScore(), candidate.fusedScore(),
                            entry.type().name().toLowerCase(java.util.Locale.ROOT),
                            entry.metadata().getOrDefault("outcome", "historical"), preview));
                }
                info("Repair entries are candidate evidence only. Current errors, environment, and tool preconditions remain authoritative.");
            };
            if (client != null) client.execute(publish); else publish.run();
        });
    }

    private static String conciseFailure(Throwable failure) {
        if (failure == null) return "unknown failure";
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String detail = current.getMessage();
        return detail == null || detail.isBlank() ? current.getClass().getSimpleName() : detail;
    }

    private static void showStatus() {
        ModelHealthSnapshot health = LocalModelService.health();
        LocalModelControlChatFeedback.header("Local Model Status");
        LocalModelControlChatFeedback.add(
                LocalModelControlChatFeedback.label(
                        "Provider",
                        LocalModelService.selectedProviderId(),
                        Formatting.WHITE
                ).append(Text.literal("  |  State: ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(health.state().name().toLowerCase(java.util.Locale.ROOT))
                                .formatted(health.state() == ModelHealthState.READY ? Formatting.GREEN : Formatting.DARK_GRAY))
                        .append(Text.literal("  |  Queue: " + LocalModelService.queueDepth()).formatted(Formatting.DARK_GRAY)),
                LocalModelControlChatFeedback.Level.INFO
        );
        LocalModelControlChatFeedback.add(
                LocalModelControlChatFeedback.label("Model", LocalModelService.configuredModelId(), Formatting.WHITE)
                        .append(Text.literal("  |  Tool registry: " + LocalModelToolCatalog.version()).formatted(Formatting.DARK_GRAY)),
                LocalModelControlChatFeedback.Level.INFO
        );
        info("Execution adapter: " + LocalModelService.selectedExecutionAdapterId()
                + " | tool protocol: " + LocalModelService.selectedToolProtocol());
        String universalMode = health.diagnostics().getOrDefault("computeMode", "");
        String universalBackend = health.diagnostics().getOrDefault("primaryBackend", "");
        String universalArchitecture = health.diagnostics().getOrDefault("architecture", "");
        String universalBackends = health.diagnostics().getOrDefault("runtimeBackends", "");
        String universalFingerprint = health.diagnostics().getOrDefault("hardwareFingerprint", "");
        String universalTuning = health.diagnostics().getOrDefault("tuningSource", "none");
        if (!universalMode.isBlank() || !universalBackend.isBlank()) {
            String tuningProtocol = health.diagnostics().getOrDefault("tuningProtocol", "");
            String tuningAdapter = health.diagnostics().getOrDefault("tuningAdapter", "");
            String tuningContextRegime = health.diagnostics().getOrDefault("tuningContextRegime", "");
            info("Universal plan: mode=" + (universalMode.isBlank() ? "unknown" : universalMode)
                    + " | backend=" + (universalBackend.isBlank() ? "unknown" : universalBackend)
                    + (universalArchitecture.isBlank() ? "" : " | architecture=" + universalArchitecture)
                    + (universalBackends.isBlank() ? "" : " | runtime backends=" + universalBackends)
                    + ("none".equalsIgnoreCase(universalTuning) || universalTuning.isBlank() ? "" : " | tuning=" + universalTuning)
                    + (tuningProtocol.isBlank() ? "" : " | benchmark=" + tuningProtocol)
                    + (tuningAdapter.isBlank() ? "" : " via " + tuningAdapter)
                    + (tuningContextRegime.isBlank() ? "" : " | tuned context=" + tuningContextRegime)
                    + (universalFingerprint.isBlank() ? "" : " | hardware=" + universalFingerprint));
        }
        String planPlacement = health.diagnostics().getOrDefault("planPlacement", "");
        if (!planPlacement.isBlank()) {
            info("Universal execution: placement=" + planPlacement
                    + " | GPU layers=" + health.diagnostics().getOrDefault("planGpuLayers", "-1")
                    + " | threads=" + health.diagnostics().getOrDefault("planThreads", "0/0")
                    + " | poll=" + health.diagnostics().getOrDefault("planPoll", "0/0")
                    + " | batch=" + health.diagnostics().getOrDefault("planBatch", "0/0")
                    + " | state=" + health.diagnostics().getOrDefault("planStatePlacement", "automatic")
                    + " | operators=" + health.diagnostics().getOrDefault("planOperatorPlacement", "automatic"));
            long universalBudgetBytes = parseLongOrZero(health.diagnostics().getOrDefault("planMemoryBudgetBytes", "0"));
            long universalAvailableBytes = parseLongOrZero(health.diagnostics().getOrDefault("memoryAvailableBytes", "0"));
            long universalReserveBytes = parseLongOrZero(health.diagnostics().getOrDefault("memoryReservedHostBytes", "0"));
            long universalFloorBytes = parseLongOrZero(health.diagnostics().getOrDefault("memorySafetyFloorBytes", "0"));
            String universalPressure = health.diagnostics().getOrDefault("memoryPressure", "unknown");
            if (universalAvailableBytes > 0L) {
                info("Universal launch policy memory: pressure=" + universalPressure
                        + " | available=" + (universalAvailableBytes / (1024L * 1024L)) + " MiB"
                        + " | safety floor=" + (universalFloorBytes / (1024L * 1024L)) + " MiB"
                        + " | host reserve=" + (universalReserveBytes / (1024L * 1024L)) + " MiB"
                        + " | inference budget=" + (universalBudgetBytes / (1024L * 1024L)) + " MiB");
            }
            long observedLaunchAvailableBytes = parseLongOrZero(health.diagnostics().getOrDefault("observedLaunchMemoryAvailableBytes", "0"));
            long observedLaunchReserveBytes = parseLongOrZero(health.diagnostics().getOrDefault("observedLaunchMemoryReservedHostBytes", "0"));
            long observedLaunchFloorBytes = parseLongOrZero(health.diagnostics().getOrDefault("observedLaunchMemorySafetyFloorBytes", "0"));
            long observedLaunchBudgetBytes = parseLongOrZero(health.diagnostics().getOrDefault("observedLaunchMemoryBudgetBytes", "0"));
            String observedLaunchPressure = health.diagnostics().getOrDefault("observedLaunchMemoryPressure", "unknown");
            String observedLaunchProfile = health.diagnostics().getOrDefault("observedLaunchMemoryProfile", "");
            if (observedLaunchAvailableBytes > 0L) {
                info("Universal observed launch memory: pressure=" + observedLaunchPressure
                        + " | available=" + (observedLaunchAvailableBytes / (1024L * 1024L)) + " MiB"
                        + " | safety floor=" + (observedLaunchFloorBytes / (1024L * 1024L)) + " MiB"
                        + " | host reserve=" + (observedLaunchReserveBytes / (1024L * 1024L)) + " MiB"
                        + " | discretionary budget=" + (observedLaunchBudgetBytes / (1024L * 1024L)) + " MiB"
                        + (observedLaunchProfile.isBlank() ? "" : " | adapter profile=" + observedLaunchProfile));
            }
            long runtimeAvailableBytes = parseLongOrZero(health.diagnostics().getOrDefault("runtimeMemoryAvailableBytes", "0"));
            long runtimeReserveBytes = parseLongOrZero(health.diagnostics().getOrDefault("runtimeMemoryReservedHostBytes", "0"));
            long runtimeFloorBytes = parseLongOrZero(health.diagnostics().getOrDefault("runtimeMemorySafetyFloorBytes", "0"));
            long runtimeAdditionalBytes = parseLongOrZero(health.diagnostics().getOrDefault("runtimeMemoryAdditionalBudgetBytes", "0"));
            String runtimePressure = health.diagnostics().getOrDefault("runtimeMemoryPressure", "unknown");
            String runtimeRawPressure = health.diagnostics().getOrDefault("runtimeMemoryRawPressure", runtimePressure);
            String runtimeTrend = health.diagnostics().getOrDefault("runtimeMemoryTrend", "unknown");
            String runtimeRecoverySamples = health.diagnostics().getOrDefault("runtimeMemoryRecoverySamples", "0");
            String automaticSamples = health.diagnostics().getOrDefault("runtimeMemoryAutomaticSamples", "0");
            long automaticSampleAgeMs = parseLongOrZero(health.diagnostics().getOrDefault("runtimeMemoryAutomaticSampleAgeMs", "0"));
            if (runtimeAvailableBytes > 0L) {
                info("Universal live memory: pressure=" + runtimePressure
                        + (runtimeRawPressure.equals(runtimePressure) ? "" : " | raw=" + runtimeRawPressure)
                        + " | trend=" + runtimeTrend
                        + ("0".equals(runtimeRecoverySamples) ? "" : " | recovery=" + runtimeRecoverySamples + "/3")
                        + ("0".equals(automaticSamples) ? "" : " | samples=" + automaticSamples + " | sample_age=" + automaticSampleAgeMs + "ms")
                        + " | available=" + (runtimeAvailableBytes / (1024L * 1024L)) + " MiB"
                        + " | safety floor=" + (runtimeFloorBytes / (1024L * 1024L)) + " MiB"
                        + " | host reserve=" + (runtimeReserveBytes / (1024L * 1024L)) + " MiB"
                        + " | additional budget=" + (runtimeAdditionalBytes / (1024L * 1024L)) + " MiB");
            }
            String[] optionalKinds = {"cache_growth", "speculation", "expert_cache", "tensor_residency", "conversion_workspace"};
            java.util.List<String> launchOptional = new java.util.ArrayList<>();
            java.util.List<String> liveOptional = new java.util.ArrayList<>();
            for (String kind : optionalKinds) {
                if (Boolean.parseBoolean(health.diagnostics().getOrDefault("optionalLaunch." + kind + ".allowed", "false"))) {
                    long ceiling = parseLongOrZero(health.diagnostics().getOrDefault("optionalLaunch." + kind + ".ceilingBytes", "0"));
                    launchOptional.add(kind + "<=" + (ceiling / (1024L * 1024L)) + "MiB");
                }
                if (Boolean.parseBoolean(health.diagnostics().getOrDefault("optionalLive." + kind + ".allowed", "false"))) {
                    long ceiling = parseLongOrZero(health.diagnostics().getOrDefault("optionalLive." + kind + ".ceilingBytes", "0"));
                    liveOptional.add(kind + "<=" + (ceiling / (1024L * 1024L)) + "MiB");
                }
            }
            info("Universal optional resources: launch=" + (launchOptional.isEmpty() ? "none" : String.join(",", launchOptional))
                    + " | live=" + (liveOptional.isEmpty() ? "none" : String.join(",", liveOptional)));
        }
        int dynamicTools = com.spirit.koil.api.model.tool.DynamicMcpToolRegistry.modelTools().size();
        int registeredTools = LocalModelToolCatalog.allRegisteredTools().size();
        int staticTools = Math.max(0, registeredTools - dynamicTools);
        info("Tools: " + registeredTools + " registered | static=" + staticTools
                + " | dynamic MCP=" + dynamicTools
                + " | internet=" + com.spirit.koil.api.model.tool.InternetResearchModelToolRegistry.modelTools().size()
                + " | dataset=" + com.spirit.koil.api.model.tool.DatasetIntelligenceModelToolRegistry.modelTools().size()
                + " | browser=" + com.spirit.koil.api.model.tool.BrowserIntelligenceModelToolRegistry.modelTools().size()
                + " | content=" + com.spirit.koil.api.model.tool.ContentIntelligenceModelToolRegistry.modelTools().size()
                + " | MCP catalogue=" + com.spirit.koil.api.model.tool.McpCatalogueModelToolRegistry.modelTools().size());
        if (dynamicTools == 0) {
            info("Dynamic MCP: no external runtime tools are attached; built-in/static tool families remain available.");
        }
        if ("llama_cpp".equals(LocalModelService.selectedExecutionAdapterId())) {
            LlamaCppComputeSettings activeCompute = LocalModelService.activeLlamaCppComputeSettings();
            if (activeCompute != null) {
                info(computeStatusLine(activeCompute, health) + " | activation: in progress");
            } else {
                info(computeStatusLine(LocalModelService.llamaCppComputeSettings(), health));
            }
            String placement = health.diagnostics().getOrDefault("actualComputePlacement", "");
            String layers = health.diagnostics().getOrDefault("actualGpuLayers", "");
            if (!placement.isBlank() && !"pending".equals(placement)) {
                info("Compute runtime: " + placement + (layers.isBlank() ? "" : " | GPU layers: " + layers));
            }
            String safety = health.diagnostics().getOrDefault("computeSafety", "");
            if (!safety.isBlank() && !"not_checked".equalsIgnoreCase(safety)) {
                String safetyDetail = health.diagnostics().getOrDefault("computeSafetyDetail", "");
                info("Compute safety: " + safety
                        + (safetyDetail.isBlank() ? "" : " | " + safetyDetail));
            }
            String optionalCacheAllowed = health.diagnostics().getOrDefault("optionalCacheGrowthAllowed", "");
            String optionalCacheCeiling = health.diagnostics().getOrDefault("optionalCacheGrowthCeilingBytes", "0");
            String warmupState = health.diagnostics().getOrDefault("warmupState", "");
            if (!optionalCacheAllowed.isBlank() || !warmupState.isBlank()) {
                long cacheCeilingBytes = parseLongOrZero(optionalCacheCeiling);
                String retention = health.diagnostics().getOrDefault("optionalStateRetention", "");
                String retentionDetail = health.diagnostics().getOrDefault("optionalStateRetentionDetail", "");
                info("Native cache policy: optional_growth="
                        + (Boolean.parseBoolean(optionalCacheAllowed)
                        ? "admitted<=" + (cacheCeilingBytes / (1024L * 1024L)) + "MiB"
                        : "denied")
                        + (warmupState.isBlank() ? "" : " | startup_warmup=" + warmupState)
                        + (retention.isBlank() ? "" : " | optional_state=" + retention)
                        + (retentionDetail.isBlank() ? "" : " (" + retentionDetail + ")"));
            }
            String statePrecisionK = health.diagnostics().getOrDefault("statePrecisionK", "");
            String statePrecisionV = health.diagnostics().getOrDefault("statePrecisionV", "");
            if (!statePrecisionK.isBlank() || !statePrecisionV.isBlank()) {
                boolean adaptivePrecision = Boolean.parseBoolean(health.diagnostics().getOrDefault("statePrecisionAdaptive", "false"));
                String statePrecisionReason = health.diagnostics().getOrDefault("statePrecisionReason", "");
                String statePrecisionRelative = health.diagnostics().getOrDefault("statePrecisionRelativeBytes", "");
                long estimatedFullStateBytes = parseLongOrZero(health.diagnostics().get("statePrecisionEstimatedFullBytes"));
                long estimatedSavingsBytes = parseLongOrZero(health.diagnostics().get("statePrecisionEstimatedSavingsBytes"));
                String estimateConfidence = health.diagnostics().getOrDefault("statePrecisionEstimateConfidence", "unknown");
                String statePrecisionBackend = health.diagnostics().getOrDefault("statePrecisionBackend", "unknown");
                String statePrecisionBackendSource = health.diagnostics().getOrDefault("statePrecisionBackendSource", "unproven");
                String statePrecisionSource = health.diagnostics().getOrDefault("statePrecisionSource", "");
                info("Native state precision: K=" + (statePrecisionK.isBlank() ? "unknown" : statePrecisionK)
                        + " | V=" + (statePrecisionV.isBlank() ? "unknown" : statePrecisionV)
                        + " | policy=" + (adaptivePrecision ? "adaptive" : "full_precision")
                        + ("unknown".equals(statePrecisionBackend) ? "" : " | backend=" + statePrecisionBackend)
                        + ("unproven".equals(statePrecisionBackendSource) ? "" : " | backend_source=" + statePrecisionBackendSource)
                        + (statePrecisionSource.isBlank() ? "" : " | source=" + statePrecisionSource)
                        + (statePrecisionRelative.isBlank() ? "" : " | relative KV bytes~" + statePrecisionRelative)
                        + (estimatedFullStateBytes <= 0L ? "" : " | estimated KV=" + (estimatedFullStateBytes / (1024L * 1024L)) + " MiB")
                        + (estimatedSavingsBytes <= 0L ? "" : " | projected K savings=" + (estimatedSavingsBytes / (1024L * 1024L)) + " MiB")
                        + ("unknown".equals(estimateConfidence) ? "" : " | estimate=" + estimateConfidence)
                        + (statePrecisionReason.isBlank() ? "" : " | " + statePrecisionReason));
            }
            String pressureResponse = health.diagnostics().getOrDefault("residentPressureResponse", "");
            if (!pressureResponse.isBlank() && !"normal".equalsIgnoreCase(pressureResponse)) {
                String backgroundWork = health.diagnostics().getOrDefault("residentPressureBackgroundWork", "unknown");
                String modelDegradation = health.diagnostics().getOrDefault("residentPressureModelDegradation", "unknown");
                String userInference = health.diagnostics().getOrDefault("residentPressureUserInference", "unknown");
                String recoverySamples = health.diagnostics().getOrDefault("residentPressureRecoverySamples", "0");
                String responseReason = health.diagnostics().getOrDefault("residentPressureResponseReason", "");
                info("Resident pressure response: " + pressureResponse
                        + " | background_model_work=" + backgroundWork
                        + " | model_degradation=" + modelDegradation
                        + " | user_inference=" + userInference
                        + ("0".equals(recoverySamples) ? "" : " | recovery=" + recoverySamples + "/3")
                        + (responseReason.isBlank() ? "" : " | " + responseReason));
            }
            String startupMemoryProfile = health.diagnostics().getOrDefault("startupMemoryProfile", "configured");
            String activeContext = health.diagnostics().getOrDefault("activeContextTokens", "");
            String configuredContext = health.diagnostics().getOrDefault("configuredContextTokens", "");
            boolean startupRetry = Boolean.parseBoolean(health.diagnostics().getOrDefault("startupRecoveryAttempted", "false"));
            if (!"configured".equalsIgnoreCase(startupMemoryProfile) || startupRetry) {
                String startupAvailableMiB = health.diagnostics().getOrDefault("startupAvailableMiB", "");
                String startupModelMiB = health.diagnostics().getOrDefault("startupModelMiB", "");
                info("Startup memory: " + startupMemoryProfile
                        + (activeContext.isBlank() ? "" : " | active context=" + activeContext)
                        + (configuredContext.isBlank() ? "" : "/" + configuredContext)
                        + (startupAvailableMiB.isBlank() || "0".equals(startupAvailableMiB) ? "" : " | available=" + startupAvailableMiB + " MiB")
                        + (startupModelMiB.isBlank() || "0".equals(startupModelMiB) ? "" : " | model=" + startupModelMiB + " MiB")
                        + (startupRetry ? " | recovered after signal exit" : ""));
            }
            int learnedContextCeiling = (int) parseLongOrZero(
                    health.diagnostics().getOrDefault("learnedResidentContextCeilingTokens", "0"));
            String learnedContextReason = health.diagnostics().getOrDefault("learnedResidentContextReason", "");
            if (learnedContextCeiling > 0) {
                info("Resident memory learning: next launch context<=" + learnedContextCeiling
                        + (learnedContextReason.isBlank() ? "" : " | " + learnedContextReason));
            }
            String pressureAttribution = health.diagnostics().getOrDefault("residentPressureAttribution", "unknown");
            String pressureAttributionDetail = health.diagnostics().getOrDefault("residentPressureAttributionDetail", "");
            if (!"unknown".equalsIgnoreCase(pressureAttribution)) {
                info("Resident pressure attribution: " + pressureAttribution
                        + (pressureAttributionDetail.isBlank() ? "" : " | " + pressureAttributionDetail));
            }
            String stability = health.diagnostics().getOrDefault("computeStability", "");
            if (!stability.isBlank()) {
                info("Compute stability: " + stability);
            }
            String integrity = health.diagnostics().getOrDefault("computeIntegrity", "");
            if (!integrity.isBlank() && !"not_checked".equalsIgnoreCase(integrity)) {
                String integrityDetail = health.diagnostics().getOrDefault("computeIntegrityDetail", "");
                info("Compute integrity: " + integrity
                        + (integrityDetail.isBlank() || "healthy".equalsIgnoreCase(integrityDetail)
                        ? "" : " | " + integrityDetail));
            }
            LlamaCppComputeSettings visibleCompute = activeCompute == null
                    ? LocalModelService.llamaCppComputeSettings()
                    : activeCompute;
            if (visibleCompute.mode() == LlamaCppComputeMode.MAX) {
                String resolved = health.diagnostics().getOrDefault("resolvedComputeDevice", "none");
                String profile = health.diagnostics().getOrDefault("maxProfile", "safe fit fallback");
                boolean tuned = Boolean.parseBoolean(health.diagnostics().getOrDefault("maxProfileTuned", "false"));
                String profileSource = health.diagnostics().getOrDefault("maxProfileSource", "none");
                info("Max runtime: " + (tuned ? "tuned" : "fallback")
                        + " | accelerator=" + resolved
                        + " | profile=" + profile
                        + (profileSource.isBlank() || "none".equals(profileSource) ? "" : " | source=" + profileSource));
                String tuning = LocalModelService.llamaCppMaxTuningStatus();
                if (!"idle".equalsIgnoreCase(tuning) && !tuning.isBlank()) {
                    info("Max tuner: " + tuning);
                }
            }
        }
        info(codeIntelligenceStatus());
        info(knowledgeStatus());
        ModelInstallationSnapshot installation = LocalModelInstallationService.instance().snapshot();
        if (installation.state().active()) {
            String progress = installation.totalBytes() > 0L
                    ? Math.round(installation.progress() * 100.0D) + "%"
                    : "working";
            info("Install: " + installation.state().name().toLowerCase(java.util.Locale.ROOT)
                    + " | " + progress
                    + (installation.detail().isBlank() ? "" : " | " + installation.detail())
                    + (installation.currentFile().isBlank() ? "" : " | " + installation.currentFile()));
        } else if (installation.state() == ModelInstallationState.FAILED
                || installation.state() == ModelInstallationState.CANCELLED) {
            warning("Last install: " + installation.state().name().toLowerCase(java.util.Locale.ROOT)
                    + " | " + installation.detail());
        }
        info("Prompt contract: " + LocalModelSystemPrompt.load().length()
                + " characters | llama.cpp prefix caching: enabled");
        if (!health.detail().isBlank()) {
            info("Runtime: " + health.detail());
        }
    }

    /** Compact user-facing diagnostic; detailed MCP metadata remains in the debug provenance surface. */
    public static String codeIntelligenceStatus() {
        var health = CodeIntelligenceService.instance().health();
        String state = health.state().name().toLowerCase(java.util.Locale.ROOT);
        return "Code intelligence: " + state + (health.detail().isBlank() ? "" : " | " + health.detail());
    }

    /** Compact retrieval health without forcing metadata/native runtime initialization. */
    public static String knowledgeStatus() {
        return KoilKnowledgeRuntime.status();
    }

    private static void showQueue() {
        List<LocalModelService.QueuedPrompt> queued = LocalModelService.queuedPrompts();
        LocalModelControlChatFeedback.header("Local Model Queue");
        if (queued.isEmpty()) {
            info("No editable queued messages.");
            return;
        }
        int position = 1;
        for (LocalModelService.QueuedPrompt prompt : queued) {
            info(position++ + ". " + prompt.mode() + " | " + prompt.requestId()
                    + " | rev " + prompt.revision() + " | " + abbreviate(prompt.prompt(), 160));
        }
    }

    private static void editQueuedPrompt(String requestId, String revision, String prompt) {
        try {
            boolean updated = LocalModelService.editQueuedPrompt(
                    java.util.UUID.fromString(requestId), Long.parseLong(revision), prompt
            );
            if (updated) {
                chat("Queued model message updated in place.");
            } else {
                warning("Queued message changed, started, or no longer exists; reopen the queue and retry.");
            }
        } catch (RuntimeException invalid) {
            warning("Invalid queued request ID or revision.");
        }
    }

    private static String abbreviate(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1) + "…";
    }

    private static void showSelectedModel() {
        LocalModelCatalogEntry selected = LocalModelCatalog.find(LocalModelService.selectedCatalogId()).orElse(null);
        if (selected == null) {
            warning("The selected model is not a compact catalog entry.");
            return;
        }
        LocalModelControlChatFeedback.header(selected.displayName());
        info(selected.complexReasoningLabel()
                + " | " + selected.capabilityLabel()
                + " | " + bytes(selected.downloadBytes())
                + " | " + selected.quantization());
        info("Parameters: " + selected.parameterCount()
                + " | Relative estimate is catalog guidance, not benchmark accuracy.");
        info("Context: " + selected.contextTokens() + " tokens | License: " + selected.license());
        info("Memory guidance: " + bytes(selected.estimatedMinimumMemoryBytes()) + " minimum | "
                + bytes(selected.estimatedRecommendedMemoryBytes()) + " recommended");
    }

    private static void showCatalog(int requestedPage, String commandRoot) {
        LocalModelCatalogChatState.show(LocalModelCatalog.generationEntries(), requestedPage, "Local Model Catalog", "");
    }

    private static void refreshModelCatalog() {
        info("Refreshing Hugging Face GGUF model discovery.");
        LocalModelCatalog.refreshRemote(true).whenComplete((result, failure) -> onClient(() -> {
            if (failure != null || result == null) {
                error("Model catalog refresh failed: " + (failure == null ? "unknown result" : message(failure)));
                return;
            }
            success("Model catalog refreshed | " + result.candidatesSeen() + " candidates | "
                    + result.builtInModelsPromoted() + " existing models made runnable | "
                    + result.newModelsAdded() + " new runnable models discovered.");
        }));
    }

    private static void searchModelCatalog(String query, int requestedPage, boolean searchRemote) {
        if (query == null || query.isBlank()) {
            warning("Usage: /model catalog search <model or repository>");
            return;
        }
        String cleanQuery = query.replace('\n', ' ').replace('\r', ' ').strip();
        List<LocalModelCatalogEntry> initialMatches = LocalModelCatalogView.search(LocalModelCatalog.generationEntries(), cleanQuery);
        LocalModelCatalogChatState.show(initialMatches, requestedPage,
                "Catalog Search: " + cleanQuery, "Searching Hugging Face + Hugging Bay...");
        if (!searchRemote) {
            if (initialMatches.isEmpty()) {
                LocalModelCatalogChatState.detail("No current Koil catalog models matched this search.");
            }
            return;
        }
        LocalModelCatalog.searchRemote(cleanQuery).whenComplete((result, failure) -> onClient(() -> {
            if (failure != null || result == null) {
                LocalModelCatalogChatState.detail("Model catalog search failed: "
                        + (failure == null ? "unknown result" : message(failure)));
                return;
            }
            if (result.failed()) {
                LocalModelCatalogChatState.detail(result.detail());
                return;
            }
            List<LocalModelCatalogEntry> matches = LocalModelCatalogView.search(LocalModelCatalog.generationEntries(), cleanQuery);
            if (!matches.isEmpty()) {
                LocalModelCatalogChatState.show(matches, requestedPage, "Catalog Search: " + cleanQuery,
                        result.detail());
            } else if (matches.isEmpty() && result.candidatesSeen() > 0) {
                LocalModelCatalogChatState.detail("Remote catalogs found candidates, but none mapped to a safe runnable Koil entry.");
            } else if (matches.isEmpty()) {
                LocalModelCatalogChatState.detail("No Hugging Face, Hugging Bay, or current Koil models matched this search.");
            }
        }));
    }

    private static void showInstalled() {
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        var entries = installer.installedEntries();
        if (entries.isEmpty()) {
            warning("No catalog models are installed. Use /model list, then /model install <catalog_id>.");
            return;
        }
        LocalModelControlChatFeedback.header("Installed Local Models");
        String selectedId = LocalModelService.selectedCatalogId();
        entries.forEach(entry -> {
            boolean selected = entry.id().equals(selectedId);
            LocalModelControlChatFeedback.add(
                    Text.literal(entry.displayName()).formatted(Formatting.WHITE)
                            .append(Text.literal(selected ? "  |  Selected" : "  |  Installed")
                                    .formatted(selected ? Formatting.GREEN : Formatting.DARK_GRAY)),
                    selected ? LocalModelControlChatFeedback.Level.SUCCESS : LocalModelControlChatFeedback.Level.INFO
            );
            info(entry.id() + " | " + bytes(installer.installedBytes(entry)) + " | " + entry.capabilityLabel());
        });
    }

    private static void showComputeStatus() {
        LlamaCppComputeSettings active = LocalModelService.activeLlamaCppComputeSettings();
        LlamaCppComputeSettings settings = active == null ? LocalModelService.llamaCppComputeSettings() : active;
        LocalModelControlChatFeedback.header("llama.cpp Compute");
        info(computeStatusLine(settings) + (active == null ? "" : " | activation: in progress"));
        switch (settings.mode()) {
            case CPU -> info("CPU mode disables accelerator devices and keeps model layers on CPU.");
            case MAX -> {
                var tuned = LocalModelService.latestLlamaCppMaxTuning();
                if (tuned.isPresent()) {
                    var profile = tuned.get();
                    info("A saved MAX calibration exists for this model. Hardware/runtime identity is revalidated when MAX launches. Last winner: " + profile.profile().summary());
                    info("Measured winner: prompt " + String.format(java.util.Locale.ROOT, "%.2f", profile.benchmark().promptTokensPerSecond())
                            + " t/s | generation " + String.format(java.util.Locale.ROOT, "%.2f", profile.benchmark().generationTokensPerSecond())
                            + " t/s | TTFT " + String.format(java.util.Locale.ROOT, "%.0f", profile.benchmark().timeToFirstTokenMillis()) + " ms.");
                    info("Run /model compute tune to recalibrate after hardware, model, or llama.cpp runtime changes.");
                } else {
                    info("Max has no measured profile yet and uses llama.cpp's bounded fit engine with Koil's accelerator-integrity check. Run /model compute tune to benchmark CPU, safe hybrid splits, and GPU placement and persist the fastest valid profile.");
                }
            }
            case GPU -> info("GPU mode asks llama.cpp's fit engine for the fastest accelerator placement that preserves Koil's memory headroom instead of forcing every layer onto the GPU.");
            case HYBRID -> info("Hybrid mode requests " + settings.hybridGpuLayers()
                    + " model layers on the GPU, keeps KV cache and host tensor operations on CPU, and preflights the exact split against current memory headroom before launch.");
        }
        info("Changes are persistent per Minecraft instance and reload the active llama.cpp runtime.");
    }

    private static long parseLongOrZero(String value) {
        try { return Math.max(0L, Long.parseLong(value == null ? "" : value.strip())); }
        catch (Exception ignored) { return 0L; }
    }

    private static String computeStatusLine(LlamaCppComputeSettings settings) {
        return computeStatusLine(settings, null);
    }

    private static String computeStatusLine(LlamaCppComputeSettings settings, ModelHealthSnapshot health) {
        String detail = settings.mode().displayName();
        if (settings.mode() == LlamaCppComputeMode.HYBRID) {
            detail += " | GPU layers: " + settings.hybridGpuLayers();
        } else if (settings.mode() == LlamaCppComputeMode.MAX) {
            if (health == null) {
                detail += " | profile: resolved at launch";
            } else {
                String source = health.diagnostics().getOrDefault("maxProfileSource", "none");
                boolean tuned = Boolean.parseBoolean(health.diagnostics().getOrDefault("maxProfileTuned", "false"));
                String label = switch (source) {
                    case "universal_plan" -> "universal calibration";
                    case "native_compat" -> "native compatibility calibration";
                    case "native_store" -> "native calibration";
                    case "benchmark_candidate" -> "benchmark candidate";
                    case "fit_fallback" -> "safe fit fallback";
                    default -> tuned ? "tuned calibration" : "safe fit fallback";
                };
                detail += " | profile: " + label;
            }
        } else if (settings.mode() == LlamaCppComputeMode.GPU) {
            detail += " | GPU layers: safe fitted maximum";
        }
        return "Compute: " + detail + " | Device: " + settings.deviceLabel();
    }

    private static void setComputeMode(LlamaCppComputeMode mode, Integer hybridGpuLayers) {
        LlamaCppComputeSettings current = LocalModelService.llamaCppComputeSettings();
        LlamaCppComputeSettings next = current.withMode(mode);
        if (hybridGpuLayers != null) {
            next = next.withHybridGpuLayers(hybridGpuLayers);
        }
        LlamaCppComputeSettings applied = next;
        LlamaCppComputeSettings active = LocalModelService.activeLlamaCppComputeSettings();
        if (active != null) {
            if (active.equals(applied)) {
                ModelInstallationSnapshot snapshot = LocalModelInstallationService.instance().snapshot();
                info("That compute activation is already in progress. The original request will continue automatically."
                        + (snapshot.detail().isBlank() ? "" : " Current stage: " + snapshot.detail()));
            } else {
                warning("Another compute activation is already in progress: " + computeStatusLine(active)
                        + ". Use /model status for progress or /model cancel to stop it before changing modes.");
            }
            return;
        }
        info("Applying " + computeStatusLine(applied) + ".");
        if (applied.mode() != LlamaCppComputeMode.CPU && isIntelMac()) {
            info("Intel macOS: Koil will use a local Metal-enabled llama.cpp "
                    + com.spirit.koil.api.model.install.LlamaCppMetalRuntimeInstaller.VERSION
                    + " runtime. The first accelerator activation automatically provisions Koil's pinned CMake toolchain "
                    + "and builds llama.cpp from the pinned upstream source.");
        }
        LocalModelService.configureLlamaCppCompute(applied).whenComplete((ignored, failure) -> onClient(() -> {
            if (failure != null) {
                error("Failed to apply llama.cpp compute mode: " + message(failure));
                return;
            }
            ModelHealthSnapshot health = LocalModelService.health();
            String placement = health.diagnostics().getOrDefault("actualComputePlacement", "");
            String layers = health.diagnostics().getOrDefault("actualGpuLayers", "");
            String verified = placement.isBlank() || "pending".equalsIgnoreCase(placement)
                    ? ""
                    : " Verified runtime: " + placement
                    + (layers.isBlank() ? "." : " | GPU layers: " + layers + ".");
            success("llama.cpp compute mode saved: " + applied.mode().displayName()
                    + (applied.mode() == LlamaCppComputeMode.HYBRID
                    ? " with " + applied.hybridGpuLayers() + " requested GPU layers."
                    : ".")
                    + verified);
        }));
    }

    private static void tuneMaxCompute() {
        tuneMaxCompute(null);
    }

    private static void tuneMaxCompute(String contextTarget) {
        int requestedContextTokens;
        try {
            requestedContextTokens = parseMaxTuneContext(contextTarget);
        } catch (IllegalArgumentException failure) {
            error(failure.getMessage());
            return;
        }
        if (LocalModelService.activeLlamaCppMaxTuning() != null) {
            info("MAX autotuning is already running: " + LocalModelService.llamaCppMaxTuningStatus());
            return;
        }
        String contextDetail = requestedContextTokens < 0
                ? " Target context: highest safely launchable tier; Koil will discover it using the normal startup memory policy before benchmarking."
                : requestedContextTokens > 0
                ? " Target context: " + requestedContextTokens + " tokens; normal startup memory safety remains enforced."
                : "";
        info("Starting MAX autotune. Koil will benchmark real CPU, GPU, and hybrid placements plus thread/poll/batch refinements, then persist and activate the fastest measured profile." + contextDetail);
        java.util.concurrent.CompletableFuture<LlamaCppMaxTuningResult> tuning = requestedContextTokens < 0
                ? LocalModelService.tuneLlamaCppMaxPerformanceSafestContext()
                : requestedContextTokens > 0
                ? LocalModelService.tuneLlamaCppMaxPerformance(requestedContextTokens)
                : LocalModelService.tuneLlamaCppMaxPerformance();
        tuning.whenComplete((result, failure) -> onClient(() -> {
            if (failure != null) {
                error("MAX autotune failed: " + message(failure));
                return;
            }
            if (result == null || result.winner() == null) {
                error("MAX autotune finished without a valid winner.");
                return;
            }
            LlamaCppMaxTuningResult.CandidateResult measuredWinner = result.candidates().stream()
                    .filter(candidate -> candidate.profile().equals(result.winner().profile()))
                    .max(java.util.Comparator.comparingDouble(LlamaCppMaxTuningResult.CandidateResult::score))
                    .orElse(null);
            String metrics = measuredWinner == null ? ""
                    : " | prompt=" + String.format(java.util.Locale.ROOT, "%.2f", measuredWinner.benchmark().promptTokensPerSecond()) + " t/s"
                    + " | generation=" + String.format(java.util.Locale.ROOT, "%.2f", measuredWinner.benchmark().generationTokensPerSecond()) + " t/s"
                    + " | TTFT=" + String.format(java.util.Locale.ROOT, "%.0f", measuredWinner.benchmark().timeToFirstTokenMillis()) + " ms";
            success("MAX autotune complete: " + result.winner().profile().summary()
                    + metrics
                    + " | candidates=" + result.candidates().size()
                    + " | rejected=" + result.failedCandidates());
        }));
    }

    private static int parseMaxTuneContext(String value) {
        if (value == null || value.isBlank()) return 0;
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT).replace("_", "");
        if ("safe".equals(normalized) || "highest".equals(normalized)) return -1;

        // "128k" is a model-context tier, not necessarily the binary value 131072.
        // Many model configs (including LFM2) advertise an exact 128000-token ceiling.
        // Preserve the traditional power-of-two shorthand for the smaller tiers while
        // resolving the 128K tier to the common exact model ceiling. Users may still
        // request 131072 explicitly for models that genuinely advertise that limit.
        if ("128k".equals(normalized)) return 128000;

        int multiplier = 1;
        if (normalized.endsWith("k")) {
            multiplier = 1024;
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        final int parsed;
        try {
            parsed = Math.multiplyExact(Integer.parseInt(normalized), multiplier);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Invalid MAX tune context. Use safe, 2k, 4k, 8k, 16k, 32k, 64k, or 128k.");
        }
        return switch (parsed) {
            case 2048, 4096, 8192, 16384, 32768, 65536, 128000, 131072 -> parsed;
            default -> throw new IllegalArgumentException(
                    "MAX tune context must be safe, 2k, 4k, 8k, 16k, 32k, 64k, 128k, or exact 131072.");
        };
    }

    private static boolean isIntelMac() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac")
                && (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64"));
    }

    private static void showHelp() {
        chat("/model status | info | tools verify | diagnostics | rescan");
        chat("/model probe <short|structured|repetition|exact|code|long> runs a fixed validation prompt");
        chat("/model start | stop | restart | cancel");
        chat("/model compute [cpu|max [tune [safe|2k|4k|8k|16k|32k|64k|128k]]|tune|gpu|hybrid [gpu_layers]]");
        chat("/model list [page] | catalog refresh | catalog search <query> | installed | logs | prompt");
        chat("/model install <id> | install-url <gguf-url> | use <id> | switch <id> | uninstall <id>");
        chat("/model reliability [reset [catalog-id]]");
        chat("/model voice [true|false|list|set <voice_id>]");
        chat("/model reset [general|automation|all]");
        chat("Use /model list [page] to browse available local models.");
    }

    private static CompletableFuture<Suggestions> suggestVoices(SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(
                ModelVoiceService.voices().stream().map(ModelVoiceDefinition::id).sorted().toList(),
                builder
        );
    }

    private static void showVoiceStatus() {
        var settings = ModelVoiceService.settings();
        LocalModelControlChatFeedback.header("Local Model Voice");
        info("Voice: " + (settings.enabled() ? "On" : "Off")
                + " | " + ModelVoiceService.selectedVoiceLabel()
                + " | id " + settings.voiceId());
        if (settings.enabled()) {
            info("Koil prepares short generated phrases ahead and plays them in order without overlap.");
            ModelVoiceService.voices().stream()
                    .filter(voice -> voice.id().equals(settings.voiceId()) && voice.remote())
                    .findFirst()
                    .ifPresent(voice -> warning("This selected voice is remote. Short generated phrases are sent to " + voice.providerId() + " for speech."));
        }
    }

    private static void showVoices() {
        LocalModelControlChatFeedback.header("Available Model Voices");
        for (ModelVoiceDefinition voice : ModelVoiceService.voices()) {
            info(voice.id() + " | " + voice.displayName() + " | " + (voice.remote() ? "Remote" : "Local"));
        }
        info("Select one with /model voice set <voice_id>.");
    }

    private static void setVoiceEnabled(boolean enabled) {
        ModelVoiceService.setEnabled(enabled);
        success("Local model voice is " + (enabled ? "on" : "off") + ".");
        if (enabled) {
            showVoiceStatus();
        }
    }

    private static void setVoice(String voiceId) {
        if (!ModelVoiceService.setVoice(voiceId)) {
            error("Unknown or unavailable voice id '" + voiceId + "'. Use /model voice list.");
            return;
        }
        success("Local model voice set to " + ModelVoiceService.selectedVoiceLabel() + ".");
        showVoiceStatus();
    }

    private static void showPromptLocation() {
        LocalModelControlChatFeedback.header("Local Model System Prompt");
        info("Editable file: " + LocalModelSystemPrompt.PATH);
        info("Identity changes apply to the next model request. The Rich Chat and safety contracts are appended by Koil.");
    }

    private static void showReliability(String catalogId) {
        LocalModelCatalogEntry entry = LocalModelCatalog.find(catalogId).orElse(null);
        if (entry == null) {
            warning("No selected catalog model has reliability evidence.");
            return;
        }
        LocalModelReliabilityStore.Snapshot snapshot = LocalModelReliabilityStore.snapshot(entry.modelId());
        LocalModelControlChatFeedback.header("Model Runtime Reliability");
        info(entry.displayName() + " | Automation " + (snapshot.quarantined() ? "quarantined" : "available"));
        info("Runtime crashes: " + snapshot.crashCount()
                + " | major tool-protocol failures: " + snapshot.protocolFailureCount());
        if (!snapshot.lastCode().isBlank()) {
            info("Latest: " + snapshot.lastCode()
                    + (snapshot.lastDetail().isBlank() ? "" : " | " + abbreviate(snapshot.lastDetail(), 160)));
        }
    }

    private static void resetReliability(String catalogId) {
        LocalModelCatalogEntry entry = LocalModelCatalog.find(catalogId).orElse(null);
        if (entry == null) {
            warning("Unknown model catalog id '" + catalogId + "'.");
            return;
        }
        if (LocalModelReliabilityStore.reset(entry)) {
            success("Cleared recorded major-failure quarantine for " + entry.displayName() + ".");
        } else {
            info(entry.displayName() + " has no recorded reliability quarantine.");
        }
    }

    private static void installModel(String catalogId, boolean allowReplacement) {
        LocalModelCatalogEntry target = catalogEntry(catalogId);
        if (target == null) {
            return;
        }
        if (!target.runnable() && LocalModelCatalog.canResolveForInstall(target)) {
            info("Resolving a verified GGUF implementation for " + target.displayName() + " from Hugging Face.");
            LocalModelCatalog.resolveForInstall(target.id()).whenComplete((resolved, failure) -> onClient(() -> {
                if (failure != null || resolved == null || resolved.isEmpty() || !resolved.get().runnable()) {
                    error("No verified llama.cpp-compatible GGUF could be resolved for " + target.displayName()
                            + (failure == null ? "." : ": " + message(failure)));
                    return;
                }
                installModel(resolved.get().id(), allowReplacement);
            }));
            return;
        }
        if (!target.runnable()) {
            warning(target.displayName() + " does not currently have a compatible local runtime implementation.");
            return;
        }

        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        if (installer.snapshot().state().active()) {
            warning("A model install or uninstall operation is already active.");
            return;
        }
        if (installer.installed(target)) {
            useInstalledModel(target.id());
            return;
        }

        LocalModelInstallationService.StoragePlan plan = installer.storagePlan(target);
        if (plan.fits()) {
            success("Installing " + target.displayName() + " | awaiting confirmation.");
            info(target.displayName() + " needs " + bytes(plan.remainingDownloadBytes())
                    + " of downloads. A confirmation screen is opening now.");
            confirm(
                    "Install local model?",
                    "Download " + target.displayName() + " (" + bytes(plan.remainingDownloadBytes())
                            + " remaining)?\nKoil will verify every file and select it when complete.",
                    "Install",
                    () -> performInstall(target)
            );
            return;
        }

        LocalModelCatalogEntry selected = LocalModelCatalog.find(LocalModelService.selectedCatalogId()).orElse(null);
        long reclaimable = selected == null || selected.id().equals(target.id())
                ? 0L
                : installer.installedBytes(selected);
        boolean fitsAfterReplacement = allowReplacement
                && selected != null
                && reclaimable > 0L
                && plan.usableBytes() + reclaimable >= plan.requiredBytes();
        if (!fitsAfterReplacement) {
            error("Not enough storage for " + target.displayName() + ". Need " + bytes(plan.requiredBytes())
                    + " including safety headroom; " + bytes(plan.usableBytes()) + " is usable.");
            if (!allowReplacement) {
                warning("Use /model switch <catalog_id> to let Koil offer removal of the selected model when that would make enough room.");
            }
            return;
        }

        LocalModelCatalogEntry oldModel = selected;
        success("Installing " + target.displayName() + " | awaiting replacement confirmation.");
        info(target.displayName() + " needs more storage. A confirmation screen is opening with the exact replacement details.");
        confirm(
                "Replace selected model?",
                "Installing " + target.displayName() + " needs more room. Remove " + oldModel.displayName()
                        + " (" + bytes(reclaimable) + ") first?\nIf the new download fails, the old model stays removed.",
                "Remove & Install",
                () -> replaceInstalledModel(oldModel, target)
        );
    }

    /** Panel action; preserves the normal confirmation and verified-installation path. */
    public static void installFromCatalogPanel(String catalogId) {
        installModel(catalogId, false);
    }

    /** Panel action; selects only an already verified installation. */
    public static void useFromCatalogPanel(String catalogId) {
        useInstalledModel(catalogId);
    }

    /** Panel action; preserves the normal destructive-operation confirmation. */
    public static void uninstallFromCatalogPanel(String catalogId) {
        uninstallModel(catalogId);
    }

    private static void installDirectUrl(String url) {
        if (url == null || url.isBlank()) {
            warning("Usage: /model install-url <Hugging Face or Hugging Bay GGUF file URL>");
            return;
        }
        info("Resolving the exact GGUF file and its verification metadata.");
        LocalModelCatalog.registerDirectFile(url).whenComplete((result, failure) -> onClient(() -> {
            if (failure != null || result == null || !result.resolved() || result.entry() == null) {
                error("Direct model link could not be resolved: "
                        + (failure != null ? message(failure)
                        : result == null ? "no resolution result" : result.detail()));
                return;
            }
            success(result.entry().displayName() + " was added to the local catalog from the exact GGUF link.");
            installModel(result.entry().id(), false);
        }));
    }

    private static void performInstall(LocalModelCatalogEntry target) {
        success("Installing " + target.displayName() + " | started.");
        info("Use /model status for the current stage.");
        LocalModelInstallationService.instance().installWithResult(target.id())
                .whenComplete((snapshot, failure) -> onClient(() -> {
                    if (failure != null) {
                        error("Model installation failed: " + message(failure));
                        return;
                    }
                    finishInstall(target, snapshot);
                }));
    }

    private static void replaceInstalledModel(
            LocalModelCatalogEntry oldModel,
            LocalModelCatalogEntry target
    ) {
        chat("Stopping the current runtime before replacing " + oldModel.displayName() + ".");
        LocalModelService.stopRuntime().whenComplete((ignored, stopFailure) -> {
            if (stopFailure != null) {
                onClient(() -> error("Could not stop the current runtime: " + message(stopFailure)));
                return;
            }
            LocalModelInstallationService.instance().uninstall(oldModel.id())
                    .whenComplete((uninstall, uninstallFailure) -> {
                        if (uninstallFailure != null || uninstall == null || !uninstall.removed()) {
                            onClient(() -> error("Could not remove " + oldModel.displayName() + ": "
                                    + (uninstallFailure == null
                                    ? uninstall == null ? "unknown uninstall failure" : uninstall.detail()
                                    : message(uninstallFailure))));
                            return;
                        }
                        onClient(() -> {
                            chat(uninstall.detail() + " Starting " + target.displayName() + " installation.");
                            performInstall(target);
                        });
                    });
        });
    }

    private static void finishInstall(
            LocalModelCatalogEntry target,
            ModelInstallationSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.state() != ModelInstallationState.READY) {
            error("Model installation did not complete: "
                    + (snapshot == null ? "no installation result" : snapshot.detail()));
            return;
        }
        LocalModelService.reloadConfiguration().whenComplete((ignored, reloadFailure) -> onClient(() -> {
            if (reloadFailure != null) {
                error(target.displayName() + " installed, but activation failed: " + message(reloadFailure));
            } else {
                success(target.displayName() + " is installed and selected.");
            }
        }));
    }

    private static void useInstalledModel(String catalogId) {
        LocalModelCatalogEntry entry = catalogEntry(catalogId);
        if (entry == null) {
            return;
        }
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        if (!installer.installed(entry)) {
            warning(entry.displayName() + " is not installed. Use /model install " + entry.id() + ".");
            return;
        }
        if (!installer.selectInstalled(entry)) {
            error("Koil could not select " + entry.displayName() + " because its verified files are incomplete.");
            return;
        }
        LocalModelService.reloadConfiguration().whenComplete((ignored, failure) -> onClient(() -> {
            if (failure != null) {
                error("Model switch failed: " + message(failure));
            } else {
                success(entry.displayName() + " is now selected.");
            }
        }));
    }

    private static void uninstallModel(String catalogId) {
        LocalModelCatalogEntry entry = catalogEntry(catalogId);
        if (entry == null) {
            return;
        }
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        long installedBytes = installer.installedBytes(entry);
        if (installedBytes <= 0L) {
            warning(entry.displayName() + " is not installed.");
            return;
        }
        boolean selected = entry.id().equals(LocalModelService.selectedCatalogId());
        uninstall("Uninstalling " + entry.displayName() + " | awaiting confirmation.");
        confirm(
                "Uninstall local model?",
                "Permanently remove " + entry.displayName() + " (" + bytes(installedBytes) + ") from this Koil instance?"
                        + (selected ? " It is currently selected, so the runtime will stop." : ""),
                "Uninstall",
                () -> performUninstall(entry, selected)
        );
    }

    private static void performUninstall(LocalModelCatalogEntry entry, boolean selected) {
        uninstall("Uninstalling " + entry.displayName()
                + (selected ? " | stopping selected runtime." : " | started."));
        Runnable remove = () -> LocalModelInstallationService.instance().uninstall(entry.id())
                .whenComplete((result, failure) -> onClient(() -> {
                    if (failure != null) {
                        error("Model uninstall failed: " + message(failure));
                        return;
                    }
                    if (result == null || !result.removed()) {
                        error("Model uninstall failed: " + (result == null ? "no uninstall result" : result.detail()));
                        return;
                    }
                    if (selected) {
                        LocalModelService.reloadConfiguration().whenComplete((ignored, reloadFailure) -> onClient(() -> {
                            if (reloadFailure == null) {
                                uninstall(result.detail());
                            } else {
                                error(result.detail() + " Configuration reload failed: " + message(reloadFailure));
                            }
                        }));
                    } else {
                        uninstall(result.detail());
                    }
                }));
        if (!selected) {
            remove.run();
            return;
        }
        LocalModelService.stopRuntime().whenComplete((ignored, failure) -> {
            if (failure != null) {
                onClient(() -> error("Could not stop the selected model runtime: " + message(failure)));
            } else {
                remove.run();
            }
        });
    }

    private static LocalModelCatalogEntry catalogEntry(String catalogId) {
        LocalModelCatalogEntry entry = LocalModelCatalog.find(catalogId).orElse(null);
        if (entry == null) {
            error("Unknown model id '" + catalogId + "'. Use /model list for exact catalog ids.");
        }
        return entry;
    }

    private static void confirm(
            String title,
            String detail,
            String confirmLabel,
            Runnable confirmedAction
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        // ChatScreen closes itself after dispatching the Enter key. Opening a
        // screen synchronously here lets that close overwrite the confirmation,
        // making install/uninstall appear to do nothing.
        CompletableFuture.delayedExecutor(50L, TimeUnit.MILLISECONDS).execute(() ->
                client.execute(() -> {
                    Screen parent = client.currentScreen;
                    client.setScreen(new ConfirmScreen(confirmed -> {
                        client.setScreen(parent);
                        if (confirmed) {
                            confirmedAction.run();
                        }
                    }, Text.literal(title), Text.literal(detail), Text.literal(confirmLabel), ScreenTexts.CANCEL));
                })
        );
    }

    private static void startRuntime() {
        chat("Starting the selected local model runtime...");
        LocalModelService.startRuntime().whenComplete((health, failure) -> onClient(() -> {
            if (failure != null) {
                error("Local model start failed: " + message(failure));
                return;
            }
            chat("Local model runtime: " + health.state().name().toLowerCase(java.util.Locale.ROOT)
                    + (health.detail().isBlank() ? "" : " | " + health.detail()));
        }));
    }

    private static void stopRuntime() {
        chat("Stopping the local model runtime...");
        LocalModelService.stopRuntime().whenComplete((ignored, failure) -> onClient(() -> {
            if (failure != null) {
                error("Local model stop failed: " + message(failure));
            } else {
                success("Local model runtime stopped. It will remain stopped until the next start or model request.");
            }
        }));
    }

    private static void restartRuntime() {
        chat("Restarting the selected local model runtime...");
        LocalModelService.restartRuntime().whenComplete((health, failure) -> onClient(() -> {
            if (failure != null) {
                error("Local model restart failed: " + message(failure));
                return;
            }
            chat("Local model runtime restarted: " + health.state().name().toLowerCase(java.util.Locale.ROOT)
                    + (health.detail().isBlank() ? "" : " | " + health.detail()));
        }));
    }

    private static void showHardware(boolean refresh) {
        chat(refresh ? "Refreshing local model hardware preflight..." : "Loading local model hardware preflight...");
        LocalModelService.hardwareReport(refresh).whenComplete((report, failure) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return;
            }
            client.execute(() -> {
                if (failure != null) {
                    error("Hardware preflight failed: " + failure.getMessage());
                    return;
                }
                showHardwareReport(report);
            });
        });
    }

    private static void showHardwareReport(HardwareCapabilityReport report) {
        chat("Local model tier: " + report.tier().name().toLowerCase(java.util.Locale.ROOT)
                + " | OS: " + report.operatingSystem() + " | arch: " + report.architecture());
        chat("CPU: " + report.logicalCpuCount() + " logical | RAM: "
                + bytes(report.availableMemoryBytes()) + " available / " + bytes(report.installedMemoryBytes()));
        chat("Model directory: " + bytes(report.modelDirectoryBytes()) + " across "
                + report.modelFileCount() + " files | free storage: " + bytes(report.freeStorageBytes()));
        chat("GPU/VRAM/SIMD/drive type are reported as unknown until measured; no speed estimate was invented.");
        if (report.runtimeValidationRequired()) {
            warning("Runtime validation is still required before compatibility is considered proven.");
        }
    }

    private static String bytes(long value) {
        return BinaryStorageFormatter.formatAvailable(value);
    }

    private static String message(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null || cursor.getMessage().isBlank()
                ? cursor.getClass().getSimpleName()
                : cursor.getMessage();
    }

    private static void onClient(Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(action);
    }

    private static void chat(String value) {
        info(value);
    }

    private static void info(String value) {
        LocalModelControlChatFeedback.info(value);
    }

    private static void success(String value) {
        LocalModelControlChatFeedback.success(value);
    }

    private static void warning(String value) {
        LocalModelControlChatFeedback.warning(value);
    }

    private static void uninstall(String value) {
        LocalModelControlChatFeedback.uninstall(value);
    }

    private static void error(String value) {
        LocalModelControlChatFeedback.error(value);
    }

    private enum SuggestionMode {
        INSTALL,
        SWITCH
    }
}
