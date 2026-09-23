package com.spirit.koil.api.design.sprite.physics;

/**
 * Authoritative fixed-step transform and physical state for a dynamic Koil actor.
 * Rendering reads interpolated state from this body, but never mutates gameplay state.
 */
public final class PhysicsBody2D {
    public enum MotionType { DYNAMIC, KINEMATIC }

    private float x;
    private float y;
    private float previousX;
    private float previousY;
    private float velocityX;
    private float velocityY;
    private float rotation;
    private float previousRotation;
    private float angularVelocity;
    private float halfWidth = 6.0F;
    private float halfHeight = 6.0F;
    private float mass = 1.0F;
    private float gravity = 256.0F;
    private float linearDampingPerMinecraftTick = 0.98F;
    private float angularDampingPerMinecraftTick = 0.91F;
    private float restitution = 0.15F;
    private float surfaceFriction = 0.82F;
    private int depth;
    private MotionType motionType = MotionType.DYNAMIC;
    private boolean collideWorld = true;
    private boolean collideActors = true;
    private boolean collideSceneBounds = true;
    private boolean canSleep = true;
    private boolean sleeping;
    private boolean grounded;
    private float sleepSeconds;
    private boolean externalPosePending;

    public PhysicsBody2D(float x, float y, int depth) {
        this.x = this.previousX = x;
        this.y = this.previousY = y;
        this.depth = depth;
    }

    public float x() { return x; }
    public float y() { return y; }
    public float previousX() { return previousX; }
    public float previousY() { return previousY; }
    public float velocityX() { return velocityX; }
    public float velocityY() { return velocityY; }
    public float rotation() { return rotation; }
    public float previousRotation() { return previousRotation; }
    public float angularVelocity() { return angularVelocity; }
    public float halfWidth() { return halfWidth; }
    public float halfHeight() { return halfHeight; }
    public float mass() { return mass; }
    public float inverseMass() { return motionType == MotionType.KINEMATIC ? 0.0F : 1.0F / Math.max(0.001F, mass); }
    public float gravity() { return gravity; }
    public float linearDampingPerMinecraftTick() { return linearDampingPerMinecraftTick; }
    public float angularDampingPerMinecraftTick() { return angularDampingPerMinecraftTick; }
    public float restitution() { return restitution; }
    public float surfaceFriction() { return surfaceFriction; }
    public int depth() { return depth; }
    public MotionType motionType() { return motionType; }
    public boolean dynamic() { return motionType == MotionType.DYNAMIC; }
    public boolean kinematic() { return motionType == MotionType.KINEMATIC; }
    public boolean collideWorld() { return collideWorld; }
    public boolean collideActors() { return collideActors; }
    public boolean collideSceneBounds() { return collideSceneBounds; }
    public boolean canSleep() { return canSleep; }
    public boolean sleeping() { return sleeping; }
    public boolean grounded() { return grounded; }
    public float sleepSeconds() { return sleepSeconds; }

