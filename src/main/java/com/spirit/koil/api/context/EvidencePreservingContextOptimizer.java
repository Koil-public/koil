package com.spirit.koil.api.context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, provenance-preserving context compactor.
 *
 * <p>It never invents facts or paraphrases source evidence. It selects and bounds
 * exact source fragments, deduplicates repetition, prioritizes objective-relevant
 * and diagnostic evidence, and leaves the canonical artifact retrievable by ref.</p>
 */
public final class EvidencePreservingContextOptimizer implements ContextOptimizer {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public ContextRepresentation optimize(ContextArtifact artifact, ContextOptimizationRequest request) {
        if (artifact == null || request == null) throw new IllegalArgumentException("artifact and request");
        String canonical = artifact.canonicalContent();
        int target = Math.max(0, request.targetCharacters());
        if (request.requestedLevel() == ContextRepresentationLevel.L0_EXACT
                || request.requestedLevel() == ContextRepresentationLevel.L1_LOSSLESS
                || target <= 0 || canonical.length() <= target) {
            String value = target > 0 && canonical.length() > target ? bounded(canonical, target) : canonical;
            return new ContextRepresentation(artifact.id(), request.requestedLevel(), value,
                    canonical.length(), value.length(), canonical.length() <= target || target <= 0 ? "lossless-pass-through" : "bounded-exact");
        }
        if (request.requestedLevel() == ContextRepresentationLevel.L4_AGGRESSIVE) {
            String value = "[context ref=" + artifact.id() + " source=" + artifact.sourceType() + " chars=" + canonical.length() + "]";
            return new ContextRepresentation(artifact.id(), request.requestedLevel(), bounded(value, target),
                    canonical.length(), Math.min(value.length(), target), "aggressive-reference");
        }
        if (request.requestedLevel() == ContextRepresentationLevel.L5_EVICTED_RETRIEVABLE) {
            String value = "[context ref=" + artifact.id() + "]";
            return new ContextRepresentation(artifact.id(), request.requestedLevel(), bounded(value, target),
                    canonical.length(), Math.min(value.length(), target), "evicted-retrievable");
        }

        String normalized = normalizeStructured(canonical);
        List<Block> blocks = blocks(normalized, request.objective());
        String header = target < 320
                ? "[ctx ref=" + artifact.id() + " | exact extract]\n"
                : "[loss-aware context projection; exact fragments only; canonical_ref=" + artifact.id()
                + "; source=" + artifact.sourceType() + "; original_chars=" + canonical.length() + "]\n";
        int bodyBudget = Math.max(32, target - header.length() - 36);
        List<Block> selected = select(blocks, bodyBudget);
        StringBuilder body = new StringBuilder();
        int selectedCharacters = 0;
        for (Block block : selected) {
            String value = block.text();
            int remaining = bodyBudget - body.length() - (body.isEmpty() ? 0 : 1);
            if (remaining <= 0) break;
            value = boundedHeadTail(value, remaining);
            if (!body.isEmpty()) body.append('\n');
            body.append(value);
            selectedCharacters += value.length();
        }
        int omitted = Math.max(0, blocks.size() - selected.size());
        String footer = omitted > 0 ? "\n[omitted=" + omitted + "; retrieve=" + artifact.id() + "]" : "";
        String content = header + body;
        if (content.length() + footer.length() <= target) content += footer;
        if (content.length() > target) content = content.substring(0, target).stripTrailing();
        String strategy = request.requestedLevel() == ContextRepresentationLevel.L3_SEMANTIC
                ? "objective-ranked-extractive" : "structural-extractive";
        return new ContextRepresentation(artifact.id(), request.requestedLevel(), content,
                canonical.length(), content.length(), strategy + ":selected_chars=" + selectedCharacters);
    }

