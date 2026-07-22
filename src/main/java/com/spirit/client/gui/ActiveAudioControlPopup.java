package com.spirit.client.gui;

import com.spirit.client.gui.ide.FIleIconHelper;
import com.spirit.client.gui.ide.FileExplorerScreen;
import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.util.file.media.ActiveVisualPlaybackRegistry;
import com.spirit.koil.api.util.file.media.VisualPlaybackState;
import com.spirit.koil.api.util.file.media.VisualTransportControls;
import com.spirit.koil.api.design.uiColorVal;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.io.File;

/** Compact cross-screen adapter around Koil's shared audio and video transports. */
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
        open = hasActiveMedia();
    }

    public boolean isOpen() { return open; }
    public boolean contains(double mouseX, double mouseY) { return open && mouseX >= x && mouseX <= x + WIDTH && mouseY >= y && mouseY <= y + HEIGHT; }
    public void close() { open = false; }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open || button != 0 || !contains(mouseX, mouseY)) return false;
        ActiveVisualPlaybackRegistry.ActivePlayback visual = activeVisualPlayback();
        if (mouseY >= y + 17 && mouseY <= y + 33) {
            if (mouseX >= x + 22 && mouseX < x + 38) {
                if (visual != null) {
                    if (visual.session().state() == VisualPlaybackState.PLAYING) visual.session().pause();
                    else visual.session().play();
                } else if (AudioManager.isAudioPlaying()) {
                    AudioManager.pauseAudio();
                } else {
                    File file = AudioManager.currentAudioFile();
                    if (file != null) AudioManager.playAudio(file, false, 1.0F);
                }
            } else if (mouseX >= x + 42 && mouseX < x + 58) {
                if (visual != null) {
                    visual.session().stop();
                    ActiveVisualPlaybackRegistry.clear(visual.session());
                } else {
                    AudioManager.stopAllAudio();
                }
                close();
            } else if (mouseX >= x + 62 && mouseX <= x + WIDTH - 10 && canSeek(visual)) {
                float progress = (float) ((mouseX - (x + 62)) / (double) (WIDTH - 72));
                if (visual != null) visual.session().seekTo(Math.round(visual.session().durationMillis() * progress));
                else AudioManager.seekToProgress(progress, 1.0F);
            }
        }
        return true;
    }

    public void render(DrawContext context, int mouseX, int mouseY) {
        if (!open) return;
        ActiveVisualPlaybackRegistry.ActivePlayback visual = activeVisualPlayback();
        File file = visual == null ? AudioManager.currentAudioFile() : null;
        if (file == null && visual == null) { close(); return; }
        if (visual != null) visual.session().update(System.currentTimeMillis());
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 9000);
        context.fill(x, y, x + WIDTH, y + HEIGHT, 0xEC171B22);
        context.drawBorder(x, y, WIDTH, HEIGHT, 0xFF596675);
        String mediaName = visual == null ? file.getName() : visual.label();
        Identifier icon = FIleIconHelper.resolve(mediaName);
        context.drawTexture(icon, x + 5, y + 5, 0, 0, 16, 16, 16, 16);
        String name = trim(renderer, mediaName, WIDTH - 30);
        context.drawText(renderer, name, x + 24, y + 7, 0xFFF0F4FA, false);
        int playX = x + 22, stopX = x + 42, barX = x + 62, barW = WIDTH - 100, barY = y + 21;
        boolean playing = visual == null ? AudioManager.isAudioPlaying() : visual.session().state() == VisualPlaybackState.PLAYING;
        context.drawTexture(playing ? FileExplorerScreen.PAUSE_BUTTON : FileExplorerScreen.PLAY_BUTTON, playX, y + 17, 0, 0, 16, 16, 16, 16);
        context.drawTexture(FileExplorerScreen.STOP_BUTTON, stopX, y + 17, 0, 0, 16, 16, 16, 16);
        float progress = visual == null
                ? AudioManager.getPlaybackProgress()
                : (visual.session().durationMillis() <= 0L ? 0.0F : visual.session().positionMillis() / (float) visual.session().durationMillis());
        progress = Math.max(0.0F, Math.min(1.0F, progress));
        int fill = Math.max(0, Math.min(barW, Math.round(progress * barW)));
        context.fill(barX, barY, barX + fill, barY + 8, uiColorVal.uiColorIDEAudioTimestampBarFill);
        context.drawBorder(barX, barY, barW, 8, uiColorVal.uiColorIDEAudioTimestampBarBorder);
        if (fill > 0) {
            context.fill(barX + fill - 1, barY - 1, barX + fill + 1, barY + 9, uiColorVal.uiColorIDEAudioTimestampBarLine);
        }
        String current = visual == null ? time(AudioManager.getPlaybackPositionMicros()) : VisualTransportControls.formatDuration(visual.session().positionMillis());
        String total = visual == null ? time(AudioManager.getPlaybackLengthMicros()) : VisualTransportControls.formatDuration(visual.session().durationMillis());
        VisualTransportControls.renderThumbTimestamp(context, renderer, current, barX + fill, barX, barW, barY + 11, uiColorVal.uiColorIDEAudioTimestampText);
        context.drawText(renderer, total, barX + barW + 4, barY, uiColorVal.uiColorIDEAudioTimestampText, false);
        context.getMatrices().pop();
    }

    private static String trim(TextRenderer renderer, String value, int width) {
        if (renderer.getWidth(value) <= width) return value;
        return renderer.trimToWidth(value, Math.max(1, width - renderer.getWidth("…"))) + "…";
    }

    private static boolean hasActiveMedia() {
        return AudioManager.hasActiveAudio() || ActiveVisualPlaybackRegistry.hasActivePlayback();
    }

    private static ActiveVisualPlaybackRegistry.ActivePlayback activeVisualPlayback() {
        ActiveVisualPlaybackRegistry.ActivePlayback visual = ActiveVisualPlaybackRegistry.active();
        if (visual == null) return null;
        VisualPlaybackState state = visual.session().state();
        return state == VisualPlaybackState.PLAYING || state == VisualPlaybackState.SEEKING || !AudioManager.hasActiveAudio() ? visual : null;
    }

    private static boolean canSeek(ActiveVisualPlaybackRegistry.ActivePlayback visual) {
        return visual == null ? AudioManager.canSeekPlayback() : visual.session().canSeek();
    }

    private static String time(long micros) {
        long seconds = Math.max(0L, micros / 1_000_000L);
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }
}
