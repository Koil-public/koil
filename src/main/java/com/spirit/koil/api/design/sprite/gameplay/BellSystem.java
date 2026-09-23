package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Detached bell interaction timeline.
 *
 * <p>Koil owns only when a scene bell was struck and from which projected side.
 * Rendering consumes this immutable state and applies Minecraft's vanilla bell
 * swing equation to Minecraft's native bell model. No playable World and no
 * mutation of BellBlockEntity's package-private animation fields is required.</p>
 */
public final class BellSystem {
    public static final int VANILLA_RING_TICKS = 50;

    public record RenderState(boolean ringing, float ringTicks, Direction sideHit) {
        public static final RenderState IDLE = new RenderState(false, 0.0F, Direction.EAST);
    }

    private record Ring(long startedAtTick, Direction sideHit) { }

    private final SceneProjection projection;
    private final LongSupplier gameTick;
    private final Map<SceneCellPos, Ring> rings = new HashMap<>();

    public BellSystem(SceneProjection projection, LongSupplier gameTick) {
        this.projection = projection;
        this.gameTick = gameTick;
    }

    /** Starts or restarts Minecraft's 50-tick bell ringing animation. */
    public void ring(SceneCellPos pos) {
        ring(pos, visibleHitDirection());
    }

    /**
     * Starts a ring using the projected side that was actually clicked. Vanilla
     * BellBlockEntityRenderer uses this side to choose pitch versus roll and the
     * sign of the swing, so Koil retains it as gameplay-derived animation state.
     */
    public void ring(SceneCellPos pos, Direction sideHit) {
        if (pos == null) return;
        Direction side = sideHit == null ? visibleHitDirection() : sideHit;
        if (side.getAxis() == Direction.Axis.Y) side = visibleHitDirection();
        rings.put(pos, new Ring(gameTick.getAsLong(), side));
    }

    /**
     * Returns the detached equivalent of the BellBlockEntity fields consumed by
     * BellBlockEntityRenderer. {@code tickDelta} is Koil's game-tick render alpha.
     */
    public RenderState renderState(SceneCellPos pos, float tickDelta) {
        if (pos == null) return RenderState.IDLE;
        Ring ring = rings.get(pos);
        if (ring == null) return RenderState.IDLE;

        long elapsed = Math.max(0L, gameTick.getAsLong() - ring.startedAtTick());
        if (elapsed >= VANILLA_RING_TICKS) {
            rings.remove(pos);
            return RenderState.IDLE;
        }
        float partial = Math.max(0.0F, Math.min(1.0F, tickDelta));
        return new RenderState(true, elapsed + partial, ring.sideHit());
    }

    public void minecraftTick() {
        long now = gameTick.getAsLong();
        rings.entrySet().removeIf(entry -> now - entry.getValue().startedAtTick() >= VANILLA_RING_TICKS);
    }

    public void reset() {
        rings.clear();
    }

    private Direction visibleHitDirection() {
        // In Koil's side view an east/west hit maps the native bell swing to roll
        // around Z, which remains visible after the hidden Minecraft depth axis is
        // flattened. Bell attachment/facing itself remains authoritative BlockState.
        Direction right = projection == null ? Direction.EAST : projection.screenRightDirection();
        if (right == Direction.EAST || right == Direction.WEST) return right;
        return Direction.EAST;
    }
}
