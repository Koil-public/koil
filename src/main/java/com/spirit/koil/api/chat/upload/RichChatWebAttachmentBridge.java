package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatAttachmentType;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts direct web-hosted files into the same attachment cards as uploads. */
public final class RichChatWebAttachmentBridge {
    private static final Pattern WEB_URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

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
        List<String> urls = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        while (matcher.find()) {
            String url = RichChatRemoteImageCache.sanitizeRemoteUrl(matcher.group());
            if (url.isBlank() || !isDirectAttachmentUrl(url)) {
                continue;
            }
            urls.add(url);
            ranges.add(new int[]{matcher.start(), matcher.end()});
        }
        if (urls.isEmpty()) {
            return message;
        }
        if (urls.size() > 1) {
            return rewriteMultiple(visible, urls, ranges);
        }
        String rawUrl = urls.get(0);
        int[] range = ranges.get(0);
        String prefix = visible.substring(0, range[0]);
        String suffix = visible.substring(range[1]);
        RichChatAttachment attachment = attachment(rawUrl);
        LocalRichAttachmentBridge.rememberVisibleAttachment(attachment, prefix);
        return Text.literal(LocalRichAttachmentBridge.marker(attachment.attachmentId(), prefix) + suffix.stripLeading());
    }

    private static Text rewriteMultiple(String visible, List<String> urls, List<int[]> ranges) {
        StringBuilder body = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < urls.size(); i++) {
            int[] range = ranges.get(i);
            body.append(visible, cursor, range[0]);
            String matched = visible.substring(range[0], range[1]);
            String url = urls.get(i);
            if (matched.length() > url.length()) {
                body.append(matched.substring(url.length()));
            }
            cursor = range[1];
        }
        body.append(visible.substring(cursor));
        String visibleBody = body.toString().replaceAll("[\\t ]{2,}", " ").stripTrailing();
        String beforeFirstUrl = visible.substring(0, ranges.get(0)[0]);
        String indent = com.spirit.koil.api.chat.LocalMultilineChatBridge.indentForPrefix(beforeFirstUrl);
        boolean prefixOnly = visibleBody.strip().equals(beforeFirstUrl.strip());

        StringBuilder rewritten = new StringBuilder();
        if (!prefixOnly && !visibleBody.isBlank()) {
            rewritten.append(visibleBody);
        }
        for (int i = 0; i < urls.size(); i++) {
            RichChatAttachment attachment = attachment(urls.get(i));
            String markerPrefix = prefixOnly && i == 0 ? beforeFirstUrl : indent;
            LocalRichAttachmentBridge.rememberVisibleAttachment(attachment, markerPrefix);
            if (rewritten.length() > 0) {
                rewritten.append('\n');
            }
            rewritten.append(LocalRichAttachmentBridge.marker(attachment.attachmentId(), markerPrefix));
        }
        return rewritten.length() == 0 ? Text.literal(visible) : Text.literal(rewritten.toString());
    }

    private static boolean isDirectAttachmentUrl(String url) {
        return RichChatRemoteMediaResolver.looksLikeProcessableUrl(url);
    }

    private static RichChatAttachment attachment(String url) {
        String hash = RichChatRemoteImageCache.sha256(url);
        UUID id = UUID.nameUUIDFromBytes(("rich-chat-url:" + hash).getBytes(StandardCharsets.UTF_8));
        RichChatRemoteMediaResolver.Descriptor descriptor = RichChatRemoteMediaResolver.infer(url);
        RichChatAttachmentType type = descriptor.type() == null ? RichChatAttachmentType.FILE : descriptor.type();
        String fileName = descriptor.fileName();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source_url", url);
        metadata.put("upload_state", "remote_link");
        metadata.put("remote_hash", hash);
        metadata.put("inferred_type", type.name().toLowerCase(Locale.ROOT));
        metadata.put("inferred_extension", descriptor.extension());
        RichChatAttachment attachment = new RichChatAttachment(
                id,
                type,
                fileName,
                descriptor.mimeType(),
                0L,
                hash,
                0,
                0,
                0L,
                metadata
        );
        // A previously resolved URL can reserve its exact measured height at
        // insertion time. A first-time URL starts its async probe here and
        // uses the renderer's bounded maximum-height reservation until ready.
        RichChatRemoteImageCache.localPath(attachment);
        return RichChatRemoteImageCache.resolvedAttachment(attachment);
    }
}
