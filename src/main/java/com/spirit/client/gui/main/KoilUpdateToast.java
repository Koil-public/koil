package com.spirit.client.gui.main;

import com.google.common.collect.ImmutableList;
import com.spirit.client.gui.update.UpdateScreen;
import com.spirit.client.gui.update.elements.UpdateState;
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
public class KoilUpdateToast implements Toast {
    private final Type type;
    private Text title;
    private List<OrderedText> lines;
    private long startTime;
    private boolean justUpdated;
    private final int width;
    private static long lastShownAt;
    private static int lastShownWidth = 200;
    private static int lastShownHeight = 44;

    Identifier TEXTURE = loadExternalPngTexture(uiImageDirectory, "koil_update_toasts.png");

    public KoilUpdateToast(Type type, Text title, @Nullable Text description) {
        this(type, title, getTextAsList(description), Math.max(160, 30 + Math.max(MinecraftClient.getInstance().textRenderer.getWidth(title), description == null ? 0 : MinecraftClient.getInstance().textRenderer.getWidth(description))));
    }

    public static KoilUpdateToast create(MinecraftClient client, Type type, Text title, Text description) {
        TextRenderer textRenderer = client.textRenderer;
        List<OrderedText> list = textRenderer.wrapLines(description, 200);
        Stream<OrderedText> stream = list.stream();
        int i = Math.max(200, stream.mapToInt(textRenderer::getWidth).max().orElse(200));
        return new KoilUpdateToast(type, title, list, i + 30);
    }

    private KoilUpdateToast(Type type, Text title, List<OrderedText> lines, int width) {
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
        lastShownAt = System.currentTimeMillis();
        lastShownWidth = i;
        lastShownHeight = j;
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
            case UPDATE_UI -> 0;
            case UPDATE_DEBUG -> 32;
            case UPDATE_API -> 64;
            case UPDATE_CONSOLE -> 96;
            case UPDATE_OTHER -> 128;
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

    private int getTitleColor(Type type) {
        return switch (type) {
            case UPDATE_UI -> new Color(0xA7, 0x00, 0x3A, 255).getRGB();
            case UPDATE_DEBUG -> new Color(0x2D, 0xA7, 0x00, 255).getRGB();
            case UPDATE_API -> new Color(0x00, 0x85, 0xA4, 255).getRGB();
            case UPDATE_CONSOLE -> new Color(0x74, 0x00, 0xA4, 255).getRGB();
            case UPDATE_OTHER -> new Color(0x8C, 0x88, 0xB5, 255).getRGB();
        };
    }

    private int getDescriptionColor(Type type) {
        return switch (type) {
            case UPDATE_UI -> new Color(0xD5, 0x5D, 0x87, 255).getRGB();
            case UPDATE_DEBUG -> new Color(0x78, 0xD4, 0x5A, 255).getRGB();
            case UPDATE_API -> new Color(0x5E, 0xC7, 0xDB, 255).getRGB();
            case UPDATE_CONSOLE -> new Color(0xAE, 0x66, 0xD3, 255).getRGB();
            case UPDATE_OTHER -> new Color(0xC2, 0xBE, 0xE8, 255).getRGB();
        };
    }

    public Type getType() {
        return this.type;
    }

    public static void add(ToastManager manager, Type type, Text title, @Nullable Text description) {
        manager.add(new KoilUpdateToast(type, title, description));
    }

    public static boolean handleClick(MinecraftClient client, double mouseX, double mouseY) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        UpdateState.Status status = UpdateState.resolve();
        if (!status.updateAvailable()) {
            return false;
        }
        long visibleWindow = Type.UPDATE_OTHER.displayDuration + 1500L;
        if (System.currentTimeMillis() - lastShownAt > visibleWindow) {
            return false;
        }
        int screenWidth = client.getWindow().getScaledWidth();
        int toastX = Math.max(0, screenWidth - lastShownWidth - 4);
        int toastY = 4;
        int toastH = Math.max(32, lastShownHeight + 4);
        if (mouseX >= toastX && mouseX <= screenWidth && mouseY >= toastY && mouseY <= toastY + toastH) {
            client.setScreen(new UpdateScreen(client.currentScreen));
            return true;
        }
        return false;
    }

    @Environment(EnvType.CLIENT)
    public enum Type {
        UPDATE_UI,
        UPDATE_DEBUG,
        UPDATE_API,
        UPDATE_CONSOLE,
        UPDATE_OTHER;

        final long displayDuration;

        Type(long displayDuration) {
            this.displayDuration = displayDuration;
        }

        Type() {
            this(5000L);
        }
    }
}
