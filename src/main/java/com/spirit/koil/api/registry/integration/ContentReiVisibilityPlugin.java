package com.spirit.koil.api.registry.integration;

import com.spirit.koil.api.registry.ContentActivationEvents;
import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import com.spirit.koil.api.registry.DynamicContentHolderRegistry;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps REI's entry index synchronized with the active world's Content definitions. */
public final class ContentReiVisibilityPlugin implements REIClientPlugin {
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean();

    @Override
    public void registerEntries(EntryRegistry registry) {
        refresh(registry);
        if (LISTENER_REGISTERED.compareAndSet(false, true)) {
            ContentActivationEvents.register(snapshot -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> refresh(EntryRegistry.getInstance()));
            });
        }
    }

    private static void refresh(EntryRegistry registry) {
        registry.removeEntryIf(ContentReiVisibilityPlugin::isInactiveItem);
        for (DynamicContentHolderRegistry.Holder holder : DynamicContentHolderRegistry.holders().values()) {
            if (!ContentVisibilityPolicy.shouldExpose(holder.item())) {
                continue;
            }
            EntryStack<ItemStack> entry = EntryStacks.of(holder.item());
            if (!registry.alreadyContain(entry)) {
                registry.addEntry(entry);
            }
        }
        if (!registry.isReloading()) {
            registry.refilter();
        }
    }

    private static boolean isInactiveItem(EntryStack<?> entry) {
        Object value = entry.getValue();
        return value instanceof ItemStack stack
                && !stack.isEmpty()
                && !ContentVisibilityPolicy.shouldExpose(stack.getItem());
    }
}
