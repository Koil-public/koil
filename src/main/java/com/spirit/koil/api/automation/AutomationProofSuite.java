package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.ktl.KtlCompilerService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class AutomationProofSuite {
    private static final Path PROOF_DIR = Path.of("koil/automation/validation");
    private static final Path CACHE_PROOF_FILE = PROOF_DIR.resolve("proof_cache_probe.ktl");

    private AutomationProofSuite() {
    }

    public static boolean runAll() {
        AutomationReporter.run("[run ]", "proof.suite = start");
        KtlCompilerService.getInstance().reload();
        boolean passed = true;
        passed &= proveInterpret("phrase.move.xyz", new AutomationRequest("go to 10 64 -20", false, false), "sem.task.move_to_target", "movement/navigation/move_to_position", Map.of(
                "target.kind", "location",
                "target.x", 10.0,
                "target.y", 64.0,
                "target.z", -20.0
        ));
        passed &= proveInterpret("phrase.move.xz", new AutomationRequest("go to 10 -20", false, false), "sem.task.move_to_target", "movement/navigation/move_to_position", Map.of(
                "target.kind", "location",
                "target.x", 10.0,
                "target.z", -20.0
        ));
        passed &= proveInterpret("phrase.walk.to.xyz", new AutomationRequest("walk to 10 64 -20", false, false), "sem.task.move_to_target", "movement/navigation/move_to_position", Map.of(
                "target.kind", "location",
                "target.x", 10.0,
                "target.y", 64.0,
                "target.z", -20.0
        ));
        passed &= proveInterpret("phrase.walk.to.xz", new AutomationRequest("walk to 10 -20", false, false), "sem.task.move_to_target", "movement/navigation/move_to_position", Map.of(
                "target.kind", "location",
                "target.x", 10.0,
                "target.z", -20.0
        ));
        passed &= proveInterpret("phrase.walk.to.live.negative.xyz", new AutomationRequest("walk to 850 -60 -830", false, false), "sem.task.move_to_target", "movement/navigation/move_to_position", Map.of(
                "target.kind", "location",
                "target.x", 850.0,
                "target.y", -60.0,
                "target.z", -830.0
        ));
        passed &= proveInterpret("phrase.walk.bare.negative.xz", new AutomationRequest("walk 850 -830", false, false), "sem.task.move_to_target", "movement/navigation/move_to_position", Map.of(
                "target.kind", "location",
                "target.x", 850.0,
                "target.z", -830.0
        ));
        passed &= proveInterpret("phrase.walk.forward", new AutomationRequest("walk forward 5 blocks", false, false), "sem.task.move_relative", "movement/navigation/move_relative", Map.of(
                "direction.id", "forward",
                "count.value", 5
        ));
        passed &= proveInterpret("phrase.walk.amount.defaults.forward", new AutomationRequest("walk 5 blocks", false, false), "sem.task.move_relative", "movement/navigation/move_relative", Map.of(
                "direction.id", "forward",
                "count.value", 5
        ));
        passed &= proveInterpret("phrase.walk.left", new AutomationRequest("walk left", false, false), "sem.task.move_relative", "movement/navigation/move_relative", Map.of(
                "direction.id", "left"
        ));
        passed &= proveInterpret("phrase.stop", new AutomationRequest("stop", false, false), "sem.task.stop_movement", "movement/core/movement_stop", Map.of());
        passed &= proveInterpret("phrase.stop.success.command", new AutomationRequest("stop on success /say done", false, false), "sem.task.stop_movement", "movement/core/movement_stop", Map.of(
                "completion.success.command", "say done"
        ));
        passed &= proveInterpret("phrase.stop.failure.command", new AutomationRequest("stop on fail /kill @s", false, false), "sem.task.stop_movement", "movement/core/movement_stop", Map.of(
                "completion.failure.command", "kill @s"
        ));
        passed &= proveInterpret("phrase.stop.both.commands", new AutomationRequest("stop on success /say done on failure /kill @s", false, false), "sem.task.stop_movement", "movement/core/movement_stop", Map.of(
                "completion.success.command", "say done",
                "completion.failure.command", "kill @s"
        ));
        passed &= proveInterpret("phrase.jump", new AutomationRequest("jump", false, false), "sem.task.jump_once", "movement/core/movement_jump_once", Map.of());
        passed &= proveInterpret("phrase.open.chest", new AutomationRequest("open nearest chest", false, false), "sem.task.open_target", "movement/interact/move_to_open_container", Map.of(
                "target.id", "minecraft:chest"
        ));
        passed &= proveInterpret("phrase.follow.zombie", new AutomationRequest("follow nearest zombie", false, false), "sem.task.follow_target", "movement/follow/follow_entity", Map.of(
                "target.id", "minecraft:zombie",
                "target.selector", "nearest"
        ));
        passed &= proveInterpret("phrase.attack.zombie", new AutomationRequest("attack nearest zombie", false, false), "sem.task.attack_target", "movement/interact/move_to_attack_entity", Map.of(
                "target.id", "minecraft:zombie",
                "target.selector", "nearest"
        ));
        passed &= proveInterpret("phrase.kill.zombie", new AutomationRequest("kill nearest zombie", false, false), "sem.task.kill_target", "combat/core/kill_entity_until_count", Map.of(
                "target.id", "minecraft:zombie",
                "target.selector", "nearest",
                "count.value", 1
        ));
        passed &= proveInterpret("phrase.break.oak.log", new AutomationRequest("break oak log", false, false), "sem.task.break_target", "movement/interact/move_to_break_block", Map.of(
                "target.id", "minecraft:oak_log"
        ));
        passed &= proveInterpret("phrase.mine.iron.ore", new AutomationRequest("mine 3 iron ore", false, false), "sem.task.mine_block_count", "blocks/core/mine_block_until_count", Map.of(
                "target.id", "minecraft:iron_ore",
                "count.value", 3
        ));
        passed &= proveInterpret("phrase.eat.apples", new AutomationRequest("eat 2 apples", false, false), "sem.task.consume_item", "survival/core/eat_item_until_count", Map.of(
                "item.id", "minecraft:apple",
                "count.value", 2
        ));
        passed &= proveInterpret("phrase.take.sugar.cane.chest", new AutomationRequest("take sugar cane from nearest chest", false, false), "sem.task.take_item_from_container", "container/core/take_item_from_container", Map.of(
                "item.id", "minecraft:sugar_cane",
                "target.id", "minecraft:chest",
                "target.selector", "nearest"
        ));
        passed &= proveInterpret("phrase.sequence", new AutomationRequest("jump then stop", false, false), "sem.task.sequence", "movement/core/sequence_prompt", Map.of());
        passed &= proveInterpret("direct.run.template", new AutomationRequest("movement/navigation/move_relative.ktl direction.id=forward count.value=3 unit.id=blocks", true, true), "sem.task.move_relative", "movement/navigation/move_relative", Map.of(
                "direction.id", "forward",
                "count.value", 3
        ));
        passed &= proveCacheRoundTrip();
        passed &= proveFeedbackRegistryFlow();
        AutomationReporter.done("[done]", "proof.suite = " + (passed ? "success" : "failed"));
        return passed;
    }

    public static boolean runCacheOnly() {
        AutomationReporter.run("[run ]", "proof.cache = start");
        boolean passed = proveCacheRoundTrip();
        AutomationReporter.done("[done]", "proof.cache = " + (passed ? "success" : "failed"));
        return passed;
    }

    private static AutomationRequest withReferenceMemory(String input) {
        Map<String, Object> remembered = new LinkedHashMap<>();
        remembered.put("target.kind", "item");
        remembered.put("target.id", "minecraft:apple");
        remembered.put("item.id", "minecraft:apple");
        KtlCompilerService.SessionMemory.put("them", remembered);
        return new AutomationRequest(input, false, false);
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
                    version: 1
                    kind: lexicon
                    id: lexicon.proof_cache_probe
                    entries:
                      - id: lex.proof_cache_probe
                        type: action
                        phrases: [proof cache probe]
                        maps_to: sem.task.wait_duration
                    """;
            String sourceTwo = """
                    version: 1
                    kind: lexicon
                    id: lexicon.proof_cache_probe
                    entries:
                      - id: lex.proof_cache_probe
                        type: action
                        phrases: [proof cache probe updated]
                        maps_to: sem.task.wait_duration
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
