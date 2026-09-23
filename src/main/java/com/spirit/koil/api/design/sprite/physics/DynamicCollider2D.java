package com.spirit.koil.api.design.sprite.physics;

import com.spirit.koil.api.design.sprite.actor.Actor;

/** Dynamic broad-phase entry used for actor-to-actor collision. */
public record DynamicCollider2D(Actor actor, CollisionShape2D bounds, int depth) { }
