package com.spirit.client.gui;

import com.google.gson.JsonPrimitive;
import com.spirit.client.gui.datapack.DatapackScreen;
import com.spirit.client.gui.ide.FileEditorScreen;
import com.spirit.client.gui.ide.FileExplorerScreen;
import com.spirit.client.gui.mod.ModConfigScreen;
import com.spirit.client.gui.mod.ModScreen;
import com.spirit.client.gui.resourcepack.ResourcePackMenuScreen;
import com.spirit.client.gui.resourcepack.ResourcepackScreen;
import com.spirit.client.gui.main.KoilMenuScreen;
import com.spirit.client.gui.measure.PixelDifferenceOverlay;
import com.spirit.client.gui.measure.PixelMagnifierOverlay;
import com.spirit.client.gui.mod.ModMenuScreen;
import com.spirit.client.gui.update.UpdateScreen;
import com.spirit.client.gui.video.KoilVideoOptionsScreen;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.audio.AudioManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.DirectConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.*;
import net.minecraft.client.gui.screen.pack.ExperimentalWarningScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.EditWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static com.spirit.Main.SUBLOGGER;

@Environment(EnvType.CLIENT)
public final class VanillaScreenToolResolver {
    private static final List<Provider> EXTRA_PROVIDERS = new ArrayList<>();

    private VanillaScreenToolResolver() {
    }

    public static void registerProvider(Provider provider) {
        if (provider != null) {
            EXTRA_PROVIDERS.add(provider);
        }
    }

    public static <T extends Screen> void registerScreen(Class<T> screenClass, Function<T, ResolvedScreenTools> factory) {
        if (screenClass == null || factory == null) {
            return;
        }
        registerProvider(screen -> {
            if (!screenClass.isInstance(screen)) {
                return null;
            }
            return factory.apply(screenClass.cast(screen));
        });
    }

    public static <T extends Screen> void registerSimpleScreen(Class<T> screenClass, String displayName, Function<T, String> pathResolver) {
        registerScreen(screenClass, screen -> createDefaultTools(screen, displayName, pathResolver == null ? null : pathResolver.apply(screen)));
    }

    public static ResolvedScreenTools resolve(Screen screen) {
        if (screen == null) {
            return null;
        }

        for (Provider provider : EXTRA_PROVIDERS) {
            ResolvedScreenTools resolved = provider.resolve(screen);
            if (resolved != null) {
                return resolved;
            }
        }

        return resolveBuiltin(screen);
    }

    private static ResolvedScreenTools resolveBuiltin(Screen screen) {
        String displayName = resolveDisplayName(screen);
        String path = resolvePath(screen);
        return createDefaultTools(screen, displayName, path);
    }

    private static @Nullable ResolvedScreenTools createDefaultTools(Screen screen, String displayName, @Nullable String path) {
        boolean hasPath = path != null && !path.isBlank();
        List<PopupMenu.MenuEntry> actions = new ArrayList<>();
        actions.add(new PopupMenu.MenuEntry("info", "Screen Info"));
        if (hasPath) {
            actions.add(new PopupMenu.MenuEntry("open_explorer", "Open In Explorer"));
        }
        if (hasPath && supportsEditor(screen, path)) {
            actions.add(new PopupMenu.MenuEntry("open_editor", "Open In Editor"));
        }
        actions.add(new PopupMenu.MenuEntry("screen_tools", "Screen Tools..."));
        actions.add(new PopupMenu.MenuEntry("ui_appearance", "Appearance..."));
        if (AudioManager.hasActiveAudio()) {
            actions.add(new PopupMenu.MenuEntry("media_controls", "Media controls..."));
        }

        List<String> infoLines = buildInfoLines(screen, displayName, path, actions);
        return new ResolvedScreenTools(displayName, path, actions, infoLines, defaultActionHandler());
    }

