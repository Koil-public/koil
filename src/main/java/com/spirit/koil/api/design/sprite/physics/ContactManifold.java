package com.spirit.koil.api.design.sprite.physics;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import net.minecraft.block.BlockState;

/** Collision result emitted by the fixed-step scene physics world. */
public record ContactManifold(
        long actorId,
        Long otherActorId,
        SceneCellPos blockCell,
        BlockState blockState,
        float normalX,
        float normalY,
        float impactSpeed,
        boolean grounded
) { }
