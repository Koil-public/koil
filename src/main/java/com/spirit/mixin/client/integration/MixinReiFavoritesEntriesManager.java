package com.spirit.mixin.client.integration;

import com.spirit.koil.api.registry.integration.ContentViewerSavedEntryFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Hides inactive Content from REI's saved favorites view without deleting it. */
@Pseudo
@Mixin(targets = "me.shedaniel.rei.impl.client.gui.widget.favorites.FavoritesEntriesManager", remap = false)
public abstract class MixinReiFavoritesEntriesManager {
    @Inject(method = "getFavorites", at = @At("RETURN"), cancellable = true, remap = false)
    private void koil$filterInactiveContentFavorites(CallbackInfoReturnable<List<?>> callback) {
        callback.setReturnValue(ContentViewerSavedEntryFilter.filterReiFavorites(callback.getReturnValue()));
    }
}
