package com.spirit.mixin.server.content;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.command.argument.ItemStackArgument;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects inactive world-scoped Content when a command actually creates a stack. */
@Mixin(ItemStackArgument.class)
public abstract class MixinItemStackArgument {
    private static final DynamicCommandExceptionType KOIL_INACTIVE_CONTENT =
            new DynamicCommandExceptionType(id -> Text.literal("Content " + id + " is not active in this world"));

    @Shadow
    public abstract Item getItem();

    @Inject(method = "createStack", at = @At("HEAD"))
    private void koil$rejectInactiveContent(
            int amount,
            boolean checkOverstack,
            CallbackInfoReturnable<ItemStack> callback
    ) throws CommandSyntaxException {
        Item item = getItem();
        if (ContentVisibilityPolicy.shouldExpose(item)) {
            return;
        }
        String id = ContentVisibilityPolicy.contentId(item).orElse("unknown");
        throw KOIL_INACTIVE_CONTENT.create(id);
    }
}
