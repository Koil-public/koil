package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelCompatibility;
import com.spirit.koil.api.model.catalog.LocalModelCatalogView;
import com.spirit.koil.api.model.catalog.LocalModelAutomationEligibility;
import com.spirit.koil.api.command.CommandOutputPresentation;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

public final class LocalModelCatalogChatRowProof {
    private LocalModelCatalogChatRowProof() {
    }

    public static void main(String[] args) {
        LocalModelCatalogEntry entry = LocalModelCatalog.find("qwen3.5-0.8b-q4")
                .orElseThrow(() -> new IllegalStateException("proof model is missing"));
        LocalModelCompatibility compatibility = new LocalModelCompatibility(
                LocalModelCompatibility.Level.RECOMMENDED,
                "Recommended",
                "Proof compatibility detail."
        );

        require("0 b".equals(LocalModelCatalogChatRow.formatStorage(0L)), "byte unit formatting failed");
        require("unknown".equals(LocalModelCatalogChatRow.formatStorage(-1L)), "unknown storage formatting failed");
        require("1024 b".equals(LocalModelCatalogChatRow.formatStorage(1024L)), "byte-range formatting failed");
        require("1048575 b".equals(LocalModelCatalogChatRow.formatStorage((1024L * 1024L) - 1L)),
                "pre-mb threshold formatting failed");
        require("1.00 mb".equals(LocalModelCatalogChatRow.formatStorage(1024L * 1024L)), "megabyte unit formatting failed");
        require("1024 mb".equals(LocalModelCatalogChatRow.formatStorage((1024L * 1024L * 1024L) - 1L)),
                "pre-gb threshold formatting failed");
        require("1.00 gb".equals(LocalModelCatalogChatRow.formatStorage(1024L * 1024L * 1024L)), "gigabyte unit formatting failed");
        require("1.00 tb".equals(LocalModelCatalogChatRow.formatStorage(1024L * 1024L * 1024L * 1024L)), "terabyte unit formatting failed");
        require(CommandOutputPresentation.formatting(CommandOutputPresentation.Tone.PRIMARY) == net.minecraft.util.Formatting.WHITE,
                "primary command tone is not white");
        require(CommandOutputPresentation.formatting(CommandOutputPresentation.Tone.SUCCESS) == net.minecraft.util.Formatting.GREEN,
                "success command tone is not green");
        require(CommandOutputPresentation.formatting(CommandOutputPresentation.Tone.ERROR) == net.minecraft.util.Formatting.RED,
                "error command tone is not red");
        require(CommandOutputPresentation.formatting(CommandOutputPresentation.Tone.LIMITED) == net.minecraft.util.Formatting.DARK_GRAY,
                "limited command tone is not dark gray");
        require(CommandOutputPresentation.commandIndicator(
                        "Command output",
                        "Command",
                        CommandOutputPresentation.Tone.PRIMARY,
                        false
                ).indicatorColor() == (CommandOutputPresentation.COMMAND_BAR_ORANGE & 0x00FFFFFF),
                "command output indicator bar is not orange");
        require(CommandOutputPresentation.commandIndicator(
                        "Command output",
                        "Command",
                        CommandOutputPresentation.Tone.METADATA,
                        true
                ).indicatorColor() == (CommandOutputPresentation.COMMAND_BAR_ORANGE_DIM & 0x00FFFFFF),
                "dim command output indicator bar is not orange");
        Text restyledCommand = CommandOutputPresentation.restyleRow(
                Text.literal("Command result").formatted(
                        net.minecraft.util.Formatting.AQUA,
                        net.minecraft.util.Formatting.BOLD
                ),
                CommandOutputPresentation.Tone.PRIMARY
        );
        String restyledJson = Text.Serializer.toJson(restyledCommand)
                .toLowerCase(java.util.Locale.ROOT);
        require(!restyledJson.contains("\"bold\":true")
                        && !restyledJson.contains("\"color\":\"aqua\"")
                        && restyledJson.contains("\"color\":\"white\""),
                "ordinary command output did not adopt the four-color contract");

        for (LocalModelCatalogEntry catalogEntry : LocalModelCatalog.entries()) {
            Text installRow = LocalModelCatalogChatRow.create(catalogEntry, compatibility, false, false);
            verifyPalette(installRow);
            if (catalogEntry.runnable() || LocalModelCatalog.canResolveForInstall(catalogEntry)) {
                verifyInteraction(installRow, "/model install " + catalogEntry.id());
            } else {
                require(installRow.getStyle().getClickEvent() == null
                                && installRow.getString().contains("[catalog]"),
                        "metadata-only catalog row incorrectly exposed an install action");
            }
            String installHover = hoverText(installRow).getString();
            require(installHover.contains("Model ID: " + catalogEntry.id()), "hover omitted model id");
            require(installHover.contains("Intent estimate:"), "hover omitted intent estimate");
            String expectedTools = (catalogEntry.capabilityTags().contains(
                    com.spirit.koil.api.model.catalog.LocalModelCapabilityTag.CHAT) ? "yes" : "no")
                    + " / "
                    + (LocalModelAutomationEligibility.supportsAutomationTools(catalogEntry) ? "yes" : "no");
            require(installHover.contains("Chat / tools: " + expectedTools),
                    "hover misstated chat/tool support for " + catalogEntry.id());
            require(installHover.contains(LocalModelAutomationEligibility.supportsAutomationTools(catalogEntry)
                            ? "Automation: eligible — verified tool protocol"
                            : "Automation: unavailable — no verified tool protocol/runtime"),
                    "hover omitted the Automation protocol eligibility for " + catalogEntry.id());
            require(installHover.contains("Storage:"), "hover omitted storage");
            require(installHover.contains("Compatibility: Recommended"), "hover omitted compatibility");

            Text uninstallChoice = LocalModelCatalogChatRow.create(catalogEntry, compatibility, true, false);
            verifyPalette(uninstallChoice);
            verifyInteraction(uninstallChoice, "/model uninstall " + catalogEntry.id());
        }

        Text uninstallRow = LocalModelCatalogChatRow.create(entry, compatibility, true, true);
        verifyInteraction(uninstallRow, "/model uninstall " + entry.id());
        require(uninstallRow.getString().contains("[uninstall]"), "installed row omitted uninstall action");
        require(hoverText(uninstallRow).getString().contains("Status: selected and installed"),
                "selected model status was not preserved");
        require(hoverText(uninstallRow).getString().contains(
                        "Chat / tools: yes / "
                                + (LocalModelAutomationEligibility.supportsAutomationTools(entry) ? "yes" : "no")),
                "selected model misstated protocol-backed tool availability");

        LocalModelCatalogView.Page firstPage = LocalModelCatalogView.page(LocalModelCatalog.entries(), 1, 10);
        LocalModelCatalogView.Page lastPage = LocalModelCatalogView.page(
                LocalModelCatalog.entries(), Integer.MAX_VALUE, 10);
        require(firstPage.entries().size() == 10 && firstPage.pageCount() > 1
                        && lastPage.page() == lastPage.pageCount() && !lastPage.entries().isEmpty(),
                "catalog pagination did not keep every large-roster page reachable");
        require(LocalModelCatalogView.search(LocalModelCatalog.entries(), "qwen thinking").stream()
                        .allMatch(model -> (model.displayName() + " " + model.canonical().modelType())
                                .toLowerCase(java.util.Locale.ROOT).contains("qwen")),
                "catalog search did not match canonical metadata");
        require(!LocalModelCatalogView.search(LocalModelCatalog.entries(), "qwen3.8").isEmpty(),
                "catalog search returned no current Qwen3.8 entries");

        System.out.println("Local model catalog chat-row proof passed.");
    }

