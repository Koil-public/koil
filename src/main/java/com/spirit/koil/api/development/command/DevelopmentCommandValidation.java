package com.spirit.koil.api.development.command;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Pure validation boundary for the development command bridge.
 *
 * <p>This class validates command intent; it never executes commands or touches
 * Minecraft. Commands without a leading slash must resolve through the active
 * command tree so ordinary chat cannot be submitted accidentally.</p>
 */
public final class DevelopmentCommandValidation {
    public static final int MAX_COMMAND_LENGTH = 256;

    private static final Pattern WINDOWS_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Set<String> PROHIBITED_ROOTS = Set.of(
            "bash", "sh", "zsh", "fish", "cmd", "cmd.exe", "powershell", "pwsh",
            "java", "javac", "jshell", "python", "python3", "node", "ruby", "perl",
            "curl", "wget", "packet", "rawpacket", "raw_packet", "sendpacket"
    );

    private DevelopmentCommandValidation() {
    }

    public static Result validate(String rawCommand, Predicate<String> knownCommandRoot) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return Result.rejected("Command is empty.");
        }
        if (rawCommand.length() > MAX_COMMAND_LENGTH + 1) {
            return Result.rejected("Command exceeds Minecraft's 256-character command limit.");
        }
        for (int index = 0; index < rawCommand.length(); index++) {
            char value = rawCommand.charAt(index);
            if (value == '\n' || value == '\r') {
                return Result.rejected("Multiline commands are not allowed.");
            }
            if (value == '\0' || Character.isISOControl(value)) {
                return Result.rejected("Control characters are not allowed.");
            }
        }

        String trimmed = rawCommand.trim();
        if (looksLikePath(trimmed)) {
            return Result.rejected("File paths are not Minecraft commands.");
        }
        boolean explicitlySlashed = trimmed.startsWith("/");
        String normalized = explicitlySlashed ? trimmed.substring(1).trim() : trimmed;
        if (normalized.isBlank()) {
            return Result.rejected("Command is empty after removing the leading slash.");
        }
        if (normalized.length() > MAX_COMMAND_LENGTH) {
            return Result.rejected("Command exceeds Minecraft's 256-character command limit.");
        }
        if (looksLikeJavaSource(normalized)) {
            return Result.rejected("Java source or runtime expressions are not allowed.");
        }

        String root = firstToken(normalized).toLowerCase(Locale.ROOT);
        if (PROHIBITED_ROOTS.contains(root)) {
            return Result.rejected("Operating-system, runtime, and raw-packet commands are not allowed.");
        }
        if (!explicitlySlashed && (knownCommandRoot == null || !knownCommandRoot.test(root))) {
            return Result.rejected("Input without a leading slash must match the active Minecraft command tree; normal chat is not allowed.");
        }
        return Result.accepted(normalized);
    }

    private static String firstToken(String command) {
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    private static boolean looksLikePath(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return command.startsWith("./")
                || command.startsWith("../")
                || command.startsWith("~/")
                || command.startsWith("\\\\")
                || WINDOWS_PATH.matcher(command).matches()
                || lower.startsWith("file://")
                || lower.startsWith("/users/")
                || lower.startsWith("/home/")
                || lower.startsWith("/var/")
                || lower.startsWith("/etc/");
    }

    private static boolean looksLikeJavaSource(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.startsWith("import java.")
                || lower.startsWith("package ")
                || lower.startsWith("public class ")
                || lower.startsWith("class ")
                || lower.startsWith("system.")
                || lower.startsWith("runtime.getruntime");
    }

    public record Result(boolean accepted, String normalizedCommand, String error) {
        private static Result accepted(String command) {
            return new Result(true, command, null);
        }

        private static Result rejected(String error) {
            return new Result(false, "", error);
        }
    }
}
