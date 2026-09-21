package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.latex.RichChatLatexFormatter;
import com.spirit.koil.api.chat.latex.RichChatLatexTextureRenderer;
import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import com.spirit.koil.api.chat.upload.RichChatWebAttachmentBridge;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared Rich Chat pipeline for detached UI surfaces.
 *
 * <p>ChatHud normally gets this behavior through {@code MixinChatHud}: Rich Chat
 * rewrites the source, wraps with row semantics, converts section controls to
 * real Text styles, and finally paints through {@link RichChatAttachmentRenderer}.
 * Model surfaces such as the bottom generation popup and Automation Workspace
 * are not native ChatHud rows, so they must explicitly use the same contract
 * rather than partially reimplementing it.</p>
 *
 * <p>This class is deliberately the only detached-surface entry point. Keeping
 * formatting, wrapping, line-height calculation, and final paint here prevents
 * the model UIs from drifting away from Rich Chat when new syntax/effects are
 * added later.</p>
 */
public final class RichChatSurfaceRenderer {
    private RichChatSurfaceRenderer() {
    }

    public static Text format(Text message, RichChatRowType rowType, int wrapWidth) {
        int safeWidth = Math.max(24, wrapWidth);
        try (RichChatRenderContext.SurfaceScope ignored = RichChatRenderContext.detachedSurface(
                safeWidth, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4)) {
            return formatInsideSurface(message, rowType, safeWidth);
        }
    }

    private static Text formatInsideSurface(Text message, RichChatRowType rowType, int wrapWidth) {
        if (message == null) return null;
        if (!RichChatSettings.enabled()) return message;

        // Detached surfaces must always enter Rich Chat from source text, never from
        // another surface's renderer transport representation. Code/table bridges use
        // private-use sentinels containing ids such as CODE:<uuid>:<row>. Reusing that
        // transport across surfaces can expose the payload when a marker is stripped or
        // its backing block has expired. Canonicalize it back to Markdown first.
        Text rewritten = canonicalizeTransportSource(message);
        if (RichChatSettings.mediaEnabled()) {
            rewritten = RichChatWebAttachmentBridge.rewrite(rewritten);
        }
        if (RichChatSettings.latexEnabled()) {
            rewritten = RichChatLatexFormatter.format(rewritten);
        }
        rewritten = RichChatPrivateMessageBridge.observeAndRewrite(rewritten);
        if (RichChatSettings.effectsEnabled()) {
            rewritten = RichChatCodeBlockBridge.rewrite(rewritten);
            rewritten = RichChatTableBridge.rewrite(rewritten);
        }

        RichChatRowType effectiveType = rowType == null || rowType == RichChatRowType.UNKNOWN
                ? RichChatRowClassifier.classify(rewritten, null)
                : rowType;
        if (rewritten != null && effectiveType.usesStructuralSpacing()) {
            rewritten = wrapWidth > 0
                    ? RichChatBodyWrapFormatter.format(rewritten, effectiveType, wrapWidth)
                    : RichChatBodyWrapFormatter.format(rewritten, effectiveType);
        }
        if (RichChatSettings.effectsEnabled()) {
            rewritten = RichChatMaskedLinkBridge.rewrite(rewritten);
        }
        return RichChatSectionFormatting.styleBeforeWrapping(rewritten);
    }

    private static Text canonicalizeTransportSource(Text message) {
        if (message == null) return null;
        String visible = message.getString();
        if (visible == null || visible.isEmpty()) return message;

        boolean internal = RichChatCodeBlockBridge.containsMarker(visible)
                || RichChatTableBridge.containsMarker(visible);
        if (!internal) return message;

        String source = visible;
        if (RichChatCodeBlockBridge.containsMarker(source)) {
            source = RichChatCodeBlockBridge.logFriendlyText(source);
        }
        if (RichChatTableBridge.containsMarker(source)) {
            source = RichChatTableBridge.logFriendlyText(source);
        }
        return Text.literal(source);
    }

