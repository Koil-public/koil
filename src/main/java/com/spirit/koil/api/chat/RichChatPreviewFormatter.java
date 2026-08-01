package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.latex.RichChatLatexFormatter;
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
        rewritten = RichChatTableBridge.rewrite(rewritten);
        if (rewritten != null) {
            RichChatRowType rowType = RichChatRowClassifier.classify(rewritten, null);
            if (rowType == RichChatRowType.PLAYER_CHAT
                    || rowType == RichChatRowType.PRIVATE_MESSAGE
                    || rowType == RichChatRowType.MODEL_RESPONSE) {
                rewritten = RichChatBodyWrapFormatter.format(rewritten, rowType);
            }
        }
        rewritten = RichChatMaskedLinkBridge.rewrite(rewritten);
        return RichChatSectionFormatting.styleBeforeWrapping(rewritten);
    }
}
