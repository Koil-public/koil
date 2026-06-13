package com.spirit.koil.api.util.web;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DownloadProgressTracker {
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private DownloadProgressTracker() {
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void emit(Type type, String url, String targetPath, long bytesRead, long totalBytes, String message) {
        DownloadEvent event = new DownloadEvent(type, url, targetPath, bytesRead, totalBytes, message, System.currentTimeMillis());
        for (Listener listener : LISTENERS) {
            listener.onDownloadEvent(event);
        }
    }

    public interface Listener {
        void onDownloadEvent(DownloadEvent event);
    }

    public enum Type {
        REQUEST,
        PROGRESS,
        COMPLETE,
        SKIP,
        ERROR,
        INFO
    }

    public record DownloadEvent(Type type, String url, String targetPath, long bytesRead, long totalBytes, String message, long timestampMs) {
    }
}
