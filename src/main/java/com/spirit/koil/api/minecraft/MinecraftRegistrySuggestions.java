package com.spirit.koil.api.minecraft;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Shared, allocation-bounded identifier matching for model knowledge and
 * Koil text suggestions. It is intentionally UI-neutral so chat, editors, and
 * future text fields can present the same ranked Minecraft registry data.
 */
public final class MinecraftRegistrySuggestions {
    private MinecraftRegistrySuggestions() {
    }

    public static SearchResult search(Iterable<Identifier> identifiers, String query, int requestedLimit) {
        String needle = normalize(query);
        int limit = Math.max(1, Math.min(64, requestedLimit));
        List<Candidate> matches = new ArrayList<>();
        if (identifiers != null) {
            for (Identifier identifier : identifiers) {
                Candidate candidate = candidate(identifier, needle);
                if (candidate != null) {
                    matches.add(candidate);
                }
            }
        }
        matches.sort(Comparator
                .comparingInt(Candidate::score)
                .thenComparing(candidate -> "minecraft".equals(candidate.identifier().getNamespace()) ? 0 : 1)
                .thenComparing(candidate -> candidate.identifier().toString()));
        int matchCount = matches.size();
        if (matches.size() > limit) {
            matches = new ArrayList<>(matches.subList(0, limit));
        }
        return new SearchResult(List.copyOf(matches), matchCount, matchCount > limit);
    }

    private static Candidate candidate(Identifier identifier, String needle) {
        if (identifier == null) {
            return null;
        }
        String full = identifier.toString().toLowerCase(Locale.ROOT);
        String path = identifier.getPath().toLowerCase(Locale.ROOT);
        int score;
        if (needle.isEmpty()) {
            score = "minecraft".equals(identifier.getNamespace()) ? 0 : 2;
        } else if (path.startsWith(needle)) {
            score = "minecraft".equals(identifier.getNamespace()) ? 0 : 1;
        } else if (full.startsWith(needle)) {
            score = 2;
        } else if (path.contains(needle)) {
            score = 3;
        } else if (full.contains(needle)) {
            score = 4;
        } else {
            return null;
        }
        return new Candidate(identifier, score);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .strip();
    }

    public record Candidate(Identifier identifier, int score) {
    }

    public record SearchResult(List<Candidate> candidates, int matchCount, boolean truncated) {
        public SearchResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            matchCount = Math.max(candidates.size(), matchCount);
        }
    }
}
