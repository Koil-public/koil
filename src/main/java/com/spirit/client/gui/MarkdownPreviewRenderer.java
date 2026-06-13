package com.spirit.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.spirit.Main.SUBLOGGER;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadImage;

public final class MarkdownPreviewRenderer {
    private static final String BREAK_TOKEN = "[[KOIL_BR]]";
    private static final String UNDERLINE_TOKEN_OPEN = "[[KOIL_U_OPEN]]";
    private static final String UNDERLINE_TOKEN_CLOSE = "[[KOIL_U_CLOSE]]";
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\d+\\.)\\s+(.*)");
    private static final Pattern DETAILS_PATTERN = Pattern.compile("<summary>(.*?)</summary>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*?(?:alt=[\"']([^\"']*)[\"'])?[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_TAG_PATTERN = Pattern.compile("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR_IMAGE_PATTERN = Pattern.compile("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>\\s*(<img[^>]*src=[\"'][^\"']+[\"'][^>]*>)\\s*</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IFRAME_PATTERN = Pattern.compile("<iframe[^>]*src=[\"']([^\"']+)[\"'][^>]*>(.*?)</iframe>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEADING_TAG_PATTERN = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");
    private static final Pattern PARAGRAPH_TAG_PATTERN = Pattern.compile("(?is)<p([^>]*)>(.*?)</p>");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("(?is)<li([^>]*)>(.*?)</li>");
    private static final Pattern STRONG_TAG_PATTERN = Pattern.compile("(?is)<strong([^>]*)>(.*?)</strong>");
    private static final Pattern BOLD_TAG_PATTERN = Pattern.compile("(?is)<b([^>]*)>(.*?)</b>");
    private static final Pattern EMPHASIS_TAG_PATTERN = Pattern.compile("(?is)<(em|i)([^>]*)>(.*?)</\\1>");
    private static final Pattern UNDERLINE_TAG_PATTERN = Pattern.compile("(?is)<u([^>]*)>(.*?)</u>");
    private static final Pattern STRIKE_TAG_PATTERN = Pattern.compile("(?is)<(s|del|strike)([^>]*)>(.*?)</\\1>");
    private static final Pattern SPAN_TAG_PATTERN = Pattern.compile("(?is)<span([^>]*)>(.*?)</span>");
    private static final Pattern WIDTH_ATTRIBUTE_PATTERN = Pattern.compile("width=[\"']?(\\d+)(?:px)?[\"']?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_URL_PATTERN = Pattern.compile("(?<![<(])(https?://\\S+)");
    private static final Pattern TABLE_RULE_PATTERN = Pattern.compile("^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");

    private static final Map<String, ImagePreview> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> IMAGE_LOADING = ConcurrentHashMap.newKeySet();
    private static final List<LinkRegion> ACTIVE_LINKS = new ArrayList<>();
    private static final List<DetailsRegion> ACTIVE_DETAILS = new ArrayList<>();
    private static final Set<String> EXPANDED_DETAILS = ConcurrentHashMap.newKeySet();
    private static final Map<String, TableBlock> TABLE_CACHE = new ConcurrentHashMap<>();

    public enum Accent {
        NONE,
        HEADING,
        QUOTE,
        BULLET,
        CODE,
        RULE,
        TASK,
        IMAGE,
        DETAILS_SUMMARY,
        TABLE_BLOCK
    }

    public record InlineSpan(String text, int color, boolean bold, boolean italic, boolean underline,
                             boolean strikethrough, boolean code, String linkTarget, String imagePayload) {
    }

    public record Line(String rawText, List<InlineSpan> spans, int indent, int color, int accentColor,
                       Accent accent, String payload) {
        public Line(String rawText, int indent, int color, int accentColor, Accent accent) {
            this(rawText, List.of(new InlineSpan(rawText, color, false, false, false, false, false, null, null)),
                    indent, color, accentColor, accent, "");
        }
    }

    private record ImagePreview(Identifier textureId, NativeImageBackedTexture texture, int width, int height) {
    }

    private record LinkRegion(int x, int y, int width, int height, String url, String label) {
    }

    private record DetailsRegion(int x, int y, int width, int height, String detailsId, boolean expanded, String label) {
    }

    private record TableBlock(List<List<String>> rows, boolean hasHeader, int maxWidth) {
    }

    private MarkdownPreviewRenderer() {
    }

    public static void beginInteractiveFrame() {
        ACTIVE_LINKS.clear();
        ACTIVE_DETAILS.clear();
    }

    public static boolean renderLinkTooltip(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
        for (LinkRegion region : ACTIVE_LINKS) {
            if (mouseX >= region.x && mouseX <= region.x + region.width
                    && mouseY >= region.y && mouseY <= region.y + region.height) {
                context.drawTooltip(textRenderer, List.of(
                        Text.literal("Link").formatted(Formatting.GRAY),
                        Text.literal(region.label).formatted(Formatting.WHITE),
                        Text.literal(region.url).formatted(Formatting.DARK_GRAY)
                ), mouseX, mouseY);
                return true;
            }
        }
        return false;
    }

    public static boolean handleLinkClick(double mouseX, double mouseY) {
        for (DetailsRegion region : ACTIVE_DETAILS) {
            if (mouseX >= region.x && mouseX <= region.x + region.width
                    && mouseY >= region.y && mouseY <= region.y + region.height) {
                if (region.expanded) {
                    EXPANDED_DETAILS.remove(region.detailsId);
                } else {
                    EXPANDED_DETAILS.add(region.detailsId);
                }
                return true;
            }
        }
        for (LinkRegion region : ACTIVE_LINKS) {
            if (mouseX >= region.x && mouseX <= region.x + region.width
                    && mouseY >= region.y && mouseY <= region.y + region.height) {
                Util.getOperatingSystem().open(region.url);
                return true;
            }
        }
        return false;
    }

    public static List<Line> wrap(String markdown, TextRenderer textRenderer, int maxWidth) {
        List<Line> lines = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            lines.add(new Line("", 0, 0xFFD6DEE8, 0, Accent.NONE));
            return lines;
        }

        markdown = preprocessHtml(markdown);

        List<String> sourceLines = List.of(markdown.replace("\r", "").split("\n"));
        boolean inCodeFence = false;
        boolean inDetails = false;
        for (int i = 0; i < sourceLines.size(); i++) {
            String paragraph = sourceLines.get(i);
            if (paragraph.isEmpty()) {
                lines.add(new Line("", 0, 0xFFD6DEE8, 0, Accent.NONE));
                continue;
            }

            DetailsBlock detailsBlock = parseDetailsBlock(sourceLines, i, textRenderer, maxWidth);
            if (detailsBlock != null) {
                lines.add(detailsBlock.summaryLine());
                if (EXPANDED_DETAILS.contains(detailsBlock.detailsId())) {
                    lines.addAll(detailsBlock.bodyLines());
                }
                i = detailsBlock.endIndex();
                continue;
            }

            if (!inCodeFence && isTableStart(sourceLines, i)) {
                i = renderTableBlock(lines, sourceLines, i, maxWidth);
                continue;
            }

            Accent accent = Accent.NONE;
            int indent = 0;
            int color = 0xFFD6DEE8;
            int accentColor = 0;
            String content = paragraph;

            Matcher imageLineMatcher = Pattern.compile("^!\\[(.*?)]\\((.+?)\\)\\s*$").matcher(content.trim());
            Matcher imageTagMatcher = IMG_TAG_PATTERN.matcher(content.trim());
            Matcher anchorImageMatcher = ANCHOR_IMAGE_PATTERN.matcher(content.trim());
            String tokenizedImage = decodeImageToken(content.trim());
            int leadingSpaces = countLeadingSpaces(content);
            boolean indentedCode = !inCodeFence && (content.startsWith("\t") || leadingSpaces >= 4);
            if (leadingSpaces >= 2 && !indentedCode) {
                indent = Math.min(18, (leadingSpaces / 2) * 4);
                content = content.substring(Math.min(content.length(), leadingSpaces));
            }

            if (tokenizedImage != null) {
                accent = Accent.IMAGE;
                content = resizeImagePayload(tokenizedImage, maxWidth - indent);
            } else if (anchorImageMatcher.matches()) {
                accent = Accent.IMAGE;
                String anchorUrl = anchorImageMatcher.group(1);
                String imageTag = anchorImageMatcher.group(2);
                Matcher nestedImageMatcher = IMG_TAG_PATTERN.matcher(imageTag);
                if (nestedImageMatcher.find()) {
                    String imageUrl = nestedImageMatcher.group(1);
                    String altText = nestedImageMatcher.group(2) == null ? "" : nestedImageMatcher.group(2);
                    int imageWidth = Math.min(extractHtmlImageWidth(imageTag, maxWidth - indent), Math.max(64, maxWidth - indent));
                    content = encodeImagePayload(altText, imageUrl, imageWidth, anchorUrl);
                }
            } else if (imageLineMatcher.matches()) {
                accent = Accent.IMAGE;
                content = encodeImagePayload(imageLineMatcher.group(1), imageLineMatcher.group(2), Math.max(64, maxWidth - indent), "");
            } else if (imageTagMatcher.find()) {
                accent = Accent.IMAGE;
                content = encodeImagePayload(
                        imageTagMatcher.group(2) == null ? "" : imageTagMatcher.group(2),
                        imageTagMatcher.group(1),
                        Math.min(extractHtmlImageWidth(content, maxWidth - indent), Math.max(64, maxWidth - indent)),
                        ""
                );
            } else if (content.equalsIgnoreCase("<details>")) {
                inDetails = true;
                continue;
            } else if (content.equalsIgnoreCase("</details>")) {
                inDetails = false;
                continue;
            } else if (content.startsWith("```")) {
                inCodeFence = !inCodeFence;
                accent = Accent.CODE;
                color = 0xFFE7D6B4;
                accentColor = 0x8A705D3E;
                content = content.substring(3).trim();
                if (content.isEmpty()) {
                    content = inCodeFence ? "code" : "";
                }
            } else if (inCodeFence || indentedCode) {
                accent = Accent.CODE;
                color = 0xFFE7D6B4;
                accentColor = 0x8A705D3E;
                if (indentedCode) {
                    content = content.startsWith("\t") ? content.substring(1) : content.substring(Math.min(content.length(), 4));
                }
            } else if (content.equals("---") || content.equals("***")) {
                accent = Accent.RULE;
                color = 0xFFB8C4D2;
                accentColor = 0x8A708091;
                content = "────────────────";
            } else if (content.startsWith("###### ")) {
                accent = Accent.HEADING;
                color = 0xFFC4CFDB;
                accentColor = 0x8A536476;
                content = "h6|||" + content.substring(7);
            } else if (content.startsWith("##### ")) {
                accent = Accent.HEADING;
                color = 0xFFD8E0E8;
                accentColor = 0x8A566A7D;
                content = "h5|||" + content.substring(6);
            } else if (content.startsWith("#### ")) {
                accent = Accent.HEADING;
                color = 0xFFE4EAF0;
                accentColor = 0x8A5A7087;
                content = "h4|||" + content.substring(5);
            } else if (content.startsWith("### ")) {
                accent = Accent.HEADING;
                color = 0xFFF0F3F7;
                accentColor = 0x8A5B738D;
                content = "h3|||" + content.substring(4);
            } else if (content.startsWith("## ")) {
                accent = Accent.HEADING;
                color = 0xFFF0F3F7;
                accentColor = 0x8A607B98;
                content = "h2|||" + content.substring(3);
            } else if (content.startsWith("# ")) {
                accent = Accent.HEADING;
                color = 0xFFF4F6F9;
                accentColor = 0x8A6E8CAB;
                content = "h1|||" + content.substring(2);
            } else if (content.startsWith("> ")) {
                accent = Accent.QUOTE;
                indent = 8;
                color = 0xFFD7DEE7;
                accentColor = 0x8A6F8297;
                content = content.substring(2);
            } else if (content.startsWith("- [ ] ") || content.startsWith("- [x] ") || content.startsWith("- [X] ")) {
                accent = Accent.TASK;
                indent = 10;
                color = 0xFFD6DEE8;
                accentColor = 0x8A768A9C;
                content = content.startsWith("- [ ] ") ? "[ ] " + content.substring(6) : "[✔] " + content.substring(6);
            } else if (content.startsWith("- ") || content.startsWith("* ")) {
                accent = Accent.BULLET;
                indent = 10;
                color = 0xFFD6DEE8;
                accentColor = 0x8A768A9C;
                content = "• " + content.substring(2);
            } else {
                Matcher ordered = ORDERED_LIST.matcher(content);
                if (ordered.matches()) {
                    accent = Accent.BULLET;
                    indent = 10;
                    color = 0xFFD6DEE8;
                    accentColor = 0x8A768A9C;
                    content = ordered.group(1) + " " + ordered.group(2);
                }
            }

            if (inDetails && accent != Accent.CODE && accent != Accent.IMAGE) {
                indent += 8;
            }

            if (accent == Accent.IMAGE) {
                lines.add(new Line(content, List.of(), indent, color, accentColor, accent, content));
                continue;
            }

            String headingLevel = "";
            if (accent == Accent.HEADING && content.contains("|||")) {
                int split = content.indexOf("|||");
                headingLevel = content.substring(0, split);
                content = content.substring(split + 3);
            }
            String normalized = normalizeBlockText(content);
            List<InlineSpan> spans = parseInlineSpans(normalized, color, accent == Accent.CODE);
            List<Line> wrapped = wrapSpans(spans, textRenderer, Math.max(20, maxWidth - indent - (accent == Accent.HEADING ? 6 : 0)), indent, color, accentColor, accent);
            if (accent == Accent.HEADING && !headingLevel.isBlank() && !wrapped.isEmpty()) {
                Line first = wrapped.get(0);
                wrapped.set(0, new Line(first.rawText(), first.spans(), first.indent(), first.color(), first.accentColor(), first.accent(), headingLevel));
            }
            lines.addAll(wrapped);
        }
        return lines;
    }

    private static String normalizeBlockText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content;
        normalized = normalized.replace("<details>", "").replace("</details>", "");
        normalized = normalized.replaceAll("<iframe.*?>", "[Video: click to open]");
        normalized = normalized.replace("</iframe>", "");
        normalized = normalized.replaceAll("^\\|\\s*", "");
        normalized = normalized.replaceAll("\\s*\\|$", "");
        normalized = normalized.replace('|', '│');
        normalized = normalized.replace("<kbd>", "[").replace("</kbd>", "]");
        normalized = normalized.replaceAll("(?i)</?picture[^>]*>", "");
        normalized = normalized.replaceAll("(?i)</?source[^>]*>", "");
        normalized = normalized.replaceAll("(?i)</?map[^>]*>", "");
        normalized = normalized.replaceAll("(?i)</?input[^>]*>", "");
        normalized = normalized.replace(BREAK_TOKEN, "\n");
        normalized = normalized.replace(UNDERLINE_TOKEN_OPEN, "");
        normalized = normalized.replace(UNDERLINE_TOKEN_CLOSE, "");
        normalized = normalized.replaceAll("(?i)<br\\s*/?>", "");
        normalized = normalized.replaceAll("(?i)<hr\\s*/?>", "────────────────");
        normalized = normalized.replaceAll("(?i)</?p>", "");
        normalized = normalized.replaceAll("(?i)</?(div|span|section|article|strong|em|b|i|u|ul|ol|li|h[1-6])[^>]*>", "");
        return normalized;
    }

    private static String preprocessHtml(String markdown) {
        String normalized = markdown.replaceAll("(?i)<br\\s*/?>", BREAK_TOKEN);
        normalized = normalized.replaceAll("(?i)<hr\\s*/?>", "\n---\n");
        normalized = normalized.replaceAll("(?i)</?(div|section|article)[^>]*>", "");
        normalized = replaceHeadingTags(normalized);
        normalized = replaceParagraphTags(normalized);
        normalized = normalized.replaceAll("(?i)<ul[^>]*>", "\n");
        normalized = normalized.replaceAll("(?i)</ul>", "\n");
        normalized = normalized.replaceAll("(?i)<ol[^>]*>", "\n");
        normalized = normalized.replaceAll("(?i)</ol>", "\n");
        normalized = replaceListItems(normalized);
        normalized = replaceStrongTags(normalized);
        normalized = replaceBoldTags(normalized);
        normalized = replaceEmphasisTags(normalized);
        normalized = replaceUnderlineTags(normalized);
        normalized = replaceStrikeTags(normalized);
        normalized = replaceSpanTags(normalized);
        Matcher anchorImageMatcher = ANCHOR_IMAGE_PATTERN.matcher(normalized);
        StringBuffer imageBuffer = new StringBuffer();
        while (anchorImageMatcher.find()) {
            String href = anchorImageMatcher.group(1) == null ? "" : anchorImageMatcher.group(1);
            String imgTag = anchorImageMatcher.group(2) == null ? "" : anchorImageMatcher.group(2);
            Matcher imgMatcher = IMG_TAG_PATTERN.matcher(imgTag);
            if (imgMatcher.find()) {
                String imageUrl = imgMatcher.group(1) == null ? "" : imgMatcher.group(1);
                String alt = imgMatcher.group(2) == null ? "" : imgMatcher.group(2);
                String replacement = encodeImageToken(alt, imageUrl, extractHtmlImageWidth(imgTag, 220), href);
                anchorImageMatcher.appendReplacement(imageBuffer, Matcher.quoteReplacement(replacement));
            }
        }
        anchorImageMatcher.appendTail(imageBuffer);
        normalized = imageBuffer.toString();
        Matcher iframeMatcher = IFRAME_PATTERN.matcher(normalized);
        StringBuffer iframeBuffer = new StringBuffer();
        while (iframeMatcher.find()) {
            String url = iframeMatcher.group(1) == null ? "" : iframeMatcher.group(1);
            iframeMatcher.appendReplacement(iframeBuffer, Matcher.quoteReplacement("[Video: click to open](" + url + ")"));
        }
        iframeMatcher.appendTail(iframeBuffer);
        normalized = iframeBuffer.toString();
        Matcher linkMatcher = LINK_TAG_PATTERN.matcher(normalized);
        StringBuffer buffer = new StringBuffer();
        while (linkMatcher.find()) {
            String url = linkMatcher.group(1) == null ? "" : linkMatcher.group(1);
            String label = linkMatcher.group(2) == null || linkMatcher.group(2).isBlank() ? url : linkMatcher.group(2).replaceAll("<[^>]+>", "");
            linkMatcher.appendReplacement(buffer, Matcher.quoteReplacement("[" + label + "](" + url + ")"));
        }
        linkMatcher.appendTail(buffer);
        normalized = buffer.toString();
        normalized = normalized.replaceAll("(?i)<summary>", "<summary>");
        normalized = normalized.replaceAll("(?i)</summary>", "</summary>");
        normalized = normalized.replaceAll("(?i)<details>", "<details>");
        normalized = normalized.replaceAll("(?i)</details>", "</details>");
        return normalized;
    }

    private static String replaceParagraphTags(String source) {
        Matcher matcher = PARAGRAPH_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String attributes = matcher.group(1) == null ? "" : matcher.group(1).toLowerCase();
            String body = normalizeInlineHtmlText(matcher.group(2));
            String prefix = "\n\n";
            String suffix = "\n\n";
            if (attributes.contains("align=\"center\"") || attributes.contains("text-align:center") || attributes.contains("text-align: center")) {
                body = body.isBlank() ? body : body;
            } else if (attributes.contains("align=\"right\"") || attributes.contains("text-align:right") || attributes.contains("text-align: right")) {
                body = body.isBlank() ? body : body;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(prefix + body + suffix));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceHeadingTags(String source) {
        Matcher matcher = HEADING_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            int level = Integer.parseInt(matcher.group(1));
            String body = normalizeInlineHtmlText(matcher.group(2));
            String prefix = "#".repeat(Math.max(1, Math.min(6, level)));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("\n" + prefix + " " + body + "\n"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceListItems(String source) {
        Matcher matcher = LIST_ITEM_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String attributes = matcher.group(1) == null ? "" : matcher.group(1);
            String body = normalizeInlineHtmlText(matcher.group(2));
            String prefix = attributes.toLowerCase().contains("checked") ? "- [x] " : "- ";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("\n" + prefix + body + "\n"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceStrongTags(String source) {
        Matcher matcher = STRONG_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String attrs = matcher.group(1) == null ? "" : matcher.group(1).toLowerCase();
            String body = normalizeInlineHtmlText(matcher.group(2));
            String replacement = attrs.contains("bbc") ? "**" + body + "**" : "**" + body + "**";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceBoldTags(String source) {
        Matcher matcher = BOLD_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String body = normalizeInlineHtmlText(matcher.group(2));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("**" + body + "**"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceEmphasisTags(String source) {
        Matcher matcher = EMPHASIS_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String body = normalizeInlineHtmlText(matcher.group(3));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("*" + body + "*"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceUnderlineTags(String source) {
        Matcher matcher = UNDERLINE_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String body = normalizeInlineHtmlText(matcher.group(2));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(UNDERLINE_TOKEN_OPEN + body + UNDERLINE_TOKEN_CLOSE));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceStrikeTags(String source) {
        Matcher matcher = STRIKE_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String body = normalizeInlineHtmlText(matcher.group(3));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("~~" + body + "~~"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceSpanTags(String source) {
        Matcher matcher = SPAN_TAG_PATTERN.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String attrs = matcher.group(1) == null ? "" : matcher.group(1).toLowerCase();
            String body = normalizeInlineHtmlText(matcher.group(2));
            String replacement = body;
            boolean underline = attrs.contains("underline") || attrs.contains("text-decoration: underline");
            boolean bold = attrs.contains("font-weight:bold") || attrs.contains("font-weight: bold") || attrs.contains("bbc");
            boolean italic = attrs.contains("font-style:italic") || attrs.contains("font-style: italic");
            boolean strike = attrs.contains("line-through") || attrs.contains("strikethrough");
            if (underline) {
                replacement = UNDERLINE_TOKEN_OPEN + replacement + UNDERLINE_TOKEN_CLOSE;
            }
            if (strike) {
                replacement = "~~" + replacement + "~~";
            }
            if (italic) {
                replacement = "*" + replacement + "*";
            }
            if (bold) {
                replacement = "**" + replacement + "**";
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String normalizeInlineHtmlText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = body.replace(BREAK_TOKEN, "\n");
        normalized = normalized.replaceAll("(?i)<br\\s*/?>", "\n");
        normalized = normalized.replaceAll("(?i)</?p[^>]*>", "");
        normalized = normalized.replaceAll("(?i)</?(div|section|article|ul|ol)[^>]*>", "");
        normalized = normalized.replaceAll("(?i)&nbsp;", " ");
        return normalized.trim();
    }

    private static int countLeadingSpaces(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static List<InlineSpan> parseInlineSpans(String content, int defaultColor, boolean codeMode) {
        List<InlineSpan> spans = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            spans.add(new InlineSpan("", defaultColor, false, false, false, false, codeMode, null, null));
            return spans;
        }
        int index = 0;
        boolean bold = false;
        boolean italic = false;
        boolean strike = false;
        boolean underline = false;
        while (index < content.length()) {
            if (!codeMode && content.startsWith(UNDERLINE_TOKEN_OPEN, index)) {
                underline = true;
                index += UNDERLINE_TOKEN_OPEN.length();
                continue;
            }
            if (!codeMode && content.startsWith(UNDERLINE_TOKEN_CLOSE, index)) {
                underline = false;
                index += UNDERLINE_TOKEN_CLOSE.length();
                continue;
            }
            if (!codeMode && content.startsWith("**", index)) {
                bold = !bold;
                index += 2;
                continue;
            }
            if (!codeMode && content.startsWith("__", index)) {
                bold = !bold;
                index += 2;
                continue;
            }
            if (!codeMode && content.startsWith("*", index)) {
                italic = !italic;
                index += 1;
                continue;
            }
            if (!codeMode && content.startsWith("_", index)) {
                italic = !italic;
                index += 1;
                continue;
            }
            if (!codeMode && content.startsWith("~~", index)) {
                strike = !strike;
                index += 2;
                continue;
            }
            if (!codeMode && content.startsWith("`", index)) {
                int close = content.indexOf('`', index + 1);
                if (close > index + 1) {
                    String code = content.substring(index + 1, close);
                    spans.add(new InlineSpan(code, 0xFFE7D6B4, false, false, false, false, true, null, null));
                    index = close + 1;
                    continue;
                }
            }
            if (!codeMode && content.startsWith("![", index)) {
                int mid = content.indexOf("](", index);
                int end = mid >= 0 ? content.indexOf(')', mid + 2) : -1;
                if (mid > index + 1 && end > mid) {
                    String alt = content.substring(index + 2, mid);
                    String url = content.substring(mid + 2, end);
                    String payload = encodeImagePayload(alt, url, 18, "");
                    spans.add(new InlineSpan("\u25A0", defaultColor, false, false, false, false, false, null, payload));
                    index = end + 1;
                    continue;
                }
            }
            if (!codeMode && content.startsWith("[", index)) {
                int mid = content.indexOf("](", index);
                int end = mid >= 0 ? content.indexOf(')', mid + 2) : -1;
                if (mid > index && end > mid) {
                    String label = content.substring(index + 1, mid);
                    String url = content.substring(mid + 2, end);
                    spans.add(new InlineSpan(label, 0xFF8FC5FF, bold, italic, true, strike, false, url, null));
                    index = end + 1;
                    continue;
                }
            }
            if (!codeMode && content.startsWith("<http", index)) {
                int end = content.indexOf('>', index);
                if (end > index) {
                    String url = content.substring(index + 1, end);
                    spans.add(new InlineSpan(url, 0xFF8FC5FF, false, false, true, false, false, url, null));
                    index = end + 1;
                    continue;
                }
            }
            Matcher rawUrl = RAW_URL_PATTERN.matcher(content.substring(index));
            if (!codeMode && rawUrl.lookingAt()) {
                String url = rawUrl.group(1);
                spans.add(new InlineSpan(url, 0xFF8FC5FF, bold, italic, true, strike, false, url, null));
                index += url.length();
                continue;
            }
            int next = findNextMarker(content, index, codeMode);
            String piece = content.substring(index, next);
            if (!piece.isEmpty()) {
                spans.add(new InlineSpan(piece, defaultColor, bold, italic, underline, strike, codeMode, null, null));
            }
            index = next;
        }
        if (spans.isEmpty()) {
            spans.add(new InlineSpan("", defaultColor, bold, italic, underline, strike, codeMode, null, null));
        }
        return spans;
    }

    private static int findNextMarker(String content, int start, boolean codeMode) {
        int next = content.length();
        String[] markers = codeMode ? new String[0] : new String[]{"![", UNDERLINE_TOKEN_OPEN, UNDERLINE_TOKEN_CLOSE, "**", "__", "~~", "*", "_", "`", "[", "<http", "http://", "https://"};
        for (String marker : markers) {
            int candidate = content.indexOf(marker, start);
            if (candidate >= 0 && candidate < next) {
                next = candidate;
            }
        }
        return Math.max(start + 1, next);
    }

    private static List<Line> wrapSpans(List<InlineSpan> spans, TextRenderer textRenderer, int maxWidth, int indent, int color, int accentColor, Accent accent) {
        if (accent == Accent.CODE) {
            return List.of(new Line(joinText(spans), List.copyOf(spans), indent, color, accentColor, accent, ""));
        }
        List<Line> lines = new ArrayList<>();
        List<InlineSpan> current = new ArrayList<>();
        int currentWidth = 0;
        boolean firstLine = true;
        for (InlineSpan span : spans) {
            if (span.imagePayload() != null && !span.imagePayload().isBlank()) {
                int imageWidth = inlineImageWidth(span.imagePayload());
                if (currentWidth + imageWidth > maxWidth && !current.isEmpty()) {
                    lines.add(new Line(joinText(current), List.copyOf(current), indent, color, firstLine ? accentColor : 0, firstLine ? accent : Accent.NONE, ""));
                    current = new ArrayList<>();
                    currentWidth = 0;
                    firstLine = false;
                }
                current.add(span);
                currentWidth += imageWidth;
                continue;
            }
            List<String> tokens = wordWrapTokens(span.text());
            for (String token : tokens) {
                if (token.isEmpty()) {
                    continue;
                }
                String remaining = current.isEmpty() ? stripLeadingSpaces(token) : token;
                while (!remaining.isEmpty()) {
                    InlineSpan tokenSpan = new InlineSpan(remaining, span.color(), span.bold(), span.italic(), span.underline(), span.strikethrough(), span.code(), span.linkTarget(), null);
                    int tokenWidth = spanWidth(textRenderer, tokenSpan);
                    if (currentWidth + tokenWidth <= maxWidth || current.isEmpty()) {
                        if (tokenWidth <= maxWidth || !current.isEmpty()) {
                            current.add(tokenSpan);
                            currentWidth += tokenWidth;
                            remaining = "";
                            continue;
                        }
                    }
                    if (!current.isEmpty()) {
                        lines.add(new Line(joinText(current), List.copyOf(current), indent, color, firstLine ? accentColor : 0, firstLine ? accent : Accent.NONE, ""));
                        current = new ArrayList<>();
                        currentWidth = 0;
                        firstLine = false;
                        remaining = stripLeadingSpaces(remaining);
                        continue;
                    }
                    int fit = fitCharacters(remaining, textRenderer, maxWidth);
                    if (fit <= 0) {
                        fit = 1;
                    }
                    String piece = remaining.substring(0, fit);
                    InlineSpan pieceSpan = new InlineSpan(piece, span.color(), span.bold(), span.italic(), span.underline(), span.strikethrough(), span.code(), span.linkTarget(), null);
                    current.add(pieceSpan);
                    currentWidth += spanWidth(textRenderer, pieceSpan);
                    remaining = remaining.substring(fit);
                    if (!remaining.isEmpty()) {
                        lines.add(new Line(joinText(current), List.copyOf(current), indent, color, firstLine ? accentColor : 0, firstLine ? accent : Accent.NONE, ""));
                        current = new ArrayList<>();
                        currentWidth = 0;
                        firstLine = false;
                        remaining = stripLeadingSpaces(remaining);
                    }
                }
            }
        }
        if (current.isEmpty()) {
            current.add(new InlineSpan("", color, false, false, false, false, false, null, null));
        }
        lines.add(new Line(joinText(current), List.copyOf(current), indent, color, firstLine ? accentColor : 0, firstLine ? accent : Accent.NONE, ""));
        return lines;
    }

    private static List<String> wordWrapTokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher matcher = Pattern.compile("\\S+\\s*|\\s+").matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static String stripLeadingSpaces(String text) {
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index == 0 ? text : text.substring(index);
    }

    private static int fitCharacters(String text, TextRenderer textRenderer, int maxWidth) {
        if (text.isEmpty()) {
            return 0;
        }
        int fit = 0;
        while (fit < text.length() && textRenderer.getWidth(text.substring(0, fit + 1)) <= maxWidth) {
            fit++;
        }
        return fit;
    }

    private static int spanWidth(TextRenderer textRenderer, InlineSpan span) {
        if (span.imagePayload() != null && !span.imagePayload().isBlank()) {
            return inlineImageWidth(span.imagePayload());
        }
        return textRenderer.getWidth(asText(span.text(), span));
    }

    private static int inlineImageWidth(String payload) {
        String[] parts = payload.split("\\|\\|\\|", 4);
        int requested = 18;
        if (parts.length > 2) {
            try {
                requested = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                requested = 18;
            }
        }
        return Math.max(12, Math.min(requested, 18)) + 2;
    }

    private static String joinText(List<InlineSpan> spans) {
        StringBuilder builder = new StringBuilder();
        for (InlineSpan span : spans) {
            builder.append(span.text());
        }
        return builder.toString();
    }

    private static boolean isTableStart(List<String> sourceLines, int lineIndex) {
        if (lineIndex + 1 >= sourceLines.size()) {
            return false;
        }
        String current = sourceLines.get(lineIndex).trim();
        String next = sourceLines.get(lineIndex + 1).trim();
        return current.contains("|") && TABLE_RULE_PATTERN.matcher(next).matches();
    }

    private static int renderTableBlock(List<Line> lines, List<String> sourceLines, int startIndex, int maxWidth) {
        List<List<String>> rows = new ArrayList<>();
        int index = startIndex;
        while (index < sourceLines.size()) {
            String current = sourceLines.get(index);
            if (!current.contains("|")) {
                break;
            }
            if (index == startIndex + 1 && TABLE_RULE_PATTERN.matcher(current.trim()).matches()) {
                index++;
                continue;
            }
            rows.add(splitTableRow(current));
            index++;
            if (index < sourceLines.size() && !sourceLines.get(index).contains("|")) {
                break;
            }
        }
        if (rows.isEmpty()) {
            return startIndex;
        }
        String tableId = "table_" + Integer.toHexString((String.join("\n", sourceLines.subList(startIndex, index))).hashCode()) + "_" + startIndex;
        TABLE_CACHE.put(tableId, new TableBlock(rows, true, Math.max(140, Math.min(maxWidth, maxCellRenderWidth(rows, startIndex)))));
        lines.add(new Line("[table]", List.of(), 0, 0xFFD6DEE8, 0, Accent.TABLE_BLOCK, tableId));
        return index - 1;
    }

    private static int maxCellRenderWidth(List<List<String>> rows, int fallbackSeed) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        return Math.max(180, Math.min(520, columns * 108 + Math.max(0, columns - 1) * 2 + (fallbackSeed % 7)));
    }

    private static List<String> splitTableRow(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    public static int renderLine(DrawContext context, TextRenderer textRenderer, Line line, int x, int y) {
        int drawX = x + line.indent();
        switch (line.accent()) {
            case HEADING -> {
                int ruleWidth = switch (line.payload()) {
                    case "h1" -> Math.max(54, textRenderer.getWidth(line.rawText()) + 10);
                    case "h2" -> Math.max(46, textRenderer.getWidth(line.rawText()) + 6);
                    case "h3" -> 32;
                    case "h4" -> 22;
                    default -> 0;
                };
                if (ruleWidth > 0) {
                    context.fill(x, y + textRenderer.fontHeight + 1, x + ruleWidth, y + textRenderer.fontHeight + 2, line.accentColor());
                }
            }
            case QUOTE -> context.fill(x, y, x + 2, y + textRenderer.fontHeight, line.accentColor());
            case BULLET -> {
            }
            case CODE -> {
                context.fill(x, y - 1, x + textRenderer.getWidth(line.rawText()) + 8, y + textRenderer.fontHeight + 1, 0x352A2D33);
                drawX += 4;
            }
            case RULE -> {
                context.drawText(textRenderer, line.rawText(), x, y, line.color(), false);
                return textRenderer.fontHeight + 4;
            }
            case TASK -> {
                context.fill(x + 1, y + 3, x + 8, y + 10, 0x2835414E);
                context.drawBorder(x + 1, y + 3, 7, 7, line.accentColor());
            }
            case IMAGE -> {
                return renderImageLine(context, textRenderer, line, x, y);
            }
            case DETAILS_SUMMARY -> {
                return renderDetailsSummaryLine(context, textRenderer, line, x, y);
            }
            case TABLE_BLOCK -> {
                return renderTableBlockLine(context, textRenderer, line, x, y);
            }
            case NONE -> {
            }
        }

        if (line.accent() == Accent.HEADING) {
            String headingText = joinText(line.spans());
            int headingColor = switch (line.payload()) {
                case "h1" -> 0xFFF6F7FA;
                case "h2" -> 0xFFF1F4F8;
                case "h3" -> 0xFFE8EEF5;
                case "h4" -> 0xFFDDE6EF;
                case "h5" -> 0xFFD4DEE9;
                case "h6" -> 0xFFC2CDD9;
                default -> line.color();
            };
            MutableText heading = Text.literal(headingText).setStyle(Style.EMPTY.withColor(headingColor).withBold(!"h6".equals(line.payload())));
            context.drawText(textRenderer, heading, drawX, y, headingColor, true);
            return switch (line.payload()) {
                case "h1" -> textRenderer.fontHeight + 8;
                case "h2" -> textRenderer.fontHeight + 6;
                case "h3" -> textRenderer.fontHeight + 5;
                default -> textRenderer.fontHeight + 4;
            };
        }

        int currentX = drawX;
        for (InlineSpan span : line.spans()) {
            if (span.imagePayload() != null && !span.imagePayload().isBlank()) {
                int width = renderInlineImageSpan(context, textRenderer, span, currentX, y);
                currentX += width;
                continue;
            }
            MutableText text = asText(span.text(), span);
            int width = textRenderer.getWidth(text);
            int headingColor = switch (line.payload()) {
                case "h1" -> 0xFFF6F7FA;
                case "h2" -> 0xFFF1F4F8;
                case "h3" -> 0xFFE8EEF5;
                case "h4" -> 0xFFDDE6EF;
                case "h5" -> 0xFFD4DEE9;
                case "h6" -> 0xFFC2CDD9;
                default -> span.color();
            };
            MutableText drawText = line.accent() == Accent.HEADING
                    ? Text.literal(span.text()).setStyle(text.getStyle().withColor(headingColor).withBold(!"h6".equals(line.payload())))
                    : text;
            context.drawText(textRenderer, drawText, currentX, y, headingColor, line.accent() == Accent.HEADING);
            if (span.linkTarget() != null && !span.linkTarget().isBlank()) {
                ACTIVE_LINKS.add(new LinkRegion(currentX, y, width, textRenderer.fontHeight, span.linkTarget(), span.text()));
            }
            currentX += width;
        }
        return textRenderer.fontHeight + 2;
    }

    private static int renderInlineImageSpan(DrawContext context, TextRenderer textRenderer, InlineSpan span, int x, int y) {
        String[] parts = span.imagePayload().split("\\|\\|\\|", 4);
        String alt = parts.length > 0 ? parts[0] : "";
        String url = parts.length > 1 ? parts[1] : "";
        String linkTarget = parts.length > 3 ? parts[3] : "";
        int target = inlineImageWidth(span.imagePayload()) - 2;
        ImagePreview preview = getOrQueueImage(url);
        int box = Math.max(12, Math.min(target, 18));
        int frameY = y + Math.max(0, (textRenderer.fontHeight - box) / 2);
        context.fill(x, frameY, x + box, frameY + box, 0x24303943);
        context.drawBorder(x, frameY, box, box, 0x8A627282);
        if (preview != null && preview.width() > 0 && preview.height() > 0) {
            float scale = Math.min((box - 2) / (float) preview.width(), (box - 2) / (float) preview.height());
            int drawWidth = Math.max(1, Math.round(preview.width() * scale));
            int drawHeight = Math.max(1, Math.round(preview.height() * scale));
            int imageX = x + ((box - drawWidth) / 2);
            int imageY = frameY + ((box - drawHeight) / 2);
            context.getMatrices().push();
            context.getMatrices().translate(imageX, imageY, 0);
            context.getMatrices().scale(scale, scale, 1.0F);
            context.drawTexture(preview.textureId(), 0, 0, 0, 0, preview.width(), preview.height(), preview.width(), preview.height());
            context.getMatrices().pop();
        } else if (!alt.isBlank()) {
            String glyph = alt.substring(0, 1).toUpperCase();
            context.drawText(textRenderer, glyph, x + 4, frameY + 2, 0xFFD6DEE8, false);
        }
        if (linkTarget != null && !linkTarget.isBlank()) {
            ACTIVE_LINKS.add(new LinkRegion(x, frameY, box, box, linkTarget, alt.isBlank() ? url : alt));
        } else if (url != null && !url.isBlank()) {
            ACTIVE_LINKS.add(new LinkRegion(x, frameY, box, box, url, alt.isBlank() ? url : alt));
        }
        return box + 2;
    }

    private static MutableText asText(String content, InlineSpan span) {
        Style style = Style.EMPTY.withColor(span.color());
        if (span.bold()) style = style.withBold(true);
        if (span.italic()) style = style.withItalic(true);
        if (span.underline()) style = style.withUnderline(true);
        if (span.strikethrough()) style = style.withStrikethrough(true);
        if (span.code()) style = style.withColor(0xFFE7D6B4);
        return Text.literal(content).setStyle(style);
    }

    private static int renderImageLine(DrawContext context, TextRenderer textRenderer, Line line, int x, int y) {
        String[] parts = line.payload().split("\\|\\|\\|", 4);
        String alt = parts.length > 0 ? parts[0] : "";
        String url = parts.length > 1 ? parts[1] : "";
        int maxWidth = 180;
        if (parts.length > 2) {
            try {
                maxWidth = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                maxWidth = 180;
            }
        }
        String linkTarget = parts.length > 3 ? parts[3] : "";
        ImagePreview preview = getOrQueueImage(url);
        int frameWidth = Math.max(96, Math.min(maxWidth, 220));
        int drawWidth = Math.max(80, Math.min(frameWidth - 8, 212));
        int drawHeight = 58;
        if (preview != null && preview.width() > 0 && preview.height() > 0) {
            float widthScale = (frameWidth - 8) / (float) preview.width();
            float heightScale = 132.0F / preview.height();
            float scale = Math.min(Math.min(widthScale, heightScale), 1.0F);
            drawWidth = Math.max(32, Math.round(preview.width() * scale));
            drawHeight = Math.max(18, Math.round(preview.height() * scale));
            int frameHeight = drawHeight + 8;
            context.fill(x, y, x + frameWidth, y + frameHeight, 0x24303943);
            context.drawBorder(x, y, frameWidth, frameHeight, 0x8A627282);
            int imageX = x + Math.max(4, (frameWidth - drawWidth) / 2);
            context.getMatrices().push();
            context.getMatrices().translate(imageX, y + 4, 0);
            context.getMatrices().scale(scale, scale, 1.0F);
            context.drawTexture(preview.textureId(), 0, 0, 0, 0, preview.width(), preview.height(), preview.width(), preview.height());
            context.getMatrices().pop();
            if (linkTarget != null && !linkTarget.isBlank()) {
                ACTIVE_LINKS.add(new LinkRegion(x, y, frameWidth, frameHeight, linkTarget, alt == null || alt.isBlank() ? linkTarget : alt));
            }
            if (alt != null && !alt.isBlank()) {
                context.drawText(textRenderer, textRenderer.trimToWidth(alt, frameWidth - 10), x + 6, y + frameHeight + 2, 0xFFBFCAD8, false);
                return frameHeight + textRenderer.fontHeight + 4;
            }
            return frameHeight + 2;
        }

        int placeholderWidth = Math.max(96, Math.min(frameWidth, 220));
        context.fill(x, y, x + placeholderWidth, y + 34, 0x24303943);
        context.drawBorder(x, y, placeholderWidth, 34, 0x8A627282);
        String label = alt == null || alt.isBlank() ? "Image preview" : alt;
        context.drawText(textRenderer, textRenderer.trimToWidth(label, placeholderWidth - 10), x + 5, y + 6, 0xFFD6DEE8, false);
        context.drawText(textRenderer, IMAGE_LOADING.contains(url) ? "Loading image..." : "Preview unavailable", x + 5, y + 18, 0xFF96A9BC, false);
        if (linkTarget != null && !linkTarget.isBlank()) {
            ACTIVE_LINKS.add(new LinkRegion(x, y, placeholderWidth, 34, linkTarget, label));
        }
        return 38;
    }

    private static String encodeImagePayload(String alt, String url, int maxWidth, String linkTarget) {
        return (alt == null ? "" : alt) + "|||" + (url == null ? "" : url) + "|||" + Math.max(64, maxWidth) + "|||" + (linkTarget == null ? "" : linkTarget);
    }

    private static int renderTableBlockLine(DrawContext context, TextRenderer textRenderer, Line line, int x, int y) {
        TableBlock table = TABLE_CACHE.get(line.payload());
        if (table == null || table.rows().isEmpty()) {
            return textRenderer.fontHeight + 2;
        }
        int columnCount = table.rows().stream().mapToInt(List::size).max().orElse(1);
        int gap = columnCount >= 6 ? 1 : 2;
        int totalWidth = Math.max(140, table.maxWidth());
        int[] columnWidths = computeTableColumnWidths(table, textRenderer, totalWidth, gap);
        int currentY = y;
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            List<String> row = table.rows().get(rowIndex);
            boolean header = table.hasHeader() && rowIndex == 0;
            List<List<Line>> renderedCells = new ArrayList<>();
            int rowHeight = textRenderer.fontHeight + 8;
            for (int cellIndex = 0; cellIndex < columnCount; cellIndex++) {
                String cellText = cellIndex < row.size() ? row.get(cellIndex) : "";
                int cellWidth = columnWidths[cellIndex];
                List<Line> cellLines = wrap(cellText, textRenderer, Math.max(16, cellWidth - 8));
                renderedCells.add(cellLines);
                int cellHeight = 6;
                for (Line cellLine : cellLines) {
                    cellHeight += measureLineHeight(cellLine, textRenderer, Math.max(16, cellWidth - 8));
                }
                rowHeight = Math.max(rowHeight, cellHeight);
            }
            int cursorX = x;
            for (int cellIndex = 0; cellIndex < columnCount; cellIndex++) {
                int cellWidth = columnWidths[cellIndex];
                int background = header ? 0x30425364 : 0x20303943;
                int border = header ? 0x8A7A8DA6 : 0x8A5A6A78;
                context.fill(cursorX, currentY, cursorX + cellWidth, currentY + rowHeight, background);
                context.drawBorder(cursorX, currentY, cellWidth, rowHeight, border);
                int cellY = currentY + 3;
                for (Line cellLine : renderedCells.get(cellIndex)) {
                    Line drawLine = header && cellLine.accent() == Accent.NONE
                            ? new Line(cellLine.rawText(), cellLine.spans(), cellLine.indent(), 0xFFF1F4F8, cellLine.accentColor(), cellLine.accent(), cellLine.payload())
                            : cellLine;
                    cellY += renderLine(context, textRenderer, drawLine, cursorX + 4, cellY);
                }
                cursorX += cellWidth + gap;
            }
            currentY += rowHeight + gap;
        }
        return currentY - y;
    }

    private static int[] computeTableColumnWidths(TableBlock table, TextRenderer textRenderer, int totalWidth, int gap) {
        int columnCount = table.rows().stream().mapToInt(List::size).max().orElse(1);
        int availableWidth = Math.max(80, totalWidth - ((columnCount - 1) * gap));
        int minWidth = columnCount >= 8 ? Math.max(12, Math.min(48, Math.max(1, availableWidth / columnCount))) : columnCount >= 6 ? 20 : columnCount >= 4 ? 26 : 34;
        int[] desired = new int[columnCount];
        int desiredTotal = 0;
        for (List<String> row : table.rows()) {
            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.size() ? row.get(i) : "";
                desired[i] = Math.max(desired[i], estimateTableCellWidth(cell, textRenderer, minWidth));
            }
        }
        for (int i = 0; i < columnCount; i++) {
            desired[i] = Math.max(minWidth, desired[i]);
            desiredTotal += desired[i];
        }
        int[] widths = new int[columnCount];
        if (desiredTotal <= availableWidth) {
            System.arraycopy(desired, 0, widths, 0, columnCount);
            widths[columnCount - 1] += availableWidth - desiredTotal;
            return widths;
        }
        float scale = availableWidth / (float) desiredTotal;
        int used = 0;
        for (int i = 0; i < columnCount; i++) {
            widths[i] = Math.max(minWidth, Math.round(desired[i] * scale));
            used += widths[i];
        }
        while (used > availableWidth) {
            boolean changed = false;
            for (int i = widths.length - 1; i >= 0 && used > availableWidth; i--) {
                if (widths[i] > minWidth) {
                    widths[i]--;
                    used--;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        while (used < availableWidth) {
            widths[used % columnCount]++;
            used++;
        }
        return widths;
    }

    private static int estimateTableCellWidth(String cell, TextRenderer textRenderer, int minWidth) {
        if (cell == null || cell.isBlank()) {
            return minWidth;
        }
        String payload = decodeImageToken(cell.trim());
        if (payload != null) {
            String[] parts = payload.split("\\|\\|\\|", 4);
            int requested = parts.length > 2 ? parseIntOrDefault(parts[2], 96) : 96;
            return Math.max(minWidth, Math.min(requested + 8, 140));
        }
        String normalized = normalizeBlockText(cell);
        int rawWidth = textRenderer.getWidth(textRenderer.trimToWidth(normalized, 120));
        return Math.max(minWidth, Math.min(rawWidth + 10, 140));
    }

    private static int renderDetailsSummaryLine(DrawContext context, TextRenderer textRenderer, Line line, int x, int y) {
        int drawX = x + line.indent();
        boolean expanded = line.payload() != null && EXPANDED_DETAILS.contains(line.payload());
        int width = Math.max(100, textRenderer.getWidth(line.rawText()) + 24);
        context.fill(x, y, x + width, y + textRenderer.fontHeight + 6, 0x22333D49);
        context.drawBorder(x, y, width, textRenderer.fontHeight + 6, 0x8A6A7684);
        context.drawText(textRenderer, expanded ? "-" : "+", drawX + 4, y + 3, 0xFFE5EDF6, false);
        int currentX = drawX + 14;
        for (InlineSpan span : line.spans()) {
            MutableText text = asText(span.text(), span);
            int spanWidth = textRenderer.getWidth(text);
            context.drawText(textRenderer, text, currentX, y + 3, span.color(), false);
            currentX += spanWidth;
        }
        ACTIVE_DETAILS.add(new DetailsRegion(x, y, width, textRenderer.fontHeight + 6, line.payload(), expanded, line.rawText()));
        return textRenderer.fontHeight + 8;
    }

    private record DetailsBlock(String detailsId, Line summaryLine, List<Line> bodyLines, int endIndex) {
    }

    private static DetailsBlock parseDetailsBlock(List<String> sourceLines, int startIndex, TextRenderer textRenderer, int maxWidth) {
        String current = sourceLines.get(startIndex).trim();
        if (!current.toLowerCase().contains("<details")) {
            return null;
        }
        int summaryIndex = startIndex;
        String summaryText = null;
        Matcher inlineSummary = DETAILS_PATTERN.matcher(current);
        if (inlineSummary.find()) {
            summaryText = inlineSummary.group(1).trim();
        } else {
            for (int i = startIndex + 1; i < sourceLines.size(); i++) {
                Matcher matcher = DETAILS_PATTERN.matcher(sourceLines.get(i));
                if (matcher.find()) {
                    summaryText = matcher.group(1).trim();
                    summaryIndex = i;
                    break;
                }
            }
        }
        if (summaryText == null || summaryText.isBlank()) {
            summaryText = "Details";
        }
        int endIndex = summaryIndex;
        List<String> body = new ArrayList<>();
        boolean bodyStarted = false;
        for (int i = summaryIndex; i < sourceLines.size(); i++) {
            String line = sourceLines.get(i);
            if (!bodyStarted) {
                int closingSummary = line.toLowerCase().indexOf("</summary>");
                if (closingSummary >= 0) {
                    String after = line.substring(closingSummary + "</summary>".length()).trim();
                    if (!after.isEmpty() && !after.equalsIgnoreCase("</details>")) {
                        body.add(after);
                    }
                    bodyStarted = true;
                }
                continue;
            }
            if (line.trim().equalsIgnoreCase("</details>")) {
                endIndex = i;
                break;
            }
            body.add(line);
            endIndex = i;
        }
        String detailsId = Integer.toHexString((summaryText + "\n" + String.join("\n", body)).hashCode());
        List<InlineSpan> summarySpans = parseInlineSpans(summaryText, 0xFFE4EAF0, false);
        Line summaryLine = new Line(summaryText, summarySpans, 0, 0xFFE4EAF0, 0x8A5A7087, Accent.DETAILS_SUMMARY, detailsId);
        List<Line> bodyLines = new ArrayList<>();
        if (!body.isEmpty()) {
            for (Line bodyLine : wrap(String.join("\n", body), textRenderer, Math.max(40, maxWidth - 10))) {
                bodyLines.add(new Line(bodyLine.rawText(), bodyLine.spans(), bodyLine.indent() + 10, bodyLine.color(), bodyLine.accentColor(), bodyLine.accent(), bodyLine.payload()));
            }
        }
        return new DetailsBlock(detailsId, summaryLine, bodyLines, Math.max(endIndex, startIndex));
    }

    private static int measureLineHeight(Line line, TextRenderer textRenderer, int widthHint) {
        return switch (line.accent()) {
            case HEADING -> textRenderer.fontHeight + 4;
            case QUOTE, BULLET, TASK, NONE -> textRenderer.fontHeight + 2;
            case CODE -> textRenderer.fontHeight + 2;
            case RULE -> textRenderer.fontHeight + 4;
            case DETAILS_SUMMARY -> textRenderer.fontHeight + 8;
            case IMAGE -> measureImageHeight(line);
            case TABLE_BLOCK -> textRenderer.fontHeight + 8;
        };
    }

    private static int measureImageHeight(Line line) {
        String[] parts = line.payload().split("\\|\\|\\|", 4);
        String url = parts.length > 1 ? parts[1] : "";
        int maxWidth = 180;
        if (parts.length > 2) {
            try {
                maxWidth = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                maxWidth = 180;
            }
        }
        ImagePreview preview = IMAGE_CACHE.get(url);
        if (preview != null && preview.width() > 0 && preview.height() > 0) {
            int frameWidth = Math.max(96, Math.min(maxWidth, 220));
            float widthScale = (frameWidth - 8) / (float) preview.width();
            float heightScale = 132.0F / preview.height();
            float scale = Math.min(Math.min(widthScale, heightScale), 1.0F);
            int drawHeight = Math.max(18, Math.round(preview.height() * scale));
            int frameHeight = drawHeight + 8;
            return frameHeight + ((parts.length > 0 && parts[0] != null && !parts[0].isBlank()) ? MinecraftClient.getInstance().textRenderer.fontHeight + 4 : 2);
        }
        return 38;
    }

    private static int extractHtmlImageWidth(String rawTag, int fallback) {
        Matcher matcher = WIDTH_ATTRIBUTE_PATTERN.matcher(rawTag);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return Math.max(64, fallback);
            }
        }
        return Math.max(64, fallback);
    }

    private static String encodeImageToken(String alt, String url, int width, String linkTarget) {
        String payload = (alt == null ? "" : alt) + "\n"
                + (url == null ? "" : url) + "\n"
                + width + "\n"
                + (linkTarget == null ? "" : linkTarget);
        return "[[KOIL_IMAGE:" + Base64.getEncoder().encodeToString(payload.getBytes()) + "]]";
    }

    private static String decodeImageToken(String raw) {
        if (raw == null || !raw.startsWith("[[KOIL_IMAGE:") || !raw.endsWith("]]")) {
            return null;
        }
        String inner = raw.substring("[[KOIL_IMAGE:".length(), raw.length() - 2);
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(inner));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String[] parts = decoded.split("\n", 4);
        if (parts.length < 4) {
            return null;
        }
        String alt = parts[0];
        String url = parts[1];
        String width = parts[2];
        String linkTarget = parts[3];
        return encodeImagePayload(alt, url, Math.max(64, parseIntOrDefault(width, 220)), linkTarget);
    }

    private static String resizeImagePayload(String payload, int maxWidth) {
        String[] parts = payload.split("\\|\\|\\|", 4);
        String alt = parts.length > 0 ? parts[0] : "";
        String url = parts.length > 1 ? parts[1] : "";
        int currentWidth = parts.length > 2 ? parseIntOrDefault(parts[2], 220) : 220;
        String link = parts.length > 3 ? parts[3] : "";
        return encodeImagePayload(alt, url, Math.max(64, Math.min(currentWidth, maxWidth)), link);
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static ImagePreview getOrQueueImage(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        ImagePreview cached = IMAGE_CACHE.get(rawUrl);
        if (cached != null) {
            return cached;
        }
        if (!IMAGE_LOADING.add(rawUrl)) {
            return null;
        }
        Thread thread = new Thread(() -> {
            try (InputStream stream = new URL(URI.create(rawUrl).toString()).openStream()) {
                NativeImageBackedTexture texture = loadImage(stream.readAllBytes(), rawUrl);
                if (texture == null) {
                    return;
                }
                Identifier textureId = new Identifier("koil", "markdown_image/" + Integer.toHexString(rawUrl.hashCode()));
                MinecraftClient.getInstance().execute(() -> {
                    MinecraftClient.getInstance().getTextureManager().registerTexture(textureId, texture);
                    int width = texture.getImage() == null ? 0 : texture.getImage().getWidth();
                    int height = texture.getImage() == null ? 0 : texture.getImage().getHeight();
                    IMAGE_CACHE.put(rawUrl, new ImagePreview(textureId, texture, width, height));
                });
            } catch (Exception exception) {
                SUBLOGGER.logW("Markdown-Preview thread", "Failed to decode preview image " + rawUrl + ": " + exception.getMessage());
            } finally {
                IMAGE_LOADING.remove(rawUrl);
            }
        }, "koil-markdown-image-" + Integer.toHexString(rawUrl.hashCode()));
        thread.setDaemon(true);
        thread.start();
        return null;
    }
}
