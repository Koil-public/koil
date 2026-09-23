package com.spirit.koil.api.model.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stable OS/architecture key shared by runtime metadata and installers. */
public final class LocalModelRuntimePlatform {
    private LocalModelRuntimePlatform() {
    }

    public static String currentId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String family = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : "unknown";
        String machine = arch.equals("aarch64") || arch.equals("arm64") ? "arm64"
                : arch.equals("x86_64") || arch.equals("amd64") ? "x86_64" : arch.replaceAll("[^a-z0-9]+", "-");
        return family + "-" + machine;
    }

    /** Windows launchability is extension-based; ZIP extraction has no POSIX execute bit to preserve. */
    public static boolean isLaunchable(Path executable) {
        return isLaunchable(executable, System.getProperty("os.name", ""));
    }

    /** Visible platform seam for installer/provider proofs; production callers use the current-platform overload. */
    public static boolean isLaunchable(Path executable, String osName) {
        if (executable == null || !Files.isRegularFile(executable)) {
            return false;
        }
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("win")
                ? windowsLaunchableName(executable.getFileName().toString())
                : Files.isExecutable(executable);
    }

    /** Builds a direct process command, using cmd.exe only for explicit Windows batch launchers. */
    public static List<String> launchCommand(Path executable, List<String> arguments) {
        return launchCommand(executable, arguments, System.getProperty("os.name", ""));
    }

    /** Visible platform seam for launcher proofs; production callers use the current-platform overload. */
    public static List<String> launchCommand(Path executable, List<String> arguments, String osName) {
        Path normalized = executable.toAbsolutePath().normalize();
        List<String> safeArguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (isWindows(osName) && batchLauncher(normalized.getFileName().toString())) {
            List<String> command = new ArrayList<>(List.of("cmd.exe", "/d", "/s", "/c"));
            StringBuilder invocation = new StringBuilder(quoteForCmd(normalized.toString()));
            for (String argument : safeArguments) invocation.append(' ').append(quoteForCmd(argument));
            command.add(invocation.toString());
            return List.copyOf(command);
        }
        List<String> command = new ArrayList<>(safeArguments.size() + 1);
        command.add(normalized.toString());
        command.addAll(safeArguments);
        return List.copyOf(command);
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean windowsLaunchableName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || batchLauncher(lower);
    }

    private static boolean batchLauncher(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    private static String quoteForCmd(String value) {
        return '"' + (value == null ? "" : value.replace("\"", "\"\"")) + '"';
    }
}
