package com.spirit.mixin.client.gui.revamp.accessor;

import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldListWidget.WorldEntry.class)
public interface WorldListWidgetWorldEntryAccessor {
    @Accessor("level")
    LevelSummary koil$getLevel();
}
