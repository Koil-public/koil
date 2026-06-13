package com.spirit.mixin.client.gui.revamp.accessor;

import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntryListWidget.class)
public interface EntryListWidgetAccessor {
    @Invoker("addEntry")
    int koil$invokeAddEntry(EntryListWidget.Entry<?> entry);
}
