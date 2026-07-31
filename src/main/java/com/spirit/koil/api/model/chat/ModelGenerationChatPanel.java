package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.chat.ChatHudPanel;
import com.spirit.koil.api.chat.ChatHudPanelBounds;
import com.spirit.koil.api.chat.ChatHudPanelContext;
import com.spirit.koil.api.chat.ChatHudPanelPlacement;
import com.spirit.koil.api.chat.ChatHudPanelVisualStyle;
import com.spirit.koil.api.chat.RichChatBodyWrapFormatter;
import com.spirit.koil.api.chat.RichChatPreviewFormatter;
import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelGenerationChatPanel implements ChatHudPanel {
    private static final int MAXIMUM_VISIBLE_TEXT_LINES = 12;
    private static final Map<UUID, Integer> SCROLL_OFFSETS = new ConcurrentHashMap<>();
    private static final Set<UUID> MANUAL_SCROLL = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, CachedLines> FORMATTED_LINES = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "koil:local_model_generation";
    }

    @Override
    public ChatHudPanelPlacement placement() {
        return ChatHudPanelPlacement.BOTTOM;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public boolean visible(ChatHudPanelContext context) {
        MinecraftClient client = context.client();
        return client != null
                && client.player != null
                && (client.currentScreen == null || client.currentScreen instanceof ChatScreen)
                && ModelGenerationHudState.visibleSnapshot() != null;
    }

    @Override
    public int height(ChatHudPanelContext context) {
        Block block = block(context);
        return block == null ? 0 : block.height();
    }

    @Override
    public void render(DrawContext drawContext, ChatHudPanelContext context, ChatHudPanelBounds bounds) {
        Block block = block(context);
        MinecraftClient client = context.client();
        if (block == null || client == null) {
            return;
        }
        ChatHudPanelVisualStyle.drawSurface(
                drawContext,
                bounds,
                client,
                uiColorVal.uiColorLocalModelMessageBar
        );

        int x = bounds.x() + 6;
        int y = bounds.y() + 4;
        drawContext.drawTextWithShadow(client.textRenderer, title(block.snapshot()), x, y, uiColorVal.uiColorLocalModelPopupText);
        y += client.textRenderer.fontHeight + 2;
        if (block.metrics() != null) {
            drawContext.drawTextWithShadow(
                    client.textRenderer,
                    block.metrics(),
                    x,
                    y,
                    uiColorVal.uiColorLocalModelPopupText
            );
            y += client.textRenderer.fontHeight + 1;
        }
        for (PanelLine line : block.visibleLines()) {
            RichChatAttachmentRenderer.renderPreviewOrDrawText(
                    drawContext,
                    client.textRenderer,
                    line.text(),
                    x,
                    y,
                    uiColorVal.uiColorLocalModelPopupText
            );
            y += line.height();
        }
        for (Button button : buttons(block, bounds, client)) {
            drawContext.fill(
                    button.x(),
                    button.y(),
                    button.x() + button.width(),
                    button.y() + button.height(),
                    ChatHudPanelVisualStyle.buttonBackground(client)
            );
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal(button.label()), button.x() + 4, button.y() + 3, uiColorVal.uiColorLocalModelPopupText);
        }
    }

    @Override
    public boolean mouseClicked(
            ChatHudPanelContext context,
            ChatHudPanelBounds bounds,
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button != 0 || context.client() == null) {
            return false;
        }
        Block block = block(context);
        if (block == null) {
            return false;
        }
        for (Button action : buttons(block, bounds, context.client())) {
            if (!action.contains(mouseX, mouseY)) {
                continue;
            }
            switch (action.action()) {
                case "cancel" -> ModelGenerationHudState.cancelVisible();
                case "copy" -> context.client().keyboard.setClipboard(block.snapshot().text());
                case "dismiss" -> ModelGenerationHudState.dismiss(block.snapshot().requestId());
                case "approve" -> ModelGenerationHudState.resolveApproval(block.snapshot().requestId(), true);
                case "deny" -> ModelGenerationHudState.resolveApproval(block.snapshot().requestId(), false);
                default -> {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(
            ChatHudPanelContext context,
            ChatHudPanelBounds bounds,
            double mouseX,
            double mouseY,
            double amount
    ) {
        if (amount == 0.0D || !bounds.contains(mouseX, mouseY)) {
            return false;
        }
        Block block = block(context);
        if (block == null || block.maximumScroll() <= 0) {
            return false;
        }
        int direction = amount > 0.0D ? -1 : 1;
        scroll(block.snapshot().requestId(), direction, block.maximumScroll());
        return true;
    }

    private static Block block(ChatHudPanelContext context) {
        ModelGenerationHudState.Snapshot snapshot = ModelGenerationHudState.visibleSnapshot();
        MinecraftClient client = context.client();
        if (snapshot == null || client == null || client.textRenderer == null) {
            return null;
        }
        String visibleText = snapshot.approval() != null
                ? approvalVisibleText(snapshot)
                : ModelChatIdentity.decorate(combinedVisibleText(snapshot));
        visibleText = RichChatModelOutputSanitizer.normalizeStreamingPreview(visibleText);
        List<PanelLine> all = formattedLines(snapshot, visibleText, context, client);
        int lineHeight = client.textRenderer.fontHeight + 1;
        int maximumContentHeight = MAXIMUM_VISIBLE_TEXT_LINES * lineHeight;
        int maximumScroll = tailStart(all, maximumContentHeight);
        int requestedOffset = MANUAL_SCROLL.contains(snapshot.requestId())
                ? SCROLL_OFFSETS.getOrDefault(snapshot.requestId(), maximumScroll)
                : maximumScroll;
        int offset = Math.max(0, Math.min(maximumScroll, requestedOffset));
        SCROLL_OFFSETS.put(snapshot.requestId(), offset);
        List<PanelLine> visible = visibleLines(all, offset, maximumContentHeight);
        int visibleTextHeight = visible.stream().mapToInt(PanelLine::height).sum();
        int buttonHeight = client.textRenderer.fontHeight + 6;
        Text metricText = snapshot.automationRequest()
                ? null
                : ModelRequestMetricsPresentation.automationTopLine(
                        snapshot,
                        com.spirit.koil.api.model.LocalModelService.configuredModelId(),
                        com.spirit.koil.api.model.LocalModelService.queueDepth(),
                        com.spirit.koil.api.model.LocalModelService.configuredContextWindowTokens()
                );
        OrderedText metrics = metricText == null
                ? null
                : client.textRenderer.wrapLines(
                                metricText,
                                Math.max(1, context.panelWidth() - 12)
                        )
                        .stream()
                        .findFirst()
                        .orElse(Text.empty().asOrderedText());
        int metricsHeight = metrics == null ? 0 : lineHeight;
        int height = 4 + client.textRenderer.fontHeight + 2 + metricsHeight
                + visibleTextHeight
                + buttonHeight + 4;
        return new Block(snapshot, metrics, visible, Math.max(32, height), maximumScroll);
    }

    private static List<PanelLine> formattedLines(
            ModelGenerationHudState.Snapshot snapshot,
            String visibleText,
            ChatHudPanelContext context,
            MinecraftClient client
    ) {
        int width = Math.max(24, context.panelWidth() - 12);
        boolean approval = snapshot.approval() != null;
        CachedLines cached = FORMATTED_LINES.get(snapshot.requestId());
        if (cached != null
                && cached.width() == width
                && cached.approval() == approval
                && cached.source().equals(visibleText)) {
            return cached.lines();
        }
        List<PanelLine> lines = new ArrayList<>();
        if (!visibleText.isBlank()) {
            Text wrappingText = RichChatPreviewFormatter.format(Text.literal(visibleText));
            int nativeWidth = width + RichChatBodyWrapFormatter.nativeWrapWidthAdjustment(
                    client.textRenderer,
                    wrappingText.getString()
            );
            for (OrderedText line : client.textRenderer.wrapLines(wrappingText, nativeWidth)) {
                lines.add(new PanelLine(
                        line,
                        Math.max(
                                client.textRenderer.fontHeight + 1,
                                RichChatAttachmentRenderer.liveFormattedLineHeight(client.textRenderer, line)
                        )
                ));
            }
        }
        List<PanelLine> immutable = List.copyOf(lines);
        if (FORMATTED_LINES.size() >= 16 && !FORMATTED_LINES.containsKey(snapshot.requestId())) {
            UUID first = FORMATTED_LINES.keySet().stream().findFirst().orElse(null);
            if (first != null) {
                FORMATTED_LINES.remove(first);
            }
        }
        FORMATTED_LINES.put(snapshot.requestId(), new CachedLines(visibleText, width, approval, immutable));
        return immutable;
    }

    private static int tailStart(List<PanelLine> lines, int maximumHeight) {
        int used = 0;
        int start = lines == null ? 0 : lines.size();
        while (start > 0) {
            int next = lines.get(start - 1).height();
            if (used > 0 && used + next > maximumHeight) {
                break;
            }
            used += next;
            start--;
        }
        return Math.max(0, start);
    }

    private static List<PanelLine> visibleLines(List<PanelLine> lines, int offset, int maximumHeight) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<PanelLine> visible = new ArrayList<>();
        int used = 0;
        for (int index = Math.max(0, offset); index < lines.size(); index++) {
            PanelLine line = lines.get(index);
            if (!visible.isEmpty() && used + line.height() > maximumHeight) {
                break;
            }
            visible.add(line);
            used += line.height();
        }
        return List.copyOf(visible);
    }

    private static List<Button> buttons(Block block, ChatHudPanelBounds bounds, MinecraftClient client) {
        List<Button> buttons = new ArrayList<>();
        int y = bounds.y() + bounds.height() - client.textRenderer.fontHeight - 10;
        int x = bounds.x() + 6;
        int height = client.textRenderer.fontHeight + 6;
        if (block.snapshot().approval() != null) {
            ModelGenerationHudState.Approval approval = block.snapshot().approval();
            int approveWidth = client.textRenderer.getWidth(approval.approveLabel()) + 8;
            buttons.add(new Button("approve", approval.approveLabel(), x, y, approveWidth, height));
            x += approveWidth + 4;
            int denyWidth = client.textRenderer.getWidth(approval.denyLabel()) + 8;
            buttons.add(new Button("deny", approval.denyLabel(), x, y, denyWidth, height));
            return buttons;
        }
        if (block.snapshot().state().terminal()) {
            if (!block.snapshot().text().isBlank()) {
                int width = client.textRenderer.getWidth("Copy") + 8;
                buttons.add(new Button("copy", "Copy", x, y, width, height));
                x += width + 4;
            }
            int width = client.textRenderer.getWidth("Dismiss") + 8;
            buttons.add(new Button("dismiss", "Dismiss", x, y, width, height));
        } else {
            int width = client.textRenderer.getWidth("Cancel") + 8;
            buttons.add(new Button("cancel", "Cancel", x, y, width, height));
        }
        return buttons;
    }

    private static void scroll(UUID requestId, int direction, int maximumScroll) {
        int current = SCROLL_OFFSETS.getOrDefault(requestId, maximumScroll);
        int next = Math.max(0, Math.min(maximumScroll, current + direction));
        SCROLL_OFFSETS.put(requestId, next);
        if (next >= maximumScroll) {
            MANUAL_SCROLL.remove(requestId);
        } else {
            MANUAL_SCROLL.add(requestId);
        }
    }

    private static Text title(ModelGenerationHudState.Snapshot snapshot) {
        return Text.literal("Local model").formatted(Formatting.DARK_GRAY);
    }

    private static String activityText(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot.currentToolStep() > 0 && snapshot.totalToolSteps() > 0
                && (snapshot.state() == com.spirit.koil.api.model.ModelRequestState.EXECUTING_TOOL
                || snapshot.state() == com.spirit.koil.api.model.ModelRequestState.WAITING_FOR_TOOL_RESULT)) {
            String tool = snapshot.activeToolId()
                    .replace('.', ' ')
                    .replace('_', ' ')
                    .strip();
            if (!tool.isBlank()) {
                return "Step " + snapshot.currentToolStep() + "/" + snapshot.totalToolSteps() + ": " + tool;
            }
            return "Step " + snapshot.currentToolStep() + "/" + snapshot.totalToolSteps();
        }
        return ModelRequestStatusPresentation.forActivity(
                snapshot.state(),
                snapshot.detail(),
                snapshot.activeToolId()
        ).label() + "…";
    }

    private static String combinedVisibleText(ModelGenerationHudState.Snapshot snapshot) {
        String current = snapshot.text().isBlank() ? activityText(snapshot) : snapshot.text();
        if (snapshot.activity() == null || snapshot.activity().isBlank()) {
            return current;
        }
        return snapshot.activity().strip() + "\n\n" + current;
    }

    private static String approvalVisibleText(ModelGenerationHudState.Snapshot snapshot) {
        String approval = snapshot.approval() == null ? "" : snapshot.approval().message();
        if (snapshot.activity() == null || snapshot.activity().isBlank()) {
            return approval;
        }
        return snapshot.activity().strip() + "\n\n" + approval;
    }

    private record Block(
            ModelGenerationHudState.Snapshot snapshot,
            OrderedText metrics,
            List<PanelLine> visibleLines,
            int height,
            int maximumScroll
    ) {
    }

    private record CachedLines(String source, int width, boolean approval, List<PanelLine> lines) {
    }

    private record PanelLine(OrderedText text, int height) {
    }

    private record Button(String action, String label, int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width
                    && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }
}
