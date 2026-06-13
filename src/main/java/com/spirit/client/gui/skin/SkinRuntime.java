package com.spirit.client.gui.skin;

import net.minecraft.util.Identifier;

public final class SkinRuntime {
    private static String activeSkinId;
    private static Identifier activeSkinTexture;
    private static boolean activeSlim;

    private SkinRuntime() {
    }

    public static void setActiveSkin(String id, Identifier texture, boolean slim) {
        activeSkinId = id;
        activeSkinTexture = texture;
        activeSlim = slim;
    }

    public static void clearActiveSkin() {
        activeSkinId = null;
        activeSkinTexture = null;
        activeSlim = false;
    }

    public static String getActiveSkinId() {
        return activeSkinId;
    }

    public static Identifier getActiveSkinTexture() {
        return activeSkinTexture;
    }

    public static boolean isActiveSlim() {
        return activeSlim;
    }
}
