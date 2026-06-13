package com.spirit.koil.api.automation.ktl;

import com.spirit.koil.api.automation.AutomationReporter;
import com.spirit.koil.api.automation.AutomationRequest;
import com.spirit.koil.api.automation.runtime.ExecutionPlan;
import com.spirit.koil.api.automation.runtime.InterpretationResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KtlCompilerService {
    private static final Path ROOT = Path.of("koil/automation");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]+)\"|(\\S+)");
    private static final Set<String> CAPABILITY_KEYS = Set.of(
            "cap.combat.attack_target", "cap.combat.attack_until_dead", "cap.combat.confirm_target_death", "cap.command.execute_raw", "cap.container.find_item_in_open_screen",
            "cap.container.quick_move_selected_hotbar", "cap.container.quick_move_slot", "cap.input.mouse_delta", "cap.input.press_key",
            "cap.input.release_all", "cap.input.release_key", "cap.input.tap_key", "cap.interaction.attack_target",
            "cap.interaction.break_block_target", "cap.interaction.close_screen", "cap.interaction.consume_selected_item", "cap.interaction.interact_entity_target",
            "cap.interaction.interact_target", "cap.interaction.open_target", "cap.interaction.stop_using_item", "cap.interaction.use_item",
            "cap.interaction.use_item_on_block_target", "cap.interaction.use_item_on_current_block", "cap.interaction.use_item_on_entity_target", "cap.interaction.use_main_hand_item",
            "cap.interaction.use_off_hand_item", "cap.interaction.use_selected_item", "cap.goal.execute_named", "cap.inventory.consume_item", "cap.inventory.count_item",
            "cap.inventory.drop_selected_item", "cap.inventory.equip_item", "cap.inventory.has_item", "cap.inventory.open_inventory_screen", "cap.inventory.require_count",
            "cap.inventory.select_hotbar_item", "cap.look.face_position", "cap.look.face_target", "cap.look.face_target_horizontal",
            "cap.look.face_block_center", "cap.look.face_block_face", "cap.look.face_movement_direction", "cap.look.face_parkour_landing",
            "cap.look.set_pitch", "cap.look.set_yaw", "cap.look.turn_pitch", "cap.look.turn_relative", "cap.look.turn_yaw",
            "cap.movement.check_progress", "cap.movement.check_safety", "cap.movement.choose_recovery", "cap.movement.release_all",
            "cap.movement.run_recovery", "cap.movement.set_backward", "cap.movement.set_forward", "cap.movement.set_jump",
            "cap.movement.set_left_strafe", "cap.movement.set_right_strafe", "cap.movement.set_sneak", "cap.movement.set_sprint",
            "cap.movement.snapshot", "cap.movement.stop", "cap.movement.timed_jump", "cap.movement.walk_relative",
            "cap.parkour.analyze_jump", "cap.parkour.execute_jump", "cap.path.compute_relative_target", "cap.path.follow_target",
            "cap.path.move_relative_verified", "cap.path.move_to_target", "cap.path.plan_local", "cap.path.replan_local_segment", "cap.path.resolve_target", "cap.path.verify_relative_arrival", "cap.path.verify_target_arrival",
            "cap.player.crouch", "cap.player.dismount", "cap.player.jump", "cap.player.sprint", "cap.player.uncrouch",
            "cap.player.unsprint", "cap.report.error_line", "cap.report.say", "cap.report.status_line",
            "cap.state.capture_player_position", "cap.state.copy_value", "cap.state.decrement_counter", "cap.state.increment_counter",
            "cap.state.read_stat", "cap.state.remove_memory", "cap.state.set_counter", "cap.state.write_memory",
            "cap.wait.ticks", "cap.world.scan_blocks", "cap.world.scan_entities", "cap.world.scan_players",
            "cap.world.scan_target", "cap.world.target_in_range", "cap.world.validate_target"
    );
    private static final Set<String> EVALUATOR_KEYS = Set.of(
            "counter_lt_target", "eval.block_matches", "eval.compare_numbers", "eval.compare_stat",
            "eval.inventory_count_compare", "eval.target_exists", "eval.time_of_day_compare", "eval.automation_task_running"
    );
    private static final Set<String> KTL_KINDS = Set.of(
            "lexicon", "grammar_patterns", "condition_definition", "condition_pack", "semantic_operation", "semantic_operation_pack",
            "template_metadata", "task_template", "task_preset", "task_macro", "resolver_rules", "alias_pack", "compile_profile",
            "namespace_pack", "reference_patterns", "selector_pack"
    );
    private static final Set<String> GRAMMAR_SHAPES = Set.of(
            "sequence_then", "sequence_and_then", "if_condition_then_else", "if_condition_then", "action_count_target",
            "action_target", "action_selector_target", "action_direction_amount_unit", "action_direction", "action_amount_unit",
            "action_duration", "action_hand", "action_key", "action_key_duration", "action_target_location",
            "action_selector_count_target", "action_text", "raw_command", "reference_action", "action_on_target",
            "action_item_on_target", "action_item_from_target", "action_to_target", "action_simple"
    );
    private static final KtlCompilerService INSTANCE = new KtlCompilerService();

    private final Yaml yaml = new Yaml();
    private CompiledAssets assets = new CompiledAssets();
    private CompileSummary lastSummary = new CompileSummary(0, 0, 0);
    private String lastSourceFingerprint = "";
    private final Map<String, String> lastSourceHashes = new LinkedHashMap<>();
    private final Map<String, NormalizedDocument> lastNormalizedByPath = new LinkedHashMap<>();

    public static KtlCompilerService getInstance() {
        return INSTANCE;
    }

    public synchronized void reload() {
        ensureDirectories();
        Map<String, NormalizedDocument> normalized = new LinkedHashMap<>();
        int hits = 0;
        int misses = 0;
        try {
            List<Path> paths = Files.exists(ROOT) ? Files.walk(ROOT).filter(path -> path.toString().endsWith(".ktl")).sorted().toList() : List.of();
            String sourceFingerprint = fingerprint(paths);
            if (!this.lastSourceFingerprint.isBlank() && this.lastSourceFingerprint.equals(sourceFingerprint)) {
                this.lastSummary = new CompileSummary(paths.size(), paths.size(), 0);
                AutomationReporter.cache("[cache]", "language reuse=hot files=" + paths.size());
                return;
            }
            for (Path path : paths) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                String hash = sha256(source);
                String key = path.toString();
                NormalizedDocument document = null;
                if (hash.equals(this.lastSourceHashes.get(key))) {
                    document = this.lastNormalizedByPath.get(key);
                }
                if (document != null) {
                    hits++;
                } else {
                    document = normalize(path, source);
                    misses++;
                }
                this.lastSourceHashes.put(key, hash);
                this.lastNormalizedByPath.put(key, document);
                NormalizedDocument existing = normalized.put(document.id, document);
                if (existing != null) {
                    throw new IllegalStateException(".ktl validation failed: duplicate id '" + document.id + "' in " + existing.sourcePath + " and " + document.sourcePath);
                }
            }
            this.lastSourceHashes.keySet().retainAll(paths.stream().map(Path::toString).toList());
            this.lastNormalizedByPath.keySet().retainAll(paths.stream().map(Path::toString).toList());
            validateNormalizedDocuments(normalized.values());
            this.assets = compile(normalized.values());
            this.lastSummary = new CompileSummary(paths.size(), hits, misses);
            this.lastSourceFingerprint = sourceFingerprint;
            AutomationReporter.cache("[cache]", "language memory_hit=" + hits + " rebuild=" + misses + " files=" + paths.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load .ktl sources: " + exception.getMessage(), exception);
        }
    }

    public synchronized InterpretationResult interpret(AutomationRequest request) {
        CompletionCommandParse completionCommands = parseCompletionCommands(request.rawInput().trim());
        String input = completionCommands.input();
        List<String> tokens = tokenize(input);
        AutomationReporter.info("[raw ]", input);
        if (!completionCommands.successCommand().isBlank()) {
            AutomationReporter.bind("completion.success.command", completionCommands.successCommand());
        }
        if (!completionCommands.failureCommand().isBlank()) {
            AutomationReporter.bind("completion.failure.command", completionCommands.failureCommand());
        }
        AutomationReporter.pipeline("[tok ]", tokens.isEmpty() ? "(none)" : String.join(" / ", describeTokens(tokens)));
        if (request.directTemplate()) {
            DirectTemplateInvocation invocation = parseDirectTemplate(input);
            CompiledTaskTemplate template = requireTemplate(invocation.templateId());
            Map<String, Object> params = new LinkedHashMap<>(invocation.params());
            applyCompletionCommands(params, completionCommands);
            if ("kill_target_until_count".equals(template.templateId())) {
                params.putIfAbsent("state.counter", 0);
            }
            AutomationReporter.run("[run ]", "direct template -> " + template.templateId());
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                AutomationReporter.bind(entry.getKey(), entry.getValue());
            }
            return new InterpretationResult(new ExecutionPlan(template, params), template.semanticOperationId(), template.templateId(), params, Map.of());
        }
        if (input.startsWith("/")) {
            CompiledTaskTemplate template = requireTemplate("raw_command");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("raw.command", input.substring(1));
            applyCompletionCommands(params, completionCommands);
            AutomationReporter.pipeline("[gram]", "raw_command");
            AutomationReporter.bind("raw.command", input.substring(1));
            return new InterpretationResult(new ExecutionPlan(template, params), "sem.task.raw_command", template.templateId(), params, Map.of());
        }

        Frame frame = parseFrame(input);
        if (frame == null) {
            AutomationReporter.block("[block]", "grammar = no_match for '" + input + "'");
            throw new IllegalStateException("No grammar pattern matched input: " + input);
        }

        reportLexiconMatches(tokens);
        if (frame.semanticOperationId != null && !frame.semanticOperationId.isBlank()) {
            AutomationReporter.pipeline("[lex ]", frame.semanticOperationId);
        }
        if (frame.selectorId != null && !frame.selectorId.isBlank()) {
            AutomationReporter.pipeline("[sel ]", frame.selectorId);
        }
        AutomationReporter.pipeline("[gram]", frame.shape);
        if (frame.conditionId != null) {
            AutomationReporter.pipeline("[cond]", frame.conditionId + " threshold=" + frame.conditionThreshold);
        }
        Map<String, Object> resolved = resolve(frame);
        String templateId = selectTemplate(frame, resolved);
        CompiledTaskTemplate template = requireTemplate(templateId);
        Map<String, Object> params = new LinkedHashMap<>(resolved);
        params.putAll(frame.additionalParams);
        applyCompletionCommands(params, completionCommands);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            AutomationReporter.bind(entry.getKey(), entry.getValue());
        }
        return new InterpretationResult(new ExecutionPlan(template, params), frame.semanticOperationId, template.templateId(), params, Map.of(
                "frame", frame.shape,
                "target", frame.targetRawText == null ? "" : frame.targetRawText
        ));
    }

    private static void applyCompletionCommands(Map<String, Object> params, CompletionCommandParse commands) {
        if (params == null || commands == null) {
            return;
        }
        if (!commands.successCommand().isBlank()) {
            params.put("completion.success.command", commands.successCommand());
        }
        if (!commands.failureCommand().isBlank()) {
            params.put("completion.failure.command", commands.failureCommand());
        }
    }

    private static CompletionCommandParse parseCompletionCommands(String input) {
        String remaining = input == null ? "" : input.trim();
        String success = "";
        String failure = "";
        while (true) {
            HookMatch match = lastHookMatch(remaining);
            if (match == null) {
                break;
            }
            String command = remaining.substring(match.commandStart()).trim();
            remaining = remaining.substring(0, match.hookStart()).trim();
            if (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            if ("success".equals(match.kind())) {
                success = command;
            } else {
                failure = command;
            }
        }
        return new CompletionCommandParse(remaining, success, failure);
    }

    private static HookMatch lastHookMatch(String input) {
        HookMatch best = null;
        String lower = input.toLowerCase(Locale.ROOT);
        for (String marker : List.of(" on success /", " on complete /", " on completed /")) {
            int index = lower.lastIndexOf(marker);
            if (index >= 0 && (best == null || index > best.hookStart())) {
                best = new HookMatch("success", index, index + marker.length() - 1);
            }
        }
        for (String marker : List.of(" on fail /", " on failure /", " on failed /")) {
            int index = lower.lastIndexOf(marker);
            if (index >= 0 && (best == null || index > best.hookStart())) {
                best = new HookMatch("failure", index, index + marker.length() - 1);
            }
        }
        return best;
    }

    public CompileSummary lastSummary() {
        return this.lastSummary;
    }

    public CompiledAssets assets() {
        return this.assets;
    }

    private CompiledAssets compile(Collection<NormalizedDocument> documents) {
        CompiledAssets compiled = new CompiledAssets();
        for (NormalizedDocument document : documents) {
            switch (document.kind) {
                case "lexicon" -> compileLexicon(compiled, document);
                case "grammar_patterns" -> compileGrammar(compiled, document);
                case "condition_definition", "condition_pack" -> compileCondition(compiled, document);
                case "semantic_operation", "semantic_operation_pack" -> compileSemanticOperation(compiled, document);
                case "resolver_rules" -> compileResolverRules(compiled, document);
                case "reference_patterns" -> compileReferences(compiled, document);
                case "selector_pack" -> compileSelectors(compiled, document);
                case "template_metadata" -> compileMetadata(compiled, document);
                case "task_template" -> compileTemplate(compiled, document);
                case "task_preset" -> compilePreset(compiled, document);
                case "task_macro" -> compileMacro(compiled, document);
                case "alias_pack" -> compileAliasPack(compiled, document);
                case "compile_profile" -> compileProfile(compiled, document);
                case "namespace_pack" -> compileNamespacePack(compiled, document);
                default -> throw new IllegalStateException("Unknown .ktl kind: " + document.kind);
            }
        }
        validateCompiledAssets(compiled);
        return compiled;
    }

    private void validateNormalizedDocuments(Collection<NormalizedDocument> documents) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (NormalizedDocument document : documents) {
            ids.add(document.id);
        }
        validateDocumentCollisions(documents, errors);
        validateDocumentReferences(documents, errors);
        for (NormalizedDocument document : documents) {
            String prefix = document.sourcePath + " kind=" + document.kind + " id=" + document.id;
            if (!KTL_KINDS.contains(document.kind)) {
                errors.add(prefix + " unknown kind");
                continue;
            }
            for (String dependency : document.dependencies) {
                if (!dependency.isBlank() && !ids.contains(dependency) && !Files.exists(Path.of(dependency)) && !Files.exists(ROOT.resolve(dependency))) {
                    errors.add(prefix + " depends_on unknown dependency " + dependency);
                }
            }
            validateDocumentShape(document, prefix, errors);
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(".ktl validation failed: " + String.join("; ", errors));
        }
    }

    private void validateDocumentReferences(Collection<NormalizedDocument> documents, List<String> errors) {
        Set<String> semanticIds = new LinkedHashSet<>();
        Set<String> templateIds = new LinkedHashSet<>();
        Set<String> conditionIds = new LinkedHashSet<>();
        for (NormalizedDocument document : documents) {
            switch (document.kind) {
                case "semantic_operation", "semantic_operation_pack" -> {
                    List<Map<String, Object>> operations = document.body.containsKey("operations") ? list(document.body.get("operations")) : List.of(document.body);
                    for (Map<String, Object> operation : operations) {
                        String id = string(operation.get("id"));
                        if (!id.isBlank()) {
                            semanticIds.add(id);
                        }
                    }
                }
                case "task_template" -> {
                    String templateId = string(document.body.get("template_id"));
                    if (!templateId.isBlank()) {
                        templateIds.add(templateId);
                    }
                }
                case "condition_definition", "condition_pack" -> {
                    List<Map<String, Object>> conditions = document.body.containsKey("conditions") ? list(document.body.get("conditions")) : List.of(document.body);
                    for (Map<String, Object> condition : conditions) {
                        String id = string(condition.get("id"));
                        if (!id.isBlank()) {
                            conditionIds.add(id);
                        }
                    }
                }
                default -> {
                }
            }
        }
        for (NormalizedDocument document : documents) {
            String prefix = document.sourcePath + " kind=" + document.kind + " id=" + document.id;
            switch (document.kind) {
                case "lexicon" -> {
                    List<Map<String, Object>> entries = list(document.body.get("entries"));
                    for (int index = 0; index < entries.size(); index++) {
                        String mapsTo = string(entries.get(index).get("maps_to"));
                        if (mapsTo.startsWith("sem.") && !semanticIds.contains(mapsTo)) {
                            errors.add(prefix + ".entries[" + index + "].maps_to references unknown semantic_operation " + mapsTo);
                        }
                    }
                }
                case "grammar_patterns" -> {
                    List<Map<String, Object>> patterns = list(document.body.get("patterns"));
                    for (int index = 0; index < patterns.size(); index++) {
                        String semanticOperation = string(patterns.get(index).get("semantic_operation"));
                        if (!semanticOperation.isBlank() && !semanticIds.contains(semanticOperation)) {
                            errors.add(prefix + ".patterns[" + index + "].semantic_operation references unknown semantic_operation " + semanticOperation);
                        }
                    }
                }
                case "condition_definition", "condition_pack" -> {
                    List<Map<String, Object>> conditions = document.body.containsKey("conditions") ? list(document.body.get("conditions")) : List.of(document.body);
                    for (int index = 0; index < conditions.size(); index++) {
                        String evaluator = string(conditions.get(index).get("evaluator_key"));
                        if (!evaluator.isBlank() && !EVALUATOR_KEYS.contains(evaluator)) {
                            errors.add(prefix + ".conditions[" + index + "].evaluator_key references unknown evaluator " + evaluator);
                        }
                    }
                }
                case "semantic_operation", "semantic_operation_pack" -> {
                    List<Map<String, Object>> operations = document.body.containsKey("operations") ? list(document.body.get("operations")) : List.of(document.body);
                    for (int index = 0; index < operations.size(); index++) {
                        for (String templateId : stringList(operations.get(index).get("preferred_templates"))) {
                            if (!templateIds.contains(templateId)) {
                                errors.add(prefix + ".operations[" + index + "].preferred_templates references unknown task_template " + templateId);
                            }
                        }
                    }
                }
                case "resolver_rules" -> {
                    List<Map<String, Object>> rules = list(document.body.get("rules"));
                    for (int index = 0; index < rules.size(); index++) {
                        Map<String, Object> rule = rules.get(index);
                        String semanticOperation = string(rule.get("semantic_operation"));
                        String preferredTemplate = string(rule.get("preferred_template"));
                        if (!semanticOperation.isBlank() && !semanticIds.contains(semanticOperation)) {
                            errors.add(prefix + ".rules[" + index + "].semantic_operation references unknown semantic_operation " + semanticOperation);
                        }
                        if (!preferredTemplate.isBlank() && !templateIds.contains(preferredTemplate)) {
                            errors.add(prefix + ".rules[" + index + "].preferred_template references unknown task_template " + preferredTemplate);
                        }
                    }
                }
                case "template_metadata" -> {
                    List<Map<String, Object>> entries = document.body.containsKey("entries") ? list(document.body.get("entries")) : List.of(document.body);
                    for (int index = 0; index < entries.size(); index++) {
                        Map<String, Object> entry = entries.get(index);
                        String templateId = string(entry.get("template_id"));
                        String entryPath = document.body.containsKey("entries") ? prefix + ".entries[" + index + "]" : prefix;
                        if (!templateId.isBlank() && !templateIds.contains(templateId)) {
                            errors.add(entryPath + ".template_id references unknown task_template " + templateId);
                        }
                        for (String semanticOperation : stringList(entry.get("semantic_operations"))) {
                            if (!semanticIds.contains(semanticOperation)) {
                                errors.add(entryPath + ".semantic_operations references unknown semantic_operation " + semanticOperation);
                            }
                        }
                    }
                }
                case "task_template" -> {
                    String semanticOperation = string(document.body.get("semantic_operation"));
                    if (!semanticOperation.isBlank() && !semanticIds.contains(semanticOperation)) {
                        errors.add(prefix + ".semantic_operation references unknown semantic_operation " + semanticOperation);
                    }
                    for (Map<String, Object> step : list(document.body.get("steps"))) {
                        String condition = string(step.get("condition"));
                        if (!condition.isBlank() && !condition.contains("${") && !EVALUATOR_KEYS.contains(condition) && !conditionIds.contains(condition)) {
                            errors.add(prefix + ".steps.condition references unknown evaluator/condition " + condition);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void validateDocumentCollisions(Collection<NormalizedDocument> documents, List<String> errors) {
        Map<String, String> templateIds = new LinkedHashMap<>();
        Map<String, String> metadataIds = new LinkedHashMap<>();
        Map<String, String> semanticIds = new LinkedHashMap<>();
        Map<String, String> conditionIds = new LinkedHashMap<>();
        Map<String, String> lexiconPhrases = new LinkedHashMap<>();
        Map<String, String> selectorPhrases = new LinkedHashMap<>();
        Map<String, String> referencePhrases = new LinkedHashMap<>();
        Map<String, String> conditionAliases = new LinkedHashMap<>();
        for (NormalizedDocument document : documents) {
            String prefix = document.sourcePath + " kind=" + document.kind + " id=" + document.id;
            switch (document.kind) {
                case "task_template" -> rememberUnique(templateIds, string(document.body.get("template_id")), prefix + ".template_id", errors);
                case "template_metadata" -> {
                    if (document.body.containsKey("entries")) {
                        List<Map<String, Object>> entries = list(document.body.get("entries"));
                        for (int index = 0; index < entries.size(); index++) {
                            rememberUnique(metadataIds, string(entries.get(index).get("template_id")), prefix + ".entries[" + index + "].template_id", errors);
                        }
                    } else {
                        rememberUnique(metadataIds, string(document.body.get("template_id")), prefix + ".template_id", errors);
                    }
                }
                case "semantic_operation", "semantic_operation_pack" -> {
                    List<Map<String, Object>> operations = document.body.containsKey("operations") ? list(document.body.get("operations")) : List.of(document.body);
                    for (int index = 0; index < operations.size(); index++) {
                        rememberUnique(semanticIds, string(operations.get(index).get("id")), prefix + ".operations[" + index + "].id", errors);
                    }
                }
                case "condition_definition", "condition_pack" -> {
                    List<Map<String, Object>> conditions = document.body.containsKey("conditions") ? list(document.body.get("conditions")) : List.of(document.body);
                    for (int index = 0; index < conditions.size(); index++) {
                        Map<String, Object> condition = conditions.get(index);
                        rememberUnique(conditionIds, string(condition.get("id")), prefix + ".conditions[" + index + "].id", errors);
                        for (String alias : stringList(condition.get("aliases"))) {
                            rememberPhrase(conditionAliases, alias, prefix + ".conditions[" + index + "].aliases", errors);
                        }
                    }
                }
                case "lexicon" -> {
                    List<Map<String, Object>> entries = list(document.body.get("entries"));
                    for (int index = 0; index < entries.size(); index++) {
                        Map<String, Object> entry = entries.get(index);
                        for (String phrase : stringList(entry.get("phrases"))) {
                            rememberPhrase(lexiconPhrases, phrase, prefix + ".entries[" + index + "].phrases", errors);
                        }
                    }
                }
                case "selector_pack" -> {
                    List<Map<String, Object>> selectors = list(document.body.get("selectors"));
                    for (int index = 0; index < selectors.size(); index++) {
                        Map<String, Object> selector = selectors.get(index);
                        for (String phrase : stringList(selector.get("phrases"))) {
                            rememberPhrase(selectorPhrases, phrase, prefix + ".selectors[" + index + "].phrases", errors);
                        }
                    }
                }
                case "reference_patterns" -> {
                    List<Map<String, Object>> patterns = list(document.body.get("patterns"));
                    for (int index = 0; index < patterns.size(); index++) {
                        Map<String, Object> pattern = patterns.get(index);
                        for (String phrase : stringList(pattern.get("phrases"))) {
                            rememberPhrase(referencePhrases, phrase, prefix + ".patterns[" + index + "].phrases", errors);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void validateDocumentShape(NormalizedDocument document, String prefix, List<String> errors) {
        switch (document.kind) {
            case "lexicon" -> {
                List<Map<String, Object>> entries = list(document.body.get("entries"));
                requireNonEmpty(entries, prefix + ".entries", errors);
                Set<String> entryIds = new LinkedHashSet<>();
                for (int index = 0; index < entries.size(); index++) {
                    Map<String, Object> entry = entries.get(index);
                    String path = prefix + ".entries[" + index + "]";
                    requireField(entry, "id", path, errors);
                    requireField(entry, "type", path, errors);
                    requireField(entry, "maps_to", path, errors);
                    requireStringList(entry.get("phrases"), path + ".phrases", errors);
                    String id = string(entry.get("id"));
                    if (!id.isBlank() && !entryIds.add(id)) {
                        errors.add(path + " duplicate id " + id);
                    }
                }
            }
            case "grammar_patterns" -> {
                List<Map<String, Object>> patterns = list(document.body.get("patterns"));
                requireNonEmpty(patterns, prefix + ".patterns", errors);
                Set<String> patternIds = new LinkedHashSet<>();
                for (int index = 0; index < patterns.size(); index++) {
                    Map<String, Object> pattern = patterns.get(index);
                    String path = prefix + ".patterns[" + index + "]";
                    requireField(pattern, "id", path, errors);
                    requireField(pattern, "shape", path, errors);
                    String id = string(pattern.get("id"));
                    String shape = string(pattern.get("shape"));
                    if (!id.isBlank() && !patternIds.add(id)) {
                        errors.add(path + " duplicate id " + id);
                    }
                    if (!shape.isBlank() && !GRAMMAR_SHAPES.contains(shape)) {
                        errors.add(path + " unknown shape " + shape);
                    }
                }
            }
            case "condition_definition", "condition_pack" -> {
                List<Map<String, Object>> conditions = document.body.containsKey("conditions") ? list(document.body.get("conditions")) : List.of(document.body);
                requireNonEmpty(conditions, prefix + ".conditions", errors);
                Set<String> conditionIds = new LinkedHashSet<>();
                for (int index = 0; index < conditions.size(); index++) {
                    Map<String, Object> condition = conditions.get(index);
                    String path = prefix + ".conditions[" + index + "]";
                    requireField(condition, "id", path, errors);
                    requireField(condition, "evaluator_key", path, errors);
                    requireStringList(condition.get("aliases"), path + ".aliases", errors);
                    String id = string(condition.get("id"));
                    if (!id.isBlank() && !conditionIds.add(id)) {
                        errors.add(path + " duplicate id " + id);
                    }
                }
            }
            case "semantic_operation", "semantic_operation_pack" -> {
                List<Map<String, Object>> operations = document.body.containsKey("operations") ? list(document.body.get("operations")) : List.of(document.body);
                requireNonEmpty(operations, prefix + ".operations", errors);
                Set<String> operationIds = new LinkedHashSet<>();
                for (int index = 0; index < operations.size(); index++) {
                    Map<String, Object> operation = operations.get(index);
                    String path = prefix + ".operations[" + index + "]";
                    requireField(operation, "id", path, errors);
                    requireField(operation, "intent", path, errors);
                    String id = string(operation.get("id"));
                    if (!id.isBlank() && !operationIds.add(id)) {
                        errors.add(path + " duplicate id " + id);
                    }
                }
            }
            case "resolver_rules" -> {
                List<Map<String, Object>> rules = list(document.body.get("rules"));
                requireNonEmpty(rules, prefix + ".rules", errors);
                for (int index = 0; index < rules.size(); index++) {
                    requireField(rules.get(index), "semantic_operation", prefix + ".rules[" + index + "]", errors);
                }
            }
            case "reference_patterns" -> {
                List<Map<String, Object>> patterns = list(document.body.get("patterns"));
                requireNonEmpty(patterns, prefix + ".patterns", errors);
                for (int index = 0; index < patterns.size(); index++) {
                    Map<String, Object> pattern = patterns.get(index);
                    String path = prefix + ".patterns[" + index + "]";
                    requireField(pattern, "referent_kind", path, errors);
                    requireStringList(pattern.get("phrases"), path + ".phrases", errors);
                }
            }
            case "selector_pack" -> {
                List<Map<String, Object>> selectors = list(document.body.get("selectors"));
                requireNonEmpty(selectors, prefix + ".selectors", errors);
                Set<String> selectorIds = new LinkedHashSet<>();
                for (int index = 0; index < selectors.size(); index++) {
                    Map<String, Object> selector = selectors.get(index);
                    String path = prefix + ".selectors[" + index + "]";
                    requireField(selector, "id", path, errors);
                    requireStringList(selector.get("phrases"), path + ".phrases", errors);
                    String id = string(selector.get("id"));
                    if (!id.isBlank() && !selectorIds.add(id)) {
                        errors.add(path + " duplicate id " + id);
                    }
                }
            }
            case "template_metadata" -> {
                if (document.body.containsKey("entries")) {
                    List<Map<String, Object>> entries = list(document.body.get("entries"));
                    requireNonEmpty(entries, prefix + ".entries", errors);
                    for (int index = 0; index < entries.size(); index++) {
                        requireField(entries.get(index), "template_id", prefix + ".entries[" + index + "]", errors);
                        requireStringList(entries.get(index).get("semantic_operations"), prefix + ".entries[" + index + "].semantic_operations", errors);
                    }
                } else {
                    requireField(document.body, "template_id", prefix, errors);
                    requireStringList(document.body.get("semantic_operations"), prefix + ".semantic_operations", errors);
                }
            }
            case "task_template" -> {
                requireField(document.body, "template_id", prefix, errors);
                requireField(document.body, "semantic_operation", prefix, errors);
                requireNonEmpty(list(document.body.get("steps")), prefix + ".steps", errors);
            }
            case "task_preset", "task_macro", "alias_pack", "compile_profile", "namespace_pack" -> {
            }
            default -> {
            }
        }
    }

    private void validateCompiledAssets(CompiledAssets compiled) {
        List<String> errors = new ArrayList<>();
        for (CompiledTaskTemplate template : compiled.templates.values()) {
            if (!compiled.templateMetadata.containsKey(template.templateId())) {
                errors.add("missing template_metadata for task_template " + template.templateId());
            }
            if (!template.semanticOperationId().isBlank() && !compiled.semanticOperations.containsKey(template.semanticOperationId())) {
                errors.add("task_template " + template.templateId() + " references unknown semantic_operation " + template.semanticOperationId());
            }
            validateSteps(compiled, template, errors);
        }
        for (CompiledTemplateMetadata metadata : compiled.templateMetadata.values()) {
            if (!compiled.templates.containsKey(metadata.templateId())) {
                errors.add("template_metadata " + metadata.templateId() + " does not point at a task_template");
            }
            for (String semanticOperation : metadata.semanticOperations()) {
                if (!compiled.semanticOperations.containsKey(semanticOperation)) {
                    errors.add("template_metadata " + metadata.templateId() + " references unknown semantic_operation " + semanticOperation);
                }
            }
        }
        for (SemanticOperationDefinition operation : compiled.semanticOperations.values()) {
            for (String templateId : operation.preferredTemplates()) {
                if (!compiled.templates.containsKey(templateId)) {
                    errors.add("semantic_operation " + operation.id() + " prefers unknown template " + templateId);
                }
            }
        }
        for (Map.Entry<String, String> entry : compiled.preferredTemplates.entrySet()) {
            if (!compiled.semanticOperations.containsKey(entry.getKey())) {
                errors.add("resolver_rules references unknown semantic_operation " + entry.getKey());
            }
            if (!compiled.templates.containsKey(entry.getValue())) {
                errors.add("resolver_rules preferred_template " + entry.getValue() + " is not a task_template");
            }
        }
        for (String semanticOperation : compiled.preferredKinds.keySet()) {
            if (!semanticOperation.isBlank() && !compiled.semanticOperations.containsKey(semanticOperation)) {
                errors.add("resolver_rules preferred_target_kind references unknown semantic_operation " + semanticOperation);
            }
        }
        for (ConditionDefinition condition : compiled.conditions.values()) {
            if (!EVALUATOR_KEYS.contains(condition.evaluatorKey())) {
                errors.add("condition " + condition.id() + " references unknown evaluator " + condition.evaluatorKey());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(".ktl validation failed: " + String.join("; ", errors));
        }
    }

    private void validateSteps(CompiledAssets compiled, CompiledTaskTemplate template, List<String> errors) {
        Set<String> labels = new LinkedHashSet<>();
        for (CompiledStep step : template.steps()) {
            if (!step.label().isBlank()) {
                labels.add(step.label());
            }
        }
        for (int index = 0; index < template.steps().size(); index++) {
            CompiledStep step = template.steps().get(index);
            String path = template.templateId() + ".steps[" + index + "]";
            switch (step.type()) {
                case "run_primitive" -> {
                    if (step.action().isBlank()) {
                        errors.add(path + " run_primitive is missing action");
                    } else if (!CAPABILITY_KEYS.contains(step.action())) {
                        errors.add(path + " run_primitive references unknown capability " + step.action());
                    }
                }
                case "delegate" -> {
                    if (step.delegate().isBlank()) {
                        errors.add(path + " delegate is missing delegate input");
                    } else {
                        validateStaticDelegateTarget(compiled, step.delegate(), path + ".delegate", errors);
                    }
                }
                case "branch" -> {
                    if (step.condition().isBlank()) {
                        errors.add(path + " branch is missing condition");
                    } else if (!step.condition().contains("${") && !EVALUATOR_KEYS.contains(step.condition()) && !compiled.conditions.containsKey(step.condition())) {
                        errors.add(path + " branch references unknown evaluator/condition " + step.condition());
                    }
                    validateStaticDelegateTarget(compiled, step.thenInput(), path + ".then_input", errors);
                    validateStaticDelegateTarget(compiled, step.elseInput(), path + ".else_input", errors);
                }
                case "goto" -> {
                    if (step.gotoLabel().isBlank()) {
                        errors.add(path + " goto is missing target label");
                    } else if (!"parent".equalsIgnoreCase(step.scope()) && !labels.contains(step.gotoLabel())) {
                        errors.add(path + " goto target does not exist: " + step.gotoLabel());
                    }
                }
                case "return" -> {
                }
                default -> errors.add(path + " has unsupported step type " + step.type());
            }
        }
    }

    private void validateStaticDelegateTarget(CompiledAssets compiled, String value, String path, List<String> errors) {
        if (value == null || value.isBlank() || value.contains("${") || value.startsWith("/")) {
            return;
        }
        String firstToken = value.trim().split("\\s+", 2)[0];
        if (!firstToken.endsWith(".ktl")) {
            return;
        }
        String templateId = firstToken.substring(0, firstToken.length() - 4);
        if (compiled.templates.containsKey(templateId)) {
            return;
        }
        int slash = Math.max(templateId.lastIndexOf('/'), templateId.lastIndexOf('\\'));
        if (slash >= 0) {
            templateId = templateId.substring(slash + 1);
        }
        if (!compiled.templates.containsKey(templateId)) {
            errors.add(path + " references unknown task template " + firstToken);
        }
    }

    private void compileLexicon(CompiledAssets compiled, NormalizedDocument document) {
        List<Map<String, Object>> entries = list(document.body.get("entries"));
        for (Map<String, Object> entry : entries) {
            String mapsTo = string(entry.get("maps_to"));
            String type = string(entry.get("type"));
            Map<String, Object> defaults = map(entry.get("defaults"));
            for (String phrase : stringList(entry.get("phrases"))) {
                compiled.lexicon.put(phrase.toLowerCase(Locale.ROOT), new LexiconEntry(string(entry.get("id")), type, phrase, mapsTo, defaults));
            }
        }
    }

    private void compileGrammar(CompiledAssets compiled, NormalizedDocument document) {
        for (Map<String, Object> entry : list(document.body.get("patterns"))) {
            compiled.grammarPatterns.add(new GrammarPattern(
                    string(entry.get("id")),
                    string(entry.get("shape")),
                    string(entry.getOrDefault("semantic_operation", "")),
                    string(entry.getOrDefault("condition_slot", "")),
                    string(entry.getOrDefault("target_slot", "")),
                    string(entry.getOrDefault("count_slot", "")),
                    string(entry.getOrDefault("selector_slot", ""))
            ));
        }
    }

    private void compileCondition(CompiledAssets compiled, NormalizedDocument document) {
        List<Map<String, Object>> conditions = document.body.containsKey("conditions") ? list(document.body.get("conditions")) : List.of(document.body);
        for (Map<String, Object> condition : conditions) {
            ConditionDefinition definition = new ConditionDefinition(
                    string(condition.get("id")),
                    string(condition.get("evaluator_key")),
                    string(condition.getOrDefault("stat", "")),
                    string(condition.getOrDefault("operator", "lt")),
                    string(condition.getOrDefault("mode", "")),
                    stringList(condition.get("aliases"))
            );
            compiled.conditions.put(definition.id(), definition);
            for (String alias : definition.aliases()) {
                compiled.conditionAliases.put(alias.toLowerCase(Locale.ROOT), definition);
            }
        }
    }

    private void compileSemanticOperation(CompiledAssets compiled, NormalizedDocument document) {
        List<Map<String, Object>> operations = document.body.containsKey("operations") ? list(document.body.get("operations")) : List.of(document.body);
        for (Map<String, Object> operation : operations) {
            SemanticOperationDefinition definition = new SemanticOperationDefinition(
                    string(operation.get("id")),
                    string(operation.get("intent")),
                    stringList(operation.get("target_kinds")),
                    stringList(operation.get("preferred_templates"))
            );
            compiled.semanticOperations.put(definition.id(), definition);
        }
    }

    private void compileResolverRules(CompiledAssets compiled, NormalizedDocument document) {
        for (Map<String, Object> rule : list(document.body.get("rules"))) {
            String semanticOperation = string(rule.get("semantic_operation"));
            compiled.preferredKinds.put(semanticOperation, string(rule.get("preferred_target_kind")));
            String preferredTemplate = string(rule.get("preferred_template"));
            if (!preferredTemplate.isBlank()) {
                compiled.preferredTemplates.put(semanticOperation, preferredTemplate);
            }
            Map<String, Object> defaults = map(rule.get("defaults"));
            if (!defaults.isEmpty()) {
                compiled.resolverDefaults.put(semanticOperation, defaults);
            }
        }
    }

    private void compileReferences(CompiledAssets compiled, NormalizedDocument document) {
        for (Map<String, Object> rule : list(document.body.get("patterns"))) {
            for (String phrase : stringList(rule.get("phrases"))) {
                compiled.referencePatterns.put(phrase.toLowerCase(Locale.ROOT), string(rule.get("referent_kind")));
            }
        }
    }

    private void compileSelectors(CompiledAssets compiled, NormalizedDocument document) {
        for (Map<String, Object> selector : list(document.body.get("selectors"))) {
            for (String phrase : stringList(selector.get("phrases"))) {
                compiled.selectors.put(phrase.toLowerCase(Locale.ROOT), string(selector.get("id")));
            }
        }
    }

    private void compileMetadata(CompiledAssets compiled, NormalizedDocument document) {
        if (document.body.containsKey("entries")) {
            for (Map<String, Object> entry : list(document.body.get("entries"))) {
                CompiledTemplateMetadata metadata = new CompiledTemplateMetadata(
                        string(entry.get("template_id")),
                        stringList(entry.get("semantic_operations")),
                        stringList(entry.get("target_kinds")),
                        stringList(entry.get("required_params")),
                        stringList(entry.get("optional_params")),
                        stringList(entry.get("tags"))
                );
                compiled.templateMetadata.put(metadata.templateId(), metadata);
            }
            return;
        }
        CompiledTemplateMetadata metadata = new CompiledTemplateMetadata(
                string(document.body.get("template_id")),
                stringList(document.body.get("semantic_operations")),
                stringList(document.body.get("target_kinds")),
                stringList(document.body.get("required_params")),
                stringList(document.body.get("optional_params")),
                stringList(document.body.get("tags"))
        );
        compiled.templateMetadata.put(metadata.templateId(), metadata);
    }

    private void compileTemplate(CompiledAssets compiled, NormalizedDocument document) {
        String templateId = string(document.body.get("template_id"));
        List<Map<String, Object>> stepMaps = list(document.body.get("steps"));
        List<Integer> sourceLines = stepSourceLines(document.sourcePath, stepMaps.size());
        List<CompiledStep> steps = new ArrayList<>();
        for (int index = 0; index < stepMaps.size(); index++) {
            Map<String, Object> step = stepMaps.get(index);
            steps.add(new CompiledStep(
                    string(step.get("type")),
                    string(step.getOrDefault("action", "")),
                    string(step.getOrDefault("delegate", "")),
                    string(step.getOrDefault("condition", "")),
                    string(step.getOrDefault("then_input", "")),
                    string(step.getOrDefault("else_input", "")),
                    string(step.getOrDefault("label", "")),
                    string(step.getOrDefault("goto", "")),
                    map(step.get("params")),
                    map(step.get("writes")),
                    string(step.getOrDefault("scope", "")),
                    document.sourcePath,
                    index < sourceLines.size() ? sourceLines.get(index) : 0
            ));
        }
        compiled.templates.put(templateId, new CompiledTaskTemplate(
                templateId,
                string(document.body.get("semantic_operation")),
                stringList(document.body.get("params")),
                steps
        ));
        Map<String, Object> embeddedMetadata = map(document.body.get("metadata"));
        if (!embeddedMetadata.isEmpty()) {
            List<String> semanticOperations = stringList(embeddedMetadata.get("semantic_operations"));
            if (semanticOperations.isEmpty()) {
                String semanticOperation = string(document.body.get("semantic_operation"));
                semanticOperations = semanticOperation.isBlank() ? List.of() : List.of(semanticOperation);
            }
            compiled.templateMetadata.put(templateId, new CompiledTemplateMetadata(
                    templateId,
                    semanticOperations,
                    stringList(embeddedMetadata.get("target_kinds")),
                    stringList(embeddedMetadata.get("required_params")),
                    stringList(embeddedMetadata.get("optional_params")),
                    stringList(embeddedMetadata.get("tags"))
            ));
        }
    }

    private List<Integer> stepSourceLines(String sourcePath, int expectedSteps) {
        List<Integer> lines = new ArrayList<>();
        try {
            List<String> sourceLines = Files.readAllLines(Path.of(sourcePath), StandardCharsets.UTF_8);
            for (int index = 0; index < sourceLines.size(); index++) {
                if (sourceLines.get(index).matches("\\s*-\\s+type:\\s+.*")) {
                    lines.add(index + 1);
                }
            }
        } catch (IOException ignored) {
            return Collections.nCopies(expectedSteps, 0);
        }
        while (lines.size() < expectedSteps) {
            lines.add(0);
        }
        return lines;
    }

    private void compilePreset(CompiledAssets compiled, NormalizedDocument document) {
        compiled.presets.put(string(document.id), document.body);
    }

    private void compileMacro(CompiledAssets compiled, NormalizedDocument document) {
        compiled.macros.put(string(document.id), document.body);
    }

    private void compileAliasPack(CompiledAssets compiled, NormalizedDocument document) {
        compiled.aliasPacks.put(string(document.id), document.body);
    }

    private void compileProfile(CompiledAssets compiled, NormalizedDocument document) {
        compiled.compileProfiles.put(string(document.id), document.body);
    }

    private void compileNamespacePack(CompiledAssets compiled, NormalizedDocument document) {
        compiled.namespacePacks.put(string(document.id), document.body);
    }

    private Frame parseFrame(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.startsWith("if ") || lower.startsWith("execute if ")) {
            for (GrammarPattern pattern : this.assets.grammarPatterns) {
                if ("if_condition_then_else".equals(pattern.shape()) || "if_condition_then".equals(pattern.shape())) {
                    Frame frame = parseConditionBranch(input, pattern);
                    if (frame != null) {
                        return applyLeadingActionDefaults(input, frame);
                    }
                }
            }
        }
        Frame coordinateMove = parseCoordinateMoveBeforeTargetResolvers(input);
        if (coordinateMove != null) {
            return applyLeadingActionDefaults(input, coordinateMove);
        }
        for (GrammarPattern pattern : this.assets.grammarPatterns) {
            Frame frame = switch (pattern.shape()) {
                case "sequence_then", "sequence_and_then" -> parseSequenceThen(input, pattern);
                case "if_condition_then_else", "if_condition_then" -> parseConditionBranch(input, pattern);
                case "action_count_target" -> parseActionCountTarget(input, pattern);
                case "action_selector_target" -> parseActionSelectorTarget(input, pattern);
                case "action_direction_amount_unit" -> parseMovement(input, pattern);
                case "action_direction" -> parseActionDirection(input, pattern);
                case "action_amount_unit" -> parseActionAmountUnit(input, pattern);
                case "action_hand" -> parseActionHand(input, pattern);
                case "action_selector_count_target" -> parseActionSelectorCountTarget(input, pattern);
                case "action_text" -> parseActionText(input, pattern);
                case "action_duration" -> parseActionDuration(input, pattern);
                case "action_key" -> parseActionKey(input, pattern);
                case "action_key_duration" -> parseActionKeyDuration(input, pattern);
                case "action_target_location" -> parseActionTargetLocation(input, pattern);
                case "reference_action" -> parseReferenceAction(input, pattern);
                case "action_on_target" -> parseActionOnTarget(input, pattern);
                case "action_item_on_target" -> parseActionItemOnTarget(input, pattern);
                case "action_item_from_target" -> parseActionItemFromTarget(input, pattern);
                case "action_to_target" -> parseActionToTarget(input, pattern);
                case "action_target" -> parseActionTarget(input, pattern);
                default -> null;
            };
            if (frame != null) {
                return applyLeadingActionDefaults(input, frame);
            }
        }
        Frame simpleAction = parseSimpleAction(input);
        if (simpleAction != null) {
            return applyLeadingActionDefaults(input, simpleAction);
        }
        if (lower.startsWith("/")) {
            Frame frame = new Frame();
            frame.shape = "raw_command";
            frame.semanticOperationId = "sem.task.raw_command";
            frame.additionalParams.put("raw.command", input.substring(1));
            return frame;
        }
        return null;
    }

    private Frame parseConditionBranch(String input, GrammarPattern pattern) {
        String trimmed = input.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("if ") && !lower.startsWith("execute if ")) {
            return null;
        }
        String body = lower.startsWith("execute if ") ? trimmed.substring("execute if ".length()).trim() : trimmed.substring(3).trim();
        int thenIndex = findConnector(body, List.of("then"));
        if (thenIndex < 0) {
            return null;
        }
        String conditionText = body.substring(0, thenIndex).trim();
        String remainder = body.substring(thenIndex + connectorAt(body, thenIndex, List.of("then")).length()).trim();
        int elseIndex = findConnector(remainder, List.of("else"));
        String thenInput = elseIndex < 0 ? remainder : remainder.substring(0, elseIndex).trim();
        String elseInput = elseIndex < 0 ? "" : remainder.substring(elseIndex + connectorAt(remainder, elseIndex, List.of("else")).length()).trim();
        if (conditionText.isBlank() || thenInput.isBlank()) {
            return null;
        }
        ConditionDefinition definition = findCondition(conditionText);
        if (definition == null) {
            return null;
        }
        Matcher number = Pattern.compile("(-?\\d+)").matcher(conditionText);
        Integer threshold = number.find() ? Integer.parseInt(number.group(1)) : null;
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = "sem.task.branch";
        frame.conditionId = definition.id();
        frame.conditionThreshold = threshold;
        frame.additionalParams.put("then.input", thenInput);
        if (!elseInput.isBlank()) {
            frame.additionalParams.put("else.input", elseInput);
        }
        return frame;
    }

    private Frame parseSequenceThen(String input, GrammarPattern pattern) {
        if (input.trim().toLowerCase(Locale.ROOT).startsWith("if ")) {
            return null;
        }
        int connectorIndex = findConnector(input, List.of("and then", "after that", "then"));
        if (connectorIndex < 0) {
            return null;
        }
        String connector = connectorAt(input, connectorIndex, List.of("and then", "after that", "then"));
        String first = input.substring(0, connectorIndex).trim();
        String second = input.substring(connectorIndex + connector.length()).trim();
        if (first.isBlank() || second.isBlank()) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = "sem.task.sequence";
        frame.additionalParams.put("first.input", first);
        frame.additionalParams.put("second.input", second);
        return frame;
    }

    private Frame parseActionCountTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 2) {
            return null;
        }
        if (!tokens[action.tokenCount].matches("-?\\d+")) {
            return null;
        }
        if ("sem.task.move_relative".equals(action.entry.mapsTo())) {
            CoordinateParse coordinate = parseCoordinateTokens(tokens, action.tokenCount);
            if (coordinate != null) {
                Frame frame = new Frame();
                frame.shape = "action_target_location";
                frame.semanticOperationId = "sem.task.move_to_target";
                frame.additionalParams.put("target.kind", "location");
                frame.additionalParams.put("target.x", coordinate.x());
                frame.additionalParams.put("target.z", coordinate.z());
                if (coordinate.y() != null) {
                    frame.additionalParams.put("target.y", coordinate.y());
                }
                return frame;
            }
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.countValue = Integer.parseInt(tokens[action.tokenCount]);
        frame.targetRawText = joinTokens(tokens, action.tokenCount + 1);
        return frame;
    }

    private Frame parseActionSelectorTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 2) {
            return null;
        }
        String selector = this.assets.selectors.get(tokens[action.tokenCount].toLowerCase(Locale.ROOT));
        if (action == null || selector == null) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.selectorId = selector;
        frame.targetRawText = joinTokens(tokens, action.tokenCount + 1);
        return frame;
    }

    private Frame parseActionTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 1) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.targetRawText = joinTokens(tokens, action.tokenCount);
        return frame;
    }

    private Frame parseSimpleAction(String input) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length != action.tokenCount) {
            return null;
        }
        SemanticOperationDefinition operation = this.assets.semanticOperations.get(action.entry.mapsTo());
        if (operation == null || !operation.targetKinds().isEmpty()) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = "action_simple";
        frame.semanticOperationId = action.entry.mapsTo();
        return frame;
    }

    private Frame parseMovement(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 3) {
            return null;
        }
        Integer amount = tryParseInteger(tokens[action.tokenCount + 1]);
        if (amount == null) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        String directionText = tokens[action.tokenCount].toLowerCase(Locale.ROOT);
        frame.additionalParams.put("direction.id", this.assets.selectors.getOrDefault(directionText, directionText));
        frame.countValue = amount;
        frame.additionalParams.put("unit.id", tokens[action.tokenCount + 2].toLowerCase(Locale.ROOT));
        return frame;
    }

    private Frame parseActionDirection(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length != action.tokenCount + 1) {
            return null;
        }
        String selector = this.assets.selectors.get(tokens[action.tokenCount].toLowerCase(Locale.ROOT));
        if (selector == null) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.additionalParams.put("direction.id", selector);
        return frame;
    }

    private Frame parseActionAmountUnit(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length != action.tokenCount + 2) {
            return null;
        }
        if (!tokens[action.tokenCount].matches("-?\\d+")) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.countValue = Integer.parseInt(tokens[action.tokenCount]);
        frame.additionalParams.put("unit.id", tokens[action.tokenCount + 1].toLowerCase(Locale.ROOT));
        if ("sem.task.move_relative".equals(frame.semanticOperationId)) {
            frame.additionalParams.put("direction.id", "forward");
        }
        return frame;
    }

    private Frame parseActionHand(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount) {
            return null;
        }
        String handText = joinTokens(tokens, action.tokenCount).toLowerCase(Locale.ROOT);
        String selector = this.assets.selectors.get(handText);
        if (selector == null || (!"main_hand".equals(selector) && !"off_hand".equals(selector))) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.additionalParams.put("hand.id", selector);
        return frame;
    }

    private Frame parseActionSelectorCountTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 3) {
            return null;
        }
        String selector = this.assets.selectors.get(tokens[action.tokenCount].toLowerCase(Locale.ROOT));
        if (selector == null || !tokens[action.tokenCount + 1].matches("-?\\d+")) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.selectorId = selector;
        frame.countValue = Integer.parseInt(tokens[action.tokenCount + 1]);
        frame.targetRawText = joinTokens(tokens, action.tokenCount + 2);
        return frame;
    }

    private Frame parseActionText(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.additionalParams.put("message", joinTokens(tokens, action.tokenCount));
        return frame;
    }

    private Frame parseActionDuration(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 2) {
            return null;
        }
        if (!tokens[action.tokenCount].matches("-?\\d+")) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.countValue = Integer.parseInt(tokens[action.tokenCount]);
        frame.additionalParams.put("unit.id", tokens[action.tokenCount + 1].toLowerCase(Locale.ROOT));
        return frame;
    }

    private Frame parseActionKey(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.additionalParams.put("input.key", joinTokens(tokens, action.tokenCount).toLowerCase(Locale.ROOT).replace(' ', '_'));
        return frame;
    }

    private Frame parseActionKeyDuration(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 2) {
            return null;
        }
        int durationIndex = tokens.length - 2;
        if (durationIndex < action.tokenCount || !tokens[durationIndex].matches("-?\\d+")) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.additionalParams.put("input.key", String.join(" ", Arrays.copyOfRange(tokens, action.tokenCount, durationIndex)).toLowerCase(Locale.ROOT).replace(' ', '_'));
        frame.countValue = Integer.parseInt(tokens[durationIndex]);
        frame.additionalParams.put("unit.id", tokens[durationIndex + 1].toLowerCase(Locale.ROOT));
        return frame;
    }

    private Frame parseActionTargetLocation(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length < action.tokenCount + 2) {
            return null;
        }
        int start = action.tokenCount;
        if (start < tokens.length && "to".equalsIgnoreCase(tokens[start])) {
            start++;
        }
        CoordinateParse coordinate = parseCoordinateTokens(tokens, start);
        if (coordinate == null) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.additionalParams.put("target.kind", "location");
        frame.additionalParams.put("target.x", coordinate.x());
        frame.additionalParams.put("target.z", coordinate.z());
        if (coordinate.y() != null) {
            frame.additionalParams.put("target.y", coordinate.y());
        }
        return frame;
    }

    private Frame parseCoordinateMoveBeforeTargetResolvers(String input) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount) {
            return null;
        }
        int start = action.tokenCount;
        if (start < tokens.length && "to".equalsIgnoreCase(cleanCoordinateToken(tokens[start]))) {
            start++;
        }
        if (tokens.length - start < 2) {
            return null;
        }
        CoordinateParse coordinate = parseCoordinateTokens(tokens, start);
        if (coordinate == null) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = "action_target_location";
        frame.semanticOperationId = switch (action.entry.mapsTo()) {
            case "sem.task.move_relative", "sem.task.move_to_target", "sem.task.swim", "sem.task.parkour_target" -> action.entry.mapsTo().equals("sem.task.move_relative") ? "sem.task.move_to_target" : action.entry.mapsTo();
            default -> null;
        };
        if (frame.semanticOperationId == null) {
            return null;
        }
        frame.additionalParams.put("target.kind", "location");
        frame.additionalParams.put("target.x", coordinate.x());
        frame.additionalParams.put("target.z", coordinate.z());
        if (coordinate.y() != null) {
            frame.additionalParams.put("target.y", coordinate.y());
        }
        return frame;
    }

    private Frame parseReferenceAction(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount) {
            return null;
        }
        String referenceText = joinTokens(tokens, action.tokenCount).toLowerCase(Locale.ROOT);
        if (!this.assets.referencePatterns.containsKey(referenceText)) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.targetRawText = referenceText;
        return frame;
    }

    private Frame parseActionOnTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount + 1) {
            return null;
        }
        int onIndex = indexOfToken(tokens, action.tokenCount, "on");
        if (onIndex < 0 || onIndex >= tokens.length - 1) {
            return null;
        }
        String left = String.join(" ", Arrays.copyOfRange(tokens, action.tokenCount, onIndex)).trim();
        if (!left.isBlank()) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.targetRawText = joinTokens(tokens, onIndex + 1);
        return frame;
    }

    private Frame parseActionItemOnTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount + 2) {
            return null;
        }
        int onIndex = indexOfToken(tokens, action.tokenCount, "on");
        if (onIndex < 0 || onIndex >= tokens.length - 1 || onIndex == action.tokenCount) {
            return null;
        }
        String itemText = String.join(" ", Arrays.copyOfRange(tokens, action.tokenCount, onIndex)).trim();
        String targetText = joinTokens(tokens, onIndex + 1).trim();
        if (itemText.isBlank() || targetText.isBlank()) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.targetRawText = targetText;
        frame.additionalParams.put("item.raw_text", itemText);
        return frame;
    }

    private Frame parseActionItemFromTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount + 2) {
            return null;
        }
        int fromIndex = indexOfToken(tokens, action.tokenCount, "from");
        if (fromIndex < 0 || fromIndex >= tokens.length - 1 || fromIndex == action.tokenCount) {
            return null;
        }
        int itemStart = action.tokenCount;
        Integer count = null;
        if (tokens[itemStart].matches("-?\\d+")) {
            count = Integer.parseInt(tokens[itemStart]);
            itemStart++;
        }
        String itemText = String.join(" ", Arrays.copyOfRange(tokens, itemStart, fromIndex)).trim();
        int targetStart = fromIndex + 1;
        String selector = targetStart < tokens.length ? this.assets.selectors.get(tokens[targetStart].toLowerCase(Locale.ROOT)) : null;
        if (selector != null && targetStart < tokens.length - 1) {
            targetStart++;
        }
        String targetText = joinTokens(tokens, targetStart).trim();
        if (itemText.isBlank() || targetText.isBlank()) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = pattern.semanticOperation().isBlank() ? action.entry.mapsTo() : pattern.semanticOperation();
        frame.targetRawText = targetText;
        if (selector != null) {
            frame.selectorId = selector;
        }
        if (count != null) {
            frame.countValue = count;
        } else {
            frame.additionalParams.put("count.value", 1);
        }
        frame.additionalParams.put("item.raw_text", itemText);
        return frame;
    }

    private Frame parseActionToTarget(String input, GrammarPattern pattern) {
        String[] tokens = input.split("\\s+");
        ActionMatch action = findLeadingAction(tokens);
        if (action == null || tokens.length <= action.tokenCount) {
            return null;
        }
        Frame frame = new Frame();
        frame.shape = pattern.shape();
        frame.semanticOperationId = action.entry.mapsTo();
        frame.targetRawText = joinTokens(tokens, action.tokenCount);
        return frame;
    }

    private Map<String, Object> resolve(Frame frame) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("semantic_operation.id", frame.semanticOperationId);
        Map<String, Object> resolverDefaults = this.assets.resolverDefaults.get(frame.semanticOperationId);
        if (resolverDefaults != null && !resolverDefaults.isEmpty()) {
            resolved.putAll(resolverDefaults);
        }
        if (frame.countValue != null) {
            resolved.put("count.value", frame.countValue);
            resolved.put("count.mode", "exact");
        }
        if ("sem.task.move_relative".equals(frame.semanticOperationId)) {
            resolved.putIfAbsent("direction.id", "forward");
        }
        if (frame.selectorId != null) {
            resolved.put("target.selector", frame.selectorId);
        }
        if (frame.conditionId != null) {
            resolved.put("condition.id", frame.conditionId);
            ConditionDefinition definition = this.assets.conditions.get(frame.conditionId);
            if (definition == null) {
                definition = builtinConditionById(frame.conditionId);
            }
            if (definition != null) {
                resolved.put("condition.evaluator", definition.evaluatorKey());
                if (!definition.operator().isBlank()) {
                    resolved.put("condition.op", definition.operator());
                }
                if (!definition.stat().isBlank()) {
                    resolved.put("stat.ref", definition.stat());
                }
                if (!definition.mode().isBlank()) {
                    resolved.put("condition.mode", definition.mode());
                }
            }
            if (frame.conditionThreshold != null) {
                resolved.put("condition.threshold", frame.conditionThreshold);
                resolved.put("condition.right", frame.conditionThreshold);
            }
        }
        resolved.putAll(frame.additionalParams);
        String itemRawText = string(frame.additionalParams.get("item.raw_text"));
        if (!itemRawText.isBlank()) {
            ResolvedTarget item = resolveItem(itemRawText);
            resolved.put("item.id", item.id);
            resolved.put("item.name", item.name);
            AutomationReporter.pipeline("[res ]", "item.id = " + item.id);
        }
        if (frame.targetRawText == null || frame.targetRawText.isBlank()) {
            if ("sem.task.consume_item".equals(frame.semanticOperationId) || "sem.task.use_item".equals(frame.semanticOperationId) || "sem.task.get_target".equals(frame.semanticOperationId)) {
                resolved.putIfAbsent("count.value", 1);
            }
            if ("sem.task.get_target".equals(frame.semanticOperationId)) {
                resolved.putIfAbsent("count.value", 1);
            }
            if ("sem.task.kill_target".equals(frame.semanticOperationId)) {
                resolved.putIfAbsent("state.counter", 0);
            }
            return resolved;
        }
        CoordinateParse coordinateTarget = parseCoordinateTargetText(frame.targetRawText);
        if (coordinateTarget != null && ("sem.task.move_to_target".equals(frame.semanticOperationId) || "sem.task.swim".equals(frame.semanticOperationId) || "sem.task.parkour_target".equals(frame.semanticOperationId))) {
            resolved.put("target.kind", "location");
            resolved.put("target.x", coordinateTarget.x());
            resolved.put("target.z", coordinateTarget.z());
            if (coordinateTarget.y() != null) {
                resolved.put("target.y", coordinateTarget.y());
            } else {
                resolved.remove("target.y");
            }
            AutomationReporter.pipeline("[res ]", "target.kind = location");
            AutomationReporter.pipeline("[res ]", "target.x = " + coordinateTarget.x());
            AutomationReporter.pipeline("[res ]", "target.z = " + coordinateTarget.z());
            SessionMemory.put("there", Map.of("target.kind", "location", "target.x", coordinateTarget.x(), "target.z", coordinateTarget.z()));
            return resolved;
        }
        SemanticOperationDefinition operation = this.assets.semanticOperations.get(frame.semanticOperationId);
        List<String> candidateKinds = new ArrayList<>();
        String preferredKind = this.assets.preferredKinds.getOrDefault(frame.semanticOperationId, operation != null && !operation.targetKinds().isEmpty() ? operation.targetKinds().get(0) : "item");
        candidateKinds.add(preferredKind);
        if (operation != null) {
            for (String kind : operation.targetKinds()) {
                if (!candidateKinds.contains(kind)) {
                    candidateKinds.add(kind);
                }
            }
        }
        String lower = frame.targetRawText.toLowerCase(Locale.ROOT);
        String referenceKind = this.assets.referencePatterns.get(lower);
        if (referenceKind != null) {
            if ("held_item".equals(referenceKind)) {
                Map<String, Object> held = resolveHeldItemReference(frame);
                resolved.putAll(held);
                AutomationReporter.pipeline("[ref ]", lower + " -> held_item");
                return resolved;
            }
            Object memory = SessionMemory.get(lower);
            if (memory instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    resolved.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                AutomationReporter.pipeline("[ref ]", lower + " -> " + map);
                return resolved;
            }
            AutomationReporter.block("[block]", "reference = unresolved '" + frame.targetRawText + "'");
            throw new IllegalStateException("Reference unresolved: " + frame.targetRawText);
        }
        for (String kind : candidateKinds) {
            try {
                if ("item".equals(kind)) {
                    ResolvedTarget target = resolveItem(frame.targetRawText);
                    resolved.put("target.kind", "item");
                    resolved.put("target.id", target.id);
                    resolved.put("item.id", target.id);
                    resolved.put("target.name", target.name);
                    resolved.putIfAbsent("count.value", "sem.task.get_target".equals(frame.semanticOperationId) ? 1 : resolved.get("count.value"));
                    AutomationReporter.pipeline("[res ]", "target.kind = item");
                    AutomationReporter.pipeline("[res ]", "target.id = " + target.id);
                    SessionMemory.put("it", Map.of("target.kind", "item", "target.id", target.id, "item.id", target.id));
                    SessionMemory.put("them", Map.of("target.kind", "item", "target.id", target.id, "item.id", target.id));
                    return resolved;
                }
                if ("block".equals(kind)) {
                    ResolvedTarget target = resolveBlock(frame.targetRawText);
                    resolved.put("target.kind", "block");
                    resolved.put("target.id", target.id);
                    resolved.put("target.name", target.name);
                    AutomationReporter.pipeline("[res ]", "target.kind = block");
                    AutomationReporter.pipeline("[res ]", "target.id = " + target.id);
                    SessionMemory.put("it", Map.of("target.kind", "block", "target.id", target.id));
                    return resolved;
                }
                if ("entity".equals(kind) || "player".equals(kind)) {
                    ResolvedTarget target = resolveEntity(frame.targetRawText);
                    resolved.put("target.kind", "entity");
                    resolved.put("target.id", target.id);
                    resolved.put("target.name", target.name);
                    resolved.putIfAbsent("state.counter", 0);
                    AutomationReporter.pipeline("[res ]", "target.kind = entity");
                    AutomationReporter.pipeline("[res ]", "target.id = " + target.id);
                    SessionMemory.put("it", Map.of("target.kind", "entity", "target.id", target.id));
                    return resolved;
                }
            } catch (IllegalStateException ignored) {
            }
        }
        resolved.put("target.kind", preferredKind);
        resolved.put("target.name", frame.targetRawText);
        return resolved;
    }

    private String selectTemplate(Frame frame, Map<String, Object> resolved) {
        int bestScore = Integer.MIN_VALUE;
        String bestTemplate = null;
        String preferredTemplate = this.assets.preferredTemplates.get(frame.semanticOperationId);
        if ("sem.task.move_to_target".equals(frame.semanticOperationId)
                && "location".equals(String.valueOf(resolved.get("target.kind")))
                && resolved.containsKey("target.x")
                && resolved.containsKey("target.z")
                && this.assets.templates.containsKey("movement/navigation/move_to_position")) {
            AutomationReporter.info("[cand]", "template.movement/navigation/move_to_position = forced coordinate route");
            AutomationReporter.pipeline("[pick]", "movement/navigation/move_to_position");
            return "movement/navigation/move_to_position";
        }
        List<TemplateCandidateScore> candidateScores = new ArrayList<>();
        for (CompiledTemplateMetadata metadata : this.assets.templateMetadata.values()) {
            if (!metadata.semanticOperations().contains(frame.semanticOperationId)) {
                continue;
            }
            if (metadata.tags().contains("tactic") && (preferredTemplate == null || !preferredTemplate.equals(metadata.templateId()))) {
                candidateScores.add(new TemplateCandidateScore(metadata.templateId(), -1000));
                continue;
            }
            int score = 0;
            boolean missingRequired = false;
            if (preferredTemplate != null && preferredTemplate.equals(metadata.templateId())) {
                score += 20;
            }
            if ("sem.task.move_to_target".equals(frame.semanticOperationId)
                    && "location".equals(String.valueOf(resolved.get("target.kind")))
                    && metadata.tags().contains("coordinate")) {
                score += 30;
            }
            if (resolved.containsKey("target.kind") && metadata.targetKinds().contains(String.valueOf(resolved.get("target.kind")))) {
                score += 4;
            }
            for (String required : metadata.requiredParams()) {
                if (resolved.containsKey(required)) {
                    score += 2;
                } else {
                    missingRequired = true;
                    score -= 100;
                }
            }
            for (String optional : metadata.optionalParams()) {
                if (resolved.containsKey(optional)) {
                    score += 1;
                }
            }
            candidateScores.add(new TemplateCandidateScore(metadata.templateId(), score));
            if (!missingRequired && score > bestScore) {
                bestScore = score;
                bestTemplate = metadata.templateId();
            }
        }
        if (bestTemplate == null) {
            AutomationReporter.block("[block]", "template = no_match for " + frame.semanticOperationId);
            throw new IllegalStateException("No template metadata matched semantic operation " + frame.semanticOperationId);
        }
        candidateScores.stream()
                .sorted(Comparator.comparingInt(TemplateCandidateScore::score).reversed())
                .limit(5)
                .forEach(candidate -> AutomationReporter.info("[cand]", "template." + candidate.templateId() + " = score " + candidate.score()));
        AutomationReporter.pipeline("[pick]", bestTemplate);
        return bestTemplate;
    }

    private DirectTemplateInvocation parseDirectTemplate(String input) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(input);
        while (matcher.find()) {
            tokens.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        if (tokens.isEmpty()) {
            throw new IllegalStateException("Missing template id");
        }
        String templateId = tokens.get(0).replace(".ktl", "");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int separator = token.indexOf('=');
            if (separator > 0) {
                String key = token.substring(0, separator);
                String value = token.substring(separator + 1);
                params.put(key, parseScalar(value));
            }
        }
        return new DirectTemplateInvocation(templateId, params);
    }

    private CompiledTaskTemplate requireTemplate(String templateId) {
        CompiledTaskTemplate template = this.assets.templates.get(templateId);
        if (template == null) {
            throw new IllegalStateException("Unknown template: " + templateId);
        }
        return template;
    }

    private ConditionDefinition findCondition(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ConditionDefinition> entry : this.assets.conditionAliases.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        ConditionDefinition builtin = builtinConditionByText(lower);
        if (builtin != null) {
            return builtin;
        }
        return null;
    }

    private static ConditionDefinition builtinConditionByText(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("automation task running")
                || lower.contains("task running")
                || lower.contains("currently running a task")
                || lower.contains("running task")
                || lower.contains("automation running")
                || lower.contains("automation is running")) {
            return builtinConditionById("cond.automation_task_running");
        }
        return null;
    }

    private static ConditionDefinition builtinConditionById(String id) {
        if ("cond.automation_task_running".equals(id) || "eval.automation_task_running".equals(id)) {
            return new ConditionDefinition("cond.automation_task_running", "eval.automation_task_running", "", "", "", List.of("automation task running", "task running", "currently running a task", "running task", "automation running"));
        }
        return null;
    }

    private ResolvedTarget resolveItem(String rawText) {
        String normalized = normalizeTarget(rawText);
        List<Identifier> matches = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(item);
            if (id == null) {
                continue;
            }
            String path = normalizeTarget(id.getPath());
            if (path.equals(normalized) || path.equals(stripPlural(normalized)) || stripPlural(path).equals(stripPlural(normalized))) {
                matches.add(id);
            }
        }
        if (matches.size() > 1) {
            String candidates = matches.stream().limit(5).map(Identifier::toString).reduce((left, right) -> left + ", " + right).orElse("");
            for (int index = 0; index < Math.min(5, matches.size()); index++) {
                AutomationReporter.info("[cand]", "item[" + index + "] = " + matches.get(index));
            }
            AutomationReporter.block("[block]", "resolver.item = ambiguous '" + rawText + "' -> " + candidates);
            throw new IllegalStateException("Item ambiguous: " + rawText + " -> " + candidates);
        }
        if (matches.isEmpty()) {
            AutomationReporter.block("[block]", "resolver.item = unresolved '" + rawText + "'");
            throw new IllegalStateException("Item unresolved: " + rawText);
        }
        Identifier id = matches.get(0);
        return new ResolvedTarget(id.toString(), id.getPath());
    }

    private Map<String, Object> resolveHeldItemReference(Frame frame) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            AutomationReporter.block("[block]", "reference.held_item = no_player");
            throw new IllegalStateException("Held item reference unavailable");
        }
        var stack = client.player.getMainHandStack();
        if (stack == null || stack.isEmpty()) {
            AutomationReporter.block("[block]", "reference.held_item = empty_hand");
            throw new IllegalStateException("Held item reference unresolved");
        }
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id == null) {
            AutomationReporter.block("[block]", "reference.held_item = unresolved_item_id");
            throw new IllegalStateException("Held item id unresolved");
        }
        if ("sem.task.consume_item".equals(frame.semanticOperationId) && !stack.isFood()) {
            frame.semanticOperationId = "sem.task.use_item";
            AutomationReporter.pipeline("[res ]", "semantic_operation.id = sem.task.use_item");
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("target.kind", "item");
        resolved.put("target.id", id.toString());
        resolved.put("item.id", id.toString());
        resolved.put("target.name", id.getPath());
        SessionMemory.put("it", Map.of("target.kind", "item", "target.id", id.toString(), "item.id", id.toString()));
        SessionMemory.put("them", Map.of("target.kind", "item", "target.id", id.toString(), "item.id", id.toString()));
        return resolved;
    }

    private ResolvedTarget resolveEntity(String rawText) {
        String normalized = normalizeTarget(rawText);
        List<Identifier> matches = new ArrayList<>();
        for (var type : Registries.ENTITY_TYPE) {
            Identifier id = Registries.ENTITY_TYPE.getId(type);
            if (id == null) {
                continue;
            }
            String path = normalizeTarget(id.getPath());
            if (path.equals(normalized) || stripPlural(path).equals(stripPlural(normalized))) {
                matches.add(id);
            }
        }
        if (matches.size() > 1) {
            String candidates = matches.stream().limit(5).map(Identifier::toString).reduce((left, right) -> left + ", " + right).orElse("");
            for (int index = 0; index < Math.min(5, matches.size()); index++) {
                AutomationReporter.info("[cand]", "entity[" + index + "] = " + matches.get(index));
            }
            AutomationReporter.block("[block]", "resolver.entity = ambiguous '" + rawText + "' -> " + candidates);
            throw new IllegalStateException("Entity ambiguous: " + rawText + " -> " + candidates);
        }
        if (matches.isEmpty()) {
            AutomationReporter.block("[block]", "resolver.entity = unresolved '" + rawText + "'");
            throw new IllegalStateException("Entity unresolved: " + rawText);
        }
        Identifier id = matches.get(0);
        return new ResolvedTarget(id.toString(), id.getPath());
    }

    private ResolvedTarget resolveBlock(String rawText) {
        String normalized = normalizeTarget(rawText);
        List<Identifier> matches = new ArrayList<>();
        for (var block : Registries.BLOCK) {
            Identifier id = Registries.BLOCK.getId(block);
            if (id == null) {
                continue;
            }
            String path = normalizeTarget(id.getPath());
            if (path.equals(normalized) || path.equals(stripPlural(normalized)) || stripPlural(path).equals(stripPlural(normalized))) {
                matches.add(id);
            }
        }
        if (matches.size() > 1) {
            String candidates = matches.stream().limit(5).map(Identifier::toString).reduce((left, right) -> left + ", " + right).orElse("");
            for (int index = 0; index < Math.min(5, matches.size()); index++) {
                AutomationReporter.info("[cand]", "block[" + index + "] = " + matches.get(index));
            }
            AutomationReporter.block("[block]", "resolver.block = ambiguous '" + rawText + "' -> " + candidates);
            throw new IllegalStateException("Block ambiguous: " + rawText + " -> " + candidates);
        }
        if (matches.isEmpty()) {
            AutomationReporter.block("[block]", "resolver.block = unresolved '" + rawText + "'");
            throw new IllegalStateException("Block unresolved: " + rawText);
        }
        Identifier id = matches.get(0);
        return new ResolvedTarget(id.toString(), id.getPath());
    }

    private NormalizedDocument normalize(Path path, String source) {
        Object parsed = this.yaml.load(source);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("Invalid .ktl document: " + path);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> body.put(String.valueOf(key), value));
        String version = string(body.remove("version"));
        String kind = string(body.remove("kind"));
        String id = string(body.remove("id"));
        if (version.isBlank() || kind.isBlank() || id.isBlank()) {
            throw new IllegalStateException("Missing version/kind/id in " + path);
        }
        List<String> dependencies = stringList(body.get("depends_on"));
        return new NormalizedDocument(path.toString(), version, kind, id, body, dependencies);
    }

    private static List<Map<String, Object>> list(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object entry : list) {
                result.add(map(entry));
            }
            return result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, entry) -> result.put(String.valueOf(key), entry));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object entry : list) {
                result.add(String.valueOf(entry));
            }
            return result;
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string);
        }
        return List.of();
    }

    private static void requireField(Map<String, Object> map, String field, String path, List<String> errors) {
        if (string(map.get(field)).isBlank()) {
            errors.add(path + " missing " + field);
        }
    }

    private static void requireStringList(Object value, String path, List<String> errors) {
        if (stringList(value).isEmpty()) {
            errors.add(path + " must be a non-empty list");
        }
    }

    private static void requireNonEmpty(List<?> values, String path, List<String> errors) {
        if (values == null || values.isEmpty()) {
            errors.add(path + " must be non-empty");
        }
    }

    private static void rememberUnique(Map<String, String> seen, String value, String path, List<String> errors) {
        if (value.isBlank()) {
            return;
        }
        String previous = seen.putIfAbsent(value, path);
        if (previous != null) {
            errors.add(path + " duplicates " + value + " already declared at " + previous);
        }
    }

    private static void rememberPhrase(Map<String, String> seen, String value, String path, List<String> errors) {
        String phrase = normalizePhrase(value);
        if (phrase.isBlank()) {
            return;
        }
        String previous = seen.putIfAbsent(phrase, path);
        if (previous != null) {
            errors.add(path + " duplicates phrase '" + phrase + "' already declared at " + previous);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalizePhrase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static Object parseScalar(String value) {
        if (value.matches("-?\\d+")) {
            return Integer.parseInt(value);
        }
        return value;
    }

    private static Double tryParseDouble(String value) {
        value = cleanCoordinateToken(value);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer tryParseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "one", "a", "an" -> 1;
                case "two" -> 2;
                case "three" -> 3;
                case "four" -> 4;
                case "five" -> 5;
                case "six" -> 6;
                case "seven" -> 7;
                case "eight" -> 8;
                case "nine" -> 9;
                case "ten" -> 10;
                case "eleven" -> 11;
                case "twelve" -> 12;
                default -> null;
            };
        }
    }

    private static CoordinateParse parseCoordinateTokens(String[] tokens, int start) {
        Map<String, Double> labeled = new LinkedHashMap<>();
        List<Double> numeric = new ArrayList<>();
        for (int i = start; i < tokens.length; i++) {
            String token = cleanCoordinateToken(tokens[i]).toLowerCase(Locale.ROOT);
            if (("x".equals(token) || "y".equals(token) || "z".equals(token)) && i + 1 < tokens.length) {
                Double value = tryParseDouble(tokens[i + 1]);
                if (value == null) {
                    return null;
                }
                labeled.put(token, value);
                i++;
                continue;
            }
            Double value = tryParseDouble(tokens[i]);
            if (value == null) {
                return null;
            }
            numeric.add(value);
        }
        if (!labeled.isEmpty()) {
            Double x = labeled.get("x");
            Double z = labeled.get("z");
            if (x == null || z == null) {
                return null;
            }
            return new CoordinateParse(x, labeled.get("y"), z);
        }
        if (numeric.size() == 2) {
            return new CoordinateParse(numeric.get(0), null, numeric.get(1));
        }
        if (numeric.size() == 3) {
            return new CoordinateParse(numeric.get(0), numeric.get(1), numeric.get(2));
        }
        return null;
    }

    private static CoordinateParse parseCoordinateTargetText(String rawText) {
        String trimmed = rawText == null ? "" : rawText.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String normalized = trimmed.replace(',', ' ');
        String[] tokens = normalized.split("\\s+");
        int start = tokens.length > 0 && "to".equalsIgnoreCase(cleanCoordinateToken(tokens[0])) ? 1 : 0;
        if (tokens.length - start < 2) {
            return null;
        }
        return parseCoordinateTokens(tokens, start);
    }

    private static String cleanCoordinateToken(String token) {
        if (token == null) {
            return "";
        }
        return token.trim()
                .replace(",", "")
                .replace("(", "")
                .replace(")", "")
                .replace("[", "")
                .replace("]", "");
    }

    private static String joinTokens(String[] tokens, int startIndex) {
        return String.join(" ", Arrays.copyOfRange(tokens, startIndex, tokens.length));
    }

    private static int indexOfToken(String[] tokens, int startIndex, String token) {
        for (int index = startIndex; index < tokens.length; index++) {
            if (token.equalsIgnoreCase(tokens[index])) {
                return index;
            }
        }
        return -1;
    }

    private static int findConnector(String input, List<String> connectors) {
        String lower = input.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < lower.length(); index++) {
            char current = lower.charAt(index);
            if (current == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (current == '(') {
                depth++;
                continue;
            }
            if (current == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0 || !isWordBoundary(lower, index - 1)) {
                continue;
            }
            for (String connector : connectors) {
                if (lower.startsWith(connector, index) && isWordBoundary(lower, index + connector.length())) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static String connectorAt(String input, int index, List<String> connectors) {
        String lower = input.toLowerCase(Locale.ROOT);
        for (String connector : connectors) {
            if (lower.startsWith(connector, index)) {
                return input.substring(index, index + connector.length());
            }
        }
        return "";
    }

    private static boolean isWordBoundary(String value, int index) {
        if (index < 0 || index >= value.length()) {
            return true;
        }
        char character = value.charAt(index);
        return !Character.isLetterOrDigit(character) && character != '_';
    }

    private ActionMatch findLeadingAction(String[] tokens) {
        ActionMatch best = null;
        for (LexiconEntry entry : this.assets.lexicon.values()) {
            if (!"action".equals(entry.type())) {
                continue;
            }
            String[] phraseTokens = entry.phrase().toLowerCase(Locale.ROOT).split("\\s+");
            if (phraseTokens.length > tokens.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < phraseTokens.length; i++) {
                if (!phraseTokens[i].equals(tokens[i].toLowerCase(Locale.ROOT))) {
                    matches = false;
                    break;
                }
            }
            if (matches && (best == null || phraseTokens.length > best.tokenCount)) {
                best = new ActionMatch(entry, phraseTokens.length);
            }
        }
        return best;
    }

    private Frame applyLeadingActionDefaults(String input, Frame frame) {
        if ("sequence_then".equals(frame.shape) || "sequence_and_then".equals(frame.shape) || "if_condition_then_else".equals(frame.shape) || "if_condition_then".equals(frame.shape)) {
            return frame;
        }
        ActionMatch action = findLeadingAction(input.split("\\s+"));
        if (action != null) {
            for (Map.Entry<String, Object> entry : action.entry.defaults().entrySet()) {
                if (frame.countValue != null && "count.value".equals(entry.getKey())) {
                    continue;
                }
                frame.additionalParams.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return frame;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(input);
        while (matcher.find()) {
            tokens.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return tokens;
    }

    private void reportLexiconMatches(List<String> tokens) {
        for (String token : tokens) {
            LexiconEntry entry = this.assets.lexicon.get(token.toLowerCase(Locale.ROOT));
            if (entry != null) {
                AutomationReporter.pipeline("[lex ]", token + " -> " + entry.mapsTo());
                continue;
            }
            String selector = this.assets.selectors.get(token.toLowerCase(Locale.ROOT));
            if (selector != null) {
                AutomationReporter.pipeline("[sel ]", token + " -> " + selector);
            }
        }
    }

    private static List<String> describeTokens(List<String> tokens) {
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (token.matches("-?\\d+")) {
                result.add("integer");
            } else if (token.startsWith("/")) {
                result.add("slash_command");
            } else {
                result.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static String normalizeTarget(String rawText) {
        String lower = rawText.toLowerCase(Locale.ROOT).trim().replace("minecraft:", "");
        return lower.replace("_", " ").replace('-', ' ').trim();
    }

    private static String stripPlural(String text) {
        return text.endsWith("s") ? text.substring(0, text.length() - 1) : text;
    }

    private static String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String fingerprint(List<Path> paths) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Path path : paths) {
            builder.append(path.toAbsolutePath()).append('|');
            builder.append(Files.getLastModifiedTime(path).toMillis()).append('|');
            builder.append(Files.size(path)).append('\n');
        }
        return sha256(builder.toString());
    }

    private static void ensureDirectories() {
        try {
            Files.createDirectories(ROOT);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record CompileSummary(int fileCount, int cacheHits, int cacheMisses) {
    }

    private record CompletionCommandParse(String input, String successCommand, String failureCommand) {
    }

    private record HookMatch(String kind, int hookStart, int commandStart) {
    }

    public static final class CompiledAssets {
        public final Map<String, LexiconEntry> lexicon = new LinkedHashMap<>();
        public final List<GrammarPattern> grammarPatterns = new ArrayList<>();
        public final Map<String, ConditionDefinition> conditions = new LinkedHashMap<>();
        public final Map<String, ConditionDefinition> conditionAliases = new LinkedHashMap<>();
        public final Map<String, SemanticOperationDefinition> semanticOperations = new LinkedHashMap<>();
        public final Map<String, String> preferredKinds = new LinkedHashMap<>();
        public final Map<String, String> preferredTemplates = new LinkedHashMap<>();
        public final Map<String, Map<String, Object>> resolverDefaults = new LinkedHashMap<>();
        public final Map<String, String> referencePatterns = new LinkedHashMap<>();
        public final Map<String, String> selectors = new LinkedHashMap<>();
        public final Map<String, CompiledTemplateMetadata> templateMetadata = new LinkedHashMap<>();
        public final Map<String, CompiledTaskTemplate> templates = new LinkedHashMap<>();
        public final Map<String, Map<String, Object>> presets = new LinkedHashMap<>();
        public final Map<String, Map<String, Object>> macros = new LinkedHashMap<>();
        public final Map<String, Map<String, Object>> aliasPacks = new LinkedHashMap<>();
        public final Map<String, Map<String, Object>> compileProfiles = new LinkedHashMap<>();
        public final Map<String, Map<String, Object>> namespacePacks = new LinkedHashMap<>();
    }

    private record ActionMatch(LexiconEntry entry, int tokenCount) {
    }

    public record NormalizedDocument(String sourcePath, String version, String kind, String id, Map<String, Object> body, List<String> dependencies) {
        public NormalizedDocument {
            body = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    public record LexiconEntry(String id, String type, String phrase, String mapsTo, Map<String, Object> defaults) {
    }

    public record GrammarPattern(String id, String shape, String semanticOperation, String conditionSlot, String targetSlot, String countSlot, String selectorSlot) {
    }

    public record ConditionDefinition(String id, String evaluatorKey, String stat, String operator, String mode, List<String> aliases) {
    }

    public record SemanticOperationDefinition(String id, String intent, List<String> targetKinds, List<String> preferredTemplates) {
    }

    public record CompiledTemplateMetadata(String templateId, List<String> semanticOperations, List<String> targetKinds, List<String> requiredParams, List<String> optionalParams, List<String> tags) {
    }

    public record CompiledTaskTemplate(String templateId, String semanticOperationId, List<String> params, List<CompiledStep> steps) {
    }

    public record CompiledStep(String type, String action, String delegate, String condition, String thenInput, String elseInput, String label, String gotoLabel, Map<String, Object> params, Map<String, Object> writes, String scope, String sourcePath, int sourceLine) {
    }

    public record DirectTemplateInvocation(String templateId, Map<String, Object> params) {
    }

    public record ResolvedTarget(String id, String name) {
    }

    private record CoordinateParse(double x, Double y, double z) {
    }

    private record TemplateCandidateScore(String templateId, int score) {
    }

    private static final class Frame {
        private String shape;
        private String semanticOperationId;
        private String targetRawText;
        private Integer countValue;
        private String selectorId;
        private String conditionId;
        private Integer conditionThreshold;
        private final Map<String, Object> additionalParams = new LinkedHashMap<>();
    }

    public static final class SessionMemory {
        private static final Map<String, Object> MEMORY = new LinkedHashMap<>();

        private SessionMemory() {
        }

        public static void put(String key, Object value) {
            MEMORY.put(key.toLowerCase(Locale.ROOT), value);
        }

        public static Object get(String key) {
            return MEMORY.get(key.toLowerCase(Locale.ROOT));
        }
    }
}
