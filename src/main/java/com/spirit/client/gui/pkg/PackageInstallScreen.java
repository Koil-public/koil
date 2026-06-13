package com.spirit.client.gui.pkg;

import com.spirit.Main;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.KoilPackageManager;
import com.spirit.koil.api.util.file.KoilPackageManager.PackageEntry;
import com.spirit.koil.api.util.file.KoilPackageManager.PendingPackage;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class PackageInstallScreen extends Screen {
    private enum Phase {
        REVIEW,
        COMPARE,
        APPLIED
    }

    private static final int HEADER_HEIGHT = 60;
    private static final int CONTENT_TOP = 62;
    private static final int SIDEBAR_WIDTH = 156;
    private static final int TREE_ROW_HEIGHT = 16;

    private final Screen parent;
    private final PendingPackage pendingPackage;
    private Phase phase = Phase.REVIEW;
    private ButtonWidget yesButton;
    private ButtonWidget noButton;
    private ButtonWidget confirmButton;
    private ButtonWidget doneButton;
    private String status = "Review package changes before Koil rewrites this instance.";
    private int leftScroll;
    private int rightScroll;
    private int leftViewportX;
    private int leftViewportY;
    private int leftViewportWidth;
    private int leftViewportHeight;
    private int leftTotalRows;
    private int leftVisibleRows;
    private int rightViewportX;
    private int rightViewportY;
    private int rightViewportWidth;
    private int rightViewportHeight;
    private int rightTotalRows;
    private int rightVisibleRows;
    private boolean draggingLeftScrollbar;
    private boolean draggingRightScrollbar;
    private int leftScrollbarDragOffset;
    private int rightScrollbarDragOffset;

    public PackageInstallScreen(Screen parent, PendingPackage pendingPackage) {
        super(Text.literal("Koil Package Install"));
        this.parent = parent;
        this.pendingPackage = pendingPackage;
    }

    public static PendingPackage firstValidPendingPackage() {
        return KoilPackageManager.findPendingPackages().stream()
                .filter(PendingPackage::valid)
                .findFirst()
                .orElse(null);
    }

    @Override
    protected void init() {
        int buttonY = this.height - 28;
        this.yesButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Yes"), button -> {
            this.phase = Phase.COMPARE;
            this.status = "Confirm the package rewrite. Existing instance files are shown on the left.";
            refreshButtons();
        }).dimensions(this.width - 214, buttonY, 96, 20).build());
        this.noButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("No"), button -> {
            MinecraftClient.getInstance().setScreen(this.parent);
        }).dimensions(this.width - 110, buttonY, 96, 20).build());
        this.confirmButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Apply Package"), button -> applyPackage()).dimensions(this.width - 142, buttonY, 128, 20).build());
        this.doneButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            MinecraftClient.getInstance().setScreen(this.parent);
        }).dimensions(this.width - 110, buttonY, 96, 20).build());
        refreshButtons();
    }

    private void refreshButtons() {
        boolean review = this.phase == Phase.REVIEW;
        boolean compare = this.phase == Phase.COMPARE;
        boolean applied = this.phase == Phase.APPLIED;
        this.yesButton.visible = review;
        this.noButton.visible = review;
        this.confirmButton.visible = compare;
        this.doneButton.visible = applied;
    }

    private void applyPackage() {
        try {
            KoilPackageManager.applyPackage(this.pendingPackage);
            this.phase = Phase.APPLIED;
            this.status = "Package applied. The package source has been removed.";
        } catch (IOException exception) {
            this.status = "Package failed: " + exception.getMessage();
        }
        refreshButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        renderChrome(context);
        renderBody(context);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int contentX = SIDEBAR_WIDTH + 16;
        int contentRight = this.width - 14;
        int gap = 8;
        int mid = contentX + (contentRight - contentX - gap) / 2;
        int delta = -(int) Math.signum(amount) * 3;
        if (this.phase == Phase.REVIEW) {
            this.rightScroll = Math.max(0, this.rightScroll - (int) Math.signum(amount) * 3);
            return true;
        }
        if (this.phase == Phase.APPLIED) {
            this.leftScroll = Math.max(0, this.leftScroll - (int) Math.signum(amount) * 3);
            return true;
        }
        if ((mouseX >= contentX && mouseX <= mid) || mouseX >= mid + gap) {
            applySyncedScrollDelta(delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverLeftScrollbar(mouseX, mouseY)) {
            this.draggingLeftScrollbar = true;
            this.leftScrollbarDragOffset = scrollbarGrabOffset(mouseY, this.leftViewportY, this.leftViewportHeight, this.leftTotalRows, this.leftVisibleRows, this.leftScroll);
            this.leftScroll = scrollOffsetFromMouse(mouseY, this.leftViewportY, this.leftViewportHeight, this.leftTotalRows, this.leftVisibleRows, this.leftScrollbarDragOffset);
            if (this.phase == Phase.COMPARE) {
                syncRightScrollToLeftRatio();
            }
            return true;
        }
        if (button == 0 && isOverRightScrollbar(mouseX, mouseY)) {
            this.draggingRightScrollbar = true;
            this.rightScrollbarDragOffset = scrollbarGrabOffset(mouseY, this.rightViewportY, this.rightViewportHeight, this.rightTotalRows, this.rightVisibleRows, this.rightScroll);
            this.rightScroll = scrollOffsetFromMouse(mouseY, this.rightViewportY, this.rightViewportHeight, this.rightTotalRows, this.rightVisibleRows, this.rightScrollbarDragOffset);
            if (this.phase == Phase.COMPARE) {
                syncLeftScrollToRightRatio();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.draggingLeftScrollbar) {
            this.leftScroll = scrollOffsetFromMouse(mouseY, this.leftViewportY, this.leftViewportHeight, this.leftTotalRows, this.leftVisibleRows, this.leftScrollbarDragOffset);
            if (this.phase == Phase.COMPARE) {
                syncRightScrollToLeftRatio();
            }
            return true;
        }
        if (button == 0 && this.draggingRightScrollbar) {
            this.rightScroll = scrollOffsetFromMouse(mouseY, this.rightViewportY, this.rightViewportHeight, this.rightTotalRows, this.rightVisibleRows, this.rightScrollbarDragOffset);
            if (this.phase == Phase.COMPARE) {
                syncLeftScrollToRightRatio();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (this.draggingLeftScrollbar || this.draggingRightScrollbar)) {
            this.draggingLeftScrollbar = false;
            this.draggingRightScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void renderChrome(DrawContext context) {
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 0, this.width, HEADER_HEIGHT, new Color(uiColorHeader, true).getRGB());
        context.drawBorder(0, 0, this.width, HEADER_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, CONTENT_TOP, SIDEBAR_WIDTH, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, CONTENT_TOP, SIDEBAR_WIDTH, this.height - CONTENT_TOP, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(SIDEBAR_WIDTH + 2, CONTENT_TOP, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(SIDEBAR_WIDTH + 2, CONTENT_TOP, this.width - SIDEBAR_WIDTH - 2, this.height - CONTENT_TOP, new Color(uiColorBackgroundBorder, true).getRGB());

        context.drawTexture(Main.LOGO_TEXTURE, 10, 5, 0, 0, 45, 45, 45, 45);
        context.getMatrices().push();
        context.getMatrices().scale(2F, 2F, 1F);
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Package Install - " + phaseTitle(), 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
    }

    private String phaseTitle() {
        return switch (this.phase) {
            case REVIEW -> "Detected package";
            case COMPARE -> "Confirm rewrite";
            case APPLIED -> "Applied changes";
        };
    }

    private void renderBody(DrawContext context) {
        int sidebarX = 12;
        int y = CONTENT_TOP + 14;
        drawSidebarLine(context, sidebarX, y, "Package", this.pendingPackage.displayName());
        y += 26;
        drawSidebarLine(context, sidebarX, y, "Version", this.pendingPackage.packageVersion().isBlank() ? "legacy" : this.pendingPackage.packageVersion());
        y += 26;
        drawSidebarLine(context, sidebarX, y, "Type", this.pendingPackage.zipPackage() ? "zip package" : "folder package");
        y += 26;
        drawSidebarLine(context, sidebarX, y, "Files", String.valueOf(this.pendingPackage.fileCount()));
        y += 26;
        drawSidebarLine(context, sidebarX, y, "Changes", this.pendingPackage.changedExistingFileCount() + " replace");
        y += 26;
        drawSidebarLine(context, sidebarX, y, "Adds", this.pendingPackage.addedFileCount() + " new");
        y += 26;
        drawSidebarLine(context, sidebarX, y, "Removes", this.pendingPackage.removedFileCount() + " delete");
        y += 32;
        context.drawText(this.textRenderer, trim(this.status, SIDEBAR_WIDTH - 24), sidebarX, y, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        int contentX = SIDEBAR_WIDTH + 16;
        int contentY = CONTENT_TOP + 12;
        int contentRight = this.width - 14;
        context.drawText(this.textRenderer, this.status, contentX, contentY, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        contentY += 18;

        if (this.phase == Phase.REVIEW) {
            renderTreePanel(context, contentX, contentY, contentRight, this.height - 38, "Package file tree", this.pendingPackage.entries(), false, this.rightScroll);
            return;
        }

        int gap = 8;
        int mid = contentX + (contentRight - contentX - gap) / 2;
        List<PackageEntry> instanceEntries = this.phase == Phase.APPLIED
                ? this.pendingPackage.entries()
                : this.pendingPackage.entries().stream().filter(entry -> entry.existsInInstance() && !"add".equals(entry.operation())).toList();
        List<PackageEntry> packageEntries = this.phase == Phase.APPLIED ? List.of() : this.pendingPackage.entries();
        renderTreePanel(context, contentX, contentY, mid, this.height - 38, this.phase == Phase.APPLIED ? "Changed instance files" : "Existing instance files", instanceEntries, true, this.leftScroll);
        renderTreePanel(context, mid + gap, contentY, contentRight, this.height - 38, "Package files", packageEntries, false, this.rightScroll);
    }

    private void drawSidebarLine(DrawContext context, int x, int y, String label, String value) {
        context.fill(x, y + 1, x + 2, y + 18, new Color(uiColorContentStripeLeft, true).getRGB());
        context.drawText(this.textRenderer, label, x + 8, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, trim(value, SIDEBAR_WIDTH - 24), x + 8, y + 11, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private void renderTreePanel(DrawContext context, int x, int y, int right, int bottom, String title, List<PackageEntry> entries, boolean instanceSide, int scroll) {
        context.fill(x, y, right, bottom, withAlpha(uiColorContentBase, 136));
        context.drawBorder(x, y, right - x, bottom - y, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, title, x + 7, y + 7, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        List<TreeRow> rows = buildRows(entries);
        int listTop = y + 24;
        int listBottom = bottom - 6;
        int visibleRows = Math.max(1, (listBottom - listTop) / TREE_ROW_HEIGHT);
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        int renderScroll = Math.max(0, Math.min(maxScroll, scroll));
        if (instanceSide) {
            this.leftScroll = renderScroll;
            this.leftViewportX = x + 4;
            this.leftViewportY = listTop;
            this.leftViewportWidth = right - x - 9;
            this.leftViewportHeight = listBottom - listTop;
            this.leftTotalRows = rows.size();
            this.leftVisibleRows = visibleRows;
        } else {
            this.rightScroll = renderScroll;
            this.rightViewportX = x + 4;
            this.rightViewportY = listTop;
            this.rightViewportWidth = right - x - 9;
            this.rightViewportHeight = listBottom - listTop;
            this.rightTotalRows = rows.size();
            this.rightVisibleRows = visibleRows;
        }
        context.enableScissor(x + 4, listTop, right - 5, listBottom);
        int rowY = listTop;
        for (int i = renderScroll; i < rows.size() && rowY + TREE_ROW_HEIGHT <= listBottom; i++) {
            TreeRow row = rows.get(i);
            int rowX = x + 8 + Math.min(7, row.depth()) * 12;
            renderTreeConnectors(context, x + 8, rowX, rowY, Math.min(7, row.depth()), row.last());
            renderTreeIcon(context, row, rowX, rowY);
            int color = row.directory() ? new Color(uiColorIDEFolderNameText, true).getRGB() : operationColor(row.operation());
            String label = row.directory() ? row.name() : operationPrefix(row.operation()) + row.name();
            context.drawText(this.textRenderer, trim(label, Math.max(40, right - rowX - 34)), rowX + 18, rowY + 4, color, false);
            rowY += TREE_ROW_HEIGHT;
        }
        if (rows.isEmpty()) {
            context.drawText(this.textRenderer, "empty", x + 8, listTop + 4, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }
        context.disableScissor();
        renderScrollbar(context, right - 8, listTop, listBottom - listTop, rows.size(), visibleRows, renderScroll);
    }

    private void renderTreeConnectors(DrawContext context, int rootX, int rowX, int y, int depth, boolean last) {
        if (depth <= 0) {
            return;
        }
        int guideX = rowX - 8;
        context.fill(guideX, y - 1, guideX + 1, last ? y + 8 : y + TREE_ROW_HEIGHT, withAlpha(uiColorBackgroundBorder, 120));
        context.fill(guideX, y + 8, rowX - 2, y + 9, withAlpha(uiColorBackgroundBorder, 120));
        for (int d = 1; d < depth; d++) {
            int ancestorX = rootX + d * 12 - 8;
            context.fill(ancestorX, y - 1, ancestorX + 1, y + TREE_ROW_HEIGHT, withAlpha(uiColorBackgroundBorder, 70));
        }
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

    private void applySyncedScrollDelta(int delta) {
        this.leftScroll = clampScroll(this.leftScroll + delta, this.leftTotalRows, this.leftVisibleRows);
        this.rightScroll = clampScroll(this.rightScroll + delta, this.rightTotalRows, this.rightVisibleRows);
    }

    private void syncRightScrollToLeftRatio() {
        this.rightScroll = scrollFromRatio(scrollRatio(this.leftScroll, this.leftTotalRows, this.leftVisibleRows), this.rightTotalRows, this.rightVisibleRows);
    }

    private void syncLeftScrollToRightRatio() {
        this.leftScroll = scrollFromRatio(scrollRatio(this.rightScroll, this.rightTotalRows, this.rightVisibleRows), this.leftTotalRows, this.leftVisibleRows);
    }

    private int clampScroll(int scroll, int totalRows, int visibleRows) {
        return Math.max(0, Math.min(Math.max(0, totalRows - visibleRows), scroll));
    }

    private float scrollRatio(int scroll, int totalRows, int visibleRows) {
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, scroll / (float) maxScroll));
    }

    private int scrollFromRatio(float ratio, int totalRows, int visibleRows) {
        int maxScroll = Math.max(0, totalRows - visibleRows);
        return Math.max(0, Math.min(maxScroll, Math.round(maxScroll * ratio)));
    }

    private boolean isOverLeftScrollbar(double mouseX, double mouseY) {
        return this.leftTotalRows > this.leftVisibleRows
                && mouseX >= this.leftViewportX + this.leftViewportWidth - 10
                && mouseX <= this.leftViewportX + this.leftViewportWidth + 6
                && mouseY >= this.leftViewportY
                && mouseY <= this.leftViewportY + this.leftViewportHeight;
    }

    private boolean isOverRightScrollbar(double mouseX, double mouseY) {
        return this.rightTotalRows > this.rightVisibleRows
                && mouseX >= this.rightViewportX + this.rightViewportWidth - 10
                && mouseX <= this.rightViewportX + this.rightViewportWidth + 6
                && mouseY >= this.rightViewportY
                && mouseY <= this.rightViewportY + this.rightViewportHeight;
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

    private void renderTreeIcon(DrawContext context, TreeRow row, int x, int y) {
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
            return safeIcon("json", "file");
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
            return safeIcon("image", "file");
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3")) {
            return safeIcon("audio", "file");
        }
        if (lower.endsWith(".zip") || lower.endsWith(".jar")) {
            return safeIcon("zip", "file");
        }
        if (lower.endsWith(".md")) {
            return safeIcon("md", "text", "file");
        }
        if (lower.endsWith(".txt")) {
            return safeIcon("txt", "text", "file");
        }
        if (lower.endsWith(".properties") || lower.endsWith(".toml") || lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return safeIcon("config", "file");
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
            var texture = ImageTextureService.loadPersistentTexture(iconFile, "koil_package_icon", name + suffix);
            return texture == null ? null : texture.textureId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void renderEmptyIconBox(DrawContext context, int x, int y) {
        context.drawBorder(x, y + 1, 14, 14, withAlpha(uiColorIDEFileNameText, 150));
    }

    private List<TreeRow> buildRows(List<PackageEntry> entries) {
        LinkedHashSet<String> rowPaths = new LinkedHashSet<>();
        Map<String, PackageEntry> entriesByPath = new HashMap<>();
        for (PackageEntry entry : entries) {
            String path = normalize(entry.relativePath());
            if (path.isBlank()) {
                continue;
            }
            entriesByPath.put(path, entry);
            StringBuilder current = new StringBuilder();
            for (String part : path.split("/")) {
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
            childrenByParent.computeIfAbsent(parent(path), ignored -> new ArrayList<>()).add(path);
        }
        for (List<String> children : childrenByParent.values()) {
            children.sort(Comparator
                    .comparing((String path) -> !childrenByParent.containsKey(path))
                    .thenComparing(path -> lastPathName(path).toLowerCase()));
        }

        List<TreeRow> rows = new ArrayList<>();
        appendTreeRows("", 0, childrenByParent, entriesByPath, rows);
        return rows;
    }

    private void appendTreeRows(String parent, int depth, Map<String, List<String>> childrenByParent, Map<String, PackageEntry> entriesByPath, List<TreeRow> rows) {
        List<String> children = childrenByParent.get(parent);
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            String path = children.get(i);
            PackageEntry source = entriesByPath.get(path);
            boolean directory = childrenByParent.containsKey(path) || source != null && source.directory();
            String operation = source == null || directory ? "folder" : source.operation();
            rows.add(new TreeRow(lastPathName(path), depth, directory, operation, i == children.size() - 1));
            if (directory) {
                appendTreeRows(path, depth + 1, childrenByParent, entriesByPath, rows);
            }
        }
    }

    private int operationColor(String operation) {
        return switch (operation) {
            case "add" -> 0xFF9FE6B0;
            case "replace" -> 0xFFE6C76F;
            case "remove" -> 0xFFFF8C8C;
            default -> new Color(uiColorIDEFileNameText, true).getRGB();
        };
    }

    private String operationPrefix(String operation) {
        return switch (operation) {
            case "add" -> "+ ";
            case "replace" -> "~ ";
            case "remove" -> "- ";
            default -> "";
        };
    }

    private String normalize(String path) {
        return path == null ? "" : path.replace("\\", "/").replaceAll("^/+", "");
    }

    private String parent(String path) {
        int index = path.lastIndexOf('/');
        return index <= 0 ? "" : path.substring(0, index);
    }

    private String lastPathName(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private String trim(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        return this.textRenderer.trimToWidth(value, Math.max(20, maxWidth));
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private record TreeRow(String name, int depth, boolean directory, String operation, boolean last) {
    }
}
