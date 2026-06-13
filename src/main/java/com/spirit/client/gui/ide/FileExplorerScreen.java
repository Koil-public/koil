/*
Koil File Explorer Color Audit Version
Prepared for uiColorVal migration review.
No functional logic changes were applied automatically.
*/

package com.spirit.client.gui.ide;

import com.spirit.Main;
import com.spirit.client.gui.MarkdownPreviewRenderer;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.PopupMenu.MenuEntry;
import com.spirit.client.gui.TopBarLayout;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.client.gui.DesignMusicController;
import com.spirit.client.gui.mod.ModConfigScreen;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.util.file.image.ExternalImageLoader;
import com.spirit.koil.api.util.file.image.ExternalImageRenderer;
import com.spirit.koil.api.util.file.jar.KoilLocalModJarInspector;
import com.spirit.koil.api.util.file.jar.KoilLocalModJarInspector.KoilLocalModJarInsight;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.media.VisualPlaybackSession;
import com.spirit.koil.api.util.file.media.VisualPlaybackState;
import com.spirit.koil.api.util.file.media.image.AnimatedGifPlaybackSession;
import com.spirit.koil.api.util.file.media.image.ImageTexture;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import com.spirit.koil.api.util.file.media.video.VideoMetadata;
import com.spirit.koil.api.util.file.media.video.VideoPlaybackSession;
import com.spirit.koil.api.util.file.media.video.VideoService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.List;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.spirit.Main.*;
import static com.spirit.client.gui.ide.CodeColorTypes.*;
import static com.spirit.koil.api.design.uiColorVal.*;
import static com.spirit.koil.api.util.file.audio.AudioManager.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;

@Environment(EnvType.CLIENT)
public class FileExplorerScreen extends Screen {
    private static final class FolderPreviewEntry {
        private final File file;
        private final String name;
        private final FileType type;
        private final int depth;
        private final boolean isLast;
        private final List<Boolean> ancestorContinuations;

        private FolderPreviewEntry(File file, String name, FileType type, int depth, boolean isLast, List<Boolean> ancestorContinuations) {
            this.file = file;
            this.name = name;
            this.type = type;
            this.depth = depth;
            this.isLast = isLast;
            this.ancestorContinuations = ancestorContinuations;
        }
    }

