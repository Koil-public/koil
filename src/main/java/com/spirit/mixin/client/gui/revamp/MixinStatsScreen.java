package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.stats.global.*;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatHandler;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.*;
import java.util.List;
import java.util.*;

import static com.spirit.koil.api.design.uiColorVal.*;
import static net.minecraft.client.gui.screen.StatsListener.PROGRESS_BAR_STAGES;

@Environment(EnvType.CLIENT)
@Mixin(StatsScreen.class)
public abstract class MixinStatsScreen extends Screen {
    @Unique private static final Text KOIL_DOWNLOADING_STATS_TEXT = Text.translatable("multiplayer.downloadingStats");
    @Unique private static final String KOIL_PAGE_GENERAL = "general";
    @Unique private static final String KOIL_PAGE_ITEMS = "items";
    @Unique private static final String KOIL_PAGE_MOBS = "mobs";
    @Unique private static final String KOIL_PAGE_GLOBAL = "global";
    @Unique private static final int KOIL_HEADER_HEIGHT = 36;
    @Unique private static final int KOIL_FOOTER_HEIGHT = 35;
    @Unique private static final int KOIL_CONTENT_INSET = 35;
    @Unique private static final Identifier KOIL_PLAY_TIME_ID = new Identifier("minecraft", "play_time");
    @Unique private static final Identifier KOIL_DEATHS_ID = new Identifier("minecraft", "deaths");
    @Unique private static final Identifier KOIL_MOB_KILLS_ID = new Identifier("minecraft", "mob_kills");
    @Unique private static final Identifier KOIL_PLAYER_KILLS_ID = new Identifier("minecraft", "player_kills");
    @Unique private static final Identifier KOIL_DAMAGE_DEALT_ID = new Identifier("minecraft", "damage_dealt");
    @Unique private static final Identifier KOIL_DAMAGE_TAKEN_ID = new Identifier("minecraft", "damage_taken");
    @Unique private static final Identifier KOIL_JUMP_ID = new Identifier("minecraft", "jump");
    @Unique private static final Identifier KOIL_WALK_ONE_CM_ID = new Identifier("minecraft", "walk_one_cm");
    @Unique private static final Identifier KOIL_CROUCH_ONE_CM_ID = new Identifier("minecraft", "crouch_one_cm");
    @Unique private static final Identifier KOIL_SPRINT_ONE_CM_ID = new Identifier("minecraft", "sprint_one_cm");
    @Unique private static final Identifier KOIL_SWIM_ONE_CM_ID = new Identifier("minecraft", "swim_one_cm");
    @Unique private static final Identifier KOIL_FALL_ONE_CM_ID = new Identifier("minecraft", "fall_one_cm");
    @Unique private static final Identifier KOIL_CLIMB_ONE_CM_ID = new Identifier("minecraft", "climb_one_cm");
    @Unique private static final Identifier KOIL_FLY_ONE_CM_ID = new Identifier("minecraft", "fly_one_cm");
    @Unique private static final Identifier KOIL_WALK_ON_WATER_ONE_CM_ID = new Identifier("minecraft", "walk_on_water_one_cm");
    @Unique private static final Identifier KOIL_WALK_UNDER_WATER_ONE_CM_ID = new Identifier("minecraft", "walk_under_water_one_cm");
    @Unique private static final Identifier KOIL_MINECART_ONE_CM_ID = new Identifier("minecraft", "minecart_one_cm");
    @Unique private static final Identifier KOIL_BOAT_ONE_CM_ID = new Identifier("minecraft", "boat_one_cm");
    @Unique private static final Identifier KOIL_PIG_ONE_CM_ID = new Identifier("minecraft", "pig_one_cm");
    @Unique private static final Identifier KOIL_HORSE_ONE_CM_ID = new Identifier("minecraft", "horse_one_cm");
    @Unique private static final Identifier KOIL_AVIATE_ONE_CM_ID = new Identifier("minecraft", "aviate_one_cm");
    @Unique private static final Identifier KOIL_STRIDER_ONE_CM_ID = new Identifier("minecraft", "strider_one_cm");

    @Shadow @Final StatHandler statHandler;
    @Shadow private boolean downloadingStats;

    @Unique private final Map<ClickableWidget, int[]> koil$originalWidgetPositions = new IdentityHashMap<>();
    @Unique private final Map<String, Boolean> koil$expandedGlobalRows = new HashMap<>();
    @Unique private final Map<String, int[]> koil$globalRowRects = new HashMap<>();
    @Unique private final Map<String, Boolean> koil$expandedGlobalGroups = new HashMap<>();
    @Unique private final Map<String, int[]> koil$globalGroupHeaderRects = new HashMap<>();
    @Unique private final Map<String, int[]> koil$stockFilterRects = new LinkedHashMap<>();
    @Unique private final Map<String, int[]> koil$stockDropdownOptionRects = new LinkedHashMap<>();
    @Unique private int koil$statsScrollOffset;
    @Unique private int koil$statsScrollMax;
    @Unique private int koil$statsViewportX = -1;
    @Unique private int koil$statsViewportY = -1;
    @Unique private int koil$statsViewportWidth;
    @Unique private int koil$statsViewportHeight;
    @Unique private boolean koil$statsScrollbarDragging;
    @Unique private int koil$statsScrollbarDragOffset;
    @Unique private int koil$statsFooterTop = -1;
    @Unique private String koil$activePage = KOIL_PAGE_GENERAL;
    @Unique private ButtonWidget koil$globalButton;
    @Unique private TextFieldWidget koil$searchBox;
    @Unique private List<Text> koil$hoverTooltipLines = Collections.emptyList();
    @Unique private long koil$rowCacheTimeMs;
    @Unique private String koil$rowCacheKey = "";
    @Unique private List<Object[]> koil$cachedGeneralRows = Collections.emptyList();
    @Unique private List<Object[]> koil$cachedItemRows = Collections.emptyList();
    @Unique private List<Object[]> koil$cachedEntityRows = Collections.emptyList();
    @Unique private List<KoilGlobalActivityRow> koil$cachedMarketRows = Collections.emptyList();
    @Unique private List<KoilGlobalActivityRow> koil$cachedImportantRows = Collections.emptyList();
    @Unique private List<KoilGlobalActivityRow> koil$cachedDeveloperRows = Collections.emptyList();
    @Unique private List<KoilGlobalMarketViewRow> koil$cachedHeadMarketRows = Collections.emptyList();
    @Unique private List<KoilGlobalMarketViewRow> koil$cachedSubMarketRows = Collections.emptyList();
    @Unique private List<KoilGlobalMarketViewRow> koil$cachedRawMarketRows = Collections.emptyList();
    @Unique private List<KoilGlobalMarketViewRow> koil$cachedDevMarketRows = Collections.emptyList();
    @Unique private List<KoilGlobalMarketViewRow> koil$cachedAllMarketValueRows = Collections.emptyList();
    @Unique private Map<String, KoilGlobalActivityRow> koil$cachedGlobalRowsByMetric = Collections.emptyMap();
    @Unique private Map<String, KoilMarketValueQuote> koil$cachedMarketQuotes = Collections.emptyMap();
    @Unique private Map<String, List<KoilGlobalActivityRow>> koil$cachedMarketComponents = Collections.emptyMap();
    @Unique private Map<String, int[]> koil$cachedMarketPressureSeries = Collections.emptyMap();
    @Unique private Map<String, List<Object[]>> koil$cachedSyntheticSeries = Collections.emptyMap();
    @Unique private Map<String, int[]> koil$cachedMiniPreviewSeries = Collections.emptyMap();
    @Unique private List<Object[]> koil$cachedOverviewCards = Collections.emptyList();
    @Unique private String koil$generalCacheKey = "";
    @Unique private String koil$itemCacheKey = "";
    @Unique private String koil$entityCacheKey = "";
    @Unique private String koil$globalCacheKey = "";
    @Unique private long koil$lastFingerprintScanMs;
    @Unique private int koil$cachedLiveVanillaStatsFingerprint = Integer.MIN_VALUE;
    @Unique private int koil$cachedGeneralCount;
    @Unique private int koil$cachedItemCount;
    @Unique private int koil$cachedEntityCount;
    @Unique private int koil$cachedGlobalCount;
    @Unique private int koil$cachedGeneralMax = 1;
    @Unique private int koil$cachedItemMax = 1;
    @Unique private int koil$cachedEntityMax = 1;
    @Unique private long koil$lastLiveVanillaStatsRequestMs;
    @Unique private long koil$lastGlobalSnapshotRequestMs;
    @Unique private String koil$stockGraphInterval = KoilMarketSeriesWindow.activeKey();
    @Unique private int koil$stockDropdownX = -1;
    @Unique private int koil$stockDropdownY = -1;
    @Unique private int koil$stockDropdownWidth = 0;
    @Unique private int koil$stockDropdownHeight = 20;
    @Unique private int koil$stockDropdownPopupX = -1;
    @Unique private int koil$stockDropdownPopupY = -1;
    @Unique private boolean koil$stockDropdownOpen;
    @Unique private int koil$lastLiveVanillaStatsFingerprint = Integer.MIN_VALUE;

    protected MixinStatsScreen(Text title) {
        super(title);
    }

    @Shadow
    @Nullable
    public abstract AlwaysSelectedEntryListWidget<?> getSelectedStatList();

