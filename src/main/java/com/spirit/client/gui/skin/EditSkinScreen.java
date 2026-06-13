package com.spirit.client.gui.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.client.gui.ColorPickerPopup;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.entity.PlayerModelPart;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.function.IntConsumer;

import static com.spirit.Main.SUBLOGGER;

public class EditSkinScreen extends Screen {
    private final Screen parent;
    private final SkinLibrary library;
    private final String entryId;
    private final Identifier sourcePreview;
    private NativeImage image;
    private NativeImageBackedTexture texture;
    private Identifier textureId;
    private SkinEntry entry;
    private Tool tool = Tool.PENCIL;
    private boolean slim;
    private int selectedColor = 0xFFFFFFFF;
    private int brushSize = 1;
    private int canvasX;
    private int canvasY;
    private int canvasSize;
    private int toolsLeft;
    private int toolsTop;
    private int toolsRight;
    private int toolsBottom;
    private int previewLeft;
    private int previewTop;
    private int previewRight;
    private int previewBottom;
    private int paletteLeft;
    private int paletteTop;
    private int paletteRight;
    private int paletteBottom;
    private float modelYaw = -28.0F;
    private float modelPitch;
    private float modelRoll;
    private int previewScrollOffset;
    private boolean strokeActive;
    private boolean showGrid = true;
    private boolean showUvGuide = true;
    private boolean showBaseLayer = true;
    private boolean showOverlayLayer = true;
    private boolean showBaseHead = true;
    private boolean showBaseBody = true;
    private boolean showBaseRightArm = true;
    private boolean showBaseLeftArm = true;
    private boolean showBaseRightLeg = true;
    private boolean showBaseLeftLeg = true;
    private boolean showOverlayHead = true;
    private boolean showOverlayBody = true;
    private boolean showOverlayRightArm = true;
    private boolean showOverlayLeftArm = true;
    private boolean showOverlayRightLeg = true;
    private boolean showOverlayLeftLeg = true;
    private boolean shapeActive;
    private int shapeStartX;
    private int shapeStartY;
    private int shapeEndX;
    private int shapeEndY;
    private int hoverPixelX = -1;
    private int hoverPixelY = -1;
    private int lastDrawPixelX = -1;
    private int lastDrawPixelY = -1;
    private int[] palette = defaultPalette();
    private long lastPaletteClick;
    private int lastPaletteIndex = -1;
    private boolean colorPickerOpen;
    private int colorPickerIndex = -1;
    private ColorPickerPopup sharedColorPickerPopup;
    private boolean draggingPreviewScrollbar;
    private int previewScrollbarDragOffset;
    private int toolsScrollOffset;
    private boolean draggingToolsScrollbar;
    private int toolsScrollbarDragOffset;
    private final Deque<int[]> undo = new ArrayDeque<>();
    private final Deque<int[]> redo = new ArrayDeque<>();
    private String status = "";

    public EditSkinScreen(Screen parent) {
        this(parent, null, null, false);
    }

    public EditSkinScreen(Screen parent, String entryId, Identifier sourcePreview, boolean slim) {
        super(Text.literal("Edit Skin"));
        this.parent = parent;
        this.library = SkinLibrary.get();
        this.entryId = entryId;
        this.sourcePreview = sourcePreview;
        this.slim = slim;
    }

    @Override
    protected void init() {
        this.library.load();
        this.palette = this.library.editorPalette(defaultPalette());
        this.entry = this.library.get(this.entryId);
        loadImage();
        calculateLayout();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        KoilVanillaScreenChrome.renderOptionsShell(context, this.client, this.width, this.height);
        KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Options"), Text.literal("Skin Texture Editor"));
        calculateLayout();
        this.hoverPixelX = canvasPixelX(mouseX);
        this.hoverPixelY = canvasPixelY(mouseY);
        int headingY = this.toolsTop + 12;
        context.drawTextWithShadow(this.textRenderer, Text.literal("Tools"), this.toolsLeft + 8, headingY, 0xFFE6EAF0);
        drawSidebarActionButtons(context, mouseX, mouseY);
        drawToolsPanel(context, mouseX, mouseY);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Canvas 64x64"), this.canvasX, headingY, 0xFFE6EAF0);
        drawCanvas(context);
        drawPaletteBar(context, mouseX, mouseY);
        context.drawTextWithShadow(this.textRenderer, Text.literal("3D Preview"), this.previewLeft + 10, headingY, 0xFFE6EAF0);
        drawPreviewScrollContent(context, mouseX, mouseY);
        drawFooterButtons(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
        if (this.colorPickerOpen) {
            renderSharedColorPicker(context);
        }
    }

    private void calculateLayout() {
        int left = 38;
        int right = this.width - 34;
        int top = 34;
        int bottom = this.height - 40;
        int toolWidth = 132;
        int previewWidth = Math.max(338, Math.min(438, this.width / 3 + 78));
        this.toolsLeft = left;
        this.toolsTop = top;
        this.toolsRight = left + toolWidth;
        this.toolsBottom = bottom;
        this.previewRight = right - 8;
        this.previewLeft = right - previewWidth;
        this.previewTop = top;
        this.previewBottom = bottom;
        int canvasLeft = this.toolsRight + 18;
        int canvasRight = this.previewLeft - 18;
        int canvasTop = top + 22;
        int paletteSpace = 58;
        int canvasBottom = bottom - paletteSpace;
        this.canvasSize = Math.max(176, Math.min(canvasRight - canvasLeft - 12, canvasBottom - canvasTop));
        this.canvasX = canvasLeft + Math.max(0, (canvasRight - canvasLeft - this.canvasSize) / 2);
        this.canvasY = canvasTop;
        this.paletteLeft = this.canvasX;
        this.paletteTop = this.canvasY + this.canvasSize + 8;
        this.paletteRight = this.canvasX + this.canvasSize;
        this.paletteBottom = this.paletteTop + 48;
    }

    private void loadImage() {
        try {
            if (this.image != null) {
                this.image.close();
            }
            if (this.entry != null) {
                this.image = this.library.readEntryImage(this.entry);
                this.slim = this.entry.isSlim();
            } else {
                this.image = readCurrentSessionSkin();
                setStatus("Loaded current Minecraft skin for editing.");
            }
            this.texture = new NativeImageBackedTexture(this.image);
            this.textureId = new Identifier("koil", "skin/editor/preview_" + System.nanoTime());
            MinecraftClient.getInstance().getTextureManager().registerTexture(this.textureId, this.texture);
            pushUndo(false);
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Editor thread", "Failed to load editor skin: " + e.getMessage());
            this.image = blankSkin();
            this.texture = new NativeImageBackedTexture(this.image);
            this.textureId = new Identifier("koil", "skin/editor/fallback_" + System.nanoTime());
            MinecraftClient.getInstance().getTextureManager().registerTexture(this.textureId, this.texture);
            setStatus("Started a new blank skin because the selected texture could not be loaded.");
        }
    }

    private NativeImage readCurrentSessionSkin() throws Exception {
        SkinEntry activeEntry = this.library.activeEntry();
        if (activeEntry != null) {
            this.slim = activeEntry.isSlim();
            return this.library.readEntryImage(activeEntry);
        }
        MinecraftClient client = MinecraftClient.getInstance();
        GameProfile profile = client.getSession().getProfile();
        if (profile == null) {
            throw new IllegalStateException("No Minecraft profile skin is available.");
        }
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = client.getSkinProvider().getTextures(profile);
        MinecraftProfileTexture skinTexture = textures == null ? null : textures.get(MinecraftProfileTexture.Type.SKIN);
        String url = skinTexture == null ? "" : skinTexture.getUrl();
        if (skinTexture != null) {
            this.slim = skinTexture.getMetadata("model") != null && "slim".equalsIgnoreCase(skinTexture.getMetadata("model"));
        }
        if (url == null || url.isBlank()) {
            url = profileSkinUrlFromProperties(profile);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("No Minecraft profile skin URL is available.");
        }
        try (InputStream input = URI.create(url).toURL().openStream()) {
            return SkinTextureTools.normalize(NativeImage.read(input));
        }
    }

    private String profileSkinUrlFromProperties(GameProfile profile) {
        try {
            for (Property property : profile.getProperties().get("textures")) {
                String decoded = new String(Base64.getDecoder().decode(property.getValue()));
                JsonObject root = JsonParser.parseString(decoded).getAsJsonObject();
                JsonObject textures = root.has("textures") && root.get("textures").isJsonObject() ? root.getAsJsonObject("textures") : null;
                JsonObject skin = textures != null && textures.has("SKIN") && textures.get("SKIN").isJsonObject() ? textures.getAsJsonObject("SKIN") : null;
                if (skin == null || !skin.has("url")) {
                    continue;
                }
                if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                    JsonObject metadata = skin.getAsJsonObject("metadata");
                    this.slim = metadata.has("model") && "slim".equalsIgnoreCase(metadata.get("model").getAsString());
                }
                return skin.get("url").getAsString();
            }
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Editor thread", "Failed to read profile skin property: " + e.getMessage());
        }
        return "";
    }

