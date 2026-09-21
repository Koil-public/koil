package com.spirit.koil.api.minecraft;

import com.spirit.koil.api.util.text.FuzzyTextMatcher;
import net.minecraft.block.Block;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Shared fuzzy resolver for model-facing Minecraft names.
 *
 * <p>Read-only callers may use {@link #best(String, String)} to surface ranked
 * suggestions. Consequential callers should use {@link #conservative(String,
 * String)}, which requires a strong score and an ambiguity margin before an
 * identifier is accepted.</p>
 */
public final class MinecraftNameResolver {
    private static final int ACTION_MINIMUM_SCORE = 700;
    private static final int ACTION_MINIMUM_MARGIN = 70;

    private MinecraftNameResolver() {}

    public static Match conservative(String kind, String query) {
        List<Match> ranked = rank(kind, query, 2);
        if (ranked.isEmpty()) return null;
        Match best = ranked.get(0);
        int second = ranked.size() > 1 ? ranked.get(1).score() : 0;
        if (best.score() < ACTION_MINIMUM_SCORE) return null;
        if (best.score() < 930 && best.score() - second < ACTION_MINIMUM_MARGIN) return null;
        return best;
    }

    public static Match best(String kind, String query) {
        List<Match> ranked = rank(kind, query, 1);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    public static List<Match> rank(String kind, String query, int requestedLimit) {
        String canonical = canonicalKind(kind);
        int limit = Math.max(1, Math.min(16, requestedLimit));
        List<Match> matches = switch (canonical) {
            case "item" -> rankRegistry(Registries.ITEM, query, id -> itemName(Registries.ITEM.get(id)), canonical);
            case "block" -> rankRegistry(Registries.BLOCK, query, id -> Registries.BLOCK.get(id).getName().getString(), canonical);
            case "entity_type" -> rankRegistry(Registries.ENTITY_TYPE, query, id -> Registries.ENTITY_TYPE.get(id).getName().getString(), canonical);
            case "status_effect" -> rankRegistry(Registries.STATUS_EFFECT, query, id -> Registries.STATUS_EFFECT.get(id).getName().getString(), canonical);
            case "enchantment" -> rankRegistry(Registries.ENCHANTMENT, query, id -> enchantmentName(Registries.ENCHANTMENT.get(id)), canonical);
            default -> List.of();
        };
        return matches.size() <= limit ? matches : List.copyOf(matches.subList(0, limit));
    }

    private static <T> List<Match> rankRegistry(
            Registry<T> registry,
            String query,
            Function<Identifier, String> displayName,
            String kind
    ) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.strip();
        List<Match> out = new ArrayList<>();
        for (Identifier id : registry.getIds()) {
            String name;
            try {
                name = displayName.apply(id);
            } catch (RuntimeException ignored) {
                name = "";
            }
            int score = Math.max(
                    FuzzyTextMatcher.score(normalized, id.toString()),
                    Math.max(FuzzyTextMatcher.score(normalized, id.getPath()),
                            FuzzyTextMatcher.score(normalized, name))
            );
            if (score > 0) out.add(new Match(kind, id, name, score));
        }
        out.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(match -> match.id().toString()));
        return List.copyOf(out);
    }

    private static String itemName(Item item) {
        if (item == null) return "";
        ItemStack stack = item.getDefaultStack();
        return stack.isEmpty() ? "" : stack.getName().getString();
    }

    private static String enchantmentName(Enchantment enchantment) {
        if (enchantment == null) return "";
        try {
            return enchantment.getName(enchantment.getMinLevel()).getString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String canonicalKind(String kind) {
        String value = kind == null ? "" : kind.toLowerCase(Locale.ROOT).replace('-', '_').strip();
        return switch (value) {
            case "items" -> "item";
            case "blocks" -> "block";
            case "entity", "entities" -> "entity_type";
            case "effect", "effects", "mob_effect", "mob_effects" -> "status_effect";
            case "enchant", "enchants", "enchantments" -> "enchantment";
            default -> value;
        };
    }

    public record Match(String kind, Identifier id, String displayName, int score) {}
}
