package com.spirit.client.gui.console;

import com.spirit.client.gui.UiSoundHelper;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.chat.RichChatCommandOutputBridge;
import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.console.ConsoleDisplayService;
import com.spirit.koil.api.console.ConsoleFormatter;
import com.spirit.koil.api.console.ConsoleRecord;
import com.spirit.koil.api.console.ConsoleRepository;
import com.spirit.koil.api.console.ConsoleStyledLine;
import com.spirit.koil.api.console.ConsoleStyledSpan;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBase;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeader;

/** Log-only console. Executor/KTL inspection lives in Automation Workspace. */
@Environment(EnvType.CLIENT)
public final class ConsoleScreen extends Screen implements ConsoleRepository.Listener {
    private static final int HEADER_BOTTOM = 55;
    private static final int FOOTER_HEIGHT = 35;
    private final Screen parent;
    private final List<ConsoleStyledLine> cachedLines = new ArrayList<>();
    private ConsoleChannel activeChannel;
    private TextFieldWidget searchField;
    private TextFieldWidget inputField;
    private double scroll;
    private long lastFileStamp = Long.MIN_VALUE;

    public ConsoleScreen(Screen parent) {
        this(parent, ConsoleChannel.KOIL, false);
    }

    /** Compatibility constructor; the retired Automation/CLI flag is ignored. */
    public ConsoleScreen(Screen parent, ConsoleChannel initialChannel, boolean ignoredAutomationMode) {
        super(Text.literal("Koil Logs"));
        this.parent = parent;
        this.activeChannel = initialChannel == null || initialChannel == ConsoleChannel.CLI
                ? ConsoleChannel.KOIL : initialChannel;
    }

    @Override
    protected void init() {
        this.searchField = new TextFieldWidget(this.textRenderer, 42, 35, Math.max(80, this.width - 300), 16, Text.literal("Search logs"));
        this.searchField.setPlaceholder(Text.literal("Search visible log history"));
        this.searchField.setMaxLength(256);
        this.searchField.setChangedListener(value -> this.scroll = 0.0D);
        this.addDrawableChild(this.searchField);

        int bottomY = this.height - 27;
        this.inputField = new TextFieldWidget(this.textRenderer, 42, bottomY, Math.max(80, this.width - 316), 20, Text.literal("Console input"));
        this.inputField.setPlaceholder(Text.literal("Send chat or /command"));
        this.inputField.setMaxLength(512);
        this.addDrawableChild(this.inputField);
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Send"), button -> submitInput())
                .dimensions(this.width - 268, bottomY, 58, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Workspace"), button -> AutomationRouter.openWorkspace(""))
                .dimensions(this.width - 206, bottomY, 82, 20).build());
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(this.width - 120, bottomY, 78, 20).build());

