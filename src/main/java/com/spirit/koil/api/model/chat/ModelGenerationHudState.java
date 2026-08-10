package com.spirit.koil.api.model.chat;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelExecutionEvent;
import com.spirit.koil.api.model.ModelFinalizationHandle;
import com.spirit.koil.api.model.ModelDeepThoughtControl;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.KoilLifetimeCounters;
import com.spirit.koil.api.model.presence.ModelPresenceState;
import com.spirit.koil.api.model.voice.ModelVoiceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class ModelGenerationHudState {
    private static final int MAXIMUM_TRACKED_REQUESTS = 8;
    private static final long COMPLETED_VISIBILITY_MILLIS = 8_000L;
    private static final Map<UUID, MutableRequest> REQUESTS = new LinkedHashMap<>();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();
    private static UUID selectedRequestId;
    private static Snapshot retainedMetricsSnapshot;

    private ModelGenerationHudState() {
    }

    public static synchronized void begin(UUID requestId, String prompt) {
        begin(requestId, prompt, false);
    }

    public static synchronized void begin(UUID requestId, String prompt, boolean automationRequest) {
        retainedMetricsSnapshot = null;
        KoilLifetimeCounters.Snapshot counters = KoilLifetimeCounters.modelRequestStarted();
        REQUESTS.put(requestId, new MutableRequest(
                requestId,
                prompt == null ? "" : prompt,
                automationRequest,
                SESSION_SEQUENCE.incrementAndGet(),
                counters,
                System.currentTimeMillis()
        ));
        if (selectedRequestId == null) selectedRequestId = requestId;
        ModelPresenceState.updateRequest(
                automationRequest
                        ? ModelPresenceState.ActivityKind.AUTOMATION
                        : ModelPresenceState.ActivityKind.ASK,
                "starting",
                true
        );
        trim();
    }

    public static synchronized void bindCancellation(UUID requestId, ModelCancellationHandle cancellation) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.cancellation = cancellation;
        }
    }

    public static synchronized void bindFinalization(UUID requestId, ModelFinalizationHandle finalization) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.finalization = finalization;
        }
    }

    public static synchronized void bindDeepThought(UUID requestId, ModelDeepThoughtControl control) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) request.deepThought = control;
    }

    public static synchronized boolean pauseDeepThought(UUID requestId) {
        MutableRequest request = REQUESTS.get(requestId);
        return request != null && request.deepThought != null && request.deepThought.pause();
    }

    public static synchronized boolean resumeDeepThought(UUID requestId) {
        MutableRequest request = REQUESTS.get(requestId);
        return request != null && request.deepThought != null && request.deepThought.resume();
    }

    public static synchronized void setAnswerNowVisible(UUID requestId, boolean visible) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.answerNowVisible = visible;
        }
    }

    public static synchronized boolean answerNow(UUID requestId) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request == null || !request.answerNowVisible || request.answerNowRequested
                || request.finalization == null || request.state.terminal()) {
            return false;
        }
        request.answerNowRequested = request.finalization.requestAnswerNow();
        if (request.answerNowRequested) {
            request.appendEvent(
                    ActivityEventType.THOUGHT_STOPPED,
                    "Preparing the best complete answer available."
            );
            request.state = ModelRequestState.FINALIZING;
            request.detail = "answer now requested";
        }
        return request.answerNowRequested;
    }

    public static synchronized void appendEvent(UUID requestId, ModelExecutionEvent event) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null && event != null) {
            request.appendEvent(map(event.type()), event.activity(), event.eventId(), event.summary(), event.data(), event.timestampMillis());
        }
    }

    public static synchronized void state(UUID requestId, ModelRequestState state, String detail) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request == null) {
            return;
        }
        request.state = state == null ? ModelRequestState.FAILED : state;
        request.detail = detail == null ? "" : detail;
        request.activityState = ModelRequestStatusPresentation.forActivity(
                request.state,
                request.detail,
                request.activeToolId
        ).activityState();
        ModelPresenceState.updateRequest(
                request.automationRequest
                        ? ModelPresenceState.ActivityKind.AUTOMATION
                        : ModelPresenceState.ActivityKind.ASK,
                request.activityState.id(),
                !request.state.terminal()
        );
        if (request.state.terminal()) {
            request.completedAtMillis = System.currentTimeMillis();
            request.resolveApproval(false);
        }
    }

    public static synchronized CompletableFuture<Boolean> requestApproval(
            UUID requestId,
            String title,
            String message,
            String approveLabel,
            String denyLabel
    ) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request == null || request.state.terminal()) {
            return CompletableFuture.completedFuture(false);
        }
        request.resolveApproval(false);
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        request.approval = new MutableApproval(
                new Approval(
                        title == null || title.isBlank() ? "Model approval" : title,
                        message == null ? "" : message,
                        approveLabel == null || approveLabel.isBlank() ? "Confirm" : approveLabel,
                        denyLabel == null || denyLabel.isBlank() ? "Deny" : denyLabel
                ),
                decision
        );
        return decision;
    }

    public static synchronized boolean resolveApproval(UUID requestId, boolean approved) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request == null || request.approval == null) {
            return false;
        }
        request.resolveApproval(approved);
        return true;
    }

    public static synchronized void append(UUID requestId, String delta) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null && delta != null && !delta.isEmpty()) {
            request.text.append(delta);
        }
    }

    public static synchronized void replaceText(UUID requestId, String text) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.text.setLength(0);
            if (text != null && !text.isEmpty()) {
                request.text.append(text);
            }
        }
    }

    public static synchronized void replacePrompt(UUID requestId, String prompt) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) request.prompt = prompt == null ? "" : prompt;
    }

    public static synchronized void appendActivity(UUID requestId, String entry) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request == null || entry == null || entry.isBlank()) {
            return;
        }
        request.appendActivity(entry);
    }

    public static synchronized void appendEvent(
            UUID requestId,
            ActivityEventType type,
            String summary
    ) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.appendEvent(type, activityFor(type, summary), "", summary, new JsonObject(), System.currentTimeMillis());
        }
    }

    public static synchronized void setPlan(
            UUID requestId,
            String planId,
            List<PlanStep> steps
    ) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.plan = new MutablePlan(planId, request.prompt, steps);
        }
    }

    public static synchronized void updatePlanStep(
            UUID requestId,
            int index,
            PlanStepStatus status,
            String result
    ) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null && request.plan != null) {
            request.plan.update(index, status, result);
        }
    }

    public static synchronized void markPlanRevised(UUID requestId) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null && request.plan != null) {
            request.plan.revised = true;
        }
    }

    public static synchronized String activity(UUID requestId) {
        MutableRequest request = REQUESTS.get(requestId);
        return request == null ? "" : request.renderActivity();
    }

    public static synchronized void usage(UUID requestId, ModelUsage usage) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null && usage != null) {
            request.usage = usage;
        }
    }

    public static synchronized void toolCallCount(UUID requestId, int count) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.toolCallCount = Math.max(0, count);
        }
    }

    public static synchronized void toolProgress(
            UUID requestId,
            int currentStep,
            int totalSteps,
            String toolId
    ) {
        toolProgress(requestId, currentStep, totalSteps, toolId, "");
    }

    public static synchronized void toolProgress(
            UUID requestId,
            int currentStep,
            int totalSteps,
            String toolId,
            String toolDetail
    ) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.currentToolStep = Math.max(0, currentStep);
            request.totalToolSteps = Math.max(request.currentToolStep, totalSteps);
            request.activeToolId = toolId == null ? "" : toolId;
            request.activeToolDetail = toolDetail == null ? "" : toolDetail;
            request.activityState = ModelRequestStatusPresentation.forActivity(
                    request.state,
                    request.activeToolDetail,
                    request.activeToolId
            ).activityState();
        }
    }

    /** Updates only nonterminal displayed requests when another startup-lifetime session begins. */
    public static synchronized void refreshLifetimeCounters() {
        KoilLifetimeCounters.Snapshot current = KoilLifetimeCounters.snapshot();
        for (MutableRequest request : REQUESTS.values()) {
            if (!request.state.terminal()) request.counters = current;
        }
    }

    public static synchronized Snapshot visibleSnapshot() {
        long now = System.currentTimeMillis();
        REQUESTS.entrySet().removeIf(entry ->
                entry.getValue().completedAtMillis > 0L
                        && now - entry.getValue().completedAtMillis > COMPLETED_VISIBILITY_MILLIS
        );
        MutableRequest selected = null;
        if (selectedRequestId != null) {
            selected = REQUESTS.get(selectedRequestId);
        }
        if (selected != null) return selected.snapshot();
        for (MutableRequest request : REQUESTS.values()) {
            if (!request.state.terminal()) {
                selected = request;
                break;
            }
            selected = request;
        }
        return selected == null ? null : selected.snapshot();
    }

    public static synchronized Snapshot snapshot(UUID requestId) {
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        return request == null ? null : request.snapshot();
    }

    /** Retains completed usage/context values until a new prompt starts. */
    public static synchronized Snapshot metricsSnapshot() {
        Snapshot visible = visibleSnapshot();
        return visible == null ? retainedMetricsSnapshot : visible;
    }

    public static synchronized boolean selectNextVisible() {
        if (REQUESTS.size() < 2) return false;
        List<UUID> ids = new ArrayList<>(REQUESTS.keySet());
        int current = selectedRequestId == null ? -1 : ids.indexOf(selectedRequestId);
        for (int offset = 1; offset <= ids.size(); offset++) {
            UUID candidate = ids.get((current + offset + ids.size()) % ids.size());
            MutableRequest request = REQUESTS.get(candidate);
            if (request != null) {
                selectedRequestId = candidate;
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean cancelVisible() {
        Snapshot snapshot = visibleSnapshot();
        if (snapshot == null || snapshot.state().terminal()) {
            return false;
        }
        ModelVoiceService.stopSpeaking("model request cancelled");
        boolean accepted = snapshot.cancellation() != null
                && snapshot.cancellation().cancel("cancelled from model popup");
        if (accepted) {
            MutableRequest request = REQUESTS.get(snapshot.requestId());
            if (request != null && !request.state.terminal()) {
                request.appendEvent(
                        ActivityEventType.CANCELLATION,
                        "Stopped thinking. The model request was cancelled."
                );
                request.state = ModelRequestState.CANCELLING;
                request.detail = "stopped thinking";
                ModelPresenceState.updateRequest(
                        request.automationRequest
                                ? ModelPresenceState.ActivityKind.AUTOMATION
                                : ModelPresenceState.ActivityKind.ASK,
                        "cancelled",
                        true
                );
            }
        }
        return accepted;
    }

    public static synchronized void dismiss(UUID requestId) {
        MutableRequest removed = REQUESTS.remove(requestId);
        if (requestId != null && requestId.equals(selectedRequestId)) selectedRequestId = null;
        if (removed != null) {
            retainedMetricsSnapshot = removed.snapshot();
            removed.resolveApproval(false);
        }
    }

    /** Called only after the final model response has entered normal chat. */
    public static synchronized void messagePresented(UUID requestId) {
        dismiss(requestId);
    }

    public static synchronized int queuedCount() {
        int queued = 0;
        for (MutableRequest request : REQUESTS.values()) {
            if (!request.state.terminal()) {
                queued++;
            }
        }
        return queued;
    }

    private static void trim() {
        while (REQUESTS.size() > MAXIMUM_TRACKED_REQUESTS) {
            UUID first = new ArrayList<>(REQUESTS.keySet()).get(0);
            MutableRequest removed = REQUESTS.remove(first);
            if (removed != null) {
                removed.resolveApproval(false);
            }
        }
    }

    private static String presenceState(ModelRequestState state, String detail) {
        if (state == null) {
            return "failed";
        }
        return switch (state) {
            case WAITING_FOR_RUNTIME, QUEUED -> "starting";
            case WAITING_FOR_DATA -> "observing";
            case PAUSED -> "idle";
            case PREPARING_CONTEXT, PREFILLING, THINKING -> "thinking";
            case INSPECTING -> "inspecting";
            case PLANNING, VALIDATING_PLAN -> "planning";
            case WAITING_FOR_PLAN_APPROVAL -> "awaiting_approval";
            case GENERATING -> "writing";
            case SELECTING_TOOL -> "thinking";
            case WAITING_FOR_ACTION_APPROVAL -> "awaiting_approval";
            case EXECUTING_TOOL, EDITING -> "executing";
            case WAITING_FOR_TOOL_RESULT, OBSERVING_RESULT -> "observing";
            case VALIDATING -> "validating";
            case RETRYING -> "retrying";
            case REPLANNING -> "replanning";
            case CHECKPOINTING -> "waiting";
            case FINALIZING -> "finalizing";
            case COMPLETED -> "completed";
            case BLOCKED, FAILED -> "failed";
            case CANCELLING, CANCELLED -> "cancelled";
        };
    }

    public record Snapshot(
            UUID requestId,
            String prompt,
            String text,
            ModelRequestState state,
            String detail,
            ModelUsage usage,
            ModelCancellationHandle cancellation,
            ModelFinalizationHandle finalization,
            boolean answerNowVisible,
            boolean answerNowRequested,
            ModelDeepThoughtControl deepThoughtControl,
            ModelDeepThoughtControl.Status deepThoughtStatus,
            Approval approval,
            int toolCallCount,
            int currentToolStep,
            int totalToolSteps,
            String activeToolId,
            String activeToolDetail,
            String activity,
            List<ActivityEvent> events,
            PlanView plan,
            boolean automationRequest,
            long sessionNumber,
            KoilLifetimeCounters.Snapshot counters,
            ModelActivityState activityState,
            long createdAtMillis,
            long completedAtMillis
    ) {
    }

    public record Approval(
            String title,
            String message,
            String approveLabel,
            String denyLabel
    ) {
    }

    private static final class MutableRequest {
        private final UUID requestId;
        private String prompt;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder activity = new StringBuilder();
        private final List<ActivityEvent> events = new ArrayList<>();
        private final boolean automationRequest;
        private final long sessionNumber;
        private KoilLifetimeCounters.Snapshot counters;
        private final long createdAtMillis;
        private ModelRequestState state = ModelRequestState.QUEUED;
        private ModelActivityState activityState = ModelActivityState.STARTING;
        private String detail = "queued";
        private ModelUsage usage = ModelUsage.empty();
        private ModelCancellationHandle cancellation;
        private ModelFinalizationHandle finalization;
        private boolean answerNowVisible;
        private boolean answerNowRequested;
        private ModelDeepThoughtControl deepThought;
        private MutableApproval approval;
        private int toolCallCount;
        private int currentToolStep;
        private int totalToolSteps;
        private String activeToolId = "";
        private String activeToolDetail = "";
        private MutablePlan plan;
        private long completedAtMillis;

        private MutableRequest(
                UUID requestId,
                String prompt,
                boolean automationRequest,
                long sessionNumber,
                KoilLifetimeCounters.Snapshot counters,
                long createdAtMillis
        ) {
            this.requestId = requestId;
            this.prompt = prompt;
            this.automationRequest = automationRequest;
            this.sessionNumber = sessionNumber;
            this.counters = counters == null ? KoilLifetimeCounters.snapshot() : counters;
            this.createdAtMillis = createdAtMillis;
        }

        private void appendActivity(String entry) {
            String normalized = entry.replace("\r\n", "\n").replace('\r', '\n').strip();
            if (normalized.isBlank()) {
                return;
            }
            if (!this.activity.isEmpty()) {
                this.activity.append("\n\n");
            }
            this.activity.append(normalized);
            int overflow = this.activity.length() - 8_192;
            if (overflow > 0) {
                int boundary = this.activity.indexOf("\n\n", overflow);
                this.activity.delete(0, boundary < 0 ? overflow : boundary + 2);
            }
        }

        private void appendEvent(ActivityEventType type, String summary) {
            appendEvent(type, activityFor(type, summary), "", summary, new JsonObject(), System.currentTimeMillis());
        }

        private void appendEvent(
                ActivityEventType type,
                ModelActivityState activityState,
                String eventId,
                String summary,
                JsonObject data,
                long timestampMillis
        ) {
            String safe = cleanVisibleSummary(summary);
            if (safe.isBlank()) {
                return;
            }
            ActivityEvent event = new ActivityEvent(
                    type == null ? ActivityEventType.RESULT : type,
                    activityState == null ? activityFor(type, summary) : activityState,
                    safe,
                    timestampMillis,
                    eventId,
                    data
            );
            if (!event.eventId().isBlank()) {
                for (int index = 0; index < this.events.size(); index++) {
                    if (event.eventId().equals(this.events.get(index).eventId())) {
                        this.events.set(index, event);
                        return;
                    }
                }
            }
            this.events.add(event);
            while (this.events.size() > 64) {
                this.events.remove(0);
            }
        }

        private String renderActivity() {
            String base = this.activity.toString().strip();
            List<ActivityEvent> visibleEvents = this.automationRequest
                    ? this.events.stream().filter(ModelGenerationHudState::isModelLevelAutomationEvent).toList()
                    : this.events;
            String timeline = ModelActivityPresentation.timeline(this.prompt, visibleEvents);
            if (!timeline.isBlank()) {
                base = base.isBlank() ? timeline : timeline + "\n\n" + base;
            }
            String planText = this.plan == null ? "" : this.plan.render();
            if (base.isBlank()) {
                return planText;
            }
            if (planText.isBlank()) {
                return base;
            }
            return base + "\n\n" + planText;
        }

        private Snapshot snapshot() {
            return new Snapshot(
                    this.requestId,
                    this.prompt,
                    this.text.toString(),
                    this.state,
                    this.detail,
                    this.usage,
                    this.cancellation,
                    this.finalization,
                    this.answerNowVisible,
                    this.answerNowRequested,
                    this.deepThought,
                    this.deepThought == null ? null : this.deepThought.status(),
                    this.approval == null ? null : this.approval.snapshot,
                    this.toolCallCount,
                    this.currentToolStep,
                    this.totalToolSteps,
                    this.activeToolId,
                    this.activeToolDetail,
                    renderActivity(),
                    List.copyOf(this.events),
                    this.plan == null ? null : this.plan.snapshot(),
                    this.automationRequest,
                    this.sessionNumber,
                    this.counters,
                    this.activityState,
                    this.createdAtMillis,
                    this.completedAtMillis
            );
        }

        private void resolveApproval(boolean approved) {
            MutableApproval pending = this.approval;
            this.approval = null;
            if (pending != null) {
                pending.decision.complete(approved);
            }
        }
    }

    private static boolean isModelLevelAutomationEvent(ActivityEvent event) {
        if (event == null) return false;
        return switch (event.type()) {
            case THOUGHT_SUMMARY, THOUGHT_STOPPED, PLAN_STEP, APPROVAL, REPLAN, CANCELLATION, CHECKPOINT -> true;
            case TOOL_START, TOOL_PROGRESS, FILE, DIFF, COMMAND, VALIDATION, RESULT, FAILURE -> false;
        };
    }

    private static String cleanVisibleSummary(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        return clean.length() <= 420 ? clean : clean.substring(0, 419) + "…";
    }

    public enum ActivityEventType {
        THOUGHT_SUMMARY,
        THOUGHT_STOPPED,
        PLAN_STEP,
        APPROVAL,
        TOOL_START,
        TOOL_PROGRESS,
        FILE,
        DIFF,
        COMMAND,
        VALIDATION,
        RESULT,
        FAILURE,
        REPLAN,
        CANCELLATION,
        CHECKPOINT
    }

    public record ActivityEvent(
            ActivityEventType type,
            ModelActivityState activityState,
            String summary,
            long timestampMillis,
            String eventId,
            JsonObject data
    ) {
        public ActivityEvent {
            type = type == null ? ActivityEventType.RESULT : type;
            activityState = activityState == null ? activityFor(type, summary) : activityState;
            summary = cleanVisibleSummary(summary);
            eventId = eventId == null ? "" : eventId.strip();
            data = data == null ? new JsonObject() : data.deepCopy();
        }

        public ActivityEvent(ActivityEventType type, String summary, long timestampMillis) {
            this(type, activityFor(type, summary), summary, timestampMillis, "", new JsonObject());
        }
    }

    private static ModelActivityState activityFor(ActivityEventType type, String summary) {
        return switch (type == null ? ActivityEventType.RESULT : type) {
            case THOUGHT_SUMMARY -> ModelActivityState.THINKING;
            case THOUGHT_STOPPED, CANCELLATION -> ModelActivityState.CANCELLED;
            case PLAN_STEP -> ModelActivityState.PLANNING;
            case APPROVAL -> ModelActivityState.AWAITING_APPROVAL;
            case TOOL_START, TOOL_PROGRESS -> ModelActivityState.fromLegacy(summary);
            case FILE -> ModelActivityState.READING;
            case DIFF -> ModelActivityState.COMPARING;
            case COMMAND -> ModelActivityState.INSPECTING;
            case VALIDATION -> ModelActivityState.VALIDATING;
            case RESULT -> ModelActivityState.OBSERVING;
            case FAILURE -> ModelActivityState.FAILED;
            case REPLAN -> ModelActivityState.REPLANNING;
            case CHECKPOINT -> ModelActivityState.WRITING;
        };
    }

    public enum PlanStepStatus {
        PENDING,
        ACTIVE,
        COMPLETED,
        FAILED,
        BLOCKED,
        SKIPPED,
        CANCELLED,
        REVISED
    }

    private static ActivityEventType map(ModelExecutionEvent.Type type) {
        return switch (type) {
            case THOUGHT_SUMMARY -> ActivityEventType.THOUGHT_SUMMARY;
            case PLAN_CREATED, PLAN_VALIDATED -> ActivityEventType.PLAN_STEP;
            case APPROVAL_REQUESTED, APPROVAL_ACCEPTED, APPROVAL_REJECTED -> ActivityEventType.APPROVAL;
            case TOOL_SELECTED, TOOL_STARTED -> ActivityEventType.TOOL_START;
            case TOOL_PROGRESS -> ActivityEventType.TOOL_PROGRESS;
            case TOOL_RESULT -> ActivityEventType.RESULT;
            case FILE_READ, FILE_SEARCHED, FILE_CREATED, FILE_MODIFIED, FILE_DELETED -> ActivityEventType.FILE;
            case DIFF_PRODUCED -> ActivityEventType.DIFF;
            case COMMAND_STARTED, COMMAND_OUTPUT, COMMAND_COMPLETED -> ActivityEventType.COMMAND;
            case VALIDATION_STARTED, VALIDATION_PASSED, VALIDATION_FAILED -> ActivityEventType.VALIDATION;
            case RETRY, REPLAN -> ActivityEventType.REPLAN;
            case BLOCKED -> ActivityEventType.FAILURE;
            case CANCELLATION_REQUESTED, CANCELLED -> ActivityEventType.CANCELLATION;
            case FINAL_RESULT -> ActivityEventType.RESULT;
            case CHECKPOINT -> ActivityEventType.CHECKPOINT;
        };
    }

    public record PlanStep(
            int index,
            String toolId,
            String summary,
            PlanStepStatus status,
            String result,
            String arguments,
            String expectedObservation,
            String validationRequirement
    ) {
        public PlanStep {
            toolId = toolId == null ? "" : toolId;
            summary = cleanVisibleSummary(summary);
            status = status == null ? PlanStepStatus.PENDING : status;
            result = cleanVisibleSummary(result);
            arguments = cleanVisibleSummary(arguments);
            expectedObservation = cleanVisibleSummary(expectedObservation);
            validationRequirement = cleanVisibleSummary(validationRequirement);
        }

        public PlanStep(int index, String toolId, String summary, PlanStepStatus status, String result) {
            this(index, toolId, summary, status, result, "", "", "");
        }
    }

    public record PlanView(
            String planId,
            List<PlanStep> steps,
            boolean revised
    ) {
    }

    private static final class MutablePlan {
        private final String planId;
        private final String objective;
        private final List<PlanStep> steps;
        private boolean revised;

        private MutablePlan(String planId, String objective, List<PlanStep> input) {
            this.planId = planId == null ? "" : planId;
            this.objective = cleanVisibleSummary(objective);
            this.steps = new ArrayList<>();
            if (input != null) {
                for (int index = 0; index < input.size(); index++) {
                    PlanStep step = input.get(index);
                    if (step == null) {
                        continue;
                    }
                    this.steps.add(new PlanStep(
                            index + 1,
                            step.toolId(),
                            step.summary(),
                            PlanStepStatus.PENDING,
                            "",
                            step.arguments(),
                            step.expectedObservation(),
                            step.validationRequirement()
                    ));
                }
            }
        }

        private void update(int index, PlanStepStatus status, String result) {
            int position = index - 1;
            if (position < 0 || position >= this.steps.size()) {
                return;
            }
            PlanStep current = this.steps.get(position);
            this.steps.set(position, new PlanStep(
                    current.index(),
                    current.toolId(),
                    current.summary(),
                    status,
                    result,
                    current.arguments(),
                    current.expectedObservation(),
                    current.validationRequirement()
            ));
        }

        private PlanView snapshot() {
            return new PlanView(this.planId, List.copyOf(this.steps), this.revised);
        }

        private String render() {
            if (this.steps.isEmpty()) {
                return "";
            }
            return ModelActivityPresentation.plan(this.planId, this.objective, this.steps, this.revised);
        }
    }

    private static final class MutableApproval {
        private final Approval snapshot;
        private final CompletableFuture<Boolean> decision;

        private MutableApproval(Approval snapshot, CompletableFuture<Boolean> decision) {
            this.snapshot = snapshot;
            this.decision = decision;
        }
    }
}
