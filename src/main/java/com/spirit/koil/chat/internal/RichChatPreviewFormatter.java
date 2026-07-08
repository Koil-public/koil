package com.spirit.koil.chat.internal;

import com.spirit.koil.chat.internal.latex.RichChatLatexFormatter;
import net.minecraft.text.Text;

public final class RichChatPreviewFormatter {
    private RichChatPreviewFormatter() {
    }

    public static Text format(Text message) {
        if (message == null) {
            return null;
        }
        Text rewritten = RichChatLatexFormatter.format(message);
        rewritten = RichChatPrivateMessageBridge.observeAndRewrite(rewritten);
        rewritten = RichChatCodeBlockBridge.rewrite(rewritten);
        if (rewritten == null || !RichChatPrivateMessageBridge.isPrivateMessageLine(rewritten.getString())) {
            rewritten = RichChatBodyWrapFormatter.format(rewritten);
        }
        return rewritten;
    }
}
