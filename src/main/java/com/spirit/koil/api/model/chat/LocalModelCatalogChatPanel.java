package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.chat.ChatHudPanel;
import com.spirit.koil.api.chat.ChatHudPanelBounds;
import com.spirit.koil.api.chat.ChatHudPanelContext;
import com.spirit.koil.api.chat.ChatHudPanelPlacement;
import com.spirit.koil.api.chat.ChatHudPanelVisualStyle;
import com.spirit.koil.api.model.LocalModelCommandBridge;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelCompatibility;
import com.spirit.koil.api.model.hardware.HardwareCapabilityReport;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bottom chat panel used by `/model list` and `/model catalog search`. */
public final class LocalModelCatalogChatPanel implements ChatHudPanel {
    private static final int HEADER_HEIGHT = 15;
    private static final int ROW_HEIGHT = 13;
    private static final int FOOTER_HEIGHT = 12;
    private static final int BUTTON_HEIGHT = 10;
    private static final long INSTALL_STATE_CACHE_MILLIS = 750L;

    private final Map<String, InstalledState> installedStateCache = new HashMap<>();
    private volatile HardwareCapabilityReport hardwareReport;
    private volatile boolean hardwareReportRequested;

    @Override
    public String id() {
        return "koil:model_catalog";
    }

    @Override
    public ChatHudPanelPlacement placement() {
        return ChatHudPanelPlacement.BOTTOM;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public boolean visible(ChatHudPanelContext context) {
        MinecraftClient client = context.client();
        return LocalModelCatalogChatState.snapshot() != null && client != null && client.player != null
                && (client.currentScreen == null || client.currentScreen instanceof ChatScreen);
    }

    @Override
    public int height(ChatHudPanelContext context) {
        LocalModelCatalogChatState.Snapshot snapshot = LocalModelCatalogChatState.snapshot();
        return snapshot == null ? 0 : HEADER_HEIGHT + Math.max(1, snapshot.entries().size()) * ROW_HEIGHT + FOOTER_HEIGHT;
    }

    @Override
    public void render(DrawContext context, ChatHudPanelContext panelContext, ChatHudPanelBounds bounds) {
        LocalModelCatalogChatState.Snapshot snapshot = LocalModelCatalogChatState.snapshot();
        MinecraftClient client = panelContext.client();
        if (snapshot == null || client == null) {
            return;
        }
        requestHardwareReport();
        ChatHudPanelVisualStyle.drawSurface(context, bounds, client, 0xFF5A8FD4);
        int textColor = 0xFFFFFFFF;
        int secondary = 0xFFB8C5D6;
        int titleX = bounds.x() + 6;
        context.drawTextWithShadow(client.textRenderer, Text.literal(snapshot.title()), titleX, bounds.y() + 3, textColor);
        String page = snapshot.page() + "/" + snapshot.pageCount() + "  " + snapshot.totalEntries() + " models";
        int right = bounds.x() + bounds.width();
        int pageX = Math.max(bounds.x() + 128, titleX + client.textRenderer.getWidth(snapshot.title()) + 14);
        int pageWidth = right - 46 - pageX;
        if (pageWidth > 0) {
            context.drawTextWithShadow(client.textRenderer, Text.literal(client.textRenderer.trimToWidth(page, pageWidth)), pageX, bounds.y() + 3, secondary);
        }
        context.drawTextWithShadow(client.textRenderer, Text.literal("<"), right - 39, bounds.y() + 3, snapshot.page() > 1 ? textColor : secondary);
        context.drawTextWithShadow(client.textRenderer, Text.literal(">"), right - 27, bounds.y() + 3,
                snapshot.page() < snapshot.pageCount() ? textColor : secondary);
        context.drawTextWithShadow(client.textRenderer, Text.literal("x"), right - 14, bounds.y() + 3, secondary);

        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        int y = bounds.y() + HEADER_HEIGHT;
        for (LocalModelCatalogEntry entry : snapshot.entries()) {
            boolean installed = installed(installer, entry);
            boolean selected = installed && entry.id().equals(LocalModelService.selectedCatalogId());
            List<ActionButton> buttons = buttons(entry, installed, selected, right, y);
            int actionsLeft = buttons.isEmpty() ? right - 6 : buttons.get(0).x() - 4;
            String row = client.textRenderer.trimToWidth(entry.displayName() + " | " + entry.parameterCount(),
                    Math.max(40, actionsLeft - bounds.x() - 8));
            context.drawTextWithShadow(client.textRenderer, Text.literal(row), bounds.x() + 6, y + 3, textColor);
            if (buttons.isEmpty()) {
                context.drawTextWithShadow(client.textRenderer, Text.literal("catalog"), right - 43, y + 3, secondary);
            }
            for (ActionButton action : buttons) {
                drawButton(context, client, action);
            }
            y += ROW_HEIGHT;
        }
        String detail = snapshot.detail().isBlank() ? "Hover a model for details. < > changes page." : snapshot.detail();
        context.drawTextWithShadow(client.textRenderer, Text.literal(client.textRenderer.trimToWidth(detail, bounds.width() - 12)),
                bounds.x() + 6, bounds.y() + bounds.height() - 10, secondary);
        renderTooltip(context, panelContext, bounds, snapshot, installer);
    }

    @Override
    public boolean mouseClicked(ChatHudPanelContext context, ChatHudPanelBounds bounds, double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        LocalModelCatalogChatState.Snapshot snapshot = LocalModelCatalogChatState.snapshot();
        if (snapshot == null) {
            return false;
        }
        int right = bounds.x() + bounds.width();
        if (mouseY < bounds.y() + HEADER_HEIGHT) {
            if (mouseX >= right - 18) {
                LocalModelCatalogChatState.close();
            } else if (mouseX >= right - 31 && snapshot.page() < snapshot.pageCount()) {
                LocalModelCatalogChatState.page(catalogEntries(snapshot), snapshot.page() + 1);
            } else if (mouseX >= right - 43 && snapshot.page() > 1) {
                LocalModelCatalogChatState.page(catalogEntries(snapshot), snapshot.page() - 1);
            }
            return true;
        }
        int row = (int) ((mouseY - bounds.y() - HEADER_HEIGHT) / ROW_HEIGHT);
        if (row < 0 || row >= snapshot.entries().size()) {
            return false;
        }
        LocalModelCatalogEntry entry = snapshot.entries().get(row);
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        boolean installed = installed(installer, entry);
        boolean selected = installed && entry.id().equals(LocalModelService.selectedCatalogId());
        for (ActionButton action : buttons(entry, installed, selected, right, bounds.y() + HEADER_HEIGHT + row * ROW_HEIGHT)) {
            if (!action.contains(mouseX, mouseY) || action.action() == Action.SELECTED) {
                continue;
            }
            switch (action.action()) {
                case INSTALL -> {
                    LocalModelCommandBridge.installFromCatalogPanel(entry.id());
                    LocalModelCatalogChatState.detail("Install confirmation requested for " + entry.displayName() + ".");
                }
                case USE -> {
                    LocalModelCommandBridge.useFromCatalogPanel(entry.id());
                    LocalModelCatalogChatState.detail("Selecting " + entry.displayName() + ".");
                }
                case REMOVE -> {
                    LocalModelCommandBridge.uninstallFromCatalogPanel(entry.id());
                    LocalModelCatalogChatState.detail("Uninstall confirmation requested for " + entry.displayName() + ".");
                }
                case SELECTED -> { }
            }
            return true;
        }
        if (!installed && !entry.runnable()) {
            LocalModelCatalogChatState.detail(entry.displayName() + " has catalog metadata but no runnable local implementation.");
        }
        return true;
    }

    private boolean installed(LocalModelInstallationService installer, LocalModelCatalogEntry entry) {
        if (installer == null || entry == null) return false;
        long now = System.currentTimeMillis();
        String key = entry.id();
        InstalledState cached = this.installedStateCache.get(key);
        if (cached != null && now - cached.checkedAtMillis() <= INSTALL_STATE_CACHE_MILLIS) {
            return cached.installed();
        }
        boolean installed = installer.installed(entry);
        this.installedStateCache.put(key, new InstalledState(installed, now));
        if (this.installedStateCache.size() > 128) {
            this.installedStateCache.entrySet().removeIf(value ->
                    now - value.getValue().checkedAtMillis() > INSTALL_STATE_CACHE_MILLIS * 4L);
        }
        return installed;
    }

    private void requestHardwareReport() {
        if (hardwareReportRequested) {
            return;
        }
        hardwareReportRequested = true;
        LocalModelService.hardwareReport(false).thenAccept(report -> hardwareReport = report);
    }

    private void renderTooltip(
            DrawContext context,
            ChatHudPanelContext panelContext,
            ChatHudPanelBounds bounds,
            LocalModelCatalogChatState.Snapshot snapshot,
            LocalModelInstallationService installer
    ) {
        MinecraftClient client = panelContext.client();
        if (!panelContext.chatOpen() || client == null || client.getWindow() == null) {
            return;
        }
        int mouseX = (int) Math.round(client.mouse.getX()
                * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth());
        int mouseY = (int) Math.round(client.mouse.getY()
                * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight());
        int row = (mouseY - bounds.y() - HEADER_HEIGHT) / ROW_HEIGHT;
        if (mouseX < bounds.x() || mouseX >= bounds.x() + bounds.width() || row < 0 || row >= snapshot.entries().size()) {
            return;
        }
        LocalModelCatalogEntry entry = snapshot.entries().get(row);
        boolean installed = installed(installer, entry);
        boolean selected = installed && entry.id().equals(LocalModelService.selectedCatalogId());
        LocalModelCompatibility compatibility = LocalModelCompatibility.evaluate(
                entry, hardwareReport, installer.storagePlan(entry).remainingDownloadBytes());
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 1_000.0F);
        context.drawTooltip(client.textRenderer, LocalModelCatalogChatRow.panelTooltip(entry, compatibility, installed, selected), mouseX, mouseY);
        context.getMatrices().pop();
    }

