package com.spirit.koil.api.console;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.chat.input.CommandSuggestionFuturePoller;
import com.spirit.koil.api.minecraft.MinecraftRegistrySuggestions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConsoleInputSuggestionService {
    private static final int MAX_SUGGESTIONS = 8;

    private ConsoleInputSuggestionService() {
    }

    public static List<ConsoleInputSuggestion> suggestions(String input, ConsoleChannel channel, boolean automationMode) {
        String prefix = input == null ? "" : input.trim();
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        Map<String, ConsoleInputSuggestion> suggestions = new LinkedHashMap<>();

        for (String entry : ConsoleCommandHistory.snapshot()) {
            maybeAdd(suggestions, lowerPrefix, entry, "HIST", "recent command", 1);
        }

        for (ConsoleInputSuggestion suggestion : liveMinecraftChatSuggestions(prefix)) {
            maybeAdd(suggestions, lowerPrefix, suggestion.value(), suggestion.kind(), suggestion.detail(), suggestion.priority());
        }

        for (ConsoleInputSuggestion suggestion : liveMinecraftRegistryCommandSuggestions(prefix)) {
            maybeAdd(suggestions, lowerPrefix, suggestion.value(), suggestion.kind(), suggestion.detail(), suggestion.priority(), prefix, searchTerms(suggestion));
        }

        for (ConsoleInputSuggestion suggestion : liveAutomationSuggestions()) {
            maybeAdd(suggestions, lowerPrefix, suggestion.value(), suggestion.kind(), suggestion.detail(), suggestion.priority());
        }

        for (ConsoleInputSuggestion suggestion : liveMinecraftCommandSuggestions()) {
            maybeAdd(suggestions, lowerPrefix, suggestion.value(), suggestion.kind(), suggestion.detail(), suggestion.priority());
        }

        for (ConsoleInputSuggestion suggestion : baseSuggestions(channel, automationMode)) {
            maybeAdd(suggestions, lowerPrefix, suggestion.value(), suggestion.kind(), suggestion.detail(), suggestion.priority());
        }

        List<ConsoleInputSuggestion> ordered = new ArrayList<>(suggestions.values());
        ordered.sort((left, right) -> {
            int priority = Integer.compare(left.priority(), right.priority());
            if (priority != 0) {
                return priority;
            }
            int length = Integer.compare(left.value().length(), right.value().length());
            if (length != 0) {
                return length;
            }
            return left.value().compareToIgnoreCase(right.value());
        });
        if (ordered.size() > MAX_SUGGESTIONS) {
            return new ArrayList<>(ordered.subList(0, MAX_SUGGESTIONS));
        }
        return ordered;
    }

    private static List<ConsoleInputSuggestion> baseSuggestions(ConsoleChannel channel, boolean automationMode) {
        List<ConsoleInputSuggestion> suggestions = new ArrayList<>();
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate", "open automation console", 10));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate on", "enable automation mode", 12));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate off", "disable automation mode", 12));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate exit", "leave automation mode", 12));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate chat", "open compact automation prompt", 13));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate yolo", "session-only approval-free registered capabilities", 13));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate deep", "toggle bounded deep thinking for complex objectives", 13));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate deep on", "enable bounded deep thinking", 14));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate deep off", "disable bounded deep thinking", 14));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate improve", "generate automation improvement files", 13));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/proof", "run automation proof suite", 14));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/proof cache", "run automation cache proof", 14));
        suggestions.add(new ConsoleInputSuggestion("TASK", "walk straight 4 blocks", "automation prompt", 22));
        suggestions.add(new ConsoleInputSuggestion("TASK", "walk 10 blocks then jump", "model-planned sequence", 22));
        suggestions.add(new ConsoleInputSuggestion("TASK", "kill 3 creepers", "automation prompt", 22));
        suggestions.add(new ConsoleInputSuggestion("TASK", "open the nearest chest", "automation prompt", 22));
        suggestions.add(new ConsoleInputSuggestion("TASK", "eat them", "reference phrase", 24));
        suggestions.add(new ConsoleInputSuggestion("RAW", "/time set day", "minecraft command", 26));
        suggestions.add(new ConsoleInputSuggestion("RAW", "/give @s minecraft:apple 1", "minecraft command", 26));
        if (channel != ConsoleChannel.CLI && !automationMode) {
            suggestions.add(new ConsoleInputSuggestion("CHAT", "hello world", "chat message", 30));
        }
        return suggestions;
    }

    private static List<ConsoleInputSuggestion> liveAutomationSuggestions() {
        List<ConsoleInputSuggestion> suggestions = new ArrayList<>();
        try {
            KtlCompilerService.CompiledAssets assets = KtlCompilerService.getInstance().assets();
            for (String templateId : assets.templates.keySet()) {
                suggestions.add(new ConsoleInputSuggestion("KTL", templateId + ".ktl", "registered automation task", 28));
            }
            Path root = Path.of("koil/automation");
            if (Files.isDirectory(root)) {
                Files.walk(root)
                        .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ktl"))
                        .sorted()
                        .forEach(path -> suggestions.add(new ConsoleInputSuggestion("FILE", root.relativize(path).toString().replace('\\', '/'), "automation source file", 32)));
            }
        } catch (Exception ignored) {
        }
        return suggestions;
    }

    private static List<ConsoleInputSuggestion> liveMinecraftCommandSuggestions() {
        List<ConsoleInputSuggestion> suggestions = new ArrayList<>();
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null && client.getNetworkHandler().getCommandDispatcher() != null) {
                client.getNetworkHandler().getCommandDispatcher().getRoot().getChildren().forEach(node ->
                        suggestions.add(new ConsoleInputSuggestion("MC", "/" + node.getName(), "minecraft command", 20))
                );
            }
        } catch (Exception ignored) {
        }
        return suggestions;
    }

    private static List<ConsoleInputSuggestion> liveMinecraftChatSuggestions(String input) {
        List<ConsoleInputSuggestion> suggestions = new ArrayList<>();
        if (input == null || input.isBlank() || !input.startsWith("/")) {
            return suggestions;
        }
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getNetworkHandler() == null || client.getNetworkHandler().getCommandDispatcher() == null) {
                return suggestions;
            }
            String commandInput = input.substring(1);
            ParseResults<CommandSource> parse = client.getNetworkHandler().getCommandDispatcher().parse(commandInput, client.getNetworkHandler().getCommandSource());
            var future = client.getNetworkHandler().getCommandDispatcher().getCompletionSuggestions(parse);
            Suggestions brigadierSuggestions = CommandSuggestionFuturePoller.readyOrNull(future);
            if (brigadierSuggestions == null) {
                return suggestions;
            }
            for (Suggestion suggestion : brigadierSuggestions.getList()) {
                String completed = "/" + suggestion.apply(commandInput);
                suggestions.add(new ConsoleInputSuggestion("MC", completed, "chat completion", 4));
            }
        } catch (Exception ignored) {
        }
        return suggestions;
    }

    private static List<ConsoleInputSuggestion> liveMinecraftRegistryCommandSuggestions(String input) {
        List<ConsoleInputSuggestion> suggestions = new ArrayList<>();
        if (input == null || input.isBlank() || !input.startsWith("/")) {
            return suggestions;
        }
        String commandInput = input.substring(1);
        String[] tokens = commandInput.split(" ", -1);
        if (tokens.length == 0) {
            return suggestions;
        }
        String command = tokens[0].toLowerCase(Locale.ROOT);
        String currentToken = tokens[tokens.length - 1];
        int argIndex = Math.max(0, tokens.length - 2);
        String prefixBeforeCurrent = input.substring(0, Math.max(0, input.length() - currentToken.length()));

        if ("summon".equals(command) && argIndex == 0) {
            addRegistryArgumentSuggestions(suggestions, prefixBeforeCurrent, currentToken, Registries.ENTITY_TYPE.getIds(), "MC", "entity id", 3);
            return suggestions;
        }
        if (("give".equals(command) || "clear".equals(command)) && argIndex == 1) {
            addRegistryArgumentSuggestions(suggestions, prefixBeforeCurrent, currentToken, Registries.ITEM.getIds(), "MC", "item id", 3);
            return suggestions;
        }
        if ("setblock".equals(command) && argIndex == 3) {
            addRegistryArgumentSuggestions(suggestions, prefixBeforeCurrent, currentToken, Registries.BLOCK.getIds(), "MC", "block id", 3);
            return suggestions;
        }
        if ("fill".equals(command) && argIndex == 6) {
            addRegistryArgumentSuggestions(suggestions, prefixBeforeCurrent, currentToken, Registries.BLOCK.getIds(), "MC", "block id", 3);
            return suggestions;
        }
        if ("effect".equals(command) && tokens.length >= 2) {
            String subcommand = tokens[1].toLowerCase(Locale.ROOT);
            if (("give".equals(subcommand) || "clear".equals(subcommand)) && argIndex == 2) {
                addRegistryArgumentSuggestions(suggestions, prefixBeforeCurrent, currentToken, Registries.STATUS_EFFECT.getIds(), "MC", "effect id", 3);
                return suggestions;
            }
        }
        if ("playsound".equals(command) && argIndex == 0) {
            addRegistryArgumentSuggestions(suggestions, prefixBeforeCurrent, currentToken, Registries.SOUND_EVENT.getIds(), "MC", "sound id", 3);
        }
        return suggestions;
    }

    private static void addRegistryArgumentSuggestions(List<ConsoleInputSuggestion> suggestions, String prefixBeforeCurrent, String currentToken, Iterable<Identifier> ids, String kind, String detail, int priority) {
        MinecraftRegistrySuggestions.SearchResult result = MinecraftRegistrySuggestions.search(
                ids,
                currentToken,
                MAX_SUGGESTIONS
        );
        for (MinecraftRegistrySuggestions.Candidate candidate : result.candidates()) {
            Identifier id = candidate.identifier();
            String value = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
            suggestions.add(new ConsoleInputSuggestion(
                    kind,
                    prefixBeforeCurrent + value,
                    detail,
                    priority + candidate.score()
            ));
        }
    }

    private static List<String> searchTerms(ConsoleInputSuggestion suggestion) {
        List<String> terms = new ArrayList<>();
        if (suggestion.value() != null && !suggestion.value().isBlank()) {
            terms.add(suggestion.value());
        }
        if (suggestion.detail() != null && !suggestion.detail().isBlank()) {
            terms.add(suggestion.detail());
        }
        return terms;
    }

    private static void maybeAdd(Map<String, ConsoleInputSuggestion> suggestions, String lowerPrefix, String value, String kind, String detail, int priority) {
        maybeAdd(suggestions, lowerPrefix, value, kind, detail, priority, lowerPrefix, List.of(value));
    }

    private static void maybeAdd(Map<String, ConsoleInputSuggestion> suggestions, String lowerPrefix, String value, String kind, String detail, int priority, String rawInput, List<String> searchTerms) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!matchesInput(rawInput, lowerPrefix, normalized, searchTerms)) {
            return;
        }
        ConsoleInputSuggestion suggestion = new ConsoleInputSuggestion(kind, value, detail, priority);
        suggestions.putIfAbsent(normalized, suggestion);
    }

    private static boolean matchesInput(String rawInput, String lowerPrefix, String normalizedValue, List<String> searchTerms) {
        if (lowerPrefix.isEmpty()) {
            return true;
        }
        if (normalizedValue.startsWith(lowerPrefix) || normalizedValue.contains(lowerPrefix)) {
            return true;
        }
        String currentToken = lastToken(rawInput);
        if (currentToken.isEmpty()) {
            return false;
        }
        for (String term : searchTerms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String lowered = term.toLowerCase(Locale.ROOT);
            if (lowered.startsWith(currentToken) || lowered.contains(currentToken)) {
                return true;
            }
        }
        return false;
    }

    private static String lastToken(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        int split = Math.max(rawInput.lastIndexOf(' '), rawInput.lastIndexOf('\t'));
        return rawInput.substring(split + 1).toLowerCase(Locale.ROOT);
    }

    public record ConsoleInputSuggestion(String kind, String value, String detail, int priority) {
    }
}
