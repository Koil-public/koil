package com.spirit.koil.api.util.event;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.function.Consumer;

public class AdvancementEventTracker {
    private static final Map<UUID, Set<Identifier>> completedAdvancements = new HashMap<>();

    public static void trackAdvancement(ServerPlayerEntity player, Identifier advancementId, Consumer<ServerPlayerEntity> action) {
        Advancement advancement = player.server.getAdvancementLoader().get(advancementId);
        if (advancement != null) {
            AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
            UUID playerId = player.getUuid();

            completedAdvancements.putIfAbsent(playerId, new HashSet<>());

            if (progress.isDone() && !completedAdvancements.get(playerId).contains(advancementId)) {
                action.accept(player);
                completedAdvancements.get(playerId).add(advancementId);
            }

            if (!progress.isDone() && completedAdvancements.get(playerId).contains(advancementId)) {
                completedAdvancements.get(playerId).remove(advancementId);
            }
        }
    }

    public static void trackAdvancement(Identifier advancementId, Consumer<ServerPlayerEntity> action) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            checkAdvancementOnJoin(player, advancementId, action);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerManager().getPlayerList().forEach(player ->
                trackAdvancement(player, advancementId, action)
        ));
    }

    private static void checkAdvancementOnJoin(ServerPlayerEntity player, Identifier advancementId, Consumer<ServerPlayerEntity> action) {
        Advancement advancement = player.server.getAdvancementLoader().get(advancementId);
        if (advancement != null) {
            AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
            UUID playerId = player.getUuid();

            completedAdvancements.putIfAbsent(playerId, new HashSet<>());

            if (progress.isDone() && !completedAdvancements.get(playerId).contains(advancementId)) {
                completedAdvancements.get(playerId).add(advancementId);
            }
        }
    }
}
