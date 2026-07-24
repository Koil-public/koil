package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.DynamicContentHolderRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 1.20.1 caches state luminance at registry construction. Managed
 * Content blocks delegate this one getter to the active-world definition so
 * world activation and safe behavior edits do not inherit another world's light.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
abstract class MixinAbstractBlockStateContentLuminance {
    @Inject(method = "getLuminance", at = @At("HEAD"), cancellable = true)
    private void koil$dynamicContentLuminance(CallbackInfoReturnable<Integer> callback) {
        int luminance = DynamicContentHolderRegistry.dynamicLuminance((BlockState) (Object) this);
        if (luminance >= 0) {
            callback.setReturnValue(luminance);
        }
    }
}
