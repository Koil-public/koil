package com.spirit.mixin.client.integration;

import com.spirit.koil.api.registry.integration.ContentViewerSavedEntryFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Hides inactive Content from JEI's bookmark presentation without deleting it. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.bookmarks.BookmarkList", remap = false)
public abstract class MixinJeiBookmarkList {
    @Inject(method = "getElements", at = @At("RETURN"), cancellable = true, remap = false)
    private void koil$filterInactiveContentBookmarks(CallbackInfoReturnable<List<?>> callback) {
        callback.setReturnValue(ContentViewerSavedEntryFilter.filterJeiElements(callback.getReturnValue()));
    }
}
