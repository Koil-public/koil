package com.spirit.koil.api.multiplayer;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.spirit.koil.api.navigation.ClientSessionTransitionCoordinator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public final class ServerCommandBridge {
    private ServerCommandBridge() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("server")
                .executes(context -> {
                    openServerScreen();
                    return 1;
                })
                .then(literal("connect")
                        .then(argument("ip", StringArgumentType.greedyString())
                                .executes(context -> connectToServer(getString(context, "ip")))))
                .then(literal("info")
                        .executes(context -> {
                            sendServerInfo();
                            return 1;
                        }))));
    }

    private static int connectToServer(String rawAddress) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return 0;
        }
        String address = normalizeAddress(rawAddress);
        if (address.isBlank()) {
            sendLine(Text.literal("- ").formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal("Give /servers connect a real address or IP.").formatted(Formatting.RED)));
            return 0;
        }
        ServerAddress parsed;
        try {
            parsed = ServerAddress.parse(address);
        } catch (Throwable throwable) {
            sendLine(Text.literal("- ").formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal("Could not parse server address: " + address).formatted(Formatting.RED)));
            return 0;
        }
        sendLine(Text.literal("- ").formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal("Connecting to ").formatted(Formatting.GRAY))
                .append(Text.literal(address).formatted(Formatting.WHITE)));
        ServerInfo info = new ServerInfo(address, address, false);
        client.send(() -> {
            Screen parent = new MultiplayerScreen(new TitleScreen());
            ClientSessionTransitionCoordinator.run(client,
                    activeClient -> ConnectScreen.connect(parent, activeClient, parsed, info, false));
        });
        return 1;
    }

    private static void openServerScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.send(() -> ClientSessionTransitionCoordinator.openScreen(
                client,
                () -> new MultiplayerScreen(new TitleScreen())
        ));
    }

    private static void sendServerInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        ServerInfo entry = client.getCurrentServerEntry();
        PlayerListEntry self = client.getNetworkHandler() == null ? null : client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        List<Text> lines = new ArrayList<>();

        lines.add(header("Server Info"));
        lines.add(info("Connection", entry == null ? "integrated/local" : safe(entry.address, "unknown")));
        lines.add(info("Type", client.isInSingleplayer() ? "singleplayer / integrated" : "remote multiplayer"));
        lines.add(info("Brand", networkBrand(client)));
        lines.add(info("Ping", self == null ? "unknown" : self.getLatency() + " ms"));
        lines.add(info("Players Listed", client.getNetworkHandler() == null ? "0" : String.valueOf(client.getNetworkHandler().getPlayerList().size())));

        if (entry != null) {
            lines.add(info("Saved Entry", safe(entry.name, "unnamed")));
            lines.add(info("Host", extractHost(entry.address)));
            lines.add(info("Port", extractPort(entry.address)));
            lines.add(info("Version", entry.version == null ? "unknown" : safe(entry.version.getString(), "unknown")));
            lines.add(info("Protocol", String.valueOf(entry.protocolVersion)));
            lines.add(info("Status", entry.online ? "online" : "unresolved/offline"));
            lines.add(info("Flags", serverFlags(entry)));
            lines.add(info("Player Sample", formatPlayerCounts(entry)));
            lines.add(info("Player List Label", entry.playerCountLabel == null ? "unknown" : safe(entry.playerCountLabel.getString(), "unknown")));
            lines.add(info("Cached MOTD", entry.label == null ? "none" : safe(entry.label.getString(), "none")));
            lines.add(info("Favicon", entry.getFavicon() == null ? "none" : entry.getFavicon().length + " bytes"));
        } else if (client.getServer() != null) {
            lines.add(info("Integrated Level", safe(client.getServer().getSaveProperties().getLevelName(), "unknown")));
            lines.add(info("Hardcore", String.valueOf(client.getServer().getSaveProperties().isHardcore())));
            lines.add(info("Commands", String.valueOf(client.getServer().getSaveProperties().areCommandsAllowed())));
        }

        for (Text line : lines) {
            sendLine(line);
        }
    }

    private static String normalizeAddress(String rawAddress) {
        String value = rawAddress == null ? "" : rawAddress.trim();
        if (value.startsWith("minecraft://")) {
            value = value.substring("minecraft://".length());
        } else if (value.startsWith("mc://")) {
            value = value.substring("mc://".length());
        }
        return value;
    }

    private static String networkBrand(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) {
            return "unavailable";
        }
        try {
            Object value = client.getNetworkHandler().getClass().getMethod("getBrand").invoke(client.getNetworkHandler());
            return value == null ? "unknown" : value.toString();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String serverFlags(ServerInfo entry) {
        List<String> flags = new ArrayList<>();
        if (entry.isLocal()) {
            flags.add("LAN");
        }
        if (entry.isSecureChatEnforced()) {
            flags.add("Secure Chat");
        }
        if (entry.getResourcePackPolicy() != null) {
            flags.add(entry.getResourcePackPolicy().name().toLowerCase(Locale.ROOT));
        }
        return flags.isEmpty() ? "none" : String.join(" | ", flags);
    }

    private static String formatPlayerCounts(ServerInfo entry) {
        if (entry == null) {
            return "unknown";
        }
        if (entry.players != null) {
            return entry.players.online() + " / " + entry.players.max();
        }
        if (entry.playerCountLabel != null) {
            return safe(entry.playerCountLabel.getString(), "unknown");
        }
        return "unknown";
    }

    private static String extractHost(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            return close > 0 ? address.substring(1, close) : address;
        }
        int colon = address.lastIndexOf(':');
        if (colon > 0) {
            return address.substring(0, colon);
        }
        return address;
    }

    private static String extractPort(String address) {
        if (address == null || address.isBlank()) {
            return "default";
        }
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close >= 0 && close + 2 <= address.length() && address.charAt(close + 1) == ':') {
                return address.substring(close + 2);
            }
            return "default";
        }
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon + 1 < address.length()) {
            return address.substring(colon + 1);
        }
        return "25565";
    }

    private static MutableText header(String text) {
        return Text.literal("[Koil Server] ").formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal(text).formatted(Formatting.WHITE));
    }

    private static MutableText info(String label, String value) {
        return Text.literal("• ").formatted(Formatting.DARK_AQUA)
                .append(Text.literal(label + ": ").formatted(Formatting.GRAY))
                .append(Text.literal(safe(value, "unknown")).formatted(Formatting.WHITE));
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void sendLine(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && text != null) {
            client.player.sendMessage(text, false);
        }
    }

}
