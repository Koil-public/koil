package com.spirit.koil.api.util.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.console.ConsoleRequestBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.spirit.Main.SUBLOGGER;

public class WindowManager {
    private static final Path WINDOW_SESSION_STATE = Path.of("koil/sys/cache/external-console-session.json");
    private static final Map<ConsoleChannel, Process> CONSOLE_PROCESSES = new EnumMap<>(ConsoleChannel.class);
    private static final Set<ConsoleChannel> REMEMBERED_OPEN_WINDOWS = EnumSet.noneOf(ConsoleChannel.class);
    private static boolean restoredLastSession;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            persistOpenWindows();
            closeAllWindows();
        }, "koil-window-shutdown"));
    }

    private WindowManager() {
    }

    public static void openConsoleWindow(ConsoleChannel channel) {
        try {
            launchExternalConsoleProcess(channel);
            REMEMBERED_OPEN_WINDOWS.add(channel);
            persistOpenWindows();
        } catch (IOException exception) {
            String message = "External console window failed to launch: " + exception.getMessage();
            SUBLOGGER.logE("Window-Management thread", message);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                client.player.sendMessage(Text.literal(message), false);
            }
        }
    }

    public static void closeAllWindows() {
        for (Process process : CONSOLE_PROCESSES.values()) {
            process.destroy();
        }
        CONSOLE_PROCESSES.clear();
    }

    public static void restoreLastSessionWindows() {
        if (restoredLastSession) {
            return;
        }
        restoredLastSession = true;
        try {
            if (!Files.isRegularFile(WINDOW_SESSION_STATE)) {
                return;
            }
            String content = Files.readString(WINDOW_SESSION_STATE, StandardCharsets.UTF_8);
            JsonObject root = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
            JsonArray channels = root.getAsJsonArray("openChannels");
            if (channels == null) {
                return;
            }
            for (var element : channels) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                ConsoleChannel channel = ConsoleChannel.fromId(element.getAsString());
                REMEMBERED_OPEN_WINDOWS.add(channel);
                openConsoleWindow(channel);
            }
        } catch (Exception ignored) {
        }
    }

    private static void launchExternalConsoleProcess(ConsoleChannel channel) throws IOException {
        ConsoleRequestBridge.initializeHost();
        if (!ConsoleRequestBridge.hostReady()) {
            throw new IOException("Console socket bridge is not ready.");
        }
        Process existing = CONSOLE_PROCESSES.get(channel);
        if (existing != null && existing.isAlive()) {
            existing.destroy();
            CONSOLE_PROCESSES.remove(channel);
        }
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = buildExternalClassPath();
        Path launchLog = Path.of("koil/logs/external-console-launch.log");
        try {
            java.nio.file.Files.createDirectories(launchLog.getParent());
        } catch (IOException ignored) {
        }
        Process process = new ProcessBuilder(
                javaBinary,
                "-Djava.awt.headless=false",
                "-cp",
                classPath,
                "com.spirit.koil.api.util.application.ExternalWindowConsole",
                channel.id(),
                String.valueOf(ConsoleRequestBridge.hostPort()),
                ConsoleRequestBridge.hostToken()
        )
                .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog.toFile()))
                .start();
        CONSOLE_PROCESSES.put(channel, process);
        SUBLOGGER.logI("Window-Management thread", "Launched external console process for " + channel.id());
        monitorLaunch(channel, process, launchLog);
    }

    private static String buildExternalClassPath() {
        Set<String> entries = new LinkedHashSet<>();
        String currentClassPath = System.getProperty("java.class.path", "");
        if (!currentClassPath.isBlank()) {
            for (String entry : currentClassPath.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }
        addCodeSource(entries, WindowManager.class);
        addCodeSource(entries, ExternalWindowConsole.class);
        addCodeSource(entries, ConsoleChannel.class);
        addCodeSource(entries, ConsoleRequestBridge.class);
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void addCodeSource(Set<String> entries, Class<?> type) {
        try {
            if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null || type.getProtectionDomain().getCodeSource().getLocation() == null) {
                return;
            }
            entries.add(Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (URISyntaxException ignored) {
        }
    }

    private static void monitorLaunch(ConsoleChannel channel, Process process, Path launchLog) {
        Thread monitor = new Thread(() -> {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                return;
            }
            CONSOLE_PROCESSES.remove(channel, process);
            REMEMBERED_OPEN_WINDOWS.remove(channel);
            persistOpenWindows();
            String message = "External console exited early. See " + launchLog;
            SUBLOGGER.logE("Window-Management thread", message);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                client.send(() -> client.player.sendMessage(Text.literal(message), false));
            }
        }, "koil-external-console-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static void persistOpenWindows() {
        try {
            Files.createDirectories(WINDOW_SESSION_STATE.getParent());
            JsonObject root = new JsonObject();
            JsonArray channels = new JsonArray();
            for (ConsoleChannel channel : REMEMBERED_OPEN_WINDOWS) {
                channels.add(channel.id());
            }
            root.add("openChannels", channels);
            Files.writeString(WINDOW_SESSION_STATE, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
