package com.spirit.koil.api.minecraft;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared, version-local Minecraft 1.20.1 item-SNBT knowledge.
 *
 * <p>The service is UI-neutral: chat completion, model grounding, and the
 * read-only Minecraft knowledge tool all consume the same bounded templates
 * and active registry identifiers.</p>
 */
public final class MinecraftNbtSuggestionService {
    private static final int MAXIMUM_SUGGESTIONS = 32;
    private static final Pattern GIVE_WITH_ENCHANTMENT = Pattern.compile(
            "(?i)\\b(?:give|gives|giving|get|create|make|spawn)\\b.*?\\b(?:a|an|some)?\\s*"
                    + "([a-z0-9_:\\- ]{2,80}?)\\s+with\\s+"
                    + "([a-z0-9_:\\- ]{2,80}?)\\s+(?:level\\s*)?(\\d{1,5})\\b"
    );
    private static final Pattern ENCHANTMENT_ID_CONTEXT = Pattern.compile(
            "(?i)id\\s*:\\s*[\"']([^\"']*)$"
    );
    private static final List<Template> ROOT_FIELDS = List.of(
            new Template("Enchantments", "Item enchantment list."),
            new Template("display", "Custom name, lore, and display color."),
            new Template("Unbreakable", "Prevent durability loss."),
            new Template("HideFlags", "Vanilla item tooltip visibility flags."),
            new Template("CustomModelData", "Resource-pack custom model data."),
            new Template("Damage", "Current durability damage."),
            new Template("RepairCost", "Anvil repair cost."),
            new Template("AttributeModifiers", "Item attribute modifier list.")
    );
    private static final List<Template> ROOT_TEMPLATES = List.of(
            new Template(
                    "Enchantments:[{id:\"minecraft:knockback\",lvl:1s}]",
                    "Item enchantments; replace the enchantment id and short level."
            ),
            new Template(
                    "display:{Name:'{\"text\":\"Custom Item\"}'}",
                    "Custom JSON display name."
            ),
            new Template("Unbreakable:1b", "Prevent durability loss."),
            new Template("HideFlags:0", "Vanilla item tooltip visibility flags."),
            new Template("CustomModelData:1", "Resource-pack custom model data."),
            new Template("Damage:0", "Current durability damage."),
            new Template("RepairCost:0", "Anvil repair cost."),
            new Template(
                    "AttributeModifiers:[{AttributeName:\"minecraft:generic.attack_damage\",Name:\"koil:attack_damage\",Amount:1.0d,Operation:0,UUID:[I;1,2,3,4],Slot:\"mainhand\"}]",
                    "One attribute modifier; use a stable unique UUID."
            )
    );

    private MinecraftNbtSuggestionService() {
    }

    public static List<Suggestion> suggest(String commandText, int requestedCursor, int requestedLimit) {
        return suggest(
                commandText,
                requestedCursor,
                requestedLimit,
                Registries.ITEM.getIds(),
                Registries.ENCHANTMENT.getIds()
        );
    }

    public static List<Suggestion> suggest(
            String commandText,
            int requestedCursor,
            int requestedLimit,
            Iterable<Identifier> itemIds,
            Iterable<Identifier> enchantmentIds
    ) {
        String command = commandText == null ? "" : commandText;
        int cursor = Math.max(0, Math.min(requestedCursor, command.length()));
        int limit = Math.max(1, Math.min(MAXIMUM_SUGGESTIONS, requestedLimit));
        String beforeCursor = command.substring(0, cursor);
        if (!isItemCommand(beforeCursor)) {
            return List.of();
        }

        Matcher enchantmentContext = ENCHANTMENT_ID_CONTEXT.matcher(beforeCursor);
        if (enchantmentContext.find() && insideItemCompound(beforeCursor)) {
            int start = enchantmentContext.start(1);
            String query = enchantmentContext.group(1);
            return registrySuggestions(
                    enchantmentIds,
                    query,
                    start,
                    cursor,
                    limit,
                    "enchantment id"
            );
        }

        int compoundStart = itemCompoundStart(beforeCursor);
        if (compoundStart >= 0) {
            return structuralSuggestions(beforeCursor, compoundStart, cursor, limit);
        }

        if (!hasCompleteItemIdentifier(beforeCursor, itemIds)) {
            return List.of();
        }
        int insertionStart = cursor;
        while (insertionStart > 0 && Character.isWhitespace(command.charAt(insertionStart - 1))) {
            insertionStart--;
        }
        return punctuationSuggestions(
                insertionStart,
                cursor,
                List.of(new Template("{", "Begin item SNBT data.")),
                limit
        );
    }