    private static final class ZipTreeNode {
        private final String name;
        private final boolean directory;
        private final Map<String, ZipTreeNode> children = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        private ZipTreeNode(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    private static final class FolderPreviewClickTarget {
        private final File file;
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private FolderPreviewClickTarget(File file, int x, int y, int width, int height) {
            this.file = file;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private static String lastVisitedPath = "/";
    private static String initialTargetPath = "/";
    private static final int FOLDER_PREVIEW_LINE_HEIGHT = 16;
    private static final int TEXT_PREVIEW_LINE_HEIGHT = 10;
    private static final int LEFT_PANEL_WIDTH = 170;
    private static final int FILE_LIST_ROW_X = 10;
    private static final int FILE_LIST_ROW_WIDTH = LEFT_PANEL_WIDTH - 20;
    private static final int FILE_LIST_SCROLLBAR_X = LEFT_PANEL_WIDTH - 8;
    private static final int FILE_LIST_SCROLLBAR_WIDTH = 8;
    private static final int EXPLORER_SCROLLBAR_TRACK_WIDTH = 3;
    private static final int EXPLORER_SCROLLBAR_THUMB_WIDTH = 5;
    private static final int MAX_SEARCH_RESULTS = 64;
    private static final String TOP_BAR_BACK_LABEL = "<";
    private static final String TOP_BAR_OPEN_LABEL = "Open";
    private static final String TOP_BAR_HOME_LABEL = "Root";
    private static final String TOP_BAR_RELOAD_LABEL = "Reset";
    private static final Set<String> CONFIG_EDITOR_EXTENSIONS = new HashSet<>(List.of(
            "json", "json5", "toml", "yaml", "yml", "properties", "cfg", "conf"
    ));
    private final Screen parentScreen;

    public static void setInitialPath(String path) {
        if (path == null || path.isBlank()) {
            lastVisitedPath = "/";
            initialTargetPath = "/";
            return;
        }
        File target = new File(path);
        File explorerTarget = target.isDirectory() ? target : target.getParentFile();
        if (explorerTarget == null) {
            explorerTarget = new File(path);
        }
        String normalizedExplorerPath = explorerTarget.getPath().replace("\\", "/").replaceFirst("^\\.", "");
        String normalizedTargetPath = target.getPath().replace("\\", "/").replaceFirst("^\\.", "");
        lastVisitedPath = normalizedExplorerPath.isBlank() ? "/" : normalizedExplorerPath;
        initialTargetPath = normalizedTargetPath.isBlank() ? "/" : normalizedTargetPath;
    }

    public static FileExplorerScreen openAtPath(String path) {
        setInitialPath(path);
        return new FileExplorerScreen();
    }

    public static FileExplorerScreen openAtLastVisitedPath() {
        if (lastVisitedPath == null || lastVisitedPath.isBlank()) {
            lastVisitedPath = "/";
        }
        initialTargetPath = lastVisitedPath;
        return new FileExplorerScreen();
    }

    private static Identifier icon(String name) {
        wantsColoredFileIcons = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "wantsColoredFileIcons").getAsBoolean();
        String suffix = wantsColoredFileIcons ? "_colored.png" : ".png";
        Identifier id = loadExternalPngTexture(uiImageDirectory, name + suffix);

        if (id == null) {
            id = loadExternalPngTexture(uiImageDirectory, name + ".png");
        }

        if (id == null && !name.equals("file")) {
            id = loadExternalPngTexture(uiImageDirectory, wantsColoredFileIcons ? "file_colored.png" : "file.png");
            if (id == null) {
                id = loadExternalPngTexture(uiImageDirectory, "file.png");
            }
        }

        if (id == null) {
            // replace this feature to draw a gray outline box like how the update and packaging screens use when theres not an image
            return Identifier.of("minecraft", "textures/item/barrier.png");
        }
        return id;
    }

    public static Identifier FOLDER_ICON = icon("folder");
    public static Identifier IMAGE_ICON = icon("image");
    public static Identifier FILE_ICON = icon("file");
    public static Identifier VIDEO_ICON = icon("video");
    public static Identifier AUDIO_ICON = icon("audio");
    public static Identifier CODE_ICON = icon("code");
    public static Identifier FONT_ICON = icon("font");
    public static Identifier JAVA_ICON = icon("java");
    public static Identifier CPP_ICON = icon("cpp");
    public static Identifier C_ICON = icon("c");
    public static Identifier CS_ICON = icon("cs");
    public static Identifier PYTHON_ICON = icon("python");
    public static Identifier JSON_ICON = icon("json");
    public static Identifier JSON5_ICON = icon("json5");
    public static Identifier PROPERTIES_ICON = icon("properties");
    public static Identifier GITHUB_ICON = icon("github");
    public static Identifier MARKDOWN_ICON = icon("markdown");
    public static Identifier ENVIRONMENT_ICON = icon("env");
    public static Identifier SHADER_ICON = icon("shader");
    public static Identifier VSH_ICON = icon("vsh");
    public static Identifier FSH_ICON = icon("fsh");
    public static Identifier PLACEBO_ICON = icon("placebo");
    public static Identifier DATABASE_ICON = icon("database");
    public static Identifier WEB_FILE_ICON = icon("web_file");
    public static Identifier SECURITY_FILE_ICON = icon("security_file");
    public static Identifier EXE_ICON = icon("exe");
    public static Identifier TEXT_ICON = icon("text");
    public static Identifier ZIP_ICON = icon("zip");
    public static Identifier MCMETA_ICON = icon("mcmeta");
    public static Identifier FABRIC_FILE_ICON = icon("fabric_file");
    public static Identifier QUILT_FILE_ICON = icon("quilt_file");
    public static Identifier KOTLIN_ICON = icon("kotlin");
    public static Identifier LOG_ICON = icon("log");
    public static Identifier KOIL_FILE_ICON = icon("koil_file");

    public static Identifier PLAY_BUTTON = icon("play");
    public static Identifier PAUSE_BUTTON = icon("pause");
    public static Identifier STOP_BUTTON = icon("stop");
    public static void reloadIcons() {
        FOLDER_ICON = icon("folder");
        IMAGE_ICON = icon("image");
        FILE_ICON = icon("file");
        VIDEO_ICON = icon("video");
        AUDIO_ICON = icon("audio");
        CODE_ICON = icon("code");
        FONT_ICON = icon("font");
        JAVA_ICON = icon("java");
        CPP_ICON = icon("cpp");
        C_ICON = icon("c");
        CS_ICON = icon("cs");
        PYTHON_ICON = icon("python");
        JSON_ICON = icon("json");
        JSON5_ICON = icon("json5");
        PROPERTIES_ICON = icon("properties");
        GITHUB_ICON = icon("github");
        MARKDOWN_ICON = icon("markdown");
        ENVIRONMENT_ICON = icon("env");
        SHADER_ICON = icon("shader");
        VSH_ICON = icon("vsh");
        FSH_ICON = icon("fsh");
        PLACEBO_ICON = icon("placebo");
        DATABASE_ICON = icon("database");
        WEB_FILE_ICON = icon("web_file");
        SECURITY_FILE_ICON = icon("security_file");
        EXE_ICON = icon("exe");
        TEXT_ICON = icon("text");
        ZIP_ICON = icon("zip");
        MCMETA_ICON = icon("mcmeta");
        FABRIC_FILE_ICON = icon("fabric_file");
        QUILT_FILE_ICON = icon("quilt_file");
        KOTLIN_ICON = icon("kotlin");
        LOG_ICON = icon("log");
        KOIL_FILE_ICON = icon("koil_file");

        PLAY_BUTTON = icon("play");
        PAUSE_BUTTON = icon("pause");
        STOP_BUTTON = icon("stop");
    }
    private final List<FileItem> fileItems = new ArrayList<>();
    private FileItem selectedFileItem;
    private String fileContent;
    private String cachedPreviewTextPath;
    private long cachedPreviewTextLastModified = Long.MIN_VALUE;
    private File currentDirectory = new File(".");
    private String cachedPreviewImagePath;
    private int cachedPreviewImageWidth = -1;
    private int cachedPreviewImageHeight = -1;
    private Identifier cachedPreviewImageTextureId;
    private String cachedPreviewMetadataPath;
    private long cachedPreviewMetadataLastModified = Long.MIN_VALUE;
    private KoilLocalModJarInsight cachedPreviewModMetadata;
    private int scrollOffset = 0;
    private int scrollOffsetViewer = 0;
    private int scrollOffsetFolderPreview = 0;
    private int horizontalScrollOffsetViewer = 0;
    private int scrollOffsetTextBar = 0;
    private long lastClickTime = 0;
    private String lastClickedPath;
    private final List<FolderPreviewClickTarget> folderPreviewClickTargets = new ArrayList<>();
    private static final long DOUBLE_CLICK_TIME_THRESHOLD = 350;
    private TextFieldWidget pathInput;
    private final PopupMenu topBarOpenMenu = new PopupMenu();
    private final PopupMenu previewOpenMenu = new PopupMenu();
    private int[] topBarOpenBounds = null;
    private int[] previewOpenBounds = null;
    private int[] previewRenameBounds = null;
    private int[] previewDeleteBounds = null;
    private int[] previewTitleBounds = null;
    private File previewOpenTarget = null;
    private TextFieldWidget renameInput;
    private boolean renameMode = false;
    private File renameTarget = null;
    private boolean showDeleteConfirmPopup = false;
    private File deleteTarget = null;
    private List<String> pathSuggestions;
    private boolean showSuggestions;
    private boolean suppressPathInputListener;
    private String lastSuggestionInput = "";
    private File lastSuggestionDirectory = new File("");
    private final int maxVisibleSuggestions = 6;
    private final int timestampBarWidth = 150;
    private final int timestampBarHeight = 10;
    private int[] audioPlayBounds = null;
    private int[] audioStopBounds = null;
    private int[] audioTimestampBounds = null;
    private VisualPlaybackSession activeVisualSession;
    private String activeVisualSessionPath;
    private int activeVisualSessionWidth = -1;
    private int activeVisualSessionHeight = -1;
    private int[] visualPreviewBounds = null;
    private int[] visualPlayOverlayBounds = null;
    private int[] visualPrimaryControlBounds = null;
    private int[] visualSecondaryControlBounds = null;
    private int[] visualTimestampBounds = null;
    private boolean visualPreviewGif = false;
    private boolean visualPreviewVideo = false;
    private String hoveredTruncatedFileName;
    private int hoveredTruncatedFileNameMouseX;
    private int hoveredTruncatedFileNameMouseY;
    private boolean draggingFileListScrollbar;
    private int fileListScrollbarDragOffset;
    private boolean draggingSuggestionScrollbar;
    private int suggestionScrollbarDragOffset;
    private boolean draggingPreviewScrollbar;
    private int previewScrollbarDragOffset;
    private int previewScrollbarY;
    private int previewScrollbarHeight;
    private int previewScrollbarTotalRows;
    private int previewScrollbarVisibleRows;
    private boolean previewScrollbarFolderMode;
    public FileExplorerScreen() {
        super(Text.literal("Title"));
        MinecraftClient client = MinecraftClient.getInstance();
        Screen current = client == null ? null : client.currentScreen;
        this.parentScreen = current instanceof FileExplorerScreen ? null : current;
    }

    @Override
    protected void init() {
        super.init();
        TopBarLayout layout = getTopBarLayout();
        pathInput = new TextFieldWidget(this.textRenderer,
                layout.searchFieldX(TOP_BAR_BACK_LABEL),
                TopBarLayout.SEARCH_FIELD_Y,
                layout.searchFieldWidth(TOP_BAR_BACK_LABEL, getTopBarActionLabels(), 220),
                TopBarLayout.SEARCH_FIELD_HEIGHT,
                Text.of(""));
        pathInput.setMaxLength(512);
        String requestedPath = initialTargetPath == null || initialTargetPath.isBlank() ? lastVisitedPath : initialTargetPath;
        pathInput.setText(requestedPath);
        pathSuggestions = new ArrayList<>();
        showSuggestions = false;

        pathInput.setChangedListener((newText) -> {
            if (!suppressPathInputListener) {
                handlePathInputChange(newText);
                updatePathSuggestions();
            }
        });
        this.addDrawableChild(pathInput);

        renameInput = new TextFieldWidget(this.textRenderer, 0, 0, 220, 18, Text.of(""));
        renameInput.setMaxLength(255);
        renameInput.setVisible(false);
        renameInput.setEditable(false);
        this.addDrawableChild(renameInput);

        loadFileItems(resolveDirectoryFromPath(requestedPath));
        if (!"/".equals(requestedPath)) {
            openInitialTargetIfPresent(requestedPath);
        }
        initialTargetPath = lastVisitedPath;

        updatePathSuggestions();
    }

    @Override
    public void filesDragged(List<Path> paths) {
        if (paths == null || paths.isEmpty() || this.client == null || currentDirectory == null || !currentDirectory.isDirectory()) {
            return;
        }
        List<Path> droppedPaths = paths.stream()
                .filter(Objects::nonNull)
                .filter(Files::exists)
                .toList();
        if (droppedPaths.isEmpty()) {
            return;
        }
        File targetDirectory = currentDirectory;
        String targetPath = pathFromInstance(targetDirectory);
        String fileSummary = droppedPaths.size() == 1
                ? droppedPaths.get(0).getFileName().toString()
                : droppedPaths.size() + " files/folders";
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                copyDroppedPaths(droppedPaths, targetDirectory.toPath());
                restoreExplorerPathAfterDelete(targetPath);
            }
            MinecraftClient.getInstance().setScreen(this);
        }, Text.literal("Copy Dropped Files"), Text.literal("Copy " + fileSummary + " into " + targetPath + "?"), Text.literal("Copy"), Text.literal("Cancel")));
    }


    @Override
    public void tick() {
        reloadIcons();
        if (renameInput != null) {
            renameInput.setVisible(renameMode);
            renameInput.setEditable(renameMode);
        }
        if (activeVisualSession != null) {
            activeVisualSession.update(System.currentTimeMillis());
        }
        super.tick();
    }

    @Override
    public void close() {
        assert this.client != null;
        stopSelectedAudioIfActive();
        closeVisualSession();
        this.client.setScreen(this.parentScreen);
    }

    private void updatePathSuggestions() {
        String inputText = pathInput.getText().toLowerCase();
        if (Objects.equals(inputText, lastSuggestionInput) && Objects.equals(currentDirectory, lastSuggestionDirectory)) {
            showSuggestions = pathInput.isFocused() && !pathSuggestions.isEmpty();
            return;
        }
        pathSuggestions.clear();

        if (isSearchQuery(inputText)) {
            List<FileItem> searchResults = searchFileItems(currentDirectory, inputText);
            for (int i = 0; i < Math.min(searchResults.size(), maxVisibleSuggestions * 2); i++) {
                pathSuggestions.add(searchResults.get(i).getFile().getPath().replaceFirst("^\\.", ""));
            }
        } else {
            for (FileItem fileItem : fileItems) {
                if (fileItem.getType() == FileType.FOLDER) {
                    String folderPath = fileItem.getFile().getPath().replaceFirst("^\\.", "");
                    if (folderPath.toLowerCase().startsWith(inputText)) {
                        pathSuggestions.add(folderPath);
                    }
                }
            }
        }

        lastSuggestionInput = inputText;
        lastSuggestionDirectory = currentDirectory;
        showSuggestions = pathInput.isFocused() && !pathSuggestions.isEmpty();
    }

    private void navigateBack() {
        cancelRename();
        stopSelectedAudioIfActive();
        String currentPath = pathInput.getText();
        if (currentPath.equals("/")) {
            close();
            return;
        }
        File currentFile = new File("." + currentPath);
        File parentFile = currentFile.getParentFile();
        if (parentFile != null && !Objects.equals(parentFile, currentFile)) {
            setDirectory(parentFile);
            return;
        }
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        assert client != null;
        hoveredTruncatedFileName = null;
        hoveredTruncatedFileNameMouseX = mouseX;
        hoveredTruncatedFileNameMouseY = mouseY;
        int topBarBackground = withAlpha(uiColorContentBase, 176);
        int topPanelBackground = withAlpha(uiColorContentBase, 196);
        int rightButtonsX = getTopBarOpenButtonX();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        context.fill(0, 0, this.width, 40, topPanelBackground);
        context.drawText(this.textRenderer, "Version - " + Main.version(), this.width - 100, 10, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().push();
        context.getMatrices().scale(0.5f, 0.5f, 1.0F);
        context.drawText(this.textRenderer, "By: SpiritXIV", (int) ((this.width - 100) / 0.5f), (int) (20 / 0.5f), new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.drawTexture(LOGO_TEXTURE, 10, 5, 0, 0, 22, 22, 22, 22);
        context.getMatrices().push();
        context.getMatrices().scale(0.5F, 0.5F, 1.0F);
        context.drawText(this.textRenderer, "Manager Menu - InDEV", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        context.drawBorder(0, 0, this.width, this.height,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 40, this.width, 68, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 40, this.width, 68, topBarBackground);
        context.drawBorder(0, 40, this.width, 28,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(172, 70, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(172, 70, this.width, this.height,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 70, 170, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, 70, 170, this.height,  new Color(uiColorBackgroundBorder, true).getRGB());

        renderTopBarButton(context, 10, TOP_BAR_BACK_LABEL);
        renderTopBarButton(context, rightButtonsX, TOP_BAR_OPEN_LABEL);
        topBarOpenBounds = new int[]{rightButtonsX, TopBarLayout.BUTTON_Y, getTopBarButtonWidth(TOP_BAR_OPEN_LABEL), TopBarLayout.BUTTON_HEIGHT};
        renderTopBarButton(context, getTopBarHomeButtonX(), TOP_BAR_HOME_LABEL);
        renderTopBarButton(context, getTopBarReloadButtonX(), TOP_BAR_RELOAD_LABEL);

        int startY = 80;
        int endY = this.height - 20;
        int filesPerPage = (endY - startY) / 20;
        int maxScroll = Math.max(0, fileItems.size() - filesPerPage);
        int scrollY = Math.min(scrollOffset, maxScroll);
        startY -= scrollY * 20;
        int startX = 10;
        for (FileItem fileItem : fileItems) {
            if (startY > endY || startY < 80) {
                startY += 20;
                continue;
            }

            if (fileItem.getType() == FileType.FOLDER) {
                renderFolder(context, fileItem, startX, startY);
            } else {
                renderFile(context, fileItem, startX, startY);
            }
            startY += 20;
        }
        renderFileListScrollbar(context);

        renderFileInfo(context, mouseX, mouseY);

        if (selectedFileItem != null && selectedFileItem.getType() == FileType.AUDIO && audioPlayBounds != null) {
            boolean isSelectedAudioActive = AudioManager.isCurrentAudioFile(selectedFileItem.getFile());

            if (isSelectedAudioActive && AudioManager.isAudioPlaying()) {
                context.drawTexture(PAUSE_BUTTON, audioPlayBounds[0], audioPlayBounds[1], 0, 0, 16, 16, 16, 16);
            } else {
                context.drawTexture(PLAY_BUTTON, audioPlayBounds[0], audioPlayBounds[1], 0, 0, 16, 16, 16, 16);
            }

            if (isSelectedAudioActive && AudioManager.hasActiveAudio() && audioStopBounds != null) {
                context.drawTexture(STOP_BUTTON, audioStopBounds[0], audioStopBounds[1], 0, 0, 16, 16, 16, 16);
            }

            if (isSelectedAudioActive && AudioManager.hasActiveAudio() && AudioManager.canSeekPlayback(selectedFileItem.getFile()) && audioTimestampBounds != null) {
                float progress = AudioManager.getPlaybackProgress(selectedFileItem.getFile());
                int filledWidth = (int) (audioTimestampBounds[2] * progress);
                int barX = audioTimestampBounds[0];
                int barY = audioTimestampBounds[1];
                int barWidth = audioTimestampBounds[2];
                int barHeight = audioTimestampBounds[3];
                context.fill(barX, barY, barX + filledWidth, barY + barHeight, new Color(uiColorIDEAudioTimestampBarFill, true).getRGB());
                context.drawBorder(barX, barY, barWidth, barHeight, new Color(uiColorIDEAudioTimestampBarBorder, true).getRGB());
                String currentTime = formatTime(AudioManager.getPlaybackPositionMicros(selectedFileItem.getFile()));
                String totalTime = formatTime(AudioManager.getPlaybackLengthMicros(selectedFileItem.getFile()));
                int currentTimeX = barX + filledWidth - currentTime.length() / 2;
                context.getMatrices().push();
                context.getMatrices().scale(0.5f, 0.5f, 1.0F);
                context.drawText(this.textRenderer, currentTime, (int) (currentTimeX / 0.5) - 2, (int) ((barY + 14) / 0.5), new Color(uiColorIDEAudioTimestampText, true).getRGB(), true);
                context.getMatrices().pop();
                context.drawText(this.textRenderer, totalTime, barX + barWidth + 3, barY + 1, new Color(uiColorIDEAudioTimestampText, true).getRGB(), true);
                context.fill(barX + filledWidth - 1, barY - 1, barX + filledWidth + 1, barY + barHeight + 1, new Color(uiColorIDEAudioTimestampBarLine, true).getRGB());
            }
        }
        if (showSuggestions) {
            renderPathSuggestions(context);
        }
        topBarOpenMenu.render(context, mouseX, mouseY);
        previewOpenMenu.render(context, mouseX, mouseY);
        if (showDeleteConfirmPopup) {
            drawDeleteConfirmPopup(context);
        }
        super.render(context, mouseX, mouseY, delta);
        if (hoveredTruncatedFileName != null && !hoveredTruncatedFileName.isBlank()) {
            context.drawTooltip(this.textRenderer, Text.literal(hoveredTruncatedFileName), hoveredTruncatedFileNameMouseX, hoveredTruncatedFileNameMouseY);
        }
    }

    private void renderPathSuggestions(DrawContext context) {
        int[] box = getSuggestionPopupBounds();
        int suggestionBoxX = box[0];
        int suggestionBoxY = box[1];
        int suggestionWidth = box[2];
        int visibleSuggestions = getVisibleSuggestionRows();
        int rowHeight = 16;
        int suggestionBoxHeight = box[3];

        context.fill(suggestionBoxX, suggestionBoxY, suggestionBoxX + suggestionWidth, suggestionBoxY + suggestionBoxHeight, withAlpha(uiColorContentBase, 230));
        context.drawBorder(suggestionBoxX, suggestionBoxY, suggestionWidth, suggestionBoxHeight, new Color(uiColorBackgroundBorder, true).getRGB());

        int suggestionItemY = suggestionBoxY + 5;
        int endIndex = Math.min(pathSuggestions.size(), scrollOffsetTextBar + visibleSuggestions);
        for (int i = scrollOffsetTextBar; i < endIndex; i++) {
            if (i >= 0 && i < pathSuggestions.size()) {
                boolean hovered = hoveredTruncatedFileNameMouseX >= suggestionBoxX + 2
                        && hoveredTruncatedFileNameMouseX <= suggestionBoxX + suggestionWidth - 12
                        && hoveredTruncatedFileNameMouseY >= suggestionItemY - 2
                        && hoveredTruncatedFileNameMouseY <= suggestionItemY + rowHeight - 2;
                if (hovered) {
                    context.fill(suggestionBoxX + 1, suggestionItemY - 2, suggestionBoxX + suggestionWidth - 11, suggestionItemY + rowHeight - 2, withAlpha(uiColorHeader, 120));
                }
                String suggestion = fitLabelToWidth(pathSuggestions.get(i), suggestionWidth - 20);
                context.drawText(this.textRenderer, suggestion, suggestionBoxX + 7, suggestionItemY + 2, new Color(uiColorIDEFileDisplayWindowText, true).getRGB(), false);
                suggestionItemY += rowHeight;
            }
        }
        renderSuggestionScrollbar(context, suggestionBoxX, suggestionBoxY, suggestionWidth, suggestionBoxHeight, visibleSuggestions);
    }

    private void renderFolder(DrawContext context, FileItem folderItem, int startX, int startY) {
        int drawX = startX + (folderItem.getDepth() * 14);
        renderTreeGuides(context, folderItem, startX, startY, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawTexture(FOLDER_ICON, drawX, startY - 4, 0, 0, 16, 16, 16, 16);
        int labelMaxWidth = Math.max(24, FILE_LIST_SCROLLBAR_X - (drawX + 24));
        String fittedName = fitLabelToWidth(folderItem.getName(), labelMaxWidth);
        context.drawText(this.textRenderer, fittedName, drawX + 20, startY, new Color(uiColorIDEFolderNameText, true).getRGB(), false);
        captureTruncatedNameTooltip(folderItem.getName(), fittedName, drawX + 20, startY, labelMaxWidth);
    }

    private void renderFile(DrawContext context, FileItem fileItem, int startX, int startY) {
        String fileName = fileItem.getName();

        Identifier iconTexture = switch (fileItem.getType()) {
            case IMAGE -> IMAGE_ICON;
            case FILE -> getFileIcon(fileName);
            case VIDEO -> VIDEO_ICON;
            case AUDIO -> AUDIO_ICON;
            case ZIP -> getFileIcon(fileName);
            case MCMETA -> MCMETA_ICON;
            default -> FILE_ICON;
        };

        int drawX = startX + (fileItem.getDepth() * 14);
        renderTreeGuides(context, fileItem, startX, startY, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawTexture(iconTexture, drawX, startY - 4, 0, 0, 16, 16, 16, 16);
        int fileNameColor = isDisabledFile(fileItem.getFile())
                ? withAlpha(uiColorIDEFileNameText, 132)
                : new Color(uiColorIDEFileNameText, true).getRGB();
        int labelMaxWidth = Math.max(24, FILE_LIST_SCROLLBAR_X - (drawX + 24));
        String fittedName = fitLabelToWidth(fileName, labelMaxWidth);
        context.drawText(this.textRenderer, fittedName, drawX + 20, startY, fileNameColor, false);
        captureTruncatedNameTooltip(fileName, fittedName, drawX + 20, startY, labelMaxWidth);
    }

    private void captureTruncatedNameTooltip(String fullName, String renderedName, int x, int y, int maxWidth) {
        if (fullName == null || renderedName == null || Objects.equals(fullName, renderedName)) {
            return;
        }
        int textWidth = Math.min(maxWidth, this.textRenderer.getWidth(renderedName));
        if (hoveredTruncatedFileNameMouseX >= x
                && hoveredTruncatedFileNameMouseX <= x + textWidth
                && hoveredTruncatedFileNameMouseY >= y - 2
                && hoveredTruncatedFileNameMouseY <= y + 10) {
            hoveredTruncatedFileName = fullName;
        }
    }

    private int getFileListVisibleRows() {
        return Math.max(1, (this.height - 100) / 20);
    }

    private int getMaxFileListScroll() {
        return Math.max(0, fileItems.size() - getFileListVisibleRows());
    }

    private void renderFileListScrollbar(DrawContext context) {
        if (getMaxFileListScroll() <= 0) {
            return;
        }
        int trackX = FILE_LIST_SCROLLBAR_X;
        int trackY = 82;
        int trackHeight = Math.max(1, this.height - 104);
        drawExplorerScrollbar(context, trackX, trackY, trackHeight, getFileListScrollbarThumbY(), getFileListScrollbarThumbHeight());
    }

    private boolean isOverFileListScrollbar(double mouseX, double mouseY) {
        if (getMaxFileListScroll() <= 0) {
            return false;
        }
        int trackY = 82;
        int trackHeight = Math.max(1, this.height - 104);
        return mouseX >= FILE_LIST_SCROLLBAR_X - 4
                && mouseX <= FILE_LIST_SCROLLBAR_X + FILE_LIST_SCROLLBAR_WIDTH
                && mouseY >= trackY
                && mouseY <= trackY + trackHeight;
    }

    private int getFileListScrollbarThumbHeight() {
        int trackHeight = Math.max(1, this.height - 104);
        return Math.max(18, Math.min(trackHeight, (int) ((getFileListVisibleRows() / (float) Math.max(1, fileItems.size())) * trackHeight)));
    }

    private int getFileListScrollbarThumbY() {
        int trackY = 82;
        int trackHeight = Math.max(1, this.height - 104);
        int thumbHeight = getFileListScrollbarThumbHeight();
        int travel = Math.max(1, trackHeight - thumbHeight);
        return trackY + (int) ((scrollOffset / (float) Math.max(1, getMaxFileListScroll())) * travel);
    }

    private void setFileListScrollFromThumbTop(int thumbTop) {
        int trackY = 82;
        int trackHeight = Math.max(1, this.height - 104);
        int thumbHeight = getFileListScrollbarThumbHeight();
        int minTop = trackY;
        int maxTop = trackY + trackHeight - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int travel = Math.max(1, maxTop - minTop);
        float ratio = (clampedTop - minTop) / (float) travel;
        scrollOffset = Math.max(0, Math.min(getMaxFileListScroll(), Math.round(ratio * getMaxFileListScroll())));
    }

    private int[] getSuggestionPopupBounds() {
        int visibleSuggestions = getVisibleSuggestionRows();
        int rowHeight = 16;
        int suggestionBoxHeight = visibleSuggestions * rowHeight + 10;
        int suggestionBoxX = pathInput.getX();
        int suggestionBoxY = pathInput.getY() + pathInput.getHeight();
        int suggestionWidth = pathInput.getWidth();
        if (suggestionBoxY + suggestionBoxHeight > this.height) {
            suggestionBoxY = pathInput.getY() - suggestionBoxHeight;
        }
        return new int[]{suggestionBoxX, suggestionBoxY, suggestionWidth, suggestionBoxHeight};
    }

    private int getVisibleSuggestionRows() {
        return Math.min(pathSuggestions.size(), maxVisibleSuggestions);
    }

    private int getMaxSuggestionScroll() {
        return Math.max(0, pathSuggestions.size() - getVisibleSuggestionRows());
    }

    private boolean isInsideSuggestionPopup(double mouseX, double mouseY) {
        if (!showSuggestions || pathSuggestions.isEmpty()) {
            return false;
        }
        int[] box = getSuggestionPopupBounds();
        return mouseX >= box[0] && mouseX <= box[0] + box[2] && mouseY >= box[1] && mouseY <= box[1] + box[3];
    }

    private void renderSuggestionScrollbar(DrawContext context, int x, int y, int width, int height, int visibleRows) {
        if (pathSuggestions.size() <= visibleRows) {
            return;
        }
        int trackX = x + width - 7;
        drawExplorerScrollbar(context, trackX, y + 5, Math.max(1, height - 10), getSuggestionScrollbarThumbY(), getSuggestionScrollbarThumbHeight());
    }

    private boolean isOverSuggestionScrollbar(double mouseX, double mouseY) {
        if (getMaxSuggestionScroll() <= 0) {
            return false;
        }
        int[] box = getSuggestionPopupBounds();
        int trackX = box[0] + box[2] - 7;
        return mouseX >= trackX - 5
                && mouseX <= trackX + 8
                && mouseY >= box[1] + 5
                && mouseY <= box[1] + box[3] - 5;
    }

    private int getSuggestionScrollbarThumbHeight() {
        int[] box = getSuggestionPopupBounds();
        int trackHeight = Math.max(1, box[3] - 10);
        return Math.max(16, Math.min(trackHeight, (int) ((getVisibleSuggestionRows() / (float) Math.max(1, pathSuggestions.size())) * trackHeight)));
    }

    private int getSuggestionScrollbarThumbY() {
        int[] box = getSuggestionPopupBounds();
        int trackY = box[1] + 5;
        int trackHeight = Math.max(1, box[3] - 10);
        int thumbHeight = getSuggestionScrollbarThumbHeight();
        int travel = Math.max(1, trackHeight - thumbHeight);
        return trackY + (int) ((scrollOffsetTextBar / (float) Math.max(1, getMaxSuggestionScroll())) * travel);
    }

    private void setSuggestionScrollFromThumbTop(int thumbTop) {
        int[] box = getSuggestionPopupBounds();
        int trackY = box[1] + 5;
        int trackHeight = Math.max(1, box[3] - 10);
        int thumbHeight = getSuggestionScrollbarThumbHeight();
        int minTop = trackY;
        int maxTop = trackY + trackHeight - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int travel = Math.max(1, maxTop - minTop);
        float ratio = (clampedTop - minTop) / (float) travel;
        scrollOffsetTextBar = Math.max(0, Math.min(getMaxSuggestionScroll(), Math.round(ratio * getMaxSuggestionScroll())));
    }

    private void resetPreviewScrollbar() {
        previewScrollbarY = 0;
        previewScrollbarHeight = 0;
        previewScrollbarTotalRows = 0;
        previewScrollbarVisibleRows = 0;
        previewScrollbarFolderMode = false;
    }

    private void configurePreviewScrollbar(int y, int height, int totalRows, int visibleRows, boolean folderMode) {
        previewScrollbarY = y;
        previewScrollbarHeight = Math.max(1, height);
        previewScrollbarTotalRows = Math.max(1, totalRows);
        previewScrollbarVisibleRows = Math.max(1, visibleRows);
        previewScrollbarFolderMode = folderMode;
    }

    private int getMaxPreviewScroll() {
        return Math.max(0, previewScrollbarTotalRows - previewScrollbarVisibleRows);
    }

    private int getPreviewScrollOffset() {
        return previewScrollbarFolderMode ? scrollOffsetFolderPreview : scrollOffsetViewer;
    }

    private void setPreviewScrollOffset(int value) {
        int clamped = Math.max(0, Math.min(getMaxPreviewScroll(), value));
        if (previewScrollbarFolderMode) {
            scrollOffsetFolderPreview = clamped;
        } else {
            scrollOffsetViewer = clamped;
        }
    }

    private void renderPreviewScrollbar(DrawContext context) {
        if (getMaxPreviewScroll() <= 0) {
            return;
        }
        int trackX = this.width - 9;
        drawExplorerScrollbar(context, trackX, previewScrollbarY, previewScrollbarHeight, getPreviewScrollbarThumbY(), getPreviewScrollbarThumbHeight());
    }

    private boolean isOverPreviewScrollbar(double mouseX, double mouseY) {
        if (getMaxPreviewScroll() <= 0) {
            return false;
        }
        int trackX = this.width - 9;
        return mouseX >= trackX - 5
                && mouseX <= trackX + 8
                && mouseY >= previewScrollbarY
                && mouseY <= previewScrollbarY + previewScrollbarHeight;
    }

    private int getPreviewScrollbarThumbHeight() {
        return Math.max(18, Math.min(previewScrollbarHeight, (int) ((previewScrollbarVisibleRows / (float) previewScrollbarTotalRows) * previewScrollbarHeight)));
    }

    private int getPreviewScrollbarThumbY() {
        int thumbHeight = getPreviewScrollbarThumbHeight();
        int travel = Math.max(1, previewScrollbarHeight - thumbHeight);
        return previewScrollbarY + (int) ((getPreviewScrollOffset() / (float) Math.max(1, getMaxPreviewScroll())) * travel);
    }

    private void setPreviewScrollFromThumbTop(int thumbTop) {
        int thumbHeight = getPreviewScrollbarThumbHeight();
        int minTop = previewScrollbarY;
        int maxTop = previewScrollbarY + previewScrollbarHeight - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int travel = Math.max(1, maxTop - minTop);
        float ratio = (clampedTop - minTop) / (float) travel;
        setPreviewScrollOffset(Math.round(ratio * getMaxPreviewScroll()));
    }

    private void drawExplorerScrollbar(DrawContext context, int trackX, int trackY, int trackHeight, int thumbY, int thumbHeight) {
        int lineX = trackX + 1;
        context.fill(lineX, trackY, lineX + EXPLORER_SCROLLBAR_TRACK_WIDTH, trackY + trackHeight, new Color(uiColorFileEditorScrollbarTrack, true).getRGB());
        context.fill(trackX, thumbY, trackX + EXPLORER_SCROLLBAR_THUMB_WIDTH, thumbY + thumbHeight, new Color(uiColorFileEditorScrollbarThumb, true).getRGB());
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (showSuggestions && isInsideSuggestionPopup(mouseX, mouseY)) {
            scrollOffsetTextBar = Math.max(0, Math.min(scrollOffsetTextBar - (int) amount, getMaxSuggestionScroll()));
            return true;
        }

        if (mouseX < LEFT_PANEL_WIDTH && mouseY > 80) {
            scrollOffset = Math.max(0, Math.min(scrollOffset - (int) amount, getMaxFileListScroll()));
        } else if (mouseX > LEFT_PANEL_WIDTH && mouseY > 80 && selectedFileItem != null && selectedFileItem.getType() == FileType.FILE) {
            String[] lines = fileContent != null ? fileContent.split("\n") : new String[0];
            if (hasShiftDown()) {
                int maxHorizontalPreviewScroll = getMaxHorizontalPreviewScroll(lines);
                horizontalScrollOffsetViewer = Math.max(0, Math.min(horizontalScrollOffsetViewer - (int) amount, maxHorizontalPreviewScroll));
            } else {
                int maxViewerScroll = Math.max(0, lines.length - getVisibleTextPreviewLines(getTextPreviewStartY()));
                scrollOffsetViewer = Math.max(0, Math.min(scrollOffsetViewer - (int) amount, maxViewerScroll));
            }
        } else if (mouseX > LEFT_PANEL_WIDTH && mouseY > 80 && getMaxPreviewScroll() > 0 && previewScrollbarFolderMode) {
            scrollOffsetFolderPreview = Math.max(0, Math.min(scrollOffsetFolderPreview - (int) amount, getMaxPreviewScroll()));
        }

        return super.mouseScrolled(mouseX, mouseY, amount);
    }


    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showDeleteConfirmPopup) {
            return handleDeleteConfirmClick(mouseX, mouseY, button);
        }
        if (topBarOpenMenu.isOpen() && (topBarOpenBounds == null || !isWithinBounds(topBarOpenBounds, mouseX, mouseY))) {
            MenuEntry selected = topBarOpenMenu.click(mouseX, mouseY);
            if (selected != null) {
                handleTopBarOpenAction(selected.id());
                return true;
            }
        }
        if (previewOpenMenu.isOpen() && (previewOpenBounds == null || !isWithinBounds(previewOpenBounds, mouseX, mouseY))) {
            MenuEntry selected = previewOpenMenu.click(mouseX, mouseY);
            if (selected != null) {
                handleOpenAction(previewOpenTarget, selected.id());
                return true;
            }
        }
        if (!pathInput.isMouseOver(mouseX, mouseY)) pathInput.setFocused(false);
        if (showSuggestions) {
            if (button == 0 && isOverSuggestionScrollbar(mouseX, mouseY)) {
                int thumbY = getSuggestionScrollbarThumbY();
                int thumbHeight = getSuggestionScrollbarThumbHeight();
                draggingSuggestionScrollbar = true;
                suggestionScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
                setSuggestionScrollFromThumbTop((int) mouseY - suggestionScrollbarDragOffset);
                return true;
            }
            int[] suggestionBox = getSuggestionPopupBounds();
            int suggestionBoxX = suggestionBox[0];
            int suggestionBoxY = suggestionBox[1];
            int suggestionWidth = suggestionBox[2];
            int rowHeight = 16;
            int suggestionBoxHeight = suggestionBox[3];
            if (mouseX >= suggestionBoxX && mouseX <= suggestionBoxX + suggestionWidth &&
                    mouseY >= suggestionBoxY && mouseY <= suggestionBoxY + suggestionBoxHeight) {
                int relativeY = Math.max(0, (int) (mouseY - suggestionBoxY - 5));
                int clickedIndex = relativeY / rowHeight + scrollOffsetTextBar;
                if (clickedIndex >= 0 && clickedIndex < pathSuggestions.size()) {
                    pathInput.setText(pathSuggestions.get(clickedIndex));
                    showSuggestions = false;
                    openPathInputTarget();
                    return true;
                }
                return true;
            }
        }

        if (button == 0 && isOverFileListScrollbar(mouseX, mouseY)) {
            int thumbY = getFileListScrollbarThumbY();
            int thumbHeight = getFileListScrollbarThumbHeight();
            draggingFileListScrollbar = true;
            fileListScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
            setFileListScrollFromThumbTop((int) mouseY - fileListScrollbarDragOffset);
            return true;
        }

        if (button == 0 && isOverPreviewScrollbar(mouseX, mouseY)) {
            int thumbY = getPreviewScrollbarThumbY();
            int thumbHeight = getPreviewScrollbarThumbHeight();
            draggingPreviewScrollbar = true;
            previewScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
            setPreviewScrollFromThumbTop((int) mouseY - previewScrollbarDragOffset);
            return true;
        }

        if (isTopBarButtonClicked(mouseX, mouseY, 10, getTopBarButtonWidth(TOP_BAR_BACK_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            navigateBack();
            return true;
        }
        int rightButtonsX = getTopBarOpenButtonX();
        if (isTopBarButtonClicked(mouseX, mouseY, rightButtonsX, getTopBarButtonWidth(TOP_BAR_OPEN_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            topBarOpenMenu.toggleAtPointer(mouseX, mouseY, this.width, this.height, buildTopBarOpenMenuItems());
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarHomeButtonX(), getTopBarButtonWidth(TOP_BAR_HOME_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            setDirectory(new File("."));
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarReloadButtonX(), getTopBarButtonWidth(TOP_BAR_RELOAD_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            reloadCurrentPath();
            return true;
        }
        if (button == 0 && isWithinBounds(previewOpenBounds, mouseX, mouseY) && previewOpenTarget != null) {
            UiSoundHelper.playButtonClick();
            previewOpenMenu.toggleAtPointer(mouseX, mouseY, this.width, this.height, buildOpenMenuItemsForTarget(previewOpenTarget));
            return true;
        }
        if (button == 0 && isWithinBounds(previewTitleBounds, mouseX, mouseY) && selectedFileItem != null) {
            long currentTime = System.currentTimeMillis();
            String clickedPath = selectedFileItem.getFile().getAbsolutePath() + "#preview-title";
            boolean isDoubleClick = clickedPath.equals(lastClickedPath) && currentTime - lastClickTime <= DOUBLE_CLICK_TIME_THRESHOLD;
            lastClickedPath = clickedPath;
            lastClickTime = currentTime;
            if (isDoubleClick) {
                UiSoundHelper.playButtonClick();
                beginRename(selectedFileItem.getFile());
                return true;
            }
        }
        if (button == 0 && isWithinBounds(previewRenameBounds, mouseX, mouseY) && selectedFileItem != null) {
            UiSoundHelper.playButtonClick();
            if (renameMode && renameTarget != null && renameTarget.equals(selectedFileItem.getFile())) {
                commitRename();
            } else {
                beginRename(selectedFileItem.getFile());
            }
            return true;
        }
        if (button == 0 && isWithinBounds(previewDeleteBounds, mouseX, mouseY) && selectedFileItem != null) {
            UiSoundHelper.playButtonClick();
            if (renameMode && renameTarget != null && renameTarget.equals(selectedFileItem.getFile())) {
                cancelRename();
            } else {
                beginDelete(selectedFileItem.getFile());
            }
            return true;
        }
        if (renameMode && renameInput != null && !renameInput.isMouseOver(mouseX, mouseY)) {
            cancelRename();
        }
        if (renameMode && renameInput != null) {
            renameInput.setFocused(renameInput.isMouseOver(mouseX, mouseY));
        }

        if (button == 0) {
            if (hasActiveVisualSessionForSelection()) {
                if (visualPreviewGif && isWithinBounds(visualPreviewBounds, mouseX, mouseY) && activeVisualSession != null) {
                    UiSoundHelper.playButtonClick();
                    if (activeVisualSession.state() == VisualPlaybackState.PLAYING) {
                        activeVisualSession.pause();
                    } else {
                        stopThemeMusicForMediaPlayback();
                        activeVisualSession.play();
                    }
                    return true;
                }
                handleVisualTimestampBarClick((int) mouseX, (int) mouseY);
                if (isWithinBounds(visualPlayOverlayBounds, mouseX, mouseY) && activeVisualSession != null) {
                    UiSoundHelper.playButtonClick();
                    stopThemeMusicForMediaPlayback();
                    activeVisualSession.play();
                    return true;
                }
                if (isWithinBounds(visualPrimaryControlBounds, mouseX, mouseY) && activeVisualSession != null) {
                    UiSoundHelper.playButtonClick();
                    if (activeVisualSession.state() == VisualPlaybackState.PLAYING) {
                        activeVisualSession.pause();
                    } else {
                        stopThemeMusicForMediaPlayback();
                        activeVisualSession.play();
                    }
                    return true;
                }
                if (!visualPreviewGif && isWithinBounds(visualSecondaryControlBounds, mouseX, mouseY) && activeVisualSession != null) {
                    UiSoundHelper.playButtonClick();
                    activeVisualSession.stop();
                    return true;
                }
            }

            FolderPreviewClickTarget previewTarget = getFolderPreviewClickTarget(mouseX, mouseY);
            if (previewTarget != null) {
                navigateToPreviewTarget(previewTarget.file);
                return true;
            }

            long currentTime = System.currentTimeMillis();
            int startY = 80;
            int endY = this.height - 20;
            int filesPerPage = (endY - startY) / 20;
            int maxScroll = Math.max(0, fileItems.size() - filesPerPage);
            int scrollY = Math.min(scrollOffset, maxScroll);
            startY -= scrollY * 20;
            boolean fileClicked = false;

            for (FileItem fileItem : fileItems) {
                if (startY > endY || startY < 80) {
                    startY += 20;
                    continue;
                }

                if (mouseX >= FILE_LIST_ROW_X && mouseX <= FILE_LIST_ROW_X + FILE_LIST_ROW_WIDTH && mouseY >= startY - 4 && mouseY <= startY + 10) {
                    String clickedPath = fileItem.getFile().getAbsolutePath();
                    boolean isDoubleClick = clickedPath.equals(lastClickedPath) && currentTime - lastClickTime <= DOUBLE_CLICK_TIME_THRESHOLD;
                    if (fileItem.getType() == FileType.FOLDER) {
                        UiSoundHelper.playButtonClick();
                        selectFileItem(fileItem);
                        selectedFileItem = fileItem;
                        fileContent = null;
                        syncPathInputToTarget(fileItem.getFile());
                        if (isDoubleClick) {
                            openFolder(fileItem);
                        } else {
                            toggleFolderExpansion(fileItem);
                        }
                    } else {
                        UiSoundHelper.playButtonClick();
                        openFile(fileItem);
                        if (isDoubleClick && isEditableTextType(fileItem.getType())) {
                            assert this.client != null;
                            this.client.setScreen(new FileEditorScreen(this, fileItem.getFile(), fileItem));
                            return true;
                        }
                        fileClicked = true;
                    }
                    lastClickedPath = clickedPath;
                    lastClickTime = currentTime;
                    break;
                }
                startY += 20;
            }

            if (fileClicked) {
                return true;
            }

            if (button == 0 && selectedFileItem != null && selectedFileItem.getType() == FileType.AUDIO) {
                if (handleTimestampBarClick((int) mouseX, (int) mouseY)) {
                    return true;
                }
                if (isWithinBounds(audioPlayBounds, mouseX, mouseY)) {
                    UiSoundHelper.playButtonClick();
                    stopThemeMusicForMediaPlayback();
                    if (!AudioManager.hasActiveAudio()) {
                        AudioManager.playAudio(selectedFileItem.getFile(), false, 1);
                    } else if (!AudioManager.isCurrentAudioFile(selectedFileItem.getFile())) {
                        AudioManager.playAudio(selectedFileItem.getFile(), false, 1);
                    } else if (AudioManager.isAudioPlaying()) {
                        pauseAudio();
                    } else {
                        playAudio(selectedFileItem.getFile(), false, 1);
                    }
                    return true;
                } else if (isWithinBounds(audioStopBounds, mouseX, mouseY)) {
                    UiSoundHelper.playButtonClick();
                    if (AudioManager.hasActiveAudio()) {
                        stopAllAudio();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && draggingSuggestionScrollbar) {
            setSuggestionScrollFromThumbTop((int) mouseY - suggestionScrollbarDragOffset);
            return true;
        }
        if (button == 0 && draggingFileListScrollbar) {
            setFileListScrollFromThumbTop((int) mouseY - fileListScrollbarDragOffset);
            return true;
        }
        if (button == 0 && draggingPreviewScrollbar) {
            setPreviewScrollFromThumbTop((int) mouseY - previewScrollbarDragOffset);
            return true;
        }
        if (button == 0) {
            handleTimestampBarClick((int) mouseX, (int) mouseY);
            handleVisualTimestampBarClick((int) mouseX, (int) mouseY);
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (draggingSuggestionScrollbar || draggingFileListScrollbar || draggingPreviewScrollbar)) {
            draggingSuggestionScrollbar = false;
            draggingFileListScrollbar = false;
            draggingPreviewScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (showDeleteConfirmPopup) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                showDeleteConfirmPopup = false;
                deleteTarget = null;
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (renameMode && renameInput != null && renameInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitRename();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelRename();
                return true;
            }
        }
        if (pathInput != null && pathInput.isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            showSuggestions = false;
            openPathInputTarget();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (renameMode && renameInput != null && renameInput.isFocused()) {
            return super.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    private boolean handleTimestampBarClick(int mouseX, int mouseY) {
        if (audioTimestampBounds != null
                && mouseX >= audioTimestampBounds[0] && mouseX <= audioTimestampBounds[0] + audioTimestampBounds[2]
                && mouseY >= audioTimestampBounds[1] && mouseY <= audioTimestampBounds[1] + audioTimestampBounds[3]
                && selectedFileItem != null
                && AudioManager.isCurrentAudioFile(selectedFileItem.getFile())
                && AudioManager.canSeekPlayback(selectedFileItem.getFile())) {
            float clickPosition = (float) (mouseX - audioTimestampBounds[0]) / audioTimestampBounds[2];
            AudioManager.seekToProgress(selectedFileItem.getFile(), clickPosition, 1);
            return true;
        }
        return false;
    }

    private void stopThemeMusicForMediaPlayback() {
        DesignMusicController.stopDesignMusicOnly();
    }

    private void handleVisualTimestampBarClick(int mouseX, int mouseY) {
        if (visualTimestampBounds != null
                && mouseX >= visualTimestampBounds[0] && mouseX <= visualTimestampBounds[0] + visualTimestampBounds[2]
                && mouseY >= visualTimestampBounds[1] && mouseY <= visualTimestampBounds[1] + visualTimestampBounds[3]
                && hasActiveVisualSessionForSelection()
                && activeVisualSession.canSeek()) {
            float clickPosition = (float) (mouseX - visualTimestampBounds[0]) / visualTimestampBounds[2];
            activeVisualSession.seekTo((long) (activeVisualSession.durationMillis() * clickPosition));
        }
    }

    private Identifier getFileIcon(String fileName) {
        return FIleIconHelper.resolve(fileName);
    }

    private void openFile(FileItem fileItem) {
        selectFileItem(fileItem);
        closeVisualSession();
        selectedFileItem = fileItem;
        fileContent = null;
        cachedPreviewTextPath = null;
        cachedPreviewTextLastModified = Long.MIN_VALUE;
        scrollOffsetViewer = 0;
        scrollOffsetFolderPreview = 0;
        horizontalScrollOffsetViewer = 0;
        syncPathInputToTarget(fileItem.getFile());
    }

    private void openFolder(FileItem folderItem) {
        setDirectory(folderItem.getFile());
    }

    private void selectFileItem(FileItem nextSelection) {
        if (shouldStopAudioForSelectionChange(nextSelection)) {
            stopAllAudio();
        }
    }

    private void stopSelectedAudioIfActive() {
        if (shouldStopAudioForSelectionChange(null)) {
            stopAllAudio();
        }
    }

    private boolean shouldStopAudioForSelectionChange(FileItem nextSelection) {
        if (selectedFileItem == null || selectedFileItem.getType() != FileType.AUDIO || selectedFileItem.getFile() == null) {
            return false;
        }
        if (!AudioManager.isCurrentAudioFile(selectedFileItem.getFile())) {
            return false;
        }
        if (nextSelection == null || nextSelection.getFile() == null) {
            return true;
        }
        return !sameFile(selectedFileItem.getFile(), nextSelection.getFile());
    }

    private boolean sameFile(File first, File second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (IOException ignored) {
            return first.getAbsolutePath().equals(second.getAbsolutePath());
        }
    }

    private boolean hasActiveVisualSessionForSelection() {
        return activeVisualSession != null
                && selectedFileItem != null
                && selectedFileItem.getFile() != null
                && selectedFileItem.getFile().getAbsolutePath().equals(activeVisualSessionPath);
    }

    private boolean supportsAnimatedImagePlayback(File file) {
        return file != null && file.isFile() && "gif".equals(ExternalImageLoader.isSupportedImageFile(file)
                ? file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "");
    }

    private VisualPlaybackSession ensureVisualSession(File file, int maxWidth, int maxHeight) {
        if (file == null || !file.isFile()) {
            closeVisualSession();
            return null;
        }
        String path = file.getAbsolutePath();
        if (activeVisualSession != null
                && path.equals(activeVisualSessionPath)
                && activeVisualSessionWidth == maxWidth
                && activeVisualSessionHeight == maxHeight) {
            return activeVisualSession;
        }

        closeVisualSession();
        try {
            if (classifyFileType(file) == FileType.VIDEO) {
                activeVisualSession = VideoService.createSession(file, maxWidth, maxHeight);
            } else if (supportsAnimatedImagePlayback(file)) {
                activeVisualSession = AnimatedGifPlaybackSession.createIfAnimated(file, maxWidth, maxHeight);
            }
            if (activeVisualSession != null) {
                activeVisualSessionPath = path;
                activeVisualSessionWidth = maxWidth;
                activeVisualSessionHeight = maxHeight;
            }
            return activeVisualSession;
        } catch (IOException exception) {
            closeVisualSession();
            return null;
        }
    }

    private void closeVisualSession() {
        if (activeVisualSession != null) {
            activeVisualSession.close();
        }
        activeVisualSession = null;
        activeVisualSessionPath = null;
        activeVisualSessionWidth = -1;
        activeVisualSessionHeight = -1;
        visualPreviewBounds = null;
        visualPlayOverlayBounds = null;
        visualPrimaryControlBounds = null;
        visualSecondaryControlBounds = null;
        visualTimestampBounds = null;
        visualPreviewGif = false;
        visualPreviewVideo = false;
    }

    private void beginRename(File target) {
        if (target == null) {
            return;
        }
        renameMode = true;
        renameTarget = target;
        renameInput.setText(target.getName());
        renameInput.setVisible(true);
        renameInput.setEditable(true);
        renameInput.setFocused(true);
        renameInput.setSelectionStart(0);
        renameInput.setSelectionEnd(target.getName().length());
    }

    private void cancelRename() {
        renameMode = false;
        renameTarget = null;
        if (renameInput != null) {
            renameInput.setFocused(false);
            renameInput.setVisible(false);
            renameInput.setEditable(false);
        }
    }

    private void commitRename() {
        if (!renameMode || renameTarget == null || renameInput == null) {
            return;
        }
        String nextName = renameInput.getText() == null ? "" : renameInput.getText().trim();
        if (nextName.isEmpty() || nextName.equals(renameTarget.getName())) {
            cancelRename();
            return;
        }
        File destination = new File(renameTarget.getParentFile(), nextName);
        if (destination.exists()) {
            cancelRename();
            return;
        }
        boolean renamed = renameTarget.renameTo(destination);
        cancelRename();
        if (renamed) {
            refreshExplorerSelection(destination);
        }
    }

    private void beginDelete(File target) {
        if (target == null) {
            return;
        }
        deleteTarget = null;
        showDeleteConfirmPopup = false;
        File parentDirectory = target.getParentFile();
        String restorePath = pathFromInstance(parentDirectory == null ? currentDirectory : parentDirectory);
        if (this.client != null) {
            this.client.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) {
                    deleteRecursively(target);
                    restoreExplorerPathAfterDelete(restorePath);
                }
                MinecraftClient.getInstance().setScreen(this);
            }, Text.literal("Delete File"), Text.literal("Are you sure you want to delete " + target.getName() + "?"), Text.literal("Delete"), Text.literal("Cancel")));
        }
    }

    private boolean handleDeleteConfirmClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return true;
        }
        int popupWidth = 360;
        int popupHeight = 164;
        int popupX = (this.width - popupWidth) / 2;
        int popupY = (this.height - popupHeight) / 2;
        int cancelX = popupX + 12;
        int deleteX = popupX + popupWidth - 116;
        int buttonY = popupY + popupHeight - 34;
        if (mouseX >= cancelX && mouseX <= cancelX + 104 && mouseY >= buttonY && mouseY <= buttonY + 20) {
            showDeleteConfirmPopup = false;
            deleteTarget = null;
            UiSoundHelper.playButtonClick();
            return true;
        }
        if (mouseX >= deleteX && mouseX <= deleteX + 104 && mouseY >= buttonY && mouseY <= buttonY + 20) {
            File target = deleteTarget;
            showDeleteConfirmPopup = false;
            deleteTarget = null;
            UiSoundHelper.playButtonClick();
            if (target != null) {
                String restorePath = pathFromInstance(target.getParentFile() == null ? currentDirectory : target.getParentFile());
                deleteRecursively(target);
                restoreExplorerPathAfterDelete(restorePath);
            }
            return true;
        }
        return true;
    }

    private void restoreExplorerPathAfterDelete(String restorePath) {
        File directory = resolveDirectoryFromPath(restorePath);
        if (!directory.isDirectory()) {
            directory = new File(".");
            restorePath = "/";
        }
        closeVisualSession();
        selectedFileItem = null;
        fileContent = null;
        scrollOffset = 0;
        scrollOffsetViewer = 0;
        scrollOffsetFolderPreview = 0;
        horizontalScrollOffsetViewer = 0;
        loadFileItems(directory);
        updatePathInput(restorePath == null || restorePath.isBlank() ? "/" : restorePath);
        updatePathSuggestions();
    }

    private void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        target.delete();
    }

    private void copyDroppedPaths(List<Path> sources, Path targetDirectory) {
        for (Path source : sources) {
            if (source == null || !Files.exists(source)) {
                continue;
            }
            try {
                Path destination = uniqueDropDestination(targetDirectory.resolve(source.getFileName()));
                if (Files.isDirectory(source)) {
                    copyDirectoryRecursively(source, destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void copyDirectoryRecursively(Path sourceDirectory, Path destinationDirectory) throws IOException {
        try (var stream = Files.walk(sourceDirectory)) {
            for (Path source : stream.toList()) {
                Path relative = sourceDirectory.relativize(source);
                Path destination = destinationDirectory.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private Path uniqueDropDestination(Path destination) {
        if (!Files.exists(destination)) {
            return destination;
        }
        Path parent = destination.getParent();
        String fileName = destination.getFileName() == null ? "dropped" : destination.getFileName().toString();
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int index = 1; index < 1000; index++) {
            Path candidate = parent.resolve(base + " (" + index + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return parent.resolve(base + " (" + System.currentTimeMillis() + ")" + extension);
    }

    private void refreshExplorerSelection(File target) {
        File directory = target != null && target.isDirectory() ? target : (target == null ? currentDirectory : target.getParentFile());
        if (directory == null) {
            directory = currentDirectory;
        }
        setDirectory(directory);
        if (target != null && target.exists() && target.isFile()) {
            FileItem matchingItem = findFileItem(target);
            if (matchingItem != null) {
                openFile(matchingItem);
            }
        } else if (target != null && target.exists() && target.isDirectory()) {
            selectedFileItem = new FileItem(target.getName(), FileType.FOLDER, target);
        } else {
            selectedFileItem = null;
        }
    }

    private void toggleFolderExpansion(FileItem folderItem) {
        if (folderItem.isExpanded()) {
            collapseFolder(folderItem);
        } else {
            expandFolder(folderItem);
        }
    }

    private void expandFolder(FileItem folderItem) {
        File[] children = folderItem.getFile().listFiles();
        if (children == null || children.length == 0) {
            folderItem.setExpanded(false);
            return;
        }

        List<FileItem> childItems = createFileItems(children, folderItem, folderItem.getDepth() + 1);
        int insertIndex = fileItems.indexOf(folderItem) + 1;
        fileItems.addAll(insertIndex, childItems);
        folderItem.setExpanded(true);
    }

    private void collapseFolder(FileItem folderItem) {
        folderItem.setExpanded(false);
        fileItems.removeIf(item -> isDescendantOf(item, folderItem));
    }

    private boolean isDescendantOf(FileItem item, FileItem ancestor) {
        FileItem current = item.getParent();
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void loadFileItems(File directory) {
        if (directory.isDirectory()) {
            currentDirectory = directory;
            this.fileItems.clear();
            File[] files = directory.listFiles();
            if (files != null) {
                this.fileItems.addAll(createFileItems(files, null, 0));
            }
            lastVisitedPath = directory.getPath().replaceFirst("^\\.", "");
            if (lastVisitedPath.isEmpty()) {
                lastVisitedPath = "/";
            }
            lastSuggestionInput = "";
            lastSuggestionDirectory = new File("");
        } else if (!"/".equals(lastVisitedPath)) {
            loadFileItems(new File("."));
        }
    }

    private void setDirectory(File directory) {
        if (!directory.isDirectory()) {
            return;
        }

        stopSelectedAudioIfActive();
        closeVisualSession();
        selectedFileItem = null;
        fileContent = null;
        scrollOffset = 0;
        scrollOffsetViewer = 0;
        scrollOffsetFolderPreview = 0;
        horizontalScrollOffsetViewer = 0;
        updatePathInput(directory.getPath().replaceFirst("^\\.", ""));
        loadFileItems(directory);
        updatePathSuggestions();
    }

    private void updatePathInput(String path) {
        suppressPathInputListener = true;
        pathInput.setText(path);
        suppressPathInputListener = false;
    }

    private void syncPathInputToTarget(File target) {
        String path = pathFromInstance(target);
        updatePathInput(path);
        lastVisitedPath = path;
        updatePathSuggestions();
    }

    private String pathFromInstance(File target) {
        if (target == null) {
            return "/";
        }
        String path = target.getPath().replace("\\", "/").replaceFirst("^\\.", "");
        return path.isBlank() ? "/" : path;
    }

    private void openInitialTargetIfPresent(String path) {
        File target = new File("." + path);
        if (!target.exists()) {
            return;
        }
        if (target.isFile()) {
            FileItem matchingItem = findFileItem(target);
            if (matchingItem != null) {
                openFile(matchingItem);
                updatePathInput(target.getPath().replace("\\", "/").replaceFirst("^\\.", ""));
            }
            return;
        }
        if (target.isDirectory()) {
            selectedFileItem = new FileItem(target.getName(), FileType.FOLDER, target);
            updatePathInput(target.getPath().replace("\\", "/").replaceFirst("^\\.", ""));
            lastVisitedPath = pathFromInstance(target);
        }
    }

    private File resolveDirectoryFromPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return currentDirectory;
        }

        File directory = new File("." + path);
        if (directory.isDirectory()) {
            return directory;
        }
        if (directory.isFile()) {
            File parent = directory.getParentFile();
            if (parent != null && parent.isDirectory()) {
                return parent;
            }
        }
        return new File(".");
    }

    private void handlePathInputChange(String input) {
        String normalizedInput = input == null ? "" : input.trim();
        scrollOffsetTextBar = 0;
        if (normalizedInput.isEmpty()) {
            loadFileItems(currentDirectory);
            return;
        }

        if (isSearchQuery(normalizedInput)) {
            clearVisibleSelectionState();
            return;
        }

        loadFileItems(resolveDirectoryFromPath(normalizedInput));
    }

    private boolean isSearchQuery(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }

        File directTarget = new File("." + input);
        return !input.startsWith("/") && !input.startsWith(".") && !directTarget.exists();
    }

    private void clearVisibleSelectionState() {
        stopSelectedAudioIfActive();
        selectedFileItem = null;
        fileContent = null;
        cachedPreviewTextPath = null;
        cachedPreviewTextLastModified = Long.MIN_VALUE;
        scrollOffset = 0;
        scrollOffsetViewer = 0;
        scrollOffsetFolderPreview = 0;
        horizontalScrollOffsetViewer = 0;
    }

    private List<FileItem> searchFileItems(File root, String query) {
        List<FileItem> results = new ArrayList<>();
        if (root == null || !root.isDirectory() || query == null || query.isBlank()) {
            return results;
        }

        collectSearchResults(root, root, query.toLowerCase(), results);
        results.sort(Comparator.comparing(FileItem::getName, String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    private void collectSearchResults(File searchRoot, File directory, String query, List<FileItem> results) {
        if (results.size() >= MAX_SEARCH_RESULTS) {
            return;
        }

        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }

        List<FileItem> orderedChildren = createFileItems(children, null, 0);
        for (FileItem child : orderedChildren) {
            String relativePath = searchRoot.toPath().relativize(child.getFile().toPath()).toString();
            if (relativePath.toLowerCase().contains(query)) {
                results.add(new FileItem(relativePath, child.getType(), child.getFile()));
                if (results.size() >= MAX_SEARCH_RESULTS) {
                    return;
                }
            }

            if (child.getType() == FileType.FOLDER) {
                collectSearchResults(searchRoot, child.getFile(), query, results);
                if (results.size() >= MAX_SEARCH_RESULTS) {
                    return;
                }
            }
        }
    }

    private void renderFileInfo(DrawContext context, int mouseX, int mouseY) {
        visualPreviewBounds = null;
        visualPlayOverlayBounds = null;
        visualPrimaryControlBounds = null;
        visualSecondaryControlBounds = null;
        visualTimestampBounds = null;
        visualPreviewGif = false;
        visualPreviewVideo = false;
        resetPreviewScrollbar();
        audioPlayBounds = null;
        audioStopBounds = null;
        audioTimestampBounds = null;
        folderPreviewClickTargets.clear();
        previewOpenBounds = null;
        previewRenameBounds = null;
        previewDeleteBounds = null;
        previewTitleBounds = null;
        previewOpenTarget = null;
        FileItem previewItem = selectedFileItem;
        if (previewItem == null && currentDirectory != null && currentDirectory.isDirectory()) {
            previewItem = new FileItem(getCurrentDirectoryDisplayName(), FileType.FOLDER, currentDirectory);
        }

        if (previewItem != null) {
            String fileName = previewItem.getName();
            FileType fileType = previewItem.getType();
            File file = previewItem.getFile();

            int startX = 180;
            int startY = 80;

            // Render file name
            String fileNameWithoutExt = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            if (renameMode && renameTarget != null && renameTarget.equals(file)) {
                renameInput.setX(startX + 50);
                renameInput.setY(startY - 2);
                renameInput.setWidth(Math.max(220, this.width - (startX + 70)));
                renameInput.setVisible(true);
                renameInput.render(context, 0, 0, 0.0F);
            } else {
                int titleWidth = Math.max(160, Math.min(this.width - (startX + 60), this.textRenderer.getWidth(fileNameWithoutExt) * 2 + 12));
                previewTitleBounds = new int[]{startX + 50, startY - 2, titleWidth, 18};
                context.getMatrices().push();
                context.getMatrices().scale(2F, 2F, 1.0F);
                context.drawText(this.textRenderer, fileNameWithoutExt, startX / 2 + 25, startY / 2, new Color(uiColorIDEFileNameText, true).getRGB(), false);
                context.getMatrices().pop();
            }
            startY += 20;

            // Render file type and size
            String fileTypeName = switch (fileType) {
                case FOLDER -> "Folder";
                case IMAGE -> "Image | " + getFileTypeDescription(file);
                case FILE -> "File | " + getFileTypeDescription(file);
                case AUDIO -> "Audio | " + getFileTypeDescription(file);
                case VIDEO -> "Video | " + getFileTypeDescription(file);
                case ZIP -> getFileTypeDescription(file);
                case MCMETA -> "Minecraft | " + getFileTypeDescription(file);
            };

            Identifier previewIcon = fileType == FileType.FOLDER ? FOLDER_ICON : getFileIcon(fileName);
            int previewIconY = fileType == FileType.FOLDER ? startY - 39 : startY - 34;
            context.drawTexture(previewIcon, startX - 10, previewIconY, 0, 0, 64, 64, 64, 64);
            String statusLabel = isDisabledFile(file) ? " | Disabled" : "";
            context.drawText(this.textRenderer, fileTypeName + " - " + getFileSize(file) + statusLabel, startX + 50, startY, new Color(uiColorIDEFileTypeText, true).getRGB(), false);
            startY += 10;

            boolean canManage = canManagePreviewTarget(file);
            if (canPreviewOpenTarget(file)) {
                previewOpenTarget = file;
                previewOpenBounds = new int[]{startX + 50, startY, 46, 16};
                renderInlineActionButton(context, previewOpenBounds[0], previewOpenBounds[1], previewOpenBounds[2], previewOpenBounds[3], "Open");
                if (canManage) {
                    previewRenameBounds = new int[]{startX + 101, startY, 58, 16};
                    previewDeleteBounds = new int[]{startX + 164, startY, 52, 16};
                    renderInlineActionButton(context, previewRenameBounds[0], previewRenameBounds[1], previewRenameBounds[2], previewRenameBounds[3], renameMode && renameTarget != null && renameTarget.equals(file) ? "Save" : "Rename");
                    renderInlineActionButton(context, previewDeleteBounds[0], previewDeleteBounds[1], previewDeleteBounds[2], previewDeleteBounds[3], renameMode && renameTarget != null && renameTarget.equals(file) ? "Cancel" : "Delete");
                }
                startY += 26;
            } else if (fileType == FileType.FOLDER) {
                startY += 22;
            } else {
                startY += 16;
            }

            if (fileType == FileType.AUDIO) {
                int audioControlY = startY - 4;
                int audioPlayX = startX + 50;
                int audioStopX = audioPlayX + 18;
                int audioBarX = audioStopX + 22;
                int audioBarY = audioControlY + 3;
                audioPlayBounds = new int[]{audioPlayX, audioControlY, 16, 16};
                audioStopBounds = new int[]{audioStopX, audioControlY, 16, 16};
                audioTimestampBounds = new int[]{audioBarX, audioBarY, timestampBarWidth, timestampBarHeight};
                startY += 20;
            }

            KoilLocalModJarInsight modMetadata = getPreviewModMetadata(file);
            if (modMetadata != null) {
                startY = renderModJarMetadata(context, modMetadata, file, startX + 50, startY, Math.max(220, this.width - (startX + 70)));
            }

            if (fileType == FileType.FOLDER) {
                startY = renderFolderPreview(context, file, startX + 50, startY);
            }

            if (fileType == FileType.ZIP) {
                startY = renderZipPreview(context, file, startX + 50, startY);
            }

            // Render file content if it's a text file
            if (isEditableTextType(fileType) && isTextFile(file)) {
                if (shouldReloadPreviewText(file)) {
                    fileContent = readFileContent(file);
                    cachedPreviewTextPath = file.getAbsolutePath();
                    cachedPreviewTextLastModified = file.lastModified();
                }
                String[] lines = fileContent.split("\n");
                int visibleLines = getVisibleTextPreviewLines(startY);
                int scrollRange = Math.max(0, lines.length - visibleLines);

                scrollOffsetViewer = Math.max(0, Math.min(scrollOffsetViewer, scrollRange));
                int textViewportY = startY;
                int textViewportHeight = Math.max(1, this.height - textViewportY - 20);
                configurePreviewScrollbar(textViewportY, textViewportHeight, Math.max(1, lines.length), visibleLines, false);

                int startIndex = scrollOffsetViewer;
                int endIndex = Math.min(startIndex + visibleLines, lines.length);

                for (int i = startIndex; i < endIndex; i++) {
                    renderPreviewLineWithSyntaxHighlighting(context, applyHorizontalPreviewOffset(lines[i]), startX + 50, startY, file);
                    startY += TEXT_PREVIEW_LINE_HEIGHT;
                }

                if (endIndex < lines.length) {
                    context.drawText(this.textRenderer, "...", startX + 50, startY, new Color(uiColorIDEFileDisplayWindowText, true).getRGB(), false);
                    startY += TEXT_PREVIEW_LINE_HEIGHT;
                }
                renderPreviewScrollbar(context);
            }

            if (fileType == FileType.IMAGE) {
                int previewX = startX + 50;
                int infoHeight = getPreferredImageInfoHeight(file);
                int maxWidth = Math.max(120, this.width - (previewX + 20));
                int maxHeight = Math.max(120, this.height - startY - infoHeight - 18);
                int preferredHeight = Math.max(140, Math.min(320, maxHeight));

                if (supportsAnimatedImagePlayback(file)) {
                    renderAnimatedImagePreview(context, previewX, startY, file, maxWidth, maxHeight, preferredHeight, mouseX, mouseY);
                } else {
                    drawImage(context, previewX, startY, file, maxWidth, maxHeight, preferredHeight);
                }
            }

            if (fileType == FileType.VIDEO) {
                VideoMetadata previewMetadata = VideoService.probe(file).metadata();
                int previewX = startX + 50;
                int infoHeight = getPreferredVideoInfoHeight(previewMetadata, file);
                int maxWidth = Math.max(120, this.width - (previewX + 20));
                int maxHeight = Math.max(120, this.height - startY - infoHeight - 18);
                int preferredHeight = Math.max(140, Math.min(320, maxHeight));
                renderVideoPreview(context, file, previewX, startY, maxWidth, maxHeight, preferredHeight, mouseX, mouseY, previewMetadata);
            }
        }
    }

    private void drawImage(DrawContext context, int x, int y, File file, int maxWidth, int maxHeight, int preferredHeight) {
        try {
            ImageTexture imageTexture = ImageTextureService.loadScaledTexture(file, "koil", "explorer_preview/" + file.getAbsolutePath(), maxWidth, maxHeight);
            if (imageTexture != null) {
                boolean needsRefresh = !file.getPath().equals(cachedPreviewImagePath)
                        || cachedPreviewImageWidth != maxWidth
                        || cachedPreviewImageHeight != maxHeight
                        || cachedPreviewImageTextureId == null;
                if (needsRefresh) {
                    cachedPreviewImageTextureId = imageTexture.textureId();
                    cachedPreviewImagePath = file.getPath();
                    cachedPreviewImageWidth = maxWidth;
                    cachedPreviewImageHeight = maxHeight;
                }

                int imageWidth = imageTexture.width();
                int imageHeight = imageTexture.height();
                int[] scaledSize = scaleVisualPreview(imageWidth, imageHeight, maxWidth, maxHeight, preferredHeight);
                if (scaledSize[0] <= 0 || scaledSize[1] <= 0) {
                    return;
                }

                ExternalImageRenderer.drawImage(context, cachedPreviewImageTextureId, x, y, scaledSize[0], scaledSize[1]);
                visualPreviewBounds = new int[]{x, y, scaledSize[0], scaledSize[1]};
                renderImageMetadataFooter(context, file, x, y, scaledSize[0], scaledSize[1], imageWidth, imageHeight, false);
            }
        } catch (IOException e) {
            cachedPreviewImageTextureId = null;
        }
    }

    private void renderAnimatedImagePreview(DrawContext context, int x, int y, File file, int maxWidth, int maxHeight, int preferredHeight, int mouseX, int mouseY) {
        VisualPlaybackSession session = ensureVisualSession(file, maxWidth, maxHeight);
        if (session == null) {
            drawImage(context, x, y, file, maxWidth, maxHeight, preferredHeight);
            return;
        }
        if (session.state() != VisualPlaybackState.PLAYING && session.state() != VisualPlaybackState.PAUSED && session.state() != VisualPlaybackState.SEEKING && session.state() != VisualPlaybackState.FAILED) {
            session.play();
        }
        int[] scaledSize = scaleVisualPreview(session.frameWidth(), session.frameHeight(), maxWidth, maxHeight, preferredHeight);
        int drawWidth = scaledSize[0];
        int drawHeight = scaledSize[1];
        if (drawWidth <= 0 || drawHeight <= 0 || session.currentFrameTexture() == null) {
            return;
        }
        ExternalImageRenderer.drawImage(context, session.currentFrameTexture(), x, y, drawWidth, drawHeight);
        visualPreviewBounds = new int[]{x, y, drawWidth, drawHeight};
        visualPreviewGif = true;
        renderImageMetadataFooter(context, file, x, y, drawWidth, drawHeight, session.frameWidth(), session.frameHeight(), true);
    }

    private void renderVideoPreview(DrawContext context, File file, int x, int y, int maxWidth, int maxHeight, int preferredHeight, int mouseX, int mouseY, VideoMetadata fallbackMetadata) {
        VisualPlaybackSession session = ensureVisualSession(file, maxWidth, maxHeight);
        if (session == null || session.currentFrameTexture() == null) {
            context.drawText(this.textRenderer, "Video session unavailable.", x, y, new Color(uiColorIDEFileTypeText, true).getRGB(), false);
            return;
        }

        int[] scaledSize = scaleVisualPreview(session.frameWidth(), session.frameHeight(), maxWidth, maxHeight, preferredHeight);
        int scaledWidth = scaledSize[0];
        int scaledHeight = scaledSize[1];
        if (scaledWidth <= 0 || scaledHeight <= 0) {
            context.drawText(this.textRenderer, "Video session unavailable.", x, y, new Color(uiColorIDEFileTypeText, true).getRGB(), false);
            return;
        }

        ExternalImageRenderer.drawImage(context, session.currentFrameTexture(), x, y, scaledWidth, scaledHeight);

        VideoMetadata metadata = fallbackMetadata;
        if (session instanceof VideoPlaybackSession videoSession && videoSession.metadata() != null) {
            metadata = videoSession.metadata();
        }
        if (metadata == null) {
            metadata = VideoService.probe(file).metadata();
        }

        visualPreviewBounds = new int[]{x, y, scaledWidth, scaledHeight};
        visualPreviewVideo = true;
        boolean hovered = isWithinBounds(visualPreviewBounds, mouseX, mouseY);
        renderVisualTransportControls(context, session, x, y, scaledWidth, scaledHeight, false, hovered);
        renderVideoMetadataFooter(context, metadata, file, x, y, scaledWidth, scaledHeight);
    }

    private void renderVisualTransportControls(DrawContext context, VisualPlaybackSession session, int x, int y, int width, int height, boolean gifOnly, boolean hovered) {
        if (session == null) {
            return;
        }
        int barHeight = 12;
        int controlPadding = 6;
        int iconSize = 16;
        int footerHeight = 30;
        int controlsY = y + height - footerHeight + 7;
        int progressX = x + controlPadding + (gifOnly ? 0 : (iconSize * 2) + 8);
        int progressWidth = Math.max(48, width - (progressX - x) - controlPadding);
        int progressY = controlsY + 3;
        int fillColor = new Color(uiColorIDEAudioTimestampBarFill, true).getRGB();
        int borderColor = new Color(uiColorIDEAudioTimestampBarBorder, true).getRGB();
        int textColor = new Color(uiColorIDEAudioTimestampText, true).getRGB();
        int overlayColor = withAlpha(uiColorContentBase, 176);

        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());

        visualTimestampBounds = null;
        visualPrimaryControlBounds = null;
        visualSecondaryControlBounds = null;
        visualPlayOverlayBounds = null;

        if (!hovered) {
            return;
        }

        context.fill(x, y + height - footerHeight, x + width, y + height, overlayColor);

        if (!gifOnly) {
            visualPrimaryControlBounds = new int[]{x + controlPadding, controlsY, iconSize, iconSize};
            visualSecondaryControlBounds = new int[]{x + controlPadding + iconSize + 4, controlsY, iconSize, iconSize};
            if (session.state() == VisualPlaybackState.PLAYING) {
                context.drawTexture(PAUSE_BUTTON, visualPrimaryControlBounds[0], visualPrimaryControlBounds[1], 0, 0, iconSize, iconSize, iconSize, iconSize);
            } else {
                context.drawTexture(PLAY_BUTTON, visualPrimaryControlBounds[0], visualPrimaryControlBounds[1], 0, 0, iconSize, iconSize, iconSize, iconSize);
            }
            context.drawTexture(STOP_BUTTON, visualSecondaryControlBounds[0], visualSecondaryControlBounds[1], 0, 0, iconSize, iconSize, iconSize, iconSize);

            if (session.state() != VisualPlaybackState.PLAYING) {
                int overlaySize = Math.max(28, Math.min(56, Math.min(width, height) / 4));
                int overlayX = x + (width - overlaySize) / 2;
                int overlayY = y + Math.max(12, (height - footerHeight - overlaySize) / 2);
                visualPlayOverlayBounds = new int[]{overlayX, overlayY, overlaySize, overlaySize};
                context.fill(overlayX, overlayY, overlayX + overlaySize, overlayY + overlaySize, withAlpha(uiColorContentBase, 188));
                context.drawBorder(overlayX, overlayY, overlaySize, overlaySize, new Color(uiColorBackgroundBorder, true).getRGB());
                int playSize = Math.max(16, overlaySize - 14);
                int playX = overlayX + (overlaySize - playSize) / 2;
                int playY = overlayY + (overlaySize - playSize) / 2;
                context.drawTexture(PLAY_BUTTON, playX, playY, 0, 0, playSize, playSize, playSize, playSize);
            }
        }

        if (session.canSeek()) {
            float progress = session.durationMillis() <= 0L ? 0.0F : Math.max(0.0F, Math.min(1.0F, session.positionMillis() / (float) session.durationMillis()));
            int filledWidth = (int) (progressWidth * progress);
            visualTimestampBounds = new int[]{progressX, progressY, progressWidth, barHeight};
            context.fill(progressX, progressY, progressX + filledWidth, progressY + barHeight, fillColor);
            context.drawBorder(progressX, progressY, progressWidth, barHeight, borderColor);
            context.fill(progressX + filledWidth - 1, progressY - 1, progressX + filledWidth + 1, progressY + barHeight + 1, new Color(uiColorIDEAudioTimestampBarLine, true).getRGB());

            String currentTime = formatVideoDuration(session.positionMillis());
            String totalTime = formatVideoDuration(session.durationMillis());
            int labelY = progressY - 10;
            context.drawText(this.textRenderer, currentTime, progressX, labelY, textColor, false);
            int totalWidth = this.textRenderer.getWidth(totalTime);
            context.drawText(this.textRenderer, totalTime, progressX + progressWidth - totalWidth, labelY, textColor, false);
        }
    }

    private int getPreferredVisualPreviewHeight(int startY, boolean includeFooter) {
        int reservedHeight = includeFooter ? 86 : 32;
        int availableHeight = Math.max(120, this.height - startY - reservedHeight);
        return Math.min(240, availableHeight);
    }

    private int[] scaleVisualPreview(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight, int preferredHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            return new int[]{0, 0};
        }
        double heightScale = preferredHeight > 0 ? (double) preferredHeight / sourceHeight : (double) maxHeight / sourceHeight;
        int scaledWidth = Math.max(1, (int) Math.round(sourceWidth * heightScale));
        int scaledHeight = Math.max(1, (int) Math.round(sourceHeight * heightScale));
        if (scaledWidth > maxWidth) {
            double widthScale = (double) maxWidth / scaledWidth;
            scaledWidth = Math.max(1, (int) Math.round(scaledWidth * widthScale));
            scaledHeight = Math.max(1, (int) Math.round(scaledHeight * widthScale));
        }
        if (scaledHeight > maxHeight) {
            double maxHeightScale = (double) maxHeight / scaledHeight;
            scaledWidth = Math.max(1, (int) Math.round(scaledWidth * maxHeightScale));
            scaledHeight = Math.max(1, (int) Math.round(scaledHeight * maxHeightScale));
        }
        return new int[]{scaledWidth, scaledHeight};
    }

    private int getPreferredImageInfoHeight(File file) {
        int lines = 4;
        if (file != null) {
            if (supportsAnimatedImagePlayback(file)) {
                lines += 1;
            }
            String parent = file.getParent();
            if (parent != null && !parent.isBlank()) {
                lines += 1;
            }
        }
        return 8 + (lines * 14);
    }

    private int getPreferredVideoInfoHeight(VideoMetadata metadata, File file) {
        int lines = 2;
        if (metadata != null) {
            lines += 2;
        }
        if (file != null) {
            String parent = file.getParent();
            if (parent != null && !parent.isBlank()) {
                lines += 1;
            }
        }
        return 8 + (lines * 14);
    }

    private String getImageAnimationSummary(File file) {
        if (file == null || !supportsAnimatedImagePlayback(file)) {
            return "Still Image";
        }
        VisualPlaybackSession session = activeVisualSession;
        if (session != null && file.getAbsolutePath().equals(activeVisualSessionPath) && session.durationMillis() > 0L) {
            return "Animated GIF | " + formatVideoDuration(session.durationMillis());
        }
        return "Animated GIF";
    }

    private void renderImageMetadataFooter(DrawContext context, File file, int x, int y, int width, int height, int sourceWidth, int sourceHeight, boolean animated) {
        int footerHeight = getPreferredImageInfoHeight(file);
        int footerY = y + height + 8;
        int bodyColor = new Color(uiColorIDEFileDisplayWindowText, true).getRGB();
        context.fill(x, footerY, x + width, footerY + footerHeight, withAlpha(uiColorContentBase, 176));
        context.drawBorder(x, footerY, width, footerHeight, new Color(uiColorBackgroundBorder, true).getRGB());

        int lineY = footerY + 6;
        String typeLine = animated ? getImageAnimationSummary(file) : "Image Preview";
        String sizeLine = sourceWidth > 0 && sourceHeight > 0 ? sourceWidth + "x" + sourceHeight : "Unknown size";
        String previewLine = "Preview " + width + "x" + height;
        String fileLine = file != null ? getFileSize(file) + " | " + getFileTypeDescription(file) : "";
        String pathLine = file != null && file.getParent() != null ? file.getParent().replace('\\', '/') : "";

        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(typeLine, width - 12), x + 6, lineY, bodyColor, false);
        lineY += 14;
        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(sizeLine + "  |  " + previewLine, width - 12), x + 6, lineY, bodyColor, false);
        lineY += 14;
        if (!fileLine.isEmpty()) {
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(fileLine, width - 12), x + 6, lineY, bodyColor, false);
            lineY += 14;
        }
        if (!pathLine.isEmpty()) {
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(pathLine, width - 12), x + 6, lineY, bodyColor, false);
        }
    }

    private void renderVideoMetadataFooter(DrawContext context, VideoMetadata metadata, File file, int x, int y, int width, int height) {
        int footerHeight = getPreferredVideoInfoHeight(metadata, file);
        int footerY = y + height + 8;
        int bodyColor = new Color(uiColorIDEFileDisplayWindowText, true).getRGB();
        context.fill(x, footerY, x + width, footerY + footerHeight, withAlpha(uiColorContentBase, 176));
        context.drawBorder(x, footerY, width, footerHeight, new Color(uiColorBackgroundBorder, true).getRGB());

        int lineY = footerY + 6;
        String topLine = "Video Preview";
        String detailLine = "";
        if (metadata != null) {
            StringBuilder builder = new StringBuilder();
            if (metadata.width() > 0 && metadata.height() > 0) {
                builder.append(metadata.width()).append("x").append(metadata.height());
            }
            if (metadata.frameRate() > 0.0D) {
                if (!builder.isEmpty()) {
                    builder.append("  |  ");
                }
                builder.append(String.format(Locale.ROOT, "%.2f FPS", metadata.frameRate()));
            }
            if (metadata.durationMillis() > 0L) {
                if (!builder.isEmpty()) {
                    builder.append("  |  ");
                }
                builder.append(formatVideoDuration(metadata.durationMillis()));
            }
            detailLine = builder.toString();
        }
        String metaLine = "";
        if (metadata != null) {
            String container = metadata.container() == null ? "" : metadata.container().toUpperCase(Locale.ROOT);
            String backend = metadata.backend() == null ? "" : metadata.backend().toUpperCase(Locale.ROOT);
            String stage = metadata.decoderStage() == null ? "" : metadata.decoderStage();
            metaLine = (container.isEmpty() ? "" : container) + (backend.isEmpty() ? "" : (container.isEmpty() ? "" : "  |  ") + backend) + (stage.isEmpty() ? "" : ((container.isEmpty() && backend.isEmpty()) ? "" : "  |  ") + stage);
        }
        String fileLine = file != null ? getFileSize(file) + " | " + getFileTypeDescription(file) : "";
        String pathLine = file != null && file.getParent() != null ? file.getParent().replace('\\', '/') : "";

        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(topLine, width - 12), x + 6, lineY, bodyColor, false);
        lineY += 14;
        if (!detailLine.isEmpty()) {
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(detailLine, width - 12), x + 6, lineY, bodyColor, false);
            lineY += 14;
        }
        if (!metaLine.isEmpty()) {
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(metaLine, width - 12), x + 6, lineY, bodyColor, false);
            lineY += 14;
        }
        if (!fileLine.isEmpty()) {
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(fileLine, width - 12), x + 6, lineY, bodyColor, false);
            lineY += 14;
        }
        if (!pathLine.isEmpty()) {
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(pathLine, width - 12), x + 6, lineY, bodyColor, false);
        }
    }

    private String formatVideoDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }


    private String getFileTypeDescription(File file) {
        String fileName = normalizeSpecialFileName(file.getName()).toLowerCase();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            String fileExtension = fileName.substring(dotIndex + 1);
            return fileExtension.toUpperCase() + " File";
        } else {
            return "File";
        }
    }

    private String getFileSize(File file) {
        long bytes = file.length();
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private String formatTime(long microseconds) {
        long seconds = microseconds / 1_000_000;
        long minutes = seconds / 60;
        seconds %= 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private String applyHorizontalPreviewOffset(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (horizontalScrollOffsetViewer <= 0) {
            return line;
        }
        if (horizontalScrollOffsetViewer >= line.length()) {
            return "";
        }
        return line.substring(horizontalScrollOffsetViewer);
    }

    private List<FileItem> createFileItems(File[] files, FileItem parent, int depth) {
        List<FileItem> folderItems = new ArrayList<>();
        List<FileItem> fileItems = new ArrayList<>();

        for (File file : files) {
            if (file.isDirectory()) {
                folderItems.add(new FileItem(file.getName(), FileType.FOLDER, file, parent, depth));
            } else {
                fileItems.add(new FileItem(file.getName(), classifyFileType(file), file, parent, depth));
            }
        }

        folderItems.sort(Comparator.comparing(FileItem::getName, String.CASE_INSENSITIVE_ORDER));
        fileItems.sort(Comparator.comparing(FileItem::getName, String.CASE_INSENSITIVE_ORDER));

        List<FileItem> orderedItems = new ArrayList<>(folderItems.size() + fileItems.size());
        orderedItems.addAll(folderItems);
        orderedItems.addAll(fileItems);
        return orderedItems;
    }

    private FileType classifyFileType(File file) {
        String fileName = normalizeSpecialFileName(file.getName()).toLowerCase();
        if (ExternalImageLoader.isSupportedImageFile(file)) {
            return FileType.IMAGE;
        }
        if (VideoService.isRecognizedVideoFile(file)) {
            return FileType.VIDEO;
        }
        if (AudioManager.isRecognizedAudioFile(file)) {
            return FileType.AUDIO;
        }
        if (fileName.endsWith(".zip") || fileName.endsWith(".rar") || fileName.endsWith(".7z") || fileName.endsWith(".tar") ||
                fileName.endsWith(".gz") || fileName.endsWith(".bz2") || fileName.endsWith(".xz") || fileName.endsWith(".jar") ||
                fileName.endsWith(".war") || fileName.endsWith(".ear")) {
            return FileType.ZIP;
        }
        if (fileName.endsWith(".mcmeta") || fileName.endsWith(".mcfunction") || fileName.endsWith(".mcpack") ||
                fileName.endsWith(".mctemplate")) {
            return FileType.MCMETA;
        }
        return FileType.FILE;
    }

    private int renderFolderPreview(DrawContext context, File folder, int x, int y) {
        File[] children = folder.listFiles();
        if (children == null) {
            context.drawText(this.textRenderer, "Unable to read folder contents.", x, y, new Color(uiColorIDEFileDisplayWindowText, true).getRGB(), false);
            return y + 10;
        }

        List<FolderPreviewEntry> previewEntries = new ArrayList<>();
        buildFolderPreviewEntries(folder, 0, new ArrayList<>(), previewEntries);

        int previewStartIndex = Math.max(0, Math.min(scrollOffsetFolderPreview, Math.max(0, previewEntries.size() - 1)));
        int previewLineCapacity = Math.max(1, (this.height - y - 20) / FOLDER_PREVIEW_LINE_HEIGHT);
        configurePreviewScrollbar(y, Math.max(1, this.height - y - 20), Math.max(1, previewEntries.size()), previewLineCapacity, true);
        int previewCount = Math.min(previewLineCapacity, Math.max(0, previewEntries.size() - previewStartIndex));
        int guideColor = darkenColor(uiColorIDEFileDisplayWindowText, 0.72f);
        int textColor = new Color(uiColorIDEFileDisplayWindowText, true).getRGB();
        int previewStartX = x - 5;
        for (int i = 0; i < previewCount; i++) {
            FolderPreviewEntry entry = previewEntries.get(previewStartIndex + i);
            int textX = renderPreviewTreeGuides(context, previewStartX, y, guideColor, entry);
            Identifier icon = entry.type == FileType.FOLDER ? FOLDER_ICON : getFileIcon(entry.name);
            context.drawTexture(icon, textX, y - 4, 0, 0, 16, 16, 16, 16);
            int labelX = textX + 20;
            context.drawText(this.textRenderer, fitPreviewLineWithIndent(entry.name, labelX - previewStartX), labelX, y, textColor, false);
            folderPreviewClickTargets.add(new FolderPreviewClickTarget(entry.file, previewStartX, y - 4, Math.max(80, this.width - previewStartX - 24), FOLDER_PREVIEW_LINE_HEIGHT));
            y += FOLDER_PREVIEW_LINE_HEIGHT;
        }

        if (previewEntries.size() > previewStartIndex + previewCount) {
            context.drawText(this.textRenderer, "...", previewStartX, y, textColor, false);
            y += FOLDER_PREVIEW_LINE_HEIGHT;
        }
        renderPreviewScrollbar(context);

        return y;
    }

    private void buildFolderPreviewEntries(File folder, int depth, List<Boolean> ancestorContinuations, List<FolderPreviewEntry> entries) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }

        List<FileItem> childItems = createFileItems(children, null, 0);
        for (int i = 0; i < childItems.size(); i++) {
            FileItem child = childItems.get(i);
            boolean isLast = i == childItems.size() - 1;
            entries.add(new FolderPreviewEntry(child.getFile(), child.getName(), child.getType(), depth, isLast, new ArrayList<>(ancestorContinuations)));

            if (child.getType() == FileType.FOLDER && depth < 2) {
                List<Boolean> nextAncestors = new ArrayList<>(ancestorContinuations);
                nextAncestors.add(!isLast);
                buildFolderPreviewEntries(child.getFile(), depth + 1, nextAncestors, entries);
            }
        }
    }

    private int renderZipPreview(DrawContext context, File archive, int x, int y) {
        List<FolderPreviewEntry> previewEntries = buildZipPreviewEntries(archive);
        if (previewEntries.isEmpty()) {
            context.drawText(this.textRenderer, "No readable archive entries.", x, y, new Color(uiColorIDEFileDisplayWindowText, true).getRGB(), false);
            return y + 10;
        }

        int previewStartIndex = Math.max(0, Math.min(scrollOffsetFolderPreview, Math.max(0, previewEntries.size() - 1)));
        int previewLineCapacity = Math.max(1, (this.height - y - 20) / FOLDER_PREVIEW_LINE_HEIGHT);
        configurePreviewScrollbar(y, Math.max(1, this.height - y - 20), Math.max(1, previewEntries.size()), previewLineCapacity, true);
        int previewCount = Math.min(previewLineCapacity, Math.max(0, previewEntries.size() - previewStartIndex));
        int guideColor = darkenColor(uiColorIDEFileDisplayWindowText, 0.72f);
        int textColor = new Color(uiColorIDEFileDisplayWindowText, true).getRGB();
        int previewStartX = x - 5;
        context.drawText(this.textRenderer, "Archive Contents", previewStartX, y, new Color(uiColorIDEFileTypeText, true).getRGB(), false);
        y += FOLDER_PREVIEW_LINE_HEIGHT;
        for (int i = 0; i < previewCount; i++) {
            FolderPreviewEntry entry = previewEntries.get(previewStartIndex + i);
            int textX = renderPreviewTreeGuides(context, previewStartX, y, guideColor, entry);
            Identifier icon = entry.type == FileType.FOLDER ? FOLDER_ICON : getFileIcon(entry.name);
            context.drawTexture(icon, textX, y - 4, 0, 0, 16, 16, 16, 16);
            int labelX = textX + 20;
            context.drawText(this.textRenderer, fitPreviewLineWithIndent(entry.name, labelX - previewStartX), labelX, y, textColor, false);
            y += FOLDER_PREVIEW_LINE_HEIGHT;
        }

        if (previewEntries.size() > previewStartIndex + previewCount) {
            context.drawText(this.textRenderer, "...", previewStartX, y, textColor, false);
            y += FOLDER_PREVIEW_LINE_HEIGHT;
        }
        renderPreviewScrollbar(context);
        return y;
    }

    private List<FolderPreviewEntry> buildZipPreviewEntries(File archive) {
        ZipTreeNode root = new ZipTreeNode("", true);
        int count = 0;
        try (ZipFile zipFile = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements() && count < 1500) {
                ZipEntry entry = entries.nextElement();
                String normalizedName = normalizeZipEntryName(entry.getName());
                if (normalizedName.isBlank()) {
                    continue;
                }
                addZipEntryNode(root, normalizedName, entry.isDirectory());
                count++;
            }
        } catch (IOException ignored) {
            return List.of();
        }

        List<FolderPreviewEntry> previewEntries = new ArrayList<>();
        appendZipPreviewEntries(root, 0, new ArrayList<>(), previewEntries);
        return previewEntries;
    }

    private void addZipEntryNode(ZipTreeNode root, String entryName, boolean directory) {
        String[] parts = entryName.split("/");
        ZipTreeNode current = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            boolean last = i == parts.length - 1;
            boolean nodeDirectory = !last || directory;
            current = current.children.computeIfAbsent(part, name -> new ZipTreeNode(name, nodeDirectory));
        }
    }

    private void appendZipPreviewEntries(ZipTreeNode node, int depth, List<Boolean> ancestorContinuations, List<FolderPreviewEntry> entries) {
        List<ZipTreeNode> children = new ArrayList<>(node.children.values());
        children.sort(Comparator
                .comparing((ZipTreeNode child) -> !child.directory)
                .thenComparing(child -> child.name, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < children.size(); i++) {
            ZipTreeNode child = children.get(i);
            boolean isLast = i == children.size() - 1;
            FileType type = child.directory ? FileType.FOLDER : classifyArchiveEntryType(child.name);
            entries.add(new FolderPreviewEntry(null, child.name, type, depth, isLast, new ArrayList<>(ancestorContinuations)));
            if (child.directory && depth < 8) {
                List<Boolean> nextAncestors = new ArrayList<>(ancestorContinuations);
                nextAncestors.add(!isLast);
                appendZipPreviewEntries(child, depth + 1, nextAncestors, entries);
            }
        }
    }

    private String normalizeZipEntryName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private FileType classifyArchiveEntryType(String name) {
        String fileName = normalizeSpecialFileName(name).toLowerCase(Locale.ROOT);
        int dotIndex = fileName.lastIndexOf('.');
        String extension = dotIndex >= 0 && dotIndex < fileName.length() - 1 ? fileName.substring(dotIndex + 1) : "";
        if (Set.of("png", "apng", "jpg", "jpeg", "jpe", "jfif", "gif", "bmp", "dib", "tiff", "tif", "webp", "ico", "svg", "dds", "tga", "psd", "kra", "pbm", "pgm", "ppm", "pnm", "wbmp").contains(extension)) {
            return FileType.IMAGE;
        }
        if (Set.of("mp4", "m4v", "mov", "webm", "mkv", "avi", "wmv", "flv", "3gp", "mpeg", "mpg", "ogv", "ts", "mts", "m2ts").contains(extension)) {
            return FileType.VIDEO;
        }
        if (Set.of("wav", "ogg", "mp3", "flac", "aac", "m4a", "wma", "aiff", "aif", "opus").contains(extension)) {
            return FileType.AUDIO;
        }
        if (Set.of("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar", "war", "ear").contains(extension)) {
            return FileType.ZIP;
        }
        if (Set.of("mcmeta", "mcfunction", "mcpack", "mctemplate").contains(extension)) {
            return FileType.MCMETA;
        }
        return FileType.FILE;
    }

    private int renderPreviewTreeGuides(DrawContext context, int x, int y, int color, FolderPreviewEntry entry) {
        int indentWidth = 12;
        int rowTop = y - 4;
        int rowBottom = y + FOLDER_PREVIEW_LINE_HEIGHT - 4;
        int branchY = y + 4;

        for (int level = 0; level < entry.ancestorContinuations.size(); level++) {
            if (entry.ancestorContinuations.get(level)) {
                int guideX = x + (level * indentWidth) + 5;
                context.drawVerticalLine(guideX, rowTop, rowBottom, color);
            }
        }

        int branchBaseX = x + (entry.depth * indentWidth) + 5;
        context.drawVerticalLine(branchBaseX, rowTop, branchY, color);
        if (!entry.isLast) {
            context.drawVerticalLine(branchBaseX, branchY, rowBottom, color);
        }
        context.drawHorizontalLine(branchBaseX, branchBaseX + 12, branchY, color);

        return branchBaseX + 12;
    }

    private FolderPreviewClickTarget getFolderPreviewClickTarget(double mouseX, double mouseY) {
        for (FolderPreviewClickTarget target : folderPreviewClickTargets) {
            if (target.contains(mouseX, mouseY)) {
                return target;
            }
        }
        return null;
    }

    private void navigateToPreviewTarget(File target) {
        if (target == null) {
            return;
        }

        if (target.isDirectory()) {
            setDirectory(target);
            selectedFileItem = new FileItem(target.getName(), FileType.FOLDER, target);
            syncPathInputToTarget(target);
            fileContent = null;
            scrollOffsetFolderPreview = 0;
            scrollOffsetViewer = 0;
            horizontalScrollOffsetViewer = 0;
            return;
        }

        File parent = target.getParentFile();
        if (parent == null) {
            return;
        }

        setDirectory(parent);
        FileItem matchingItem = findFileItem(target);
        if (matchingItem != null) {
            openFile(matchingItem);
        } else {
            selectedFileItem = new FileItem(target.getName(), classifyFileType(target), target);
            fileContent = null;
        }
        syncPathInputToTarget(target);
    }

    private FileItem findFileItem(File target) {
        for (FileItem fileItem : fileItems) {
            if (fileItem.getFile().equals(target)) {
                return fileItem;
            }
        }
        return null;
    }

    private int countFolderPreviewLines(File folder, int depth) {
        File[] children = folder.listFiles();
        if (children == null) {
            return 0;
        }

        List<FileItem> childItems = createFileItems(children, null, 0);
        int count = childItems.size();
        if (depth >= 2) {
            return count;
        }

        for (FileItem child : childItems) {
            if (child.getType() == FileType.FOLDER) {
                count += countFolderPreviewLines(child.getFile(), depth + 1);
            }
        }
        return count;
    }

    private int getVisibleTextPreviewLines(int startY) {
        int availablePreviewLines = Math.max(1, (this.height - startY - 20) / TEXT_PREVIEW_LINE_HEIGHT);
        return Math.max(1, availablePreviewLines - 1);
    }

    private File getActivePreviewFolder() {
        if (selectedFileItem != null && selectedFileItem.getType() == FileType.FOLDER) {
            return selectedFileItem.getFile();
        }
        if (selectedFileItem == null && currentDirectory != null && currentDirectory.isDirectory()) {
            return currentDirectory;
        }
        return null;
    }

    private String getCurrentDirectoryDisplayName() {
        if (lastVisitedPath == null || lastVisitedPath.isBlank() || "/".equals(lastVisitedPath)) {
            return "/";
        }
        return currentDirectory.getName().isEmpty() ? lastVisitedPath : currentDirectory.getName();
    }

    private int getTextPreviewStartY() {
        return 120;
    }

    private int getMaxHorizontalPreviewScroll(String[] lines) {
        int longestLineLength = 0;
        for (String line : lines) {
            longestLineLength = Math.max(longestLineLength, line.length());
        }

        int approxVisibleCharacters = Math.max(1, (this.width - 260) / 6);
        return Math.max(0, longestLineLength - approxVisibleCharacters);
    }

    private void renderPreviewLineWithSyntaxHighlighting(DrawContext context, String line, int x, int y, File file) {
        line = fitPreviewLine(line);
        int currentX = x;
        if (file != null) {
            for (EditorSyntaxHighlighter.StyledSpan span : EditorSyntaxHighlighter.highlight(file.getName(), line)) {
                if (!span.text().isEmpty()) {
                    context.drawText(this.textRenderer, span.text(), currentX, y, span.color(), false);
                    currentX += this.textRenderer.getWidth(span.text());
                }
            }
            return;
        }

        context.drawText(this.textRenderer, line, currentX, y, new Color(uiColorIDEFileDisplayWindowText, true).getRGB(), false);
    }

    private void renderTreeGuides(DrawContext context, FileItem fileItem, int startX, int startY, int color) {
        if (fileItem.getParent() == null) {
            return;
        }

        color = darkenColor(color, 0.72f);

        int branchY = startY + 4;

        for (int depth = 1; depth <= fileItem.getDepth(); depth++) {
            FileItem branchItem = getAncestorAtDepth(fileItem, depth);
            if (branchItem == null) {
                continue;
            }

            int connectorX = startX + (depth * 14) - 5;
            boolean hasContinuation = hasFollowingSiblingAtDepth(branchItem, depth);

            if (depth < fileItem.getDepth()) {
                if (hasContinuation) {
                    context.drawVerticalLine(connectorX, startY - 8, startY + 12, color);
                }
                continue;
            }

            context.drawVerticalLine(connectorX, startY - 8, branchY, color);
            if (hasContinuation) {
                context.drawVerticalLine(connectorX, branchY, startY + 12, color);
            }
            context.drawHorizontalLine(connectorX, connectorX + 6, branchY, color);
        }
    }

    private FileItem getAncestorAtDepth(FileItem fileItem, int depth) {
        FileItem current = fileItem;
        while (current != null && current.getDepth() > depth) {
            current = current.getParent();
        }
        return current != null && current.getDepth() == depth ? current : null;
    }

    private boolean hasFollowingSiblingAtDepth(FileItem branchItem, int depth) {
        int index = fileItems.indexOf(branchItem);
        if (index < 0) {
            return false;
        }

        for (int i = index + 1; i < fileItems.size(); i++) {
            FileItem next = fileItems.get(i);
            if (next.getDepth() < depth) {
                return false;
            }
            if (next.getDepth() == depth) {
                return true;
            }
        }
        return false;
    }

    private String fitLabelToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = textRenderer.getWidth(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String next = builder + String.valueOf(text.charAt(i));
            if (textRenderer.getWidth(next) + ellipsisWidth > maxWidth) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder + ellipsis;
    }

    private String fitPreviewLine(String text) {
        return fitLabelToWidth(text, this.width - 260);
    }

    private String fitPreviewLineWithIndent(String text, int indent) {
        return fitLabelToWidth(text, this.width - 260 - indent);
    }

    private int darkenColor(int argb, float factor) {
        Color color = new Color(argb, true);
        int red = Math.max(0, Math.min(255, Math.round(color.getRed() * factor)));
        int green = Math.max(0, Math.min(255, Math.round(color.getGreen() * factor)));
        int blue = Math.max(0, Math.min(255, Math.round(color.getBlue() * factor)));
        return new Color(red, green, blue, color.getAlpha()).getRGB();
    }

    private int withAlpha(int argb, int alpha) {
        Color color = new Color(argb, true);
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }

    private void renderTopBarButton(DrawContext context, int x, String label) {
        int width = getTopBarButtonWidth(label);
        context.fill(x, TopBarLayout.BUTTON_Y, x + width, TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT, withAlpha(uiColorContentBase, 176));
        context.drawBorder(x, TopBarLayout.BUTTON_Y, width, TopBarLayout.BUTTON_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        int textX = x + Math.max(4, (width - this.textRenderer.getWidth(label)) / 2);
        context.drawText(this.textRenderer, label, textX, TopBarLayout.BUTTON_Y + 7, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private void renderInlineActionButton(DrawContext context, int x, int y, int width, int height, String label) {
        int background = withAlpha(uiColorContentBase, 162);
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        int text = new Color(uiColorIDEFileEditButtonText, true).getRGB();
        context.fill(x, y, x + width, y + height, background);
        context.drawBorder(x, y, width, height, border);
        int textX = x + Math.max(4, (width - this.textRenderer.getWidth(label)) / 2);
        context.drawText(this.textRenderer, label, textX, y + 4, text, false);
    }

    private void drawDeleteConfirmPopup(DrawContext context) {
        int popupWidth = 360;
        int popupHeight = 164;
        int popupX = (this.width - popupWidth) / 2;
        int popupY = (this.height - popupHeight) / 2;
        context.fill(popupX, popupY, popupX + popupWidth, popupY + popupHeight, withAlpha(uiColorContentBase, 234));
        context.drawBorder(popupX, popupY, popupWidth, popupHeight, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(popupX, popupY, popupX + popupWidth, popupY + 26, withAlpha(uiColorHeader, 178));
        context.drawText(this.textRenderer, "Delete File", popupX + 10, popupY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        String name = deleteTarget == null ? "this item" : deleteTarget.getName();
        context.drawText(this.textRenderer, fitLabelToWidth("Are you sure you want to delete " + name + "?", popupWidth - 20), popupX + 10, popupY + 52, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        renderPopupButton(context, popupX + 12, popupY + popupHeight - 34, 104, "Cancel", false);
        renderPopupButton(context, popupX + popupWidth - 116, popupY + popupHeight - 34, 104, "Delete", true);
    }

    private void renderPopupButton(DrawContext context, int x, int y, int width, String label, boolean destructive) {
        int fill = destructive ? withAlpha(uiColorWarningPromptText, 142) : withAlpha(uiColorHeader, 122);
        context.fill(x, y, x + width, y + 20, fill);
        context.drawBorder(x, y, width, 20, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, label, x + Math.max(6, (width - this.textRenderer.getWidth(label)) / 2), y + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private boolean isTopBarButtonClicked(double mouseX, double mouseY, int x, int width) {
        return mouseX >= x && mouseX <= x + width && mouseY >= TopBarLayout.BUTTON_Y && mouseY <= TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT;
    }

    private List<String> getTopBarActionLabels() {
        return List.of(TOP_BAR_OPEN_LABEL, TOP_BAR_HOME_LABEL, TOP_BAR_RELOAD_LABEL);
    }

    private TopBarLayout getTopBarLayout() {
        return new TopBarLayout(this.textRenderer, this.width);
    }

    private int getTopBarOpenButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 0);
    }

    private int getTopBarHomeButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 1);
    }

    private int getTopBarReloadButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 2);
    }

    private int getTopBarButtonWidth(String label) {
        return getTopBarLayout().buttonWidth(label);
    }

    private boolean isWithinBounds(int[] bounds, double mouseX, double mouseY) {
        return bounds != null
                && mouseX >= bounds[0]
                && mouseX <= bounds[0] + bounds[2]
                && mouseY >= bounds[1]
                && mouseY <= bounds[1] + bounds[3];
    }

    private List<MenuEntry> buildTopBarOpenMenuItems() {
        return buildOpenMenuItemsForTarget(getActiveOpenTarget());
    }

    private List<MenuEntry> buildOpenMenuItemsForTarget(File target) {
        List<MenuEntry> items = new ArrayList<>();
        if (target == null) {
            return items;
        }
        if (target.isDirectory()) {
            items.add(new MenuEntry("open_folder", "Folder"));
            items.add(new MenuEntry("open_system", "with Default Application"));
            return items;
        }
        if (isEditableTextFile(target)) {
            items.add(new MenuEntry("open_editor", "in Editor"));
        }
        if (isConfigEditorCandidate(target)) {
            items.add(new MenuEntry("open_config", "in Config"));
        }

        items.add(new MenuEntry("open_system", "with Default Application"));
        return items;
    }

    private void handleTopBarOpenAction(String actionId) {
        handleOpenAction(getActiveOpenTarget(), actionId);
    }

    private void handleOpenAction(File target, String actionId) {
        if (target == null) {
            return;
        }
        switch (actionId) {
            case "open_folder" -> {
                if (target.isDirectory()) {
                    setDirectory(target);
                }
            }
            case "open_config" -> {
                if (target.isFile() && isConfigEditorCandidate(target) && this.client != null) {
                    this.client.setScreen(new ModConfigScreen(this, null, new File[]{target}));
                }
            }
            case "open_editor" -> {
                if (target.isFile() && isEditableTextFile(target) && this.client != null) {
                    this.client.setScreen(new FileEditorScreen(this, target, new FileItem(target.getName(), classifyFileType(target), target)));
                }
            }

            case "open_system" -> Util.getOperatingSystem().open(target);
            case "open_file_path" -> copyPathToClipboard(target);
        }
        UiSoundHelper.playButtonClick();
    }

    private void copyPathToClipboard(File target) {
        if (target == null || this.client == null) {
            return;
        }
        this.client.keyboard.setClipboard(target.getAbsolutePath());
    }

    private boolean isConfigEditorCandidate(File target) {
        if (target == null || !target.isFile()) {
            return false;
        }
        String name = target.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= name.length() - 1) {
            return false;
        }
        String extension = name.substring(dotIndex + 1).toLowerCase();
        return CONFIG_EDITOR_EXTENSIONS.contains(extension);
    }

    private File getActiveOpenTarget() {
        if (selectedFileItem != null) {
            return selectedFileItem.getFile();
        }
        if (pathInput != null && !pathInput.getText().isBlank()) {
            File target = new File("." + pathInput.getText());
            if (target.exists()) {
                return target;
            }
        }
        return currentDirectory;
    }

    private boolean isEditableTextFile(File file) {
        return file != null && file.isFile() && isEditableTextType(classifyFileType(file)) && isTextFile(file);
    }

    private void openPathInputTarget() {
        File target = new File("." + pathInput.getText());
        if (target.isDirectory()) {
            setDirectory(target);
            syncPathInputToTarget(target);
            return;
        }
        if (target.isFile()) {
            File parent = target.getParentFile();
            if (parent != null) {
                setDirectory(parent);
                FileItem matchingItem = findFileItem(target);
                if (matchingItem != null) {
                    openFile(matchingItem);
                }
                syncPathInputToTarget(target);
            }
        }
    }

    private void reloadCurrentPath() {
        File currentTarget = new File("." + pathInput.getText());
        if (currentTarget.isDirectory()) {
            setDirectory(currentTarget);
            return;
        }
        if (currentTarget.isFile()) {
            File parent = currentTarget.getParentFile();
            if (parent != null) {
                setDirectory(parent);
                FileItem matchingItem = findFileItem(currentTarget);
                if (matchingItem != null) {
                    openFile(matchingItem);
                }
                syncPathInputToTarget(currentTarget);
            }
        }
    }

    private int getJsonColorForPart(String part) {
        if (part.matches("\".*\"")) {
            return JSON_COLOR_STRING;
        } else if (part.matches("-?\\d+(?:\\.\\d+)?")) {
            return JSON_COLOR_NUMBER;
        } else if (part.equals("{") || part.equals("}")) {
            return JSON_COLOR_OBJECT;
        } else if (part.equals("[") || part.equals("]")) {
            return JSON_COLOR_ARRAY;
        } else if (part.equals("true") || part.equals("false")) {
            return JSON_COLOR_BOOLEAN;
        } else if (part.equals("null")) {
            return JSON_COLOR_NULL;
        }
        return COLOR_DEFAULT;
    }

    private int getJavaColorForPart(String part) {
        if (part.matches("\".*\"")) {
            return JAVA_COLOR_STRING;
        } else if (part.matches("'[^']*'")) {
            return JAVA_COLOR_CHAR;
        } else if (part.matches("-?\\d+(?:\\.\\d+)?")) {
            return JAVA_COLOR_NUMBER;
        } else if (part.equals("{") || part.equals("}")) {
            return JAVA_COLOR_BRACE;
        } else if (part.equals("[") || part.equals("]")) {
            return JAVA_COLOR_BRACKET;
        } else if (part.equals("true") || part.equals("false")) {
            return JAVA_COLOR_BOOLEAN;
        } else if (part.matches("//.*|/\\*(.|\\R)*?\\*/")) {
            return JAVA_COLOR_COMMENT;
        } else if (part.matches("@\\w+")) {
            return JAVA_COLOR_ANNOTATION;
        } else if (part.equals("null")) {
            return JAVA_COLOR_NULL;
        }
        return COLOR_DEFAULT;
    }

    private boolean isTextFile(File file) {
        String fileName = normalizeSpecialFileName(file.getName()).toLowerCase();
        return fileName.endsWith(".txt") || fileName.endsWith(".json") || fileName.endsWith(".json5") || fileName.endsWith(".log") || fileName.endsWith(".kwds") ||
                fileName.endsWith(".properties") || fileName.endsWith(".db") || fileName.endsWith(".dat") || fileName.endsWith(".dat_old") || fileName.endsWith(".class") ||
                fileName.endsWith(".mcmeta") || fileName.endsWith(".toml") || fileName.endsWith(".mcfunction") || fileName.endsWith(".mcpack") || fileName.endsWith(".mctemplate") ||
                fileName.endsWith(".xml") || fileName.endsWith(".yml") || fileName.endsWith(".yaml") || fileName.endsWith(".ini") ||
                fileName.endsWith(".config") || fileName.endsWith(".conf") || fileName.endsWith(".css") || fileName.endsWith(".js") ||
                fileName.endsWith(".html") || fileName.endsWith(".c") || fileName.endsWith(".cpp") || fileName.endsWith(".h") ||
                fileName.endsWith(".hpp") || fileName.endsWith(".java") || fileName.endsWith(".py") || fileName.endsWith(".rb") ||
                fileName.endsWith(".php") || fileName.endsWith(".sql") || fileName.endsWith(".sh") || fileName.endsWith(".bat") ||
                fileName.endsWith(".ps1") || fileName.endsWith(".md") || fileName.endsWith(".rtf") || fileName.endsWith(".doc") ||
                fileName.endsWith(".docx") || fileName.endsWith(".odt") || fileName.endsWith(".pdf") || fileName.endsWith(".tex") ||
                fileName.endsWith(".placebo") || fileName.endsWith(".glsl") || fileName.endsWith(".vert") || fileName.endsWith(".frag") ||
                fileName.endsWith(".geom") || fileName.endsWith(".comp") || fileName.endsWith(".vsh") || fileName.endsWith(".fsh") ||
                fileName.endsWith(".ktl");
    }

    private boolean isDisabledFile(File file) {
        return file != null && file.getName().toLowerCase().endsWith(".disabled");
    }

    private String normalizeSpecialFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        if (fileName.toLowerCase().endsWith(".disabled")) {
            return fileName.substring(0, fileName.length() - ".disabled".length());
        }
        return fileName;
    }

    private boolean isEditableTextType(FileType fileType) {
        return fileType == FileType.FILE || fileType == FileType.MCMETA;
    }

    private boolean canPreviewOpenTarget(File file) {
        return file != null && file.exists();
    }

    private boolean canManagePreviewTarget(File file) {
        return selectedFileItem != null
                && file != null
                && selectedFileItem.getFile().equals(file)
                && file.exists()
                && file.getParentFile() != null;
    }

    private KoilLocalModJarInsight getPreviewModMetadata(File file) {
        if (!isModJarCandidate(file)) {
            cachedPreviewModMetadata = null;
            cachedPreviewMetadataPath = null;
            cachedPreviewMetadataLastModified = Long.MIN_VALUE;
            return null;
        }
        String absolutePath = file.getAbsolutePath();
        long lastModified = file.lastModified();
        if (!absolutePath.equals(cachedPreviewMetadataPath) || cachedPreviewMetadataLastModified != lastModified) {
            cachedPreviewModMetadata = KoilLocalModJarInspector.inspect(file);
            cachedPreviewMetadataPath = absolutePath;
            cachedPreviewMetadataLastModified = lastModified;
        }
        return cachedPreviewModMetadata != null && !cachedPreviewModMetadata.metadataFile().isBlank() ? cachedPreviewModMetadata : null;
    }

    private int renderModJarMetadata(DrawContext context, KoilLocalModJarInsight metadata, File file, int x, int y, int panelWidth) {
        int contentY = y;
        renderModPreviewSectionRule(context, x - 2, x + panelWidth - 10, contentY, "Local Metadata");
        contentY += 14;
        contentY = renderModPreviewInfoLine(context, x, contentY, panelWidth, "Metadata File", blankPreviewValue(metadata.metadataFile(), "unknown"), 0xFFD8DFE9);
        contentY = renderModPreviewInfoLine(context, x, contentY, panelWidth, "Entrypoints", String.valueOf(metadata.entrypointCount()), 0xFFDCE4EE);
        contentY = renderModPreviewInfoLine(context, x, contentY, panelWidth, "Mixins", String.valueOf(metadata.mixinCount()), 0xFFD6E5DA);
        contentY = renderModPreviewInfoLine(context, x, contentY, panelWidth, "Access Wideners", String.valueOf(metadata.accessWidenerCount()), 0xFFD9D4E5);
        contentY = renderModPreviewInfoLine(context, x, contentY, panelWidth, "Loader Hints", blankPreviewValue(metadata.loaderHints(), "none"), 0xFFC9DDD9);
        contentY += 6;

        renderModPreviewSectionRule(context, x - 2, x + panelWidth - 10, contentY, "Local File");
        contentY += 14;
        contentY = renderModPreviewInfoLine(context, x, contentY, panelWidth, "File", file.getName(), new Color(uiColorContentBaseTitleText, true).getRGB());
        contentY = renderModPreviewInfoLine(context, x, contentY, panelWidth, "Path", file.getPath().replace("\\", "/"), 0xFFD8DFE9);
        return contentY + 4;
    }

    private void renderModPreviewSectionRule(DrawContext context, int left, int right, int y, String title) {
        context.fill(left, y, right, y + 1, 0x50798596);
        context.drawText(this.textRenderer, title, left + 2, y + 3, 0xFFBFCAD8, false);
    }

    private int renderModPreviewInfoLine(DrawContext context, int x, int y, int panelWidth, String label, String value, int valueColor) {
        int labelWidth = 88;
        int valueX = x + labelWidth;
        int valueWidth = Math.max(40, panelWidth - (valueX - x) - 18);
        int lineLeft = x - 2;
        int lineRight = x + panelWidth - 12;
        context.fill(lineLeft, y - 2, lineRight, y - 1, 0x20374455);
        context.drawText(this.textRenderer, label, x, y, uiColorBasicSubtitleText, false);
        context.fill(valueX - 7, y - 1, valueX - 6, y + this.textRenderer.fontHeight + 1, 0x38465567);
        List<MarkdownPreviewRenderer.Line> lines = MarkdownPreviewRenderer.wrap(value == null || value.isBlank() ? "none" : value, this.textRenderer, valueWidth);
        int currentY = y;
        for (MarkdownPreviewRenderer.Line line : lines) {
            int lineHeight = MarkdownPreviewRenderer.renderLine(
                    context,
                    this.textRenderer,
                    new MarkdownPreviewRenderer.Line(line.rawText(), 0, valueColor, 0, MarkdownPreviewRenderer.Accent.NONE),
                    valueX,
                    currentY
            );
            currentY += lineHeight;
        }
        int rowBottom = Math.max(y + this.textRenderer.fontHeight + 4, currentY + 1);
        context.fill(lineLeft, rowBottom, lineRight, rowBottom + 1, 0x142C3643);
        return rowBottom + 4;
    }

    private String blankPreviewValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isModJarCandidate(File file) {
        return file != null && normalizeSpecialFileName(file.getName()).toLowerCase().endsWith(".jar");
    }

    private String readFileContent(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    private boolean shouldReloadPreviewText(File file) {
        if (file == null) {
            return false;
        }
        String absolutePath = file.getAbsolutePath();
        long lastModified = file.lastModified();
        return fileContent == null
                || !absolutePath.equals(cachedPreviewTextPath)
                || cachedPreviewTextLastModified != lastModified;
    }
}
