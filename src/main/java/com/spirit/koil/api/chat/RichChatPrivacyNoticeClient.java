package com.spirit.koil.api.chat;

import com.spirit.client.gui.main.KoilMessageToast;
import com.spirit.koil.api.automation.AutomationPresenceNetwork;
import com.spirit.koil.api.automation.AutomationRemoteRunNetwork;
import com.spirit.koil.api.stats.global.GlobalActivityApi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class RichChatPrivacyNoticeClient {
    private static boolean shownThisConnection;

    private RichChatPrivacyNoticeClient() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> maybeShow(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> shownThisConnection = false);
    }

    private static void maybeShow(MinecraftClient client) {
        if (client == null || shownThisConnection || client.getCurrentServerEntry() == null) {
            return;
        }
        if (!isLikelyKoilServer()) {
            return;
        }
        shownThisConnection = true;
        if (client.getToastManager() != null) {
            client.getToastManager().add(KoilMessageToast.create(
                    client,
                    KoilMessageToast.Type.ANNOUNCEMENT,
                    Text.literal("Private media is not private storage"),
                    Text.literal("Files or media sent with /msg can still be visible to the server and its owners.")
            ));
        }
    }

    private static boolean isLikelyKoilServer() {
        return ClientPlayNetworking.canSend(GlobalActivityApi.REQUEST_CHANNEL)
                || ClientPlayNetworking.canSend(AutomationPresenceNetwork.STATE_SYNC_PACKET)
                || ClientPlayNetworking.canSend(AutomationRemoteRunNetwork.RUN_AS_PACKET);
    }
}