        int x = this.width - 252;
        addChannelButton("Koil", ConsoleChannel.KOIL, x); x += 62;
        addChannelButton("Package", ConsoleChannel.PACKAGE, x); x += 82;
        addChannelButton("Minecraft", ConsoleChannel.MINECRAFT, x);
        reloadSnapshot();
        ConsoleRepository.getInstance().subscribe(this.activeChannel, this);
    }

    private void addChannelButton(String label, ConsoleChannel channel, int x) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> switchChannel(channel))
                .dimensions(x, 7, label.length() * 6 + 20, 20).build());
    }

    private void switchChannel(ConsoleChannel channel) {
        if (channel == null || channel == ConsoleChannel.CLI || channel == this.activeChannel) return;
        ConsoleRepository.getInstance().unsubscribe(this.activeChannel, this);
        this.activeChannel = channel;
        this.scroll = 0.0D;
        reloadSnapshot();
        ConsoleRepository.getInstance().subscribe(this.activeChannel, this);
    }

    @Override
    public void onRecord(ConsoleRecord record) {
        if (record == null || record.channel() != this.activeChannel) return;
        this.cachedLines.add(ConsoleFormatter.style(record));
        while (this.cachedLines.size() > 4_000) this.cachedLines.remove(0);
    }

    @Override
    public void tick() {
        if (this.searchField != null) this.searchField.tick();
        if (this.inputField != null) this.inputField.tick();
        if (this.activeChannel == ConsoleChannel.MINECRAFT) {
            long stamp = fileStamp(Path.of("logs/latest.log"));
            if (stamp != this.lastFileStamp) reloadSnapshot();
        }
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int bottom = this.height - FOOTER_HEIGHT;
        KoilVanillaScreenChrome.renderListShell(context, MinecraftClient.getInstance(), this.width, this.height, HEADER_BOTTOM, bottom);
        KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Koil"), Text.literal("Logs | " + activeChannel.id()));
        int left = 42;
        int right = this.width - 42;
        int top = HEADER_BOTTOM + 5;
        context.fill(left, top, right, bottom - 3, withAlpha(uiColorContentBase, 224));
        context.drawBorder(left, top, right - left, bottom - top - 3, new Color(uiColorBackgroundBorder, true).getRGB());
        renderLines(context, left + 5, top + 4, right - 5, bottom - 7);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderLines(DrawContext context, int left, int top, int right, int bottom) {
        List<ConsoleStyledLine> lines = ConsoleDisplayService.filter(this.cachedLines,
                this.searchField == null ? "" : this.searchField.getText());
        int lineHeight = this.textRenderer.fontHeight + 3;
        int viewport = Math.max(1, bottom - top);
        int content = lines.size() * lineHeight;
        this.scroll = MathHelper.clamp(this.scroll, 0.0D, Math.max(0, content - viewport));
        int first = Math.max(0, (int) this.scroll / lineHeight);
        int y = top - ((int) this.scroll % lineHeight);
        context.enableScissor(left, top, right, bottom);
        for (int index = first; index < lines.size() && y < bottom; index++) {
            ConsoleStyledLine line = lines.get(index);
            int x = left;
            for (ConsoleStyledSpan span : line.spans()) {
                String text = this.textRenderer.trimToWidth(span.text(), Math.max(0, right - x));
                context.drawText(this.textRenderer, text, x, y, span.color(), false);
                x += this.textRenderer.getWidth(text);
                if (x >= right) break;
            }
            y += lineHeight;
        }
        context.disableScissor();
        if (content > viewport) {
            int thumb = Math.max(12, viewport * viewport / content);
            int travel = viewport - thumb;
            int thumbY = top + (int) Math.round(travel * this.scroll / Math.max(1, content - viewport));
            context.fill(right - 1, top, right, bottom, 0x66505B69);
            context.fill(right - 2, thumbY, right + 1, thumbY + thumb, 0xCC9EA9B8);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        this.scroll -= amount * 30.0D;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.inputField != null && this.inputField.isFocused() && (keyCode == 257 || keyCode == 335)) {
            submitInput();
            return true;
        }
        if (hasControlDown() && keyCode == 70 && this.searchField != null) {
            this.setFocused(this.searchField);
            this.searchField.setFocused(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void submitInput() {
        String text = this.inputField == null ? "" : this.inputField.getText().trim();
        if (text.isBlank()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            if (text.startsWith("/")) {
                String command = text.substring(1);
                RichChatCommandOutputBridge.rememberOutgoingChatCommand(command);
                client.player.networkHandler.sendChatCommand(command);
            } else {
                client.player.networkHandler.sendChatMessage(text);
            }
        }
        this.inputField.setText("");
        UiSoundHelper.playButtonClick();
    }

    private void reloadSnapshot() {
        this.cachedLines.clear();
        if (this.activeChannel == ConsoleChannel.MINECRAFT) {
            Path path = Path.of("logs/latest.log");
            this.cachedLines.addAll(ConsoleDisplayService.readStyledLog(path, this.activeChannel));
            this.lastFileStamp = fileStamp(path);
        } else {
            this.cachedLines.addAll(ConsoleDisplayService.snapshot(this.activeChannel));
            this.lastFileStamp = Long.MIN_VALUE;
        }
    }

    private long fileStamp(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    @Override
    public void removed() {
        ConsoleRepository.getInstance().unsubscribe(this.activeChannel, this);
        super.removed();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static int withAlpha(int color, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }
}