    private static void verifyPalette(Text row) {
        String json = Text.Serializer.toJson(row).toLowerCase(java.util.Locale.ROOT);
        require(!json.contains("\"bold\":true"), "catalog row or hover used bold styling");
        require(!json.contains("\"color\":\"aqua\""), "catalog row or hover used cyan/aqua styling");
        require(!json.contains("\"color\":\"yellow\""), "catalog row or hover used yellow styling");
        require(!json.contains("\"color\":\"gold\""), "catalog row or hover used gold styling");
        require(!json.contains("\"color\":\"gray\""), "catalog row or hover used gray instead of dark gray");
    }

    private static void verifyInteraction(Text row, String expectedCommand) {
        ClickEvent click = row.getStyle().getClickEvent();
        require(click != null, "catalog row omitted click action");
        require(click.getAction() == ClickEvent.Action.SUGGEST_COMMAND, "catalog row click would execute");
        require(expectedCommand.equals(click.getValue()), "catalog row prefilled the wrong command");
        require(row.getStyle().getHoverEvent() != null, "catalog row omitted hover details");
    }

    private static Text hoverText(Text row) {
        HoverEvent hover = row.getStyle().getHoverEvent();
        Text value = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_TEXT);
        if (value == null) {
            throw new IllegalStateException("catalog row hover was not SHOW_TEXT");
        }
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
