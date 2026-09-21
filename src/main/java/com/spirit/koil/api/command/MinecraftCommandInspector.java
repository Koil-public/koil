package com.spirit.koil.api.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.spirit.koil.api.util.text.FuzzyTextMatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only inspection of one command against the active server command tree.
 * The dispatcher is consulted on Minecraft's client thread, so modded and
 * datapack commands advertised by the current connection are included.
 */
public final class MinecraftCommandInspector {
    private static final int MAXIMUM_COMMAND_LENGTH = 2_048;
    private static final int MAXIMUM_SUGGESTIONS = 8;
    private static final int MAXIMUM_DISCOVERY_MATCHES = 8;
    private static final int MAXIMUM_DISCOVERY_SYNTAX = 16;
    private static final int MAXIMUM_DISCOVERY_DEPTH = 7;

    private MinecraftCommandInspector() {
    }

    public static CompletableFuture<Inspection> inspect(String rawCommand) {
        CompletableFuture<Inspection> result = new CompletableFuture<>();
        String command;
        try {
            command = normalize(rawCommand);
        } catch (IllegalArgumentException failure) {
            result.complete(new Inspection("", false, 0, failure.getMessage(), List.of()));
            return result;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            result.complete(new Inspection(command, false, 0, "Minecraft client is unavailable.", List.of()));
            return result;
        }
        client.execute(() -> inspectOnClientThread(client, command, result));
        return result;
    }