    private static ToolActionHandler defaultActionHandler() {
        return (actionId, tools, mouseX, mouseY, screenWidth, screenHeight, infoPopup) -> {
            switch (actionId) {
                case "open_explorer" -> ScreenActionHelper.openInKoilExplorer(tools.path());
                case "open_editor" -> ScreenActionHelper.openInKoilEditor(tools.path());
                case "info" -> infoPopup.openAtPointer(mouseX, mouseY, screenWidth, screenHeight, tools.infoLines());
                case "pixel_difference" -> PixelDifferenceOverlay.arm();
                case "magnifier" -> PixelMagnifierOverlay.arm();
                case "ui_redesign" -> {
                    toggleConfigBoolean("uiRedesign", "screen tools");
                    DesignMusicController.applyConfiguredMusic(true);
                }
                case "ui_panorama" -> toggleConfigBoolean("menuPanorama", "screen tools");
                default -> {
                }
            }
        };
    }

    /** Shared submenu entries for the universal top-right tools popup. */
    public static List<PopupMenu.MenuEntry> appearanceActions() {
        return List.of(
                new PopupMenu.MenuEntry("ui_redesign", toggleLabel("Koil UI Design", "uiRedesign")),
                new PopupMenu.MenuEntry("ui_panorama", toggleLabel("Menu Panorama", "menuPanorama"))
        );
    }

    public static List<PopupMenu.MenuEntry> screenToolActions() {
        return List.of(
                new PopupMenu.MenuEntry("pixel_difference", "Pixel Difference"),
                new PopupMenu.MenuEntry("magnifier", "Magnifier")
        );
    }

    /** Shared control slice backed by Koil's global AudioManager. */
    public static List<PopupMenu.MenuEntry> mediaControlActions() {
        if (!AudioManager.hasActiveAudio()) {
            return List.of();
        }
        return List.of(
                new PopupMenu.MenuEntry("media_pause_resume", AudioManager.isAudioPlaying() ? "Pause" : "Resume"),
                new PopupMenu.MenuEntry("media_stop", "Stop")
        );
    }

    public static boolean handleSharedMediaAction(String actionId) {
        if ("media_pause_resume".equals(actionId)) {
            AudioManager.pauseAudio();
            return true;
        }
        if ("media_stop".equals(actionId)) {
            AudioManager.stopAllAudio();
            return true;
        }
        return false;
    }

    private static String toggleLabel(String label, String configKey) {
        return label + ": " + (readConfigBoolean(configKey, false) ? "On" : "Off");
    }

    private static boolean readConfigBoolean(String key, boolean fallback) {
        try {
            var element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", key);
            return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
        } catch (Exception e) {
            SUBLOGGER.logW("Screen tools", "Failed to read " + key + " config value: " + e.getMessage());
            return fallback;
        }
    }

    private static boolean toggleConfigBoolean(String key, String source) {
        boolean nextValue = !readConfigBoolean(key, false);
        try {
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", key, new JsonPrimitive(nextValue));
            SUBLOGGER.logI("Screen tools", source + " set " + key + "=" + nextValue);
        } catch (IOException e) {
            SUBLOGGER.logE("Screen tools", "Failed to update " + key + " config value: " + e.getMessage());
        }
        return nextValue;
    }

