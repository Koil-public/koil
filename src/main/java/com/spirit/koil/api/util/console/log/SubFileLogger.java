package com.spirit.koil.api.util.console.log;

import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.api.console.ConsoleLogBridge;
import net.fabricmc.loader.impl.FabricLoaderImpl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.spirit.Main.VERSION;
import static com.spirit.Main.isFirstLaunchPending;

public class SubFileLogger {
    private static final Map<String, SubFileLogger> instances = new HashMap<>();
    private static final Lock lock = new ReentrantLock();
    private static final int QUEUE_CAPACITY = 1000;
    private static final long FLUSH_INTERVAL_MS = 120;
    private static volatile ToastSink toastSink = (type, title, description) -> { };
    private static final String LATEST_LOG_FILE_NAME = "latest.log"; // Default log file name

    private BufferedWriter writer;
    private final String logFilePath;
    private String baseFileName;
    private final ConsoleChannel consoleChannel;
    private final DateTimeFormatter timestampFormatter;
    private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread logWriterThread;
    private volatile boolean running = true;
    private boolean crashDetected = false;

    private SubFileLogger(String logFilePath, String baseFileName) {
        this.logFilePath = logFilePath;
        this.timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        this.baseFileName = baseFileName;
        this.consoleChannel = resolveConsoleChannel(logFilePath);

        try {
            Path path = Paths.get(logFilePath).getParent();
            if (path != null && !Files.exists(path)) {
                Files.createDirectories(path);
            }
            writer = new BufferedWriter(new FileWriter(logFilePath, true));
            writeHeader();
        } catch (IOException e) {
            e.printStackTrace();
        }

        logWriterThread = new Thread(this::processLogQueue);
        logWriterThread.setName("Koil-Log-" + baseFileName);
        logWriterThread.setDaemon(true);
        logWriterThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(this::handleShutdown));
    }

    public static void initialize(String identifier, String baseFolderPath, String baseFileName) {
        lock.lock();
        try {
            if (!instances.containsKey(identifier)) {
                String logFilePath = baseFolderPath + "/" + LATEST_LOG_FILE_NAME;
                instances.put(identifier, new SubFileLogger(logFilePath, baseFileName));
            } else {
                instances.get(identifier).logW("Logger thread", "Logger with identifier " + identifier + " has already been initialized.");
            }
        } finally {
            lock.unlock();
        }
    }

    public static SubFileLogger getInstance(String identifier) {
        lock.lock();
        try {
            SubFileLogger logger = instances.get(identifier);
            if (logger == null) {
                throw new IllegalStateException("Logger with identifier " + identifier + " is not initialized. Call initialize() first.");
            }
            return logger;
        } finally {
            lock.unlock();
        }
    }

    public void log(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[ ] | [" + timestamp + "] " + "[" + thread + "]: " + message;
        publishConsoleRecord(ConsoleLevel.PLAIN, timestamp, thread, "", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void log(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[ ] | [" + timestamp + "] " + "[" + thread + "]: " + message;
        if (toast) {
            showToast(ToastType.CONSOLE, "Console - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.PLAIN, timestamp, thread, "", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logI(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[-] | [" + timestamp + "] " + "[" + thread + "/Info]: " + message;
        publishConsoleRecord(ConsoleLevel.INFO, timestamp, thread, "Info", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logI(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[-] | [" + timestamp + "] " + "[" + thread + "/Info]: " + message;
        if (toast) {
            showToast(ToastType.CONSOLE_INFO, "Info - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.INFO, timestamp, thread, "Info", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logW(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[=] | [" + timestamp + "] " + "[" + thread + "/Warn]: " + message;
        publishConsoleRecord(ConsoleLevel.WARN, timestamp, thread, "Warn", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logW(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[=] | [" + timestamp + "] " + "[" + thread + "/Warn]: " + message;
        if (toast) {
            showToast(ToastType.CONSOLE_WARNING, "Warn - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.WARN, timestamp, thread, "Warn", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logE(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[*] | [" + timestamp + "] " + "[" + thread + "/Error]: " + message;
        publishConsoleRecord(ConsoleLevel.ERROR, timestamp, thread, "Error", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logE(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[*] | [" + timestamp + "] " + "[" + thread + "/Error]: " + message;
        if (toast) {
            showToast(ToastType.CONSOLE_ERROR, "Error - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.ERROR, timestamp, thread, "Error", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logF(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[~] | [" + timestamp + "] " + "[" + thread + "/Fatal]: " + message;
        showToast(ToastType.CONSOLE_FATAL, "Fatal - " + thread, "Please refer to the KL");
        publishConsoleRecord(ConsoleLevel.FATAL, timestamp, thread, "Fatal", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logF(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[~] | [" + timestamp + "] " + "[" + thread + "/Fatal]: " + message;
        if (toast) {
            showToast(ToastType.CONSOLE_FATAL, "Fatal - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.FATAL, timestamp, thread, "Fatal", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logD(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[>] | [" + timestamp + "] " + "[" + thread + "/Debug]: " + message;
        publishConsoleRecord(ConsoleLevel.DEBUG, timestamp, thread, "Debug", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logD(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[>] | [" + timestamp + "] " + "[" + thread + "/Debug]: " + message;
        if (toast) {
            showToast(ToastType.CONSOLE_DEBUG, "Debug - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.DEBUG, timestamp, thread, "Debug", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logU(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[&] | [" + timestamp + "] " + "[" + thread + "/Update]: " + message;
        publishConsoleRecord(ConsoleLevel.UPDATE, timestamp, thread, "Update", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logU(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[&] | [" + timestamp + "] " + "[" + thread + "/Update]: " + message;
        if (toast && !isFirstLaunchPending()) {
            showToast(ToastType.CONSOLE_UPDATE, "Update - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.UPDATE, timestamp, thread, "Update", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logO(String thread, String message) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[?] | [" + timestamp + "] " + "[" + thread + "/Unknown]: " + message;
        publishConsoleRecord(ConsoleLevel.OTHER, timestamp, thread, "Unknown", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    public void logO(String thread, String message, boolean toast, String toastMessage) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        String logMessage = "[?] | [" + timestamp + "] " + "[" + thread + "/Unknown]: " + message;
        if (toast) {
            showToast(ToastType.CONSOLE_OTHER, "Unknown - " + thread, toastMessage);
        }
        publishConsoleRecord(ConsoleLevel.OTHER, timestamp, thread, "Unknown", message, logMessage);
        enqueueLogMessage(logMessage);
    }

    private void publishConsoleRecord(ConsoleLevel level, String timestamp, String thread, String category, String message, String rawLine) {
        ConsoleLogBridge.publish(this.consoleChannel, level, timestamp, thread, category, message, rawLine);
    }

    public static void installToastSink(ToastSink sink) {
        toastSink = sink == null ? (type, title, description) -> { } : sink;
    }

    private void showToast(ToastType type, String title, String description) {
        try {
            toastSink.show(type, title, description);
        } catch (RuntimeException ignored) {
            // Presentation failures must never break common logging.
        }
    }

    public enum ToastType {
        CONSOLE,
        CONSOLE_INFO,
        CONSOLE_WARNING,
        CONSOLE_ERROR,
        CONSOLE_FATAL,
        CONSOLE_DEBUG,
        CONSOLE_UPDATE,
        CONSOLE_OTHER
    }

    @FunctionalInterface
    public interface ToastSink {
        void show(ToastType type, String title, String description);
    }

    private void enqueueLogMessage(String logMessage) {
        if (!logQueue.offer(logMessage)) {
            // Logging must never stall a model, Automation, package, or client thread.
            logQueue.poll();
            logQueue.offer("[=] | [" + LocalDateTime.now().format(timestampFormatter)
                    + "] [Logger Thread/Warn]: log queue overflow; oldest pending row was dropped");
            if (!logQueue.offer(logMessage)) {
                logQueue.poll();
                logQueue.offer(logMessage);
            }
        }
    }

    private void processLogQueue() {
        while (running || !logQueue.isEmpty()) {
            try {
                String logMessage = logQueue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (logMessage != null) {
                    writer.write(logMessage);
                    writer.newLine();
                    writer.flush();
                    while ((logMessage = logQueue.poll()) != null) {
                        writer.write(logMessage);
                        writer.newLine();
                    }
                    writer.flush();
                }
            } catch (InterruptedException interrupted) {
                if (!running) return;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void close() {
        running = false;
        try {
            logWriterThread.join();
            lock.lock();
            try {
                if (writer != null) {
                    writer.close();
                    writer = null;
                }
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }
    }

    public static void closeAll() {
        lock.lock();
        try {
            for (SubFileLogger logger : instances.values()) {
                logger.close();
            }
            instances.clear();
        } finally {
            lock.unlock();
        }
    }

    private void handleShutdown() {
        if (!crashDetected) {
            crashDetected = true;
            close();
            renameLatestLogWithTimestamp(baseFileName);
        }
    }

    private void renameLatestLogWithTimestamp(String baseFileName) {
        lock.lock();
        try {
            Path latestLogPath = Paths.get(logFilePath);
            if (Files.exists(latestLogPath)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
                Path parent = latestLogPath.toAbsolutePath().normalize().getParent();
                String stem = timestamp + "-" + baseFileName;
                for (int suffix = 0; suffix < 1000; suffix++) {
                    String name = stem + (suffix == 0 ? "" : "-" + suffix) + ".log";
                    Path timestampedLogPath = parent.resolve(name);
                    try {
                        Files.move(latestLogPath, timestampedLogPath);
                        break;
                    } catch (java.nio.file.FileAlreadyExistsException collision) {
                        // Multiple short-lived proof/runtime processes can rotate in one millisecond.
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    private void writeHeader() {
        String header = String.format("""
            =============================================================================================
                ██╗  ██╗ ██████╗ ██╗██╗       File: %s
                ██║ ██╔╝██╔═══██╗██║██║       ----------------------------------
                █████╔╝ ██║   ██║██║██║       Minecraft: %s
                ██╔═██╗ ██║   ██║██║██║       Fabric Loader: %s
                ██║  ██╗╚██████╔╝██║███████╗  Koil: v%s
                ╚═╝  ╚═╝ ╚═════╝ ╚═╝╚══════╝  -----------------------------------
            =============================================================================================
            """,
                logFilePath, runtimeMinecraftVersion(), FabricLoaderImpl.VERSION, VERSION
        );
        try {
            writer.write(header);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String runtimeMinecraftVersion() {
        String version = System.getProperty("fabric.gameVersion", "").strip();
        return version.isBlank() ? "runtime" : version;
    }

    private static ConsoleChannel resolveConsoleChannel(String logFilePath) {
        String normalized = logFilePath == null ? "" : logFilePath.replace('\\', '/').toLowerCase();
        if (normalized.contains("/package/")) {
            return ConsoleChannel.PACKAGE;
        }
        if (normalized.contains("/logs/latest.log")) {
            return ConsoleChannel.KOIL;
        }
        if (normalized.contains("/minecraft") || normalized.endsWith("logs/latest.log")) {
            return ConsoleChannel.MINECRAFT;
        }
        return ConsoleChannel.KOIL;
    }
}
