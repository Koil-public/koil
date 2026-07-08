package com.spirit.koil.api.util.file.media;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.awt.Color;

@Environment(EnvType.CLIENT)
public final class VisualTransportControls {
    private VisualTransportControls() {
    }

    public static RenderResult renderPreviewFooter(DrawContext context, TextRenderer renderer, VisualPlaybackSession session, PreviewFooterSpec spec) {
        if (context == null || renderer == null || session == null || spec == null) {
            return RenderResult.empty();
        }
        int barHeight = 12;
        int controlPadding = 6;
        int iconSize = 16;
        int footerHeight = 30;
        int mediaHeight = Math.max(1, spec.height());
        int footerTop = spec.footerBelowMedia() ? spec.y() + mediaHeight : spec.y() + mediaHeight - footerHeight;
        int borderHeight = mediaHeight + (spec.footerBelowMedia() && spec.hovered() ? footerHeight : 0);
        int controlsY = footerTop + 7;
        int progressX = spec.x() + controlPadding + (spec.gifOnly() ? 0 : (iconSize * 2) + 8);
        int progressWidth = Math.max(48, spec.width() - (progressX - spec.x()) - controlPadding);
        int progressY = controlsY + 3;

        context.drawBorder(spec.x(), spec.y(), spec.width(), borderHeight, spec.frameBorderColor());
        if (!spec.hovered()) {
            return RenderResult.empty();
        }

        context.fill(spec.x(), footerTop, spec.x() + spec.width(), footerTop + footerHeight, spec.backgroundColor());

        int[] primaryBounds = null;
        int[] secondaryBounds = null;
        int[] overlayBounds = null;
        if (!spec.gifOnly()) {
            primaryBounds = new int[]{spec.x() + controlPadding, controlsY, iconSize, iconSize};
            secondaryBounds = new int[]{spec.x() + controlPadding + iconSize + 4, controlsY, iconSize, iconSize};
            context.drawTexture(session.state() == VisualPlaybackState.PLAYING ? spec.pauseButton() : spec.playButton(), primaryBounds[0], primaryBounds[1], 0, 0, iconSize, iconSize, iconSize, iconSize);
            context.drawTexture(spec.stopButton(), secondaryBounds[0], secondaryBounds[1], 0, 0, iconSize, iconSize, iconSize, iconSize);

            if (spec.drawCenterPlayOverlay() && session.state() != VisualPlaybackState.PLAYING) {
                int overlaySize = Math.max(28, Math.min(56, Math.min(spec.width(), mediaHeight) / 4));
                int overlayX = spec.x() + (spec.width() - overlaySize) / 2;
                int overlayY = spec.y() + Math.max(12, (mediaHeight - overlaySize) / 2);
                overlayBounds = new int[]{overlayX, overlayY, overlaySize, overlaySize};
                context.fill(overlayX, overlayY, overlayX + overlaySize, overlayY + overlaySize, spec.overlayBackgroundColor());
                context.drawBorder(overlayX, overlayY, overlaySize, overlaySize, spec.frameBorderColor());
                int playSize = Math.max(16, overlaySize - 14);
                int playX = overlayX + (overlaySize - playSize) / 2;
                int playY = overlayY + (overlaySize - playSize) / 2;
                context.drawTexture(spec.playButton(), playX, playY, 0, 0, playSize, playSize, playSize, playSize);
            }
        }

        int[] timelineBounds = null;
        if (session.canSeek()) {
            float progress = session.durationMillis() <= 0L ? 0.0F : Math.max(0.0F, Math.min(1.0F, session.positionMillis() / (float) session.durationMillis()));
            int filledWidth = (int)(progressWidth * progress);
            timelineBounds = new int[]{progressX, progressY, progressWidth, barHeight};
            context.fill(progressX, progressY, progressX + filledWidth, progressY + barHeight, spec.fillColor());
            context.drawBorder(progressX, progressY, progressWidth, barHeight, spec.timelineBorderColor());
            if (filledWidth > 0) {
                context.fill(progressX + filledWidth - 1, progressY - 1, progressX + filledWidth + 1, progressY + barHeight + 1, spec.timelineLineColor());
            }

            String currentTime = formatDuration(session.positionMillis());
            String totalTime = formatDuration(session.durationMillis());
            int labelY = progressY - 10;
            context.drawText(renderer, currentTime, progressX, labelY, spec.textColor(), false);
            int totalWidth = renderer.getWidth(totalTime);
            context.drawText(renderer, totalTime, progressX + progressWidth - totalWidth, labelY, spec.textColor(), false);
        }
        return new RenderResult(primaryBounds, secondaryBounds, timelineBounds, overlayBounds);
    }

