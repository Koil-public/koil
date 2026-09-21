package com.spirit.koil.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.spirit.koil.api.model.reasoning.AgentState;
import com.spirit.koil.api.minecraft.MinecraftNameResolver;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.tool.ModelWorkspaceRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Unified deterministic argument resolver for prediction and execution.
 * Values are grounded against explicit user text, deterministic workspace
 * resolution, authoritative AgentState, validated prior tool outputs, and
 * active plan bindings. Ambiguous values may remain model hints for read-only
 * work but consequential identity/location arguments never execute solely from
 * unsupported model inference.
 */
final class ToolArgumentResolver {
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\r\\n]{1,320})`");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"'\\r\\n]{1,320})[\"']");
    private static final Pattern PATH_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])((?:[A-Za-z]:)?(?:\\.?\\.?[/\\\\])?[A-Za-z0-9_@.+\\-]+(?:[/\\\\][A-Za-z0-9_@.+\\-]+)+|[A-Za-z0-9_@.+\\-]+\\.(?:java|json5?|kt|kts|toml|ya?ml|md|txt|mcfunction|mcmeta|properties|gradle|xml))(?![A-Za-z0-9_])");
    private static final Pattern BLOCK_DISTANCE = Pattern.compile("(?i)\\b([0-9]+(?:\\.[0-9]+)?)\\s*(?:blocks?|steps?)\\b");
    private static final Pattern GIVE_ITEM = Pattern.compile(
            "(?i)\\b(?:give|grant|award)\\s+(?:(?:me|myself|@s)\\s+)?(?:(\\d{1,6})\\s+)?(.+?)\\s*$"
    );

    private static final Set<String> CONSEQUENTIAL_IDENTITY_KEYS = Set.of(
            "id", "path", "workspace", "url", "uri", "command", "target", "entity", "item", "block",
            "registry", "selector", "dataset", "config", "split", "revision", "source", "destination",
            "from", "to", "file", "directory", "dimension", "structure", "recipe", "advancement",
            "mod", "tag", "resource"
    );

    private ToolArgumentResolver() {}

    static Resolution resolve(String toolId, String objective) {
        return resolve(toolId, objective, List.of());
    }

    static Resolution resolve(String toolId, String objective, List<VerifiedToolEvidence> evidence) {
        if (toolId == null) return Resolution.none();
        String goal = objective == null ? "" : objective;
        Resolution explicit = switch (toolId) {
            case "workspace.read", "workspace.stat" -> resolveExplicitPath(goal);
            case "workspace.list" -> resolveOptionalPath(goal);
            case "workspace.search" -> resolveExplicitSearch(goal);
            case "code.architecture", "code.changes", "code.schema" -> resolveCodeWorkspace();
            case "code.symbols", "code.search", "code.semantic_search", "code.trace", "code.impact", "code.snippet", "code.query" -> resolveCodeQuery(goal);
            default -> Resolution.none();
        };
        if (explicit.resolved() && explicit.authoritative()) return explicit;
        Resolution chained = resolveFromVerifiedEvidence(toolId, evidence);
        if (chained.resolved()) return chained;
        return explicit;
    }

    static Resolution resolve(String toolId, String objective, List<VerifiedToolEvidence> evidence, AgentState state) {
        Resolution stateResolution = resolveFromAgentState(toolId, state);
        if (stateResolution.resolved() && stateResolution.authoritative()) return stateResolution;
        Resolution existing = resolve(toolId, objective, evidence);
        if (existing.resolved() && existing.authoritative()) return existing;
        return stateResolution.resolved() ? stateResolution : existing;
    }

    private static Resolution resolveFromAgentState(String toolId, AgentState state) {
        if (state == null || toolId == null || toolId.isBlank()) return Resolution.none();
        AgentState.Snapshot snapshot = state.snapshot();
        AgentState.PlanStepState step = snapshot.plan() == null ? null : snapshot.plan().steps().stream()
                .filter(value -> toolId.equals(value.toolId()))
                .filter(value -> value.status() == AgentState.PlanStepStatus.ACTIVE
                        || value.status() == AgentState.PlanStepStatus.PENDING)
                .min(Comparator.comparingInt(AgentState.PlanStepState::index)).orElse(null);
        if (step != null && step.arguments() != null && !step.arguments().entrySet().isEmpty()) {
            return new Resolution(step.arguments(), "agent_state_plan_step:" + step.id(), 1.0D, true, List.of());
        }
        ModelToolDefinition definition = LocalModelToolCatalog.definition(toolId).orElse(null);
        if (definition == null) return Resolution.none();
        JsonObject schema = definition.inputSchema();
        JsonObject properties = schema != null && schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        JsonObject args = new JsonObject();
        List<String> sources = new ArrayList<>();
        for (String key : properties.keySet()) {
            AgentState.Decision decision = snapshot.decisions().stream()
                    .filter(AgentState.Decision::locked)
                    .filter(value -> semanticKeyMatch(value.subject(), key))
                    .max(Comparator.comparingLong(AgentState.Decision::revision)).orElse(null);
            if (decision != null && !decision.value().isBlank()) {
                args.addProperty(key, decision.value());
                sources.add("decision:" + decision.id());
                continue;
            }
            AgentState.ToolEvidence evidenceValue = snapshot.toolEvidence().stream()
                    .filter(AgentState.ToolEvidence::validated)
                    .filter(value -> value.output() != null && value.output().has(key))
                    .max(Comparator.comparingLong(AgentState.ToolEvidence::revision)).orElse(null);
            if (evidenceValue != null) {
                args.add(key, evidenceValue.output().get(key).deepCopy());
                sources.add(evidenceValue.callId());
            }
        }
        if (args.entrySet().isEmpty()) return Resolution.none();
        boolean complete = ModelToolSchemaValidator.validate(schema, args).stream().noneMatch(error -> error.contains(":missing:"));
        return new Resolution(args, "agent_state:" + String.join("+", sources), complete ? 0.99D : 0.78D, complete, sources);
    }

    private static Resolution resolveFromVerifiedEvidence(String toolId, List<VerifiedToolEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return Resolution.none();
        for (int index = evidence.size() - 1; index >= 0; index--) {
            VerifiedToolEvidence item = evidence.get(index);
            if (item == null) continue;
            String workspace = item.workspace();
            String path = item.uniquePath();
            String symbol = item.uniqueSymbol();

            if (("workspace.read".equals(toolId) || "workspace.stat".equals(toolId)) && !path.isBlank()) {
                JsonObject args = new JsonObject();
                args.addProperty("path", path);
                if (!workspace.isBlank()) args.addProperty("workspace", workspace);
                if (satisfiesRequiredArguments(toolId, args)) {
                    return new Resolution(args, "verified_tool_result:" + item.toolId(), 0.99D, true, List.of(item.callId()));
                }
            }
            if ("workspace.list".equals(toolId) && !workspace.isBlank()) {
                JsonObject args = new JsonObject();
                args.addProperty("workspace", workspace);
                if (!path.isBlank()) args.addProperty("path", path);
                if (satisfiesRequiredArguments(toolId, args)) {
                    return new Resolution(args, "verified_tool_result:" + item.toolId(), 0.98D, true, List.of(item.callId()));
                }
            }
            if ((toolId.startsWith("code.") && !"code.architecture".equals(toolId)
                    && !"code.changes".equals(toolId) && !"code.schema".equals(toolId))
                    && !symbol.isBlank()) {
                JsonObject args = new JsonObject();
                String resolvedWorkspace = workspace;
                if (resolvedWorkspace.isBlank()) {
                    Resolution defaultWorkspace = resolveCodeWorkspace();
                    if (defaultWorkspace.arguments().has("workspace")) {
                        resolvedWorkspace = defaultWorkspace.arguments().get("workspace").getAsString();
                    }
                }
                if (!resolvedWorkspace.isBlank()) args.addProperty("workspace", resolvedWorkspace);
                args.addProperty("query", symbol);
                if (satisfiesRequiredArguments(toolId, args)) {
                    return new Resolution(args, "verified_tool_result_symbol:" + item.toolId(), 0.97D, true, List.of(item.callId()));
                }
            }
        }
        return Resolution.none();
    }

    static boolean satisfiesRequiredArguments(String toolId, JsonObject arguments) {
        ModelToolDefinition definition = LocalModelToolCatalog.definition(toolId).orElse(null);
        if (definition == null) return false;
        return ModelToolSchemaValidator.validate(definition.inputSchema(), arguments).stream()
                .noneMatch(error -> error.contains(":missing:"));
    }

    private static Resolution resolveOptionalPath(String objective) {
        Resolution path = resolveExplicitPath(objective);
        return path.resolved() ? path : Resolution.none();
    }

    private static Resolution resolveExplicitSearch(String objective) {
        List<String> values = explicitValues(objective).stream()
                .filter(value -> !looksLikePath(value))
                .distinct()
                .toList();
        if (values.size() != 1) return Resolution.none();
        JsonObject args = new JsonObject();
        args.addProperty("query", values.get(0));

        Resolution path = resolveExplicitPath(objective);
        if (path.resolved() && path.authoritative() && path.arguments().has("workspace")) {
            args.addProperty("workspace", path.arguments().get("workspace").getAsString());
            if (path.arguments().has("path")) args.addProperty("path", path.arguments().get("path").getAsString());
            return new Resolution(args, "user_explicit_search_text+" + path.provenance(), 0.94D, true, path.sourceCallIds());
        }
        // The query is explicit, but searching the default instance/project is
        // a material scope decision. Keep it as a model hint unless the scope
        // was deterministically resolved.
        return new Resolution(args, "user_explicit_search_text+workspace_unresolved", 0.72D, false, List.of());
    }

    private static Resolution resolveCodeWorkspace() {
        Map<String, ModelWorkspaceRegistry.Workspace> available = ModelWorkspaceRegistry.workspaces();
        String workspace = ModelWorkspaceRegistry.preferredCodeWorkspaceId("", available);
        if (workspace == null || workspace.isBlank() || !available.containsKey(workspace)) return Resolution.none();
        JsonObject args = new JsonObject();
        args.addProperty("workspace", workspace);
        return new Resolution(args, "deterministic_preferred_code_workspace", 0.99D, true, List.of());
    }

    private static Resolution resolveCodeQuery(String objective) {
        Resolution workspace = resolveCodeWorkspace();
        if (!workspace.resolved()) return Resolution.none();
        List<String> explicit = explicitValues(objective).stream()
                .filter(value -> !looksLikePath(value))
                .distinct()
                .toList();
        if (explicit.size() != 1) return Resolution.none();
        JsonObject args = workspace.arguments().deepCopy();
        args.addProperty("query", explicit.get(0));
        return new Resolution(args, "deterministic_workspace+user_explicit_query", 0.95D, true, List.of());
    }

    private static List<String> explicitValues(String objective) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher backtick = BACKTICK.matcher(objective);
        while (backtick.find()) {
            String value = backtick.group(1).strip();
            if (!value.isBlank()) values.add(value);
        }
        Matcher quoted = QUOTED.matcher(objective);
        while (quoted.find()) {
            String value = quoted.group(1).strip();
            if (!value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }

    private static Resolution resolveExplicitPath(String objective) {
        List<Candidate> candidates = new ArrayList<>();
        collect(candidates, BACKTICK.matcher(objective), "user_backtick", 1.0D);
        collect(candidates, QUOTED.matcher(objective), "user_quote", 0.98D);
        collect(candidates, PATH_TOKEN.matcher(objective), "user_path_token", 0.96D);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            String value = cleanPath(candidate.value());
            if (!looksLikePath(value) || !seen.add(value)) continue;
            WorkspaceResolution workspace = workspaceForPath(value);
            JsonObject args = new JsonObject();
            args.addProperty("path", value);
            if (!workspace.workspaceId().isBlank()) args.addProperty("workspace", workspace.workspaceId());
            String provenance = candidate.provenance()
                    + (workspace.workspaceId().isBlank() ? "" : "+" + workspace.provenance());
            return new Resolution(
                    args,
                    provenance,
                    Math.min(candidate.confidence(), workspace.confidence()),
                    workspace.authoritative(),
                    List.of()
            );
        }
        return Resolution.none();
    }

    /**
     * Resolve an explicit relative path only when Koil can prove which named
     * workspace currently owns it. If no workspace uniquely matches, the path
     * remains a useful model hint but is not safe for speculative execution.
     */
    private static WorkspaceResolution workspaceForPath(String path) {
        Map<String, ModelWorkspaceRegistry.Workspace> available = ModelWorkspaceRegistry.workspaces();
        List<String> matches = new ArrayList<>();
        for (String id : available.keySet()) {
            try {
                ModelWorkspaceRegistry.inspect(id, path, false);
                matches.add(id);
            } catch (IOException ignored) {
            }
        }
        if (matches.size() == 1) {
            return new WorkspaceResolution(matches.get(0), "deterministic_existing_workspace", 0.99D, true);
        }
        return new WorkspaceResolution("", matches.isEmpty() ? "workspace_unresolved" : "workspace_ambiguous", 0.55D, false);
    }

    private static void collect(List<Candidate> out, Matcher matcher, String provenance, double confidence) {
        while (matcher.find()) out.add(new Candidate(matcher.group(1), provenance, confidence));
    }

    private static String cleanPath(String raw) {
        String value = raw == null ? "" : raw.strip();
        while (!value.isEmpty() && ",;:)]}".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean looksLikePath(String value) {
        if (value == null || value.isBlank() || value.length() > 320) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("/") || value.contains("\\\\")) return true;
        return lower.matches(".*\\.(java|json5?|kt|kts|toml|ya?ml|md|txt|mcfunction|mcmeta|properties|gradle|xml)$");
    }

    static ExecutionResolution bindForExecution(
            AgentState state,
            ModelToolCall proposed,
            String objective,
            boolean consequential
    ) {
        if (proposed == null) return new ExecutionResolution(null, Map.of(), List.of("missing tool call"), false);
        ModelToolDefinition definition = LocalModelToolCatalog.definition(proposed.toolId()).orElse(null);
        if (definition == null) return new ExecutionResolution(proposed, Map.of(), List.of("unknown tool schema"), false);
        AgentState.Snapshot snapshot = state == null ? null : state.snapshot();
        JsonObject args = proposed.arguments() == null ? new JsonObject() : proposed.arguments().deepCopy();
        JsonObject schema = definition.inputSchema();
        JsonObject properties = schema != null && schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        AgentState.PlanStepState planStep = matchingPlanStep(snapshot, proposed);
        LinkedHashMap<String, AgentState.ArgumentBinding> bindings = new LinkedHashMap<>();
        JsonObject semanticTaskArguments = semanticTaskArguments(proposed.toolId(), objective);

        for (String key : properties.keySet()) {
            if (planStep != null && planStep.arguments().has(key)) {
                JsonElement value = planStep.arguments().get(key).deepCopy();
                args.add(key, value);
                bindings.put(key, new AgentState.ArgumentBinding(value, AgentState.ArgumentSource.PLAN_STEP,
                        "plan-step:" + planStep.id(), 1.0D));
                continue;
            }
            if (semanticTaskArguments.has(key)) {
                JsonElement value = semanticTaskArguments.get(key).deepCopy();
                args.add(key, value);
                bindings.put(key, new AgentState.ArgumentBinding(
                        value, AgentState.ArgumentSource.USER_REQUEST, "current_task_semantics", 1.0D));
                continue;
            }
            JsonElement current = args.get(key);
            Bound bound = bindExisting(snapshot, key, current, objective);
            if (bound != null) {
                args.add(key, bound.value().deepCopy());
                bindings.put(key, new AgentState.ArgumentBinding(bound.value(), bound.source(), bound.sourceId(), bound.confidence()));
                continue;
            }
            JsonObject property = properties.get(key).isJsonObject() ? properties.getAsJsonObject(key) : new JsonObject();
            if (current == null && property.has("default")) {
                JsonElement value = property.get("default").deepCopy();
                args.add(key, value);
                bindings.put(key, new AgentState.ArgumentBinding(value, AgentState.ArgumentSource.SCHEMA_DEFAULT,
                        "schema:" + proposed.toolId() + ":" + key, 1.0D));
            }
        }

        List<String> blockers = new ArrayList<>(ModelToolSchemaValidator.validate(schema, args));
        if (consequential) {
            for (Map.Entry<String, AgentState.ArgumentBinding> entry : bindings.entrySet()) {
                if (identityKey(entry.getKey()) && entry.getValue().source() == AgentState.ArgumentSource.MODEL_DERIVED) {
                    blockers.add("ungrounded_consequential_argument:" + entry.getKey());
                    if (state != null) {
                        state.requireToolArgument(
                                proposed.id(), proposed.toolId(), entry.getKey(),
                                "Ground consequential argument '" + entry.getKey() + "' for " + proposed.toolId()
                                        + " in the request, AgentState, prior validated tool evidence, or the active plan step."
                        );
                    }
                }
            }
        }
        for (String error : new ArrayList<>(blockers)) {
            int markerIndex = error.indexOf(":missing:");
            if (markerIndex >= 0 && state != null) {
                String key = error.substring(markerIndex + 9);
                state.requireToolArgument(proposed.id(), proposed.toolId(), key,
                        "Resolve required argument '" + key + "' for " + proposed.toolId()
                                + " from the request, AgentState, prior validated tool evidence, or the active plan step.");
            }
        }
        if (state != null && !bindings.isEmpty()) state.recordToolArguments(proposed.id(), proposed.toolId(), bindings);
        ModelToolCall boundCall = new ModelToolCall(proposed.id(), proposed.toolId(), args);
        return new ExecutionResolution(boundCall, Map.copyOf(bindings), List.copyOf(blockers), blockers.isEmpty());
    }

    private static Bound bindExisting(AgentState.Snapshot snapshot, String key, JsonElement current, String objective) {
        if (snapshot != null) {
            AgentState.Decision decision = snapshot.decisions().stream()
                    .filter(AgentState.Decision::locked)
                    .filter(value -> semanticKeyMatch(value.subject(), key)
                            || (current != null && equivalent(current, value.value())))
                    .max(Comparator.comparingLong(AgentState.Decision::revision)).orElse(null);
            if (decision != null) {
                JsonElement value = current == null ? new JsonPrimitive(decision.value()) : current;
                return new Bound(value, AgentState.ArgumentSource.STATE_DECISION, decision.id(), decision.confidence());
            }
            AgentState.ToolEvidence evidence = snapshot.toolEvidence().stream()
                    .filter(AgentState.ToolEvidence::validated)
                    .filter(value -> value.output() != null && ((value.output().has(key) && current == null)
                            || (current != null && containsEquivalent(value.output(), current))))
                    .max(Comparator.comparingLong(AgentState.ToolEvidence::revision)).orElse(null);
            if (evidence != null) {
                JsonElement value = current == null ? evidence.output().get(key) : current;
                return new Bound(value, AgentState.ArgumentSource.TOOL_RESULT, evidence.id(), 1.0D);
            }
            if (current != null) {
                AgentState.Fact fact = snapshot.facts().stream()
                        .filter(value -> normalize(value.statement()).contains(normalize(jsonText(current))))
                        .max(Comparator.comparingLong(AgentState.Fact::revision)).orElse(null);
                if (fact != null) return new Bound(current, AgentState.ArgumentSource.STATE_FACT, fact.id(), fact.confidence());
            }
        }
        if (current != null && groundedInObjective(current, objective)) {
            return new Bound(current, AgentState.ArgumentSource.USER_REQUEST, "user_request", 1.0D);
        }
        if (current != null) return new Bound(current, AgentState.ArgumentSource.MODEL_DERIVED, "model_tool_call", 0.55D);
        return null;
    }

    private static JsonObject semanticTaskArguments(String toolId, String objective) {
        JsonObject args = new JsonObject();
        String raw = objective == null ? "" : objective.strip();
        String lower = raw.toLowerCase(Locale.ROOT);
        if ("input.hold".equals(toolId)) {
            if (lower.matches(".*\\b(?:crouch|sneak)\\b.*")) {
                args.addProperty("key", "crouch");
                return args;
            }
            if (lower.matches(".*\\bsprint\\b.*")) {
                args.addProperty("key", "sprint");
                return args;
            }
            return args;
        }
        if ("input.release".equals(toolId)) {
            if (lower.matches(".*\\b(?:uncrouch|stop\\s+(?:crouching|sneaking)|release\\s+(?:crouch|sneak))\\b.*")) {
                args.addProperty("key", "crouch");
                return args;
            }
            if (lower.matches(".*\\b(?:stop\\s+sprinting|release\\s+sprint)\\b.*")) {
                args.addProperty("key", "sprint");
                return args;
            }
            return args;
        }
        if ("movement.walk_relative".equals(toolId)) {
            String direction = lower.matches(".*\\b(?:back|backward|backwards)\\b.*") ? "backward"
                    : lower.matches(".*\\bleft\\b.*") ? "left"
                    : lower.matches(".*\\bright\\b.*") ? "right" : "forward";
            double distance = 1.0D;
            Matcher matcher = BLOCK_DISTANCE.matcher(lower);
            if (matcher.find()) {
                try { distance = Double.parseDouble(matcher.group(1)); }
                catch (NumberFormatException ignored) { distance = 1.0D; }
            }
            if (lower.matches(".*\\b(?:walk|walking|walked|step|steps|stroll|pace|strafe|sidestep)\\b.*")) {
                args.addProperty("direction", direction);
                args.addProperty("distance", Math.max(0.1D, Math.min(4096.0D, distance)));
            }
            return args;
        }
        if ("minecraft.command".equals(toolId)) {
            if (lower.matches(".*\\b(?:kill|slay|die)\\b.*")
                    && lower.matches(".*\\b(?:me|myself|self)\\b.*")) {
                args.addProperty("command", "kill @s");
                return args;
            }

            String mode = lower.contains("creative") ? "creative"
                    : lower.contains("spectator") ? "spectator"
                    : lower.contains("adventure") ? "adventure"
                    : lower.contains("survival") ? "survival" : "";
            if (!mode.isBlank() && (lower.contains("mode") || lower.contains("gamemode")
                    || lower.contains("put me") || lower.contains("make me") || lower.contains("set me"))) {
                args.addProperty("command", "gamemode " + mode);
                return args;
            }

            String giveCommand = resolveGiveCommand(raw);
            if (!giveCommand.isBlank()) {
                args.addProperty("command", giveCommand);
            }
            return args;
        }
        if ("block.mine".equals(toolId)) {
            Matcher distance = BLOCK_DISTANCE.matcher(lower);
            int count = 1;
            if (distance.find()) {
                try { count = Math.max(1, Math.min(4096, (int) Math.round(Double.parseDouble(distance.group(1))))); }
                catch (NumberFormatException ignored) { count = 1; }
            }

            String subject = raw.replaceFirst("(?i)^.*?\\b(?:mine|break|dig)\\b", "")
                    .replaceFirst("(?i)^\\s*\\d+\\s*(?:blocks?\\s+of\\s+)?", "")
                    .replaceFirst("(?i)\\s+(?:that|which|around|near|nearby|next to|by)\\b.*$", "")
                    .replaceFirst("[.!?,;:]+$", "").strip();
            subject = subject.replaceFirst("(?i)^(?:blocks?\\s+of\\s+|some\\s+|the\\s+)", "").strip();
            MinecraftNameResolver.Match block = MinecraftNameResolver.conservative("block", subject);
            if (block != null) {
                args.addProperty("block", block.id().toString());
                args.addProperty("count", count);
                args.addProperty("quantity", "exact");
                args.addProperty("selector", lower.matches(".*\\b(?:around|near|nearby|closest|nearest)\\b.*") ? "nearest" : "any");
            }
            return args;
        }
        if ("world.set_time".equals(toolId)) {
            String time = lower.matches(".*\\bmidnight\\b.*") ? "midnight"
                    : lower.matches(".*\\bnoon\\b.*") ? "noon"
                    : lower.matches(".*\\bnight\\b.*") ? "night"
                    : lower.matches(".*\\bday(?:time)?\\b.*") ? "day" : "";
            if (!time.isBlank() && (lower.contains("time") || lower.contains("set")
                    || lower.contains("make it") || lower.contains("turn it"))) {
                args.addProperty("time", time);
            }
            return args;
        }
        return args;
    }


    /**
     * Converts a narrow, explicit natural-language give request into the same
     * command Koil would have accepted from the model. The item id is resolved
     * against the live registry before the call is considered authoritative,
     * so this never guesses an unknown or modded identifier.
     */
    private static String resolveGiveCommand(String objective) {
        String raw = objective == null ? "" : objective.strip();
        Matcher matcher = GIVE_ITEM.matcher(raw);
        if (!matcher.find()) return "";

        int count = 1;
        if (matcher.group(1) != null && !matcher.group(1).isBlank()) {
            try { count = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException ignored) { return ""; }
        }
        if (count < 1 || count > 1_000_000) return "";

        String itemText = matcher.group(2) == null ? "" : matcher.group(2).strip();
        itemText = itemText.replaceFirst("[.!?,;:]+$", "").strip();
        itemText = itemText.replaceFirst("(?i)\\s+(?:please|pls)$", "").strip();
        itemText = itemText.replaceFirst("[.!?,;:]+$", "").strip();
        itemText = itemText.replaceFirst("(?i)^(?:an?|some|the)\\s+", "").strip();
        if (itemText.isBlank()) return "";

        Identifier itemId = resolveItemIdentifier(itemText);
        if (itemId == null) return "";
        return "give @s " + itemId + " " + count;
    }

    private static Identifier resolveItemIdentifier(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).strip()
                .replace('-', '_').replaceAll("\\s+", "_");
        if (normalized.isBlank()) return null;

        // Exact identifiers stay the fastest path.
        Identifier exact = Identifier.tryParse(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (exact != null && Registries.ITEM.containsId(exact)) return exact;

        // Consequential give actions only accept a fuzzy value when one live
        // item is both strongly matched and sufficiently separated from the
        // runner-up. This fixes spelling/spacing mistakes without guessing.
        MinecraftNameResolver.Match match = MinecraftNameResolver.conservative("item", text);
        return match == null ? null : match.id();
    }

    private static AgentState.PlanStepState matchingPlanStep(AgentState.Snapshot snapshot, ModelToolCall call) {
        if (snapshot == null || snapshot.plan() == null || snapshot.plan().status() == AgentState.PlanStatus.NONE || call == null) return null;
        // Reviewed plan-step calls carry the step id as their call id. Prefer that
        // exact identity so repeated uses of the same capability cannot borrow
        // arguments from an earlier pending occurrence.
        AgentState.PlanStepState exact = snapshot.plan().steps().stream()
                .filter(step -> !step.id().isBlank() && step.id().equals(call.id()))
                .filter(step -> step.toolId().equals(call.toolId()))
                .findFirst().orElse(null);
        if (exact != null) return exact;

        AgentState.PlanStepState active = snapshot.plan().steps().stream()
                .filter(step -> step.toolId().equals(call.toolId()))
                .filter(step -> step.status() == AgentState.PlanStepStatus.ACTIVE)
                .min(Comparator.comparingInt(AgentState.PlanStepState::index)).orElse(null);
        if (active != null) return active;

        return snapshot.plan().steps().stream()
                .filter(step -> step.toolId().equals(call.toolId()))
                .filter(step -> step.status() == AgentState.PlanStepStatus.PENDING)
                .filter(step -> step.arguments().equals(call.arguments()))
                .min(Comparator.comparingInt(AgentState.PlanStepState::index))
                .orElseGet(() -> snapshot.plan().steps().stream()
                        .filter(step -> step.toolId().equals(call.toolId()))
                        .filter(step -> step.status() == AgentState.PlanStepStatus.PENDING)
                        .min(Comparator.comparingInt(AgentState.PlanStepState::index)).orElse(null));
    }

    private static boolean groundedInObjective(JsonElement value, String objective) {
        if (value == null || !value.isJsonPrimitive()) return false;
        String needle = normalize(jsonText(value));
        String haystack = normalize(objective);
        if (needle.isBlank()) return false;
        if (haystack.contains(needle)) return true;
        String raw = jsonText(value);
        int colon = raw.indexOf(':');
        return colon >= 0 && colon + 1 < raw.length() && haystack.contains(normalize(raw.substring(colon + 1)));
    }

    private static boolean identityKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).strip();
        return CONSEQUENTIAL_IDENTITY_KEYS.contains(normalized) || normalized.endsWith("id")
                || normalized.endsWith("path") || normalized.endsWith("url") || normalized.endsWith("uri")
                || normalized.contains("selector") || normalized.contains("target");
    }

    private static boolean semanticKeyMatch(String subject, String key) {
        String a = normalize(subject).replace(' ', '_');
        String b = normalize(key).replace(' ', '_');
        return a.equals(b) || a.endsWith("_" + b) || a.endsWith(":" + b) || a.contains("canonical_" + b);
    }

    private static boolean equivalent(JsonElement value, String text) {
        return value != null && value.isJsonPrimitive() && normalize(jsonText(value)).equals(normalize(text));
    }

    private static boolean containsEquivalent(JsonElement root, JsonElement expected) {
        if (root == null || expected == null) return false;
        if (root.equals(expected)) return true;
        if (root.isJsonPrimitive() && expected.isJsonPrimitive()) return normalize(jsonText(root)).equals(normalize(jsonText(expected)));
        if (root.isJsonArray()) for (JsonElement child : root.getAsJsonArray()) if (containsEquivalent(child, expected)) return true;
        if (root.isJsonObject()) for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) if (containsEquivalent(entry.getValue(), expected)) return true;
        return false;
    }

    private static String jsonText(JsonElement value) {
        try { return value == null || value.isJsonNull() ? "" : value.getAsString(); }
        catch (RuntimeException ignored) { return value == null ? "" : value.toString(); }
    }

    /**
     * Canonical text form used only for semantic/provenance matching. Keep the
     * original argument value untouched; normalization is deliberately limited
     * to case and whitespace so identifiers, paths, and URLs are not rewritten.
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    record ExecutionResolution(
            ModelToolCall call,
            Map<String, AgentState.ArgumentBinding> bindings,
            List<String> blockers,
            boolean executable
    ) {
        ExecutionResolution {
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
        String compactProvenance() {
            return bindings.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue().source().name().toLowerCase(Locale.ROOT))
                    .reduce((a, b) -> a + ", " + b).orElse("");
        }
    }

    private record Bound(JsonElement value, AgentState.ArgumentSource source, String sourceId, double confidence) {}

    record Resolution(
            JsonObject arguments,
            String provenance,
            double confidence,
            boolean authoritative,
            List<String> sourceCallIds
    ) {
        Resolution {
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
            provenance = provenance == null ? "none" : provenance;
            confidence = Math.max(0.0D, Math.min(1.0D, confidence));
            sourceCallIds = sourceCallIds == null ? List.of() : List.copyOf(sourceCallIds);
        }

        static Resolution none() {
            return new Resolution(new JsonObject(), "none", 0.0D, false, List.of());
        }

        boolean resolved() {
            return !arguments.entrySet().isEmpty();
        }
    }

    private record Candidate(String value, String provenance, double confidence) {}

    private record WorkspaceResolution(
            String workspaceId,
            String provenance,
            double confidence,
            boolean authoritative
    ) {}
}