    private static List<ActionButton> buttons(
            LocalModelCatalogEntry entry,
            boolean installed,
            boolean selected,
            int right,
            int y
    ) {
        if (!installed) {
            return entry.runnable() ? List.of(new ActionButton("Install", right - 44, y + 1, 38, Action.INSTALL)) : List.of();
        }
        int removeX = right - 48;
        int primaryWidth = selected ? 37 : 28;
        return List.of(
                new ActionButton(selected ? "Active" : "Use", removeX - primaryWidth - 4, y + 1, primaryWidth,
                        selected ? Action.SELECTED : Action.USE),
                new ActionButton("Remove", removeX, y + 1, 42, Action.REMOVE)
        );
    }

    private static void drawButton(DrawContext context, MinecraftClient client, ActionButton button) {
        int fill = switch (button.action()) {
            case INSTALL, USE -> ChatHudPanelVisualStyle.withAlpha(0x005A8FD4, 56);
            case REMOVE -> ChatHudPanelVisualStyle.withAlpha(0x00C85A5A, 50);
            case SELECTED -> ChatHudPanelVisualStyle.withAlpha(0x003A7650, 38);
        };
        int text = switch (button.action()) {
            case INSTALL, USE -> 0xFFB9D9FF;
            case REMOVE -> 0xFFFFA0A0;
            case SELECTED -> 0xFF8FE3A5;
        };
        context.fill(button.x(), button.y(), button.x() + button.width(), button.y() + BUTTON_HEIGHT, fill);
        context.drawTextWithShadow(client.textRenderer, Text.literal(button.label()), button.x() + 3, button.y() + 1, text);
    }

    private enum Action {
        INSTALL, USE, REMOVE, SELECTED
    }

    private record ActionButton(String label, int x, int y, int width, Action action) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
        }
    }

    private static java.util.List<LocalModelCatalogEntry> catalogEntries(LocalModelCatalogChatState.Snapshot snapshot) {
        String title = snapshot.title();
        if (!title.startsWith("Catalog Search: ")) {
            return LocalModelCatalog.generationEntries();
        }
        return com.spirit.koil.api.model.catalog.LocalModelCatalogView.search(
                LocalModelCatalog.generationEntries(), title.substring("Catalog Search: ".length()));
    }
    private record InstalledState(boolean installed, long checkedAtMillis) {
    }

}
