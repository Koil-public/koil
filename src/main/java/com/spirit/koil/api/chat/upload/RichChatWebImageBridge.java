package com.spirit.koil.api.chat.upload;

import net.minecraft.text.Text;

/** @deprecated use {@link RichChatWebAttachmentBridge}. */
@Deprecated(forRemoval = false)
public final class RichChatWebImageBridge {
    private RichChatWebImageBridge() {
    }

    public static Text rewrite(Text message) {
        return RichChatWebAttachmentBridge.rewrite(message);
    }
}