    /**
     * Discovers command roots and bounded syntax directly from the active Brigadier tree.
     * This intentionally does not maintain a vanilla command list: whatever the connected
     * server exposes, including modded/plugin/datapack roots, is the source of truth.
     */
    public static CompletableFuture<Discovery> discover(String rawQuery) {
        CompletableFuture<Discovery> result = new CompletableFuture<>();
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.length() > MAXIMUM_COMMAND_LENGTH) {
            result.complete(new Discovery(query, List.of(), List.of(), List.of(),
                    "Query exceeds 2,048 characters."));
            return result;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            result.complete(new Discovery(query, List.of(), List.of(), List.of(),
                    "Minecraft client is unavailable."));
            return result;
        }
        client.execute(() -> discoverOnClientThread(client, query, result));
        return result;
    }

    private static void discoverOnClientThread(
            MinecraftClient client,
            String rawQuery,
            CompletableFuture<Discovery> result
    ) {
        if (client.getNetworkHandler() == null || client.getNetworkHandler().getCommandDispatcher() == null) {
            result.complete(new Discovery(rawQuery, List.of(), List.of(), List.of(),
                    "The active connection has no command tree yet."));
            return;
        }
        try {
            var dispatcher = client.getNetworkHandler().getCommandDispatcher();
            List<CommandNode<CommandSource>> roots = new ArrayList<>(dispatcher.getRoot().getChildren());
            roots.sort(Comparator.comparing(node -> node.getName()));

            String query = normalizeDiscoveryQuery(rawQuery);
            String first = query.isBlank() ? "" : query.split("\\s+", 2)[0];
            ArrayList<ScoredRoot> scored = new ArrayList<>();
            for (CommandNode<CommandSource> root : roots) {
                String name = root.getName();
                int score = Math.max(FuzzyTextMatcher.score(query, name), FuzzyTextMatcher.score(first, name));
                String normalizedQuery = FuzzyTextMatcher.normalize(query);
                if (FuzzyTextMatcher.tokens(normalizedQuery).contains(FuzzyTextMatcher.normalize(name))) {
                    score = Math.max(score, 960);
                }
                if (first.equalsIgnoreCase(name)) score = 1000;
                if (query.isBlank()) score = 500;
                if (score >= 360) scored.add(new ScoredRoot(root, score));
            }
            scored.sort(Comparator.comparingInt(ScoredRoot::score).reversed()
                    .thenComparing(value -> value.root().getName()));
            if (scored.size() > MAXIMUM_DISCOVERY_MATCHES) {
                scored.subList(MAXIMUM_DISCOVERY_MATCHES, scored.size()).clear();
            }

            ArrayList<String> matches = new ArrayList<>();
            ArrayList<String> syntax = new ArrayList<>();
            for (ScoredRoot match : scored) {
                String rootName = match.root().getName();
                matches.add(rootName);
                collectSyntax(match.root(), rootName, 0, syntax);
                if (syntax.size() >= MAXIMUM_DISCOVERY_SYNTAX) break;
            }

            String completionSeed = query.isBlank() ? "" : query;
            ParseResults<CommandSource> parse = dispatcher.parse(completionSeed, client.getNetworkHandler().getCommandSource());
            dispatcher.getCompletionSuggestions(parse).whenComplete((suggestions, failure) -> {
                LinkedHashSet<String> completions = new LinkedHashSet<>();
                if (failure == null && suggestions != null) {
                    for (Suggestion suggestion : suggestions.getList()) {
                        String applied = suggestion.apply(completionSeed).strip();
                        if (!applied.isBlank()) completions.add("/" + applied);
                        if (completions.size() >= MAXIMUM_SUGGESTIONS) break;
                    }
                }
                result.complete(new Discovery(rawQuery, List.copyOf(matches), List.copyOf(syntax),
                        List.copyOf(completions), matches.isEmpty()
                        ? "No close command root matched the query. Try a command/root name or a shorter intent word."
                        : "Matches and syntax come from the active Brigadier command tree."));
            });
        } catch (RuntimeException failure) {
            result.complete(new Discovery(rawQuery, List.of(), List.of(), List.of(),
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    private static void collectSyntax(
            CommandNode<CommandSource> node,
            String path,
            int depth,
            List<String> out
    ) {
        if (out.size() >= MAXIMUM_DISCOVERY_SYNTAX || depth >= MAXIMUM_DISCOVERY_DEPTH) return;
        if (node.getCommand() != null && !out.contains("/" + path)) out.add("/" + path);
        for (CommandNode<CommandSource> child : node.getChildren()) {
            if (out.size() >= MAXIMUM_DISCOVERY_SYNTAX) return;
            String token = child instanceof ArgumentCommandNode<?, ?> ? "<" + child.getName() + ">" : child.getName();
            collectSyntax(child, path + " " + token, depth + 1, out);
        }
    }

    private static String normalizeDiscoveryQuery(String raw) {
        String query = raw == null ? "" : raw.strip();
        if (query.startsWith("/")) query = query.substring(1).stripLeading();
        return query.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").strip();
    }

    private static void inspectOnClientThread(
            MinecraftClient client,
            String command,
            CompletableFuture<Inspection> result
    ) {
        if (client.getNetworkHandler() == null
                || client.getNetworkHandler().getCommandDispatcher() == null) {
            result.complete(new Inspection(
                    command,
                    false,
                    0,
                    "The active connection has no command tree yet.",
                    List.of()
            ));
            return;
        }
        try {
            var dispatcher = client.getNetworkHandler().getCommandDispatcher();
            List<String> roots = dispatcher.getRoot().getChildren().stream()
                    .map(node -> node.getName())
                    .sorted()
                    .toList();
            String requestedRoot = command.split("\\s+", 2)[0];
            boolean rootAvailable = roots.contains(requestedRoot);
            ParseResults<CommandSource> parse =
                    dispatcher.parse(command, client.getNetworkHandler().getCommandSource());
            boolean executable = isExecutable(parse);
            int cursor = Math.max(0, parse.getReader().getCursor());
            String problem = executable ? "" : problem(parse);
            dispatcher.getCompletionSuggestions(parse).whenComplete((suggestions, failure) -> {
                if (failure != null) {
                    result.complete(new Inspection(command, executable, cursor, problem, List.of(), rootAvailable, roots));
                    return;
                }
                LinkedHashSet<String> values = new LinkedHashSet<>();
                if (suggestions != null) {
                    for (Suggestion suggestion : suggestions.getList()) {
                        String applied = suggestion.apply(command).strip();
                        if (!applied.isBlank()) {
                            values.add("/" + applied);
                        }
                        if (values.size() >= MAXIMUM_SUGGESTIONS) {
                            break;
                        }
                    }
                }
                String exactProblem = problem;
                if (!executable && rootAvailable && (exactProblem.isBlank() || exactProblem.startsWith("Unknown"))) {
                    exactProblem = "The live command root /" + requestedRoot + " exists, but the supplied arguments are incomplete or invalid.";
                }
                result.complete(new Inspection(command, executable, cursor, exactProblem,
                        List.copyOf(values), rootAvailable, roots));
            });
        } catch (RuntimeException failure) {
            result.complete(new Inspection(
                    command,
                    false,
                    0,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    List.of()
            ));
        }
    }

    private static boolean isExecutable(ParseResults<CommandSource> parse) {
        if (parse == null || parse.getReader().canRead() || parse.getContext() == null) {
            return false;
        }
        CommandContextBuilder<CommandSource> context = parse.getContext();
        while (context.getChild() != null) {
            context = context.getChild();
        }
        return context.getCommand() != null;
    }

    private static String problem(ParseResults<CommandSource> parse) {
        if (parse == null) {
            return "Command parsing produced no result.";
        }
        if (!parse.getExceptions().isEmpty()) {
            return parse.getExceptions().values().stream()
                    .max(java.util.Comparator.comparingInt(exception ->
                            exception.getCursor() < 0 ? parse.getReader().getCursor() : exception.getCursor()))
                    .map(Throwable::getMessage)
                    .filter(message -> message != null && !message.isBlank())
                    .orElse("Unknown or incomplete command.");
        }
        if (parse.getReader().canRead()) {
            return "Unexpected input at column " + (parse.getReader().getCursor() + 1) + ".";
        }
        return "Unknown or incomplete command.";
    }

    private static String normalize(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("Command is empty.");
        }
        if (command.length() > MAXIMUM_COMMAND_LENGTH) {
            throw new IllegalArgumentException("Command exceeds 2,048 characters.");
        }
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if (value == '\n' || value == '\r' || value == '\0' || Character.isISOControl(value)) {
                throw new IllegalArgumentException("Command contains an unsupported control character.");
            }
        }
        return command;
    }


    public record Discovery(
            String query,
            List<String> matchingRoots,
            List<String> syntax,
            List<String> completions,
            String note
    ) {
        public Discovery {
            query = query == null ? "" : query;
            matchingRoots = matchingRoots == null ? List.of() : List.copyOf(matchingRoots);
            syntax = syntax == null ? List.of() : List.copyOf(syntax);
            completions = completions == null ? List.of() : List.copyOf(completions);
            note = note == null ? "" : note;
        }
    }

    private record ScoredRoot(CommandNode<CommandSource> root, int score) {}

    public record Inspection(
            String normalizedCommand,
            boolean executable,
            int cursor,
            String problem,
            List<String> suggestions,
            boolean rootAvailable,
            List<String> availableRoots
    ) {
        public Inspection(String normalizedCommand, boolean executable, int cursor, String problem, List<String> suggestions) {
            this(normalizedCommand, executable, cursor, problem, suggestions, false, List.of());
        }
        public Inspection {
            normalizedCommand = normalizedCommand == null ? "" : normalizedCommand;
            problem = problem == null ? "" : problem;
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            availableRoots = availableRoots == null ? List.of() : List.copyOf(availableRoots);
        }
    }
}
