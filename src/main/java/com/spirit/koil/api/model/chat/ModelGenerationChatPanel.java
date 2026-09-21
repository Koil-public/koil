package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.chat.ChatHudPanel;
import com.spirit.koil.api.chat.ChatHudPanelBounds;
import com.spirit.koil.api.chat.ChatHudPanelContext;
import com.spirit.koil.api.chat.ChatHudPanelPlacement;
import com.spirit.koil.api.chat.ChatHudPanelVisualStyle;
import com.spirit.koil.api.chat.RichChatBodyWrapFormatter;
import com.spirit.koil.api.chat.RichChatRowType;
import com.spirit.koil.api.chat.LocalMultilineChatBridge;
import com.spirit.koil.api.chat.RichChatPreviewFormatter;
import com.spirit.koil.api.chat.RichChatSurfaceRenderer;
import com.spirit.koil.api.chat.RichChatStructuralStyleRegistry;
import com.spirit.koil.api.chat.RichChatStructuralContinuation;
import com.spirit.koil.api.chat.SlidingStatusText;
import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.ModelSemanticPalette;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelGenerationChatPanel implements ChatHudPanel {
    private static final int MAXIMUM_VISIBLE_TEXT_LINES = 12;
    private static final float ELAPSED_TIME_SCALE = 0.82F;
    private static final float SUBTEXT_SCALE = 0.82F;
    private static final int NATIVE_CHAT_TEXT_COLOR = 0xFFFFFFFF;
    private static final long STREAM_LAYOUT_MIN_NANOS = 72_000_000L;
    private static final int LIVE_TREE_LAYOUT_CHARS = 6_144;
    private static final int LIVE_RESPONSE_LAYOUT_CHARS = 16_384;
    private static final int LIVE_RESPONSE_CODE_BLOCK_MAX_CHARS = 49_152;
    private static final long HEADER_REFRESH_NANOS = 100_000_000L;
    private static final Map<UUID, Integer> SCROLL_OFFSETS = new ConcurrentHashMap<>();
    private static final Set<UUID> MANUAL_SCROLL = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> EXPANDED_DETAILS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, CachedLines> FORMATTED_LINES = new ConcurrentHashMap<>();
    private static final Map<UUID, CachedHeader> HEADERS = new ConcurrentHashMap<>();
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
                && ModelGenerationHudState.visibleSnapshotForRender() != null;
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
        drawContext.drawTextWithShadow(client.textRenderer, block.header(), x, y, NATIVE_CHAT_TEXT_COLOR);
        y += client.textRenderer.fontHeight + 2;
        int contentTop = y;
        int materializationCursorX = -1;
        int materializationCursorY = -1;
        if (block.maximumScroll() > 0) {
            drawContext.enableScissor(bounds.x(), contentTop, bounds.x() + bounds.width(), contentTop + block.viewportHeight());
        }
        try (com.spirit.koil.api.chat.RichChatRenderContext.SurfaceScope ignored =
                 RichChatSurfaceRenderer.surface(Math.max(24, bounds.width() - 12), contentTop, contentTop + block.viewportHeight())) {
        for (PanelLine line : block.visibleLines()) {
            int textX = x;
            if (line.treeContinuationPrefix() != null && line.treeIndentPixels() > 0) {
                renderPanelText(
                        drawContext,
                        client,
                        line.treeContinuationPrefix(),
                        x,
                        y,
                        line.gutterPrefixSubtext()
                );
                textX += line.treeIndentPixels();
            }
            OrderedText renderedLine = line.text();
            int endX = renderPanelText(
                    drawContext,
                    client,
                    renderedLine,
                    textX,
                    y,
                    line.subtext()
            );
            // Feed the exact final visual line into the same harvest field as native
            // chat. Structural controls are attached only here, after wrapping and
            // materialization, so they can never become animated/measured glyphs.
            ModelOutputHarvestField.observeLine(drawContext, client.textRenderer, renderedLine, textX, y);
            if (line.cursorAfter()) {
                materializationCursorX = endX;
                materializationCursorY = y;
            }
            if (line.statusAnimation() != null) {
                line.statusAnimation().render(drawContext, client, x, y);
            }
            y += line.height();
        }
        }
        if (block.maximumScroll() > 0) {
            drawContext.disableScissor();
            ModelPopupScrollbar.render(drawContext, scrollbarMetrics(bounds, block, client));
        }
        // Render the source/forge overlay after the popup text scissor is removed.
        // This is what allows visual copies to originate from actual native-chat
        // messages elsewhere on screen and travel into the response cursor.
        if (materializationCursorX >= 0 && materializationCursorY >= 0) {
            ModelOutputMaterialization.drawCursor(
                    drawContext,
                    client.textRenderer,
                    materializationCursorX,
                    materializationCursorY,
                    block.materialization()
            );
        }
        for (Button button : buttons(block, bounds, client)) {
            drawContext.fill(
                    button.x(),
                    button.y(),
                    button.x() + button.width(),
                    button.y() + button.height(),
                    ChatHudPanelVisualStyle.buttonBackground(client)
            );
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal(button.label()), button.x() + 4, button.y() + 3, NATIVE_CHAT_TEXT_COLOR);
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
        if (block == null) return false;
        if (!block.snapshot().state().terminal()
                && !MANUAL_SCROLL.contains(block.snapshot().requestId())
                && !EXPANDED_DETAILS.contains(block.snapshot().requestId())) {
            // Live mode normally lays out only the visible tail for performance.
            // Any explicit scroll switches to the full cached transcript first.
            MANUAL_SCROLL.add(block.snapshot().requestId());
            FORMATTED_LINES.remove(block.snapshot().requestId());
            block = block(context);
        }
        if (block == null || block.maximumScroll() <= 0) return false;
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
        ModelGenerationHudState.Snapshot snapshot = ModelGenerationHudState.visibleSnapshotForRender();
        MinecraftClient client = context.client();
        if (snapshot == null || client == null || client.textRenderer == null) {
            return null;
        }
        boolean liveStatus = snapshot.approval() == null && snapshot.text().isBlank();
        String rawGeneratedText = snapshot.text() == null ? "" : snapshot.text();
        boolean boundedLiveLayout = !snapshot.state().terminal()
                && snapshot.approval() == null
                && !MANUAL_SCROLL.contains(snapshot.requestId())
                && !EXPANDED_DETAILS.contains(snapshot.requestId());
        String generatedText = boundedLiveLayout
                ? normalizeLiveTail(rawGeneratedText, LIVE_RESPONSE_LAYOUT_CHARS)
                : RichChatModelOutputSanitizer.normalizeStreamingPreview(rawGeneratedText);
        com.spirit.koil.api.model.ModelUsage usage = snapshot.usage() == null
                ? com.spirit.koil.api.model.ModelUsage.empty()
                : snapshot.usage();
        ModelOutputMaterialization.Frame materialization = ModelOutputMaterialization.frame(
                snapshot.requestId(),
                rawGeneratedText,
                !snapshot.state().terminal(),
                usage.tokensPerSecond(),
                usage.completionTokens()
        );
        String visibleText;
        if (snapshot.approval() != null) {
            visibleText = approvalVisibleText(snapshot);
        } else if (liveStatus) {
            // Activity, debug evidence, and Request metrics are tree content. Never attach
            // the model identity marker to the tree. The identity belongs to the live
            // model status line rendered immediately after the tree.
            String activity = snapshot.activity() == null ? "" : snapshot.activity().strip();
            visibleText = boundedLiveLayout
                    ? normalizeLiveTail(activity, LIVE_TREE_LAYOUT_CHARS)
                    : RichChatModelOutputSanitizer.normalizeStreamingPreview(activity);
        } else {
            // Keep diagnostic/activity tree rows structurally separate from the model identity.
            // In the normal live-tail path, build from bounded source slices first so
            // sanitization/markdown normalization never scans the entire growing transcript.
            visibleText = boundedLiveLayout
                    ? combinedBoundedLiveText(snapshot, rawGeneratedText)
                    : RichChatModelOutputSanitizer.normalizeStreamingPreview(
                            combinedVisibleText(snapshot, generatedText));
        }
        List<PanelLine> formatted = formattedLines(
                snapshot, visibleText, context, client, materialization, generatedText);
        List<PanelLine> all;
        if (liveStatus) {
            all = new ArrayList<>(formatted.size() + 2);
            all.addAll(formatted);
            // Always show >_: directly on the live model status, even when a debug/activity
            // tree is present above it. Otherwise the marker can visually migrate onto the
            // final tree node (for example Request metrics).
            PanelLine liveLine = liveStatusLine(snapshot, client, true);
            all.add(liveLine);
            all = List.copyOf(all);
        } else {
            all = formatted;
        }
        int lineHeight = nativeChatLineHeight(client);
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
        boolean tailVisible = offset >= maximumScroll;
        if (tailVisible && !liveStatus && snapshot.approval() == null && generatedText != null && !generatedText.isBlank()) {
            // The model identity prefix is rendered as a fixed gutter, not as part of
            // the materialized text stream, so there are no identity glyphs to protect.
            visible = decorateMaterialization(visible, materialization,
                    ModelChatIdentity.PREFIX.codePointCount(0, ModelChatIdentity.PREFIX.length()));
        }
        int visibleTextHeight = visible.stream().mapToInt(PanelLine::height).sum();
        int totalTextHeight = all.stream().mapToInt(PanelLine::height).sum();
        int buttonHeight = client.textRenderer.fontHeight + 6;
        int headerWidth = Math.max(1, context.panelWidth() - 12);
        OrderedText header = cachedHeader(snapshot, headerWidth, client);
        int height = 4 + client.textRenderer.fontHeight + 2
                + visibleTextHeight
                + buttonHeight + 4;
        return new Block(snapshot, header, visible, Math.max(32, height), maximumScroll, offset,
                Math.max(1, visibleTextHeight), Math.max(1, totalTextHeight), materialization);
    }


    private static OrderedText cachedHeader(
            ModelGenerationHudState.Snapshot snapshot,
            int headerWidth,
            MinecraftClient client
    ) {
        // The compact session/counter header is part of Koil's persistent identity,
        // not debug telemetry. Keep it visible even when config.json debug=false.
        // Only the separate detailed metrics/footer diagnostics are debug-gated.
        String modelId = com.spirit.koil.api.model.LocalModelService.configuredModelId();
        int queueDepth = com.spirit.koil.api.model.LocalModelService.queueDepth();
        int contextWindow = com.spirit.koil.api.model.LocalModelService.configuredContextWindowTokens();
        HeaderKey key = new HeaderKey(
                headerWidth,
                snapshot.state(),
                snapshot.usage(),
                snapshot.toolCallCount(),
                snapshot.counters(),
                modelId,
                queueDepth,
                contextWindow
        );
        long now = System.nanoTime();
        CachedHeader cached = HEADERS.get(snapshot.requestId());
        if (cached != null && (cached.key().equals(key)
                || now - cached.builtAtNanos() < HEADER_REFRESH_NANOS)) {
            return cached.header();
        }
        Text headerText = ModelRequestMetricsPresentation.bottomHeaderFitted(
                snapshot, modelId, queueDepth, contextWindow, headerWidth, client.textRenderer::getWidth);
        OrderedText header = client.textRenderer.wrapLines(headerText, headerWidth)
                .stream()
                .findFirst()
                .orElse(Text.empty().asOrderedText());
        if (HEADERS.size() >= 16 && !HEADERS.containsKey(snapshot.requestId())) {
            UUID first = HEADERS.keySet().stream().findFirst().orElse(null);
            if (first != null) HEADERS.remove(first);
        }
        HEADERS.put(snapshot.requestId(), new CachedHeader(key, header, now));
        return header;
    }

    private static List<PanelLine> formattedLines(
            ModelGenerationHudState.Snapshot snapshot,
            String visibleText,
            ChatHudPanelContext context,
            MinecraftClient client,
            ModelOutputMaterialization.Frame materialization,
            String generatedText
    ) {
        int width = Math.max(24, context.panelWidth() - 12);
        boolean approval = snapshot.approval() != null;
        boolean showMaterialization = !approval && generatedText != null && !generatedText.isBlank();
        boolean reserveCursor = showMaterialization
                && (!snapshot.state().terminal() || (materialization != null && materialization.cursorVisible()));
        long now = System.nanoTime();
        CachedLines cached = FORMATTED_LINES.get(snapshot.requestId());
        boolean layoutIdentityMatches = cached != null
                && cached.width() == width
                && cached.approval() == approval
                && cached.cursorReserved() == reserveCursor;
        boolean sourceMatches = layoutIdentityMatches && cached.source().equals(visibleText);
        boolean appendOnlyChange = layoutIdentityMatches
                && !sourceMatches
                && visibleText.startsWith(cached.source());
        if (sourceMatches) {
            return cached.lines();
        }
        if (appendOnlyChange && now - cached.builtAtNanos() < STREAM_LAYOUT_MIN_NANOS) {
            // Fast local models can emit multiple deltas inside one rendered frame.
            // Batch only the expensive Rich Chat parse/wrap step; the native
            // materialization cursor continues animating from live usage data.
            return cached.lines();
        }
        {
            List<PanelLine> lines = new ArrayList<>();
            if (!visibleText.isBlank()) {
                int cursorReserve = reserveCursor
                        ? ModelOutputMaterialization.cursorWidth(client.textRenderer) + 1
                        : 0;
                String[] logicalLines = visibleText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                boolean responseBlock = false;
                String responseIndent = LocalMultilineChatBridge.indentForPrefix(ModelChatIdentity.PREFIX);
                StringBuilder responseRichSource = new StringBuilder();
                for (String rawLogicalLine : logicalLines) {
                    // Provider output and activity data must never carry renderer-only private-use
                    // controls into Minecraft's font path. This also converts the one legacy
                    // SUBTEXT control to the public structural syntax before parsing.
                    String logicalLine = RichChatModelOutputSanitizer.sanitizeRendererControls(rawLogicalLine);
                    boolean responseStart = logicalLine.startsWith(ModelChatIdentity.PREFIX);
                    if (responseStart) {
                        responseBlock = true;
                    }

                    if (responseBlock) {
                        // Keep the complete response as one Rich Chat document. Parsing each
                        // logical line independently destroys multi-line structures because a
                        // fence/table header cannot see its following rows. Buffering the source
                        // lets code blocks, tables, lists, links and future Rich Chat structures
                        // use exactly the same parser contract as ChatHud and Workspace.
                        String body = responseStart
                                ? logicalLine.substring(ModelChatIdentity.PREFIX.length())
                                : logicalLine;
                        if (!responseRichSource.isEmpty()) responseRichSource.append('\n');
                        responseRichSource.append(responseStart
                                ? ModelChatIdentity.PREFIX + body
                                : responseIndent + body);
                        continue;
                    }

                    // Structural activity markers are parser metadata, not glyphs. Strip them
                    // before vanilla measurement, then restore compact semantics only at draw
                    // time. This keeps tree colors while eliminating tofu squares.
                    StructuralWrapLine structural = structuralWrapLine(logicalLine);
                    Text sourceText = Text.literal(structural.wrapSource());
                    if (approval && !structural.subtext()) {
                        sourceText = RichChatBodyWrapFormatter.formatConfirmationDetails(sourceText, width);
                    }
                    Text wrappingText = RichChatPreviewFormatter.format(sourceText);
                    if (wrappingText == null) wrappingText = Text.empty();
                    TreeContinuation continuation = treeContinuation(logicalLine, client);
                    int nativeWidth = nativeWrapWidth(
                            width,
                            cursorReserve,
                            continuation.indentPixels(),
                            continuation.present(),
                            client,
                            wrappingText
                    );
                    if (structural.subtext()) {
                        // Subtext is drawn at 82% scale. Wrap in the corresponding logical
                        // coordinate width so the rendered row consumes the full popup width
                        // instead of wrapping too early or depending on hidden marker geometry.
                        nativeWidth = Math.max(24, (int) Math.floor(nativeWidth / SUBTEXT_SCALE));
                    }
                    List<OrderedText> wrapped = client.textRenderer.wrapLines(wrappingText, nativeWidth);
                    if (wrapped.isEmpty()) {
                        wrapped = List.of(Text.empty().asOrderedText());
                    }
                    for (int wrappedIndex = 0; wrappedIndex < wrapped.size(); wrappedIndex++) {
                        OrderedText line = wrapped.get(wrappedIndex);
                        OrderedText gutterPrefix = wrappedIndex > 0 && continuation.present()
                                ? continuation.prefix()
                                : null;
                        int gutterIndent = gutterPrefix == null ? 0 : continuation.indentPixels();
                        lines.add(new PanelLine(
                                line,
                                Math.max(
                                        nativeChatLineHeight(client),
                                        RichChatSurfaceRenderer.lineHeight(client.textRenderer, line)
                                ),
                                null,
                                false,
                                gutterPrefix,
                                gutterIndent,
                                gutterPrefix != null,
                                structural.subtext()
                        ));
                    }
                }
                if (!responseRichSource.isEmpty()) {
                    int richWidth = Math.max(24, width - cursorReserve);
                    List<OrderedText> wrappedResponse = RichChatSurfaceRenderer.wrap(
                            client.textRenderer,
                            Text.literal(responseRichSource.toString()),
                            RichChatRowType.MODEL_RESPONSE,
                            richWidth
                    );
                    for (OrderedText line : wrappedResponse) {
                        lines.add(new PanelLine(
                                line,
                                Math.max(
                                        nativeChatLineHeight(client),
                                        RichChatSurfaceRenderer.lineHeight(client.textRenderer, line)
                                ),
                                null, false, null, 0, false, false
                        ));
                    }
                }
            }
            List<PanelLine> immutable = List.copyOf(lines);
            if (FORMATTED_LINES.size() >= 16 && !FORMATTED_LINES.containsKey(snapshot.requestId())) {
                UUID first = FORMATTED_LINES.keySet().stream().findFirst().orElse(null);
                if (first != null) FORMATTED_LINES.remove(first);
            }
            cached = new CachedLines(visibleText, width, approval, reserveCursor, immutable, now);
            FORMATTED_LINES.put(snapshot.requestId(), cached);
        }
        return cached.lines();
    }

    private static List<PanelLine> decorateMaterialization(
            List<PanelLine> base,
            ModelOutputMaterialization.Frame frame,
            int protectedCharacters
    ) {
        if (base == null || base.isEmpty() || frame == null
                || (!frame.animating() && !frame.cursorVisible())) {
            return base == null ? List.of() : base;
        }
        List<OrderedText> wrapped = new ArrayList<>(base.size());
        for (PanelLine line : base) wrapped.add(line.text());
        List<OrderedText> materialized = ModelOutputMaterialization.applyWrappedTail(
                wrapped, frame, protectedCharacters);
        List<PanelLine> result = new ArrayList<>(base.size());
        int last = base.size() - 1;
        for (int index = 0; index < base.size(); index++) {
            PanelLine source = base.get(index);
            result.add(new PanelLine(
                    materialized.get(index),
                    source.height(),
                    source.statusAnimation(),
                    frame.cursorVisible() && index == last,
                    source.treeContinuationPrefix(),
                    source.treeIndentPixels(),
                    source.gutterPrefixSubtext(),
                    source.subtext()
            ));
        }
        return List.copyOf(result);
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
        return ModelRequestStatusPresentation.forSnapshot(snapshot).liveText();
    }

    private static PanelLine liveStatusLine(
            ModelGenerationHudState.Snapshot snapshot,
            MinecraftClient client,
            boolean includeIdentity
    ) {
        MutableText line = Text.empty();
        if (includeIdentity) {
            line.append(Text.literal(ModelChatIdentity.PREFIX));
        } else {
            int prefixWidth = client.textRenderer.getWidth(ModelChatIdentity.PREFIX);
            int spaceWidth = Math.max(1, client.textRenderer.getWidth(" "));
            int spaces = ModelChatIdentity.alignedPrefixAdvance(prefixWidth, spaceWidth) / spaceWidth;
            line.append(Text.literal(" ".repeat(Math.max(0, spaces))));
        }
        ModelRequestStatusPresentation.View status = ModelRequestStatusPresentation.forSnapshot(snapshot);
        int statusOffset = client.textRenderer.getWidth(line);
        return new PanelLine(
                line.asOrderedText(),
                nativeChatLineHeight(client),
                new StatusAnimation(
                        statusOffset,
                        status.label(),
                        status.detail(),
                        status.semanticState(),
                        ModelSemanticPalette.color(status.activityState()),
                        statusHighlightPixelOffset(status.label(), includeIdentity)
                )
        );
    }

    /** Separator between the active verb and its detail is intentionally neutral dark gray. */

    /**
     * Uses the same baseline line rhythm as native ChatHud. The popup is part of
     * chat, so response rows should react to Minecraft's chat line-spacing option
     * instead of carrying their own +1 pixel layout convention.
     */
    private static int nativeChatLineHeight(MinecraftClient client) {
        if (client == null || client.textRenderer == null) return 9;
        double spacing = client.options == null
                ? 0.0D
                : ((Double) client.options.getChatLineSpacing().getValue());
        return Math.max(1, (int) Math.round(client.textRenderer.fontHeight * (1.0D + spacing)));
    }

    private static int responseGutterAdvance(MinecraftClient client) {
        if (client == null || client.textRenderer == null) return 0;
        int prefixWidth = client.textRenderer.getWidth(ModelChatIdentity.PREFIX);
        int spaceWidth = Math.max(1, client.textRenderer.getWidth(" "));
        return Math.max(prefixWidth + 2, ModelChatIdentity.alignedPrefixAdvance(prefixWidth, spaceWidth));
    }

    public static int statusSeparatorColor() {
        return RichChatStructuralStyleRegistry.color(
                RichChatStructuralStyleRegistry.Role.SUBTEXT,
                0xFF555555
        ) & 0x00FFFFFF;
    }

    public static int statusHighlightPixelOffset(String label, boolean includeIdentity) {
        return includeIdentity && ("Starting".equals(label) || "Thinking".equals(label)) ? -1 : 0;
    }

    private static String combinedBoundedLiveText(
            ModelGenerationHudState.Snapshot snapshot,
            String rawGeneratedText
    ) {
        String deep = normalizeLiveTail(deepThoughtSummary(snapshot), Math.min(2048, LIVE_TREE_LAYOUT_CHARS));
        String activity = normalizeLiveTail(
                snapshot.activity() == null ? "" : snapshot.activity().strip(), LIVE_TREE_LAYOUT_CHARS);
        String tree;
        if (deep.isBlank()) tree = activity;
        else if (activity.isBlank()) tree = deep;
        else tree = deep + "\n" + activity;
        String responseBody = normalizeLiveTail(rawGeneratedText, LIVE_RESPONSE_LAYOUT_CHARS);
        if (responseBody.isBlank()) return tree;
        String response = ModelChatIdentity.PREFIX + responseBody;
        return tree.isBlank() ? response : tree + "\n" + response;
    }

    private static String normalizeLiveTail(String source, int limit) {
        if (source == null || source.isBlank()) return "";
        String tail = tailAtLineBoundary(source, limit, true);
        return RichChatModelOutputSanitizer.normalizeStreamingPreview(tail);
    }

    private static String tailAtLineBoundary(String source, int limit, boolean preserveCodeFence) {
        if (source == null || source.length() <= limit) return source == null ? "" : source;
        int cut = Math.max(0, source.length() - Math.max(256, limit));
        int newline = source.indexOf('\n', cut);
        if (newline >= 0 && newline + 1 < source.length()) cut = newline + 1;
        if (preserveCodeFence && cut > 0) {
            // Keep a nearby fenced block intact without scanning the entire response.
            // If the bounded cut lands between a recent fence and its next mate,
            // include that fence in the tail so Rich Chat sees a structurally useful
            // block. The search is localized to the bounded live-layout window.
            int opening = source.lastIndexOf("```", Math.max(0, cut - 1));
            int closing = source.indexOf("```", cut);
            if (opening >= 0 && closing >= cut
                    && source.length() - opening <= LIVE_RESPONSE_CODE_BLOCK_MAX_CHARS) {
                cut = opening;
            }
        }
        return source.substring(Math.max(0, Math.min(cut, source.length())));
    }

    private static String combinedVisibleText(ModelGenerationHudState.Snapshot snapshot, String generatedText) {
        String current = generatedText == null ? "" : generatedText;
        String deep = deepThoughtSummary(snapshot);
        String activity = snapshot.activity() == null ? "" : snapshot.activity().strip();

        String tree;
        if (deep.isBlank()) {
            tree = activity;
        } else if (activity.isBlank()) {
            tree = deep;
        } else {
            tree = deep + "\n" + activity;
        }

        if (current.isBlank()) {
            return tree;
        }
        String response = ModelChatIdentity.decorate(current);
        return tree.isBlank() ? response : tree + "\n" + response;
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
            int totalContentHeight,
            ModelOutputMaterialization.Frame materialization
    ) {
    }

    private record HeaderKey(
            int width,
            com.spirit.koil.api.model.ModelRequestState state,
            com.spirit.koil.api.model.ModelUsage usage,
            int toolCallCount,
            com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot counters,
            String modelId,
            int queueDepth,
            int contextWindow
    ) {
    }

    private record CachedHeader(HeaderKey key, OrderedText header, long builtAtNanos) {
    }

    private record CachedLines(
            String source,
            int width,
            boolean approval,
            boolean cursorReserved,
            List<PanelLine> lines,
            long builtAtNanos
    ) {
    }

    private record PanelLine(
            OrderedText text,
            int height,
            StatusAnimation statusAnimation,
            boolean cursorAfter,
            OrderedText treeContinuationPrefix,
            int treeIndentPixels,
            boolean gutterPrefixSubtext,
            boolean subtext
    ) {
        private PanelLine(OrderedText text, int height) {
            this(text, height, null, false, null, 0, false, false);
        }

        private PanelLine(OrderedText text, int height, StatusAnimation statusAnimation) {
            this(text, height, statusAnimation, false, null, 0, false, false);
        }

        private PanelLine(OrderedText text, int height, StatusAnimation statusAnimation, boolean cursorAfter) {
            this(text, height, statusAnimation, cursorAfter, null, 0, false, false);
        }
    }



    /**
     * Converts one logical popup row into text that is safe for vanilla wrapping.
     * Structural subtext controls are intentionally absent from wrapSource().
     */
    public static StructuralWrapLine structuralWrapLine(String rawLine) {
        String value = rawLine == null ? "" : rawLine;
        RichChatStructuralContinuation.Subtext parsed =
                RichChatStructuralContinuation.parseSubtext(value);
        if (parsed == null) {
            return new StructuralWrapLine(value, false);
        }
        return new StructuralWrapLine(parsed.leadingWhitespace() + parsed.content(), true);
    }

    /**
     * Renders subtext without ever re-inserting Koil's private structural marker.
     * The marker is parser-only metadata. Feeding it back into TextRenderer makes
     * unsupported-font clients paint U+E380 as a white square and also contaminates
     * width measurement. Scaling is applied only at the draw boundary.
     */
    private static int renderPanelText(
            DrawContext drawContext,
            MinecraftClient client,
            OrderedText text,
            int x,
            int y,
            boolean subtext
    ) {
        if (text == null || client == null || client.textRenderer == null) return x;
        if (!subtext) {
            return RichChatSurfaceRenderer.renderLine(
                    drawContext, client.textRenderer, text, x, y, NATIVE_CHAT_TEXT_COLOR);
        }
        final float scale = SUBTEXT_SCALE;
        int color = RichChatStructuralStyleRegistry.color(
                RichChatStructuralStyleRegistry.Role.SUBTEXT, 0xFF555555);
        drawContext.getMatrices().push();
        drawContext.getMatrices().scale(scale, scale, 1.0F);
        int scaledX = Math.round(x / scale);
        int scaledY = Math.round(y / scale);
        int scaledEnd = RichChatSurfaceRenderer.renderLine(
                drawContext, client.textRenderer, text, scaledX, scaledY, color);
        drawContext.getMatrices().pop();
        return Math.round(scaledEnd * scale);
    }

    public static record StructuralWrapLine(String wrapSource, boolean subtext) {
        public StructuralWrapLine {
            wrapSource = wrapSource == null ? "" : wrapSource;
        }
    }

    /**
     * Structural activity rows already reserve their visible tree gutter explicitly.
     * Do not apply Rich Chat's hidden-markup width compensation to those rows: the
     * compensation can be hundreds of pixels on compact/subtext content and would
     * effectively disable wrapping inside the popup. Ordinary response prose keeps
     * the normal adjustment.
     */
    public static int nativeWrapWidth(
            int panelContentWidth,
            int cursorReserve,
            int treeIndentPixels,
            boolean structuralTreeRow,
            MinecraftClient client,
            Text wrappingText
    ) {
        int base = Math.max(24, panelContentWidth - Math.max(0, cursorReserve)
                - Math.max(0, treeIndentPixels));
        // The popup owns its visible content boundary. Rich Chat's native-source
        // compensation is useful when Minecraft wraps source markup later, but this
        // panel is already drawing the formatted OrderedText itself. Expanding this
        // boundary can make a whole response appear as one over-wide visual row.
        return base;
    }

    static TreeContinuation treeContinuation(String rawLine, MinecraftClient client) {
        if (rawLine == null || rawLine.isEmpty() || client == null || client.textRenderer == null) {
            return TreeContinuation.NONE;
        }
        String tree = treeContinuationGlyphs(rawLine);
        if (tree.isEmpty()) return TreeContinuation.NONE;
        // Store a clean prefix. The subtext control is attached only at the final
        // draw boundary, never in cached/wrapped/materialized panel data.
        String rawPrefix = "§8" + tree + "§r";
        OrderedText prefix = RichChatPreviewFormatter.format(Text.literal(rawPrefix)).asOrderedText();
        int indent = Math.max(1, Math.round(client.textRenderer.getWidth(tree) * 0.82F));
        return new TreeContinuation(prefix, indent);
    }

    public static String treeContinuationGlyphs(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) return "";
        String value = rawLine;
        int cursor = 0;
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
        if (value.startsWith("-# ", cursor)) cursor += 3;
        else if (RichChatStructuralContinuation.isSubtext(value, cursor)) cursor += 1;
        else return "";

        while (cursor < value.length()) {
            int codeLength = com.spirit.koil.api.chat.RichChatSectionFormatting.codeLengthAt(value, cursor);
            if (codeLength <= 0) break;
            cursor += codeLength;
        }

        StringBuilder visibleTree = new StringBuilder();
        boolean sawTreeGlyph = false;
        for (int i = cursor; i < value.length();) {
            char c = value.charAt(i);
            if (c == '§') {
                int codeLength = com.spirit.koil.api.chat.RichChatSectionFormatting.codeLengthAt(value, i);
                if (codeLength > 0) {
                    i += codeLength;
                    continue;
                }
            }
            if (c == '│') {
                visibleTree.append('│');
                sawTreeGlyph = true;
                i++;
                continue;
            }
            if ((c == '├' || c == '└') && i + 1 < value.length() && value.charAt(i + 1) == '─') {
                visibleTree.append('│').append(' ');
                sawTreeGlyph = true;
                i += 2;
                continue;
            }
            if (c == ' ' && sawTreeGlyph) {
                visibleTree.append(' ');
                i++;
                continue;
            }
            break;
        }
        return sawTreeGlyph ? visibleTree.toString() : "";
    }

    static record TreeContinuation(OrderedText prefix, int indentPixels) {
        static final TreeContinuation NONE = new TreeContinuation(null, 0);
        boolean present() { return this.prefix != null && this.indentPixels > 0; }
    }

    private record StatusAnimation(
            int xOffset,
            String label,
            String detail,
            String semanticState,
            int color,
            int highlightPixelOffset
    ) {
        private StatusAnimation {
            label = label == null ? "" : label;
            detail = detail == null ? "" : detail.strip();
            semanticState = semanticState == null ? "idle" : semanticState;
        }
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
            String separator = " | ";
            int separatorX = textX + client.textRenderer.getWidth(visible);
            drawContext.drawTextWithShadow(
                    client.textRenderer,
                    Text.literal(separator),
                    separatorX,
                    y,
                    statusSeparatorColor()
            );
            if (!this.detail.isBlank()) {
                int detailX = separatorX + client.textRenderer.getWidth(separator);
                drawContext.drawTextWithShadow(
                        client.textRenderer,
                        Text.literal(this.detail),
                        detailX,
                        y,
                        SlidingStatusText.transitionColor(this.color)
                );
            }
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
