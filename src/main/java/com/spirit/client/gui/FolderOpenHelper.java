package com.spirit.client.gui;

import com.spirit.client.gui.ide.FileExplorerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;

import java.io.File;
import java.util.List;

public final class FolderOpenHelper {
    public static final String ACTION_KOIL_EXPLORER = "folder_open:koil_explorer";
    public static final String ACTION_SYSTEM = "folder_open:system";
    private static final List<PopupMenu.MenuEntry> MENU_ENTRIES = List.of(
            new PopupMenu.MenuEntry(ACTION_KOIL_EXPLORER, "in Explorer"),
            new PopupMenu.MenuEntry(ACTION_SYSTEM, "with Default Application")
    );

    private FolderOpenHelper() {
    }

    public static List<PopupMenu.MenuEntry> menuEntries() {
        return MENU_ENTRIES;
    }

    public static void handleAction(MinecraftClient client, File folder, String actionId) {
        if (client == null || folder == null || actionId == null || actionId.isBlank()) {
            return;
        }
        if (!folder.exists()) {
            folder.mkdirs();
        }
        switch (actionId) {
            case ACTION_KOIL_EXPLORER -> client.setScreen(FileExplorerScreen.openAtPath(ScreenActionHelper.normalizeToInstanceRelativePath(folder.getAbsolutePath())));
            case ACTION_SYSTEM -> Util.getOperatingSystem().open(folder.toURI());
            default -> {
            }
        }
    }
}
