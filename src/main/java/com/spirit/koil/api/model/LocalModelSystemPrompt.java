package com.spirit.koil.api.model;

import com.spirit.koil.api.model.format.RichChatModelFormattingContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * User-editable identity and behavior prompt layered above Koil's generated
 * Rich Chat contract. The file remains outside the mod jar so creators can
 * change the assistant identity without recompiling Koil.
 */
public final class LocalModelSystemPrompt {
    public static final Path PATH = Path.of("koil/sys/model/system-prompt.txt");
    private static final int MAXIMUM_PROMPT_CHARS = 32_768;
    private static volatile CachedIdentity cachedIdentity;
    private static final String DEFAULT_IDENTITY = """
            You are Model, the local assistant inside Koil.
            Model is made for Koil by SpiritXIV and the Koil team.
            Do not claim that you are Anthropic, Claude, OpenAI, or made by a model-runtime provider.
            Your available abilities depend on the tools supplied with the current request.
            Never claim that you used a tool, ran code, or changed Minecraft unless Koil returned a structured result proving what happened.
            """;

    private LocalModelSystemPrompt() {
    }

    public static String load() {
        String identity = readOrCreateIdentity();
        return identity + "\n\n" + RichChatModelFormattingContract.systemPrompt();
    }

    public static String defaultIdentity() {
        return DEFAULT_IDENTITY.strip();
    }

    private static String readOrCreateIdentity() {
        try {
            Path absolute = PATH.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            if (!Files.exists(absolute)) {
                Files.writeString(absolute, defaultIdentity() + "\n", StandardCharsets.UTF_8);
            }
            FileTime modified = Files.getLastModifiedTime(absolute);
            long size = Files.size(absolute);
            CachedIdentity current = cachedIdentity;
            if (current != null && current.modified().equals(modified) && current.size() == size) {
                return current.value();
            }
            String value = Files.readString(absolute, StandardCharsets.UTF_8);
            String cleaned = sanitize(value);
            String resolved = cleaned.isBlank() ? defaultIdentity() : cleaned;
            cachedIdentity = new CachedIdentity(modified, size, resolved);
            return resolved;
        } catch (IOException exception) {
            LocalModelRuntimeLog.write("system_prompt_fallback", exception.getMessage());
            return defaultIdentity();
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder(Math.min(normalized.length(), MAXIMUM_PROMPT_CHARS));
        for (int index = 0; index < normalized.length() && builder.length() < MAXIMUM_PROMPT_CHARS; index++) {
            char character = normalized.charAt(index);
            if (character == '\n' || character == '\t' || character >= 0x20) {
                builder.append(character);
            }
        }
        return builder.toString().strip();
    }

    private record CachedIdentity(FileTime modified, long size, String value) {
    }
}