    public static Knowledge nbtKnowledge(String query, int requestedLimit) {
        int limit = Math.max(1, Math.min(MAXIMUM_SUGGESTIONS, requestedLimit));
        String normalized = normalizeWords(query);
        List<Template> matches = ROOT_TEMPLATES.stream()
                .filter(template -> normalized.isBlank()
                        || normalizeWords(template.text()).contains(normalized)
                        || normalizeWords(template.description()).contains(normalized)
                        || normalized.contains("enchant") && template.text().startsWith("Enchantments:"))
                .limit(limit)
                .toList();
        return new Knowledge(
                "Minecraft 1.20.1 item stack SNBT",
                matches,
                """
                        Item data follows the item identifier with no space: minecraft:stick{...}. \
                        Enchantments use Enchantments:[{id:"minecraft:knockback",lvl:5s}]. \
                        The numeric enchantment level is a short and therefore uses the s suffix.
                        """.strip()
        );
    }

    /**
     * Produces an exact masked-command answer for the common natural-language
     * item-enchantment request after resolving both names against active
     * registries. It does not send or execute the command.
     */
    public static Optional<GroundedCommand> groundedItemEnchantmentCommand(String prompt) {
        return groundedItemEnchantmentCommand(
                prompt,
                Registries.ITEM.getIds(),
                Registries.ENCHANTMENT.getIds()
        );
    }

    public static Optional<GroundedCommand> groundedItemEnchantmentCommand(
            String prompt,
            Iterable<Identifier> itemIds,
            Iterable<Identifier> enchantmentIds
    ) {
        String source = prompt == null ? "" : prompt.replace('\n', ' ').strip();
        Matcher matcher = GIVE_WITH_ENCHANTMENT.matcher(source);
        if (!matcher.find()) {
            return Optional.empty();
        }
        Identifier item = resolveIdentifier(itemIds, cleanItemPhrase(matcher.group(1)));
        Identifier enchantment = resolveIdentifier(enchantmentIds, matcher.group(2));
        if (item == null || enchantment == null) {
            return Optional.empty();
        }
        int level;
        try {
            level = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        if (level < 1 || level > Short.MAX_VALUE) {
            return Optional.empty();
        }
        String command = "/give @s " + item
                + "{Enchantments:[{id:\"" + enchantment + "\",lvl:" + level + "s}]} 1";
        String itemLabel = title(item.getPath());
        String enchantmentLabel = title(enchantment.getPath());
        return Optional.of(new GroundedCommand(
                "Give " + itemLabel + " with " + enchantmentLabel + " " + level,
                command,
                item.toString(),
                enchantment.toString(),
                level
        ));
    }

    private static List<Suggestion> structuralSuggestions(
            String beforeCursor,
            int compoundStart,
            int cursor,
            int limit
    ) {
        int entryStart = topLevelSegmentStart(beforeCursor, compoundStart + 1);
        int tokenStart = skipWhitespace(beforeCursor, entryStart);
        String segment = beforeCursor.substring(tokenStart);
        int colon = topLevelColon(segment);
        if (colon < 0) {
            String query = segment.strip();
            Template exact = ROOT_FIELDS.stream()
                    .filter(field -> field.text().equalsIgnoreCase(query))
                    .findFirst()
                    .orElse(null);
            if (exact != null) {
                return punctuationSuggestions(
                        cursor,
                        cursor,
                        List.of(new Template(":", "Set " + exact.text() + ".")),
                        limit
                );
            }
            return filteredSuggestions(ROOT_FIELDS, query, tokenStart, cursor, limit);
        }

        String field = segment.substring(0, colon).strip();
        String value = segment.substring(colon + 1);
        int valueStart = tokenStart + colon + 1;
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "enchantments" -> enchantmentValueSuggestions(value, valueStart, cursor, limit);
            case "display" -> displayValueSuggestions(value, valueStart, cursor, limit);
            case "attributemodifiers" -> listValueSuggestions(value, valueStart, cursor, limit, "attribute modifier");
            case "unbreakable" -> scalarValueSuggestions(
                    value,
                    valueStart,
                    cursor,
                    limit,
                    List.of(
                            new Template("1b", "Enabled."),
                            new Template("0b", "Disabled.")
                    )
            );
            case "hideflags" -> scalarValueSuggestions(
                    value,
                    valueStart,
                    cursor,
                    limit,
                    List.of(
                            new Template("0", "Show every tooltip section."),
                            new Template("1", "Hide enchantments."),
                            new Template("63", "Hide the common vanilla tooltip sections.")
                    )
            );
            case "custommodeldata", "damage", "repaircost" -> scalarValueSuggestions(
                    value,
                    valueStart,
                    cursor,
                    limit,
                    List.of(new Template("0", "Integer value."))
            );
            default -> filteredSuggestions(ROOT_FIELDS, field, tokenStart, tokenStart + colon, limit);
        };
    }