    public CollisionShape2D bounds() {
        return new CollisionShape2D(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
    }

    public CollisionShape2D previousBounds() {
        return new CollisionShape2D(previousX - halfWidth, previousY - halfHeight,
                previousX + halfWidth, previousY + halfHeight);
    }

    public CollisionShape2D sweptBounds() { return bounds().union(previousBounds()); }

    public void setBodySize(float width, float height) {
        halfWidth = Math.max(0.25F, width * 0.5F);
        halfHeight = Math.max(0.25F, height * 0.5F);
        wake();
    }

    public void setMass(float mass) { this.mass = Math.max(0.001F, mass); }
    public void setGravity(float gravity) { this.gravity = Float.isFinite(gravity) ? gravity : 0.0F; }
    public void setLinearDampingPerMinecraftTick(float value) {
        linearDampingPerMinecraftTick = clamp01(value);
    }
    public void setAngularDampingPerMinecraftTick(float value) {
        angularDampingPerMinecraftTick = clamp01(value);
    }
    public void setRestitution(float value) { restitution = clamp01(value); }
    public void setSurfaceFriction(float value) { surfaceFriction = clamp01(value); }
    public void setDepth(int depth) { this.depth = depth; }
    public void setCollideWorld(boolean value) { collideWorld = value; }
    public void setCollideActors(boolean value) { collideActors = value; }
    public void setCollideSceneBounds(boolean value) { collideSceneBounds = value; }
    public void setCanSleep(boolean value) {
        canSleep = value;
        if (!value) wake();
    }

    public void setMotionType(MotionType type) {
        MotionType next = type == null ? MotionType.DYNAMIC : type;
        if (motionType != next) {
            motionType = next;
            wake();
        }
    }

    public void setVelocity(float vx, float vy) {
        velocityX = finite(vx);
        velocityY = finite(vy);
        if (Math.abs(velocityX) > 0.001F || Math.abs(velocityY) > 0.001F) wake();
    }

    public void addVelocity(float vx, float vy) {
        setVelocity(velocityX + finite(vx), velocityY + finite(vy));
    }

    public void setAngularVelocity(float value) {
        angularVelocity = finite(value);
        if (Math.abs(angularVelocity) > 0.001F) wake();
    }

    public void setPosition(float x, float y) {
        this.x = finite(x);
        this.y = finite(y);
        wake();
    }

    public void teleport(float x, float y) {
        this.x = this.previousX = finite(x);
        this.y = this.previousY = finite(y);
        wake();
    }

    public void setRotation(float rotation) {
        this.rotation = finite(rotation);
        wake();
    }

    public void syncExternal(float x, float y, float vx, float vy,
                             float rotation, float angularVelocity, int depth) {
        previousX = this.x;
        previousY = this.y;
        previousRotation = this.rotation;
        this.x = finite(x);
        this.y = finite(y);
        this.velocityX = finite(vx);
        this.velocityY = finite(vy);
        this.rotation = finite(rotation);
        this.angularVelocity = finite(angularVelocity);
        this.depth = depth;
        externalPosePending = true;
        wake();
    }

    void beginStep() {
        if (!externalPosePending) {
            previousX = x;
            previousY = y;
            previousRotation = rotation;
        }
        grounded = false;
    }

    void finishStep() { externalPosePending = false; }

    void integrateVelocity(float dt) {
        if (!dynamic() || sleeping) return;
        velocityY += gravity * dt;
        float tickScale = Math.max(0.0F, dt * 20.0F);
        velocityX *= (float) Math.pow(linearDampingPerMinecraftTick, tickScale);
        velocityY *= (float) Math.pow(linearDampingPerMinecraftTick, tickScale);
        angularVelocity *= (float) Math.pow(angularDampingPerMinecraftTick, tickScale);
    }

    void integratePosition(float dt) {
        if (!dynamic() || sleeping) return;
        x += velocityX * dt;
        y += velocityY * dt;
        rotation += angularVelocity * dt;
    }

    void moveTo(float x, float y) {
        this.x = finite(x);
        this.y = finite(y);
    }

    void setGrounded(boolean value) { grounded = value; }

    void updateSleep(float dt, boolean supported) {
        if (!canSleep || !dynamic()) {
            sleepSeconds = 0.0F;
            sleeping = false;
            return;
        }
        float speedSq = velocityX * velocityX + velocityY * velocityY;
        if (supported && speedSq < 16.0F && Math.abs(angularVelocity) < 10.0F) {
            sleepSeconds += dt;
            if (sleepSeconds >= 0.22F) {
                sleeping = true;
                velocityX = 0.0F;
                velocityY = 0.0F;
                angularVelocity = 0.0F;
            }
        } else {
            sleepSeconds = 0.0F;
            sleeping = false;
        }
    }

    public void wake() {
        sleeping = false;
        sleepSeconds = 0.0F;
    }

    public float interpolatedX(float alpha) { return previousX + (x - previousX) * clamp01(alpha); }
    public float interpolatedY(float alpha) { return previousY + (y - previousY) * clamp01(alpha); }
    public float interpolatedRotation(float alpha) { return previousRotation + (rotation - previousRotation) * clamp01(alpha); }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float finite(float value) { return Float.isFinite(value) ? value : 0.0F; }
}
