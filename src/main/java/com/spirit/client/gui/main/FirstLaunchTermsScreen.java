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
        String[] systems = {
            "Core Runtime",
            "Interface & Diagnostics",
            "Files, Content & Packages",
            "Intelligence & Models",
            "Automation & Macros",
            "Online, Chat & Server",
            "API & Integration"
        };
        String[] subsystems = {
            "startup, prelaunch, bootstrap, lifecycle, config and design reload",
            "screens, F3, performance, console, rich media and reports",
            "explorer, editor, KPak, mods, packs, worlds, shaders and skins",
            "local models, llama.cpp, Deep Thought, tools, voice and durable task state",
            "KTL, capability registry, planner, executor, approvals, feedback and macros",
            "HTTP services, remote media, Koil channels, snapshots, markets and HUD",
            "mixins, hooks, client/server registration, public APIs and compatibility"
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
            context.drawText(this.textRenderer, "Minecraft state + instance files + local services + optional server channels", x + 122, hubY + 5, muted, false);
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
        String[] labels = {"Local files", "Network", "Vanilla UI", "Game control", "Models/processes", "System UI"};
        String[] values = {"read / write / replace / delete", "HTTPS, remote media and server channels", "mixins and screen replacement", "automation can act for player", "downloads and launches local native runtime", "dialogs, clipboard and external windows"};
        int[] levels = {4, 4, 4, 4, 4, 2};
        int labelWidth = Math.min(104, Math.max(78, width / 4));
        int valueWidth = Math.min(190, Math.max(104, width / 3));
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
            String[] headings = {"Remote or server source", "Koil request", "Local state", "Visible or active result"};
            String[] values = {"GitHub, Hugging Face, Modrinth, Mojang, media hosts, eeverest.dev or current server", "HTTPS, localhost model API or Koil packet channel", "runtime file, model, cache, temp file, checkpoint or memory", "screen, install, model answer, tool action, skin, stats or HUD"};
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
                "Remote/server source  >  HTTPS, localhost API or Koil channel",
                "Koil request          >  parsed memory, cache, model file, checkpoint or temp file",
                "Local state           >  screen, install, answer, tool action, skin, stats or HUD"
            };
            int rowY = y + 22;
            for (String line : lines) {
                context.fill(x, rowY, x + width, rowY + 18, withAlpha(uiColorBackgroundOverlay, 105));
                context.drawBorder(x, rowY, width, 18, border);
                context.drawText(this.textRenderer, line, x + 6, rowY + 5, body, false);
                rowY += 22;
            }
        }
        context.drawText(this.textRenderer, "Different features use different paths. The exact released code remains the final authority.", x, y + height - 13, muted, false);
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
                case SYSTEM_MAP -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), width >= 500 ? 282 : 552));
                case RISK -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), width >= 520 ? 119 : 254));
                case SERVER -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), width >= 470 ? 104 : 118));
                case CHART -> rows.add(new LayoutRow(row.kind, row.primary, row.secondary, List.of(), List.of(), 128));
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
            case MODELS -> modelRows();
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
            ContentRow.text("Koil is a research-oriented middleware and operating layer implemented inside Minecraft. It combines client UI, diagnostics, instance management, rich chat, local model inference, automation, server integration, and public APIs. These systems share Minecraft state, the active instance filesystem, local services, downloaded content, and optional Koil server channels."),
            ContentRow.systemMap(),
            ContentRow.section("Main systems and current subsystems"),
            ContentRow.tableHeader("Main system", "Subsystems demonstrated by current executable source"),
            ContentRow.tableRow("Core Runtime", "prelaunch key generation, first launch, bootstrap downloads, lifecycle, configuration, design reload and command registration"),
            ContentRow.tableRow("Interface & Diagnostics", "screen redesigns, widgets, F3 diagnostics, performance monitoring, console, rich chat formatting, attachments, media and reports"),
            ContentRow.tableRow("Files, Content & Packages", "file explorer/editor, config tools, KPak, older package paths, mods, resource packs, datapacks, shaders, worlds and skins"),
            ContentRow.tableRow("Intelligence & Models", "local model catalog, model/runtime installer, llama.cpp and Colibri providers, conversations, Deep Thought, voice, tool calling and durable task state"),
            ContentRow.tableRow("Automation & Macros", "KTL compiler, capability registry, model planner, resolver, executor, approvals, feedback, improvement data and macro definitions"),
            ContentRow.tableRow("Online, Chat & Server", "bootstrap and update data, content APIs, arbitrary HTTP(S) chat media, skins, Koil packets, snapshots, markets, HUD and rich-chat synchronization"),
            ContentRow.tableRow("API & Integration", "mixins, access widening, client/server registration, Java APIs, other-mod entry points and compatibility adapters"),
            ContentRow.section("Activation and persistence"),
            ContentRow.bullet("Some systems initialize automatically during prelaunch or client startup. Others run only when a screen, command, server channel, model, package, media link, or API caller activates them."),
            ContentRow.bullet("A screen is often only the visible front end. Its work may be performed by filesystem, network, model, automation, rendering, native-process or server subsystems."),
            ContentRow.bullet("Closing a screen does not necessarily remove files, caches, logs, model downloads, Deep Thought checkpoints, activity history, package backups or generated configuration."),
            ContentRow.bullet("Choosing Vanilla UI changes presentation where supported. It does not uninstall, disable, permission-bound or sandbox Koil's other systems."),
            ContentRow.note("Treat Koil as an experimental instance-management, integration, local-intelligence and automation platform, not as a cosmetic-only mod.")
        );
    }

    private List<ContentRow> capabilitiesRows() {
        return List.of(
            ContentRow.section("What people can do with Koil"),
            ContentRow.text("These examples describe executable capability, not a promise that every feature is complete, safe, permitted on every server, or enabled by default. A user, model, KTL task, macro, package, remote source, compatible server, or another mod using Koil APIs can reach different parts of this capability surface."),
            ContentRow.risk(),
            ContentRow.section("From routine use to consequential use"),
            ContentRow.tableHeader("Impact", "Current examples demonstrated by code"),
            ContentRow.tableRow("Inspect and diagnose", "view mods, packs, configs, files, logs, registries, worlds, F3 data, performance state, activity rows, model status and hardware guidance"),
            ContentRow.tableRow("Customize and communicate", "change supported UI and design data, edit typed configs, manage skins, use multiline and formatted chat, render LaTeX, tables, links, images, audio, video, GIFs and files"),
            ContentRow.tableRow("Manage the instance", "download, create, edit, move, rename, install, disable, replace, restore or delete files, models, mods, packs, KPak content and generated state"),
            ContentRow.tableRow("Use local intelligence", "download and run local language models, ask questions, retain bounded conversations, perform Deep Thought investigations, inspect workspaces and produce model-selected tool calls"),
            ContentRow.tableRow("Control game or project state", "move, look, sprint, jump, use, mine, attack, interact, transfer items, send chat or commands, edit bounded workspaces and run registered Gradle validation operations"),
            ContentRow.tableRow("High-impact modes", "Unrestricted Automation skips Koil approval for registered model capabilities; packages and file tools can replace content; native runtimes and remote media are downloaded and processed"),
            ContentRow.section("Credible harm and failure modes"),
            ContentRow.bullet("File, model-tool or package mistakes can overwrite configuration or source, remove content, consume storage, break an instance, expose local text to a model process, or alter files under a writable Koil or development workspace."),
            ContentRow.bullet("Automation can move into danger, use or transfer the wrong item, mine or attack the wrong target, send unintended chat or commands, lose resources, die, affect other players, or trigger moderation and anti-cheat."),
            ContentRow.bullet("Language models can misunderstand prompts, hallucinate facts, select an incorrect tool, repeat work, produce malformed arguments, or reach a bounded-round failure. A larger model or confidence estimate is not proof of correctness."),
            ContentRow.bullet("Remote repositories, package hash services, media URLs, model hosts and compatible servers can be unavailable, compromised, changed, malicious, or inconsistent with the code that originally referenced them."),
            ContentRow.bullet("Downloaded native runtimes, models, archives, packages and media consume disk and processing resources. Media decoding and local inference can also consume substantial memory, CPU or GPU time."),
            ContentRow.section("Code-backed safeguards currently present"),
            ContentRow.tableHeader("Safeguard", "Scope and limitation"),
            ContentRow.tableRow("User stop and cancellation", "/automate off or exit, /model cancel, model-install cancellation and runtime shutdown can stop pending work; completed game, file or network effects are not generally undone"),
            ContentRow.tableRow("Model eligibility and bounded rounds", "catalog eligibility, tool support checks, queue limits, repeated-call limits and provider-round budgets reduce unsupported or looping agent work; experimental compact-agent mode can relax normal model eligibility"),
            ContentRow.tableRow("Contextual model approvals", "mutating workspace and registered project-validation tools request approval in Standard mode; preapproved plans and Unrestricted mode can bypass those prompts"),
            ContentRow.tableRow("Named workspace containment", "model file tools accept only registered instance, automation and development-project roots, reject absolute traversal and selected sensitive names, limit text sizes and use stale-file hashes"),
            ContentRow.tableRow("Recoverable model deletion", "workspace.delete moves individual regular files to koil/sys/model/file-trash and returns a recovery token; this does not cover every deletion path elsewhere in Koil"),
            ContentRow.tableRow("Verified model installation", "runtime and GGUF artifacts have fixed URLs, expected sizes and SHA-256 values; archive paths are contained and model uninstall is restricted to the model root"),
            ContentRow.tableRow("KPak transaction controls", "the current installer uses a lock, normalized game-root containment, ZIP validation, backup, transaction state, remote package hash, Ed25519 signature, version check and installed-file hashes"),
            ContentRow.tableRow("Minecraft and protocol authority", "normal commands and actions remain subject to server permissions and protocol handling; that does not mean the server permits automation or prevents all harmful player-authorized actions"),
            ContentRow.section("Important limits and unfinished areas"),
            ContentRow.note("Koil does not provide one general sandbox or one universal per-action permission system across all local files, networking, mixins, packages, media, model tools, automation and public APIs."),
            ContentRow.bullet("Unrestricted mode explicitly removes Koil approval prompts for registered model capabilities during that Automation session. Minecraft permissions remain unchanged, but Koil-side approval is skipped."),
            ContentRow.bullet("The current KPak trust store is an in-memory author-key registry. Unknown authors fail, but key enrollment and distribution remain separate trust decisions. Private package-signing keys are stored as unencrypted Base64 files under the user's home directory."),
            ContentRow.bullet("The KPak manifest carries an operation field, but the current installer path treats operations as ZIP-backed file writes. Do not rely on add, replace or remove labels as a complete implemented permission model without reviewing the exact build."),
            ContentRow.bullet("The prelaunch entry point generates a new KPak key identity before this first-launch acceptance screen. Declining the disclosure does not reverse files already created by prelaunch code."),
            ContentRow.bullet("Checksums prove downloaded bytes match values trusted by the current code or service. They do not prove that the referenced content, model behavior or author is benign."),
            ContentRow.section("Server support"),
            ContentRow.server(),
            ContentRow.text("Koil is not intended to provide identical behavior on every server. It is built to try to operate through client-side integrations and ordinary Minecraft behavior where possible, but it works best when the connected server also runs Koil and exposes matching channels and APIs.")
        );
    }

    private List<ContentRow> modelRows() {
        return List.of(
            ContentRow.section("Real local AI and model behavior"),
            ContentRow.text("Current Koil includes genuine neural language-model inference in addition to deterministic planners, KTL, heuristics, pathfinding, recommendations and rule-based systems. The selected GGUF model runs through a local provider such as Koil-managed llama.cpp or a configured Colibri-compatible service."),
            ContentRow.tableHeader("Component", "What it actually does"),
            ContentRow.tableRow("/ask", "sends the current prompt and bounded local conversation context to the selected local provider; the normal ask path exposes no Koil tools and cannot directly perform Minecraft or file actions"),
            ContentRow.tableRow("/ask deep", "runs a phased Deep Thought investigation with read-only Minecraft-knowledge and workspace list/read/search tools, then saves resumable checkpoints scoped to a hashed server address or single-player world identity"),
            ContentRow.tableRow("Automation Mode", "allows an eligible tool-calling model to select registered automation, workspace and validation tools; outputs can affect Minecraft, KTL, Koil files and a detected Koil development checkout"),
            ContentRow.tableRow("Deterministic support", "eligibility rules, model capability estimates, prompt routing, KTL compilation, plan validation, objective ledgers, repeated-call detection and bounded execution are code-driven controls, not learned intelligence"),
            ContentRow.tableRow("Voice", "optional model speech enumerates configured local voices and can speak streamed model output; voice settings and implementation availability control use"),
            ContentRow.section("Model installation and local process"),
            ContentRow.path("github.com/ggml-org/llama.cpp/releases", "pinned platform-specific llama.cpp runtime archive b10173 with expected byte size and SHA-256"),
            ContentRow.path("huggingface.co/<repository>/resolve/main/<model>", "catalog-selected GGUF model artifacts with code-defined sizes and SHA-256 values"),
            ContentRow.path("./koil/sys/model/runtime/**", "downloaded and extracted native llama-server runtime plus verification marker"),
            ContentRow.path("./koil/sys/model/models/<catalog-id>/**", "downloaded GGUF model files and temporary .part files while installing"),
            ContentRow.text("The managed llama.cpp provider requires localhost binding and a generated API key, starts a native llama-server process when needed, sends OpenAI-style chat-completion requests to localhost, streams responses and tool calls, and stops owned processes during shutdown."),
            ContentRow.note("Model downloads are optional and user-triggered through /model or the Local Model Setup screen. LocalModelService itself initializes at client startup, creates local configuration when missing, registers providers and may enumerate voices, but a selected model runtime is started when a request or explicit start action needs it."),
            ContentRow.section("Data given to the model"),
            ContentRow.bullet("Prompts, bounded conversation history, system instructions, registered tool schemas, selected tool results, planning summaries and task state can be sent to the selected local provider process."),
            ContentRow.bullet("Read-only Deep Thought can inspect bounded text from named workspaces. Automation can inspect and, after applicable approval or bypass, modify bounded workspace files and run registered Gradle tasks."),
            ContentRow.bullet("Koil's default managed llama.cpp path is localhost-only. A manually configured provider still needs review; do not assume a custom executable, host or service has the same privacy boundary."),
            ContentRow.section("Persistence, limits and controls"),
            ContentRow.tableHeader("Stored item", "Current behavior"),
            ContentRow.tableRow("Configuration and API key", "koil/sys/model/local-model.json; generated key is stored in plaintext JSON and POSIX owner-only permissions are attempted where supported"),
            ContentRow.tableRow("Model selection", "selected provider, model and local runtime/model paths are persisted for later sessions"),
            ContentRow.tableRow("Runtime log", "koil/sys/model/logs/local-model-runtime.log stores event summaries such as startup, request completion and context-size metadata"),
            ContentRow.tableRow("Deep Thought", "up to 64 JSON session files per scope are loaded; each checkpoint may be up to 64 MB and remains until explicitly deleted or otherwise cleaned"),
            ContentRow.tableRow("Conversation memory", "current general and automation conversations are bounded in process memory; the generated config records persistentConversationHistory and persistentAssociativeMemory as false"),
            ContentRow.tableRow("Controls", "/model cancel, stop, restart, reset, uninstall, queue editing, Answer Now, Deep Thought pause/resume and Automation off/exit provide different cancellation or clearing actions"),
            ContentRow.section("Agent approvals and elevated modes"),
            ContentRow.bullet("Standard Automation asks through the model HUD before mutating workspace files or running registered project validation, unless a tool call is already authorized by a reviewed plan."),
            ContentRow.bullet("Planning Mode requires a reviewed exact-step plan before side effects according to the current routing contract. This is a Koil control, not a mathematical proof that the plan is safe or complete."),
            ContentRow.bullet("Unrestricted mode skips Koil approval for registered model capabilities for the current Automation session."),
            ContentRow.bullet("Experimental compact-agent mode permits selected tool-capable models below the normal automation complexity requirement. Smaller models may have weaker planning and tool reliability."),
            ContentRow.note("Model-generated conclusions, confidence scores, catalog reasoning estimates and tool choices can be wrong. Review consequential actions and outputs independently.")
        );
    }

    private List<ContentRow> dataRows() {
        return List.of(
            ContentRow.section("Game and player data processed"),
            ContentRow.tableHeader("Observed or supplied value", "Current demonstrated use"),
            ContentRow.tableRow("Local player name and UUID", "context keys, activity ownership, local display, cache records and server participation"),
            ContentRow.tableRow("Visible player names and UUIDs", "visible-player activity, presence and held-item observations"),
            ContentRow.tableRow("Main-hand and off-hand item IDs", "client-observed activity rows and local history"),
            ContentRow.tableRow("Server address or world identity", "activity context and hashed Deep Thought scope; the raw identity is hashed before use as the Deep Thought directory name"),
            ContentRow.tableRow("Position, view, inventory, containers, targets, blocks and entities", "automation resolution, Minecraft knowledge, movement, interaction, diagnostics and model tool results"),
            ContentRow.tableRow("Chat, commands and automation prompts", "normal chat, rich chat, local model requests, model conversations, KTL compilation, feedback and execution logs"),
            ContentRow.tableRow("Local files and source text", "config/editor tools, diagnostics, package systems, read-only Deep Thought and approved or unrestricted model workspace tools"),
            ContentRow.tableRow("Hardware and storage facts", "local model compatibility, memory guidance, storage checks and model setup diagnostics"),
            ContentRow.spacer(),
            ContentRow.text("Global activity code samples the local player and visible players periodically, records presence and held-item observations, keeps bounded histories, and saves local context data. Model and automation systems separately inspect game state when resolving prompts or tasks."),
            ContentRow.note("The reviewed activity-client path requests structured server snapshots and receives Koil snapshot/HUD packets. It does not by itself prove that every other Koil API, chat feature, future packet or compatible mod avoids transmitting observed data."),
            ContentRow.section("Local persistence and retention"),
            ContentRow.bullet("Logs can contain command text, task state, errors, file paths, selected IDs, provider events, context sizes, output summaries and runtime diagnostics."),
            ContentRow.bullet("Automation feedback, improvement and proof systems can persist task results, selected files or nodes, classifications and failure details."),
            ContentRow.bullet("Deep Thought stores questions, investigation phases, evidence, conclusions and lifecycle state under koil/sys/model/deep-thought until deleted or cleaned."),
            ContentRow.bullet("Remote chat media is downloaded into koil/cache/chat_media, including a persistent remote-image cache and clipboard-image cache. Current code does not show a universal expiration policy for those files."),
            ContentRow.bullet("Skin lookup can send a typed or current username to Mojang and NameMC, download public texture data, normalize PNG files and add them to the local skin library."),
            ContentRow.bullet("Config, file-preview, package, F3, performance, content, rich-chat, model and editor systems parse local files to generate controls, metadata, warnings, charts, previews and reports."),
            ContentRow.section("Privacy boundaries"),
            ContentRow.text("Local processing is not the same as anonymous processing. Names, UUIDs, server identities, world context, prompts and file contents can remain identifying even when stored only on the user's computer."),
            ContentRow.text("The default managed language-model request is sent to a localhost service. Network-backed bootstrap, content, model installation, package hash, skin, rich-media and compatible-server features still contact outside hosts or the connected server when their paths run."),
            ContentRow.note("There is no single global retention, export, deletion or privacy-control panel covering every Koil log, cache, model checkpoint, activity record, package backup and third-party request. Controls are currently subsystem-specific.")
        );
    }

    private List<ContentRow> networkRows() {
        return List.of(
            ContentRow.section("Known network destinations and purposes"),
            ContentRow.tableHeader("Destination", "Purpose demonstrated by current code or bootstrap inventory"),
            ContentRow.tableRow("raw.githubusercontent.com", "Koil bootstrap JSON, design catalogs, help content, funding data, validation data and asset indexes that can change independently of the mod JAR"),
            ContentRow.tableRow("github.com/ggml-org/llama.cpp", "pinned release archives for the optional native llama.cpp runtime"),
            ContentRow.tableRow("huggingface.co", "optional GGUF model downloads from catalog-defined repositories and main-branch artifact paths"),
            ContentRow.tableRow("eeverest.dev", "current KPak installer lookup for an expected package SHA-256 by package file name"),
            ContentRow.tableRow("api.modrinth.com", "project search, metadata, versions, compatibility checks and content discovery"),
            ContentRow.tableRow("CurseForge endpoints", "optional metadata or content path when API access is configured"),
            ContentRow.tableRow("api.mojang.com / sessionserver.mojang.com / textures.minecraft.net", "username lookup, signed profile texture metadata and skin texture downloads"),
            ContentRow.tableRow("namemc.com", "best-effort public skin and profile-history lookup"),
            ContentRow.tableRow("Any HTTP(S) media URL shown to rich chat", "remote images, GIFs, audio, video, generic files and HTML-discovered media previews, subject to type-specific limits"),
            ContentRow.tableRow("127.0.0.1 local model service", "authenticated health, model-list and streaming chat-completion requests to the selected local inference runtime"),
            ContentRow.tableRow("Current Minecraft server", "normal chat, commands and interaction packets plus Koil channels for presence, rich-chat sync, snapshots, market/HUD data and compatible features"),
            ContentRow.spacer(),
            ContentRow.server(),
            ContentRow.flow(),
            ContentRow.section("Request contents and remote behavior"),
            ContentRow.bullet("Content searches can include project names, mod IDs, Minecraft version, loader filters and selected project or version identifiers."),
            ContentRow.bullet("Skin searches include the username entered or current session username when the skin tool is used."),
            ContentRow.bullet("Model and runtime downloads reveal normal HTTPS metadata to GitHub or Hugging Face and request exact catalog artifact paths. Model prompts are not sent to those download hosts by the installer."),
            ContentRow.bullet("KPak hash verification reveals the package file name to eeverest.dev and trusts the returned expected hash as one part of installation verification."),
            ContentRow.bullet("Rich-chat media fetching accepts arbitrary http or https URLs, follows normal redirects, may send browser-like User-Agent, Accept-Language, Referer and Origin headers, inspects up to 2 MB of HTML and can follow discovered media links to a bounded depth."),
            ContentRow.bullet("Current rich-media limits are approximately 25 MB for image/GIF, 64 MB for audio or generic files, 128 MB for video and 2 MB for HTML discovery. These are download limits, not proof that decoders are safe."),
            ContentRow.bullet("Bootstrap and other HTTPS requests reveal normal connection metadata such as IP address and request headers to the hosting service."),
            ContentRow.bullet("Koil server-channel traffic identifies the client as part of that Minecraft connection and carries protocol-defined content. A non-Koil server accepting ordinary packets has not thereby approved Koil automation."),
            ContentRow.section("Server distinction"),
            ContentRow.text("Without Koil on the server, client-side UI, local models, file tools and ordinary Minecraft actions may still work, but Koil-specific synchronized systems and channels are unavailable. With Koil on the server, registered snapshots, presence, rich-chat synchronization, markets, HUD state and server APIs can become available."),
            ContentRow.note("No disclosure can make externally hosted runtime files part of a platform's reviewed mod artifact. If a distribution platform requires necessary JSON, theme or functional files to be bundled, the implementation must change rather than relying on this notice."),
            ContentRow.note("Do not claim zero telemetry, fully offline operation or that nothing leaves the computer unless an automated audit verifies every network call and configurable provider in the exact released JAR.")
        );
    }

    private List<ContentRow> fileRows() {
        return List.of(
            ContentRow.section("Paths Koil can access or create"),
            ContentRow.path("./koil/**", "configuration, design data, logs, packages, automation, models, caches, reports, wiki, skins, chat media and other Koil-owned state"),
            ContentRow.path("./koil/sys/model/local-model.json", "local model provider configuration and generated API key"),
            ContentRow.path("./koil/sys/model/runtime/**", "downloaded native llama.cpp archives, extracted executable and verification marker"),
            ContentRow.path("./koil/sys/model/models/**", "downloaded GGUF model files, temporary parts and model selection paths"),
            ContentRow.path("./koil/sys/model/deep-thought/**", "persistent Deep Thought checkpoints separated by hashed world or server scope"),
            ContentRow.path("./koil/sys/model/file-trash/**", "recoverable files removed by the model workspace delete tool"),
            ContentRow.path("./koil/cache/chat_media/**", "downloaded remote chat media and clipboard URL images"),
            ContentRow.path("~/.koil/kpak/keys/**", "KPak Ed25519 identity, public key and unencrypted private-key files generated by prelaunch or package-building code"),
            ContentRow.path("./.koil/backups/** and KPak transaction/registry state", "package backup, rollback metadata and installed-package records"),
            ContentRow.path("./config/**", "config discovery and editing for Koil and supported mods"),
            ContentRow.path("./mods/**", "mod discovery, import, install, disable, delete, package detection and local JAR inspection"),
            ContentRow.path("./resourcepacks/** / ./shaderpacks/**", "pack discovery, installation, selection, metadata and removal"),
            ContentRow.path("./saves/<world>/datapacks/**", "world-specific datapack discovery, active-world content integration and management"),
            ContentRow.path("./logs/latest.log", "Minecraft log reading in Koil's console"),
            ContentRow.path("Detected Koil source checkout", "development-only model workspace access and registered Gradle compile, proof and validation tasks"),
            ContentRow.path("System temporary directory", "skin, media, package-building and other processing intermediates"),
            ContentRow.section("Operations available through Koil"),
            ContentRow.tableHeader("Operation", "Current effect"),
            ContentRow.tableRow("Read and inspect", "open files, parse configs, inspect JAR metadata, preview media, scan folders, read logs and expose bounded workspace text to local model tools"),
            ContentRow.tableRow("Create and write", "save edits, generate configs, keys, caches, reports, checkpoints, packages, skins, automation state, model selections and downloaded content"),
            ContentRow.tableRow("Replace and overwrite", "config saves, bootstrap downloads, KPak writes, model workspace writes and selected management operations can replace existing files"),
            ContentRow.tableRow("Move, rename and restore", "file tools, atomic-save fallbacks, model trash recovery and package rollback can change paths or restore backups"),
            ContentRow.tableRow("Delete and uninstall", "user-invoked file, mod, pack, model, package and cleanup actions can remove files; containment differs by subsystem"),
            ContentRow.tableRow("Execute", "the model subsystem can launch a downloaded native llama-server; development validation can launch the repository's registered Gradle wrapper tasks"),
            ContentRow.section("Current KPak authority and verification"),
            ContentRow.text("KPak installation validates a fixed serial, requires operations, acquires an install lock, records transaction state, obtains an expected package hash from eeverest.dev, verifies an Ed25519 signature with a registered author key, checks Koil version compatibility, validates the ZIP, backs up targets, writes normalized game-directory paths, verifies installed hashes and restores the backup on failure."),
            ContentRow.note("These controls do not establish that a package is desirable or safe. The remote expected-hash service and author-key enrollment are trust dependencies. The current installer can write broadly inside the normalized Minecraft game directory, including sensitive instance content, when a manifest names those paths."),
            ContentRow.note("The operation string exists in PackageOperation, but the reviewed installer currently resolves each operation as a ZIP entry and writes it. The disclosure therefore does not promise that declared remove semantics are currently implemented correctly."),
            ContentRow.section("Model workspace boundaries"),
            ContentRow.bullet("Model file tools use named roots for Koil instance files, automation files and, only in a detected development run directory, the Koil project checkout."),
            ContentRow.bullet("They reject absolute paths, normalized traversal, existing symlink escapes and selected sensitive names such as .git, .gradle, .env, servers.dat, key, credential, password and secret paths."),
            ContentRow.bullet("Mutating model tools are limited to text files up to 256 KB, use expected hashes for existing files, use atomic replacement when possible and require Standard-mode approval unless preapproved or Unrestricted mode is active."),
            ContentRow.note("Those model-workspace restrictions do not automatically apply to every older file explorer, downloader, package manager, content installer or public Koil API.")
        );
    }

    private List<ContentRow> controlRows() {
        return List.of(
            ContentRow.section("Automation and agent authority"),
            ContentRow.text("Koil's KTL runtime and local-model agent layer can act through the Minecraft client and selected local workspaces. The deterministic executor holds and releases inputs, changes view direction, resolves entities and blocks, interacts through Minecraft's client interaction manager, and can execute compiled or model-planned tasks."),
            ContentRow.tableHeader("Capability", "Examples demonstrated by current executor or model tools"),
            ContentRow.tableRow("Movement and camera", "forward, back, strafe, sprint, jump, target movement, mouse look, navigation and recovery"),
            ContentRow.tableRow("World interaction", "use item, use block, interact with entity, mine block and attack entity"),
            ContentRow.tableRow("Inventory and containers", "select slots, inspect counts and transfer items through screen-handler slots"),
            ContentRow.tableRow("Communication", "send chat messages, rich-chat content and raw Minecraft commands through normal client paths"),
            ContentRow.tableRow("Observation and task memory", "read position, inventory, stats, targets, blocks, entities, workspace text, plan state, objectives and prior tool results"),
            ContentRow.tableRow("File and development work", "list, read, search, create, write, replace, delete or restore bounded workspace files and run registered Gradle operations"),
            ContentRow.tableRow("Task control", "plan, branch, delegate, return, wait, retry, pause Deep Thought, request Answer Now, edit queued prompts and cancel active work"),
            ContentRow.section("Modes and approval behavior"),
            ContentRow.tableHeader("Mode", "Current meaning"),
            ContentRow.tableRow("Standard Automation", "requires an eligible selected model and uses Koil approval surfaces for registered mutating model tools unless an approved plan preauthorizes them"),
            ContentRow.tableRow("Planning Mode", "requires a reviewed exact-step plan before side effects according to the model routing contract"),
            ContentRow.tableRow("Deep Thinking", "uses additional bounded model rounds and may create persistent Deep Thought checkpoints for complex work"),
            ContentRow.tableRow("Experimental compact agent", "allows tool-capable smaller models below the normal complexity threshold and may reduce planning or tool reliability"),
            ContentRow.tableRow("Unrestricted", "skips Koil approval for registered model capabilities during the session; it does not expand the registry or bypass Minecraft permissions"),
            ContentRow.section("Execution defenses and limits"),
            ContentRow.bullet("Automation cancellation clears execution state, releases held inputs and stops current work where the active subsystem exposes cancellation."),
            ContentRow.bullet("Model requests use queue limits, timeouts, provider-round limits, repeated-call and repeated-response detection, required-tool tracking, durable state summaries and explicit finalization paths."),
            ContentRow.bullet("Model workspace mutations use contextual approvals, bounded roots, file-size limits, expected hashes and recoverable deletion. Registered project validation rejects arbitrary command strings and runs only listed Gradle operations."),
            ContentRow.bullet("Raw commands still use Minecraft's normal network handler, so Koil does not grant command permission the player lacks."),
            ContentRow.note("Approval and planning reduce accidental actions but do not make model reasoning correct, preview every primitive, reverse completed game actions, restore lost items, undo chat or commands, or guarantee compliance with server rules."),
            ContentRow.section("Multiplayer and survival risk"),
            ContentRow.bullet("A server accepting ordinary player packets does not signal permission for automation. Koil does not prove that a server's rules allow movement assistance, combat, mining, inventory automation, macros or model-directed behavior."),
            ContentRow.bullet("Automation can resemble botting, aim assistance, automated PvP, inventory assistance or griefing. Anti-cheat may detect it, and server staff may warn, kick or ban the account."),
            ContentRow.bullet("Actions can cause death, resource loss, unintended block or container changes, attacks on the wrong entity, unwanted messages, command effects or harm to other players."),
            ContentRow.bullet("Koil-enabled servers can provide additional synchronized state and APIs, but running Koil on the server does not automatically authorize every Automation action unless a specific server-side policy enforces that permission."),
            ContentRow.section("Rich chat, input and UI integration"),
            ContentRow.bullet("Koil injects into vanilla chat to support multiline input, formatting, masked links, tables, LaTeX, local model panels, private-message handling, attachments and synchronized rich-chat features."),
            ContentRow.bullet("Remote media URLs can cause background HTTP requests and persistent cache writes when rendered. Opening or interacting with links and attachments can expose network metadata to third-party hosts."),
            ContentRow.bullet("Mixin configuration allows Koil to inject into or replace supported vanilla screens, widgets, input handling, stats hooks, rendering paths and player-skin behavior."),
            ContentRow.bullet("File, content, media, console, skin, model, performance and package screens may use clipboard, drag-and-drop, system dialogs, media decoders or external windows where implemented."),
            ContentRow.section("Representative code proof"),
            ContentRow.code("approvalPolicy = enabled && automationMode ? ApprovalPolicy.UNRESTRICTED : ApprovalPolicy.STANDARD;"),
            ContentRow.code("case \"workspace.write\" -> write(call); case \"workspace.delete\" -> delete(call);"),
            ContentRow.code("new ProcessBuilder(command(this.selectedPort)).start();"),
            ContentRow.code("case \"cap.command.execute_raw\" -> AutomationRouter.sendRawCommand(rawCommand);")
        );
    }

    private List<ContentRow> sourceRows() {
        ArrayList<ContentRow> rows = new ArrayList<>();
        rows.add(ContentRow.section("Source-first trust model"));
        rows.add(ContentRow.note("The executable code, bundled resources, runtime configuration and downloaded content used by the exact released build are the truth. Documentation, marketing, comments, changelogs, model-generated text and this screen can be incomplete or stale."));
        rows.add(ContentRow.text("Users and reviewers should inspect the exact source revision, build configuration, Fabric metadata, prelaunch and startup entry points, mixin list, network call sites, native-process launches, model tools, file operations, package trust paths and the exact JAR hash before relying on a trust claim."));
        rows.add(ContentRow.section("High-value files to inspect"));
        rows.add(ContentRow.tableHeader("File", "Why it matters"));
        rows.add(ContentRow.tableRow("Main.java / Client.java / Prelaunch.java", "startup, first-launch gating, automatic initialization, prelaunch key generation, commands and top-level services"));
        rows.add(ContentRow.tableRow("FirstLaunchTermsScreen.java / FirstLaunchDownloadScreen.java", "this disclosure, acceptance behavior, bootstrap execution, exact URLs and destinations"));
        rows.add(ContentRow.tableRow("LocalModelService.java / LocalModelCommandBridge.java", "ask, Deep Thought, Automation agent routing, conversations, tools, cancellation and model controls"));
        rows.add(ContentRow.tableRow("LocalModelCatalog.java / LocalModelInstallationService.java", "Hugging Face artifacts, runtime/model hashes, disk use, extraction, selection and uninstall"));
        rows.add(ContentRow.tableRow("LlamaCppLocalModelProvider.java / ColibriLocalModelProvider.java", "native process startup, localhost API, prompts, streaming responses, tool calls and provider boundaries"));
        rows.add(ContentRow.tableRow("ModelWorkspaceToolRegistry.java / ProjectValidationModelToolRegistry.java", "model file reads and writes, approvals, trash recovery and registered process execution"));
        rows.add(ContentRow.tableRow("DeepThoughtSessionStore.java / LocalModelRuntimeLog.java", "persistent investigations, scope identity, retention and runtime logging"));
        rows.add(ContentRow.tableRow("AutomationModeController.java / AutomationRouter.java / AutomationExecutor.java", "Standard, Planning, Deep, Experimental and Unrestricted modes plus game-control authority"));
        rows.add(ContentRow.tableRow("KPakInstaller.java / KPakPrivateKeyStore.java / KPakTrustStore.java", "package hash, signatures, key storage, backups, containment, transaction and trust limitations"));
        rows.add(ContentRow.tableRow("RichChatRemoteImageCache.java / RichChatRemoteMediaResolver.java", "arbitrary HTTP(S) media requests, redirects, headers, limits, HTML discovery and persistent cache"));
        rows.add(ContentRow.tableRow("GlobalActivityClient.java / KoilGlobalActivityServer.java", "visible-player observation, local history and Koil network snapshots"));
        rows.add(ContentRow.tableRow("AbstractModrinthContentScreen.java / SkinOnlineFetcher.java", "third-party content queries, metadata, downloads, usernames and skins"));
        rows.add(ContentRow.tableRow("FileExplorerScreen.java / ModConfigScreen.java / koil.mixins.json", "local file authority and vanilla behavior modified by Koil"));
        rows.add(ContentRow.section("Bootstrap file inventory"));
        for (RemoteFile file : BOOTSTRAP_FILES) {
            rows.add(ContentRow.path(file.name + "  ->  " + file.destination, file.purpose));
            rows.add(ContentRow.code(file.url));
        }
        rows.add(ContentRow.section("Additional runtime download sources"));
        rows.add(ContentRow.path("github.com/ggml-org/llama.cpp/releases/download/b10173", "verified optional native llama.cpp runtime for the current platform"));
        rows.add(ContentRow.path("huggingface.co/<catalog repository>/resolve/main", "verified optional GGUF model files"));
        rows.add(ContentRow.path("eeverest.dev/koil/hash/<package-name>", "expected KPak package hash lookup"));
        rows.add(ContentRow.path("arbitrary http(s) chat-media URL", "bounded remote attachment and preview fetching"));
        rows.add(ContentRow.section("Representative current code excerpts"));
        rows.add(ContentRow.code("KPakPrivateKeyStore.generate();  // registered preLaunch entry point"));
        rows.add(ContentRow.code("URI.create(\"https://huggingface.co/\" + repository + \"/resolve/main/\" + fileName)"));
        rows.add(ContentRow.code("ProcessBuilder builder = new ProcessBuilder(command(this.selectedPort));"));
        rows.add(ContentRow.code("approvalPolicy = enabled && automationMode ? ApprovalPolicy.UNRESTRICTED : ApprovalPolicy.STANDARD;"));
        rows.add(ContentRow.code("Files.move(resolved.path(), trashed, StandardCopyOption.ATOMIC_MOVE);"));
        rows.add(ContentRow.code("URI uri = URI.create(clean); return http || https ? clean : \"\";"));
        rows.add(ContentRow.section("Reviewed public source revision"));
        rows.add(ContentRow.code("Koil-public/koil main @ a12510f88a2d56f11eae751629d82a730ffdd91d"));
        rows.add(ContentRow.text("Revision message: (pre) unfinished 13, adding /model and a new Automation intelligence layer. This disclosure also reconciles the preceding July 31 KPak key, signing and installer commits visible in main."));
        rows.add(ContentRow.note("This is an unfinished development revision. The screen must be updated again when executable behavior, provider configuration, server channels, remote sources, package trust, model tools or platform packaging changes."));
        rows.add(ContentRow.section("Public source locations"));
        rows.add(ContentRow.path("github.com/Koil-public/koil", "current public source repository"));
        rows.add(ContentRow.path("github.com/Koil-public/koil-online-data", "mutable remote bootstrap and design-data repository"));
        rows.add(ContentRow.path("github.com/ggml-org/llama.cpp", "upstream native local-model runtime source and releases"));
        rows.add(ContentRow.path("huggingface.co", "third-party model artifact hosting selected by the built-in catalog"));
        rows.add(ContentRow.note("A release should pin this screen and public description to the exact source tag or commit used to build the JAR. A moving main branch, public repository or checksum alone is not complete provenance or a safety guarantee."));
        return rows;
    }

    private List<ContentRow> uiPreferenceRows() {
        return List.of(
            ContentRow.section("Choose how supported Minecraft screens should look"),
            ContentRow.text("Koil includes mixin-based redesigns, rich-chat additions and integrated controls for supported vanilla screens. This setting selects the preferred presentation where Koil implements a fallback."),
            ContentRow.tableHeader("Choice", "Result"),
            ContentRow.tableRow("Enable Koil UI", "use Koil's redesigned layouts, integrated actions, panels, charts, metadata and theme-backed presentation where implemented"),
            ContentRow.tableRow("Keep Vanilla UI", "prefer Minecraft's default presentation where Koil supports a vanilla-style fallback; Koil remains installed and non-UI systems remain available"),
            ContentRow.note("This is a presentation preference, not a permission sandbox. Models, downloads, packages, networking, automation, APIs, caches and file systems keep the capabilities described on the previous pages when their activation paths run."),
            ContentRow.section("What acceptance does"),
            ContentRow.text("Selecting either option records first-launch completion, applies the UI preference and opens the bootstrap download screen. That screen calls Main.refreshBootstrapFiles, displays requested URLs and destinations, reloads design data and allows continuation after completion or reported errors."),
            ContentRow.text("Acceptance does not authorize undisclosed future capabilities. Materially changed model, network, package, data-processing or automation behavior should be disclosed again in a future build."),
            ContentRow.section("What declining does"),
            ContentRow.text("Exit Game closes the client without intentionally deleting Koil or instance files. Files already created by Fabric loading or Koil's registered prelaunch entry point, including KPak key material, are not rolled back by declining."),
            ContentRow.text("Use Back to review every capability page before making the final choice.")
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
        MODELS("Models & Agent"),
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
