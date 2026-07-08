package com.spirit.koil.chat.internal.upload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatAttachmentType;
import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.util.file.media.image.ImageFormatSupport;
import com.spirit.koil.api.util.file.media.video.VideoService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RichChatUploadStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Koil Rich Chat Upload");
        thread.setDaemon(true);
        return thread;
    });
    private static final Path STORAGE_ROOT = Path.of("koil", "server", "chat_uploads");
    private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;
    private static final long MEDIA_RETENTION_DAYS = 30L;
    private static final long CLEANUP_INTERVAL_MS = 60L * 60L * 1000L;
    private static volatile long lastCleanupMillis;
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "scr", "sh", "ps1", "msi", "dmg", "app", "class", "jar"
    );

    private RichChatUploadStorage() {
    }

    public static CompletableFuture<StoredAttachment> stage(Path source, UUID uploaderUuid, String uploaderName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return stageNow(source, uploaderUuid, uploaderName);
            } catch (Exception exception) {
                return StoredAttachment.failed(source, exception.getMessage() == null ? "Upload failed." : exception.getMessage());
            }
        }, EXECUTOR);
    }

    public static StoredAttachment stageTransferredBytes(String originalName, byte[] bytes, UUID uploaderUuid, String uploaderName) throws Exception {
        cleanupExpiredMedia(false);
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Attachment payload was empty.");
        }
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw new IOException("Attachment payload is larger than 10 MB.");
        }
        String safeName = sanitizeFileName(originalName == null || originalName.isBlank() ? "attachment" : originalName);
        Path incomingRoot = STORAGE_ROOT.resolve(".incoming");
        Files.createDirectories(incomingRoot);
        Path temp = uniquePath(incomingRoot.resolve(safeName));
        Files.write(temp, bytes);
        try {
            return stageNow(temp, uploaderUuid, uploaderName);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static StoredAttachment stageNow(Path source, UUID uploaderUuid, String uploaderName) throws Exception {
        cleanupExpiredMedia(false);
        if (source == null) {
            throw new IOException("No file selected.");
        }

        Path normalized = source.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            throw new IOException("Selected path is not a file.");
        }

        long size = Files.size(normalized);
        if (size <= 0L) {
            throw new IOException("File is empty.");
        }
        if (size > MAX_UPLOAD_BYTES) {
            throw new IOException("File is larger than 10 MB.");
        }

        String originalName = normalized.getFileName() == null ? "attachment" : normalized.getFileName().toString();
        String extension = extensionOf(originalName);
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            throw new IOException("." + extension + " files are blocked for chat upload.");
        }

        RichChatAttachmentType type = classify(normalized, extension);
        String mime = sniffMime(normalized, extension, type);
        verifySignature(normalized, extension, type, mime);

        String sha256 = sha256(normalized);
        Optional<StoredAttachment> existing = findExistingByHash(normalized, originalName, mime, size, sha256, uploaderUuid, uploaderName);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID attachmentId = UUID.randomUUID();
        String safeName = sanitizeFileName(originalName);
        Path attachmentDirectory = STORAGE_ROOT.resolve(attachmentId.toString());
        Files.createDirectories(attachmentDirectory);
        Path storedFile = uniquePath(attachmentDirectory.resolve(safeName));
        Files.copy(normalized, storedFile, StandardCopyOption.COPY_ATTRIBUTES);

        JsonObject metadataJson = new JsonObject();
        metadataJson.addProperty("attachmentId", attachmentId.toString());
        metadataJson.addProperty("originalFileName", originalName);
        metadataJson.addProperty("sanitizedFileName", storedFile.getFileName().toString());
        metadataJson.addProperty("mimeType", mime);
        metadataJson.addProperty("type", type.name());
        metadataJson.addProperty("sizeBytes", size);
        metadataJson.addProperty("sha256", sha256);
        int[] dimensions = imageDimensions(storedFile, type);
        metadataJson.addProperty("width", dimensions[0]);
        metadataJson.addProperty("height", dimensions[1]);
        metadataJson.addProperty("uploaderUuid", uploaderUuid == null ? "" : uploaderUuid.toString());
        metadataJson.addProperty("uploaderName", uploaderName == null ? "" : uploaderName);
        metadataJson.addProperty("uploadedAt", Instant.now().toString());
        metadataJson.addProperty("serverPath", storedFile.toAbsolutePath().normalize().toString());
        Files.writeString(attachmentDirectory.resolve("metadata.json"), GSON.toJson(metadataJson));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("original_file_name", originalName);
        metadata.put("sanitized_file_name", storedFile.getFileName().toString());
        metadata.put("server_path", storedFile.toAbsolutePath().normalize().toString());
        metadata.put("metadata_path", attachmentDirectory.resolve("metadata.json").toAbsolutePath().normalize().toString());
        metadata.put("upload_state", "stored");
        metadata.put("uploader_uuid", uploaderUuid == null ? "" : uploaderUuid.toString());
        metadata.put("uploader_name", uploaderName == null ? "" : uploaderName);
        metadata.put("uploaded_at", Long.toString(System.currentTimeMillis()));
        metadata.put("width", Integer.toString(dimensions[0]));
        metadata.put("height", Integer.toString(dimensions[1]));

        RichChatAttachment attachment = new RichChatAttachment(
                attachmentId,
                type,
                originalName,
                mime,
                size,
                sha256,
                dimensions[0],
                dimensions[1],
                0L,
                metadata
        );
        return new StoredAttachment(normalized, storedFile, attachment, null);
    }

    private static Optional<StoredAttachment> findExistingByHash(Path source, String originalName, String mime, long size, String sha256, UUID uploaderUuid, String uploaderName) {
        if (sha256 == null || sha256.isBlank() || !Files.isDirectory(STORAGE_ROOT)) {
            return Optional.empty();
        }
        try (var stream = Files.list(STORAGE_ROOT)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                Path metadataPath = directory.resolve("metadata.json");
                if (!Files.isRegularFile(metadataPath)) {
                    continue;
                }
                JsonObject json = JsonParser.parseString(Files.readString(metadataPath)).getAsJsonObject();
                if (!sha256.equals(readString(json, "sha256"))) {
                    continue;
                }
                Path storedFile = Path.of(readString(json, "serverPath"));
                if (!Files.isRegularFile(storedFile)) {
                    continue;
                }
                UUID attachmentId = UUID.fromString(readString(json, "attachmentId"));
                RichChatAttachmentType type = RichChatAttachmentType.valueOf(readString(json, "type"));
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("original_file_name", originalName);
                metadata.put("sanitized_file_name", storedFile.getFileName() == null ? originalName : storedFile.getFileName().toString());
                metadata.put("server_path", storedFile.toAbsolutePath().normalize().toString());
                metadata.put("metadata_path", metadataPath.toAbsolutePath().normalize().toString());
                metadata.put("upload_state", "reused");
                metadata.put("uploader_uuid", uploaderUuid == null ? "" : uploaderUuid.toString());
                metadata.put("uploader_name", uploaderName == null ? "" : uploaderName);
                metadata.put("uploaded_at", Long.toString(System.currentTimeMillis()));
                metadata.put("reused_attachment_id", attachmentId.toString());
                int width = readInt(json, "width");
                int height = readInt(json, "height");
                if (width <= 0 || height <= 0) {
                    int[] dimensions = imageDimensions(storedFile, type);
                    width = dimensions[0];
                    height = dimensions[1];
                }
                metadata.put("width", Integer.toString(width));
                metadata.put("height", Integer.toString(height));
                RichChatAttachment attachment = new RichChatAttachment(
                        attachmentId,
                        type,
                        originalName,
                        mime == null || mime.isBlank() ? readString(json, "mimeType") : mime,
                        size,
                        sha256,
                        width,
                        height,
                        0L,
                        metadata
                );
                return Optional.of(new StoredAttachment(source, storedFile, attachment, null));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static String readString(JsonObject json, String key) {
        return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static int readInt(JsonObject json, String key) {
        try {
            return json != null && json.has(key) && !json.get(key).isJsonNull() ? Math.max(0, json.get(key).getAsInt()) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int[] imageDimensions(Path path, RichChatAttachmentType type) {
        if (path == null || !(type == RichChatAttachmentType.IMAGE || type == RichChatAttachmentType.GIF)) {
            return new int[]{0, 0};
        }
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                return new int[]{0, 0};
            }
            return new int[]{Math.max(0, image.getWidth()), Math.max(0, image.getHeight())};
        } catch (Exception ignored) {
            return new int[]{0, 0};
        }
    }

    public static RichChatAttachment withMessageId(RichChatAttachment attachment, UUID messageId) {
        if (attachment == null) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>(attachment.metadata());
        metadata.put("message_id", messageId == null ? "" : messageId.toString());
        return new RichChatAttachment(
                attachment.attachmentId(),
                attachment.type(),
                attachment.fileName(),
                attachment.mimeType(),
                attachment.sizeBytes(),
                attachment.sha256(),
                attachment.width(),
                attachment.height(),
                attachment.durationMillis(),
                metadata
        );
    }

    public static String sizeLabel(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0D;
        if (kib < 1024.0D) {
            return String.format(Locale.ROOT, "%.1f KB", kib);
        }
        return String.format(Locale.ROOT, "%.1f MB", kib / 1024.0D);
    }

    public static String attachmentDescription(RichChatAttachment attachment) {
        if (attachment == null) {
            return "File | UNKNOWN file - 0 B";
        }
        return titleCase(attachment.type().name()) + " | " + fileKind(attachment.fileName(), attachment.mimeType()) + " file - " + sizeLabel(Math.max(0L, attachment.sizeBytes()));
    }

    private static String titleCase(String value) {
        String lower = value == null || value.isBlank() ? "file" : value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String fileKind(String fileName, String mimeType) {
        String extension = extensionOf(fileName);
        if (!extension.isBlank()) {
            if ("jpeg".equals(extension)) {
                return "JPG";
            }
            return extension.toUpperCase(Locale.ROOT);
        }
        if (mimeType != null && mimeType.contains("/")) {
            String subtype = mimeType.substring(mimeType.indexOf('/') + 1).trim();
            int suffix = subtype.indexOf(';');
            if (suffix >= 0) {
                subtype = subtype.substring(0, suffix);
            }
            if (!subtype.isBlank()) {
                return subtype.replace("x-", "").toUpperCase(Locale.ROOT);
            }
        }
        return "UNKNOWN";
    }

    private static void cleanupExpiredMedia(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCleanupMillis < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupMillis = now;
        if (!Files.isDirectory(STORAGE_ROOT)) {
            return;
        }
        Instant cutoff = Instant.now().minus(MEDIA_RETENTION_DAYS, ChronoUnit.DAYS);
        try (var stream = Files.list(STORAGE_ROOT)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                if (isExpiredMediaDirectory(directory, cutoff)) {
                    deleteRecursively(directory);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isExpiredMediaDirectory(Path directory, Instant cutoff) {
        try {
            Path metadata = directory.resolve("metadata.json");
            if (Files.isRegularFile(metadata)) {
                JsonObject json = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
                String uploadedAt = readString(json, "uploadedAt");
                if (!uploadedAt.isBlank()) {
                    return Instant.parse(uploadedAt).isBefore(cutoff);
                }
            }
            return Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
        }
    }

    private static RichChatAttachmentType classify(Path file, String extension) {
        if ("gif".equals(extension)) {
            return RichChatAttachmentType.GIF;
        }
        if (ImageFormatSupport.isSupportedImageFile(file.toFile())) {
            return RichChatAttachmentType.IMAGE;
        }
        if (AudioManager.isRecognizedAudioFile(file.toFile())) {
            return RichChatAttachmentType.AUDIO;
        }
        if (VideoService.isRecognizedVideoFile(file.toFile())) {
            return RichChatAttachmentType.VIDEO;
        }
        return RichChatAttachmentType.FILE;
    }

    private static String sniffMime(Path file, String extension, RichChatAttachmentType type) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (IOException ignored) {
        }
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ogg", "opus" -> "audio/ogg";
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "json" -> "application/json";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "txt", "md", "java", "ktl", "log" -> "text/plain";
            default -> type == RichChatAttachmentType.FILE ? "application/octet-stream" : type.name().toLowerCase(Locale.ROOT) + "/*";
        };
    }

    private static void verifySignature(Path file, String extension, RichChatAttachmentType type, String mime) throws IOException {
        byte[] header = new byte[16];
        int read;
        try (InputStream stream = Files.newInputStream(file)) {
            read = stream.read(header);
        }
        if (read < 0) {
            throw new IOException("File could not be read.");
        }

        if ("png".equals(extension) && !startsWith(header, read, new int[]{0x89, 'P', 'N', 'G'})) {
            throw new IOException("PNG signature does not match the file extension.");
        }
        if (("jpg".equals(extension) || "jpeg".equals(extension)) && !startsWith(header, read, new int[]{0xFF, 0xD8, 0xFF})) {
            throw new IOException("JPEG signature does not match the file extension.");
        }
        if ("gif".equals(extension) && !(startsWithAscii(header, read, "GIF87a") || startsWithAscii(header, read, "GIF89a"))) {
            throw new IOException("GIF signature does not match the file extension.");
        }
        if ("pdf".equals(extension) && !startsWithAscii(header, read, "%PDF")) {
            throw new IOException("PDF signature does not match the file extension.");
        }
        if ("zip".equals(extension) && !startsWith(header, read, new int[]{'P', 'K'})) {
            throw new IOException("ZIP signature does not match the file extension.");
        }
        if (type == RichChatAttachmentType.IMAGE && mime.startsWith("application/")) {
            throw new IOException("Image MIME type could not be verified.");
        }
    }

    private static boolean startsWith(byte[] value, int length, int[] prefix) {
        if (length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((value[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] value, int length, String prefix) {
        if (length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if ((char)value[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sanitizeFileName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "attachment" : fileName;
        name = name.replace('\\', '_').replace('/', '_').replace("..", "_");
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            return "attachment";
        }
        return name.length() > 96 ? name.substring(0, 96) : name;
    }

    private static Path uniquePath(Path requested) {
        if (!Files.exists(requested)) {
            return requested;
        }
        Path parent = requested.getParent();
        String fileName = requested.getFileName() == null ? "attachment" : requested.getFileName().toString();
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int index = 1; index < 1000; index++) {
            Path candidate = parent.resolve(base + " (" + index + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return parent.resolve(base + " (" + System.currentTimeMillis() + ")" + extension);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record StoredAttachment(Path sourcePath, Path storedPath, RichChatAttachment attachment, String error) {
        public static StoredAttachment failed(Path sourcePath, String error) {
            return new StoredAttachment(sourcePath, null, null, error == null || error.isBlank() ? "Upload failed." : error);
        }

        public boolean ready() {
            return attachment != null && error == null;
        }
    }
}
