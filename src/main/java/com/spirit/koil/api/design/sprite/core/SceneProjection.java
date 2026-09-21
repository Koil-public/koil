package com.spirit.koil.api.design.sprite.core;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Single authoritative mapping between Koil screen-space, scene cells and
 * Minecraft XYZ directions. The default projection is an XY side view:
 * screen-right=east, screen-left=west, screen-up=up, screen-down=down and
 * scene depth=Minecraft Z.
 */
public final class SceneProjection {
    public enum Mode { XY_SIDE, XZ_TOP, ZY_SIDE }

    private Mode mode = Mode.XY_SIDE;
    private float cellPixels = 16.0F;
    private float originX;
    private float originY;
    /** Hidden-axis gameplay is isolated by default. Rendering may composite every depth,
     *  but blocks/fluids/physics on one depth slice must not implicitly mutate another. */
    private boolean crossLayerInteractions;
    private long revision;
    private long interactionRevision;

    public Mode mode() { return mode; }
    public float cellPixels() { return cellPixels; }
    public float originX() { return originX; }
    public float originY() { return originY; }
    public boolean crossLayerInteractions() { return crossLayerInteractions; }
    public long revision() { return revision; }
    public long interactionRevision() { return interactionRevision; }

    /**
     * Opt-in bridge for systems that intentionally want Minecraft behavior to cross
     * the hidden projection axis. Normal Koil layers are independent by default.
     */
    public void setCrossLayerInteractions(boolean enabled) {
        if (crossLayerInteractions != enabled) {
            crossLayerInteractions = enabled;
            revision++;
            interactionRevision++;
        }
    }

    public void setMode(Mode mode) {
        Mode next = mode == null ? Mode.XY_SIDE : mode;
        if (this.mode != next) {
            this.mode = next;
            revision++;
            interactionRevision++;
        }
    }

    public void setMode(String value) {
        Mode next;
        if (value == null) next = Mode.XY_SIDE;
        else {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            next = switch (normalized) {
                case "xz", "xz_top", "top", "top_down" -> Mode.XZ_TOP;
                case "zy", "zy_side" -> Mode.ZY_SIDE;
                default -> Mode.XY_SIDE;
            };
        }
        setMode(next);
    }

    public void setCellPixels(float pixels) {
        if (!Float.isFinite(pixels)) return;
        float next = Math.max(1.0F, pixels);
        if (Math.abs(next - cellPixels) > 0.0001F) { cellPixels = next; revision++; }
    }

    /**
     * Screen-space camera origin for editor/background composition. Defaults to
     * 0,0 so existing HUD and compatibility projections remain byte-for-byte
     * equivalent until a caller explicitly pans the scene.
     */
    public void setOrigin(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        if (Math.abs(originX - x) > 0.0001F || Math.abs(originY - y) > 0.0001F) {
            originX = x;
            originY = y;
            revision++;
        }
    }

    public void pan(float deltaX, float deltaY) {
        if (!Float.isFinite(deltaX) || !Float.isFinite(deltaY)) return;
        setOrigin(originX + deltaX, originY + deltaY);
    }

    public SceneCellPos screenToCell(float screenX, float screenY, int depth) {
        int horizontal = Math.round((screenX - originX) / cellPixels);
        int vertical = Math.round((screenY - originY) / cellPixels);
        return switch (mode) {
            case XY_SIDE -> new SceneCellPos(horizontal, -vertical, depth);
            case XZ_TOP -> new SceneCellPos(horizontal, depth, vertical);
            case ZY_SIDE -> new SceneCellPos(depth, -vertical, horizontal);
        };
    }

    public float cellCenterScreenX(SceneCellPos pos) {
        return switch (mode) {
            case XY_SIDE, XZ_TOP -> originX + pos.x() * cellPixels;
            case ZY_SIDE -> originX + pos.depth() * cellPixels;
        };
    }

    public float cellCenterScreenY(SceneCellPos pos) {
        return switch (mode) {
            case XY_SIDE, ZY_SIDE -> originY - pos.y() * cellPixels;
            case XZ_TOP -> originY + pos.depth() * cellPixels;
        };
    }

    public BlockPos toMinecraft(SceneCellPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.depth());
    }

    public SceneCellPos fromMinecraft(BlockPos pos) {
        return new SceneCellPos(pos.getX(), pos.getY(), pos.getZ());
    }

    public SceneCellPos offset(SceneCellPos pos, Direction direction) {
        if (pos == null || direction == null) return pos;
        return new SceneCellPos(
                pos.x() + direction.getOffsetX(),
                pos.y() + direction.getOffsetY(),
                pos.depth() + direction.getOffsetZ());
    }

    /** Returns true when two cells belong to the same currently-hidden depth slice. */
    public boolean sameDepthLayer(SceneCellPos a, SceneCellPos b) {
        return a != null && b != null && depthCoordinate(a) == depthCoordinate(b);
    }

    /**
     * Offset used by gameplay/simulation neighbor logic. Unlike {@link #offset},
     * this respects Koil's layer-isolation policy and returns {@code null} when the
     * requested Minecraft neighbor lives on another hidden depth slice.
     */
    public SceneCellPos interactionOffset(SceneCellPos pos, Direction direction) {
        SceneCellPos next = offset(pos, direction);
        if (pos == null || next == null) return null;
        return crossLayerInteractions || sameDepthLayer(pos, next) ? next : null;
    }


    /** Returns the Minecraft coordinate projected out of the 2D screen plane. */
    public int depthCoordinate(SceneCellPos pos) {
        if (pos == null) return 0;
        return switch (mode) {
            case XY_SIDE -> pos.depth();
            case XZ_TOP -> pos.y();
            case ZY_SIDE -> pos.x();
        };
    }

    /** Creates a scene cell from screen-space coordinates and an off-screen depth coordinate. */
    public SceneCellPos screenToCellAtDepth(float screenX, float screenY, int depthCoordinate) {
        return screenToCell(screenX, screenY, depthCoordinate);
    }

    public Direction screenRightDirection() {
        return switch (mode) {
            case XY_SIDE, XZ_TOP -> Direction.EAST;
            case ZY_SIDE -> Direction.SOUTH;
        };
    }

    public Direction screenLeftDirection() { return screenRightDirection().getOpposite(); }

    public Direction screenDownDirection() {
        return switch (mode) {
            case XY_SIDE, ZY_SIDE -> Direction.DOWN;
            case XZ_TOP -> Direction.SOUTH;
        };
    }

    public Direction screenUpDirection() { return screenDownDirection().getOpposite(); }
}
