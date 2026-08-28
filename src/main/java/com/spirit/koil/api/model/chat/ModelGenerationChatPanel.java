package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.chat.ChatHudPanel;
import com.spirit.koil.api.chat.ChatHudPanelBounds;
import com.spirit.koil.api.chat.ChatHudPanelContext;
import com.spirit.koil.api.chat.ChatHudPanelPlacement;
import com.spirit.koil.api.chat.ChatHudPanelVisualStyle;
import com.spirit.koil.api.chat.RichChatBodyWrapFormatter;
import com.spirit.koil.api.chat.RichChatPreviewFormatter;
import com.spirit.koil.api.chat.RichChatStructuralStyleRegistry;
import com.spirit.koil.api.chat.SlidingStatusText;
import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.ModelSemanticPalette;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.MutableText;
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
    private static final float ELAPSED_TIME_SCALE = 0.82F;
    private static final Map<UUID, Integer> SCROLL_OFFSETS = new ConcurrentHashMap<>();
    private static final Set<UUID> MANUAL_SCROLL = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> EXPANDED_DETAILS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, CachedLines> FORMATTED_LINES = new ConcurrentHashMap<>();
    private static UUID scrollbarDragRequest;
    private static int scrollbarDragOffset;

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
        drawContext.drawTextWithShadow(client.textRenderer, block.header(), x, y, uiColorVal.uiColorLocalModelPopupText);
        y += client.textRenderer.fontHeight + 2;
        int contentTop = y;
        if (block.maximumScroll() > 0) {
            drawContext.enableScissor(bounds.x(), contentTop, bounds.x() + bounds.width(), contentTop + block.viewportHeight());
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
            if (line.statusAnimation() != null) {
                line.statusAnimation().render(drawContext, client, x, y);
            }
            y += line.height();
        }
        if (block.maximumScroll() > 0) {
            drawContext.disableScissor();
            ModelPopupScrollbar.render(drawContext, scrollbarMetrics(bounds, block, client));
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
        renderElapsedTime(drawContext, bounds, client, block.snapshot());
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
        ModelPopupScrollbar.Metrics scrollbar = scrollbarMetrics(bounds, block, context.client());
        if (scrollbar != null && scrollbar.contains(mouseX, mouseY)) {
            scrollbarDragRequest = block.snapshot().requestId();
            if (scrollbar.thumbContains(mouseX, mouseY)) {
                scrollbarDragOffset = (int) mouseY - scrollbar.thumbY();
            } else {
                scrollbarDragOffset = scrollbar.thumbHeight() / 2;
                setScrollFromThumb(block.snapshot().requestId(), (int) mouseY - scrollbarDragOffset, scrollbar);
            }
            return true;
        }
        for (Button action : buttons(block, bounds, context.client())) {
            if (!action.contains(mouseX, mouseY)) {
                continue;
            }
            switch (action.action()) {
                case "cancel" -> ModelGenerationHudState.cancelVisible();
                case "answer_now" -> ModelGenerationHudState.answerNow(block.snapshot().requestId());
                case "pause" -> ModelGenerationHudState.pauseDeepThought(block.snapshot().requestId());
                case "resume" -> ModelGenerationHudState.resumeDeepThought(block.snapshot().requestId());
                case "details" -> {
                    if (!EXPANDED_DETAILS.add(block.snapshot().requestId())) {
                        EXPANDED_DETAILS.remove(block.snapshot().requestId());
                    }
                    FORMATTED_LINES.remove(block.snapshot().requestId());
                }
                case "edit_queue" -> context.client().setScreen(new ChatScreen(
                        "/model queue edit " + block.snapshot().requestId() + " "
                                + com.spirit.koil.api.model.LocalModelService.queuedPrompts().stream()
                                .filter(value -> value.requestId().equals(block.snapshot().requestId()))
                                .map(com.spirit.koil.api.model.LocalModelService.QueuedPrompt::revision)
                                .findFirst().orElse(1L) + " " + block.snapshot().prompt()
                ));
                case "queue_next" -> ModelGenerationHudState.selectNextVisible();
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

    @Override
    public boolean mouseDragged(ChatHudPanelContext context, ChatHudPanelBounds bounds, double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button != 0 || scrollbarDragRequest == null) return false;
        Block block = block(context);
        if (block == null || !scrollbarDragRequest.equals(block.snapshot().requestId())) return false;
        ModelPopupScrollbar.Metrics metrics = scrollbarMetrics(bounds, block, context.client());
        if (metrics == null) return false;
        setScrollFromThumb(block.snapshot().requestId(), (int) mouseY - scrollbarDragOffset, metrics);
        return true;
    }

    @Override
    public boolean mouseReleased(ChatHudPanelContext context, ChatHudPanelBounds bounds, double mouseX, double mouseY, int button) {
        if (button != 0 || scrollbarDragRequest == null) return false;
        scrollbarDragRequest = null;
        scrollbarDragOffset = 0;
        return true;
    }

    private static Block block(ChatHudPanelContext context) {
        ModelGenerationHudState.Snapshot snapshot = ModelGenerationHudState.visibleSnapshot();
        MinecraftClient client = context.client();
        if (snapshot == null || client == null || client.textRenderer == null) {
            return null;
        }
        boolean liveStatus = snapshot.approval() == null && snapshot.text().isBlank();
        String visibleText;
        if (snapshot.approval() != null) {
            visibleText = approvalVisibleText(snapshot);
        } else if (liveStatus) {
            String activity = snapshot.activity() == null ? "" : snapshot.activity().strip();
            visibleText = activity.isBlank() ? "" : ModelChatIdentity.decorate(activity);
        } else {
            visibleText = ModelChatIdentity.decorate(combinedVisibleText(snapshot));
        }
        visibleText = RichChatModelOutputSanitizer.normalizeStreamingPreview(visibleText);
        List<PanelLine> formatted = formattedLines(snapshot, visibleText, context, client);
        List<PanelLine> all;
        if (liveStatus) {
            all = new ArrayList<>(formatted.size() + 1);
            all.addAll(formatted);
            all.add(liveStatusLine(snapshot, client, formatted.isEmpty()));
            all = List.copyOf(all);
        } else {
            all = formatted;
        }
        int lineHeight = client.textRenderer.fontHeight + 1;
        int maximumContentHeight = (EXPANDED_DETAILS.contains(snapshot.requestId())
                ? MAXIMUM_VISIBLE_TEXT_LINES * 2
                : MAXIMUM_VISIBLE_TEXT_LINES) * lineHeight;
        int maximumScroll = tailStart(all, maximumContentHeight);
        int requestedOffset = MANUAL_SCROLL.contains(snapshot.requestId())
                ? SCROLL_OFFSETS.getOrDefault(snapshot.requestId(), maximumScroll)
                : maximumScroll;
        int offset = Math.max(0, Math.min(maximumScroll, requestedOffset));
        SCROLL_OFFSETS.put(snapshot.requestId(), offset);
        List<PanelLine> visible = visibleLines(all, offset, maximumContentHeight);
        int visibleTextHeight = visible.stream().mapToInt(PanelLine::height).sum();
        int totalTextHeight = all.stream().mapToInt(PanelLine::height).sum();
        int buttonHeight = client.textRenderer.fontHeight + 6;
        int headerWidth = Math.max(1, context.panelWidth() - 12);
        Text headerText = ModelRequestMetricsPresentation.bottomHeaderFitted(
                snapshot,
                com.spirit.koil.api.model.LocalModelService.configuredModelId(),
                com.spirit.koil.api.model.LocalModelService.queueDepth(),
                com.spirit.koil.api.model.LocalModelService.configuredContextWindowTokens(),
                headerWidth,
                client.textRenderer::getWidth
        );
        OrderedText header = client.textRenderer.wrapLines(
                        headerText,
                        headerWidth
                )
                .stream()
                .findFirst()
                .orElse(Text.empty().asOrderedText());
        int height = 4 + client.textRenderer.fontHeight + 2
                + visibleTextHeight
                + buttonHeight + 4;
        return new Block(snapshot, header, visible, Math.max(32, height), maximumScroll, offset,
                Math.max(1, visibleTextHeight), Math.max(1, totalTextHeight));
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
            Text sourceText = Text.literal(visibleText);
            if (approval) {
                sourceText = RichChatBodyWrapFormatter.formatConfirmationDetails(sourceText, width);
            }
            Text wrappingText = RichChatPreviewFormatter.format(sourceText);
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
        if (ModelGenerationHudState.queuedCount() > 1) {
            int queueWidth = client.textRenderer.getWidth("Queue") + 8;
            buttons.add(new Button("queue_next", "Queue", x, y, queueWidth, height));
            x += queueWidth + 4;
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
            if (block.snapshot().state() == com.spirit.koil.api.model.ModelRequestState.QUEUED) {
                int editWidth = client.textRenderer.getWidth("Edit Queue") + 8;
                buttons.add(new Button("edit_queue", "Edit Queue", x, y, editWidth, height));
                x += editWidth + 4;
            }
            if (block.snapshot().deepThoughtStatus() != null) {
                var status = block.snapshot().deepThoughtStatus();
                String action = status.paused() ? "Resume" : "Pause";
                int controlWidth = client.textRenderer.getWidth(action) + 8;
                buttons.add(new Button(status.paused() ? "resume" : "pause", action, x, y, controlWidth, height));
                x += controlWidth + 4;
                int detailWidth = client.textRenderer.getWidth("Details") + 8;
                buttons.add(new Button("details", "Details", x, y, detailWidth, height));
                x += detailWidth + 4;
            }
            if (block.snapshot().answerNowVisible() && !block.snapshot().answerNowRequested()) {
                int answerWidth = client.textRenderer.getWidth("Answer Now") + 8;
                buttons.add(new Button("answer_now", "Answer Now", x, y, answerWidth, height));
                x += answerWidth + 4;
            }
            int width = client.textRenderer.getWidth("Cancel") + 8;
            buttons.add(new Button("cancel", "Cancel", x, y, width, height));
        }
        return buttons;
    }

    private static void renderElapsedTime(
            DrawContext drawContext,
            ChatHudPanelBounds bounds,
            MinecraftClient client,
            ModelGenerationHudState.Snapshot snapshot
    ) {
        String elapsed = ModelRequestMetricsPresentation.compactRateAndElapsed(snapshot, System.currentTimeMillis());
        int color = RichChatStructuralStyleRegistry.color(
                RichChatStructuralStyleRegistry.Role.SUBTEXT,
                0xFF555555
        );
        float right = bounds.x() + bounds.width() - 6.0F;
        float bottom = bounds.y() + bounds.height() - 4.0F;
        float width = client.textRenderer.getWidth(elapsed) * ELAPSED_TIME_SCALE;
        int drawX = Math.round((right - width) / ELAPSED_TIME_SCALE);
        int drawY = Math.round((bottom - client.textRenderer.fontHeight * ELAPSED_TIME_SCALE) / ELAPSED_TIME_SCALE);
        drawContext.getMatrices().push();
        drawContext.getMatrices().scale(ELAPSED_TIME_SCALE, ELAPSED_TIME_SCALE, 1.0F);
        drawContext.drawText(
                client.textRenderer,
                Text.literal(elapsed),
                drawX,
                drawY,
                color,
                false
        );
        drawContext.getMatrices().pop();
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

    private static String activityText(ModelGenerationHudState.Snapshot snapshot) {
        if (!snapshot.automationRequest()
                && snapshot.currentToolStep() > 0 && snapshot.totalToolSteps() > 1
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
        ).label() + "...";
    }

    private static PanelLine liveStatusLine(
            ModelGenerationHudState.Snapshot snapshot,
            MinecraftClient client,
            boolean includeIdentity
    ) {
        MutableText line = Text.empty();
        if (includeIdentity) {
            line.append(Text.literal(ModelChatIdentity.PREFIX).formatted(Formatting.DARK_GRAY));
        } else {
            int prefixWidth = client.textRenderer.getWidth(ModelChatIdentity.PREFIX);
            int spaceWidth = Math.max(1, client.textRenderer.getWidth(" "));
            int spaces = ModelChatIdentity.alignedPrefixAdvance(prefixWidth, spaceWidth) / spaceWidth;
            line.append(Text.literal(" ".repeat(Math.max(0, spaces))));
        }
        if (!snapshot.automationRequest()
                && snapshot.currentToolStep() > 0 && snapshot.totalToolSteps() > 1
                && (snapshot.state() == com.spirit.koil.api.model.ModelRequestState.EXECUTING_TOOL
                || snapshot.state() == com.spirit.koil.api.model.ModelRequestState.WAITING_FOR_TOOL_RESULT)) {
            line.append(Text.literal(activityText(snapshot)).styled(style -> style.withColor(
                    ModelSemanticPalette.color(snapshot.activityState()) & 0x00FFFFFF
            )));
            return new PanelLine(line.asOrderedText(), client.textRenderer.fontHeight + 1);
        } else {
            ModelRequestStatusPresentation.View status = ModelRequestStatusPresentation.forActivity(
                    snapshot.state(),
                    snapshot.detail(),
                    snapshot.activeToolId()
            );
            int statusOffset = client.textRenderer.getWidth(line);
            return new PanelLine(
                    line.asOrderedText(),
                    client.textRenderer.fontHeight + 1,
                    new StatusAnimation(
                            statusOffset,
                            status.label(),
                            status.semanticState(),
                            ModelSemanticPalette.color(status.activityState()),
                            statusHighlightPixelOffset(status.label(), includeIdentity)
                    )
            );
        }
    }

    public static int statusHighlightPixelOffset(String label, boolean includeIdentity) {
        return includeIdentity && ("Starting".equals(label) || "Thinking".equals(label)) ? -1 : 0;
    }

    private static String combinedVisibleText(ModelGenerationHudState.Snapshot snapshot) {
        String current = snapshot.text();
        String deep = deepThoughtSummary(snapshot);
        if (snapshot.activity() == null || snapshot.activity().isBlank()) {
            return deep.isBlank() ? current : deep + (current.isBlank() ? "" : "\n" + current);
        }
        return deep + (deep.isBlank() ? "" : "\n") + snapshot.activity().strip() + "\n" + current;
    }

    private static String deepThoughtSummary(ModelGenerationHudState.Snapshot snapshot) {
        return ModelActivityPresentation.deepThought(
                snapshot.deepThoughtStatus(),
                EXPANDED_DETAILS.contains(snapshot.requestId())
        );
    }

    private static String approvalVisibleText(ModelGenerationHudState.Snapshot snapshot) {
        String approval = snapshot.approval() == null ? "" : snapshot.approval().message();
        if (snapshot.activity() == null || snapshot.activity().isBlank()) {
            return approval;
        }
        return snapshot.activity().strip() + "\n" + approval;
    }

    private record Block(
            ModelGenerationHudState.Snapshot snapshot,
            OrderedText header,
            List<PanelLine> visibleLines,
            int height,
            int maximumScroll,
            int scrollOffset,
            int viewportHeight,
            int totalContentHeight
    ) {
    }

    private record CachedLines(String source, int width, boolean approval, List<PanelLine> lines) {
    }

    private record PanelLine(OrderedText text, int height, StatusAnimation statusAnimation) {
        private PanelLine(OrderedText text, int height) {
            this(text, height, null);
        }
    }

    private record StatusAnimation(
            int xOffset,
            String label,
            String semanticState,
            int color,
            int highlightPixelOffset
    ) {
        private void render(DrawContext drawContext, MinecraftClient client, int x, int y) {
            SlidingStatusText.HighlightWindow window = SlidingStatusText.highlightWindow(
                    this.label,
                    this.semanticState,
                    System.currentTimeMillis()
            );
            String visible = window.visibleText();
            int textX = x + this.xOffset + this.highlightPixelOffset;
            drawContext.drawTextWithShadow(
                    client.textRenderer,
                    SlidingStatusText.baseStyled(this.label, this.semanticState, this.color),
                    textX,
                    y,
                    this.color & 0x00FFFFFF
            );
            if (!window.active() || window.endCharacter() <= window.startCharacter()) return;
            int visibleBandWidth = window.endCharacter() - window.startCharacter();
            if (visibleBandWidth > 1 && window.startCharacter() > 0) {
                renderRange(drawContext, client, visible, textX, y,
                        window.startCharacter() - 1, window.startCharacter(),
                        SlidingStatusText.transitionColor(this.color));
            }
            if (visibleBandWidth > 1 && window.endCharacter() < visible.length()) {
                renderRange(drawContext, client, visible, textX, y,
                        window.endCharacter(), window.endCharacter() + 1,
                        SlidingStatusText.transitionColor(this.color));
            }
            renderRange(drawContext, client, visible, textX, y,
                    window.startCharacter(), window.endCharacter(), this.color & 0x00FFFFFF);
        }

        private static void renderRange(
                DrawContext drawContext,
                MinecraftClient client,
                String visible,
                int textX,
                int y,
                int startCharacter,
                int endCharacter,
                int color
        ) {
            int start = client.textRenderer.getWidth(visible.substring(0, startCharacter));
            int end = client.textRenderer.getWidth(visible.substring(0, endCharacter));
            drawContext.enableScissor(textX + start, y - 1, textX + Math.max(start + 1, end), y + client.textRenderer.fontHeight + 2);
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal(visible), textX, y, color);
            drawContext.disableScissor();
        }
    }

    private static ModelPopupScrollbar.Metrics scrollbarMetrics(ChatHudPanelBounds bounds, Block block, MinecraftClient client) {
        if (bounds == null || block == null || client == null || block.maximumScroll() <= 0) return null;
        int contentTop = bounds.y() + 4 + client.textRenderer.fontHeight + 2;
        double fraction = block.viewportHeight() / (double) Math.max(block.viewportHeight(), block.totalContentHeight());
        return ModelPopupScrollbar.topDownRange(
                bounds.x() + bounds.width() - 5,
                contentTop,
                block.viewportHeight(),
                block.maximumScroll(),
                block.scrollOffset(),
                fraction
        );
    }

    private static void setScrollFromThumb(UUID requestId, int thumbTop, ModelPopupScrollbar.Metrics metrics) {
        int next = ModelPopupScrollbar.offsetFromThumbTop(thumbTop, metrics);
        SCROLL_OFFSETS.put(requestId, next);
        if (next >= metrics.maxScroll()) MANUAL_SCROLL.remove(requestId);
        else MANUAL_SCROLL.add(requestId);
    }

    private record Button(String action, String label, int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width
                    && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }
}
