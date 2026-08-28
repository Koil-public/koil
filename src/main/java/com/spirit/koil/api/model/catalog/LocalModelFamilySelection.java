package com.spirit.koil.api.model.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Clean family/variant projection for model selectors.
 *
 * <p>The projection deliberately calculates family-relative complexity from
 * every known catalog capability, while exposing only installed runnable
 * variants. Runtime formats and quantizations remain below this boundary.</p>
 */
public final class LocalModelFamilySelection {
    private static final List<String> TIERS = List.of(
        "Low", "Medium-Low", "Medium", "High", "Extremely High"
    );

    private LocalModelFamilySelection() {
    }

    public static List<FamilyOption> families(
            List<LocalModelCatalogEntry> known,
            List<LocalModelCatalogEntry> installed
    ) {
        Map<String, List<LocalModelCatalogEntry>> installedByFamily = groupByFamily(installed);
        return known.stream()
                .map(LocalModelCatalogEntry::family)
                .filter(family -> !family.isBlank() && installedByFamily.containsKey(key(family)))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(family -> new FamilyOption(family, variants(family, known, installedByFamily.get(key(family)))))
                .filter(option -> !option.variants().isEmpty())
                .toList();
    }

    public static List<VariantOption> variants(
            String family,
            List<LocalModelCatalogEntry> known,
            List<LocalModelCatalogEntry> installed
    ) {
        if (family == null || family.isBlank()) return List.of();
        List<LocalModelCatalogEntry> knownFamily = known == null ? List.of() : known.stream()
                .filter(entry -> entry.family().equalsIgnoreCase(family.strip()))
                .toList();
        List<LocalModelCatalogEntry> installedFamily = installed == null ? List.of() : installed.stream()
                .filter(LocalModelCatalogEntry::runnable)
                .filter(entry -> entry.family().equalsIgnoreCase(family.strip()))
                .toList();
        if (knownFamily.isEmpty() || installedFamily.isEmpty()) return List.of();

        List<Capability> hierarchy = knownFamily.stream()
                .map(entry -> new Capability(
                        entry.canonical().baseCapability(),
                        entry.canonical().capabilitySortValue()))
                .filter(capability -> !capability.label().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        capability -> key(capability.label()),
                        capability -> capability,
                        (left, right) -> left.sortValue() >= right.sortValue() ? left : right,
                        LinkedHashMap::new
                ))
                .values().stream()
                .sorted(Comparator.comparingDouble(Capability::sortValue)
                        .thenComparing(Capability::label, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<String, LocalModelCatalogEntry> exactVariants = new LinkedHashMap<>();
        installedFamily.stream()
                .sorted(implementationPreference())
                .forEach(entry -> exactVariants.putIfAbsent(key(entry.canonical().variantKey()), entry));

        List<VariantOption> options = new ArrayList<>();
        for (LocalModelCatalogEntry entry : exactVariants.values()) {
            int rank = rankOf(hierarchy, entry.canonical().baseCapability());
            String tier = tier(rank, hierarchy.size());
            String label = variantLabel(tier, entry.canonical());
            options.add(new VariantOption(label, entry.id(), entry.canonical().variantKey()));
        }
        options.sort(Comparator
                .comparingInt((VariantOption option) -> tierIndex(option.label()))
                .thenComparing(VariantOption::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(VariantOption::catalogId));
        return List.copyOf(options);
    }

    public static LocalModelCatalogEntry resolve(
            String family,
            String visibleComplexity,
            List<LocalModelCatalogEntry> known,
            List<LocalModelCatalogEntry> installed
    ) {
        if (visibleComplexity == null) return null;
        VariantOption option = variants(family, known, installed).stream()
                .filter(candidate -> candidate.label().equals(visibleComplexity))
                .findFirst().orElse(null);
        if (option == null) return null;
        return installed.stream().filter(entry -> entry.id().equals(option.catalogId())).findFirst().orElse(null);
    }

    private static Map<String, List<LocalModelCatalogEntry>> groupByFamily(List<LocalModelCatalogEntry> entries) {
        Map<String, List<LocalModelCatalogEntry>> grouped = new LinkedHashMap<>();
        if (entries == null) return grouped;
        for (LocalModelCatalogEntry entry : entries) {
            if (entry == null || !entry.runnable()) continue;
            grouped.computeIfAbsent(key(entry.family()), ignored -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    private static Comparator<LocalModelCatalogEntry> implementationPreference() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return Comparator
                .comparingInt((LocalModelCatalogEntry entry) -> formatRank(entry, os))
                .thenComparingLong(LocalModelCatalogEntry::estimatedRecommendedMemoryBytes)
                .thenComparing(LocalModelCatalogEntry::id);
    }

    private static int formatRank(LocalModelCatalogEntry entry, String os) {
        String formats = String.join(" ", entry.canonical().runtimeFormats()).toLowerCase(Locale.ROOT);
        if (os.contains("mac") && formats.contains("mlx")) return 0;
        if (formats.contains("gguf")) return 1;
        if (formats.contains("fp8")) return 2;
        if (formats.contains("safetensors")) return 3;
        return 4;
    }

    private static int rankOf(List<Capability> hierarchy, String capability) {
        for (int index = 0; index < hierarchy.size(); index++) {
            if (hierarchy.get(index).label().equalsIgnoreCase(capability)) return index;
        }
        return 0;
    }

    private static String tier(int rank, int count) {
        if (count <= 1) return "Medium";
        int bucket = (int) Math.round(
                Math.max(0, Math.min(count - 1, rank)) * (TIERS.size() - 1.0D) / (count - 1.0D)
        );
        return TIERS.get(bucket);
    }

    private static String variantLabel(String tier, LocalModelCanonicalMetadata metadata) {
        List<String> words = new ArrayList<>();
        words.add(tier);
        String type = metadata.modelType();
        if (!type.isBlank() && !"Base".equalsIgnoreCase(type)) words.add(type);
        words.addAll(metadata.modifiers());
        return String.join(" ", words);
    }

    private static int tierIndex(String label) {
        for (int index = 0; index < TIERS.size(); index++) {
            if (label.startsWith(TIERS.get(index))) return index;
        }
        return TIERS.size();
    }

    private static String key(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public record FamilyOption(String label, List<VariantOption> variants) {
        public FamilyOption {
            label = label == null ? "" : label.strip();
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    public record VariantOption(String label, String catalogId, String canonicalVariantKey) {
        public VariantOption {
            label = label == null ? "" : label.strip();
            catalogId = catalogId == null ? "" : catalogId.strip();
            canonicalVariantKey = canonicalVariantKey == null ? "" : canonicalVariantKey.strip();
        }
    }

    private record Capability(String label, double sortValue) { }
}
