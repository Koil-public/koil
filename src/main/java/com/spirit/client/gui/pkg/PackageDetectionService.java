package com.spirit.client.gui.pkg;

import com.spirit.client.gui.main.FirstLaunchDownloadScreen;
import com.spirit.client.gui.main.FirstLaunchTermsScreen;
import com.spirit.koil.api.util.file.KoilPackageManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.spirit.Main.isFirstLaunchPending;

public final class PackageDetectionService {
    private static final int SCAN_INTERVAL_TICKS = 40;
    private static final Map<String, Long> PROMPTED_PACKAGES = new HashMap<>();
    private static int ticksUntilScan;

    private PackageDetectionService() {
    }

    public static void tick(MinecraftClient client) {
        if (client == null || isFirstLaunchPending() || isBlockedScreen(client.currentScreen)) {
            return;
        }
        if (!(client.currentScreen instanceof TitleScreen)) {
            return;
        }

        if (ticksUntilScan > 0) {
            ticksUntilScan--;
            return;
        }
        ticksUntilScan = SCAN_INTERVAL_TICKS;

        tryOpenPackagePrompt(client, client.currentScreen);
    }

    public static boolean tryOpenPackagePrompt(MinecraftClient client, Screen parent) {
        if (client == null || isFirstLaunchPending() || isBlockedScreen(client.currentScreen)) {
            return false;
        }

        KoilPackageManager.PendingPackage pendingPackage = firstDetectablePendingPackage();
        if (pendingPackage == null || wasPrompted(pendingPackage.source())) {
            return false;
        }

        rememberPrompted(pendingPackage.source());
        client.setScreen(new PackageInstallScreen(parent, pendingPackage));
        return true;
    }

    private static KoilPackageManager.PendingPackage firstDetectablePendingPackage() {
        return KoilPackageManager.findPendingPackages().stream()
                .filter(pendingPackage -> isKoilPackagePkgName(pendingPackage.source()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isBlockedScreen(Screen screen) {
        return screen instanceof FirstLaunchTermsScreen
                || screen instanceof FirstLaunchDownloadScreen
                || screen instanceof PackageInstallScreen;
    }

    private static boolean wasPrompted(File source) {
        String key = sourceKey(source);
        Long lastModified = PROMPTED_PACKAGES.get(key);
        return lastModified != null && lastModified == sourceLastModified(source);
    }

    private static void rememberPrompted(File source) {
        PROMPTED_PACKAGES.put(sourceKey(source), sourceLastModified(source));
    }

    private static String sourceKey(File source) {
        return source == null ? "" : source.getAbsoluteFile().toPath().normalize().toString();
    }

    private static long sourceLastModified(File source) {
        return source == null ? -1L : source.lastModified();
    }

    private static boolean isKoilPackagePkgName(File source) {
        if (source == null) {
            return false;
        }
        String name = source.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        String prefix = "koil-package-pkg";
        return name.startsWith(prefix) && name.length() > prefix.length();
    }
}
