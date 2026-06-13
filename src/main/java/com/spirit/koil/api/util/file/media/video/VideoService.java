package com.spirit.koil.api.util.file.media.video;

import com.spirit.koil.api.util.file.media.MediaPerformanceProfile;
import com.spirit.koil.api.util.file.media.image.ImageTexture;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;

import java.io.IOException;
import java.io.File;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VideoService {
    private static final Set<String> RECOGNIZED_VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "mov", "webm", "mkv", "avi", "wmv", "flv", "3gp",
            "mpeg", "mpg", "ogv", "ts", "mts", "m2ts"
    );
    private static final VideoBackend BACKEND = new ExternalFfmpegVideoBackend();
    private static final ExecutorService BACKGROUND_WORKER = Executors.newFixedThreadPool(Math.max(1, MediaPerformanceProfile.VIDEO_BACKGROUND_THREADS), runnable -> {
        Thread thread = new Thread(runnable, "koil-video-worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final Map<String, CachedProbe> PROBE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CachedThumbnail> THUMBNAIL_CACHE = new ConcurrentHashMap<>();

    private VideoService() {
    }

    public static boolean isRecognizedVideoFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String extension = extensionOf(file.getName());
        return !extension.isBlank() && RECOGNIZED_VIDEO_EXTENSIONS.contains(extension);
    }

    public static VideoProbeResult probe(File file) {
        if (file == null || !file.exists()) {
            return new VideoProbeResult(false, false, "Video file not found.", null);
        }
        String extension = extensionOf(file.getName());
        boolean recognized = RECOGNIZED_VIDEO_EXTENSIONS.contains(extension);
        if (!recognized) {
            VideoMetadata metadata = new VideoMetadata(
                    file.getName(),
                    extension.isBlank() ? "unknown" : extension,
                    -1,
                    -1,
                    0.0D,
                    -1L,
                    false,
                    "unsupported-container",
                    "none",
                    false
            );
            return new VideoProbeResult(false, false, "Container is not on Koil's staged video-support list.", metadata);
        }
        return BACKEND.probe(file);
    }

    public static VideoPlaybackSession createSession(File file) {
        return createSession(file, MediaPerformanceProfile.VIDEO_PLAYBACK_MAX_WIDTH, MediaPerformanceProfile.VIDEO_PLAYBACK_MAX_HEIGHT);
    }

    public static VideoPlaybackSession createSession(File file, int maxWidth, int maxHeight) {
        VideoProbeResult probe = probe(file);
        if (!probe.supportedContainer() || probe.metadata() == null || !probe.metadata().thumbnailCapable() || !(BACKEND instanceof ExternalFfmpegVideoBackend ffmpegBackend)) {
            return new UnsupportedVideoPlaybackSession(probe.metadata(), probe.message());
        }
        try {
            return new ExternalFfmpegVideoPlaybackSession(file, probe.metadata(), ffmpegBackend, maxWidth, maxHeight);
        } catch (IOException exception) {
            return new UnsupportedVideoPlaybackSession(probe.metadata(), "Video session startup failed: " + exception.getMessage());
        }
    }

    public static VideoPreviewSnapshot requestPreview(File file, int maxWidth, int maxHeight) {
        purgeExpiredEntries(System.currentTimeMillis());
        VideoPosterCache.purgeExpiredEntries();
        if (file == null || !file.exists()) {
            return new VideoPreviewSnapshot(
                    new VideoProbeResult(false, false, "Video file not found.", null),
                    null,
                    false,
                    "Video file not found."
            );
        }
        if (!isRecognizedVideoFile(file)) {
            VideoProbeResult unsupported = probe(file);
            return new VideoPreviewSnapshot(unsupported, null, false, unsupported.message());
        }

        CachedProbe probeEntry = PROBE_CACHE.computeIfAbsent(cacheKey(file), ignored -> new CachedProbe(file.lastModified()));
        probeEntry.lastAccessTime = System.currentTimeMillis();
        if (probeEntry.signature != file.lastModified()) {
            probeEntry.reset(file.lastModified());
            invalidateThumbnail(file);
        }
        if (probeEntry.result == null && !probeEntry.pending) {
            queueProbe(file, probeEntry);
        }

        VideoProbeResult probe = probeEntry.result;
        CachedThumbnail thumbnailEntry = THUMBNAIL_CACHE.computeIfAbsent(thumbnailCacheKey(file, maxWidth, maxHeight),
                ignored -> new CachedThumbnail(file.lastModified(), maxWidth, maxHeight));
        thumbnailEntry.lastAccessTime = System.currentTimeMillis();
        if (thumbnailEntry.signature != file.lastModified()) {
            thumbnailEntry.reset(file.lastModified(), maxWidth, maxHeight);
        }
        if (thumbnailEntry.rawBytes == null && thumbnailEntry.texture == null && thumbnailEntry.failureMessage == null) {
            byte[] cachedPoster = VideoPosterCache.read(file, thumbnailEntry.maxWidth, thumbnailEntry.maxHeight);
            if (cachedPoster != null && cachedPoster.length > 0) {
                thumbnailEntry.rawBytes = cachedPoster;
            }
        }

        if (probe != null && probe.supportedContainer() && probe.metadata() != null && probe.metadata().thumbnailCapable() && !thumbnailEntry.pending && thumbnailEntry.rawBytes == null && thumbnailEntry.failureMessage == null) {
            queueThumbnail(file, probe.metadata(), thumbnailEntry);
        }

        ImageTexture texture = materializeThumbnail(file, maxWidth, maxHeight, thumbnailEntry);
        String statusMessage = deriveStatusMessage(probeEntry, thumbnailEntry, texture);
        boolean loading = probeEntry.pending || thumbnailEntry.pending;
        return new VideoPreviewSnapshot(probe, texture, loading, statusMessage);
    }

    public static boolean isBackendAvailable() {
        return BACKEND.isAvailable();
    }

    private static ImageTexture materializeThumbnail(File file, int maxWidth, int maxHeight, CachedThumbnail entry) {
        if (entry.rawBytes == null) {
            return entry.texture;
        }
        if (entry.texture != null) {
            return entry.texture;
        }
        try {
            ImageTexture texture = ImageTextureService.loadTexture(
                    entry.rawBytes,
                    "koil",
                    "video_thumbnail/" + sanitizeTextureKey(file.getAbsolutePath()) + "_" + Math.max(1, maxWidth) + "x" + Math.max(1, maxHeight),
                    file.getName()
            );
            entry.texture = texture;
            return texture;
        } catch (IOException exception) {
            entry.failureMessage = "Thumbnail upload failed: " + exception.getMessage();
            entry.rawBytes = null;
            return null;
        }
    }

    private static String deriveStatusMessage(CachedProbe probeEntry, CachedThumbnail thumbnailEntry, ImageTexture texture) {
        if (probeEntry.failureMessage != null) {
            return probeEntry.failureMessage;
        }
        if (thumbnailEntry.failureMessage != null) {
            return thumbnailEntry.failureMessage;
        }
        if (probeEntry.result != null && probeEntry.result.message() != null && !probeEntry.result.message().isBlank() && (!probeEntry.result.supportedContainer() || texture == null)) {
            return probeEntry.result.message();
        }
        if (texture != null) {
            return "Poster thumbnail ready.";
        }
        if (thumbnailEntry.pending) {
            return "Generating poster thumbnail...";
        }
        if (probeEntry.pending) {
            return "Probing video metadata...";
        }
        return "Video preview pending.";
    }

    private static void queueProbe(File file, CachedProbe entry) {
        entry.pending = true;
        BACKGROUND_WORKER.execute(() -> {
            try {
                entry.result = probe(file);
                entry.failureMessage = entry.result == null ? "Video probe returned no result." : null;
            } catch (Exception exception) {
                entry.failureMessage = "Video probe failed: " + exception.getMessage();
            } finally {
                entry.pending = false;
            }
        });
    }

    private static void queueThumbnail(File file, VideoMetadata metadata, CachedThumbnail entry) {
        entry.pending = true;
        BACKGROUND_WORKER.execute(() -> {
            try {
                entry.rawBytes = BACKEND.extractThumbnail(file, metadata, entry.maxWidth, entry.maxHeight);
                VideoPosterCache.write(file, entry.maxWidth, entry.maxHeight, entry.rawBytes);
                entry.failureMessage = null;
            } catch (Exception exception) {
                entry.failureMessage = "Thumbnail extraction failed: " + exception.getMessage();
                entry.rawBytes = null;
            } finally {
                entry.pending = false;
            }
        });
    }

    private static void invalidateThumbnail(File file) {
        String path = file.getAbsolutePath();
        Iterator<Map.Entry<String, CachedThumbnail>> iterator = THUMBNAIL_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedThumbnail> entry = iterator.next();
            if (entry.getKey().startsWith(path + "::")) {
                iterator.remove();
            }
        }
    }

    private static void purgeExpiredEntries(long now) {
        purgeMap(PROBE_CACHE, now);
        purgeMap(THUMBNAIL_CACHE, now);
    }

    private static <T extends CacheEntry> void purgeMap(Map<String, T> cache, long now) {
        Iterator<Map.Entry<String, T>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, T> entry = iterator.next();
            if (now - entry.getValue().lastAccessTime > MediaPerformanceProfile.VIDEO_CACHE_TTL_MS && !entry.getValue().pending) {
                iterator.remove();
            }
        }
    }

    private static String cacheKey(File file) {
        return file.getAbsolutePath();
    }

    private static String thumbnailCacheKey(File file, int maxWidth, int maxHeight) {
        return file.getAbsolutePath() + "::" + Math.max(1, Math.min(maxWidth, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_WIDTH))
                + "x" + Math.max(1, Math.min(maxHeight, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_HEIGHT));
    }

    private static String sanitizeTextureKey(String raw) {
        return raw == null ? "unknown" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private abstract static class CacheEntry {
        protected volatile boolean pending;
        protected volatile long lastAccessTime = System.currentTimeMillis();
    }

    private static final class CachedProbe extends CacheEntry {
        private volatile long signature;
        private volatile VideoProbeResult result;
        private volatile String failureMessage;

        private CachedProbe(long signature) {
            this.signature = signature;
        }

        private void reset(long signature) {
            this.signature = signature;
            this.result = null;
            this.failureMessage = null;
            this.pending = false;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    private static final class CachedThumbnail extends CacheEntry {
        private volatile long signature;
        private volatile int maxWidth;
        private volatile int maxHeight;
        private volatile byte[] rawBytes;
        private volatile ImageTexture texture;
        private volatile String failureMessage;

        private CachedThumbnail(long signature, int maxWidth, int maxHeight) {
            this.signature = signature;
            this.maxWidth = Math.max(1, Math.min(maxWidth, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_WIDTH));
            this.maxHeight = Math.max(1, Math.min(maxHeight, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_HEIGHT));
        }

        private void reset(long signature, int maxWidth, int maxHeight) {
            this.signature = signature;
            this.maxWidth = Math.max(1, Math.min(maxWidth, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_WIDTH));
            this.maxHeight = Math.max(1, Math.min(maxHeight, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_HEIGHT));
            this.rawBytes = null;
            this.texture = null;
            this.failureMessage = null;
            this.pending = false;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
}
