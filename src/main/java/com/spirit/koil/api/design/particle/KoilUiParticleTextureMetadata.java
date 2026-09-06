package com.spirit.koil.api.design.particle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the real dimensions and animation layout of a texture from the
 * currently-active Minecraft resource manager. This keeps UI rendering,
 * collision bounds and resource-pack overrides synchronized.
 */
public final class KoilUiParticleTextureMetadata {
    private static final Map<Identifier, TextureInfo> CACHE = new HashMap<>();
    private static final TextureInfo MISSING = new TextureInfo(16, 16, 16, 16,
            List.of(new Frame(0, 1)), false, false);

    private KoilUiParticleTextureMetadata() { }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static synchronized TextureInfo resolve(Identifier texture) {
        if (texture == null) return MISSING;
        TextureInfo cached = CACHE.get(texture);
        if (cached != null) return cached;
        TextureInfo loaded = load(texture);
        CACHE.put(texture, loaded);
        return loaded;
    }

    private static TextureInfo load(Identifier texture) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) return MISSING;
        Optional<Resource> optional = client.getResourceManager().getResource(texture);
        if (optional.isEmpty()) return MISSING;

        Resource resource = optional.get();
        int imageWidth = 16;
        int imageHeight = 16;
        try (InputStream stream = resource.getInputStream(); NativeImage image = NativeImage.read(stream)) {
            imageWidth = Math.max(1, image.getWidth());
            imageHeight = Math.max(1, image.getHeight());
        } catch (Exception ignored) {
            return MISSING;
        }

        int frameWidth = imageWidth;
        int frameHeight = imageHeight;
        int defaultFrameTime = 1;
        boolean interpolate = false;
        List<Frame> frames = new ArrayList<>();
        boolean animated = false;

        try {
            Optional<AnimationResourceMetadata> animation = resource.getMetadata().decode(AnimationResourceMetadata.READER);
            if (animation.isPresent()) {
                AnimationResourceMetadata metadata = animation.get();
                SpriteDimensions dimensions = metadata.getSize(imageWidth, imageHeight);
                frameWidth = Math.max(1, dimensions.width());
                frameHeight = Math.max(1, dimensions.height());
                defaultFrameTime = Math.max(1, metadata.getDefaultFrameTime());
                interpolate = metadata.shouldInterpolate();
                metadata.forEachFrame((index, time) -> frames.add(new Frame(Math.max(0, index), Math.max(1, time))));
                animated = true;
            }
        } catch (Exception ignored) {
            // Static textures and malformed metadata safely fall back to the
            // whole texture rather than rendering Minecraft's missing texture.
        }

        int columns = Math.max(1, imageWidth / frameWidth);
        int rows = Math.max(1, imageHeight / frameHeight);
        int availableFrames = Math.max(1, columns * rows);
        if (frames.isEmpty()) {
            if (animated && availableFrames > 1) {
                for (int i = 0; i < availableFrames; i++) frames.add(new Frame(i, defaultFrameTime));
            } else {
                frames.add(new Frame(0, defaultFrameTime));
            }
        }

        List<Frame> valid = new ArrayList<>();
        for (Frame frame : frames) {
            if (frame.index() >= 0 && frame.index() < availableFrames) valid.add(frame);
        }
        if (valid.isEmpty()) valid.add(new Frame(0, defaultFrameTime));

        return new TextureInfo(imageWidth, imageHeight, frameWidth, frameHeight,
                Collections.unmodifiableList(valid), interpolate, animated && valid.size() > 1);
    }

    public record Frame(int index, int timeTicks) { }

    public record FrameView(
            int sourceX,
            int sourceY,
            int sourceWidth,
            int sourceHeight,
            int textureWidth,
            int textureHeight,
            int frameIndex,
            int nextSourceX,
            int nextSourceY,
            float interpolation,
            boolean interpolate
    ) { }

    public record TextureInfo(
            int imageWidth,
            int imageHeight,
            int frameWidth,
            int frameHeight,
            List<Frame> frames,
            boolean interpolate,
            boolean animated
    ) {
        public float aspectRatio() {
            return frameHeight <= 0 ? 1.0F : frameWidth / (float) frameHeight;
        }

        public FrameView frameAt(float ageSeconds, float animationSpeed, boolean loop, int variant) {
            if (frames == null || frames.isEmpty()) {
                return new FrameView(0, 0, frameWidth, frameHeight, imageWidth, imageHeight,
                        0, 0, 0, 0.0F, false);
            }
            if (frames.size() == 1) {
                int index = frames.get(0).index();
                int sx = (index % Math.max(1, imageWidth / frameWidth)) * frameWidth;
                int sy = (index / Math.max(1, imageWidth / frameWidth)) * frameHeight;
                return new FrameView(sx, sy, frameWidth, frameHeight, imageWidth, imageHeight,
                        index, sx, sy, 0.0F, false);
            }

            float ticks = Math.max(0.0F, ageSeconds * 20.0F * Math.max(0.01F, animationSpeed));
            int totalTicks = 0;
            for (Frame frame : frames) totalTicks += Math.max(1, frame.timeTicks());
            if (totalTicks <= 0) totalTicks = frames.size();

            float cursor;
            if (loop) {
                cursor = ticks % totalTicks;
            } else {
                cursor = Math.min(Math.max(0.0F, totalTicks - 0.0001F), ticks);
            }
            int current = 0;
            int frameStart = 0;
            for (int i = 0; i < frames.size(); i++) {
                int duration = Math.max(1, frames.get(i).timeTicks());
                if (cursor < frameStart + duration) {
                    current = i;
                    break;
                }
                frameStart += duration;
                current = i;
            }
            Frame frame = frames.get(current);
            int next = loop ? (current + 1) % frames.size() : Math.min(frames.size() - 1, current + 1);
            Frame nextFrame = frames.get(next);
            int columns = Math.max(1, imageWidth / frameWidth);
            int sx = (frame.index() % columns) * frameWidth;
            int sy = (frame.index() / columns) * frameHeight;
            int nsx = (nextFrame.index() % columns) * frameWidth;
            int nsy = (nextFrame.index() / columns) * frameHeight;
            float local = (cursor - frameStart) / Math.max(1.0F, frame.timeTicks());
            return new FrameView(sx, sy, frameWidth, frameHeight, imageWidth, imageHeight,
                    frame.index(), nsx, nsy, Math.max(0.0F, Math.min(1.0F, local)), interpolate && next != current);
        }
    }
}
