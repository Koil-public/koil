package com.spirit.koil.api.design.sprite.debug;

/** Stable diagnostics surfaced by the new scene runtime during migration. */
public record SceneDiagnostics(
        String runtimeMode,
        boolean requiresGameplayWorld,
        String projection,
        long minecraftTick,
        long physicsStep,
        float renderAlpha,
        int blocks,
        int chunks,
        int fluids,
        int fluidSources,
        int fluidFalling,
        int actors,
        int legacyBlockProxies,
        int legacyFluidProxies,
        int legacyActorProxies,
        int pendingCommands,
        int staticColliders,
        int physicsContacts,
        int sleepingActors,
        int fallingActors
) { }
