package com.spirit.koil.api.chat;

import com.spirit.client.gui.PopupMenu;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeMode;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeSettings;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelFamilySelection;
import com.spirit.koil.api.model.chat.LocalModelControlChatFeedback;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import com.spirit.koil.api.model.voice.ModelVoiceService;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes the ChatScreen ellipsis menu without owning Minecraft-version
 * coordinates. The screen mixin supplies placement; reusable systems supply
 * entries and actions here.
 */
public final class ChatComposerMenuBridge {
    public static final String PRIVATE_SECTION = "composer:private";
    public static final String AUTOMATION_SECTION = "composer:automation";
    public static final String MODEL_SECTION = "composer:model";
    public static final String MODEL_ACTIVE_SELECTOR = "composer:model_active_selector";
    public static final String MODEL_COMPLEXITY_SELECTOR = "composer:model_complexity_selector";
    public static final String MODEL_CATALOG_ACTION = "composer:model_setup";
    public static final String MODEL_CATALOG_LABEL = "Browse & manage models…";
    public static final String MODEL_COMPUTE_SELECTOR = "composer:model_compute_selector";
    public static final String MODEL_HYBRID_LAYERS_SELECTOR = "composer:model_hybrid_layers_selector";
    public static final String MODEL_MAX_TUNE_ACTION = "composer:model_max_tune";
    public static final String VOICE_SELECTOR = "composer:voice_selector";
    public static final String EXPERIMENTAL_SELECTOR = "composer:automation_experimental";

    private ChatComposerMenuBridge() {
    }

    public static List<PopupMenu.MenuEntry> rootEntries() {
        return List.of(
                new PopupMenu.MenuEntry(PRIVATE_SECTION, "/msg", 0, "", 0xFFAAB4C3, ""),
                new PopupMenu.MenuEntry(AUTOMATION_SECTION, "/automate", 0, "", 0xFFAAB4C3, ""),
                new PopupMenu.MenuEntry(MODEL_SECTION, "/model", 0, "", 0xFFAAB4C3, "")
        );
    }

    public static List<PopupMenu.MenuEntry> childEntries(String section, MinecraftClient client) {
        if (PRIVATE_SECTION.equals(section)) {
            return RichChatPrivateMessageBridge.menuEntries(client);
        }
        if (AUTOMATION_SECTION.equals(section)) {
            List<PopupMenu.MenuEntry> entries = new ArrayList<>();
            entries.add(new PopupMenu.MenuEntry(
                    "composer:automation_toggle",
                    "Automation: " + (AutomationModeController.isAutomationMode() ? "On" : "Off")
            ));
            entries.add(new PopupMenu.MenuEntry(
                    "composer:automation_unrestricted",
                    "unrestricted",
                    AutomationModeController.isUnrestrictedMode() ? 0x00FF00FF : 0,
                    AutomationModeController.isUnrestrictedMode() ? "•" : ""
            ));
            entries.add(new PopupMenu.MenuEntry(
                    "composer:automation_deep",
                    "deep thought",
                    AutomationModeController.isDeepThinkingEnabled() ? 0xFF4479C4 : 0,
                    AutomationModeController.isDeepThinkingEnabled() ? "•" : ""
            ));
            entries.add(new PopupMenu.MenuEntry(
                    "composer:automation_planning",
                    AutomationModeController.isPlanningActive() && !AutomationModeController.isPlanningModeEnabled()
                            ? "planning: automatic"
                            : "planning",
                    AutomationModeController.isPlanningModeEnabled() || AutomationModeController.isPlanningActive() ? 0xFFB067FF : 0,
                    AutomationModeController.isPlanningModeEnabled() || AutomationModeController.isPlanningActive() ? "•" : ""
            ));
            entries.add(new PopupMenu.MenuEntry(
                    EXPERIMENTAL_SELECTOR,
                    "experimental",
                    AutomationModeController.hasExperimentalFeaturesEnabled() ? 0xFF55FF55 : 0,
                    AutomationModeController.hasExperimentalFeaturesEnabled() ? "•" : "",
                    0xFFAAB4C3,
                    ">"
            ));
            return entries;
        }
        if (MODEL_SECTION.equals(section)) {
            List<PopupMenu.MenuEntry> entries = new ArrayList<>();
            ModelSelectionSnapshot selection = modelSelectionSnapshot();
            entries.add(new PopupMenu.MenuEntry(MODEL_CATALOG_ACTION, MODEL_CATALOG_LABEL));
            if (!selection.families().isEmpty()) {
                entries.add(new PopupMenu.MenuEntry(
                        MODEL_ACTIVE_SELECTOR,
                        "Active: " + selection.activeFamily(),
                        0,
                        "",
                        0xFFAAB4C3,
                        ">"
                ));
                entries.add(new PopupMenu.MenuEntry(
                        MODEL_COMPLEXITY_SELECTOR,
                        "Complexity: " + selection.activeComplexity(),
                        0,
                        "",
                        0xFFAAB4C3,
                        ">"
                ));
            }
            LlamaCppComputeSettings compute = visibleComputeSettings();
            entries.add(new PopupMenu.MenuEntry(
                    MODEL_COMPUTE_SELECTOR,
                    "Compute: " + computeMenuLabel(compute),
                    compute.mode() == LlamaCppComputeMode.MAX ? 0xFF55AA55 : 0,
                    compute.mode() == LlamaCppComputeMode.MAX ? "•" : "",
                    0xFFAAB4C3,
                    ">"
            ));
            var settings = ModelVoiceService.settings();
            entries.add(new PopupMenu.MenuEntry(
                    "composer:voice_toggle",
                    "Speak: " + (settings.enabled() ? "On" : "Off")
            ));
            entries.add(new PopupMenu.MenuEntry(
                    VOICE_SELECTOR,
                    "voice: " + ModelVoiceService.selectedVoiceLabel(),
                    0,
                    "",
                    0xFFAAB4C3,
                    ">"
            ));
            return entries;
        }
        return List.of();
    }