    private static List<Suggestion> enchantmentValueSuggestions(
            String value,
            int valueStart,
            int cursor,
            int limit
    ) {
        int contentStart = valueStart + leadingWhitespace(value);
        String trimmed = value.stripLeading();
        if (trimmed.isEmpty()) {
            return punctuationSuggestions(
                    cursor,
                    cursor,
                    List.of(new Template("[", "Begin the enchantment list.")),
                    limit
            );
        }
        if (!trimmed.startsWith("[")) {
            return filteredSuggestions(
                    List.of(new Template("[", "Begin the enchantment list.")),
                    trimmed,
                    contentStart,
                    cursor,
                    limit
            );
        }
        String list = trimmed.substring(1);
        int listStart = contentStart + 1;
        int unclosedEntry = lastUnclosed(list, '{', '}');
        if (unclosedEntry < 0) {
            String tail = list.stripTrailing();
            if (tail.isBlank() || tail.endsWith(",")) {
                return punctuationSuggestions(
                        cursor,
                        cursor,
                        List.of(new Template("{", "Begin one enchantment entry.")),
                        limit
                );
            }
            if (tail.endsWith("}")) {
                return punctuationSuggestions(
                        cursor,
                        cursor,
                        List.of(
                                new Template(",", "Add another enchantment."),
                                new Template("]", "Finish the enchantment list.")
                        ),
                        limit
                );
            }
            if (tail.endsWith("]")) {
                return rootContinuationSuggestions(cursor, limit);
            }
            return List.of();
        }

        int entryStart = listStart + unclosedEntry + 1;
        String entry = list.substring(unclosedEntry + 1);
        int fieldStart = topLevelSegmentStart(entry, 0);
        int absoluteFieldStart = entryStart + fieldStart;
        String fieldSegment = entry.substring(fieldStart);
        int colon = topLevelColon(fieldSegment);
        List<Template> fields = List.of(
                new Template("id", "Namespaced enchantment identifier."),
                new Template("lvl", "Short enchantment level.")
        );
        if (colon < 0) {
            String query = fieldSegment.strip();
            Template exact = fields.stream()
                    .filter(field -> field.text().equalsIgnoreCase(query))
                    .findFirst()
                    .orElse(null);
            return exact == null
                    ? filteredSuggestions(fields, query, skipWhitespace(entry, fieldStart) + entryStart, cursor, limit)
                    : punctuationSuggestions(
                    cursor,
                    cursor,
                    List.of(new Template(":", "Set " + exact.text() + ".")),
                    limit
            );
        }
        String field = fieldSegment.substring(0, colon).strip().toLowerCase(Locale.ROOT);
        String fieldValue = fieldSegment.substring(colon + 1);
        int fieldValueStart = absoluteFieldStart + colon + 1;
        if ("id".equals(field)) {
            if (fieldValue.strip().isEmpty()) {
                return punctuationSuggestions(
                        cursor,
                        cursor,
                        List.of(new Template("\"", "Begin a namespaced enchantment id.")),
                        limit
                );
            }
            if (closedQuotedValue(fieldValue)) {
                return punctuationSuggestions(
                        cursor,
                        cursor,
                        List.of(
                                new Template(",", "Set another field."),
                                new Template("}", "Finish this enchantment.")
                        ),
                        limit
                );
            }
            return List.of();
        }
        if ("lvl".equals(field)) {
            return scalarValueSuggestions(
                    fieldValue,
                    fieldValueStart,
                    cursor,
                    limit,
                    List.of(
                            new Template("1s", "Level 1 short."),
                            new Template("5s", "Level 5 short."),
                            new Template("10s", "Level 10 short.")
                    ),
                    List.of(
                            new Template(",", "Set another field."),
                            new Template("}", "Finish this enchantment.")
                    )
            );
        }
        return filteredSuggestions(fields, field, absoluteFieldStart, absoluteFieldStart + colon, limit);
    }

