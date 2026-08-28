package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.automation.ktl.KtlBuiltinLibraryInstaller;
import com.spirit.koil.api.automation.ktl.KtlDevelopmentLibrarySynchronizer;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.automation.feedback.AutomationFailureRegistry;
import com.spirit.koil.api.automation.feedback.AutomationFailureType;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackNode;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackService;
import com.spirit.koil.api.automation.runtime.ExecutionPlan;
import com.spirit.koil.api.automation.runtime.InterpretationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class AutomationProofSuite {
    private static final Path PROOF_DIR = Path.of("koil/automation/validation");
    private static final Path CACHE_PROOF_FILE = PROOF_DIR.resolve("proof_cache_probe.ktl");

    private AutomationProofSuite() {
    }

    public static void main(String[] args) {
        if (!runAll()) {
            throw new IllegalStateException("KTL automation proof suite failed");
        }
        System.out.println("KTL automation proof suite passed.");
    }

    public static boolean runAll() {
        AutomationReporter.run("[task]", "proof.suite = start");
        KtlCompilerService.getInstance().reload();
        boolean passed = true;
        passed &= proveClientCommandTree();
        passed &= proveInterpret("task.walk.forward", new AutomationRequest(
                "movement/navigation/move_relative.ktl direction.id=forward count.value=5 unit.id=blocks",
                true,
                true
        ), "sem.task.move_relative", "movement/navigation/move_relative", Map.of(
                "direction.id", "forward",
                "count.value", 5
        ));
        passed &= proveInterpret("task.jump", new AutomationRequest(
                "movement/core/movement_jump_once.ktl",
                true,
                true
        ), "sem.task.jump_once", "movement/core/movement_jump_once", Map.of());
        passed &= proveInterpret("task.follow.zombie", new AutomationRequest(
                "movement/follow/follow_entity.ktl target.id=minecraft:zombie target.selector=nearest",
                true,
                true
        ), "sem.task.follow_target", "movement/follow/follow_entity", Map.of(
                "target.id", "minecraft:zombie",
                "target.selector", "nearest"
        ));
        passed &= proveInterpret("task.input.hold", new AutomationRequest(
                "flow/core/hold_input.ktl input.key=forward count.value=4",
                true,
                true
        ), "sem.task.hold_input", "flow/core/hold_input", Map.of(
                "input.key", "forward",
                "count.value", 4
        ));
        passed &= proveInterpret("task.input.mouse", new AutomationRequest(
                "flow/core/mouse_delta.ktl yaw.delta=8 pitch.delta=-2",
                true,
                true
        ), "sem.task.mouse_input", "flow/core/mouse_delta", Map.of(
                "yaw.delta", 8,
                "pitch.delta", -2
        ));
        passed &= proveRejectedLanguagePrompt();
        passed &= proveRetiredPromptTemplate();
        passed &= proveV2LibraryContract();
        passed &= proveAdditiveBuiltinMigration();
        passed &= proveDevelopmentLibrarySync();
        passed &= proveComposedSkillFamilies();
        passed &= proveCacheRoundTrip();
        passed &= proveFeedbackRegistryFlow();
        AutomationReporter.done("[done]", "proof.suite = " + (passed ? "success" : "failed"));
        return passed;
    }

    private static boolean proveClientCommandTree() {
        try {
            var automate = AutomationRouter.automationModeCommand("automate").build();
            var feedback = automate.getChild("feedback");
            var proof = automate.getChild("proof");
            var deep = automate.getChild("deep");
            var plan = automate.getChild("plan");
            boolean valid = feedback == null
                    && !AutomationFeedbackService.userSurfaceEnabled()
                    && proof != null
                    && proof.getChild("all") != null
                    && proof.getChild("cache") != null
                    && deep != null
                    && deep.getChild("on") != null
                    && deep.getChild("off") != null
                    && deep.getChild("status") != null
                    && plan != null
                    && plan.getChild("on") != null
                    && plan.getChild("off") != null
                    && plan.getChild("status") != null;
            if (!valid) {
                AutomationReporter.fail("[fail]", "command.tree = disabled feedback or /automate proof contract is wrong");
                return false;
            }
            AutomationReporter.done("[done]", "command.tree = feedback disabled + /automate proof active");
            return true;
        } catch (RuntimeException exception) {
            AutomationReporter.fail("[fail]", "command.tree threw " + messageOf(exception));
            return false;
        }
    }

    private static boolean proveV2LibraryContract() {
        try {
            KtlCompilerService.CompiledAssets assets = KtlCompilerService.getInstance().assets();
            if (assets.templates.size() < 110) {
                AutomationReporter.fail("[fail]", "ktl.v2 = expected at least 110 task templates, found " + assets.templates.size());
                return false;
            }
            for (KtlCompilerService.CompiledTaskTemplate template : assets.templates.values()) {
                KtlCompilerService.CompiledTemplateMetadata metadata = assets.templateMetadata.get(template.templateId());
                if (metadata == null) {
                    AutomationReporter.fail("[fail]", "ktl.v2 = missing metadata for " + template.templateId());
                    return false;
                }
                if (metadata.timeoutTicks() <= 0 || metadata.visibility().isBlank()) {
                    AutomationReporter.fail("[fail]", "ktl.v2 = incomplete execution contract for " + template.templateId());
                    return false;
                }
                if (metadata.modelCallable() && !"public".equals(metadata.visibility())) {
                    AutomationReporter.fail("[fail]", "ktl.v2 = non-public model skill " + template.templateId());
                    return false;
                }
                if (!metadata.recoveryTask().isBlank()) {
                    String recovery = metadata.recoveryTask().endsWith(".ktl")
                            ? metadata.recoveryTask().substring(0, metadata.recoveryTask().length() - 4)
                            : metadata.recoveryTask();
                    if (!assets.templates.containsKey(recovery)) {
                        AutomationReporter.fail("[fail]", "ktl.v2 = missing recovery task " + recovery);
                        return false;
                    }
                }
            }
            AutomationReporter.done("[done]", "ktl.v2 = contracts " + assets.templates.size());
            return true;
        } catch (RuntimeException exception) {
            AutomationReporter.fail("[fail]", "ktl.v2 threw " + messageOf(exception));
            return false;
        }
    }

    private static boolean proveAdditiveBuiltinMigration() {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("koil-ktl-migration-proof-");
            Path legacyPack = temporary.resolve("flow/semantics/flow-operations.ktl");
            Files.createDirectories(legacyPack.getParent());
            String legacy = "legacy-user-edited-flow-pack";
            Files.writeString(legacyPack, legacy, StandardCharsets.UTF_8);
            int installed = KtlBuiltinLibraryInstaller.installMissing(temporary);
            Path additivePack = temporary.resolve("flow/semantics/input-operations.ktl");
            boolean passed = installed > 0
                    && Files.isRegularFile(additivePack)
                    && Files.readString(additivePack).contains("sem.task.hold_input")
                    && Files.readString(additivePack).contains("sem.task.mouse_input")
                    && legacy.equals(Files.readString(legacyPack));
            if (passed) {
                AutomationReporter.done("[done]", "ktl.migration = additive semantics preserved local pack");
            } else {
                AutomationReporter.fail("[fail]", "ktl.migration = missing additive pack or local overwrite");
            }
            return passed;
        } catch (Exception exception) {
            AutomationReporter.fail("[fail]", "ktl.migration threw " + messageOf(exception));
            return false;
        } finally {
            if (temporary != null) {
                try (Stream<Path> paths = Files.walk(temporary)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean proveDevelopmentLibrarySync() {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("koil-ktl-sync-proof-");
            Files.createDirectories(temporary.resolve(".git"));
            Files.writeString(temporary.resolve("build.gradle"), "// proof", StandardCharsets.UTF_8);
            Path sourceRoot = temporary.resolve("src/main/resources/koil/automation");
            Path runRoot = temporary.resolve("run/koil/automation");
            Path sourceTask = sourceRoot.resolve("flow/proof.ktl");
            Path runTask = runRoot.resolve("flow/proof.ktl");
            Files.createDirectories(sourceTask.getParent());
            Files.createDirectories(runTask.getParent());
            Files.writeString(sourceTask, "source-forward", StandardCharsets.UTF_8);
            Files.writeString(runTask, "legacy-run", StandardCharsets.UTF_8);

            KtlDevelopmentLibrarySynchronizer.sync(temporary);
            if (!"source-forward".equals(Files.readString(runTask))) return false;

            Files.writeString(runTask, "edited-in-run", StandardCharsets.UTF_8);
            KtlDevelopmentLibrarySynchronizer.sync(temporary);
            if (!"edited-in-run".equals(Files.readString(sourceTask))) return false;

            Files.writeString(sourceTask, "edited-in-source", StandardCharsets.UTF_8);
            KtlDevelopmentLibrarySynchronizer.sync(temporary);
            if (!"edited-in-source".equals(Files.readString(runTask))) return false;

            Path runOnly = runRoot.resolve("movement/new-capability.ktl");
            Files.createDirectories(runOnly.getParent());
            Files.writeString(runOnly, "new-task", StandardCharsets.UTF_8);
            KtlDevelopmentLibrarySynchronizer.sync(temporary);
            boolean newFileSynced = Files.readString(sourceRoot.resolve("movement/new-capability.ktl")).equals("new-task")
                    && Files.readString(sourceRoot.resolve("manifest.txt")).contains("movement/new-capability.ktl")
                    && Files.readString(sourceRoot.resolve("manifest.txt")).equals(Files.readString(runRoot.resolve("manifest.txt")));
            if (!newFileSynced) return false;

            Files.writeString(sourceTask, "source-conflict", StandardCharsets.UTF_8);
            Files.writeString(runTask, "run-conflict", StandardCharsets.UTF_8);
            boolean conflictRejected = false;
            try {
                KtlDevelopmentLibrarySynchronizer.sync(temporary);
            } catch (IllegalStateException expected) {
                conflictRejected = expected.getMessage() != null && expected.getMessage().contains("both sides");
            }
            boolean preserved = "source-conflict".equals(Files.readString(sourceTask))
                    && "run-conflict".equals(Files.readString(runTask));
            if (conflictRejected && preserved) {
                AutomationReporter.done("[done]", "ktl.development_sync = bidirectional and conflict safe");
                return true;
            }
            AutomationReporter.fail("[fail]", "ktl.development_sync = conflict was overwritten or accepted");
            return false;
        } catch (Exception exception) {
            AutomationReporter.fail("[fail]", "ktl.development_sync threw " + messageOf(exception));
            return false;
        } finally {
            if (temporary != null) {
                try (Stream<Path> paths = Files.walk(temporary)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean proveComposedSkillFamilies() {
        KtlCompilerService.CompiledAssets assets = KtlCompilerService.getInstance().assets();
        Map<String, String> requiredDelegates = Map.of(
                "farming/core/harvest_and_replant", "farming/core/harvest_replant_iteration",
                "interaction/core/use_item_on_block", "inventory/core/require_item",
                "goals/core/attack_target", "combat/core/attack_entity_until_dead",
                "inventory/core/drop_item", "inventory/core/require_item"
        );
        for (Map.Entry<String, String> requirement : requiredDelegates.entrySet()) {
            KtlCompilerService.CompiledTaskTemplate template = assets.templates.get(requirement.getKey());
            if (template == null) {
                AutomationReporter.fail("[fail]", "ktl.composition = missing " + requirement.getKey());
                return false;
            }
            boolean delegates = template.steps().stream()
                    .anyMatch(step -> "delegate".equals(step.type())
                            && step.delegate().startsWith(requirement.getValue())
                            || "branch".equals(step.type())
                            && (step.thenInput().startsWith(requirement.getValue())
                            || step.elseInput().startsWith(requirement.getValue())));
            if (!delegates) {
                AutomationReporter.fail("[fail]", "ktl.composition = " + requirement.getKey()
                        + " does not delegate " + requirement.getValue());
                return false;
            }
        }
        for (String internal : List.of(
                "movement/recovery/recover_stuck",
                "farming/core/harvest_replant_iteration",
                "inventory/core/require_item"
        )) {
            KtlCompilerService.CompiledTemplateMetadata metadata = assets.templateMetadata.get(internal);
            if (metadata == null || metadata.modelCallable() || "public".equals(metadata.visibility())) {
                AutomationReporter.fail("[fail]", "ktl.composition = internal task exposed " + internal);
                return false;
            }
        }
        AutomationReporter.done("[done]", "ktl.composition = composed families verified");
        return true;
    }

    private static boolean proveRejectedLanguagePrompt() {
        try {
            KtlCompilerService.getInstance().interpret(new AutomationRequest("walk 5 blocks then jump", false, false));
            AutomationReporter.fail("[fail]", "language.prompt = unexpectedly accepted");
            return false;
        } catch (IllegalArgumentException expected) {
            boolean passed = expected.getMessage() != null && expected.getMessage().contains("Automation Mode");
            AutomationReporter.done("[done]", "language.prompt = rejected by task-only compiler");
            return passed;
        }
    }

    private static boolean proveRetiredPromptTemplate() {
        try {
            KtlCompilerService.getInstance().interpret(new AutomationRequest(
                    "movement/core/sequence_prompt.ktl",
                    true,
                    true
            ));
            AutomationReporter.fail("[fail]", "language.sequence_template = unexpectedly available");
            return false;
        } catch (RuntimeException expected) {
            AutomationReporter.done("[done]", "language.sequence_template = retired");
            return true;
        }
    }

    public static boolean runCacheOnly() {
        AutomationReporter.run("[run ]", "proof.cache = start");
        boolean passed = proveCacheRoundTrip();
        AutomationReporter.done("[done]", "proof.cache = " + (passed ? "success" : "failed"));
        return passed;
    }

    private static boolean proveFeedbackRegistryFlow() {
        try {
            AutomationFailureRegistry.ensureDefaultRegistry();
            List<AutomationFailureType> movementTypes = AutomationFailureRegistry.failureTypesFor("movement");
            if (movementTypes.stream().noneMatch(type -> "movement.stuck".equals(type.id()))) {
                AutomationReporter.fail("[fail]", "feedback.registry = missing movement.stuck");
                return false;
            }
            AutomationCliViewModel.beginSession("proof feedback");
            AutomationCliViewModel.beginFrame("frame-proof-feedback", "", "movement/navigation/move_to_position", "sem.task.move_to_target");
            AutomationCliViewModel.enterNode("frame-proof-feedback", "move_to_target#0", "move_to_target", "run_primitive -> cap.path.move_to_target  source.file=proof_feedback.ktl  source.line=1");
            AutomationCliViewModel.primitiveCall("frame-proof-feedback", "move_to_target#0", "cap.path.move_to_target", Map.of("target.kind", "location", "target.x", 10));
            List<AutomationFeedbackNode> nodes = AutomationFeedbackService.executableNodes(AutomationCliViewModel.snapshot());
            AutomationFeedbackNode movementNode = nodes.stream().filter(node -> "movement".equals(node.nodeType())).findFirst().orElse(null);
            if (movementNode == null) {
                AutomationReporter.fail("[fail]", "feedback.nodes = no movement node");
                return false;
            }
            AutomationFailureType failureType = movementTypes.stream().filter(type -> "movement.stuck".equals(type.id())).findFirst().orElse(null);
            AutomationFeedbackService.submitBad(movementNode, failureType);
            boolean stored = Files.exists(AutomationFeedbackService.eventsPath()) && Files.readString(AutomationFeedbackService.eventsPath(), StandardCharsets.UTF_8).contains("movement.stuck");
            if (stored) {
                AutomationReporter.done("[done]", "feedback.registry_flow = success");
            } else {
                AutomationReporter.fail("[fail]", "feedback.registry_flow = event not stored");
            }
            return stored;
        } catch (Exception exception) {
            AutomationReporter.fail("[fail]", "feedback.registry_flow threw " + messageOf(exception));
            return false;
        }
    }

    private static boolean proveInterpret(String name, AutomationRequest request, String expectedSemantic, String expectedTemplate, Map<String, Object> expectedParams) {
        try {
            InterpretationResult result = KtlCompilerService.getInstance().interpret(request);
            boolean passed = true;
            if (!expectedSemantic.equals(result.semanticOperationId())) {
                AutomationReporter.fail("[fail]", name + " semantic = " + result.semanticOperationId() + " expected " + expectedSemantic);
                passed = false;
            }
            if (!expectedTemplate.equals(result.selectedTemplateId())) {
                AutomationReporter.fail("[fail]", name + " template = " + result.selectedTemplateId() + " expected " + expectedTemplate);
                passed = false;
            }
            for (Map.Entry<String, Object> entry : expectedParams.entrySet()) {
                Object actual = result.boundParams().get(entry.getKey());
                if (!String.valueOf(entry.getValue()).equals(String.valueOf(actual))) {
                    AutomationReporter.fail("[fail]", name + " param." + entry.getKey() + " = " + actual + " expected " + entry.getValue());
                    passed = false;
                }
            }
            if (!planContainsExecutableStep(result.plan())) {
                AutomationReporter.fail("[fail]", name + " plan = no executable step");
                passed = false;
            }
            if ("movement/navigation/move_relative".equals(expectedTemplate) && !planStartsWithAction(result.plan(), "cap.path.move_relative_verified")) {
                AutomationReporter.fail("[fail]", name + " first_action = " + firstAction(result.plan()) + " expected cap.path.move_relative_verified");
                passed = false;
            }
            if (passed) {
                AutomationReporter.done("[done]", name + " = " + result.selectedTemplateId());
            }
            return passed;
        } catch (Exception exception) {
            AutomationReporter.fail("[fail]", name + " threw " + messageOf(exception));
            return false;
        }
    }

    private static boolean proveAmbiguous(String name, AutomationRequest request, String expectedFragment) {
        try {
            KtlCompilerService.getInstance().interpret(request);
            AutomationReporter.fail("[fail]", name + " = expected ambiguity");
            return false;
        } catch (Exception exception) {
            String message = messageOf(exception).toLowerCase();
            boolean passed = message.contains(expectedFragment.toLowerCase());
            if (passed) {
                AutomationReporter.done("[done]", name + " = " + messageOf(exception));
            } else {
                AutomationReporter.fail("[fail]", name + " = " + messageOf(exception));
            }
            return passed;
        }
    }

    private static boolean proveCacheRoundTrip() {
        try {
            Files.createDirectories(PROOF_DIR);
            String sourceOne = """
                    version: 2
                    kind: task_template
                    id: template.proof_cache_probe
                    template_id: validation/proof_cache_probe
                    semantic_operation: sem.task.jump_once
                    params: []
                    metadata:
                      semantic_operations: [sem.task.jump_once]
                      target_kinds: []
                      required_params: []
                      optional_params: []
                      tags: [validation]
                      description: Cache proof
                      visibility: internal
                      model_callable: false
                      resource_locks: []
                      side_effects: []
                      timeout_ticks: 100
                      failure_policy: fail
                    steps:
                      - type: return
                        label: proof_one
                    """;
            String sourceTwo = """
                    version: 2
                    kind: task_template
                    id: template.proof_cache_probe
                    template_id: validation/proof_cache_probe
                    semantic_operation: sem.task.jump_once
                    params: []
                    metadata:
                      semantic_operations: [sem.task.jump_once]
                      target_kinds: []
                      required_params: []
                      optional_params: []
                      tags: [validation, updated]
                      description: Updated cache proof
                      visibility: internal
                      model_callable: false
                      resource_locks: []
                      side_effects: []
                      timeout_ticks: 100
                      failure_policy: fail
                    steps:
                      - type: return
                        label: proof_two
                    """;

            Files.writeString(CACHE_PROOF_FILE, sourceOne, StandardCharsets.UTF_8);
            KtlCompilerService.getInstance().reload();
            KtlCompilerService.CompileSummary first = KtlCompilerService.getInstance().lastSummary();

            KtlCompilerService.getInstance().reload();
            KtlCompilerService.CompileSummary second = KtlCompilerService.getInstance().lastSummary();

            Files.writeString(CACHE_PROOF_FILE, sourceTwo, StandardCharsets.UTF_8);
            KtlCompilerService.getInstance().reload();
            KtlCompilerService.CompileSummary third = KtlCompilerService.getInstance().lastSummary();

            boolean passed = first.cacheMisses() > 0 && second.cacheHits() > 0 && third.cacheMisses() > 0;
            if (passed) {
                AutomationReporter.done("[done]", "cache.round_trip = hit " + second.cacheHits() + " / rebuild " + third.cacheMisses());
            } else {
                AutomationReporter.fail("[fail]", "cache.round_trip = first(miss=" + first.cacheMisses() + ") second(hit=" + second.cacheHits() + ") third(miss=" + third.cacheMisses() + ")");
            }
            return passed;
        } catch (Exception exception) {
            AutomationReporter.fail("[fail]", "cache.round_trip threw " + messageOf(exception));
            return false;
        } finally {
            cleanupProofFile();
        }
    }

    private static boolean planContainsExecutableStep(ExecutionPlan plan) {
        return plan != null
                && plan.template() != null
                && plan.template().steps() != null
                && plan.template().steps().stream().anyMatch(step -> "run_primitive".equals(step.type()) || "delegate".equals(step.type()) || "branch".equals(step.type()) || "goto".equals(step.type()));
    }

    private static boolean planStartsWithAction(ExecutionPlan plan, String action) {
        return action.equals(firstAction(plan));
    }

    private static String firstAction(ExecutionPlan plan) {
        if (plan == null || plan.template() == null || plan.template().steps() == null) {
            return "(none)";
        }
        return plan.template().steps().stream()
                .filter(step -> "run_primitive".equals(step.type()))
                .map(KtlCompilerService.CompiledStep::action)
                .findFirst()
                .orElse("(none)");
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static void cleanupProofFile() {
        try {
            Files.deleteIfExists(CACHE_PROOF_FILE);
            if (Files.exists(PROOF_DIR)) {
                try (Stream<Path> remaining = Files.list(PROOF_DIR)) {
                    if (remaining.findAny().isEmpty()) {
                        Files.deleteIfExists(PROOF_DIR);
                    }
                }
            }
            KtlCompilerService.getInstance().reload();
        } catch (IOException ignored) {
        }
    }
}
