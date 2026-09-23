package com.spirit.koil.api.design.sprite.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Synchronous typed event bus owned by one KoilScene. */
public final class SceneEventBus {
    private final List<Consumer<SceneEvent>> listeners = new ArrayList<>();

    public void subscribe(Consumer<SceneEvent> listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void unsubscribe(Consumer<SceneEvent> listener) { listeners.remove(listener); }

    public void publish(SceneEvent event) {
        if (event == null || listeners.isEmpty()) return;
        for (Consumer<SceneEvent> listener : List.copyOf(listeners)) listener.accept(event);
    }

    public void clearListeners() { listeners.clear(); }
}
