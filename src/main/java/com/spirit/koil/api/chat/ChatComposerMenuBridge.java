package com.spirit.koil.api.chat;

import com.spirit.client.gui.PopupMenu;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.model.LocalModelService;
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
    public static final String VOICE_SELECTOR = "composer:voice_selector";

    private ChatComposerMenuBridge() {
    }

    public static List<PopupMenu.MenuEntry> rootEntries() {
        return List.of(
                new PopupMenu.MenuEntry(PRIVATE_SECTION, "/msg", 0, "", 0xFFAAB4C3, ">"),
                new PopupMenu.MenuEntry(AUTOMATION_SECTION, "/automate", 0, "", 0xFFAAB4C3, ">"),
                new PopupMenu.MenuEntry(MODEL_SECTION, "/model", 0, "", 0xFFAAB4C3, ">")
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
                    "composer:automation_yolo",
                    "unrestricted",
                    AutomationModeController.isYoloMode() ? 0xFFFFAA00 : 0,
                    AutomationModeController.isYoloMode() ? "•" : ""
            ));
            entries.add(new PopupMenu.MenuEntry(
                    "composer:automation_deep",
                    "deep thought",
                    AutomationModeController.isDeepThinkingEnabled() ? 0xFF4479C4 : 0,
                    AutomationModeController.isDeepThinkingEnabled() ? "•" : ""
            ));
            entries.add(new PopupMenu.MenuEntry(
                    "composer:automation_planning",
                    "planning",
                    AutomationModeController.isPlanningModeEnabled() ? 0xFFB067FF : 0,
                    AutomationModeController.isPlanningModeEnabled() ? "•" : ""
            ));
            return entries;
        }
        if (MODEL_SECTION.equals(section)) {
            List<PopupMenu.MenuEntry> entries = new ArrayList<>();
            String selected = LocalModelService.selectedCatalogId();
            for (var model : LocalModelInstallationService.instance().installedEntries()) {
                boolean active = model.id().equals(selected);
                entries.add(new PopupMenu.MenuEntry(
                        "composer:model:" + model.id(),
                        model.displayName(),
                        active ? 0xFF55AA55 : 0,
                        active ? "•" : ""
                ));
            }
            if (entries.isEmpty()) {
                entries.add(new PopupMenu.MenuEntry("composer:model_setup", "Open model setup…"));
            }
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
        if ("composer:automation_yolo".equals(actionId)) {
            AutomationRouter.enableAutomationYoloFromUi();
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
        if (actionId.startsWith("composer:model:")) {
            String modelId = actionId.substring("composer:model:".length());
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
            return ActionResult.HANDLED;
        }
        if ("composer:model_setup".equals(actionId)) {
            return ActionResult.OPEN_MODEL_SETUP_COMMAND;
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

    public static boolean isSection(String actionId) {
        return PRIVATE_SECTION.equals(actionId)
                || AUTOMATION_SECTION.equals(actionId)
                || MODEL_SECTION.equals(actionId);
    }

    public static boolean isNestedSelector(String actionId) {
        return "pm_target_header".equals(actionId) || VOICE_SELECTOR.equals(actionId);
    }

    public enum ActionResult {
        NOT_HANDLED,
        HANDLED,
        OPEN_MODEL_SETUP_COMMAND
    }
}
