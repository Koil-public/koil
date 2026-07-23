package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects inactive Content block placement by block-state commands. */
@Mixin(BlockStateArgument.class)
public abstract class MixinBlockStateArgument {
    @Shadow
    public abstract BlockState getBlockState();

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void koil$rejectInactiveContentBlock(
            ServerWorld world,
            BlockPos pos,
            int flags,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!ContentVisibilityPolicy.mayCreate(getBlockState().getBlock())) {
            callback.setReturnValue(false);
        }
    }
}
