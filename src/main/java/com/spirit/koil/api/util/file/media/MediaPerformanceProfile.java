package com.spirit.koil.api.util.file.media;

public final class MediaPerformanceProfile {
    public static final int VIDEO_THUMBNAIL_MAX_WIDTH = 320;
    public static final int VIDEO_THUMBNAIL_MAX_HEIGHT = 180;
    public static final int VIDEO_BACKGROUND_THREADS = 1;
    public static final long VIDEO_CACHE_TTL_MS = 120_000L;
    public static final long VIDEO_POSTER_DISK_CACHE_TTL_MS = 604_800_000L;
    public static final long VIDEO_PROBE_TIMEOUT_MS = 15_000L;
    public static final long VIDEO_THUMBNAIL_TIMEOUT_MS = 5_000L;
    public static final int VIDEO_PLAYBACK_MAX_WIDTH = 384;
    public static final int VIDEO_PLAYBACK_MAX_HEIGHT = 216;
    public static final double VIDEO_PLAYBACK_DEFAULT_FPS = 12.0D;
    public static final double VIDEO_PLAYBACK_MAX_FPS = 15.0D;
    public static final float VIDEO_AUDIO_SAMPLE_RATE = 44_100.0F;
    public static final int VIDEO_AUDIO_CHANNELS = 2;
    public static final int VIDEO_AUDIO_SAMPLE_BYTES = 2;
    public static final int VIDEO_AUDIO_STREAM_BUFFER_BYTES = 16_384;
    public static final int ANIMATED_IMAGE_MAX_WIDTH = 384;
    public static final int ANIMATED_IMAGE_MAX_HEIGHT = 216;
    public static final int ANIMATED_IMAGE_MAX_FRAMES = 240;
    public static final long ANIMATED_IMAGE_MIN_FRAME_DELAY_MS = 40L;

    private MediaPerformanceProfile() {
    }
}
