package com.spirit.koil.api.design.sprite.actor;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import net.minecraft.block.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Unsupported Minecraft block temporarily promoted from the grid into actor physics. */
public final class FallingBlockActor extends Actor {
    private final BlockState blockState;
    private final SceneCellPos origin;
    private BlockCell.Authority sourceBlockAuthority = BlockCell.Authority.SCENE;
    private long sourceBlockId = -1L;
    private final Map<String, String> sourceRuntimeData = new LinkedHashMap<>();
    private final Map<String, String> sourceBlockEntityData = new LinkedHashMap<>();
    private int sourceFlags;

    public FallingBlockActor(long id, Authority authority, BlockState blockState, SceneCellPos origin,
                                 float x, float y, int depth) {
        super(id, authority, x, y, depth);
        this.blockState = blockState;
        this.origin = origin;
        setBodySize(15.8F, 15.8F);
        setMass(1.0F);
        setGravity(256.0F);
        setDrag(0.98F);
        setRestitution(0.02F);
        setSurfaceFriction(0.90F);
    }

    @Override public String kind() { return "falling_block"; }
    public BlockState blockState() { return blockState; }
    public SceneCellPos origin() { return origin; }
    public BlockCell.Authority sourceBlockAuthority() { return sourceBlockAuthority; }
    public long sourceBlockId() { return sourceBlockId; }
    public int sourceFlags() { return sourceFlags; }
    public Map<String, String> sourceRuntimeData() { return Collections.unmodifiableMap(sourceRuntimeData); }
    public Map<String, String> sourceBlockEntityData() { return Collections.unmodifiableMap(sourceBlockEntityData); }

    /** Carries authored per-cell metadata through the temporary actor phase. */
    public void captureSourceCell(BlockCell cell) {
        sourceRuntimeData.clear();
        sourceBlockEntityData.clear();
        sourceFlags = 0;
        if (cell == null) return;
        sourceRuntimeData.putAll(cell.runtimeData());
        sourceBlockEntityData.putAll(cell.blockEntityData());
        sourceFlags = cell.flags();
        setMass(cell.runtimeFloat("mass", body().mass()));
        setGravity(cell.runtimeFloat("gravity", body().gravity()));
        setDrag(cell.runtimeFloat("drag", body().linearDampingPerMinecraftTick()));
        setRestitution(cell.runtimeFloat("restitution", body().restitution()));
        setSurfaceFriction(cell.runtimeFloat("surface_friction", body().surfaceFriction()));
    }

    public void setSourceBlockAuthority(BlockCell.Authority authority, long sourceId) {
        sourceBlockAuthority = authority == null ? BlockCell.Authority.SCENE : authority;
        sourceBlockId = sourceId;
    }
}
