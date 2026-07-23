package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatAttachmentType;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RichChatRemoteImageCache {
    private static final Path CACHE_ROOT = Path.of("koil", "cache", "chat_media", "remote_images");
    private static final long MAX_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_HTML_BYTES = 2L * 1024L * 1024L;
    private static final long RETRY_FAILED_AFTER_MS = 5000L;
    private static final Set<String> DOWNLOADING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
    private static final String IMAGE_OR_HTML_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,text/html,application/xhtml+xml,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";
    private static final Pattern META_IMAGE = Pattern.compile("<meta\\s+[^>]*(?:property|name)=[\"'](?:og:image|og:image:url|twitter:image|twitter:image:src)[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>|<meta\\s+[^>]*content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"'](?:og:image|og:image:url|twitter:image|twitter:image:src)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_IMAGE = Pattern.compile("<img\\s+[^>]*(?:src|data-src)=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private RichChatRemoteImageCache() {
    }

    public static String sanitizeRemoteUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String clean = raw.trim();
        while (!clean.isEmpty() && "<([{\"'".indexOf(clean.charAt(0)) >= 0) {
            clean = clean.substring(1).trim();
        }
        while (!clean.isEmpty() && ".,;:!?)\"]}>'".indexOf(clean.charAt(clean.length() - 1)) >= 0) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        try {
            URI uri = URI.create(clean);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? clean : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean likelyPreviewableUrl(String rawUrl) {
        String url = sanitizeRemoteUrl(rawUrl);
        if (url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
            String combined = host + " " + path + " " + query;
            return path.matches(".*\\.(png|jpe?g|gif|webp|bmp|avif|apng)$")
                    || query.matches(".*(?:^|[&?])(format|fm|ext)=?(png|jpe?g|gif|webp|bmp|avif|apng).*")
                    || combined.contains("cdn.")
                    || combined.contains("cdn/")
                    || combined.contains("media.")
                    || combined.contains("media/")
                    || combined.contains("image")
                    || combined.contains("images")
                    || combined.contains("img")
                    || combined.contains("photo")
                    || combined.contains("attachment")
                    || combined.contains("discord")
                    || combined.contains("redd.it")
                    || combined.contains("redditmedia")
                    || combined.contains("googleusercontent")
                    || combined.contains("ggpht")
                    || combined.contains("imgur")
                    || combined.contains("twimg")
                    || combined.contains("ytimg")
                    || combined.contains("pinimg")
                    || combined.contains("wikimedia")
                    || combined.contains("cloudfront")
                    || combined.contains("imagedelivery");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String bestRemoteFileName(String rawUrl) {
        String url = sanitizeRemoteUrl(rawUrl);
        if (url.isBlank()) {
            return "remote-image.png";
        }
        try {
            String path = URI.create(url).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
        }
        return "remote-image." + extension(url, "");
    }

    public static String mimeForUrl(String rawUrl) {
        return switch (extension(rawUrl, "")) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "avif" -> "image/avif";
            default -> "image/png";
        };
    }

    public static Optional<Path> downloadClipboardImage(String rawUrl) {
        String url = sanitizeRemoteUrl(rawUrl);
        if (url.isBlank()) {
            return Optional.empty();
        }
        try {
            Path directory = Path.of("koil", "cache", "chat_media", "clipboard");
            Files.createDirectories(directory);
            DownloadPayload payload = downloadPayload(url, 0);
            if (payload == null || payload.bytes() == null || payload.bytes().length == 0) {
                return Optional.empty();
            }
            String extension = extension(payload.resolvedUrl(), payload.contentType());
            Path path = directory.resolve("clipboard-remote-" + sha256(url) + "." + extension);
            Files.write(path, payload.bytes());
            return Optional.of(path);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static Optional<File> localPath(RichChatAttachment attachment) {
        if (attachment == null || attachment.metadata() == null) {
            return Optional.empty();
        }
        String url = attachment.metadata().get("source_url");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String key = attachment.sha256().isBlank() ? sha256(url) : attachment.sha256();
        Optional<File> cached = existingCachedFile(key);
        if (cached.isPresent()) {
            FAILED.remove(key);
            return cached;
        }
        boolean imagePayload = attachment.type() == RichChatAttachmentType.IMAGE || attachment.type() == RichChatAttachmentType.GIF;
        String extension = imagePayload
                ? extension(attachment.metadata().getOrDefault("resolved_url", url), attachment.metadata().getOrDefault("resolved_content_type", ""))
                : attachmentExtension(attachment.fileName());
        Path target = CACHE_ROOT.resolve(key + "." + extension);
        if (Files.isRegularFile(target)) {
            return Optional.of(target.toFile());
        }
        long failedAt = FAILED.getOrDefault(key, 0L);
        if ((failedAt <= 0L || System.currentTimeMillis() - failedAt >= RETRY_FAILED_AFTER_MS) && DOWNLOADING.add(key)) {
            download(url, target, key, imagePayload);
        }
        return Optional.empty();
    }

    private static void download(String url, Path target, String key, boolean imagePayload) {
        try {
            Files.createDirectories(CACHE_ROOT);
            CompletableFuture.runAsync(() -> {
                try {
                    DownloadPayload payload = imagePayload
                            ? downloadPayload(url, 0, null)
                            : downloadFilePayload(url);
                    if (payload == null || payload.bytes() == null || payload.bytes().length == 0) {
                        FAILED.put(key, System.currentTimeMillis());
                        return;
                    }
                    Files.write(target, payload.bytes());
                    FAILED.remove(key);
                } catch (Exception exception) {
                    FAILED.put(key, System.currentTimeMillis());
                } finally {
                    DOWNLOADING.remove(key);
                }
            });
        } catch (Exception exception) {
            DOWNLOADING.remove(key);
            FAILED.put(key, System.currentTimeMillis());
        }
    }

    private static DownloadPayload downloadFilePayload(String url) {
        try {
            DownloadResponse response = requestWithFallbacks(url, null);
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0 || body.length > MAX_BYTES) {
                return null;
            }
            return new DownloadPayload(body, response.finalUrl(), response.contentType());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String attachmentExtension(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String candidate = name.substring(dot + 1);
            if (candidate.matches("[a-z0-9]{1,12}")) {
                return candidate;
            }
        }
        return "bin";
    }

    private static Optional<File> existingCachedFile(String key) {
        if (key == null || key.isBlank() || !Files.isDirectory(CACHE_ROOT)) {
            return Optional.empty();
        }
        try (var stream = Files.list(CACHE_ROOT)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName() == null ? "" : path.getFileName().toString();
                        return name.startsWith(key + ".");
                    })
                    .findFirst()
                    .map(Path::toFile);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static boolean validImage(byte[] bytes, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean signature = starts(bytes, 0x89, 'P', 'N', 'G')
                || starts(bytes, 0xFF, 0xD8, 0xFF)
                || startsAscii(bytes, "GIF87a")
                || startsAscii(bytes, "GIF89a")
                || starts(bytes, 'B', 'M')
                || isWebp(bytes)
                || isAvif(bytes);
        return signature && (type.isBlank() || type.startsWith("image/") || type.equals("application/octet-stream") || type.equals("binary/octet-stream"));
    }

    private static boolean isHtml(String contentType, byte[] bytes) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("text/html") || type.contains("application/xhtml")) {
            return true;
        }
        String start = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return start.startsWith("<!doctype html") || start.startsWith("<html") || start.contains("<head");
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';
    }

    private static boolean isAvif(byte[] bytes) {
        return bytes.length >= 12
                && bytes[4] == 'f'
                && bytes[5] == 't'
                && bytes[6] == 'y'
                && bytes[7] == 'p'
                && ((bytes[8] == 'a' && bytes[9] == 'v' && bytes[10] == 'i' && bytes[11] == 'f')
                || (bytes[8] == 'a' && bytes[9] == 'v' && bytes[10] == 'i' && bytes[11] == 's'));
    }

    private static String resolveImageUrl(String baseUrl, byte[] htmlBytes) {
        String html = new String(htmlBytes, StandardCharsets.UTF_8);
        String image = firstGroup(META_IMAGE.matcher(html));
        if (image.isBlank()) {
            image = firstGroup(FIRST_IMAGE.matcher(html));
        }
        if (image.isBlank() || image.startsWith("data:")) {
            return "";
        }
        try {
            return URI.create(baseUrl).resolve(image).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstGroup(Matcher matcher) {
        if (!matcher.find()) {
            return "";
        }
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            if (group != null && !group.isBlank()) {
                return group.trim();
            }
        }
        return "";
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
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
        if (bytes.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if ((char)bytes[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String extension(String url, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("jpeg") || type.contains("jpg")) {
            return "jpg";
        }
        if (type.contains("gif")) {
            return "gif";
        }
        if (type.contains("webp")) {
            return "webp";
        }
        if (type.contains("bmp")) {
            return "bmp";
        }
        if (type.contains("avif")) {
            return "avif";
        }
        if (type.contains("png")) {
            return "png";
        }
        String clean = url == null ? "" : url.toLowerCase(Locale.ROOT);
        Matcher formatMatcher = Pattern.compile("(?:^|[?&])(format|fm|ext)=([a-z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(clean);
        if (formatMatcher.find()) {
            String ext = formatMatcher.group(2);
            if (ext != null && !ext.isBlank()) {
                return switch (ext.toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "jpg";
                    case "gif" -> "gif";
                    case "webp" -> "webp";
                    case "bmp" -> "bmp";
                    case "avif" -> "avif";
                    default -> "png";
                };
            }
        }
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        if (clean.endsWith(".jpg") || clean.endsWith(".jpeg")) {
            return "jpg";
        }
        if (clean.endsWith(".gif")) {
            return "gif";
        }
        if (clean.endsWith(".webp")) {
            return "webp";
        }
        if (clean.endsWith(".bmp")) {
            return "bmp";
        }
        if (clean.endsWith(".avif")) {
            return "avif";
        }
        return "png";
    }

    private static DownloadPayload downloadPayload(String url, int depth) {
        return downloadPayload(url, depth, null);
    }

    private static DownloadPayload downloadPayload(String url, int depth, String refererHint) {
        if (url == null || url.isBlank() || depth > 3) {
            return null;
        }
        try {
            DownloadResponse response = requestWithFallbacks(url, refererHint);
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            byte[] body = response.body();
            String contentType = response.contentType();
            if (body == null || body.length == 0 || body.length > MAX_BYTES) {
                return null;
            }
            if (validImage(body, contentType)) {
                return new DownloadPayload(body, response.finalUrl(), contentType);
            }
            if (isHtml(contentType, body) && body.length <= MAX_HTML_BYTES) {
                String resolved = resolveImageUrl(response.finalUrl(), body);
                if (!resolved.isBlank() && !resolved.equals(response.finalUrl())) {
                    return downloadPayload(resolved, depth + 1, response.finalUrl());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static DownloadResponse requestWithFallbacks(String url, String refererHint) {
        LinkedHashSet<String> attempts = new LinkedHashSet<>();
        attempts.addAll(refererCandidates(url, refererHint));
        attempts.add(null);

        DownloadResponse last = null;
        for (String referer : attempts) {
            last = requestOnce(url, referer);
            if (last == null) {
                continue;
            }
            if (last.statusCode() >= 200 && last.statusCode() < 300) {
                return last;
            }
            if (last.statusCode() != 401 && last.statusCode() != 403) {
                return last;
            }
        }
        return last;
    }

    private static DownloadResponse requestOnce(String url, String referer) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", IMAGE_OR_HTML_ACCEPT)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .GET();
            if (referer != null && !referer.isBlank()) {
                builder.header("Referer", referer);
                String origin = originOf(referer);
                if (!origin.isBlank()) {
                    builder.header("Origin", origin);
                }
            }
            HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response == null) {
                return null;
            }
            return new DownloadResponse(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("content-type").orElse(""),
                    response.uri() == null ? url : response.uri().toString()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Set<String> refererCandidates(String url, String refererHint) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String sanitizedHint = sanitizeRemoteUrl(refererHint);
        if (!sanitizedHint.isBlank()) {
            candidates.add(sanitizedHint);
            String hintRoot = siteRoot(sanitizedHint);
            if (!hintRoot.isBlank()) {
                candidates.add(hintRoot);
            }
        }

        String preferred = preferredReferer(url);
        if (!preferred.isBlank()) {
            candidates.add(preferred);
        }
        String sameHost = siteRoot(url);
        if (!sameHost.isBlank()) {
            candidates.add(sameHost);
        }
        String siteRoot = siteRootFromRegistrableDomain(url);
        if (!siteRoot.isBlank()) {
            candidates.add(siteRoot);
        }
        return candidates;
    }

    private static String preferredReferer(String rawUrl) {
        try {
            URI uri = URI.create(sanitizeRemoteUrl(rawUrl));
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            String siteRoot = siteRoot(rawUrl);
            if (!assetSubdomain(lowerHost)) {
                return siteRoot;
            }
            String registrable = registrableDomain(lowerHost);
            if (registrable.isBlank()) {
                return siteRoot;
            }
            String preferred = uri.getScheme() + "://www." + registrable + "/";
            return preferred.equalsIgnoreCase(siteRoot) ? siteRoot : preferred;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean assetSubdomain(String host) {
        return host.startsWith("cdn.")
                || host.startsWith("img.")
                || host.startsWith("images.")
                || host.startsWith("media.")
                || host.startsWith("static.")
                || host.startsWith("assets.")
                || host.startsWith("i.");
    }

    private static String siteRoot(String rawUrl) {
        try {
            URI uri = URI.create(sanitizeRemoteUrl(rawUrl));
            if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String siteRootFromRegistrableDomain(String rawUrl) {
        try {
            URI uri = URI.create(sanitizeRemoteUrl(rawUrl));
            String host = uri.getHost();
            if (uri.getScheme() == null || host == null || host.isBlank()) {
                return "";
            }
            String registrable = registrableDomain(host.toLowerCase(Locale.ROOT));
            if (registrable.isBlank()) {
                return "";
            }
            return uri.getScheme() + "://" + registrable + "/";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String registrableDomain(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String[] parts = host.split("\\.");
        if (parts.length <= 2) {
            return host;
        }
        int last = parts.length - 1;
        boolean countryCodeTail = parts[last].length() == 2;
        boolean shortSecondLevel = parts[last - 1].length() <= 3;
        if (countryCodeTail && shortSecondLevel && parts.length >= 3) {
            return parts[last - 2] + "." + parts[last - 1] + "." + parts[last];
        }
        return parts[last - 1] + "." + parts[last];
    }

    private static String originOf(String rawUrl) {
        try {
            URI uri = URI.create(sanitizeRemoteUrl(rawUrl));
            if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            int port = uri.getPort();
            return port > 0 ? uri.getScheme() + "://" + uri.getHost() + ":" + port : uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignored) {
            return "";
        }
    }

    private record DownloadPayload(byte[] bytes, String resolvedUrl, String contentType) {
    }

    private record DownloadResponse(int statusCode, byte[] body, String contentType, String finalUrl) {
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString((value == null ? "" : value).hashCode());
        }
    }
}
