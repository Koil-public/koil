package com.spirit.client.gui.main;

import com.google.common.collect.ImmutableList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.List;
import java.util.stream.Stream;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;
import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class ConsoleToast implements Toast {
    private final Type type;
    private Text title;
    private List<OrderedText> lines;
    private long startTime;
    private boolean justUpdated;
    private final int width;

    Identifier TEXTURE = loadExternalPngTexture(uiImageDirectory, "koil_toasts.png");

    public ConsoleToast(Type type, Text title, @Nullable Text description) {
        this(type, title, getTextAsList(description), Math.max(160, 30 + Math.max(MinecraftClient.getInstance().textRenderer.getWidth(title), description == null ? 0 : MinecraftClient.getInstance().textRenderer.getWidth(description))));
    }

    public static ConsoleToast create(MinecraftClient client, Type type, Text title, Text description) {
        TextRenderer textRenderer = client.textRenderer;
        List<OrderedText> list = textRenderer.wrapLines(description, 200);
        Stream<OrderedText> stream = list.stream();
        int i = Math.max(200, stream.mapToInt(textRenderer::getWidth).max().orElse(200));
        return new ConsoleToast(type, title, list, i + 30);
    }

    private ConsoleToast(Type type, Text title, List<OrderedText> lines, int width) {
        this.type = type;
        this.title = title;
        this.lines = lines;
        this.width = width;
    }

    private static ImmutableList<OrderedText> getTextAsList(@Nullable Text text) {
        return text == null ? ImmutableList.of() : ImmutableList.of(text.asOrderedText());
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        return 20 + Math.max(this.lines.size(), 1) * 12;
    }

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        if (this.justUpdated) {
            this.startTime = startTime;
            this.justUpdated = false;
        }

        int i = this.getWidth();
        int j = this.getHeight();
        int l = Math.min(8, j - 28);

        int textureY = getTextureOffsetY(this.type);

        this.drawPart(context, manager, i, textureY, 0, 28);

        for (int m = 28; m < j - l; m += 10) {
            this.drawPart(context, manager, i, textureY + 16, m, Math.min(16, j - m - l));
        }

        this.drawPart(context, manager, i, textureY + 32 - l, j - l, l);

        int titleColor = getTitleColor(this.type);
        int descriptionColor = getDescriptionColor(this.type);

        context.drawText(manager.getClient().textRenderer, this.title, 18, 7, titleColor, false);
        for (int lineIndex = 0; lineIndex < this.lines.size(); ++lineIndex) {
            context.drawText(manager.getClient().textRenderer, this.lines.get(lineIndex), 18, 18 + lineIndex * 12, descriptionColor, false);
        }

        return (double) (startTime - this.startTime) < (double) this.type.displayDuration * manager.getNotificationDisplayTimeMultiplier() ? Visibility.SHOW : Visibility.HIDE;
    }

    private int getTextureOffsetY(Type type) {
        return switch (type) {
            case CONSOLE -> 0;
            case CONSOLE_INFO -> 32;
            case CONSOLE_WARNING -> 64;
            case CONSOLE_ERROR -> 96;
            case CONSOLE_FATAL -> 128;
            case CONSOLE_DEBUG -> 160;
            case CONSOLE_OTHER -> 192;
            case CONSOLE_UPDATE -> 224;
        };
    }

    private void drawPart(DrawContext context, ToastManager manager, int width, int textureV, int y, int height) {
        if (TEXTURE == null) {
            int background = new Color(uiColorConsoleToastFallbackBackground, true).getRGB();
            int border = getTitleColor(this.type);
            context.fill(0, y, width, y + height, background);
            if (y == 0) {
                context.fill(0, 0, 3, Math.max(height, getHeight()), border);
                context.drawBorder(0, 0, width, getHeight(), new Color(uiColorConsoleToastFallbackOutline, true).getRGB());
            }
            return;
        }
        int partWidth = width / 8;
        int j = Math.min(60, width - partWidth);

        context.drawTexture(TEXTURE, 0, y, 0, 64 + textureV, partWidth, height);

        for (int k = partWidth; k < width - j; k += partWidth) {
            context.drawTexture(TEXTURE, k, y, 32, 64 + textureV, Math.min(partWidth, width - k - j), height);
        }

        context.drawTexture(TEXTURE, width - j, y, 160 - j, 64 + textureV, j, height);
    }

    private int getTitleColor(Type type) {
        return switch (type) {
            case CONSOLE -> new Color(uiColorConsoleToastConsoleTitle, true).getRGB();
            case CONSOLE_INFO -> new Color(uiColorConsoleToastInfoTitle, true).getRGB();
            case CONSOLE_WARNING -> new Color(uiColorConsoleToastWarningTitle, true).getRGB();
            case CONSOLE_ERROR -> new Color(uiColorConsoleToastErrorTitle, true).getRGB();
            case CONSOLE_FATAL -> new Color(uiColorConsoleToastFatalTitle, true).getRGB();
            case CONSOLE_DEBUG -> new Color(uiColorConsoleToastDebugTitle, true).getRGB();
            case CONSOLE_UPDATE -> new Color(uiColorConsoleToastUpdateTitle, true).getRGB();
            case CONSOLE_OTHER -> new Color(uiColorConsoleToastOtherTitle, true).getRGB();
        };
    }

    private int getDescriptionColor(Type type) {
        return switch (type) {
            case CONSOLE -> new Color(uiColorConsoleToastConsoleDescription, true).getRGB();
            case CONSOLE_INFO -> new Color(uiColorConsoleToastInfoDescription, true).getRGB();
            case CONSOLE_WARNING -> new Color(uiColorConsoleToastWarningDescription, true).getRGB();
            case CONSOLE_ERROR -> new Color(uiColorConsoleToastErrorDescription, true).getRGB();
            case CONSOLE_FATAL -> new Color(uiColorConsoleToastFatalDescription, true).getRGB();
            case CONSOLE_DEBUG -> new Color(uiColorConsoleToastDebugDescription, true).getRGB();
            case CONSOLE_UPDATE -> new Color(uiColorConsoleToastUpdateDescription, true).getRGB();
            case CONSOLE_OTHER -> new Color(uiColorConsoleToastOtherDescription, true).getRGB();
        };
    }

    public Type getType() {
        return this.type;
    }

    public static void add(ToastManager manager, Type type, Text title, @Nullable Text description) {
        manager.add(new ConsoleToast(type, title, description));
    }

    @Environment(EnvType.CLIENT)
    public enum Type {
        CONSOLE,
        CONSOLE_INFO,
        CONSOLE_WARNING,
        CONSOLE_ERROR,
        CONSOLE_FATAL,
        CONSOLE_DEBUG,
        CONSOLE_UPDATE,
        CONSOLE_OTHER;

        final long displayDuration;

        Type(long displayDuration) {
            this.displayDuration = displayDuration;
        }

        Type() {
            this(5000L);
        }
    }
}