package com.spirit.mixin.client.gui.accessor;

import net.minecraft.client.gui.screen.ChatInputSuggestor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChatInputSuggestor.class)
public interface ChatInputSuggestorAccessor {
    @Accessor("window")
    ChatInputSuggestor.SuggestionWindow koil$getWindow();
}
