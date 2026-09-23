package com.spirit.koil.api.design.sprite.gameplay;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime coverage snapshot for the detached non-UI gameplay layer.
 *
 * <p>Generic means the content still receives registry visuals/physics/state but
 * has no specialized detached use action. This report deliberately does not call
 * such content "fully supported" merely because it can spawn.</p>
 */
public final class GameplayCoverageReport {
    public record Summary(int itemTotal, int itemSceneHandled, int itemGeneric,
                          int blockTotal, int blockSceneUseHandled, int blockGeneric,
                          List<String> genericItemExamples, List<String> genericBlockExamples) {
        public String compact() {
            return "items=" + itemSceneHandled + "/" + itemTotal + " scene-use (" + itemGeneric + " generic), "
                    + "blocks=" + blockSceneUseHandled + "/" + blockTotal + " bare-use (" + blockGeneric + " generic)";
        }
    }

    private GameplayCoverageReport() { }

    public static Summary snapshot(BlockInteractionSystem blockInteractions) {
        int itemTotal = 0;
        int itemHandled = 0;
        List<String> genericItems = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            itemTotal++;
            boolean handled = GameplayAdapterRegistry.itemAdapter(item) != null
                    || ItemCapabilityRegistry.profile(item).sceneHandled()
                    || item == net.minecraft.item.Items.GLOWSTONE
                    || item == net.minecraft.item.Items.HONEYCOMB;
            if (handled) itemHandled++;
            else if (genericItems.size() < 24) genericItems.add(String.valueOf(Registries.ITEM.getId(item)));
        }

        int blockTotal = 0;
        int blockHandled = 0;
        List<String> genericBlocks = new ArrayList<>();
        for (Block block : Registries.BLOCK) {
            blockTotal++;
            BlockState state = block.getDefaultState();
            boolean handled = blockInteractions != null && blockInteractions.owns(state);
            if (handled) blockHandled++;
            else if (genericBlocks.size() < 24) genericBlocks.add(String.valueOf(Registries.BLOCK.getId(block)));
        }
        return new Summary(itemTotal, itemHandled, itemTotal - itemHandled,
                blockTotal, blockHandled, blockTotal - blockHandled,
                List.copyOf(genericItems), List.copyOf(genericBlocks));
    }
}