    public static List<PopupMenu.MenuEntry> nestedEntries(String selector, MinecraftClient client) {
        if ("pm_target_header".equals(selector)) {
            List<PopupMenu.MenuEntry> targets = RichChatPrivateMessageBridge.targetMenuEntries(client);
            return targets.isEmpty()
                    ? List.of(new PopupMenu.MenuEntry("composer:no_targets", "No other players"))
                    : targets;
        }
        if (VOICE_SELECTOR.equals(selector)) {
            String selectedVoice = ModelVoiceService.settings().voiceId();
            List<PopupMenu.MenuEntry> voices = new ArrayList<>();
            for (var voice : ModelVoiceService.voices()) {
                boolean selected = voice.id().equalsIgnoreCase(selectedVoice);
                voices.add(new PopupMenu.MenuEntry(
                        "composer:voice:" + voice.id(),
                        voice.displayName(),
                        selected ? 0xFF55AA55 : 0,
                        selected ? "•" : ""
                ));
            }
            return voices.isEmpty()
                    ? List.of(new PopupMenu.MenuEntry("composer:no_voices", "No voices available"))
                    : List.copyOf(voices);
        }
        if (MODEL_ACTIVE_SELECTOR.equals(selector)) {
            ModelSelectionSnapshot selection = modelSelectionSnapshot();
            return selection.families().stream().map(family -> {
                boolean active = family.label().equalsIgnoreCase(selection.activeFamily());
                return new PopupMenu.MenuEntry(
                        "composer:model_family:" + family.label(),
                        family.label(),
                        active ? 0xFF55AA55 : 0,
                        active ? "•" : ""
                );
            }).toList();
        }
        if (MODEL_COMPLEXITY_SELECTOR.equals(selector)) {
            ModelSelectionSnapshot selection = modelSelectionSnapshot();
            return selection.families().stream()
                    .filter(family -> family.label().equalsIgnoreCase(selection.activeFamily()))
                    .findFirst()
                    .map(family -> family.variants().stream().map(variant -> {
                        boolean active = variant.catalogId().equals(LocalModelService.selectedCatalogId());
                        return new PopupMenu.MenuEntry(
                                "composer:model:" + variant.catalogId(),
                                variant.label(),
                                active ? 0xFF55AA55 : 0,
                                active ? "•" : ""
                        );
                    }).toList())
                    .orElse(List.of());
        }
        if (MODEL_COMPUTE_SELECTOR.equals(selector)) {
            LlamaCppComputeSettings settings = visibleComputeSettings();
            return List.of(
                    computeModeEntry(settings, LlamaCppComputeMode.MAX),
                    new PopupMenu.MenuEntry(
                            MODEL_MAX_TUNE_ACTION,
                            LocalModelService.latestLlamaCppMaxTuning().isPresent()
                                    ? "Retune MAX for this machine…"
                                    : "Tune MAX for this machine…",
                            LocalModelService.activeLlamaCppMaxTuning() != null ? 0xFFFFAA55 : 0,
                            LocalModelService.activeLlamaCppMaxTuning() != null ? "•" : ""
                    ),
                    computeModeEntry(settings, LlamaCppComputeMode.GPU),
                    computeModeEntry(settings, LlamaCppComputeMode.HYBRID),
                    new PopupMenu.MenuEntry(
                            MODEL_HYBRID_LAYERS_SELECTOR,
                            "Hybrid GPU layers: " + settings.hybridGpuLayers(),
                            settings.mode() == LlamaCppComputeMode.HYBRID ? 0xFF55AA55 : 0,
                            settings.mode() == LlamaCppComputeMode.HYBRID ? "•" : "",
                            0xFFAAB4C3,
                            ">"
                    ),
                    computeModeEntry(settings, LlamaCppComputeMode.CPU)
            );
        }
        if (MODEL_HYBRID_LAYERS_SELECTOR.equals(selector)) {
            return hybridLayerEntries(visibleComputeSettings());
        }
        if (EXPERIMENTAL_SELECTOR.equals(selector)) {
            return List.of(
                    new PopupMenu.MenuEntry(
                            "composer:experimental_verification",
                            "verification",
                            AutomationModeController.isVerificationEnabled() ? 0xFF55AA55 : 0,
                            AutomationModeController.isVerificationEnabled() ? "•" : ""
                    ),
                    new PopupMenu.MenuEntry(
                            "composer:experimental_compact_context",
                            "compact context agent",
                            AutomationModeController.isExperimentalCompactAgentEnabled() ? 0xFF55FF55 : 0,
                            AutomationModeController.isExperimentalCompactAgentEnabled() ? "•" : ""
                    ),
                    experimentalEntry("persistent_history", "persistent conversation history", com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.PERSISTENT_CONVERSATION_HISTORY),
                    experimentalEntry("persistent_knowledge", "persistent knowledge", com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.PERSISTENT_KNOWLEDGE),
                    experimentalEntry("expert_prefetch", "expert prefetch", com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.EXPERT_PREFETCH),
                    experimentalEntry("completion_mode", "completion mode", com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.COMPLETION_MODE),
                    experimentalEntry("no_fail", "no-fail", com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.NO_FAIL)
            );
        }
        return List.of();
    }

