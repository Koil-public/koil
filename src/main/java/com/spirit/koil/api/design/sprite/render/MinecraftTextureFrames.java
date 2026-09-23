package com.spirit.koil.api.design.sprite.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Detached-world-safe texture frame metadata reader for scene renderers. */
public final class MinecraftTextureFrames {
    public record Frame(int index, int ticks) { }
    public record View(int x, int y, int width, int height, int textureWidth, int textureHeight,
                       int nextX, int nextY, float interpolation, boolean interpolate) { }
    private record Info(int imageWidth, int imageHeight, int frameWidth, int frameHeight,
                        List<Frame> frames, boolean interpolate) { }

    private static final Info MISSING = new Info(16, 16, 16, 16, List.of(new Frame(0, 1)), false);
    private static final Map<Identifier, Info> CACHE = new HashMap<>();

    private MinecraftTextureFrames() { }

    public static synchronized void clear() { CACHE.clear(); }

    public static synchronized View frame(Identifier texture, float ageSeconds) {
        Info info = CACHE.computeIfAbsent(texture, MinecraftTextureFrames::load);
        List<Frame> frames = info.frames();
        if (frames.isEmpty()) return new View(0, 0, info.frameWidth(), info.frameHeight(),
                info.imageWidth(), info.imageHeight(), 0, 0, 0.0F, false);
        if (frames.size() == 1) return viewFor(info, 0, 0.0F);

        float ticks = Math.max(0.0F, ageSeconds * 20.0F);
        int total = 0;
        for (Frame frame : frames) total += Math.max(1, frame.ticks());
        float cursor = total <= 0 ? 0.0F : ticks % total;
        int start = 0;
        int index = 0;
        for (int i = 0; i < frames.size(); i++) {
            int duration = Math.max(1, frames.get(i).ticks());
            if (cursor < start + duration) { index = i; break; }
            start += duration;
            index = i;
        }
        float local = (cursor - start) / Math.max(1.0F, frames.get(index).ticks());
        return viewFor(info, index, Math.max(0.0F, Math.min(1.0F, local)));
    }


    /**
     * Normalizes a larger animated frame (for example vanilla water_flow/lava_flow)
     * to the logical tile dimensions of a reference still texture. Resource packs
     * keep their own resolution because the ratio is derived from the loaded assets.
     */
    public static View normalizeToReference(View source, View reference) {
        if (source == null) return reference;
        if (reference == null) return source;
        int width = Math.max(1, Math.min(source.width(), reference.width()));
        int height = Math.max(1, Math.min(source.height(), reference.height()));
        int offsetX = Math.max(0, (source.width() - width) / 2);
        int offsetY = Math.max(0, (source.height() - height) / 2);
        return new View(
                source.x() + offsetX, source.y() + offsetY, width, height,
                source.textureWidth(), source.textureHeight(),
                source.nextX() + offsetX, source.nextY() + offsetY,
                source.interpolation(), source.interpolate());
    }
    private static View viewFor(Info info, int frameListIndex, float interpolation) {
        int columns = Math.max(1, info.imageWidth() / info.frameWidth());
        Frame frame = info.frames().get(frameListIndex);
        Frame next = info.frames().get((frameListIndex + 1) % info.frames().size());
        int x = (frame.index() % columns) * info.frameWidth();
        int y = (frame.index() / columns) * info.frameHeight();
        int nx = (next.index() % columns) * info.frameWidth();
        int ny = (next.index() / columns) * info.frameHeight();
        return new View(x, y, info.frameWidth(), info.frameHeight(), info.imageWidth(), info.imageHeight(),
                nx, ny, interpolation, info.interpolate() && info.frames().size() > 1);
    }

    private static Info load(Identifier texture) {
        if (texture == null) return MISSING;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) return MISSING;
        Optional<Resource> optional = client.getResourceManager().getResource(texture);
        if (optional.isEmpty()) return MISSING;
        Resource resource = optional.get();
        int imageWidth;
        int imageHeight;
        try (InputStream stream = resource.getInputStream(); NativeImage image = NativeImage.read(stream)) {
            imageWidth = Math.max(1, image.getWidth());
            imageHeight = Math.max(1, image.getHeight());
        } catch (Exception ignored) {
            return MISSING;
        }

        int frameWidth = imageWidth;
        int frameHeight = imageHeight;
        int defaultTicks = 1;
        boolean interpolate = false;
        List<Frame> frames = new ArrayList<>();
        try {
            Optional<AnimationResourceMetadata> animation = resource.getMetadata().decode(AnimationResourceMetadata.READER);
            if (animation.isPresent()) {
                AnimationResourceMetadata metadata = animation.get();
                SpriteDimensions dimensions = metadata.getSize(imageWidth, imageHeight);
                frameWidth = Math.max(1, dimensions.width());
                frameHeight = Math.max(1, dimensions.height());
                defaultTicks = Math.max(1, metadata.getDefaultFrameTime());
                interpolate = metadata.shouldInterpolate();
                metadata.forEachFrame((index, time) -> frames.add(new Frame(Math.max(0, index), Math.max(1, time))));
            }
        } catch (Exception ignored) { }

        int available = Math.max(1, (imageWidth / frameWidth) * (imageHeight / frameHeight));
        if (frames.isEmpty()) {
            for (int i = 0; i < available; i++) frames.add(new Frame(i, defaultTicks));
        }
        frames.removeIf(frame -> frame.index() < 0 || frame.index() >= available);
        if (frames.isEmpty()) frames.add(new Frame(0, defaultTicks));
        return new Info(imageWidth, imageHeight, frameWidth, frameHeight, List.copyOf(frames), interpolate);
    }
}
