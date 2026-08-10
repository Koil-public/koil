package com.spirit.koil.api.chat;

import com.spirit.koil.api.model.format.RichChatModelFormattingContract;
import com.spirit.koil.api.chat.sync.RichChatSyncNetwork;
import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.List;

/** Deterministic parser and model-contract proof; no client is required. */
public final class RichChatSectionFormattingProof {
    private RichChatSectionFormattingProof() {
    }

    public static void main(String[] args) {
        proveLegacyColors();
        proveStylesAndReset();
        proveHexForms();
        proveRichFeatureComposition();
        proveDraftControlsAndDynamicStructure();
        proveFallbackAndContinuation();
        provePreWrapStylesAndSpeech();
        proveIncompleteDraftVisibility();
        proveSenderEchoReconstruction();
        proveSharedStatusVisual();
        proveSafeStructuralContinuation();
        proveSyncRoundTrip();
        proveModelContract();
        System.out.println("Rich Chat section-formatting proof passed.");
    }

    private static void proveLegacyColors() {
        for (char code : "0123456789abcdef".toCharArray()) {
            List<RichChatSectionFormatting.Segment> segments = RichChatSectionFormatting.parse(
                    "\u00a7" + code + "color",
                    Style.EMPTY
            );
            require(segments.size() == 1 && "color".equals(segments.get(0).text()),
                    "legacy color code remained visible: " + code);
            require(segments.get(0).style().getColor() != null,
                    "legacy color was not applied: " + code);
        }
    }

    private static void proveStylesAndReset() {
        List<RichChatSectionFormatting.Segment> styles = RichChatSectionFormatting.parse(
                "\u00a7kK\u00a7lL\u00a7mM\u00a7nN\u00a7oO\u00a7rR",
                Style.EMPTY
        );
        require(styles.stream().anyMatch(segment -> segment.style().isObfuscated()), "§k was not applied");
        require(styles.stream().anyMatch(segment -> segment.style().isBold()), "§l was not applied");
        require(styles.stream().anyMatch(segment -> segment.style().isStrikethrough()), "§m was not applied");
        require(styles.stream().anyMatch(segment -> segment.style().isUnderlined()), "§n was not applied");
        require(styles.stream().anyMatch(segment -> segment.style().isItalic()), "§o was not applied");
        RichChatSectionFormatting.Segment reset = styles.get(styles.size() - 1);
        require("R".equals(reset.text()) && !reset.style().isBold() && reset.style().getColor() == null,
                "§r did not restore the base style");

        List<RichChatSectionFormatting.Segment> colorReset = RichChatSectionFormatting.parse(
                "\u00a7lbold\u00a7cred",
                Style.EMPTY
        );
        require(colorReset.get(0).style().isBold() && !colorReset.get(1).style().isBold(),
                "a Java color code did not reset earlier formatting");
    }

    private static void proveHexForms() {
        List<RichChatSectionFormatting.Segment> compact = RichChatSectionFormatting.parse(
                "\u00a7#04280Dforest",
                Style.EMPTY
        );
        require(compact.size() == 1 && compact.get(0).style().getColor().getRgb() == 0x04280D,
                "Koil compact hex color failed");
        List<RichChatSectionFormatting.Segment> expanded = RichChatSectionFormatting.parse(
                "\u00a7x\u00a70\u00a74\u00a72\u00a78\u00a70\u00a7Dforest",
                Style.EMPTY
        );
        require(expanded.size() == 1 && expanded.get(0).style().getColor().getRgb() == 0x04280D,
                "expanded Minecraft hex color failed");
    }

    private static void proveRichFeatureComposition() {
        Style heading = Style.EMPTY.withBold(true).withUnderline(true);
        List<RichChatSectionFormatting.Segment> headingSegments = RichChatSectionFormatting.parse(
                "\u00a73OH \u00a7fYEA \u00a7kyes \u00a70WOW.",
                heading
        );
        require(headingSegments.size() == 4,
                "section formatting did not split the combined heading example into semantic runs");
        require(headingSegments.stream().allMatch(segment -> segment.style().isBold()
                        && segment.style().isUnderlined()),
                "section colors erased the surrounding Rich Chat heading style");
        require(headingSegments.get(0).style().getColor().getRgb() == 0x00AAAA
                        && headingSegments.get(1).style().getColor().getRgb() == 0xFFFFFF
                        && headingSegments.get(2).style().isObfuscated()
                        && headingSegments.get(3).style().getColor().getRgb() == 0x000000
                        && !headingSegments.get(3).style().isObfuscated(),
                "combined legacy colors/styles did not retain Minecraft semantics");
        Style hiddenSpoiler = Style.EMPTY.withObfuscated(true);
        require(RichChatSectionFormatting.parse("\u00a73OH", hiddenSpoiler).get(0).style().isObfuscated(),
                "section color exposed an unrevealed Rich Chat spoiler");
        require("\u00a73".equals(RichChatSectionFormatting.continuationPrefix(
                        "\u00a73OH **bold**"
                )), "section color did not survive a Rich Chat inline boundary/wrap");
    }

