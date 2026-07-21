package com.spirit.koil.api.screen;

import com.spirit.client.gui.ide.FileExplorerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side screen registry. Koil and compatible mods may register stable IDs
 * without giving a server arbitrary class-loading or reflection access.
 */
public final class KoilRemoteScreenRegistry {
    private static final Map<String, ScreenFactory> FACTORIES = new ConcurrentHashMap<>();

    private KoilRemoteScreenRegistry() {
    }

    public static void register(String id, ScreenFactory factory) {
        String normalized = normalize(id);
        if (normalized.isBlank() || factory == null) {
            return;
        }
        FACTORIES.put(normalized, factory);
    }

    public static Screen create(MinecraftClient client, Screen parent, String id, String data) {
        ScreenFactory factory = FACTORIES.get(normalize(id));
        return factory == null || client == null ? null : factory.open(client, parent, data == null ? "" : data);
    }

    public static void registerBuiltins() {
        register("minecraft:options", (client, parent, data) -> new OptionsScreen(parent, client.options));
        register("minecraft:multiplayer", (client, parent, data) -> new MultiplayerScreen(parent));
        register("koil:file_explorer", (client, parent, data) -> data.isBlank()
                ? new FileExplorerScreen(parent)
                : FileExplorerScreen.openAtPath(data, parent));
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    public interface ScreenFactory {
        Screen open(MinecraftClient client, Screen parent, String data);
    }
}
