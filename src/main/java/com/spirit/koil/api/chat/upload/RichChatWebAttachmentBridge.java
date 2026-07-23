package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatAttachmentType;
import net.minecraft.text.Text;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts direct web-hosted files into the same attachment cards as uploads. */
public final class RichChatWebAttachmentBridge {
    private static final Pattern WEB_URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "bmp", "avif", "apng");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "ogg", "oga", "flac", "m4a", "aac", "opus");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mov", "m4v", "mkv", "avi");

    private RichChatWebAttachmentBridge() {
    }

    public static Text rewrite(Text message) {
        if (message == null) {
            return null;
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank() || LocalRichAttachmentBridge.containsMarker(visible)) {
            return message;
        }
        Matcher matcher = WEB_URL.matcher(visible);
        if (!matcher.find()) {
            return message;
        }
        String rawUrl = RichChatRemoteImageCache.sanitizeRemoteUrl(matcher.group());
        if (rawUrl.isBlank() || !isDirectAttachmentUrl(rawUrl)) {
            return message;
        }
        String prefix = visible.substring(0, matcher.start());
        String suffix = visible.substring(matcher.end());
        RichChatAttachment attachment = attachment(rawUrl);
        LocalRichAttachmentBridge.rememberVisibleAttachment(attachment, prefix);
        return Text.literal(LocalRichAttachmentBridge.marker(attachment.attachmentId(), prefix) + suffix.stripLeading());
    }

    private static boolean isDirectAttachmentUrl(String url) {
        String extension = extension(RichChatRemoteImageCache.bestRemoteFileName(url));
        if (!extension.isBlank() || RichChatRemoteImageCache.likelyPreviewableUrl(url)) {
            return true;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return (host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net"))
                    && (path.contains("/attachments/") || path.contains("/ephemeral-attachments/"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static RichChatAttachment attachment(String url) {
        String hash = RichChatRemoteImageCache.sha256(url);
        UUID id = UUID.nameUUIDFromBytes(("rich-chat-url:" + hash).getBytes(StandardCharsets.UTF_8));
        String fileName = RichChatRemoteImageCache.bestRemoteFileName(url);
        RichChatAttachmentType type = classify(fileName);
        if (type == RichChatAttachmentType.FILE
                && extension(fileName).isBlank()
                && RichChatRemoteImageCache.likelyPreviewableUrl(url)) {
            type = RichChatAttachmentType.IMAGE;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source_url", url);
        metadata.put("upload_state", "remote_link");
        metadata.put("remote_hash", hash);
        return new RichChatAttachment(id, type, fileName, mime(fileName, type), 0L, hash, 0, 0, 0L, metadata);
    }

    private static RichChatAttachmentType classify(String fileName) {
        String extension = extension(fileName);
        if ("gif".equals(extension)) {
            return RichChatAttachmentType.GIF;
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return RichChatAttachmentType.IMAGE;
        }
        if (AUDIO_EXTENSIONS.contains(extension)) {
            return RichChatAttachmentType.AUDIO;
        }
        if (VIDEO_EXTENSIONS.contains(extension)) {
            return RichChatAttachmentType.VIDEO;
        }
        return RichChatAttachmentType.FILE;
    }

    private static String mime(String fileName, RichChatAttachmentType type) {
        String extension = extension(fileName);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "avif" -> "image/avif";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "oga", "opus" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "m4a", "aac" -> "audio/mp4";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "txt", "md", "java", "json", "log" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            default -> type == RichChatAttachmentType.FILE ? "application/octet-stream" : type.name().toLowerCase(Locale.ROOT) + "/*";
        };
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= fileName.length()) {
            return "";
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,12}") ? extension : "";
    }
}