    public static ActionResult handleAction(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return ActionResult.NOT_HANDLED;
        }
        if (RichChatPrivateMessageBridge.handleMenuAction(actionId)) {
            return ActionResult.HANDLED;
        }
        if ("composer:automation_toggle".equals(actionId)) {
            AutomationRouter.toggleAutomationModeFromUi();
            return ActionResult.HANDLED;
        }
        if ("composer:automation_unrestricted".equals(actionId)) {
            AutomationRouter.toggleAutomationUnrestrictedFromUi();
            return ActionResult.HANDLED;
        }
        if ("composer:automation_deep".equals(actionId)) {
            AutomationRouter.toggleDeepThinkingFromUi();
            return ActionResult.HANDLED;
        }
        if ("composer:automation_planning".equals(actionId)) {
            AutomationRouter.togglePlanningModeFromUi();
            return ActionResult.HANDLED;
        }
        if ("composer:experimental_compact_context".equals(actionId)) {
            AutomationRouter.toggleExperimentalModeFromUi();
            return ActionResult.HANDLED;
        }
        if ("composer:experimental_verification".equals(actionId)) {
            AutomationRouter.toggleVerificationFromUi();
            return ActionResult.HANDLED;
        }
        if (actionId.startsWith("composer:experimental_feature:")) {
            String id = actionId.substring("composer:experimental_feature:".length());
            com.spirit.koil.api.model.ModelExperimentalFeatures.Feature feature = switch (id) {
                case "persistent_history" -> com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.PERSISTENT_CONVERSATION_HISTORY;
                case "persistent_knowledge", "associative_memory" -> com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.PERSISTENT_KNOWLEDGE;
                case "expert_prefetch" -> com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.EXPERT_PREFETCH;
                case "completion_mode" -> com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.COMPLETION_MODE;
                case "no_fail" -> com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.NO_FAIL;
                default -> null;
            };
            if (feature != null) {
                com.spirit.koil.api.model.ModelExperimentalFeatures.toggle(feature);
                LocalModelService.refreshExperimentalFeatures();
                return ActionResult.HANDLED;
            }
        }
        if (actionId.startsWith("composer:model_family:")) {
            String familyName = actionId.substring("composer:model_family:".length());
            ModelSelectionSnapshot selection = modelSelectionSnapshot();
            LocalModelFamilySelection.FamilyOption family = selection.families().stream()
                    .filter(option -> option.label().equalsIgnoreCase(familyName))
                    .findFirst().orElse(null);
            if (family == null || family.variants().isEmpty()) {
                LocalModelControlChatFeedback.warning("That installed model family is no longer available.");
                return ActionResult.HANDLED;
            }
            selectModel(family.variants().get(0).catalogId());
            return ActionResult.HANDLED;
        }
        if (actionId.startsWith("composer:model:")) {
            String modelId = actionId.substring("composer:model:".length());
            selectModel(modelId);
            return ActionResult.HANDLED;
        }
        if (MODEL_CATALOG_ACTION.equals(actionId)) {
            return ActionResult.OPEN_MODEL_CATALOG;
        }
        if (MODEL_MAX_TUNE_ACTION.equals(actionId)) {
            tuneMaxPerformance();
            return ActionResult.HANDLED;
        }
        if (actionId.startsWith("composer:compute:")) {
            String modeId = actionId.substring("composer:compute:".length());
            LlamaCppComputeMode mode = switch (modeId) {
                case "cpu" -> LlamaCppComputeMode.CPU;
                case "max", "auto" -> LlamaCppComputeMode.MAX;
                case "gpu" -> LlamaCppComputeMode.GPU;
                case "hybrid" -> LlamaCppComputeMode.HYBRID;
                default -> null;
            };
            if (mode != null) {
                applyComputeSettings(LocalModelService.llamaCppComputeSettings().withMode(mode));
                return ActionResult.HANDLED;
            }
        }
        if (actionId.startsWith("composer:hybrid_layers:")) {
            String rawLayers = actionId.substring("composer:hybrid_layers:".length());
            try {
                int layers = Integer.parseInt(rawLayers);
                LlamaCppComputeSettings next = LocalModelService.llamaCppComputeSettings()
                        .withMode(LlamaCppComputeMode.HYBRID)
                        .withHybridGpuLayers(layers);
                applyComputeSettings(next);
            } catch (NumberFormatException ignored) {
                LocalModelControlChatFeedback.error("That hybrid GPU-layer value is invalid.");
            }
            return ActionResult.HANDLED;
        }
        if ("composer:voice_toggle".equals(actionId)) {
            ModelVoiceService.setEnabled(!ModelVoiceService.settings().enabled());
            return ActionResult.HANDLED;
        }
        if (actionId.startsWith("composer:voice:")) {
            String voiceId = actionId.substring("composer:voice:".length());
            if (ModelVoiceService.setVoice(voiceId)) {
                LocalModelControlChatFeedback.success(
                        "Local model voice set to " + ModelVoiceService.selectedVoiceLabel() + "."
                );
            } else {
                LocalModelControlChatFeedback.error("That model voice is no longer available.");
            }
            return ActionResult.HANDLED;
        }
        return ActionResult.NOT_HANDLED;
    }

    private static LlamaCppComputeSettings visibleComputeSettings() {
        LlamaCppComputeSettings active = LocalModelService.activeLlamaCppComputeSettings();
        return active == null ? LocalModelService.llamaCppComputeSettings() : active;
    }

    private static PopupMenu.MenuEntry computeModeEntry(
            LlamaCppComputeSettings settings,
            LlamaCppComputeMode mode
    ) {
        boolean selected = settings.mode() == mode;
        String label = mode == LlamaCppComputeMode.MAX
                ? mode.displayName() + (LocalModelService.latestLlamaCppMaxTuning().isPresent() ? " [saved]" : " [untuned]")
                : mode.displayName();
        return new PopupMenu.MenuEntry(
                "composer:compute:" + mode.name().toLowerCase(java.util.Locale.ROOT),
                label,
                selected ? 0xFF55AA55 : 0,
                selected ? "•" : ""
        );
    }

    private static List<PopupMenu.MenuEntry> hybridLayerEntries(LlamaCppComputeSettings settings) {
        int current = settings.hybridGpuLayers();
        int modelLayers = knownModelLayerCount();
        int[] presets = {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 256};
        java.util.SortedSet<Integer> values = new java.util.TreeSet<>();
        for (int layers : presets) {
            if (modelLayers <= 1 || layers < modelLayers) values.add(layers);
        }
        if (modelLayers > 1) values.add(modelLayers - 1);
        if (modelLayers <= 1 || current < modelLayers) values.add(current);

        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        for (int layers : values) {
            boolean selected = current == layers;
            entries.add(new PopupMenu.MenuEntry(
                    "composer:hybrid_layers:" + layers,
                    layers + (layers == 1 ? " GPU layer" : " GPU layers"),
                    selected ? 0xFF55AA55 : 0,
                    selected ? "•" : ""
            ));
        }
        return List.copyOf(entries);
    }

    private static int knownModelLayerCount() {
        try {
            String actual = LocalModelService.health().diagnostics().getOrDefault("actualGpuLayers", "");
            int slash = actual.indexOf('/');
            if (slash < 0 || slash + 1 >= actual.length()) return -1;
            return Integer.parseInt(actual.substring(slash + 1).trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String computeMenuLabel(LlamaCppComputeSettings settings) {
        return switch (settings.mode()) {
            case CPU -> "CPU";
            case MAX -> "MAX";
            case GPU -> "GPU";
            case HYBRID -> "Hybrid " + settings.hybridGpuLayers();
        };
    }

    private static void tuneMaxPerformance() {
        if (LocalModelService.activeLlamaCppMaxTuning() != null) {
            LocalModelControlChatFeedback.info(
                    "MAX autotuning is already running: " + LocalModelService.llamaCppMaxTuningStatus());
            return;
        }
        LocalModelControlChatFeedback.info(
                "Starting MAX autotune. Preparing benchmark for CPU, GPU, hybrid placement, CPU thread counts, polling, and batch geometry, then keep the fastest measured profile for this machine/model/runtime.");
        LocalModelService.tuneLlamaCppMaxPerformance().whenComplete((result, failure) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            Runnable feedback = () -> {
                if (failure != null) {
                    LocalModelControlChatFeedback.error("MAX autotune failed: " + rootMessage(failure));
                    return;
                }
                if (result == null || result.winner() == null) {
                    LocalModelControlChatFeedback.error("MAX autotune finished without a valid winner.");
                    return;
                }
                var winner = result.winner();
                LocalModelControlChatFeedback.success(
                        "MAX tuned: " + winner.profile().summary()
                                + " | prompt=" + String.format(java.util.Locale.ROOT, "%.2f", winner.benchmark().promptTokensPerSecond()) + " t/s"
                                + " | generation=" + String.format(java.util.Locale.ROOT, "%.2f", winner.benchmark().generationTokensPerSecond()) + " t/s"
                                + " | TTFT=" + String.format(java.util.Locale.ROOT, "%.0f", winner.benchmark().timeToFirstTokenMillis()) + " ms"
                                + " | tested=" + result.candidates().size()
                                + " | rejected=" + result.failedCandidates());
            };
            if (client == null) feedback.run(); else client.execute(feedback);
        });
    }

    private static void applyComputeSettings(LlamaCppComputeSettings settings) {
        LlamaCppComputeSettings requested = settings == null ? LlamaCppComputeSettings.defaults() : settings;
        LocalModelControlChatFeedback.info("Applying " + requested.mode().displayName()
                + (requested.mode() == LlamaCppComputeMode.HYBRID
                ? " with " + requested.hybridGpuLayers() + " GPU layers."
                : "."));
        LocalModelService.configureLlamaCppCompute(requested).whenComplete((ignored, failure) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            Runnable feedback = () -> {
                if (failure != null) {
                    LocalModelControlChatFeedback.error(
                            "Compute switch failed: " + rootMessage(failure)
                    );
                } else {
                    LocalModelControlChatFeedback.success(
                            "Compute set to " + requested.mode().displayName()
                                    + (requested.mode() == LlamaCppComputeMode.HYBRID
                                    ? " with " + requested.hybridGpuLayers() + " GPU layers."
                                    : ".")
                    );
                }
            };
            if (client == null) {
                feedback.run();
            } else {
                client.execute(feedback);
            }
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void selectModel(String modelId) {
        LocalModelService.selectInstalledCatalogModel(modelId).whenComplete((selected, failure) -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) {
                    return;
                }
                client.execute(() -> {
                    if (failure != null) {
                        LocalModelControlChatFeedback.error("Model switch failed: " + failure.getMessage());
                    } else if (Boolean.TRUE.equals(selected)) {
                        LocalModelControlChatFeedback.success("Selected local model " + modelId + ".");
                    } else {
                        LocalModelControlChatFeedback.warning("Model " + modelId + " is not installed or is incomplete.");
                    }
                });
            });
    }

    private static ModelSelectionSnapshot modelSelectionSnapshot() {
        List<LocalModelCatalogEntry> known = LocalModelCatalog.generationEntries();
        List<LocalModelCatalogEntry> installed = LocalModelInstallationService.instance().installedEntries();
        List<LocalModelFamilySelection.FamilyOption> families = LocalModelFamilySelection.families(known, installed);
        LocalModelCatalogEntry selected = LocalModelCatalog.find(LocalModelService.selectedCatalogId()).orElse(null);
        String family = selected == null ? "" : selected.family();
        String catalogFamily = family;
        if (family.isBlank() || families.stream().noneMatch(option -> option.label().equalsIgnoreCase(catalogFamily))) {
            family = families.isEmpty() ? "None" : families.get(0).label();
        }
        String selectedFamily = family;
        String complexity = families.stream()
                .filter(option -> option.label().equalsIgnoreCase(selectedFamily))
                .flatMap(option -> option.variants().stream())
                .filter(option -> selected != null && option.catalogId().equals(selected.id()))
                .map(LocalModelFamilySelection.VariantOption::label)
                .findFirst()
                .orElseGet(() -> families.stream()
                        .filter(option -> option.label().equalsIgnoreCase(selectedFamily))
                        .flatMap(option -> option.variants().stream())
                        .findFirst().map(LocalModelFamilySelection.VariantOption::label).orElse("None"));
        return new ModelSelectionSnapshot(families, selectedFamily, complexity);
    }

    private static PopupMenu.MenuEntry experimentalEntry(
            String id,
            String label,
            com.spirit.koil.api.model.ModelExperimentalFeatures.Feature feature
    ) {
        boolean enabled = com.spirit.koil.api.model.ModelExperimentalFeatures.snapshot().enabled(feature);
        return new PopupMenu.MenuEntry("composer:experimental_feature:" + id, label,
                enabled ? 0xFF55AA55 : 0, enabled ? "•" : "");
    }

    public static boolean isSection(String actionId) {
        return PRIVATE_SECTION.equals(actionId)
                || AUTOMATION_SECTION.equals(actionId)
                || MODEL_SECTION.equals(actionId);
    }

    public static boolean isNestedSelector(String actionId) {
        return "pm_target_header".equals(actionId)
                || VOICE_SELECTOR.equals(actionId)
                || EXPERIMENTAL_SELECTOR.equals(actionId)
                || MODEL_ACTIVE_SELECTOR.equals(actionId)
                || MODEL_COMPLEXITY_SELECTOR.equals(actionId)
                || MODEL_COMPUTE_SELECTOR.equals(actionId)
                || MODEL_HYBRID_LAYERS_SELECTOR.equals(actionId);
    }

    private record ModelSelectionSnapshot(
            List<LocalModelFamilySelection.FamilyOption> families,
            String activeFamily,
            String activeComplexity
    ) { }

    public enum ActionResult {
        NOT_HANDLED,
        HANDLED,
        OPEN_MODEL_CATALOG
    }
}
