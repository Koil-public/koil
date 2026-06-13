package com.spirit.koil.api.performance;

import com.spirit.Main;
import com.sun.management.OperatingSystemMXBean;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackProfile;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL11;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PerformanceHardwareScanner {
    private static final List<String> OPTIMIZATION_MOD_IDS = List.of(
            "sodium", "lithium", "iris", "ferritecore", "immediatelyfast", "entityculling",
            "modernfix", "starlight", "c2me", "lazydfu", "krypton", "moreculling", "memoryleakfix"
    );

    private PerformanceHardwareScanner() {
    }

    public static PerformanceHardwareProfile scan(MinecraftClient client) {
        List<String> mods = FabricLoader.getInstance().getAllMods().stream()
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getId() + "@" + metadata.getVersion().getFriendlyString())
                .sorted()
                .toList();
        List<String> optimizationMods = OPTIMIZATION_MOD_IDS.stream()
                .filter(id -> FabricLoader.getInstance().isModLoaded(id))
                .toList();

        int width = client != null && client.getWindow() != null ? client.getWindow().getWidth() : 0;
        int height = client != null && client.getWindow() != null ? client.getWindow().getHeight() : 0;
        int refreshRate = detectRefreshRate();
        String[] gl = detectGlStrings();

        PerformanceHardwareProfile profile = new PerformanceHardwareProfile(
                System.currentTimeMillis(),
                System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""),
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                detectSystemMemoryMb(),
                gl[0],
                gl[1],
                gl[2],
                "unknown",
                probeStorageSpeed(),
                width,
                height,
                refreshRate,
                detectBatteryStatus(),
                Main.version(),
                mods,
                optimizationMods,
                enabledResourcePacks(client),
                FabricLoader.getInstance().isModLoaded("iris") || FabricLoader.getInstance().isModLoaded("oculus")
        );
        PerformanceJsonStore.write(PerformancePaths.HARDWARE_PROFILE, profile);
        ensureDefaultColorConfig();
        return profile;
    }

    private static long detectSystemMemoryMb() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean osBean) {
                return Math.max(0L, osBean.getTotalMemorySize() / 1024L / 1024L);
            }
        } catch (Throwable ignored) {
        }
        return Runtime.getRuntime().maxMemory() / 1024L / 1024L;
    }

    private static String[] detectGlStrings() {
        try {
            String vendor = safeGlString(GL11.GL_VENDOR);
            String renderer = safeGlString(GL11.GL_RENDERER);
            String version = safeGlString(GL11.GL_VERSION);
            return new String[]{vendor, renderer, version};
        } catch (Throwable ignored) {
            return new String[]{"unknown", "unknown", "unknown"};
        }
    }

    private static String safeGlString(int key) {
        String value = GL11.glGetString(key);
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static int detectRefreshRate() {
        try {
            long monitor = GLFW.glfwGetPrimaryMonitor();
            if (monitor != 0L) {
                GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                if (mode != null) {
                    return mode.refreshRate();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static double probeStorageSpeed() {
        Path file = PerformancePaths.ROOT.resolve("storage_probe.tmp");
        try {
            Files.createDirectories(PerformancePaths.ROOT);
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            for (int i = 0; i < buffer.capacity(); i++) {
                buffer.put((byte) (i * 31));
            }
            byte[] bytes = buffer.array();
            long start = System.nanoTime();
            Files.write(file, bytes);
            Files.readAllBytes(file);
            long end = System.nanoTime();
            Files.deleteIfExists(file);
            double seconds = Math.max(0.001D, (end - start) / 1_000_000_000.0D);
            return Math.round((2.0D / seconds) * 10.0D) / 10.0D;
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    private static String detectBatteryStatus() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("win")) {
            return "unknown; laptop/battery mode will be inferred from user-selected profile";
        }
        return "unknown";
    }

    private static List<String> enabledResourcePacks(MinecraftClient client) {
        try {
            if (client == null || client.getResourcePackManager() == null) {
                return List.of();
            }
            return client.getResourcePackManager().getEnabledProfiles().stream()
                    .map(ResourcePackProfile::getName)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static void ensureDefaultColorConfig() {
        // Performance color defaults now live in the main design.json tree under "performance".
        // This method remains as a compatibility hook for older scanner call sites.
    }
}
