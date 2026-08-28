package com.spirit.koil.api.model.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical identity/capability metadata shared by catalog and clean selectors. */
public record LocalModelCanonicalMetadata(
        String family,
        String generation,
        String baseCapability,
        double totalParametersBillions,
        double activeParametersBillions,
        Architecture architecture,
        String modelType,
        List<String> modifiers,
        List<String> modalities,
        int nativeContextTokens,
        int extendedContextTokens,
        String canonicalRepository,
        String canonicalParent,
        Maturity maturity,
        String chatTemplateAdapter,
        String reasoningParserAdapter,
        String toolParserAdapter,
        List<String> runtimeFormats,
        boolean runnable,
        String unavailableReason
) {
    private static final Pattern CAPABILITY = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*([BT])(?:-A(\\d+(?:\\.\\d+)?)[BT]?)?");

    public LocalModelCanonicalMetadata {
        family = safe(family);
        generation = safe(generation);
        baseCapability = safe(baseCapability);
        totalParametersBillions = Math.max(0.0D, totalParametersBillions);
        activeParametersBillions = Math.max(0.0D, activeParametersBillions);
        architecture = architecture == null ? Architecture.UNKNOWN : architecture;
        modelType = safe(modelType);
        modifiers = immutableWords(modifiers);
        modalities = immutableWords(modalities);
        nativeContextTokens = Math.max(0, nativeContextTokens);
        extendedContextTokens = Math.max(nativeContextTokens, extendedContextTokens);
        canonicalRepository = safe(canonicalRepository);
        canonicalParent = safe(canonicalParent);
        maturity = maturity == null ? Maturity.SUPPORTED : maturity;
        chatTemplateAdapter = safe(chatTemplateAdapter);
        reasoningParserAdapter = safe(reasoningParserAdapter);
        toolParserAdapter = safe(toolParserAdapter);
        runtimeFormats = immutableWords(runtimeFormats);
        unavailableReason = safe(unavailableReason);
        if (family.isBlank() || baseCapability.isBlank()) {
            throw new IllegalArgumentException("canonical model family/capability is incomplete");
        }
        if (runnable && runtimeFormats.isEmpty()) {
            throw new IllegalArgumentException("runnable model metadata requires a runtime format");
        }
    }

    public static LocalModelCanonicalMetadata legacy(
            String displayName,
            String parameterCount,
            String quantization,
            int contextTokens,
            boolean runnable
    ) {
        String display = safe(displayName);
        String family = legacyFamily(display);
        ParameterShape parameters = parseParameters(parameterCount);
        String type = legacyType(display);
        String capability = parameters.label().isBlank() ? safe(parameterCount) : parameters.label();
        return new LocalModelCanonicalMetadata(
                family,
                family,
                capability.isBlank() ? "Unknown" : capability,
                parameters.totalBillions(),
                parameters.activeBillions(),
                parameters.activeBillions() > 0.0D && parameters.activeBillions() < parameters.totalBillions()
                        ? Architecture.MOE : Architecture.DENSE,
                type,
                legacyModifiers(display),
                List.of("text"),
                contextTokens,
                contextTokens,
                "",
                "",
                Maturity.SUPPORTED,
                "llama_cpp_embedded",
                type.equals("Thinking") || type.equals("Reasoning") ? "model_native" : "",
                "llama_cpp_native",
                List.of(safe(quantization).isBlank() ? "GGUF" : "GGUF " + safe(quantization)),
                runnable,
                runnable ? "" : "No supported Koil implementation is registered."
        );
    }

    public String variantKey() {
        return family + "|" + baseCapability + "|" + modelType + "|" + String.join(",", modifiers);
    }

    public double capabilitySortValue() {
        if (activeParametersBillions > 0.0D && architecture == Architecture.MOE) {
            // Active experts represent compute, while a bounded storage term prevents
            // a 1T MoE from being treated as equivalent to a tiny dense model.
            return activeParametersBillions + Math.sqrt(totalParametersBillions) * 0.15D;
        }
        return totalParametersBillions;
    }

    private static String legacyFamily(String display) {
        String[] words = display.split("\\s+");
        StringBuilder family = new StringBuilder();
        for (String word : words) {
            if (CAPABILITY.matcher(word).matches() || isTypeWord(word) || isModifierWord(word)) break;
            if (!family.isEmpty()) family.append(' ');
            family.append(word);
        }
        return family.isEmpty() ? display : family.toString();
    }

    private static String legacyType(String display) {
        String lower = display.toLowerCase(Locale.ROOT);
        if (lower.contains("thinking")) return "Thinking";
        if (lower.contains("reasoning")) return "Reasoning";
        if (lower.contains("coder") || lower.contains("code")) return "Coder";
        if (lower.contains("instruct")) return "Instruct";
        if (lower.contains("chat")) return "Chat";
        return "Base";
    }

    private static List<String> legacyModifiers(String display) {
        String lower = display.toLowerCase(Locale.ROOT);
        List<String> modifiers = new ArrayList<>();
        if (lower.contains("uncensored")) modifiers.add("Uncensored");
        if (lower.contains("abliterated")) modifiers.add("Abliterated");
        return List.copyOf(modifiers);
    }

    private static ParameterShape parseParameters(String value) {
        Matcher matcher = CAPABILITY.matcher(safe(value));
        if (!matcher.find()) return new ParameterShape(0.0D, 0.0D, safe(value));
        double total = Double.parseDouble(matcher.group(1));
        if ("T".equalsIgnoreCase(matcher.group(2))) total *= 1_000.0D;
        double active = matcher.group(3) == null ? 0.0D : Double.parseDouble(matcher.group(3));
        return new ParameterShape(total, active, matcher.group());
    }

    private static boolean isTypeWord(String value) {
        String word = value.toLowerCase(Locale.ROOT);
        return List.of("base", "chat", "instruct", "thinking", "reasoning", "agent", "coder", "tool", "vision", "research").contains(word);
    }

    private static boolean isModifierWord(String value) {
        String word = value.toLowerCase(Locale.ROOT);
        return List.of("uncensored", "abliterated", "specialized", "fine-tuned").contains(word);
    }

    private static List<String> immutableWords(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(LocalModelCanonicalMetadata::safe).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    public enum Architecture { DENSE, MOE, HYBRID, DIFFUSION, EMBEDDING, RERANKER, AUDIO, UNKNOWN }
    public enum Maturity { PREFERRED, SUPPORTED, SPECIALIZED, FRONTIER, EXPERIMENTAL }
    private record ParameterShape(double totalBillions, double activeBillions, String label) { }
}
