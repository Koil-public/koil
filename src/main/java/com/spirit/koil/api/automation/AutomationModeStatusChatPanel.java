package com.spirit.koil.api.automation;

import com.spirit.koil.api.chat.ChatHudPanel;
import com.spirit.koil.api.chat.ChatHudPanelBounds;
import com.spirit.koil.api.chat.ChatHudPanelContext;
import com.spirit.koil.api.chat.ChatHudPanelPlacement;
import com.spirit.koil.api.chat.ChatHudPanelVisualStyle;
import com.spirit.koil.api.chat.SlidingStatusText;
import com.spirit.koil.api.automation.cli.AutomationPresenceState;
import com.spirit.koil.api.automation.cli.AutomationStateColors;
import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelRequestMetricsPresentation;
import com.spirit.koil.api.model.presence.CombinedModelExecutorStatus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AutomationModeStatusChatPanel implements ChatHudPanel {
    private static final Identifier AUTOMATION_LOGO = new Identifier("koil", "textures/gui/icons/automation.png");
    private static final int HEIGHT = 33;
    private static final int LOGO_SIZE = 24;
    private static final float TITLE_SCALE = 1.12F;

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
        if (snapshot.approvalPolicy() == AutomationModeController.ApprovalPolicy.UNRESTRICTED
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

        Text metrics = metricLine(availableWidth, client);
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
            renderExperimentalTooltip(drawContext, context, snapshot, textX, bounds.y() + 19, client);
        }
    }

    private static Text metricLine(int availableWidth, MinecraftClient client) {
        return ModelRequestMetricsPresentation.automationSessionLine(
                ModelGenerationHudState.visibleSnapshot(),
                modeIndicators(AutomationModeController.snapshot())
        );
    }

    public static Text modeIndicators(AutomationModeController.Snapshot mode) {
        MutableText modeIndicators = Text.empty();
        if (mode.experimentalFeaturesEnabled()) {
            int experimentalColor = experimentalIndicatorColor(
                    uiColorVal.uiColorAutomationModeExperimentalText
            );
            modeIndicators.append(Text.literal("TEST").styled(style -> style.withColor(experimentalColor)));
        }
        if (mode.deepThinkingEnabled()) {
            if (!modeIndicators.getString().isBlank()) {
                modeIndicators.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY));
            }
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
        return modeIndicators;
    }

    public static String modeIndicatorLabels(AutomationModeController.Snapshot mode) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        if (mode.experimentalFeaturesEnabled()) labels.add("TEST");
        if (mode.deepThinkingEnabled()) labels.add("D-T");
        if (mode.planningModeEnabled() || mode.planningActive()) labels.add("Plan");
        return String.join(" | ", labels);
    }

    private static void renderExperimentalTooltip(
            DrawContext drawContext,
            ChatHudPanelContext context,
            AutomationModeController.Snapshot snapshot,
            int textX,
            int textY,
            MinecraftClient client
    ) {
        if (!context.chatOpen() || !snapshot.experimentalFeaturesEnabled() || client.getWindow() == null) {
            return;
        }
        int mouseX = (int) Math.round(client.mouse.getX()
                * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth());
        int mouseY = (int) Math.round(client.mouse.getY()
                * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight());
        int testWidth = client.textRenderer.getWidth("TEST");
        if (mouseX < textX || mouseX > textX + testWidth
                || mouseY < textY - 1 || mouseY > textY + client.textRenderer.fontHeight + 1) {
            return;
        }
        List<Text> tooltip = new ArrayList<>();
        tooltip.add(Text.literal("Experimental features").formatted(Formatting.WHITE));
        for (String feature : snapshot.enabledExperimentalFeatures()) {
            tooltip.add(Text.literal("• " + feature).formatted(Formatting.GREEN));
        }
        drawContext.getMatrices().push();
        drawContext.getMatrices().translate(0.0F, 0.0F, 1_000.0F);
        drawContext.drawTooltip(client.textRenderer, tooltip, Optional.empty(), mouseX, mouseY);
        drawContext.getMatrices().pop();
    }

    public static int experimentalIndicatorColor(int configured) {
        int color = configured & 0x00FFFFFF;
        return color == 0 ? 0x55FF55 : color;
    }

    private static Text animatedStateLabel(StatusView status) {
        int color = AutomationStateColors.color(status.state()) & 0x00FFFFFF;
        return SlidingStatusText.styled(status.label(), AutomationStateColors.normalizeState(status.state()), color);
    }

    private static StatusView statusView(AutomationModeController.Snapshot snapshot) {
        String combinedState = CombinedModelExecutorStatus.snapshot().state();
        String state = combinedState == null || combinedState.isBlank() || "idle".equals(combinedState)
                ? fallbackState(snapshot)
                : combinedState;
        String label = titleCase(state);
        return new StatusView(state, label);
    }

    private static String fallbackState(AutomationModeController.Snapshot snapshot) {
        return switch (snapshot.state()) {
            case CONNECTING -> "starting";
            case EXECUTING -> "running";
            case PAUSED -> "idle";
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
