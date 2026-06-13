package com.spirit.client.gui.update;

import com.spirit.client.gui.update.elements.UpdateScreenData;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.Main.*;
import static com.spirit.koil.api.design.uiColorVal.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;

@Environment(EnvType.CLIENT)
public class UpdateScreen extends Screen {
    private static final Identifier LOGO_TEXTURE = loadExternalPngTexture(uiImageDirectory, "icon.png");
    private final Screen parent;
    private UpdateScreenData.UpdateData data;
    private List<UpdateScreenData.Branch> branches = new ArrayList<>();
    private List<UpdateScreenData.Release> releases = new ArrayList<>();
    private UpdateScreenData.Branch selectedBranch;
    private UpdateScreenData.Release selectedRelease;
    private ButtonWidget branchButton;
    private ButtonWidget fileButton;
    private ButtonWidget downloadButton;
    private ButtonWidget refreshButton;
    private ButtonWidget doneButton;
    private int selectedBranchIndex;
    private int selectedFileIndex;
    private int scrollPosition;
    private int maxScrollOffset;
    private String status = "Review Koil release notes. Downloading an update will close Minecraft when complete.";
    private boolean betaTester;

    public UpdateScreen(Screen parent) {
        super(Text.literal("Koil Update"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        loadData(true);
        int buttonY = this.height - 28;
        this.branchButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(branchButtonLabel()), button -> cycleBranch())
                .dimensions(140, buttonY, 126, 20)
                .tooltip(Tooltip.of(Text.literal("Select Public, .unfinished, or .frequent when beta access is enabled.")))
                .build());
        this.fileButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(fileButtonLabel()), button -> cycleFile())
                .dimensions(270, buttonY, 128, 20)
                .tooltip(Tooltip.of(Text.literal("Select which jar file this branch should download.")))
                .build());
        this.downloadButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Download & Quit"), button -> downloadSelected())
                .dimensions(402, buttonY, 118, 20)
                .tooltip(Tooltip.of(Text.literal("Downloads the selected Koil jar, removes old Koil jars, then closes Minecraft so the update can load next launch.")))
                .build());
        this.refreshButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> {
            loadData(true);
            this.status = "Update data refreshed.";
        }).dimensions(this.width - 178, buttonY, 78, 20).build());
        this.doneButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(this.width - 94, buttonY, 80, 20)
                .build());
        refreshButtonStates();
    }

    private void loadData(boolean refreshOnline) {
        if (refreshOnline) {
            UpdateScreenData.refreshOnlineData();
        }
        this.data = UpdateScreenData.readLocalData();
        this.betaTester = UpdateScreenData.isBetaTester();
        this.branches = UpdateScreenData.visibleBranches(this.data, this.betaTester);
        if (this.branches.isEmpty()) {
            this.branches.add(new UpdateScreenData.Branch("public", "Public", "public", false, "Downloads from Modrinth."));
        }
        String activeBranch = activeKoilBranch();
        this.selectedBranchIndex = 0;
        for (int i = 0; i < this.branches.size(); i++) {
            if (this.branches.get(i).key.equalsIgnoreCase(activeBranch)) {
                this.selectedBranchIndex = i;
                break;
            }
        }
        selectBranch(this.selectedBranchIndex);
    }

    private void selectBranch(int index) {
        this.selectedBranchIndex = Math.max(0, Math.min(index, this.branches.size() - 1));
        this.selectedBranch = this.branches.get(this.selectedBranchIndex);
        this.releases = UpdateScreenData.releasesForBranch(this.data, this.selectedBranch.key, this.betaTester);
        this.selectedRelease = this.releases.isEmpty() ? null : this.releases.get(0);
        this.selectedFileIndex = 0;
        this.scrollPosition = 0;
        refreshButtonStates();
    }

    private void refreshButtonStates() {
        if (this.branchButton == null) {
            return;
        }
        this.branchButton.setMessage(Text.literal(branchButtonLabel()));
        this.fileButton.setMessage(Text.literal(fileButtonLabel()));
        this.branchButton.visible = this.betaTester;
        this.fileButton.visible = this.betaTester && this.selectedBranch != null && !"public".equalsIgnoreCase(this.selectedBranch.key) && fileCount() > 1;
        this.downloadButton.active = this.selectedBranch != null;
    }

    private void cycleBranch() {
        if (this.branches.isEmpty()) {
            return;
        }
        selectBranch((this.selectedBranchIndex + 1) % this.branches.size());
    }

    private void cycleFile() {
        int count = fileCount();
        if (count <= 0) {
            return;
        }
        this.selectedFileIndex = (this.selectedFileIndex + 1) % count;
        refreshButtonStates();
    }

    private int fileCount() {
        return this.selectedRelease == null || this.selectedRelease.files == null ? 0 : this.selectedRelease.files.size();
    }

    private String selectedFileName() {
        if (fileCount() <= 0) {
            return "";
        }
        return this.selectedRelease.files.get(Math.max(0, Math.min(this.selectedFileIndex, fileCount() - 1))).fileName;
    }

    private String branchButtonLabel() {
        return "Branch: " + (this.selectedBranch == null ? "Public" : this.selectedBranch.label);
    }

    private String fileButtonLabel() {
        String fileName = selectedFileName();
        return fileName.isBlank() ? "File: default jar" : "File: " + trim(fileName, 18);
    }

    private void downloadSelected() {
        UpdateScreenData.DownloadResult result = UpdateScreenData.downloadSelected(this.data, this.selectedBranch, this.selectedRelease, selectedFileName());
        this.status = result.message;
        if (result.success) {
            this.status = result.message + " Closing Minecraft...";
            SUBLOGGER.logF("Shut-down thread", "Koil update downloaded. Closing Minecraft so the new jar can load next launch.");
            if (this.client != null) {
                this.client.scheduleStop();
            }
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        this.scrollPosition = Math.max(0, Math.min(this.scrollPosition - (int) amount * 12, this.maxScrollOffset));
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        assert client != null;
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        renderChrome(context);
        renderSidebar(context);
        renderUpdatePanel(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderChrome(DrawContext context) {
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 0, this.width, 60, new Color(uiColorHeader, true).getRGB());
        context.drawBorder(0, 0, this.width, 60, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 62, 125, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, 62, 125, this.height - 62, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(127, 62, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(127, 62, this.width - 127, this.height - 62, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawTexture(LOGO_TEXTURE, 10, 5, 0, 0, 45, 45, 45, 45);
        context.getMatrices().push();
        context.getMatrices().scale(2, 2, 1.0F);
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Update Manager", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.drawText(this.textRenderer, "Version - " + version(), this.width - 100, 10, new Color(uiColorHeaderTitleText, true).getRGB(), true);
    }

    private void renderSidebar(DrawContext context) {
        int x = 10;
        int y = 76;
        drawSidebarLine(context, x, y, "Current", version());
        y += 32;
        drawSidebarLine(context, x, y, "Branch", this.selectedBranch == null ? "Public" : this.selectedBranch.label);
        y += 32;
        drawSidebarLine(context, x, y, "Update Type", this.selectedRelease == null ? "Unknown" : UpdateScreenData.displayType(this.data, this.selectedRelease));
        y += 32;
        drawSidebarLine(context, x, y, "Download", this.selectedBranch != null && "public".equalsIgnoreCase(this.selectedBranch.key) ? "Modrinth" : "GitHub jar");
        y += 38;
        context.drawText(this.textRenderer, trim(this.status, 18), x, y, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
    }

    private void drawSidebarLine(DrawContext context, int x, int y, String label, String value) {
        int accent = this.selectedRelease == null ? new Color(uiColorContentStripeLeft, true).getRGB() : UpdateScreenData.colorForType(this.data, UpdateScreenData.dominantTypeKey(this.data, this.selectedRelease));
        context.fill(x, y + 1, x + 2, y + 18, accent);
        context.drawText(this.textRenderer, label, x + 8, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, trim(value, 16), x + 8, y + 11, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private void renderUpdatePanel(DrawContext context, int mouseX, int mouseY) {
        int x = 140;
        int y = 76;
        int right = this.width - 16;
        int bottom = this.height - 36;
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Koil Updates", (int) (x / 1.2F), (int) (y / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Branch-aware changelog timeline and jar download selection.", x, y + 18, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        int timelineTop = y + 38;
        int timelineBottom = bottom - 4;
        context.enableScissor(x, timelineTop, right, timelineBottom);
        UpdateScreenData.TimelineRenderResult result = UpdateScreenData.renderReleaseTimelineInteractive(
                context,
                this.textRenderer,
                this.data,
                this.releases,
                x,
                timelineTop + 6,
                right - x - 8,
                this.scrollPosition,
                timelineTop,
                timelineBottom,
                this.betaTester,
                mouseX,
                mouseY
        );
        context.disableScissor();
        this.maxScrollOffset = Math.max(0, result.contentHeight - (timelineBottom - timelineTop - 8));
        this.scrollPosition = Math.max(0, Math.min(this.scrollPosition, this.maxScrollOffset));
        if (this.releases.isEmpty()) {
            context.drawText(this.textRenderer, "No release entries were found for this branch.", x + 10, timelineTop + 12, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        }
        UpdateScreenData.renderHoverPopup(context, this.textRenderer, result, mouseX, mouseY, this.width, this.height);
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
