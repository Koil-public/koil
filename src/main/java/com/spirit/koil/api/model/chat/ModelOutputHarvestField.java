package com.spirit.koil.api.model.chat;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lightweight per-HUD-frame registry of visible text cells that can act as
 * visual-only source points for model-output materialization.
 *
 * The registry never edits, hides, consumes, or copies data back into chat. It
 * only records screen-space positions of glyphs that Minecraft already drew.
 * Source matter may then visually emerge from one of those positions while the
 * original glyph remains untouched underneath.
 */
public final class ModelOutputHarvestField {
    private static final int MAXIMUM_CELLS = 384;
    private static final int MAXIMUM_LINE_GLYPHS = 96;
    private static final int MINIMUM_VERTICAL_GAP = 2;

    private static final ArrayList<Cell> CELLS = new ArrayList<>(MAXIMUM_CELLS);
    private static final int[] ASCII_WIDTHS = new int[128];
    private static TextRenderer widthRenderer;
    private static int observedEligible;

    private ModelOutputHarvestField() {
    }

    /** Starts a new native chat/HUD frame. */
    public static void beginFrame() {
        CELLS.clear();
        observedEligible = 0;
    }

    /**
     * Records visible glyph cells using the DrawContext's current transform.
     * This makes native ChatHud coordinates line up with later screen-space
     * model-popup rendering even when Minecraft chat scale/translation is active.
     */
    public static void observeLine(
            DrawContext context,
            TextRenderer renderer,
            OrderedText line,
            int x,
            int y
    ) {
        if (context == null || renderer == null || line == null) return;
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f left = new Vector4f();
        Vector4f right = new Vector4f();
        int[] localX = new int[] {x};
        int[] glyphs = new int[] {0};
        line.accept((index, style, codePoint) -> {
            if (glyphs[0]++ >= MAXIMUM_LINE_GLYPHS) return false;
            int width = glyphWidth(renderer, codePoint);
            if (harvestEligible(codePoint)) {
                left.set(localX[0], y, 0.0F, 1.0F).mul(matrix);
                right.set(localX[0] + width, y, 0.0F, 1.0F).mul(matrix);
                int screenX = Math.round(left.x());
                int screenY = Math.round(left.y());
                int screenWidth = Math.max(1, Math.round(Math.abs(right.x() - left.x())));
                offer(new Cell(screenX, screenY, screenWidth, codePoint));
            }
            localX[0] += width;
            return true;
        });
    }

    /**
     * Selects stable, distinct visible glyphs physically above the materializer.
     * Selection is deterministic for a source burst seed so characters do not
     * jump between messages every render frame.
     */
    public static List<Cell> sampleAbove(int cursorX, int cursorY, int requested, int seed) {
        int count = Math.max(0, Math.min(12, requested));
        if (count == 0 || CELLS.isEmpty()) return List.of();

        ArrayList<Cell> eligible = new ArrayList<>(Math.min(CELLS.size(), 160));
        for (Cell cell : CELLS) {
            if (cell == null) continue;
            if (cell.y() + MINIMUM_VERTICAL_GAP >= cursorY) continue;
            eligible.add(cell);
        }
        if (eligible.isEmpty()) return List.of();

        int wanted = Math.min(count, eligible.size());
        ArrayList<Cell> selected = new ArrayList<>(wanted);
        int start = Math.floorMod(mix32(seed), eligible.size());
        int step = Math.max(1, Math.floorMod(mix32(seed ^ 0x632BE59B), eligible.size()));
        while (greatestCommonDivisor(step, eligible.size()) != 1) step++;

        for (int i = 0, index = start; i < wanted; i++, index = Math.floorMod(index + step, eligible.size())) {
            selected.add(eligible.get(index));
        }
        return List.copyOf(selected);
    }


    /** Finds a nearby visible text cell so moving source matter can temporarily
     *  project an obfuscated overlay onto the exact character cell it is crossing. */
    public static Cell nearestCell(int screenX, int screenY, int maxDx, int maxDy) {
        if (CELLS.isEmpty()) return null;
        Cell best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Cell cell : CELLS) {
            if (cell == null) continue;
            int cx = cell.x() + Math.max(1, cell.width() / 2);
            int cy = cell.y();
            int dx = Math.abs(cx - screenX);
            int dy = Math.abs(cy - screenY);
            if (dx > maxDx || dy > maxDy) continue;
            int score = dx * 3 + dy * 5;
            if (score < bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        return best;
    }

    static int visibleCellCount() {
        return CELLS.size();
    }

    private static void offer(Cell cell) {
        int seen = ++observedEligible;
        if (CELLS.size() < MAXIMUM_CELLS) {
            CELLS.add(cell);
            return;
        }
        // Deterministic reservoir sampling keeps coverage across old/native chat
        // and model-output lines without growing allocations with chat history.
        int replacement = Math.floorMod(mix32(seen * 0x9E3779B9), seen);
        if (replacement < MAXIMUM_CELLS) CELLS.set(replacement, cell);
    }

    private static int glyphWidth(TextRenderer renderer, int codePoint) {
        if (renderer != widthRenderer) {
            widthRenderer = renderer;
            Arrays.fill(ASCII_WIDTHS, 0);
        }
        if (codePoint >= 0 && codePoint < ASCII_WIDTHS.length) {
            int cached = ASCII_WIDTHS[codePoint];
            if (cached > 0) return cached;
            int width = Math.max(1, renderer.getWidth(String.valueOf((char) codePoint)));
            ASCII_WIDTHS[codePoint] = width;
            return width;
        }
        return Math.max(1, renderer.getWidth(new String(Character.toChars(codePoint))));
    }

    private static boolean harvestEligible(int codePoint) {
        if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) return false;
        int type = Character.getType(codePoint);
        return type != Character.FORMAT
                && type != Character.PRIVATE_USE
                && type != Character.SPACE_SEPARATOR
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR
                && type != Character.SURROGATE
                && type != Character.UNASSIGNED;
    }

    private static int greatestCommonDivisor(int a, int b) {
        int x = Math.max(1, Math.abs(a));
        int y = Math.max(1, Math.abs(b));
        while (y != 0) {
            int next = x % y;
            x = y;
            y = next;
        }
        return x;
    }

    private static int mix32(int value) {
        int x = value;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    /** Actual visible screen-space glyph location. */
    public record Cell(int x, int y, int width, int codePoint) {
    }
}
