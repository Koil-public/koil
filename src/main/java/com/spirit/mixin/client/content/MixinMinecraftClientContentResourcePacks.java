package com.spirit.mixin.client.content;

import com.spirit.koil.api.registry.client.ActiveWorldContentResourcePackProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/** Adds Koil's dynamic world-only resource provider to the client pack manager. */
@Mixin(MinecraftClient.class)
public abstract class MixinMinecraftClientContentResourcePacks {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/resource/ResourcePackManager;<init>([Lnet/minecraft/resource/ResourcePackProvider;)V"
            ),
            index = 0
    )
    private ResourcePackProvider[] koil$appendContentResourceProvider(ResourcePackProvider[] providers) {
        ResourcePackProvider[] expanded = Arrays.copyOf(providers, providers.length + 1);
        expanded[providers.length] = ActiveWorldContentResourcePackProvider.INSTANCE;
        return expanded;
    }
}
