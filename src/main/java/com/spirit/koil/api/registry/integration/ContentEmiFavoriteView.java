package com.spirit.koil.api.registry.integration;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-persistent view over EMI favorites that omits inactive world Content.
 *
 * <p>The underlying EMI favorite list remains untouched and is still what EMI
 * saves. A snapshot is rebuilt only after Content activation changes or the
 * source list size changes, not during every rendered frame.</p>
 */
final class ContentEmiFavoriteView extends AbstractList<EmiFavorite> {
    private static volatile ContentEmiFavoriteView installed;

    private final List<EmiFavorite> source;
    private volatile List<EmiFavorite> visible = List.of();
    private volatile int observedSourceSize = -1;
    private volatile boolean dirty = true;

    private ContentEmiFavoriteView(List<EmiFavorite> source) {
        this.source = source;
    }

    static synchronized void install() {
        if (EmiFavorites.favoriteSidebar instanceof ContentEmiFavoriteView current) {
            installed = current;
            current.invalidateView();
            return;
        }
        ContentEmiFavoriteView view = new ContentEmiFavoriteView(EmiFavorites.favoriteSidebar);
        installed = view;
        EmiFavorites.favoriteSidebar = view;
    }

    static void invalidate() {
        ContentEmiFavoriteView view = installed;
        if (view != null) {
            view.invalidateView();
        }
    }

    @Override
    public EmiFavorite get(int index) {
        return snapshot().get(index);
    }

    @Override
    public int size() {
        return snapshot().size();
    }

    private void invalidateView() {
        dirty = true;
    }

    private List<EmiFavorite> snapshot() {
        int currentSize = source.size();
        if (!dirty && observedSourceSize == currentSize) {
            return visible;
        }
        synchronized (this) {
            currentSize = source.size();
            if (!dirty && observedSourceSize == currentSize) {
                return visible;
            }
            ArrayList<EmiFavorite> filtered = new ArrayList<>(currentSize);
            for (EmiFavorite favorite : source) {
                if (shouldExpose(favorite)) {
                    filtered.add(favorite);
                }
            }
            visible = List.copyOf(filtered);
            observedSourceSize = currentSize;
            dirty = false;
            return visible;
        }
    }

    private static boolean shouldExpose(EmiFavorite favorite) {
        for (EmiStack stack : favorite.getEmiStacks()) {
            var itemStack = stack.getItemStack();
            if (!itemStack.isEmpty() && !ContentVisibilityPolicy.shouldExpose(itemStack)) {
                return false;
            }
        }
        return true;
    }
}