    private NativeImage blankSkin() {
        NativeImage blank = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                blank.setColor(x, y, SkinTextureTools.argbToNative(0xFFFFFFFF));
            }
        }
        return blank;
    }

    private void drawSidebarActionButtons(DrawContext context, int mouseX, int mouseY) {
        int y = actionButtonY();
        drawPreviewButton(context, mouseX, mouseY, this.toolsLeft + 8, y, this.toolsLeft + 43, y + 18, "Undo", 0xFFE6EAF0);
        drawPreviewButton(context, mouseX, mouseY, this.toolsLeft + 47, y, this.toolsLeft + 82, y + 18, "Redo", 0xFFE6EAF0);
        drawPreviewButton(context, mouseX, mouseY, this.toolsLeft + 86, y, this.toolsLeft + 124, y + 18, "Open", 0xFFE6EAF0);
    }

    private void drawFooterButtons(DrawContext context, int mouseX, int mouseY) {
        int y = this.height - 28;
        drawMinecraftButton(context, mouseX, mouseY, 38, y, 124, y + 20, "Back");
    }


    private boolean handleActionButtonClick(double mouseX, double mouseY) {
        int y = actionButtonY();
        if (isInside(mouseX, mouseY, this.toolsLeft + 8, y, this.toolsLeft + 43, y + 18)) {
            playClick();
            undo();
            return true;
        }
        if (isInside(mouseX, mouseY, this.toolsLeft + 47, y, this.toolsLeft + 82, y + 18)) {
            playClick();
            redo();
            return true;
        }
        if (isInside(mouseX, mouseY, this.toolsLeft + 86, y, this.toolsLeft + 124, y + 18)) {
            playClick();
            openSkinTexture();
            return true;
        }
        if (isInside(mouseX, mouseY, 38, this.height - 28, 124, this.height - 8)) {
            playClick();
            this.client.setScreen(this.parent);
            return true;
        }
        return false;
    }

    private void openSkinTexture() {
        try {
            String chosenPath;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png"));
                filters.flip();
                chosenPath = TinyFileDialogs.tinyfd_openFileDialog("Open Skin Texture", "", filters, "Minecraft skin PNG", false);
            }
            if (chosenPath == null || chosenPath.isBlank()) {
                setStatus("No skin texture selected.");
                return;
            }
            NativeImage loaded = SkinTextureTools.readSkin(Path.of(chosenPath));
            replaceEditorImage(loaded, "Opened " + Path.of(chosenPath).getFileName() + ".");
        } catch (Exception e) {
            setStatus("Open failed: " + e.getMessage());
            SUBLOGGER.logE("Skin-Editor thread", "Open skin texture failed: " + e.getMessage());
        }
    }

    private void replaceEditorImage(NativeImage loaded, String message) {
        if (loaded == null) {
            return;
        }
        if (this.image != null) {
            this.image.close();
        }
        this.image = loaded;
        this.texture = new NativeImageBackedTexture(this.image);
        this.textureId = new Identifier("koil", "skin/editor/opened_" + System.nanoTime());
        MinecraftClient.getInstance().getTextureManager().registerTexture(this.textureId, this.texture);
        this.undo.clear();
        this.redo.clear();
        pushUndo(false);
        setStatus(message);
    }

    private int toolsViewportTop() {
        return actionButtonY() + 24;
    }

    private int toolsViewportBottom() {
        return this.toolsBottom - 6;
    }

    private int toolsContentHeight() {
        return layerPartGridTopY() + 6 * 17 + 12 - toolsViewportTop();
    }

    private int maxToolsScrollOffset() {
        return Math.max(0, toolsContentHeight() - Math.max(1, toolsViewportBottom() - toolsViewportTop()));
    }

    private void drawToolsPanel(DrawContext context, int mouseX, int mouseY) {
        this.toolsScrollOffset = Math.max(0, Math.min(maxToolsScrollOffset(), this.toolsScrollOffset));
        int left = this.toolsLeft + 4;
        int right = this.toolsRight - 4;
        int top = toolsViewportTop();
        int bottom = toolsViewportBottom();
        renderScissored(context, left, top, right, bottom, () -> {
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, -this.toolsScrollOffset, 0.0F);
            drawTools(context, mouseX, mouseY + this.toolsScrollOffset);
            context.getMatrices().pop();
        });
        drawToolsScrollbar(context);
    }

    private void drawToolsScrollbar(DrawContext context) {
        int max = maxToolsScrollOffset();
        if (max <= 0) {
            return;
        }
        int trackLeft = toolsScrollbarTrackLeft();
        int trackTop = toolsScrollbarTrackTop();
        int trackBottom = toolsScrollbarTrackBottom();
        int thumbTop = toolsScrollbarThumbTop();
        int thumbHeight = toolsScrollbarThumbHeight();
        int lineX = trackLeft + 1;
        context.fill(lineX, trackTop, lineX + 1, trackBottom, 0x664D5563);
        context.fill(trackLeft, thumbTop, trackLeft + 3, thumbTop + thumbHeight, 0xFF727C8D);
    }

    private int toolsScrollbarTrackLeft() {
        return this.toolsRight - 8;
    }

    private int toolsScrollbarTrackTop() {
        return toolsViewportTop();
    }

    private int toolsScrollbarTrackBottom() {
        return toolsViewportBottom();
    }

    private int toolsScrollbarThumbHeight() {
        int trackHeight = Math.max(1, toolsScrollbarTrackBottom() - toolsScrollbarTrackTop());
        return Math.max(24, trackHeight * trackHeight / Math.max(trackHeight, toolsContentHeight()));
    }

    private int toolsScrollbarThumbTop() {
        int max = maxToolsScrollOffset();
        int trackTop = toolsScrollbarTrackTop();
        int movable = Math.max(0, toolsScrollbarTrackBottom() - trackTop - toolsScrollbarThumbHeight());
        if (max <= 0) {
            return trackTop;
        }
        return trackTop + this.toolsScrollOffset * movable / max;
    }

    private void updateToolsScrollFromThumb(double mouseY) {
        int max = maxToolsScrollOffset();
        int movable = Math.max(1, toolsScrollbarTrackBottom() - toolsScrollbarTrackTop() - toolsScrollbarThumbHeight());
        int top = (int) mouseY - this.toolsScrollbarDragOffset;
        int clamped = Math.max(toolsScrollbarTrackTop(), Math.min(toolsScrollbarTrackTop() + movable, top));
        this.toolsScrollOffset = Math.max(0, Math.min(max, (clamped - toolsScrollbarTrackTop()) * max / movable));
    }

    private void drawTools(DrawContext context, int mouseX, int mouseY) {
        for (int i = 0; i < Tool.values().length; i++) {
            drawToolButton(context, mouseX, mouseY, Tool.values()[i], i);
        }
        int brushY = brushButtonY();
        context.drawTextWithShadow(this.textRenderer, Text.literal("Brush"), this.toolsLeft + 8, brushY - 12, 0xFFE6EAF0);
        drawSmallButton(context, this.toolsLeft + 8, brushY, 18, "-", this.brushSize > 1, mouseX, mouseY);
        context.drawTextWithShadow(this.textRenderer, Text.literal(String.valueOf(this.brushSize)), this.toolsLeft + 34, brushY + 5, 0xFFC8D0DA);
        drawSmallButton(context, this.toolsLeft + 54, brushY, 18, "+", this.brushSize < 8, mouseX, mouseY);
        int gridY = gridToggleY();
        context.drawTextWithShadow(this.textRenderer, Text.literal("Guides"), this.toolsLeft + 8, gridY - 13, 0xFFE6EAF0);
        drawWideToggle(context, this.toolsLeft + 8, gridY, "Grid", this.showGrid, mouseX, mouseY);
        drawWideToggle(context, this.toolsLeft + 8, uvToggleY(), "UV", this.showUvGuide, mouseX, mouseY);
        int layerY = baseLayerToggleY();
        context.drawTextWithShadow(this.textRenderer, Text.literal("Layers"), this.toolsLeft + 8, layerY - 13, 0xFFE6EAF0);
        drawWideToggle(context, this.toolsLeft + 8, layerY, "Base All", this.showBaseLayer, mouseX, mouseY);
        drawWideToggle(context, this.toolsLeft + 8, overlayLayerToggleY(), "Layer All", this.showOverlayLayer, mouseX, mouseY);
        drawLayerPartToggles(context, mouseX, mouseY);
    }

    private int actionButtonY() {
        return this.toolsTop + 22;
    }

    private int toolGridTopY() {
        return actionButtonY() + 26;
    }

    private int toolButtonLeft(int index) {
        int columnWidth = (this.toolsRight - this.toolsLeft - 22) / 2;
        return this.toolsLeft + 8 + (index % 2) * (columnWidth + 6);
    }

    private int toolButtonRight(int index) {
        int columnWidth = (this.toolsRight - this.toolsLeft - 22) / 2;
        return toolButtonLeft(index) + columnWidth;
    }

    private int toolButtonTop(int index) {
        return toolGridTopY() + (index / 2) * 18;
    }

    private int toolsAfterToolY() {
        return toolGridTopY() + ((Tool.values().length + 1) / 2) * 18;
    }

    private int brushButtonY() {
        return toolsAfterToolY() + 22;
    }

    private int gridToggleY() {
        return brushButtonY() + 38;
    }

    private int uvToggleY() {
        return gridToggleY() + 18;
    }

    private int baseLayerToggleY() {
        return uvToggleY() + 38;
    }

    private int overlayLayerToggleY() {
        return baseLayerToggleY() + 18;
    }

    private int layerPartGridTopY() {
        return overlayLayerToggleY() + 24;
    }

    private int layerPartButtonLeft(int index) {
        int columnWidth = (this.toolsRight - this.toolsLeft - 22) / 2;
        return this.toolsLeft + 8 + (index % 2) * (columnWidth + 6);
    }

    private int layerPartButtonRight(int index) {
        int columnWidth = (this.toolsRight - this.toolsLeft - 22) / 2;
        return layerPartButtonLeft(index) + columnWidth;
    }

    private int layerPartButtonTop(int index) {
        return layerPartGridTopY() + (index / 2) * 17;
    }

    private void drawToolButton(DrawContext context, int mouseX, int mouseY, Tool value, int index) {
        int left = toolButtonLeft(index);
        int right = toolButtonRight(index);
        int y = toolButtonTop(index);
        boolean active = value == this.tool;
        boolean hovered = isInside(mouseX, mouseY, left, y, right, y + 16);
        int fill = active ? 0x44313A46 : hovered ? 0x44262C34 : 0x331B1F26;
        context.fill(left, y, right, y + 16, fill);
        context.drawBorder(left, y, right - left, 16, active ? 0xFFB7C0CF : 0x55727C8D);
        context.drawTextWithShadow(this.textRenderer, Text.literal(value.label), left + 4, y + 4, active ? 0xFFE6EAF0 : 0xFFC8D0DA);
    }

    private void drawLayerPartToggles(DrawContext context, int mouseX, int mouseY) {
        String[] names = new String[]{"B Head", "B Body", "B RArm", "B LArm", "B RLeg", "B LLeg", "L Head", "L Body", "L RArm", "L LArm", "L RLeg", "L LLeg"};
        for (int i = 0; i < names.length; i++) {
            boolean enabled = layerPartEnabled(i >= 6, i % 6);
            int left = layerPartButtonLeft(i);
            int right = layerPartButtonRight(i);
            int top = layerPartButtonTop(i);
            boolean hovered = isInside(mouseX, mouseY, left, top, right, top + 15);
            context.fill(left, top, right, top + 15, enabled ? 0x44313A46 : hovered ? 0x44262C34 : 0x331B1F26);
            context.drawBorder(left, top, right - left, 15, enabled ? 0x88727C8D : 0x664D5563);
            context.drawTextWithShadow(this.textRenderer, Text.literal(names[i]), left + 3, top + 4, enabled ? 0xFFE6EAF0 : 0xFFC8D0DA);
        }
    }

    private void drawSmallButton(DrawContext context, int x, int y, int size, String label, boolean enabled, int mouseX, int mouseY) {
        boolean hovered = enabled && isInside(mouseX, mouseY, x, y, x + size, y + size);
        context.fill(x, y, x + size, y + size, hovered ? 0x44262C34 : 0x331B1F26);
        context.drawBorder(x, y, size, size, enabled ? 0x88727C8D : 0x334D5563);
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), x + 6, y + 5, enabled ? 0xFFE6EAF0 : 0x66727C8D);
    }

    private void drawWideToggle(DrawContext context, int x, int y, String label, boolean enabled, int mouseX, int mouseY) {
        boolean hovered = isInside(mouseX, mouseY, x, y, x + 76, y + 16);
        context.fill(x, y, x + 76, y + 16, enabled ? 0x44313A46 : hovered ? 0x44262C34 : 0x331B1F26);
        context.drawBorder(x, y, 76, 16, enabled ? 0x88727C8D : 0x664D5563);
        context.drawTextWithShadow(this.textRenderer, Text.literal(label + ": " + (enabled ? "On" : "Off")), x + 5, y + 4, enabled ? 0xFFE6EAF0 : 0xFFC8D0DA);
    }

    private void drawPaletteColor(DrawContext context, int x, int y, int color, boolean active) {
        context.fill(x, y, x + 18, y + 18, 0xFF20242C);
        if (((color >> 24) & 255) == 0) {
            context.fill(x + 2, y + 2, x + 16, y + 16, 0xFF303642);
            context.drawTextWithShadow(this.textRenderer, Text.literal("T"), x + 6, y + 5, 0xFFC8D0DA);
        } else {
            context.fill(x + 2, y + 2, x + 16, y + 16, color);
        }
        context.drawBorder(x, y, 18, 18, active ? 0xFFFFFFFF : 0x88727C8D);
    }

    private void drawPaletteBar(DrawContext context, int mouseX, int mouseY) {
        context.drawTextWithShadow(this.textRenderer, Text.literal("Marker Colors"), this.paletteLeft, this.paletteTop, 0xFFE6EAF0);
        int columns = 8;
        int swatch = 18;
        int gap = 6;
        int totalWidth = columns * swatch + (columns - 1) * gap;
        int startX = this.paletteLeft + Math.max(0, (this.paletteRight - this.paletteLeft - totalWidth) / 2);
        int startY = this.paletteTop + 15;
        for (int i = 0; i < this.palette.length; i++) {
            int x = startX + (i % columns) * (swatch + gap);
            int rowY = startY + (i / columns) * 22;
            drawPaletteColor(context, x, rowY, this.palette[i], this.palette[i] == this.selectedColor);
        }
    }

    private void drawCanvas(DrawContext context) {
        int cell = Math.max(1, this.canvasSize / 64);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int px = this.canvasX + x * this.canvasSize / 64;
                int py = this.canvasY + y * this.canvasSize / 64;
                int right = this.canvasX + (x + 1) * this.canvasSize / 64;
                int bottom = this.canvasY + (y + 1) * this.canvasSize / 64;
                context.fill(px, py, Math.max(px + 1, right), Math.max(py + 1, bottom), ((x + y) & 1) == 0 ? 0xFF2A2E36 : 0xFF20242C);
                if (isPixelVisibleByLayer(x, y)) {
                    int color = SkinTextureTools.nativeToArgb(this.image.getColor(x, y));
                    if (((color >> 24) & 255) > 0) {
                        context.fill(px, py, Math.max(px + 1, right), Math.max(py + 1, bottom), color);
                    }
                }
            }
        }
        if (!this.showBaseLayer || !this.showOverlayLayer) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(layerVisibilityLabel()), this.canvasX + 4, this.canvasY + 4, 0xFFE6C46A);
        }
        if (this.showGrid) {
            for (int i = 0; i <= 64; i++) {
                int line = this.canvasX + i * this.canvasSize / 64;
                int lineY = this.canvasY + i * this.canvasSize / 64;
                int alpha = i % 8 == 0 ? 0x774D5563 : 0x224D5563;
                context.fill(line, this.canvasY, line + 1, this.canvasY + this.canvasSize, alpha);
                context.fill(this.canvasX, lineY, this.canvasX + this.canvasSize, lineY + 1, alpha);
            }
        }
        if (this.showUvGuide) {
            drawUvGuide(context);
        }
        if (this.shapeActive) {
            drawShapePreview(context);
        } else if (this.hoverPixelX >= 0 && this.hoverPixelY >= 0 && !isShapeTool()) {
            drawBrushPreview(context);
        }
        if (this.hoverPixelX >= 0 && this.hoverPixelY >= 0) {
            int hx = this.canvasX + this.hoverPixelX * this.canvasSize / 64;
            int hy = this.canvasY + this.hoverPixelY * this.canvasSize / 64;
            context.drawBorder(hx, hy, Math.max(cell, 2), Math.max(cell, 2), 0xFFFFFFFF);
        }
        context.drawBorder(this.canvasX, this.canvasY, this.canvasSize, this.canvasSize, 0xFFB7C0CF);
    }

    private boolean isPixelVisibleByLayer(int x, int y) {
        boolean overlay = isOverlayLayerPixel(x, y);
        int part = skinPartIndex(x, y, overlay);
        if (part < 0) {
            return overlay ? this.showOverlayLayer : this.showBaseLayer;
        }
        return overlay ? this.showOverlayLayer && layerPartEnabled(true, part) : this.showBaseLayer && layerPartEnabled(false, part);
    }

    private int skinPartIndex(int x, int y, boolean overlay) {
        if (y >= 0 && y < 16) {
            return 0;
        }
        if (y >= 16 && y < 32) {
            if (x >= 16 && x < 40) {
                return 1;
            }
            if (x >= 40 && x < 56) {
                return 2;
            }
            if (x >= 0 && x < 16) {
                return 4;
            }
            return -1;
        }
        if (y >= 32 && y < 48) {
            if (x >= 16 && x < 40) {
                return 1;
            }
            if (x >= 40 && x < 56) {
                return 2;
            }
            if (x >= 0 && x < 16) {
                return 4;
            }
            return -1;
        }
        if (y >= 48 && y < 64) {
            if (overlay) {
                if (x >= 48 && x < 64) {
                    return 3;
                }
                if (x >= 0 && x < 16) {
                    return 5;
                }
                return -1;
            }
            if (x >= 32 && x < 48) {
                return 3;
            }
            if (x >= 16 && x < 32) {
                return 5;
            }
        }
        return -1;
    }

    private boolean layerPartEnabled(boolean overlay, int part) {
        if (overlay) {
            if (part == 0) {
                return this.showOverlayHead;
            }
            if (part == 1) {
                return this.showOverlayBody;
            }
            if (part == 2) {
                return this.showOverlayRightArm;
            }
            if (part == 3) {
                return this.showOverlayLeftArm;
            }
            if (part == 4) {
                return this.showOverlayRightLeg;
            }
            if (part == 5) {
                return this.showOverlayLeftLeg;
            }
            return true;
        }
        if (part == 0) {
            return this.showBaseHead;
        }
        if (part == 1) {
            return this.showBaseBody;
        }
        if (part == 2) {
            return this.showBaseRightArm;
        }
        if (part == 3) {
            return this.showBaseLeftArm;
        }
        if (part == 4) {
            return this.showBaseRightLeg;
        }
        if (part == 5) {
            return this.showBaseLeftLeg;
        }
        return true;
    }

    private void toggleLayerPart(boolean overlay, int part) {
        if (overlay) {
            if (part == 0) {
                this.showOverlayHead = !this.showOverlayHead;
            } else if (part == 1) {
                this.showOverlayBody = !this.showOverlayBody;
            } else if (part == 2) {
                this.showOverlayRightArm = !this.showOverlayRightArm;
            } else if (part == 3) {
                this.showOverlayLeftArm = !this.showOverlayLeftArm;
            } else if (part == 4) {
                this.showOverlayRightLeg = !this.showOverlayRightLeg;
            } else if (part == 5) {
                this.showOverlayLeftLeg = !this.showOverlayLeftLeg;
            }
        } else if (part == 0) {
            this.showBaseHead = !this.showBaseHead;
        } else if (part == 1) {
            this.showBaseBody = !this.showBaseBody;
        } else if (part == 2) {
            this.showBaseRightArm = !this.showBaseRightArm;
        } else if (part == 3) {
            this.showBaseLeftArm = !this.showBaseLeftArm;
        } else if (part == 4) {
            this.showBaseRightLeg = !this.showBaseRightLeg;
        } else if (part == 5) {
            this.showBaseLeftLeg = !this.showBaseLeftLeg;
        }
    }

    private boolean isOverlayLayerPixel(int x, int y) {
        if (x >= 32 && x < 64 && y >= 0 && y < 16) {
            return true;
        }
        if (x >= 16 && x < 40 && y >= 32 && y < 48) {
            return true;
        }
        if (x >= 0 && x < 16 && y >= 32 && y < 48) {
            return true;
        }
        if (x >= 40 && x < 56 && y >= 32 && y < 48) {
            return true;
        }
        if (x >= 0 && x < 16 && y >= 48 && y < 64) {
            return true;
        }
        return x >= 48 && x < 64 && y >= 48 && y < 64;
    }

    private String layerVisibilityLabel() {
        return "Filtered layers";
    }

    private void drawBrushPreview(DrawContext context) {
        int half = this.brushSize / 2;
        int minX = Math.max(0, this.hoverPixelX - half);
        int minY = Math.max(0, this.hoverPixelY - half);
        int maxX = Math.min(63, this.hoverPixelX - half + this.brushSize - 1);
        int maxY = Math.min(63, this.hoverPixelY - half + this.brushSize - 1);
        int left = this.canvasX + minX * this.canvasSize / 64;
        int top = this.canvasY + minY * this.canvasSize / 64;
        int right = this.canvasX + (maxX + 1) * this.canvasSize / 64;
        int bottom = this.canvasY + (maxY + 1) * this.canvasSize / 64;
        context.fill(left, top, right, bottom, SkinTextureTools.withAlpha(this.selectedColor, 80));
        context.drawBorder(left, top, Math.max(1, right - left), Math.max(1, bottom - top), 0xFFFFFFFF);
    }

    private void drawShapePreview(DrawContext context) {
        int color = SkinTextureTools.withAlpha(this.selectedColor, 150);
        if (this.tool == Tool.LINE) {
            drawPreviewLine(context, this.shapeStartX, this.shapeStartY, this.shapeEndX, this.shapeEndY, color);
            return;
        }
        int minX = Math.min(this.shapeStartX, this.shapeEndX);
        int maxX = Math.max(this.shapeStartX, this.shapeEndX);
        int minY = Math.min(this.shapeStartY, this.shapeEndY);
        int maxY = Math.max(this.shapeStartY, this.shapeEndY);
        int left = this.canvasX + minX * this.canvasSize / 64;
        int top = this.canvasY + minY * this.canvasSize / 64;
        int right = this.canvasX + (maxX + 1) * this.canvasSize / 64;
        int bottom = this.canvasY + (maxY + 1) * this.canvasSize / 64;
        if (this.tool == Tool.RECT_FILL) {
            context.fill(left, top, right, bottom, SkinTextureTools.withAlpha(this.selectedColor, 90));
        }
        context.drawBorder(left, top, Math.max(1, right - left), Math.max(1, bottom - top), color);
    }

    private void drawPreviewLine(DrawContext context, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            int left = this.canvasX + x * this.canvasSize / 64;
            int top = this.canvasY + y * this.canvasSize / 64;
            int right = this.canvasX + (x + 1) * this.canvasSize / 64;
            int bottom = this.canvasY + (y + 1) * this.canvasSize / 64;
            context.fill(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom), color);
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void drawUvGuide(DrawContext context) {
        drawUvRect(context, 8, 8, 8, 8, "Head");
        drawUvRect(context, 40, 8, 8, 8, "Hat");
        drawUvRect(context, 20, 20, 8, 12, "Body");
        drawUvRect(context, 44, 20, 4, 12, "R Arm");
        drawUvRect(context, 4, 20, 4, 12, "R Leg");
        drawUvRect(context, 36, 52, 4, 12, "L Arm");
        drawUvRect(context, 20, 52, 4, 12, "L Leg");
        drawUvRect(context, 44, 36, 4, 12, "R Arm 2");
        drawUvRect(context, 4, 36, 4, 12, "R Leg 2");
    }

    private void drawUvRect(DrawContext context, int x, int y, int w, int h, String label) {
        int left = this.canvasX + x * this.canvasSize / 64;
        int top = this.canvasY + y * this.canvasSize / 64;
        int width = Math.max(1, w * this.canvasSize / 64);
        int height = Math.max(1, h * this.canvasSize / 64);
        context.drawBorder(left, top, width, height, 0x99E6C46A);
        if (width > 28 && height > 9) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + 2, top + 2, 0xFFE6C46A);
        }
    }

    private int previewViewportTop() {
        return this.previewTop + 30;
    }

    private int previewViewportBottom() {
        return this.previewBottom - 8;
    }

    private int previewContentHeight() {
        return 452;
    }

    private int maxPreviewScrollOffset() {
        return Math.max(0, previewContentHeight() - Math.max(1, previewViewportBottom() - previewViewportTop()));
    }

    private void drawPreviewScrollContent(DrawContext context, int mouseX, int mouseY) {
        this.previewScrollOffset = Math.max(0, Math.min(maxPreviewScrollOffset(), this.previewScrollOffset));
        int left = this.previewLeft + 8;
        int right = this.previewRight - 8;
        int viewportTop = previewViewportTop();
        int viewportBottom = previewViewportBottom();
        renderScissored(context, left, viewportTop, right, viewportBottom, () -> {
            int y = viewportTop - this.previewScrollOffset;
            context.drawTextWithShadow(this.textRenderer, Text.literal("Yaw " + (int) this.modelYaw + " Pitch " + (int) this.modelPitch + " Roll " + (int) this.modelRoll), left + 2, y + 2, 0xFF8F98A8);
            int modelTop = y - 4;
            int modelCenter = modelTop + 98;
            float modelScale = Math.min(92.0F, Math.max(68.0F, (this.previewRight - this.previewLeft) / 3.9F));
            SkinModelRenderer.render(context, this.textureId == null ? this.sourcePreview : this.textureId, this.slim, (this.previewLeft + this.previewRight) / 2, modelCenter, modelScale, this.modelYaw, this.modelPitch, this.modelRoll, this.showBaseLayer && this.showBaseHead, this.showBaseLayer && this.showBaseBody, this.showBaseLayer && this.showBaseRightArm, this.showBaseLayer && this.showBaseLeftArm, this.showBaseLayer && this.showBaseRightLeg, this.showBaseLayer && this.showBaseLeftLeg, this.showOverlayLayer && this.showOverlayHead, this.showOverlayLayer && this.showOverlayBody, this.showOverlayLayer && this.showOverlayRightArm, this.showOverlayLayer && this.showOverlayLeftArm, this.showOverlayLayer && this.showOverlayRightLeg, this.showOverlayLayer && this.showOverlayLeftLeg);
            int dataTop = modelTop + 224;
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.entry == null ? "New editable skin" : this.entry.safeName()), left + 2, dataTop, 0xFFE6EAF0);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Model: " + (this.slim ? "Slim" : "Regular") + "  Tool: " + this.tool.label + "  Brush: " + this.brushSize), left + 2, dataTop + 12, 0xFFB7C0CF);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Layers: " + layerVisibilityLabel() + "  Color: #" + colorHex(this.selectedColor)), left + 2, dataTop + 24, 0xFF8F98A8);
            if (this.hoverPixelX >= 0 && this.hoverPixelY >= 0) {
                context.drawTextWithShadow(this.textRenderer, Text.literal("Pixel: " + this.hoverPixelX + ", " + this.hoverPixelY + "  " + uvZoneName(this.hoverPixelX, this.hoverPixelY) + "  #" + colorHex(SkinTextureTools.nativeToArgb(this.image.getColor(this.hoverPixelX, this.hoverPixelY)))), left + 2, dataTop + 36, 0xFF8F98A8);
            }
            int buttonY = dataTop + 58;
            int buttonWidth = 72;
            int gap = 5;
            int totalWidth = buttonWidth * 3 + gap * 2;
            int startX = (this.previewLeft + this.previewRight) / 2 - totalWidth / 2;
            drawPreviewButton(context, mouseX, mouseY, startX, buttonY, startX + buttonWidth, buttonY + 18, this.slim ? "Slim" : "Regular", 0xFFB7C0CF);
            drawPreviewButton(context, mouseX, mouseY, startX + buttonWidth + gap, buttonY, startX + buttonWidth * 2 + gap, buttonY + 18, "Overwrite", 0xFF9FE6A0);
            drawPreviewButton(context, mouseX, mouseY, startX + (buttonWidth + gap) * 2, buttonY, startX + buttonWidth * 3 + gap * 2, buttonY + 18, "Save Copy", 0xFFE6EAF0);
            int vanillaY = buttonY + 32;
            context.drawTextWithShadow(this.textRenderer, Text.literal("Vanilla Skin Parts"), left + 2, vanillaY, 0xFFE6EAF0);
            drawVanillaSkinParts(context, mouseX, mouseY, left + 2, vanillaY + 14);
        });
        drawPreviewScrollbar(context);
    }

    private void drawPreviewButton(DrawContext context, int mouseX, int mouseY, int left, int top, int right, int bottom, String label, int textColor) {
        boolean hovered = isInside(mouseX, mouseY, left, top, right, bottom);
        context.fill(left, top, right, bottom, hovered ? 0x44262C34 : 0x331B1F26);
        context.drawBorder(left, top, right - left, bottom - top, hovered ? 0x88727C8D : 0x664D5563);
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + 5, top + 4, textColor);
    }

    private void drawVanillaSkinParts(DrawContext context, int mouseX, int mouseY, int x, int y) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return;
        }
        GameOptions options = minecraft.options;
        int gap = 4;
        int columnWidth = Math.max(82, (this.previewRight - this.previewLeft - 28 - gap) / 2);
        int row = 0;
        int column = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (part == PlayerModelPart.HAT) {
                continue;
            }
            int buttonX = x + column * (columnWidth + gap);
            int buttonY = y + row * 18;
            drawVanillaButton(context, buttonX, buttonY, columnWidth, 16, part.getOptionName().getString() + ": " + (options.isPlayerModelPartEnabled(part) ? "On" : "Off"), true, mouseX, mouseY);
            column++;
            if (column >= 2) {
                column = 0;
                row++;
            }
        }
        if (column != 0) {
            column = 0;
            row++;
        }
        int hatY = y + row * 18;
        drawVanillaButton(context, x, hatY, columnWidth, 16, PlayerModelPart.HAT.getOptionName().getString() + ": " + (options.isPlayerModelPartEnabled(PlayerModelPart.HAT) ? "On" : "Off"), true, mouseX, mouseY);
        net.minecraft.util.Arm arm = options.getMainArm().getValue();
        drawVanillaButton(context, x + columnWidth + gap, hatY, columnWidth, 16, "Main: " + (arm == net.minecraft.util.Arm.LEFT ? "Left" : "Right"), true, mouseX, mouseY);
    }


    private void drawVanillaButton(DrawContext context, int left, int top, int width, int height, String label, boolean enabled, int mouseX, int mouseY) {
        drawMinecraftButton(context, mouseX, mouseY, left, top, left + width, top + height, label);
    }

    private void drawMinecraftButton(DrawContext context, int mouseX, int mouseY, int left, int top, int right, int bottom, String label) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), value -> {}).dimensions(left, top, right - left, bottom - top).build();
        button.render(context, mouseX, mouseY, 0.0F);
    }


    private void drawPreviewScrollbar(DrawContext context) {
        int max = maxPreviewScrollOffset();
        if (max <= 0) {
            return;
        }
        int trackLeft = previewScrollbarTrackLeft();
        int trackTop = previewScrollbarTrackTop();
        int trackBottom = previewScrollbarTrackBottom();
        int thumbTop = previewScrollbarThumbTop();
        int thumbHeight = previewScrollbarThumbHeight();
        int lineX = trackLeft + 1;
        context.fill(lineX, trackTop, lineX + 1, trackBottom, 0x664D5563);
        context.fill(trackLeft, thumbTop, trackLeft + 3, thumbTop + thumbHeight, 0xFF727C8D);
    }

    private int previewScrollbarTrackLeft() {
        return this.previewRight - 7;
    }

    private int previewScrollbarTrackTop() {
        return previewViewportTop();
    }

    private int previewScrollbarTrackBottom() {
        return previewViewportBottom();
    }

    private int previewScrollbarThumbHeight() {
        int trackHeight = Math.max(1, previewScrollbarTrackBottom() - previewScrollbarTrackTop());
        return Math.max(24, trackHeight * trackHeight / Math.max(trackHeight, previewContentHeight()));
    }

    private int previewScrollbarThumbTop() {
        int max = maxPreviewScrollOffset();
        int trackTop = previewScrollbarTrackTop();
        int movable = Math.max(0, previewScrollbarTrackBottom() - trackTop - previewScrollbarThumbHeight());
        if (max <= 0) {
            return trackTop;
        }
        return trackTop + this.previewScrollOffset * movable / max;
    }

    private void updatePreviewScrollFromThumb(double mouseY) {
        int max = maxPreviewScrollOffset();
        int movable = Math.max(1, previewScrollbarTrackBottom() - previewScrollbarTrackTop() - previewScrollbarThumbHeight());
        int top = (int) mouseY - this.previewScrollbarDragOffset;
        int clamped = Math.max(previewScrollbarTrackTop(), Math.min(previewScrollbarTrackTop() + movable, top));
        this.previewScrollOffset = Math.max(0, Math.min(max, (clamped - previewScrollbarTrackTop()) * max / movable));
    }

    private boolean handlePreviewClick(double mouseX, double mouseY) {
        int viewportTop = previewViewportTop();
        int y = viewportTop - this.previewScrollOffset;
        int dataTop = y - 4 + 224;
        int buttonY = dataTop + 58;
        int buttonWidth = 72;
        int gap = 5;
        int totalWidth = buttonWidth * 3 + gap * 2;
        int startX = (this.previewLeft + this.previewRight) / 2 - totalWidth / 2;
        if (isInside(mouseX, mouseY, startX, buttonY, startX + buttonWidth, buttonY + 18)) {
            playClick();
            toggleModel();
            return true;
        }
        if (isInside(mouseX, mouseY, startX + buttonWidth + gap, buttonY, startX + buttonWidth * 2 + gap, buttonY + 18)) {
            playClick();
            overwrite();
            return true;
        }
        if (isInside(mouseX, mouseY, startX + (buttonWidth + gap) * 2, buttonY, startX + buttonWidth * 3 + gap * 2, buttonY + 18)) {
            playClick();
            saveCopy();
            return true;
        }
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return false;
        }
        int vanillaY = buttonY + 32 + 14;
        int left = this.previewLeft + 10;
        GameOptions options = minecraft.options;
        int partGap = 4;
        int columnWidth = Math.max(82, (this.previewRight - this.previewLeft - 28 - partGap) / 2);
        int row = 0;
        int column = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (part == PlayerModelPart.HAT) {
                continue;
            }
            int buttonX = left + column * (columnWidth + partGap);
            int partButtonY = vanillaY + row * 18;
            if (isInside(mouseX, mouseY, buttonX, partButtonY, buttonX + columnWidth, partButtonY + 16)) {
                playClick();
                options.togglePlayerModelPart(part, !options.isPlayerModelPartEnabled(part));
                setStatus(part.getOptionName().getString() + " " + (options.isPlayerModelPartEnabled(part) ? "enabled" : "disabled") + ".");
                return true;
            }
            column++;
            if (column >= 2) {
                column = 0;
                row++;
            }
        }
        if (column != 0) {
            column = 0;
            row++;
        }
        int hatY = vanillaY + row * 18;
        if (isInside(mouseX, mouseY, left, hatY, left + columnWidth, hatY + 16)) {
            playClick();
            options.togglePlayerModelPart(PlayerModelPart.HAT, !options.isPlayerModelPartEnabled(PlayerModelPart.HAT));
            setStatus(PlayerModelPart.HAT.getOptionName().getString() + " " + (options.isPlayerModelPartEnabled(PlayerModelPart.HAT) ? "enabled" : "disabled") + ".");
            return true;
        }
        int mainHandX = left + columnWidth + partGap;
        if (isInside(mouseX, mouseY, mainHandX, hatY, mainHandX + columnWidth, hatY + 16)) {
            playClick();
            net.minecraft.util.Arm arm = options.getMainArm().getValue();
            options.getMainArm().setValue(arm == net.minecraft.util.Arm.LEFT ? net.minecraft.util.Arm.RIGHT : net.minecraft.util.Arm.LEFT);
            setStatus("Main hand changed.");
            return true;
        }
        return false;
    }


    private void renderScissored(DrawContext context, int left, int top, int right, int bottom, Runnable render) {
        context.enableScissor(left, top, right, bottom);
        try {
            render.run();
        } finally {
            context.disableScissor();
        }
    }

    private void applyToolAt(double mouseX, double mouseY, boolean drag) {
        int px = canvasPixelX(mouseX);
        int py = canvasPixelY(mouseY);
        if (px < 0 || py < 0 || px >= 64 || py >= 64) {
            return;
        }
        if (this.tool == Tool.PICKER) {
            this.selectedColor = SkinTextureTools.nativeToArgb(this.image.getColor(px, py));
            setStatus("Picked color #" + colorHex(this.selectedColor) + ".");
            return;
        }
        if ((this.tool == Tool.FILL || this.tool == Tool.MIRROR) && drag) {
            return;
        }
        if (!this.strokeActive) {
            this.strokeActive = true;
            this.redo.clear();
            this.lastDrawPixelX = -1;
            this.lastDrawPixelY = -1;
        }
        if (this.tool == Tool.PENCIL) {
            if (drag && this.lastDrawPixelX >= 0) {
                drawLine(this.lastDrawPixelX, this.lastDrawPixelY, px, py, this.selectedColor);
            } else {
                applyBrush(px, py, this.selectedColor);
            }
        } else if (this.tool == Tool.ERASER) {
            if (drag && this.lastDrawPixelX >= 0) {
                drawLine(this.lastDrawPixelX, this.lastDrawPixelY, px, py, 0x00000000);
            } else {
                applyBrush(px, py, 0x00000000);
            }
        } else if (this.tool == Tool.FILL) {
            floodFill(px, py, this.image.getColor(px, py), this.selectedColor);
        } else if (this.tool == Tool.LIGHTEN) {
            applyFilterBrush(px, py, true);
        } else if (this.tool == Tool.DARKEN) {
            applyFilterBrush(px, py, false);
        } else if (this.tool == Tool.MIRROR) {
            mirrorSide(px < 32);
            setStatus(px < 32 ? "Copied left half to right half." : "Copied right half to left half.");
        }
        this.lastDrawPixelX = px;
        this.lastDrawPixelY = py;
        upload();
    }

    private void beginShape(double mouseX, double mouseY) {
        int px = canvasPixelX(mouseX);
        int py = canvasPixelY(mouseY);
        if (px < 0 || py < 0) {
            return;
        }
        this.shapeActive = true;
        this.shapeStartX = px;
        this.shapeStartY = py;
        this.shapeEndX = px;
        this.shapeEndY = py;
        setStatus(this.tool.label + " preview active. Drag to size it, release to draw it.");
    }

    private void updateShape(double mouseX, double mouseY) {
        int px = canvasPixelX(mouseX);
        int py = canvasPixelY(mouseY);
        if (px < 0 || py < 0) {
            return;
        }
        this.shapeEndX = px;
        this.shapeEndY = py;
    }

    private void commitShape() {
        if (!this.shapeActive) {
            return;
        }
        if (this.tool == Tool.LINE) {
            drawLine(this.shapeStartX, this.shapeStartY, this.shapeEndX, this.shapeEndY, this.selectedColor);
        } else if (this.tool == Tool.RECT_FILL) {
            drawRect(this.shapeStartX, this.shapeStartY, this.shapeEndX, this.shapeEndY, true);
        } else if (this.tool == Tool.RECT_OUTLINE) {
            drawRect(this.shapeStartX, this.shapeStartY, this.shapeEndX, this.shapeEndY, false);
        }
        this.shapeActive = false;
        pushUndo(true);
        upload();
        setStatus(this.tool.label + " applied.");
    }

    private boolean isShapeTool() {
        return this.tool == Tool.LINE || this.tool == Tool.RECT_FILL || this.tool == Tool.RECT_OUTLINE;
    }

    private void applyBrush(int px, int py, int color) {
        int half = this.brushSize / 2;
        for (int oy = 0; oy < this.brushSize; oy++) {
            for (int ox = 0; ox < this.brushSize; ox++) {
                setPixelSafe(px + ox - half, py + oy - half, color);
            }
        }
    }

    private void applyFilterBrush(int px, int py, boolean lighten) {
        int half = this.brushSize / 2;
        for (int oy = 0; oy < this.brushSize; oy++) {
            for (int ox = 0; ox < this.brushSize; ox++) {
                int x = px + ox - half;
                int y = py + oy - half;
                if (x >= 0 && y >= 0 && x < 64 && y < 64) {
                    int color = SkinTextureTools.nativeToArgb(this.image.getColor(x, y));
                    this.image.setColor(x, y, SkinTextureTools.argbToNative(lighten ? SkinTextureTools.lighten(color, 12) : SkinTextureTools.darken(color, 12)));
                }
            }
        }
    }

    private void setPixelSafe(int x, int y, int color) {
        if (x >= 0 && y >= 0 && x < 64 && y < 64) {
            this.image.setColor(x, y, SkinTextureTools.argbToNative(color));
        }
    }

    private void drawLine(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            applyBrush(x, y, color);
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void drawRect(int x0, int y0, int x1, int y1, boolean fill) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1);
        int maxY = Math.max(y0, y1);
        if (fill) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    setPixelSafe(x, y, this.selectedColor);
                }
            }
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            applyBrush(x, minY, this.selectedColor);
            applyBrush(x, maxY, this.selectedColor);
        }
        for (int y = minY; y <= maxY; y++) {
            applyBrush(minX, y, this.selectedColor);
            applyBrush(maxX, y, this.selectedColor);
        }
    }

    private void floodFill(int startX, int startY, int targetColor, int replacement) {
        int replacementNative = SkinTextureTools.argbToNative(replacement);
        if (targetColor == replacementNative) {
            return;
        }
        boolean[] visited = new boolean[64 * 64];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY});
        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();
            int x = point[0];
            int y = point[1];
            if (x < 0 || y < 0 || x >= 64 || y >= 64) {
                continue;
            }
            int index = y * 64 + x;
            if (visited[index]) {
                continue;
            }
            visited[index] = true;
            if (this.image.getColor(x, y) != targetColor) {
                continue;
            }
            this.image.setColor(x, y, replacementNative);
            queue.add(new int[]{x + 1, y});
            queue.add(new int[]{x - 1, y});
            queue.add(new int[]{x, y + 1});
            queue.add(new int[]{x, y - 1});
        }
    }

    private void mirrorSide(boolean leftToRight) {
        int fromStart = leftToRight ? 0 : 32;
        int toStart = leftToRight ? 32 : 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 32; x++) {
                int sourceX = fromStart + x;
                int targetX = toStart + (31 - x);
                this.image.setColor(targetX, y, this.image.getColor(sourceX, y));
            }
        }
    }

    private void renderSharedColorPicker(DrawContext context) {
        if (this.sharedColorPickerPopup == null) {
            this.colorPickerOpen = false;
            return;
        }
        this.sharedColorPickerPopup.render(context);
    }

    private void openColorPicker(int paletteIndex) {
        openColorPicker(paletteIndex, this.width / 2, this.height / 2);
    }

    private void openColorPicker(int paletteIndex, int mouseX, int mouseY) {
        if (paletteIndex < 0 || paletteIndex >= this.palette.length) {
            return;
        }
        this.colorPickerIndex = paletteIndex;
        openSharedColorPicker(mouseX, mouseY, this.palette[paletteIndex], color -> {
            if (this.colorPickerIndex >= 0 && this.colorPickerIndex < this.palette.length) {
                this.palette[this.colorPickerIndex] = color;
                this.selectedColor = color;
                this.library.saveEditorPalette(this.palette);
                setStatus("Palette color saved and remembered.");
            }
        }, "Editing palette color " + (paletteIndex + 1) + ".");
    }

    private void openSelectedColorPicker(int initialColor) {
        this.colorPickerIndex = -1;
        openSharedColorPicker(this.width / 2, this.height / 2, initialColor, color -> {
            this.selectedColor = color;
            setStatus("Selected color #" + colorHex(this.selectedColor) + ".");
        }, "Editing selected brush color.");
    }

    private void openSharedColorPicker(int mouseX, int mouseY, int initialColor, IntConsumer onColorChanged, String openedStatus) {
        this.sharedColorPickerPopup = new ColorPickerPopup();
        this.sharedColorPickerPopup.open(mouseX, mouseY, this.width, this.height, initialColor, onColorChanged);
        this.colorPickerOpen = true;
        setStatus(openedStatus);
    }

    private boolean handleColorPickerClick(double mouseX, double mouseY, int button) {
        if (this.sharedColorPickerPopup == null) {
            this.colorPickerOpen = false;
            return false;
        }
        this.sharedColorPickerPopup.mouseClicked(mouseX, mouseY, button);
        if (!this.sharedColorPickerPopup.isOpen()) {
            this.colorPickerOpen = false;
            this.sharedColorPickerPopup = null;
        }
        return true;
    }

    private boolean handleColorPickerDrag(double mouseX, double mouseY) {
        if (this.sharedColorPickerPopup == null) {
            this.colorPickerOpen = false;
            return false;
        }
        return this.sharedColorPickerPopup.mouseDragged(mouseX, mouseY, 0) || this.colorPickerOpen;
    }

    private void upload() {
        if (this.texture != null) {
            this.texture.upload();
        }
    }

    private void pushUndo(boolean clearRedo) {
        if (this.image == null) {
            return;
        }
        int[] snapshot = SkinTextureTools.snapshot(this.image);
        int[] current = this.undo.peekLast();
        if (current != null && Arrays.equals(current, snapshot)) {
            if (clearRedo) {
                this.redo.clear();
            }
            return;
        }
        this.undo.addLast(snapshot);
        while (this.undo.size() > 48) {
            this.undo.removeFirst();
        }
        if (clearRedo) {
            this.redo.clear();
        }
    }

    private void undo() {
        if (this.undo.size() <= 1) {
            setStatus("Nothing to undo.");
            return;
        }
        int[] current = this.undo.removeLast();
        this.redo.addLast(current);
        int[] snapshot = this.undo.peekLast();
        if (snapshot != null) {
            SkinTextureTools.restore(this.image, snapshot);
        }
        this.shapeActive = false;
        upload();
        setStatus("Undo applied.");
    }

    private void redo() {
        if (this.redo.isEmpty()) {
            setStatus("Nothing to redo.");
            return;
        }
        int[] snapshot = this.redo.removeLast();
        this.undo.addLast(snapshot);
        SkinTextureTools.restore(this.image, snapshot);
        this.shapeActive = false;
        upload();
        setStatus("Redo applied.");
    }

    private void overwrite() {
        try {
            if (this.entry == null) {
                saveCopy();
                return;
            }
            this.library.overwriteEntry(this.entry, this.image, this.slim);
            setStatus("Overwrote " + this.entry.safeName() + ".");
        } catch (Exception e) {
            setStatus("Overwrite failed: " + e.getMessage());
            SUBLOGGER.logE("Skin-Editor thread", "Overwrite failed: " + e.getMessage());
        }
    }

    private void saveCopy() {
        try {
            String name = this.entry == null ? "Edited Skin" : this.entry.safeName() + " Edit";
            SkinEntry saved = this.library.addFromNativeImage(this.image, name, this.slim, "Editor", "Created in Koil skin editor", "");
            this.library.setActive(saved);
            this.client.setScreen(new ChangeSkinScreen(this.parent, saved.id));
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage());
            SUBLOGGER.logE("Skin-Editor thread", "Save copy failed: " + e.getMessage());
        }
    }

    private void toggleModel() {
        this.slim = !this.slim;
        setStatus("Model changed to " + (this.slim ? "Slim" : "Regular") + ".");
    }

    private void setStatus(String message) {
        this.status = message == null ? "" : message;
    }

    private void playClick() {
        UiSoundHelper.playButtonClick();
    }

    private int canvasPixelX(double mouseX) {
        if (mouseX < this.canvasX || mouseX > this.canvasX + this.canvasSize) {
            return -1;
        }
        return Math.max(0, Math.min(63, (int) ((mouseX - this.canvasX) * 64.0D / this.canvasSize)));
    }

    private int canvasPixelY(double mouseY) {
        if (mouseY < this.canvasY || mouseY > this.canvasY + this.canvasSize) {
            return -1;
        }
        return Math.max(0, Math.min(63, (int) ((mouseY - this.canvasY) * 64.0D / this.canvasSize)));
    }

    private String colorHex(int color) {
        return String.format("%08X", color);
    }

    private String uvZoneName(int x, int y) {
        if (x >= 8 && x < 16 && y >= 8 && y < 16) {
            return "Head";
        }
        if (x >= 40 && x < 48 && y >= 8 && y < 16) {
            return "Hat";
        }
        if (x >= 20 && x < 28 && y >= 20 && y < 32) {
            return "Body";
        }
        if (x >= 44 && x < 48 && y >= 20 && y < 32) {
            return "Right Arm";
        }
        if (x >= 4 && x < 8 && y >= 20 && y < 32) {
            return "Right Leg";
        }
        if (x >= 36 && x < 40 && y >= 52 && y < 64) {
            return "Left Arm";
        }
        if (x >= 20 && x < 24 && y >= 52 && y < 64) {
            return "Left Leg";
        }
        if (y >= 32) {
            return "Outer Layer";
        }
        return "Skin Map";
    }

    private boolean isInside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    private static int[] defaultPalette() {
        return new int[]{0xFFFFFFFF, 0xFF000000, 0xFF3B2F2F, 0xFF7A4A2A, 0xFFB88C63, 0xFFE0C097, 0xFFFFD6A4, 0xFFFFC8C8, 0xFFFF0000, 0xFFFF8C00, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF3F7CFF, 0xFF9C5CFF, 0x00000000};
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.colorPickerOpen) {
            return handleColorPickerClick(mouseX, mouseY, button);
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            int paletteIndex = paletteIndexAt(mouseX, mouseY);
            if (paletteIndex >= 0) {
                playClick();
                openColorPicker(paletteIndex, (int) mouseX, (int) mouseY);
                return true;
            }
            if (isInside(mouseX, mouseY, this.canvasX, this.canvasY, this.canvasX + this.canvasSize, this.canvasY + this.canvasSize)) {
                playClick();
                int pixelX = canvasPixelX(mouseX);
                int pixelY = canvasPixelY(mouseY);
                int initialColor = pixelX >= 0 && pixelY >= 0 ? SkinTextureTools.nativeToArgb(this.image.getColor(pixelX, pixelY)) : this.selectedColor;
                openSelectedColorPicker(initialColor);
                return true;
            }
            return false;
        }
        if (handleActionButtonClick(mouseX, mouseY)) {
            return true;
        }
        if (maxPreviewScrollOffset() > 0) {
            int thumbTop = previewScrollbarThumbTop();
            int thumbBottom = thumbTop + previewScrollbarThumbHeight();
            if (isInside(mouseX, mouseY, previewScrollbarTrackLeft() - 2, thumbTop, previewScrollbarTrackLeft() + 6, thumbBottom)) {
                this.draggingPreviewScrollbar = true;
                this.previewScrollbarDragOffset = (int) mouseY - thumbTop;
                return true;
            }
        }
        if (maxToolsScrollOffset() > 0) {
            int thumbTop = toolsScrollbarThumbTop();
            int thumbBottom = thumbTop + toolsScrollbarThumbHeight();
            if (isInside(mouseX, mouseY, toolsScrollbarTrackLeft() - 2, thumbTop, toolsScrollbarTrackLeft() + 6, thumbBottom)) {
                this.draggingToolsScrollbar = true;
                this.toolsScrollbarDragOffset = (int) mouseY - thumbTop;
                return true;
            }
        }
        double toolMouseY = mouseY + this.toolsScrollOffset;
        boolean insideToolsViewport = isInside(mouseX, mouseY, this.toolsLeft, toolsViewportTop(), this.toolsRight, toolsViewportBottom());
        for (int i = 0; i < Tool.values().length; i++) {
            Tool value = Tool.values()[i];
            if (insideToolsViewport && isInside(mouseX, toolMouseY, toolButtonLeft(i), toolButtonTop(i), toolButtonRight(i), toolButtonTop(i) + 16)) {
                playClick();
                this.tool = value;
                this.shapeActive = false;
                setStatus("Selected " + value.label + ".");
                return true;
            }
        }
        int brushY = brushButtonY();
        if (insideToolsViewport && isInside(mouseX, toolMouseY, this.toolsLeft + 8, brushY, this.toolsLeft + 26, brushY + 18)) {
            playClick();
            this.brushSize = Math.max(1, this.brushSize - 1);
            setStatus("Brush size " + this.brushSize + ".");
            return true;
        }
        if (insideToolsViewport && isInside(mouseX, toolMouseY, this.toolsLeft + 54, brushY, this.toolsLeft + 72, brushY + 18)) {
            playClick();
            this.brushSize = Math.min(8, this.brushSize + 1);
            setStatus("Brush size " + this.brushSize + ".");
            return true;
        }
        if (insideToolsViewport && isInside(mouseX, toolMouseY, this.toolsLeft + 8, gridToggleY(), this.toolsLeft + 90, gridToggleY() + 16)) {
            playClick();
            this.showGrid = !this.showGrid;
            setStatus("Grid guide " + (this.showGrid ? "enabled" : "disabled") + ".");
            return true;
        }
        if (insideToolsViewport && isInside(mouseX, toolMouseY, this.toolsLeft + 8, uvToggleY(), this.toolsLeft + 90, uvToggleY() + 16)) {
            playClick();
            this.showUvGuide = !this.showUvGuide;
            setStatus("UV guide " + (this.showUvGuide ? "enabled" : "disabled") + ".");
            return true;
        }
        if (insideToolsViewport && isInside(mouseX, toolMouseY, this.toolsLeft + 8, baseLayerToggleY(), this.toolsLeft + 90, baseLayerToggleY() + 16)) {
            playClick();
            this.showBaseLayer = !this.showBaseLayer;
            setStatus("Base layer visibility " + (this.showBaseLayer ? "enabled" : "disabled") + ".");
            return true;
        }
        if (insideToolsViewport && isInside(mouseX, toolMouseY, this.toolsLeft + 8, overlayLayerToggleY(), this.toolsLeft + 90, overlayLayerToggleY() + 16)) {
            playClick();
            this.showOverlayLayer = !this.showOverlayLayer;
            setStatus("Overlay layer visibility " + (this.showOverlayLayer ? "enabled" : "disabled") + ".");
            return true;
        }
        for (int i = 0; i < 12; i++) {
            if (insideToolsViewport && isInside(mouseX, toolMouseY, layerPartButtonLeft(i), layerPartButtonTop(i), layerPartButtonRight(i), layerPartButtonTop(i) + 15)) {
                playClick();
                toggleLayerPart(i >= 6, i % 6);
                setStatus("Layer part visibility changed.");
                return true;
            }
        }
        int paletteIndex = paletteIndexAt(mouseX, mouseY);
        if (paletteIndex >= 0) {
            playClick();
            long now = System.currentTimeMillis();
            if (this.lastPaletteIndex == paletteIndex && now - this.lastPaletteClick < 360L) {
                openColorPicker(paletteIndex, (int) mouseX, (int) mouseY);
            } else {
                this.selectedColor = this.palette[paletteIndex];
                setStatus("Selected color #" + colorHex(this.selectedColor) + ". Double-click this box to edit it.");
            }
            this.lastPaletteIndex = paletteIndex;
            this.lastPaletteClick = now;
            return true;
        }
        if (isInside(mouseX, mouseY, this.canvasX, this.canvasY, this.canvasX + this.canvasSize, this.canvasY + this.canvasSize)) {
            this.strokeActive = false;
            if (isShapeTool()) {
                beginShape(mouseX, mouseY);
            } else {
                applyToolAt(mouseX, mouseY, false);
            }
            return true;
        }
        if (isInside(mouseX, mouseY, this.previewLeft, previewViewportTop(), this.previewRight, previewViewportBottom())) {
            if (handlePreviewClick(mouseX, mouseY)) {
                return true;
            }
            return true;
        }
        return false;
    }

    private int paletteIndexAt(double mouseX, double mouseY) {
        int columns = 8;
        int swatch = 18;
        int gap = 6;
        int totalWidth = columns * swatch + (columns - 1) * gap;
        int startX = this.paletteLeft + Math.max(0, (this.paletteRight - this.paletteLeft - totalWidth) / 2);
        int startY = this.paletteTop + 15;
        for (int i = 0; i < this.palette.length; i++) {
            int x = startX + (i % columns) * (swatch + gap);
            int rowY = startY + (i / columns) * 22;
            if (isInside(mouseX, mouseY, x, rowY, x + swatch, rowY + swatch)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInside(mouseX, mouseY, this.previewLeft, previewViewportTop(), this.previewRight, previewViewportBottom())) {
            int max = maxPreviewScrollOffset();
            this.previewScrollOffset = Math.max(0, Math.min(max, this.previewScrollOffset - (int) Math.signum(amount) * 18));
            return true;
        }
        if (isInside(mouseX, mouseY, this.toolsLeft, toolsViewportTop(), this.toolsRight, toolsViewportBottom())) {
            int max = maxToolsScrollOffset();
            this.toolsScrollOffset = Math.max(0, Math.min(max, this.toolsScrollOffset - (int) Math.signum(amount) * 18));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.colorPickerOpen) {
            return handleColorPickerDrag(mouseX, mouseY);
        }
        if (this.draggingPreviewScrollbar) {
            updatePreviewScrollFromThumb(mouseY);
            return true;
        }
        if (this.draggingToolsScrollbar) {
            updateToolsScrollFromThumb(mouseY);
            return true;
        }
        if (button == 0 && isInside(mouseX, mouseY, this.canvasX, this.canvasY, this.canvasX + this.canvasSize, this.canvasY + this.canvasSize)) {
            if (this.shapeActive && isShapeTool()) {
                updateShape(mouseX, mouseY);
            } else {
                applyToolAt(mouseX, mouseY, true);
            }
            return true;
        }
        if (button == 0 && isInside(mouseX, mouseY, this.previewLeft, previewViewportTop(), this.previewRight, previewViewportBottom())) {
            if (hasShiftDown()) {
                this.modelRoll = SkinModelRenderer.wrap(this.modelRoll + (float) deltaX * 0.75F);
            } else {
                this.modelYaw = SkinModelRenderer.wrap(this.modelYaw + (float) deltaX * 0.75F);
                this.modelPitch = SkinModelRenderer.clampPitch(this.modelPitch + (float) deltaY * 0.75F);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.colorPickerOpen) {
            if (this.sharedColorPickerPopup != null) {
                this.sharedColorPickerPopup.mouseReleased();
            }
            return true;
        }
        if (this.draggingPreviewScrollbar) {
            this.draggingPreviewScrollbar = false;
            return true;
        }
        if (this.draggingToolsScrollbar) {
            this.draggingToolsScrollbar = false;
            return true;
        }
        if (button == 0 && this.shapeActive && isShapeTool()) {
            updateShape(mouseX, mouseY);
            commitShape();
            return true;
        }
        if (button == 0 && this.strokeActive) {
            pushUndo(true);
        }
        this.strokeActive = false;
        this.lastDrawPixelX = -1;
        this.lastDrawPixelY = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.colorPickerOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.colorPickerOpen = false;
                this.sharedColorPickerPopup = null;
                return true;
            }
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_Z) {
            playClick();
            undo();
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_Y) {
            playClick();
            redo();
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int index = keyCode - GLFW.GLFW_KEY_1;
            if (index < Tool.values().length) {
                playClick();
                this.tool = Tool.values()[index];
                this.shapeActive = false;
                setStatus("Selected " + this.tool.label + ".");
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_0 && Tool.values().length >= 10) {
            playClick();
            this.tool = Tool.values()[9];
            this.shapeActive = false;
            setStatus("Selected " + this.tool.label + ".");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET) {
            playClick();
            this.brushSize = Math.max(1, this.brushSize - 1);
            setStatus("Brush size " + this.brushSize + ".");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
            playClick();
            this.brushSize = Math.min(8, this.brushSize + 1);
            setStatus("Brush size " + this.brushSize + ".");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G) {
            playClick();
            this.showGrid = !this.showGrid;
            setStatus("Grid guide " + (this.showGrid ? "enabled" : "disabled") + ".");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_U) {
            playClick();
            this.showUvGuide = !this.showUvGuide;
            setStatus("UV guide " + (this.showUvGuide ? "enabled" : "disabled") + ".");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_B) {
            playClick();
            this.showBaseLayer = !this.showBaseLayer;
            setStatus("Base layer visibility " + (this.showBaseLayer ? "enabled" : "disabled") + ".");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_O) {
            playClick();
            this.showOverlayLayer = !this.showOverlayLayer;
            setStatus("Overlay layer visibility " + (this.showOverlayLayer ? "enabled" : "disabled") + ".");
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    private enum Tool {
        PENCIL("Pencil"),
        ERASER("Eraser"),
        PICKER("Picker"),
        FILL("Fill"),
        LINE("Line"),
        RECT_FILL("Rect Fill"),
        RECT_OUTLINE("Rect Edge"),
        LIGHTEN("Lighten"),
        DARKEN("Darken"),
        MIRROR("Mirror");

        private final String label;

        Tool(String label) {
            this.label = label;
        }
    }
}