    private static void proveDraftControlsAndDynamicStructure() {
        List<RichChatSectionFormatting.Segment> draft = RichChatSectionFormatting.parseDraft(
                "\u00a73OH",
                Style.EMPTY
        );
        require("\u00a73OH".equals(draft.stream()
                        .map(RichChatSectionFormatting.Segment::text)
                        .collect(java.util.stream.Collectors.joining())),
                "draft formatting did not keep the section control visibly represented");
        require("OH".equals(RichChatSectionFormatting.parse("\u00a73OH", Style.EMPTY).stream()
                        .map(RichChatSectionFormatting.Segment::text)
                        .collect(java.util.stream.Collectors.joining())),
                "final formatting exposed a section control token");
        List<RichChatSectionFormatting.Segment> compactHexDraft =
                RichChatSectionFormatting.parseDraft("\u00a7#04280DForest", Style.EMPTY);
        require("\u00a7Forest".equals(compactHexDraft.stream()
                        .map(RichChatSectionFormatting.Segment::text)
                        .collect(java.util.stream.Collectors.joining())),
                "compact hex draft exposed #RRGGBB instead of only its section indicator");

        RichChatStructuralStyleRegistry.HeadingStyle original =
                RichChatStructuralStyleRegistry.heading(1);
        try {
            RichChatStructuralStyleRegistry.registerHeading(
                    1,
                    new RichChatStructuralStyleRegistry.HeadingStyle(
                            new RichChatStructuralStyleRegistry.TextStyle(
                                    0x22AA44,
                                    false,
                                    true,
                                    false,
                                    null,
                                    null
                            ),
                            1.5F,
                            0,
                            1
                    )
            );
            RichChatHeadingLayout.Heading changed = RichChatHeadingLayout.detect("# Dynamic");
            Style replaced = RichChatStructuralStyleRegistry.heading(1).text().apply(
                    Style.EMPTY.withBold(true).withUnderline(true).withColor(0xFFFFFF)
            );
            require(changed != null
                            && changed.scale() == 1.5F
                            && changed.yOffset() == 0
                            && changed.spacerLines() == 1
                            && RichChatStructuralStyleRegistry.heading(1).text().rgb() == 0x22AA44
                            && replaced.getColor() != null
                            && replaced.getColor().getRgb() == 0x22AA44
                            && !replaced.isBold()
                            && replaced.isItalic()
                            && !replaced.isUnderlined(),
                    "heading style/geometry remained hardcoded instead of using the registry");
        } finally {
            RichChatStructuralStyleRegistry.registerHeading(1, original);
        }
    }

    private static void proveFallbackAndContinuation() {
        String source = "\u00a7aDone \u00a7lstrong\u00a7r plain";
        require("Done strong plain".equals(RichChatSectionFormatting.stripCodes(source)),
                "network fallback retained valid control codes");
        require("\u00a7a\u00a7l".equals(RichChatSectionFormatting.continuationPrefix("\u00a7aDone \u00a7lstrong")),
                "wrapped row did not preserve active formatting");
        require("bad \u00a7#GGGGGG".equals(RichChatSectionFormatting.stripCodes("bad \u00a7#GGGGGG")),
                "invalid hex input was silently removed");
        require(!RichChatSectionFormatting.containsFormatting("plain text"),
                "plain text was misclassified as section formatted");
    }

