package com.spirit.koil.api.chat;

/**
 * Private Rich Chat continuation markers used after native line wrapping.
 * These markers carry structural style without repeating user-visible source
 * syntax such as {@code -# } on continuation rows.
 */
public final class RichChatStructuralContinuation {
    public static final char SUBTEXT = '\uE380';

    private RichChatStructuralContinuation() {
    }

    public static boolean isSubtext(String value, int index) {
        return value != null
                && index >= 0
                && index < value.length()
                && value.charAt(index) == SUBTEXT;
    }
}
