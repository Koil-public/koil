package com.spirit.koil.api.design.sprite.physics;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import net.minecraft.block.BlockState;

/** One projected VoxelShape box from an authoritative scene block. */
public record StaticCollider2D(
        SceneCellPos cell,
        BlockState state,
        int depth,
        CollisionShape2D bounds,
        float slipperiness,
        float restitution,
        float surfaceFriction,
        boolean slime,
        boolean honey
) { }
