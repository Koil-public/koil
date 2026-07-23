package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachmentType;
import com.spirit.koil.api.util.file.media.image.ImageFormatSupport;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves remote chat media by evidence rather than host-specific rendering
 * rules. URL hints are sufficient to create a non-blocking placeholder;
 * response MIME, disposition, signatures, and HTML metadata can then correct
 * the final media contract after download.
 */
public final class RichChatRemoteMediaResolver {
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "wav", "ogg", "oga", "mp3", "flac", "aac", "m4a", "wma", "aiff", "aif", "opus"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "mov", "webm", "mkv", "avi", "wmv", "flv", "3gp",
            "mpeg", "mpg", "ogv", "ts", "mts", "m2ts"
    );
    private static final Set<String> GENERIC_FILE_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "log", "json", "json5", "xml", "yaml", "yml", "toml",
            "properties", "cfg", "conf", "ini", "csv", "tsv", "pdf", "rtf",
            "java", "kt", "kts", "groovy", "gradle", "c", "h", "cpp", "hpp", "cs", "js", "mjs",
            "cjs", "ts", "tsx", "jsx", "py", "rb", "rs", "go", "swift", "sh", "bat", "ps1",
            "html", "htm", "css", "scss", "less", "sql", "mcfunction", "mcmeta",
            "zip", "jar", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
            "ttf", "otf", "woff", "woff2", "bin", "dat", "nbt"
    );
    private static final Pattern QUERY_FORMAT = Pattern.compile("(?:^|[&?])(?:format|fm|ext)=([^&#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_FILENAME = Pattern.compile("(?:^|[&?])(?:filename|file|download)=([^&#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_DISPOSITION_FILENAME = Pattern.compile("filename\\*?\\s*=\\s*(?:UTF-8''|[\"'])?([^\"';\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<(meta|img|video|audio|source|link)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile("([:\\w-]+)\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    private RichChatRemoteMediaResolver() {
    }

    public static Descriptor infer(String rawUrl) {
        String url = RichChatRemoteImageCache.sanitizeRemoteUrl(rawUrl);
        if (url.isBlank()) {
            return Descriptor.unknown("remote-file.bin");
        }
        String fileName = fileNameFromUrl(url);
        String queryFormat = queryValue(QUERY_FORMAT, url);
        String queryFileName = queryValue(QUERY_FILENAME, url);
        if (!queryFileName.isBlank() && hasExtension(queryFileName)) {
            fileName = safeFileName(queryFileName);
        }
        String extension = normalizeExtension(!queryFormat.isBlank() ? queryFormat : extension(fileName));
        if (extension.isBlank()) {
            extension = normalizeExtension(extension(fileName));
        }
        RichChatAttachmentType type = typeForExtension(extension);
        if (type == null && likelyMediaLocation(url)) {
            type = RichChatAttachmentType.IMAGE;
        }
        if (fileName.isBlank()) {
            fileName = defaultFileName(type, extension);
        }
        return descriptor(type, fileName, mimeForExtension(extension, type), extension);
    }

    public static Descriptor inspect(
            String requestedUrl,
            String resolvedUrl,
            String contentType,
            String contentDisposition,
            byte[] bytes,
            RichChatAttachmentType expectedType
    ) {
        Descriptor urlDescriptor = infer(resolvedUrl == null || resolvedUrl.isBlank() ? requestedUrl : resolvedUrl);
        String dispositionName = fileNameFromDisposition(contentDisposition);
        String mime = normalizeMime(contentType);
        RichChatAttachmentType type = typeForMime(mime);
        String extension = extensionForMime(mime);

        Signature signature = signature(bytes);
        if (signature.type() != null) {
            type = signature.type();
            if (!signature.extension().isBlank()) {
                extension = signature.extension();
            }
            if (!signature.mimeType().isBlank()) {
                mime = signature.mimeType();
            }
        }
        if (type == null) {
            type = urlDescriptor.type() == null ? expectedType : urlDescriptor.type();
        }
        String fileName = dispositionName.isBlank() ? urlDescriptor.fileName() : dispositionName;
        if (extension.isBlank()) {
            extension = normalizeExtension(extension(fileName));
        }
        if (extension.isBlank()) {
            extension = urlDescriptor.extension();
        }
        if (mime.isBlank()) {
            mime = mimeForExtension(extension, type);
        }
        if (fileName.isBlank()) {
            fileName = defaultFileName(type, extension);
        } else if (!extension.isBlank() && extension(fileName).isBlank()) {
            fileName = fileName + "." + extension;
        }
        return descriptor(type, fileName, mime, extension);
    }

    public static boolean looksLikeProcessableUrl(String rawUrl) {
        String url = RichChatRemoteImageCache.sanitizeRemoteUrl(rawUrl);
        if (url.isBlank()) {
            return false;
        }
        Descriptor descriptor = infer(url);
        if (descriptor.type() != null) {
            if (descriptor.type() != RichChatAttachmentType.FILE) {
                return true;
            }
            return GENERIC_FILE_EXTENSIONS.contains(descriptor.extension());
        }
        return likelyMediaLocation(url);
    }

    public static List<MediaCandidate> embeddedMedia(String baseUrl, byte[] htmlBytes, RichChatAttachmentType preferredType) {
        if (htmlBytes == null || htmlBytes.length == 0) {
            return List.of();
        }
        String html = new String(htmlBytes, StandardCharsets.UTF_8);
        LinkedHashMap<String, MediaCandidate> candidates = new LinkedHashMap<>();
        Matcher tags = HTML_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group(1).toLowerCase(Locale.ROOT);
            Map<String, String> attributes = attributes(tags.group(2));
            String property = firstNonBlank(attributes.get("property"), attributes.get("name")).toLowerCase(Locale.ROOT);
            String relation = attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
            String source = "";
            RichChatAttachmentType type = null;

            if ("meta".equals(tag)) {
                source = attributes.getOrDefault("content", "");
                if (property.contains("image")) {
                    type = RichChatAttachmentType.IMAGE;
                } else if (property.contains("video") || property.contains("player:stream")) {
                    type = RichChatAttachmentType.VIDEO;
                } else if (property.contains("audio")) {
                    type = RichChatAttachmentType.AUDIO;
                }
            } else if ("img".equals(tag)) {
                source = firstNonBlank(attributes.get("src"), attributes.get("data-src"), attributes.get("data-original"));
                type = RichChatAttachmentType.IMAGE;
            } else if ("video".equals(tag)) {
                source = firstNonBlank(attributes.get("src"), attributes.get("data-src"));
                type = RichChatAttachmentType.VIDEO;
            } else if ("audio".equals(tag)) {
                source = firstNonBlank(attributes.get("src"), attributes.get("data-src"));
                type = RichChatAttachmentType.AUDIO;
            } else if ("source".equals(tag)) {
                source = attributes.getOrDefault("src", "");
                type = typeForMime(attributes.getOrDefault("type", ""));
            } else if ("link".equals(tag) && (relation.contains("image_src") || relation.contains("preload"))) {
                source = attributes.getOrDefault("href", "");
                type = typeForMime(attributes.getOrDefault("type", ""));
                if (type == null && relation.contains("image")) {
                    type = RichChatAttachmentType.IMAGE;
                }
            }
            String resolved = resolveUrl(baseUrl, htmlDecode(source));
            if (resolved.isBlank() || resolved.startsWith("data:")) {
                continue;
            }
            Descriptor inferred = infer(resolved);
            RichChatAttachmentType resolvedType = type == null ? inferred.type() : type;
            if (resolvedType == null || resolvedType == RichChatAttachmentType.FILE) {
                continue;
            }
            candidates.putIfAbsent(resolved, new MediaCandidate(resolved, resolvedType));
        }
        List<MediaCandidate> ordered = new ArrayList<>(candidates.values());
        ordered.sort((left, right) -> Integer.compare(priority(left.type(), preferredType), priority(right.type(), preferredType)));
        return List.copyOf(ordered);
    }

    public static String acceptHeader(RichChatAttachmentType type) {
        if (type == RichChatAttachmentType.VIDEO) {
            return "video/*,application/octet-stream;q=0.8,text/html;q=0.5,*/*;q=0.2";
        }
        if (type == RichChatAttachmentType.AUDIO) {
            return "audio/*,application/ogg,application/octet-stream;q=0.8,text/html;q=0.5,*/*;q=0.2";
        }
        if (type == RichChatAttachmentType.FILE) {
            return "application/*,text/*,image/*,audio/*,video/*,*/*;q=0.5";
        }
        return "image/avif,image/webp,image/apng,image/svg+xml,image/*,text/html,application/xhtml+xml,*/*;q=0.5";
    }

    public static long maxBytes(RichChatAttachmentType type) {
        return switch (type == null ? RichChatAttachmentType.FILE : type) {
            case IMAGE, GIF -> 25L * 1024L * 1024L;
            case AUDIO -> 64L * 1024L * 1024L;
            case VIDEO -> 128L * 1024L * 1024L;
            case FILE -> 64L * 1024L * 1024L;
        };
    }

    public static boolean isHtml(String contentType, byte[] bytes) {
        String type = normalizeMime(contentType);
        if (type.equals("text/html") || type.equals("application/xhtml+xml")) {
            return true;
        }
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        String start = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return start.startsWith("<!doctype html") || start.startsWith("<html") || start.contains("<head");
    }

    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int query = fileName.indexOf('?');
        String clean = query >= 0 ? fileName.substring(0, query) : fileName;
        int dot = clean.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= clean.length()) {
            return "";
        }
        return normalizeExtension(clean.substring(dot + 1));
    }

    private static Descriptor descriptor(RichChatAttachmentType type, String fileName, String mimeType, String extension) {
        return new Descriptor(type, safeFileName(fileName), normalizeMime(mimeType), normalizeExtension(extension));
    }

    private static RichChatAttachmentType typeForExtension(String extension) {
        String ext = normalizeExtension(extension);
        if ("gif".equals(ext)) {
            return RichChatAttachmentType.GIF;
        }
        if (ImageFormatSupport.isSupportedExtension(ext) || "avif".equals(ext) || "heic".equals(ext) || "heif".equals(ext)) {
            return RichChatAttachmentType.IMAGE;
        }
        if (AUDIO_EXTENSIONS.contains(ext)) {
            return RichChatAttachmentType.AUDIO;
        }
        if (VIDEO_EXTENSIONS.contains(ext)) {
            return RichChatAttachmentType.VIDEO;
        }
        if (GENERIC_FILE_EXTENSIONS.contains(ext)) {
            return RichChatAttachmentType.FILE;
        }
        return null;
    }

    private static RichChatAttachmentType typeForMime(String rawMime) {
        String mime = normalizeMime(rawMime);
        if (mime.equals("image/gif")) {
            return RichChatAttachmentType.GIF;
        }
        if (mime.startsWith("image/")) {
            return RichChatAttachmentType.IMAGE;
        }
        if (mime.startsWith("audio/") || mime.equals("application/ogg")) {
            return RichChatAttachmentType.AUDIO;
        }
        if (mime.startsWith("video/")) {
            return RichChatAttachmentType.VIDEO;
        }
        if (!mime.isBlank() && !mime.equals("application/octet-stream") && !mime.equals("binary/octet-stream")
                && !mime.equals("text/html") && !mime.equals("application/xhtml+xml")) {
            return RichChatAttachmentType.FILE;
        }
        return null;
    }

    private static String mimeForExtension(String extension, RichChatAttachmentType type) {
        String ext = normalizeExtension(extension);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp", "dib" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "avif" -> "image/avif";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "oga", "opus" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "m4a", "aac" -> "audio/mp4";
            case "aiff", "aif" -> "audio/aiff";
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "txt", "md", "log", "java", "json", "xml", "yaml", "yml", "toml" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "jar" -> "application/java-archive";
            default -> type == null ? "application/octet-stream" : switch (type) {
                case IMAGE -> "image/*";
                case GIF -> "image/gif";
                case AUDIO -> "audio/*";
                case VIDEO -> "video/*";
                case FILE -> "application/octet-stream";
            };
        };
    }

    private static String extensionForMime(String rawMime) {
        String mime = normalizeMime(rawMime);
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            case "image/svg+xml" -> "svg";
            case "image/avif" -> "avif";
            case "audio/mpeg" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/ogg", "application/ogg" -> "ogg";
            case "audio/flac" -> "flac";
            case "audio/mp4", "audio/aac" -> "m4a";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            case "video/x-matroska" -> "mkv";
            case "video/x-msvideo" -> "avi";
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            case "application/java-archive" -> "jar";
            default -> "";
        };
    }

    private static Signature signature(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return Signature.NONE;
        }
        if (starts(bytes, 0x89, 'P', 'N', 'G')) return new Signature(RichChatAttachmentType.IMAGE, "png", "image/png");
        if (starts(bytes, 0xFF, 0xD8, 0xFF)) return new Signature(RichChatAttachmentType.IMAGE, "jpg", "image/jpeg");
        if (startsAscii(bytes, "GIF87a") || startsAscii(bytes, "GIF89a")) return new Signature(RichChatAttachmentType.GIF, "gif", "image/gif");
        if (starts(bytes, 'B', 'M')) return new Signature(RichChatAttachmentType.IMAGE, "bmp", "image/bmp");
        if (isRiff(bytes, "WEBP")) return new Signature(RichChatAttachmentType.IMAGE, "webp", "image/webp");
        if (isIsoBmff(bytes, "avif", "avis")) return new Signature(RichChatAttachmentType.IMAGE, "avif", "image/avif");
        if (startsAscii(bytes, "ID3")) return new Signature(RichChatAttachmentType.AUDIO, "mp3", "audio/mpeg");
        if (startsAscii(bytes, "fLaC")) return new Signature(RichChatAttachmentType.AUDIO, "flac", "audio/flac");
        if (isRiff(bytes, "WAVE")) return new Signature(RichChatAttachmentType.AUDIO, "wav", "audio/wav");
        if (isRiff(bytes, "AVI ")) return new Signature(RichChatAttachmentType.VIDEO, "avi", "video/x-msvideo");
        if (starts(bytes, 0x1A, 0x45, 0xDF, 0xA3)) return new Signature(RichChatAttachmentType.VIDEO, "mkv", "video/x-matroska");
        if (isIsoBmff(bytes, "qt  ")) return new Signature(RichChatAttachmentType.VIDEO, "mov", "video/quicktime");
        if (isIsoBmff(bytes, "isom", "iso2", "mp41", "mp42", "avc1", "dash", "M4V ")) return new Signature(RichChatAttachmentType.VIDEO, "mp4", "video/mp4");
        if (startsAscii(bytes, "%PDF-")) return new Signature(RichChatAttachmentType.FILE, "pdf", "application/pdf");
        if (starts(bytes, 'P', 'K', 0x03, 0x04)) return new Signature(RichChatAttachmentType.FILE, "zip", "application/zip");
        return Signature.NONE;
    }

    private static boolean likelyMediaLocation(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
            String combined = host + "/" + path + "?" + query;
            return combined.contains("media")
                    || combined.contains("image")
                    || combined.contains("img")
                    || combined.contains("photo")
                    || combined.contains("video")
                    || combined.contains("audio")
                    || combined.contains("attachment")
                    || combined.contains("cdn")
                    || combined.contains("giphy")
                    || combined.contains("tenor")
                    || combined.contains("wikimedia")
                    || combined.contains("discordapp")
                    || combined.contains("redd.it")
                    || combined.contains("redditmedia")
                    || combined.contains("imgur")
                    || combined.contains("twimg")
                    || combined.contains("ytimg")
                    || combined.contains("cloudfront");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String fileNameFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return safeFileName(name);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String fileNameFromDisposition(String disposition) {
        if (disposition == null || disposition.isBlank()) {
            return "";
        }
        Matcher matcher = CONTENT_DISPOSITION_FILENAME.matcher(disposition);
        if (!matcher.find()) {
            return "";
        }
        return safeFileName(urlDecode(matcher.group(1)));
    }

    private static String safeFileName(String value) {
        String name = urlDecode(value == null ? "" : value).replace('\\', '_').replace('/', '_').trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }

    private static String defaultFileName(RichChatAttachmentType type, String extension) {
        String stem = type == null ? "remote-file" : switch (type) {
            case IMAGE -> "remote-image";
            case GIF -> "remote-animation";
            case AUDIO -> "remote-audio";
            case VIDEO -> "remote-video";
            case FILE -> "remote-file";
        };
        String ext = normalizeExtension(extension);
        return ext.isBlank() ? stem + ".bin" : stem + "." + ext;
    }

    private static Map<String, String> attributes(String body) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = HTML_ATTRIBUTE.matcher(body == null ? "" : body);
        while (matcher.find()) {
            String value = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
            attributes.put(matcher.group(1).toLowerCase(Locale.ROOT), htmlDecode(value));
        }
        return attributes;
    }

    private static String resolveUrl(String baseUrl, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        try {
            return URI.create(baseUrl).resolve(candidate.trim()).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String queryValue(Pattern pattern, String url) {
        try {
            String query = URI.create(url).getRawQuery();
            Matcher matcher = pattern.matcher(query == null ? "" : "?" + query);
            return matcher.find() ? urlDecode(matcher.group(1)) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeExtension(String extension) {
        if (extension == null) {
            return "";
        }
        String clean = extension.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        return clean.matches("[a-z0-9]{1,12}") ? clean : "";
    }

    private static String normalizeMime(String mime) {
        if (mime == null) {
            return "";
        }
        int semicolon = mime.indexOf(';');
        return (semicolon >= 0 ? mime.substring(0, semicolon) : mime).trim().toLowerCase(Locale.ROOT);
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static String htmlDecode(String value) {
        return (value == null ? "" : value)
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static boolean hasExtension(String value) {
        return !extension(value).isBlank();
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes == null || bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsAscii(byte[] bytes, String prefix) {
        if (bytes == null || bytes.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if ((char) bytes[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRiff(byte[] bytes, String form) {
        return bytes != null && bytes.length >= 12
                && startsAscii(bytes, "RIFF")
                && bytes[8] == form.charAt(0)
                && bytes[9] == form.charAt(1)
                && bytes[10] == form.charAt(2)
                && bytes[11] == form.charAt(3);
    }

    private static boolean isIsoBmff(byte[] bytes, String... brands) {
        if (bytes == null || bytes.length < 12 || !startsAtAscii(bytes, 4, "ftyp")) {
            return false;
        }
        for (String brand : brands) {
            if (startsAtAscii(bytes, 8, brand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsAtAscii(byte[] bytes, int offset, String text) {
        if (bytes == null || text == null || offset < 0 || bytes.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((char) bytes[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int priority(RichChatAttachmentType type, RichChatAttachmentType preferredType) {
        if (type == preferredType) {
            return 0;
        }
        if (preferredType == RichChatAttachmentType.GIF && type == RichChatAttachmentType.IMAGE) {
            return 1;
        }
        return switch (type) {
            case VIDEO -> 2;
            case AUDIO -> 3;
            case GIF -> 4;
            case IMAGE -> 5;
            case FILE -> 6;
        };
    }

    public record Descriptor(RichChatAttachmentType type, String fileName, String mimeType, String extension) {
        private static Descriptor unknown(String fileName) {
            return new Descriptor(null, fileName, "application/octet-stream", "bin");
        }
    }

    public record MediaCandidate(String url, RichChatAttachmentType type) {
    }

    private record Signature(RichChatAttachmentType type, String extension, String mimeType) {
        private static final Signature NONE = new Signature(null, "", "");
    }
}
