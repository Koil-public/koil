package com.spirit.mixin.client.gui.revamp.accessor;

import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiplayerServerListWidget.class)
public interface MultiplayerServerListWidgetAccessor {
    @Accessor("LAN_SCANNING_TEXT")
    static Text getLanScanningText() {
        throw new AssertionError();
    }
}
