package com.spirit.koil.api.console;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
        suggestions.add(new ConsoleInputSuggestion("CMD", "/automate improve", "generate automation improvement files", 13));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/proof", "run automation proof suite", 14));
        suggestions.add(new ConsoleInputSuggestion("CMD", "/proof cache", "run automation cache proof", 14));
        suggestions.add(new ConsoleInputSuggestion("RUN", "/run walk straight 4 blocks", "run phrase-search", 18));
        suggestions.add(new ConsoleInputSuggestion("RUN", "/execute as @s run run eat 2 apples", "run prompt with actor label", 18));
        suggestions.add(new ConsoleInputSuggestion("RUN", "/run kill 3 creepers", "run phrase-search", 18));
        suggestions.add(new ConsoleInputSuggestion("RUN", "/run open_container_and_transfer_matching_items.ktl item.id=minecraft:cobblestone", "run template", 18));
        suggestions.add(new ConsoleInputSuggestion("TASK", "walk straight 4 blocks", "automation prompt", 22));
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
                suggestions.add(new ConsoleInputSuggestion("KTL", "/run " + templateId + ".ktl", "compiled task template", 14));
            }
            for (String operationId : assets.semanticOperations.keySet()) {
                suggestions.add(new ConsoleInputSuggestion("SEM", operationId, "semantic operation", 28));
            }
            for (String selectorId : assets.selectors.values()) {
                suggestions.add(new ConsoleInputSuggestion("SEL", selectorId, "selector id", 34));
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
            Suggestions brigadierSuggestions = client.getNetworkHandler().getCommandDispatcher().getCompletionSuggestions(parse).join();
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
        String normalizedToken = currentToken == null ? "" : currentToken.toLowerCase(Locale.ROOT);
        List<RegistryCandidate> candidates = new ArrayList<>();
        for (Identifier id : ids) {
            RegistryCandidate candidate = toRegistryCandidate(id, prefixBeforeCurrent, normalizedToken, detail, priority);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator
                .comparingInt(RegistryCandidate::score)
                .thenComparing(candidate -> candidate.identifier().getNamespace().equals("minecraft") ? 0 : 1)
                .thenComparing(candidate -> candidate.identifier().toString()));
        for (int i = 0; i < Math.min(MAX_SUGGESTIONS, candidates.size()); i++) {
            RegistryCandidate candidate = candidates.get(i);
            suggestions.add(new ConsoleInputSuggestion(kind, candidate.completed(), detail, priority + candidate.score()));
        }
    }

    private static RegistryCandidate toRegistryCandidate(Identifier id, String prefixBeforeCurrent, String normalizedToken, String detail, int priority) {
        String full = id.toString().toLowerCase(Locale.ROOT);
        String path = id.getPath().toLowerCase(Locale.ROOT);
        String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
        int score;
        if (normalizedToken.isEmpty()) {
            score = namespace.equals("minecraft") ? 0 : 2;
        } else if (path.startsWith(normalizedToken)) {
            score = namespace.equals("minecraft") ? 0 : 1;
        } else if (full.startsWith(normalizedToken)) {
            score = 2;
        } else if (path.contains(normalizedToken)) {
            score = 3;
        } else if (full.contains(normalizedToken)) {
            score = 4;
        } else {
            return null;
        }
        String value = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
        return new RegistryCandidate(id, prefixBeforeCurrent + value, score);
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

    private record RegistryCandidate(Identifier identifier, String completed, int score) {
    }
}
