package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rejects inactive Content placed directly onto a handled-screen cursor. */
@Mixin(ScreenHandler.class)
public abstract class MixinScreenHandlerContentGuard {
    @Inject(method = "setCursorStack", at = @At("HEAD"), cancellable = true)
    private void koil$rejectInactiveContentCursor(ItemStack stack, CallbackInfo callback) {
        if (!stack.isEmpty() && !ContentVisibilityPolicy.mayCreate(stack.getItem())) {
            callback.cancel();
        }
    }
}
