package com.spirit.client.gui.main;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.web.WebFileDownloader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.*;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.design.uiColorVal.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;

@Environment(EnvType.CLIENT)
public class FundingScreen extends Screen {
    private final List<Tier> tiers = new ArrayList<>();
    private static final Identifier LOGO_TEXTURE = loadExternalPngTexture(uiImageDirectory, "icon.png");
    private static final Identifier FALLBACK_TIER_TEXTURE = LOGO_TEXTURE;
    private final List<ButtonWidget> homeButtons;
    private final Screen parent;
    private int currentPanel;
    private int scrollOffset;
    private int maxScrollOffset;
    private long openedAt;
    private String globalKofiLink = "https://ko-fi.com/spiritxiv";
    private String globalMembershipLink = "https://ko-fi.com/spiritxiv/membership";
    private String fundraiserTitle = "Goal:";
    private String fundraiserDescription = "This bar tracks how much fans have donated in total toward the current Ko-fi custom-amount shop fundraiser item. The Membership section is for recurring support tiers, while the Shop section is where fans can donate custom amounts to fundraiser items.";
    private String expandedTierId;
    private final Map<String, Float> tierExpansionProgress = new HashMap<>();
    private long lastTierAnimationTime;
    private int fundingBarX;
    private int fundingBarY;
    private int fundingBarWidth;
    private int fundingBarHeight;

    public static int CURRENT_FUNDING;
    public static int GOAL_FUNDING;
    public static int NEXT_GOAL_FUNDING;
    public static int WAYPOINT_COUNT;

    public FundingScreen(Screen parent) {
        super(Text.literal("Title"));
        this.parent = parent;
        this.homeButtons = new ArrayList<>();
        this.currentPanel = 1;
        this.scrollOffset = 0;
        this.expandedTierId = null;
    }