    public static List<OrderedText> wrap(
            TextRenderer renderer,
            Text message,
            RichChatRowType rowType,
            int wrapWidth
    ) {
        if (renderer == null) return List.of();
        int safeWidth = Math.max(8, wrapWidth);
        try (RichChatRenderContext.SurfaceScope ignored = RichChatRenderContext.detachedSurface(
                safeWidth, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4)) {
            Text formatted = formatInsideSurface(message, rowType, safeWidth);
            if (formatted == null) formatted = Text.empty();

            String visible = formatted.getString();
            int nativeWidth = safeWidth
                    + RichChatPrivateMessageBridge.nativeWrapWidthAdjustment(renderer, visible)
                    + RichChatBodyWrapFormatter.nativeWrapWidthAdjustment(renderer, visible);
            List<OrderedText> wrapped = renderer.wrapLines(formatted, Math.max(8, nativeWidth));
            if (wrapped.isEmpty()) return List.of(Text.empty().asOrderedText());

            // Native ChatHud needs explicit spacer-marker rows because all of its
            // rows have one fixed height. Detached surfaces already reserve each
            // Rich Chat row's real height, so carrying those invisible rows over
            // creates the open blank lines seen between code/table cards.
            List<OrderedText> compact = new ArrayList<>(wrapped.size());
            for (OrderedText line : wrapped) {
                if (!detachedSpacer(line)) compact.add(line);
            }
            return compact.isEmpty() ? List.of(Text.empty().asOrderedText()) : List.copyOf(compact);
        }
    }

    private static boolean detachedSpacer(OrderedText line) {
        if (line == null) return false;
        String plain = RichChatLatexTextureRenderer.plainText(line);
        String display = RichChatPrivateMessageBridge.displayText(plain);
        if (display == null || display.isEmpty()) return false;

        // Spacer rows can still carry Rich Chat's hidden/private prefix and the
        // visible body continuation prefix (for example the model-response
        // gutter indent). Strip those structural prefixes before deciding
        // whether the row contains only a reserve marker.
        String privatePrefix = RichChatPrivateMessageBridge.leadingMarkerPrefix(display);
        String visible = privatePrefix.isEmpty() ? display : display.substring(privatePrefix.length());
        String bodyPrefix = RichChatBodyWrapFormatter.detectVisibleBodyPrefix(visible);
        String body = bodyPrefix.isEmpty() ? visible : visible.substring(bodyPrefix.length());
        String stripped = body.strip();
        return stripped.length() == 1
                && (stripped.charAt(0) == RichChatCodeBlockBridge.SPACER_MARKER
                || stripped.charAt(0) == RichChatTableBridge.SPACER_MARKER);
    }

    /**
     * Establishes the real detached viewport for final Rich Chat painting. Code
     * blocks/media/LaTeX must clip against this surface, never ChatHud hidden
     * underneath another screen.
     */
    public static RichChatRenderContext.SurfaceScope surface(int contentWidth, int viewportTop, int viewportBottom) {
        return RichChatRenderContext.detachedSurface(contentWidth, viewportTop, viewportBottom);
    }

    public static int renderLine(
            DrawContext context,
            TextRenderer renderer,
            OrderedText line,
            int x,
            int y,
            int color
    ) {
        if (context == null || renderer == null || line == null) return x;
        if (!RichChatSettings.enabled()
                || (!RichChatSettings.mediaEnabled()
                && !RichChatSettings.latexEnabled()
                && !RichChatSettings.effectsEnabled())) {
            return context.drawTextWithShadow(renderer, line, x, y, color);
        }

        // Detached model surfaces intentionally do not inherit ChatHud's clock
        // overlay. Everything else is painted by the same Rich Chat renderer.
        RichChatTimestampBridge.beginRenderSuppression();
        try {
            return RichChatAttachmentRenderer.renderPreviewOrDrawText(
                    context, renderer, line, x, y, color);
        } finally {
            RichChatTimestampBridge.endRenderSuppression();
        }
    }

    public static int lineHeight(TextRenderer renderer, OrderedText line) {
        if (renderer == null) return 1;
        if (!RichChatSettings.enabled()) return renderer.fontHeight + 1;
        return Math.max(
                renderer.fontHeight + 1,
                RichChatAttachmentRenderer.liveFormattedLineHeight(renderer, line)
        );
    }
}
