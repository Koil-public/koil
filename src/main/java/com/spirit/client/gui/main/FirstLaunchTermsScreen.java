package com.spirit.client.gui.main;

import com.spirit.Main;
import com.spirit.koil.api.design.DesignLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class FirstLaunchTermsScreen extends Screen {
    private static final int WARNING_LEFT = 38;
    private static final int WARNING_TITLE_Y = 70;
    private static final int WARNING_BODY_Y = 90;
    private static final int WARNING_LINE_HEIGHT = 12;
    private static final int WARNING_FOOTER_HEIGHT = 102;
    private static final int WARNING_BUTTON_HEIGHT = 20;
    private static final List<String> DOWNLOAD_URLS = List.of(
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/config.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/sys.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/koil.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/key.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/catcher.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/design.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/data.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/content/sys/design/files/music.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/content/sys/design/files/background.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/wiki/help_book.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/store/membership.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/auth/validDigits.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/auth/validSerial.json",
            "https://raw.githubusercontent.com/SpiritXIV/koil-online-data/main/auth/verifiedAuthors.json"
    );

    private Step step = Step.TERMS;
    private boolean downloadsExpanded;
    private int scrollOffset;
    private boolean draggingBodyScrollbar;
    private int bodyScrollbarDragOffset;

    public FirstLaunchTermsScreen() {
        super(Text.literal("Koil First Launch Terms"));
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearChildren();
        int x = WARNING_LEFT;
        int buttonY = Math.max(this.height - 86, WARNING_BODY_Y + Math.min(bodyContentHeight(), bodyViewportHeight()) + 18);
        buttonY = Math.min(buttonY, this.height - 30);
        int rightButtonWidth = 80;
        int rightButtonGap = 8;
        int acceptX = Math.max(x, this.width - x - rightButtonWidth);
        int denyX = Math.max(x, acceptX - 78 - rightButtonGap);
        if (step == Step.TERMS) {
            addDrawableChild(ButtonWidget.builder(Text.literal(downloadsExpanded ? "Hide Downloads" : "Show Downloads"), button -> downloadsExpanded = !downloadsExpanded)
                    .dimensions(x, buttonY, Math.min(124, Math.max(84, this.width - 76)), WARNING_BUTTON_HEIGHT).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Deny"), button -> denyTerms())
                    .dimensions(denyX, buttonY, 78, WARNING_BUTTON_HEIGHT).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Accept"), button -> {
                step = Step.DEFAULT_UI;
                scrollOffset = 0;
                rebuildButtons();
            }).dimensions(acceptX, buttonY, rightButtonWidth, WARNING_BUTTON_HEIGHT).build());
        } else if (step == Step.DEFAULT_UI) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Yes"), button -> {
                step = Step.CHANGE_UI;
                rebuildButtons();
            }).dimensions(denyX, buttonY, 78, WARNING_BUTTON_HEIGHT).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("No"), button -> acceptAndExit(true))
                    .dimensions(acceptX, buttonY, rightButtonWidth, WARNING_BUTTON_HEIGHT).build());
        } else {
            addDrawableChild(ButtonWidget.builder(Text.literal("Yes"), button -> acceptAndExit(true))
                    .dimensions(denyX, buttonY, 78, WARNING_BUTTON_HEIGHT).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("No"), button -> acceptAndExit(false))
                    .dimensions(acceptX, buttonY, rightButtonWidth, WARNING_BUTTON_HEIGHT).build());
        }
    }

    private void acceptAndExit(boolean uiRedesign) {
        Main.SUBLOGGER.logI("First-Launch thread", "First launch terms accepted. uiRedesign=" + uiRedesign);
        Main.completeFirstLaunch(uiRedesign);
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new FirstLaunchDownloadScreen());
    }

    private void denyTerms() {
        Main.SUBLOGGER.logW("First-Launch thread", "First launch terms denied. Attempting Koil jar removal before crash.");
        try {
            FabricLoader.getInstance().getModContainer("koil")
                    .ifPresent(container -> container.getRootPaths().forEach(this::deleteModPath));
        } catch (Exception exception) {
            Main.SUBLOGGER.logE("First-Launch thread", "Failed while attempting Koil jar removal: " + exception.getMessage());
        }
        throw new RuntimeException("Koil first launch terms were denied. Koil attempted to remove its mod file as requested.");
    }

    private void deleteModPath(Path path) {
        try {
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                if (Files.deleteIfExists(path)) {
                    Main.SUBLOGGER.logI("First-Launch thread", "Deleted Koil jar after terms denial: " + path);
                }
            }
        } catch (IOException exception) {
            Main.SUBLOGGER.logE("First-Launch thread", "Failed to delete Koil jar after terms denial: " + path + " - " + exception.getMessage());
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int maxScroll = Math.max(0, bodyContentHeight() - bodyViewportHeight());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (amount * 18)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverBodyScrollbar(mouseX, mouseY)) {
            this.draggingBodyScrollbar = true;
            this.bodyScrollbarDragOffset = scrollbarGrabOffset(mouseY);
            this.scrollOffset = scrollOffsetFromMouse(mouseY, this.bodyScrollbarDragOffset);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.draggingBodyScrollbar) {
            this.scrollOffset = scrollOffsetFromMouse(mouseY, this.bodyScrollbarDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingBodyScrollbar) {
            this.draggingBodyScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);
        renderBackupPromptShell(context);

        List<BodyRow> rows = wrappedBodyRows(bodyWidth());
        int contentTop = WARNING_BODY_Y;
        int contentBottom = Math.max(contentTop + 20, this.height - WARNING_FOOTER_HEIGHT - 10);
        context.enableScissor(WARNING_LEFT, contentTop, this.width - WARNING_LEFT, contentBottom);
        int y = contentTop - scrollOffset;
        for (BodyRow row : rows) {
            if (y > contentTop - WARNING_LINE_HEIGHT && y < contentBottom) {
                context.drawText(this.textRenderer, row.text(), WARNING_LEFT, y, row.color(), false);
            }
            y += row.height();
        }
        context.disableScissor();

        renderScrollIndicator(context, rows);
        super.render(context, mouseX, mouseY, delta);
    }

    private List<RawLine> termsLines() {
        if (step == Step.DEFAULT_UI) {
            return List.of(
                    RawLine.title("Do you like Minecraft's default UI?"),
                    RawLine.blank(),
                    RawLine.body("Choose Yes if you prefer the normal Minecraft screen layout."),
                    RawLine.body("Choose No if you want Koil's redesigned Minecraft UI enabled.")
            );
        }
        if (step == Step.CHANGE_UI) {
            return List.of(
                    RawLine.title("Do you still want to see a change?"),
                    RawLine.blank(),
                    RawLine.body("Choosing Yes enables Koil's redesigned Minecraft UI screens."),
                    RawLine.body("Choosing No keeps the default Minecraft UI style where Koil supports that fallback.")
            );
        }
        ArrayList<RawLine> lines = new ArrayList<>();
        lines.add(RawLine.title("External Files"));
        lines.add(RawLine.body("Koil downloads runtime configuration, design files, wiki data, Koro state files, and store/funding metadata from the SpiritXIV koil-online-data GitHub repository."));
        lines.add(RawLine.body("Koil writes those files under this Minecraft instance, mainly in ./koil, ./config, and design subfolders."));
        lines.add(RawLine.blank());
        lines.add(RawLine.title("Generated Formatting"));
        lines.add(RawLine.body("Some Koil screens inspect your local files and generate formatting, previews, warnings, icons, grouped controls, and metadata panels from file contents."));
        lines.add(RawLine.body("This is deterministic program logic running locally in the mod. It is not a ChatGPT-style model and it does not send your file contents to ChatGPT for those screen layouts."));
        lines.add(RawLine.blank());
        lines.add(RawLine.title("Koil Packages"));
        lines.add(RawLine.body("A valid koil-package is allowed to copy package contents into the instance root and can overwrite or reformat files in the instance folder."));
        lines.add(RawLine.body("Only install a koil-package from a source you trust, because accepted packages can change your local Minecraft instance files."));
        lines.add(RawLine.blank());
        if (downloadsExpanded) {
            lines.add(RawLine.title("Download URLs"));
            for (String url : DOWNLOAD_URLS) {
                lines.add(RawLine.body(url));
            }
            lines.add(RawLine.body("The asset updater may also download files listed by the downloaded catcher/key metadata."));
        }
        return lines;
    }

    private void renderBackupPromptShell(DrawContext context) {
        context.getMatrices().push();
        context.getMatrices().scale(2F, 2F, 1.0F);
        context.drawText(this.textRenderer, Text.literal("[!] Warning."), 20, 8, new Color(uiColorWarningPromptText, true).getRGB(), true);
        context.getMatrices().pop();

        context.fill(0, 6, this.width, this.height - WARNING_FOOTER_HEIGHT, new Color(uiColorContentBase, true).getRGB());
        context.fill(0, 8, this.width, 10, new Color(uiColorContentStripeLeft, true).getRGB());
        context.fill(0, this.height - WARNING_FOOTER_HEIGHT - 2, this.width, this.height - WARNING_FOOTER_HEIGHT - 4, new Color(uiColorContentStripeRight, true).getRGB());

        String title = step == Step.TERMS ? "Koil First Launch Terms" : "Koil UI Preference";
        context.drawText(this.textRenderer, title, WARNING_LEFT, WARNING_TITLE_Y, new Color(uiColorBasicTitleText, true).getRGB(), true);
    }

    private void renderScrollIndicator(DrawContext context, List<BodyRow> rows) {
        int contentHeight = rows.stream().mapToInt(BodyRow::height).sum();
        int viewportHeight = bodyViewportHeight();
        if (contentHeight <= viewportHeight) {
            return;
        }
        int trackX = this.width - 16;
        int trackTop = WARNING_BODY_Y;
        int trackHeight = viewportHeight;
        context.fill(trackX, trackTop, trackX + 2, trackTop + trackHeight, withAlpha(uiColorBackgroundBorder, 90));
        int thumbHeight = Math.max(18, trackHeight * viewportHeight / contentHeight);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scrollOffset / Math.max(1, contentHeight - viewportHeight);
        context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, uiColorIDEFileNameText);
    }

    private boolean isOverBodyScrollbar(double mouseX, double mouseY) {
        return bodyContentHeight() > bodyViewportHeight()
                && mouseX >= this.width - 28
                && mouseX <= this.width - 6
                && mouseY >= WARNING_BODY_Y
                && mouseY <= WARNING_BODY_Y + bodyViewportHeight();
    }

    private int scrollbarGrabOffset(double mouseY) {
        int thumbHeight = bodyScrollbarThumbHeight();
        int thumbY = bodyScrollbarThumbY(thumbHeight);
        if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
            return (int) mouseY - thumbY;
        }
        return thumbHeight / 2;
    }

    private int scrollOffsetFromMouse(double mouseY, int dragOffset) {
        int contentHeight = bodyContentHeight();
        int viewportHeight = bodyViewportHeight();
        if (contentHeight <= viewportHeight) {
            return 0;
        }
        int thumbHeight = bodyScrollbarThumbHeight();
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        int maxScroll = Math.max(1, contentHeight - viewportHeight);
        int relativeY = Math.max(0, Math.min(thumbTravel, (int) mouseY - WARNING_BODY_Y - dragOffset));
        return Math.max(0, Math.min(maxScroll, Math.round(relativeY * maxScroll / (float) thumbTravel)));
    }

    private int bodyScrollbarThumbHeight() {
        int contentHeight = bodyContentHeight();
        int viewportHeight = bodyViewportHeight();
        return Math.max(18, viewportHeight * viewportHeight / Math.max(1, contentHeight));
    }

    private int bodyScrollbarThumbY(int thumbHeight) {
        int contentHeight = bodyContentHeight();
        int viewportHeight = bodyViewportHeight();
        return WARNING_BODY_Y + (viewportHeight - thumbHeight) * scrollOffset / Math.max(1, contentHeight - viewportHeight);
    }

    private List<BodyRow> wrappedBodyRows(int wrapWidth) {
        ArrayList<BodyRow> rows = new ArrayList<>();
        for (RawLine line : termsLines()) {
            if (line.text().isBlank()) {
                rows.add(new BodyRow(OrderedText.EMPTY, 6, new Color(uiColorHeaderSubTitleText, true).getRGB()));
                continue;
            }
            int color = line.title()
                    ? new Color(uiColorContentBaseTitleText, true).getRGB()
                    : new Color(uiColorHeaderSubTitleText, true).getRGB();
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(line.text()), wrapWidth);
            for (OrderedText text : wrapped) {
                rows.add(new BodyRow(text, WARNING_LINE_HEIGHT, color));
            }
            if (line.title()) {
                rows.add(new BodyRow(OrderedText.EMPTY, 3, color));
            }
        }
        return rows;
    }

    private int bodyWidth() {
        return Math.max(100, this.width - (WARNING_LEFT * 2) - 16);
    }

    private int bodyViewportHeight() {
        return Math.max(24, this.height - WARNING_FOOTER_HEIGHT - WARNING_BODY_Y - 10);
    }

    private int bodyContentHeight() {
        return wrappedBodyRows(bodyWidth()).stream().mapToInt(BodyRow::height).sum();
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha))).getRGB();
    }

    private enum Step {
        TERMS,
        DEFAULT_UI,
        CHANGE_UI
    }

    private record RawLine(String text, boolean title) {
        private static RawLine title(String text) {
            return new RawLine(text, true);
        }

        private static RawLine body(String text) {
            return new RawLine(text, false);
        }

        private static RawLine blank() {
            return new RawLine("", false);
        }
    }

    private record BodyRow(OrderedText text, int height, int color) {
    }
}
