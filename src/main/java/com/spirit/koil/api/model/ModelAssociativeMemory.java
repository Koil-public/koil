package com.spirit.koil.api.model;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lexical retrieval over bounded final exchanges, never private reasoning. */
final class ModelAssociativeMemory {
    private static final Path PATH = Path.of("koil", "sys", "model", "associative-memory.json");
    private static final int MAXIMUM_ENTRIES = 256;

    private ModelAssociativeMemory() {}

    static synchronized void remember(String prompt, String answer) {
        if (prompt == null || prompt.isBlank() || answer == null || answer.isBlank()) return;
        List<Entry> entries = load();
        entries.add(new Entry(compact(prompt, 800), compact(answer, 1_600), System.currentTimeMillis()));
        if (entries.size() > MAXIMUM_ENTRIES) entries = new ArrayList<>(entries.subList(entries.size() - MAXIMUM_ENTRIES, entries.size()));
        save(entries);
    }

    static synchronized String relevantContext(String prompt) {
        Set<String> query = tokens(prompt);
        if (query.isEmpty()) return "";
        List<Scored> scored = new ArrayList<>();
        for (Entry entry : load()) {
            Set<String> candidate = tokens(entry.prompt + " " + entry.answer);
            long overlap = query.stream().filter(candidate::contains).count();
            if (overlap > 0) scored.add(new Scored(entry, overlap));
        }
        scored.sort(Comparator.comparingLong(Scored::score).reversed()
                .thenComparing(Comparator.comparingLong((Scored value) -> value.entry.timestamp).reversed()));
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < Math.min(3, scored.size()); index++) {
            Entry entry = scored.get(index).entry;
            if (!context.isEmpty()) context.append('\n');
            context.append("- Prior final exchange: ").append(compact(entry.prompt, 240))
                    .append(" -> ").append(compact(entry.answer, 500));
        }
        return context.toString();
    }

    private static List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        try {
            if (!Files.isRegularFile(PATH)) return entries;
            JsonArray root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : root) {
                JsonObject row = element.getAsJsonObject();
                entries.add(new Entry(row.get("prompt").getAsString(), row.get("answer").getAsString(), row.get("timestamp").getAsLong()));
            }
        } catch (Exception ignored) {}
        return entries;
    }

    private static void save(List<Entry> entries) {
        try {
            JsonArray root = new JsonArray();
            for (Entry entry : entries) {
                JsonObject row = new JsonObject();
                row.addProperty("prompt", entry.prompt); row.addProperty("answer", entry.answer); row.addProperty("timestamp", entry.timestamp);
                root.add(row);
            }
            Path parent = PATH.toAbsolutePath().normalize().getParent(); Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "associative-memory-", ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            try { Files.move(temporary, PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception unsupported) { Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception ignored) {}
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) return tokens;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_:./-]+")) if (token.length() >= 3) tokens.add(token);
        return tokens;
    }

    private static String compact(String text, int maximum) {
        String clean = text.replaceAll("\\s+", " ").strip();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1) + "…";
    }

    private record Entry(String prompt, String answer, long timestamp) {}
    private record Scored(Entry entry, long score) {}
}
