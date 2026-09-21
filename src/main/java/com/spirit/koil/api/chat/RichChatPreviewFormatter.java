package com.spirit.koil.api.chat;

import net.minecraft.text.Text;

/**
 * Compatibility facade for callers that only need formatted Rich Chat Text.
 * Detached UI surfaces should prefer {@link RichChatSurfaceRenderer} when they
 * also own wrapping/rendering, so formatting and final paint cannot diverge.
 */
public final class RichChatPreviewFormatter {
    private RichChatPreviewFormatter() {
    }

    public static Text format(Text message) {
        return format(message, null, -1);
    }

    public static Text format(Text message, RichChatRowType rowType, int wrapWidth) {
        return RichChatSurfaceRenderer.format(message, rowType, wrapWidth);
    }
}