    private static String resolveDisplayName(Screen screen) {
        if (screen instanceof SelectWorldScreen || screen instanceof CreateWorldScreen || screen instanceof EditWorldScreen) {
            return "Singleplayer";
        }
        if (screen instanceof MultiplayerScreen || screen instanceof AddServerScreen || screen instanceof DirectConnectScreen) {
            return "Multiplayer";
        }
        if (screen instanceof PackScreen || screen instanceof ExperimentalWarningScreen) {
            return "Resource Packs";
        }
        if (screen instanceof ResourcepackScreen) {
            return "Resource Packs";
        }
        if (screen instanceof DatapackScreen) {
            return "Datapacks";
        }
        if (screen instanceof ModScreen) {
            return "Mods";
        }
        if (screen instanceof FileExplorerScreen) {
            return "File Explorer";
        }
        if (screen instanceof FileEditorScreen) {
            return "File Editor";
        }
        if (screen instanceof ModConfigScreen) {
            return "Config Editor";
        }
        if (screen instanceof ModMenuScreen) {
            return "Mod Menu";
        }
        if (screen instanceof ResourcePackMenuScreen) {
            return "Resource Pack Manager";
        }
        if (screen instanceof KoilMenuScreen) {
            return "Koil Manager";
        }
        if (screen instanceof UpdateScreen) {
            return "Koil Updater";
        }
        if (screen instanceof GameOptionsScreen
                || screen instanceof OptionsScreen
                || screen instanceof ChatOptionsScreen
                || screen instanceof ControlsOptionsScreen
                || screen instanceof KeybindsScreen
                || screen instanceof LanguageOptionsScreen
                || screen instanceof MouseOptionsScreen
                || screen instanceof OnlineOptionsScreen
                || screen instanceof SimpleOptionsScreen
                || screen instanceof SkinOptionsScreen
                || screen instanceof SoundOptionsScreen
                || screen instanceof TelemetryInfoScreen
                || screen instanceof VideoOptionsScreen
                || screen instanceof KoilVideoOptionsScreen
                || screen instanceof AccessibilityOptionsScreen) {
            return "Options";
        }
        if (screen instanceof TitleScreen) {
            return "Minecraft";
        }
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString().trim();
        if (!title.isBlank()) {
            return title;
        }
        return humanize(screen.getClass().getSimpleName());
    }

    private static @Nullable String resolvePath(Screen screen) {
        if (screen instanceof SelectWorldScreen || screen instanceof CreateWorldScreen || screen instanceof EditWorldScreen) {
            return "./saves";
        }
        if (screen instanceof MultiplayerScreen || screen instanceof AddServerScreen || screen instanceof DirectConnectScreen) {
            return "./servers.dat";
        }
        if (screen instanceof PackScreen || screen instanceof ExperimentalWarningScreen) {
            return "./resourcepacks";
        }
        if (screen instanceof ResourcepackScreen) {
            return "./resourcepacks";
        }
        if (screen instanceof DatapackScreen) {
            return resolveDatapackPath((DatapackScreen) screen);
        }
        if (screen instanceof ModScreen) {
            return "./mods";
        }
        if (screen instanceof FileExplorerScreen) {
            return resolveExplorerPath(screen);
        }
        if (screen instanceof FileEditorScreen) {
            return resolveEditorPath(screen);
        }
        if (screen instanceof ModConfigScreen) {
            return resolveConfigPath(screen);
        }
        if (screen instanceof ModMenuScreen) {
            return "./mods";
        }
        if (screen instanceof ResourcePackMenuScreen) {
            return resolveResourcePackManagerPath(screen);
        }
        if (screen instanceof KoilMenuScreen || screen instanceof UpdateScreen) {
            return "./koil";
        }
        if (screen instanceof GameOptionsScreen
                || screen instanceof OptionsScreen
                || screen instanceof ChatOptionsScreen
                || screen instanceof ControlsOptionsScreen
                || screen instanceof KeybindsScreen
                || screen instanceof LanguageOptionsScreen
                || screen instanceof MouseOptionsScreen
                || screen instanceof OnlineOptionsScreen
                || screen instanceof SimpleOptionsScreen
                || screen instanceof SkinOptionsScreen
                || screen instanceof SoundOptionsScreen
                || screen instanceof TelemetryInfoScreen
                || screen instanceof VideoOptionsScreen
                || screen instanceof KoilVideoOptionsScreen
                || screen instanceof AccessibilityOptionsScreen) {
            return "./options.txt";
        }
        return null;
    }

    private static String resolveDatapackPath(DatapackScreen screen) {
        try {
            java.io.File directory = screen.getDatapacksDirectory();
            if (directory == null) {
                return "./saves";
            }
            return ScreenActionHelper.normalizeToInstanceRelativePath(directory.getPath());
        } catch (Exception ignored) {
            return "./saves";
        }
    }