    public static RenderResult renderBottomBar(DrawContext context, TextRenderer renderer, VisualPlaybackSession session, BottomBarSpec spec) {
        if (context == null || renderer == null || session == null || spec == null) {
            return RenderResult.empty();
        }
        int iconSize = 16;
        int playX = spec.x();
        int stopX = playX + 18;
        int barX = stopX + 20;
        String total = formatDuration(session.durationMillis());
        int totalWidth = renderer.getWidth(total);
        int barWidth = Math.max(96, spec.width() - (barX - spec.x()) - totalWidth - 6);
        int barY = spec.y() + 2;
        long duration = Math.max(1L, session.durationMillis());
        long position = Math.max(0L, session.positionMillis());
        float progress = Math.max(0.0F, Math.min(1.0F, position / (float)duration));
        int filled = Math.max(0, Math.min(barWidth, Math.round(barWidth * progress)));

        context.drawTexture(session.state() == VisualPlaybackState.PLAYING ? spec.pauseButton() : spec.playButton(), playX, spec.y(), 0, 0, iconSize, iconSize, iconSize, iconSize);
        context.drawTexture(spec.stopButton(), stopX, spec.y(), 0, 0, iconSize, iconSize, iconSize, iconSize);
        context.fill(barX, barY, barX + filled, barY + spec.barHeight(), spec.fillColor());
        context.drawBorder(barX, barY, barWidth, spec.barHeight(), spec.timelineBorderColor());
        if (filled > 0) {
            context.fill(barX + filled - 1, barY - 1, barX + filled + 1, barY + spec.barHeight() + 1, spec.timelineLineColor());
        }
        String current = formatDuration(position);
        context.drawText(renderer, current, barX, barY + 1, spec.textColor(), true);
        context.drawText(renderer, total, barX + barWidth + 4, barY + 1, spec.textColor(), true);
        return new RenderResult(
                new int[]{playX, spec.y(), iconSize, iconSize},
                new int[]{stopX, spec.y(), iconSize, iconSize},
                new int[]{barX, barY, barWidth, spec.barHeight()},
                null
        );
    }

    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10L ? "0" : "") + seconds;
    }

    public record PreviewFooterSpec(
            int x,
            int y,
            int width,
            int height,
            boolean footerBelowMedia,
            boolean gifOnly,
            boolean hovered,
            boolean drawCenterPlayOverlay,
            Identifier playButton,
            Identifier pauseButton,
            Identifier stopButton,
            int frameBorderColor,
            int backgroundColor,
            int overlayBackgroundColor,
            int fillColor,
            int timelineBorderColor,
            int timelineLineColor,
            int textColor
    ) {
    }

    public record BottomBarSpec(
            int x,
            int y,
            int width,
            int barHeight,
            Identifier playButton,
            Identifier pauseButton,
            Identifier stopButton,
            int fillColor,
            int timelineBorderColor,
            int timelineLineColor,
            int textColor
    ) {
    }

    public record RenderResult(int[] primaryBounds, int[] secondaryBounds, int[] timelineBounds, int[] overlayBounds) {
        public static RenderResult empty() {
            return new RenderResult(null, null, null, null);
        }
    }
}
