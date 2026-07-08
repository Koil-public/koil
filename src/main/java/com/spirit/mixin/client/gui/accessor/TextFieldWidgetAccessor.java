package com.spirit.mixin.client.gui.accessor;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextFieldWidget.class)
public interface TextFieldWidgetAccessor {
    @Accessor("firstCharacterIndex")
    int koil$getFirstCharacterIndex();

    @Accessor("selectionStart")
    int koil$getSelectionStart();

    @Accessor("selectionEnd")
    int koil$getSelectionEnd();
}
