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

@Environment(EnvType.CLIENT)
public class KoilMessageToast implements Toast {
    private final Type type;
    private Text title;
    private List<OrderedText> lines;
    private long startTime;
    private boolean justUpdated;
    private final int width;

    Identifier TEXTURE = loadExternalPngTexture(uiImageDirectory, "koil_message_toasts.png");

    public KoilMessageToast(Type type, Text title, @Nullable Text description) {
        this(type, title, getTextAsList(description), Math.max(160, 30 + Math.max(MinecraftClient.getInstance().textRenderer.getWidth(title), description == null ? 0 : MinecraftClient.getInstance().textRenderer.getWidth(description))));
    }

    public static KoilMessageToast create(MinecraftClient client, Type type, Text title, Text description) {
        TextRenderer textRenderer = client.textRenderer;
        List<OrderedText> list = textRenderer.wrapLines(description, 200);
        Stream<OrderedText> stream = list.stream();
        int i = Math.max(200, stream.mapToInt(textRenderer::getWidth).max().orElse(200));
        return new KoilMessageToast(type, title, list, i + 30);
    }

    private KoilMessageToast(Type type, Text title, List<OrderedText> lines, int width) {
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

        // Drawing the background
        this.drawPart(context, manager, i, textureY, 0, 28);

        for (int m = 28; m < j - l; m += 10) {
            this.drawPart(context, manager, i, textureY + 16, m, Math.min(16, j - m - l));
        }

        this.drawPart(context, manager, i, textureY + 32 - l, j - l, l);

        // Getting text colors
        int titleColor = getTitleColor(this.type);
        int descriptionColor = getDescriptionColor(this.type);

        // Drawing title and description with custom colors
        context.drawText(manager.getClient().textRenderer, this.title, 18, 7, titleColor, false);
        for (int lineIndex = 0; lineIndex < this.lines.size(); ++lineIndex) {
            context.drawText(manager.getClient().textRenderer, this.lines.get(lineIndex), 18, 18 + lineIndex * 12, descriptionColor, false);
        }

        return (double) (startTime - this.startTime) < (double) this.type.displayDuration * manager.getNotificationDisplayTimeMultiplier() ? Visibility.SHOW : Visibility.HIDE;
    }

    private int getTextureOffsetY(Type type) {
        return switch (type) {
            case MUSIC -> 0;
            case CONSOLE_INFO -> 32;
            case CONSOLE_WARNING -> 64;
            case CONSOLE_ERROR -> 96;
            case CONSOLE_FATAL -> 128;
            case CONSOLE_DEBUG -> 160;
            case ANNOUNCEMENT -> 192;
            case KORO_MESSAGE -> 224;
        };
    }

    private void drawPart(DrawContext context, ToastManager manager, int width, int textureV, int y, int height) {
        if (TEXTURE == null) {
            int background = new Color(12, 14, 18, 232).getRGB();
            int border = getTitleColor(this.type);
            context.fill(0, y, width, y + height, background);
            if (y == 0) {
                context.fill(0, 0, 3, Math.max(height, getHeight()), border);
                context.drawBorder(0, 0, width, getHeight(), new Color(42, 46, 56, 255).getRGB());
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

    private int getTitleColor(KoilMessageToast.Type type) {
        return switch (type) {
            case MUSIC -> new Color(127, 127, 127, 255).getRGB();
            case CONSOLE_INFO -> new Color(196, 176, 17, 255).getRGB();
            case CONSOLE_WARNING -> new Color(196, 96, 17, 255).getRGB();
            case CONSOLE_ERROR -> new Color(196, 17, 30, 255).getRGB();
            case CONSOLE_FATAL -> new Color(196, 17, 30, 255).getRGB();
            case CONSOLE_DEBUG -> new Color(45, 196, 17, 255).getRGB();
            case ANNOUNCEMENT -> new Color(104, 36, 36, 255).getRGB();
            case KORO_MESSAGE -> new Color(153, 153, 153, 255).getRGB();
        };
    }

    private int getDescriptionColor(KoilMessageToast.Type type) {
        return switch (type) {
            case MUSIC -> new Color(110, 110, 110, 255).getRGB();
            case CONSOLE_INFO -> new Color(156, 139, 14, 255).getRGB();
            case CONSOLE_WARNING -> new Color(158, 77, 14, 255).getRGB();
            case CONSOLE_ERROR -> new Color(142, 12, 21, 255).getRGB();
            case CONSOLE_FATAL -> new Color(143, 14, 24, 255).getRGB();
            case CONSOLE_DEBUG -> new Color(32, 136, 12, 255).getRGB();
            case ANNOUNCEMENT -> new Color(208, 115, 115, 255).getRGB();
            case KORO_MESSAGE -> new Color(131, 131, 131, 255).getRGB();
        };
    }

    public Type getType() {
        return this.type;
    }

    public static void add(ToastManager manager, Type type, Text title, @Nullable Text description) {
        manager.add(new KoilMessageToast(type, title, description));
    }

    @Environment(EnvType.CLIENT)
    public enum Type {
        MUSIC, // 3
        CONSOLE_INFO, // 4
        CONSOLE_WARNING, // 5
        CONSOLE_ERROR, // 6
        CONSOLE_FATAL, // 7
        CONSOLE_DEBUG,  // 8
        ANNOUNCEMENT, // 1
        KORO_MESSAGE; // 2

        final long displayDuration;

        Type(long displayDuration) {
            this.displayDuration = displayDuration;
        }

        Type() {
            this(12000L);
        }
    }
}
