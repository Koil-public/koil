package com.spirit.koil.api.util.file.media.image;

import com.spirit.koil.api.util.file.media.ManagedFrameTexture;
import com.spirit.koil.api.util.file.media.MediaPerformanceProfile;
import com.spirit.koil.api.util.file.media.VisualPlaybackSession;
import com.spirit.koil.api.util.file.media.VisualPlaybackState;
import net.minecraft.util.Identifier;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class AnimatedGifPlaybackSession implements VisualPlaybackSession {
    private final List<BufferedImage> frames;
    private final long[] frameStartMillis;
    private final long durationMillis;
    private final ManagedFrameTexture texture;
    private final int frameWidth;
    private final int frameHeight;
    private VisualPlaybackState state = VisualPlaybackState.READY;
    private long positionMillis;
    private long playbackAnchorSystemMillis;
    private long playbackAnchorMediaMillis;
    private int currentFrameIndex;
    private String failureReason;

    private AnimatedGifPlaybackSession(List<BufferedImage> frames, long[] frameStartMillis, long durationMillis, ManagedFrameTexture texture, int frameWidth, int frameHeight) {
        this.frames = frames;
        this.frameStartMillis = frameStartMillis;
        this.durationMillis = durationMillis;
        this.texture = texture;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        if (!frames.isEmpty()) {
            this.texture.updateBufferedImage(frames.get(0));
        }
    }

    public static AnimatedGifPlaybackSession createIfAnimated(File file, int maxWidth, int maxHeight) throws IOException {
        if (file == null || !file.isFile() || !"gif".equals(ImageFormatSupport.extensionOf(file.getName()))) {
            return null;
        }

        int targetWidth = Math.max(1, Math.min(maxWidth, MediaPerformanceProfile.ANIMATED_IMAGE_MAX_WIDTH));
        int targetHeight = Math.max(1, Math.min(maxHeight, MediaPerformanceProfile.ANIMATED_IMAGE_MAX_HEIGHT));

        try (ImageInputStream imageStream = ImageIO.createImageInputStream(file)) {
            if (imageStream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageStream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageStream, false, false);
                int frameCount = reader.getNumImages(true);
                if (frameCount <= 1) {
                    return null;
                }
                Dimension logicalSize = extractLogicalScreenSize(reader);
                int canvasWidth = logicalSize.width > 0 ? logicalSize.width : reader.getWidth(0);
                int canvasHeight = logicalSize.height > 0 ? logicalSize.height : reader.getHeight(0);
                BufferedImage composedCanvas = new BufferedImage(Math.max(1, canvasWidth), Math.max(1, canvasHeight), BufferedImage.TYPE_INT_ARGB);

                List<BufferedImage> frames = new ArrayList<>();
                long[] frameStartMillis = new long[Math.min(frameCount, MediaPerformanceProfile.ANIMATED_IMAGE_MAX_FRAMES)];
                long runningStart = 0L;
                int actualFrames = 0;
                for (int i = 0; i < frameCount && actualFrames < MediaPerformanceProfile.ANIMATED_IMAGE_MAX_FRAMES; i++) {
                    BufferedImage rawFrame = reader.read(i);
                    if (rawFrame == null) {
                        continue;
                    }
                    IIOMetadata metadata = reader.getImageMetadata(i);
                    FrameDescriptor descriptor = extractFrameDescriptor(metadata, rawFrame);
                    BufferedImage canvasBeforeDraw = copyImage(composedCanvas);
                    Graphics2D graphics = composedCanvas.createGraphics();
                    graphics.setComposite(AlphaComposite.SrcOver);
                    graphics.drawImage(rawFrame, descriptor.left(), descriptor.top(), null);
                    graphics.dispose();
                    frameStartMillis[actualFrames] = runningStart;
                    frames.add(scaleAndPad(composedCanvas, targetWidth, targetHeight));
                    runningStart += descriptor.delayMillis();
                    actualFrames++;
                    applyDisposal(composedCanvas, canvasBeforeDraw, descriptor);
                }
                if (frames.size() <= 1) {
                    return null;
                }
                long[] trimmedStarts = new long[actualFrames];
                System.arraycopy(frameStartMillis, 0, trimmedStarts, 0, actualFrames);
                ManagedFrameTexture texture = new ManagedFrameTexture(
                        "koil",
                        "animated_gif/" + file.getAbsolutePath() + "_" + targetWidth + "x" + targetHeight,
                        targetWidth,
                        targetHeight
                );
                return new AnimatedGifPlaybackSession(frames, trimmedStarts, Math.max(1L, runningStart), texture, targetWidth, targetHeight);
            } finally {
                reader.dispose();
            }
        }
    }

    @Override
    public VisualPlaybackState state() {
        return this.state;
    }

    @Override
    public void play() {
        if (this.state == VisualPlaybackState.FAILED || this.frames.isEmpty()) {
            return;
        }
        if (this.positionMillis >= this.durationMillis) {
            this.positionMillis = 0L;
            applyFrame(0);
        }
        this.playbackAnchorMediaMillis = this.positionMillis;
        this.playbackAnchorSystemMillis = System.currentTimeMillis();
        this.state = VisualPlaybackState.PLAYING;
    }

    @Override
    public void pause() {
        if (this.state != VisualPlaybackState.PLAYING) {
            return;
        }
        this.positionMillis = currentPlaybackPosition(System.currentTimeMillis());
        this.state = VisualPlaybackState.PAUSED;
    }

    @Override
    public void stop() {
        this.positionMillis = 0L;
        applyFrame(0);
        this.state = VisualPlaybackState.READY;
    }

    @Override
    public void seekTo(long targetMillis) {
        this.positionMillis = Math.max(0L, Math.min(this.durationMillis, targetMillis));
        applyFrame(frameIndexForPosition(this.positionMillis));
        if (this.state == VisualPlaybackState.PLAYING) {
            this.playbackAnchorMediaMillis = this.positionMillis;
            this.playbackAnchorSystemMillis = System.currentTimeMillis();
        } else {
            this.state = VisualPlaybackState.PAUSED;
        }
    }

    @Override
    public void update(long nowMillis) {
        if (this.state != VisualPlaybackState.PLAYING) {
            return;
        }
        this.positionMillis = currentPlaybackPosition(nowMillis);
        if (this.positionMillis >= this.durationMillis) {
            this.positionMillis = this.durationMillis <= 0L ? 0L : this.positionMillis % this.durationMillis;
            this.playbackAnchorMediaMillis = this.positionMillis;
            this.playbackAnchorSystemMillis = nowMillis;
        }
        applyFrame(frameIndexForPosition(this.positionMillis));
    }

    @Override
    public Identifier currentFrameTexture() {
        return this.texture.textureId();
    }

    @Override
    public String failureReason() {
        return this.failureReason;
    }

    @Override
    public long positionMillis() {
        return this.state == VisualPlaybackState.PLAYING ? currentPlaybackPosition(System.currentTimeMillis()) : this.positionMillis;
    }

    @Override
    public long durationMillis() {
        return this.durationMillis;
    }

    @Override
    public boolean canSeek() {
        return this.durationMillis > 0L;
    }

    @Override
    public int frameWidth() {
        return this.frameWidth;
    }

    @Override
    public int frameHeight() {
        return this.frameHeight;
    }

    @Override
    public void close() {
        this.texture.close();
        this.frames.clear();
    }

    private void applyFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= this.frames.size() || frameIndex == this.currentFrameIndex && this.currentFrameIndex < this.frames.size()) {
            return;
        }
        this.currentFrameIndex = frameIndex;
        this.texture.updateBufferedImage(this.frames.get(frameIndex));
    }

    private long currentPlaybackPosition(long nowMillis) {
        return Math.max(0L, Math.min(this.durationMillis, this.playbackAnchorMediaMillis + (nowMillis - this.playbackAnchorSystemMillis)));
    }

    private int frameIndexForPosition(long positionMillis) {
        for (int i = this.frameStartMillis.length - 1; i >= 0; i--) {
            if (positionMillis >= this.frameStartMillis[i]) {
                return i;
            }
        }
        return 0;
    }

    private static BufferedImage scaleAndPad(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage scaled = ImageDecoder.scaleToFit(source, targetWidth, targetHeight);
        BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int x = (targetWidth - scaled.getWidth()) / 2;
        int y = (targetHeight - scaled.getHeight()) / 2;
        graphics.drawImage(scaled, x, y, null);
        graphics.dispose();
        return canvas;
    }

    private static void applyDisposal(BufferedImage composedCanvas, BufferedImage canvasBeforeDraw, FrameDescriptor descriptor) {
        String disposalMethod = descriptor.disposalMethod();
        if ("restoreToBackgroundColor".equals(disposalMethod)) {
            Graphics2D graphics = composedCanvas.createGraphics();
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(descriptor.left(), descriptor.top(), descriptor.width(), descriptor.height());
            graphics.dispose();
            return;
        }
        if ("restoreToPrevious".equals(disposalMethod) && canvasBeforeDraw != null) {
            Graphics2D graphics = composedCanvas.createGraphics();
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(canvasBeforeDraw, 0, 0, null);
            graphics.dispose();
        }
    }

    private static FrameDescriptor extractFrameDescriptor(IIOMetadata metadata, BufferedImage rawFrame) {
        if (metadata == null) {
            return new FrameDescriptor(0, 0, rawFrame.getWidth(), rawFrame.getHeight(), 100L, "none");
        }
        try {
            Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
            NodeList children = root.getChildNodes();
            int left = 0;
            int top = 0;
            int width = rawFrame.getWidth();
            int height = rawFrame.getHeight();
            long delayMillis = 100L;
            String disposalMethod = "none";
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if ("GraphicControlExtension".equals(child.getNodeName())) {
                    NamedNodeMap attributes = child.getAttributes();
                    Node delayTime = attributes.getNamedItem("delayTime");
                    if (delayTime != null) {
                        int hundredths = Integer.parseInt(delayTime.getNodeValue());
                        delayMillis = Math.max(MediaPerformanceProfile.ANIMATED_IMAGE_MIN_FRAME_DELAY_MS, hundredths * 10L);
                    }
                    Node disposal = attributes.getNamedItem("disposalMethod");
                    if (disposal != null && !disposal.getNodeValue().isBlank()) {
                        disposalMethod = disposal.getNodeValue();
                    }
                }
                if ("ImageDescriptor".equals(child.getNodeName())) {
                    NamedNodeMap attributes = child.getAttributes();
                    left = parseIntAttribute(attributes, "imageLeftPosition", 0);
                    top = parseIntAttribute(attributes, "imageTopPosition", 0);
                    width = parseIntAttribute(attributes, "imageWidth", width);
                    height = parseIntAttribute(attributes, "imageHeight", height);
                }
            }
            return new FrameDescriptor(left, top, width, height, delayMillis, disposalMethod);
        } catch (Exception ignored) {
            return new FrameDescriptor(0, 0, rawFrame.getWidth(), rawFrame.getHeight(), 100L, "none");
        }
    }

    private static Dimension extractLogicalScreenSize(ImageReader reader) {
        try {
            IIOMetadata streamMetadata = reader.getStreamMetadata();
            if (streamMetadata == null) {
                return new Dimension(0, 0);
            }
            Node root = streamMetadata.getAsTree("javax_imageio_gif_stream_1.0");
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!"LogicalScreenDescriptor".equals(child.getNodeName())) {
                    continue;
                }
                NamedNodeMap attributes = child.getAttributes();
                int width = parseIntAttribute(attributes, "logicalScreenWidth", 0);
                int height = parseIntAttribute(attributes, "logicalScreenHeight", 0);
                return new Dimension(width, height);
            }
        } catch (Exception ignored) {
        }
        return new Dimension(0, 0);
    }

    private static int parseIntAttribute(NamedNodeMap attributes, String attributeName, int fallback) {
        if (attributes == null) {
            return fallback;
        }
        Node node = attributes.getNamedItem(attributeName);
        if (node == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(node.getNodeValue());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static BufferedImage copyImage(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private record FrameDescriptor(int left, int top, int width, int height, long delayMillis, String disposalMethod) {
    }
}
