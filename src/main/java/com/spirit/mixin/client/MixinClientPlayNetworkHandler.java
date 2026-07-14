package com.spirit.mixin.client;

import com.spirit.koil.chat.internal.RichChatCommandOutputBridge;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinClientPlayNetworkHandler {
    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void koil$rememberCommandBlockPackets(Packet<?> packet, CallbackInfo ci) {
        RichChatCommandOutputBridge.rememberOutgoingCommandBlockPacket(packet);
    }
}
