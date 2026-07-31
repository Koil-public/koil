package com.spirit.client.gui.model;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.BinaryStorageFormatter;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelCompatibility;
import com.spirit.koil.api.model.hardware.HardwareCapabilityReport;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import com.spirit.koil.api.model.install.ModelInstallationSnapshot;
import com.spirit.koil.api.model.install.ModelInstallationState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.awt.Color;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.uiColorContentBase;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderSubTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorLocalModelSetupRowBackground;
import static com.spirit.koil.api.design.uiColorVal.uiColorLocalModelSetupRowBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorLocalModelSetupSelectedBackground;
import static com.spirit.koil.api.design.uiColorVal.uiColorLocalModelSetupSelectedBorder;

public final class LocalModelSetupScreen extends Screen {
    private static final int HEADER_BOTTOM = 36;
    private static final int FOOTER_TOP_OFFSET = 31;
    private static final int ROW_HEIGHT = 42;
    private final Screen parent;
    private final LocalModelInstallationService installer = LocalModelInstallationService.instance();
    private String selectedId;
    private double scroll;
    private ButtonWidget actionButton;
    private ButtonWidget cancelButton;
    private boolean activating;
    private ModelInstallationSnapshot handledSnapshot = ModelInstallationSnapshot.idle();
    private volatile HardwareCapabilityReport hardwareReport;

