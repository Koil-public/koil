package com.spirit.koil.api.registry.integration;

import com.spirit.koil.api.registry.ContentActivationEvents;
import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import com.spirit.koil.api.registry.DynamicContentHolderRegistry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps JEI's runtime ingredient index synchronized with active world Content. */
@JeiPlugin
public final class ContentJeiVisibilityPlugin implements IModPlugin {
    private static final Identifier PLUGIN_ID = new Identifier("koil", "content_visibility");

    private final AtomicBoolean listenerRegistered = new AtomicBoolean();
    private final Set<String> previouslyInactive = new HashSet<>();
    private volatile IJeiRuntime runtime;

    @Override
    public Identifier getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        previouslyInactive.clear();
        refresh();
        if (listenerRegistered.compareAndSet(false, true)) {
            ContentActivationEvents.register(snapshot -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(this::refresh);
            });
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        previouslyInactive.clear();
    }

    private void refresh() {
        IJeiRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            return;
        }

        Set<String> inactive = new HashSet<>(ContentVisibilityPolicy.inactiveIds());
        var remove = new ArrayList<ItemStack>();
        var restore = new ArrayList<ItemStack>();
        for (DynamicContentHolderRegistry.Holder holder : DynamicContentHolderRegistry.holders().values()) {
            if (inactive.contains(holder.id())) {
                remove.add(new ItemStack(holder.item()));
            } else if (previouslyInactive.contains(holder.id())) {
                restore.add(new ItemStack(holder.item()));
            }
        }

        IIngredientManager ingredients = currentRuntime.getIngredientManager();
        if (!remove.isEmpty()) {
            ingredients.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, remove);
        }
        if (!restore.isEmpty()) {
            ingredients.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, restore);
        }
        previouslyInactive.clear();
        previouslyInactive.addAll(inactive);
    }
}
