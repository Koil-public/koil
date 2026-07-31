package com.spirit.koil.api.automation.ktl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationRequest;
import com.spirit.koil.api.automation.capability.AutomationCapabilityException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read/execute boundary for already compiled KTL task templates.
 *
 * <p>This registry exposes task composition, not Java primitives. A caller can
 * search registered task metadata and invoke an exact compiled template with
 * validated parameters. It cannot provide KTL source text, invent a path, or
 * call a primitive id directly.</p>
 */
public final class AutomationKtlSkillRegistry {
    private static final Pattern SKILL_ID = Pattern.compile("^[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*$");
    private static final int MAXIMUM_PARAMETERS = 48;
    private static final int MAXIMUM_STRING_LENGTH = 512;

    private AutomationKtlSkillRegistry() {
    }

    public static List<SkillDescriptor> search(String query, int requestedLimit) {
        return search(KtlCompilerService.getInstance().assets(), query, requestedLimit);
    }

    public static List<SkillDescriptor> search(
            KtlCompilerService.CompiledAssets assets,
            String query,
            int requestedLimit
    ) {
        String normalizedQuery = normalizeSearch(query);
        int limit = Math.max(1, Math.min(24, requestedLimit));
        List<ScoredSkill> matches = new ArrayList<>();
        for (SkillDescriptor descriptor : descriptors(assets)) {
            int score = score(descriptor, normalizedQuery);
            if (normalizedQuery.isBlank() || score > 0) {
                matches.add(new ScoredSkill(descriptor, score));
            }
        }
        matches.sort(Comparator
                .comparingInt(ScoredSkill::score).reversed()
                .thenComparing(match -> match.descriptor().id()));
        return matches.stream().limit(limit).map(ScoredSkill::descriptor).toList();
    }

    public static SkillDescriptor inspect(String rawSkillId) {
        return inspect(KtlCompilerService.getInstance().assets(), rawSkillId);
    }

    public static SkillDescriptor inspect(
            KtlCompilerService.CompiledAssets assets,
            String rawSkillId
    ) {
        String skillId = normalizeSkillId(rawSkillId);
        return descriptors(assets).stream()
                .filter(descriptor -> descriptor.id().equals(skillId))
                .findFirst()
                .orElseThrow(() -> new AutomationCapabilityException(
                        "unknown_ktl_skill",
                        "Unknown registered KTL skill: " + skillId
                ));
    }

    public static PreparedSkill prepare(
            String rawSkillId,
            JsonObject parameters,
            UUID executionId
    ) {
        return prepare(KtlCompilerService.getInstance().assets(), rawSkillId, parameters, executionId);
    }