    private static List<Suggestion> displayValueSuggestions(
            String value,
            int valueStart,
            int cursor,
            int limit
    ) {
        int contentStart = valueStart + leadingWhitespace(value);
        String trimmed = value.stripLeading();
        if (trimmed.isEmpty()) {
            return punctuationSuggestions(
                    cursor,
                    cursor,
                    List.of(new Template("{", "Begin display data.")),
                    limit
            );
        }
        if (!trimmed.startsWith("{")) {
            return filteredSuggestions(
                    List.of(new Template("{", "Begin display data.")),
                    trimmed,
                    contentStart,
                    cursor,
                    limit
            );
        }
        int closing = matchingClose(trimmed, 0, '{', '}');
        if (closing >= 0) {
            return rootContinuationSuggestions(cursor, limit);
        }
        String entry = trimmed.substring(1);
        int entryStart = contentStart + 1;
        int fieldStart = topLevelSegmentStart(entry, 0);
        int absoluteFieldStart = entryStart + fieldStart;
        String segment = entry.substring(fieldStart);
        int colon = topLevelColon(segment);
        List<Template> fields = List.of(
                new Template("Name", "JSON display name."),
                new Template("Lore", "List of JSON lore lines."),
                new Template("color", "Decimal leather armor color.")
        );
        if (colon < 0) {
            String query = segment.strip();
            Template exact = fields.stream()
                    .filter(field -> field.text().equalsIgnoreCase(query))
                    .findFirst()
                    .orElse(null);
            return exact == null
                    ? filteredSuggestions(fields, query, skipWhitespace(entry, fieldStart) + entryStart, cursor, limit)
                    : punctuationSuggestions(
                    cursor,
                    cursor,
                    List.of(new Template(":", "Set " + exact.text() + ".")),
                    limit
            );
        }
        String field = segment.substring(0, colon).strip().toLowerCase(Locale.ROOT);
        String fieldValue = segment.substring(colon + 1);
        int fieldValueStart = absoluteFieldStart + colon + 1;
        return switch (field) {
            case "name" -> scalarValueSuggestions(
                    fieldValue,
                    fieldValueStart,
                    cursor,
                    limit,
                    List.of(new Template("'", "Begin the quoted JSON text component."))
            );
            case "lore" -> listValueSuggestions(fieldValue, fieldValueStart, cursor, limit, "lore line");
            case "color" -> scalarValueSuggestions(
                    fieldValue,
                    fieldValueStart,
                    cursor,
                    limit,
                    List.of(new Template("0", "Decimal RGB color."))
            );
            default -> filteredSuggestions(fields, field, absoluteFieldStart, absoluteFieldStart + colon, limit);
        };
    }

    private static List<Suggestion> listValueSuggestions(
            String value,
            int valueStart,
            int cursor,
            int limit,
            String detail
    ) {
        String trimmed = value.stripLeading();
        int start = valueStart + leadingWhitespace(value);
        if (trimmed.isEmpty()) {
            return punctuationSuggestions(
                    cursor,
                    cursor,
                    List.of(new Template("[", "Begin " + detail + " list.")),
                    limit
            );
        }
        if (!trimmed.startsWith("[")) {
            return filteredSuggestions(
                    List.of(new Template("[", "Begin " + detail + " list.")),
                    trimmed,
                    start,
                    cursor,
                    limit
            );
        }
        return trimmed.endsWith("]")
                ? rootContinuationSuggestions(cursor, limit)
                : List.of();
    }

    private static List<Suggestion> scalarValueSuggestions(
            String value,
            int valueStart,
            int cursor,
            int limit,
            List<Template> values
    ) {
        return scalarValueSuggestions(
                value,
                valueStart,
                cursor,
                limit,
                values,
                List.of(
                        new Template(",", "Set another item-data field."),
                        new Template("}", "Finish item data.")
                )
        );
    }

