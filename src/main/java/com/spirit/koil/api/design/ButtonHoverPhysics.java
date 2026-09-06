package com.spirit.koil.api.design;

import com.spirit.koil.api.design.particle.KoilUiParticleEngine;
import net.minecraft.client.gui.DrawContext;

/**
 * Compatibility adapter for the existing title/options Koil buttons.
 *
 * <p>The original button-facing API is intentionally retained so current
 * screens do not need to know about particle registry or simulation details.
 * New UI code can use {@link KoilUiParticleEngine} directly.</p>
 */
public final class ButtonHoverPhysics {
    private final KoilUiParticleEngine engine = new KoilUiParticleEngine();

    public void reset() {
        engine.reset();
    }

    public void beginFrame(
        int screenWidth,
        int screenHeight,
        int buttonX,
        int buttonY,
        int buttonWidth,
        int buttonHeight,
        boolean hovered
    ) {
        engine.beginFrame(screenWidth, screenHeight, buttonX, buttonY, buttonWidth, buttonHeight, hovered);
    }

    public void renderBehind(DrawContext context) {
        engine.renderBehind(context);
    }

    public void renderForeground(DrawContext context) {
        engine.renderForeground(context);
    }

    /** Optional screen-specific effect pool. Empty means every registered effect. */
    public void setEffectPool(String... effectIds) {
        engine.setEffectPool(effectIds);
    }

    public String getCurrentEffectId() {
        return engine.getCurrentEffectId();
    }

    public KoilUiParticleEngine engine() {
        return engine;
    }
}
