package com.spirit.koil.api.model.voice;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CyzonModelVoiceProvider implements ModelVoiceProvider {
    private static final String PROVIDER_ID = "cyzon";
    private static final String VOICE_ID = "cyzon:default";
    private static final int MAXIMUM_AUDIO_BYTES = 2 * 1024 * 1024;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ModelVoiceDefinition> voices() {
        return List.of(new ModelVoiceDefinition(VOICE_ID, "Cyzon Default", PROVIDER_ID, true));
    }

    @Override
    public synchronized Path synthesize(String voiceId, String text, Path outputDirectory) throws Exception {
        if (!VOICE_ID.equals(voiceId)) {
            throw new IllegalArgumentException("unknown Cyzon voice: " + voiceId);
        }
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        URI requestUri = URI.create("https://tts.cyzon.us/tts?text=" + encoded);
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpURLConnection connection = open(requestUri);
            try {
                int status = connection.getResponseCode();
                if (status == 429 && attempt < 3) {
                    Thread.sleep(retryDelayMillis(connection, attempt));
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Cyzon returned HTTP " + status);
                }
                URI finalUri = connection.getURL().toURI();
                if (!"tts.cyzon.us".equalsIgnoreCase(finalUri.getHost())) {
                    throw new IOException("Cyzon redirected outside its configured host");
                }
                String contentType = connection.getContentType();
                if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                    throw new IOException("Cyzon returned non-audio content");
                }
                Files.createDirectories(outputDirectory);
                Path output = outputDirectory.resolve("cyzon-" + UUID.randomUUID() + ".wav");
                try (InputStream input = connection.getInputStream();
                     var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        total += read;
                        if (total > MAXIMUM_AUDIO_BYTES) {
                            throw new IOException("Cyzon audio exceeded the 2.00 mb voice limit");
                        }
                        stream.write(buffer, 0, read);
                    }
                } catch (Exception failure) {
                    Files.deleteIfExists(output);
                    throw failure;
                }
                validateWave(output);
                return output;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Cyzon rate limit retry failed after four bounded attempts");
    }

    private static long retryDelayMillis(HttpURLConnection connection, int attempt) {
        String retryAfter = connection == null ? null : connection.getHeaderField("Retry-After");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.strip());
                return Math.max(250L, Math.min(2_500L, seconds * 1_000L));
            } catch (NumberFormatException ignored) {
            }
        }
        return switch (attempt) {
            case 0 -> 350L;
            case 1 -> 800L;
            default -> 1_500L;
        };
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "audio/wav,audio/*;q=0.8");
        connection.setRequestProperty("User-Agent", "Koil-Local-Model-Voice/1");
        return connection;
    }

    private static void validateWave(Path path) throws IOException {
        byte[] header = Files.readAllBytes(path);
        if (header.length < 12
                || header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F'
                || header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') {
            Files.deleteIfExists(path);
            throw new IOException("Cyzon returned an invalid WAV file");
        }
    }
}