    @Override
    protected void init() {
        super.init();
        this.openedAt = System.currentTimeMillis();
        this.lastTierAnimationTime = this.openedAt;
        WebFileDownloader.downloadFile("https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/store/membership.json", "membership.json", "./koil/sys/store", 16);
        initHome();
        loadTiers();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private void switchPanel(int panelId) {
        for (ButtonWidget button : homeButtons) {
            this.remove(button);
        }
        this.currentPanel = panelId;
        this.scrollOffset = 0;
        initHome();
        playUiClick(1.0f);
    }

    private void initHome() {
        homeButtons.clear();

        ButtonWidget panelMembership = this.addDrawableChild(ButtonWidget.builder(Text.literal("Membership"), button -> switchPanel(1)).dimensions(10, 70, 100, 20).build());
        ButtonWidget panelShop = this.addDrawableChild(ButtonWidget.builder(Text.literal("Shop"), button -> switchPanel(2)).dimensions(10, 100, 100, 20).build());
        ButtonWidget openKofi = this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Ko-fi"), button -> {
            openLink(globalKofiLink);
            playUiPurchase(1.06f);
        }).dimensions(10, 130, 100, 20).build());

        homeButtons.add(panelMembership);
        homeButtons.add(panelShop);
        homeButtons.add(openKofi);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        assert client != null;

        KoilScreenBackgrounds.render(context, client, this.width, this.height);

        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 0, this.width, 60, new Color(uiColorHeader, true).getRGB());
        context.drawBorder(0, 0, this.width, 60, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(127, 62, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(127, 62, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 62, 125, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, 62, 125, this.height, new Color(uiColorBackgroundBorder, true).getRGB());

        renderIntroFlash(context);

        if (LOGO_TEXTURE != null) {
            context.drawTexture(LOGO_TEXTURE, 10, 5, 0, 0, 45, 45, 45, 45);
        }

        context.getMatrices().push();
        context.getMatrices().scale(2, 2, 1.0F);
        context.drawText(this.textRenderer, "Koil's Funding", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Support this project!", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);

        CURRENT_FUNDING = getIntValue("./koil/sys/store/membership.json", "current", 0);
        GOAL_FUNDING = Math.max(1, getIntValue("./koil/sys/store/membership.json", "goal", 100));
        NEXT_GOAL_FUNDING = Math.max(GOAL_FUNDING + 1, getIntValue("./koil/sys/store/membership.json", "nextGoal", GOAL_FUNDING * 2));
        WAYPOINT_COUNT = Math.max(1, getIntValue("./koil/sys/store/membership.json", "waypointCount", 4));

        renderFundingBar(context, mouseX, mouseY);

        switch (currentPanel) {
            case 0:
            case 1:
                renderTiers(context, mouseX, mouseY, delta);
                break;
            case 2:
                renderShop(context, mouseX, mouseY, delta);
                break;
            default:
                renderTiers(context, mouseX, mouseY, delta);
                switchPanel(1);
        }

        if (isFundingBarHovered(mouseX, mouseY)) {
            renderFundingTooltip(context, mouseX, mouseY);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private int getIntValue(String path, String key, int fallback) {
        try {
            JsonElement value = JSONFileEditor.getValueFromJson(path, key);
            if (value != null && value.isJsonPrimitive()) {
                return value.getAsInt();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void renderIntroFlash(DrawContext context) {
        long elapsed = System.currentTimeMillis() - this.openedAt;
        if (elapsed > 900L) {
            return;
        }

        float t = 1.0f - Math.min(1.0f, elapsed / 900.0f);
        int alpha = (int) (35 * t);
        context.fill(127, 62, this.width, this.height, withAlpha(0xFFFFFF, alpha));
    }

    private void renderFundingBar(DrawContext context, int mouseX, int mouseY) {
        int barWidth = this.width / 2;
        int barHeight = 12;
        int barX = this.width / 2 - barWidth / 2 + 100;
        int barY = 34;
        float progress = CURRENT_FUNDING / (float) GOAL_FUNDING;
        progress = Math.max(0.0f, Math.min(1.0f, progress));
        int filledWidth = (int) (barWidth * progress);
        int waypointCount = Math.max(1, WAYPOINT_COUNT);
        long time = System.currentTimeMillis();

        this.fundingBarX = barX;
        this.fundingBarY = barY;
        this.fundingBarWidth = barWidth;
        this.fundingBarHeight = barHeight;

        context.drawText(this.textRenderer, fundraiserTitle, barX, barY - 12, new Color(255, 255, 255, 255).getRGB(), true);

        int outerGlowAlpha = 28 + (int) (22 * Math.abs(Math.sin(time / 280.0)));
        int hoveredBoost = isFundingBarHovered(mouseX, mouseY) ? 28 : 0;

        context.fill(barX - 5, barY - 5, barX + barWidth + 5, barY + barHeight + 5, withAlpha(0xFBE7B2, outerGlowAlpha + hoveredBoost));
        context.fill(barX - 3, barY - 3, barX + barWidth + 3, barY + barHeight + 3, withAlpha(0xC9A34C, 22 + hoveredBoost / 2));
        context.fill(barX - 2, barY - 2, barX + barWidth + 2, barY + barHeight + 2, new Color(18, 18, 22, 175).getRGB());
        context.fill(barX, barY, barX + barWidth, barY + barHeight, new Color(52, 52, 60, 210).getRGB());
        context.fill(barX, barY, barX + barWidth, barY + 1, withAlpha(0xFFFFFF, 20));
        context.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, withAlpha(0x000000, 24));

        int fillBase = new Color(176, 136, 54, 255).getRGB();
        int fillWarm = new Color(219, 183, 92, 255).getRGB();
        int fillGlow = new Color(255, 229, 150, 110).getRGB();

        if (filledWidth > 0) {
            context.fill(barX, barY, barX + filledWidth, barY + barHeight, fillBase);
            context.fill(barX, barY, barX + filledWidth, barY + 4, fillWarm);
            context.fill(barX, barY + 5, barX + filledWidth, barY + 7, withAlpha(fillGlow, 64));

            int crestAlpha = 155 + (int) (65 * Math.abs(Math.sin(time / 160.0)));
            context.fill(barX + filledWidth - 1, barY, barX + filledWidth, barY + barHeight, withAlpha(0xFFF8DE, crestAlpha));

            int shineWidth = 38;
            int shineOffset = (int) (((time / 4.9) % (barWidth + shineWidth * 2)) - shineWidth);
            for (int i = 0; i < shineWidth; i++) {
                int x = barX + shineOffset + i;
                if (x >= barX && x < barX + filledWidth) {
                    int alpha = Math.max(0, 30 - Math.abs(i - shineWidth / 2) * 2);
                    context.fill(x, barY + 1, x + 1, barY + barHeight - 1, withAlpha(0xFFFFFF, alpha));
                }
            }

            for (int i = 0; i < 9; i++) {
                int sparkX = barX + 4 + (int) ((Math.sin((time / 155.0) + i * 0.92) + 1.0) * 0.5 * Math.max(4, filledWidth - 8));
                int sparkY = barY + 1 + (i % 6);
                int sparkSize = i % 3 == 0 ? 2 : 1;
                int alpha = 110 + (int) (70 * Math.abs(Math.sin((time + i * 90) / 210.0)));
                context.fill(sparkX, sparkY, sparkX + sparkSize, sparkY + sparkSize, withAlpha(0xFFF3CC, alpha));
            }

            int travelingLine = (int) ((Math.sin(time / 205.0) + 1.0) * 0.5 * Math.max(2, filledWidth - 4));
            context.fill(barX + 2, barY + 9, barX + filledWidth - 2, barY + 10, withAlpha(0xFFF0C6, 16));
            context.fill(barX + travelingLine, barY + 2, barX + travelingLine + 1, barY + barHeight - 2, withAlpha(0xFFFCE7, 76));
        }

        context.drawBorder(barX, barY, barWidth, barHeight, new Color(15, 15, 18, 180).getRGB());

        int dustZoneWidth = Math.max(12, (filledWidth > 0 ? filledWidth : barWidth) - 4);
        int dustStartX = barX + 2;
        int dustEndX = dustStartX + dustZoneWidth;
        int dustTopY = barY + 1;
        int dustBottomY = barY + barHeight - 2;

        for (int i = 0; i < 26; i++) {
            double waveA = Math.sin((time / 230.0) + i * 0.71);
            double waveB = Math.cos((time / 170.0) + i * 1.13);

            int px = dustStartX + (int) ((i / 25.0) * (dustEndX - dustStartX)) + (int) (waveA * 2.0);
            int py = dustTopY + (int) (((waveB + 1.0) * 0.5) * Math.max(1, dustBottomY - dustTopY));
            int size = i % 5 == 0 ? 2 : 1;
            int alpha = 70 + (int) (90 * Math.abs(Math.sin((time + i * 70) / 180.0)));
            int color = i % 4 == 0 ? 0xFFF8E3 : i % 3 == 0 ? 0xF2C96D : 0xE4B94E;

            int dustLimit = filledWidth > 0 ? barX + filledWidth - size - 1 : barX + barWidth - size - 1;
            px = Math.max(barX + 1, Math.min(px, Math.max(barX + 1, dustLimit)));
            py = Math.max(barY + 1, Math.min(py, barY + barHeight - size - 1));

            context.fill(px, py, px + size, py + size, withAlpha(color, alpha));
        }

        for (int i = 0; i < 10; i++) {
            int px = dustStartX + (int) ((Math.sin((time / 145.0) + i * 0.8) + 1.0) * 0.5 * Math.max(1, dustZoneWidth - 2));
            int py = dustTopY + (i % 5);
            int alpha = 90 + (int) (60 * Math.abs(Math.cos((time + i * 80) / 160.0)));

            int dustLimit = filledWidth > 0 ? barX + filledWidth - 2 : barX + barWidth - 2;
            px = Math.max(barX + 1, Math.min(px, Math.max(barX + 1, dustLimit)));
            py = Math.max(barY + 1, Math.min(py, barY + barHeight - 2));

            context.fill(px, py, px + 1, py + 1, withAlpha(0xFFF4D0, alpha));
        }

        for (int i = 1; i <= waypointCount; i++) {
            int waypointX = barX + (i * barWidth / waypointCount);
            String waypointText = "$" + (GOAL_FUNDING / waypointCount) * i;
            boolean isCompletedWaypoint = CURRENT_FUNDING >= (GOAL_FUNDING / waypointCount) * i;
            int waypointColor = isCompletedWaypoint ? new Color(255, 245, 220, 255).getRGB() : new Color(155, 155, 165, 255).getRGB();
            float distanceToFilled = Math.abs(filledWidth - (waypointX - barX));
            float scale = 1.22f - Math.min(1.0f, distanceToFilled / ((float) barWidth / waypointCount));
            float yOffset = (scale - 1.0f) * 9;

            context.fill(waypointX, barY + 1, waypointX + 1, barY + barHeight - 1, withAlpha(isCompletedWaypoint ? 0xFFF1C9 : 0x8A8A96, isCompletedWaypoint ? 60 : 26));

            context.getMatrices().push();
            context.getMatrices().scale(scale, scale, 1.0F);
            int scaledWaypointTextX = (int) ((waypointX - (this.textRenderer.getWidth(waypointText) * scale) / 2) / scale);
            int scaledWaypointTextY = (int) ((barY - 15 - yOffset) / scale);
            context.drawText(this.textRenderer, waypointText, scaledWaypointTextX, scaledWaypointTextY, waypointColor, true);
            context.getMatrices().pop();
        }

        renderFundingCurrentValue(context, barX, barY, filledWidth, barHeight);
        renderFundingGoalValue(context, barX + barWidth + 10, barY - 4, GOAL_FUNDING, CURRENT_FUNDING >= GOAL_FUNDING, time);
    }

    private void renderFundingCurrentValue(DrawContext context, int barX, int barY, int filledWidth, int barHeight) {
        context.getMatrices().push();
        context.getMatrices().scale(0.55f, 0.55f, 1.0F);
        String text = "$" + CURRENT_FUNDING;
        int width = this.textRenderer.getWidth(text);
        int textX = (int) ((barX + Math.max(0, filledWidth - width / 2)) / 0.55f);
        int textY = (int) ((barY + barHeight + 6) / 0.55f);
        context.drawText(this.textRenderer, text, textX, textY, new Color(255, 255, 255, 255).getRGB(), true);
        context.getMatrices().pop();
    }

    private void renderFundingGoalValue(DrawContext context, int x, int y, int goalValue, boolean reached, long time) {
        String text = "$" + goalValue;
        int textWidth = this.textRenderer.getWidth(text);
        int boxWidth = textWidth + 18;
        int boxHeight = 18;
        int boxX = x;
        int boxY = y;

        int fillColor = reached ? new Color(103, 81, 26, 215).getRGB() : new Color(36, 36, 42, 215).getRGB();
        int borderColor = reached ? new Color(255, 219, 116, 210).getRGB() : new Color(145, 130, 92, 150).getRGB();
        int textColor;

        if (reached) {
            int shimmer = 185 + (int) (70 * Math.abs(Math.sin(time / 120.0)));
            textColor = new Color(255, 228, 142, shimmer).getRGB();
        } else {
            int shimmer = 165 + (int) (40 * Math.abs(Math.sin(time / 260.0)));
            textColor = new Color(225, 206, 152, shimmer).getRGB();
        }

        context.fill(boxX - 1, boxY - 1, boxX + boxWidth + 1, boxY + boxHeight + 1, withAlpha(0xF8D98E, reached ? 24 : 12));
        context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, fillColor);
        context.drawBorder(boxX, boxY, boxWidth, boxHeight, borderColor);
        context.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + 2, withAlpha(0xFFFFFF, 14));

        for (int i = 0; i < 4; i++) {
            int sparkX = boxX + 4 + (int) ((Math.sin((time / 170.0) + i * 1.2) + 1.0) * 0.5 * Math.max(2, boxWidth - 10));
            int sparkY = boxY + 4 + (i % 3) * 4;
            int alpha = reached ? 120 : 55;
            context.fill(sparkX, sparkY, sparkX + 1, sparkY + 1, withAlpha(0xFFF7D7, alpha));
        }

        context.drawText(this.textRenderer, text, boxX + 9, boxY + 5, textColor, true);
    }

    private boolean isFundingBarHovered(double mouseX, double mouseY) {
        return mouseX >= this.fundingBarX - 4 && mouseX <= this.fundingBarX + this.fundingBarWidth + 4 && mouseY >= this.fundingBarY - 14 && mouseY <= this.fundingBarY + this.fundingBarHeight + 6;
    }

    private void renderFundingTooltip(DrawContext context, int mouseX, int mouseY) {
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(fundraiserDescription), 230);
        int width = 0;
        for (OrderedText line : lines) {
            width = Math.max(width, this.textRenderer.getWidth(line));
        }

        int tooltipX = Math.min(mouseX + 12, this.width - width - 16);
        int tooltipY = Math.min(mouseY + 12, this.height - (lines.size() * 10) - 14);
        int boxWidth = width + 10;
        int boxHeight = lines.size() * 10 + 8;

        context.fill(tooltipX, tooltipY, tooltipX + boxWidth, tooltipY + boxHeight, new Color(16, 16, 18, 235).getRGB());
        context.drawBorder(tooltipX, tooltipY, boxWidth, boxHeight, new Color(215, 190, 120, 255).getRGB());

        int lineY = tooltipY + 4;
        for (OrderedText line : lines) {
            context.drawText(this.textRenderer, line, tooltipX + 5, lineY, new Color(230, 230, 230, 255).getRGB(), false);
            lineY += 10;
        }
    }

    private void renderTiers(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Membership", (int) (140 / 1.2), (int) (75 / 1.2), Color.WHITE.getRGB(), true);
        context.getMatrices().pop();

        int contentLeft = 140;
        int contentTop = 90;
        int contentRight = this.width - 20;
        int contentBottom = this.height - 16;
        int yStart = contentTop - scrollOffset;
        long time = System.currentTimeMillis();
        updateTierExpansionProgress(time);

        context.enableScissor(contentLeft, contentTop, contentRight, contentBottom);

        for (Tier tier : tiers) {
            if (!tier.enabled || !tier.visible || !tier.active) {
                continue;
            }

            int tierHeight = getTierHeight(tier);
            int spacing = 10;
            int left = contentLeft;
            int right = contentRight;
            int top = yStart;
            int bottom = yStart + tierHeight;

            if (bottom < contentTop - 12 || top > contentBottom + 12) {
                yStart += tierHeight + spacing;
                continue;
            }

            boolean hovered = mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
            int iconBoxLeft = left + 12;
            int iconBoxTop = top + 14;
            int iconBoxSize = 56;
            int iconSize = 48;
            int iconDrawX = iconBoxLeft + (iconBoxSize - iconSize) / 2;
            int iconDrawY = iconBoxTop + (iconBoxSize - iconSize) / 2;

            int basePanel = new Color(26, 26, 28, 185).getRGB();
            int borderColor = mixColor(tier.accentColor, new Color(80, 80, 80, 255).getRGB(), 0.75f);
            int softGlow = withAlpha(tier.accentSecondaryColor, hovered ? 82 : 45);
            int brightGlow = withAlpha(tier.accentColor, hovered ? 68 : 35);

            context.fill(left, top, right, bottom, basePanel);
            context.fill(left, top, left + 5, bottom, brightGlow);
            context.fill(left + 5, top, left + 9, bottom, softGlow);
            context.drawBorder(left, top, right - left, tierHeight, borderColor);

            renderAnimatedMetalSweep(context, left, top, right, bottom, tier, time);
            renderTierSparkles(context, left, top, right, bottom, tier, hovered, time);
            renderCornerEnergy(context, left, top, right, bottom, tier, hovered, time);
            renderTierBand(context, left, top, right, tier, time);

            context.fill(iconBoxLeft, iconBoxTop, iconBoxLeft + iconBoxSize, iconBoxTop + iconBoxSize, withAlpha(tier.accentColor, 20));
            context.drawBorder(iconBoxLeft, iconBoxTop, iconBoxSize, iconBoxSize, withAlpha(tier.accentSecondaryColor, 80));

            if (tier.iconTexture != null) {
                context.drawTexture(tier.iconTexture, iconDrawX, iconDrawY, 0, 0, iconSize, iconSize, iconSize, iconSize);
            } else if (FALLBACK_TIER_TEXTURE != null) {
                context.drawTexture(FALLBACK_TIER_TEXTURE, iconDrawX, iconDrawY, 0, 0, iconSize, iconSize, iconSize, iconSize);
            }

            int textLeft = left + 80;

            context.drawText(this.textRenderer, tier.displayName, textLeft, top + 10, tier.accentSecondaryColor, true);
            context.drawText(this.textRenderer, tier.presenceText, textLeft, top + 22, withAlpha(tier.badgeColor, 255), false);

            List<OrderedText> wrappedDescription = this.textRenderer.wrapLines(Text.literal(tier.description), this.width - 440);
            int descY = top + 35;
            int descLines = Math.min(3, wrappedDescription.size());
            for (int i = 0; i < descLines; i++) {
                context.drawText(this.textRenderer, wrappedDescription.get(i), textLeft, descY, new Color(210, 210, 210, 255).getRGB(), false);
                descY += 10;
            }

            renderBadge(context, tier, textLeft, top + 68, hovered);
            renderMembershipPrice(context, tier, right - 30, top + 10);
            renderTags(context, tier, textLeft, top + 86, right - 186);
            boolean supportHovered = isSupportButtonHit(tier, mouseX, mouseY);
            renderSupportButton(context, tier, right - 156, top + 68, supportHovered);
            renderExpandHint(context, tier, right - 156, top + 88);

            float expansion = getTierExpansionProgress(tier);
            if (expansion > 0.02f) {
                renderBenefits(context, tier, left + 18, top + 112, right - 18, iconBoxLeft + iconBoxSize + 8, expansion);
            }

            yStart += tierHeight + spacing;
        }

        context.disableScissor();
        maxScrollOffset = Math.max(0, computeTotalContentHeight() - (contentBottom - contentTop) + 8);
    }

    private int computeTotalContentHeight() {
        int total = 0;
        for (Tier tier : tiers) {
            if (tier.enabled && tier.visible && tier.active) {
                total += getTierHeight(tier) + 10;
            }
        }
        return total;
    }

    private int getTierHeight(Tier tier) {
        int base = 108;
        return base + Math.round(getTierExpandedHeight(tier) * getTierExpansionProgress(tier));
    }

    private int getTierExpandedHeight(Tier tier) {
        int benefitsLines = 0;
        int wrapWidth = Math.max(120, this.width - 210);
        for (String benefit : tier.benefits) {
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal("• " + benefit), wrapWidth);
            benefitsLines += Math.max(1, wrapped.size());
        }

        return 16 + benefitsLines * 10 + 12;
    }

    private void updateTierExpansionProgress(long time) {
        if (lastTierAnimationTime <= 0L) {
            lastTierAnimationTime = time;
        }
        float delta = Math.min(0.05f, Math.max(0.0f, (time - lastTierAnimationTime) / 1000.0f));
        lastTierAnimationTime = time;
        float step = Math.min(1.0f, delta * 8.0f);
        for (Tier tier : tiers) {
            if (tier.id == null || tier.id.isBlank()) {
                continue;
            }
            float current = tierExpansionProgress.getOrDefault(tier.id, 0.0f);
            float target = isExpanded(tier) ? 1.0f : 0.0f;
            float next = current + (target - current) * step;
            if (target == 0.0f && next < 0.015f) {
                tierExpansionProgress.remove(tier.id);
            } else if (target == 1.0f && next > 0.985f) {
                tierExpansionProgress.put(tier.id, 1.0f);
            } else {
                tierExpansionProgress.put(tier.id, next);
            }
        }
    }

    private float getTierExpansionProgress(Tier tier) {
        if (tier.id == null || tier.id.isBlank()) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, tierExpansionProgress.getOrDefault(tier.id, isExpanded(tier) ? 1.0f : 0.0f)));
    }

    private boolean isExpanded(Tier tier) {
        return tier.id != null && tier.id.equals(this.expandedTierId);
    }

    private void renderTierBand(DrawContext context, int left, int top, int right, Tier tier, long time) {
        int pulseAlpha = 22 + (int) (16 * Math.abs(Math.sin(time / 240.0)));
        int bandLeft = left + 82;
        context.fill(bandLeft, top + 56, right - 10, top + 57, withAlpha(tier.accentSecondaryColor, pulseAlpha));
    }

    private void renderCornerEnergy(DrawContext context, int left, int top, int right, int bottom, Tier tier, boolean hovered, long time) {
        int alpha = hovered ? 95 : 55;
        int pulse = (int) (10 + 8 * Math.abs(Math.sin(time / 220.0)));
        context.fill(left, top, left + 20 + pulse, top + 2, withAlpha(tier.accentSecondaryColor, alpha));
        context.fill(left, top, left + 2, top + 16 + pulse, withAlpha(tier.accentSecondaryColor, alpha));
        context.fill(right - 20 - pulse, bottom - 2, right, bottom, withAlpha(tier.accentSecondaryColor, alpha));
        context.fill(right - 2, bottom - 16 - pulse, right, bottom, withAlpha(tier.accentSecondaryColor, alpha));
    }

    private void renderBadge(DrawContext context, Tier tier, int x, int y, boolean hovered) {
        int badgeWidth = this.textRenderer.getWidth(tier.badgeText) + 10;
        int badgeHeight = 12;
        int fill = withAlpha(tier.badgeColor, hovered ? 72 : 52);
        int border = withAlpha(tier.accentSecondaryColor, 220);

        context.fill(x, y, x + badgeWidth, y + badgeHeight, fill);
        context.drawBorder(x, y, badgeWidth, badgeHeight, border);
        context.drawText(this.textRenderer, tier.badgeText, x + 5, y + 2, tier.badgeColor, false);
    }

    private void renderMembershipPrice(DrawContext context, Tier tier, int rightX, int topY) {
        PriceParts parts = splitPrice(tier.amount);
        int dollarsWidth = this.textRenderer.getWidth(parts.main);
        int centsVisualWidth = parts.cents.isEmpty() ? 0 : Math.round(this.textRenderer.getWidth(parts.cents) * 0.78f);
        int priceVisualWidth = Math.max(46, Math.round((dollarsWidth * 1.55f) + centsVisualWidth + 6));
        int boxWidth = Math.max(52, Math.min(78, priceVisualWidth + 4));
        int boxHeight = 20;
        int boxLeft = rightX - boxWidth;
        int boxTop = topY + 1;
        int priceLeft = boxLeft + Math.max(4, (boxWidth - priceVisualWidth) / 2) - 1;

        context.drawBorder(boxLeft, boxTop, boxWidth, boxHeight, withAlpha(tier.accentSecondaryColor, 132));
        context.fill(boxLeft + 1, boxTop + 1, boxLeft + boxWidth - 1, boxTop + boxHeight - 1, withAlpha(tier.accentColor, 10));

        context.getMatrices().push();
        float bigScale = 1.55f;
        int dollarsX = (int) (priceLeft / bigScale);
        int dollarsY = (int) ((boxTop + 5) / bigScale);

        int shimmer = 205 + (int) (50 * Math.abs(Math.sin(System.currentTimeMillis() / tier.pricePulseSpeed)));
        int glowColor = withAlpha(tier.accentSecondaryColor, shimmer);

        context.getMatrices().scale(bigScale, bigScale, 1.0F);
        context.drawText(this.textRenderer, parts.main, dollarsX, dollarsY, glowColor, true);
        context.getMatrices().pop();

        if (!parts.cents.isEmpty()) {
            context.getMatrices().push();
            float smallScale = 0.78f;
            int centsDrawX = (int) ((priceLeft + Math.round(dollarsWidth * bigScale) + 1) / smallScale);
            int centsDrawY = (int) ((boxTop + 11) / smallScale);
            context.getMatrices().scale(smallScale, smallScale, 1.0F);
            context.drawText(this.textRenderer, parts.cents, centsDrawX, centsDrawY, withAlpha(tier.accentSecondaryColor, 225), true);
            context.getMatrices().pop();
        }

        int textSparkleBaseX = priceLeft - 1;
        int textSparkleWidth = Math.max(14, Math.min(boxWidth - 10, priceVisualWidth + 4));
        for (int j = 0; j < 4; j++) {
            double phase = (System.currentTimeMillis() / (180.0 + j * 24.0)) + (j * 0.9);
            int sparkleX = textSparkleBaseX + (int) ((Math.sin(phase) + 1.0) * 0.5 * textSparkleWidth);
            int sparkleY = boxTop + 5 + (j % 2) * 8;
            int sparkleSize = j % 2 == 0 ? 2 : 1;
            int sparkleAlpha = 86 + (int) (88 * Math.abs(Math.cos(phase * 0.8)));
            context.fill(sparkleX, sparkleY, sparkleX + sparkleSize, sparkleY + sparkleSize, withAlpha(0xFFF7D5, sparkleAlpha));
            if (sparkleSize > 1) {
                context.fill(sparkleX - 1, sparkleY + 1, sparkleX + sparkleSize + 1, sparkleY + 2, withAlpha(0xFFF7D5, sparkleAlpha / 2));
                context.fill(sparkleX, sparkleY - 1, sparkleX + 1, sparkleY + sparkleSize + 1, withAlpha(0xFFF7D5, sparkleAlpha / 2));
            }
        }
    }

    private PriceParts splitPrice(double amount) {
        BigDecimal value = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
        String text = "$" + value.toPlainString();
        int dot = text.lastIndexOf('.');
        if (dot > 0 && dot < text.length() - 1) {
            return new PriceParts(text.substring(0, dot), text.substring(dot));
        }
        return new PriceParts(text, "");
    }

    private void renderTags(DrawContext context, Tier tier, int startX, int y, int maxX) {
        int x = startX;
        int shown = 0;

        for (String tag : tier.tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }

            String text = tag.toUpperCase(Locale.ROOT);
            int chipWidth = this.textRenderer.getWidth(text) + 10;
            int chipHeight = 11;

            if (x + chipWidth > maxX) {
                break;
            }

            int chipFill = withAlpha(tier.accentColor, 38);
            int chipBorder = withAlpha(tier.accentSecondaryColor, 140);
            int chipText = withAlpha(tier.accentSecondaryColor, 255);

            context.fill(x, y, x + chipWidth, y + chipHeight, chipFill);
            context.drawBorder(x, y, chipWidth, chipHeight, chipBorder);
            context.drawText(this.textRenderer, text, x + 5, y + 2, chipText, false);

            x += chipWidth + 4;
            shown++;

            if (shown >= 7) {
                break;
            }
        }
    }

    private void renderSupportButton(DrawContext context, Tier tier, int x, int y, boolean hovered) {
        int width = 108;
        int height = 14;
        int fill = withAlpha(tier.accentColor, hovered ? 84 : 58);
        int border = withAlpha(tier.accentSecondaryColor, 220);
        int textColor = withAlpha(tier.accentSecondaryColor, 255);
        String label = tier.ctaLabel == null || tier.ctaLabel.isBlank() ? "PURCHASE" : tier.ctaLabel.toUpperCase(Locale.ROOT);
        int textX = x + Math.max(4, (width - this.textRenderer.getWidth(label)) / 2);

        context.fill(x, y, x + width, y + height, fill);
        context.drawBorder(x, y, width, height, border);
        context.fill(x + 1, y + 1, x + width - 1, y + 3, withAlpha(0xFFFFFF, hovered ? 34 : 20));
        context.drawText(this.textRenderer, label, textX, y + 3, textColor, false);
    }

    private void renderExpandHint(DrawContext context, Tier tier, int x, int y) {
        String text = isExpanded(tier) ? "Hide benefits" : "Show benefits";
        int centeredX = x + Math.max(0, (108 - this.textRenderer.getWidth(text)) / 2);
        context.drawText(this.textRenderer, text, centeredX, y, withAlpha(tier.accentSecondaryColor, 210), false);
    }

    private void renderBenefits(DrawContext context, Tier tier, int left, int startY, int right, int minTextX, float expansion) {
        int fullAreaHeight = Math.max(1, getTierExpandedHeight(tier) - 8);
        int areaHeight = Math.max(1, Math.round(fullAreaHeight * expansion));
        int boxBottom = startY + areaHeight;
        int fillColor = withAlpha(tier.accentColor, 22);
        int borderColor = withAlpha(tier.accentSecondaryColor, 120);
        int textLeft = Math.max(left + 6, minTextX);
        int clipBottom = boxBottom - 6;

        int clippedTop = startY - 4;
        int clippedBottom = Math.max(clippedTop + 1, boxBottom - 1);
        context.enableScissor(left, clippedTop, right, clippedBottom);
        context.fill(left, clippedTop, right, clippedBottom, fillColor);
        context.drawBorder(left, clippedTop, right - left, Math.max(1, clippedBottom - clippedTop), borderColor);
        renderBenefitSweep(context, left, clippedTop, right, clippedBottom, tier, System.currentTimeMillis(), expansion);
        if (startY + 10 <= clipBottom) {
            context.drawText(this.textRenderer, "Benefits", textLeft, startY + 2, withAlpha(tier.accentSecondaryColor, 255), true);
        }

        int y = startY + 16 - Math.round((1.0f - expansion) * 8.0f);
        int wrapWidth = Math.max(120, right - textLeft - 8);
        for (String benefit : tier.benefits) {
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal("• " + benefit), wrapWidth);
            for (OrderedText line : wrapped) {
                if (y + 9 <= clipBottom) {
                    context.drawText(this.textRenderer, line, textLeft, y, new Color(225, 225, 225, Math.round(255 * expansion)).getRGB(), false);
                }
                y += 10;
            }
        }
        context.disableScissor();
    }

    private void renderAnimatedMetalSweep(DrawContext context, int left, int top, int right, int bottom, Tier tier, long time) {
        int width = right - left;
        int sweepWidth = 82;
        int offset = (int) ((((time - openedAt) / tier.sweepSpeed) % (width + sweepWidth * 2)) - sweepWidth);

        for (int i = 0; i < sweepWidth; i++) {
            int x = left + offset + i;
            if (x <= left || x >= right) {
                continue;
            }
            int distance = Math.abs(i - sweepWidth / 2);
            int alpha = Math.max(0, 32 - distance);
            context.fill(x, top + 1, x + 1, bottom - 1, withAlpha(tier.accentSecondaryColor, alpha));
            if (x + 1 < right && alpha > 12) {
                context.fill(x + 1, top + 2, x + 2, bottom - 2, withAlpha(0xFFFFFF, Math.min(14, alpha / 3)));
            }
        }
    }

    private void renderBenefitSweep(DrawContext context, int left, int top, int right, int bottom, Tier tier, long time, float expansion) {
        if (bottom <= top + 4) {
            return;
        }
        int width = right - left;
        int sweepWidth = 54;
        int offset = (int) ((((time - openedAt) / Math.max(2.0f, tier.sweepSpeed * 0.85f)) % (width + sweepWidth * 2)) - sweepWidth);
        for (int i = 0; i < sweepWidth; i++) {
            int x = left + offset + i;
            if (x <= left || x >= right) {
                continue;
            }
            int distance = Math.abs(i - sweepWidth / 2);
            int alpha = Math.round(Math.max(0, 26 - distance) * expansion);
            context.fill(x, top + 1, x + 1, bottom - 1, withAlpha(tier.accentSecondaryColor, alpha));
        }
    }

    private void renderTierSparkles(DrawContext context, int left, int top, int right, int bottom, Tier tier, boolean hovered, long time) {
        int count = hovered ? tier.sparkleCount + 2 : tier.sparkleCount;

        for (int i = 0; i < count; i++) {
            int px = left + 14 + (int) ((Math.sin((time / 320.0) + i * 1.7) + 1.0) * 0.5 * (right - left - 28));
            int py = top + 8 + (int) ((Math.cos((time / 410.0) + i * 2.3) + 1.0) * 0.5 * (bottom - top - 16));
            int size = i % 2 == 0 ? 2 : 1;
            int alpha = hovered ? 120 : 75;
            context.fill(px, py, px + size, py + size, withAlpha(tier.accentSecondaryColor, alpha));
        }
    }

    private void renderShop(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Shop", (int) (140 / 1.2), (int) (75 / 1.2), Color.WHITE.getRGB(), true);
        context.getMatrices().pop();
    }

    private void loadTiers() {
        tiers.clear();

        JsonElement root = JSONFileEditor.readJsonFile("./koil/sys/store/membership.json");
        if (root == null || !root.isJsonObject()) {
            return;
        }

        JsonObject rootObject = root.getAsJsonObject();
        JsonArray tierArray = getArray(rootObject, "tiers");
        JsonObject defaults = rootObject.has("defaults") && rootObject.get("defaults").isJsonObject() ? rootObject.getAsJsonObject("defaults") : new JsonObject();
        JsonObject styles = rootObject.has("styles") && rootObject.get("styles").isJsonObject() ? rootObject.getAsJsonObject("styles") : new JsonObject();

        if (rootObject.has("links") && rootObject.get("links").isJsonObject()) {
            JsonObject links = rootObject.getAsJsonObject("links");
            this.globalKofiLink = getString(links, "kofi", this.globalKofiLink);
            this.globalMembershipLink = getString(links, "membership", this.globalMembershipLink);
        }

        this.fundraiserTitle = getString(rootObject, "fundraiserTitle", this.fundraiserTitle);
        this.fundraiserDescription = getString(rootObject, "fundraiserDescription", this.fundraiserDescription);

        if (tierArray == null) {
            return;
        }

        String defaultCurrency = getString(defaults, "currency", "USD");
        String defaultBillingPeriod = getString(defaults, "billingPeriod", "monthly");
        String defaultCtaLabel = getString(defaults, "ctaLabel", "Purchase");
        String defaultIconBasePath = getString(defaults, "iconBasePath", "membership/");
        String defaultIconType = getString(defaults, "iconType", "png");
        String defaultDisplayNameSuffix = getString(defaults, "displayNameSuffix", " Supporter");
        boolean defaultEnabled = getBoolean(defaults, "enabled", true);
        boolean defaultVisible = getBoolean(defaults, "visible", true);
        boolean defaultActive = getBoolean(defaults, "active", true);

        for (JsonElement tierElement : tierArray) {
            if (!tierElement.isJsonObject()) {
                continue;
            }

            JsonObject tierObject = tierElement.getAsJsonObject();
            String styleKey = getString(tierObject, "style", getString(tierObject, "metal", "default"));
            JsonObject styleObject = styles.has(styleKey) && styles.get(styleKey).isJsonObject() ? styles.getAsJsonObject(styleKey) : new JsonObject();

            String id = getString(tierObject, "id", "");
            String name = getString(tierObject, "name", "");
            String displayName = getString(tierObject, "displayName", name + defaultDisplayNameSuffix);
            String description = getString(tierObject, "description", "");
            double amount = getDouble(tierObject, "amount", 0.0);
            String link = getString(tierObject, "link", this.globalKofiLink);
            String membershipUrl = getString(tierObject, "membershipUrl", this.globalMembershipLink);
            String ctaLabel = getString(tierObject, "ctaLabel", defaultCtaLabel);
            String badgeText = getString(tierObject, "badgeText", getString(styleObject, "badgeText", name.toUpperCase(Locale.ROOT)));
            String presenceText = getString(tierObject, "presenceText", getString(styleObject, "presenceText", "Supporter"));
            String metal = getString(tierObject, "metal", styleKey);
            int accentColor = parseColor(getString(tierObject, "accentColor", getString(styleObject, "accentColor", "#FFFFFF")), Color.WHITE.getRGB());
            int accentSecondaryColor = parseColor(getString(tierObject, "accentColorSecondary", getString(styleObject, "accentColorSecondary", "#FFFFFF")), Color.WHITE.getRGB());
            int badgeColor = parseColor(getString(tierObject, "badgeColor", getString(styleObject, "badgeColor", "#FFFFFF")), Color.WHITE.getRGB());
            boolean enabled = getBoolean(tierObject, "enabled", defaultEnabled);
            boolean visible = getBoolean(tierObject, "visible", defaultVisible);
            boolean active = getBoolean(tierObject, "active", defaultActive);
            String iconPath = getString(tierObject, "iconPath", defaultIconBasePath + styleKey + "." + defaultIconType);
            List<String> tags = getMergedStringList(styleObject, "tags", tierObject, "tags");
            List<String> benefits = getStringList(tierObject, "benefits");

            tiers.add(new Tier(id, iconPath, name, displayName, description, amount, link, membershipUrl, ctaLabel, badgeText, presenceText, metal, defaultCurrency, defaultBillingPeriod, accentColor, accentSecondaryColor, badgeColor, enabled, visible, active, tags, benefits));
        }
    }

    private double getDouble(JsonObject object, String key, double fallback) {
        try {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsDouble();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private List<String> getMergedStringList(JsonObject first, String firstKey, JsonObject second, String secondKey) {
        List<String> values = new ArrayList<>();
        values.addAll(getStringList(first, firstKey));
        for (String item : getStringList(second, secondKey)) {
            if (!values.contains(item)) {
                values.add(item);
            }
        }
        return values;
    }

    private void openLink(String link) {
        if (link == null || link.isBlank()) {
            return;
        }
        Util.getOperatingSystem().open(link);
    }

    private void playUiClick(float pitch) {
        if (this.client != null) {
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, pitch));
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pitch + 0.28f));
        }
    }

    private void playUiPurchase(float pitch) {
        if (this.client != null) {
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, pitch));
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, pitch + 0.22f));
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pitch + 0.38f));
        }
    }

    private void playTierClick(Tier tier) {
        String lowerMetal = tier.metal.toLowerCase(Locale.ROOT);
        if (lowerMetal.equals("copper")) {
            playUiClick(0.88f);
        } else if (lowerMetal.equals("silver")) {
            playUiClick(1.00f);
        } else if (lowerMetal.equals("gold")) {
            playUiClick(1.10f);
        } else if (lowerMetal.equals("platinum")) {
            playUiClick(1.18f);
        } else {
            playUiClick(1.0f);
        }
    }

    private void playTierPurchase(Tier tier) {
        String lowerMetal = tier.metal.toLowerCase(Locale.ROOT);
        if (lowerMetal.equals("copper")) {
            playUiPurchase(0.94f);
        } else if (lowerMetal.equals("silver")) {
            playUiPurchase(1.02f);
        } else if (lowerMetal.equals("gold")) {
            playUiPurchase(1.08f);
        } else if (lowerMetal.equals("platinum")) {
            playUiPurchase(1.14f);
        } else {
            playUiPurchase(1.0f);
        }
    }

    private Tier getTierAt(double mouseX, double mouseY) {
        if (currentPanel != 1) {
            return null;
        }

        int contentLeft = 140;
        int contentTop = 90;
        int contentRight = this.width - 20;
        int contentBottom = this.height - 16;

        if (mouseX < contentLeft || mouseX > contentRight || mouseY < contentTop || mouseY > contentBottom) {
            return null;
        }

        int yStart = contentTop - scrollOffset;

        for (Tier tier : tiers) {
            if (!tier.enabled || !tier.visible || !tier.active) {
                continue;
            }

            int top = yStart;
            int bottom = yStart + getTierHeight(tier);

            if (mouseX >= contentLeft && mouseX <= contentRight && mouseY >= top && mouseY <= bottom) {
                return tier;
            }

            yStart += getTierHeight(tier) + 10;
        }

        return null;
    }

    private int getTierTop(Tier target) {
        int contentTop = 90;
        int yStart = contentTop - scrollOffset;

        for (Tier tier : tiers) {
            if (!tier.enabled || !tier.visible || !tier.active) {
                continue;
            }

            if (tier == target) {
                return yStart;
            }

            yStart += getTierHeight(tier) + 10;
        }

        return yStart;
    }

    private boolean isSupportButtonHit(Tier tier, double mouseX, double mouseY) {
        int top = getTierTop(tier);
        int buttonX = this.width - 156;
        int buttonY = top + 68;
        return mouseX >= buttonX && mouseX <= buttonX + 108 && mouseY >= buttonY && mouseY <= buttonY + 14;
    }

    private boolean isExpandHintHit(Tier tier, double mouseX, double mouseY) {
        int top = getTierTop(tier);
        int buttonX = this.width - 156;
        String text = isExpanded(tier) ? "Hide benefits" : "Show benefits";
        int hintX = buttonX + Math.max(0, (108 - this.textRenderer.getWidth(text)) / 2);
        int hintY = top + 88;
        int hintWidth = this.textRenderer.getWidth(text);
        return mouseX >= hintX && mouseX <= hintX + hintWidth && mouseY >= hintY && mouseY <= hintY + 12;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Tier tier = getTierAt(mouseX, mouseY);
        if (button == 0 && tier != null) {
            if (isSupportButtonHit(tier, mouseX, mouseY)) {
                playTierPurchase(tier);
                String targetLink = tier.membershipUrl != null && !tier.membershipUrl.isBlank() ? tier.membershipUrl : tier.link;
                openLink(targetLink);
                return true;
            }
            if (isExpandHintHit(tier, mouseX, mouseY)) {
                playTierClick(tier);
                if (isExpanded(tier)) {
                    this.expandedTierId = null;
                } else {
                    this.expandedTierId = tier.id;
                }
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (currentPanel == 1) {
            int contentLeft = 140;
            int contentTop = 90;
            int contentRight = this.width - 20;
            int contentBottom = this.height - 16;

            if (mouseX >= contentLeft && mouseX <= contentRight && mouseY >= contentTop && mouseY <= contentBottom) {
                this.scrollOffset -= (int) (amount * 18.0);
                this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScrollOffset));
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private static JsonArray getArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(key);
    }

    private static String getString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsBoolean();
    }

    private static List<String> getStringList(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return values;
        }

        JsonArray array = object.getAsJsonArray(key);
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static int parseColor(String value, int fallback) {
        try {
            return Color.decode(value).getRGB();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb & 16777215);
    }

    private static int mixColor(int colorA, int colorB, float ratio) {
        float clamped = Math.max(0.0f, Math.min(1.0f, ratio));

        int aA = (colorA >> 24) & 255;
        int rA = (colorA >> 16) & 255;
        int gA = (colorA >> 8) & 255;
        int bA = colorA & 255;

        int aB = (colorB >> 24) & 255;
        int rB = (colorB >> 16) & 255;
        int gB = (colorB >> 8) & 255;
        int bB = colorB & 255;

        int a = (int) (aA * (1.0f - clamped) + aB * clamped);
        int r = (int) (rA * (1.0f - clamped) + rB * clamped);
        int g = (int) (gA * (1.0f - clamped) + gB * clamped);
        int b = (int) (bA * (1.0f - clamped) + bB * clamped);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static class PriceParts {
        public final String main;
        public final String cents;

        public PriceParts(String main, String cents) {
            this.main = main;
            this.cents = cents;
        }
    }

    private static class Tier {
        public final String id;
        public final String iconPath;
        public final Identifier iconTexture;
        public final String name;
        public final String displayName;
        public final String description;
        public final double amount;
        public final String link;
        public final String membershipUrl;
        public final String ctaLabel;
        public final String badgeText;
        public final String presenceText;
        public final String metal;
        public final String currency;
        public final String billingPeriod;
        public final int accentColor;
        public final int accentSecondaryColor;
        public final int badgeColor;
        public final boolean enabled;
        public final boolean visible;
        public final boolean active;
        public final List<String> tags;
        public final List<String> benefits;
        public final float sweepSpeed;
        public final int sparkleCount;
        public final float pricePulseSpeed;

        public Tier(String id, String iconPath, String name, String displayName, String description, double amount, String link, String membershipUrl, String ctaLabel, String badgeText, String presenceText, String metal, String currency, String billingPeriod, int accentColor, int accentSecondaryColor, int badgeColor, boolean enabled, boolean visible, boolean active, List<String> tags, List<String> benefits) {
            this.id = id;
            this.iconPath = iconPath;
            Identifier loadedTexture = null;
            try {
                loadedTexture = loadExternalPngTexture(uiImageDirectory, iconPath);
            } catch (Exception ignored) {
            }
            this.iconTexture = loadedTexture != null ? loadedTexture : FALLBACK_TIER_TEXTURE;
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.amount = amount;
            this.link = link;
            this.membershipUrl = membershipUrl;
            this.ctaLabel = ctaLabel;
            this.badgeText = badgeText;
            this.presenceText = presenceText;
            this.metal = metal;
            this.currency = currency;
            this.billingPeriod = billingPeriod;
            this.accentColor = accentColor;
            this.accentSecondaryColor = accentSecondaryColor;
            this.badgeColor = badgeColor;
            this.enabled = enabled;
            this.visible = visible;
            this.active = active;
            this.tags = tags != null ? tags : new ArrayList<>();
            this.benefits = benefits != null ? benefits : new ArrayList<>();

            String lowerMetal = metal.toLowerCase(Locale.ROOT);
            if (lowerMetal.equals("copper")) {
                this.sweepSpeed = 7.0f;
                this.sparkleCount = 3;
                this.pricePulseSpeed = 320.0f;
            } else if (lowerMetal.equals("silver")) {
                this.sweepSpeed = 5.5f;
                this.sparkleCount = 4;
                this.pricePulseSpeed = 270.0f;
            } else if (lowerMetal.equals("gold")) {
                this.sweepSpeed = 4.7f;
                this.sparkleCount = 5;
                this.pricePulseSpeed = 230.0f;
            } else if (lowerMetal.equals("platinum")) {
                this.sweepSpeed = 4.0f;
                this.sparkleCount = 6;
                this.pricePulseSpeed = 200.0f;
            } else {
                this.sweepSpeed = 6.0f;
                this.sparkleCount = 3;
                this.pricePulseSpeed = 300.0f;
            }
        }
    }
}
