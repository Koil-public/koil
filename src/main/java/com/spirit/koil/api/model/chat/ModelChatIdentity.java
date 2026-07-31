package com.spirit.koil.api.model.chat;

/**
 * One visible identity contract for streamed and finalized local-model text.
 * The marker is intentionally not a player name, but Rich Chat treats it as a
 * body prefix so continuation rows never render beneath it.
 */
public final class ModelChatIdentity {
    public static final String LABEL = ">_";
    public static final String PREFIX = LABEL + ": ";

    private ModelChatIdentity() {
    }

    public static String decorate(String text) {
        String safe = text == null ? "" : text;
        return safe.startsWith(PREFIX) ? safe : PREFIX + safe;
    }

    /**
     * Continuation rows are represented by whole space glyphs. Align the
     * first row's body to that same advance so wrapped rows do not appear to
     * creep right when the prefix width is not divisible by the space width.
     */
    public static int alignedPrefixAdvance(int prefixWidth, int spaceWidth) {
        int safePrefixWidth = Math.max(0, prefixWidth);
        int safeSpaceWidth = Math.max(1, spaceWidth);
        return (int) Math.ceil(safePrefixWidth / (double) safeSpaceWidth) * safeSpaceWidth;
    }

    public static int alignmentPadding(int prefixWidth, int spaceWidth) {
        return alignedPrefixAdvance(prefixWidth, spaceWidth) - Math.max(0, prefixWidth);
    }
}
