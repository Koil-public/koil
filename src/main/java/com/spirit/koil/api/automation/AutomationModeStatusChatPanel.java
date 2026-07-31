package com.spirit.koil.api.automation;

import com.spirit.koil.api.chat.ChatHudPanel;
import com.spirit.koil.api.chat.ChatHudPanelBounds;
import com.spirit.koil.api.chat.ChatHudPanelContext;
import com.spirit.koil.api.chat.ChatHudPanelPlacement;
import com.spirit.koil.api.chat.ChatHudPanelVisualStyle;
import com.spirit.koil.api.automation.cli.AutomationPresenceState;
import com.spirit.koil.api.automation.cli.AutomationStateColors;
import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelRequestMetricsPresentation;
import com.spirit.koil.api.model.chat.ModelRequestStatusPresentation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public final class AutomationModeStatusChatPanel implements ChatHudPanel {
    private static final Identifier AUTOMATION_LOGO = new Identifier("koil", "textures/gui/icons/automation.png");
    private static final int HEIGHT = 33;
    private static final int LOGO_SIZE = 24;
    private static final float TITLE_SCALE = 1.12F;
    private static final long STATUS_ANIMATION_STEP_MILLIS = 110L;
    private static final int STATUS_ANIMATION_RESTART_PAUSE_STEPS = 4;

    @Override
    public String id() {
        return "koil:automation_mode_status";
    }

    @Override
    public ChatHudPanelPlacement placement() {
        return ChatHudPanelPlacement.TOP;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean visible(ChatHudPanelContext context) {
        MinecraftClient client = context.client();
        return AutomationModeController.isAutomationMode()
                && client != null
                && client.player != null
                && (client.currentScreen == null || client.currentScreen instanceof ChatScreen);
    }

    @Override
    public int height(ChatHudPanelContext context) {
        return visible(context) ? HEIGHT : 0;
    }

    @Override
    public void render(DrawContext drawContext, ChatHudPanelContext context, ChatHudPanelBounds bounds) {
        MinecraftClient client = context.client();
        if (client == null) {
            return;
        }
        AutomationModeController.Snapshot snapshot = AutomationModeController.snapshot();
        StatusView status = statusView(snapshot);
        ChatHudPanelVisualStyle.drawSurface(
                drawContext,
                bounds,
                client,
                AutomationStateColors.color(status.state())
        );
        int logoX = bounds.x() + 5;
        // The identity block is exactly the title and metrics rows.
        int logoY = bounds.y() + 5;
        drawContext.drawTexture(AUTOMATION_LOGO, logoX, logoY, 0, 0, LOGO_SIZE, LOGO_SIZE, LOGO_SIZE, LOGO_SIZE);
        int textX = logoX + LOGO_SIZE + 5;
        int availableWidth = Math.max(0, bounds.x() + bounds.width() - textX - 5);
        Text title = Text.literal("Automation").formatted(Formatting.WHITE, Formatting.BOLD);
        Text titleSeparator = Text.literal(" | ").formatted(Formatting.GRAY);
        Text stateLabel = animatedStateLabel(status);
        Text unrestrictedSeparator = Text.literal(" | ").formatted(Formatting.GRAY);
        Text unrestricted = Text.literal("Unrestricted").styled(style ->
                style.withColor(uiColorVal.uiColorAutomationModeUnrestrictedText & 0x00FFFFFF));
        int logicalWidth = Math.max(1, (int) (availableWidth / TITLE_SCALE));
        drawContext.getMatrices().push();
        drawContext.getMatrices().translate(textX, bounds.y() + 5, 0.0F);
        drawContext.getMatrices().scale(TITLE_SCALE, TITLE_SCALE, 1.0F);
        drawContext.drawTextWithShadow(
                client.textRenderer,
                title,
                0,
                0,
                uiColorVal.uiColorAutomationModePopupText
        );
        int separatorX = client.textRenderer.getWidth(title);
        drawContext.drawTextWithShadow(
                client.textRenderer,
                titleSeparator,
                separatorX,
                0,
                uiColorVal.uiColorAutomationModePopupText
        );
        int stateX = separatorX + client.textRenderer.getWidth(titleSeparator);
        drawContext.drawTextWithShadow(
                client.textRenderer,
                stateLabel,
                stateX,
                0,
                uiColorVal.uiColorAutomationModePopupText
        );
        int unrestrictedSeparatorX = stateX + client.textRenderer.getWidth(stateLabel);
        int unrestrictedX = unrestrictedSeparatorX + client.textRenderer.getWidth(unrestrictedSeparator);
        if (snapshot.approvalPolicy() == AutomationModeController.ApprovalPolicy.YOLO
                && unrestrictedX + client.textRenderer.getWidth(unrestricted) <= logicalWidth) {
            drawContext.drawTextWithShadow(
                    client.textRenderer,
                    unrestrictedSeparator,
                    unrestrictedSeparatorX,
                    0,
                    uiColorVal.uiColorAutomationModePopupText
            );
            drawContext.drawTextWithShadow(
                    client.textRenderer,
                    unrestricted,
                    unrestrictedX,
                    0,
                    uiColorVal.uiColorAutomationModePopupText
            );
        }
        drawContext.getMatrices().pop();

        Text metrics = metricLine();
        if (!metrics.getString().isBlank()) {
            net.minecraft.text.OrderedText renderedMetrics = client.textRenderer
                    .wrapLines(metrics, Math.max(1, availableWidth))
                    .stream()
                    .findFirst()
                    .orElse(Text.empty().asOrderedText());
            drawContext.drawTextWithShadow(
                    client.textRenderer,
                    renderedMetrics,
                    textX,
                    bounds.y() + 19,
                    uiColorVal.uiColorAutomationModePopupText
            );
        }
    }

    private static Text metricLine() {
        ModelGenerationHudState.Snapshot generation = ModelGenerationHudState.visibleSnapshot();
        AutomationModeController.Snapshot mode = AutomationModeController.snapshot();
        MutableText modeIndicators = Text.empty();
        if (mode.deepThinkingEnabled()) {
            modeIndicators.append(Text.literal("D-T").styled(style -> style.withColor(
                    uiColorVal.uiColorAutomationModeDeepThinkingText & 0x00FFFFFF
            )));
        }
        if (mode.planningModeEnabled() || mode.planningActive()) {
            if (!modeIndicators.getString().isBlank()) {
                modeIndicators.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY));
            }
            int configured = uiColorVal.uiColorAutomationModePlanningText & 0x00FFFFFF;
            int planColor = configured == 0 ? 0xB067FF : configured;
            modeIndicators.append(Text.literal("Plan").styled(style -> style.withColor(planColor)));
        }
        return ModelRequestMetricsPresentation.automationTopLine(
                generation,
                LocalModelService.configuredModelId(),
                LocalModelService.queueDepth(),
                LocalModelService.configuredContextWindowTokens(),
                modeIndicators
        );
    }

    private static Text animatedStateLabel(StatusView status) {
        int color = AutomationStateColors.color(status.state()) & 0x00FFFFFF;
        if (!isWorkingState(status.state())) {
            return Text.literal(status.label()).styled(style -> style.withColor(color));
        }
        String word = status.label() == null ? "" : status.label();
        String animated = word + "...";
        int wordLength = Math.max(1, word.length());
        int bandWidth = Math.max(2, Math.min(6, (wordLength + 2) / 3));
        int movementSteps = animated.length() + bandWidth;
        int cycleSteps = movementSteps + STATUS_ANIMATION_RESTART_PAUSE_STEPS;
        int phase = (int) ((System.currentTimeMillis() / STATUS_ANIMATION_STEP_MILLIS)
                % Math.max(1, cycleSteps));
        int bandStart = phase < movementSteps
                ? phase - bandWidth + 1
                : animated.length() + 1;
        int dimColor = blend(color, 0x4A4F57, 0.68F);
        int edgeColor = blend(color, dimColor, 0.38F);
        MutableText label = Text.empty();
        for (int index = 0; index < animated.length(); index++) {
            boolean highlighted = index >= bandStart && index < bandStart + bandWidth;
            boolean edge = index == bandStart - 1 || index == bandStart + bandWidth;
            int characterColor = highlighted ? color : (edge ? edgeColor : dimColor);
            label.append(Text.literal(String.valueOf(animated.charAt(index)))
                    .styled(style -> style.withColor(characterColor)));
        }
        return label;
    }

    private static int blend(int source, int target, float targetWeight) {
        float weight = Math.max(0.0F, Math.min(1.0F, targetWeight));
        int red = Math.round(((source >> 16) & 0xFF) * (1.0F - weight) + ((target >> 16) & 0xFF) * weight);
        int green = Math.round(((source >> 8) & 0xFF) * (1.0F - weight) + ((target >> 8) & 0xFF) * weight);
        int blue = Math.round((source & 0xFF) * (1.0F - weight) + (target & 0xFF) * weight);
        return (red << 16) | (green << 8) | blue;
    }

    private static boolean isWorkingState(String state) {
        String normalized = AutomationStateColors.normalizeState(state);
        return "thinking".equals(normalized)
                || "waiting".equals(normalized)
                || "running".equals(normalized)
                || "using".equals(normalized)
                || "moving".equals(normalized);
    }

    private static StatusView statusView(AutomationModeController.Snapshot snapshot) {
        ModelGenerationHudState.Snapshot generation = ModelGenerationHudState.visibleSnapshot();
        if (generation != null) {
            ModelRequestStatusPresentation.View modelStatus =
                    ModelRequestStatusPresentation.forActivity(
                            generation.state(),
                            generation.detail(),
                            generation.activeToolId()
                    );
            return new StatusView(modelStatus.semanticState(), modelStatus.label());
        }
        String presenceState = AutomationPresenceState.localState();
        String state = presenceState == null || presenceState.isBlank()
                ? fallbackState(snapshot)
                : presenceState;
        String label = titleCase(state);
        return new StatusView(state, label);
    }

    private static String fallbackState(AutomationModeController.Snapshot snapshot) {
        return switch (snapshot.state()) {
            case CONNECTING -> "waiting";
            case EXECUTING -> "running";
            case PAUSED -> "waiting";
            case UNAVAILABLE -> "failed";
            default -> "idle";
        };
    }

    private static String titleCase(String state) {
        String normalized = state == null || state.isBlank() ? "idle" : state.trim().replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private record StatusView(String state, String label) {
    }
}
