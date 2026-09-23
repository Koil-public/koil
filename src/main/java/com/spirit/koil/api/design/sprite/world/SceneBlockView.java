package com.spirit.koil.api.design.sprite.world;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * Detached Minecraft-shaped view over the authoritative Koil scene grids.
 *
 * <p>This is deliberately a {@link BlockView}, not a World/ClientWorld. Vanilla
 * block geometry and connection helpers that only need read-only neighboring
 * block/fluid state can therefore consume the real Koil scene without opening
 * or simulating a playable Minecraft world.</p>
 */
public final class SceneBlockView implements BlockView {
    private static final int BOTTOM_Y = -2048;
    private static final int HEIGHT = 4096;

    private final BlockGrid blocks;
    private final FluidGrid fluids;

    public SceneBlockView(BlockGrid blocks, FluidGrid fluids) {
        this.blocks = blocks;
        this.fluids = fluids;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        // Block-entity state is scene-owned and intentionally not represented by a
        // live Minecraft BlockEntity object yet. Read-only block/model/shape logic
        // must not silently bootstrap a World to obtain one.
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (pos == null || blocks == null) return Blocks.AIR.getDefaultState();
        return blocks.getBlockState(new SceneCellPos(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (pos == null) return Fluids.EMPTY.getDefaultState();
        SceneCellPos scenePos = new SceneCellPos(pos.getX(), pos.getY(), pos.getZ());
        if (fluids != null) {
            FluidState fluid = fluids.getFluidState(scenePos);
            if (fluid != null && !fluid.isEmpty()) return fluid;
        }
        BlockState state = getBlockState(pos);
        try { return state.getFluidState(); }
        catch (RuntimeException ignored) { return Fluids.EMPTY.getDefaultState(); }
    }

    @Override public int getHeight() { return HEIGHT; }
    @Override public int getBottomY() { return BOTTOM_Y; }
}
