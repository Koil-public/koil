package com.spirit.koil.api.stats.global.client;

import com.spirit.koil.api.stats.global.KoilStatsScreenOpenRequests;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.StatsScreen;

@Environment(EnvType.CLIENT)
public final class GlobalStatsScreenOpener {
    private GlobalStatsScreenOpener() {
    }

    public static void openGlobalStatsScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        KoilStatsScreenOpenRequests.requestGlobalPage();
        client.execute(() -> client.setScreen(new StatsScreen(client.currentScreen, client.player.getStatHandler())));
    }
}