    private static String normalizeStructured(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) return text;
        if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
            try {
                JsonElement parsed = JsonParser.parseString(text);
                return PRETTY.toJson(parsed);
            } catch (RuntimeException ignored) {
                return text;
            }
        }
        return text;
    }

    private static List<Block> blocks(String content, String objective) {
        Set<String> objectiveTerms = terms(objective);
        LinkedHashMap<String, MutableBlock> unique = new LinkedHashMap<>();
        int ordinal = 0;
        for (String raw : content.split("(?m)(?:\\R\\s*\\R)+|(?<=\\})\\s*(?=\\{)")) {
            String value = raw.strip();
            if (value.isEmpty()) continue;
            String[] lines = value.split("\\R");
            if (lines.length > 1) {
                for (String line : lines) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty()) add(unique, trimmed, ordinal++, objectiveTerms);
                }
            } else if (value.length() > 1800) {
                for (int start = 0; start < value.length(); start += 1200) {
                    add(unique, value.substring(start, Math.min(value.length(), start + 1200)), ordinal++, objectiveTerms);
                }
            } else {
                add(unique, value, ordinal++, objectiveTerms);
            }
        }
        List<Block> out = new ArrayList<>();
        for (MutableBlock value : unique.values()) out.add(value.freeze());
        return List.copyOf(out);
    }

    private static void add(Map<String, MutableBlock> unique, String text, int ordinal, Set<String> objectiveTerms) {
        String key = text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        MutableBlock prior = unique.get(key);
        if (prior != null) {
            prior.repetitions++;
            return;
        }
        double score = signalScore(text, objectiveTerms, ordinal);
        unique.put(key, new MutableBlock(text, ordinal, score));
    }

    private static double signalScore(String text, Set<String> objectiveTerms, int ordinal) {
        String lower = text.toLowerCase(Locale.ROOT);
        double score = Math.max(0.0D, 1.0D - ordinal * 0.002D);
        if (lower.matches(".*\\b(error|exception|failed|failure|warning|warn|fatal|denied|invalid|timeout|cancelled|canceled)\\b.*")) score += 5.0D;
        if (lower.contains("http://") || lower.contains("https://")) score += 2.5D;
        if (lower.matches(".*(?:[/\\\\][\\w.() -]+){2,}.*")) score += 1.4D;
        if (lower.matches(".*\\b(?:[a-z_][a-z0-9_$.]*\\.)+[a-z_][a-z0-9_$]*\\b.*")) score += 1.25D;
        if (lower.matches(".*\\b\\d+(?:\\.\\d+)?(?:ms|s|kb|mb|gb|%|tokens?)?\\b.*")) score += 0.7D;
        if (text.startsWith("#") || text.matches("^[A-Z][A-Za-z0-9 _./:-]{2,80}:?$")) score += 1.0D;
        Set<String> blockTerms = terms(text);
        int overlaps = 0;
        for (String term : objectiveTerms) if (blockTerms.contains(term)) overlaps++;
        score += Math.min(6.0D, overlaps * 1.15D);
        return score;
    }

    private static List<Block> select(List<Block> blocks, int budget) {
        if (blocks.isEmpty() || budget <= 0) return List.of();
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        List<Integer> ranked = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) ranked.add(i);
        ranked.sort(Comparator.comparingDouble((Integer i) -> blocks.get(i).score()).reversed().thenComparingInt(i -> i));
        int used = 0;
        for (int index : ranked) {
            int size = blocks.get(index).rendered().length() + 1;
            if (used + size > budget) continue;
            selected.add(index);
            used += size;
        }
        if (selected.isEmpty() && !ranked.isEmpty()) selected.add(ranked.get(0));
        List<Integer> ordered = new ArrayList<>(selected);
        ordered.sort(Integer::compareTo);
        List<Block> result = new ArrayList<>();
        for (int index : ordered) {
            Block block = blocks.get(index);
            String rendered = block.rendered();
            if (rendered.length() > Math.max(240, budget / 2)) rendered = boundedHeadTail(rendered, Math.max(240, budget / 2));
            result.add(new Block(rendered, block.ordinal(), block.score(), block.repetitions()));
        }
        return List.copyOf(result);
    }

    private static Set<String> terms(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String term : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_:.]+", " ").split("\\s+")) {
            if (term.length() >= 3 && !STOP.contains(term)) out.add(term);
        }
        return out;
    }

    private static String boundedHeadTail(String text, int maximum) {
        if (maximum <= 0 || text.length() <= maximum) return text;
        int head = Math.max(1, (int) Math.round(maximum * 0.68D));
        int tail = Math.max(1, maximum - head - 24);
        return text.substring(0, Math.min(head, text.length())).stripTrailing()
                + "\n…[middle omitted]…\n"
                + text.substring(Math.max(0, text.length() - tail)).stripLeading();
    }

    private static String bounded(String text, int maximum) {
        if (maximum <= 0) return "";
        if (text.length() <= maximum) return text;
        return boundedHeadTail(text, maximum);
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "into", "then", "than", "have", "has", "had", "was", "were", "are", "you", "your", "its", "about", "what", "when", "where", "which", "will", "would", "could", "should");

    private record Block(String text, int ordinal, double score, int repetitions) {
        String rendered() { return repetitions <= 1 ? text : text + "\n[repeated x" + repetitions + "]"; }
    }

    private static final class MutableBlock {
        private final String text;
        private final int ordinal;
        private final double score;
        private int repetitions = 1;
        private MutableBlock(String text, int ordinal, double score) { this.text = text; this.ordinal = ordinal; this.score = score; }
        private Block freeze() { return new Block(text, ordinal, score, repetitions); }
    }
}
