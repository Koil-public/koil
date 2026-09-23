package com.spirit.koil.api.design.particle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One intent/contact router for virtual-world gameplay interactions.
 * Physical contact is deliberately not equivalent to using an item on a block.
 */
public final class VirtualInteractionRouter {
    public enum Action {
        PHYSICAL_CONTACT,
        BLOCK_USE,
        ITEM_USE_ON_BLOCK,
        PROJECTILE_HIT
    }

    private record Pair(long actorId, long blockId) { }

    private final VirtualBlockWorld blockWorld;
    private final Set<Pair> projectileContacts = new HashSet<>();

    public VirtualInteractionRouter(VirtualBlockWorld blockWorld) {
        this.blockWorld = blockWorld;
    }

    public void clear() {
        projectileContacts.clear();
    }

    /**
     * Routes pending explicit input intents and edge-triggered projectile contacts.
     * Ordinary item/block contact only publishes contact metadata.
     */
    public void update(Collection<? extends VirtualBlockWorld.SpriteNode> nodes,
                       VirtualGeometryWorld geometry) {
        if (nodes == null || geometry == null) {
            projectileContacts.clear();
            return;
        }
        List<VirtualBlockWorld.SpriteNode> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingLong(VirtualBlockWorld.SpriteNode::id));
        Set<Pair> currentProjectileContacts = new HashSet<>();

        for (VirtualBlockWorld.SpriteNode node : ordered) {
            if (node == null || node.removed()) continue;
            if (node.blockNode()) {
                if (bool(node.data("__koil_pending_block_use"))) {
                    blockWorld.useBlock(node.id());
                    node.data("__koil_pending_block_use", false);
                    node.data("__koil_last_virtual_action", Action.BLOCK_USE.name().toLowerCase());
                }
                continue;
            }

            Set<Long> touching = geometry.touchingBlockIds(node);
            publishPhysicalContact(node, touching);

            if (node.itemNode() && bool(node.data("__koil_pending_item_use"))) {
                long target = chooseTargetBlock(node, geometry);
                boolean handled = target >= 0L && blockWorld.interactItemWithBlock(target, node.id());
                node.data("__koil_pending_item_use", false);
                node.data("__koil_last_virtual_action", Action.ITEM_USE_ON_BLOCK.name().toLowerCase());
                node.data("__koil_last_virtual_action_handled", handled);
                node.data("__koil_last_virtual_action_block", target);
            }

            if (isProjectile(node)) {
                for (long blockId : touching) {
                    Pair pair = new Pair(node.id(), blockId);
                    currentProjectileContacts.add(pair);
                    if (!projectileContacts.contains(pair)) {
                        boolean handled = blockWorld.notifyProjectileHit(blockId, node.id());
                        node.data("__koil_last_virtual_action", Action.PROJECTILE_HIT.name().toLowerCase());
                        node.data("__koil_last_virtual_action_handled", handled);
                        node.data("__koil_last_virtual_action_block", blockId);
                    }
                }
            }
        }

        projectileContacts.clear();
        projectileContacts.addAll(currentProjectileContacts);
    }

    private long chooseTargetBlock(VirtualBlockWorld.SpriteNode actor, VirtualGeometryWorld geometry) {
        Set<Long> candidates = geometry.interactionBlockIds(actor);
        if (candidates.isEmpty()) return -1L;
        long requested = longData(actor.data("__koil_pending_item_use_target"), -1L);
        actor.data("__koil_pending_item_use_target", -1L);
        if (requested >= 0L && candidates.contains(requested)) return requested;
        return geometry.bestInteractionBlockId(actor);
    }

    private void publishPhysicalContact(VirtualBlockWorld.SpriteNode actor, Set<Long> touching) {
        if (actor == null) return;
        if (touching == null || touching.isEmpty()) {
            actor.data("__koil_world_touching_blocks", "");
            actor.data("__koil_world_touching_block_count", 0);
            return;
        }
        LinkedHashSet<Long> ordered = new LinkedHashSet<>(touching.stream().sorted().toList());
        StringBuilder value = new StringBuilder();
        for (long id : ordered) {
            if (value.length() > 0) value.append(',');
            value.append(id);
        }
        actor.data("__koil_world_touching_blocks", value.toString());
        actor.data("__koil_world_touching_block_count", ordered.size());
        actor.data("__koil_last_virtual_contact", Action.PHYSICAL_CONTACT.name().toLowerCase());
    }

    private static boolean isProjectile(VirtualBlockWorld.SpriteNode node) {
        return node.hasTag("projectile_item") || node.hasTag("entity:projectile");
    }

    private static boolean bool(String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    private static long longData(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
