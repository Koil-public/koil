package com.spirit.mixin.client.gui;

import com.spirit.koil.api.model.presence.ModelPresenceLineGeometry;
import com.spirit.koil.api.model.presence.ModelPresenceState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(PlayerListHud.class)
public abstract class MixinPlayerListHud {
    @Unique
    private static final ThreadLocal<UUID> koil$playerNameBeingDrawn = new ThreadLocal<>();

    @Inject(method = "getPlayerName", at = @At("RETURN"))
    private void koil$rememberPlayerForStatusLine(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        koil$playerNameBeingDrawn.set(entry == null || entry.getProfile() == null
                ? null
                : entry.getProfile().getId());
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"
            )
    )
    private int koil$drawPlayerNameStatusLine(
            DrawContext context,
            TextRenderer renderer,
            Text text,
            int x,
            int y,
            int color
    ) {
        int result = context.drawTextWithShadow(renderer, text, x, y, color);
        UUID playerId = koil$playerNameBeingDrawn.get();
        koil$playerNameBeingDrawn.remove();
        if (playerId == null || !ModelPresenceState.visibleFor(playerId) || text == null) {
            return result;
        }
        ModelPresenceLineGeometry.Bounds line = ModelPresenceLineGeometry.beneathName(
                x,
                y,
                renderer.getWidth(text),
                renderer.fontHeight
        );
        if (line.right() > line.left()) {
            context.fill(
                    line.left(),
                    line.top(),
                    line.right(),
                    line.bottom(),
                    ModelPresenceState.colorFor(playerId)
            );
        }
        return result;
    }
}
