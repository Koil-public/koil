package com.spirit.koil.api.chat;

import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.model.chat.ModelChatIdentity;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ModelChatMessageBridge {
    private static final String LOGGED_NAME = "Koil local model";
    private static final int MAXIMUM_TRACE_CHARS = 3_000;
    private static final int MAXIMUM_RETAINED_TRACES = 128;
    private static final Object TRACE_LOCK = new Object();
    private static final Map<MessageIndicator, ModelActivityPresentation.TraceSnapshot> TYPED_TRACES = new IdentityHashMap<>();
    private static final Map<MessageIndicator, List<Text>> LEGACY_TRACE_LINES = new IdentityHashMap<>();
    private static final List<MessageIndicator> TRACE_ORDER = new ArrayList<>();

    private ModelChatMessageBridge() {
    }

    public static MessageIndicator indicator() {
        return indicator("");
    }

    public static MessageIndicator indicator(String safeActivityTrace) {
        List<Text> trace = styledTrace(safeActivityTrace);
        MessageIndicator indicator = new MessageIndicator(
                uiColorVal.uiColorLocalModelMessageBar & 0x00FFFFFF,
                null,
                trace.isEmpty() ? Text.literal("Local model response") : null,
                LOGGED_NAME
        );
        if (!trace.isEmpty()) {
            rememberTrace(indicator, trace);
        }
        return indicator;
    }

    public static MessageIndicator indicator(ModelActivityPresentation.TraceSnapshot traceSnapshot) {
        String rendered = ModelActivityPresentation.render(traceSnapshot);
        List<Text> trace = styledTrace(rendered);
        MessageIndicator indicator = new MessageIndicator(
                uiColorVal.uiColorLocalModelMessageBar & 0x00FFFFFF,
                null,
                trace.isEmpty() ? Text.literal("Local model response") : null,
                LOGGED_NAME
        );
        if (!trace.isEmpty()) rememberTrace(indicator, traceSnapshot);
        return indicator;
    }

    public static boolean isModelIndicator(MessageIndicator indicator) {
        return indicator != null && LOGGED_NAME.equals(indicator.loggedName());
    }

    public static void addToChat(MinecraftClient client, String finalizedText) {
        addToChat(client, finalizedText, "");
    }

    public static void addToChat(MinecraftClient client, String finalizedText, String safeActivityTrace) {
        if (client == null || client.inGameHud == null || finalizedText == null || finalizedText.isBlank()) {
            return;
        }
        String visibleText = ModelChatIdentity.decorate(finalizedText);
        RichChatMessageData message = RichMessageBuilder.create()
                .scope(RichChatScope.SYSTEM)
                .type(RichChatMessageType.MODEL_RESPONSE)
                .rawText(finalizedText)
                .fallbackText(visibleText)
                .segment(RichChatSegment.multilineText(visibleText))
                .metadata("source", "local_model")
                .metadata("visible_identity", ModelChatIdentity.LABEL)
                .build();
        RichChatMessageStore.remember(message);
        client.inGameHud.getChatHud().addMessage(Text.literal(visibleText), null, indicator(safeActivityTrace));
    }

    public static void addToChat(
            MinecraftClient client,
            String finalizedText,
            ModelActivityPresentation.TraceSnapshot traceSnapshot
    ) {
        if (client == null || client.inGameHud == null || finalizedText == null || finalizedText.isBlank()) return;
        String visibleText = ModelChatIdentity.decorate(finalizedText);
        RichChatMessageData message = RichMessageBuilder.create()
                .scope(RichChatScope.SYSTEM)
                .type(RichChatMessageType.MODEL_RESPONSE)
                .rawText(finalizedText)
                .fallbackText(visibleText)
                .segment(RichChatSegment.multilineText(visibleText))
                .metadata("source", "local_model")
                .metadata("visible_identity", ModelChatIdentity.LABEL)
                .build();
        RichChatMessageStore.remember(message);
        client.inGameHud.getChatHud().addMessage(Text.literal(visibleText), null, indicator(traceSnapshot));
    }

    public static List<Text> traceTooltipLines(MessageIndicator indicator) {
        synchronized (TRACE_LOCK) {
            ModelActivityPresentation.TraceSnapshot typed = TYPED_TRACES.get(indicator);
            if (typed != null) return styledTrace(ModelActivityPresentation.render(typed));
            List<Text> lines = LEGACY_TRACE_LINES.get(indicator);
            return lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public static boolean renderTraceTooltip(
            DrawContext context,
            MinecraftClient client,
            int mouseX,
            int mouseY
    ) {
        if (context == null || client == null || client.inGameHud == null) {
            return false;
        }
        MessageIndicator indicator = client.inGameHud.getChatHud().getIndicatorAt(mouseX, mouseY);
        List<Text> trace = traceTooltipLines(indicator);
        if (trace.isEmpty()) {
            return false;
        }
        TextRenderer renderer = client.textRenderer;
        int targetWidth = Math.max(180, Math.min(460, client.getWindow().getScaledWidth() - 24));
        List<Text> tooltip = new ArrayList<>(trace.size() + 1);
        MutableText title = Text.literal("Activity trace").formatted(net.minecraft.util.Formatting.WHITE);
        while (renderer.getWidth(title) < targetWidth) {
            title.append(Text.literal(" "));
        }
        tooltip.add(title);
        for (Text line : trace) {
            for (net.minecraft.text.OrderedText wrapped : renderer.wrapLines(line, targetWidth)) {
                tooltip.add(fromOrderedText(wrapped));
            }
        }
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 1_100.0F);
        context.drawTooltip(renderer, tooltip, Optional.empty(), mouseX, mouseY);
        context.getMatrices().pop();
        return true;
    }

    private static Text fromOrderedText(net.minecraft.text.OrderedText ordered) {
        MutableText result = Text.empty();
        final Style[] active = {null};
        final StringBuilder run = new StringBuilder();
        ordered.accept((index, style, codePoint) -> {
            if (active[0] != null && !active[0].equals(style)) {
                result.append(Text.literal(run.toString()).setStyle(active[0]));
                run.setLength(0);
            }
            active[0] = style;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (!run.isEmpty()) result.append(Text.literal(run.toString()).setStyle(active[0] == null ? Style.EMPTY : active[0]));
        return result;
    }

    private static void rememberTrace(MessageIndicator indicator, List<Text> trace) {
        synchronized (TRACE_LOCK) {
            LEGACY_TRACE_LINES.put(indicator, List.copyOf(trace));
            TRACE_ORDER.add(indicator);
            while (TRACE_ORDER.size() > MAXIMUM_RETAINED_TRACES) {
                MessageIndicator removed = TRACE_ORDER.remove(0);
                LEGACY_TRACE_LINES.remove(removed);
                TYPED_TRACES.remove(removed);
            }
        }
    }

    private static void rememberTrace(MessageIndicator indicator, ModelActivityPresentation.TraceSnapshot trace) {
        synchronized (TRACE_LOCK) {
            TYPED_TRACES.put(indicator, trace);
            TRACE_ORDER.add(indicator);
            while (TRACE_ORDER.size() > MAXIMUM_RETAINED_TRACES) {
                MessageIndicator removed = TRACE_ORDER.remove(0);
                LEGACY_TRACE_LINES.remove(removed);
                TYPED_TRACES.remove(removed);
            }
        }
    }

    private static List<Text> styledTrace(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String bounded = value.length() > MAXIMUM_TRACE_CHARS
                ? value.substring(0, MAXIMUM_TRACE_CHARS - 1) + "…"
                : value;
        List<Text> lines = new ArrayList<>();
        for (String sourceLine : bounded.split("\\R", -1)) {
            String line = sourceLine.startsWith("-# ") ? sourceLine.substring(3) : sourceLine;
            if (line.isBlank()) {
                continue;
            }
            MutableText styled = Text.empty();
            for (RichChatSectionFormatting.Segment segment
                    : RichChatSectionFormatting.parse(line, Style.EMPTY)) {
                styled.append(Text.literal(segment.text()).setStyle(segment.style()));
            }
            lines.add(styled);
        }
        return List.copyOf(lines);
    }
}