    public static PreparedSkill prepare(
            KtlCompilerService.CompiledAssets assets,
            String rawSkillId,
            JsonObject parameters,
            UUID executionId
    ) {
        SkillDescriptor descriptor = inspect(assets, rawSkillId);
        JsonObject safeParameters = parameters == null ? new JsonObject() : parameters.deepCopy();
        if (safeParameters.size() > MAXIMUM_PARAMETERS) {
            throw new AutomationCapabilityException(
                    "too_many_ktl_parameters",
                    "KTL skill parameters are limited to " + MAXIMUM_PARAMETERS + "."
            );
        }
        Set<String> supported = new LinkedHashSet<>(descriptor.parameters());
        for (String key : safeParameters.keySet()) {
            if (!supported.contains(key)) {
                throw new AutomationCapabilityException(
                        "unknown_ktl_parameter",
                        descriptor.id() + " does not accept parameter '" + key + "'."
                );
            }
        }
        for (String required : descriptor.requiredParameters()) {
            if (!safeParameters.has(required) || safeParameters.get(required).isJsonNull()) {
                throw new AutomationCapabilityException(
                        "missing_ktl_parameter",
                        descriptor.id() + " requires parameter '" + required + "'."
                );
            }
        }

        StringBuilder invocation = new StringBuilder(descriptor.id()).append(".ktl");
        Map<String, Object> normalizedParameters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : safeParameters.entrySet()) {
            String encoded = encodeParameter(entry.getKey(), entry.getValue());
            invocation.append(' ').append(encoded);
            normalizedParameters.put(entry.getKey(), primitiveValue(entry.getValue()));
        }
        UUID safeExecutionId = executionId == null ? UUID.randomUUID() : executionId;
        return new PreparedSkill(
                descriptor,
                Map.copyOf(normalizedParameters),
                new AutomationRequest(invocation.toString(), true, true, safeExecutionId)
        );
    }

    private static List<SkillDescriptor> descriptors(KtlCompilerService.CompiledAssets assets) {
        if (assets == null) {
            return List.of();
        }
        List<SkillDescriptor> descriptors = new ArrayList<>(assets.templates.size());
        for (KtlCompilerService.CompiledTaskTemplate template : assets.templates.values()) {
            KtlCompilerService.CompiledTemplateMetadata metadata =
                    assets.templateMetadata.get(template.templateId());
            if (metadata != null && !metadata.modelCallable()) {
                continue;
            }
            List<String> required = metadata == null ? List.of() : metadata.requiredParams();
            List<String> optional = metadata == null
                    ? template.params().stream().filter(parameter -> !required.contains(parameter)).toList()
                    : metadata.optionalParams();
            List<String> tags = metadata == null ? List.of() : metadata.tags();
            List<String> targetKinds = metadata == null ? List.of() : metadata.targetKinds();
            List<String> delegates = template.steps().stream()
                    .filter(step -> "delegate".equals(step.type()) && !step.delegate().isBlank())
                    .map(step -> firstToken(step.delegate()).replace(".ktl", ""))
                    .distinct()
                    .limit(24)
                    .toList();
            descriptors.add(new SkillDescriptor(
                    template.templateId(),
                    template.semanticOperationId(),
                    template.params(),
                    required,
                    optional,
                    tags,
                    targetKinds,
                    delegates,
                    template.steps().size(),
                    metadata == null ? "" : metadata.description(),
                    metadata == null ? "public" : metadata.visibility(),
                    metadata == null ? List.of() : metadata.resourceLocks(),
                    metadata == null ? List.of() : metadata.sideEffects(),
                    metadata == null ? 1_200 : metadata.timeoutTicks(),
                    metadata == null ? "fail" : metadata.failurePolicy(),
                    metadata == null ? "" : metadata.recoveryTask()
            ));
        }
        descriptors.sort(Comparator.comparing(SkillDescriptor::id));
        return List.copyOf(descriptors);
    }

    private static String normalizeSkillId(String rawSkillId) {
        String skillId = rawSkillId == null ? "" : rawSkillId.strip().toLowerCase(Locale.ROOT);
        if (skillId.endsWith(".ktl")) {
            skillId = skillId.substring(0, skillId.length() - 4);
        }
        if (!SKILL_ID.matcher(skillId).matches()) {
            throw new AutomationCapabilityException(
                    "invalid_ktl_skill",
                    "KTL skill must be an exact registered task id, not a file path or primitive."
            );
        }
        return skillId;
    }

    private static String encodeParameter(String key, JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new AutomationCapabilityException(
                    "invalid_ktl_parameter",
                    "KTL parameter '" + key + "' must be a string, number, or boolean."
            );
        }
        String value = element.getAsString();
        if (value.length() > MAXIMUM_STRING_LENGTH
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\0') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\\') >= 0
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new AutomationCapabilityException(
                    "invalid_ktl_parameter",
                    "KTL parameter '" + key + "' contains unsupported or excessive text."
            );
        }
        String token = key + "=" + value;
        return value.chars().anyMatch(Character::isWhitespace) ? '"' + token + '"' : token;
    }

    private static Object primitiveValue(JsonElement element) {
        if (element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsNumber();
        }
        return element.getAsString();
    }

    private static int score(SkillDescriptor descriptor, String query) {
        if (query.isBlank()) {
            return 1;
        }
        String id = normalizeSearch(descriptor.id());
        String semantic = normalizeSearch(descriptor.semanticOperation());
        int score = 0;
        if (id.equals(query)) {
            score += 100;
        }
        if (id.contains(query)) {
            score += 30;
        }
        for (String word : query.split(" ")) {
            if (word.isBlank()) {
                continue;
            }
            if (id.contains(word)) {
                score += 8;
            }
            if (semantic.contains(word)) {
                score += 5;
            }
            if (descriptor.tags().stream().map(AutomationKtlSkillRegistry::normalizeSearch)
                    .anyMatch(tag -> tag.contains(word))) {
                score += 4;
            }
        }
        return score;
    }

    private static String normalizeSearch(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String firstToken(String value) {
        int separator = value.indexOf(' ');
        return separator < 0 ? value : value.substring(0, separator);
    }

    public record SkillDescriptor(
            String id,
            String semanticOperation,
            List<String> parameters,
            List<String> requiredParameters,
            List<String> optionalParameters,
            List<String> tags,
            List<String> targetKinds,
            List<String> delegates,
            int stepCount,
            String description,
            String visibility,
            List<String> resourceLocks,
            List<String> sideEffects,
            int timeoutTicks,
            String failurePolicy,
            String recoveryTask
    ) {
        public SkillDescriptor {
            parameters = List.copyOf(parameters);
            requiredParameters = List.copyOf(requiredParameters);
            optionalParameters = List.copyOf(optionalParameters);
            tags = List.copyOf(tags);
            targetKinds = List.copyOf(targetKinds);
            delegates = List.copyOf(delegates);
            description = description == null ? "" : description;
            visibility = visibility == null ? "public" : visibility;
            resourceLocks = List.copyOf(resourceLocks);
            sideEffects = List.copyOf(sideEffects);
            failurePolicy = failurePolicy == null ? "fail" : failurePolicy;
            recoveryTask = recoveryTask == null ? "" : recoveryTask;
        }
    }

    public record PreparedSkill(
            SkillDescriptor descriptor,
            Map<String, Object> parameters,
            AutomationRequest request
    ) {
        public PreparedSkill {
            parameters = Map.copyOf(parameters);
        }
    }

    private record ScoredSkill(SkillDescriptor descriptor, int score) {
    }
}
