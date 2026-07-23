package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Final server-world boundary preventing commands, structures, clone operations,
 * or mods from placing an inactive Content block.
 */
@Mixin(World.class)
public abstract class MixinWorldContentPlacement {
    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void koil$rejectInactiveContentBlock(
            BlockPos pos,
            BlockState state,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if ((Object) this instanceof ServerWorld
                && !ContentVisibilityPolicy.mayCreate(state.getBlock())) {
            callback.setReturnValue(false);
        }
    }
}