    private static List<Suggestion> scalarValueSuggestions(
            String value,
            int valueStart,
            int cursor,
            int limit,
            List<Template> values,
            List<Template> completion
    ) {
        int start = valueStart + leadingWhitespace(value);
        String query = value.strip();
        if (query.isEmpty()) {
            return filteredSuggestions(values, "", cursor, cursor, limit);
        }
        boolean exact = values.stream().anyMatch(candidate -> candidate.text().equalsIgnoreCase(query));
        return exact || looksLikeCompleteScalar(query)
                ? punctuationSuggestions(cursor, cursor, completion, limit)
                : filteredSuggestions(values, query, start, cursor, limit);
    }

    private static List<Suggestion> rootContinuationSuggestions(int cursor, int limit) {
        return punctuationSuggestions(
                cursor,
                cursor,
                List.of(
                        new Template(",", "Set another item-data field."),
                        new Template("}", "Finish item data.")
                ),
                limit
        );
    }

    private static List<Suggestion> filteredSuggestions(
            List<Template> candidates,
            String query,
            int start,
            int cursor,
            int limit
    ) {
        String normalized = normalizeWords(query);
        List<Suggestion> suggestions = new ArrayList<>();
        StringRange range = StringRange.between(Math.max(0, start), Math.max(start, cursor));
        for (Template candidate : candidates) {
            if (!normalized.isBlank()
                    && !normalizeWords(candidate.text()).startsWith(normalized)
                    && !normalizeWords(candidate.text()).contains(normalized)) {
                continue;
            }
            suggestions.add(new Suggestion(
                    range,
                    candidate.text(),
                    new LiteralMessage(candidate.description())
            ));
            if (suggestions.size() >= limit) {
                break;
            }
        }
        return List.copyOf(suggestions);
    }

    private static List<Suggestion> punctuationSuggestions(
            int start,
            int cursor,
            List<Template> candidates,
            int limit
    ) {
        return filteredSuggestions(candidates, "", start, cursor, limit);
    }

    private static List<Suggestion> registrySuggestions(
            Iterable<Identifier> identifiers,
            String query,
            int start,
            int cursor,
            int limit,
            String detail
    ) {
        String normalized = normalizeWords(query);
        List<Identifier> matches = new ArrayList<>();
        for (Identifier identifier : identifiers) {
            String id = identifier.toString();
            if (normalized.isBlank()
                    || normalizeWords(id).contains(normalized)
                    || normalizeWords(identifier.getPath()).contains(normalized)) {
                matches.add(identifier);
            }
        }
        matches.sort(Comparator.comparing(Identifier::toString));
        StringRange range = StringRange.between(start, cursor);
        return matches.stream()
                .limit(limit)
                .map(identifier -> new Suggestion(
                        range,
                        identifier.toString(),
                        new LiteralMessage(detail)
                ))
                .toList();
    }

    private static Identifier resolveIdentifier(Iterable<Identifier> identifiers, String requested) {
        String normalized = normalizeWords(requested);
        if (normalized.isBlank()) {
            return null;
        }
        Map<String, Identifier> exact = new LinkedHashMap<>();
        List<Identifier> partial = new ArrayList<>();
        for (Identifier identifier : identifiers) {
            exact.put(normalizeWords(identifier.toString()), identifier);
            exact.put(normalizeWords(identifier.getPath()), identifier);
            if (normalizeWords(identifier.toString()).endsWith(normalized)
                    || normalizeWords(identifier.getPath()).equals(normalized)) {
                partial.add(identifier);
            }
        }
        Identifier direct = exact.get(normalized);
        if (direct != null) {
            return direct;
        }
        return partial.size() == 1 ? partial.get(0) : null;
    }

    private static boolean isItemCommand(String beforeCursor) {
        String lower = beforeCursor.stripLeading().toLowerCase(Locale.ROOT);
        if (lower.startsWith("/")) {
            lower = lower.substring(1);
        }
        return lower.startsWith("give ")
                || lower.startsWith("clear ")
                || lower.startsWith("item ")
                || lower.startsWith("replaceitem ");
    }

