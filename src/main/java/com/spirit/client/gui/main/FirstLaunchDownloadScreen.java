package com.spirit.client.gui.main;

import com.spirit.Main;
import com.spirit.koil.api.design.DesignLoader;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import com.spirit.koil.api.util.web.DownloadProgressTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class FirstLaunchDownloadScreen extends Screen implements DownloadProgressTracker.Listener {
    private static final int HEADER_HEIGHT = 60;
    private static final int SIDEBAR_WIDTH = 125;
    private static final int CONTENT_LEFT = 127;
    private static final int CONTENT_TOP = 62;
    private static final int LINE_HEIGHT = 12;

    private final Set<String> requestedPaths = new LinkedHashSet<>();
    private final Set<String> downloadedPaths = new LinkedHashSet<>();
    private final List<ScreenEvent> eventLines = new ArrayList<>();
    private ButtonWidget continueButton;
    private volatile boolean started;
    private volatile boolean complete;
    private volatile boolean failed;
    private volatile String status = "Preparing Koil runtime downloads...";
    private volatile String currentUrl = "";
    private volatile String currentTarget = "";
    private volatile long currentBytes;
    private volatile long currentTotalBytes = -1;
    private volatile int requestedCount;
    private volatile int completedCount;
    private volatile int skippedCount;
    private volatile int errorCount;
    private final long createdAtMs = System.currentTimeMillis();
    private volatile long completedAtMs;
    private long lastDesignReloadMs;
    private int treeScrollOffset;
    private int eventScrollOffset;
    private int treeTotalRows;
    private int treeVisibleRows;
    private int eventTotalRows;
    private int eventVisibleRows;
    private boolean draggingTreeScrollbar;
    private boolean draggingEventScrollbar;
    private boolean eventsPinnedToBottom = true;
    private int treeScrollbarDragOffset;
    private int eventScrollbarDragOffset;
    private int treeViewportX;
    private int treeViewportY;
    private int treeViewportWidth;
    private int treeViewportHeight;
    private int eventViewportX;
    private int eventViewportY;
    private int eventViewportWidth;
    private int eventViewportHeight;

    public FirstLaunchDownloadScreen() {
        super(Text.literal("Koil File Download"));
    }

    @Override
    protected void init() {
        DownloadProgressTracker.removeListener(this);
        DownloadProgressTracker.addListener(this);
        this.continueButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Continue"), button -> {
            MinecraftClient.getInstance().setScreen(null);
        }).dimensions(continueButtonX(), continueButtonY(), 84, 18).build());
        this.continueButton.active = this.complete;
        this.continueButton.visible = this.complete;
        startBootstrap();
    }

    private void startBootstrap() {
        if (this.started) {
            return;
        }
        this.started = true;
        Thread thread = new Thread(() -> {
            try {
                Main.refreshBootstrapFiles();
                Main.reloadDesign();
                DesignLoader.reloadLoadingTexture();
                this.status = this.failed ? "Finished with errors. Review the list below." : "Koil files are ready.";
            } catch (Exception exception) {
                this.failed = true;
                this.status = "Bootstrap failed: " + exception.getMessage();
                    addEventLine("Error", exception.getMessage(), "", 0xFFFF8C8C);
                Main.SUBLOGGER.logE("First-Launch thread", "First launch bootstrap screen failed: " + exception.getMessage());
            } finally {
                this.complete = true;
                this.completedAtMs = System.currentTimeMillis();
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.execute(() -> {
                        if (this.continueButton != null) {
                            this.continueButton.active = true;
                            this.continueButton.visible = true;
                        }
                    });
                }
            }
        }, "koil-first-launch-file-download-screen");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void removed() {
        DownloadProgressTracker.removeListener(this);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return this.complete;
    }

    @Override
    public void onDownloadEvent(DownloadProgressTracker.DownloadEvent event) {
        synchronized (this) {
            this.currentUrl = event.url() == null ? "" : event.url();
            this.currentTarget = normalizePath(event.targetPath());
            this.currentBytes = event.bytesRead();
            this.currentTotalBytes = event.totalBytes();
            this.status = event.message() == null ? event.type().name() : event.message();
            switch (event.type()) {
                case REQUEST -> {
                    this.requestedCount++;
                    if (!this.currentTarget.isBlank()) {
                        this.requestedPaths.add(this.currentTarget);
                    }
                    addEventLine("Request", lastPathName(this.currentTarget), this.currentTarget, new Color(uiColorHeaderSubTitleText, true).getRGB());
                }
                case COMPLETE -> {
                    this.completedCount++;
                    if (!this.currentTarget.isBlank()) {
                        this.downloadedPaths.add(this.currentTarget);
                    }
                    addEventLine("Complete", lastPathName(this.currentTarget), this.currentTarget, 0xFF9FE6B0);
                }
                case SKIP -> {
                    this.skippedCount++;
                    if (!this.currentTarget.isBlank()) {
                        this.downloadedPaths.add(this.currentTarget);
                    }
                    addEventLine("Skipped", lastPathName(this.currentTarget), this.currentTarget, 0xFFB8C5D6);
                }
                case ERROR -> {
                    this.failed = true;
                    this.errorCount++;
                    addEventLine("Error", this.status, this.currentTarget, 0xFFFF8C8C);
                }
                case INFO -> addEventLine("Info", this.status, this.currentTarget, new Color(uiColorHeaderSubTitleText, true).getRGB());
                case PROGRESS -> {
                }
            }
        }
    }

    private synchronized void addEventLine(String label, String title, String detail, int color) {
        if ((title == null || title.isBlank()) && (detail == null || detail.isBlank())) {
            return;
        }
        this.eventLines.add(new ScreenEvent(label, title == null ? "" : title, detail == null ? "" : detail, color));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (System.currentTimeMillis() - this.lastDesignReloadMs > 900L) {
            this.lastDesignReloadMs = System.currentTimeMillis();
            Main.reloadDesign();
            DesignLoader.reloadLoadingTexture();
        }
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        renderPanel(context);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderPanel(DrawContext context) {
        int panelRight = this.width;
        int panelBottom = this.height;
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(MinecraftClient.getInstance()));
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 0, this.width, HEADER_HEIGHT, new Color(uiColorHeader, true).getRGB());
        context.drawBorder(0, 0, this.width, HEADER_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, CONTENT_TOP, SIDEBAR_WIDTH, panelBottom, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, CONTENT_TOP, SIDEBAR_WIDTH, panelBottom - CONTENT_TOP, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(CONTENT_LEFT, CONTENT_TOP, panelRight, panelBottom, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(CONTENT_LEFT, CONTENT_TOP, panelRight - CONTENT_LEFT, panelBottom - CONTENT_TOP, new Color(uiColorBackgroundBorder, true).getRGB());

        context.drawTexture(Main.LOGO_TEXTURE, 10, 5, 0, 0, 45, 45, 45, 45);
        context.getMatrices().push();
        context.getMatrices().scale(2F, 2F, 1F);
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "File Download - First Launch", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);

        renderSidebar(context, panelBottom);

        int x = CONTENT_LEFT + 12;
        int y = CONTENT_TOP + 12;
        context.drawText(this.textRenderer, this.status, x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        y += 15;
        renderProgressBar(context, x, y, panelRight - x - 18, 10);
        y += 18;

        String countLine = "Files: " + this.completedCount + " done  " + this.skippedCount + " skipped  " + this.errorCount + " errors  " + elapsedSeconds() + "s";
        context.drawText(this.textRenderer, countLine, x, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        y += LINE_HEIGHT;
        context.drawText(this.textRenderer, "Target: " + trim(this.currentTarget, 90), x, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        y += LINE_HEIGHT;
        context.drawText(this.textRenderer, "URL: " + trim(this.currentUrl, 96), x, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);

        int splitX = CONTENT_LEFT + Math.max(250, (panelRight - CONTENT_LEFT) / 2);
        int listTop = y + 12;
        int listBottom = panelBottom - 8;
        if (this.continueButton != null) {
            this.continueButton.setX(continueButtonX());
            this.continueButton.setY(continueButtonY());
        }
        context.fill(x, listTop, splitX - 8, listBottom, withAlpha(uiColorContentBase, 116));
        context.drawBorder(x, listTop, splitX - x - 8, listBottom - listTop, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(splitX, listTop, panelRight - 12, listBottom, withAlpha(uiColorContentBase, 116));
        context.drawBorder(splitX, listTop, panelRight - splitX - 12, listBottom - listTop, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, "Downloaded tree", x + 6, listTop + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, "Recent events", splitX + 6, listTop + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        this.treeViewportX = x + 6;
        this.treeViewportY = listTop + 20;
        this.treeViewportWidth = splitX - x - 24;
        this.treeViewportHeight = Math.max(1, listBottom - 6 - this.treeViewportY);
        this.eventViewportX = splitX + 6;
        this.eventViewportY = listTop + 20;
        this.eventViewportWidth = panelRight - splitX - 28;
        this.eventViewportHeight = Math.max(1, listBottom - 6 - this.eventViewportY);
        renderTree(context, this.treeViewportX, this.treeViewportY, this.treeViewportWidth, this.treeViewportY + this.treeViewportHeight);
        renderEvents(context, this.eventViewportX, this.eventViewportY, this.eventViewportWidth, this.eventViewportY + this.eventViewportHeight);
    }

    private void renderSidebar(DrawContext context, int panelBottom) {
        int x = 10;
        int y = CONTENT_TOP + 14;
        context.drawText(this.textRenderer, "Bootstrap", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        y += 18;
        renderSidebarMetric(context, x, y, "Complete", String.valueOf(this.completedCount), 0xFF9FE6B0);
        y += 22;
        renderSidebarMetric(context, x, y, "Skipped", String.valueOf(this.skippedCount), 0xFFB8C5D6);
        y += 22;
        renderSidebarMetric(context, x, y, "Errors", String.valueOf(this.errorCount), this.errorCount > 0 ? 0xFFFF8C8C : new Color(uiColorHeaderSubTitleText, true).getRGB());
        y += 28;
        context.drawText(this.textRenderer, "Elapsed", x, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, elapsedSeconds() + "s", x, y + 11, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        if (this.complete) {
            context.fill(x, panelBottom - 34, SIDEBAR_WIDTH - 12, panelBottom - 33, this.failed ? 0xFFFF8C8C : 0xFF9FE6B0);
            context.drawText(this.textRenderer, this.failed ? "Finished with errors" : "Ready", x, panelBottom - 28, this.failed ? 0xFFFF8C8C : 0xFF9FE6B0, false);
        }
    }

    private int continueButtonX() {
        return Math.max(CONTENT_LEFT + 12, this.width - 104);
    }

    private int continueButtonY() {
        return Math.max(CONTENT_TOP + 12, this.height - 34);
    }

    private void renderSidebarMetric(DrawContext context, int x, int y, String label, String value, int color) {
        context.fill(x, y + 2, x + 2, y + 16, color);
        context.drawText(this.textRenderer, label, x + 8, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, value, x + 8, y + 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private void renderProgressBar(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, withAlpha(uiColorBackgroundBorder, 100));
        float fileProgress = this.currentTotalBytes > 0 ? Math.min(1F, this.currentBytes / (float) this.currentTotalBytes) : 0F;
        float countProgress = this.requestedCount > 0 ? Math.min(1F, (this.completedCount + this.skippedCount + this.errorCount) / (float) this.requestedCount) : 0F;
        float progress = Math.max(fileProgress, countProgress);
        if (this.complete && !this.failed) {
            progress = 1F;
        }
        int fillWidth = Math.max(0, Math.min(width, (int) (width * progress)));
        int progressColor = this.failed ? 0xFFE07070 : this.complete ? 0xFF65D67A : 0xFFE0B84A;
        context.fill(x, y, x + fillWidth, y + height, progressColor);
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        String bytes = bytesLabel(this.currentBytes, this.currentTotalBytes);
        context.drawText(this.textRenderer, bytes, x + width - this.textRenderer.getWidth(bytes) - 4, y - 11, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
    }

    private void renderTree(DrawContext context, int x, int y, int width, int bottom) {
        List<DownloadTreeRow> rows;
        synchronized (this) {
            rows = buildDownloadTreeRows();
        }
        int rowHeight = 18;
        int visibleRows = Math.max(1, (bottom - y) / rowHeight);
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        this.treeScrollOffset = Math.max(0, Math.min(maxScroll, this.treeScrollOffset));
        this.treeTotalRows = rows.size();
        this.treeVisibleRows = visibleRows;
        context.enableScissor(x, y, x + width, bottom);
        int renderY = y;
        for (int i = this.treeScrollOffset; i < rows.size() && renderY + rowHeight <= bottom; i++) {
            DownloadTreeRow row = rows.get(i);
            int depth = Math.min(6, row.depth());
            int rowX = x + depth * 12;
            renderTreeConnectors(context, x, rowX, renderY, depth, row.last());
            renderTreeIcon(context, row, rowX, renderY);
            int color = row.directory()
                    ? new Color(uiColorIDEFolderNameText, true).getRGB()
                    : row.completed() ? new Color(uiColorIDEFileNameText, true).getRGB() : withAlpha(uiColorIDEFileNameText, 136);
            context.drawText(this.textRenderer, trim(row.name(), Math.max(10, (width - (rowX - x) - 24) / 6)), rowX + 20, renderY + 5, color, false);
            renderY += rowHeight;
        }
        context.disableScissor();
        renderScrollbar(context, x + width - 4, y, bottom - y, rows.size(), visibleRows, this.treeScrollOffset);
    }

    private void renderTreeConnectors(DrawContext context, int rootX, int rowX, int y, int depth, boolean last) {
        if (depth <= 0) {
            return;
        }
        int color = withAlpha(uiColorBackgroundBorder, 120);
        int guideX = rowX - 8;
        context.fill(guideX, y - 1, guideX + 1, last ? y + 8 : y + 18, color);
        context.fill(guideX, y + 8, rowX - 2, y + 9, color);
        for (int d = 1; d < depth; d++) {
            int ancestorX = rootX + d * 12 - 8;
            context.fill(ancestorX, y - 1, ancestorX + 1, y + 18, withAlpha(uiColorBackgroundBorder, 70));
        }
    }

    private void renderTreeIcon(DrawContext context, DownloadTreeRow row, int x, int y) {
        if (!row.completed() && !row.directory()) {
            context.drawBorder(x, y + 1, 14, 14, withAlpha(uiColorIDEFileNameText, 150));
            context.fill(x + 3, y + 4, x + 11, y + 5, withAlpha(uiColorIDEFileNameText, 70));
            context.fill(x + 3, y + 8, x + 9, y + 9, withAlpha(uiColorIDEFileNameText, 70));
            return;
        }
        Identifier icon = row.directory() ? safeIcon("folder") : safeFileIcon(row.name());
        if (icon == null) {
            renderEmptyIconBox(context, x, y);
            return;
        }
        context.drawTexture(icon, x, y + 1, 0, 0, 16, 16, 16, 16);
    }

    private Identifier safeFileIcon(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.equals(".ds_store") || lower.startsWith(".")) {
            return safeIcon("env", "file");
        }
        if (lower.endsWith(".json")) {
            return safeIcon("json");
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
            return safeIcon("image");
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3")) {
            return safeIcon("audio");
        }
        if (lower.endsWith(".zip") || lower.endsWith(".jar")) {
            return safeIcon("zip");
        }
        if (lower.endsWith(".md")) {
            return safeIcon("md", "text");
        }
        if (lower.endsWith(".txt")) {
            return safeIcon("txt", "text");
        }
        return safeIcon("file");
    }

    private Identifier safeIcon(String... names) {
        boolean wantsColored = false;
        try {
            wantsColored = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "wantsColoredFileIcons").getAsBoolean();
        } catch (Exception ignored) {
        }
        for (String name : names) {
            Identifier icon = loadIconVariant(name, wantsColored ? "_colored.png" : ".png");
            if (icon != null) {
                return icon;
            }
            if (wantsColored) {
                icon = loadIconVariant(name, ".png");
                if (icon != null) {
                    return icon;
                }
            }
        }
        if (names.length > 0 && !"file".equals(names[names.length - 1])) {
            return safeIcon("file");
        }
        return null;
    }

    private Identifier loadIconVariant(String name, String suffix) {
        try {
            File iconFile = new File(Main.uiImageDirectory, name + suffix);
            if (!iconFile.exists() || !iconFile.isFile()) {
                return null;
            }
            var texture = ImageTextureService.loadPersistentTexture(iconFile, "koil_first_launch_icon", name + suffix);
            return texture == null ? null : texture.textureId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void renderEmptyIconBox(DrawContext context, int x, int y) {
        context.drawBorder(x, y + 1, 14, 14, withAlpha(uiColorIDEFileNameText, 150));
    }

    private List<DownloadTreeRow> buildDownloadTreeRows() {
        LinkedHashSet<String> allPaths = new LinkedHashSet<>();
        allPaths.addAll(this.requestedPaths);
        allPaths.addAll(this.downloadedPaths);

        LinkedHashSet<String> rowPaths = new LinkedHashSet<>();
        for (String path : allPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalizedPath = normalizeTreePath(path);
            if (normalizedPath.isBlank()) {
                continue;
            }
            String[] parts = normalizedPath.split("/");
            StringBuilder current = new StringBuilder();
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                if (current.length() > 0) {
                    current.append('/');
                }
                current.append(part);
                rowPaths.add(current.toString());
            }
        }

        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (String path : rowPaths) {
            childrenByParent.computeIfAbsent(parentPath(path), ignored -> new ArrayList<>()).add(path);
        }
        for (List<String> children : childrenByParent.values()) {
            children.sort(Comparator
                    .comparing((String path) -> !childrenByParent.containsKey(path))
                    .thenComparing(path -> lastPathName(path).toLowerCase()));
        }

        List<DownloadTreeRow> rows = new ArrayList<>();
        appendTreeRows("", 0, childrenByParent, rows);
        return rows;
    }

    private void appendTreeRows(String parent, int depth, Map<String, List<String>> childrenByParent, List<DownloadTreeRow> rows) {
        List<String> children = childrenByParent.get(parent);
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            String path = children.get(i);
            boolean directory = childrenByParent.containsKey(path);
            Path realPath = FabricLoader.getInstance().getGameDir().resolve(path);
            boolean completed = this.downloadedPaths.contains(path) || realPath.toFile().exists();
            rows.add(new DownloadTreeRow(path, lastPathName(path), depth, directory, completed, i == children.size() - 1));
            if (directory) {
                appendTreeRows(path, depth + 1, childrenByParent, rows);
            }
        }
    }

    private String normalizeTreePath(String path) {
        return path == null ? "" : path.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private void renderEvents(DrawContext context, int x, int y, int width, int bottom) {
        List<ScreenEvent> lines;
        synchronized (this) {
            lines = new ArrayList<>(this.eventLines);
        }
        int rowHeight = LINE_HEIGHT * 2;
        int visibleRows = Math.max(1, (bottom - y) / rowHeight);
        int maxScroll = Math.max(0, lines.size() - visibleRows);
        if (this.eventsPinnedToBottom) {
            this.eventScrollOffset = maxScroll;
        }
        this.eventScrollOffset = Math.max(0, Math.min(maxScroll, this.eventScrollOffset));
        this.eventTotalRows = lines.size();
        this.eventVisibleRows = visibleRows;
        context.enableScissor(x, y, x + width, bottom);
        int renderY = y;
        for (int i = this.eventScrollOffset; i < lines.size() && renderY < bottom; i++) {
            ScreenEvent line = lines.get(i);
            context.fill(x, renderY + 2, x + 2, renderY + 10, line.color());
            context.drawText(this.textRenderer, line.label(), x + 7, renderY, line.color(), false);
            context.drawText(this.textRenderer, trim(line.title(), Math.max(12, (width - 72) / 6)), x + 58, renderY, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            renderY += LINE_HEIGHT;
            if (!line.detail().isBlank() && renderY < bottom) {
                context.drawText(this.textRenderer, trim(line.detail(), Math.max(18, width / 6)), x + 12, renderY, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                renderY += LINE_HEIGHT;
            }
        }
        context.disableScissor();
        renderScrollbar(context, x + width - 4, y, bottom - y, lines.size(), visibleRows, this.eventScrollOffset);
    }

    private void renderScrollbar(DrawContext context, int x, int y, int height, int totalRows, int visibleRows, int scrollOffset) {
        if (totalRows <= visibleRows) {
            return;
        }
        context.fill(x, y, x + 2, y + height, withAlpha(uiColorBackgroundBorder, 85));
        int thumbHeight = scrollbarThumbHeight(height, totalRows, visibleRows);
        int thumbY = scrollbarThumbY(y, height, totalRows, visibleRows, scrollOffset);
        context.fill(x - 1, thumbY, x + 3, thumbY + thumbHeight, withAlpha(uiColorIDEFileNameText, 150));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInside(mouseX, mouseY, this.treeViewportX, this.treeViewportY, this.treeViewportWidth, this.treeViewportHeight)) {
            this.treeScrollOffset = Math.max(0, this.treeScrollOffset - (int) Math.signum(amount));
            return true;
        }
        if (isInside(mouseX, mouseY, this.eventViewportX, this.eventViewportY, this.eventViewportWidth, this.eventViewportHeight)) {
            this.eventScrollOffset = Math.max(0, this.eventScrollOffset - (int) Math.signum(amount));
            updateEventPinningFromUserScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverTreeScrollbar(mouseX, mouseY)) {
            this.draggingTreeScrollbar = true;
            this.treeScrollbarDragOffset = scrollbarGrabOffset(mouseY, this.treeViewportY, this.treeViewportHeight, this.treeTotalRows, this.treeVisibleRows, this.treeScrollOffset);
            this.treeScrollOffset = scrollOffsetFromMouse(mouseY, this.treeViewportY, this.treeViewportHeight, this.treeTotalRows, this.treeVisibleRows, this.treeScrollbarDragOffset);
            return true;
        }
        if (button == 0 && isOverEventScrollbar(mouseX, mouseY)) {
            this.draggingEventScrollbar = true;
            this.eventScrollbarDragOffset = scrollbarGrabOffset(mouseY, this.eventViewportY, this.eventViewportHeight, this.eventTotalRows, this.eventVisibleRows, this.eventScrollOffset);
            this.eventScrollOffset = scrollOffsetFromMouse(mouseY, this.eventViewportY, this.eventViewportHeight, this.eventTotalRows, this.eventVisibleRows, this.eventScrollbarDragOffset);
            updateEventPinningFromUserScroll();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.draggingTreeScrollbar) {
            this.treeScrollOffset = scrollOffsetFromMouse(mouseY, this.treeViewportY, this.treeViewportHeight, this.treeTotalRows, this.treeVisibleRows, this.treeScrollbarDragOffset);
            return true;
        }
        if (button == 0 && this.draggingEventScrollbar) {
            this.eventScrollOffset = scrollOffsetFromMouse(mouseY, this.eventViewportY, this.eventViewportHeight, this.eventTotalRows, this.eventVisibleRows, this.eventScrollbarDragOffset);
            updateEventPinningFromUserScroll();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (this.draggingTreeScrollbar || this.draggingEventScrollbar)) {
            this.draggingTreeScrollbar = false;
            this.draggingEventScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isOverTreeScrollbar(double mouseX, double mouseY) {
        return this.treeTotalRows > this.treeVisibleRows
                && mouseX >= this.treeViewportX + this.treeViewportWidth - 10
                && mouseX <= this.treeViewportX + this.treeViewportWidth + 6
                && mouseY >= this.treeViewportY
                && mouseY <= this.treeViewportY + this.treeViewportHeight;
    }

    private boolean isOverEventScrollbar(double mouseX, double mouseY) {
        return this.eventTotalRows > this.eventVisibleRows
                && mouseX >= this.eventViewportX + this.eventViewportWidth - 10
                && mouseX <= this.eventViewportX + this.eventViewportWidth + 6
                && mouseY >= this.eventViewportY
                && mouseY <= this.eventViewportY + this.eventViewportHeight;
    }

    private void updateEventPinningFromUserScroll() {
        int maxScroll = Math.max(0, this.eventTotalRows - this.eventVisibleRows);
        this.eventsPinnedToBottom = this.eventScrollOffset >= maxScroll;
    }

    private int scrollbarGrabOffset(double mouseY, int y, int height, int totalRows, int visibleRows, int scrollOffset) {
        int thumbHeight = scrollbarThumbHeight(height, totalRows, visibleRows);
        int thumbY = scrollbarThumbY(y, height, totalRows, visibleRows, scrollOffset);
        if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
            return (int) mouseY - thumbY;
        }
        return thumbHeight / 2;
    }

    private int scrollOffsetFromMouse(double mouseY, int y, int height, int totalRows, int visibleRows, int dragOffset) {
        if (totalRows <= visibleRows) {
            return 0;
        }
        int thumbHeight = scrollbarThumbHeight(height, totalRows, visibleRows);
        int thumbTravel = Math.max(1, height - thumbHeight);
        int maxScroll = Math.max(1, totalRows - visibleRows);
        int relativeY = Math.max(0, Math.min(thumbTravel, (int) mouseY - y - dragOffset));
        return Math.max(0, Math.min(maxScroll, Math.round(relativeY * maxScroll / (float) thumbTravel)));
    }

    private int scrollbarThumbHeight(int height, int totalRows, int visibleRows) {
        return Math.min(height, Math.max(18, height * visibleRows / Math.max(1, totalRows)));
    }

    private int scrollbarThumbY(int y, int height, int totalRows, int visibleRows, int scrollOffset) {
        int thumbHeight = scrollbarThumbHeight(height, totalRows, visibleRows);
        int thumbTravel = Math.max(1, height - thumbHeight);
        int maxScroll = Math.max(1, totalRows - visibleRows);
        return y + (thumbTravel * Math.max(0, Math.min(maxScroll, scrollOffset)) / maxScroll);
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        try {
            Path root = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            if (path.startsWith(root)) {
                return root.relativize(path).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }
        return rawPath.replace('\\', '/');
    }

    private long elapsedSeconds() {
        long endMs = this.completedAtMs > 0 ? this.completedAtMs : System.currentTimeMillis();
        return Math.max(0, (endMs - this.createdAtMs) / 1000L);
    }

    private static String bytesLabel(long bytes, long total) {
        if (total <= 0) {
            return bytes > 0 ? readableBytes(bytes) : "waiting";
        }
        return readableBytes(bytes) + " / " + readableBytes(total);
    }

    private static String readableBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String lastPathName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    private static String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha))).getRGB();
    }

    private record ScreenEvent(String label, String title, String detail, int color) {
    }

    private record DownloadTreeRow(String path, String name, int depth, boolean directory, boolean completed, boolean last) {
    }
}
