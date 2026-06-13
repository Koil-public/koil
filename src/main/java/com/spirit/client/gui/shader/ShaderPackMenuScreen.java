package com.spirit.client.gui.shader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.client.gui.*;
import com.spirit.client.gui.browser.*;
import com.spirit.koil.api.util.file.image.ExternalImageLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.design.uiColorVal.uiColorBasicSubtitleText;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

public class ShaderPackMenuScreen extends Screen {
    private enum PreviewSourceMode {
        AUTO("Auto"),
        MODRINTH("Modrinth"),
        LOCAL("Local");

        private final String label;

        PreviewSourceMode(String label) {
            this.label = label;
        }
    }

    private static final Identifier SHADER_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "shader.png");
    private static final Identifier FALLBACK_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");
    private final Screen parent;
    private final Path shaderpacksDir = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
    private final PopupMenu folderOpenPopup = new PopupMenu();
    private final PopupMenu previewSourcePopup = new PopupMenu();
    private final Map<String, ShaderPackMetadata> localMetadataCache = new HashMap<>();
    private final Map<String, RemoteShaderData> remoteDataCache = new ConcurrentHashMap<>();
    private final Set<String> remoteLoadsInFlight = ConcurrentHashMap.newKeySet();
    private TextFieldWidget searchField;
    private ShaderListWidget shaderListWidget;
    private ButtonWidget enableDisableButton;
    private ButtonWidget deleteButton;
    private ButtonWidget configButton;
    private ButtonWidget websiteButton;
    private CheckboxWidget shadersEnabledCheckbox;
    private ShaderPackEntry selectedPack;
    private File pendingFolderOpenDirectory;
    private int lastMouseX;
    private int lastMouseY;
    private final List<ContentPreviewTooltipRegion> previewTooltipRegions = new ArrayList<>();
    private int previewScrollOffset;
    private int previewScrollMax;
    private int previewViewportX = -1;
    private int previewViewportY = -1;
    private int previewViewportWidth;
    private int previewViewportHeight;
    private boolean previewScrollbarDragging;
    private int previewScrollbarDragOffset;
    private String status = "Select a shaderpack.";
    private PreviewSourceMode previewSourceMode = PreviewSourceMode.AUTO;
    private int previewSourceChipX;
    private int previewSourceChipY;
    private int previewSourceChipWidth;

    public ShaderPackMenuScreen(Screen parent) {
        super(Text.literal("Shaders"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ensureDirectory();
        int searchX = this.width - 210;
        this.searchField = new TextFieldWidget(this.textRenderer, searchX, 10, 200, 20, Text.literal("Search installed Shaders"));
        this.searchField.setPlaceholder(Text.literal("Search installed shaderpacks"));
        this.searchField.setChangedListener(query -> rebuildList());
        this.addDrawableChild(this.searchField);

        int topButtonWidth = BrowserLayoutHelper.FOOTER_TOP_BUTTON_WIDTH;
        int smallButtonWidth = BrowserLayoutHelper.FOOTER_SMALL_BUTTON_WIDTH;
        int rightActionX = BrowserLayoutHelper.FOOTER_RIGHT_ACTION_X;
        int rightActionGap = BrowserLayoutHelper.FOOTER_TOP_BUTTON_GAP;
        int checkboxX = rightActionX + topButtonWidth + rightActionGap;
        int checkboxWidth = Math.max(72, this.width - checkboxX - 10);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Download Shaders"), button -> {
            if (this.client != null) {
                this.client.setScreen(new ShaderpackScreen(this));
            }
        }).dimensions(BrowserLayoutHelper.footerTopButtonX(0), this.height - 52, topButtonWidth, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Import Shader"), button -> importShader()).dimensions(BrowserLayoutHelper.footerTopButtonX(1), this.height - 52, topButtonWidth, 20).build());
        this.websiteButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Website"), button -> openSelectedShaderWebsite()).dimensions(rightActionX, this.height - 28, topButtonWidth, 20).build());
        this.shadersEnabledCheckbox = this.addDrawableChild(new CheckboxWidget(checkboxX, this.height - 52, checkboxWidth, 20, Text.literal("Shaders Enabled"), shadersEnabled(), true) {
            @Override
            public void onPress() {
                super.onPress();
                ShaderPackMenuScreen.this.setShadersEnabledFromCheckbox(this.isChecked());
            }
        });
        this.enableDisableButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Select"), button -> toggleSelectedShader()).dimensions(BrowserLayoutHelper.footerSmallButtonX(0), this.height - 28, smallButtonWidth, 20).build());
        this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), button -> confirmDeleteSelectedShader()).dimensions(BrowserLayoutHelper.footerSmallButtonX(1), this.height - 28, smallButtonWidth, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Folder"), button -> openFolderPopupAtPointer(this.shaderpacksDir.toFile())).dimensions(rightActionX, this.height - 52, topButtonWidth, 20).build());
        this.configButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Config"), button -> openSelectedShaderConfig()).dimensions(BrowserLayoutHelper.footerSmallButtonX(2), this.height - 28, smallButtonWidth, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close()).dimensions(BrowserLayoutHelper.footerSmallButtonX(3), this.height - 28, smallButtonWidth, 20).build());

        this.shaderListWidget = new ShaderListWidget(this.client, BrowserLayoutHelper.LIST_INNER_RIGHT - BrowserLayoutHelper.LIST_INNER_LEFT, this.height, BrowserLayoutHelper.LIST_WIDGET_TOP, this.height - BrowserLayoutHelper.FOOTER_HEIGHT, 36);
        this.shaderListWidget.setLeftPos(BrowserLayoutHelper.LIST_INNER_LEFT);
        this.addSelectableChild(this.shaderListWidget);
        rebuildList();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        MinecraftClient client = MinecraftClient.getInstance();
        renderShaderScreenBackground(context, client);
        MarkdownPreviewRenderer.beginInteractiveFrame();
        BrowserLayoutHelper.renderBrowserChrome(context, client, this.textRenderer, this.width, this.height, "Shaders");
        if (this.shaderListWidget != null) {
            this.shaderListWidget.render(context, mouseX, mouseY, delta);
        }
        renderPreview(context);
        BrowserLayoutHelper.renderBrowserBars(context, client, this.textRenderer, this.width, this.height, "Shaders");
        super.render(context, mouseX, mouseY, delta);
        renderPreviewHoverTooltip(context, mouseX, mouseY);
        this.folderOpenPopup.render(context, mouseX, mouseY);
        this.previewSourcePopup.render(context, mouseX, mouseY);
        ((ScreenChromeHost) (Object) this).koil$renderScreenChromeLate(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context) {
        renderShaderScreenBackground(context, MinecraftClient.getInstance());
    }

    private void renderShaderScreenBackground(DrawContext context, MinecraftClient client) {
        BrowserLayoutHelper.renderContentBackground(context, client, this.width, this.height);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (((ScreenChromeHost) (Object) this).koil$consumeScreenChromeClick(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && this.folderOpenPopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.folderOpenPopup.click(mouseX, mouseY);
            if (selected != null) {
                FolderOpenHelper.handleAction(this.client, this.pendingFolderOpenDirectory, selected.id());
                return true;
            }
            if (!this.folderOpenPopup.isOpen()) {
                return true;
            }
        }
        if (this.folderOpenPopup.isOpen() && this.folderOpenPopup.contains(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && this.previewSourcePopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.previewSourcePopup.click(mouseX, mouseY);
            if (selected != null) {
                applyPreviewSourceSelection(selected.id());
                return true;
            }
            if (!this.previewSourcePopup.isOpen()) {
                return true;
            }
        }
        if (this.previewSourcePopup.isOpen() && this.previewSourcePopup.contains(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && this.previewSourceChipWidth > 0
                && mouseX >= this.previewSourceChipX
                && mouseX <= this.previewSourceChipX + this.previewSourceChipWidth
                && mouseY >= this.previewSourceChipY
                && mouseY <= this.previewSourceChipY + 10) {
            this.previewSourcePopup.openAtPointer(mouseX, mouseY, this.width, this.height, buildPreviewSourceEntries());
            return true;
        }
        if (button == 0 && this.previewScrollMax > 0 && this.previewViewportHeight > 0 && isOverPreviewScrollbar(mouseX, mouseY)) {
            int thumbY = previewScrollbarThumbY();
            int thumbHeight = previewScrollbarThumbHeight();
            this.previewScrollbarDragging = true;
            if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
                this.previewScrollbarDragOffset = (int) mouseY - thumbY;
            } else {
                this.previewScrollbarDragOffset = thumbHeight / 2;
                setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.previewScrollbarDragging) {
            setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.previewScrollbarDragging) {
            this.previewScrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (this.previewScrollMax > 0
                && mouseX >= this.previewViewportX
                && mouseX <= this.previewViewportX + this.previewViewportWidth
                && mouseY >= this.previewViewportY
                && mouseY <= this.previewViewportY + this.previewViewportHeight) {
            this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, this.previewScrollOffset - (int) (amount * 18)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private boolean isOverPreviewScrollbar(double mouseX, double mouseY) {
        int scrollbarX = this.previewViewportX + this.previewViewportWidth - 2;
        return mouseX >= scrollbarX - 3
                && mouseX <= scrollbarX + 6
                && mouseY >= this.previewViewportY
                && mouseY <= this.previewViewportY + this.previewViewportHeight;
    }

    private int previewScrollbarThumbHeight() {
        return Math.max(18, (int) ((this.previewViewportHeight / (float) (this.previewViewportHeight + this.previewScrollMax)) * this.previewViewportHeight));
    }

    private int previewScrollbarThumbY() {
        int thumbTravel = Math.max(1, this.previewViewportHeight - previewScrollbarThumbHeight());
        return this.previewViewportY + (int) ((this.previewScrollOffset / (float) this.previewScrollMax) * thumbTravel);
    }

    private void setPreviewScrollFromThumbTop(int thumbTop) {
        int thumbHeight = previewScrollbarThumbHeight();
        int minTop = this.previewViewportY;
        int maxTop = this.previewViewportY + this.previewViewportHeight - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int travel = Math.max(1, maxTop - minTop);
        float ratio = (clampedTop - minTop) / (float) travel;
        this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, Math.round(ratio * this.previewScrollMax)));
    }

    private void renderPreview(DrawContext context) {
        this.previewTooltipRegions.clear();
        ShaderPackEntry entry = this.selectedPack;
        ShaderPackMetadata metadata = metadataFor(entry);
        RemoteShaderData remoteData = remoteFor(entry);
        Identifier icon = entry == null ? (SHADER_ICON == null ? FALLBACK_ICON : SHADER_ICON) : iconFor(entry, metadata, remoteData);
        String title = entry == null ? "Shaderpack Manager" : remoteData.found() && !remoteData.title().isBlank() ? remoteData.title() : entry.displayName();
        String state = entry == null ? "Disabled" : shaderState(entry);
        List<ContentPreviewChip> headerChips = new ArrayList<>();
        headerChips.add(new ContentPreviewChip(state, shaderStateChipBackground(state), buildStateTooltip(state)));
        if (remoteData.found()) {
            headerChips.add(new ContentPreviewChip("Remote Match", 0x8A314E39, List.of(
                    Text.literal("Remote Match").styled(style -> style.withColor(0xFF8ED4A8)),
                    Text.literal(blankFallback(remoteData.title(), entry == null ? "unknown" : entry.displayName())).styled(style -> style.withColor(0xFFE6EDF5)),
                    Text.literal("Modrinth metadata was found for this shaderpack.").styled(style -> style.withColor(0xFF96A9BC))
            )));
        }

        List<ContentPreviewChip> categoryChips = new ArrayList<>();
        if (remoteData.found() && remoteData.categories() != null && !remoteData.categories().isEmpty()) {
            Set<String> seenTags = new LinkedHashSet<>();
            for (String rawCategory : remoteData.categories()) {
                String category = sanitizePreviewTag(rawCategory);
                if (category.isBlank() || isGenericShaderTag(category) || !seenTags.add(category.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                categoryChips.add(new ContentPreviewChip(category, getPreviewTagBackground(category), buildPreviewTagTooltip(category)));
            }
        }

        List<ContentPreviewSection> sections = new ArrayList<>();
        if (entry == null) {
            sections.add(new ContentPreviewSection("Remote Data", List.of(), List.of("Select a shaderpack from the list to view its local file data and remote metadata.")));
        } else {
            if (remoteData.loading()) {
                sections.add(new ContentPreviewSection("Remote Data", List.of(), List.of("Loading provider metadata...")));
            } else if (remoteData.found()) {
                sections.add(new ContentPreviewSection("Remote Data", List.of(
                        new ContentPreviewSectionRow("Provider", blankFallback(remoteData.provider(), "Modrinth"), 0xFF76E6A0),
                        new ContentPreviewSectionRow("Sides", formatSides(remoteData), 0xFFD7E2EF),
                        new ContentPreviewSectionRow("Targets", formatTargets(remoteData), 0xFFD6E5DA),
                        new ContentPreviewSectionRow("Type", displayProjectType(remoteData.projectType()), 0xFFDCE4EE),
                        new ContentPreviewSectionRow("License", blankFallback(remoteData.license(), "unknown"), 0xFFE4DAC8),
                        new ContentPreviewSectionRow("Loader", formatCollection(remoteData.loaders(), "unknown"), 0xFFD6E5DA),
                        new ContentPreviewSectionRow("Downloads", blankFallback(remoteData.downloads(), "0"), 0xFFDDE5EF),
                        new ContentPreviewSectionRow("Followers", blankFallback(remoteData.followers(), "0"), 0xFFD7DDF0),
                        new ContentPreviewSectionRow("Links", formatLinks(remoteData), 0xFFD3E3DE),
                        new ContentPreviewSectionRow("Dates", formatDates(remoteData), 0xFFD9D5E8),
                        new ContentPreviewSectionRow("Project", blankFallback(remoteData.title(), entry.displayName()) + "  |  " + blankFallback(remoteData.slug(), "unknown"), 0xFFF2F4F7),
                        new ContentPreviewSectionRow("Author", blankFallback(remoteData.author(), "unknown"), 0xFFD8E7DE),
                        new ContentPreviewSectionRow("Version", formatVersion(remoteData), 0xFFDCE4EE)
                ), remoteData.approximateProject() ? List.of("Closest available project metadata is being shown for this installed shaderpack.") : List.of()));
            } else {
                sections.add(new ContentPreviewSection("Remote Data", List.of(), List.of("No provider metadata match was found for this content item.")));
            }

            sections.add(new ContentPreviewSection("Local Metadata", List.of(
                    new ContentPreviewSectionRow("Type", "shaderpack", 0xFFD5DDE7),
                    new ContentPreviewSectionRow("Environment", "client", 0xFFCCD7E6),
                    new ContentPreviewSectionRow("Metadata File", blankFallback(metadata.metadataFile(), "unknown"), 0xFFD8DFE9),
                    new ContentPreviewSectionRow("Pack Format", blankFallback(metadata.packFormat(), "unknown"), 0xFFE5D9C2),
                    new ContentPreviewSectionRow("File", entry.fileName(), 0xFFD8DFE9),
                    new ContentPreviewSectionRow("Size", formatFileSize(entry.path()), 0xFFDDE5EF),
                    new ContentPreviewSectionRow("Modified", formatLastModified(entry.path()), 0xFFD9D5E8),
                    new ContentPreviewSectionRow("State", selectedStatus(), state.equals("Enabled") ? 0xFF9BDEAE : state.equals("Selected") ? 0xFFD9C48F : 0xFFD48E8E),
                    new ContentPreviewSectionRow("Iris", isIrisInstalled() ? "available" : "unavailable", isIrisInstalled() ? 0xFF9BDEAE : 0xFFD48E8E)
            ), List.of()));
            String markdown = remoteData.found() && !remoteData.body().isBlank() ? remoteData.body() : remoteData.found() && !remoteData.description().isBlank() ? remoteData.description() : blankFallback(metadata.description(), "none");
            sections.add(new ContentPreviewSection(remoteData.found() ? "Description" : "Local Description", List.of(), List.of(markdown)));
        }
        ContentPreviewRenderState stateRender = ContentPreviewRenderer.render(context, this.textRenderer, this.width, this.height, new ContentPreviewModel(
                icon,
                title,
                "ID",
                entry == null ? "none" : entry.displayName(),
                blankFallback(metadata.packFormat(), "unknown"),
                remoteData.found() ? blankFallback(remoteData.author(), "Unknown") : "Unknown",
                getPreviewSourceLabel(remoteData),
                buildPreviewSourceTooltip(remoteData),
                headerChips,
                categoryChips,
                sections
        ), this.previewScrollOffset);
        this.previewViewportX = stateRender.viewportX();
        this.previewViewportY = stateRender.viewportY();
        this.previewViewportWidth = stateRender.viewportWidth();
        this.previewViewportHeight = stateRender.viewportHeight();
        this.previewScrollMax = stateRender.scrollMax();
        this.previewSourceChipX = stateRender.sourceChipX();
        this.previewSourceChipY = stateRender.sourceChipY();
        this.previewSourceChipWidth = stateRender.sourceChipWidth();
        this.previewTooltipRegions.addAll(stateRender.tooltipRegions());
        if (this.previewScrollOffset > this.previewScrollMax) {
            this.previewScrollOffset = this.previewScrollMax;
        }
    }

    private int renderRemoteBars(DrawContext context, int x, int y, int width, RemoteShaderData data) {
        int downloads = parseInt(data.downloads());
        int followers = parseInt(data.followers());
        int versions = Math.max(0, data.versionCount());
        int max = Math.max(1, Math.max(downloads, Math.max(followers, versions)));
        y = renderBar(context, x, y, width, "Downloads", downloads, max, 0xFF76E6A0);
        y = renderBar(context, x, y, width, "Followers", followers, max, 0xFF8FC5FF);
        return renderBar(context, x, y, width, "Versions", versions, max, 0xFFFFD38F);
    }

    private int renderBar(DrawContext context, int x, int y, int width, String label, int value, int max, int accent) {
        int labelWidth = 64;
        int barX = x + labelWidth;
        int barWidth = Math.max(20, width - labelWidth - 42);
        int fillWidth = Math.max(1, (int) (barWidth * (value / (float) Math.max(1, max))));
        context.drawText(this.textRenderer, label, x, y + 1, 0xFF96A9BC, false);
        context.fill(barX, y + 2, barX + barWidth, y + 8, 0x66343D4C);
        context.fill(barX, y + 2, barX + fillWidth, y + 8, accent);
        context.drawText(this.textRenderer, shortNumber(value), barX + barWidth + 5, y + 1, 0xFFE2E7EF, false);
        return y + 12;
    }

    private int renderShaderDetailChip(DrawContext context, int x, int y, String label, int background) {
        return renderShaderDetailChip(context, x, y, label, background, List.of());
    }

    private int renderShaderDetailChip(DrawContext context, int x, int y, String label, int background, List<Text> tooltipLines) {
        int width = this.textRenderer.getWidth(label) + 10;
        context.fill(x, y, x + width, y + 11, background);
        context.drawBorder(x, y, width, 11, 0xB06B7485);
        context.drawText(this.textRenderer, label, x + 5, y + 2, 0xFFE8EDF5, false);
        if (tooltipLines != null && !tooltipLines.isEmpty()) {
            this.previewTooltipRegions.add(new ContentPreviewTooltipRegion(x, y, width, 11, tooltipLines));
        }
        return x + width;
    }

    private int renderShaderPreviewInfoLine(DrawContext context, int x, int y, int panelWidth, String label, String value, int valueColor) {
        context.drawText(this.textRenderer, label, x, y, uiColorBasicSubtitleText, false);
        String fittedValue = fitDetailsText(blankFallback(value, "unknown"), Math.max(80, panelWidth - 94));
        context.drawText(this.textRenderer, fittedValue, x + 72, y, valueColor, false);
        return y + this.textRenderer.fontHeight + 4;
    }

    private void renderShaderSectionRule(DrawContext context, int left, int right, int y, String label) {
        int labelWidth = this.textRenderer.getWidth(label) + 8;
        int labelX = left + 2;
        int lineY = y + 5;
        context.fill(left, lineY, labelX - 2, lineY + 1, 0x665A6675);
        context.drawText(this.textRenderer, label, labelX, y, 0xFF96A9BC, false);
        context.fill(labelX + labelWidth, lineY, right, lineY + 1, 0x665A6675);
    }

    private void renderPreviewHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        if (MarkdownPreviewRenderer.renderLinkTooltip(context, this.textRenderer, mouseX, mouseY)) {
            return;
        }
        for (ContentPreviewTooltipRegion region : this.previewTooltipRegions) {
            if (mouseX >= region.x() && mouseX <= region.x() + region.width() && mouseY >= region.y() && mouseY <= region.y() + region.height()) {
                context.drawTooltip(this.textRenderer, region.lines(), mouseX, mouseY);
                return;
            }
        }
        if (mouseX >= this.previewSourceChipX
                && mouseX <= this.previewSourceChipX + this.previewSourceChipWidth
                && mouseY >= this.previewSourceChipY
                && mouseY <= this.previewSourceChipY + 10) {
            context.drawTooltip(this.textRenderer, buildPreviewSourceTooltip(remoteFor(this.selectedPack)), mouseX, mouseY);
        }
    }

    private List<Text> buildStateTooltip(String state) {
        return List.of(
                Text.literal("State").styled(style -> style.withColor(0xFF96A9BC)),
                Text.literal(state).styled(style -> style.withColor("Enabled".equals(state) ? 0xFF8ED4A8 : "Selected".equals(state) ? 0xFFD9C48F : 0xFFD48E8E)),
                Text.literal(switch (state) {
                    case "Enabled" -> "This shaderpack is selected and shaders are enabled.";
                    case "Selected" -> "This shaderpack is selected, but shaders are currently disabled.";
                    default -> "This shaderpack is installed but not active.";
                }).styled(style -> style.withColor(0xFFE6EDF5))
        );
    }

    private String sanitizePreviewTag(String rawTag) {
        if (rawTag == null) {
            return "";
        }
        String tag = rawTag.trim();
        if (tag.isEmpty()) {
            return "";
        }
        if (tag.length() > 48) {
            return "";
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.equals("unknown") || lower.equals("none") || lower.equals("null") || lower.equals("n/a")) {
            return "";
        }
        if (lower.contains(".class") || lower.contains(".java") || lower.contains(".jar") || lower.contains(".zip")) {
            return "";
        }
        if (lower.matches(".*\\.(class|java|jar|zip|png|jpg|jpeg|gif|webp|json|json5|toml|yml|yaml|txt|md|properties|cfg|conf)$")) {
            return "";
        }
        if (tag.contains("/") || tag.contains("\\") || tag.contains(":") || tag.contains("{") || tag.contains("}") || tag.contains("[") || tag.contains("]")) {
            return "";
        }
        if (tag.matches(".*\\s{2,}.*")) {
            return "";
        }
        if (tag.matches("(?:[A-Za-z_$][\\w$]*\\.){2,}[A-Za-z_$][\\w$]*")) {
            return "";
        }
        if (tag.matches("^[A-Za-z_$][\\w$]*\\.[A-Za-z_$][\\w$]*$") && lower.contains("class")) {
            return "";
        }
        return tag;
    }

    private boolean isGenericShaderTag(String tag) {
        String normalized = tag == null ? "" : tag.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        return normalized.equals("shader")
                || normalized.equals("shaders")
                || normalized.equals("shader pack")
                || normalized.equals("shaderpack")
                || normalized.equals("pack");
    }

    private int getPreviewTagBackground(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.contains("vanilla") || lower.contains("default")) {
            return 0x8A41513A;
        }
        if (lower.contains("realistic") || lower.contains("path") || lower.contains("ray") || lower.contains("pbr")) {
            return 0x8A33465C;
        }
        if (lower.contains("fantasy") || lower.contains("colorful") || lower.contains("vibrant")) {
            return 0x8A4A3B55;
        }
        if (lower.contains("performance") || lower.contains("potato") || lower.contains("low")) {
            return 0x8A38543F;
        }
        if (lower.contains("atmosphere") || lower.contains("cinematic") || lower.contains("lighting")) {
            return 0x8A5A4B33;
        }
        return 0x8A33465C;
    }

    private int getPreviewTagAccentColor(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.contains("vanilla") || lower.contains("default")) {
            return 0xFFB9D49A;
        }
        if (lower.contains("realistic") || lower.contains("path") || lower.contains("ray") || lower.contains("pbr")) {
            return 0xFF8FC5FF;
        }
        if (lower.contains("fantasy") || lower.contains("colorful") || lower.contains("vibrant")) {
            return 0xFFD5B6FF;
        }
        if (lower.contains("performance") || lower.contains("potato") || lower.contains("low")) {
            return 0xFF9BDEAE;
        }
        if (lower.contains("atmosphere") || lower.contains("cinematic") || lower.contains("lighting")) {
            return 0xFFD9C48F;
        }
        return 0xFF8FC5FF;
    }

    private List<Text> buildPreviewTagTooltip(String tag) {
        int accent = getPreviewTagAccentColor(tag);
        return List.of(
                Text.literal("Category").styled(style -> style.withColor(0xFF96A9BC)),
                Text.literal(tag).styled(style -> style.withColor(accent)),
                Text.literal("Remote provider category tag used to describe this shaderpack's purpose or style.").styled(style -> style.withColor(0xFFE6EDF5))
        );
    }

    private String fitDetailsText(String value, int width) {
        String text = value == null ? "" : value;
        return this.textRenderer.trimToWidth(text, Math.max(12, width));
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int shaderStateChipBackground(String state) {
        if ("Enabled".equals(state)) {
            return 0x8A314E39;
        }
        if ("Selected".equals(state)) {
            return 0x8A5A4B33;
        }
        return 0x8A5A3B3B;
    }

    private String shaderState(ShaderPackEntry entry) {
        if (entry == null) {
            return "Disabled";
        }
        boolean selected = entry.matches(currentShaderPackName());
        if (selected && shadersEnabled()) {
            return "Enabled";
        }
        if (selected) {
            return "Selected";
        }
        return "Disabled";
    }

    private ShaderPackMetadata metadataFor(ShaderPackEntry entry) {
        if (entry == null) {
            return ShaderPackMetadata.EMPTY;
        }
        String key = entry.fileName() + ":" + lastModified(entry.path());
        ShaderPackMetadata cached = this.localMetadataCache.get(key);
        if (cached != null) {
            return cached;
        }
        ShaderPackMetadata metadata = readShaderMetadata(entry);
        this.localMetadataCache.put(key, metadata);
        return metadata;
    }

    private ShaderPackMetadata readShaderMetadata(ShaderPackEntry entry) {
        String description = "";
        String packFormat = "";
        String metadataFile = "";
        Identifier icon = null;
        try {
            if (Files.isDirectory(entry.path())) {
                Path mcmeta = findPackFile(entry.path(), "pack.mcmeta");
                if (mcmeta != null) {
                    String rawMetadata = Files.readString(mcmeta);
                    description = parsePackDescription(rawMetadata);
                    packFormat = parsePackFormat(rawMetadata);
                    metadataFile = entry.path().relativize(mcmeta).toString();
                }
                Path packIcon = findPackFile(entry.path(), "pack.png");
                if (packIcon != null) {
                    icon = ExternalImageLoader.registerDynamicTexture("koil", "shaderpack_icon/" + sanitizeKey(entry.fileName()) + "_" + lastModified(packIcon), packIcon.toFile());
                }
            } else {
                try (ZipFile zipFile = new ZipFile(entry.path().toFile())) {
                    ZipEntry mcmeta = findPackZipEntry(zipFile, "pack.mcmeta");
                    if (mcmeta != null) {
                        try (InputStream input = zipFile.getInputStream(mcmeta)) {
                            String rawMetadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                            description = parsePackDescription(rawMetadata);
                            packFormat = parsePackFormat(rawMetadata);
                            metadataFile = mcmeta.getName();
                        }
                    }
                    ZipEntry packIcon = findPackZipEntry(zipFile, "pack.png");
                    if (packIcon != null) {
                        try (InputStream input = zipFile.getInputStream(packIcon)) {
                            icon = ExternalImageLoader.registerDynamicTexture("koil", "shaderpack_icon/" + sanitizeKey(entry.fileName()) + "_" + lastModified(entry.path()) + "_" + sanitizeKey(packIcon.getName()), input.readAllBytes());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (description == null || description.isBlank()) {
            description = "No local shader metadata found.";
        }
        return new ShaderPackMetadata(description, icon, packFormat, metadataFile);
    }

    private Path findPackFile(Path root, String fileName) {
        Path direct = root.resolve(fileName);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (Stream<Path> stream = Files.walk(root, 3)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && fileName.equals(path.getFileName().toString()))
                    .min(Comparator.comparingInt(path -> root.relativize(path).getNameCount()))
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ZipEntry findPackZipEntry(ZipFile zipFile, String fileName) {
        ZipEntry direct = zipFile.getEntry(fileName);
        if (direct != null && !direct.isDirectory()) {
            return direct;
        }
        ZipEntry best = null;
        int bestDepth = Integer.MAX_VALUE;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.endsWith("/" + fileName)) {
                continue;
            }
            int depth = name.split("/").length;
            if (depth < bestDepth) {
                best = entry;
                bestDepth = depth;
            }
        }
        return best;
    }

    private String parsePackDescription(String rawJson) {
        try {
            JsonObject root = new Gson().fromJson(rawJson, JsonObject.class);
            JsonObject pack = root == null || !root.has("pack") || !root.get("pack").isJsonObject() ? null : root.getAsJsonObject("pack");
            if (pack == null || !pack.has("description")) {
                return "";
            }
            return cleanDescription(flattenDescription(pack.get("description")));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String parsePackFormat(String rawJson) {
        try {
            JsonObject root = new Gson().fromJson(rawJson, JsonObject.class);
            JsonObject pack = root == null || !root.has("pack") || !root.get("pack").isJsonObject() ? null : root.getAsJsonObject("pack");
            if (pack == null || !pack.has("pack_format") || pack.get("pack_format").isJsonNull()) {
                return "";
            }
            return pack.get("pack_format").getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String flattenDescription(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement child : element.getAsJsonArray()) {
                builder.append(flattenDescription(child));
            }
            return builder.toString();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            StringBuilder builder = new StringBuilder();
            if (object.has("text")) {
                builder.append(object.get("text").getAsString());
            }
            if (object.has("translate")) {
                builder.append(object.get("translate").getAsString());
            }
            if (object.has("fallback")) {
                builder.append(object.get("fallback").getAsString());
            }
            if (object.has("with") && object.get("with").isJsonArray()) {
                builder.append(flattenDescription(object.get("with")));
            }
            if (object.has("extra") && object.get("extra").isJsonArray()) {
                builder.append(flattenDescription(object.get("extra")));
            }
            return builder.toString();
        }
        return "";
    }

    private String cleanDescription(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("§.", "").replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    private RemoteShaderData remoteFor(ShaderPackEntry entry) {
        if (entry == null) {
            return RemoteShaderData.EMPTY;
        }
        if (this.previewSourceMode == PreviewSourceMode.LOCAL) {
            return RemoteShaderData.EMPTY;
        }
        String key = sanitizeKey(entry.fileName() + ":" + this.previewSourceMode.name());
        RemoteShaderData cached = this.remoteDataCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (this.remoteLoadsInFlight.add(key)) {
            CompletableFuture.supplyAsync(() -> fetchRemoteShaderData(entry))
                    .whenComplete((data, throwable) -> {
                        this.remoteLoadsInFlight.remove(key);
                        this.remoteDataCache.put(key, throwable == null && data != null ? data : RemoteShaderData.EMPTY);
                    });
        }
        return RemoteShaderData.LOADING;
    }

    private List<PopupMenu.MenuEntry> buildPreviewSourceEntries() {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        for (PreviewSourceMode mode : PreviewSourceMode.values()) {
            entries.add(new PopupMenu.MenuEntry("source:" + mode.name(), mode.label));
        }
        return entries;
    }

    private void applyPreviewSourceSelection(String actionId) {
        if (actionId == null || !actionId.startsWith("source:")) {
            return;
        }
        try {
            this.previewSourceMode = PreviewSourceMode.valueOf(actionId.substring("source:".length()));
            this.previewScrollOffset = 0;
        } catch (IllegalArgumentException ignored) {
        }
    }

    private String getPreviewSourceLabel(RemoteShaderData remoteData) {
        if (this.previewSourceMode == PreviewSourceMode.LOCAL) {
            return "Local";
        }
        if (this.previewSourceMode == PreviewSourceMode.MODRINTH) {
            return "Modrinth";
        }
        return remoteData != null && remoteData.found() ? "Modrinth" : "Auto";
    }

    private RemoteShaderData fetchRemoteShaderData(ShaderPackEntry entry) {
        try {
            String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
            HttpClient client = HttpClient.newHttpClient();
            List<JsonObject> hits = new ArrayList<>();
            for (String queryCandidate : buildRemoteQueryCandidates(entry)) {
                hits.addAll(fetchModrinthShaderHits(client, queryCandidate, version, true));
                if (!hits.isEmpty()) {
                    break;
                }
            }
            if (hits.isEmpty()) {
                for (String queryCandidate : buildRemoteQueryCandidates(entry)) {
                    hits.addAll(fetchModrinthShaderHits(client, queryCandidate, version, false));
                    if (!hits.isEmpty()) {
                        break;
                    }
                }
            }
            JsonObject hit = chooseBestRemoteHit(entry, hits);
            if (hit == null) {
                return RemoteShaderData.EMPTY;
            }
            String slug = getString(hit, "slug");
            if (slug.isBlank()) {
                return RemoteShaderData.EMPTY;
            }
            JsonObject project = new Gson().fromJson(client.send(HttpRequest.newBuilder(new URI("https://api.modrinth.com/v2/project/" + slug)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
            JsonArray versions = new Gson().fromJson(client.send(HttpRequest.newBuilder(new URI("https://api.modrinth.com/v2/project/" + slug + "/version?game_versions=%5B%22" + URLEncoder.encode(version, StandardCharsets.UTF_8) + "%22%5D")).GET().build(), HttpResponse.BodyHandlers.ofString()).body(), JsonArray.class);
            if (versions == null || versions.isEmpty()) {
                versions = new Gson().fromJson(client.send(HttpRequest.newBuilder(new URI("https://api.modrinth.com/v2/project/" + slug + "/version")).GET().build(), HttpResponse.BodyHandlers.ofString()).body(), JsonArray.class);
            }
            JsonObject chosenVersion = versions == null || versions.isEmpty() ? null : versions.get(0).getAsJsonObject();
            List<String> categories = readStringArray(project == null ? null : project.getAsJsonArray("categories"), 6);
            List<String> loaders = chosenVersion == null ? List.of() : readStringArray(chosenVersion.getAsJsonArray("loaders"), 8);
            List<String> gameVersions = chosenVersion == null ? List.of() : readStringArray(chosenVersion.getAsJsonArray("game_versions"), 8);
            JsonObject license = project == null || !project.has("license") || !project.get("license").isJsonObject() ? null : project.getAsJsonObject("license");
            return new RemoteShaderData(
                    true,
                    false,
                    "Modrinth",
                    getString(project, "title").isBlank() ? getString(hit, "title") : getString(project, "title"),
                    slug,
                    getString(project, "project_type"),
                    getString(project, "description").isBlank() ? getString(hit, "description") : getString(project, "description"),
                    getString(project, "body"),
                    getString(hit, "author"),
                    getString(project, "downloads").isBlank() ? getString(hit, "downloads") : getString(project, "downloads"),
                    getString(project, "followers"),
                    chosenVersion == null ? "" : getString(chosenVersion, "name"),
                    chosenVersion == null ? "" : getString(chosenVersion, "version_number"),
                    chosenVersion == null ? "" : getString(chosenVersion, "version_type"),
                    versions == null ? 0 : versions.size(),
                    getString(hit, "icon_url"),
                    getString(license, "name"),
                    getString(project, "source_url"),
                    getString(project, "issues_url"),
                    getString(project, "wiki_url"),
                    getString(project, "discord_url"),
                    getString(project, "published"),
                    getString(project, "updated"),
                    getString(project, "client_side"),
                    getString(project, "server_side"),
                    loaders,
                    gameVersions,
                    categories,
                    scoreSearchHit(hit, entry) < 80
            );
        } catch (Exception ignored) {
            return RemoteShaderData.EMPTY;
        }
    }

    private List<JsonObject> fetchModrinthShaderHits(HttpClient client, String queryText, String version, boolean requireGameVersion) throws Exception {
        String query = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
        String facets = requireGameVersion
                ? "%5B%5B%22versions%3A" + URLEncoder.encode(version, StandardCharsets.UTF_8) + "%22%5D,%5B%22project_type%3Ashader%22%5D%5D"
                : "%5B%5B%22project_type%3Ashader%22%5D%5D";
        URI searchUri = new URI("https://api.modrinth.com/v2/search?query=" + query + "&limit=12&facets=" + facets);
        HttpResponse<String> searchResponse = client.send(HttpRequest.newBuilder(searchUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject searchRoot = new Gson().fromJson(searchResponse.body(), JsonObject.class);
        JsonArray hits = searchRoot == null ? null : searchRoot.getAsJsonArray("hits");
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<JsonObject> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement element : hits) {
            JsonObject object = element.getAsJsonObject();
            String id = getString(object, "project_id");
            if (id.isBlank()) {
                id = getString(object, "slug");
            }
            if (seen.add(id)) {
                results.add(object);
            }
        }
        return results;
    }

    private List<String> buildRemoteQueryCandidates(ShaderPackEntry entry) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(entry.displayName());
        candidates.add(cleanRemoteSearchName(entry.displayName()));
        candidates.add(cleanRemoteSearchName(entry.fileName()));
        ShaderPackMetadata metadata = metadataFor(entry);
        if (metadata != null && metadata.description() != null && !metadata.description().isBlank()) {
            candidates.add(cleanRemoteSearchName(metadata.description()));
        }
        return candidates.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private String cleanRemoteSearchName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replace(".zip", " ")
                .replace("shaderpack", " ")
                .replace("shader pack", " ")
                .replace("shaders", " ")
                .replace("shader", " ")
                .replaceAll("\\[[^]]*]", " ")
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("v?\\d+(?:\\.\\d+)+(?:[-+._a-z0-9]*)?", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private JsonObject chooseBestRemoteHit(ShaderPackEntry entry, List<JsonObject> hits) {
        JsonObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonObject hit : hits) {
            int score = scoreSearchHit(hit, entry);
            if (score > bestScore) {
                bestScore = score;
                best = hit;
            }
        }
        return bestScore >= 3 ? best : null;
    }

    private int scoreSearchHit(JsonObject hit, ShaderPackEntry entry) {
        String title = getString(hit, "title");
        String slug = getString(hit, "slug");
        String displayName = entry.displayName();
        String cleanedDisplay = cleanRemoteSearchName(displayName);
        String cleanedFile = cleanRemoteSearchName(entry.fileName());
        String cleanedTitle = cleanRemoteSearchName(title);
        String cleanedSlug = cleanRemoteSearchName(slug);
        int score = 0;
        if (!displayName.isBlank() && title.equalsIgnoreCase(displayName)) {
            score += 100;
        }
        if (!displayName.isBlank() && slug.equalsIgnoreCase(displayName)) {
            score += 90;
        }
        if (!cleanedDisplay.isBlank() && cleanedDisplay.equals(cleanedTitle)) {
            score += 80;
        }
        if (!cleanedDisplay.isBlank() && cleanedDisplay.equals(cleanedSlug)) {
            score += 70;
        }
        for (String token : tokenList(cleanedDisplay + " " + cleanedFile)) {
            if (cleanedTitle.contains(token) || cleanedSlug.contains(token)) {
                score += token.length() >= 5 ? 6 : 2;
            }
        }
        return score;
    }

    private List<String> tokenList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : value.split("\\s+")) {
            if (token.isBlank()
                    || token.equals("shader")
                    || token.equals("shaders")
                    || token.equals("shaderpack")
                    || token.equals("pack")
                    || token.equals("iris")
                    || token.equals("fabric")
                    || token.matches("v?\\d+(?:\\.\\d+)*")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private Identifier iconFor(ShaderPackEntry entry, ShaderPackMetadata metadata, RemoteShaderData remoteData) {
        if (metadata.icon() != null) {
            return metadata.icon();
        }
        if (remoteData.found()) {
            Identifier remote = ContentRemoteIconResolver.resolve(remoteData.iconUrl(), "shaderpack_" + sanitizeKey(remoteData.slug()));
            if (remote != null) {
                return remote;
            }
        }
        return SHADER_ICON == null ? FALLBACK_ICON : SHADER_ICON;
    }

    private int parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String shortNumber(int value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fm", value / 1_000_000.0F);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0F);
        }
        return String.valueOf(value);
    }

    private String getString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private List<String> readStringArray(JsonArray array, int limit) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (JsonElement element : array) {
            values.add(element.getAsString());
            if (values.size() >= limit) {
                break;
            }
        }
        return values;
    }

    private String displayProjectType(String projectType) {
        String normalized = blankFallback(projectType, "shader").toLowerCase(Locale.ROOT);
        if (normalized.equals("shader") || normalized.equals("shaderpack") || normalized.equals("shader_pack")) {
            return "Shader Pack";
        }
        return normalized.replace('_', ' ').replace('-', ' ');
    }

    private String formatSides(RemoteShaderData data) {
        return blankFallback(data.clientSide(), "unknown") + " client  |  " + blankFallback(data.serverSide(), "unknown") + " server";
    }

    private String formatTargets(RemoteShaderData data) {
        String loaderPart = formatCollection(data.loaders(), "targets unknown");
        String versionPart = data.gameVersions() == null || data.gameVersions().isEmpty() ? "versions unknown" : String.join(", ", data.gameVersions().subList(0, Math.min(3, data.gameVersions().size())));
        return loaderPart + "  |  " + versionPart;
    }

    private String formatLinks(RemoteShaderData data) {
        List<String> links = new ArrayList<>();
        if (!blankFallback(data.sourceUrl(), "").isBlank()) {
            links.add("Source");
        }
        if (!blankFallback(data.issuesUrl(), "").isBlank()) {
            links.add("Issues");
        }
        if (!blankFallback(data.wikiUrl(), "").isBlank()) {
            links.add("Wiki");
        }
        if (!blankFallback(data.discordUrl(), "").isBlank()) {
            links.add("Discord");
        }
        return links.isEmpty() ? "none" : String.join("  |  ", links);
    }

    private String formatDates(RemoteShaderData data) {
        return blankFallback(data.publishedAt(), "unknown") + "  |  " + blankFallback(data.updatedAt(), "unknown");
    }

    private String formatVersion(RemoteShaderData data) {
        String version = blankFallback(data.versionNumber(), "");
        String title = blankFallback(data.versionTitle(), "");
        String type = blankFallback(data.versionType(), "");
        String summary = data.versionCount() + " compatible builds";
        if (version.isBlank() && title.isBlank()) {
            return summary;
        }
        return (version.isBlank() ? title : version) + (type.isBlank() ? "" : "  |  " + type) + "  |  " + summary;
    }

    private String formatCollection(Collection<String> values, String fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        return String.join(", ", values.stream().limit(4).toList());
    }

    private String formatFileSize(Path path) {
        try {
            long size = Files.isDirectory(path) ? directorySize(path) : Files.size(path);
            if (size >= 1024L * 1024L) {
                return String.format(Locale.ROOT, "%.1f MB", size / (1024.0F * 1024.0F));
            }
            if (size >= 1024L) {
                return String.format(Locale.ROOT, "%.1f KB", size / 1024.0F);
            }
            return size + " B";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private long directorySize(Path path) {
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (Exception ignored) {
                    return 0L;
                }
            }).sum();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String formatLastModified(Path path) {
        try {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Files.getLastModifiedTime(path).toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private List<Text> buildPreviewSourceTooltip(RemoteShaderData remoteData) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)));
        if (this.previewSourceMode == PreviewSourceMode.LOCAL) {
            lines.add(Text.literal("Local file metadata only").styled(style -> style.withColor(0xFFE6EDF5)));
            lines.add(Text.literal("Remote provider preview is disabled.").styled(style -> style.withColor(0xFFB8C4D2)));
        } else if (remoteData != null && remoteData.found()) {
            lines.add(Text.literal(blankFallback(remoteData.provider(), "Modrinth")).styled(style -> style.withColor(0xFF76E6A0)));
            lines.add(Text.literal(remoteData.approximateProject() ? "Closest project match" : "Matched project metadata").styled(style -> style.withColor(remoteData.approximateProject() ? 0xFFD9C48F : 0xFFE6EDF5)));
        } else if (remoteData != null && remoteData.loading()) {
            lines.add(Text.literal("Loading provider metadata").styled(style -> style.withColor(0xFF8FC5FF)));
        } else {
            lines.add(Text.literal("Best available match").styled(style -> style.withColor(0xFFE6EDF5)));
            lines.add(Text.literal("Uses Modrinth project metadata when a shaderpack match is found.").styled(style -> style.withColor(0xFFB8C4D2)));
        }
        return lines;
    }

    private void openSelectedShaderWebsite() {
        RemoteShaderData remoteData = remoteFor(this.selectedPack);
        String url = remoteData.found() && !remoteData.slug().isBlank()
                ? "https://modrinth.com/shader/" + remoteData.slug()
                : "https://modrinth.com/shaders";
        net.minecraft.util.Util.getOperatingSystem().open(url);
    }

    private String sanitizeKey(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String selectedStatus() {
        if (this.selectedPack == null) {
            return "No shader selected";
        }
        return shaderState(this.selectedPack);
    }

    private void rebuildList() {
        if (this.shaderListWidget == null) {
            return;
        }
        this.shaderListWidget.clearShaderRows();
        String query = this.searchField == null ? "" : this.searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<ShaderPackEntry> entries = discoverShaders().stream()
                .filter(entry -> query.isBlank() || entry.displayName().toLowerCase(Locale.ROOT).contains(query) || entry.fileName().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(ShaderPackEntry::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (ShaderPackEntry entry : entries) {
            this.shaderListWidget.addShader(entry);
        }
        if (this.selectedPack == null || entries.stream().noneMatch(entry -> entry.fileName().equals(this.selectedPack.fileName()))) {
            this.selectedPack = entries.isEmpty() ? null : entries.get(0);
        }
        updateButtons();
    }

    private List<ShaderPackEntry> discoverShaders() {
        ensureDirectory();
        List<ShaderPackEntry> entries = new ArrayList<>();
        try (var stream = Files.list(this.shaderpacksDir)) {
            stream.filter(this::isShaderCandidate).forEach(path -> entries.add(new ShaderPackEntry(path)));
        } catch (Exception ignored) {
        }
        return entries;
    }

    private boolean isShaderCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isDirectory(path) || name.endsWith(".zip");
    }

    private void setSelectedShader(ShaderPackEntry entry) {
        this.selectedPack = entry;
        updateButtons();
    }

    private void updateButtons() {
        boolean hasSelection = this.selectedPack != null;
        if (this.enableDisableButton != null) {
            this.enableDisableButton.active = hasSelection;
            boolean selected = hasSelection && this.selectedPack.matches(currentShaderPackName());
            boolean selectedActive = selected && shadersEnabled();
            this.enableDisableButton.setMessage(Text.literal(selected ? selectedActive ? "Disable" : "Enable" : "Select"));
        }
        if (this.deleteButton != null) {
            this.deleteButton.active = hasSelection;
        }
        if (this.websiteButton != null) {
            this.websiteButton.active = true;
        }
        if (this.configButton != null) {
            this.configButton.active = hasSelection && selectedShaderHasConfig();
        }
        if (this.shadersEnabledCheckbox != null && this.shadersEnabledCheckbox.isChecked() != shadersEnabled()) {
            this.shadersEnabledCheckbox.onPress();
        }
    }

    private void setShadersEnabledFromCheckbox(boolean enabled) {
        if (enabled) {
            String current = currentShaderPackName();
            if ((current == null || current.isBlank()) && this.selectedPack != null) {
                current = this.selectedPack.fileName();
            }
            setIrisShader(current, true);
            this.status = current == null || current.isBlank() ? "Shaders enabled." : "Shaders enabled with " + current + ".";
        } else {
            setIrisShaderEnabledOnly(false);
            this.status = "Shaders disabled.";
        }
        updateButtons();
    }

    private void toggleSelectedShader() {
        if (this.selectedPack == null) {
            return;
        }
        boolean selectedActive = this.selectedPack.matches(currentShaderPackName()) && shadersEnabled();
        boolean selected = this.selectedPack.matches(currentShaderPackName());
        if (selectedActive) {
            setIrisShader(null, false);
            this.status = "Shaders disabled.";
        } else if (selected) {
            setIrisShader(this.selectedPack.fileName(), true);
            this.status = "Enabled " + this.selectedPack.displayName() + ".";
        } else {
            setIrisShader(this.selectedPack.fileName(), shadersEnabled());
            this.status = "Selected " + this.selectedPack.displayName() + ".";
        }
        updateButtons();
    }

    private boolean selectedShaderHasConfig() {
        return this.selectedPack != null && isIrisInstalled() && shaderPackHasOptionData(this.selectedPack.path());
    }

    private void openSelectedShaderConfig() {
        if (this.selectedPack == null) {
            return;
        }
        if (!isIrisInstalled()) {
            this.status = "Iris is not installed.";
            updateButtons();
            return;
        }
        if (!shaderPackHasOptionData(this.selectedPack.path())) {
            this.status = "No Iris shader options were detected for " + this.selectedPack.displayName() + ".";
            updateButtons();
            return;
        }
        setIrisShader(this.selectedPack.fileName(), shadersEnabled());
        Screen irisScreen = createIrisShaderPackScreen();
        if (irisScreen == null || this.client == null) {
            this.status = "Iris shader config screen is unavailable.";
            updateButtons();
            return;
        }
        setIrisOptionMenuOpen(irisScreen);
        this.client.setScreen(irisScreen);
    }

    private Screen createIrisShaderPackScreen() {
        try {
            Class<?> screenClass = Class.forName("net.irisshaders.iris.gui.screen.ShaderPackScreen");
            Constructor<?> constructor = screenClass.getDeclaredConstructor(Screen.class);
            constructor.setAccessible(true);
            Object screen = constructor.newInstance(this);
            return screen instanceof Screen cast ? cast : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private void setIrisOptionMenuOpen(Screen irisScreen) {
        try {
            Field field = irisScreen.getClass().getDeclaredField("optionMenuOpen");
            field.setAccessible(true);
            field.setBoolean(irisScreen, true);
        } catch (Exception ignored) {
        }
    }

    private boolean shaderPackHasOptionData(Path path) {
        if (path == null) {
            return false;
        }
        if (generatedIrisOptionsFileExists(path)) {
            return true;
        }
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path, 6)) {
                    return stream
                            .filter(Files::isRegularFile)
                            .limit(320)
                            .anyMatch(this::shaderOptionFileContainsOptions);
                }
            }
            if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    int inspected = 0;
                    while (entries.hasMoreElements() && inspected < 320) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !isShaderOptionDataName(entry.getName())) {
                            continue;
                        }
                        inspected++;
                        try (InputStream input = zip.getInputStream(entry)) {
                            String text = new String(input.readNBytes(96_000), StandardCharsets.UTF_8);
                            if (containsShaderOptionData(text)) {
                                return true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean generatedIrisOptionsFileExists(Path shaderPath) {
        String fileName = shaderPath.getFileName().toString();
        List<String> candidates = new ArrayList<>();
        candidates.add(fileName + ".txt");
        if (fileName.endsWith(".zip")) {
            candidates.add(fileName.substring(0, fileName.length() - 4) + ".txt");
        }
        candidates.add(fileName.replaceAll("\\.zip$", "") + ".properties");
        for (String candidate : candidates) {
            if (Files.isRegularFile(this.shaderpacksDir.resolve(candidate))) {
                return true;
            }
        }
        return false;
    }

    private boolean shaderOptionFileContainsOptions(Path path) {
        String name = path.getFileName().toString();
        if (!isShaderOptionDataName(name)) {
            return false;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return containsShaderOptionData(text);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isShaderOptionDataName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("shaders.properties")
                || lower.endsWith(".properties")
                || lower.endsWith(".vsh")
                || lower.endsWith(".fsh")
                || lower.endsWith(".gsh")
                || lower.endsWith(".glsl")
                || lower.endsWith(".inc");
    }

    private boolean containsShaderOptionData(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("screen=")
                || lower.contains("screen =")
                || lower.contains("sliders=")
                || lower.contains("sliders =")
                || lower.contains("profile.")
                || lower.contains("option.")
                || text.matches("(?s).*//\\s*\\[[^\\]]+\\].*");
    }

    private void deleteSelectedShader() {
        if (this.selectedPack == null) {
            return;
        }
        try {
            if (Files.isDirectory(this.selectedPack.path())) {
                org.apache.commons.io.FileUtils.deleteDirectory(this.selectedPack.path().toFile());
            } else {
                Files.deleteIfExists(this.selectedPack.path());
            }
            this.status = "Deleted " + this.selectedPack.displayName() + ".";
            this.selectedPack = null;
            rebuildList();
        } catch (Exception exception) {
            this.status = "Delete failed: " + exception.getMessage();
        }
    }

    private void confirmDeleteSelectedShader() {
        if (this.selectedPack == null) {
            return;
        }
        String displayName = this.selectedPack.displayName();
        if (this.client == null) {
            deleteSelectedShader();
            return;
        }
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                deleteSelectedShader();
            }
            if (this.client != null) {
                this.client.setScreen(this);
            }
        }, Text.literal("Delete Shader"), Text.literal("Are you sure you want to delete " + displayName + "?"), Text.literal("Delete"), Text.literal("Cancel")));
    }

    private void importShader() {
        String file = TinyFileDialogs.tinyfd_openFileDialog("Import Shader", this.shaderpacksDir.toAbsolutePath().toString(), null, null, false);
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            Path source = Path.of(file);
            ensureDirectory();
            Path target = this.shaderpacksDir.resolve(source.getFileName()).normalize();
            if (!target.startsWith(this.shaderpacksDir.normalize())) {
                return;
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            this.status = "Imported " + source.getFileName() + ".";
            rebuildList();
        } catch (Exception exception) {
            this.status = "Import failed: " + exception.getMessage();
        }
    }

    private void openFolderPopupAtPointer(File folder) {
        if (folder == null) {
            return;
        }
        ensureDirectory();
        this.pendingFolderOpenDirectory = folder;
        this.folderOpenPopup.toggleAtPointer(this.lastMouseX, this.lastMouseY, this.width, this.height, FolderOpenHelper.menuEntries());
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(this.shaderpacksDir);
        } catch (Exception ignored) {
        }
    }

    private boolean isIrisInstalled() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    private String currentShaderPackName() {
        if (isIrisInstalled()) {
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Object config = iris.getMethod("getIrisConfig").invoke(null);
                Object optional = config.getClass().getMethod("getShaderPackName").invoke(config);
                if (optional instanceof java.util.Optional<?> value && value.isPresent()) {
                    return String.valueOf(value.get());
                }
            } catch (Exception ignored) {
            }
        }
        Properties properties = readIrisProperties();
        return properties.getProperty("shaderPack", "");
    }

    private boolean shadersEnabled() {
        if (isIrisInstalled()) {
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Object config = iris.getMethod("getIrisConfig").invoke(null);
                Object enabled = config.getClass().getMethod("areShadersEnabled").invoke(config);
                if (enabled instanceof Boolean value) {
                    return value;
                }
            } catch (Exception ignored) {
            }
        }
        return Boolean.parseBoolean(readIrisProperties().getProperty("enableShaders", "false"));
    }

    private void setIrisShader(String shaderName, boolean enabled) {
        boolean reflected = false;
        if (isIrisInstalled()) {
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Object config = iris.getMethod("getIrisConfig").invoke(null);
                Method setShaderPackName = config.getClass().getMethod("setShaderPackName", String.class);
                Method setShadersEnabled = config.getClass().getMethod("setShadersEnabled", boolean.class);
                Method save = config.getClass().getMethod("save");
                setShaderPackName.invoke(config, shaderName);
                setShadersEnabled.invoke(config, enabled);
                save.invoke(config);
                iris.getMethod("reload").invoke(null);
                reflected = true;
            } catch (Exception exception) {
                this.status = "Iris apply failed: " + exception.getMessage();
            }
        }
        if (!reflected) {
            writeIrisProperties(shaderName, enabled);
        }
    }

    private void setIrisShaderEnabledOnly(boolean enabled) {
        boolean reflected = false;
        if (isIrisInstalled()) {
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Object config = iris.getMethod("getIrisConfig").invoke(null);
                Method setShadersEnabled = config.getClass().getMethod("setShadersEnabled", boolean.class);
                Method save = config.getClass().getMethod("save");
                setShadersEnabled.invoke(config, enabled);
                save.invoke(config);
                iris.getMethod("reload").invoke(null);
                reflected = true;
            } catch (Exception exception) {
                this.status = "Iris apply failed: " + exception.getMessage();
            }
        }
        if (!reflected) {
            Properties properties = readIrisProperties();
            properties.setProperty("enableShaders", String.valueOf(enabled));
            Path path = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
            try {
                Files.createDirectories(path.getParent());
                try (var output = Files.newOutputStream(path)) {
                    properties.store(output, "Iris shader settings");
                }
            } catch (Exception ignored) {
            }
        }
    }

    private Properties readIrisProperties() {
        Properties properties = new Properties();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (Exception ignored) {
        }
        return properties;
    }

    private void writeIrisProperties(String shaderName, boolean enabled) {
        Properties properties = readIrisProperties();
        properties.setProperty("enableShaders", String.valueOf(enabled));
        if (shaderName == null || shaderName.isBlank()) {
            properties.remove("shaderPack");
        } else {
            properties.setProperty("shaderPack", shaderName);
        }
        Path path = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
        try {
            Files.createDirectories(path.getParent());
            try (var output = Files.newOutputStream(path)) {
                properties.store(output, "Iris shader settings");
            }
        } catch (Exception ignored) {
        }
    }

    private record ShaderPackEntry(Path path) {
        private String fileName() {
            return path.getFileName().toString();
        }

        private String displayName() {
            String name = fileName();
            return name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        }

        private boolean matches(String current) {
            if (current == null) {
                return false;
            }
            return current.equals(fileName()) || current.equals(displayName());
        }
    }

    private record ShaderPackMetadata(String description, Identifier icon, String packFormat, String metadataFile) {
        private static final ShaderPackMetadata EMPTY = new ShaderPackMetadata("No shader selected.", null, "", "");
    }

    private record RemoteShaderData(boolean found, boolean loading, String provider, String title, String slug, String projectType, String description, String body, String author, String downloads, String followers, String versionTitle, String versionNumber, String versionType, int versionCount, String iconUrl, String license, String sourceUrl, String issuesUrl, String wikiUrl, String discordUrl, String publishedAt, String updatedAt, String clientSide, String serverSide, List<String> loaders, List<String> gameVersions, List<String> categories, boolean approximateProject) {
        private static final RemoteShaderData EMPTY = new RemoteShaderData(false, false, "", "", "", "", "", "", "", "", "", "", "", "", 0, "", "", "", "", "", "", "", "", "", "", List.of(), List.of(), List.of(), false);
        private static final RemoteShaderData LOADING = new RemoteShaderData(false, true, "", "", "", "", "", "", "", "", "", "", "", "", 0, "", "", "", "", "", "", "", "", "", "", List.of(), List.of(), List.of(), false);
    }

    private final class ShaderListWidget extends EntryListWidget<ShaderRow> {
        private ShaderListWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
        }

        private void clearShaderRows() {
            super.clearEntries();
        }

        private void addShader(ShaderPackEntry entry) {
            addEntry(new ShaderRow(entry));
        }

        @Override
        public int getRowWidth() {
            return this.width - 4;
        }

        @Override
        public int getRowLeft() {
            return this.left + 2;
        }

        @Override
        protected int getScrollbarPositionX() {
            return BrowserLayoutHelper.LIST_INNER_RIGHT - 6;
        }

        @Override
        protected void renderBackground(DrawContext context) {
        }

        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {
        }
    }

    private final class ShaderRow extends EntryListWidget.Entry<ShaderRow> {
        private final ShaderPackEntry entry;

        private ShaderRow(ShaderPackEntry entry) {
            this.entry = entry;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            boolean selected = selectedPack != null && selectedPack.fileName().equals(this.entry.fileName());
            ShaderPackMetadata metadata = metadataFor(this.entry);
            RemoteShaderData remoteData = remoteFor(this.entry);
            Identifier icon = iconFor(this.entry, metadata, remoteData);
            String title = remoteData.found() && !remoteData.title().isBlank() ? remoteData.title() : this.entry.displayName();
            String state = shaderState(this.entry);
            String metadataLine = state + "  |  " + this.entry.fileName();
            ContentBrowserListRowRenderer.renderModMenuStyleItem(context, textRenderer, icon, FALLBACK_ICON, x, y, entryWidth, title, metadataLine);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            setSelectedShader(this.entry);
            return true;
        }
    }
}
