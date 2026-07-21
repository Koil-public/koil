package com.spirit.client.gui;

import com.spirit.client.gui.ide.FIleIconHelper;
import com.spirit.client.gui.ide.FileExplorerScreen;
import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.design.uiColorVal;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.io.File;

/** Compact cross-screen adapter around the shared AudioManager transport. */
@Environment(EnvType.CLIENT)
public final class ActiveAudioControlPopup {
    private static final int WIDTH = 224;
    private static final int HEIGHT = 39;
    private boolean open;
    private int x;
    private int y;

    public void openAtPointer(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        x = Math.max(8, Math.min((int) Math.round(mouseX) + 4, screenWidth - WIDTH - 8));
        y = Math.max(8, Math.min((int) Math.round(mouseY) - 2, screenHeight - HEIGHT - 8));
        open = AudioManager.hasActiveAudio();
    }

    public boolean isOpen() { return open; }
    public boolean contains(double mouseX, double mouseY) { return open && mouseX >= x && mouseX <= x + WIDTH && mouseY >= y && mouseY <= y + HEIGHT; }
    public void close() { open = false; }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open || button != 0 || !contains(mouseX, mouseY)) return false;
        if (mouseY >= y + 17 && mouseY <= y + 33) {
            if (mouseX >= x + 22 && mouseX < x + 38) {
                AudioManager.pauseAudio();
            } else if (mouseX >= x + 42 && mouseX < x + 58) {
                AudioManager.stopAllAudio();
                close();
            } else if (mouseX >= x + 62 && mouseX <= x + WIDTH - 10 && AudioManager.canSeekPlayback()) {
                float progress = (float) ((mouseX - (x + 62)) / (double) (WIDTH - 72));
                AudioManager.seekToProgress(progress, 1.0F);
            }
        }
        return true;
    }

    public void render(DrawContext context, int mouseX, int mouseY) {
        if (!open) return;
        File file = AudioManager.currentAudioFile();
        if (file == null) { close(); return; }
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 9000);
        context.fill(x, y, x + WIDTH, y + HEIGHT, 0xEC171B22);
        context.drawBorder(x, y, WIDTH, HEIGHT, 0xFF596675);
        Identifier icon = FIleIconHelper.resolve(file.getName());
        context.drawTexture(icon, x + 5, y + 5, 0, 0, 16, 16, 16, 16);
        String name = trim(renderer, file.getName(), WIDTH - 30);
        context.drawText(renderer, name, x + 24, y + 7, 0xFFF0F4FA, false);
        int playX = x + 22, stopX = x + 42, barX = x + 62, barW = WIDTH - 100, barY = y + 21;
        context.drawTexture(AudioManager.isAudioPlaying() ? FileExplorerScreen.PAUSE_BUTTON : FileExplorerScreen.PLAY_BUTTON, playX, y + 17, 0, 0, 16, 16, 16, 16);
        context.drawTexture(FileExplorerScreen.STOP_BUTTON, stopX, y + 17, 0, 0, 16, 16, 16, 16);
        float progress = AudioManager.getPlaybackProgress();
        int fill = Math.max(0, Math.min(barW, Math.round(progress * barW)));
        context.fill(barX, barY, barX + fill, barY + 8, uiColorVal.uiColorIDEAudioTimestampBarFill);
        context.drawBorder(barX, barY, barW, 8, uiColorVal.uiColorIDEAudioTimestampBarBorder);
        context.fill(barX + fill - 1, barY - 1, barX + fill + 1, barY + 9, uiColorVal.uiColorIDEAudioTimestampBarLine);
        String current = time(AudioManager.getPlaybackPositionMicros());
        String total = time(AudioManager.getPlaybackLengthMicros());
        int thumbTextX = Math.max(barX, Math.min(barX + fill - renderer.getWidth(current) / 2, barX + barW - renderer.getWidth(current)));
        context.drawText(renderer, current, thumbTextX, barY - 9, uiColorVal.uiColorIDEAudioTimestampText, false);
        context.drawText(renderer, total, barX + barW + 4, barY, uiColorVal.uiColorIDEAudioTimestampText, false);
        context.getMatrices().pop();
    }

    private static String trim(TextRenderer renderer, String value, int width) {
        if (renderer.getWidth(value) <= width) return value;
        return renderer.trimToWidth(value, Math.max(1, width - renderer.getWidth("…"))) + "…";
    }

    private static String time(long micros) {
        long seconds = Math.max(0L, micros / 1_000_000L);
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }
}
