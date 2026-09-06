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
public final class KoilUiParticleEventGraph {
    public enum Event { BEGIN, TICK, BOUNCE, EXPIRE, DETONATE, SPLIT }

    private final Map<Event, List<Consumer<KoilUiParticleEngine.EffectContext>>> effectActions = new EnumMap<>(Event.class);
    private final Map<Event, List<Consumer<KoilUiParticleEngine.ParticleEventContext>>> particleActions = new EnumMap<>(Event.class);

    public KoilUiParticleEventGraph onEffect(Event event, Consumer<KoilUiParticleEngine.EffectContext> action) {
        effectActions.computeIfAbsent(event, ignored -> new ArrayList<>()).add(action);
        return this;
    }

    public KoilUiParticleEventGraph onParticle(Event event, Consumer<KoilUiParticleEngine.ParticleEventContext> action) {
        particleActions.computeIfAbsent(event, ignored -> new ArrayList<>()).add(action);
        return this;
    }

    public void fire(Event event, KoilUiParticleEngine.EffectContext context) {
        for (Consumer<KoilUiParticleEngine.EffectContext> action : effectActions.getOrDefault(event, List.of())) action.accept(context);
    }

    public void fire(Event event, KoilUiParticleEngine.ParticleEventContext context) {
        for (Consumer<KoilUiParticleEngine.ParticleEventContext> action : particleActions.getOrDefault(event, List.of())) action.accept(context);
    }
}