    public LocalModelSetupScreen(Screen parent) {
        super(Text.literal("Local Models"));
        this.parent = parent;
        String active = LocalModelService.selectedCatalogId();
        this.selectedId = active == null || active.isBlank() ? "qwen2.5-3b-q4" : active;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.max(80, Math.min(150, (this.width - 24) / 3));
        int gap = 4;
        int total = buttonWidth * 3 + gap * 2;
        int x = (this.width - total) / 2;
        int y = this.height - 27;
        this.actionButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Install & Use"), button -> action())
                .dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;
        this.cancelButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel Download"), button -> this.installer.cancel())
                .dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(x, y, buttonWidth, 20).build());
        refreshButtons();
        clampScroll();
        LocalModelService.hardwareReport(false).thenAccept(report -> this.hardwareReport = report);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        KoilVanillaScreenChrome.renderListShell(
                context,
                MinecraftClient.getInstance(),
                this.width,
                this.height,
                HEADER_BOTTOM,
                listBottom()
        );
        KoilVanillaScreenChrome.renderTitle(
                context,
                this.textRenderer,
                Text.literal("Options"),
                Text.literal("Local Model Setup")
        );
        renderCatalog(context, mouseX, mouseY);
        renderDetails(context);
        renderInstallation(context);
        handleCompletedInstall();
        refreshButtons();
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderCatalog(DrawContext context, int mouseX, int mouseY) {
        int left = 8;
        int top = listTop();
        int width = catalogWidth();
        int bottom = listBottom();
        context.enableScissor(left, top, left + width, bottom);
        int y = top + 4 - (int) this.scroll;
        for (LocalModelCatalogEntry entry : LocalModelCatalog.entries()) {
            if (y + ROW_HEIGHT >= top && y < bottom) {
                boolean selected = entry.id().equals(this.selectedId);
                boolean hovered = mouseX >= left + 4 && mouseX < left + width - 4
                        && mouseY >= y && mouseY < y + ROW_HEIGHT - 3;
                int background = selected
                        ? new Color(uiColorLocalModelSetupSelectedBackground, true).getRGB()
                        : withAlpha(uiColorLocalModelSetupRowBackground, hovered ? 190 : 150);
                int border = selected
                        ? new Color(uiColorLocalModelSetupSelectedBorder, true).getRGB()
                        : withAlpha(uiColorLocalModelSetupRowBorder, hovered ? 190 : 130);
                context.fill(left + 4, y, left + width - 4, y + ROW_HEIGHT - 3, background);
                context.drawBorder(left + 4, y, width - 8, ROW_HEIGHT - 3, border);
                context.drawText(this.textRenderer, entry.displayName(), left + 10, y + 6,
                        new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                String facts = entry.complexReasoningEstimatePercent() + "% complex  |  " + BinaryStorageFormatter.format(entry.downloadBytes())
                        + "  |  " + entry.quantization();
                context.drawText(this.textRenderer, facts, left + 10, y + 18,
                        new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                String installStatus = this.installer.installed(entry)
                        ? entry.id().equals(LocalModelService.selectedCatalogId()) ? "Selected" : "Installed"
                        : "";
                String status = installStatus.isBlank()
                        ? entry.capabilityLabel()
                        : installStatus + " | " + entry.capabilityLabel();
                status = this.textRenderer.trimToWidth(status, Math.max(20, width - 22));
                context.drawText(this.textRenderer, status, left + 10, y + 29,
                        this.installer.installed(entry) ? 0xFF8FE3A5 : 0xFFB8C5D6, false);
            }
            y += ROW_HEIGHT;
        }
        context.disableScissor();
    }

    private void renderDetails(DrawContext context) {
        LocalModelCatalogEntry entry = selected();
        if (entry == null) {
            return;
        }
        int x = detailsLeft();
        int right = this.width - 8;
        int top = listTop() + 4;
        context.fill(x, top, right, listBottom(), withAlpha(uiColorContentBase, 118));
        context.drawBorder(x, top, right - x, listBottom() - top, withAlpha(uiColorLocalModelSetupRowBorder, 130));
        x += 8;
        int y = top + 8;
        int color = new Color(uiColorContentBaseTitleText, true).getRGB();
        int secondary = new Color(uiColorHeaderSubTitleText, true).getRGB();
        context.drawText(this.textRenderer, entry.displayName(), x, y, color, false);
        y += 15;
        context.drawText(this.textRenderer, "Complex-intent estimate: "
                + entry.complexReasoningEstimatePercent() + "%", x, y, secondary, false);
        y += 11;
        context.drawText(this.textRenderer, "Parameters: " + entry.parameterCount(), x, y, secondary, false);
        y += 11;
        context.drawText(this.textRenderer, "Download: " + BinaryStorageFormatter.format(entry.downloadBytes()), x, y, secondary, false);
        y += 11;
        context.drawText(this.textRenderer, "Quantization: " + entry.quantization(), x, y, secondary, false);
        y += 11;
        context.drawText(this.textRenderer, "Context: " + entry.contextTokens() + " tokens", x, y, secondary, false);
        y += 11;
        context.drawText(this.textRenderer, "License: " + entry.license(), x, y, secondary, false);
        y += 11;
        context.drawText(this.textRenderer, "Capabilities: " + entry.capabilityLabel(), x, y, 0xFF8FCBFF, false);
        y += 14;
        context.drawText(this.textRenderer, "Estimated memory guidance", x, y, color, false);
        y += 11;
        context.drawText(this.textRenderer, "Minimum: " + BinaryStorageFormatter.format(entry.estimatedMinimumMemoryBytes()), x, y, secondary, false);
        y += 11;
        context.drawText(this.textRenderer, "Recommended: " + BinaryStorageFormatter.format(entry.estimatedRecommendedMemoryBytes()), x, y, secondary, false);
        y += 11;
        LocalModelCompatibility compatibility = LocalModelCompatibility.evaluate(
                entry,
                this.hardwareReport,
                this.installer.installed(entry) ? 0L : entry.downloadBytes()
        );
        context.drawText(this.textRenderer, compatibility.label(), x, y, compatibilityColor(compatibility), false);
        y += 14;
        int wrapWidth = Math.max(40, right - x - 7);
        for (var line : this.textRenderer.wrapLines(Text.literal(entry.summary()), wrapWidth)) {
            context.drawText(this.textRenderer, line, x, y, secondary, false);
            y += 10;
        }
        y += 4;
        context.drawText(this.textRenderer, "Runtime measurements decide real speed.", x, y, 0xFFFFCC77, false);
        y += 11;
        context.drawText(this.textRenderer, "Installation starts only after confirmation.", x, y, 0xFFFFCC77, false);
    }

    private void renderInstallation(DrawContext context) {
        ModelInstallationSnapshot snapshot = this.installer.snapshot();
        if (snapshot.state() == ModelInstallationState.IDLE) {
            return;
        }
        int x = 12;
        int width = Math.max(1, this.width - 24);
        int y = listBottom() - 18;
        context.fill(x, y, x + width, y + 8, 0xA0202020);
        int fill = (int) Math.round(width * snapshot.progress());
        context.fill(x, y, x + fill, y + 8, snapshot.state() == ModelInstallationState.FAILED ? 0xFFD45A5A : 0xFF5A8FD4);
        context.drawBorder(x, y, width, 8, 0xFFC0C0C0);
        String detail = snapshot.detail();
        if (!snapshot.currentFile().isBlank()) {
            detail += " " + snapshot.currentFile();
        }
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(trim(detail, 100)), this.width / 2, y - 11,
                snapshot.state() == ModelInstallationState.FAILED ? 0xFFFF8C8C : 0xFFE0E0E0);
    }

    private void action() {
        LocalModelCatalogEntry entry = selected();
        if (entry == null || this.activating || this.installer.snapshot().state().active()) {
            return;
        }
        if (this.installer.installed(entry)) {
            if (this.installer.selectInstalled(entry)) {
                activateSelection();
            }
            return;
        }
        if (this.client == null) {
            return;
        }
        Text warning = Text.literal("Download " + entry.displayName() + " (" + BinaryStorageFormatter.format(entry.downloadBytes())
                + ") plus a verified llama.cpp runtime? This uses local storage and network data.");
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (this.client != null) {
                this.client.setScreen(this);
            }
            if (confirmed) {
                this.installer.install(entry.id());
            }
        }, Text.literal("Install local model?"), warning, Text.literal("Install"), ScreenTexts.CANCEL));
    }

    private void handleCompletedInstall() {
        ModelInstallationSnapshot snapshot = this.installer.snapshot();
        if (snapshot == this.handledSnapshot) {
            return;
        }
        this.handledSnapshot = snapshot;
        if (snapshot.state() == ModelInstallationState.READY && snapshot.catalogId().equals(this.selectedId)) {
            activateSelection();
        }
    }

    private void activateSelection() {
        if (this.activating) {
            return;
        }
        this.activating = true;
        LocalModelService.reloadConfiguration().whenComplete((ignored, failure) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> {
                    this.activating = false;
                    if (failure != null && client.inGameHud != null) {
                        client.inGameHud.getChatHud().addMessage(Text.literal(
                                "Local model selection failed: " + failure.getMessage()
                        ));
                    }
                    refreshButtons();
                });
            }
        });
    }

    private void refreshButtons() {
        if (this.actionButton == null || this.cancelButton == null) {
            return;
        }
        ModelInstallationSnapshot snapshot = this.installer.snapshot();
        LocalModelCatalogEntry entry = selected();
        boolean installed = this.installer.installed(entry);
        boolean selected = entry != null && entry.id().equals(LocalModelService.selectedCatalogId());
        this.actionButton.setMessage(Text.literal(
                this.activating ? "Activating..."
                        : selected ? "Selected"
                        : installed ? "Use"
                        : "Install & Use"
        ));
        this.actionButton.active = entry != null && !this.activating && !snapshot.state().active() && !selected;
        this.cancelButton.visible = snapshot.state().active();
        this.cancelButton.active = snapshot.state().active();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && clickRow(mouseX, mouseY)) {
            refreshButtons();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickRow(double mouseX, double mouseY) {
        int left = 8;
        int top = listTop();
        int width = catalogWidth();
        if (mouseX < left || mouseX >= left + width || mouseY < top || mouseY >= listBottom()) {
            return false;
        }
        int index = (int) ((mouseY - top - 4 + this.scroll) / ROW_HEIGHT);
        List<LocalModelCatalogEntry> entries = LocalModelCatalog.entries();
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        this.selectedId = entries.get(index).id();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX >= 8 && mouseX < 8 + catalogWidth() && mouseY >= listTop() && mouseY < listBottom()) {
            this.scroll -= amount * ROW_HEIGHT;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private void clampScroll() {
        int content = LocalModelCatalog.entries().size() * ROW_HEIGHT + 8;
        this.scroll = MathHelper.clamp(this.scroll, 0.0D, Math.max(0, content - (listBottom() - listTop())));
    }

    private LocalModelCatalogEntry selected() {
        return LocalModelCatalog.find(this.selectedId).orElse(null);
    }

    private int catalogWidth() {
        return Math.max(180, Math.min(330, (int) (this.width * 0.55D)));
    }

    private int detailsLeft() {
        return 12 + catalogWidth();
    }

    private int listTop() {
        return HEADER_BOTTOM + 4;
    }

    private int listBottom() {
        return this.height - FOOTER_TOP_OFFSET;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int compatibilityColor(LocalModelCompatibility compatibility) {
        return switch (compatibility.level()) {
            case RECOMMENDED -> 0xFF8FE3A5;
            case SUPPORTED_WITH_LIMITS -> 0xFFFFCC77;
            case NOT_RECOMMENDED, STORAGE_BLOCKED -> 0xFFFF7777;
            case UNKNOWN -> 0xFFB8C5D6;
        };
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maximum - 3)) + "...";
    }
}
