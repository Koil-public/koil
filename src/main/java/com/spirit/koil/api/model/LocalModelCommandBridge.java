package com.spirit.koil.api.model;

import com.spirit.client.gui.model.LocalModelSetupScreen;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelCompatibility;
import com.spirit.koil.api.model.catalog.LocalModelCatalogView;
import com.spirit.koil.api.model.catalog.LocalModelReliabilityStore;
import com.spirit.koil.api.model.chat.LocalModelCatalogChatRow;
import com.spirit.koil.api.model.chat.LocalModelControlChatFeedback;
import com.spirit.koil.api.model.hardware.HardwareCapabilityReport;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import com.spirit.koil.api.model.install.ModelInstallationSnapshot;
import com.spirit.koil.api.model.install.ModelInstallationState;
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
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
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
    private static final int CATALOG_PAGE_SIZE = 10;

    private LocalModelCommandBridge() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("ask")
                        .executes(context -> LocalModelService.ask("") ? 1 : 0)
                        .then(argument("prompt", greedyString())
                                .executes(context -> LocalModelService.ask(getString(context, "prompt")) ? 1 : 0))
                        .then(literal("reset")
                                .executes(context -> {
                                    LocalModelService.resetGeneralConversation();
                                    return 1;
                                }))
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
                dispatcher.register(literal("model")
                        .executes(context -> {
                            showStatus();
                            return 1;
                        })
                        .then(literal("status").executes(context -> {
                            showStatus();
                            return 1;
                        }))
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
                        .then(literal("catalog")
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
                        .then(literal("cancel").executes(context -> {
                            chat(LocalModelService.cancelActiveWork()
                                    ? "Cancelling active local model or automation work."
                                    : "No active local model or automation work to cancel.");
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
                )
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(modelsCommand())
        );
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> modelsCommand() {
        return literal("models")
                        .executes(context -> {
                            openSetup();
                            return 1;
                        })
                        .then(literal("setup").executes(context -> {
                            openSetup();
                            return 1;
                        }))
                        .then(literal("list")
                                .executes(context -> {
                                    showCatalog(1, "models");
                                    return 1;
                                })
                                .then(argument("page", integer(1))
                                        .executes(context -> {
                                            showCatalog(getInteger(context, "page"), "models");
                                            return 1;
                                        })))
                        .then(literal("catalog")
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
                        .then(literal("install-url")
                                .then(argument("url", greedyString())
                                        .executes(context -> {
                                            installDirectUrl(getString(context, "url"));
                                            return 1;
                                        })));
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
                .handle((report, failure) -> LocalModelCatalog.entries().stream()
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

    private static void openSetup() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.setScreen(new LocalModelSetupScreen(client.currentScreen));
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
        ModelInstallationSnapshot installation = LocalModelInstallationService.instance().snapshot();
        if (installation.state().active()) {
            info("Install: " + installation.state().name().toLowerCase(java.util.Locale.ROOT)
                    + " | " + Math.round(installation.progress() * 100.0D) + "%"
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
        showCatalogEntries(
                LocalModelCatalog.entries(),
                requestedPage,
                page -> "/" + commandRoot + " list " + page,
                "Local Model Catalog"
        );
    }

    private static void showCatalogEntries(
            List<LocalModelCatalogEntry> entries,
            int requestedPage,
            java.util.function.IntFunction<String> pageCommand,
            String title
    ) {
        LocalModelCatalogView.Page catalogPage = LocalModelCatalogView.page(
                entries, requestedPage, CATALOG_PAGE_SIZE);
        if (catalogPage.totalEntries() == 0) {
            warning("No models matched this catalog view.");
            return;
        }
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        LocalModelService.hardwareReport(false).whenComplete((report, failure) -> onClient(() -> {
            LocalModelControlChatFeedback.header(title + "  |  page " + catalogPage.page() + "/" + catalogPage.pageCount()
                    + "  |  " + catalogPage.totalEntries() + " models");
            if (failure != null || report == null) {
                warning("Hardware compatibility could not be measured: " + (failure == null ? "unknown result" : message(failure)));
            }
            String selectedId = LocalModelService.selectedCatalogId();
            for (LocalModelCatalogEntry entry : catalogPage.entries()) {
                LocalModelCompatibility compatibility = LocalModelCompatibility.evaluate(
                        entry,
                        report,
                        installer.storagePlan(entry).remainingDownloadBytes()
                );
                boolean installed = installer.installed(entry);
                LocalModelControlChatFeedback.add(
                        LocalModelCatalogChatRow.create(
                                entry,
                                compatibility,
                                installed,
                                installed && entry.id().equals(selectedId)
                        ),
                        feedbackLevel(compatibility)
                );
            }
            showCatalogNavigation(catalogPage.page(), catalogPage.pageCount(), pageCommand);
        }));
    }

    private static void showCatalogNavigation(
            int page,
            int pageCount,
            java.util.function.IntFunction<String> pageCommand
    ) {
        MutableText navigation = Text.literal("Pages: ").formatted(Formatting.DARK_GRAY);
        if (page > 1) navigation.append(navigationLink("< previous", pageCommand.apply(page - 1)));
        if (page > 1 && page < pageCount) navigation.append(Text.literal("  |  ").formatted(Formatting.DARK_GRAY));
        if (page < pageCount) navigation.append(navigationLink("next >", pageCommand.apply(page + 1)));
        if (pageCount <= 1) navigation.append(Text.literal("1 / 1").formatted(Formatting.WHITE));
        LocalModelControlChatFeedback.add(navigation, LocalModelControlChatFeedback.Level.INFO);
    }

    private static Text navigationLink(String label, String command) {
        return Text.literal(label).formatted(Formatting.WHITE).styled(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Text.literal("Open " + command).formatted(Formatting.GRAY))));
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
        List<LocalModelCatalogEntry> initialMatches = LocalModelCatalogView.search(LocalModelCatalog.entries(), cleanQuery);
        if (!initialMatches.isEmpty()) {
            showCatalogEntries(
                    initialMatches,
                    requestedPage,
                    page -> "/models catalog search-page " + page + " " + cleanQuery,
                    "Catalog Search: " + abbreviate(cleanQuery, 64)
            );
        }
        if (!searchRemote) {
            if (initialMatches.isEmpty()) {
                warning("No current Koil catalog models matched '" + abbreviate(cleanQuery, 80) + "'.");
            }
            return;
        }
        info("Searching Hugging Face GGUF models for additional matches: " + cleanQuery);
        LocalModelCatalog.searchRemote(cleanQuery).whenComplete((result, failure) -> onClient(() -> {
            if (failure != null || result == null) {
                error("Model catalog search failed: " + (failure == null ? "unknown result" : message(failure)));
                return;
            }
            if (result.failed()) {
                error(result.detail());
                return;
            }
            success("Model catalog search complete | " + result.candidatesSeen() + " candidates | "
                    + result.builtInModelsPromoted() + " existing models made runnable | "
                    + result.newModelsAdded() + " new runnable models added.");
            List<LocalModelCatalogEntry> matches = LocalModelCatalogView.search(LocalModelCatalog.entries(), cleanQuery);
            if (!matches.isEmpty() && (initialMatches.isEmpty() || matches.size() != initialMatches.size())) {
                showCatalogEntries(
                        matches,
                        requestedPage,
                        page -> "/models catalog search-page " + page + " " + cleanQuery,
                        "Catalog Search: " + abbreviate(cleanQuery, 64)
                );
            } else if (matches.isEmpty() && result.candidatesSeen() > 0) {
                warning("Hugging Face returned candidates, but none resolved into a safe runnable Koil catalog entry for this query.");
            } else if (matches.isEmpty()) {
                warning("No Hugging Face or current Koil catalog models matched '" + abbreviate(cleanQuery, 80) + "'.");
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

    private static void showHelp() {
        chat("/model status | info | diagnostics | rescan");
        chat("/model start | stop | restart | cancel");
        chat("/model list [page] | catalog refresh | catalog search <query> | installed | logs | prompt");
        chat("/model install <id> | install-url <huggingface-url> | use <id> | switch <id> | uninstall <id>");
        chat("/model reliability [reset [catalog-id]]");
        chat("/model voice [true|false|list|set <voice_id>]");
        chat("/model reset [general|automation|all]");
        chat("/models setup opens Local Model Setup; /models list [page] and /models catalog search <query> are aliases.");
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

    private static void installDirectUrl(String url) {
        if (url == null || url.isBlank()) {
            warning("Usage: /model install-url <huggingface.co GGUF file URL>");
            return;
        }
        info("Resolving the exact Hugging Face GGUF file and its verification metadata.");
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

    private static LocalModelControlChatFeedback.Level feedbackLevel(LocalModelCompatibility compatibility) {
        return switch (compatibility.level()) {
            case RECOMMENDED -> LocalModelControlChatFeedback.Level.SUCCESS;
            case SUPPORTED_WITH_LIMITS -> LocalModelControlChatFeedback.Level.WARNING;
            case NOT_RECOMMENDED, STORAGE_BLOCKED -> LocalModelControlChatFeedback.Level.ERROR;
            case UNAVAILABLE, UNKNOWN -> LocalModelControlChatFeedback.Level.INFO;
        };
    }

    private enum SuggestionMode {
        INSTALL,
        SWITCH
    }
}