    private static List<String> buildInfoLines(Screen screen, String displayName, @Nullable String path, List<PopupMenu.MenuEntry> actions) {
        List<String> lines = new ArrayList<>();
        lines.add(displayName);
        lines.add("Game Name: " + describeScreenTitle(screen));
        lines.add("Class: " + screen.getClass().getName());
        lines.add("Class File: " + toSourceFilePath(screen.getClass()));
        lines.add("Path: " + ((path == null || path.isBlank()) ? "No default file target" : ScreenActionHelper.normalizeToInstanceRelativePath(path)));
        return lines;
    }

    private static String describeScreenTitle(Screen screen) {
        if (screen.getTitle() == null) {
            return resolveDisplayName(screen);
        }
        String title = screen.getTitle().getString().trim();
        return title.isBlank() ? resolveDisplayName(screen) : title;
    }

    private static boolean supportsEditor(Screen screen, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (screen instanceof MultiplayerScreen || screen instanceof AddServerScreen || screen instanceof DirectConnectScreen) {
            return true;
        }
        if (screen instanceof ModConfigScreen) {
            return true;
        }
        return screen instanceof GameOptionsScreen
                || screen instanceof OptionsScreen
                || screen instanceof ChatOptionsScreen
                || screen instanceof ControlsOptionsScreen
                || screen instanceof KeybindsScreen
                || screen instanceof LanguageOptionsScreen
                || screen instanceof MouseOptionsScreen
                || screen instanceof OnlineOptionsScreen
                || screen instanceof SimpleOptionsScreen
                || screen instanceof SkinOptionsScreen
                || screen instanceof SoundOptionsScreen
                || screen instanceof TelemetryInfoScreen
                || screen instanceof VideoOptionsScreen
                || screen instanceof KoilVideoOptionsScreen
                || screen instanceof AccessibilityOptionsScreen;
    }

    private static String resolveExplorerPath(Screen screen) {
        File directory = readFieldValue(screen, "currentDirectory", File.class);
        if (directory != null) {
            return ScreenActionHelper.normalizeToInstanceRelativePath(directory.getPath());
        }
        return ".";
    }

    private static String resolveEditorPath(Screen screen) {
        File file = readFieldValue(screen, "fileItem", File.class);
        if (file != null) {
            return ScreenActionHelper.normalizeToInstanceRelativePath(file.getPath());
        }
        return ".";
    }

    private static String resolveConfigPath(Screen screen) {
        File file = readFieldValue(screen, "configFile", File.class);
        if (file != null) {
            return ScreenActionHelper.normalizeToInstanceRelativePath(file.getPath());
        }
        return "./koil/sys/config.json";
    }

    private static String resolveResourcePackManagerPath(Screen screen) {
        Path path = readFieldValue(screen, "packDirectory", Path.class);
        if (path != null) {
            return ScreenActionHelper.normalizeToInstanceRelativePath(path.toString());
        }
        return "./resourcepacks";
    }

    private static String toSourceFilePath(Class<?> screenClass) {
        return "src/main/java/" + screenClass.getName().replace('.', '/') + ".java";
    }

    private static <T> @Nullable T readFieldValue(Object target, String fieldName, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String humanize(String simpleName) {
        String base = simpleName.replace("Screen", "");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(base.charAt(i - 1))) {
                builder.append(' ');
            }
            builder.append(c);
        }
        return builder.toString().trim().toLowerCase(Locale.ROOT);
    }

    public interface Provider {
        ResolvedScreenTools resolve(Screen screen);
    }

    @FunctionalInterface
    public interface ToolActionHandler {
        void handle(String actionId, ResolvedScreenTools tools, double mouseX, double mouseY, int screenWidth, int screenHeight, InfoPopup infoPopup);
    }

    public record ResolvedScreenTools(
            String displayName,
            String path,
            List<PopupMenu.MenuEntry> actions,
            List<String> infoLines,
            ToolActionHandler actionHandler
    ) {
    }
}