    @Inject(method = "createButtons", at = @At("TAIL"))
    private void koil$addGlobalButton(CallbackInfo info) {
        if (this.koil$searchBox == null) {
            this.koil$searchBox = this.addDrawableChild(new TextFieldWidget(this.textRenderer, this.width - 210, 8, 176, 20, Text.literal("Search")));
            this.koil$searchBox.setMaxLength(128);
            this.koil$searchBox.setChangedListener(value -> {
                this.koil$statsScrollOffset = 0;
                koil$invalidateRowCache();
            });
        }

        if (this.koil$globalButton != null) {
            return;
        }

        this.koil$globalButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Global"), button -> {
            this.koil$activePage = KOIL_PAGE_GLOBAL;
            this.koil$statsScrollOffset = 0;
            this.koil$statsScrollbarDragging = false;
            koil$invalidateRowCache();
        }).dimensions(this.width / 2 + 126, this.height - 52, 80, 20).build());
    }

    @Inject(method = "shouldPause", at = @At("HEAD"), cancellable = true)
    private void koil$statsShouldNotPause(CallbackInfoReturnable<Boolean> info) {
        if (koil$isKoilRedesignEnabled()) {
            info.setReturnValue(false);
        }
    }

    @Inject(method = "selectStatList", at = @At("TAIL"))
    private void koil$captureSelectedStatList(@Nullable AlwaysSelectedEntryListWidget<?> list, CallbackInfo info) {
        String detectedPage = koil$pageFromList(list);

        if (!detectedPage.isBlank()) {
            this.koil$activePage = detectedPage;
            this.koil$statsScrollOffset = 0;
            this.koil$statsScrollbarDragging = false;
            koil$invalidateRowCache();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void koil$renderStatsRedesign(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (!koil$isKoilRedesignEnabled()) {
            koil$restoreVanillaWidgetPositions();
            if (this.koil$searchBox != null) {
                this.koil$searchBox.visible = false;
            }
            this.koil$statsScrollbarDragging = false;
            return;
        }

        if (this.koil$searchBox != null) {
            this.koil$searchBox.visible = true;
        }
        koil$positionRedesignButtons();
        koil$positionSearchBox();
        koil$positionStockDropdown();
        koil$renderFrameOnly(context);
        KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Statistics"), null);

        if (this.downloadingStats) {
            koil$renderDownloadingStats(context);
        } else {
            koil$requestLiveVanillaStatsIfNeeded();
            koil$renderStatsPanel(context, MinecraftClient.getInstance(), mouseX, mouseY);
            koil$renderRedesignChildren(context, mouseX, mouseY, delta);
            koil$renderStockDropdown(context, mouseX, mouseY);
            koil$renderStockDropdownOptions(context, mouseX, mouseY);
            koil$renderHoverTooltip(context, mouseX, mouseY);
        }

        info.cancel();
    }

    @Unique
    private boolean koil$isKoilRedesignEnabled() {
        try {
            return JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Unique
    private void koil$renderFrameOnly(DrawContext context) {
        int footerTop = Math.max(KOIL_HEADER_HEIGHT, this.height - KOIL_FOOTER_HEIGHT);
        int headerColor = koil$color(uiColorHeader);
        int headerStripe = koil$color(uiColorHeaderStripe);
        int footerColor = koil$color(uiColorFooter);
        int footerStripe = koil$color(uiColorFooterStripe);
        int panelColor = koil$color(uiColorContentBase);
        int borderColor = koil$color(uiColorBackgroundBorder);
        int stripeLeft = koil$color(uiColorContentStripeLeft);
        int stripeRight = koil$color(uiColorContentStripeRight);

        context.fill(0, 0, this.width, KOIL_HEADER_HEIGHT, headerColor);
        context.fill(0, KOIL_HEADER_HEIGHT - 3, this.width, KOIL_HEADER_HEIGHT, headerStripe);
        context.fill(0, footerTop, this.width, this.height, footerColor);
        context.fill(0, footerTop, this.width, Math.min(this.height, footerTop + 3), footerStripe);

        if (this.width > KOIL_CONTENT_INSET * 2 && footerTop > KOIL_HEADER_HEIGHT) {
            int left = KOIL_CONTENT_INSET;
            int right = this.width - KOIL_CONTENT_INSET;
            context.fill(left, KOIL_HEADER_HEIGHT, right, footerTop, panelColor);
            context.drawBorder(left, KOIL_HEADER_HEIGHT, right - left, footerTop - KOIL_HEADER_HEIGHT, borderColor);
            context.fill(left + 2, KOIL_HEADER_HEIGHT, left + 4, footerTop, stripeLeft);
            context.fill(right - 4, KOIL_HEADER_HEIGHT, right - 2, footerTop, stripeRight);
        }
    }

    @Unique
    private void koil$positionRedesignButtons() {
        List<ClickableWidget> widgets = new ArrayList<>();

        for (Element child : this.children()) {
            if (!(child instanceof ClickableWidget widget) || !widget.visible || widget == this.koil$searchBox) {
                continue;
            }

            this.koil$originalWidgetPositions.putIfAbsent(widget, new int[]{widget.getX(), widget.getY()});
            widgets.add(widget);
        }

        if (widgets.isEmpty()) {
            this.koil$statsFooterTop = this.height - KOIL_FOOTER_HEIGHT;
            return;
        }

        if (this.koil$globalButton != null) {
            this.koil$globalButton.active = !KOIL_PAGE_GLOBAL.equals(this.koil$activePage);
        }

        int gap = 8;
        int maxRowWidth = Math.max(120, this.width - 76);
        List<List<ClickableWidget>> rows = new ArrayList<>();
        List<ClickableWidget> currentRow = new ArrayList<>();
        int currentWidth = 0;

        for (ClickableWidget widget : widgets) {
            int nextWidth = currentRow.isEmpty() ? widget.getWidth() : currentWidth + gap + widget.getWidth();

            if (!currentRow.isEmpty() && nextWidth > maxRowWidth) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentWidth = 0;
            }

            currentRow.add(widget);
            currentWidth = currentWidth == 0 ? widget.getWidth() : currentWidth + gap + widget.getWidth();
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        int rowHeight = 20;

        for (ClickableWidget widget : widgets) {
            rowHeight = Math.max(rowHeight, widget.getHeight());
        }

        int totalHeight = rows.size() * rowHeight + Math.max(0, rows.size() - 1) * gap;
        int footerTop = Math.max(KOIL_HEADER_HEIGHT, this.height - KOIL_FOOTER_HEIGHT);
        int startY = Math.max(footerTop + 7, this.height - 7 - totalHeight);
        this.koil$statsFooterTop = footerTop;

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<ClickableWidget> row = rows.get(rowIndex);
            int rowWidth = -gap;

            for (ClickableWidget widget : row) {
                rowWidth += widget.getWidth() + gap;
            }

            int x = Math.max(8, (this.width - rowWidth) / 2);
            int y = startY + rowIndex * (rowHeight + gap);

            for (ClickableWidget widget : row) {
                widget.setX(x);
                widget.setY(y);
                x += widget.getWidth() + gap;
            }
        }
    }

    @Unique
    private void koil$positionSearchBox() {
        if (this.koil$searchBox == null) {
            return;
        }

        int boxWidth = Math.min(180, Math.max(96, this.width / 4));
        this.koil$searchBox.setX(this.width - boxWidth - 35);
        this.koil$searchBox.setY(8);
        this.koil$searchBox.setWidth(boxWidth);
    }

    @Unique
    private void koil$positionStockDropdown() {
        this.koil$stockGraphInterval = KoilMarketSeriesWindow.activeKey();
        int labelWidth = this.textRenderer == null ? 116 : this.textRenderer.getWidth(koil$stockDropdownLabel()) + 14;
        int buttonWidth = Math.max(92, labelWidth);
        int searchX = this.koil$searchBox == null ? this.width - 215 : this.koil$searchBox.getX();
        this.koil$stockDropdownWidth = Math.min(142, buttonWidth);
        this.koil$stockDropdownHeight = 20;
        this.koil$stockDropdownX = Math.max(8, searchX - this.koil$stockDropdownWidth - 6);
        this.koil$stockDropdownY = 8;
    }

    @Unique
    private String koil$stockDropdownLabel() {
        return "Range: " + KoilMarketSeriesWindow.label(this.koil$stockGraphInterval);
    }

    @Unique
    private void koil$renderStockDropdown(DrawContext context, int mouseX, int mouseY) {
        if (!KOIL_PAGE_GLOBAL.equals(this.koil$activePage) || this.koil$stockDropdownX < 0 || this.koil$stockDropdownWidth <= 0) {
            this.koil$stockDropdownOpen = false;
            this.koil$stockDropdownPopupX = -1;
            this.koil$stockDropdownPopupY = -1;
            return;
        }

        boolean hovered = koil$isMouseInsideRawRect(mouseX, mouseY, this.koil$stockDropdownX, this.koil$stockDropdownY, this.koil$stockDropdownWidth, this.koil$stockDropdownHeight);
        int fill = hovered || this.koil$stockDropdownOpen ? koil$withAlpha(uiColorContentBase, 245) : koil$color(uiColorContentBase);
        int border = koil$color(uiColorBackgroundBorder);
        int text = koil$color(uiColorContentBaseTitleText);
        String label = koil$ellipsize(koil$stockDropdownLabel(), this.koil$stockDropdownWidth - 14);
        context.fill(this.koil$stockDropdownX, this.koil$stockDropdownY, this.koil$stockDropdownX + this.koil$stockDropdownWidth, this.koil$stockDropdownY + this.koil$stockDropdownHeight, fill);
        context.drawBorder(this.koil$stockDropdownX, this.koil$stockDropdownY, this.koil$stockDropdownWidth, this.koil$stockDropdownHeight, border);
        context.drawText(this.textRenderer, Text.literal(label), this.koil$stockDropdownX + 7, this.koil$stockDropdownY + 6, text, false);
    }

    @Unique
    private String[] koil$stockFilterKeys() {
        return new String[]{KoilMarketSeriesWindow.TODAY, KoilMarketSeriesWindow.THREE_DAYS, KoilMarketSeriesWindow.SEVEN_DAYS, KoilMarketSeriesWindow.ALL};
    }

    @Unique
    private void koil$renderStockDropdownOptions(DrawContext context, int mouseX, int mouseY) {
        this.koil$stockDropdownOptionRects.clear();

        if (!KOIL_PAGE_GLOBAL.equals(this.koil$activePage) || !this.koil$stockDropdownOpen || this.koil$stockDropdownX < 0) {
            return;
        }

        String[] keys = koil$stockFilterKeys();
        int menuWidth = this.koil$stockDropdownWidth;

        for (String key : keys) {
            menuWidth = Math.max(menuWidth, this.textRenderer.getWidth(KoilMarketSeriesWindow.label(key)) + 16);
        }

        menuWidth = Math.min(164, Math.max(92, menuWidth));
        int rowHeight = 18;
        int menuHeight = keys.length * rowHeight + 8;
        int anchorX = this.koil$stockDropdownPopupX >= 0 ? this.koil$stockDropdownPopupX : this.koil$stockDropdownX;
        int anchorY = this.koil$stockDropdownPopupY >= 0 ? this.koil$stockDropdownPopupY : this.koil$stockDropdownY + this.koil$stockDropdownHeight + 3;
        int menuX = Math.max(8, Math.min(anchorX, this.width - menuWidth - 8));
        int menuY = Math.max(8, Math.min(anchorY, this.height - menuHeight - 8));

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 4096.0F);
        context.fill(menuX + 2, menuY + 3, menuX + menuWidth + 3, menuY + menuHeight + 4, 0x66000000);
        context.fill(menuX, menuY, menuX + menuWidth, menuY + menuHeight, koil$withAlpha(uiColorContentBase, 245));
        context.drawBorder(menuX, menuY, menuWidth, menuHeight, koil$color(uiColorBackgroundBorder));

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            String normalized = KoilMarketSeriesWindow.normalize(key);
            String label = KoilMarketSeriesWindow.label(key);
            int rowY = menuY + 4 + i * rowHeight;
            int rowText = koil$color(uiColorContentBaseTitleText);

            context.drawText(this.textRenderer, Text.literal(label), menuX + 7, rowY + 5, rowText, false);
            this.koil$stockDropdownOptionRects.put(normalized, new int[]{menuX + 1, rowY, menuWidth - 2, rowHeight});
        }

        context.getMatrices().pop();
    }

    @Unique
    private boolean koil$applyStockDropdownSelection(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        this.koil$stockGraphInterval = KoilMarketSeriesWindow.normalize(key);
        KoilMarketSeriesWindow.setActiveKey(this.koil$stockGraphInterval);
        this.koil$stockDropdownOpen = false;
        this.koil$stockDropdownPopupX = -1;
        this.koil$stockDropdownPopupY = -1;
        this.koil$statsScrollOffset = 0;
        koil$invalidateRowCache();
        return true;
    }

    @Unique
    private void koil$restoreVanillaWidgetPositions() {
        for (Map.Entry<ClickableWidget, int[]> entry : this.koil$originalWidgetPositions.entrySet()) {
            ClickableWidget widget = entry.getKey();
            int[] position = entry.getValue();
            widget.setX(position[0]);
            widget.setY(position[1]);
        }
    }

    @Unique
    private void koil$renderRedesignChildren(DrawContext context, int mouseX, int mouseY, float delta) {
        for (Element child : this.children()) {
            if (child instanceof AlwaysSelectedEntryListWidget) {
                continue;
            }

            if (child instanceof Drawable drawable) {
                drawable.render(context, mouseX, mouseY, delta);
            }
        }
    }

    @Unique
    private void koil$renderDownloadingStats(DrawContext context) {
        int x = KOIL_CONTENT_INSET + 12;
        int y = KOIL_HEADER_HEIGHT + 24;
        int width = Math.max(120, this.width - KOIL_CONTENT_INSET * 2 - 24);
        String stage = PROGRESS_BAR_STAGES[(int) (Util.getMeasuringTimeMs() / 150L % PROGRESS_BAR_STAGES.length)];

        context.drawCenteredTextWithShadow(this.textRenderer, KOIL_DOWNLOADING_STATS_TEXT, this.width / 2, y + 6, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, stage, this.width / 2, y + 24, 0xFFB8C7D6);

        int barX = x + 12;
        int barY = y + 44;
        int barWidth = width - 24;
        int filled = (int) ((Util.getMeasuringTimeMs() % 1600L) / 1600.0F * barWidth);

        context.fill(barX, barY, barX + barWidth, barY + 4, 0x88000000);
        context.fill(barX, barY, barX + Math.max(6, filled), barY + 4, 0xFF8FC5FF);
    }


    @Unique
    private void koil$requestGlobalSnapshotIfNeeded() {
        if (!KOIL_PAGE_GLOBAL.equals(this.koil$activePage)) {
            return;
        }

        long now = Util.getMeasuringTimeMs();

        if (now - this.koil$lastGlobalSnapshotRequestMs < 1500L) {
            return;
        }

        this.koil$lastGlobalSnapshotRequestMs = now;
        GlobalActivityClient.requestServerSnapshot();
    }

    @Unique
    private void koil$renderStatsPanel(DrawContext context, MinecraftClient client, int mouseX, int mouseY) {
        this.koil$hoverTooltipLines = Collections.emptyList();
        koil$requestGlobalSnapshotIfNeeded();
        koil$refreshRowCache();
        int panelLeft = KOIL_CONTENT_INSET;
        int panelRight = this.width - KOIL_CONTENT_INSET;
        int panelTop = KOIL_HEADER_HEIGHT;
        int panelBottom = Math.max(panelTop + 80, this.koil$statsFooterTop);
        int contentX = panelLeft + 12;
        int contentWidth = Math.max(80, panelRight - panelLeft - 24);
        int cursorY = panelTop + 8;

        this.koil$globalRowRects.clear();
        this.koil$globalGroupHeaderRects.clear();
        this.koil$stockFilterRects.clear();
        this.koil$stockGraphInterval = KoilMarketSeriesWindow.activeKey();
        cursorY = koil$renderContextLine(context, contentX, cursorY, contentWidth, client);
        cursorY = koil$renderSummaryStrip(context, contentX, cursorY + 6, contentWidth);
        cursorY = koil$renderSectionHeader(context, contentX, cursorY + 7, contentWidth);

        this.koil$statsViewportX = contentX;
        this.koil$statsViewportY = cursorY + 5;
        this.koil$statsViewportWidth = contentWidth;
        this.koil$statsViewportHeight = Math.max(34, panelBottom - 8 - this.koil$statsViewportY);

        context.enableScissor(this.koil$statsViewportX, this.koil$statsViewportY, this.koil$statsViewportX + this.koil$statsViewportWidth, this.koil$statsViewportY + this.koil$statsViewportHeight);

        int contentHeight;

        if (KOIL_PAGE_ITEMS.equals(this.koil$activePage)) {
            contentHeight = koil$renderItemRows(context, this.koil$statsViewportX, this.koil$statsViewportY - this.koil$statsScrollOffset, this.koil$statsViewportWidth - 8, mouseX, mouseY);
        } else if (KOIL_PAGE_MOBS.equals(this.koil$activePage)) {
            contentHeight = koil$renderEntityRows(context, this.koil$statsViewportX, this.koil$statsViewportY - this.koil$statsScrollOffset, this.koil$statsViewportWidth - 8, mouseX, mouseY);
        } else if (KOIL_PAGE_GLOBAL.equals(this.koil$activePage)) {
            contentHeight = koil$renderGlobalRows(context, this.koil$statsViewportX, this.koil$statsViewportY - this.koil$statsScrollOffset, this.koil$statsViewportWidth - 8, mouseX, mouseY);
        } else {
            contentHeight = koil$renderGeneralRows(context, this.koil$statsViewportX, this.koil$statsViewportY - this.koil$statsScrollOffset, this.koil$statsViewportWidth - 8, mouseX, mouseY);
        }

        context.disableScissor();
        this.koil$statsScrollMax = Math.max(0, contentHeight - this.koil$statsViewportHeight);

        if (this.koil$statsScrollOffset > this.koil$statsScrollMax) {
            this.koil$statsScrollOffset = this.koil$statsScrollMax;
        }

        koil$renderStatsScrollbar(context);
    }

    @Unique
    private int koil$renderContextLine(DrawContext context, int x, int y, int width, MinecraftClient client) {
        String world = client == null || client.world == null ? "menu" : client.isInSingleplayer() ? "singleplayer" : "multiplayer";
        String globalState = GlobalActivityClient.hasServerData() ? "server synced" : GlobalActivityClient.hasObservedData() ? "client observed" : "waiting for activity";
        String line = "World: " + world + "  |  Context: " + GlobalActivityClient.contextKey() + "  |  Sources: " + koil$sourceSummary() + "  |  Global: " + globalState;
        String baseLine = KoilMarketValueEngine.baseExchangeLine(koil$allMarketValueRows());
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(line, width - 4)), x + 2, y, 0xFFB8C7D6, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(baseLine, width - 4)), x + 2, y + 11, 0xFFE8C86F, false);
        return y + 22;
    }

    @Unique
    private int koil$renderSummaryStrip(DrawContext context, int x, int y, int width) {
        List<Object[]> cards = koil$overviewCards();
        int columns = width < 560 ? 3 : 7;
        int gap = 5;
        int cellWidth = Math.max(42, (width - gap * (columns - 1)) / columns);
        int cellHeight = 29;

        for (int i = 0; i < cards.size(); i++) {
            Object[] card = cards.get(i);
            int column = i % columns;
            int row = i / columns;
            int cellX = x + column * (cellWidth + gap);
            int cellY = y + row * (cellHeight + gap);
            String name = (String) card[0];
            String value = (String) card[1];
            float progress = (Float) card[2];
            int color = (Integer) card[3];

            context.fill(cellX, cellY, cellX + cellWidth, cellY + cellHeight, 0x22000000);
            context.drawBorder(cellX, cellY, cellWidth, cellHeight, 0x443D4A56);
            koil$drawSummaryIcon(context, name, cellX + 3, cellY + 6);
            context.drawText(this.textRenderer, Text.literal(koil$ellipsize(name, cellWidth - 25)), cellX + 22, cellY + 4, 0xFFE7EDF4, false);
            context.drawText(this.textRenderer, Text.literal(koil$ellipsize(value, cellWidth - 25)), cellX + 22, cellY + 15, color, false);
            koil$drawBar(context, cellX + 4, cellY + 25, cellWidth - 8, 2, progress, color);
        }

        int rows = (int) Math.ceil(cards.size() / (float) columns);
        return y + rows * cellHeight + Math.max(0, rows - 1) * gap;
    }

    @Unique
    private int koil$renderSectionHeader(DrawContext context, int x, int y, int width) {
        String left = koil$pageTitle(this.koil$activePage);
        String right = koil$pageCount(this.koil$activePage);
        String hint = koil$pageHint(this.koil$activePage);

        context.fill(x, y, x + width, y + 24, 0x16000000);
        context.drawText(this.textRenderer, Text.literal(left), x + 3, y + 3, 0xFFE7EDF4, false);

        context.drawText(this.textRenderer, Text.literal(right), x + width - 3 - this.textRenderer.getWidth(right), y + 3, 0xFFE8C86F, false);

        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(hint, width - 6)), x + 3, y + 14, 0xFF8E9AA8, false);

        return y + 24;
    }

    @Unique
    private int koil$renderGeneralRows(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
        List<Object[]> rows = this.koil$cachedGeneralRows;
        int max = this.koil$cachedGeneralMax;
        int rowY = y;

        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            String label = (String) row[0];
            String formatted = (String) row[1];
            int raw = (Integer) row[2];
            String category = (String) row[3];
            int color = (Integer) row[4];
            String source = (String) row[5];
            int height = 29;

            if (!koil$isRenderRowVisible(rowY, height)) {
                rowY += height + 3;
                continue;
            }

            koil$drawLightRow(context, x, rowY, width, height, i);
            context.fill(x + 4, rowY + 5, x + 7, rowY + height - 5, color);
            context.drawText(this.textRenderer, Text.literal(koil$ellipsize(label, Math.max(70, width / 2))), x + 12, rowY + 4, 0xFFE7EDF4, false);
            context.drawText(this.textRenderer, Text.literal(koil$ellipsize(category + " | " + source, Math.max(70, width / 2))), x + 12, rowY + 15, 0xFF8E9AA8, false);
            context.drawText(this.textRenderer, Text.literal(formatted), x + width - 8 - this.textRenderer.getWidth(formatted), rowY + 4, 0xFFFFFFFF, false);
            koil$drawBar(context, x + width - Math.max(82, width / 4) - 8, rowY + 19, Math.max(72, width / 4), 3, raw / (float) max, color);

            if (koil$inside(mouseX, mouseY, x, rowY, width, height)) {
                koil$setHoverTooltip(
                        koil$tooltipLine(label, 0xFFE7EDF4),
                        koil$tooltipLine("Category: " + category, color),
                        koil$tooltipLine("Source: " + source, 0xFF8E9AA8),
                        koil$tooltipLine("Value: " + formatted, 0xFFFFFFFF)
                );
            }

            rowY += height + 3;
        }

        if (rows.isEmpty()) {
            koil$drawEmptyState(context, x, rowY, width, "No general statistics are available yet.");
            rowY += 38;
        }

        return rowY - y;
    }

    @Unique
    private int koil$renderItemRows(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
        List<Object[]> rows = this.koil$cachedItemRows;
        int max = this.koil$cachedItemMax;
        int rowY = y;

        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            Item item = (Item) row[0];
            String name = (String) row[1];
            int mined = (Integer) row[2];
            int used = (Integer) row[3];
            int crafted = (Integer) row[4];
            int pickedUp = (Integer) row[5];
            int dropped = (Integer) row[6];
            int broken = (Integer) row[7];
            int total = (Integer) row[8];
            String source = (String) row[9];
            int height = width < 430 ? 48 : 34;

            if (!koil$isRenderRowVisible(rowY, height)) {
                rowY += height + 3;
                continue;
            }

            koil$drawLightRow(context, x, rowY, width, height, i);
            context.fill(x + 4, rowY + 5, x + 7, rowY + height - 5, 0xFFE8C86F);
            context.drawItemWithoutEntity(item.getDefaultStack(), x + 11, rowY + 8);

            if (width < 430) {
                context.drawText(this.textRenderer, Text.literal(koil$ellipsize(name, width - 42)), x + 31, rowY + 4, 0xFFE7EDF4, false);
                context.drawText(this.textRenderer, Text.literal(koil$ellipsize(source + " | " + koil$itemDominantMetric(mined, used, crafted, pickedUp, dropped, broken), width - 42)), x + 31, rowY + 15, 0xFF8E9AA8, false);
                String detail = "T " + koil$compact(total) + "  M " + koil$compact(mined) + "  U " + koil$compact(used) + "  C " + koil$compact(crafted) + "  P " + koil$compact(pickedUp) + "  D " + koil$compact(dropped) + "  B " + koil$compact(broken);
                context.drawText(this.textRenderer, Text.literal(koil$ellipsize(detail, width - 42)), x + 31, rowY + 26, 0xFFC7D3E0, false);
                koil$drawMiniSpark(context, x + 31, rowY + 39, width - 39, 6, new int[]{mined, used, crafted, pickedUp, dropped, broken}, 0xFFE8C86F);
            } else {
                context.drawText(this.textRenderer, Text.literal(koil$ellipsize(name, Math.max(80, width - 328))), x + 31, rowY + 4, 0xFFE7EDF4, false);
                context.drawText(this.textRenderer, Text.literal(koil$ellipsize(source + " | " + koil$itemDominantMetric(mined, used, crafted, pickedUp, dropped, broken), Math.max(80, width - 328))), x + 31, rowY + 15, 0xFF8E9AA8, false);
                int startX = x + width - 276;
                koil$drawMetric(context, "M", mined, startX, rowY + 5, 0xFF8FC5FF);
                koil$drawMetric(context, "U", used, startX + 45, rowY + 5, 0xFFA6D989);
                koil$drawMetric(context, "C", crafted, startX + 90, rowY + 5, 0xFFE8C86F);
                koil$drawMetric(context, "P", pickedUp, startX + 135, rowY + 5, 0xFF8FD5D0);
                koil$drawMetric(context, "D", dropped, startX + 180, rowY + 5, 0xFFE6A15C);
                koil$drawMetric(context, "B", broken, startX + 225, rowY + 5, 0xFFE06A6A);
                koil$drawBar(context, x + 31, rowY + 27, width - 39, 3, total / (float) max, 0xFFE8C86F);
            }

            if (koil$inside(mouseX, mouseY, x, rowY, width, height)) {
                koil$setHoverTooltip(
                        koil$tooltipLine(name, 0xFFE7EDF4),
                        koil$tooltipLine("Total activity: " + total, 0xFFE8C86F),
                        koil$tooltipLine("Pattern: " + koil$itemDominantMetric(mined, used, crafted, pickedUp, dropped, broken), 0xFF8FD5D0),
                        koil$tooltipLine("Source: " + source, 0xFF8E9AA8)
                );
            }

            rowY += height + 3;
        }

        if (rows.isEmpty()) {
            koil$drawEmptyState(context, x, rowY, width, "No item or block statistics have been recorded yet.");
            rowY += 38;
        }

        return rowY - y;
    }

    @Unique
    private int koil$renderEntityRows(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
        List<Object[]> rows = this.koil$cachedEntityRows;
        int max = this.koil$cachedEntityMax;
        int rowY = y;

        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            String name = (String) row[0];
            int killed = (Integer) row[1];
            int killedBy = (Integer) row[2];
            String source = (String) row[3];
            int threatColor = koil$threatColor(killed, killedBy);
            int height = 38;

            if (!koil$isRenderRowVisible(rowY, height)) {
                rowY += height + 3;
                continue;
            }

            koil$drawLightRow(context, x, rowY, width, height, i);
            context.fill(x + 4, rowY + 5, x + 7, rowY + height - 5, threatColor);
            context.drawText(this.textRenderer, Text.literal(koil$ellipsize(name, Math.max(70, width - 230))), x + 12, rowY + 4, 0xFFE7EDF4, false);
            context.drawText(this.textRenderer, Text.literal(koil$ellipsize(source + " | " + koil$threatLabel(killed, killedBy), Math.max(70, width - 230))), x + 12, rowY + 15, 0xFF8E9AA8, false);
            context.drawText(this.textRenderer, Text.literal("K " + killed), x + width - 178, rowY + 5, 0xFFA6D989, false);
            context.drawText(this.textRenderer, Text.literal("D " + killedBy), x + width - 118, rowY + 5, threatColor, false);
            context.drawText(this.textRenderer, Text.literal("R " + koil$ratio(killed, killedBy)), x + width - 58, rowY + 5, 0xFFE8C86F, false);
            koil$drawDualBar(context, x + 12, rowY + 28, width - 20, killed / (float) max, killedBy / (float) max, 0xFFA6D989, threatColor);

            if (koil$inside(mouseX, mouseY, x, rowY, width, height)) {
                koil$setHoverTooltip(
                        koil$tooltipLine(name, 0xFFE7EDF4),
                        koil$tooltipLine("Kills: " + killed, 0xFFA6D989),
                        koil$tooltipLine("Killed by: " + killedBy, koil$threatColor(killed, killedBy)),
                        koil$tooltipLine("Ratio: " + koil$ratio(killed, killedBy), 0xFFE8C86F),
                        koil$tooltipLine("Source: " + source, 0xFF8E9AA8)
                );
            }

            rowY += height + 3;
        }

        if (rows.isEmpty()) {
            koil$drawEmptyState(context, x, rowY, width, "No mob statistics have been recorded yet.");
            rowY += 38;
        }

        return rowY - y;
    }

    @Unique
    private int koil$renderGlobalRows(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
        List<KoilGlobalMarketViewRow> head = this.koil$cachedHeadMarketRows;
        List<KoilGlobalMarketViewRow> sub = this.koil$cachedSubMarketRows;
        List<KoilGlobalMarketViewRow> raw = this.koil$cachedRawMarketRows;
        List<KoilGlobalMarketViewRow> dev = this.koil$cachedDevMarketRows;
        int rowY = y;

        if (head.isEmpty() && sub.isEmpty() && raw.isEmpty() && dev.isEmpty()) {
            return koil$renderGlobalEmpty(context, x, rowY, width) - y;
        }

        rowY = koil$renderGlobalMarketGroup(context, x, rowY, width, "head", "Head Markets", head.size() + " combined summary markets", "Combined category markets are open by default.", head, true, mouseX, mouseY);
        rowY += 4;
        rowY = koil$renderGlobalMarketGroup(context, x, rowY, width, "sub", "Sub Markets", sub.size() + " item/block/entity grouped markets", "Grouped markets stay hidden until opened.", sub, false, mouseX, mouseY);
        rowY += 4;
        rowY = koil$renderGlobalMarketGroup(context, x, rowY, width, "raw", "Raw Markets", raw.size() + " exact watched signals", "Exact signal markets stay hidden until opened.", raw, false, mouseX, mouseY);

        if (!dev.isEmpty()) {
            rowY += 4;
            rowY = koil$renderGlobalMarketGroup(context, x, rowY, width, "dev", "Dev Market", dev.size() + " unprocessed fallback signals", "Unclassified developer feed stays hidden until opened.", dev, false, mouseX, mouseY);
        }

        return rowY - y;
    }

    @Unique
    private int koil$renderGlobalMarketGroup(DrawContext context, int x, int y, int width, String groupKey, String title, String value, String emptyText, List<KoilGlobalMarketViewRow> rows, boolean defaultOpen, int mouseX, int mouseY) {
        boolean open = koil$isGlobalGroupOpen(groupKey, defaultOpen);
        int rowY = koil$drawGlobalSubHeader(context, x, y, width, groupKey, title, value, open);

        if (!open) {
            return rowY;
        }

        if (rows.isEmpty()) {
            koil$drawEmptyState(context, x, rowY, width, emptyText);
            return rowY + 38;
        }

        for (int i = 0; i < rows.size(); i++) {
            rowY = koil$renderGlobalMarketViewRow(context, x, rowY, width, rows.get(i), i, mouseX, mouseY);
        }

        return rowY;
    }

    @Unique
    private int koil$drawGlobalSubHeader(DrawContext context, int x, int y, int width, String groupKey, String title, String value, boolean open) {
        this.koil$globalGroupHeaderRects.put(groupKey, new int[]{x, y, width, 21});
        context.fill(x, y, x + width, y + 21, 0x18000000);
        context.fill(x + 2, y + 4, x + 5, y + 17, open ? 0xFF8FC5FF : 0x668FC5FF);
        context.drawText(this.textRenderer, Text.literal(title), x + 11, y + 6, open ? 0xFFE7EDF4 : 0xFF8E9AA8, false);
        String state = open ? "open" : "collapsed";
        String right = state + "  |  " + value;
        context.drawText(this.textRenderer, Text.literal(right), x + width - 4 - this.textRenderer.getWidth(right), y + 6, open ? 0xFFB8C7D6 : 0xFF718091, false);
        return y + 24;
    }

    @Unique
    private boolean koil$isGlobalGroupOpen(String groupKey, boolean defaultOpen) {
        return this.koil$expandedGlobalGroups.getOrDefault(groupKey, defaultOpen);
    }

    @Unique
    private int koil$renderGlobalMarketViewRow(DrawContext context, int x, int y, int width, KoilGlobalMarketViewRow view, int index, int mouseX, int mouseY) {
        KoilGlobalActivityRow row = view.chartRow();
        String key = view.tier() + ":" + view.id();
        boolean expanded = this.koil$expandedGlobalRows.getOrDefault(key, false);
        int height = expanded ? 432 : 46;

        if (!koil$isRenderRowVisible(y, height)) {
            return y + height + 4;
        }

        int color = koil$globalMarketDomainColor(view);
        KoilMarketValueQuote quote = koil$quoteForView(view);
        int changeColor = row.change() > 0 ? 0xFFA6D989 : row.change() < 0 ? 0xFFE06A6A : 0xFF8E9AA8;
        int iconX = x + 11;
        int titleX = x + 34;
        this.koil$globalRowRects.put(key, new int[]{x, y, width, 46});
        koil$drawLightRow(context, x, y, width, height, index);
        context.fill(x + 4, y + 5, x + 7, y + height - 5, color);
        koil$drawMarketViewIcon(context, view, iconX, y + 11);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(view.title(), Math.max(80, width - 320))), titleX, y + 4, 0xFFE7EDF4, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(view.description(), Math.max(80, width - 320))), titleX, y + 15, 0xFF8E9AA8, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(koil$marketDataSourcesLine(view), Math.max(80, width - 320))), titleX, y + 28, 0xFFB8C7D6, false);

        String value = "total " + koil$compact(view.value());
        String change = (row.change() >= 0 ? "+" : "") + row.change();
        String source = view.authoritative() ? "server" : "observed " + view.confidence() + "%";
        String momens = quote.compactMomens();
        String exchange = quote.compactExchange(view.subject());
        context.drawText(this.textRenderer, Text.literal(momens), x + width - 196, y + 4, 0xFFE8C86F, false);
        context.drawText(this.textRenderer, Text.literal(change), x + width - 116, y + 4, changeColor, false);
        context.drawText(this.textRenderer, Text.literal(source), x + width - 76, y + 4, view.authoritative() ? 0xFFA6D989 : 0xFFE6A15C, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(exchange, 190)), x + width - 196, y + 17, 0xFFB7A8FF, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(value + "  |  " + koil$marketTopInputsLine(view), 190)), x + width - 196, y + 30, 0xFF8FD5D0, false);

        if (expanded) {
            int chartX = x + 12;
            int chartY = y + 59;
            int dataWidth = width >= 620 ? Math.min(236, Math.max(198, width / 3)) : 0;
            int chartWidth = dataWidth > 0 ? Math.max(120, width - dataWidth - 32) : Math.max(90, width - 24);
            int chartHeight = dataWidth > 0 ? 246 : 254;
            koil$drawStockChart(context, chartX, chartY, chartWidth, chartHeight, row, view, color, mouseX, mouseY);

            if (dataWidth > 0) {
                int dataX = chartX + chartWidth + 8;
                koil$drawMarketValuePanel(context, dataX, chartY, dataWidth, view, quote, row, changeColor);
                koil$drawStockDataRibbon(context, dataX, chartY + 101, dataWidth, row, view, color, changeColor);
                koil$drawMarketComponentPanel(context, dataX, chartY + 209, dataWidth, view);
                int rankingY = chartY + chartHeight + 8;
                int rankingHeight = Math.max(56, y + height - rankingY - 10);
                koil$drawMarketRankingsPanel(context, chartX, rankingY, Math.max(90, width - 24), rankingHeight, view, quote, row);
            } else {
                koil$drawStockDataRibbon(context, chartX, chartY + chartHeight + 6, chartWidth, row, view, color, changeColor);
                koil$drawMarketComponentPanel(context, chartX, chartY + chartHeight + 56, chartWidth, view);
            }
        } else {
            koil$drawMiniStockPreview(context, x + width - 196, y + 34, 184, 8, koil$miniPreviewSeriesForView(view), color);
        }

        if (koil$inside(mouseX, mouseY, x, y, width, 46) && this.koil$hoverTooltipLines.isEmpty()) {
            List<Text> lines = new ArrayList<>();
            lines.add(koil$tooltipLine(view.title(), 0xFFE7EDF4));
            lines.add(koil$tooltipLine("Tier: " + koil$tierLabel(view.tier()) + "  |  Domain: " + view.domain(), color));
            lines.add(koil$tooltipLine(view.description(), 0xFFB8C7D6));
            lines.add(koil$tooltipLine(view.authoritative() ? "Source: server exact, per world/server" : "Source: client observed, per world/server", view.authoritative() ? 0xFFA6D989 : 0xFFE6A15C));
            lines.add(koil$tooltipLine("Confidence: " + view.confidence() + "%", view.authoritative() ? 0xFFA6D989 : 0xFFE6A15C));
            lines.add(koil$tooltipLine("Leader: " + view.leader() + " (" + view.leaderValue() + ")", 0xFF8FD5D0));
            lines.add(koil$tooltipLine("Total: " + view.value() + "   Change: " + koil$signed(row.change()), row.change() >= 0 ? 0xFFA6D989 : 0xFFE06A6A));
            lines.add(koil$tooltipLine("Momens value: " + quote.compactMomens(), 0xFFE8C86F));
            lines.add(koil$tooltipLine(quote.longExchange(view.subject()), 0xFFB7A8FF));
            lines.add(koil$tooltipLine(quote.scoreSummary(), 0xFF8FD5D0));
            lines.add(koil$tooltipLine("Inputs: " + Math.max(0, view.rawMetrics().size()) + " raw signals", 0xFFE8C86F));

            for (String component : view.componentTooltipLines(10)) {
                int lineColor = component.contains("Broken") || component.contains("Damage") || component.contains("Deaths") ? 0xFFE06A6A : component.contains("Placed") || component.contains("Used") ? 0xFFA6D989 : component.contains("Crafted") || component.contains("Traded") ? 0xFFE8C86F : 0xFFB8C7D6;
                lines.add(koil$tooltipLine(component, lineColor));
            }

            for (String sourceLine : koil$marketRawSourceTooltipLines(view, 6)) {
                lines.add(koil$tooltipLine(sourceLine, 0xFF8E9AA8));
            }

            lines.add(koil$tooltipLine("Click to " + (expanded ? "collapse" : "expand"), 0xFFB8C7D6));
            koil$setHoverTooltip(lines.toArray(new Text[0]));
        }

        return y + height + 4;
    }

    @Unique
    private void koil$drawMarketComponentPanel(DrawContext context, int x, int y, int width, KoilGlobalMarketViewRow view) {
        KoilMarketValueQuote quote = koil$quoteForView(view);
        int panelHeight = 48;
        context.fill(x, y, x + width, y + panelHeight, 0x12000000);
        context.drawBorder(x, y, width, panelHeight, 0x223D4A56);
        context.drawText(this.textRenderer, Text.literal("market inputs + Momens value"), x + 5, y + 4, 0xFF718091, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(view.compactComponentSummary(), width - 10)), x + 5, y + 15, 0xFFE7EDF4, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(quote.compactMomens() + "  |  " + quote.compactExchange(view.subject()) + "  |  " + koil$marketDataSourcesLine(view), width - 10)), x + 5, y + 26, 0xFF8FD5D0, false);
        String footer = "raw signals: " + view.rawMetrics().size() + "  |  hover candles, lines, averages, components, and Momens value data";
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(footer, width - 10)), x + 5, y + 37, 0xFF8E9AA8, false);
    }

    @Unique
    private void koil$drawMarketValuePanel(DrawContext context, int x, int y, int width, KoilGlobalMarketViewRow view, KoilMarketValueQuote quote, KoilGlobalActivityRow row, int changeColor) {
        int panelHeight = 92;
        context.fill(x, y, x + width, y + panelHeight, 0x16000000);
        context.drawBorder(x, y, width, panelHeight, 0x223D4A56);
        context.drawText(this.textRenderer, Text.literal("live market value"), x + 5, y + 4, 0xFF718091, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(quote.compactMomens(), width - 10)), x + 5, y + 16, 0xFFE8C86F, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(quote.compactExchange(view.subject()), width - 10)), x + 5, y + 28, 0xFFB7A8FF, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize("reserve: " + quote.reserveTitle(), width - 10)), x + 5, y + 40, 0xFF8FD5D0, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(quote.oneMomenReserveLine(), width - 10)), x + 5, y + 52, 0xFFB8C7D6, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize("change: " + koil$signed(row.change()) + "  trend: " + koil$signed(row.trend()), width - 10)), x + 5, y + 64, changeColor, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize("source: " + (view.authoritative() ? "server exact" : "observed " + view.confidence() + "%"), width - 10)), x + 5, y + 76, view.authoritative() ? 0xFFA6D989 : 0xFFE6A15C, false);
    }

    @Unique
    private void koil$drawMarketRankingsPanel(DrawContext context, int x, int y, int width, int height, KoilGlobalMarketViewRow view, KoilMarketValueQuote quote, KoilGlobalActivityRow row) {
        int safeHeight = Math.max(44, height);
        context.fill(x, y, x + width, y + safeHeight, 0x14000000);
        context.drawBorder(x, y, width, safeHeight, 0x223D4A56);
        int gap = 6;
        int columns = width >= 560 ? 3 : 2;
        int columnWidth = Math.max(74, (width - gap * (columns - 1)) / columns);
        koil$drawMarketRankingColumn(context, x + 5, y + 5, columnWidth - 10, safeHeight - 10, "player ranking", koil$marketLeaderRankingLines(view, 5));
        koil$drawMarketRankingColumn(context, x + columnWidth + gap + 5, y + 5, columnWidth - 10, safeHeight - 10, "stat ranking", koil$marketStatRankingLines(view, 5));

        if (columns > 2) {
            koil$drawMarketRankingColumn(context, x + (columnWidth + gap) * 2 + 5, y + 5, columnWidth - 10, safeHeight - 10, "price checks", koil$marketScoreLines(view, quote, row, 5));
        }
    }

    @Unique
    private void koil$drawMarketRankingColumn(DrawContext context, int x, int y, int width, int height, String title, List<Object[]> rows) {
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize(title, width)), x, y, 0xFF718091, false);
        int rowY = y + 12;
        int limit = Math.max(1, Math.min(rows.size(), Math.max(1, (height - 12) / 11)));

        for (int i = 0; i < limit; i++) {
            Object[] row = rows.get(i);
            String label = String.valueOf(row[0]);
            String value = koil$ellipsize(String.valueOf(row[1]), Math.max(18, width / 2));
            int color = row.length > 2 && row[2] instanceof Integer ? (Integer) row[2] : 0xFFB8C7D6;
            String prefix = rows.size() > 1 ? (i + 1) + ". " : "";
            int valueWidth = this.textRenderer.getWidth(value);
            context.drawText(this.textRenderer, Text.literal(koil$ellipsize(prefix + label, Math.max(20, width - valueWidth - 8))), x, rowY, color, false);
            context.drawText(this.textRenderer, Text.literal(value), x + Math.max(0, width - valueWidth), rowY, color, false);
            rowY += 11;
        }
    }

    @Unique
    private List<Object[]> koil$marketLeaderRankingLines(KoilGlobalMarketViewRow view, int limit) {
        Map<String, Integer> totals = new LinkedHashMap<>();

        for (KoilGlobalActivityRow row : koil$componentRowsForView(view, 64)) {
            String leader = row.leader() == null || row.leader().isBlank() ? "none" : row.leader();
            int value = row.leaderValue() > 0 ? row.leaderValue() : row.value();

            if (!"none".equalsIgnoreCase(leader) && value > 0) {
                totals.put(leader, totals.getOrDefault(leader, 0) + value);
            }
        }

        if (totals.isEmpty() && view.leader() != null && !view.leader().isBlank() && view.leaderValue() > 0) {
            totals.put(view.leader(), view.leaderValue());
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(totals.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Object[]> rows = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : entries) {
            if (rows.size() >= Math.max(1, limit)) {
                break;
            }

            rows.add(new Object[]{entry.getKey(), koil$compact(entry.getValue()), 0xFF8FD5D0});
        }

        if (rows.isEmpty()) {
            rows.add(new Object[]{"not enough player data", "0", 0xFF718091});
        }

        return rows;
    }

    @Unique
    private List<Object[]> koil$marketStatRankingLines(KoilGlobalMarketViewRow view, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(view.components().entrySet());
        entries.sort((a, b) -> Integer.compare(Math.max(0, b.getValue()), Math.max(0, a.getValue())));
        List<Object[]> rows = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : entries) {
            if (rows.size() >= Math.max(1, limit)) {
                break;
            }

            rows.add(new Object[]{koil$formatIdentifierName(entry.getKey()), koil$compact(entry.getValue()), 0xFFB8C7D6});
        }

        if (rows.isEmpty()) {
            rows.add(new Object[]{"no component data", "0", 0xFF718091});
        }

        return rows;
    }

    @Unique
    private List<Object[]> koil$marketScoreLines(KoilGlobalMarketViewRow view, KoilMarketValueQuote quote, KoilGlobalActivityRow row, int limit) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"value", quote.compactMomens(), 0xFFE8C86F});
        rows.add(new Object[]{"exchange", quote.compactExchange(view.subject()), 0xFFB7A8FF});
        rows.add(new Object[]{"samples", String.valueOf(Math.max(0, row.pointsView().length)), 0xFF55DDE8});
        rows.add(new Object[]{"confidence", view.confidence() + "%", view.authoritative() ? 0xFFA6D989 : 0xFFE6A15C});
        rows.add(new Object[]{"raw signals", String.valueOf(Math.max(0, view.rawMetrics().size())), 0xFF8FD5D0});
        rows.add(new Object[]{"demand", KoilMarketValueQuote.compactDecimal(quote.demandScore()), 0xFFA6D989});
        rows.add(new Object[]{"supply", KoilMarketValueQuote.compactDecimal(quote.supplyScore()), 0xFF8FC5FF});
        rows.add(new Object[]{"scarcity", KoilMarketValueQuote.compactDecimal(quote.scarcityScore()), 0xFFE6A15C});
        return rows.subList(0, Math.min(rows.size(), Math.max(1, limit)));
    }


    @Unique
    private List<KoilGlobalMarketViewRow> koil$allMarketValueRows() {
        return this.koil$cachedAllMarketValueRows;
    }

    @Unique
    private KoilMarketValueQuote koil$quoteForView(KoilGlobalMarketViewRow view) {
        String key = koil$marketCacheKey(view);
        KoilMarketValueQuote cached = this.koil$cachedMarketQuotes.get(key);

        if (cached != null) {
            return cached;
        }

        KoilMarketValueQuote quote = KoilMarketValueEngine.quote(view, koil$allMarketValueRows());
        this.koil$cachedMarketQuotes.put(key, quote);
        return quote;
    }

    @Unique
    private String koil$marketDataSourcesLine(KoilGlobalMarketViewRow view) {
        List<KoilGlobalActivityRow> rows = koil$componentRowsForView(view, 4);

        if (rows.isEmpty()) {
            return view.compactComponentSummary();
        }

        StringBuilder builder = new StringBuilder("uses ");

        for (int i = 0; i < rows.size(); i++) {
            KoilGlobalActivityRow row = rows.get(i);

            if (i > 0) {
                builder.append("  |  ");
            }

            builder.append(koil$marketMetricShortTitle(row.metric())).append(": ").append(koil$compact(row.value())).append(" from ").append(row.leader());
        }

        int remaining = Math.max(0, view.rawMetrics().size() - rows.size());

        if (remaining > 0) {
            builder.append("  +").append(remaining).append(" more");
        }

        return builder.toString();
    }

    @Unique
    private String koil$marketTopInputsLine(KoilGlobalMarketViewRow view) {
        List<KoilGlobalActivityRow> rows = koil$componentRowsForView(view, 3);

        if (rows.isEmpty()) {
            return "inputs " + view.rawMetrics().size();
        }

        StringBuilder builder = new StringBuilder("top ");

        for (int i = 0; i < rows.size(); i++) {
            KoilGlobalActivityRow row = rows.get(i);

            if (i > 0) {
                builder.append(", ");
            }

            builder.append(koil$marketMetricShortTitle(row.metric()));
        }

        return builder.toString();
    }

    @Unique
    private List<String> koil$marketRawSourceTooltipLines(KoilGlobalMarketViewRow view, int limit) {
        List<String> lines = new ArrayList<>();
        List<KoilGlobalActivityRow> rows = koil$componentRowsForView(view, limit);

        if (rows.isEmpty()) {
            lines.add("Raw inputs: none loaded");
            return lines;
        }

        lines.add("Raw inputs:");

        for (KoilGlobalActivityRow row : rows) {
            String source = row.authoritative() ? "server" : "observed " + row.confidence() + "%";
            lines.add("- " + koil$marketMetricShortTitle(row.metric()) + ": " + row.value() + "  leader " + row.leader() + "  " + source);
        }

        int remaining = Math.max(0, view.rawMetrics().size() - rows.size());

        if (remaining > 0) {
            lines.add("+ " + remaining + " more raw signals");
        }

        return lines;
    }

    @Unique
    private List<KoilGlobalActivityRow> koil$componentRowsForView(KoilGlobalMarketViewRow view, int limit) {
        String cacheKey = koil$marketCacheKey(view) + "|" + Math.max(0, limit);
        List<KoilGlobalActivityRow> cached = this.koil$cachedMarketComponents.get(cacheKey);

        if (cached != null) {
            return cached;
        }

        List<KoilGlobalActivityRow> rows = new ArrayList<>();

        for (String metric : view.rawMetrics()) {
            KoilGlobalActivityRow row = this.koil$cachedGlobalRowsByMetric.get(koil$normalizeGlobalMetric(metric));

            if (row != null) {
                rows.add(row);
            }
        }

        rows.sort((a, b) -> Integer.compare(b.value(), a.value()));
        List<KoilGlobalActivityRow> result;

        if (rows.size() > Math.max(0, limit)) {
            result = Collections.unmodifiableList(new ArrayList<>(rows.subList(0, Math.max(0, limit))));
        } else {
            result = Collections.unmodifiableList(rows);
        }

        this.koil$cachedMarketComponents.put(cacheKey, result);
        return result;
    }

    @Unique
    private String koil$marketCacheKey(KoilGlobalMarketViewRow view) {
        if (view == null) {
            return "null";
        }

        KoilGlobalActivityRow row = view.chartRow();
        int[] points = row == null ? new int[0] : row.pointsView();
        int last = points.length == 0 ? 0 : points[points.length - 1];
        int change = row == null ? 0 : row.change();
        return view.tier() + ":" + view.id() + ":" + view.value() + ":" + view.leaderValue() + ":" + view.confidence() + ":" + view.authoritative() + ":" + points.length + ":" + last + ":" + change;
    }

    @Unique
    private String koil$normalizeGlobalMetric(String metric) {
        return metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    @Unique
    private String koil$marketMetricShortTitle(String metric) {
        String value = koil$normalizeGlobalMetric(metric);

        if (value.startsWith("block_broken/")) {
            return koil$formatIdentifierName(value.substring("block_broken/".length()).replace('/', ' ')) + " broken";
        }

        if (value.startsWith("block_place_intent/")) {
            return koil$formatIdentifierName(value.substring("block_place_intent/".length()).replace('/', ' ')) + " placed";
        }

        if (value.startsWith("block_placed/")) {
            return koil$formatIdentifierName(value.substring("block_placed/".length()).replace('/', ' ')) + " placed";
        }

        if (value.startsWith("block_used/")) {
            return koil$formatIdentifierName(value.substring("block_used/".length()).replace('/', ' ')) + " used";
        }

        if (value.startsWith("item_use_intent/")) {
            return koil$formatIdentifierName(value.substring("item_use_intent/".length()).replace('/', ' ')) + " used";
        }

        if (value.startsWith("item_crafted/")) {
            return koil$formatIdentifierName(value.substring("item_crafted/".length()).replace('/', ' ')) + " crafted";
        }

        if (value.startsWith("item_picked_up/")) {
            return koil$formatIdentifierName(value.substring("item_picked_up/".length()).replace('/', ' ')) + " picked up";
        }

        if (value.startsWith("item_dropped/")) {
            return koil$formatIdentifierName(value.substring("item_dropped/".length()).replace('/', ' ')) + " dropped";
        }

        if (value.startsWith("mob_killed/")) {
            return koil$formatIdentifierName(value.substring("mob_killed/".length()).replace('/', ' ')) + " killed";
        }

        if (value.startsWith("entity_killed/")) {
            return koil$formatIdentifierName(value.substring("entity_killed/".length()).replace('/', ' ')) + " killed";
        }

        if (value.startsWith("food_eaten/")) {
            return koil$formatIdentifierName(value.substring("food_eaten/".length()).replace('/', ' ')) + " eaten";
        }

        return koil$formatIdentifierName(value.replace('/', ' '));
    }

    @Unique
    private int koil$componentLineColor(int index, String metric) {
        int metricColor = koil$globalMetricColor(metric, false);

        if (index == 0) {
            return metricColor | 0xFF000000;
        }

        int[] colors = new int[]{0xCC8FC5FF, 0xCCA6D989, 0xCCE8C86F, 0xCCB7A8FF, 0xCCE6A15C, 0xCC8FD5D0};
        return colors[Math.abs(index) % colors.length];
    }

    @Unique
    private void koil$drawComponentLegend(DrawContext context, int x, int y, List<KoilGlobalActivityRow> components, int maxWidth) {
        int cursorX = x;
        int count = Math.min(3, components.size());

        for (int i = 0; i < count; i++) {
            KoilGlobalActivityRow component = components.get(i);
            int color = koil$componentLineColor(i, component.metric());
            String label = koil$ellipsize(koil$marketMetricShortTitle(component.metric()), 70);
            int labelWidth = this.textRenderer.getWidth(label) + 14;

            if (cursorX + labelWidth > x + maxWidth) {
                break;
            }

            context.fill(cursorX, y + 2, cursorX + 8, y + 4, color);
            context.drawText(this.textRenderer, Text.literal(label), cursorX + 11, y - 1, 0xCCB8C7D6, false);
            cursorX += labelWidth + 8;
        }
    }

    @Unique
    private String koil$tierLabel(String tier) {
        if ("head".equals(tier)) {
            return "Head Market";
        }

        if ("sub".equals(tier)) {
            return "Sub Market";
        }

        if ("raw".equals(tier)) {
            return "Raw Market";
        }

        return "Dev Market";
    }

    @Unique
    private int koil$renderGlobalEmpty(DrawContext context, int x, int y, int width) {
        int height = 96;
        context.fill(x, y, x + width, y + height, 0x16000000);
        context.drawBorder(x, y, width, height, 0x443D4A56);
        context.fill(x + 4, y + 6, x + 7, y + height - 6, 0xFF8FC5FF);
        context.drawText(this.textRenderer, Text.literal("Waiting for server or observed global activity."), x + 14, y + 8, 0xFFE7EDF4, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize("With Koil on the server this page is authoritative. Without Koil on the server it falls back to per-server/per-world client-observed packet-visible activity.", width - 28)), x + 14, y + 22, 0xFFB8C7D6, false);
        context.drawText(this.textRenderer, Text.literal(koil$ellipsize("The fallback never mixes worlds or servers and marks confidence so estimated data is not mistaken for exact server data.", width - 28)), x + 14, y + 34, 0xFF8E9AA8, false);
        koil$drawPreviewStockChart(context, x + 14, y + 52, width - 28, 36, new int[]{100, 102, 99, 108, 112, 117, 113, 121}, 0xFF8FC5FF);
        return y + height + 4;
    }

    @Unique
    private void koil$drawLightRow(DrawContext context, int x, int y, int width, int height, int index) {
        int fill = index % 2 == 0 ? 0x12000000 : 0x22000000;
        context.fill(x, y, x + width, y + height, fill);
        context.drawBorder(x, y, width, height, 0x263D4A56);
    }

    @Unique
    private void koil$drawMetric(DrawContext context, String label, int value, int x, int y, int color) {
        context.drawText(this.textRenderer, Text.literal(label), x, y, 0xFF718091, false);
        context.drawText(this.textRenderer, Text.literal(koil$compact(value)), x + 10, y, color, false);
    }

    @Unique
    private void koil$drawEmptyState(DrawContext context, int x, int y, int width, String text) {
        context.fill(x, y, x + width, y + 34, 0x16000000);
        context.drawBorder(x, y, width, 34, 0x443D4A56);
        context.fill(x + 4, y + 5, x + 7, y + 29, 0xFF8E9AA8);
        context.drawText(this.textRenderer, Text.literal(text), x + 14, y + 12, 0xFFB8C7D6, false);
    }

    @Unique
    private void koil$drawBar(DrawContext context, int x, int y, int width, int height, float progress, int fillColor) {
        int clampedWidth = Math.max(0, width);
        int filled = Math.max(0, Math.min(clampedWidth, Math.round(clampedWidth * Math.max(0.0F, Math.min(1.0F, progress)))));
        context.fill(x, y, x + clampedWidth, y + height, 0x44000000);

        if (filled > 0) {
            context.fill(x, y, x + filled, y + height, fillColor);
        }
    }

    @Unique
    private void koil$drawDualBar(DrawContext context, int x, int y, int width, float first, float second, int firstColor, int secondColor) {
        int half = Math.max(1, width / 2);
        koil$drawBar(context, x, y, half - 1, 3, first, firstColor);
        koil$drawBar(context, x + half + 1, y, half - 1, 3, second, secondColor);
    }

    @Unique
    private void koil$drawMiniSpark(DrawContext context, int x, int y, int width, int height, int[] points, int color) {
        context.fill(x, y, x + width, y + height, 0x22000000);
        koil$drawLineChart(context, x + 1, y + 1, width - 2, height - 2, points, color);
    }

    @Unique
    private void koil$drawStockChart(DrawContext context, int x, int y, int width, int height, KoilGlobalActivityRow row, int color, int mouseX, int mouseY) {
        koil$drawStockChart(context, x, y, width, height, row, null, color, mouseX, mouseY);
    }

    @Unique
    private void koil$drawStockChart(DrawContext context, int x, int y, int width, int height, KoilGlobalActivityRow row, @Nullable KoilGlobalMarketViewRow view, int color, int mouseX, int mouseY) {
        int[] rawOpen = row.candleOpenView();
        int[] rawHigh = row.candleHighView();
        int[] rawLow = row.candleLowView();
        int[] rawClose = row.candleCloseView();
        int[] rawAverage = row.movingAverageView();
        int[] rawPoints = row.pointsView();
        List<KoilGlobalActivityRow> components = view == null ? Collections.emptyList() : koil$componentRowsForView(view, 5);
        List<Object[]> syntheticComponents = view == null ? Collections.emptyList() : koil$syntheticComponentSeries(view);
        int[] rawDisplayPoints = view == null ? koil$visualSeries(row.metric(), rawPoints) : koil$marketMainPressureSeries(view, rawPoints);
        KoilMarketSeriesWindow.Window stockWindow = koil$stockWindowInfo(rawDisplayPoints);
        int plotWidth = koil$stockPlotWidth(width, stockWindow);
        int[] points = koil$renderSeriesForWidth(stockWindow.points(), plotWidth);
        int plotX = koil$stockPlotX(x, width, plotWidth);
        int[] average = koil$movingAverageFromSeries(points);
        int[][] derivedCandles = koil$candlesFromSeries(points);
        int[] open = derivedCandles[0];
        int[] high = derivedCandles[1];
        int[] low = derivedCandles[2];
        int[] close = derivedCandles[3];

        context.fill(x, y, x + width, y + height, 0x1A000000);
        context.drawBorder(x, y, width, height, 0x443D4A56);
        koil$drawDashedHorizontal(context, x + 1, y + height / 4, width - 2, 0x228E9AA8);
        koil$drawDashedHorizontal(context, x + 1, y + height / 2, width - 2, 0x448E9AA8);
        koil$drawDashedHorizontal(context, x + 1, y + height * 3 / 4, width - 2, 0x228E9AA8);
        koil$drawVerticalGuideLines(context, x + 1, y + 1, width - 2, height - 2, 0x148E9AA8);
        String windowLabel = KoilMarketSeriesWindow.label(this.koil$stockGraphInterval) + " | previous close baseline";
        context.drawText(this.textRenderer, Text.literal(windowLabel), x + width - 7 - this.textRenderer.getWidth(windowLabel), y + 5, 0xFF718091, false);

        int chartHeight = Math.max(52, height - 42);
        int volumeY = y + chartHeight + 4;
        int volumeHeight = Math.max(24, height - chartHeight - 6);
        koil$drawVolumeBars(context, plotX, volumeY, plotWidth, volumeHeight, points, 0xAA718091, row, mouseX, mouseY);
        context.drawText(this.textRenderer, Text.literal("VOL"), x + 5, volumeY + 2, 0xFF718091, false);

        int[] rangeValues = koil$chartRangeValues(points, open, high, low, close, components, syntheticComponents, plotWidth);
        int min = rangeValues[0];
        int max = rangeValues[1];
        int range = Math.max(1, max - min);
        int highLineY = koil$chartY(y + 1, chartHeight - 2, max, min, range);
        int lowLineY = koil$chartY(y + 1, chartHeight - 2, min, min, range);
        koil$drawDashedHorizontal(context, x + 1, highLineY, width - 2, 0x448FC5FF);
        koil$drawDashedHorizontal(context, x + 1, lowLineY, width - 2, 0x44E06A6A);
        koil$drawChartValueTag(context, x + width - 46, highLineY - 5, "H " + koil$compact(max), 0xFF8FC5FF);
        koil$drawChartValueTag(context, x + width - 46, lowLineY - 5, "L " + koil$compact(min), 0xFFE06A6A);

        if (min < 0 && max > 0) {
            int zeroLineY = koil$chartY(y + 1, chartHeight - 2, 0, min, range);
            koil$drawDashedHorizontal(context, x + 1, zeroLineY, width - 2, 0x66FFFFFF);
            koil$drawChartValueTag(context, x + 4, zeroLineY - 5, "0", 0xFFFFFFFF);
        }

        koil$drawSyntheticComponentStockLines(context, plotX, y + 1, plotWidth, chartHeight - 2, syntheticComponents, min, range, mouseX, mouseY);
        koil$drawComponentStockLines(context, plotX, y + 1, plotWidth, chartHeight - 2, components, min, range, mouseX, mouseY);
        koil$drawCandles(context, plotX, y + 1, plotWidth, chartHeight - 2, open, high, low, close, min, range, mouseX, mouseY, row, view, components, syntheticComponents, points);

        if (average.length > 1) {
            koil$drawIndicatorLineFixedRange(context, plotX, y + 1, plotWidth, chartHeight - 2, average, min, range, 0xCCE8C86F, true);
            if (koil$isMajorMarketMove(average, range)) {
                koil$drawEndpointArrow(context, plotX, y + 1, plotWidth, chartHeight - 2, average, min, range, 0xCCE8C86F);
            }
        }

        if (points.length > 1) {
            koil$drawMainMarketLine(context, plotX, y + 1, plotWidth, chartHeight - 2, points, min, range);
        }

        koil$updateStockChartHover(context, row, view, plotX, y + 1, plotWidth, chartHeight - 2, points, average, components, syntheticComponents, min, range, mouseX, mouseY);
        koil$drawChartLegend(context, x + 5, y + 5, row, color);
        koil$drawSyntheticLegend(context, x + 5, y + 18, syntheticComponents, width - 10);
        koil$drawComponentLegend(context, x + 5, y + 30, components, width - 10);
    }


    @Unique
    private long koil$worldTimeForStockWindow() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            return 0L;
        }
        return client.world.getTimeOfDay();
    }

    @Unique
    private int[] koil$stockWindow(int[] source) {
        return koil$stockWindowInfo(source).points();
    }

    @Unique
    private KoilMarketSeriesWindow.Window koil$stockWindowInfo(int[] source) {
        this.koil$stockGraphInterval = KoilMarketSeriesWindow.activeKey();
        return KoilMarketSeriesWindow.filter(source, this.koil$stockGraphInterval, koil$worldTimeForStockWindow());
    }

    @Unique
    private int[] koil$stockRenderWindow(int[] source, int width) {
        return koil$renderSeriesForWidth(koil$stockWindow(source), width);
    }

    @Unique
    private int[] koil$renderSeriesForWidth(int[] source, int width) {
        if (source == null || source.length == 0) {
            return new int[0];
        }

        int safeWidth = Math.max(2, width);
        int bucketCount = Math.max(2, Math.min(source.length, safeWidth / 2));

        if (source.length <= Math.max(2, safeWidth)) {
            return Arrays.copyOf(source, source.length);
        }

        List<Integer> values = new ArrayList<>();

        for (int bucket = 0; bucket < bucketCount; bucket++) {
            int start = Math.max(0, Math.min(source.length - 1, (int) Math.floor(bucket * (source.length / (double) bucketCount))));
            int end = Math.max(start + 1, Math.min(source.length, (int) Math.floor((bucket + 1) * (source.length / (double) bucketCount))));
            int minIndex = start;
            int maxIndex = start;

            for (int i = start; i < end; i++) {
                if (source[i] < source[minIndex]) {
                    minIndex = i;
                }

                if (source[i] > source[maxIndex]) {
                    maxIndex = i;
                }
            }

            if (minIndex <= maxIndex) {
                koil$appendRenderPoint(values, source[minIndex]);
                koil$appendRenderPoint(values, source[maxIndex]);
            } else {
                koil$appendRenderPoint(values, source[maxIndex]);
                koil$appendRenderPoint(values, source[minIndex]);
            }
        }

        koil$appendRenderPoint(values, source[source.length - 1]);

        if (values.size() == 1) {
            values.add(values.get(0));
        }

        int[] out = new int[values.size()];

        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }

        return out;
    }

    @Unique
    private void koil$appendRenderPoint(List<Integer> values, int value) {
        if (values.isEmpty() || !Objects.equals(values.get(values.size() - 1), value)) {
            values.add(value);
        }
    }

    @Unique
    private int koil$stockPlotWidth(int width, KoilMarketSeriesWindow.Window window) {
        return Math.max(1, width - 2);
    }

    @Unique
    private int koil$stockPlotX(int x, int width, int plotWidth) {
        return x + 1;
    }

    @Unique
    private boolean koil$shouldUseDerivedCandles(@Nullable KoilGlobalMarketViewRow view, String metric, int[] open, int[] high, int[] low, int[] close, int[] points) {
        if (points == null || points.length == 0) {
            return false;
        }

        if (view != null) {
            return true;
        }

        if (!koil$hasUsableCandles(open, high, low, close)) {
            return true;
        }

        String value = koil$normalizeGlobalMetric(metric);

        return value.contains("market")
                || value.contains("block_broken")
                || value.contains("block_place")
                || value.contains("block_used")
                || value.contains("item_")
                || value.contains("entity_")
                || koil$isNegativePressureMetric(value)
                || koil$isDemandPressureMetric(value);
    }

    @Unique
    private boolean koil$hasUsableCandles(int[] open, int[] high, int[] low, int[] close) {
        if (open == null || high == null || low == null || close == null) {
            return false;
        }

        int count = Math.min(Math.min(open.length, high.length), Math.min(low.length, close.length));

        if (count <= 0) {
            return false;
        }

        for (int i = 0; i < count; i++) {
            if (open[i] != 0 || high[i] != 0 || low[i] != 0 || close[i] != 0) {
                return true;
            }
        }

        return false;
    }

    @Unique
    private int[] koil$visualOpen(String metric, int[] open, int[] high, int[] low, int[] close) {
        return koil$isDownPressureMetric(metric) ? koil$visualSeries(metric, open) : koil$copySeries(open);
    }

    @Unique
    private int[] koil$visualHigh(String metric, int[] open, int[] high, int[] low, int[] close) {
        return koil$isDownPressureMetric(metric) ? koil$visualSeries(metric, low) : koil$copySeries(high);
    }

    @Unique
    private int[] koil$visualLow(String metric, int[] open, int[] high, int[] low, int[] close) {
        return koil$isDownPressureMetric(metric) ? koil$visualSeries(metric, high) : koil$copySeries(low);
    }

    @Unique
    private int[] koil$visualClose(String metric, int[] open, int[] high, int[] low, int[] close) {
        return koil$isDownPressureMetric(metric) ? koil$visualSeries(metric, close) : koil$copySeries(close);
    }

    @Unique
    private int[] koil$visualSeries(String metric, int[] source) {
        if (source == null || source.length == 0) {
            return new int[0];
        }

        int[] copy = koil$copySeries(source);

        if (!koil$isDownPressureMetric(metric)) {
            return copy;
        }

        int base = copy[0];

        for (int i = 0; i < copy.length; i++) {
            copy[i] = base - Math.max(0, copy[i] - base);
        }

        return copy;
    }

    @Unique
    private int[] koil$copySeries(int[] source) {
        if (source == null || source.length == 0) {
            return new int[0];
        }

        return Arrays.copyOf(source, source.length);
    }

    @Unique
    private boolean koil$isDownPressureMetric(String metric) {
        return koil$isNegativePressureMetric(metric) || koil$isSupplyPressureMetric(metric);
    }

    @Unique
    private int[] koil$marketMainPressureSeries(KoilGlobalMarketViewRow view, int[] fallback) {
        String cacheKey = koil$marketCacheKey(view);
        int[] cached = this.koil$cachedMarketPressureSeries.get(cacheKey);

        if (cached != null) {
            return cached;
        }

        List<KoilGlobalActivityRow> rows = koil$componentRowsForView(view, 64);

        if (rows.isEmpty()) {
            int[] copy = koil$copySeries(fallback);
            this.koil$cachedMarketPressureSeries.put(cacheKey, copy);
            return copy;
        }

        int maxLength = 0;

        for (KoilGlobalActivityRow row : rows) {
            maxLength = Math.max(maxLength, row.pointsView().length);
        }

        if (maxLength <= 0) {
            int[] copy = koil$copySeries(fallback);
            this.koil$cachedMarketPressureSeries.put(cacheKey, copy);
            return copy;
        }

        int[] out = new int[maxLength];
        out[0] = 0;

        for (int i = 1; i < maxLength; i++) {
            int delta = 0;

            for (KoilGlobalActivityRow row : rows) {
                int[] points = row.pointsView();

                if (points.length == 0) {
                    continue;
                }

                int current = koil$alignedSeriesValue(points, i, maxLength);
                int previous = koil$alignedSeriesValue(points, i - 1, maxLength);
                int movement = Math.max(0, current - previous);

                if (koil$isNegativePressureMetric(row.metric()) || koil$isSupplyPressureMetric(row.metric())) {
                    delta -= movement;
                } else if (koil$isDemandPressureMetric(row.metric())) {
                    delta += movement;
                } else {
                    delta += Math.round(movement * 0.20F);
                }
            }

            out[i] = out[i - 1] + delta;
        }

        this.koil$cachedMarketPressureSeries.put(cacheKey, out);
        return out;
    }

    @Unique
    private int koil$alignedSeriesValue(int[] points, int targetIndex, int targetLength) {
        if (points == null || points.length == 0) {
            return 0;
        }

        int offset = Math.max(0, targetLength - points.length);
        int sourceIndex = targetIndex - offset;

        if (sourceIndex < 0) {
            return points[0];
        }

        if (sourceIndex >= points.length) {
            return points[points.length - 1];
        }

        return points[sourceIndex];
    }

    @Unique
    private int[] koil$movingAverageFromSeries(int[] points) {
        if (points == null || points.length == 0) {
            return new int[0];
        }

        int[] out = new int[points.length];
        int window = 4;

        for (int i = 0; i < points.length; i++) {
            int start = Math.max(0, i - window + 1);
            int total = 0;
            int count = 0;

            for (int j = start; j <= i; j++) {
                total += points[j];
                count++;
            }

            out[i] = count <= 0 ? points[i] : Math.round(total / (float) count);
        }

        return out;
    }

    @Unique
    private int[][] koil$candlesFromSeries(int[] points) {
        if (points == null || points.length == 0) {
            return new int[][]{new int[0], new int[0], new int[0], new int[0]};
        }

        int[] open = new int[points.length];
        int[] high = new int[points.length];
        int[] low = new int[points.length];
        int[] close = new int[points.length];

        for (int i = 0; i < points.length; i++) {
            int previous = i == 0 ? points[i] : points[i - 1];
            int current = points[i];
            open[i] = previous;
            close[i] = current;
            high[i] = Math.max(previous, current);
            low[i] = Math.min(previous, current);
        }

        return new int[][]{open, high, low, close};
    }

    @Unique
    private boolean koil$isNegativePressureMetric(String metric) {
        String value = koil$normalizeGlobalMetric(metric);
        return value.contains("dropped")
                || value.contains("death")
                || value.contains("killed_by")
                || value.contains("damage_taken")
                || value.contains("loss")
                || value.contains("spent")
                || value.contains("removed");
    }

    @Unique
    private boolean koil$isSupplyPressureMetric(String metric) {
        String value = koil$normalizeGlobalMetric(metric);
        return value.contains("container_in/")
                || value.contains("broken")
                || value.contains("break")
                || value.contains("mined")
                || value.contains("harvest")
                || value.contains("produced")
                || value.contains("smelt_output")
                || value.contains("compost_output")
                || value.contains("station_output");
    }

    @Unique
    private boolean koil$isDemandPressureMetric(String metric) {
        String value = koil$normalizeGlobalMetric(metric);
        return value.contains("container_out/")
                || value.contains("mob_killed")
                || value.contains("entity_killed")
                || value.equals("mobs_killed")
                || value.contains("place")
                || value.contains("placed")
                || value.contains("used")
                || value.contains("use_intent")
                || value.contains("crafted")
                || value.contains("picked_up")
                || value.contains("traded")
                || value.contains("eaten")
                || value.contains("mainhand")
                || value.contains("offhand");
    }

    @Unique
    private List<Object[]> koil$syntheticComponentSeries(KoilGlobalMarketViewRow view) {
        String cacheKey = koil$marketCacheKey(view);
        List<Object[]> cached = this.koil$cachedSyntheticSeries.get(cacheKey);

        if (cached != null) {
            return cached;
        }

        List<KoilGlobalActivityRow> rows = koil$componentRowsForView(view, 64);
        List<Object[]> out = new ArrayList<>();

        if (rows.isEmpty()) {
            List<Object[]> empty = Collections.emptyList();
            this.koil$cachedSyntheticSeries.put(cacheKey, empty);
            return empty;
        }

        int maxLength = 0;

        for (KoilGlobalActivityRow row : rows) {
            maxLength = Math.max(maxLength, row.pointsView().length);
        }

        if (maxLength <= 0) {
            List<Object[]> empty = Collections.emptyList();
            this.koil$cachedSyntheticSeries.put(cacheKey, empty);
            return empty;
        }

        int[] allActivity = new int[maxLength];
        int[] demand = new int[maxLength];
        int[] supply = new int[maxLength];
        int[] loss = new int[maxLength];
        int[] ratio = new int[maxLength];

        for (KoilGlobalActivityRow row : rows) {
            int[] points = row.pointsView();

            if (points.length == 0) {
                continue;
            }

            for (int i = 0; i < maxLength; i++) {
                int value = koil$alignedSeriesValue(points, i, maxLength);
                allActivity[i] += Math.abs(value);

                if (koil$isNegativePressureMetric(row.metric())) {
                    loss[i] += Math.abs(value);
                } else if (koil$isSupplyPressureMetric(row.metric())) {
                    supply[i] += Math.abs(value);
                } else if (koil$isDemandPressureMetric(row.metric())) {
                    demand[i] += Math.abs(value);
                }
            }
        }

        int base = loss.length == 0 ? 0 : loss[0];
        int supplyBase = supply.length == 0 ? 0 : supply[0];

        for (int i = 0; i < maxLength; i++) {
            ratio[i] = demand[i] - loss[i] - supply[i];
            loss[i] = base - Math.max(0, loss[i] - base);
            supply[i] = supplyBase - Math.max(0, supply[i] - supplyBase);
        }

        if (koil$seriesHasSignal(allActivity)) {
            out.add(new Object[]{"total activity line", 0xCC8FC5FF, allActivity, "all raw inputs combined without changing the place/break ratio"});
        }

        if (koil$seriesHasSignal(demand)) {
            out.add(new Object[]{"demand/use/place pressure", 0xCCA6D989, demand, "positive pressure from placed, used, crafted, picked up, traded, and held items"});
        }

        if (koil$seriesHasSignal(supply)) {
            out.add(new Object[]{"supply/production pressure", 0xCCE8C86F, supply, "value-down pressure from broken, mined, harvested, smelted, and produced resources"});
        }

        if (koil$seriesHasSignal(loss)) {
            out.add(new Object[]{"loss/destruction pressure", 0xCCE06A6A, loss, "scarcity pressure from dropped, deaths, damage taken, destroyed, and loss signals"});
        }

        if (koil$seriesHasSignal(ratio)) {
            out.add(new Object[]{"net value pressure", 0xCCFFB86F, ratio, "demand pressure minus production supply and loss pressure"});
        }

        List<Object[]> result = Collections.unmodifiableList(out);
        this.koil$cachedSyntheticSeries.put(cacheKey, result);
        return result;
    }

    @Unique
    private boolean koil$seriesHasSignal(int[] points) {
        if (points == null || points.length == 0) {
            return false;
        }

        for (int point : points) {
            if (point != 0) {
                return true;
            }
        }

        return false;
    }

    @Unique
    private void koil$appendCandleCauseTooltipLines(List<Text> lines, KoilGlobalActivityRow row, @Nullable KoilGlobalMarketViewRow view, List<KoilGlobalActivityRow> components, List<Object[]> syntheticComponents, int[] mainPoints, int candleIndex, int candleCount, int open, int close) {
        int netMove = close - open;
        int positive = 0;
        int negative = 0;
        int neutral = 0;
        List<Object[]> top = new ArrayList<>();

        for (KoilGlobalActivityRow component : components) {
            int[] points = component.pointsView();

            if (points.length == 0) {
                continue;
            }

            int current = koil$alignedSeriesValue(points, candleIndex, candleCount);
            int previous = candleIndex <= 0 ? current : koil$alignedSeriesValue(points, candleIndex - 1, candleCount);
            int movement = current - previous;

            if (movement == 0) {
                continue;
            }

            int amount = Math.abs(movement);
            int color;
            String prefix;

            if (koil$isNegativePressureMetric(component.metric())) {
                negative += amount;
                color = 0xFFE06A6A;
                prefix = "negative";
            } else if (koil$isSupplyPressureMetric(component.metric())) {
                negative += amount;
                color = 0xFFE8C86F;
                prefix = "supply";
            } else if (koil$isDemandPressureMetric(component.metric())) {
                positive += amount;
                color = 0xFFA6D989;
                prefix = "positive";
            } else {
                neutral += amount;
                color = 0xFFB8C7D6;
                prefix = "neutral";
            }

            top.add(new Object[]{component.title(), amount, color, prefix});
        }

        for (Object[] synthetic : syntheticComponents) {
            if (synthetic.length < 3 || !(synthetic[2] instanceof int[] points) || points.length == 0) {
                continue;
            }

            int current = koil$alignedSeriesValue(points, candleIndex, candleCount);
            int previous = candleIndex <= 0 ? current : koil$alignedSeriesValue(points, candleIndex - 1, candleCount);
            int movement = current - previous;

            if (movement == 0) {
                continue;
            }

            String name = String.valueOf(synthetic[0]);
            int amount = Math.abs(movement);
            int color = synthetic.length > 1 && synthetic[1] instanceof Integer ? (Integer) synthetic[1] : 0xFFB8C7D6;
            String prefix = movement > 0 ? "up" : "down";
            top.add(new Object[]{name, amount, color, prefix});
        }

        if (view != null && components.isEmpty()) {
            positive = Math.max(0, netMove);
            negative = Math.max(0, -netMove);
        }

        top.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        lines.add(koil$tooltipLine("Movement cause", 0xFFE7EDF4));
        lines.add(koil$tooltipLine("Positive pressure: +" + positive, 0xFFA6D989));
        lines.add(koil$tooltipLine("Negative pressure: -" + negative, 0xFFE06A6A));
        lines.add(koil$tooltipLine("Neutral activity: " + neutral, 0xFFB8C7D6));
        lines.add(koil$tooltipLine("Net pressure shown: " + koil$signed(netMove), netMove >= 0 ? 0xFFA6D989 : 0xFFE06A6A));

        int limit = Math.min(6, top.size());

        if (limit > 0) {
            lines.add(koil$tooltipLine("Top candle inputs", 0xFFE8C86F));
        }

        for (int i = 0; i < limit; i++) {
            Object[] item = top.get(i);
            String name = koil$ellipsize(String.valueOf(item[0]), 180);
            int amount = (Integer) item[1];
            int color = (Integer) item[2];
            String prefix = String.valueOf(item[3]);
            lines.add(koil$tooltipLine(prefix + ": " + name + "  " + amount, color));
        }
    }

    @Unique
    private void koil$drawSeriesNodes(DrawContext context, int x, int y, int width, int height, int[] points, int min, int range, int color, int radius) {
        if (points == null || points.length == 0) {
            return;
        }

        int step = Math.max(1, points.length / 24);

        for (int i = 0; i < points.length; i += step) {
            int pointX = x + 1 + Math.round(i / (float) Math.max(1, points.length - 1) * Math.max(1, width - 2));
            int pointY = koil$chartY(y, height, points[i], min, range);
            context.fill(pointX - radius, pointY - radius, pointX + radius + 1, pointY + radius + 1, color | 0xFF000000);
            context.drawBorder(pointX - radius - 1, pointY - radius - 1, radius * 2 + 3, radius * 2 + 3, 0xAA000000);
        }
    }

    @Unique
    @Nullable
    private Object[] koil$syntheticLineAt(List<Object[]> components, int x, int y, int width, int height, int min, int range, int mouseX, int mouseY) {
        for (Object[] component : components) {
            if (component.length < 3 || !(component[2] instanceof int[] points) || points.length < 2) {
                continue;
            }

            int previousX = x + 1;
            int previousY = koil$chartY(y, height, points[0], min, range);

            for (int pointIndex = 1; pointIndex < points.length; pointIndex++) {
                int nextX = x + 1 + Math.round(pointIndex / (float) (points.length - 1) * Math.max(1, width - 2));
                int nextY = koil$chartY(y, height, points[pointIndex], min, range);

                if (koil$distanceToSegment(mouseX, mouseY, previousX, previousY, nextX, nextY) <= 5.5D) {
                    return component;
                }

                previousX = nextX;
                previousY = nextY;
            }
        }

        return null;
    }

    @Unique
    private void koil$drawSyntheticLegend(DrawContext context, int x, int y, List<Object[]> components, int maxWidth) {
        int cursorX = x;
        int count = Math.min(3, components.size());

        for (int i = 0; i < count; i++) {
            Object[] component = components.get(i);
            String label = String.valueOf(component[0]);
            int color = component.length > 1 && component[1] instanceof Integer ? (Integer) component[1] : 0xCC8FC5FF;
            label = koil$ellipsize(label, 86);
            int labelWidth = this.textRenderer.getWidth(label) + 14;

            if (cursorX + labelWidth > x + maxWidth) {
                break;
            }

            context.fill(cursorX, y + 2, cursorX + 8, y + 4, color);
            context.drawText(this.textRenderer, Text.literal(label), cursorX + 11, y - 1, 0xCCB8C7D6, false);
            cursorX += labelWidth + 8;
        }
    }

    @Unique
    private int[] koil$chartRangeValues(int[] points, int[] open, int[] high, int[] low, int[] close, List<KoilGlobalActivityRow> components, List<Object[]> syntheticComponents, int width) {
        int min = Integer.MAX_VALUE;
        int max = 1;
        int[][] arrays = new int[][]{points, open, high, low, close};

        for (int[] array : arrays) {
            if (array == null) {
                continue;
            }

            for (int value : array) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        for (KoilGlobalActivityRow component : components) {
            int[] componentPoints = koil$stockRenderWindow(koil$visualSeries(component.metric(), component.pointsView()), width);

            for (int value : componentPoints) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        for (Object[] synthetic : syntheticComponents) {
            if (synthetic.length < 3 || !(synthetic[2] instanceof int[] syntheticPoints)) {
                continue;
            }

            int[] displaySyntheticPoints = koil$stockRenderWindow(syntheticPoints, width);

            for (int value : displaySyntheticPoints) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        if (min == Integer.MAX_VALUE) {
            min = 0;
        }

        return new int[]{min, max};
    }

    @Unique
    private void koil$drawCandles(DrawContext context, int x, int y, int width, int height, int[] open, int[] high, int[] low, int[] close, int min, int range, int mouseX, int mouseY, KoilGlobalActivityRow row, @Nullable KoilGlobalMarketViewRow view, List<KoilGlobalActivityRow> components, List<Object[]> syntheticComponents, int[] mainPoints) {
        if (open.length == 0 || high.length == 0 || low.length == 0 || close.length == 0) {
            return;
        }

        int count = Math.min(Math.min(open.length, high.length), Math.min(low.length, close.length));
        int candleWidth = Math.max(4, Math.min(10, width / Math.max(1, count) - 2));

        for (int i = 0; i < count; i++) {
            int centerX = x + Math.round(i / (float) Math.max(1, count - 1) * Math.max(1, width));
            int bodyLeft = Math.max(x, centerX - candleWidth / 2);
            int bodyRight = Math.min(x + width, centerX + candleWidth / 2);
            int openY = koil$chartY(y, height, open[i], min, range);
            int highY = koil$chartY(y, height, high[i], min, range);
            int lowY = koil$chartY(y, height, low[i], min, range);
            int closeY = koil$chartY(y, height, close[i], min, range);
            boolean up = close[i] >= open[i];
            int candleColor = up ? 0xFFA6D989 : 0xFFE06A6A;
            int bodyTop = Math.max(y, Math.min(openY, closeY));
            int bodyBottom = Math.min(y + height, Math.max(openY, closeY));
            int wickTop = Math.max(y, Math.min(highY, lowY));
            int wickBottom = Math.min(y + height, Math.max(highY, lowY));
            int tailLeft = Math.max(x, centerX - Math.max(2, candleWidth / 2));
            int tailRight = Math.min(x + width, centerX + Math.max(3, candleWidth / 2));
            context.fill(centerX - 1, wickTop, centerX + 1, wickBottom + 1, candleColor);
            context.fill(tailLeft, wickTop, tailRight, wickTop + 1, candleColor);
            context.fill(tailLeft, wickBottom, tailRight, wickBottom + 1, candleColor);
            context.fill(bodyLeft, bodyTop, Math.max(bodyLeft + 4, bodyRight), Math.max(bodyTop + 4, bodyBottom + 1), candleColor);
            context.drawBorder(bodyLeft, bodyTop, Math.max(4, bodyRight - bodyLeft), Math.max(4, bodyBottom - bodyTop + 1), 0xCC000000);

            if (koil$isMouseInsideStatsViewport(mouseX, mouseY) && mouseX >= tailLeft - 2 && mouseX <= tailRight + 2 && mouseY >= wickTop - 2 && mouseY <= wickBottom + 2 && this.koil$hoverTooltipLines.isEmpty()) {
                context.fill(centerX - 1, wickTop, centerX + 2, wickBottom + 1, koil$withAlpha(candleColor, 255));
                context.drawBorder(bodyLeft - 1, bodyTop - 1, Math.max(6, bodyRight - bodyLeft + 2), Math.max(6, bodyBottom - bodyTop + 3), 0xFFFFFFFF);
                koil$drawHoverNode(context, centerX, closeY, candleColor);
                List<Text> lines = new ArrayList<>();
                lines.add(koil$tooltipLine(row.title(), 0xFFE7EDF4));
                lines.add(koil$tooltipLine("Feature: candle " + (i + 1) + " / " + count, candleColor));
                lines.add(koil$tooltipLine("Open: " + open[i], 0xFFB8C7D6));
                lines.add(koil$tooltipLine("High: " + high[i], 0xFFA6D989));
                lines.add(koil$tooltipLine("Low: " + low[i], 0xFFE06A6A));
                lines.add(koil$tooltipLine("Close: " + close[i], candleColor));
                lines.add(koil$tooltipLine("Net candle move: " + koil$signed(close[i] - open[i]), close[i] >= open[i] ? 0xFFA6D989 : 0xFFE06A6A));
                lines.add(koil$tooltipLine("Body: " + Math.abs(close[i] - open[i]) + "  Tail/Wick: " + Math.max(0, Math.abs(high[i] - low[i])), 0xFF8FD5D0));
                koil$appendCandleCauseTooltipLines(lines, row, view, components, syntheticComponents, mainPoints, i, count, open[i], close[i]);
                lines.add(koil$tooltipLine(up ? "Meaning: this capture closed above its open" : "Meaning: this capture closed below its open", up ? 0xFFA6D989 : 0xFFE06A6A));
                koil$setHoverTooltip(lines.toArray(new Text[0]));
            }
        }
    }

    @Unique
    private void koil$drawSyntheticComponentStockLines(DrawContext context, int x, int y, int width, int height, List<Object[]> components, int min, int range, int mouseX, int mouseY) {
        for (int i = 0; i < components.size(); i++) {
            Object[] component = components.get(i);

            if (component.length < 3 || !(component[2] instanceof int[] points)) {
                continue;
            }

            int color = component.length > 1 && component[1] instanceof Integer ? (Integer) component[1] : 0xCC8FC5FF;
            int[] displayPoints = koil$stockRenderWindow(points, width);
            int adjustedColor = koil$impactAdjustedColor(color, displayPoints, range);
            boolean dashed = i % 2 == 1;
            koil$drawPressureLineWithArrow(context, x, y, width, height, displayPoints, min, range, adjustedColor, dashed, koil$isMajorMarketMove(displayPoints, range));
        }
    }

    @Unique
    private void koil$drawComponentStockLines(DrawContext context, int x, int y, int width, int height, List<KoilGlobalActivityRow> components, int min, int range, int mouseX, int mouseY) {
        for (int i = 0; i < components.size(); i++) {
            KoilGlobalActivityRow component = components.get(i);
            int color = koil$componentLineColor(i, component.metric());
            int[] visualPoints = koil$stockRenderWindow(koil$visualSeries(component.metric(), component.pointsView()), width);
            int adjustedColor = koil$impactAdjustedColor(color, visualPoints, range);
            boolean strong = koil$seriesImpact(visualPoints, range) > 0.34F;
            koil$drawPressureLineWithArrow(context, x, y, width, height, visualPoints, min, range, adjustedColor, i % 2 == 1, koil$isMajorMarketMove(visualPoints, range));
        }
    }

    @Unique
    private void koil$drawMainMarketLine(DrawContext context, int x, int y, int width, int height, int[] points, int min, int range) {
        if (points == null || points.length < 2) {
            return;
        }

        int softWhite = 0xAAFFFFFF;
        int fullWhite = 0xFFFFFFFF;
        koil$drawIndicatorLineFixedRange(context, x, y, width, height, points, min, range, softWhite, false);
        koil$drawIndicatorLineFixedRange(context, x, y + 1, width, height, points, min, range, fullWhite, false);
        koil$drawIndicatorLineFixedRange(context, x, y - 1, width, height, points, min, range, 0x66FFFFFF, false);

        if (koil$isMajorMarketMove(points, range)) {
            koil$drawEndpointArrow(context, x, y, width, height, points, min, range, fullWhite);
        }
    }

    @Unique
    private void koil$drawPressureLineWithArrow(DrawContext context, int x, int y, int width, int height, int[] points, int min, int range, int color, boolean dashed, boolean strong) {
        if (points == null || points.length < 2) {
            return;
        }

        koil$drawIndicatorLineFixedRange(context, x, y, width, height, points, min, range, color, dashed);

        if (strong) {
            koil$drawIndicatorLineFixedRange(context, x, y + 1, width, height, points, min, range, color, dashed);
        }

        if (strong) {
            koil$drawEndpointArrow(context, x, y, width, height, points, min, range, color);
        }
    }

    @Unique
    private boolean koil$isMajorMarketMove(int[] points, int range) {
        if (points == null || points.length < 2) {
            return false;
        }

        int start = points[0];
        int end = points[points.length - 1];
        int latestDelta = Math.abs(end - points[Math.max(0, points.length - 2)]);
        int totalDelta = Math.abs(end - start);
        float impact = koil$seriesImpact(points, range);

        return impact >= 0.38F || latestDelta >= Math.max(2, Math.round(range * 0.14F)) || totalDelta >= Math.max(3, Math.round(range * 0.28F));
    }

    @Unique
    private int koil$impactAdjustedColor(int color, int[] points, int range) {
        int rgb = color & 0x00FFFFFF;
        int alpha = Math.max(84, Math.min(242, Math.round(82.0F + koil$seriesImpact(points, range) * 170.0F)));
        return (alpha << 24) | rgb;
    }

    @Unique
    private float koil$seriesImpact(int[] points, int range) {
        if (points == null || points.length < 2) {
            return 0.0F;
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int point : points) {
            min = Math.min(min, point);
            max = Math.max(max, point);
        }

        return Math.max(0.0F, Math.min(1.0F, (max - min) / (float) Math.max(1, range)));
    }

    @Unique
    private void koil$drawEndpointArrow(DrawContext context, int x, int y, int width, int height, int[] points, int min, int range, int color) {
        if (points == null || points.length < 2) {
            return;
        }

        int endIndex = points.length - 1;
        int previousIndex = Math.max(0, endIndex - 1);

        while (previousIndex > 0 && points[previousIndex] == points[endIndex]) {
            previousIndex--;
        }

        int endX = x + 1 + Math.round(endIndex / (float) Math.max(1, points.length - 1) * Math.max(1, width - 2));
        int endY = koil$chartY(y, height, points[endIndex], min, range);
        int previousX = x + 1 + Math.round(previousIndex / (float) Math.max(1, points.length - 1) * Math.max(1, width - 2));
        int previousY = koil$chartY(y, height, points[previousIndex], min, range);
        int dx = endX - previousX;
        int dy = endY - previousY;

        if (dx == 0 && dy == 0) {
            return;
        }

        int dirX = Integer.compare(dx, 0);
        int dirY = Integer.compare(dy, 0);
        if (Math.abs(dx) >= Math.abs(dy)) {
            if (dirX == 0) {
                dirX = 1;
            }

            koil$drawLine(context, endX, endY, endX - dirX * 5, endY - 3, color, x, y, x + width - 1, y + height - 1);
            koil$drawLine(context, endX, endY, endX - dirX * 5, endY + 3, color, x, y, x + width - 1, y + height - 1);
        } else {
            if (dirY == 0) {
                dirY = -1;
            }

            koil$drawLine(context, endX, endY, endX - 3, endY - dirY * 5, color, x, y, x + width - 1, y + height - 1);
            koil$drawLine(context, endX, endY, endX + 3, endY - dirY * 5, color, x, y, x + width - 1, y + height - 1);
        }
    }

    @Unique
    private void koil$drawIndicatorLineFixedRange(DrawContext context, int x, int y, int width, int height, int[] points, int min, int range, int color, boolean dashed) {
        if (points == null || points.length < 2) {
            return;
        }

        int previousX = x + 1;
        int previousY = koil$chartY(y, height, points[0], min, range);

        for (int i = 1; i < points.length; i++) {
            int nextX = x + 1 + Math.round(i / (float) (points.length - 1) * Math.max(1, width - 2));
            int nextY = koil$chartY(y, height, points[i], min, range);

            if (dashed && i % 2 == 0) {
                previousX = nextX;
                previousY = nextY;
                continue;
            }

            koil$drawLine(context, previousX, previousY, nextX, nextY, color, x, y, x + width - 1, y + height - 1);
            previousX = nextX;
            previousY = nextY;
        }
    }

    @Unique
    private void koil$updateStockChartHover(DrawContext context, KoilGlobalActivityRow row, @Nullable KoilGlobalMarketViewRow view, int x, int y, int width, int height, int[] points, int[] average, List<KoilGlobalActivityRow> components, List<Object[]> syntheticComponents, int min, int range, int mouseX, int mouseY) {
        if (!koil$isMouseInsideStatsViewport(mouseX, mouseY) || mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height || points == null || points.length == 0 || !this.koil$hoverTooltipLines.isEmpty()) {
            return;
        }

        String feature = "";
        int featureColor = 0xFFFFFFFF;
        int[] featurePoints = null;
        int[] hover = null;
        Object[] syntheticHit = null;
        KoilGlobalActivityRow componentHit = null;
        int featurePriority = Integer.MAX_VALUE;

        for (Object[] synthetic : syntheticComponents) {
            if (synthetic.length < 3 || !(synthetic[2] instanceof int[] syntheticPoints)) {
                continue;
            }

            syntheticPoints = koil$stockRenderWindow(syntheticPoints, width);

            if (syntheticPoints.length < 2) {
                continue;
            }

            int[] hit = koil$nearestSeriesHover(syntheticPoints, x, y, width, height, min, range, mouseX, mouseY, 6.0D);

            if (hit != null && hit[4] < featurePriority) {
                featurePriority = hit[4];
                hover = hit;
                syntheticHit = synthetic;
                componentHit = null;
                featurePoints = syntheticPoints;
                feature = String.valueOf(synthetic[0]);
                featureColor = synthetic.length > 1 && synthetic[1] instanceof Integer ? (Integer) synthetic[1] : 0xFF8FC5FF;
            }
        }

        for (int i = 0; i < components.size(); i++) {
            KoilGlobalActivityRow component = components.get(i);
            int[] componentPoints = koil$stockRenderWindow(koil$visualSeries(component.metric(), component.pointsView()), width);
            int[] hit = koil$nearestSeriesHover(componentPoints, x, y, width, height, min, range, mouseX, mouseY, 5.5D);

            if (hit != null && hit[4] < featurePriority) {
                featurePriority = hit[4];
                hover = hit;
                syntheticHit = null;
                componentHit = component;
                featurePoints = componentPoints;
                feature = "component line: " + koil$marketMetricShortTitle(component.metric());
                featureColor = koil$componentLineColor(i, component.metric());
            }
        }

        int[] averageHit = koil$nearestSeriesHover(average, x, y, width, height, min, range, mouseX, mouseY, 5.0D);

        if (averageHit != null && averageHit[4] < featurePriority) {
            featurePriority = averageHit[4];
            hover = averageHit;
            syntheticHit = null;
            componentHit = null;
            featurePoints = average;
            feature = "moving average line";
            featureColor = 0xFFE8C86F;
        }

        int[] mainHit = koil$nearestSeriesHover(points, x, y, width, height, min, range, mouseX, mouseY, 6.0D);

        if (mainHit != null && mainHit[4] < featurePriority) {
            hover = mainHit;
            syntheticHit = null;
            componentHit = null;
            featurePoints = points;
            feature = "main market line";
            featureColor = 0xFFFFFFFF;
        }

        if (hover == null || featurePoints == null) {
            return;
        }

        int safeIndex = Math.max(0, Math.min(points.length - 1, Math.round((hover[0] / (float) Math.max(1, featurePoints.length - 1)) * Math.max(1, points.length - 1))));
        int featureIndex = Math.max(0, Math.min(featurePoints.length - 1, hover[0]));
        int point = points[safeIndex];
        int avg = average == null || average.length == 0 ? point : average[Math.max(0, Math.min(average.length - 1, safeIndex))];
        int pointX = hover[1];
        int hoverPointY = hover[2];
        int featureValue = hover[3];
        int highlightColor = koil$withAlpha(featureColor, 255);

        if ("main market line".equals(feature)) {
            koil$drawIndicatorLineFixedRange(context, x, y, width, height, points, min, range, 0xFFFFFFFF, false);
            koil$drawIndicatorLineFixedRange(context, x, y + 1, width, height, points, min, range, 0xFFFFFFFF, false);
        } else if ("moving average line".equals(feature)) {
            koil$drawIndicatorLineFixedRange(context, x, y, width, height, average, min, range, 0xFFE8C86F, true);
            koil$drawIndicatorLineFixedRange(context, x, y + 1, width, height, average, min, range, 0xCCE8C86F, true);
        } else {
            koil$drawIndicatorLineFixedRange(context, x, y, width, height, featurePoints, min, range, highlightColor, false);
            koil$drawIndicatorLineFixedRange(context, x, y + 1, width, height, featurePoints, min, range, koil$withAlpha(featureColor, 190), false);
        }

        koil$drawDashedVertical(context, pointX, y, height, 0x668FC5FF);
        koil$drawDashedHorizontal(context, x, hoverPointY, width, 0x668FC5FF);
        koil$drawHoverNode(context, pointX, hoverPointY, highlightColor);

        List<Text> lines = new ArrayList<>();
        lines.add(koil$tooltipLine(row.title(), 0xFFE7EDF4));
        lines.add(koil$tooltipLine("Feature: " + feature, highlightColor));
        lines.add(koil$tooltipLine("Feature capture: " + (featureIndex + 1) + " / " + featurePoints.length, 0xFFB8C7D6));
        lines.add(koil$tooltipLine("Feature value: " + featureValue, highlightColor));
        lines.add(koil$tooltipLine("Main capture: " + (safeIndex + 1) + " / " + points.length, 0xFFB8C7D6));
        lines.add(koil$tooltipLine("Main market line: " + point, 0xFFFFFFFF));
        lines.add(koil$tooltipLine("Moving average: " + avg, 0xFFE8C86F));
        lines.add(koil$tooltipLine("Volume: " + row.volume(), 0xFFE6A15C));
        lines.add(koil$tooltipLine("Trend: " + koil$signed(row.trend()), row.trend() >= 0 ? 0xFFA6D989 : 0xFFE06A6A));
        lines.add(koil$tooltipLine("Volatility: " + row.volatility(), 0xFFB7A8FF));
        lines.add(koil$tooltipLine("Leader: " + row.leader() + " (" + row.leaderValue() + ")", 0xFF8FD5D0));

        if (syntheticHit != null) {
            lines.add(koil$tooltipLine("Component role: " + String.valueOf(syntheticHit[3]), 0xFFB8C7D6));
            lines.add(koil$tooltipLine("Used for: separating activity, demand, and loss pressure", 0xFF8FD5D0));
        }

        if (componentHit != null) {
            lines.add(koil$tooltipLine("Component source: " + componentHit.source(), componentHit.authoritative() ? 0xFFA6D989 : 0xFFE6A15C));
            lines.add(koil$tooltipLine("Component confidence: " + componentHit.confidence() + "%", componentHit.authoritative() ? 0xFFA6D989 : 0xFFE6A15C));
            lines.add(koil$tooltipLine("Component leader: " + componentHit.leader() + " (" + componentHit.leaderValue() + ")", 0xFF8FD5D0));
        }

        if (view != null) {
            for (String sourceLine : koil$marketRawSourceTooltipLines(view, 5)) {
                lines.add(koil$tooltipLine(sourceLine, 0xFF8E9AA8));
            }
        }

        lines.add(koil$tooltipLine(row.authoritative() ? "Source: server exact, per world/server" : "Source: client observed " + row.confidence() + "%, per world/server", row.authoritative() ? 0xFFA6D989 : 0xFFE6A15C));
        koil$setHoverTooltip(lines.toArray(new Text[0]));
    }

    @Unique
    @Nullable
    private int[] koil$nearestSeriesHover(int[] points, int x, int y, int width, int height, int min, int range, int mouseX, int mouseY, double threshold) {
        if (points == null || points.length < 2) {
            return null;
        }

        double bestDistance = Double.MAX_VALUE;
        int bestIndex = 0;
        int bestX = x;
        int bestY = y;
        int bestValue = points[0];

        int previousX = x + 1;
        int previousY = koil$chartY(y, height, points[0], min, range);

        for (int i = 1; i < points.length; i++) {
            int nextX = x + 1 + Math.round(i / (float) (points.length - 1) * Math.max(1, width - 2));
            int nextY = koil$chartY(y, height, points[i], min, range);
            double[] closest = koil$closestPointOnSegment(mouseX, mouseY, previousX, previousY, nextX, nextY);
            double distance = closest[2];

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = Math.abs(closest[0] - previousX) <= Math.abs(closest[0] - nextX) ? i - 1 : i;
                bestX = (int) Math.round(closest[0]);
                bestY = (int) Math.round(closest[1]);
                float progress = Math.max(0.0F, Math.min(1.0F, (float) closest[3]));
                bestValue = Math.round(points[i - 1] + (points[i] - points[i - 1]) * progress);
            }

            previousX = nextX;
            previousY = nextY;
        }

        if (bestDistance > threshold) {
            return null;
        }

        return new int[]{bestIndex, bestX, bestY, bestValue, Math.round((float) bestDistance * 100.0F)};
    }

    @Unique
    private double[] koil$closestPointOnSegment(int px, int py, int x1, int y1, int x2, int y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0.0D && dy == 0.0D) {
            double singleDx = px - x1;
            double singleDy = py - y1;
            return new double[]{x1, y1, Math.sqrt(singleDx * singleDx + singleDy * singleDy), 0.0D};
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0D, Math.min(1.0D, t));
        double closestX = x1 + t * dx;
        double closestY = y1 + t * dy;
        double outX = px - closestX;
        double outY = py - closestY;
        return new double[]{closestX, closestY, Math.sqrt(outX * outX + outY * outY), t};
    }

    @Unique
    private void koil$drawHoverNode(DrawContext context, int x, int y, int color) {
        context.fill(x - 2, y - 2, x + 3, y + 3, color);
        context.drawBorder(x - 3, y - 3, 7, 7, 0xDD000000);
        context.drawBorder(x - 4, y - 4, 9, 9, koil$withAlpha(color, 130));
    }

    @Unique
    private int koil$withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    @Unique
    @Nullable
    private KoilGlobalActivityRow koil$componentLineAt(List<KoilGlobalActivityRow> components, int x, int y, int width, int height, int min, int range, int mouseX, int mouseY) {
        for (int i = 0; i < components.size(); i++) {
            KoilGlobalActivityRow component = components.get(i);
            int[] points = koil$stockRenderWindow(koil$visualSeries(component.metric(), component.pointsView()), width);

            if (points == null || points.length < 2) {
                continue;
            }

            int previousX = x + 1;
            int previousY = koil$chartY(y, height, points[0], min, range);

            for (int pointIndex = 1; pointIndex < points.length; pointIndex++) {
                int nextX = x + 1 + Math.round(pointIndex / (float) (points.length - 1) * Math.max(1, width - 2));
                int nextY = koil$chartY(y, height, points[pointIndex], min, range);

                if (koil$distanceToSegment(mouseX, mouseY, previousX, previousY, nextX, nextY) <= 4.5D) {
                    return component;
                }

                previousX = nextX;
                previousY = nextY;
            }
        }

        return null;
    }

    @Unique
    private double koil$distanceToSegment(int px, int py, int x1, int y1, int x2, int y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0.0D && dy == 0.0D) {
            double singleDx = px - x1;
            double singleDy = py - y1;
            return Math.sqrt(singleDx * singleDx + singleDy * singleDy);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0D, Math.min(1.0D, t));
        double closestX = x1 + t * dx;
        double closestY = y1 + t * dy;
        double outX = px - closestX;
        double outY = py - closestY;
        return Math.sqrt(outX * outX + outY * outY);
    }

    @Unique
    private void koil$drawPreviewStockChart(DrawContext context, int x, int y, int width, int height, int[] points, int color) {
        KoilGlobalActivityRow row = new KoilGlobalActivityRow("market", "preview", "preview", "Preview", points == null || points.length == 0 ? 0 : points[points.length - 1], "server", 0, points, "preview", 100, 0, 0);
        koil$drawStockChart(context, x, y, width, height, row, color, -1, -1);
    }

    @Unique
    private void koil$drawIndicatorLine(DrawContext context, int x, int y, int width, int height, int[] points, int color, boolean dashed) {
        if (points == null || points.length < 2) {
            return;
        }

        int max = 1;
        int min = Integer.MAX_VALUE;

        for (int point : points) {
            max = Math.max(max, point);
            min = Math.min(min, point);
        }

        if (min == Integer.MAX_VALUE) {
            min = 0;
        }

        int spread = Math.max(1, Math.max(Math.abs(min), Math.abs(max)));
        min = -spread;
        max = spread;
        int range = Math.max(1, max - min);
        int zeroY = koil$chartY(y, height, 0, min, range);
        if (zeroY > y && zeroY < y + height) {
            koil$drawDashedHorizontal(context, x, zeroY, width, 0x338E9AA8);
        }
        int previousX = x + 1;
        int previousY = koil$chartY(y, height, points[0], min, range);

        for (int i = 1; i < points.length; i++) {
            int nextX = x + 1 + Math.round(i / (float) (points.length - 1) * Math.max(1, width - 2));
            int nextY = koil$chartY(y, height, points[i], min, range);

            if (dashed && i % 2 == 0) {
                previousX = nextX;
                previousY = nextY;
                continue;
            }

            koil$drawLine(context, previousX, previousY, nextX, nextY, color, x, y, x + width - 1, y + height - 1);
            previousX = nextX;
            previousY = nextY;
        }
    }

    @Unique
    private void koil$drawDashedHorizontal(DrawContext context, int x, int y, int width, int color) {
        for (int i = 0; i < width; i += 6) {
            context.fill(x + i, y, Math.min(x + width, x + i + 3), y + 1, color);
        }

        if (width > 8) {
            int endX = x + width - 1;
            context.fill(endX - 3, y - 2, endX, y - 1, color);
            context.fill(endX - 3, y + 2, endX, y + 3, color);
            context.fill(endX - 1, y - 1, endX + 1, y + 2, color);
        }
    }

    @Unique
    private void koil$drawDashedVertical(DrawContext context, int x, int y, int height, int color) {
        for (int i = 0; i < height; i += 6) {
            context.fill(x, y + i, x + 1, Math.min(y + height, y + i + 3), color);
        }

        if (height > 8) {
            int endY = y + height - 1;
            context.fill(x - 2, endY - 3, x - 1, endY, color);
            context.fill(x + 2, endY - 3, x + 3, endY, color);
            context.fill(x - 1, endY - 1, x + 2, endY + 1, color);
        }
    }

    @Unique
    private int koil$chartY(int y, int height, int value, int min, int range) {
        return y + height - 2 - Math.round((value - min) / (float) Math.max(1, range) * Math.max(1, height - 3));
    }

    @Unique
    private void koil$drawLineChart(DrawContext context, int x, int y, int width, int height, int[] points, int color) {
        context.fill(x, y, x + width, y + height, 0x22000000);
        context.drawBorder(x, y, width, height, 0x443D4A56);

        if (points == null || points.length == 0 || width <= 1 || height <= 1) {
            return;
        }

        points = koil$renderSeriesForWidth(points, width);
        int max = 1;
        int min = Integer.MAX_VALUE;

        for (int point : points) {
            max = Math.max(max, point);
            min = Math.min(min, point);
        }

        if (min == Integer.MAX_VALUE) {
            min = 0;
        }

        int range = Math.max(1, max - min);
        int previousX = x + 1;
        int previousY = y + height - 2 - Math.round((points[0] - min) / (float) range * Math.max(1, height - 3));

        for (int i = 1; i < points.length; i++) {
            int nextX = x + 1 + Math.round(i / (float) (points.length - 1) * Math.max(1, width - 2));
            int nextY = y + height - 2 - Math.round((points[i] - min) / (float) range * Math.max(1, height - 3));
            koil$drawLine(context, previousX, previousY, nextX, nextY, color, x, y, x + width - 1, y + height - 1);
            previousX = nextX;
            previousY = nextY;
        }
    }

    @Unique
    private void koil$drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color, int minX, int minY, int maxX, int maxY) {
        x1 = Math.max(minX, Math.min(maxX, x1));
        x2 = Math.max(minX, Math.min(maxX, x2));
        y1 = Math.max(minY, Math.min(maxY, y1));
        y2 = Math.max(minY, Math.min(maxY, y2));

        if (y1 == y2) {
            int left = Math.max(minX, Math.min(x1, x2));
            int right = Math.min(maxX, Math.max(x1, x2));

            if (y1 >= minY && y1 <= maxY && right >= left) {
                context.fill(left, y1, right + 1, y1 + 1, color);
            }

            return;
        }

        if (x1 == x2) {
            int top = Math.max(minY, Math.min(y1, y2));
            int bottom = Math.min(maxY, Math.max(y1, y2));

            if (x1 >= minX && x1 <= maxX && bottom >= top) {
                context.fill(x1, top, x1 + 1, bottom + 1, color);
            }

            return;
        }

        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int px = x1;
        int py = y1;
        int guard = 0;
        int limit = Math.max(1, (maxX - minX + 1) * (maxY - minY + 1));

        while (guard++ < limit) {
            if (px >= minX && px <= maxX && py >= minY && py <= maxY) {
                context.fill(px, py, px + 1, py + 1, color);
            }

            if (px == x2 && py == y2) {
                break;
            }

            int e2 = err * 2;

            if (e2 > -dy) {
                err -= dy;
                px += sx;
            }

            if (e2 < dx) {
                err += dx;
                py += sy;
            }
        }
    }


    @Unique
    private void koil$setHoverTooltip(Text... lines) {
        this.koil$hoverTooltipLines = Arrays.asList(lines);
    }

    @Unique
    private MutableText koil$tooltipLine(String value, int color) {
        return Text.literal(value == null ? "" : value).styled(style -> style.withColor(color & 0xFFFFFF));
    }

    @Unique
    private String koil$searchQuery() {
        return this.koil$searchBox == null ? "" : this.koil$searchBox.getText().trim().toLowerCase(Locale.ROOT);
    }

    @Unique
    private boolean koil$matchesSearch(String... values) {
        String query = koil$searchQuery();

        if (query.isBlank()) {
            return true;
        }

        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }

        return false;
    }

    @Unique
    private List<Object[]> koil$filterGeneralRows(List<Object[]> rows) {
        String query = koil$searchQuery();

        if (query.isBlank()) {
            return rows;
        }

        List<Object[]> filtered = new ArrayList<>();

        for (Object[] row : rows) {
            if (koil$matchesSearch(String.valueOf(row[0]), String.valueOf(row[1]), String.valueOf(row[3]), String.valueOf(row[5]))) {
                filtered.add(row);
            }
        }

        return filtered;
    }

    @Unique
    private List<Object[]> koil$filterItemRows(List<Object[]> rows) {
        String query = koil$searchQuery();

        if (query.isBlank()) {
            return rows;
        }

        List<Object[]> filtered = new ArrayList<>();

        for (Object[] row : rows) {
            if (koil$matchesSearch(String.valueOf(row[1]), String.valueOf(row[9]), koil$itemDominantMetric((Integer) row[2], (Integer) row[3], (Integer) row[4], (Integer) row[5], (Integer) row[6], (Integer) row[7]))) {
                filtered.add(row);
            }
        }

        return filtered;
    }

    @Unique
    private List<Object[]> koil$filterEntityRows(List<Object[]> rows) {
        String query = koil$searchQuery();

        if (query.isBlank()) {
            return rows;
        }

        List<Object[]> filtered = new ArrayList<>();

        for (Object[] row : rows) {
            if (koil$matchesSearch(String.valueOf(row[0]), String.valueOf(row[3]), koil$threatLabel((Integer) row[1], (Integer) row[2]))) {
                filtered.add(row);
            }
        }

        return filtered;
    }

    @Unique
    private List<KoilGlobalActivityRow> koil$filterGlobalRows(List<KoilGlobalActivityRow> rows) {
        String query = koil$searchQuery();

        if (query.isBlank()) {
            return rows;
        }

        List<KoilGlobalActivityRow> filtered = new ArrayList<>();

        for (KoilGlobalActivityRow row : rows) {
            if (koil$matchesSearch(row.title(), row.metric(), row.id(), row.leader(), row.source(), koil$marketDisplayName(row), koil$globalMetricGroup(row.metric()))) {
                filtered.add(row);
            }
        }

        return filtered;
    }

    @Unique
    private void koil$renderHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        if (this.koil$hoverTooltipLines.isEmpty()) {
            return;
        }

        context.drawTooltip(this.textRenderer, this.koil$hoverTooltipLines, Optional.empty(), mouseX, mouseY);
    }

    @Unique
    private void koil$drawSummaryIcon(DrawContext context, String name, int x, int y) {
        context.drawItemWithoutEntity(koil$summaryIconItem(name).getDefaultStack(), x, y);
    }

    @Unique
    private Item koil$summaryIconItem(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);

        if (value.contains("time")) {
            return Items.CLOCK;
        }

        if (value.contains("travel")) {
            return Items.COMPASS;
        }

        if (value.contains("combat")) {
            return Items.IRON_SWORD;
        }

        if (value.contains("item")) {
            return Items.CHEST;
        }

        if (value.contains("mob")) {
            return Items.SKELETON_SKULL;
        }

        if (value.contains("jump")) {
            return Items.FEATHER;
        }

        if (value.contains("global")) {
            return Items.EMERALD;
        }

        return Items.PAPER;
    }


    @Unique
    private void koil$drawMarketViewIcon(DrawContext context, KoilGlobalMarketViewRow view, int x, int y) {
        Item icon = Items.PAPER;

        for (String metric : view.rawMetrics()) {
            Item parsed = koil$registryItemFromMetric(koil$normalizeGlobalMetric(metric));

            if (parsed != Items.AIR) {
                icon = parsed;
                break;
            }
        }

        if (icon == Items.PAPER || icon == Items.AIR) {
            String domain = view.domain() == null ? "" : view.domain().toLowerCase(Locale.ROOT);

            if ("block".equals(domain)) {
                icon = Items.GRASS_BLOCK;
            } else if ("item".equals(domain)) {
                icon = Items.CHEST;
            } else if ("resource".equals(domain)) {
                icon = Items.DIAMOND;
            } else if ("entity".equals(domain)) {
                icon = Items.SKELETON_SKULL;
            } else if ("combat".equals(domain)) {
                icon = Items.IRON_SWORD;
            } else if ("food".equals(domain)) {
                icon = Items.BREAD;
            } else if ("trade".equals(domain)) {
                icon = Items.EMERALD;
            } else if ("equipment".equals(domain)) {
                icon = Items.SHIELD;
            }
        }

        context.drawItemWithoutEntity(icon.getDefaultStack(), x, y);
    }
    @Unique
    private void koil$drawMetricIcon(DrawContext context, KoilGlobalActivityRow row, int x, int y) {
        Item icon = koil$metricIconItem(row);
        context.drawItemWithoutEntity(icon.getDefaultStack(), x, y);
    }

    @Unique
    private Item koil$metricIconItem(KoilGlobalActivityRow row) {
        if (row == null) {
            return Items.PAPER;
        }

        String value = (row.metric() + " " + row.id() + " " + row.title()).toLowerCase(Locale.ROOT);
        Item parsed = koil$registryItemFromMetric(value);

        if (parsed != Items.AIR) {
            return parsed;
        }

        if (value.contains("mainhand") || value.contains("main hand")) {
            return Items.IRON_SWORD;
        }

        if (value.contains("offhand") || value.contains("off hand")) {
            return Items.SHIELD;
        }

        if (row.market() || value.contains("market") || value.contains("trade") || value.contains("buy") || value.contains("sell")) {
            return Items.EMERALD;
        }

        if (value.contains("break") || value.contains("mine")) {
            return Items.IRON_PICKAXE;
        }

        if (value.contains("place") || value.contains("build")) {
            return Items.STONE;
        }

        if (value.contains("craft")) {
            return Items.CRAFTING_TABLE;
        }

        if (koil$isFoodMetricText(value)) {
            return Items.BREAD;
        }

        if (value.contains("kill") || value.contains("damage")) {
            return Items.IRON_SWORD;
        }

        if (value.contains("death")) {
            return Items.SKELETON_SKULL;
        }

        if (value.contains("item")) {
            return Items.CHEST;
        }

        if (value.contains("block")) {
            return Items.GRASS_BLOCK;
        }

        return Items.PAPER;
    }

    @Unique
    private Item koil$registryItemFromMetric(String metric) {
        String[] prefixes = new String[]{"block_broken/", "block_place_intent/", "block_placed/", "block_used/", "block_use/", "item_use_intent/", "item_used/", "item_crafted/", "item_picked_up/", "item_dropped/", "item_traded/", "food_eaten/", "item_eaten/", "mob_killed/", "entity_killed/", "killed_by/", "mainhand/", "offhand/", "self_mainhand/", "self_offhand/"};

        for (String prefix : prefixes) {
            int prefixIndex = metric.indexOf(prefix);

            if (prefixIndex < 0) {
                continue;
            }

            String rest = metric.substring(prefixIndex + prefix.length());
            int separator = rest.indexOf('/');

            if (separator <= 0 || separator >= rest.length() - 1) {
                Identifier simpleId = koil$registryIdFromLooseName(rest);

                if (simpleId == null) {
                    return Items.AIR;
                }

                if (prefix.startsWith("block_")) {
                    Block block = Registries.BLOCK.get(simpleId);
                    Item item = block.asItem();
                    return item == Items.AIR ? Items.AIR : item;
                }

                Item item = Registries.ITEM.get(simpleId);
                return item == null || item == Items.AIR ? Items.AIR : item;
            }

            Identifier id;

            try {
                id = new Identifier(rest.substring(0, separator), rest.substring(separator + 1));
            } catch (Exception ignored) {
                return Items.AIR;
            }

            if (prefix.startsWith("block_")) {
                Block block = Registries.BLOCK.get(id);
                Item item = block.asItem();
                return item == Items.AIR ? Items.STONE : item;
            }

            Item item = Registries.ITEM.get(id);
            return item == null || item == Items.AIR ? Items.AIR : item;
        }

        return Items.AIR;
    }


    @Unique
    @Nullable
    private Identifier koil$registryIdFromLooseName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String clean = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');

        try {
            Identifier minecraft = new Identifier("minecraft", clean);

            if (Registries.ITEM.get(minecraft) != Items.AIR || Registries.BLOCK.get(minecraft).asItem() != Items.AIR) {
                return minecraft;
            }
        } catch (Exception ignored) {
        }

        return null;
    }
    @Unique
    private int[] koil$miniPreviewSeriesForView(KoilGlobalMarketViewRow view) {
        if (view == null || view.chartRow() == null) {
            return new int[0];
        }

        String cacheKey = koil$marketCacheKey(view) + "|" + this.koil$stockGraphInterval + "|" + KoilMarketSeriesWindow.daySampleIndex(koil$worldTimeForStockWindow());
        int[] cached = this.koil$cachedMiniPreviewSeries.get(cacheKey);

        if (cached != null) {
            return cached;
        }

        KoilGlobalActivityRow row = view.chartRow();
        int[] result = koil$stockRenderWindow(koil$marketMainPressureSeries(view, row.pointsView()), 184);
        this.koil$cachedMiniPreviewSeries.put(cacheKey, result);
        return result;
    }

    @Unique
    private void koil$drawMiniStockPreview(DrawContext context, int x, int y, int width, int height, int[] points, int color) {
        context.fill(x, y, x + width, y + height, 0x18000000);
        koil$drawIndicatorLine(context, x + 1, y + 1, width - 2, height - 2, points, color, false);
    }

    @Unique
    private void koil$drawStockDataRibbon(DrawContext context, int x, int y, int width, KoilGlobalActivityRow row, @Nullable KoilGlobalMarketViewRow view, int color, int changeColor) {
        int cellGap = 5;
        int cellCount = width < 520 ? 3 : 6;
        int cellWidth = Math.max(58, (width - cellGap * (cellCount - 1)) / cellCount);
        int[] displayPoints = view == null ? koil$stockWindow(koil$visualSeries(row.metric(), row.pointsView())) : koil$stockWindow(koil$marketMainPressureSeries(view, row.pointsView()));
        int[][] displayCandles = koil$candlesFromSeries(displayPoints);
        Object[][] cells = new Object[][]{
                {"open", String.valueOf(koil$latest(displayCandles[0])), 0xFFB8C7D6},
                {"high", String.valueOf(koil$latest(displayCandles[1])), 0xFFA6D989},
                {"low", String.valueOf(koil$latest(displayCandles[2])), 0xFFE06A6A},
                {"close", String.valueOf(koil$latest(displayCandles[3])), color},
                {"window", KoilMarketSeriesWindow.label(this.koil$stockGraphInterval), 0xFF55DDE8},
                {"trend", koil$signed(row.trend()), changeColor},
                {"volatility", koil$compact(row.volatility()), 0xFFB7A8FF},
                {"leader", koil$ellipsize(row.leader(), 54), 0xFF8FD5D0},
                {"conf", row.confidence() + "%", row.authoritative() ? 0xFFA6D989 : 0xFFE6A15C}
        };

        for (int i = 0; i < cells.length; i++) {
            int column = i % cellCount;
            int rowIndex = i / cellCount;
            int cellX = x + column * (cellWidth + cellGap);
            int cellY = y + rowIndex * 22;
            context.fill(cellX, cellY, cellX + cellWidth, cellY + 18, 0x14000000);
            context.drawText(this.textRenderer, Text.literal((String) cells[i][0]), cellX + 4, cellY + 2, 0xFF718091, false);
            context.drawText(this.textRenderer, Text.literal(String.valueOf(cells[i][1])), cellX + 4, cellY + 10, (Integer) cells[i][2], false);
        }
    }

    @Unique
    private void koil$drawChartValueTag(DrawContext context, int x, int y, String value, int color) {
        int width = this.textRenderer.getWidth(value) + 5;
        context.fill(x, y, x + width, y + 10, 0x66000000);
        context.drawText(this.textRenderer, Text.literal(value), x + 2, y + 1, color, false);
    }

    @Unique
    private void koil$drawChartLegend(DrawContext context, int x, int y, KoilGlobalActivityRow row, int color) {
        int labelColor = 0xCCB8C7D6;
        context.fill(x, y, x + 7, y + 2, 0xFFFFFFFF);
        context.drawText(this.textRenderer, Text.literal("main"), x + 10, y - 3, labelColor, false);
        context.fill(x + 44, y, x + 49, y + 2, 0x99E8C86F);
        context.drawText(this.textRenderer, Text.literal("avg"), x + 52, y - 3, labelColor, false);
        context.fill(x + 82, y, x + 87, y + 2, 0x55718091);
        context.drawText(this.textRenderer, Text.literal("volume"), x + 90, y - 3, labelColor, false);
        String state = row.change() > 0 ? "rising" : row.change() < 0 ? "falling" : "flat";
        int stateColor = row.change() > 0 ? 0xFFA6D989 : row.change() < 0 ? 0xFFE06A6A : 0xFF8E9AA8;
        context.drawText(this.textRenderer, Text.literal(state), x + 146, y - 3, stateColor, false);
    }

    @Unique
    private void koil$drawVerticalGuideLines(DrawContext context, int x, int y, int width, int height, int color) {
        int segments = 6;

        for (int i = 1; i < segments; i++) {
            int lineX = x + Math.round(i / (float) segments * width);

            for (int py = y; py < y + height; py += 6) {
                context.fill(lineX, py, lineX + 1, Math.min(y + height, py + 3), color);
            }
        }
    }

    @Unique
    private void koil$drawVolumeBars(DrawContext context, int x, int y, int width, int height, int[] points, int color, KoilGlobalActivityRow row, int mouseX, int mouseY) {
        if (points == null || points.length == 0 || width <= 1 || height <= 1) {
            return;
        }

        context.fill(x, y, x + width, y + height, 0x26000000);
        context.drawBorder(x, y, width, height, 0x223D4A56);

        int max = 1;

        for (int i = 1; i < points.length; i++) {
            max = Math.max(max, Math.abs(points[i] - points[i - 1]));
        }

        int barWidth = Math.max(2, width / Math.max(1, points.length) - 1);

        for (int i = 1; i < points.length; i++) {
            int delta = points[i] - points[i - 1];
            int volume = Math.abs(delta);
            int barHeight = Math.max(2, Math.round(volume / (float) max * Math.max(2, height - 3)));
            int barX = x + Math.round(i / (float) Math.max(1, points.length - 1) * Math.max(1, width - barWidth));
            int barTop = y + height - barHeight - 1;
            int barColor = delta >= 0 ? 0xBBA6D989 : 0xBBE06A6A;
            context.fill(barX, barTop, barX + barWidth, y + height - 1, barColor);

            if (koil$isMouseInsideStatsViewport(mouseX, mouseY) && mouseX >= barX - 1 && mouseX <= barX + barWidth + 1 && mouseY >= barTop - 1 && mouseY <= y + height && this.koil$hoverTooltipLines.isEmpty()) {
                context.fill(barX - 1, barTop - 1, barX + barWidth + 1, y + height, koil$withAlpha(barColor, 255));
                context.drawBorder(barX - 2, barTop - 2, barWidth + 4, Math.max(4, y + height - barTop + 2), 0xFFFFFFFF);
                koil$setHoverTooltip(
                        koil$tooltipLine(row.title(), 0xFFE7EDF4),
                        koil$tooltipLine("Feature: volume bar", 0xFFE6A15C),
                        koil$tooltipLine("Capture: " + (i + 1) + " / " + points.length, 0xFFB8C7D6),
                        koil$tooltipLine("Activity volume: " + volume, 0xFFE6A15C),
                        koil$tooltipLine("Movement: " + koil$signed(delta), delta >= 0 ? 0xFFA6D989 : 0xFFE06A6A),
                        koil$tooltipLine(delta >= 0 ? "Meaning: positive market pressure" : "Meaning: negative market pressure", delta >= 0 ? 0xFFA6D989 : 0xFFE06A6A),
                        koil$tooltipLine(row.authoritative() ? "Source: server exact" : "Source: client observed " + row.confidence() + "%", row.authoritative() ? 0xFFA6D989 : 0xFFE6A15C)
                );
            }
        }
    }

    @Unique
    private void koil$requestLiveVanillaStatsIfNeeded() {
        long now = Util.getMeasuringTimeMs();

        if (now - this.koil$lastLiveVanillaStatsRequestMs >= 2000L) {
            this.koil$lastLiveVanillaStatsRequestMs = now;

            try {
                MinecraftClient client = MinecraftClient.getInstance();

                if (client != null && client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
                }
            } catch (Exception ignored) {
            }
        }

        int fingerprint = koil$liveVanillaStatsFingerprint();

        if (fingerprint != this.koil$lastLiveVanillaStatsFingerprint) {
            this.koil$lastLiveVanillaStatsFingerprint = fingerprint;
            koil$invalidateRowCache();
        }
    }

    @Unique
    private int koil$liveVanillaStatsFingerprint() {
        long now = Util.getMeasuringTimeMs();

        if (this.koil$cachedLiveVanillaStatsFingerprint != Integer.MIN_VALUE && now - this.koil$lastFingerprintScanMs < 850L) {
            return this.koil$cachedLiveVanillaStatsFingerprint;
        }

        this.koil$lastFingerprintScanMs = now;
        this.koil$cachedLiveVanillaStatsFingerprint = koil$computeLiveVanillaStatsFingerprint();
        return this.koil$cachedLiveVanillaStatsFingerprint;
    }

    @Unique
    private int koil$computeLiveVanillaStatsFingerprint() {
        int result = 1;
        result = 31 * result + koil$summaryPlayTimeTicks();
        result = 31 * result + koil$summaryTravelCm();
        result = 31 * result + koil$summaryJumps();
        result = 31 * result + koil$summaryDeaths();
        result = 31 * result + koil$summaryMobKills();
        result = 31 * result + koil$summaryPlayerKills();
        result = 31 * result + koil$summaryDamageDealt();
        result = 31 * result + koil$summaryDamageTaken();
        result = 31 * result + koil$liveGeneralStatsFingerprint();
        result = 31 * result + koil$liveItemStatsFingerprint();
        result = 31 * result + koil$liveEntityStatsFingerprint();
        return result;
    }

    @Unique
    private int koil$liveGeneralStatsFingerprint() {
        int result = 1;

        for (Stat<Identifier> stat : Stats.CUSTOM) {
            try {
                int value = this.statHandler.getStat(stat);

                if (value != 0) {
                    result = 31 * result + stat.getValue().hashCode();
                    result = 31 * result + value;
                }
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    @Unique
    private int koil$liveItemStatsFingerprint() {
        int result = 1;

        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }

            int total = 0;

            if (item instanceof BlockItem blockItem) {
                total += koil$blockStatValue(Stats.MINED, blockItem.getBlock());
            }

            total += koil$itemStatValue(Stats.BROKEN, item);
            total += koil$itemStatValue(Stats.CRAFTED, item);
            total += koil$itemStatValue(Stats.USED, item);
            total += koil$itemStatValue(Stats.PICKED_UP, item);
            total += koil$itemStatValue(Stats.DROPPED, item);

            if (total != 0) {
                result = 31 * result + Item.getRawId(item);
                result = 31 * result + total;
            }
        }

        return result;
    }

    @Unique
    private int koil$liveEntityStatsFingerprint() {
        int result = 1;

        for (EntityType<?> entityType : Registries.ENTITY_TYPE) {
            int killed = 0;
            int killedBy = 0;

            try {
                killed = this.statHandler.getStat(Stats.KILLED.getOrCreateStat(entityType));
                killedBy = this.statHandler.getStat(Stats.KILLED_BY.getOrCreateStat(entityType));
            } catch (Exception ignored) {
            }

            if (killed != 0 || killedBy != 0) {
                result = 31 * result + Registries.ENTITY_TYPE.getRawId(entityType);
                result = 31 * result + killed;
                result = 31 * result + killedBy;
            }
        }

        return result;
    }

    @Unique
    private List<Object[]> koil$overviewCards() {
        koil$refreshRowCache();
        return this.koil$cachedOverviewCards;
    }

    @Unique
    private void koil$invalidateRowCache() {
        this.koil$rowCacheTimeMs = 0L;
        this.koil$rowCacheKey = "";
        this.koil$generalCacheKey = "";
        this.koil$itemCacheKey = "";
        this.koil$entityCacheKey = "";
        this.koil$globalCacheKey = "";
        this.koil$cachedLiveVanillaStatsFingerprint = Integer.MIN_VALUE;
        this.koil$cachedMarketPressureSeries = new HashMap<>();
        this.koil$cachedSyntheticSeries = new HashMap<>();
        this.koil$cachedMiniPreviewSeries = new HashMap<>();
    }

    @Unique
    private void koil$refreshRowCache() {
        long now = Util.getMeasuringTimeMs();
        String query = koil$searchQuery();
        int liveFingerprint = koil$liveVanillaStatsFingerprint();
        String sizeKey = this.width + "|" + this.height;
        String vanillaKey = query + "|" + liveFingerprint + "|" + sizeKey;
        String globalKey = query + "|" + GlobalActivityClient.updatedAt() + "|" + GlobalActivityClient.contextKey() + "|" + sizeKey;
        boolean changed = false;

        if (KOIL_PAGE_GENERAL.equals(this.koil$activePage) && !vanillaKey.equals(this.koil$generalCacheKey)) {
            this.koil$generalCacheKey = vanillaKey;
            this.koil$cachedGeneralRows = koil$filterGeneralRows(koil$generalRows());
            this.koil$cachedGeneralMax = koil$maxIntColumn(this.koil$cachedGeneralRows, 2);
            this.koil$cachedGeneralCount = this.koil$cachedGeneralRows.size();
            changed = true;
        }

        if (KOIL_PAGE_ITEMS.equals(this.koil$activePage) && !vanillaKey.equals(this.koil$itemCacheKey)) {
            this.koil$itemCacheKey = vanillaKey;
            this.koil$cachedItemRows = koil$filterItemRows(koil$itemRows());
            this.koil$cachedItemMax = koil$maxIntColumn(this.koil$cachedItemRows, 8);
            this.koil$cachedItemCount = this.koil$cachedItemRows.size();
            changed = true;
        }

        if (KOIL_PAGE_MOBS.equals(this.koil$activePage) && !vanillaKey.equals(this.koil$entityCacheKey)) {
            this.koil$entityCacheKey = vanillaKey;
            this.koil$cachedEntityRows = koil$filterEntityRows(koil$entityRows());
            this.koil$cachedEntityMax = koil$maxEntityColumn(this.koil$cachedEntityRows);
            this.koil$cachedEntityCount = this.koil$cachedEntityRows.size();
            changed = true;
        }

        if ((KOIL_PAGE_GLOBAL.equals(this.koil$activePage) || this.koil$cachedAllMarketValueRows.isEmpty()) && !globalKey.equals(this.koil$globalCacheKey)) {
            this.koil$globalCacheKey = globalKey;
            this.koil$cachedMarketRows = koil$sortGlobalRows(koil$filterGlobalRows(GlobalActivityClient.marketRows()));
            List<KoilGlobalActivityRow> activityRows = koil$sortGlobalRows(koil$filterGlobalRows(GlobalActivityClient.activityRows()));
            List<KoilGlobalActivityRow> importantRows = new ArrayList<>();
            List<KoilGlobalActivityRow> developerRows = new ArrayList<>();

            for (KoilGlobalActivityRow row : activityRows) {
                if (koil$isImportantGlobalMetric(row.metric())) {
                    importantRows.add(row);
                } else {
                    developerRows.add(row);
                }
            }

            this.koil$cachedImportantRows = importantRows;
            this.koil$cachedDeveloperRows = developerRows;
            List<KoilGlobalActivityRow> hierarchySourceRows = koil$globalHierarchySourceRows();
            Map<String, KoilGlobalActivityRow> globalRowsByMetric = new LinkedHashMap<>();

            for (KoilGlobalActivityRow row : hierarchySourceRows) {
                if (row == null || row.metric() == null || row.metric().isBlank()) {
                    continue;
                }

                globalRowsByMetric.putIfAbsent(koil$normalizeGlobalMetric(row.metric()), row);
            }

            this.koil$cachedGlobalRowsByMetric = globalRowsByMetric;
            Map<String, List<KoilGlobalMarketViewRow>> hierarchy = KoilGlobalMarketViewRow.buildHierarchy(hierarchySourceRows, query);
            this.koil$cachedHeadMarketRows = hierarchy.getOrDefault("head", Collections.emptyList());
            this.koil$cachedSubMarketRows = hierarchy.getOrDefault("sub", Collections.emptyList());
            this.koil$cachedRawMarketRows = hierarchy.getOrDefault("raw", Collections.emptyList());
            this.koil$cachedDevMarketRows = hierarchy.getOrDefault("dev", Collections.emptyList());
            List<KoilGlobalMarketViewRow> allMarketRows = new ArrayList<>();
            allMarketRows.addAll(this.koil$cachedHeadMarketRows);
            allMarketRows.addAll(this.koil$cachedSubMarketRows);
            allMarketRows.addAll(this.koil$cachedRawMarketRows);
            allMarketRows.addAll(this.koil$cachedDevMarketRows);
            this.koil$cachedAllMarketValueRows = Collections.unmodifiableList(allMarketRows);
            this.koil$cachedMarketQuotes = new HashMap<>();
            this.koil$cachedMarketComponents = new HashMap<>();
            this.koil$cachedMarketPressureSeries = new HashMap<>();
            this.koil$cachedSyntheticSeries = new HashMap<>();
            this.koil$cachedMiniPreviewSeries = new HashMap<>();
            this.koil$cachedGlobalCount = allMarketRows.size();
            changed = true;
        }

        if (changed || this.koil$cachedOverviewCards.isEmpty() || now - this.koil$rowCacheTimeMs > 1000L) {
            this.koil$rowCacheTimeMs = now;
            this.koil$cachedOverviewCards = koil$buildOverviewCards();
        }
    }

    @Unique
    private int koil$maxIntColumn(List<Object[]> rows, int column) {
        int max = 1;

        for (Object[] row : rows) {
            max = Math.max(max, (Integer) row[column]);
        }

        return max;
    }

    @Unique
    private int koil$maxEntityColumn(List<Object[]> rows) {
        int max = 1;

        for (Object[] row : rows) {
            max = Math.max(max, Math.max((Integer) row[1], (Integer) row[2]));
        }

        return max;
    }

    @Unique
    private List<Object[]> koil$buildOverviewCards() {
        int playTicks = koil$summaryPlayTimeTicks();
        int deaths = koil$summaryDeaths();
        int kills = koil$summaryMobKills();
        int playerKills = koil$summaryPlayerKills();
        int distanceCm = koil$summaryTravelCm();
        int jumps = koil$summaryJumps();
        int globalCount = Math.max(this.koil$cachedGlobalCount, this.koil$cachedHeadMarketRows.size() + this.koil$cachedSubMarketRows.size() + this.koil$cachedRawMarketRows.size() + this.koil$cachedDevMarketRows.size());
        int damageDealt = koil$summaryDamageDealt();
        int damageTaken = koil$summaryDamageTaken();
        int combatTotal = koil$summaryCombatActivity(kills, playerKills, deaths, damageDealt, damageTaken);
        List<Object[]> cards = new ArrayList<>();
        cards.add(new Object[]{"Time", koil$formatTime(playTicks), Math.min(1.0F, playTicks / 360000.0F), 0xFF8FC5FF});
        cards.add(new Object[]{"Travel", koil$formatDistance(distanceCm), Math.min(1.0F, distanceCm / 1000000.0F), 0xFFA6D989});
        cards.add(new Object[]{"Combat", kills + "M/" + playerKills + "P/" + deaths + "D", Math.min(1.0F, combatTotal / 5000.0F), deaths > kills + playerKills ? 0xFFE06A6A : 0xFFA6D989});
        cards.add(new Object[]{"Items", String.valueOf(Math.max(this.koil$cachedItemCount, this.koil$cachedItemRows.size())), Math.min(1.0F, Math.max(this.koil$cachedItemCount, this.koil$cachedItemRows.size()) / 128.0F), 0xFFE8C86F});
        cards.add(new Object[]{"Mobs", String.valueOf(Math.max(this.koil$cachedEntityCount, this.koil$cachedEntityRows.size())), Math.min(1.0F, Math.max(this.koil$cachedEntityCount, this.koil$cachedEntityRows.size()) / 64.0F), 0xFFE6A15C});
        cards.add(new Object[]{"Jumps", koil$compact(jumps), Math.min(1.0F, jumps / 10000.0F), 0xFF8FD5D0});
        cards.add(new Object[]{"Global", String.valueOf(globalCount), Math.min(1.0F, globalCount / 64.0F), globalCount > 0 ? 0xFFB7A8FF : 0xFF718091});
        return cards;
    }

    @Unique
    private boolean koil$isRenderRowVisible(int y, int height) {
        return y + height >= this.koil$statsViewportY - 8 && y <= this.koil$statsViewportY + this.koil$statsViewportHeight + 8;
    }

    @Unique
    private List<Object[]> koil$generalRows() {
        List<Object[]> rows = new ArrayList<>();

        for (Stat<Identifier> stat : Stats.CUSTOM) {
            Identifier id = stat.getValue();
            int raw = this.statHandler.getStat(stat);
            String translationKey = "stat." + id.toString().replace(':', '.');
            String translated = I18n.translate(translationKey);
            String label = translated.equals(translationKey) ? koil$formatIdentifierName(id.getPath()) : translated;
            String category = koil$generalCategory(id);
            int color = koil$categoryColor(category);
            String source = id.getNamespace().equals("minecraft") ? "minecraft" : "modded: " + id.getNamespace();
            rows.add(new Object[]{label, stat.format(raw), raw, category, color, source});
        }

        rows.sort((a, b) -> {
            int active = Boolean.compare((Integer) a[2] <= 0, (Integer) b[2] <= 0);

            if (active != 0) {
                return active;
            }

            int category = ((String) a[3]).compareToIgnoreCase((String) b[3]);

            if (category != 0) {
                return category;
            }

            return ((String) a[0]).compareToIgnoreCase((String) b[0]);
        });
        return rows;
    }

    @Unique
    private List<Object[]> koil$itemRows() {
        List<Object[]> rows = new ArrayList<>();

        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }

            int mined = 0;
            Identifier id = Registries.ITEM.getId(item);

            if (item instanceof BlockItem blockItem) {
                mined = koil$blockStatValue(Stats.MINED, blockItem.getBlock());
            }

            int broken = koil$itemStatValue(Stats.BROKEN, item);
            int crafted = koil$itemStatValue(Stats.CRAFTED, item);
            int used = koil$itemStatValue(Stats.USED, item);
            int pickedUp = koil$itemStatValue(Stats.PICKED_UP, item);
            int dropped = koil$itemStatValue(Stats.DROPPED, item);
            int total = mined + broken + crafted + used + pickedUp + dropped;

            if (total <= 0) {
                continue;
            }

            String source = id.getNamespace().equals("minecraft") ? "minecraft" : "modded: " + id.getNamespace();
            rows.add(new Object[]{item, item.getName().getString(), mined, used, crafted, pickedUp, dropped, broken, total, source});
        }

        rows.sort((a, b) -> {
            int total = Integer.compare((Integer) b[8], (Integer) a[8]);

            if (total != 0) {
                return total;
            }

            return ((String) a[1]).compareToIgnoreCase((String) b[1]);
        });
        return rows;
    }

    @Unique
    private List<Object[]> koil$entityRows() {
        List<Object[]> rows = new ArrayList<>();

        for (EntityType<?> entityType : Registries.ENTITY_TYPE) {
            int killed = this.statHandler.getStat(Stats.KILLED.getOrCreateStat(entityType));
            int killedBy = this.statHandler.getStat(Stats.KILLED_BY.getOrCreateStat(entityType));

            if (killed <= 0 && killedBy <= 0) {
                continue;
            }

            Identifier id = Registries.ENTITY_TYPE.getId(entityType);
            String source = id.getNamespace().equals("minecraft") ? "minecraft" : "modded: " + id.getNamespace();
            rows.add(new Object[]{entityType.getName().getString(), killed, killedBy, source});
        }

        rows.sort((a, b) -> {
            int total = Integer.compare((Integer) b[1] + (Integer) b[2], (Integer) a[1] + (Integer) a[2]);

            if (total != 0) {
                return total;
            }

            return ((String) a[0]).compareToIgnoreCase((String) b[0]);
        });
        return rows;
    }

    @Unique
    private String koil$pageFromList(@Nullable AlwaysSelectedEntryListWidget<?> selectedList) {
        if (selectedList == null) {
            return "";
        }

        String className = selectedList.getClass().getName().toLowerCase(Locale.ROOT);

        if (className.contains("item")) {
            return KOIL_PAGE_ITEMS;
        }

        if (className.contains("entity") || className.contains("mob")) {
            return KOIL_PAGE_MOBS;
        }

        if (className.contains("general")) {
            return KOIL_PAGE_GENERAL;
        }

        return "";
    }

    @Unique
    private String koil$pageFromButton(ClickableWidget widget) {
        String label = widget.getMessage().getString().toLowerCase(Locale.ROOT);

        if (label.contains("item")) {
            return KOIL_PAGE_ITEMS;
        }

        if (label.contains("mob") || label.contains("entity")) {
            return KOIL_PAGE_MOBS;
        }

        if (label.contains("general")) {
            return KOIL_PAGE_GENERAL;
        }

        if (label.contains("global")) {
            return KOIL_PAGE_GLOBAL;
        }

        return "";
    }

    @Unique
    private int koil$totalDistanceCm() {
        return koil$summaryTravelCm();
    }

    @Unique
    private int koil$summaryPlayTimeTicks() {
        return Math.max(koil$customRaw(KOIL_PLAY_TIME_ID), koil$customRawByPath("play_time", "total_world_time"));
    }

    @Unique
    private int koil$summaryDeaths() {
        return Math.max(koil$customRaw(KOIL_DEATHS_ID), koil$customRawByPath("deaths"));
    }

    @Unique
    private int koil$summaryMobKills() {
        return Math.max(koil$customRaw(KOIL_MOB_KILLS_ID), koil$customRawByPath("mob_kills", "mobs_killed"));
    }

    @Unique
    private int koil$summaryPlayerKills() {
        return Math.max(koil$customRaw(KOIL_PLAYER_KILLS_ID), koil$customRawByPath("player_kills"));
    }

    @Unique
    private int koil$summaryDamageDealt() {
        return Math.max(koil$customRaw(KOIL_DAMAGE_DEALT_ID), koil$customRawByPath("damage_dealt"));
    }

    @Unique
    private int koil$summaryDamageTaken() {
        return Math.max(koil$customRaw(KOIL_DAMAGE_TAKEN_ID), koil$customRawByPath("damage_taken"));
    }

    @Unique
    private int koil$summaryJumps() {
        return Math.max(koil$customRaw(KOIL_JUMP_ID), koil$customRawByPath("jump", "jumps"));
    }

    @Unique
    private int koil$summaryTravelCm() {
        int direct = koil$customRawByPath("walk_one_cm", "crouch_one_cm", "sprint_one_cm", "swim_one_cm", "fall_one_cm", "climb_one_cm", "fly_one_cm", "walk_on_water_one_cm", "walk_under_water_one_cm", "minecart_one_cm", "boat_one_cm", "pig_one_cm", "horse_one_cm", "aviate_one_cm", "strider_one_cm");
        int old = koil$customRaw(KOIL_WALK_ONE_CM_ID)
                + koil$customRaw(KOIL_CROUCH_ONE_CM_ID)
                + koil$customRaw(KOIL_SPRINT_ONE_CM_ID)
                + koil$customRaw(KOIL_SWIM_ONE_CM_ID)
                + koil$customRaw(KOIL_FALL_ONE_CM_ID)
                + koil$customRaw(KOIL_CLIMB_ONE_CM_ID)
                + koil$customRaw(KOIL_FLY_ONE_CM_ID)
                + koil$customRaw(KOIL_WALK_ON_WATER_ONE_CM_ID)
                + koil$customRaw(KOIL_WALK_UNDER_WATER_ONE_CM_ID)
                + koil$customRaw(KOIL_MINECART_ONE_CM_ID)
                + koil$customRaw(KOIL_BOAT_ONE_CM_ID)
                + koil$customRaw(KOIL_PIG_ONE_CM_ID)
                + koil$customRaw(KOIL_HORSE_ONE_CM_ID)
                + koil$customRaw(KOIL_AVIATE_ONE_CM_ID)
                + koil$customRaw(KOIL_STRIDER_ONE_CM_ID);
        return Math.max(direct, old);
    }

    @Unique
    private int koil$summaryCombatActivity(int kills, int playerKills, int deaths, int damageDealt, int damageTaken) {
        return Math.max(0, kills) + Math.max(0, playerKills) + Math.max(0, deaths) + Math.max(0, damageDealt) + Math.max(0, damageTaken);
    }

    @Unique
    private int koil$customRawByPath(String... paths) {
        int total = 0;
        Set<String> wanted = new HashSet<>();

        for (String path : paths) {
            if (path != null && !path.isBlank()) {
                wanted.add(path);
            }
        }

        for (Stat<Identifier> stat : Stats.CUSTOM) {
            Identifier value = stat.getValue();

            if (value == null || !wanted.contains(value.getPath())) {
                continue;
            }

            try {
                total += Math.max(0, this.statHandler.getStat(stat));
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    @Unique
    private int koil$customRaw(Identifier id) {
        try {
            Stat<Identifier> stat = Stats.CUSTOM.getOrCreateStat(id);
            return this.statHandler.getStat(stat);
        } catch (Exception ignored) {
            return 0;
        }
    }

    @Unique
    private int koil$itemStatValue(StatType<Item> statType, Item item) {
        try {
            return this.statHandler.getStat(statType.getOrCreateStat(item));
        } catch (Exception ignored) {
            return 0;
        }
    }

    @Unique
    private int koil$blockStatValue(StatType<Block> statType, Block block) {
        try {
            return this.statHandler.getStat(statType.getOrCreateStat(block));
        } catch (Exception ignored) {
            return 0;
        }
    }

    @Unique
    private List<KoilGlobalActivityRow> koil$globalHierarchySourceRows() {
        List<KoilGlobalActivityRow> rows = new ArrayList<>();

        if (GlobalActivityClient.hasServerData()) {
            rows.addAll(GlobalActivityClient.activityRows());
            rows.addAll(GlobalActivityClient.marketRows());
            return koil$dedupeGlobalRows(rows, true);
        }

        rows.addAll(koil$localStoredGlobalRows());
        rows.addAll(GlobalActivityClient.activityRows());
        rows.addAll(GlobalActivityClient.marketRows());
        return rows;
    }

    @Unique
    private List<KoilGlobalActivityRow> koil$dedupeGlobalRows(List<KoilGlobalActivityRow> rows, boolean preferAuthoritative) {
        Map<String, KoilGlobalActivityRow> byKey = new LinkedHashMap<>();

        for (KoilGlobalActivityRow row : rows) {
            if (row == null || row.metric() == null || row.metric().isBlank()) {
                continue;
            }

            String key = row.type() + "|" + koil$normalizeGlobalMetric(row.metric());
            KoilGlobalActivityRow existing = byKey.get(key);

            if (existing == null) {
                byKey.put(key, row);
                continue;
            }

            if (preferAuthoritative && row.authoritative() && !existing.authoritative()) {
                byKey.put(key, row);
            } else if (row.confidence() > existing.confidence() && row.value() >= existing.value()) {
                byKey.put(key, row);
            }
        }

        return new ArrayList<>(byKey.values());
    }

    @Unique
    private List<KoilGlobalActivityRow> koil$localStoredGlobalRows() {
        List<KoilGlobalActivityRow> rows = new ArrayList<>();
        String playerName = koil$localPlayerName();
        int blocksBroken = 0;
        int blocksPlaced = 0;
        int itemsUsed = 0;
        int itemsCrafted = 0;
        int itemsPickedUp = 0;
        int itemsDropped = 0;
        int foodEaten = 0;
        int mobsKilled = 0;
        int entityDeaths = 0;

        for (Block block : Registries.BLOCK) {
            int broken = koil$blockStatValue(Stats.MINED, block);

            if (broken > 0) {
                Identifier id = Registries.BLOCK.getId(block);
                String metric = "block_broken/" + id.getNamespace() + "/" + id.getPath();
                rows.add(koil$localStoredRow(metric, broken, playerName));
                blocksBroken += broken;
            }
        }

        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }

            Identifier itemId = Registries.ITEM.getId(item);
            int used = koil$itemStatValue(Stats.USED, item);
            int crafted = koil$itemStatValue(Stats.CRAFTED, item);
            int pickedUp = koil$itemStatValue(Stats.PICKED_UP, item);
            int dropped = koil$itemStatValue(Stats.DROPPED, item);

            if (item instanceof BlockItem blockItem && used > 0) {
                Identifier blockId = Registries.BLOCK.getId(blockItem.getBlock());
                String metric = "block_placed/" + blockId.getNamespace() + "/" + blockId.getPath();
                rows.add(koil$localStoredRow(metric, used, playerName));
                blocksPlaced += used;
            }

            if (used > 0) {
                rows.add(koil$localStoredRow("item_used/" + itemId.getNamespace() + "/" + itemId.getPath(), used, playerName));
                itemsUsed += used;
            }

            if (crafted > 0) {
                rows.add(koil$localStoredRow("item_crafted/" + itemId.getNamespace() + "/" + itemId.getPath(), crafted, playerName));
                itemsCrafted += crafted;
            }

            if (pickedUp > 0) {
                rows.add(koil$localStoredRow("item_picked_up/" + itemId.getNamespace() + "/" + itemId.getPath(), pickedUp, playerName));
                itemsPickedUp += pickedUp;
            }

            if (dropped > 0) {
                rows.add(koil$localStoredRow("item_dropped/" + itemId.getNamespace() + "/" + itemId.getPath(), dropped, playerName));
                itemsDropped += dropped;
            }

            try {
                if (item.getFoodComponent() != null && used > 0) {
                    rows.add(koil$localStoredRow("food_eaten/" + itemId.getNamespace() + "/" + itemId.getPath(), used, playerName));
                    foodEaten += used;
                }
            } catch (Exception ignored) {
            }
        }

        for (EntityType<?> entityType : Registries.ENTITY_TYPE) {
            int killed = this.statHandler.getStat(Stats.KILLED.getOrCreateStat(entityType));
            int killedBy = this.statHandler.getStat(Stats.KILLED_BY.getOrCreateStat(entityType));
            Identifier id = Registries.ENTITY_TYPE.getId(entityType);

            if (killed > 0) {
                rows.add(koil$localStoredRow("mob_killed/" + id.getNamespace() + "/" + id.getPath(), killed, playerName));
                mobsKilled += killed;
            }

            if (killedBy > 0) {
                rows.add(koil$localStoredRow("killed_by/" + id.getNamespace() + "/" + id.getPath(), killedBy, playerName));
                entityDeaths += killedBy;
            }
        }

        koil$addLocalAggregateRow(rows, "blocks_broken", blocksBroken, playerName);
        koil$addLocalAggregateRow(rows, "blocks_placed", blocksPlaced, playerName);
        koil$addLocalAggregateRow(rows, "items_used", itemsUsed, playerName);
        koil$addLocalAggregateRow(rows, "items_crafted", itemsCrafted, playerName);
        koil$addLocalAggregateRow(rows, "items_picked_up", itemsPickedUp, playerName);
        koil$addLocalAggregateRow(rows, "items_dropped", itemsDropped, playerName);
        koil$addLocalAggregateRow(rows, "food_eaten", foodEaten, playerName);
        koil$addLocalAggregateRow(rows, "mobs_killed", mobsKilled, playerName);
        koil$addLocalAggregateRow(rows, "entity_deaths", entityDeaths, playerName);
        koil$addLocalAggregateRow(rows, "deaths", koil$summaryDeaths(), playerName);
        koil$addLocalAggregateRow(rows, "damage_dealt", koil$summaryDamageDealt(), playerName);
        koil$addLocalAggregateRow(rows, "damage_taken", koil$summaryDamageTaken(), playerName);
        koil$addLocalAggregateRow(rows, "play_time", koil$summaryPlayTimeTicks(), playerName);
        koil$addLocalAggregateRow(rows, "travel_distance", koil$summaryTravelCm(), playerName);
        koil$addLocalAggregateRow(rows, "jumps", koil$summaryJumps(), playerName);
        koil$addLocalAggregateRow(rows, "combat_activity", koil$summaryCombatActivity(mobsKilled, koil$summaryPlayerKills(), koil$summaryDeaths(), koil$summaryDamageDealt(), koil$summaryDamageTaken()), playerName);
        return rows;
    }

    @Unique
    private void koil$addLocalAggregateRow(List<KoilGlobalActivityRow> rows, String metric, int value, String playerName) {
        if (value > 0) {
            rows.add(koil$localStoredRow(metric, value, playerName));
        }
    }

    @Unique
    private KoilGlobalActivityRow koil$localStoredRow(String metric, int value, String playerName) {
        int[] points = koil$localStoredPoints(value);
        int change = points.length < 2 ? 0 : points[points.length - 1] - points[points.length - 2];
        return new KoilGlobalActivityRow("activity", "local_stored/" + metric, metric, koil$marketMetricShortTitle(metric), value, playerName, value, points, "local stored player stats", 0, change, Math.max(0, change), GlobalActivityClient.contextKey(), 90, false);
    }

    @Unique
    private int[] koil$localStoredPoints(int value) {
        int safe = Math.max(0, value);

        if (safe <= 0) {
            return new int[0];
        }

        int count = 16;
        int[] points = new int[count];

        for (int i = 0; i < count; i++) {
            points[i] = Math.round(safe * (i + 1) / (float) count);
        }

        return points;
    }

    @Unique
    private String koil$localPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.getSession() == null) {
            return "local player";
        }

        return client.getSession().getUsername();
    }

    @Unique
    private List<KoilGlobalActivityRow> koil$sortGlobalRows(List<KoilGlobalActivityRow> rows) {
        List<KoilGlobalActivityRow> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            int priority = Integer.compare(koil$globalMetricPriority(b.metric()), koil$globalMetricPriority(a.metric()));

            if (priority != 0) {
                return priority;
            }

            return Integer.compare(b.value(), a.value());
        });
        return sorted;
    }

    @Unique
    private boolean koil$isImportantGlobalMetric(String metric) {
        return koil$globalMetricPriority(metric) >= 70;
    }

    @Unique
    private int koil$globalMetricPriority(String metric) {
        String value = metric == null ? "" : metric.toLowerCase(Locale.ROOT);

        if (value.contains("block_broken") || value.contains("blocks_broken") || value.contains("mine")) {
            return 100;
        }

        if (value.contains("block_place") || value.contains("blocks_placed")) {
            return 95;
        }

        if (value.contains("container_in") || value.contains("container_out")) {
            return 92;
        }

        if (value.contains("item_use") || value.contains("item_used")) {
            return 90;
        }

        if (koil$isFoodMetricText(value)) {
            return 86;
        }

        if (value.contains("mob") || value.contains("kill") || value.contains("death") || value.contains("damage")) {
            return 84;
        }

        if (value.contains("mainhand") || value.contains("offhand") || value.contains("main_hand") || value.contains("off_hand")) {
            return 76;
        }

        if (value.contains("craft") || value.contains("picked") || value.contains("dropped")) {
            return 72;
        }

        if (value.contains("player_presence") || value.contains("player_visible")) {
            return 20;
        }

        return 10;
    }

    @Unique
    private String koil$globalMetricGroup(String metric) {
        String value = metric == null ? "" : metric.toLowerCase(Locale.ROOT);

        if (value.contains("block_broken") || value.contains("blocks_broken") || value.contains("mine")) {
            return "block break demand";
        }

        if (value.contains("block_place") || value.contains("blocks_placed")) {
            return "block placement demand";
        }

        if (value.contains("item_use") || value.contains("item_used")) {
            return "item use demand";
        }

        if (koil$isFoodMetricText(value)) {
            return "food demand";
        }

        if (value.contains("mob") || value.contains("kill") || value.contains("death") || value.contains("damage")) {
            return "combat activity";
        }

        if (value.contains("mainhand") || value.contains("offhand") || value.contains("main_hand") || value.contains("off_hand")) {
            return "held item pressure";
        }

        if (value.contains("craft")) {
            return "crafting demand";
        }

        if (value.contains("picked") || value.contains("dropped")) {
            return "inventory flow";
        }

        return "developer signal";
    }

    @Unique
    private String koil$pageTitle(String page) {
        if (KOIL_PAGE_ITEMS.equals(page)) {
            return "Item & Block Activity";
        }

        if (KOIL_PAGE_MOBS.equals(page)) {
            return "Mob Activity";
        }

        if (KOIL_PAGE_GLOBAL.equals(page)) {
            return "Global Market Activity";
        }

        return "General Activity";
    }

    @Unique
    private String koil$pageCount(String page) {
        koil$refreshRowCache();

        if (KOIL_PAGE_ITEMS.equals(page)) {
            return this.koil$cachedItemRows.size() + " entries";
        }

        if (KOIL_PAGE_MOBS.equals(page)) {
            return this.koil$cachedEntityRows.size() + " entities";
        }

        if (KOIL_PAGE_GLOBAL.equals(page)) {
            return this.koil$cachedHeadMarketRows.size() + " head / " + this.koil$cachedSubMarketRows.size() + " sub / " + this.koil$cachedRawMarketRows.size() + " raw";
        }

        return this.koil$cachedGeneralRows.size() + " stats";
    }

    @Unique
    private String koil$pageHint(String page) {
        if (KOIL_PAGE_ITEMS.equals(page)) {
            return "M mined, U used, C crafted, P picked up, D dropped, B broken.";
        }

        if (KOIL_PAGE_MOBS.equals(page)) {
            return "Kills, deaths, ratio, and risk are shown with dual combat bars.";
        }

        if (KOIL_PAGE_GLOBAL.equals(page)) {
            return "Head markets summarize categories; sub markets group one block/item/entity; raw markets show exact signals like Sand Block Broken.";
        }

        return "Shows vanilla and modded custom stats with source, category, value, and activity strength.";
    }

    @Unique
    private String koil$generalCategory(Identifier id) {
        String value = id.getPath();

        if (value.contains("time") || value.contains("since")) {
            return "Time";
        }

        if (value.contains("cm") || value.contains("distance") || value.contains("walk") || value.contains("sprint") || value.contains("swim") || value.contains("fly") || value.contains("climb") || value.contains("fall") || value.equals("jump")) {
            return "Movement";
        }

        if (value.contains("kill") || value.contains("damage") || value.contains("death")) {
            return "Combat";
        }

        if (value.contains("craft") || value.contains("mine") || value.contains("use") || value.contains("drop") || value.contains("pickup") || value.contains("open") || value.contains("interact")) {
            return "Interaction";
        }

        if (value.contains("villager") || value.contains("trade") || value.contains("raid")) {
            return "World";
        }

        if (value.contains("fish") || value.contains("breed") || value.contains("sleep") || value.contains("bell") || value.contains("cauldron")) {
            return "Activity";
        }

        return id.getNamespace().equals("minecraft") ? "Other" : "Modded";
    }

    @Unique
    private int koil$categoryColor(String category) {
        return switch (category) {
            case "Time" -> 0xFF8FC5FF;
            case "Movement" -> 0xFFA6D989;
            case "Combat" -> 0xFFE06A6A;
            case "Interaction" -> 0xFFE8C86F;
            case "World" -> 0xFF8FD5D0;
            case "Activity" -> 0xFFE6A15C;
            case "Modded" -> 0xFFB7A8FF;
            default -> 0xFFB8C7D6;
        };
    }


    @Unique
    private String koil$marketDisplayName(KoilGlobalActivityRow row) {
        if (row == null) {
            return "Unknown Market";
        }

        String metric = row.metric() == null ? "" : row.metric();
        String label = koil$metricPathLabel(metric);

        if (label.isBlank()) {
            label = koil$metricPathLabel(row.id());
        }

        if (label.isBlank()) {
            label = row.title();
        }

        if (row.market()) {
            if (koil$metricContains(metric, "block_broken/")) {
                return label + " Block Break Market";
            }

            if (koil$metricContains(metric, "block_place_intent/") || koil$metricContains(metric, "block_placed/")) {
                return label + " Block Placement Market";
            }

            if (koil$metricContains(metric, "item_use_intent/") || koil$metricContains(metric, "item_used/")) {
                return label + " Item Use Market";
            }

            if (koil$metricContains(metric, "item_crafted/")) {
                return label + " Crafting Market";
            }

            if (koil$metricContains(metric, "item_traded/")) {
                return label + " Trade Market";
            }

            if (koil$metricContains(metric, "mainhand/") || koil$metricContains(metric, "self_mainhand/")) {
                return label + " Main-Hand Demand Market";
            }

            if (koil$metricContains(metric, "offhand/") || koil$metricContains(metric, "self_offhand/")) {
                return label + " Off-Hand Demand Market";
            }

            return label + " Market";
        }

        if (koil$metricContains(metric, "block_broken/")) {
            return label + " Blocks Broken";
        }

        if (koil$metricContains(metric, "block_place_intent/") || koil$metricContains(metric, "block_placed/")) {
            return label + " Block Placement Demand";
        }

        if (koil$metricContains(metric, "item_use_intent/") || koil$metricContains(metric, "item_used/")) {
            return label + " Item Use Demand";
        }

        if (koil$metricContains(metric, "mainhand/") || koil$metricContains(metric, "self_mainhand/")) {
            return label + " Main-Hand Demand";
        }

        if (koil$metricContains(metric, "offhand/") || koil$metricContains(metric, "self_offhand/")) {
            return label + " Off-Hand Demand";
        }

        return label;
    }


    @Unique
    private boolean koil$metricContains(String metric, String marker) {
        return metric != null && metric.contains(marker);
    }

    @Unique
    private String koil$metricPathLabel(String metric) {
        if (metric == null || metric.isBlank()) {
            return "";
        }

        String[] prefixes = new String[]{"block_broken/", "block_place_intent/", "block_placed/", "block_used/", "block_use/", "item_use_intent/", "item_used/", "item_crafted/", "item_picked_up/", "item_dropped/", "item_traded/", "food_eaten/", "item_eaten/", "mob_killed/", "entity_killed/", "killed_by/", "mainhand/", "offhand/", "self_mainhand/", "self_offhand/"};

        for (String prefix : prefixes) {
            int prefixIndex = metric.indexOf(prefix);

            if (prefixIndex < 0) {
                continue;
            }

            String rest = metric.substring(prefixIndex + prefix.length());
            int separator = rest.indexOf('/');

            if (separator <= 0 || separator >= rest.length() - 1) {
                continue;
            }

            return koil$formatIdentifierName(rest.substring(separator + 1));
        }

        String value = metric;
        int separator = value.lastIndexOf('/');

        if (separator >= 0 && separator < value.length() - 1) {
            value = value.substring(separator + 1);
        }

        return koil$formatIdentifierName(value);
    }

    @Unique
    private int koil$globalMarketDomainColor(KoilGlobalMarketViewRow view) {
        String domain = view == null || view.domain() == null ? "" : view.domain().toLowerCase(Locale.ROOT);
        String metric = view == null || view.chartRow() == null ? "" : view.chartRow().metric().toLowerCase(Locale.ROOT);

        if ("resource".equals(domain) || metric.contains("processing/") || metric.contains("resource/")) {
            return 0xFFE8C86F;
        }

        if ("block".equals(domain)) {
            return 0xFF8FC5FF;
        }

        if ("item".equals(domain)) {
            return 0xFF8FD5D0;
        }

        if ("entity".equals(domain) || "combat".equals(domain)) {
            return 0xFFE06A6A;
        }

        if ("food".equals(domain)) {
            return 0xFFE6A15C;
        }

        if ("trade".equals(domain)) {
            return 0xFFA6D989;
        }

        return 0xFFB7A8FF;
    }

    private int koil$globalMetricColor(String metric, boolean market) {
        String value = metric.toLowerCase(Locale.ROOT);

        if (market) {
            return 0xFFB7A8FF;
        }

        if (value.contains("break") || value.contains("mine")) {
            return 0xFF8FC5FF;
        }

        if (value.contains("place") || value.contains("build")) {
            return 0xFFA6D989;
        }

        if (value.contains("trade") || value.contains("market") || value.contains("sell") || value.contains("buy")) {
            return 0xFFE8C86F;
        }

        if (value.contains("kill") || value.contains("death") || value.contains("damage")) {
            return 0xFFE06A6A;
        }

        if (koil$isFoodMetricText(value)) {
            return 0xFFE6A15C;
        }

        return 0xFF8FD5D0;
    }

    @Unique
    private String koil$itemDominantMetric(int mined, int used, int crafted, int pickedUp, int dropped, int broken) {
        int max = Math.max(mined, Math.max(used, Math.max(crafted, Math.max(pickedUp, Math.max(dropped, broken)))));

        if (max <= 0) {
            return "tracked";
        }

        if (max == mined) {
            return "mostly mined";
        }

        if (max == used) {
            return "mostly used";
        }

        if (max == crafted) {
            return "mostly crafted";
        }

        if (max == pickedUp) {
            return "mostly collected";
        }

        if (max == dropped) {
            return "mostly dropped";
        }

        return "mostly broken";
    }

    @Unique
    private String koil$threatLabel(int killed, int killedBy) {
        if (killedBy <= 0 && killed > 0) {
            return "safe record";
        }

        if (killedBy > killed) {
            return "dangerous";
        }

        if (killedBy > 0) {
            return "some risk";
        }

        return "unknown";
    }

    @Unique
    private int koil$threatColor(int killed, int killedBy) {
        if (killedBy > killed) {
            return 0xFFE06A6A;
        }

        if (killedBy > 0) {
            return 0xFFE6A15C;
        }

        return 0xFFA6D989;
    }

    @Unique
    private String koil$sourceSummary() {
        Set<String> namespaces = new TreeSet<>();

        for (Stat<Identifier> stat : Stats.CUSTOM) {
            Identifier id = stat.getValue();

            if (!id.getNamespace().equals("minecraft")) {
                namespaces.add(id.getNamespace());
            }
        }

        for (Item item : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(item);

            if (!id.getNamespace().equals("minecraft")) {
                namespaces.add(id.getNamespace());
            }
        }

        for (EntityType<?> entityType : Registries.ENTITY_TYPE) {
            Identifier id = Registries.ENTITY_TYPE.getId(entityType);

            if (!id.getNamespace().equals("minecraft")) {
                namespaces.add(id.getNamespace());
            }
        }

        return namespaces.isEmpty() ? "minecraft" : "minecraft + " + namespaces.size() + " modded";
    }

    @Unique
    private String koil$formatIdentifierName(String value) {
        String[] parts = value.replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.length() == 0 ? value : builder.toString();
    }

    @Unique
    private String koil$formatTime(int ticks) {
        long seconds = Math.max(0, ticks) / 20L;
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        long remainingSeconds = seconds % 60L;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }

        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }

        return remainingSeconds + "s";
    }

    @Unique
    private String koil$formatDistance(int centimeters) {
        double meters = Math.max(0, centimeters) / 100.0D;

        if (meters >= 1000.0D) {
            return String.format(Locale.ROOT, "%.2f km", meters / 1000.0D);
        }

        return String.format(Locale.ROOT, "%.1f m", meters);
    }

    @Unique
    private int koil$latest(int[] values) {
        return values == null || values.length == 0 ? 0 : values[values.length - 1];
    }

    @Unique
    private String koil$signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    @Unique
    private String koil$compact(int value) {
        if (value >= 1000000) {
            return String.format(Locale.ROOT, "%.1fm", value / 1000000.0F);
        }

        if (value >= 1000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0F);
        }

        return String.valueOf(value);
    }

    @Unique
    private String koil$ratio(int killed, int killedBy) {
        if (killedBy <= 0) {
            return killed <= 0 ? "0.0" : killed + ".0";
        }

        return String.format(Locale.ROOT, "%.1f", killed / (float) killedBy);
    }

    @Unique
    private String koil$ellipsize(String value, int maxWidth) {
        if (value == null) {
            return "";
        }

        if (this.textRenderer.getWidth(value) <= maxWidth) {
            return value;
        }

        String suffix = "...";
        int suffixWidth = this.textRenderer.getWidth(suffix);
        String trimmed = value;

        while (!trimmed.isEmpty() && this.textRenderer.getWidth(trimmed) + suffixWidth > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed.isEmpty() ? suffix : trimmed + suffix;
    }

    @Unique
    private void koil$renderStatsScrollbar(DrawContext context) {
        if (this.koil$statsScrollMax <= 0 || this.koil$statsViewportHeight <= 0) {
            return;
        }

        int trackX = koil$statsScrollbarTrackX();
        int thumbHeight = koil$statsScrollbarThumbHeight();
        int thumbY = koil$statsScrollbarThumbY();
        context.fill(trackX, this.koil$statsViewportY, trackX + 2, this.koil$statsViewportY + this.koil$statsViewportHeight, 0x44000000);
        context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, 0x99B8C7D6);
    }

    @Unique
    private int koil$statsScrollbarTrackX() {
        return this.koil$statsViewportX + this.koil$statsViewportWidth - 4;
    }

    @Unique
    private int koil$statsScrollbarThumbHeight() {
        if (this.koil$statsScrollMax <= 0 || this.koil$statsViewportHeight <= 0) {
            return this.koil$statsViewportHeight;
        }

        return Math.max(18, (int) ((this.koil$statsViewportHeight / (float) (this.koil$statsViewportHeight + this.koil$statsScrollMax)) * this.koil$statsViewportHeight));
    }

    @Unique
    private int koil$statsScrollbarThumbY() {
        if (this.koil$statsScrollMax <= 0 || this.koil$statsViewportHeight <= 0) {
            return this.koil$statsViewportY;
        }

        int thumbHeight = koil$statsScrollbarThumbHeight();
        int thumbTravel = Math.max(1, this.koil$statsViewportHeight - thumbHeight);
        return this.koil$statsViewportY + (int) ((this.koil$statsScrollOffset / (float) this.koil$statsScrollMax) * thumbTravel);
    }

    @Unique
    private boolean koil$isMouseOverStatsScrollbar(double mouseX, double mouseY) {
        if (this.koil$statsScrollMax <= 0 || this.koil$statsViewportHeight <= 0) {
            return false;
        }

        int trackX = koil$statsScrollbarTrackX();
        return mouseX >= trackX - 4 && mouseX <= trackX + 6 && mouseY >= this.koil$statsViewportY && mouseY <= this.koil$statsViewportY + this.koil$statsViewportHeight;
    }

    @Unique
    private boolean koil$isMouseOverStatsScrollbarThumb(double mouseX, double mouseY) {
        if (!koil$isMouseOverStatsScrollbar(mouseX, mouseY)) {
            return false;
        }

        int thumbY = koil$statsScrollbarThumbY();
        int thumbHeight = koil$statsScrollbarThumbHeight();
        return mouseY >= thumbY && mouseY <= thumbY + thumbHeight;
    }

    @Unique
    private void koil$setStatsScrollFromThumbY(double thumbY) {
        if (this.koil$statsScrollMax <= 0 || this.koil$statsViewportHeight <= 0) {
            this.koil$statsScrollOffset = 0;
            return;
        }

        int thumbHeight = koil$statsScrollbarThumbHeight();
        int thumbTravel = Math.max(1, this.koil$statsViewportHeight - thumbHeight);
        int clampedThumbY = Math.max(this.koil$statsViewportY, Math.min(this.koil$statsViewportY + thumbTravel, (int) thumbY));
        float progress = (clampedThumbY - this.koil$statsViewportY) / (float) thumbTravel;
        this.koil$statsScrollOffset = Math.max(0, Math.min(this.koil$statsScrollMax, Math.round(progress * this.koil$statsScrollMax)));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!koil$isKoilRedesignEnabled()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button == 0 && KOIL_PAGE_GLOBAL.equals(this.koil$activePage)) {
            boolean insideDropdown = koil$isMouseInsideRawRect(mouseX, mouseY, this.koil$stockDropdownX, this.koil$stockDropdownY, this.koil$stockDropdownWidth, this.koil$stockDropdownHeight);

            if (this.koil$stockDropdownOpen) {
                for (Map.Entry<String, int[]> entry : this.koil$stockDropdownOptionRects.entrySet()) {
                    int[] rect = entry.getValue();

                    if (koil$isMouseInsideRawRect(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3])) {
                        if (koil$applyStockDropdownSelection(entry.getKey())) {
                            koil$playUiClick();
                        }

                        return true;
                    }
                }

                if (!insideDropdown) {
                    this.koil$stockDropdownOpen = false;
                    this.koil$stockDropdownPopupX = -1;
                    this.koil$stockDropdownPopupY = -1;
                    return true;
                }
            }

            if (insideDropdown) {
                this.koil$stockDropdownOpen = true;
                this.koil$stockDropdownPopupX = (int) Math.round(mouseX);
                this.koil$stockDropdownPopupY = (int) Math.round(mouseY);
                koil$playUiClick();
                return true;
            }

            for (Map.Entry<String, int[]> entry : this.koil$globalGroupHeaderRects.entrySet()) {
                int[] rect = entry.getValue();

                if (koil$inside((int) mouseX, (int) mouseY, rect[0], rect[1], rect[2], rect[3])) {
                    String key = entry.getKey();
                    boolean defaultOpen = "head".equals(key);
                    this.koil$expandedGlobalGroups.put(key, !koil$isGlobalGroupOpen(key, defaultOpen));
                    this.koil$statsScrollOffset = Math.max(0, Math.min(this.koil$statsScrollOffset, this.koil$statsScrollMax));
                    koil$playUiClick();
                    return true;
                }
            }

            for (Map.Entry<String, int[]> entry : this.koil$globalRowRects.entrySet()) {
                int[] rect = entry.getValue();

                if (koil$inside((int) mouseX, (int) mouseY, rect[0], rect[1], rect[2], rect[3])) {
                    String key = entry.getKey();
                    boolean open = !this.koil$expandedGlobalRows.getOrDefault(key, false);
                    this.koil$expandedGlobalRows.clear();

                    if (open) {
                        this.koil$expandedGlobalRows.put(key, true);
                    }

                    koil$playUiClick();
                    return true;
                }
            }
        }

        if (button == 0 && koil$isMouseOverStatsScrollbar(mouseX, mouseY)) {
            int thumbY = koil$statsScrollbarThumbY();

            if (koil$isMouseOverStatsScrollbarThumb(mouseX, mouseY)) {
                this.koil$statsScrollbarDragOffset = (int) mouseY - thumbY;
            } else {
                this.koil$statsScrollbarDragOffset = koil$statsScrollbarThumbHeight() / 2;
                koil$setStatsScrollFromThumbY(mouseY - this.koil$statsScrollbarDragOffset);
            }

            this.koil$statsScrollbarDragging = true;
            return true;
        }

        String clickedPage = "";

        if (button == 0) {
            for (Element child : this.children()) {
                if (!(child instanceof ClickableWidget widget) || !widget.visible || !widget.active) {
                    continue;
                }

                if (mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth() && mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight()) {
                    clickedPage = koil$pageFromButton(widget);
                    break;
                }
            }
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);

        if (!clickedPage.isBlank()) {
            this.koil$activePage = clickedPage;
            this.koil$statsScrollOffset = 0;
            this.koil$statsScrollbarDragging = false;
            koil$invalidateRowCache();
        }

        return handled;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!koil$isKoilRedesignEnabled()) {
            this.koil$statsScrollbarDragging = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        if (button == 0 && this.koil$statsScrollbarDragging) {
            this.koil$statsScrollbarDragging = false;
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!koil$isKoilRedesignEnabled()) {
            this.koil$statsScrollbarDragging = false;
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        if (button == 0 && this.koil$statsScrollbarDragging) {
            koil$setStatsScrollFromThumbY(mouseY - this.koil$statsScrollbarDragOffset);
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!koil$isKoilRedesignEnabled()) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }

        if (mouseX >= this.koil$statsViewportX
                && mouseX <= this.koil$statsViewportX + this.koil$statsViewportWidth
                && mouseY >= this.koil$statsViewportY
                && mouseY <= this.koil$statsViewportY + this.koil$statsViewportHeight
                && this.koil$statsScrollMax > 0) {
            this.koil$statsScrollOffset = Math.max(0, Math.min(this.koil$statsScrollMax, this.koil$statsScrollOffset - (int) amount * 18));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, amount);
    }


    @Unique
    private boolean koil$isMouseInsideRawRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return width > 0 && height > 0 && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    @Unique
    private boolean koil$inside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return koil$isMouseInsideStatsViewport(mouseX, mouseY) && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    @Unique
    private boolean koil$isMouseInsideStatsViewport(double mouseX, double mouseY) {
        return this.koil$statsViewportWidth > 0
                && this.koil$statsViewportHeight > 0
                && mouseX >= this.koil$statsViewportX
                && mouseX <= this.koil$statsViewportX + this.koil$statsViewportWidth
                && mouseY >= this.koil$statsViewportY
                && mouseY <= this.koil$statsViewportY + this.koil$statsViewportHeight;
    }

    @Unique
    private boolean koil$isFoodMetricText(String value) {
        String safe = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return safe.contains("food") || safe.contains("eaten") || safe.contains("food_") || safe.contains("/food") || safe.startsWith("food_") || safe.startsWith("food/");
    }

    @Unique
    private void koil$playUiClick() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client != null && client.getSoundManager() != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    @Unique
    private void koil$playUiTick() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client != null && client.getSoundManager() != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.35F));
        }
    }

    @Unique
    private int koil$color(int value) {
        return new Color(value, true).getRGB();
    }
}
