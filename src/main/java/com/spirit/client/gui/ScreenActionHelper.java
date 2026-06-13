package com.spirit.client.gui;

import com.spirit.client.gui.ide.FileExplorerScreen;
import com.spirit.client.gui.ide.FileEditorScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;

import java.io.File;
import java.nio.file.Path;

public final class ScreenActionHelper {
    private ScreenActionHelper() {
    }

    public static void openInKoilExplorer(String path) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.setScreen(FileExplorerScreen.openAtPath(normalizeExplorerPath(path)));
    }

    public static void openInKoilEditor(String path) {
        openInKoilEditor(path, -1);
    }

    public static void openInKoilEditor(String path, int lineNumber) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || path == null || path.isBlank()) {
            return;
        }
        try {
            File file = new File(path);
            if (!file.isAbsolute()) {
                file = Path.of(path).normalize().toFile();
            }
            if (!file.exists() || !file.isFile()) {
                return;
            }
            client.setScreen(new FileEditorScreen(client.currentScreen, file, null, lineNumber));
        } catch (Exception ignored) {
        }
    }

    public static void openInSystemExplorer(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Util.getOperatingSystem().open(new File(path).toURI());
    }

    public static String normalizeToInstanceRelativePath(String path) {
        return normalizeExplorerPath(path);
    }

    public static String normalizeExplorerPath(String path) {
        if (path == null || path.isBlank()) {
            return ".";
        }
        try {
            Path rawPath = Path.of(path).normalize();
            Path absolutePath = rawPath.isAbsolute() ? rawPath : Path.of(".").toAbsolutePath().normalize().resolve(rawPath).normalize();
            Path instanceRoot = Path.of(".").toAbsolutePath().normalize();
            if (absolutePath.startsWith(instanceRoot)) {
                Path relative = instanceRoot.relativize(absolutePath);
                String normalized = relative.toString().replace("\\", "/");
                return normalized.isBlank() ? "." : "./" + normalized;
            }
            return absolutePath.toString().replace("\\", "/");
        } catch (Exception ignored) {
            return path;
        }
    }
}
