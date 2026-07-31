package com.spirit.koil.api.model.voice;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MacOsSayModelVoiceProvider implements ModelVoiceProvider {
    private static final String PROVIDER_ID = "macos";
    private static final Path SAY = Path.of("/usr/bin/say");
    private static final Path AFCONVERT = Path.of("/usr/bin/afconvert");
    private static final Pattern VOICE_LINE = Pattern.compile("^(.+?)\\s+([a-z]{2}_[A-Z]{2})\\s+#.*$");
    private volatile Map<String, String> voiceNames;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ModelVoiceDefinition> voices() {
        if (!Files.isExecutable(SAY) || !Files.isExecutable(AFCONVERT)) {
            return List.of();
        }
        Map<String, String> names = voiceNames;
        if (names == null) {
            synchronized (this) {
                names = voiceNames;
                if (names == null) {
                    names = discover();
                    voiceNames = names;
                }
            }
        }
        List<ModelVoiceDefinition> definitions = new ArrayList<>(names.size());
        names.forEach((id, displayName) ->
                definitions.add(new ModelVoiceDefinition(id, displayName + " (macOS)", PROVIDER_ID, false)));
        return List.copyOf(definitions);
    }

    @Override
    public Path synthesize(String voiceId, String text, Path outputDirectory) throws Exception {
        String name = resolvedVoiceNames().get(voiceId);
        if (name == null) {
            throw new IllegalArgumentException("unknown macOS voice: " + voiceId);
        }
        Files.createDirectories(outputDirectory);
        String id = UUID.randomUUID().toString();
        Path intermediate = outputDirectory.resolve("macos-" + id + ".aiff");
        Path output = outputDirectory.resolve("macos-" + id + ".wav");
        Process process = new ProcessBuilder(
                SAY.toString(),
                "-v",
                name,
                "-o",
                intermediate.toAbsolutePath().normalize().toString(),
                text
        ).redirectErrorStream(true).start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            Files.deleteIfExists(intermediate);
            Files.deleteIfExists(output);
            throw new IllegalStateException("macOS voice synthesis timed out");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(intermediate) || Files.size(intermediate) <= 0L) {
            Files.deleteIfExists(intermediate);
            Files.deleteIfExists(output);
            throw new IllegalStateException("macOS voice synthesis failed with exit " + process.exitValue());
        }
        Process conversion = new ProcessBuilder(
                AFCONVERT.toString(),
                "-f",
                "WAVE",
                "-d",
                "LEI16",
                intermediate.toAbsolutePath().normalize().toString(),
                output.toAbsolutePath().normalize().toString()
        ).redirectErrorStream(true).start();
        boolean converted = conversion.waitFor(15, TimeUnit.SECONDS);
        Files.deleteIfExists(intermediate);
        if (!converted) {
            conversion.destroyForcibly();
            Files.deleteIfExists(output);
            throw new IllegalStateException("macOS voice conversion timed out");
        }
        if (conversion.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0L) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("macOS voice conversion failed with exit " + conversion.exitValue());
        }
        return output;
    }

    private Map<String, String> resolvedVoiceNames() {
        voices();
        return voiceNames == null ? Map.of() : voiceNames;
    }

    private static Map<String, String> discover() {
        Map<String, String> names = new LinkedHashMap<>();
        try {
            Process process = new ProcessBuilder(SAY.toString(), "-v", "?").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = VOICE_LINE.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }
                    String name = matcher.group(1).strip();
                    String id = uniqueId(names, "macos:" + slug(name));
                    names.put(id, name);
                }
            }
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.copyOf(names);
    }

    private static String uniqueId(Map<String, String> names, String seed) {
        String candidate = seed;
        int suffix = 2;
        while (names.containsKey(candidate)) {
            candidate = seed + "-" + suffix++;
        }
        return candidate;
    }

    private static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "voice" : slug;
    }
}
