package com.spirit.koil.api.design.sprite.core;

import com.spirit.koil.api.design.sprite.world.Scene;

@FunctionalInterface
public interface SceneCommand {
    void apply(Scene scene);
}
