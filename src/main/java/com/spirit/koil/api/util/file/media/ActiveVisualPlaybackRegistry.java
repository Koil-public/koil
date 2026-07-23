package com.spirit.koil.api.util.file.media;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class ActiveVisualPlaybackRegistry {
    private static volatile VisualPlaybackSession activeSession;
    private static volatile String activeLabel = "Video";

    private ActiveVisualPlaybackRegistry() {
    }

    public static synchronized void activate(VisualPlaybackSession session, String label) {
        if (session == null) {
            return;
        }
        activeSession = session;
        activeLabel = label == null || label.isBlank() ? "Video" : label;
    }

    public static synchronized ActivePlayback active() {
        VisualPlaybackSession session = activeSession;
        if (session == null) {
            return null;
        }
        try {
            if (session.state() == VisualPlaybackState.FAILED || session.state() == VisualPlaybackState.IDLE) {
                clear(session);
                return null;
            }
            return new ActivePlayback(session, activeLabel);
        } catch (RuntimeException exception) {
            clear(session);
            return null;
        }
    }

    public static boolean hasActivePlayback() {
        return active() != null;
    }

    public static synchronized void clear(VisualPlaybackSession session) {
        if (session == null || activeSession == session) {
            activeSession = null;
            activeLabel = "Video";
        }
    }

    public record ActivePlayback(VisualPlaybackSession session, String label) {
    }
}
