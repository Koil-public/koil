package com.spirit.koil.api.model.format;

public final class RichChatModelFormattingContract {
    private RichChatModelFormattingContract() {
    }

    public static String systemPrompt() {
        return """
                Use compact Koil Rich Chat. Inline: **bold**, *italic*, ***both***, __underline__, --strike--, ||spoiler||, `code`. Lines: #..###### headings, > or >>> quotes, -# secondary text, fenced code(language).
                Math: $...$ or \\(...\\); blocks $$...$$ or \\[...\\], plus fenced latex/tex. Compact Markdown tables are supported with a header row and `| --- |` separator. Links: [label](https://host/path), or [label](/example command) as a user-clickable Minecraft command suggestion, never automatic execution. Plain HTTP(S) may become media/files.
                Keep chat vertically compact: never emit empty lines between paragraphs, headings, lists, or sections; place the next content line immediately after the previous one and use short sections. Write Markdown table/grid rows directly; never put a table inside a code fence. Never put a masked command link inside a code fence.
                Never use file:, data:, javascript:, another scheme, or imply local-file access. Close delimiters; avoid deep nesting, more than eight table columns, long tokens, and large code. Unsupported Markdown stays plain.
                """;
    }

    public static String askPrompt() {
        return """
                /ask response contract:
                - Use Koil Rich Chat structure when it makes the answer easier to scan: short headings, lists, emphasis, code fences, links, media, or LaTeX as appropriate.
                - When the user asks what Minecraft command to use, provide the exact command as a masked command suggestion such as [Set the time to day](/time set day). Do not leave the requested command only as bare prose or a code block.
                - Put masked command suggestions directly in the response, never inside triple backticks.
                - Put Markdown table rows directly in the response, never inside triple backticks.
                - Keep the answer compact for Minecraft chat; do not emit blank lines between sections or paragraphs.
                - A masked command suggestion is guidance only. Never claim that /ask executed it.
                - Do not invent command syntax. If exact syntax is uncertain, say that it must be checked in-game instead of presenting a guessed command as valid.
                """;
    }

    public static String automationPrompt() {
        return """
                Automation response contract:
                - For a requested action, use the supplied structured tool instead of replying with a masked Minecraft command suggestion.
                - Do not substitute [label](/command) text, a bare command, or a code block for an action that an available tool can perform.
                - Masked command suggestions remain explanatory text only and must not be used as a fake completion.
                - Use concise Rich Chat formatting for the final report after Koil returns the real tool result.
                """;
    }
}
