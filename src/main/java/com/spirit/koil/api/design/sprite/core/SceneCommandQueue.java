package com.spirit.koil.api.design.sprite.core;

import com.spirit.koil.api.design.sprite.world.Scene;
import java.util.ArrayDeque;
import java.util.Queue;

/** Mutations enter the scene through a deterministic command queue. */
public final class SceneCommandQueue {
    private final Queue<SceneCommand> commands = new ArrayDeque<>();

    public void submit(SceneCommand command) { if (command != null) commands.add(command); }

    public int drain(Scene scene) {
        int applied = 0;
        while (!commands.isEmpty()) {
            SceneCommand command = commands.poll();
            if (command != null) { command.apply(scene); applied++; }
        }
        return applied;
    }

    public int size() { return commands.size(); }
    public void clear() { commands.clear(); }
}
