package com.spirit.mixin.client.gui.revamp.multiplayer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.LoadingDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LoadingDisplay.class)
@Environment(EnvType.CLIENT)
public class MixinLoadingDisplay {
    private static final String[] CUSTOM_TEXTS = new String[]{
            ""
    };
    /**
     * @author SpiritXIV
     * @reason for the funny
     */

    @Overwrite
    public static String get(long tick) {
        int i = (int)(tick / 300L % (long)CUSTOM_TEXTS.length);
        return CUSTOM_TEXTS[i];
    }
}
