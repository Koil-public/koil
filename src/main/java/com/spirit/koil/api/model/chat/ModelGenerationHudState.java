package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.presence.ModelPresenceState;

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

    private ModelGenerationHudState() {
    }

    public static synchronized void begin(UUID requestId, String prompt) {
        begin(requestId, prompt, false);
    }

    public static synchronized void begin(UUID requestId, String prompt, boolean automationRequest) {
        REQUESTS.put(requestId, new MutableRequest(
                requestId,
                prompt == null ? "" : prompt,
                automationRequest,
                SESSION_SEQUENCE.incrementAndGet(),
                System.currentTimeMillis()
        ));
        ModelPresenceState.updateRequest(
                automationRequest
                        ? ModelPresenceState.ActivityKind.AUTOMATION
                        : ModelPresenceState.ActivityKind.ASK,
                "waiting",
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

    public static synchronized void state(UUID requestId, ModelRequestState state, String detail) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request == null) {
            return;
        }
        request.state = state == null ? ModelRequestState.FAILED : state;
        request.detail = detail == null ? "" : detail;
        ModelPresenceState.updateRequest(
                request.automationRequest
                        ? ModelPresenceState.ActivityKind.AUTOMATION
                        : ModelPresenceState.ActivityKind.ASK,
                presenceState(request.state, request.detail),
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
            request.appendEvent(type, summary);
        }
    }

    public static synchronized void setPlan(
            UUID requestId,
            String planId,
            List<PlanStep> steps
    ) {
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            request.plan = new MutablePlan(planId, steps);
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
        }
    }

    public static synchronized Snapshot visibleSnapshot() {
        long now = System.currentTimeMillis();
        REQUESTS.entrySet().removeIf(entry ->
                entry.getValue().completedAtMillis > 0L
                        && now - entry.getValue().completedAtMillis > COMPLETED_VISIBILITY_MILLIS
        );
        MutableRequest selected = null;
        for (MutableRequest request : REQUESTS.values()) {
            if (!request.state.terminal()) {
                selected = request;
                break;
            }
            selected = request;
        }
        return selected == null ? null : selected.snapshot();
    }

    public static synchronized boolean cancelVisible() {
        Snapshot snapshot = visibleSnapshot();
        if (snapshot == null || snapshot.cancellation() == null || snapshot.state().terminal()) {
            return false;
        }
        return snapshot.cancellation().cancel("cancelled from model popup");
    }

    public static synchronized void dismiss(UUID requestId) {
        MutableRequest removed = REQUESTS.remove(requestId);
        if (removed != null) {
            removed.resolveApproval(false);
        }
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
            case WAITING_FOR_RUNTIME, QUEUED -> "waiting";
            case PREPARING_CONTEXT, PREFILLING -> "thinking";
            case GENERATING -> "writing";
            case EXECUTING_TOOL -> detail != null && detail.toLowerCase(java.util.Locale.ROOT).contains("plan")
                    ? "planning"
                    : "acting";
            case WAITING_FOR_TOOL_RESULT -> "waiting";
            case FINALIZING -> "finishing";
            case COMPLETED -> "completed";
            case FAILED, CANCELLED -> "failed";
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
        private final String prompt;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder activity = new StringBuilder();
        private final List<ActivityEvent> events = new ArrayList<>();
        private final boolean automationRequest;
        private final long sessionNumber;
        private final long createdAtMillis;
        private ModelRequestState state = ModelRequestState.QUEUED;
        private String detail = "queued";
        private ModelUsage usage = ModelUsage.empty();
        private ModelCancellationHandle cancellation;
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
                long createdAtMillis
        ) {
            this.requestId = requestId;
            this.prompt = prompt;
            this.automationRequest = automationRequest;
            this.sessionNumber = sessionNumber;
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
            String safe = cleanVisibleSummary(summary);
            if (safe.isBlank()) {
                return;
            }
            ActivityEvent event = new ActivityEvent(
                    type == null ? ActivityEventType.RESULT : type,
                    safe,
                    System.currentTimeMillis()
            );
            this.events.add(event);
            while (this.events.size() > 64) {
                this.events.remove(0);
            }
            appendActivity(renderEvent(event, this.events.size() == 1));
        }

        private String renderActivity() {
            String base = this.activity.toString().strip();
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

    private static String renderEvent(ActivityEvent event, boolean first) {
        String marker = first ? "• " : "├─ ";
        String label = switch (event.type()) {
            case THOUGHT_SUMMARY -> "Thought";
            case PLAN_STEP -> "Plan";
            case TOOL_START -> "Start";
            case RESULT -> "Result";
            case FAILURE -> "Failed";
            case REPLAN -> "Replan";
        };
        return "-# " + marker + label + " — " + event.summary() + "\n-# │";
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
        PLAN_STEP,
        TOOL_START,
        RESULT,
        FAILURE,
        REPLAN
    }

    public record ActivityEvent(
            ActivityEventType type,
            String summary,
            long timestampMillis
    ) {
    }

    public enum PlanStepStatus {
        PENDING,
        ACTIVE,
        COMPLETED,
        FAILED,
        REVISED
    }

    public record PlanStep(
            int index,
            String toolId,
            String summary,
            PlanStepStatus status,
            String result
    ) {
        public PlanStep {
            toolId = toolId == null ? "" : toolId;
            summary = cleanVisibleSummary(summary);
            status = status == null ? PlanStepStatus.PENDING : status;
            result = cleanVisibleSummary(result);
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
        private final List<PlanStep> steps;
        private boolean revised;

        private MutablePlan(String planId, List<PlanStep> input) {
            this.planId = planId == null ? "" : planId;
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
                            ""
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
                    result
            ));
        }

        private PlanView snapshot() {
            return new PlanView(this.planId, List.copyOf(this.steps), this.revised);
        }

        private String render() {
            if (this.steps.isEmpty()) {
                return "";
            }
            StringBuilder rendered = new StringBuilder("**Plan ")
                    .append(this.planId);
            if (this.revised) {
                rendered.append(" — revised");
            }
            rendered.append("**");
            for (PlanStep step : this.steps) {
                rendered.append("\n")
                        .append(step.index())
                        .append(". [")
                        .append(step.status().name().toLowerCase(java.util.Locale.ROOT))
                        .append("] ")
                        .append(step.toolId());
                if (!step.summary().isBlank()) {
                    rendered.append(" — ").append(step.summary());
                }
                if (!step.result().isBlank()) {
                    rendered.append(" — ").append(step.result());
                }
            }
            return rendered.toString();
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
