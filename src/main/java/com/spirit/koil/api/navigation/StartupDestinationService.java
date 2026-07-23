package com.spirit.koil.api.navigation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * One-shot client bootstrap destination. The paired config keys intentionally
 * follow Koil's enable/value naming convention so ModConfigScreen renders them
 * as one dependent control.
 */
public final class StartupDestinationService {
    public static final String ENABLE_KEY = "enableStartupDestination";
    public static final String VALUE_KEY = "startupDestination";
    private static final Path CONFIG_PATH = Path.of("koil", "sys", "config.json");
    private static final int TITLE_SETTLE_TICKS = 20;

    private static boolean attempted;
    private static int titleTicks;

    private StartupDestinationService() {
    }

    public static void initialize() {
        ensureConfigKeys();
    }

    public static void tick(MinecraftClient client) {
        if (attempted || client == null || client.world != null || client.getNetworkHandler() != null) {
            return;
        }
        if (!(client.currentScreen instanceof TitleScreen)) {
            titleTicks = 0;
            return;
        }
        if (++titleTicks < TITLE_SETTLE_TICKS) {
            return;
        }
        JsonObject config = readConfig();
        if (!booleanValue(config, ENABLE_KEY, false)) {
            attempted = true;
            return;
        }
        String destination = stringValue(config, VALUE_KEY).trim();
        if (destination.isBlank()) {
            attempted = true;
            return;
        }
        attempted = true;
        open(client, destination);
    }

    private static void open(MinecraftClient client, String rawDestination) {
        String destination = rawDestination.trim();
        String lower = destination.toLowerCase(Locale.ROOT);
        if (lower.startsWith("world:")) {
            openWorld(client, destination.substring("world:".length()).trim());
            return;
        }
        if (lower.startsWith("server:")) {
            openServer(client, destination.substring("server:".length()).trim());
            return;
        }
        String worldFolder = resolveWorldFolder(client, destination);
        if (!worldFolder.isBlank()) {
            openWorld(client, worldFolder);
        } else {
            openServer(client, destination);
        }
    }

    private static void openWorld(MinecraftClient client, String requestedWorld) {
        String folder = resolveWorldFolder(client, requestedWorld);
        if (folder.isBlank()) {
            return;
        }
        Screen parent = client.currentScreen == null ? new TitleScreen() : client.currentScreen;
        client.createIntegratedServerLoader().start(parent, folder);
    }

    private static String resolveWorldFolder(MinecraftClient client, String requestedWorld) {
        if (client == null || requestedWorld == null || requestedWorld.isBlank()) {
            return "";
        }
        Path saves = client.runDirectory.toPath().toAbsolutePath().normalize().resolve("saves");
        if (!Files.isDirectory(saves)) {
            return "";
        }
        String requested = requestedWorld.trim();
        try (var stream = Files.list(saves)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> {
                        String folder = path.getFileName().toString();
                        return folder.equalsIgnoreCase(requested)
                                || levelName(path).equalsIgnoreCase(requested);
                    })
                    .map(path -> path.getFileName().toString())
                    .findFirst()
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String levelName(Path worldPath) {
        try {
            NbtCompound root = NbtIo.readCompressed(worldPath.resolve("level.dat").toFile());
            NbtCompound data = root == null ? null : root.getCompound("Data");
            return data == null ? "" : data.getString("LevelName");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void openServer(MinecraftClient client, String requestedServer) {
        if (requestedServer == null || requestedServer.isBlank()) {
            return;
        }
        ServerInfo saved = findSavedServer(client, requestedServer.trim());
        String addressText = saved == null ? requestedServer.trim() : saved.address;
        if (!ServerAddress.isValid(addressText)) {
            return;
        }
        ServerAddress address = ServerAddress.parse(addressText);
        ServerInfo info = saved == null
                ? new ServerInfo(addressText, addressText, false)
                : saved;
        Screen parent = client.currentScreen == null ? new TitleScreen() : client.currentScreen;
        ConnectScreen.connect(parent, client, address, info, false);
    }

    private static ServerInfo findSavedServer(MinecraftClient client, String requested) {
        try {
            ServerList list = new ServerList(client);
            list.loadFile();
            for (int index = 0; index < list.size(); index++) {
                ServerInfo info = list.get(index);
                if (info != null && (info.name.equalsIgnoreCase(requested) || info.address.equalsIgnoreCase(requested))) {
                    return info;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void ensureConfigKeys() {
        try {
            JsonObject config = readConfig();
            boolean changed = false;
            if (!config.has(ENABLE_KEY)) {
                config.addProperty(ENABLE_KEY, false);
                changed = true;
            }
            if (!config.has(VALUE_KEY)) {
                config.addProperty(VALUE_KEY, "");
                changed = true;
            }
            if (changed) {
                Path parent = CONFIG_PATH.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        CONFIG_PATH,
                        new GsonBuilder().setPrettyPrinting().create().toJson(config),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            }
        } catch (Exception ignored) {
        }
    }

    private static JsonObject readConfig() {
        try {
            if (!Files.isRegularFile(CONFIG_PATH)) {
                return new JsonObject();
            }
            return JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String stringValue(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
