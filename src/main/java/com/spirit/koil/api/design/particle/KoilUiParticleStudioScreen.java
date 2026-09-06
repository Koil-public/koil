package com.spirit.koil.api.design.particle;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla-style particle authoring/debug preview screen.
 *
 * <p>The Studio intentionally uses ordinary Minecraft widgets. This both keeps
 * the screen visually compatible with vanilla 1.20.1 and lets the preview
 * exercise the same button-collider discovery used by normal Koil screens.</p>
 */
public final class KoilUiParticleStudioScreen extends Screen {
    private final Screen parent;
    private final KoilUiParticleEngine engine = new KoilUiParticleEngine();
    private final List<String> ids = new ArrayList<>();
    private ButtonWidget previewButton;
    private int index;
    private boolean previewActive = true;
    private boolean restart = true;

    public KoilUiParticleStudioScreen(Screen parent) {
        super(Text.literal("Koil Particle Studio"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ids.clear();
        ids.addAll(KoilUiParticleRegistry.ids());
        if (ids.isEmpty()) ids.add("starburst");
        index = Math.max(0, Math.min(index, ids.size() - 1));

        int center = width / 2;
        int previewY = Math.max(62, height / 2 - 24);
        previewButton = addDrawableChild(ButtonWidget.builder(Text.literal("Particle Preview"), b -> {
            restart = true;
            previewActive = true;
        }).dimensions(center - 60, previewY, 120, 20).build());

        int sliderY = previewY + 38;
        addDrawableChild(new SettingsSlider(center - 154, sliderY, 100, "Density", 0.0F, 2.0F,
                KoilUiParticleSettings.density(), value -> {
            KoilUiParticleSettings.density(value);
            engine.setDensityScale(value);
        }));
        addDrawableChild(new SettingsSlider(center - 50, sliderY, 100, "Motion", 0.0F, 2.0F,
                KoilUiParticleSettings.motion(), value -> {
            KoilUiParticleSettings.motion(value);
            engine.setMotionScale(value);
        }));
        addDrawableChild(new SettingsSlider(center + 54, sliderY, 100, "Sound", 0.0F, 1.5F,
                KoilUiParticleSettings.soundVolume(), value -> {
            KoilUiParticleSettings.soundVolume(value);
            engine.setSoundVolume(value);
        }));

        int navY = height - 52;
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> change(-1)).dimensions(center - 82, navY, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> change(1)).dimensions(center + 42, navY, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Replay"), b -> restart = true).dimensions(center - 40, navY, 80, 20).build());

        int bottomY = height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Debug"), b -> {
            KoilUiParticleSettings.debug(!engine.isDebugEnabled());
            engine.setDebugEnabled(KoilUiParticleSettings.debug());
        }).dimensions(center - 154, bottomY, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Sound"), b -> {
            KoilUiParticleSettings.sounds(!engine.isSoundEnabled());
            engine.setSoundEnabled(KoilUiParticleSettings.sounds());
        }).dimensions(center - 102, bottomY, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Flash"), b -> {
            KoilUiParticleSettings.flashing(!engine.isFlashingEnabled());
            engine.setFlashingEnabled(KoilUiParticleSettings.flashing());
        }).dimensions(center - 50, bottomY, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("LOD"), b -> cyclePerformance()).dimensions(center + 2, bottomY, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(center + 54, bottomY, 100, 20).build());
    }

    private void change(int direction) {
        if (ids.isEmpty()) return;
        index = Math.floorMod(index + direction, ids.size());
        restart = true;
    }

    private void cyclePerformance() {
        KoilUiParticleEngine.PerformanceMode[] values = KoilUiParticleEngine.PerformanceMode.values();
        KoilUiParticleEngine.PerformanceMode current = engine.getPerformanceMode();
        KoilUiParticleEngine.PerformanceMode next = values[(current.ordinal() + 1) % values.length];
        KoilUiParticleSettings.performanceMode(next);
        engine.setPerformanceMode(next);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        if (previewButton != null) {
            engine.beginFrame(width, height, previewButton.getX(), previewButton.getY(), previewButton.getWidth(), previewButton.getHeight(), "particle.studio", previewActive);
            if (restart && !ids.isEmpty()) {
                engine.selectEffect(ids.get(index), true);
                restart = false;
            }
            engine.renderBehind(context);
        }
        super.render(context, mouseX, mouseY, delta);
        if (previewButton != null) engine.renderForeground(context);

        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Koil Particle Studio"), width / 2, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(ids.isEmpty() ? "No effects" : ids.get(index)), width / 2, 28, 0xFFE6A8);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("quality " + Math.round(engine.getAdaptiveQuality() * 100.0F) + "% | "
                        + engine.getPerformanceMode().name().toLowerCase() + " | "
                        + engine.getParticleCount() + " particles | " + engine.getButtonColliderCount() + " colliders | "
                        + (engine.isSoundEnabled() ? "sound" : "muted")),
                width / 2, 40, 0xA0A0A0);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private static final class SettingsSlider extends SliderWidget {
        private final String label;
        private final float min;
        private final float max;
        private final java.util.function.Consumer<Float> change;

        private SettingsSlider(int x, int y, int width, String label, float min, float max, float current,
                               java.util.function.Consumer<Float> change) {
            super(x, y, width, 20, Text.literal(""), normalize(current, min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.change = change;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float actual = actualValue();
            setMessage(Text.literal(label + ": " + Math.round(actual * 100.0F) + "%"));
        }

        @Override
        protected void applyValue() {
            float actual = actualValue();
            change.accept(actual);
            updateMessage();
        }

        private float actualValue() {
            return min + (max - min) * (float) value;
        }

        private static double normalize(float current, float min, float max) {
            if (max <= min) return 0.0D;
            return Math.max(0.0D, Math.min(1.0D, (current - min) / (max - min)));
        }
    }
}
