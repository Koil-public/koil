package com.spirit.koil.api.design.sprite.actor;

import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Authoritative dynamic-actor collection. Motion is advanced by KoilPhysicsWorld. */
public final class ActorWorld {
    private final Map<Long, Actor> actors = new LinkedHashMap<>();
    private final SceneEventBus events;
    private final LongSupplier physicsStep;
    private final AtomicLong nextNativeActorId = new AtomicLong(1_000_000_000L);

    public ActorWorld(SceneEventBus events, LongSupplier physicsStep) {
        this.events = events;
        this.physicsStep = physicsStep;
    }

    public long allocateNativeId() {
        long id;
        do { id = nextNativeActorId.getAndIncrement(); } while (actors.containsKey(id));
        return id;
    }

    public Actor get(long id) { return actors.get(id); }

    public <T extends Actor> T put(T actor) {
        if (actor == null) return null;
        Actor previous = actors.put(actor.id(), actor);
        if (previous == null && events != null) {
            events.publish(new SceneEvent.ActorSpawned(actor.id(), actor.kind(), physicsStep.getAsLong()));
        }
        return actor;
    }

    public Actor remove(long id) {
        Actor actor = actors.remove(id);
        if (actor != null) {
            actor.remove();
            if (events != null) events.publish(new SceneEvent.ActorRemoved(id, actor.kind(), physicsStep.getAsLong()));
        }
        return actor;
    }

    public int size() { return actors.size(); }
    public List<Actor> actors() { return List.copyOf(actors.values()); }

    public void clear() {
        actors.clear();
        nextNativeActorId.set(1_000_000_000L);
    }
}
