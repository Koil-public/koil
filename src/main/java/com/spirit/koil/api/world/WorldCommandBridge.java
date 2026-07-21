package com.spirit.koil.api.world;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public final class WorldCommandBridge {
    private WorldCommandBridge() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("world")
                    .executes(context -> {
                        openWorldScreen();
                        return 1;
                    })
                    .then(literal("info")
                            .executes(context -> {
                                sendWorldInfo();
                                return 1;
                            })));
        });
    }

    private static void openWorldScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.send(() -> client.setScreen(new SelectWorldScreen(commandParent(client))));
    }

    private static void sendWorldInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            sendLine(header("World Info").append(Text.literal(" unavailable.").formatted(Formatting.RED)));
            return;
        }

        BlockPos pos = client.player.getBlockPos();
        RegistryEntry<Biome> biome = client.world.getBiome(pos);
        String biomeId = biome.getKey().map(key -> key.getValue().toString()).orElse("unknown");
        List<Text> lines = new ArrayList<>();

        lines.add(header("World Info"));
        lines.add(info("Type", client.isInSingleplayer() ? "singleplayer / integrated" : "remote server world"));
        lines.add(info("Dimension", client.world.getRegistryKey().getValue().toString()));
        lines.add(info("Biome", biomeId));
        lines.add(info("Position", pos.toShortString()));
        lines.add(info("Spawn", client.world.getSpawnPos().toShortString()));
        lines.add(info("Difficulty", client.world.getDifficulty().getName().toLowerCase(Locale.ROOT)));
        lines.add(info("Weather", client.world.isThundering() ? "thunder" : client.world.isRaining() ? "rain" : "clear"));
        lines.add(info("Time", String.valueOf(client.world.getTimeOfDay())));
        lines.add(info("Day", String.valueOf(client.world.getTimeOfDay() / 24000L)));
        lines.add(info("Moon Phase", String.valueOf(client.world.getMoonPhase())));
        lines.add(info("Bottom / Top Y", client.world.getBottomY() + " / " + client.world.getTopY()));
        lines.add(info("Coord Scale", String.valueOf(client.world.getDimension().coordinateScale())));
        lines.add(info("Block Light", String.valueOf(client.world.getLightLevel(pos))));

        if (client.getServer() != null) {
            lines.add(info("Level Name", safe(client.getServer().getSaveProperties().getLevelName(), "unknown")));
            lines.add(info("Seed", String.valueOf(client.getServer().getOverworld().getSeed())));
            lines.add(info("Hardcore", String.valueOf(client.getServer().getSaveProperties().isHardcore())));
            lines.add(info("Commands", String.valueOf(client.getServer().getSaveProperties().areCommandsAllowed())));
            lines.add(info("Game Mode", client.getServer().getDefaultGameMode().getName()));
        }

        for (Text line : lines) {
            sendLine(line);
        }
    }

    private static Screen commandParent(MinecraftClient client) {
        if (client == null || client.currentScreen instanceof ChatScreen) {
            return null;
        }
        return client.currentScreen;
    }

    private static MutableText header(String text) {
        return Text.literal("- ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal(text).formatted(Formatting.WHITE));
    }

    private static MutableText info(String label, String value) {
        return Text.literal("• ").formatted(Formatting.DARK_GREEN)
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
