package com.spirit.koil.api.model.chat;

/**
 * Shared glyph contract for connected model and executor activity rows.
 * Keeping these characters in one place prevents related chat surfaces from
 * drifting back to ASCII approximations such as {@code |---}.
 */
public final class ModelActivityTreeGlyphs {
    public static final String BRANCH = "├─";
    public static final String LAST_BRANCH = "└─";
    public static final String RAIL = "│";

    private ModelActivityTreeGlyphs() {
    }
}
