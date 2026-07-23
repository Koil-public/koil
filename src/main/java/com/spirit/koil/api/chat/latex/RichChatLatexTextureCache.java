package com.spirit.koil.api.chat.latex;

import com.spirit.client.gui.skin.SkinTextureTools;
import com.spirit.koil.api.chat.RichChatBodyWrapFormatter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class RichChatLatexTextureCache {
    private static final String START = "\uE730";
    private static final String END = "\uE731";
    private static final int MAX_SOURCE_CHARS = 4096;
    private static final int RASTER_SCALE = 3;
    private static final int MAX_INLINE_DISPLAY_WIDTH = 220;
    private static final int MAX_INLINE_DISPLAY_HEIGHT = 18;
    private static final int MAX_TALL_INLINE_DISPLAY_HEIGHT = 24;
    private static final int MAX_DISPLAY_WIDTH = 512;
    private static final int MAX_DISPLAY_HEIGHT = 160;
    private static final int LONG_BLOCK_SOURCE_CHARS = 82;
    private static final double MIN_LONG_BLOCK_EXTRA_SCALE = 0.96D;
    private static final double MIN_INLINE_WIDTH_SCALE = 0.82D;
    private static final double MIN_TALL_INLINE_WIDTH_SCALE = 0.78D;
    private static final int CHAT_SAFE_PADDING = 8;
    private static final int FALLBACK_CHAT_WIDTH = 320;
    private static final int FALLBACK_INLINE_WIDTH = 64;
    private static final int FALLBACK_INLINE_HEIGHT = 12;
    private static final int FALLBACK_TALL_WIDTH = 140;
    private static final int FALLBACK_TALL_HEIGHT = 22;
    private static final int FALLBACK_BLOCK_WIDTH = 220;
    private static final int FALLBACK_BLOCK_HEIGHT = 48;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<String, Entry> BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Entry> BY_ID = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Koil Rich Chat LaTeX Renderer");
            thread.setDaemon(true);
            return thread;
        }
    });

    private RichChatLatexTextureCache() {
    }

    public static String marker(String source, Mode mode) {
        return marker(source, mode, 0);
    }

    public static String marker(String source, Mode mode, int row) {
        return marker(source, mode, row, maxDisplayWidth(mode));
    }

    public static String marker(String source, Mode mode, int row, int maxWidth) {
        Entry entry = entry(source, mode, maxWidth);
        return START + entry.id + ":" + Math.max(0, row) + END;
    }

    public static int layoutAdvance(String source, Mode mode) {
        return entry(source, mode).advanceWidth();
    }

    public static int layoutHeight(String source, Mode mode) {
        return entry(source, mode).height();
    }

    public static int reservedBlankLines(String source, Mode mode, int lineHeight) {
        return reservedBlankLines(source, mode, lineHeight, maxDisplayWidth(mode));
    }

    public static int reservedBlankLines(String source, Mode mode, int lineHeight, int maxWidth) {
        int safeLineHeight = Math.max(1, lineHeight);
        int height = Math.max(1, entry(source, mode, maxWidth).height());
        int safetyPixels = mode == Mode.BLOCK || mode == Mode.DOCUMENT || mode == Mode.INLINE ? 1 : 0;
        int rowsNeeded = (int) Math.ceil((height + safetyPixels) / (double) safeLineHeight);
        return Math.max(0, rowsNeeded - 1);
    }

    public static int inlineSafetyBlankLines(String source, int lineHeight) {
        return Math.max(1, reservedBlankLines(source, Mode.INLINE, lineHeight));
    }

    public static int currentChatContentWidth() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                double scale = Math.max(0.1D, client.inGameHud.getChatHud().getChatScale());
                int width = (int) Math.ceil(client.inGameHud.getChatHud().getWidth() / scale) - CHAT_SAFE_PADDING;
                if (width > 0) {
                    return Math.max(24, width);
                }
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_CHAT_WIDTH;
    }

    public static int availableWidthFrom(int lineX, int cursorX) {
        int used = Math.max(0, cursorX - lineX);
        return Math.max(0, currentChatContentWidth() - used);
    }

    public static int currentChatViewportBottom() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null && client.getWindow() != null) {
                double scale = Math.max(0.1D, client.inGameHud.getChatHud().getChatScale());
                return (int) Math.floor((client.getWindow().getScaledHeight() - 40) / scale);
            }
        } catch (Throwable ignored) {
        }
        return Integer.MAX_VALUE / 4;
    }

    public static int currentChatViewportTop() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                int bottom = currentChatViewportBottom();
                int visibleLines = Math.max(1, client.inGameHud.getChatHud().getVisibleLineCount());
                return bottom - visibleLines * currentChatLineHeight();
            }
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE / 4;
    }

    public static int currentChatLineHeight() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.options != null) {
                double spacing = (Double) client.options.getChatLineSpacing().getValue();
                return Math.max(1, (int) (9.0D * (spacing + 1.0D)));
            }
        } catch (Throwable ignored) {
        }
        return 9;
    }

    public static boolean containsMarker(String text) {
        return text != null && text.indexOf(START) >= 0 && text.indexOf(END) > text.indexOf(START);
    }

    public static Marker nextMarker(String text, int fromIndex) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf(START, Math.max(0, fromIndex));
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(END, start + START.length());
        if (end < 0) {
            return null;
        }
        String marker = text.substring(start + START.length(), end);
        String id = marker;
        int row = 0;
        int split = marker.lastIndexOf(':');
        if (split > 0) {
            id = marker.substring(0, split);
            try {
                row = Math.max(0, Integer.parseInt(marker.substring(split + 1)));
            } catch (NumberFormatException ignored) {
                row = 0;
            }
        }
        int markerEnd = end + END.length();
        return new Marker(start, markerEnd, BY_ID.get(id), row);
    }

    private static Entry entry(String source, Mode mode) {
        return entry(source, mode, maxDisplayWidth(mode));
    }

    private static Entry entry(String source, Mode mode, int requestedMaxWidth) {
        String normalized = normalize(source);
        Mode safeMode = mode == null ? Mode.INLINE : mode;
        int layoutWidth = Math.max(24, Math.min(maxDisplayWidth(safeMode), requestedMaxWidth));
        String key = safeMode.name() + ":" + layoutWidth + ":" + normalized;
        return BY_KEY.computeIfAbsent(key, ignored -> {
            String id = Integer.toString(NEXT_ID.getAndIncrement(), 36);
            Entry created = new Entry(id, normalized, safeMode, layoutWidth, initialLayout(normalized, safeMode, layoutWidth));
            BY_ID.put(id, created);
            queue(created);
            return created;
        });
    }

    private static String normalize(String source) {
        String normalized = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() > MAX_SOURCE_CHARS) {
            normalized = normalized.substring(0, MAX_SOURCE_CHARS);
        }
        return normalized;
    }

    private static Layout initialLayout(String source, Mode mode, int maxWidth) {
        if (source == null || source.isBlank()) {
            return fallbackLayout(mode, maxWidth);
        }
        try {
            TeXIcon icon = createIcon(source, mode);
            return layoutForTexture(icon.getIconWidth(), icon.getIconHeight(), mode, source, maxWidth);
        } catch (Throwable ignored) {
            return fallbackLayout(mode, maxWidth);
        }
    }

    private static Layout fallbackLayout(Mode mode, int maxWidth) {
        if (mode == Mode.BLOCK || mode == Mode.DOCUMENT) {
            return layoutFromDisplaySize(Math.min(FALLBACK_BLOCK_WIDTH, maxWidth), FALLBACK_BLOCK_HEIGHT, mode);
        }
        if (mode == Mode.TALL_INLINE) {
            return layoutFromDisplaySize(Math.min(FALLBACK_TALL_WIDTH, maxWidth), FALLBACK_TALL_HEIGHT, mode);
        }
        return layoutFromDisplaySize(Math.min(FALLBACK_INLINE_WIDTH, maxWidth), FALLBACK_INLINE_HEIGHT, mode);
    }

    private static Layout layoutForTexture(int textureWidth, int textureHeight, Mode mode, String source, int requestedMaxWidth) {
        int safeTextureWidth = Math.max(1, textureWidth);
        int safeTextureHeight = Math.max(1, textureHeight);
        int rawDisplayWidth = Math.max(1, Math.round(safeTextureWidth / (float) RASTER_SCALE));
        int rawDisplayHeight = Math.max(1, Math.round(safeTextureHeight / (float) RASTER_SCALE));
        int maxWidth = Math.max(24, Math.min(maxDisplayWidth(mode), requestedMaxWidth));
        int maxHeight = maxDisplayHeight(mode);
        double heightScale = Math.min(1.0D, maxHeight / (double) rawDisplayHeight);
        double widthScale = Math.min(1.0D, maxWidth / (double) rawDisplayWidth);
        if (mode == Mode.BLOCK || mode == Mode.DOCUMENT) {
            double scale = fitBlockScale(rawDisplayWidth, rawDisplayHeight, maxWidth, maxHeight) * longBlockExtraScale(source);
            int displayWidth = Math.max(1, (int) Math.round(rawDisplayWidth * scale));
            int displayHeight = Math.max(1, (int) Math.round(rawDisplayHeight * scale));
            return layoutFromDisplaySize(displayWidth, displayHeight, mode);
        }
        double scale = heightScale;
        if (widthScale < scale && widthScale >= minWidthScale(mode)) {
            scale = widthScale;
        }
        int overflowWidth = maxReadableOverflowWidth(mode, maxWidth);
        if (rawDisplayWidth * scale > overflowWidth) {
            scale = Math.min(scale, overflowWidth / (double) rawDisplayWidth);
        }
        int displayWidth = Math.max(1, (int) Math.round(rawDisplayWidth * scale));
        int displayHeight = Math.max(1, (int) Math.round(rawDisplayHeight * scale));
        return layoutFromDisplaySize(displayWidth, displayHeight, mode);
    }

    private static double minWidthScale(Mode mode) {
        if (mode == Mode.INLINE) {
            return MIN_INLINE_WIDTH_SCALE;
        }
        if (mode == Mode.TALL_INLINE) {
            return MIN_TALL_INLINE_WIDTH_SCALE;
        }
        return 0.0D;
    }

    private static double fitBlockScale(int rawDisplayWidth, int rawDisplayHeight, int maxWidth, int maxHeight) {
        double widthScale = Math.min(1.0D, maxWidth / (double) Math.max(1, rawDisplayWidth));
        double heightScale = Math.min(1.0D, maxHeight / (double) Math.max(1, rawDisplayHeight));
        return Math.min(widthScale, heightScale);
    }

    private static double longBlockExtraScale(String source) {
        int length = source == null ? 0 : source.replaceAll("\\s+", " ").trim().length();
        if (length <= LONG_BLOCK_SOURCE_CHARS) {
            return 1.0D;
        }
        double shrink = 1.0D - (length - LONG_BLOCK_SOURCE_CHARS) * 0.0015D;
        return Math.max(MIN_LONG_BLOCK_EXTRA_SCALE, Math.min(1.0D, shrink));
    }

    private static int maxReadableOverflowWidth(Mode mode, int maxWidth) {
        int multiplier = mode == Mode.INLINE ? 2 : 3;
        return Math.max(maxWidth, maxWidth * multiplier);
    }

    private static Layout layoutFromDisplaySize(int displayWidth, int displayHeight, Mode mode) {
        int safeDisplayWidth = Math.max(1, displayWidth);
        int safeDisplayHeight = Math.max(1, displayHeight);
        return new Layout(safeDisplayWidth, safeDisplayHeight, safeDisplayWidth + spacingForMode(mode));
    }

    private static int maxDisplayWidth(Mode mode) {
        int chatWidth = Math.max(48, RichChatBodyWrapFormatter.currentWrapWidth() - 4);
        if (mode == Mode.INLINE) {
            return Math.max(24, Math.min(MAX_INLINE_DISPLAY_WIDTH, chatWidth));
        }
        return Math.max(24, Math.min(MAX_DISPLAY_WIDTH, chatWidth));
    }

    private static int maxDisplayHeight(Mode mode) {
        if (mode == Mode.INLINE) {
            return MAX_INLINE_DISPLAY_HEIGHT;
        }
        if (mode == Mode.TALL_INLINE) {
            return MAX_TALL_INLINE_DISPLAY_HEIGHT;
        }
        return MAX_DISPLAY_HEIGHT;
    }

    public static int spacingForMode(Mode mode) {
        if (mode == Mode.BLOCK || mode == Mode.DOCUMENT) {
            return 4;
        }
        return mode == Mode.TALL_INLINE ? 1 : 2;
    }

    private static TeXIcon createIcon(String source, Mode mode) {
        String formulaSource = mode == Mode.BLOCK || mode == Mode.DOCUMENT ? "\\displaystyle " + source : source;
        TeXFormula formula = new TeXFormula(formulaSource).setColor(Color.WHITE);
        float displaySize = mode == Mode.BLOCK || mode == Mode.DOCUMENT ? 16.0F : 10.5F;
        TeXIcon icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, displaySize * RASTER_SCALE);
        icon.setForeground(Color.WHITE);
        icon.setInsets(insetsForMode(mode));
        return icon;
    }

    private static Insets insetsForMode(Mode mode) {
        if (mode == Mode.BLOCK || mode == Mode.DOCUMENT) {
            return new Insets(2 * RASTER_SCALE, 4 * RASTER_SCALE, 2 * RASTER_SCALE, 4 * RASTER_SCALE);
        }
        return new Insets(RASTER_SCALE, 2 * RASTER_SCALE, RASTER_SCALE, 2 * RASTER_SCALE);
    }

    private static void queue(Entry entry) {
        WORKER.execute(() -> render(entry));
    }

    private static void render(Entry entry) {
        try {
            if (entry.source.isBlank()) {
                entry.fail("empty formula");
                return;
            }
            TeXIcon icon = createIcon(entry.source, entry.mode);
            int textureWidth = Math.max(1, icon.getIconWidth());
            int textureHeight = Math.max(1, icon.getIconHeight());
            Layout layout = layoutForTexture(textureWidth, textureHeight, entry.mode, entry.source, entry.maxWidth);
            int width = textureWidth;
            int height = textureHeight;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                graphics.setColor(new Color(0, 0, 0, 0));
                graphics.fillRect(0, 0, width, height);
                graphics.setColor(Color.WHITE);
                icon.paintIcon(null, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            NativeImage nativeImage = toNativeImage(image);
            MinecraftClient client = MinecraftClient.getInstance();
            final int finalTextureWidth = textureWidth;
            final int finalTextureHeight = textureHeight;
            final Layout finalLayout = layout;
            client.execute(() -> {
                try {
                    NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
                    Identifier identifier = client.getTextureManager().registerDynamicTexture("koil_rich_chat_latex_" + entry.id, texture);
                    entry.ready(identifier, finalTextureWidth, finalTextureHeight, finalLayout);
                } catch (Throwable throwable) {
                    nativeImage.close();
                    entry.fail(throwable.getMessage());
                }
            });
        } catch (Throwable throwable) {
            entry.fail(throwable.getMessage());
        }
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), true);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                nativeImage.setColor(x, y, SkinTextureTools.argbToNative(image.getRGB(x, y)));
            }
        }
        return nativeImage;
    }

    private record Layout(int displayWidth, int displayHeight, int advanceWidth) {
    }

    public enum Mode {
        INLINE,
        TALL_INLINE,
        BLOCK,
        DOCUMENT
    }

    public record Marker(int start, int end, Entry entry, int row) {
    }

    public static final class Entry {
        private final String id;
        private final String source;
        private final Mode mode;
        private final int maxWidth;
        private final int advanceWidth;
        private volatile Status status = Status.PENDING;
        private volatile Identifier textureId;
        private volatile int textureWidth;
        private volatile int textureHeight;
        private volatile int displayWidth;
        private volatile int displayHeight;
        private volatile String error;

        private Entry(String id, String source, Mode mode, int maxWidth, Layout initialLayout) {
            this.id = id;
            this.source = source;
            this.mode = mode;
            this.maxWidth = Math.max(24, maxWidth);
            this.displayWidth = Math.max(1, initialLayout.displayWidth());
            this.displayHeight = Math.max(1, initialLayout.displayHeight());
            this.advanceWidth = Math.max(1, initialLayout.advanceWidth());
        }

        public String source() {
            return source;
        }

        public Mode mode() {
            return mode;
        }

        public Status status() {
            return status;
        }

        public Identifier textureId() {
            return textureId;
        }

        public int width() {
            return displayWidth;
        }

        public int height() {
            return displayHeight;
        }

        public int advanceWidth() {
            return advanceWidth;
        }

        public int textureWidth() {
            return textureWidth;
        }

        public int textureHeight() {
            return textureHeight;
        }

        public String error() {
            return error;
        }

        private void ready(Identifier textureId, int textureWidth, int textureHeight, Layout layout) {
            this.textureId = textureId;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            int maxReservedDrawWidth = Math.max(1, advanceWidth - spacingForMode(mode));
            int readyWidth = Math.max(1, layout.displayWidth());
            int readyHeight = Math.max(1, layout.displayHeight());
            if (readyWidth > maxReservedDrawWidth) {
                readyHeight = Math.max(1, Math.round(readyHeight * (maxReservedDrawWidth / (float) readyWidth)));
                readyWidth = maxReservedDrawWidth;
            }
            this.displayWidth = readyWidth;
            this.displayHeight = readyHeight;
            this.status = Status.READY;
        }

        private void fail(String error) {
            this.error = error == null || error.isBlank() ? "render failed" : error;
            this.status = Status.FAILED;
        }
    }

    public enum Status {
        PENDING,
        READY,
        FAILED
    }
}