    private static void provePreWrapStylesAndSpeech() {
        Text styled = RichChatSectionFormatting.styleBeforeWrapping(
                Text.literal("# \u00a7#04280DForest \u00a7lBold\u00a7r plain")
        );
        require("# Forest Bold plain".equals(styled.getString()),
                "pre-wrap conversion left compact hex digits/control source visible");
        String reconstructed = RichChatSectionFormatting.controlSource(styled.asOrderedText());
        require(reconstructed.contains("\u00a7#04280D")
                        && reconstructed.contains("Forest"),
                "ordered structural text did not retain compact hex styling for the renderer");
        Text preview = RichChatPreviewFormatter.format(
                Text.literal("> \u00a73OH \u00a7fYEA")
        );
        require("> OH YEA".equals(preview.getString())
                        && RichChatSectionFormatting.controlSource(preview.asOrderedText()).contains("\u00a7#00AAAA"),
                "model popup preview lost section styles before wrapping");

        require("Done now".equals(RichChatSectionFormatting.speechSafeText(
                        "\u00a7aDone \u00a7#04280Dnow"
                )), "voice-safe formatting spoke a legacy or compact-hex control");
        require("safe".equals(RichChatSectionFormatting.speechSafeText("\u00a7zsafe"))
                        && "word".equals(RichChatSectionFormatting.speechSafeText("\u00a7#0428word"))
                        && "forest".equals(RichChatSectionFormatting.speechSafeText(
                        "\u00a7x\u00a70\u00a74\u00a72\u00a78\u00a70\u00a7Dforest"
                )),
                "voice-safe formatting retained an unknown/partial control payload");
    }

    private static void proveIncompleteDraftVisibility() {
        require(RichChatSectionFormatting.containsSectionSign("\u00a7")
                        && RichChatSectionFormatting.containsSectionSign("/say \u00a7")
                        && RichChatSectionFormatting.containsSectionSign("\u00a7#0428"),
                "section-sign paste routing ignored an incomplete chat/command draft");
        require(RichChatAttachmentRenderer.containsLiveFormatting("\u00a7"),
                "a lone section sign did not enter the live Rich Chat preview");
        require(RichChatAttachmentRenderer.containsLiveFormatting("\u00a7#0428"),
                "an incomplete hex code did not enter the live Rich Chat preview");
        require("\u00a7".equals(RichChatSectionFormatting.stripCodes("\u00a7")),
                "a lone section sign disappeared before its code was complete");
        require("\u00a7#0428".equals(RichChatSectionFormatting.stripCodes("\u00a7#0428")),
                "an incomplete hex code disappeared before all six digits were typed");
        require("forest".equals(RichChatSectionFormatting.stripCodes("\u00a7#04280Dforest")),
                "a complete hex control sequence remained visible");
        require("/say ".equals(RichChatSectionFormatting.networkSafeFallback("/say \u00a7"))
                        && "#0428".equals(RichChatSectionFormatting.networkSafeFallback("\u00a7#0428"))
                        && RichChatSectionFormatting.networkSafeFallback("bad \u00a7#GGGGGG")
                        .indexOf(RichChatSectionFormatting.PREFIX) < 0,
                "an incomplete/invalid section sign could reach a vanilla packet");
    }

    private static void proveModelContract() {
        String contract = RichChatModelFormattingContract.systemPrompt()
                + RichChatModelFormattingContract.automationPrompt();
        require(contract.contains("§#RRGGBB") && contract.contains("§a verified completion"),
                "model prompt omitted section/hex formatting guidance");
        require(contract.contains("Never reveal or format hidden chain-of-thought"),
                "model prompt omitted the private-reasoning boundary");
        require(contract.contains("In every substantive visible answer")
                        && contract.contains("color at least the key result or status phrase"),
                "model prompt did not require semantic color in substantive answers");
        require(contract.contains("compose with Rich Chat containers and inline formatting")
                        && contract.contains("Do not author `#` title or heading lines")
                        && contract.contains("quotes, -# subtext")
                        && contract.contains("spoilers"),
                "model prompt omitted section/Rich Chat composition guidance");
    }

    private static void proveSenderEchoReconstruction() {
        String fallback = "Green Bold normal";
        String original = "\u00a7aGreen \u00a7lBold\u00a7r normal";
        LocalMultilineChatBridge.remember(fallback, original);
        Text rewritten = LocalMultilineChatBridge.rewrite(Text.literal("<Player> " + fallback));
        require(("<Player> " + original).equals(rewritten.getString()),
                "single-line sender fallback was not restored to section-formatted rich text");

        String combinedFallback = "> OH YEA yes WOW.";
        String combinedOriginal = "> \u00a73OH \u00a7fYEA \u00a7kyes \u00a70WOW.";
        LocalMultilineChatBridge.remember(combinedFallback, combinedOriginal);
        Text combined = LocalMultilineChatBridge.rewrite(
                Text.literal("<Player> " + combinedFallback)
        );
        require(("<Player> " + combinedOriginal).equals(combined.getString()),
                "quote sender echo lost section formatting before Rich Chat rendering");
        require(RichChatAttachmentRenderer.containsLiveFormatting(combined.getString()),
                "a player-prefixed quote with section formatting bypassed the Rich Chat renderer");

        for (String structure : new String[]{"# ", "-# ", "||", "`"}) {
            String suffix = "||".equals(structure) || "`".equals(structure) ? structure : "";
            String rich = structure + "\u00a73OH" + suffix;
            require(RichChatAttachmentRenderer.containsLiveFormatting("<Player> " + rich),
                    "section formatting did not compose with Rich Chat structure: " + structure);
        }
    }

