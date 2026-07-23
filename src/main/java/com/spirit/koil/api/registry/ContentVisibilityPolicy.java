package com.spirit.koil.api.registry;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Stable API boundary for deciding whether a physical Content holder may be exposed
 * by commands, creative/search surfaces, or third-party item viewers.
 */
public final class ContentVisibilityPolicy {
    private ContentVisibilityPolicy() {
    }

    public static Optional<String> contentId(Item item) {
        return DynamicContentHolderRegistry.contentId(item);
    }

    public static Optional<String> contentId(Block block) {
        return DynamicContentHolderRegistry.contentId(block);
    }

    public static boolean isManaged(Item item) {
        return contentId(item).isPresent();
    }

    public static boolean isActive(Item item) {
        return contentId(item)
                .map(id -> DynamicRegistryManager.instance().activeDefinitions().containsKey(id))
                .orElse(true);
    }

    public static boolean shouldExpose(Item item) {
        return !isManaged(item) || isActive(item);
    }

    public static boolean shouldExpose(ItemStack stack) {
        return stack.isEmpty() || shouldExpose(stack.getItem());
    }

    public static boolean isManaged(Block block) {
        return contentId(block).isPresent();
    }

    public static boolean isActive(Block block) {
        return contentId(block)
                .map(id -> DynamicRegistryManager.instance().activeDefinitions().containsKey(id))
                .orElse(true);
    }

    public static boolean shouldExpose(Block block) {
        return !isManaged(block) || isActive(block);
    }

    /**
     * Allows startup/world-loading work before a world snapshot exists, then enforces
     * strict creation once Koil has identified the loaded world.
     */
    public static boolean mayCreate(Item item) {
        return !hasActiveWorld() || shouldExpose(item);
    }

    public static boolean mayCreate(Block block) {
        return !hasActiveWorld() || shouldExpose(block);
    }

    public static boolean hasActiveWorld() {
        return !DynamicRegistryManager.instance().activeWorldSnapshot().worldId().isBlank();
    }

    public static boolean hasInactiveHolders() {
        var active = DynamicRegistryManager.instance().activeDefinitions();
        for (String id : DynamicContentHolderRegistry.contentIds()) {
            if (!active.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> inactiveIds() {
        return DynamicContentHolderRegistry.holders().keySet().stream()
                .filter(id -> !DynamicRegistryManager.instance().activeDefinitions().containsKey(id))
                .sorted()
                .toList();
    }

    public static List<String> inactiveBlockIds() {
        return DynamicContentHolderRegistry.holders().values().stream()
                .filter(holder -> holder.block() != null)
                .map(DynamicContentHolderRegistry.Holder::id)
                .filter(id -> !DynamicRegistryManager.instance().activeDefinitions().containsKey(id))
                .sorted()
                .toList();
    }
}
