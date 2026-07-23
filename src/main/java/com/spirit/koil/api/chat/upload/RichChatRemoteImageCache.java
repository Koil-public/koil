package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatAttachmentType;
import com.spirit.koil.api.util.file.media.video.VideoMetadata;
import com.spirit.koil.api.util.file.media.video.VideoProbeResult;
import com.spirit.koil.api.util.file.media.video.VideoService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

public final class RichChatRemoteImageCache {
    private static final Path CACHE_ROOT = Path.of("koil", "cache", "chat_media", "remote_images");
    private static final long MAX_HTML_BYTES = 2L * 1024L * 1024L;
    private static final long RETRY_FAILED_AFTER_MS = 5000L;
    private static final Set<String> DOWNLOADING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, RichChatRemoteMediaResolver.Descriptor> RESOLVED = new ConcurrentHashMap<>();
    private static final Map<String, MediaDimensions> RESOLVED_DIMENSIONS = new ConcurrentHashMap<>();
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";
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
        return RichChatRemoteMediaResolver.looksLikeProcessableUrl(rawUrl);
    }

    public static String bestRemoteFileName(String rawUrl) {
        return RichChatRemoteMediaResolver.infer(rawUrl).fileName();
    }

    public static String mimeForUrl(String rawUrl) {
        return RichChatRemoteMediaResolver.infer(rawUrl).mimeType();
    }

    public static Optional<Path> downloadClipboardImage(String rawUrl) {
        String url = sanitizeRemoteUrl(rawUrl);
        if (url.isBlank()) {
            return Optional.empty();
        }
        try {
            Path directory = Path.of("koil", "cache", "chat_media", "clipboard");
            Files.createDirectories(directory);
            RichChatAttachmentType expectedType = RichChatRemoteMediaResolver.infer(url).type();
            if (expectedType != RichChatAttachmentType.GIF) {
                expectedType = RichChatAttachmentType.IMAGE;
            }
            DownloadPayload payload = downloadPayload(url, 0, null, expectedType);
            if (payload == null || payload.bytes() == null || payload.bytes().length == 0) {
                return Optional.empty();
            }
            if (payload.descriptor().type() != RichChatAttachmentType.IMAGE && payload.descriptor().type() != RichChatAttachmentType.GIF) {
                return Optional.empty();
            }
            String extension = payload.descriptor().extension();
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
            rememberResolvedFromFile(key, cached.get(), attachment);
            return cached;
        }
        long failedAt = FAILED.getOrDefault(key, 0L);
        if ((failedAt <= 0L || System.currentTimeMillis() - failedAt >= RETRY_FAILED_AFTER_MS) && DOWNLOADING.add(key)) {
            download(url, key, attachment.type());
        }
        return Optional.empty();
    }

    public static RichChatAttachment resolvedAttachment(RichChatAttachment attachment) {
        if (attachment == null || attachment.metadata() == null) {
            return attachment;
        }
        String url = attachment.metadata().get("source_url");
        if (url == null || url.isBlank()) {
            return attachment;
        }
        String key = attachment.sha256().isBlank() ? sha256(url) : attachment.sha256();
        RichChatRemoteMediaResolver.Descriptor descriptor = RESOLVED.get(key);
        if (descriptor == null) {
            existingCachedFile(key).ifPresent(file -> rememberResolvedFromFile(key, file, attachment));
            descriptor = RESOLVED.get(key);
        }
        if (descriptor == null || descriptor.type() == null) {
            return attachment;
        }
        Map<String, String> metadata = new java.util.LinkedHashMap<>(attachment.metadata());
        metadata.put("resolved_type", descriptor.type().name().toLowerCase(Locale.ROOT));
        metadata.put("resolved_content_type", descriptor.mimeType());
        metadata.put("resolved_extension", descriptor.extension());
        String fileName = resolvedDisplayName(attachment.fileName(), descriptor);
        long size = existingCachedFile(key).map(File::length).orElse(attachment.sizeBytes());
        MediaDimensions dimensions = RESOLVED_DIMENSIONS.getOrDefault(
                key,
                new MediaDimensions(attachment.width(), attachment.height(), attachment.durationMillis())
        );
        return new RichChatAttachment(
                attachment.attachmentId(),
                descriptor.type(),
                fileName,
                descriptor.mimeType(),
                size,
                attachment.sha256(),
                dimensions.width(),
                dimensions.height(),
                dimensions.durationMillis(),
                metadata
        );
    }

    private static void download(String url, String key, RichChatAttachmentType expectedType) {
        try {
            Files.createDirectories(CACHE_ROOT);
            CompletableFuture.runAsync(() -> {
                try {
                    DownloadPayload payload = downloadPayload(url, 0, null, expectedType);
                    if (payload == null || payload.bytes() == null || payload.bytes().length == 0) {
                        FAILED.put(key, System.currentTimeMillis());
                        return;
                    }
                    String extension = payload.descriptor().extension().isBlank() ? "bin" : payload.descriptor().extension();
                    Path target = CACHE_ROOT.resolve(key + "." + extension);
                    Files.write(target, payload.bytes());
                    RESOLVED.put(key, payload.descriptor());
                    RESOLVED_DIMENSIONS.put(key, inspectDimensions(target, payload.descriptor().type()));
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
                    .filter(path -> {
                        try {
                            return Files.size(path) > 0L;
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .findFirst()
                    .map(Path::toFile);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static DownloadPayload downloadPayload(String url, int depth, String refererHint, RichChatAttachmentType expectedType) {
        if (url == null || url.isBlank() || depth > 3) {
            return null;
        }
        try {
            DownloadResponse response = requestWithFallbacks(url, refererHint, expectedType);
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                return null;
            }
            if (RichChatRemoteMediaResolver.isHtml(response.contentType(), body)) {
                if (body.length > MAX_HTML_BYTES) {
                    return null;
                }
                for (RichChatRemoteMediaResolver.MediaCandidate candidate :
                        RichChatRemoteMediaResolver.embeddedMedia(response.finalUrl(), body, expectedType)) {
                    if (candidate.url().equals(response.finalUrl())) {
                        continue;
                    }
                    DownloadPayload resolved = downloadPayload(candidate.url(), depth + 1, response.finalUrl(), candidate.type());
                    if (resolved != null) {
                        return resolved;
                    }
                }
                if (expectedType != RichChatAttachmentType.FILE) {
                    return null;
                }
            }
            RichChatRemoteMediaResolver.Descriptor descriptor = RichChatRemoteMediaResolver.inspect(
                    url,
                    response.finalUrl(),
                    response.contentType(),
                    response.contentDisposition(),
                    body,
                    expectedType
            );
            if (descriptor.type() == null) {
                return null;
            }
            return new DownloadPayload(body, response.finalUrl(), descriptor);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static DownloadResponse requestWithFallbacks(String url, String refererHint, RichChatAttachmentType expectedType) {
        LinkedHashSet<String> attempts = new LinkedHashSet<>();
        attempts.addAll(refererCandidates(url, refererHint));
        attempts.add(null);

        DownloadResponse last = null;
        for (String referer : attempts) {
            last = requestOnce(url, referer, expectedType);
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

    private static DownloadResponse requestOnce(String url, String referer, RichChatAttachmentType expectedType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", RichChatRemoteMediaResolver.acceptHeader(expectedType))
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .GET();
            if (referer != null && !referer.isBlank()) {
                builder.header("Referer", referer);
                String origin = originOf(referer);
                if (!origin.isBlank()) {
                    builder.header("Origin", origin);
                }
            }
            HttpResponse<InputStream> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response == null) {
                return null;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            String contentDisposition = response.headers().firstValue("content-disposition").orElse("");
            long limit = contentType.toLowerCase(Locale.ROOT).contains("html")
                    ? MAX_HTML_BYTES
                    : RichChatRemoteMediaResolver.maxBytes(expectedType);
            long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            byte[] body;
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    body = new byte[0];
                } else if (declaredLength > limit) {
                    return new DownloadResponse(413, new byte[0], contentType, contentDisposition, response.uri() == null ? url : response.uri().toString());
                } else {
                    body = input.readNBytes((int) Math.min(Integer.MAX_VALUE, limit + 1L));
                    if (body.length > limit) {
                        return new DownloadResponse(413, new byte[0], contentType, contentDisposition, response.uri() == null ? url : response.uri().toString());
                    }
                }
            }
            return new DownloadResponse(
                    response.statusCode(),
                    body,
                    contentType,
                    contentDisposition,
                    response.uri() == null ? url : response.uri().toString()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void rememberResolvedFromFile(String key, File file, RichChatAttachment attachment) {
        if (key == null || key.isBlank() || file == null || !file.isFile() || RESOLVED.containsKey(key)) {
            return;
        }
        RichChatRemoteMediaResolver.Descriptor cached = RichChatRemoteMediaResolver.infer("https://cache.invalid/" + file.getName());
        RichChatAttachmentType type = cached.type() == null && attachment != null ? attachment.type() : cached.type();
        String mime = cached.mimeType();
        if ((mime == null || mime.isBlank() || mime.equals("application/octet-stream")) && attachment != null) {
            mime = attachment.mimeType();
        }
        RESOLVED.put(key, new RichChatRemoteMediaResolver.Descriptor(type, file.getName(), mime, cached.extension()));
        RESOLVED_DIMENSIONS.put(key, inspectDimensions(file.toPath(), type));
    }

    private static MediaDimensions inspectDimensions(Path path, RichChatAttachmentType type) {
        if (path == null || type == null || !Files.isRegularFile(path)) {
            return MediaDimensions.EMPTY;
        }
        if (type == RichChatAttachmentType.VIDEO) {
            try {
                VideoProbeResult probe = VideoService.probe(path.toFile());
                VideoMetadata metadata = probe == null ? null : probe.metadata();
                if (metadata != null) {
                    return new MediaDimensions(
                            Math.max(0, metadata.width()),
                            Math.max(0, metadata.height()),
                            Math.max(0L, metadata.durationMillis())
                    );
                }
            } catch (Exception ignored) {
            }
            return MediaDimensions.EMPTY;
        }
        if (type != RichChatAttachmentType.IMAGE && type != RichChatAttachmentType.GIF) {
            return MediaDimensions.EMPTY;
        }
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            return image == null
                    ? MediaDimensions.EMPTY
                    : new MediaDimensions(Math.max(0, image.getWidth()), Math.max(0, image.getHeight()), 0L);
        } catch (Exception ignored) {
            return MediaDimensions.EMPTY;
        }
    }

    private static String resolvedDisplayName(String original, RichChatRemoteMediaResolver.Descriptor descriptor) {
        String name = original == null || original.isBlank() ? descriptor.fileName() : original;
        String extension = RichChatRemoteMediaResolver.extension(name);
        if (descriptor.extension().isBlank() || descriptor.extension().equalsIgnoreCase(extension)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "." + descriptor.extension();
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

    private record DownloadPayload(byte[] bytes, String resolvedUrl, RichChatRemoteMediaResolver.Descriptor descriptor) {
    }

    private record DownloadResponse(int statusCode, byte[] body, String contentType, String contentDisposition, String finalUrl) {
    }

    private record MediaDimensions(int width, int height, long durationMillis) {
        private static final MediaDimensions EMPTY = new MediaDimensions(0, 0, 0L);
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