    private static void proveSharedStatusVisual() {
        Text first = SlidingStatusText.styled("Thinking", "thinking", 0x66AAFF, 0L);
        Text later = SlidingStatusText.styled(
                "Thinking",
                "thinking",
                0x66AAFF,
                SlidingStatusText.STEP_MILLIS * 5L
        );
        require("Thinking...".equals(first.getString()),
                "bottom model status did not use a contiguous three-dot animation label");
        require(!Text.Serializer.toJson(first).equals(Text.Serializer.toJson(later)),
                "top model status highlight did not move left to right");
        Text stableBase = SlidingStatusText.baseStyled("Thinking", "thinking", 0x66AAFF);
        require("Thinking...".equals(stableBase.getString())
                        && Text.Serializer.toJson(stableBase).equals(Text.Serializer.toJson(
                        SlidingStatusText.baseStyled("Thinking", "thinking", 0x66AAFF))),
                "bottom model status base glyph run was not stable");
        SlidingStatusText.HighlightWindow start = SlidingStatusText.highlightWindow(
                "Thinking", "thinking", 0L);
        SlidingStatusText.HighlightWindow entered = SlidingStatusText.highlightWindow(
                "Thinking", "thinking", SlidingStatusText.STEP_MILLIS);
        SlidingStatusText.HighlightWindow moved = SlidingStatusText.highlightWindow(
                "Thinking", "thinking", SlidingStatusText.STEP_MILLIS * 5L);
        require(start.active()
                        && start.startCharacter() == 0
                        && start.endCharacter() == 1
                        && entered.active()
                        && entered.endCharacter() - entered.startCharacter() > 1
                        && moved.endCharacter() - moved.startCharacter() > 1,
                "status animation did not enter with one glyph before expanding to its moving band");
        require(start.visibleText().equals(moved.visibleText())
                        && (start.startCharacter() != moved.startCharacter()
                        || start.endCharacter() != moved.endCharacter()),
                "bottom model popup highlight did not move over one fixed visible glyph run");
        require(first.getString().indexOf(RichChatSectionFormatting.PREFIX) < 0,
                "bottom model status exposed section/hex source text");
    }

    private static void proveSafeStructuralContinuation() {
        String continuation = RichChatStructuralContinuation.subtextPrefix("  ") + "wrapped secondary text";
        require(RichChatAttachmentRenderer.containsLiveFormatting(continuation),
                "wrapped subtext continuation lost its standard semantic prefix");
        require(continuation.indexOf(RichChatStructuralContinuation.SUBTEXT) < 0,
                "wrapped subtext continuation exposed a private-use marker glyph");
        RichChatStructuralContinuation.Subtext inherited =
                RichChatStructuralContinuation.parseSubtext("§7-# again");
        require(RichChatAttachmentRenderer.containsLiveFormatting("§7-# again"),
                "renderer did not recognize inherited formatting before subtext syntax");
        require(inherited != null
                        && inherited.leadingWhitespace().isEmpty()
                        && "§7again".equals(inherited.content())
                        && !inherited.content().contains("-#"),
                "inherited wrap styling made the subtext marker visible");
    }

    private static void proveSyncRoundTrip() {
        RichChatMessageData source = RichMessageBuilder.text("\u00a7aDone\u00a7r")
                .fallbackText("Done")
                .metadata("section_formatting", "true")
                .build();
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        RichChatSyncNetwork.write(buffer, source, List.of());
        RichChatMessageData decoded = RichChatSyncNetwork.read(buffer).message();
        require("\u00a7aDone\u00a7r".equals(decoded.rawText()),
                "rich sync did not preserve section formatting");
        require("Done".equals(decoded.fallbackText()) && !decoded.fallbackText().contains("\u00a7"),
                "rich sync fallback exposed section controls to vanilla chat");
        require("true".equals(decoded.metadata().get("section_formatting")),
                "rich sync omitted section-formatting metadata");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
