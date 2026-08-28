package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.command.CommandOutputPresentation;
import com.spirit.koil.api.model.BinaryStorageFormatter;
import com.spirit.koil.api.model.catalog.LocalModelAutomationEligibility;
import com.spirit.koil.api.model.catalog.LocalModelCapabilityTag;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelCompatibility;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

import static com.spirit.koil.api.command.CommandOutputPresentation.Tone;

/**
 * Builds the compact, interactive model choice shown by {@code /model list}.
 * The click action only prefills chat; install and uninstall keep their normal
 * confirmation boundaries.
 */
public final class LocalModelCatalogChatRow {
    private LocalModelCatalogChatRow() {
    }

    public static Text create(
            LocalModelCatalogEntry entry,
            LocalModelCompatibility compatibility,
            boolean installed,
            boolean selected
    ) {
        String command = selectionCommand(entry, installed);
        Formatting compatibilityColor = compatibilityFormatting(compatibility);
        boolean available = entry.runnable() || LocalModelCatalog.canResolveForInstall(entry);
        MutableText row = Text.literal("• ").formatted(compatibilityColor)
                .append(CommandOutputPresentation.text(entry.displayName(), Tone.PRIMARY))
                .append(Text.literal(installed ? "  [uninstall]" : available ? "  [install]" : "  [catalog]")
                        .formatted(installed ? Formatting.RED : available ? Formatting.GREEN : Formatting.DARK_GRAY))
                .append(Text.literal("  " + statusLabel(compatibility, installed, selected))
                        .formatted(installed ? Formatting.GREEN : compatibilityColor));
        return row.styled(style -> {
            var styled = style.withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    hoverText(entry, compatibility, installed, selected, command)
            ));
            return command.isBlank() ? styled : styled.withClickEvent(
                    new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command));
        });
    }

    public static String selectionCommand(LocalModelCatalogEntry entry, boolean installed) {
        if (entry == null) return "";
        if (!installed && !entry.runnable() && !LocalModelCatalog.canResolveForInstall(entry)) return "";
        return "/model " + (installed ? "uninstall " : "install ") + entry.id();
    }

    public static String formatStorage(long bytes) {
        return BinaryStorageFormatter.format(bytes);
    }

    private static Text hoverText(
            LocalModelCatalogEntry entry,
            LocalModelCompatibility compatibility,
            boolean installed,
            boolean selected,
            String command
    ) {
        boolean chats = entry.capabilityTags().contains(LocalModelCapabilityTag.CHAT);
        boolean automationEligible = LocalModelAutomationEligibility.supportsAutomationTools(entry);
        boolean resolvable = !entry.runnable() && LocalModelCatalog.canResolveForInstall(entry);
        MutableText tooltip = CommandOutputPresentation.text(entry.displayName(), Tone.PRIMARY)
                .append(detail("Model ID", entry.id()))
                .append(detail(
                        "Intent estimate",
                        entry.complexReasoningEstimatePercent() + "% complex (relative guidance)"
                ))
                .append(detail(
                        "Chat / tools",
                        yesNo(chats) + " / " + yesNo(automationEligible)
                ))
                .append(detail(
                        "Automation",
                        automationEligible
                                ? "eligible — verified tool protocol"
                                : "unavailable — no verified tool protocol/runtime"
                ))
                .append(detail("Storage", formatStorage(entry.downloadBytes())))
                .append(detail("Parameters", entry.parameterCount()))
                .append(detail("Quantization", entry.quantization()))
                .append(detail("Context", String.format(Locale.ROOT, "%,d tokens", entry.contextTokens())))
                .append(detail(
                        "Memory",
                        formatStorage(entry.estimatedMinimumMemoryBytes()) + " minimum / "
                                + formatStorage(entry.estimatedRecommendedMemoryBytes()) + " recommended"
                ))
                .append(detail("License", entry.license()))
                .append(detail(
                        "Compatibility",
                        compatibility.label() + (compatibility.detail().isBlank() ? "" : " — " + compatibility.detail())
                ))
                .append(detail("Status", selected ? "selected and installed" : installed ? "installed" : "not installed"))
                .append(command.isBlank()
                        ? Text.literal("\nMetadata only; no compatible local runtime is currently resolvable.")
                                .formatted(Formatting.DARK_GRAY)
                        : Text.literal(resolvable
                                        ? "\nClick to resolve a verified Hugging Face GGUF and install: "
                                        : "\nClick to prefill: ")
                                .formatted(Formatting.DARK_GRAY)
                                .append(Text.literal(command).formatted(installed ? Formatting.RED : Formatting.GREEN)));
        return tooltip;
    }

    private static MutableText detail(String label, String value) {
        return Text.literal("\n" + label + ": ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(value == null ? "" : value).formatted(Formatting.WHITE));
    }

    private static String statusLabel(
            LocalModelCompatibility compatibility,
            boolean installed,
            boolean selected
    ) {
        if (selected) {
            return "Selected";
        }
        if (installed) {
            return "Installed";
        }
        return compatibility.label();
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static Formatting compatibilityFormatting(LocalModelCompatibility compatibility) {
        return switch (compatibility.level()) {
            case RECOMMENDED -> Formatting.GREEN;
            case SUPPORTED_WITH_LIMITS, UNKNOWN -> Formatting.DARK_GRAY;
            case NOT_RECOMMENDED, STORAGE_BLOCKED -> Formatting.RED;
            case UNAVAILABLE -> Formatting.DARK_GRAY;
        };
    }
}
