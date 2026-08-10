package com.spirit.koil.api.model;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;

/** Cheap live-state prefetch for the first expert planning round. */
final class ModelExpertPrefetch {
    private ModelExpertPrefetch() {}

    static String capture() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return "World state is not currently available.";
        var player = client.player;
        String held = player.getMainHandStack().isEmpty() ? "empty"
                : Registries.ITEM.getId(player.getMainHandStack().getItem()) + " x" + player.getMainHandStack().getCount();
        String vehicle = player.getVehicle() == null ? "none" : Registries.ENTITY_TYPE.getId(player.getVehicle().getType()).toString();
        return "Prefetched live state: position=" + round(player.getX()) + "," + round(player.getY()) + "," + round(player.getZ())
                + "; dimension=" + client.world.getRegistryKey().getValue()
                + "; health=" + round(player.getHealth()) + "/" + round(player.getMaxHealth())
                + "; hunger=" + player.getHungerManager().getFoodLevel() + "/20"
                + "; held=" + held + "; vehicle=" + vehicle + ". Treat this as an initial observation and re-inspect after changes.";
    }

    private static double round(double value) { return Math.round(value * 10.0D) / 10.0D; }
}
