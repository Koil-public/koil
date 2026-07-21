package com.spirit.client.gui.main;

import com.spirit.Main;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class FirstLaunchTermsScreen extends Screen {
    private static final int HEADER_HEIGHT = 60;
    private static final int FOOTER_HEIGHT = 54;
    private static final int SIDEBAR_WIDTH = 146;
    private static final int CONTENT_PADDING = 14;
    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 20;
    private static final int NAVIGATION_TOP = HEADER_HEIGHT + 38;
    private static final List<RemoteFile> BOOTSTRAP_FILES = List.of(
            new RemoteFile("config.json", "runtime configuration", "./koil/sys/config.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/config.json"),
            new RemoteFile("sys.json", "system metadata", "./koil/sys/sys.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/sys.json"),
            new RemoteFile("koil.json", "Koil metadata", "Koil runtime storage", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/koil.json"),
            new RemoteFile("key.json", "asset/update index", "./koil/sys/key.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/key.json"),
            new RemoteFile("catcher.json", "asset/update index", "./koil/sys/catcher.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/catcher.json"),
            new RemoteFile("design.json", "default UI color and layout data", "./koil/sys/design", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/design.json"),
            new RemoteFile("data.json", "runtime data", "Koil runtime storage", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/data.json"),
            new RemoteFile("music.json", "design music catalog", "./koil/sys/design", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/content/sys/design/files/music.json"),
            new RemoteFile("background.json", "design background catalog", "./koil/sys/design", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/content/sys/design/files/background.json"),
            new RemoteFile("help_book.json", "in-game help content", "./koil/wiki/help_book.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/wiki/help_book.json"),
            new RemoteFile("membership.json", "funding display metadata", "./koil/store/membership.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/store/membership.json"),
            new RemoteFile("validDigits.json", "package validation data", "./koil/auth/validDigits.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/validDigits.json"),
            new RemoteFile("validSerial.json", "package validation data", "./koil/auth/validSerial.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/validSerial.json"),
            new RemoteFile("verifiedAuthors.json", "package author validation data", "./koil/auth/verifiedAuthors.json", "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/verifiedAuthors.json")
    );

    private Step step = Step.DISCLOSURE;
    private DisclosurePage page = DisclosurePage.SYSTEMS;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;

    public FirstLaunchTermsScreen() {
        super(Text.literal("Koil First Launch Disclosure"));
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearChildren();
        if (step == Step.DISCLOSURE) {
            int buttonY = NAVIGATION_TOP;
            for (DisclosurePage disclosurePage : DisclosurePage.values()) {
                ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(disclosurePage.label), value -> selectPage(disclosurePage))
                        .dimensions(10, buttonY, SIDEBAR_WIDTH - 20, 18)
                        .build());
                button.active = disclosurePage != page;
                buttonY += 20;
            }

            int footerY = this.height - 32;
            addDrawableChild(ButtonWidget.builder(Text.literal("Exit Game"), value -> declineAndExit())
                    .dimensions(12, footerY, 88, BUTTON_HEIGHT)
                    .build());
            int acceptWidth = Math.min(190, Math.max(132, this.width - 122));
            addDrawableChild(ButtonWidget.builder(Text.literal("Accept and Choose UI"), value -> {
                        this.step = Step.UI_PREFERENCE;
                        this.scrollOffset = 0;
                        rebuildButtons();
                    })
                    .dimensions(this.width - acceptWidth - 12, footerY, acceptWidth, BUTTON_HEIGHT)
                    .build());
        } else {
            int footerY = this.height - 32;
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"), value -> {
                        this.step = Step.DISCLOSURE;
                        this.scrollOffset = 0;
                        rebuildButtons();
                    })
                    .dimensions(12, footerY, 72, BUTTON_HEIGHT)
                    .build());

            int gap = 8;
            int controlsLeft = 92;
            int available = Math.max(160, this.width - controlsLeft - 12);
            int buttonWidth = Math.max(76, Math.min(190, (available - gap) / 2));
            int rightX = this.width - buttonWidth - 12;
            int leftX = Math.max(controlsLeft, rightX - buttonWidth - gap);
            addDrawableChild(ButtonWidget.builder(Text.literal("Keep Vanilla UI"), value -> acceptAndExit(false))
                    .dimensions(leftX, footerY, buttonWidth, BUTTON_HEIGHT)
                    .build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Enable Koil UI"), value -> acceptAndExit(true))
                    .dimensions(rightX, footerY, buttonWidth, BUTTON_HEIGHT)
                    .build());
        }
    }

    private void selectPage(DisclosurePage disclosurePage) {
        this.page = disclosurePage;
        this.scrollOffset = 0;
        rebuildButtons();
    }

    private void acceptAndExit(boolean uiRedesign) {
        Main.SUBLOGGER.logI("First-Launch thread", "First launch disclosure accepted. uiRedesign=" + uiRedesign);
        Main.completeFirstLaunch(uiRedesign);
        MinecraftClient.getInstance().setScreen(new FirstLaunchDownloadScreen());
    }

    private void declineAndExit() {
        Main.SUBLOGGER.logW("First-Launch thread", "First launch disclosure declined. Closing without deleting Koil or instance files.");
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.stop();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isInsideContent(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }
        setScrollOffset(this.scrollOffset - (int) (amount * 22));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            this.scrollbarDragOffset = scrollbarGrabOffset(mouseY);
            this.scrollOffset = scrollOffsetFromMouse(mouseY, this.scrollbarDragOffset);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.draggingScrollbar) {
            this.scrollOffset = scrollOffsetFromMouse(mouseY, this.scrollbarDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        renderShell(context);
        renderContent(context);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderShell(DrawContext context) {
        int overlay = KoilScreenBackgrounds.overlayColor(MinecraftClient.getInstance());
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        int header = new Color(uiColorHeader, true).getRGB();
        int title = new Color(uiColorHeaderTitleText, true).getRGB();
        int subtitle = new Color(uiColorHeaderSubTitleText, true).getRGB();

        context.fill(0, 0, this.width, this.height, overlay);
        context.drawBorder(0, 0, this.width, this.height, border);
        context.fill(0, 0, this.width, HEADER_HEIGHT, header);
        context.drawBorder(0, 0, this.width, HEADER_HEIGHT, border);
        context.drawTexture(Main.LOGO_TEXTURE, 10, 5, 0, 0, 45, 45, 45, 45);
        context.getMatrices().push();
        context.getMatrices().scale(2F, 2F, 1F);
        context.drawText(this.textRenderer, "Koil", 34, 6, title, true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, step == Step.DISCLOSURE ? "System Information - First Launch" : "Interface Choice - First Launch", 68, 35, subtitle, true);

        if (step == Step.DISCLOSURE) {
            context.drawText(this.textRenderer, "Select a topic", 12, HEADER_HEIGHT + 18, subtitle, false);
        }
    }

    private void renderContent(DrawContext context) {
        int left = contentLeft();
        int top = contentTop();
        int right = contentRight();
        int bottom = contentBottom();
        int width = Math.max(80, right - left);
        List<LayoutRow> rows = layoutRows(width);
        int maxScroll = Math.max(0, totalHeight(rows) - Math.max(1, bottom - top));
        if (this.scrollOffset > maxScroll) {
            this.scrollOffset = maxScroll;
        }

        context.enableScissor(left, top, right + 1, bottom);
        int y = top - this.scrollOffset;
        for (LayoutRow row : rows) {
            if (y + row.height > top && y < bottom) {
                renderRow(context, row, left, y, width);
            }
            y += row.height;
        }
        context.disableScissor();
        renderScrollbar(context, rows);
    }

    private void renderRow(DrawContext context, LayoutRow row, int x, int y, int width) {
        int title = new Color(uiColorContentBaseTitleText, true).getRGB();
        int body = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        int muted = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int accent = new Color(uiColorIDEFileNameText, true).getRGB();
        int warning = new Color(uiColorWarningPromptText, true).getRGB();
        int border = new Color(uiColorBackgroundBorder, true).getRGB();

        switch (row.kind) {
            case SECTION -> {
                float scale = 1.3F;
                context.fill(x, y + row.height - 2, x + width, y + row.height - 1, withAlpha(uiColorContentStripeLeft, 120));
                context.getMatrices().push();
                context.getMatrices().scale(scale, scale, 1.0F);
                context.drawText(this.textRenderer, row.primary, Math.round(x / scale), Math.round((y + 9) / scale), title, true);
                context.getMatrices().pop();
            }
            case TEXT -> drawWrapped(context, row.primaryLines, x, y + 1, body, false);
            case BULLET -> {
                context.fill(x + 2, y + 5, x + 5, y + 8, accent);
                drawWrapped(context, row.primaryLines, x + 10, y + 1, body, false);
            }
            case PATH -> {
                int split = x + Math.max(112, Math.min(width - 110, width * 44 / 100));
                context.fill(x, y, x + width, y + row.height - 2, withAlpha(uiColorBackgroundOverlay, 110));
                context.drawBorder(x, y, width, row.height - 2, withAlpha(uiColorBackgroundBorder, 130));
                context.fill(split, y, split + 1, y + row.height - 2, withAlpha(uiColorBackgroundBorder, 90));
                drawWrapped(context, row.primaryLines, x + 6, y + 4, accent, false);
                drawWrapped(context, row.secondaryLines, split + 6, y + 4, muted, false);
            }
            case CODE -> {
                context.fill(x, y, x + width, y + row.height - 2, withAlpha(uiColorBackgroundOverlay, 150));
                context.drawBorder(x, y, width, row.height - 2, withAlpha(uiColorIDEFileNameText, 100));
                drawWrapped(context, row.primaryLines, x + 6, y + 4, accent, false);
            }
            case NOTE -> {
                context.fill(x, y, x + 3, y + row.height - 2, warning);
                context.fill(x + 3, y, x + width, y + row.height - 2, withAlpha(uiColorWarningPromptText, 20));
                drawWrapped(context, row.primaryLines, x + 9, y + 4, body, false);
            }
            case TABLE_HEADER, TABLE_ROW -> {
                int split = x + Math.max(112, Math.min(width - 110, width * 36 / 100));
                int fill = row.kind == RowKind.TABLE_HEADER ? withAlpha(uiColorBackgroundOverlay, 150) : withAlpha(uiColorBackgroundOverlay, 70);
                context.fill(x, y, x + width, y + row.height - 1, fill);
                context.fill(split, y, split + 1, y + row.height - 1, border);
                context.fill(x, y + row.height - 1, x + width, y + row.height, withAlpha(uiColorBackgroundBorder, 90));
                drawWrapped(context, row.primaryLines, x + 5, y + 4, row.kind == RowKind.TABLE_HEADER ? title : accent, row.kind == RowKind.TABLE_HEADER);
                drawWrapped(context, row.secondaryLines, split + 6, y + 4, row.kind == RowKind.TABLE_HEADER ? title : body, row.kind == RowKind.TABLE_HEADER);
            }
            case SYSTEM_MAP -> renderSystemMap(context, x, y, width, row.height);
            case RISK -> renderActionImpact(context, x, y, width, row.height);
            case SERVER -> renderServerCompatibility(context, x, y, width, row.height);
            case CHART -> renderCapabilityChart(context, x, y, width, row.height);
            case FLOW -> renderDataFlow(context, x, y, width, row.height);
            case SPACER -> {
            }
        }
    }

    private void drawWrapped(DrawContext context, List<OrderedText> lines, int x, int y, int color, boolean shadow) {
        int lineY = y;
        for (OrderedText line : lines) {
            context.drawText(this.textRenderer, line, x, lineY, color, shadow);
            lineY += LINE_HEIGHT;
        }
    }

    private void renderSystemMap(DrawContext context, int x, int y, int width, int height) {
        int title = new Color(uiColorContentBaseTitleText, true).getRGB();
        int body = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        int muted = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int accent = new Color(uiColorIDEFileNameText, true).getRGB();
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        String[] systems = {"Core Runtime", "Interface & Diagnostics", "Files & Content", "Automation", "Online & Server", "API & Integration"};
        String[] subsystems = {
                "startup, bootstrap, lifecycle, config, design reload",
                "screens, F3, performance, console, media rendering",
                "explorer, editor, packages, mods, packs, skins",
                "CLI, planner, resolver, executor, feedback",
                "HTTP services, Koil channels, snapshots, markets, HUD",
                "mixins, hooks, client/server registration, public APIs"
        };
        context.getMatrices().push();
        context.getMatrices().translate(x, y + 3, 0);
        context.getMatrices().scale(1.3F, 1.3F, 1.0F);
        context.drawText(this.textRenderer, "How Koil's main systems connect", 0, 0, title, true);
        context.getMatrices().pop();
        int hubY = y + 18;
        context.fill(x, hubY, x + width, hubY + 24, withAlpha(uiColorContentStripeLeft, 38));
        context.drawBorder(x, hubY, width, 24, withAlpha(uiColorContentStripeLeft, 150));
        context.drawText(this.textRenderer, "Shared Koil runtime", x + 7, hubY + 5, accent, true);
        if (width >= 430) {
            context.drawText(this.textRenderer, "Minecraft client state + Koil services + instance storage", x + 122, hubY + 5, muted, false);
        }

        int columns = width >= 500 ? 2 : 1;
        int gap = 8;
        int cardWidth = columns == 2 ? (width - gap) / 2 : width;
        int cardHeight = columns == 2 ? 50 : 62;
        int cardsTop = hubY + 32;
        for (int index = 0; index < systems.length; index++) {
            int column = index % columns;
            int row = index / columns;
            int cardX = x + column * (cardWidth + gap);
            int cardY = cardsTop + row * (cardHeight + gap);
            context.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, withAlpha(uiColorBackgroundOverlay, 105));
            context.drawBorder(cardX, cardY, cardWidth, cardHeight, border);
            context.fill(cardX, cardY, cardX + 3, cardY + cardHeight, withAlpha(accent, 170));
            context.drawText(this.textRenderer, systems[index], cardX + 8, cardY + 5, title, true);
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(subsystems[index]), cardWidth - 16);
            drawWrapped(context, wrapped, cardX + 8, cardY + 18, body, false);
        }
    }

    private void renderActionImpact(DrawContext context, int x, int y, int width, int height) {
        int title = new Color(uiColorContentBaseTitleText, true).getRGB();
        int body = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        int muted = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int accent = new Color(uiColorIDEFileNameText, true).getRGB();
        int warning = new Color(uiColorWarningPromptText, true).getRGB();
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        String[] labels = {"Inspect", "Customize", "Manage", "Act", "High impact"};
        String[] examples = {"view metadata, logs and stats", "themes, configs and screens", "install, replace, move or delete files", "move, mine, attack, use and transfer", "commands, package removal, world or server consequences"};

        context.drawText(this.textRenderer, "Action-impact ladder", x, y + 2, title, true);
        if (width >= 520) {
            int gap = 6;
            int boxWidth = (width - gap * 4) / 5;
            int boxY = y + 20;
            for (int index = 0; index < labels.length; index++) {
                int boxX = x + index * (boxWidth + gap);
                int stripe = index < 3 ? withAlpha(accent, 105 + index * 24) : withAlpha(warning, 145 + (index - 3) * 45);
                context.fill(boxX, boxY, boxX + boxWidth, boxY + 84, withAlpha(uiColorBackgroundOverlay, 100));
                context.drawBorder(boxX, boxY, boxWidth, 84, border);
                context.fill(boxX, boxY, boxX + boxWidth, boxY + 3, stripe);
                context.drawText(this.textRenderer, labels[index], boxX + 5, boxY + 8, index >= 3 ? warning : accent, true);
                List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(examples[index]), boxWidth - 10);
                drawWrapped(context, wrapped, boxX + 5, boxY + 23, body, false);
            }
        } else {
            int rowY = y + 20;
            for (int index = 0; index < labels.length; index++) {
                int stripe = index < 3 ? withAlpha(accent, 115 + index * 22) : withAlpha(warning, 155 + (index - 3) * 35);
                context.fill(x, rowY, x + width, rowY + 42, withAlpha(uiColorBackgroundOverlay, 100));
                context.drawBorder(x, rowY, width, 42, border);
                context.fill(x, rowY, x + 3, rowY + 42, stripe);
                context.drawText(this.textRenderer, labels[index], x + 8, rowY + 6, index >= 3 ? warning : accent, true);
                List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(examples[index]), width - 16);
                drawWrapped(context, wrapped, x + 8, rowY + 19, body, false);
                rowY += 46;
            }
        }
    }

    private void renderServerCompatibility(DrawContext context, int x, int y, int width, int height) {
        int title = new Color(uiColorContentBaseTitleText, true).getRGB();
        int body = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        int muted = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int accent = new Color(uiColorIDEFileNameText, true).getRGB();
        int warning = new Color(uiColorWarningPromptText, true).getRGB();
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        context.drawText(this.textRenderer, "Server compatibility model", x, y + 2, title, true);

        int gap = 10;
        int boxTop = y + 20;
        if (width >= 470) {
            int boxWidth = (width - gap) / 2;
            context.fill(x, boxTop, x + boxWidth, boxTop + 58, withAlpha(uiColorBackgroundOverlay, 100));
            context.drawBorder(x, boxTop, boxWidth, 58, border);
            context.fill(x + boxWidth + gap, boxTop, x + width, boxTop + 58, withAlpha(uiColorBackgroundOverlay, 100));
            context.drawBorder(x + boxWidth + gap, boxTop, boxWidth, 58, border);
            context.drawText(this.textRenderer, "Server without Koil", x + 7, boxTop + 6, warning, true);
            List<OrderedText> ordinaryServer = this.textRenderer.wrapLines(Text.literal("Client-side screens and normal Minecraft actions may work. Koil channels and server-owned features remain unavailable."), boxWidth - 14);
            drawWrapped(context, ordinaryServer, x + 7, boxTop + 21, body, false);
            int rightX = x + boxWidth + gap;
            context.drawText(this.textRenderer, "Server running Koil", rightX + 7, boxTop + 6, accent, true);
            List<OrderedText> koilServer = this.textRenderer.wrapLines(Text.literal("Koil channels can provide synced snapshots, markets, HUD data and registered server/client cooperation."), boxWidth - 14);
            drawWrapped(context, koilServer, rightX + 7, boxTop + 21, body, false);
        } else {
            String[] lines = {
                    "Without Koil server  >  client-side tools + normal Minecraft actions",
                    "With Koil server     >  Koil channels + synced server-owned features",
                    "Unavailable channel  >  compatible features fall back or remain unavailable"
            };
            int rowY = boxTop;
            for (String line : lines) {
                context.fill(x, rowY, x + width, rowY + 22, withAlpha(uiColorBackgroundOverlay, 100));
                context.drawBorder(x, rowY, width, 22, border);
                context.drawText(this.textRenderer, line, x + 6, rowY + 7, body, false);
                rowY += 26;
            }
        }
        context.drawText(this.textRenderer, "Koil is built to try to work across servers, not to guarantee identical behavior on every server.", x, y + height - 12, muted, false);
    }

    private void renderCapabilityChart(DrawContext context, int x, int y, int width, int height) {
        String[] labels = {"Local files", "Network", "Vanilla UI", "Game control", "System UI"};
        String[] values = {"write / replace / delete", "HTTPS and server channels", "mixins and screen replacement", "automation can act for player", "dialogs and external windows"};
        int[] levels = {4, 3, 4, 4, 2};
        int labelWidth = Math.min(104, Math.max(78, width / 4));
        int valueWidth = Math.min(164, Math.max(92, width / 3));
        int chartLeft = x + labelWidth;
        int chartRight = x + width - valueWidth - 8;
        int chartWidth = Math.max(48, chartRight - chartLeft);
        int body = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        int muted = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int accent = new Color(uiColorIDEFileNameText, true).getRGB();
        int rowY = y + 17;

        context.drawText(this.textRenderer, "Capability reach", x, y + 2, new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.drawText(this.textRenderer, "reach, not frequency", x + width - this.textRenderer.getWidth("reach, not frequency"), y + 2, muted, false);
        for (int index = 0; index < labels.length; index++) {
            context.drawText(this.textRenderer, labels[index], x, rowY + 2, body, false);
            for (int segment = 0; segment < 4; segment++) {
                int segmentLeft = chartLeft + segment * chartWidth / 4;
                int segmentRight = chartLeft + (segment + 1) * chartWidth / 4 - 2;
                int color = segment < levels[index] ? withAlpha(accent, 150 + segment * 22) : withAlpha(uiColorBackgroundBorder, 45);
                context.fill(segmentLeft, rowY + 2, segmentRight, rowY + 9, color);
            }
            context.drawText(this.textRenderer, values[index], chartRight + 6, rowY + 2, muted, false);
            rowY += 17;
        }
    }

    private void renderDataFlow(DrawContext context, int x, int y, int width, int height) {
        int accent = new Color(uiColorIDEFileNameText, true).getRGB();
        int body = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        int muted = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        context.drawText(this.textRenderer, "Observed network and storage flow", x, y + 2, new Color(uiColorContentBaseTitleText, true).getRGB(), true);

        if (width >= 470) {
            int gap = 14;
            int boxWidth = (width - gap * 3) / 4;
            String[] headings = {"Remote source", "Koil request", "Local state", "Visible result"};
            String[] values = {"GitHub, Modrinth, Mojang, NameMC", "HTTP(S) or Koil network channel", "runtime file, cache, temp file, memory", "screen, install, skin, stats, HUD"};
            int boxY = y + 22;
            for (int index = 0; index < 4; index++) {
                int boxX = x + index * (boxWidth + gap);
                context.fill(boxX, boxY, boxX + boxWidth, boxY + 46, withAlpha(uiColorBackgroundOverlay, 105));
                context.drawBorder(boxX, boxY, boxWidth, 46, border);
                context.drawText(this.textRenderer, headings[index], boxX + 5, boxY + 5, accent, true);
                List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(values[index]), boxWidth - 10);
                drawWrapped(context, wrapped, boxX + 5, boxY + 18, body, false);
                if (index < 3) {
                    int arrowX = boxX + boxWidth + 3;
                    context.fill(arrowX, boxY + 22, arrowX + gap - 6, boxY + 24, accent);
                    context.fill(arrowX + gap - 8, boxY + 19, arrowX + gap - 5, boxY + 27, accent);
                }
            }
        } else {
            String[] lines = {
                    "Remote source  >  HTTPS or server channel",
                    "Koil request   >  parsed memory / local cache / temp file",
                    "Local state    >  screen, install, skin, stats, or HUD result"
            };
            int rowY = y + 22;
            for (String line : lines) {
                context.fill(x, rowY, x + width, rowY + 18, withAlpha(uiColorBackgroundOverlay, 105));
                context.drawBorder(x, rowY, width, 18, border);
                context.drawText(this.textRenderer, line, x + 6, rowY + 5, body, false);
                rowY += 22;
            }
        }
        context.drawText(this.textRenderer, "A feature can have more than one path. The source code remains the final authority.", x, y + height - 13, muted, false);
    }

    private List<LayoutRow> layoutRows(int width) {
        ArrayList<LayoutRow> rows = new ArrayList<>();
        for (ContentRow row : contentRows()) {
            switch (row.kind) {
                case SECTION -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), 24));
                case TEXT -> {
                    List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(row.primary), Math.max(40, width));
                    rows.add(new LayoutRow(row.kind, row.primary, row.secondary, lines, List.of(), lines.size() * LINE_HEIGHT + 5));
                }
                case BULLET -> {
                    List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(row.primary), Math.max(40, width - 12));
                    rows.add(new LayoutRow(row.kind, row.primary, row.secondary, lines, List.of(), lines.size() * LINE_HEIGHT + 5));
                }
                case PATH -> {
                    int firstWidth = Math.max(80, Math.min(width - 90, width * 44 / 100) - 12);
                    int secondWidth = Math.max(80, width - firstWidth - 24);
                    List<OrderedText> first = this.textRenderer.wrapLines(Text.literal(row.primary), firstWidth);
                    List<OrderedText> second = this.textRenderer.wrapLines(Text.literal(row.secondary), secondWidth);
                    int lineCount = Math.max(first.size(), second.size());
                    rows.add(new LayoutRow(row.kind, row.primary, row.secondary, first, second, Math.max(21, lineCount * LINE_HEIGHT + 8)));
                }
                case CODE -> {
                    List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(row.primary), Math.max(40, width - 12));
                    rows.add(new LayoutRow(row.kind, row.primary, row.secondary, lines, List.of(), lines.size() * LINE_HEIGHT + 10));
                }
                case NOTE -> {
                    List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(row.primary), Math.max(40, width - 14));
                    rows.add(new LayoutRow(row.kind, row.primary, row.secondary, lines, List.of(), lines.size() * LINE_HEIGHT + 11));
                }
                case TABLE_HEADER, TABLE_ROW -> {
                    int firstWidth = Math.max(80, Math.min(width - 90, width * 36 / 100) - 10);
                    int secondWidth = Math.max(80, width - firstWidth - 22);
                    List<OrderedText> first = this.textRenderer.wrapLines(Text.literal(row.primary), firstWidth);
                    List<OrderedText> second = this.textRenderer.wrapLines(Text.literal(row.secondary), secondWidth);
                    int lineCount = Math.max(first.size(), second.size());
                    rows.add(new LayoutRow(row.kind, row.primary, row.secondary, first, second, lineCount * LINE_HEIGHT + 8));
                }
                case SYSTEM_MAP -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), width >= 500 ? 222 : 482));
                case RISK -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), width >= 520 ? 119 : 254));
                case SERVER -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), width >= 470 ? 104 : 118));
                case CHART -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), 111));
                case FLOW -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), width >= 470 ? 92 : 108));
                case SPACER -> rows.add(new LayoutRow(row.kind, "", "", List.of(), List.of(), 8));
            }
        }
        return rows;
    }

    private List<ContentRow> contentRows() {
        if (step == Step.UI_PREFERENCE) {
            return uiPreferenceRows();
        }
        return switch (page) {
            case SYSTEMS -> systemsRows();
            case CAPABILITIES -> capabilitiesRows();
            case DATA -> dataRows();
            case NETWORK -> networkRows();
            case FILES -> fileRows();
            case CONTROL -> controlRows();
            case SOURCE -> sourceRows();
        };
    }

    private List<ContentRow> systemsRows() {
        return List.of(
                ContentRow.section("How Koil is structured"),
                ContentRow.text("Koil runs as a group of connected systems inside the Minecraft client. The systems share the current game state, the instance filesystem, Koil runtime services, and supported Minecraft integration points."),
                ContentRow.systemMap(),
                ContentRow.section("Main systems and their subsystems"),
                ContentRow.tableHeader("Main system", "Subsystems demonstrated by the reviewed source"),
                ContentRow.tableRow("Core Runtime", "startup, first launch, bootstrap downloads, lifecycle, configuration, theme and design reload"),
                ContentRow.tableRow("Interface & Diagnostics", "screen redesigns, widgets, F3 diagnostics, performance monitoring, console, media and reports"),
                ContentRow.tableRow("Files & Content", "file explorer/editor, config tools, package manager, mods, resource packs, datapacks, shaders and skins"),
                ContentRow.tableRow("Automation", "console routing, KTL compiler, planner, resolver, executor, runtime state, proof and feedback"),
                ContentRow.tableRow("Online & Server", "remote metadata, bootstrap files, skin services, Koil packet channels, snapshots, markets and HUD"),
                ContentRow.tableRow("API & Integration", "mixins, screen hooks, client/server registration, Java APIs and compatibility adapters"),
                ContentRow.section("What this means in practice"),
                ContentRow.bullet("A screen is often only the visible front end. Its work may be performed by file, network, automation, rendering or server subsystems."),
                ContentRow.bullet("A subsystem can retain local state, caches or generated files after its screen closes."),
                ContentRow.bullet("Changing the interface preference does not uninstall or sandbox the other Koil systems."),
                ContentRow.note("Koil should be treated as an instance-management, diagnostics, integration and automation platform rather than a cosmetic-only mod.")
        );
    }

    private List<ContentRow> capabilitiesRows() {
        return List.of(
                ContentRow.section("What people can do with Koil"),
                ContentRow.text("The following examples are based on executable paths in the reviewed source. They describe what a person using Koil, a task/template author, a package author, a selected remote source, or a compatible Koil server can cause Koil to process or attempt."),
                ContentRow.risk(),
                ContentRow.section("From routine use to potentially harmful use"),
                ContentRow.tableHeader("Impact", "Real examples demonstrated by code"),
                ContentRow.tableRow("Routine inspection", "view mod and pack metadata, read logs, inspect configs, view files, render F3 diagnostics, search content and look up skins"),
                ContentRow.tableRow("Customization", "change supported interface presentation, edit typed config values, choose themes, manage skins and open Koil tools"),
                ContentRow.tableRow("Instance management", "download, create, edit, move, rename, install, disable, replace or delete files, mods, packs and package entries"),
                ContentRow.tableRow("Player automation", "move, sprint, jump, look, navigate, use items or blocks, mine, attack, interact, select slots and transfer container items"),
                ContentRow.tableRow("Communication and commands", "send chat messages and submit raw Minecraft commands through the normal client network handler"),
                ContentRow.tableRow("High-impact package actions", "a reviewed package can declare add, replace and remove operations against paths inside the Minecraft instance"),
                ContentRow.section("Examples of possible harm"),
                ContentRow.bullet("An incorrect file or package action could overwrite configs, remove mods or packs, damage an instance, or remove world files located inside the instance root."),
                ContentRow.bullet("An automation task could walk into danger, consume or move resources through normal game behavior, mine or attack the wrong target, alter containers, expose chat text, or execute a command with the player's existing permission level."),
                ContentRow.bullet("On multiplayer servers, automation may trigger anti-cheat, violate rules, cause griefing-like effects, lose items, expose the account to moderation, or perform actions the user did not carefully review."),
                ContentRow.bullet("A malicious or compromised package or remote file source could provide harmful content. Identity metadata and a successful download do not prove that content is safe."),
                ContentRow.section("Code-backed safeguards that exist"),
                ContentRow.tableHeader("Safeguard", "What it actually limits"),
                ContentRow.tableRow("User start and stop controls", "automation is entered through user-facing routes; /automate off or exit cancels execution, clears queued work and releases held inputs"),
                ContentRow.tableRow("Package review and identity checks", "packages are listed as pending approval, checked against author/serial data, and re-inspected before application"),
                ContentRow.tableRow("Path containment", "ZIP extraction and declared remove operations normalize targets and block paths that resolve outside the instance root"),
                ContentRow.tableRow("Optional SHA-256 verification", "package files are hash-checked only when the manifest supplies a nonblank sha256 value"),
                ContentRow.tableRow("Network bounds", "the activity client checks whether the server channel is available, rate-limits requests, caps row counts and limits decoded string lengths"),
                ContentRow.tableRow("Protocol-specific receivers", "the reviewed Koil activity receivers decode defined snapshot and market-HUD packet structures; those reviewed paths are not arbitrary automation or code-execution receivers"),
                ContentRow.tableRow("Minecraft server authority", "commands and interactions still travel through normal Minecraft client/server paths, so server permissions, protections and rejection rules still apply"),
                ContentRow.section("Important limits of those safeguards"),
                ContentRow.note("The reviewed code does not create a general sandbox or granular permission system around Koil's local file, network, mixin or automation capabilities."),
                ContentRow.bullet("Verified author and serial data establish package identity under Koil's scheme; they do not establish that every operation is safe, and the trusted lists are themselves loaded from Koil auth-data files."),
                ContentRow.bullet("The package root check still permits broad changes anywhere inside the Minecraft instance, including saves, configs and mods."),
                ContentRow.bullet("A manifest-provided hash detects mismatch against that manifest; it is not an independent trust decision when the manifest and payload come from the same untrusted source."),
                ContentRow.bullet("The reviewed automation path does not show a separate confirmation prompt before every primitive such as attack, mine, transfer, chat or raw command."),
                ContentRow.bullet("Server permissions can reject unauthorized commands, but they do not prevent every harmful action that a normally permitted player can perform."),
                ContentRow.bullet("The reviewed snapshot and HUD receivers are narrowly structured, but that finding does not prove that every receiver in every future build has the same limits."),
                ContentRow.section("Server support"),
                ContentRow.server(),
                ContentRow.text("Koil is not intended to provide identical behavior on every server. It is built to try to operate through client-side integrations and normal Minecraft actions, but it works best when the connected server also runs Koil and exposes the matching Koil channels and server APIs.")
        );
    }

    private List<ContentRow> dataRows() {
        return List.of(
                ContentRow.section("Game and player data processed locally"),
                ContentRow.tableHeader("Observed value", "Demonstrated use"),
                ContentRow.tableRow("Local player name and UUID", "Context keys, activity ownership, local display and cache records"),
                ContentRow.tableRow("Visible player names and UUIDs", "Visible-player activity and held-item observations"),
                ContentRow.tableRow("Main-hand and off-hand item IDs", "Client-observed activity rows and local history"),
                ContentRow.tableRow("World/server context", "Separates cached observations by current world or server"),
                ContentRow.tableRow("Player position, inventory, targets, blocks and entities", "Automation resolution, movement, interaction and diagnostics"),
                ContentRow.spacer(),
                ContentRow.text("The reviewed GlobalActivityClient code samples the local player and visible players every 100 client ticks, records held-item and presence observations, maintains short histories, and saves local context data."),
                ContentRow.note("The reviewed client path requests server snapshots and receives Koil snapshot/HUD packets. That code does not show client-observed held-item history being uploaded. This statement applies only to that reviewed path, not to code that has not been audited."),
                ContentRow.section("Files derived from use"),
                ContentRow.bullet("Logs can contain command text, task state, errors, file paths, selected IDs, and runtime diagnostics."),
                ContentRow.bullet("Automation feedback and proof systems can persist structured task results and failure details."),
                ContentRow.bullet("Skin lookup can use a typed username to request public Mojang and NameMC data, download PNG files, normalize them, and add them to the local skin library."),
                ContentRow.bullet("Config, file-preview, package, F3, performance, content, and editor systems parse local files to generate controls, metadata, warnings, charts, previews, and reports."),
                ContentRow.section("Not an AI privacy shortcut"),
                ContentRow.text("Local deterministic parsing is not the same as sending a file to an AI service. However, that does not make every Koil feature offline. Network-backed content, metadata, skin, bootstrap, update, and compatible-server features still make requests when used or initialized by their code paths.")
        );
    }

    private List<ContentRow> networkRows() {
        return List.of(
                ContentRow.section("Known network destinations and purposes"),
                ContentRow.tableHeader("Destination", "Purpose demonstrated by code or bootstrap inventory"),
                ContentRow.tableRow("raw.githubusercontent.com", "Koil bootstrap JSON, design catalogs, help content, funding data, package-validation data, and asset indexes"),
                ContentRow.tableRow("api.modrinth.com", "Project search, metadata, versions, compatibility checks, and content discovery"),
                ContentRow.tableRow("CurseForge endpoints", "Optional metadata/content path when API access is configured"),
                ContentRow.tableRow("api.mojang.com", "Minecraft username to profile lookup for skin tools"),
                ContentRow.tableRow("sessionserver.mojang.com", "Signed profile texture metadata for skin tools"),
                ContentRow.tableRow("textures.minecraft.net", "Skin texture download"),
                ContentRow.tableRow("namemc.com", "Best-effort public skin/profile-history lookup"),
                ContentRow.tableRow("Current Minecraft server", "Koil custom packet channels for snapshots, market/HUD data, and compatible server features"),
                ContentRow.spacer(),
                ContentRow.server(),
                ContentRow.flow(),
                ContentRow.section("Request contents"),
                ContentRow.bullet("Content searches can include project names, mod IDs, Minecraft version, loader filters, and selected project/version identifiers."),
                ContentRow.bullet("Skin searches include the username entered or the current session username when the skin tool is used."),
                ContentRow.bullet("Bootstrap requests reveal the normal network metadata associated with an HTTPS request, including IP address and request headers, to the hosting service."),
                ContentRow.bullet("Koil server-channel requests identify the connected client as a participant in that Minecraft connection and use protocol-defined packet content."),
                ContentRow.section("What changes when the server also runs Koil"),
                ContentRow.text("GlobalActivityClient checks ClientPlayNetworking.canSend before requesting a Koil snapshot. When the channel is unavailable, the reviewed activity view falls back to client-observed rows. When the server registers Koil's server API and channels, it can provide server-owned snapshots, market values, HUD state and other compatible features."),
                ContentRow.note("Koil attempts to remain useful on servers that do not run Koil, but server rules, protocol support, installed mods, anti-cheat and permissions can limit or block features. Koil-on-server is the intended environment for the fullest integration."),
                ContentRow.note("Do not claim 'zero telemetry' or 'nothing leaves the computer' unless an automated build audit verifies every network call in the exact released JAR.")
        );
    }

    private List<ContentRow> fileRows() {
        return List.of(
                ContentRow.section("Instance paths Koil can access"),
                ContentRow.path("./koil/**", "runtime configuration, design data, logs, packages, automation, caches, reports, wiki, skins and other Koil-owned state"),
                ContentRow.path("./config/**", "config discovery and editing for Koil and supported mods"),
                ContentRow.path("./mods/**", "mod discovery, import, install, disable, delete, package detection and local JAR inspection"),
                ContentRow.path("./resourcepacks/**", "resource-pack discovery, install, enable/disable and removal"),
                ContentRow.path("./shaderpacks/**", "shader-pack discovery, metadata, selection and management where supported"),
                ContentRow.path("./saves/<world>/datapacks/**", "world-specific datapack discovery, install and management"),
                ContentRow.path("./logs/latest.log", "Minecraft log reading in Koil's console"),
                ContentRow.path("System temporary directory", "downloaded skin images and media-processing intermediates"),
                ContentRow.section("Operations available through Koil tools"),
                ContentRow.tableHeader("Operation", "Effect"),
                ContentRow.tableRow("Read and inspect", "Open files, parse configs, inspect JAR metadata, preview media, scan folders, read logs"),
                ContentRow.tableRow("Create and write", "Save edits, generate configs, caches, reports, packages, skins, automation state and downloaded content"),
                ContentRow.tableRow("Replace and overwrite", "Config saves, downloads, package application and selected management operations can replace existing files"),
                ContentRow.tableRow("Move and rename", "File-manager and content-management operations can change paths"),
                ContentRow.tableRow("Delete", "User-invoked file, mod, pack, package and cleanup actions can remove files"),
                ContentRow.section("Koil package authority"),
                ContentRow.text("Package code searches for koil-package-* folders and ZIP files in the mods folder and instance root, reads package.json, classifies entries as add, replace, or remove, and holds valid packages for user review."),
                ContentRow.note("A valid author or serial is not a safety guarantee. Review every path and operation. A package that is allowed to apply at the instance root can materially change the game installation and worlds."),
                ContentRow.code("PackageEntry(relativePath, directory, size, existsInInstance, operation, sha256)"),
                ContentRow.code("operations: add | replace | remove")
        );
    }

    private List<ContentRow> controlRows() {
        return List.of(
                ContentRow.section("Automation authority"),
                ContentRow.text("Koil's KTL automation runtime is capable of acting through the Minecraft client. Its executor holds and releases key inputs, changes view direction, resolves nearby entities and blocks, interacts through the client interaction manager, and can delegate nested tasks."),
                ContentRow.tableHeader("Capability", "Examples demonstrated by executor code"),
                ContentRow.tableRow("Movement and camera", "forward/back/strafe, sprint, jump, target movement, mouse look, navigation recovery"),
                ContentRow.tableRow("World interaction", "use item, use block, interact with entity, mine block, attack entity"),
                ContentRow.tableRow("Inventory and containers", "select inventory slots, inspect counts, transfer items through screen-handler slots"),
                ContentRow.tableRow("Communication", "send chat messages and execute raw Minecraft commands"),
                ContentRow.tableRow("Observation and memory", "read player position, inventory, stats, targets, blocks, entities and task state"),
                ContentRow.tableRow("Task control", "branch, delegate, return, wait, retry, hold input and cancel active execution"),
                ContentRow.section("Execution defenses and limits"),
                ContentRow.bullet("AutomationExecutor.cancel clears the execution stack, releases every held input, clears cached entities and reports the cancellation state."),
                ContentRow.bullet("AutomationRouter discards superseded planner outcomes and exposes /automate off and /automate exit to stop current work."),
                ContentRow.bullet("Raw commands are sent through Minecraft's normal network handler, so Koil does not grant command permissions the player does not already have."),
                ContentRow.note("These controls reduce runaway execution after a stop request. They do not preview or approve every primitive, reverse completed actions, recover lost items, undo world changes, or guarantee compliance with a server's rules."),
                ContentRow.note("Automation can cause normal in-game consequences, including movement, combat, block changes, item use, container changes, command execution, server-rule violations, or loss of items. Review tasks before running them."),
                ContentRow.section("Vanilla integration and rendering"),
                ContentRow.bullet("Mixin configuration allows Koil to inject into or replace supported vanilla screens, widgets, input handling, stats hooks, rendering paths and player-skin behavior."),
                ContentRow.bullet("The redesigned F3 system reads extensive client/world/target information and can render diagnostics, charts, timelines, target details and reports."),
                ContentRow.bullet("Content, config, file, media, console, skin, performance and package screens can open system dialogs or external windows where their code requests it."),
                ContentRow.section("Code proof"),
                ContentRow.code("updateHeldInputs(); updateTapInputs(); updateMouseLook(player);"),
                ContentRow.code("case \"cap.command.execute_raw\" -> AutomationRouter.sendRawCommand(rawCommand);"),
                ContentRow.code("case \"cap.report.say\" -> AutomationRouter.sendChatMessage(message);")
        );
    }

    private List<ContentRow> sourceRows() {
        ArrayList<ContentRow> rows = new ArrayList<>();
        rows.add(ContentRow.section("Source-first trust model"));
        rows.add(ContentRow.note("The code and bundled resources in the exact released build are the truth. Documentation, marketing text, generated descriptions, comments, changelogs, and this screen can be incomplete or stale."));
        rows.add(ContentRow.text("Users should be able to inspect the repository, released source tag, build configuration, mixin list, network call sites, file-operation call sites, and the exact JAR hash before relying on a trust claim."));
        rows.add(ContentRow.section("High-value files to inspect"));
        rows.add(ContentRow.tableHeader("File", "Why it matters"));
        rows.add(ContentRow.tableRow("Main.java / Client.java", "startup, registration, bootstrap, lifecycle, commands and top-level services"));
        rows.add(ContentRow.tableRow("FirstLaunchTermsScreen.java", "this disclosure and acceptance behavior"));
        rows.add(ContentRow.tableRow("FirstLaunchDownloadScreen.java", "bootstrap download execution and destination handling"));
        rows.add(ContentRow.tableRow("GlobalActivityClient.java / KoilGlobalActivityServer.java", "local observation, cache format and Koil network snapshots"));
        rows.add(ContentRow.tableRow("KoilPackageManager.java", "package discovery, validation, add/replace/remove planning and application"));
        rows.add(ContentRow.tableRow("AutomationExecutor.java / AutomationRouter.java", "player-control authority, commands, chat and task execution"));
        rows.add(ContentRow.tableRow("AbstractModrinthContentScreen.java", "remote content queries, metadata and downloads"));
        rows.add(ContentRow.tableRow("SkinOnlineFetcher.java", "Mojang/NameMC username requests and skin downloads"));
        rows.add(ContentRow.tableRow("FileExplorerScreen.java / ModConfigScreen.java", "local file browsing, edits, deletion, preview and typed config controls"));
        rows.add(ContentRow.tableRow("koil.mixins.json / access widener", "vanilla classes and behavior Koil modifies or accesses"));
        rows.add(ContentRow.section("Bootstrap file inventory"));
        for (RemoteFile file : BOOTSTRAP_FILES) {
            rows.add(ContentRow.path(file.name + "  ->  " + file.destination, file.purpose));
            rows.add(ContentRow.code(file.url));
        }
        rows.add(ContentRow.section("Representative code excerpts"));
        rows.add(ContentRow.code("ClientPlayNetworking.send(GlobalActivityApi.REQUEST_CHANNEL, PacketByteBufs.create());"));
        rows.add(ContentRow.code("Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);"));
        rows.add(ContentRow.code("HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();"));
        rows.add(ContentRow.code("collectPendingPackages(new File(\"./mods\"), packages); collectPendingPackages(new File(\".\"), packages);"));
        rows.add(ContentRow.section("Reviewed public source revision"));
        rows.add(ContentRow.code("Koil-public/koil @ 68f22b55965756ae3c0e6d243c94ddc50138b2f9"));
        rows.add(ContentRow.text("This screen must be revised if the released JAR contains behavior that differs from that reviewed source revision."));
        rows.add(ContentRow.section("Public source locations"));
        rows.add(ContentRow.path("github.com/Koil-public/koil", "source repository containing this screen when reviewed"));
        rows.add(ContentRow.path("github.com/Koil-public/koil-online-data", "remote bootstrap and design data repository"));
        rows.add(ContentRow.note("A release should pin this screen to the exact source tag or commit used to build the JAR. A moving main branch is not sufficient provenance."));
        return rows;
    }

    private List<ContentRow> uiPreferenceRows() {
        return List.of(
                ContentRow.section("Choose how supported Minecraft screens should look"),
                ContentRow.text("Koil includes mixin-based redesigns and additional controls for supported vanilla screens. This setting selects the preferred presentation where Koil provides a fallback."),
                ContentRow.tableHeader("Choice", "Result"),
                ContentRow.tableRow("Enable Koil UI", "Use Koil's redesigned layouts, integrated actions, panels, charts, metadata and theme-backed presentation where implemented"),
                ContentRow.tableRow("Keep Vanilla UI", "Prefer Minecraft's default presentation where Koil supports a vanilla-style fallback; Koil remains installed and its non-UI systems are not uninstalled"),
                ContentRow.note("This is a presentation preference, not a permission sandbox. Individual Koil features still retain the capabilities described on the previous pages when their code paths run."),
                ContentRow.section("Acceptance record"),
                ContentRow.text("Selecting either option records first-launch completion, applies the UI preference, and opens the bootstrap download screen."),
                ContentRow.text("Use Back to review the capability disclosure before making the final choice.")
        );
    }

    private int contentLeft() {
        return step == Step.DISCLOSURE ? SIDEBAR_WIDTH + CONTENT_PADDING : 28;
    }

    private int contentRight() {
        return this.width - 22;
    }

    private int contentTop() {
        return HEADER_HEIGHT + 14;
    }

    private int contentBottom() {
        return Math.max(contentTop() + 24, this.height - FOOTER_HEIGHT - 8);
    }

    private int contentWidth() {
        return Math.max(80, contentRight() - contentLeft());
    }

    private int viewportHeight() {
        return Math.max(24, contentBottom() - contentTop());
    }

    private int contentHeight() {
        return totalHeight(layoutRows(contentWidth()));
    }

    private int totalHeight(List<LayoutRow> rows) {
        int total = 0;
        for (LayoutRow row : rows) {
            total += row.height;
        }
        return total;
    }

    private boolean isInsideContent(double mouseX, double mouseY) {
        return mouseX >= contentLeft() && mouseX <= contentRight() && mouseY >= contentTop() && mouseY <= contentBottom();
    }

    private void setScrollOffset(int value) {
        int maxScroll = Math.max(0, contentHeight() - viewportHeight());
        this.scrollOffset = Math.max(0, Math.min(maxScroll, value));
    }

    private void renderScrollbar(DrawContext context, List<LayoutRow> rows) {
        int contentHeight = totalHeight(rows);
        int viewportHeight = viewportHeight();
        if (contentHeight <= viewportHeight) {
            return;
        }
        int trackX = this.width - 13;
        int trackTop = contentTop();
        int trackHeight = viewportHeight;
        context.fill(trackX, trackTop, trackX + 2, trackTop + trackHeight, withAlpha(uiColorBackgroundBorder, 100));
        int thumbHeight = Math.max(18, trackHeight * viewportHeight / contentHeight);
        int thumbY = trackTop + (trackHeight - thumbHeight) * this.scrollOffset / Math.max(1, contentHeight - viewportHeight);
        context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, new Color(uiColorIDEFileNameText, true).getRGB());
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        return contentHeight() > viewportHeight()
                && mouseX >= this.width - 25
                && mouseX <= this.width - 4
                && mouseY >= contentTop()
                && mouseY <= contentBottom();
    }

    private int scrollbarGrabOffset(double mouseY) {
        int thumbHeight = scrollbarThumbHeight();
        int thumbY = scrollbarThumbY(thumbHeight);
        if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
            return (int) mouseY - thumbY;
        }
        return thumbHeight / 2;
    }

    private int scrollOffsetFromMouse(double mouseY, int dragOffset) {
        int contentHeight = contentHeight();
        int viewportHeight = viewportHeight();
        if (contentHeight <= viewportHeight) {
            return 0;
        }
        int thumbHeight = scrollbarThumbHeight();
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        int maxScroll = Math.max(1, contentHeight - viewportHeight);
        int relativeY = Math.max(0, Math.min(thumbTravel, (int) mouseY - contentTop() - dragOffset));
        return Math.max(0, Math.min(maxScroll, Math.round(relativeY * maxScroll / (float) thumbTravel)));
    }

    private int scrollbarThumbHeight() {
        int contentHeight = contentHeight();
        int viewportHeight = viewportHeight();
        return Math.max(18, viewportHeight * viewportHeight / Math.max(1, contentHeight));
    }

    private int scrollbarThumbY(int thumbHeight) {
        int contentHeight = contentHeight();
        int viewportHeight = viewportHeight();
        return contentTop() + (viewportHeight - thumbHeight) * this.scrollOffset / Math.max(1, contentHeight - viewportHeight);
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha))).getRGB();
    }

    private enum Step {
        DISCLOSURE,
        UI_PREFERENCE
    }

    private enum DisclosurePage {
        SYSTEMS("Koil Systems"),
        CAPABILITIES("What People Can Do"),
        DATA("Data Processed"),
        NETWORK("Servers & Network"),
        FILES("Files & Packages"),
        CONTROL("Automation & UI"),
        SOURCE("Source Proof");

        private final String label;

        DisclosurePage(String label) {
            this.label = label;
        }
    }

    private enum RowKind {
        SECTION,
        TEXT,
        BULLET,
        PATH,
        CODE,
        NOTE,
        TABLE_HEADER,
        TABLE_ROW,
        SYSTEM_MAP,
        RISK,
        SERVER,
        CHART,
        FLOW,
        SPACER
    }

    private record ContentRow(RowKind kind, String primary, String secondary) {
        private static ContentRow section(String text) {
            return new ContentRow(RowKind.SECTION, text, "");
        }

        private static ContentRow text(String text) {
            return new ContentRow(RowKind.TEXT, text, "");
        }

        private static ContentRow bullet(String text) {
            return new ContentRow(RowKind.BULLET, text, "");
        }

        private static ContentRow path(String path, String meaning) {
            return new ContentRow(RowKind.PATH, path, meaning);
        }

        private static ContentRow code(String text) {
            return new ContentRow(RowKind.CODE, text, "");
        }

        private static ContentRow note(String text) {
            return new ContentRow(RowKind.NOTE, text, "");
        }

        private static ContentRow tableHeader(String first, String second) {
            return new ContentRow(RowKind.TABLE_HEADER, first, second);
        }

        private static ContentRow tableRow(String first, String second) {
            return new ContentRow(RowKind.TABLE_ROW, first, second);
        }

        private static ContentRow systemMap() {
            return new ContentRow(RowKind.SYSTEM_MAP, "", "");
        }

        private static ContentRow risk() {
            return new ContentRow(RowKind.RISK, "", "");
        }

        private static ContentRow server() {
            return new ContentRow(RowKind.SERVER, "", "");
        }

        private static ContentRow chart() {
            return new ContentRow(RowKind.CHART, "", "");
        }

        private static ContentRow flow() {
            return new ContentRow(RowKind.FLOW, "", "");
        }

        private static ContentRow spacer() {
            return new ContentRow(RowKind.SPACER, "", "");
        }
    }

    private record LayoutRow(RowKind kind, String primary, String secondary, List<OrderedText> primaryLines, List<OrderedText> secondaryLines, int height) {
    }

    private record RemoteFile(String name, String purpose, String destination, String url) {
    }
}