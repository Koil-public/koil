package com.spirit.koil.api.util.file.media.image;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ImageTextureService {
    private static final long BASE_UNUSED_TEXTURE_TTL_MS = 45_000L;
    private static final long LARGE_TEXTURE_TTL_MS = 20_000L;
    private static final long HUGE_TEXTURE_TTL_MS = 10_000L;
    private static final long VISIBLE_TEXTURE_GRACE_MS = 1_500L;
    private static final long PURGE_INTERVAL_MS = 1_500L;
    private static final int MAX_CACHE_ENTRIES = 384;
    private static final long MAX_CACHE_BYTES = 256L * 1024L * 1024L;
    private static final long LARGE_TEXTURE_BYTES = 4L * 1024L * 1024L;
    private static final long HUGE_TEXTURE_BYTES = 16L * 1024L * 1024L;

    private static final Map<TextureCacheKey, CachedTexture> FILE_TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, CachedTexture> MEMORY_TEXTURES = new ConcurrentHashMap<>();
    private static final AtomicLong VISIBILITY_EPOCH = new AtomicLong(1L);

    private static volatile long lastPurgeTime = 0L;
    private static volatile long totalEstimatedBytes = 0L;
    private static volatile long visibleEpoch = 1L;

    private ImageTextureService() {
    }

    public static void beginVisibilityFrame() {
        visibleEpoch = VISIBILITY_EPOCH.incrementAndGet();
    }

    public static ImageTexture loadTexture(File file, String namespace, String uniqueKey) throws IOException {
        return loadScaledTexture(file, namespace, uniqueKey, -1, -1, false);
    }

    public static ImageTexture loadPersistentTexture(File file, String namespace, String uniqueKey) throws IOException {
        return loadScaledTexture(file, namespace, uniqueKey, -1, -1, true);
    }

    public static ImageTexture loadScaledTexture(File file, String namespace, String uniqueKey, int maxWidth, int maxHeight) throws IOException {
        return loadScaledTexture(file, namespace, uniqueKey, maxWidth, maxHeight, false);
    }

    public static ImageTexture loadPersistentScaledTexture(File file, String namespace, String uniqueKey, int maxWidth, int maxHeight) throws IOException {
        return loadScaledTexture(file, namespace, uniqueKey, maxWidth, maxHeight, true);
    }

    public static ImageTexture loadScaledTexture(File file, String namespace, String uniqueKey, int maxWidth, int maxHeight, boolean persistent) throws IOException {
        if (file == null || !file.exists()) {
            return null;
        }

        MinecraftClient client = readyClient();
        if (client == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        purgeUnusedTextures(client, now, false);

        TextureCacheKey cacheKey = new TextureCacheKey(file.getAbsolutePath(), Math.max(-1, maxWidth), Math.max(-1, maxHeight));
        long signature = computeFileSignature(file);
        CachedTexture cached = FILE_TEXTURES.get(cacheKey);
        if (cached != null && cached.signature == signature) {
            if (persistent) {
                cached.setPersistent(true);
            }
            cached.markVisible(now, visibleEpoch);
            return cached.asTexture();
        }

        BufferedImage image = ImageDecoder.decodeFile(file);
        if (image == null) {
            invalidateFile(file, client);
            return null;
        }

        BufferedImage finalImage = maxWidth > 0 && maxHeight > 0 ? ImageDecoder.scaleToFit(image, maxWidth, maxHeight) : image;
        NativeImageBackedTexture texture = ImageDecoder.createTexture(finalImage);
        if (texture == null) {
            invalidateFile(file, client);
            return null;
        }

        Identifier textureId = new Identifier(namespace, sanitizeTextureKey(uniqueKey) + variantSuffix(maxWidth, maxHeight));
        CachedTexture replacement = new CachedTexture(
                textureId,
                texture,
                finalImage.getWidth(),
                finalImage.getHeight(),
                signature,
                now,
                persistent
        );
        replacement.markVisible(now, visibleEpoch);

        CachedTexture previous = FILE_TEXTURES.put(cacheKey, replacement);
        if (previous != null) {
            totalEstimatedBytes -= previous.estimatedBytes;
            closeTexture(client, previous);
        }
        registerTexture(client, textureId, texture);
        totalEstimatedBytes += replacement.estimatedBytes;

        purgeUnusedTextures(client, now, true);
        return replacement.asTexture();
    }

    public static ImageTexture loadTexture(byte[] bytes, String namespace, String uniqueKey, String sourceDescription) throws IOException {
        return loadTexture(bytes, namespace, uniqueKey, sourceDescription, false);
    }

    public static ImageTexture loadPersistentTexture(byte[] bytes, String namespace, String uniqueKey, String sourceDescription) throws IOException {
        return loadTexture(bytes, namespace, uniqueKey, sourceDescription, true);
    }

    public static ImageTexture loadTexture(byte[] bytes, String namespace, String uniqueKey, String sourceDescription, boolean persistent) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        MinecraftClient client = readyClient();
        if (client == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        purgeUnusedTextures(client, now, false);

        String sanitizedKey = namespace + ":" + sanitizeTextureKey(uniqueKey);
        long signature = computeByteSignature(bytes);
        CachedTexture cached = MEMORY_TEXTURES.get(sanitizedKey);
        if (cached != null && cached.signature == signature) {
            if (persistent) {
                cached.setPersistent(true);
            }
            cached.markVisible(now, visibleEpoch);
            return cached.asTexture();
        }

        BufferedImage image = ImageDecoder.decodeBytes(bytes, sourceDescription);
        if (image == null) {
            invalidateMemory(namespace, uniqueKey, client);
            return null;
        }

        NativeImageBackedTexture texture = ImageDecoder.createTexture(image);
        if (texture == null) {
            invalidateMemory(namespace, uniqueKey, client);
            return null;
        }

        Identifier textureId = new Identifier(namespace, sanitizeTextureKey(uniqueKey));
        CachedTexture replacement = new CachedTexture(
                textureId,
                texture,
                image.getWidth(),
                image.getHeight(),
                signature,
                now,
                persistent
        );
        replacement.markVisible(now, visibleEpoch);

        CachedTexture previous = MEMORY_TEXTURES.put(sanitizedKey, replacement);
        if (previous != null) {
            totalEstimatedBytes -= previous.estimatedBytes;
            closeTexture(client, previous);
        }
        registerTexture(client, textureId, texture);
        totalEstimatedBytes += replacement.estimatedBytes;

        purgeUnusedTextures(client, now, true);
        return replacement.asTexture();
    }

    public static void markFileVisible(File file) {
        if (file == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String absolutePath = file.getAbsolutePath();
        for (Map.Entry<TextureCacheKey, CachedTexture> entry : FILE_TEXTURES.entrySet()) {
            if (entry.getKey().sourcePath.equals(absolutePath)) {
                entry.getValue().markVisible(now, visibleEpoch);
            }
        }
    }

    public static void markFilePersistent(File file) {
        if (file == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String absolutePath = file.getAbsolutePath();
        for (Map.Entry<TextureCacheKey, CachedTexture> entry : FILE_TEXTURES.entrySet()) {
            if (entry.getKey().sourcePath.equals(absolutePath)) {
                entry.getValue().setPersistent(true);
                entry.getValue().markVisible(now, visibleEpoch);
            }
        }
    }

    public static void markMemoryVisible(String namespace, String uniqueKey) {
        if (namespace == null || uniqueKey == null) {
            return;
        }

        CachedTexture cached = MEMORY_TEXTURES.get(namespace + ":" + sanitizeTextureKey(uniqueKey));
        if (cached != null) {
            cached.markVisible(System.currentTimeMillis(), visibleEpoch);
        }
    }

    public static void markMemoryPersistent(String namespace, String uniqueKey) {
        if (namespace == null || uniqueKey == null) {
            return;
        }

        CachedTexture cached = MEMORY_TEXTURES.get(namespace + ":" + sanitizeTextureKey(uniqueKey));
        if (cached != null) {
            cached.setPersistent(true);
            cached.markVisible(System.currentTimeMillis(), visibleEpoch);
        }
    }

    public static void markTextureVisible(ImageTexture texture) {
        if (texture == null || texture.textureId() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        markVisibleByIdentifier(FILE_TEXTURES, texture.textureId(), now);
        markVisibleByIdentifier(MEMORY_TEXTURES, texture.textureId(), now);
    }

    public static void markTexturePersistent(ImageTexture texture) {
        if (texture == null || texture.textureId() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        markPersistentByIdentifier(FILE_TEXTURES, texture.textureId(), now);
        markPersistentByIdentifier(MEMORY_TEXTURES, texture.textureId(), now);
    }

    public static void invalidateFile(File file) {
        MinecraftClient client = readyClient();
        if (client == null || file == null) {
            return;
        }
        invalidateFile(file, client);
    }

    public static void invalidateMemory(String namespace, String uniqueKey) {
        MinecraftClient client = readyClient();
        if (client == null || namespace == null || uniqueKey == null) {
            return;
        }
        invalidateMemory(namespace, uniqueKey, client);
    }

    public static void clearAll() {
        MinecraftClient client = readyClient();
        if (client == null) {
            FILE_TEXTURES.clear();
            MEMORY_TEXTURES.clear();
            totalEstimatedBytes = 0L;
            return;
        }

        for (CachedTexture texture : FILE_TEXTURES.values()) {
            closeTexture(client, texture);
        }
        for (CachedTexture texture : MEMORY_TEXTURES.values()) {
            closeTexture(client, texture);
        }

        FILE_TEXTURES.clear();
        MEMORY_TEXTURES.clear();
        totalEstimatedBytes = 0L;
    }

    public static int getCachedTextureCount() {
        return FILE_TEXTURES.size() + MEMORY_TEXTURES.size();
    }

    public static long getEstimatedCacheBytes() {
        return totalEstimatedBytes;
    }

    public static void purgeNow() {
        MinecraftClient client = readyClient();
        if (client == null) {
            return;
        }
        purgeUnusedTextures(client, System.currentTimeMillis(), true);
    }

    private static void invalidateFile(File file, MinecraftClient client) {
        String absolutePath = file.getAbsolutePath();
        for (Map.Entry<TextureCacheKey, CachedTexture> entry : FILE_TEXTURES.entrySet()) {
            if (entry.getKey().sourcePath.equals(absolutePath)) {
                if (FILE_TEXTURES.remove(entry.getKey(), entry.getValue())) {
                    totalEstimatedBytes -= entry.getValue().estimatedBytes;
                    closeTexture(client, entry.getValue());
                }
            }
        }
    }

    private static void invalidateMemory(String namespace, String uniqueKey, MinecraftClient client) {
        String cacheKey = namespace + ":" + sanitizeTextureKey(uniqueKey);
        CachedTexture removed = MEMORY_TEXTURES.remove(cacheKey);
        if (removed != null) {
            totalEstimatedBytes -= removed.estimatedBytes;
            closeTexture(client, removed);
        }
    }

    private static void purgeUnusedTextures(MinecraftClient client, long now, boolean force) {
        if (!force && now - lastPurgeTime < PURGE_INTERVAL_MS) {
            return;
        }

        lastPurgeTime = now;
        purgeMap(FILE_TEXTURES, client, now);
        purgeMap(MEMORY_TEXTURES, client, now);

        int totalEntries = FILE_TEXTURES.size() + MEMORY_TEXTURES.size();
        if (totalEntries > MAX_CACHE_ENTRIES || totalEstimatedBytes > MAX_CACHE_BYTES) {
            pressurePurge(client, now);
        }

        if (totalEstimatedBytes < 0L) {
            totalEstimatedBytes = 0L;
        }
    }

    private static <K> void purgeMap(Map<K, CachedTexture> textures, MinecraftClient client, long now) {
        for (Map.Entry<K, CachedTexture> entry : textures.entrySet()) {
            CachedTexture cached = entry.getValue();
            if (cached.shouldPurge(now, visibleEpoch)) {
                if (textures.remove(entry.getKey(), cached)) {
                    totalEstimatedBytes -= cached.estimatedBytes;
                    closeTexture(client, cached);
                }
            }
        }
    }

    private static void pressurePurge(MinecraftClient client, long now) {
        List<Map.Entry<TextureCacheKey, CachedTexture>> fileEntries = new ArrayList<>(FILE_TEXTURES.entrySet());
        List<Map.Entry<String, CachedTexture>> memoryEntries = new ArrayList<>(MEMORY_TEXTURES.entrySet());
        List<PurgeCandidate> candidates = new ArrayList<>(fileEntries.size() + memoryEntries.size());

        for (Map.Entry<TextureCacheKey, CachedTexture> entry : fileEntries) {
            if (!entry.getValue().persistent) {
                candidates.add(new PurgeCandidate(entry.getKey(), null, entry.getValue()));
            }
        }

        for (Map.Entry<String, CachedTexture> entry : memoryEntries) {
            if (!entry.getValue().persistent) {
                candidates.add(new PurgeCandidate(null, entry.getKey(), entry.getValue()));
            }
        }

        candidates.sort(Comparator.comparingLong(PurgeCandidate::score));

        for (PurgeCandidate candidate : candidates) {
            if (FILE_TEXTURES.size() + MEMORY_TEXTURES.size() <= MAX_CACHE_ENTRIES && totalEstimatedBytes <= MAX_CACHE_BYTES) {
                break;
            }

            CachedTexture cached = candidate.texture;
            if (!cached.canPressurePurge(now, visibleEpoch)) {
                continue;
            }

            boolean removed = false;
            if (candidate.fileKey != null) {
                removed = FILE_TEXTURES.remove(candidate.fileKey, cached);
            } else if (candidate.memoryKey != null) {
                removed = MEMORY_TEXTURES.remove(candidate.memoryKey, cached);
            }

            if (removed) {
                totalEstimatedBytes -= cached.estimatedBytes;
                closeTexture(client, cached);
            }
        }
    }

    private static <K> void markVisibleByIdentifier(Map<K, CachedTexture> textures, Identifier textureId, long now) {
        for (CachedTexture cached : textures.values()) {
            if (cached.textureId.equals(textureId)) {
                cached.markVisible(now, visibleEpoch);
            }
        }
    }

    private static <K> void markPersistentByIdentifier(Map<K, CachedTexture> textures, Identifier textureId, long now) {
        for (CachedTexture cached : textures.values()) {
            if (cached.textureId.equals(textureId)) {
                cached.setPersistent(true);
                cached.markVisible(now, visibleEpoch);
            }
        }
    }

    private static void registerTexture(MinecraftClient client, Identifier textureId, NativeImageBackedTexture texture) {
        client.getTextureManager().registerTexture(textureId, texture);
    }

    private static void closeTexture(MinecraftClient client, CachedTexture texture) {
        if (texture == null) {
            return;
        }

        client.getTextureManager().destroyTexture(texture.textureId);
        texture.texture.close();
    }

    private static MinecraftClient readyClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getTextureManager() != null ? client : null;
    }

    private static long computeByteSignature(byte[] bytes) {
        long signature = 1125899906842597L;
        for (byte value : bytes) {
            signature = 31L * signature + value;
        }
        return signature;
    }

    private static long computeFileSignature(File file) {
        return (file.lastModified() * 31L) ^ file.length();
    }

    private static String sanitizeTextureKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.toLowerCase().replaceAll("[^a-z0-9/._-]", "_");
    }

    private static String variantSuffix(int maxWidth, int maxHeight) {
        if (maxWidth <= 0 || maxHeight <= 0) {
            return "";
        }
        return "_" + maxWidth + "x" + maxHeight;
    }

    private record TextureCacheKey(String sourcePath, int maxWidth, int maxHeight) {
    }

    private record PurgeCandidate(TextureCacheKey fileKey, String memoryKey, CachedTexture texture) {
        private long score() {
            long agePenalty = texture.lastAccessTime;
            long sizeBonus = texture.estimatedBytes / 1024L;
            long visibilityPenalty = texture.lastVisibleEpoch;
            return agePenalty - sizeBonus + visibilityPenalty;
        }
    }

    private static final class CachedTexture {
        private final Identifier textureId;
        private final NativeImageBackedTexture texture;
        private final int width;
        private final int height;
        private final long signature;
        private final long estimatedBytes;
        private volatile long lastAccessTime;
        private volatile long visibleUntilTime;
        private volatile long lastVisibleEpoch;
        private volatile boolean persistent;

        private CachedTexture(Identifier textureId, NativeImageBackedTexture texture, int width, int height, long signature, long lastAccessTime, boolean persistent) {
            this.textureId = textureId;
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.signature = signature;
            this.estimatedBytes = Math.max(1L, (long) width * (long) height * 4L);
            this.lastAccessTime = lastAccessTime;
            this.visibleUntilTime = 0L;
            this.lastVisibleEpoch = 0L;
            this.persistent = persistent;
        }

        private void markVisible(long now, long epoch) {
            this.lastAccessTime = now;
            this.lastVisibleEpoch = epoch;
            long nextVisibleUntil = now + VISIBLE_TEXTURE_GRACE_MS;
            if (nextVisibleUntil > this.visibleUntilTime) {
                this.visibleUntilTime = nextVisibleUntil;
            }
        }

        private void setPersistent(boolean persistent) {
            this.persistent = persistent;
        }

        private boolean shouldPurge(long now, long currentEpoch) {
            if (persistent) {
                return false;
            }
            if (now <= visibleUntilTime) {
                return false;
            }
            if (lastVisibleEpoch >= currentEpoch - 1L) {
                return false;
            }
            return now - lastAccessTime > ttlForSize();
        }

        private boolean canPressurePurge(long now, long currentEpoch) {
            if (persistent) {
                return false;
            }
            if (now <= visibleUntilTime) {
                return false;
            }
            return lastVisibleEpoch < currentEpoch;
        }

        private long ttlForSize() {
            if (estimatedBytes >= HUGE_TEXTURE_BYTES) {
                return HUGE_TEXTURE_TTL_MS;
            }
            if (estimatedBytes >= LARGE_TEXTURE_BYTES) {
                return LARGE_TEXTURE_TTL_MS;
            }
            return BASE_UNUSED_TEXTURE_TTL_MS;
        }

        private ImageTexture asTexture() {
            return new ImageTexture(textureId, width, height);
        }
    }
}
