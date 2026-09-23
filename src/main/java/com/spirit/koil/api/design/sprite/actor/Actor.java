package com.spirit.koil.api.design.sprite.actor;

import com.spirit.koil.api.design.sprite.physics.PhysicsBody2D;

/** Base class for dynamic scene objects. Physical state is owned by PhysicsBody2D. */
public abstract class Actor {
    public enum Authority { SCENE, LEGACY_PROXY }

    private final long id;
    private final Authority authority;
    private final PhysicsBody2D body;
    private boolean removed;

    protected Actor(long id, Authority authority, float x, float y, int depth) {
        this.id = id;
        this.authority = authority == null ? Authority.SCENE : authority;
        this.body = new PhysicsBody2D(x, y, depth);
    }

    public abstract String kind();

    public long id() { return id; }
    public Authority authority() { return authority; }
    public PhysicsBody2D body() { return body; }
    public float x() { return body.x(); }
    public float y() { return body.y(); }
    public float previousX() { return body.previousX(); }
    public float previousY() { return body.previousY(); }
    public float velocityX() { return body.velocityX(); }
    public float velocityY() { return body.velocityY(); }
    public float rotation() { return body.rotation(); }
    public float previousRotation() { return body.previousRotation(); }
    public float angularVelocity() { return body.angularVelocity(); }
    public float halfWidth() { return body.halfWidth(); }
    public float halfHeight() { return body.halfHeight(); }
    public float gravity() { return body.gravity(); }
    public float drag() { return body.linearDampingPerMinecraftTick(); }
    public int depth() { return body.depth(); }
    public boolean removed() { return removed; }

    public void setBodySize(float width, float height) { body.setBodySize(width, height); }
    public void setMass(float mass) { body.setMass(mass); }
    public void setGravity(float gravity) { body.setGravity(gravity); }
    public void setDrag(float drag) { body.setLinearDampingPerMinecraftTick(drag); }
    public void setRestitution(float value) { body.setRestitution(value); }
    public void setSurfaceFriction(float value) { body.setSurfaceFriction(value); }
    public void setVelocity(float x, float y) { body.setVelocity(x, y); }
    public void setAngularVelocity(float value) { body.setAngularVelocity(value); }
    public void setRotation(float value) { body.setRotation(value); }
    public void setDepth(int depth) { body.setDepth(depth); }
    public void setKinematic(boolean value) {
        body.setMotionType(value ? PhysicsBody2D.MotionType.KINEMATIC : PhysicsBody2D.MotionType.DYNAMIC);
    }
    public void remove() { removed = true; }

    public void syncExternal(float x, float y, float vx, float vy, float rotation, float angularVelocity, int depth) {
        body.syncExternal(x, y, vx, vy, rotation, angularVelocity, depth);
    }

    public float interpolatedX(float alpha) { return body.interpolatedX(alpha); }
    public float interpolatedY(float alpha) { return body.interpolatedY(alpha); }
    public float interpolatedRotation(float alpha) { return body.interpolatedRotation(alpha); }
}
