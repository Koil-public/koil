package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;
import com.spirit.koil.api.model.skill.KoilSkillDefinition;
import com.spirit.koil.api.model.skill.KoilSkillMode;
import com.spirit.koil.api.model.skill.KoilSkillRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Read-only model tools for discovering and inspecting Agent Skills. */
public final class AgentSkillModelToolRegistry {
    public static final String SEARCH = "skill.search";
    public static final String INSPECT = "skill.inspect";
    public static final String READ_RESOURCE = "skill.read_resource";
    private static final int MAX_RESOURCE_BYTES = 256 * 1024;
    private static final int DEFAULT_RESOURCE_CHARACTERS = 6_000;
    private static final int MAX_RESOURCE_CHARACTERS = 12_000;
    private static final int MAX_INSPECT_INSTRUCTION_CHARACTERS = 6_000;

    private static final List<ModelToolDefinition> TOOLS = List.of(
            new ModelToolDefinition(
                    SEARCH,
                    "Search Koil's installed Agent Skills and compare multiple relevant candidates. Use this when several Skills may fit, or when the preselected procedural guidance is insufficient.",
                    searchSchema(),
                    List.of(), Set.of(), true, Duration.ofSeconds(3), false, false,
                    Set.of("completed", "failed"),
                    ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.WORKSPACE, ToolExecutionPolicy.CostClass.CHEAP)
            ),
            new ModelToolDefinition(
                    INSPECT,
                    "Inspect one installed Agent Skill, including its instructions, declared tool hints, source, and resource root. This grants no tool permission.",
                    inspectSchema(),
                    List.of(), Set.of(), true, Duration.ofSeconds(3), false, false,
                    Set.of("completed", "failed"),
                    ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.WORKSPACE, ToolExecutionPolicy.CostClass.CHEAP)
            ),
            new ModelToolDefinition(
                    READ_RESOURCE,
                    "Read one text resource bundled under an installed Agent Skill. The path must remain inside that Skill's own directory. Scripts are returned as inert text and are never executed by this tool.",
                    resourceSchema(),
                    List.of(), Set.of(), true, Duration.ofSeconds(4), false, false,
                    Set.of("completed", "failed"),
                    ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.WORKSPACE, ToolExecutionPolicy.CostClass.MODERATE)
            )
    );

    private AgentSkillModelToolRegistry() {
    }

    public static String version() {
        return "agent-skills-v3";
    }

    public static List<ModelToolDefinition> modelTools() {
        return TOOLS;
    }

    public static boolean supports(String toolId) {
        return SEARCH.equals(toolId) || INSPECT.equals(toolId) || READ_RESOURCE.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        try {
            return CompletableFuture.completedFuture(switch (call.toolId()) {
                case SEARCH -> search(call);
                case INSPECT -> inspect(call);
                case READ_RESOURCE -> readResource(call);
                default -> failure(call, "unknown_skill_tool", "Unknown Agent Skill tool: " + call.toolId());
            });
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(call, "skill_tool_failed", message(exception)));
        }
    }

    private static ModelToolResult search(ModelToolCall call) {
        String query = string(call.arguments(), "query");
        String modeValue = string(call.arguments(), "mode");
        int limit = integer(call.arguments(), "limit", 8, 1, 24);
        KoilSkillMode mode = parseMode(modeValue);
        Set<String> queryTokens = tokens(query);
        List<KoilSkillDefinition> selected = KoilSkillRegistry.definitions().stream()
                .filter(skill -> skill.modes().contains(mode))
                .map(skill -> new SkillScore(skill, skillScore(skill, query, queryTokens)))
                .filter(candidate -> query.isBlank() || candidate.score() > 0)
                .sorted(Comparator.comparingInt(SkillScore::score).reversed()
                        .thenComparing(candidate -> candidate.skill().id()))
                .limit(limit)
                .map(SkillScore::skill)
                .toList();
        JsonArray array = new JsonArray();
        selected.forEach(skill -> array.add(summary(skill, false)));
        JsonObject output = new JsonObject();
        output.addProperty("query", query);
        output.addProperty("mode", mode.name().toLowerCase(Locale.ROOT));
        output.addProperty("installedCount", KoilSkillRegistry.definitions().size());
        output.addProperty("resultCount", array.size());
        output.add("skills", array);
        return completed(call, output, "Installed Agent Skills were searched.");
    }

    private static ModelToolResult inspect(ModelToolCall call) {
        KoilSkillDefinition skill = requireSkill(string(call.arguments(), "skill"));
        JsonObject output = summary(skill, true);
        return completed(call, output, "Agent Skill inspected. The instructions are procedural guidance only.");
    }

    private static ModelToolResult readResource(ModelToolCall call) {
        KoilSkillDefinition skill = requireSkill(string(call.arguments(), "skill"));
        if (skill.root().isBlank()) {
            return failure(call, "skill_has_no_resource_root", "This built-in Skill has no filesystem resource root.");
        }
        String relativeValue = string(call.arguments(), "path");
        if (relativeValue.isBlank()) return failure(call, "skill_resource_path_required", "path is required.");
        Path root = Path.of(skill.root()).toAbsolutePath().normalize();
        Path requested = root.resolve(relativeValue).normalize();
        if (!requested.startsWith(root)) {
            return failure(call, "skill_resource_path_escape", "The requested path escapes the Skill root.");
        }
        if (!Files.isRegularFile(requested)) {
            return failure(call, "skill_resource_not_found", "No regular file exists at that Skill-relative path.");
        }
        try {
            long size = Files.size(requested);
            if (size > MAX_RESOURCE_BYTES) {
                return failure(call, "skill_resource_too_large", "Skill resource exceeds the bounded read limit of " + MAX_RESOURCE_BYTES + " bytes.");
            }
            String content = Files.readString(requested, StandardCharsets.UTF_8);
            int offset = integer(call.arguments(), "offset", 0, 0, Math.max(0, content.length()));
            int maxCharacters = integer(call.arguments(), "max_chars", DEFAULT_RESOURCE_CHARACTERS, 256, MAX_RESOURCE_CHARACTERS);
            int end = Math.min(content.length(), offset + maxCharacters);
            String slice = offset >= content.length() ? "" : content.substring(offset, end);
            JsonObject output = new JsonObject();
            output.addProperty("skill", skill.id());
            output.addProperty("path", root.relativize(requested).toString());
            output.addProperty("bytes", size);
            output.addProperty("content", slice);
            output.addProperty("offset", offset);
            output.addProperty("nextOffset", end < content.length() ? end : -1);
            output.addProperty("totalCharacters", content.length());
            output.addProperty("truncated", end < content.length());
            output.addProperty("executable", false);
            return completed(call, output, end < content.length()
                    ? "Skill resource slice read as inert text; more content remains."
                    : "Skill resource read as inert text.");
        } catch (Exception exception) {
            return failure(call, "skill_resource_read_failed", message(exception));
        }
    }

    private static KoilSkillDefinition requireSkill(String id) {
        return KoilSkillRegistry.definitions().stream()
                .filter(skill -> skill.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown installed Skill: " + id));
    }

    private static JsonObject summary(KoilSkillDefinition skill, boolean includeInstructions) {
        JsonObject output = new JsonObject();
        output.addProperty("id", skill.id());
        output.addProperty("version", skill.version());
        output.addProperty("description", skill.description());
        output.addProperty("source", skill.source());
        output.addProperty("trust", skill.trust().name().toLowerCase(Locale.ROOT));
        output.addProperty("resourceRootAvailable", !skill.root().isBlank());
        JsonArray modes = new JsonArray();
        skill.modes().stream().map(value -> value.name().toLowerCase(Locale.ROOT)).sorted().forEach(modes::add);
        output.add("modes", modes);
        JsonArray declaredTools = new JsonArray();
        skill.declaredTools().forEach(declaredTools::add);
        output.add("declaredTools", declaredTools);
        if (includeInstructions) {
            String instructions = skill.compactGuidance();
            boolean truncated = instructions.length() > MAX_INSPECT_INSTRUCTION_CHARACTERS;
            String visible = truncated
                    ? instructions.substring(0, MAX_INSPECT_INSTRUCTION_CHARACTERS).strip()
                    : instructions;
            output.addProperty("instructions", visible);
            output.addProperty("instructionCharacters", instructions.length());
            output.addProperty("instructionsTruncated", truncated);
            if (truncated) {
                output.addProperty("continuationHint",
                        "The Skill is larger than one safe context slice. Use skill.read_resource with path=SKILL.md and offset/max_chars to read the next relevant slice.");
            }
        }
        return output;
    }

    private static KoilSkillMode parseMode(String value) {
        if (value == null || value.isBlank()) return KoilSkillMode.AUTOMATION;
        try {
            return KoilSkillMode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return KoilSkillMode.AUTOMATION;
        }
    }

    private static int skillScore(KoilSkillDefinition skill, String rawQuery, Set<String> queryTokens) {
        if (rawQuery == null || rawQuery.isBlank()) return 1;
        String query = rawQuery.toLowerCase(Locale.ROOT).strip();
        String id = skill.id().toLowerCase(Locale.ROOT);
        String description = skill.description().toLowerCase(Locale.ROOT);
        String semantic = skill.semanticSummary().toLowerCase(Locale.ROOT);
        int score = 0;
        if (id.equals(query)) score += 100;
        if (id.contains(query)) score += 40;
        if (description.contains(query)) score += 25;
        if (semantic.contains(query)) score += 20;
        for (String token : queryTokens) {
            if (id.equals(token) || id.endsWith("." + token)) score += 12;
            else if (id.contains(token)) score += 7;
            if (description.contains(token)) score += 4;
            if (semantic.contains(token)) score += 3;
            if (skill.declaredTools().stream().anyMatch(tool -> tool.toLowerCase(Locale.ROOT).contains(token))) score += 2;
        }
        return score;
    }

    private static Set<String> tokens(String value) {
        java.util.LinkedHashSet<String> output = new java.util.LinkedHashSet<>();
        if (value == null) return output;
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9_.:-]+")) {
            if (!token.isBlank()) output.add(token);
        }
        return output;
    }

    private record SkillScore(KoilSkillDefinition skill, int score) {
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isBlank()) return true;
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static JsonObject searchSchema() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("query", stringSchema(0, 512));
        properties.add("mode", enumSchema("ask", "deep_thought", "automation"));
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", 24);
        properties.add("limit", limit);
        require(schema, "query");
        return schema;
    }

    private static JsonObject inspectSchema() {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("skill", stringSchema(1, 256));
        require(schema, "skill");
        return schema;
    }

    private static JsonObject resourceSchema() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("skill", stringSchema(1, 256));
        properties.add("path", stringSchema(1, 512));
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "integer");
        offset.addProperty("minimum", 0);
        properties.add("offset", offset);
        JsonObject maxChars = new JsonObject();
        maxChars.addProperty("type", "integer");
        maxChars.addProperty("minimum", 256);
        maxChars.addProperty("maximum", MAX_RESOURCE_CHARACTERS);
        properties.add("max_chars", maxChars);
        require(schema, "skill", "path");
        return schema;
    }

    private static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonObject stringSchema(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("minLength", minimum);
        schema.addProperty("maxLength", maximum);
        return schema;
    }

    private static JsonObject enumSchema(String... values) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        JsonArray options = new JsonArray();
        for (String value : values) options.add(value);
        schema.add("enum", options);
        return schema;
    }

    private static void require(JsonObject schema, String... keys) {
        JsonArray required = new JsonArray();
        for (String key : keys) required.add(key);
        schema.add("required", required);
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsString().strip() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback, int minimum, int maximum) {
        try {
            int value = object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
            return Math.max(minimum, Math.min(maximum, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) {
        return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail);
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                "failed", new JsonObject(), code, detail == null ? "" : detail);
    }

    private static String message(Throwable failure) {
        return failure == null || failure.getMessage() == null ? "unknown skill tool failure" : failure.getMessage();
    }
}
