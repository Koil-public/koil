package com.spirit.client.gui.tool;

import com.spirit.Main;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.KoilPackageBuilder;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class PackageBuilderScreen extends Screen {
    private static final int HEADER_HEIGHT = 60;
    private static final int CONTENT_TOP = 62;
    private static final int LEFT_WIDTH = 360;
    private static final int ROW_HEIGHT = 18;

    private final Path gameRoot = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    private final Set<String> expandedPaths = new HashSet<>();
    private final Set<String> selectedPaths = new LinkedHashSet<>();
    private final Set<String> selectedFolderPaths = new LinkedHashSet<>();
    private final List<TreeRow> visibleRows = new ArrayList<>();
    private final Map<Integer, TreeRow> rowTargets = new HashMap<>();
    private TextFieldWidget packageIdField;
    private TextFieldWidget displayNameField;
    private TextFieldWidget packageVersionField;
    private TextFieldWidget targetVersionField;
    private TextFieldWidget authorIdField;
    private TextFieldWidget authorField;
    private TextFieldWidget serialField;
    private ButtonWidget createButton;
    private ButtonWidget clearButton;
    private ButtonWidget openOutputButton;
    private ButtonWidget doneButton;
    private String status = "Select files, enter valid package identity values, then create a package.";
    private File lastBuiltPackage;
    private int treeScroll;
    private int treeX;
    private int treeY;
    private int treeWidth;
    private int treeHeight;
    private int treeViewportX;
    private int treeViewportY;
    private int treeViewportWidth;
    private int treeViewportHeight;
    private int treeTotalRows;
    private int treeVisibleRows;
    private boolean draggingTreeScrollbar;
    private int treeScrollbarDragOffset;
    private boolean draggingTreeSelection;
    private boolean dragSelectionAdds;
    private int lastDragSelectionIndex = -1;
    private int lastMouseY;

    public PackageBuilderScreen() {
        super(Text.literal("Koil Package Builder"));
        this.expandedPaths.add("");
    }

    @Override
    protected void init() {
        int rightX = Math.min(this.width - 220, LEFT_WIDTH + 28);
        int fieldWidth = Math.max(160, this.width - rightX - 18);
        int y = CONTENT_TOP + 58;
        this.packageIdField = addField(rightX, y, fieldWidth, "generated package id");
        this.packageIdField.setText("generated from valid author + serial");
        this.packageIdField.setEditable(false);
        y += 30;
        this.displayNameField = addField(rightX, y, fieldWidth, "display name");
        this.displayNameField.setText("Hi Package");
        y += 30;
        this.packageVersionField = addField(rightX, y, fieldWidth, "package version");
        this.packageVersionField.setText("1.0.0");
        y += 30;
        this.targetVersionField = addField(rightX, y, fieldWidth, "target Koil version");
        this.targetVersionField.setText(Main.version());
        y += 30;
        this.authorIdField = addField(rightX, y, fieldWidth, "author id");
        this.authorIdField.setText(currentUsername());
        y += 30;
        this.authorField = addField(rightX, y, fieldWidth, "encrypted author value");
        this.authorField.setText("8zvw0tmb");
        y += 30;
        this.serialField = addField(rightX, y, fieldWidth, "encrypted serial value");
        this.serialField.setText("confirmedkoilpackage");

        int buttonY = this.height - 28;
        this.createButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Create Package"), button -> createPackage())
                .dimensions(rightX, buttonY, 110, 20)
                .build());
        this.clearButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), button -> {
            this.selectedPaths.clear();
            this.selectedFolderPaths.clear();
            this.status = "Selection cleared.";
        }).dimensions(rightX + 116, buttonY, 54, 20).build());
        this.openOutputButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Output"), button -> openOutputFolder())
                .dimensions(rightX + 176, buttonY, 92, 20)
                .build());
        this.doneButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            MinecraftClient.getInstance().setScreen(new com.spirit.client.gui.main.KoilMenuScreen());
        }).dimensions(this.width - 74, buttonY, 60, 20).build());
    }

    private TextFieldWidget addField(int x, int y, int width, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 18, Text.literal(placeholder));
        field.setPlaceholder(Text.literal(placeholder));
        this.addDrawableChild(field);
        return field;
    }

    private String currentUsername() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getSession() != null && client.getSession().getUsername() != null && !client.getSession().getUsername().isBlank()) {
            return client.getSession().getUsername();
        }
        return "UnknownAuthor";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.lastMouseY = mouseY;
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        renderChrome(context);
        renderTreePanel(context);
        if (this.draggingTreeSelection) {
            applyDragSelection(this.lastMouseY);
        }
        renderBuilderPanel(context);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderChrome(DrawContext context) {
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 0, this.width, HEADER_HEIGHT, new Color(uiColorHeader, true).getRGB());
        context.drawBorder(0, 0, this.width, HEADER_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, CONTENT_TOP, LEFT_WIDTH + 10, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, CONTENT_TOP, LEFT_WIDTH + 10, this.height - CONTENT_TOP, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(LEFT_WIDTH + 12, CONTENT_TOP, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(LEFT_WIDTH + 12, CONTENT_TOP, this.width - LEFT_WIDTH - 12, this.height - CONTENT_TOP, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawTexture(Main.LOGO_TEXTURE, 10, 5, 0, 0, 45, 45, 45, 45);
        context.getMatrices().push();
        context.getMatrices().scale(2F, 2F, 1F);
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Package Builder", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
    }

    private void renderTreePanel(DrawContext context) {
        int x = 12;
        int y = CONTENT_TOP + 12;
        int right = LEFT_WIDTH;
        int bottom = this.height - 8;
        context.drawText(this.textRenderer, "Instance files", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, "Click box to select. Click folder name to expand.", x, y + 12, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        this.treeX = x;
        this.treeY = y + 30;
        this.treeWidth = right - x - 8;
        this.treeHeight = bottom - this.treeY;
        context.fill(this.treeX, this.treeY, this.treeX + this.treeWidth, bottom, withAlpha(uiColorContentBase, 120));
        context.drawBorder(this.treeX, this.treeY, this.treeWidth, this.treeHeight, new Color(uiColorBackgroundBorder, true).getRGB());
        this.treeViewportX = this.treeX + 6;
        this.treeViewportY = this.treeY + 6;
        this.treeViewportWidth = this.treeWidth - 12;
        this.treeViewportHeight = Math.max(1, bottom - 6 - this.treeViewportY);
        renderTreeRows(context, this.treeViewportX, this.treeViewportY, this.treeViewportWidth, this.treeViewportY + this.treeViewportHeight);
    }

    private void renderBuilderPanel(DrawContext context) {
        int x = LEFT_WIDTH + 28;
        int y = CONTENT_TOP + 12;
        context.drawText(this.textRenderer, "Package metadata", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, trim(this.status, Math.max(24, (this.width - x - 20) / 6)), x, y + 12, statusColor(), false);
        drawFieldLabel(context, x, this.packageIdField.getY() - 10, "Generated package id");
        drawFieldLabel(context, x, this.displayNameField.getY() - 10, "Display name");
        drawFieldLabel(context, x, this.packageVersionField.getY() - 10, "Package version");
        drawFieldLabel(context, x, this.targetVersionField.getY() - 10, "Target Koil version");
        drawFieldLabel(context, x, this.authorIdField.getY() - 10, "Author id");
        drawFieldLabel(context, x, this.authorField.getY() - 10, "Encrypted author");
        drawFieldLabel(context, x, this.serialField.getY() - 10, "Encrypted serial");

        int summaryY = this.serialField.getY() + 30;
        context.drawText(this.textRenderer, "Selected folder paths: " + this.selectedFolderPaths.size(), x, summaryY, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, "Estimated files: " + estimateSelectedFileCount(), x, summaryY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        if (this.lastBuiltPackage != null) {
            context.drawText(this.textRenderer, "Last package: " + this.lastBuiltPackage.getName(), x, summaryY + 30, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        }
    }

    private void drawFieldLabel(DrawContext context, int x, int y, String label) {
        context.drawText(this.textRenderer, label, x, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
    }

    private int statusColor() {
        if (this.status.startsWith("Created")) {
            return 0xFF9FE6B0;
        }
        if (this.status.startsWith("Failed")) {
            return 0xFFFF8C8C;
        }
        return new Color(uiColorContentBaseDescriptionText, true).getRGB();
    }

    private void renderTreeRows(DrawContext context, int x, int y, int width, int bottom) {
        this.visibleRows.clear();
        this.rowTargets.clear();
        buildVisibleRows("", this.gameRoot, 0);
        int visibleCount = Math.max(1, (bottom - y) / ROW_HEIGHT);
        int maxScroll = Math.max(0, this.visibleRows.size() - visibleCount);
        this.treeScroll = Math.max(0, Math.min(maxScroll, this.treeScroll));
        this.treeTotalRows = this.visibleRows.size();
        this.treeVisibleRows = visibleCount;
        context.enableScissor(x, y, x + width, bottom);
        int rowY = y;
        for (int i = this.treeScroll; i < this.visibleRows.size() && rowY + ROW_HEIGHT <= bottom; i++) {
            TreeRow row = this.visibleRows.get(i);
            this.rowTargets.put(rowY, row);
            int rowX = x + Math.min(8, row.depth()) * 12;
            renderTreeConnectors(context, x, rowX, rowY, Math.min(8, row.depth()), row.last());
            renderSelectionBox(context, row, rowX, rowY);
            renderTreeIcon(context, row, rowX + 16, rowY);
            int color = row.directory() ? new Color(uiColorIDEFolderNameText, true).getRGB() : new Color(uiColorIDEFileNameText, true).getRGB();
            context.drawText(this.textRenderer, trim(row.name(), Math.max(10, (width - (rowX - x) - 44) / 6)), rowX + 34, rowY + 5, color, false);
            rowY += ROW_HEIGHT;
        }
        context.disableScissor();
        renderScrollbar(context, x + width - 4, y, bottom - y, this.visibleRows.size(), visibleCount, this.treeScroll);
    }

    private void buildVisibleRows(String parentPath, Path directory, int depth) {
        File[] children = directory.toFile().listFiles(file -> !file.getName().startsWith("._") && !isKoilTempFile(file));
        if (children == null) {
            return;
        }
        List<File> sorted = new ArrayList<>(List.of(children));
        sorted.sort(Comparator
                .comparing((File file) -> !file.isDirectory())
                .thenComparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        for (int i = 0; i < sorted.size(); i++) {
            File child = sorted.get(i);
            Path childPath = child.toPath().toAbsolutePath().normalize();
            if (!childPath.startsWith(this.gameRoot)) {
                continue;
            }
            String relativePath = normalize(this.gameRoot.relativize(childPath).toString());
            if (relativePath.startsWith("koil/packages/built/")) {
                continue;
            }
            TreeRow row = new TreeRow(child, child.getName(), relativePath, depth, child.isDirectory(), i == sorted.size() - 1);
            this.visibleRows.add(row);
            if (child.isDirectory() && this.expandedPaths.contains(relativePath)) {
                buildVisibleRows(relativePath, childPath, depth + 1);
            }
        }
    }

    private void renderSelectionBox(DrawContext context, TreeRow row, int x, int y) {
        int border = isSelected(row) ? 0xFF9FE6B0 : withAlpha(uiColorIDEFileNameText, 160);
        context.drawBorder(x, y + 3, 10, 10, border);
        if (isSelected(row)) {
            context.fill(x + 2, y + 5, x + 8, y + 11, 0xFF9FE6B0);
        }
    }

    private boolean isKoilTempFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String normalizedName = file.getName()
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return normalizedName.equals("koil temp file")
                || normalizedName.startsWith("koil temp file.");
    }

    private boolean isSelected(TreeRow row) {
        if (row == null || row.relativePath().isBlank()) {
            return false;
        }
        return row.directory()
                ? this.selectedFolderPaths.contains(row.relativePath())
                : this.selectedPaths.contains(row.relativePath());
    }

    private void renderTreeConnectors(DrawContext context, int rootX, int rowX, int y, int depth, boolean last) {
        if (depth <= 0) {
            return;
        }
        int guideX = rowX - 8;
        context.fill(guideX, y - 1, guideX + 1, last ? y + 8 : y + ROW_HEIGHT, withAlpha(uiColorBackgroundBorder, 120));
        context.fill(guideX, y + 8, rowX - 2, y + 9, withAlpha(uiColorBackgroundBorder, 120));
        for (int d = 1; d < depth; d++) {
            int ancestorX = rootX + d * 12 - 8;
            context.fill(ancestorX, y - 1, ancestorX + 1, y + ROW_HEIGHT, withAlpha(uiColorBackgroundBorder, 70));
        }
    }

    private void renderTreeIcon(DrawContext context, TreeRow row, int x, int y) {
        Identifier icon = row.directory() ? safeIcon("folder") : safeFileIcon(row.name());
        if (icon == null) {
            context.drawBorder(x, y + 1, 14, 14, withAlpha(uiColorIDEFileNameText, 150));
            return;
        }
        context.drawTexture(icon, x, y + 1, 0, 0, 16, 16, 16, 16);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInside(mouseX, mouseY, this.treeX, this.treeY, this.treeWidth, this.treeHeight)) {
            this.treeScroll = Math.max(0, this.treeScroll - (int) Math.signum(amount) * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverTreeScrollbar(mouseX, mouseY)) {
            this.draggingTreeScrollbar = true;
            this.treeScrollbarDragOffset = scrollbarGrabOffset(mouseY, this.treeViewportY, this.treeViewportHeight, this.treeTotalRows, this.treeVisibleRows, this.treeScroll);
            this.treeScroll = scrollOffsetFromMouse(mouseY, this.treeViewportY, this.treeViewportHeight, this.treeTotalRows, this.treeVisibleRows, this.treeScrollbarDragOffset);
            return true;
        }
        if (button == 0 && isInside(mouseX, mouseY, this.treeX, this.treeY, this.treeWidth, this.treeHeight)) {
            TreeRow row = rowAt(mouseY);
            if (row != null) {
                int rowX = this.treeX + 6 + Math.min(8, row.depth()) * 12;
                if (mouseX >= rowX - 6 && mouseX <= rowX + 10) {
                    this.draggingTreeSelection = true;
                    this.dragSelectionAdds = !isSelected(row);
                    this.lastDragSelectionIndex = -1;
                    applyDragSelection(mouseY);
                } else if (row.directory()) {
                    if (this.expandedPaths.contains(row.relativePath())) {
                        this.expandedPaths.remove(row.relativePath());
                    } else {
                        this.expandedPaths.add(row.relativePath());
                    }
                } else {
                    toggleSelected(row);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.draggingTreeScrollbar) {
            this.treeScroll = scrollOffsetFromMouse(mouseY, this.treeViewportY, this.treeViewportHeight, this.treeTotalRows, this.treeVisibleRows, this.treeScrollbarDragOffset);
            return true;
        }
        if (button == 0 && this.draggingTreeSelection) {
            applyDragSelection(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingTreeSelection) {
            this.draggingTreeSelection = false;
            this.lastDragSelectionIndex = -1;
            return true;
        }
        if (button == 0 && this.draggingTreeScrollbar) {
            this.draggingTreeScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private TreeRow rowAt(double mouseY) {
        int listTop = this.treeY + 6;
        int index = this.treeScroll + (int) ((mouseY - listTop) / ROW_HEIGHT);
        if (index < 0 || index >= this.visibleRows.size()) {
            return null;
        }
        return this.visibleRows.get(index);
    }

    private void toggleSelected(TreeRow row) {
        if (isSelected(row)) {
            deselectRowAndChildren(row);
        } else {
            selectRowAndParents(row);
        }
    }

    private void applyDragSelection(double mouseY) {
        int index = rowIndexAt(mouseY);
        if (index < 0 || index >= this.visibleRows.size() || index == this.lastDragSelectionIndex) {
            return;
        }
        int start = this.lastDragSelectionIndex < 0 ? index : Math.min(this.lastDragSelectionIndex, index);
        int end = this.lastDragSelectionIndex < 0 ? index : Math.max(this.lastDragSelectionIndex, index);
        this.lastDragSelectionIndex = index;
        for (int i = start; i <= end; i++) {
            TreeRow row = this.visibleRows.get(i);
            if (this.dragSelectionAdds) {
                selectRowAndParents(row);
            } else {
                deselectRowAndChildren(row);
            }
        }
    }

    private int rowIndexAt(double mouseY) {
        int listTop = this.treeY + 6;
        return this.treeScroll + (int) ((mouseY - listTop) / ROW_HEIGHT);
    }

    private void selectRowAndParents(TreeRow row) {
        if (row == null || row.relativePath().isBlank()) {
            return;
        }
        if (row.directory()) {
            this.selectedFolderPaths.add(row.relativePath());
            selectChildren(row.relativePath());
        } else {
            this.selectedPaths.add(row.relativePath());
        }
        String parent = parentPath(row.relativePath());
        while (!parent.isBlank()) {
            this.selectedFolderPaths.add(parent);
            parent = parentPath(parent);
        }
    }

    private void selectChildren(String relativePath) {
        Path root = this.gameRoot.resolve(relativePath).normalize();
        if (!root.startsWith(this.gameRoot) || !Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.forEach(path -> {
                Path normalizedPath = path.toAbsolutePath().normalize();
                if (!normalizedPath.startsWith(this.gameRoot) || normalizedPath.equals(root)) {
                    return;
                }
                File file = normalizedPath.toFile();
                if (file.getName().startsWith("._") || isKoilTempFile(file)) {
                    return;
                }
                String childPath = normalize(this.gameRoot.relativize(normalizedPath).toString());
                if (!childPath.isBlank() && !childPath.startsWith("koil/packages/built/")) {
                    if (Files.isDirectory(normalizedPath)) {
                        this.selectedFolderPaths.add(childPath);
                    } else if (Files.isRegularFile(normalizedPath)) {
                        this.selectedPaths.add(childPath);
                    }
                }
            });
        } catch (Exception exception) {
            this.status = "Failed: unable to map folder contents: " + exception.getMessage();
        }
    }

    private void deselectRowAndChildren(TreeRow row) {
        if (row == null || row.relativePath().isBlank()) {
            return;
        }
        if (!row.directory()) {
            this.selectedPaths.remove(row.relativePath());
            pruneVisualParentSelection();
            return;
        }
        String prefix = row.relativePath().endsWith("/") ? row.relativePath() : row.relativePath() + "/";
        this.selectedFolderPaths.remove(row.relativePath());
        this.selectedFolderPaths.removeIf(path -> path.startsWith(prefix));
        this.selectedPaths.removeIf(path -> path.startsWith(prefix));
        pruneVisualParentSelection();
    }

    private void pruneVisualParentSelection() {
        Set<String> requiredFolders = new LinkedHashSet<>();
        for (String selectedPath : this.selectedPaths) {
            String parent = parentPath(selectedPath);
            while (!parent.isBlank()) {
                requiredFolders.add(parent);
                parent = parentPath(parent);
            }
        }
        this.selectedFolderPaths.retainAll(requiredFolders);
    }

    private String parentPath(String path) {
        int index = path == null ? -1 : path.lastIndexOf('/');
        return index <= 0 ? "" : path.substring(0, index);
    }

    private void createPackage() {
        try {
            String validationError = validatePackageInputs();
            if (!validationError.isBlank()) {
                this.status = "Failed: " + validationError;
                return;
            }
            KoilPackageBuilder.BuildResult result = KoilPackageBuilder.createPackage(new KoilPackageBuilder.BuildRequest(
                    "",
                    this.displayNameField.getText(),
                    this.packageVersionField.getText(),
                    this.targetVersionField.getText(),
                    this.authorIdField.getText(),
                    this.authorField.getText(),
                    this.serialField.getText(),
                    new ArrayList<>(this.selectedPaths)
            ));
            this.lastBuiltPackage = result.packageFile();
            this.packageIdField.setText(result.packageId());
            this.status = "Created " + result.packageFile().getName() + " | " + result.fileCount() + " files | " + readableBytes(result.totalBytes());
        } catch (Exception exception) {
            this.status = "Failed: " + exception.getMessage();
        }
    }

    private String validatePackageInputs() {
        if (this.selectedPaths.isEmpty()) {
            return "select at least one file or folder.";
        }
        if (this.authorField.getText().trim().isBlank()) {
            return "encrypted author value is required.";
        }
        if (this.serialField.getText().trim().isBlank()) {
            return "encrypted serial value is required.";
        }
        return "";
    }

    private void openOutputFolder() {
        Path output = this.gameRoot.resolve("koil/packages/built");
        try {
            Files.createDirectories(output);
            Util.getOperatingSystem().open(output.toFile());
        } catch (Exception exception) {
            this.status = "Failed: " + exception.getMessage();
        }
    }

    private int estimateSelectedFileCount() {
        int count = 0;
        for (String selectedPath : this.selectedPaths) {
            Path path = this.gameRoot.resolve(selectedPath).normalize();
            try {
                if (Files.isRegularFile(path)) {
                    count++;
                }
            } catch (Exception ignored) {
            }
        }
        return count;
    }

    private Identifier safeFileIcon(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
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
            var texture = ImageTextureService.loadPersistentTexture(iconFile, "koil_package_builder_icon", name + suffix);
            return texture == null ? null : texture.textureId();
        } catch (Exception ignored) {
            return null;
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

    private boolean isOverTreeScrollbar(double mouseX, double mouseY) {
        return this.treeTotalRows > this.treeVisibleRows
                && mouseX >= this.treeViewportX + this.treeViewportWidth - 10
                && mouseX <= this.treeViewportX + this.treeViewportWidth + 6
                && mouseY >= this.treeViewportY
                && mouseY <= this.treeViewportY + this.treeViewportHeight;
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

    private String normalize(String path) {
        return path == null ? "" : path.replace("\\", "/").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String readableBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return new DecimalFormat("#,##0.#").format(bytes / 1024.0) + " KB";
        }
        return new DecimalFormat("#,##0.#").format(bytes / (1024.0 * 1024.0)) + " MB";
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha))).getRGB();
    }

    private record TreeRow(File file, String name, String relativePath, int depth, boolean directory, boolean last) {
    }
}
