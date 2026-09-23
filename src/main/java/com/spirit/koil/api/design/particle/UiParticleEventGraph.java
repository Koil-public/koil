package com.spirit.koil.api.design.particle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Small composable event graph for addon-authored effects. It deliberately uses
 * callbacks instead of inventing a second particle runtime.
 */
public final class UiParticleEventGraph {
    public enum Event { BEGIN, TICK, BOUNCE, EXPIRE, DETONATE, SPLIT }

    private final Map<Event, List<Consumer<UiParticleEngine.EffectContext>>> effectActions = new EnumMap<>(Event.class);
    private final Map<Event, List<Consumer<UiParticleEngine.ParticleEventContext>>> particleActions = new EnumMap<>(Event.class);

    public UiParticleEventGraph onEffect(Event event, Consumer<UiParticleEngine.EffectContext> action) {
        effectActions.computeIfAbsent(event, ignored -> new ArrayList<>()).add(action);
        return this;
    }

    public UiParticleEventGraph onParticle(Event event, Consumer<UiParticleEngine.ParticleEventContext> action) {
        particleActions.computeIfAbsent(event, ignored -> new ArrayList<>()).add(action);
        return this;
    }

    public void fire(Event event, UiParticleEngine.EffectContext context) {
        for (Consumer<UiParticleEngine.EffectContext> action : effectActions.getOrDefault(event, List.of())) action.accept(context);
    }

    public void fire(Event event, UiParticleEngine.ParticleEventContext context) {
        for (Consumer<UiParticleEngine.ParticleEventContext> action : particleActions.getOrDefault(event, List.of())) action.accept(context);
    }
}