    private static boolean hasCompleteItemIdentifier(
            String beforeCursor,
            Iterable<Identifier> itemIds
    ) {
        String lower = beforeCursor.toLowerCase(Locale.ROOT);
        if (lower.indexOf('{') >= 0) {
            return false;
        }
        String[] tokens = lower.strip().split("\\s+");
        if (tokens.length < 3) {
            return false;
        }
        Map<String, Boolean> knownItems = new LinkedHashMap<>();
        for (Identifier identifier : itemIds) {
            knownItems.put(identifier.toString(), true);
        }
        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.startsWith("@") || token.matches("\\d+")) {
                continue;
            }
            Identifier identifier = Identifier.tryParse(token);
            if (identifier != null && knownItems.containsKey(identifier.toString())) {
                return beforeCursor.stripTrailing().endsWith(token);
            }
        }
        return false;
    }

    private static int itemCompoundStart(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return -1;
        }
        int depth = 0;
        boolean quoted = false;
        char quote = '\0';
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == quote && (index == 0 || text.charAt(index - 1) != '\\')) {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
        }
        return depth > 0 ? start : -1;
    }

    private static boolean insideItemCompound(String text) {
        return itemCompoundStart(text) >= 0;
    }

    private static int nbtTokenStart(String text, int minimum) {
        boolean quoted = false;
        char quote = '\0';
        int squareDepth = 0;
        int braceDepth = 0;
        int start = minimum;
        for (int index = minimum; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == quote && text.charAt(Math.max(0, index - 1)) != '\\') {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == '[') {
                squareDepth++;
            } else if (character == ']') {
                squareDepth = Math.max(0, squareDepth - 1);
            } else if (character == '{') {
                braceDepth++;
            } else if (character == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (character == ',' && squareDepth == 0 && braceDepth == 0) {
                start = index + 1;
            }
        }
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return start;
    }

    private static int topLevelSegmentStart(String text, int minimum) {
        int start = Math.max(0, Math.min(minimum, text.length()));
        int squareDepth = 0;
        int braceDepth = 0;
        boolean quoted = false;
        char quote = '\0';
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == quote && !escaped(text, index)) {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == '[') {
                squareDepth++;
            } else if (character == ']') {
                squareDepth = Math.max(0, squareDepth - 1);
            } else if (character == '{') {
                braceDepth++;
            } else if (character == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (character == ',' && squareDepth == 0 && braceDepth == 0) {
                start = index + 1;
            }
        }
        return start;
    }

    private static int topLevelColon(String text) {
        int squareDepth = 0;
        int braceDepth = 0;
        boolean quoted = false;
        char quote = '\0';
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == quote && !escaped(text, index)) {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == '[') {
                squareDepth++;
            } else if (character == ']') {
                squareDepth = Math.max(0, squareDepth - 1);
            } else if (character == '{') {
                braceDepth++;
            } else if (character == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (character == ':' && squareDepth == 0 && braceDepth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int lastUnclosed(String text, char open, char close) {
        int depth = 0;
        int last = -1;
        boolean quoted = false;
        char quote = '\0';
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == quote && !escaped(text, index)) {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == open) {
                depth++;
                last = index;
            } else if (character == close && depth > 0) {
                depth--;
                if (depth == 0) {
                    last = -1;
                }
            }
        }
        return depth > 0 ? last : -1;
    }

    private static int matchingClose(String text, int opening, char open, char close) {
        int depth = 0;
        boolean quoted = false;
        char quote = '\0';
        for (int index = Math.max(0, opening); index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == quote && !escaped(text, index)) {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == open) {
                depth++;
            } else if (character == close && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int start) {
        int index = Math.max(0, Math.min(start, text.length()));
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int leadingWhitespace(String text) {
        return skipWhitespace(text, 0);
    }

    private static boolean escaped(String text, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) {
            slashes++;
        }
        return slashes % 2 != 0;
    }

    private static boolean closedQuotedValue(String value) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.length() >= 2
                && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)
                && !escaped(trimmed, trimmed.length() - 1);
    }

    private static boolean looksLikeCompleteScalar(String value) {
        return value != null && value.matches("[-+]?(?:\\d+(?:\\.\\d+)?)(?:[bBsSlLfFdD])?");
    }

    private static String cleanItemPhrase(String value) {
        return value == null
                ? ""
                : value.replaceAll("(?i)\\b(?:me|a|an|some|the|item|named|called)\\b", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String normalizeWords(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String title(String path) {
        String[] words = path.replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    public record Template(String text, String description) {
    }

    public record Knowledge(String format, List<Template> templates, String guidance) {
    }

    public record GroundedCommand(
            String label,
            String command,
            String itemId,
            String enchantmentId,
            int level
    ) {
        public String maskedLink() {
            return "[" + label + "](" + command + ")";
        }
    }
}
