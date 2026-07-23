package com.spirit.koil.api.registry.integration;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared, optional-dependency-safe filtering for item-viewer saved-entry views.
 *
 * <p>Viewer integrations pass their entry objects through this boundary. Koil
 * filters a copied presentation list and never mutates the viewer's persistent
 * favorites/bookmarks.</p>
 */
public final class ContentViewerSavedEntryFilter {
    private ContentViewerSavedEntryFilter() {
    }

    public static List<?> filterReiFavorites(List<?> favorites) {
        return filter(favorites, ContentViewerSavedEntryFilter::inactiveReiFavorite);
    }

    public static List<?> filterJeiElements(List<?> elements) {
        return filter(elements, ContentViewerSavedEntryFilter::inactiveJeiElement);
    }

    private static List<?> filter(List<?> source, EntryTest test) {
        if (source == null || source.isEmpty() || !ContentVisibilityPolicy.hasInactiveHolders()) {
            return source;
        }
        ArrayList<Object> visible = null;
        for (int index = 0; index < source.size(); index++) {
            Object entry = source.get(index);
            if (test.isInactive(entry)) {
                if (visible == null) {
                    visible = new ArrayList<>(source.subList(0, index));
                }
            } else if (visible != null) {
                visible.add(entry);
            }
        }
        return visible == null ? source : List.copyOf(visible);
    }

    private static boolean inactiveReiFavorite(Object favorite) {
        Object entryStack = invokeNoArgs(favorite, "toStack");
        Object value = invokeNoArgs(entryStack, "getValue");
        return value instanceof ItemStack stack && !ContentVisibilityPolicy.shouldExpose(stack);
    }

    private static boolean inactiveJeiElement(Object element) {
        Object typedIngredient = invokeNoArgs(element, "getTypedIngredient");
        Object ingredient = invokeNoArgs(typedIngredient, "getIngredient");
        return ingredient instanceof ItemStack stack && !ContentVisibilityPolicy.shouldExpose(stack);
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A viewer update may change an internal entry shape. Fail open so an
            // optional integration can never crash the game.
            return null;
        }
    }

    @FunctionalInterface
    private interface EntryTest {
        boolean isInactive(Object entry);
    }
}
