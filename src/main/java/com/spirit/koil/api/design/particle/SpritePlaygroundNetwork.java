package com.spirit.koil.api.design.particle;

import net.minecraft.util.Identifier;

/** Network contract for scene-playground and saved-background control. */
public final class SpritePlaygroundNetwork {
    public static final Identifier CONTROL_PACKET = new Identifier("koil", "sprite_playground_control");

    public static final String ACTION_OPEN = "open";
    public static final String ACTION_LOAD_EDITOR = "load_editor";
    public static final String ACTION_CLEAR_EDITOR = "clear_editor";
    public static final String ACTION_LOAD_BACKGROUND = "load_background";
    public static final String ACTION_LOAD_LAST_EDITOR = "load_last_editor";
    public static final String ACTION_LOAD_LAST_BACKGROUND = "load_last_background";
    public static final String ACTION_CLEAR_BACKGROUND = "clear_background";

    public static final int MAX_ACTION_LENGTH = 32;
    public static final int MAX_SCENE_NAME_LENGTH = 128;

    private SpritePlaygroundNetwork() { }
}
