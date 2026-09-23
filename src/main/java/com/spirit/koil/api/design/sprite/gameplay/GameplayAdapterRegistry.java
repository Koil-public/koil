package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public detached gameplay extension point for custom mod content.
 *
 * <p>Vanilla-family subclasses do not need this registry; Koil's built-in family
 * adapters discover them by Java type. This registry exists for genuinely custom
 * blocks/items whose semantics cannot be derived safely without the mod telling us.</p>
 */
public final class GameplayAdapterRegistry {
    public record ItemContext(ItemActor actor, SceneCellPos cell, float targetX, float targetY,
                              BlockGrid blocks, FluidGrid fluids, ActorWorld actors,
                              SceneProjection projection, SceneEventBus events, long gameTick) { }

    public record BlockContext(SceneCellPos position, BlockState state, BlockGrid blocks,
                               SceneProjection projection, SceneEventBus events, long gameTick) { }

    public interface ItemAdapter {
        default boolean beginUse(ItemContext context) { return false; }
        default boolean releaseUse(ItemContext context) { return false; }
        default boolean useOnBlock(ItemContext context) { return false; }
        default boolean useAtCell(ItemContext context) { return false; }
    }

    public interface BlockAdapter {
        boolean use(BlockContext context);
    }

    private static final Map<Item, ItemAdapter> ITEM_EXACT = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ItemAdapter> ITEM_CLASS = new ConcurrentHashMap<>();
    private static final Map<Block, BlockAdapter> BLOCK_EXACT = new ConcurrentHashMap<>();
    private static final Map<Class<?>, BlockAdapter> BLOCK_CLASS = new ConcurrentHashMap<>();

    private GameplayAdapterRegistry() { }

    public static void register(Item item, ItemAdapter adapter) {
        if (item == null) return;
        if (adapter == null) ITEM_EXACT.remove(item); else ITEM_EXACT.put(item, adapter);
    }

    public static void registerItemClass(Class<? extends Item> type, ItemAdapter adapter) {
        if (type == null) return;
        if (adapter == null) ITEM_CLASS.remove(type); else ITEM_CLASS.put(type, adapter);
    }

    public static void register(Block block, BlockAdapter adapter) {
        if (block == null) return;
        if (adapter == null) BLOCK_EXACT.remove(block); else BLOCK_EXACT.put(block, adapter);
    }

    public static void registerBlockClass(Class<? extends Block> type, BlockAdapter adapter) {
        if (type == null) return;
        if (adapter == null) BLOCK_CLASS.remove(type); else BLOCK_CLASS.put(type, adapter);
    }

    public static ItemAdapter itemAdapter(Item item) {
        if (item == null) return null;
        ItemAdapter exact = ITEM_EXACT.get(item);
        if (exact != null) return exact;
        Class<?> type = item.getClass();
        while (type != null && Item.class.isAssignableFrom(type)) {
            ItemAdapter adapter = ITEM_CLASS.get(type);
            if (adapter != null) return adapter;
            type = type.getSuperclass();
        }
        return null;
    }

    public static BlockAdapter blockAdapter(Block block) {
        if (block == null) return null;
        BlockAdapter exact = BLOCK_EXACT.get(block);
        if (exact != null) return exact;
        Class<?> type = block.getClass();
        while (type != null && Block.class.isAssignableFrom(type)) {
            BlockAdapter adapter = BLOCK_CLASS.get(type);
            if (adapter != null) return adapter;
            type = type.getSuperclass();
        }
        return null;
    }
}
