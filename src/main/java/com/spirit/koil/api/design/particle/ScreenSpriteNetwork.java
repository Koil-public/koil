package com.spirit.koil.api.design.particle;

import net.minecraft.util.Identifier;

/** Stable packet id for server-authorized screen-sprite requests. */
public final class ScreenSpriteNetwork {
    public static final Identifier SPRITE_PACKET = new Identifier("koil", "screen_sprite");

    private ScreenSpriteNetwork() {
    }
}
