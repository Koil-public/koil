package com.spirit.koil.api.model.catalog;

import java.net.URI;
import java.util.List;

public final class LocalModelFamilySelectionProof {
    private LocalModelFamilySelectionProof() {
    }

    public static void main(String[] args) {
        List<LocalModelCatalogEntry> known = List.of(
                entry("tiny", "Example", "1B", 1, "Instruct", List.of()),
                entry("small", "Example", "3B", 3, "Instruct", List.of()),
                entry("middle", "Example", "7B", 7, "Thinking", List.of()),
                entry("large", "Example", "27B", 27, "Thinking", List.of("Uncensored")),
                entry("giant", "Example", "70B", 70, "Reasoning", List.of())
        );
        List<LocalModelCatalogEntry> installed = List.of(known.get(1), known.get(2), known.get(3));

        List<LocalModelFamilySelection.FamilyOption> families = LocalModelFamilySelection.families(known, installed);
        require(families.size() == 1 && "Example".equals(families.get(0).label()), "Active leaked variant details");
        List<LocalModelFamilySelection.VariantOption> variants = families.get(0).variants();
        require(variants.stream().anyMatch(option -> option.label().equals("Medium-Low Instruct")), "missing family-relative 3B tier");
        require(variants.stream().anyMatch(option -> option.label().equals("Medium Thinking")), "Thinking did not inherit its base tier");
        require(variants.stream().anyMatch(option -> option.label().equals("High Thinking Uncensored")), "modifier changed or leaked implementation identity");
        require(variants.stream().noneMatch(option -> option.label().matches(".*(GGUF|Q4|MLX|27B).*")), "technical detail leaked into Complexity");
        LocalModelCatalogEntry resolved = LocalModelFamilySelection.resolve("Example", "High Thinking Uncensored", known, installed);
        require(resolved != null && resolved.id().equals("large"), "visible Complexity did not resolve exactly");
        require(LocalModelFamilySelection.families(known, List.of()).isEmpty(), "Active included an uninstalled family");
        System.out.println("Local model family selection proof passed.");
    }

    private static LocalModelCatalogEntry entry(
            String id,
            String family,
            String capability,
            double parameters,
            String type,
            List<String> modifiers
    ) {
        return new LocalModelCatalogEntry(
                id, family + " " + capability, "proof", "proof", id, capability, "Q4_K_M", "proof",
                32_768, 1, 1, 50, true,
                List.of(LocalModelCapabilityTag.CHAT, LocalModelCapabilityTag.AUTOMATION_TOOLS), "proof",
                List.of(new ModelArtifact(id + ".gguf", URI.create("https://example.invalid/" + id), 1, "a".repeat(64))),
                new LocalModelCanonicalMetadata(
                        family, family, capability, parameters, 0,
                        LocalModelCanonicalMetadata.Architecture.DENSE, type, modifiers, List.of("text"),
                        32_768, 32_768, "proof/" + id, "", LocalModelCanonicalMetadata.Maturity.SUPPORTED,
                        "proof", type.equals("Thinking") ? "proof" : "", "proof", List.of("GGUF Q4_K_M"), true, ""
                )
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
