package com.spirit.koil.api.design.sprite.physics;

/** Immutable axis-aligned 2D collision rectangle in screen coordinates. */
public record CollisionShape2D(float left, float top, float right, float bottom) {
    public CollisionShape2D {
        float normalizedLeft = Math.min(left, right);
        float normalizedRight = Math.max(left, right);
        float normalizedTop = Math.min(top, bottom);
        float normalizedBottom = Math.max(top, bottom);
        left = normalizedLeft;
        right = normalizedRight;
        top = normalizedTop;
        bottom = normalizedBottom;
    }

    public float width() { return Math.max(0.0F, right - left); }
    public float height() { return Math.max(0.0F, bottom - top); }
    public float centerX() { return (left + right) * 0.5F; }
    public float centerY() { return (top + bottom) * 0.5F; }

    public CollisionShape2D expanded(float x, float y) {
        return new CollisionShape2D(left - x, top - y, right + x, bottom + y);
    }

    public CollisionShape2D union(CollisionShape2D other) {
        if (other == null) return this;
        return new CollisionShape2D(
                Math.min(left, other.left), Math.min(top, other.top),
                Math.max(right, other.right), Math.max(bottom, other.bottom));
    }

    public boolean intersects(CollisionShape2D other) {
        return other != null && right > other.left && left < other.right
                && bottom > other.top && top < other.bottom;
    }
}
