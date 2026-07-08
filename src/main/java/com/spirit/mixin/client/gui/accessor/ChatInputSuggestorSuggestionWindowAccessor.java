package com.spirit.mixin.client.gui.accessor;

import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import net.minecraft.client.util.math.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatInputSuggestor.SuggestionWindow.class)
public interface ChatInputSuggestorSuggestionWindowAccessor {
    @Accessor("area")
    Rect2i koil$getArea();

    @Accessor("suggestions")
    List<Suggestion> koil$getSuggestions();

    @Accessor("selection")
    int koil$getSelection();

    @Invoker("select")
    void koil$select(int index);

    @Invoker("complete")
    void koil$complete();
}
