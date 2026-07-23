package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatAttachmentType;
import net.minecraft.text.Text;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RichChatWebImageBridge {
    private static final Pattern WEB_URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private RichChatWebImageBridge() {
    }

    public static Text rewrite(Text message) {
        if (message == null) {
            return message;
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
        if (rawUrl.isBlank() || !RichChatRemoteImageCache.likelyPreviewableUrl(rawUrl)) {
            return message;
        }
        String prefix = visible.substring(0, matcher.start());
        String suffix = visible.substring(matcher.end());
        RichChatAttachment attachment = attachment(rawUrl);
        LocalRichAttachmentBridge.rememberVisibleAttachment(attachment, prefix);
        return Text.literal(LocalRichAttachmentBridge.marker(attachment.attachmentId(), prefix) + suffix.stripLeading());
    }

    private static RichChatAttachment attachment(String url) {
        String hash = RichChatRemoteImageCache.sha256(url);
        UUID id = UUID.nameUUIDFromBytes(("rich-chat-url:" + hash).getBytes(StandardCharsets.UTF_8));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source_url", url);
        metadata.put("upload_state", "remote_link");
        metadata.put("remote_hash", hash);
        String fileName = RichChatRemoteImageCache.bestRemoteFileName(url);
        return new RichChatAttachment(
                id,
                fileName.toLowerCase(Locale.ROOT).endsWith(".gif") ? RichChatAttachmentType.GIF : RichChatAttachmentType.IMAGE,
                fileName,
                RichChatRemoteImageCache.mimeForUrl(fileName),
                0L,
                hash,
                0,
                0,
                0L,
                metadata
        );
    }
}
