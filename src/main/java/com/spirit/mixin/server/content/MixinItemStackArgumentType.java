package com.spirit.mixin.server.content;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.command.argument.ItemStackArgumentType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Hides inactive world-scoped Content from item-stack command suggestions. */
@Mixin(ItemStackArgumentType.class)
public abstract class MixinItemStackArgumentType {
    @Inject(method = "listSuggestions", at = @At("RETURN"), cancellable = true)
    private <S> void koil$hideInactiveContentSuggestions(
            CommandContext<S> context,
            SuggestionsBuilder builder,
            CallbackInfoReturnable<CompletableFuture<Suggestions>> callback
    ) {
        Set<String> inactive = Set.copyOf(ContentVisibilityPolicy.inactiveIds());
        if (inactive.isEmpty()) {
            return;
        }
        callback.setReturnValue(callback.getReturnValue().thenApply(suggestions -> {
            List<Suggestion> filtered = suggestions.getList().stream()
                    .filter(suggestion -> !inactive.contains(suggestion.getText()))
                    .toList();
            return filtered.size() == suggestions.getList().size()
                    ? suggestions
                    : new Suggestions(suggestions.getRange(), filtered);
        }));
    }
}
