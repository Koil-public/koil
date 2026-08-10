package com.spirit.koil.api.performance;

import com.sun.management.OperatingSystemMXBean;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackProfile;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL11;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

public final class PerformanceHardwareScanner {
    private static final List<String> OPTIMIZATION_MOD_IDS = List.of(
            "sodium", "lithium", "iris", "ferritecore", "immediatelyfast", "entityculling",
            "modernfix", "starlight", "c2me", "lazydfu", "krypton", "moreculling", "memoryleakfix"
    );
    private static PerformanceHardwareProfile cachedProfile;
    private static long cachedAtMillis;

    private PerformanceHardwareScanner() {
    }

    public static PerformanceHardwareProfile scan(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (cachedProfile != null && now - cachedAtMillis < 30_000L) {
            return cachedProfile;
        }
        List<String> mods = FabricLoader.getInstance().getAllMods().stream()
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getId() + "@" + metadata.getVersion().getFriendlyString())
                .sorted()
                .toList();
        List<String> optimizationMods = OPTIMIZATION_MOD_IDS.stream()
                .filter(id -> FabricLoader.getInstance().isModLoaded(id))
                .toList();
        DisplayMode displayMode = detectDisplayMode(client);
        String[] gl = detectGlStrings();
        PerformanceHardwareProfile profile = new PerformanceHardwareProfile(
                now,
                System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""),
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                detectSystemMemoryMb(),
                gl[0],
                gl[1],
                gl[2],
                "unavailable",
                probeStorageSpeed(),
                displayMode.width(),
                displayMode.height(),
                displayMode.refreshRate(),
                "unknown",
                minecraftVersion(),
                mods,
                optimizationMods,
                enabledResourcePacks(client),
                FabricLoader.getInstance().isModLoaded("iris") || FabricLoader.getInstance().isModLoaded("oculus")
        );
        cachedProfile = profile;
        cachedAtMillis = now;
        PerformanceJsonStore.write(PerformancePaths.HARDWARE_PROFILE, profile);
        return profile;
    }

    public static long systemMemoryAvailableMb() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean osBean) {
                return Math.max(0L, osBean.getFreeMemorySize() / 1024L / 1024L);
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static long detectSystemMemoryMb() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean osBean) {
                return Math.max(0L, osBean.getTotalMemorySize() / 1024L / 1024L);
            }
        } catch (Throwable ignored) {
        }
        return 0L;
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

    private static DisplayMode detectDisplayMode(MinecraftClient client) {
        try {
            long monitor = 0L;
            if (client != null && client.getWindow() != null) {
                monitor = GLFW.glfwGetWindowMonitor(client.getWindow().getHandle());
            }
            if (monitor == 0L) {
                monitor = GLFW.glfwGetPrimaryMonitor();
            }
            if (monitor != 0L) {
                GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                if (mode != null) {
                    return new DisplayMode(mode.width(), mode.height(), mode.refreshRate());
                }
            }
        } catch (Throwable ignored) {
        }
        int width = client != null && client.getWindow() != null ? client.getWindow().getWidth() : 0;
        int height = client != null && client.getWindow() != null ? client.getWindow().getHeight() : 0;
        return new DisplayMode(width, height, 0);
    }

    private static double probeStorageSpeed() {
        Path file = PerformancePaths.ROOT.resolve("storage_probe.tmp");
        int bytesPerPass = 4 * 1024 * 1024;
        try {
            Files.createDirectories(PerformancePaths.ROOT);
            ByteBuffer writeBuffer = ByteBuffer.allocateDirect(bytesPerPass);
            for (int i = 0; i < bytesPerPass; i++) {
                writeBuffer.put((byte) (i * 31));
            }
            writeBuffer.flip();
            long start = System.nanoTime();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                while (writeBuffer.hasRemaining()) {
                    channel.write(writeBuffer);
                }
                channel.force(true);
            }
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(bytesPerPass);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                while (readBuffer.hasRemaining() && channel.read(readBuffer) >= 0) {
                }
            }
            long end = System.nanoTime();
            Files.deleteIfExists(file);
            double seconds = Math.max(0.001D, (end - start) / 1_000_000_000.0D);
            return Math.round(((bytesPerPass * 2.0D / 1024.0D / 1024.0D) / seconds) * 10.0D) / 10.0D;
        } catch (Exception ignored) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignoredAgain) {
            }
            return 0.0D;
        }
    }

    private static String minecraftVersion() {
        try {
            return SharedConstants.getGameVersion().getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
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

    private record DisplayMode(int width, int height, int refreshRate) {
    }
}
