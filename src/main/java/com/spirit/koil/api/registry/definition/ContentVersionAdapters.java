package com.spirit.koil.api.registry.definition;

/** Central adapter selection; version checks stay out of scanners and holders. */
public final class ContentVersionAdapters {
    private static final ContentVersionAdapter MINECRAFT_1_20_1 = new VersionAdapter_1_20_1();

    private ContentVersionAdapters() {
    }

    public static ContentVersionAdapter current() {
        return MINECRAFT_1_20_1;
    }
}
