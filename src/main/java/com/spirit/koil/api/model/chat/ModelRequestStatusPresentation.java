package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelRequestState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared live activity vocabulary used by every model surface.
 *
 * <p>Status text is intentionally literal. The verb is selected from the real request state,
 * provider detail, or active tool. User prompt content is never used as status-detail copy;
 * prompt-processing states use the literal subject "user prompt" instead. This gives long
 * local-model waits useful motion without manufacturing work that did not occur.</p>
 */
public final class ModelRequestStatusPresentation {
    public static final long COMPLETED_HOLD_MILLIS = 420L;
    /** Retained for binary/source compatibility. Completed-status history is no longer rendered. */
    public static final long HISTORY_FADE_MILLIS = 0L;
    public static final long ELAPSED_HINT_MILLIS = 4_000L;
    private static final long STALE_REQUEST_MILLIS = 120_000L;
    private static final int MAX_TRACKED_REQUESTS = 256;
    private static final ConcurrentHashMap<UUID, StatusMemory> REQUESTS = new ConcurrentHashMap<>();

    private ModelRequestStatusPresentation() {
    }

    public static View forState(ModelRequestState state) {
        ModelRequestState safe = state == null ? ModelRequestState.FAILED : state;
        return switch (safe) {
            case WAITING_FOR_RUNTIME -> view("Starting", "Started", ModelActivityState.STARTING, "local model runtime");
            case QUEUED -> view("Queued", "Queued", ModelActivityState.STARTING, "request");
            case PREPARING_CONTEXT -> view("Preparing", "Prepared", ModelActivityState.PREPARING, "model context");
            case THINKING -> view("Thinking", "Finished thinking", ModelActivityState.THINKING, "through the answer");
            case PREFILLING -> view("Ingesting", "Ingested", ModelActivityState.PREPARING, "user prompt");
            case INSPECTING -> view("Inspecting", "Inspected", ModelActivityState.INSPECTING, "available evidence");
            case PLANNING -> view("Planning", "Planned", ModelActivityState.PLANNING, "next actions");
            case VALIDATING_PLAN -> view("Checking", "Checked", ModelActivityState.VALIDATING, "plan");
            case WAITING_FOR_PLAN_APPROVAL -> view("Waiting", "Received", ModelActivityState.AWAITING_APPROVAL, "for plan approval");
            case GENERATING -> view("Writing", "Written", ModelActivityState.WRITING, "response");
            case SELECTING_TOOL -> view("Selecting", "Selected", ModelActivityState.RESOLVING, "next tool");
            case WAITING_FOR_ACTION_APPROVAL -> view("Waiting", "Received", ModelActivityState.AWAITING_APPROVAL, "for action approval");
            case EXECUTING_TOOL -> view("Executing", "Executed", ModelActivityState.EXECUTING, "tool action");
            case WAITING_FOR_TOOL_RESULT -> view("Waiting", "Received", ModelActivityState.OBSERVING, "for tool result");
            case OBSERVING_RESULT -> view("Reviewing", "Reviewed", ModelActivityState.READING, "tool result");
            case EDITING -> view("Updating", "Updated", ModelActivityState.EDITING, "files");
            case VALIDATING -> view("Verifying", "Verified", ModelActivityState.VALIDATING, "result");
            case RETRYING -> view("Retrying", "Retried", ModelActivityState.RETRYING, "request");
            case REPLANNING -> view("Replanning", "Replanned", ModelActivityState.REPLANNING, "next actions");
            case CHECKPOINTING -> view("Saving", "Saved", ModelActivityState.WRITING, "checkpoint");
            case WAITING_FOR_DATA -> view("Waiting", "Received", ModelActivityState.OBSERVING, "for data");
            case PAUSED -> view("Paused", "Paused", ModelActivityState.IDLE, "");
            case FINALIZING -> view("Finalizing", "Finalized", ModelActivityState.FINALIZING, "response");
            case COMPLETED -> view("Complete", "Completed", ModelActivityState.COMPLETE, "");
            case BLOCKED -> view("Blocked", "Blocked", ModelActivityState.BLOCKED, "");
            case CANCELLING, CANCELLED -> view("Cancelled", "Cancelled", ModelActivityState.CANCELLED, "");
            case FAILED -> view("Failed", "Failed", ModelActivityState.FAILED, "");
        };
    }

    public static View forRequest(UUID requestId, ModelRequestState state, String detail, String toolId) {
        return forRequest(requestId, state, detail, toolId, "");
    }

    /**
     * Stateful presentation used by live UI surfaces.
     *
     * <p>Status changes are immediate. The previous operation is not retained or faded;
     * the active row simply replaces it.</p>
     */
    public static View forRequest(
            UUID requestId,
            ModelRequestState state,
            String detail,
            String toolId,
            String prompt
    ) {
        View current = forActivity(state, detail, toolId, prompt);
        if (requestId == null) return current;
        long now = System.currentTimeMillis();
        StatusMemory memory = REQUESTS.computeIfAbsent(requestId, ignored -> new StatusMemory());
        synchronized (memory) {
            if (memory.current == null || !sameActivity(memory.current, current)) {
                memory.currentSince = now;
            }
            memory.current = current;
            memory.lastSeen = now;
        }
        cleanup(now);
        return current;
    }

    /** Builds the richest truthful status available from the immutable HUD snapshot. */
    public static View forSnapshot(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot == null) return forState(ModelRequestState.FAILED);
        View base = forRequest(
                snapshot.requestId(),
                snapshot.state(),
                snapshot.activeToolDetail().isBlank() ? snapshot.detail() : snapshot.activeToolDetail(),
                snapshot.activeToolId(),
                snapshot.prompt()
        );
        long elapsed = activeElapsedMillis(snapshot.requestId());
        String detail = enrichDetail(snapshot, base.detail(), elapsed);
        return new View(base.label(), base.completedLabel(), detail, base.activityState());
    }

    /**
     * Compatibility accessor. Completed status history was intentionally removed; callers
     * always receive an empty view so state changes replace one another immediately.
     */
    public static HistoryView recentCompleted(UUID requestId) {
        return HistoryView.empty();
    }

    public static long activeElapsedMillis(UUID requestId) {
        if (requestId == null) return 0L;
        StatusMemory memory = REQUESTS.get(requestId);
        if (memory == null) return 0L;
        long now = System.currentTimeMillis();
        synchronized (memory) {
            return memory.currentSince <= 0L ? 0L : Math.max(0L, now - memory.currentSince);
        }
    }

    private static boolean sameActivity(View left, View right) {
        return left != null && right != null
                && left.activityState() == right.activityState()
                && left.label().equals(right.label())
                && left.detail().equals(right.detail());
    }

    private static void cleanup(long now) {
        if (REQUESTS.size() <= MAX_TRACKED_REQUESTS) return;
        REQUESTS.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > STALE_REQUEST_MILLIS);
    }

    public static View forActivity(ModelRequestState state, String detail, String toolId) {
        return forActivity(state, detail, toolId, "");
    }

    public static View forActivity(ModelRequestState state, String detail, String toolId, String prompt) {
        ModelRequestState safe = state == null ? ModelRequestState.FAILED : state;
        String rawDetail = detail == null ? "" : detail.strip();
        String normalizedDetail = normalize(rawDetail);

        if (toolRelevant(safe) && toolId != null && !toolId.isBlank()) {
            ModelToolActivityPresentation.Activity tool = ModelToolActivityPresentation.activity(toolId, rawDetail);
            return new View(
                    tool.activeLabel(),
                    tool.completedLabel(),
                    subject(tool.detail(), tool.activeLabel()),
                    tool.state()
            );
        }

        View specialized = specializedDetail(safe, rawDetail, normalizedDetail, prompt);
        if (specialized != null) return specialized;

        View base = forState(safe);
        String liveDetail = usefulDetail(rawDetail, base.detail());
        if (isGenericDetail(liveDetail)) {
            liveDetail = base.detail();
        }
        return new View(base.label(), base.completedLabel(), subject(liveDetail, base.label()), base.activityState());
    }

    private static String enrichDetail(
            ModelGenerationHudState.Snapshot snapshot,
            String baseDetail,
            long activeElapsed
    ) {
        String detail = baseDetail == null ? "" : baseDetail.strip();

        PromptProgress progress = latestPromptProgress(snapshot.events());
        boolean continuationPrefill = snapshot.state() == ModelRequestState.PREFILLING
                && contains(normalize(detail), "continuation", "tool result", "model reasoning", "model output", "final response correction");
        if (snapshot.state() == ModelRequestState.PREFILLING && !continuationPrefill
                && progress != null && progress.total() > 0) {
            StringBuilder value = new StringBuilder("user prompt · ")
                    .append(progress.processed()).append('/').append(progress.total()).append(" tokens processed");
            int percent = (int) Math.round(Math.min(100.0D, progress.processed() * 100.0D / progress.total()));
            value.append(" · ").append(percent).append('%');
            if (progress.cached() > 0) value.append(" · ").append(progress.cached()).append(" cached");
            long sinceProgress = Math.max(0L, System.currentTimeMillis() - progress.timestampMillis());
            if (sinceProgress >= ELAPSED_HINT_MILLIS) {
                value.append(" · last progress ").append(compactDuration(sinceProgress)).append(" ago");
            }
            detail = value.toString();
        } else if (snapshot.state() == ModelRequestState.THINKING) {
            boolean exposedReasoning = hasRecentCognitiveOutput(snapshot.events());
            if (activeElapsed >= 8_000L) {
                detail = exposedReasoning
                        ? "model is still reasoning through the request"
                        : "model is still working through the request";
            } else if (detail.isBlank() || isGenericDetail(detail)) {
                detail = "working through the request";
            }
        } else if (snapshot.state() == ModelRequestState.GENERATING && snapshot.usage() != null
                && snapshot.usage().completionTokens() > 0) {
            detail = "response · " + snapshot.usage().completionTokens() + " tokens generated";
        } else if (isToolPhase(snapshot.state()) && snapshot.currentToolStep() > 0 && snapshot.totalToolSteps() > 1) {
            String step = "step " + snapshot.currentToolStep() + "/" + snapshot.totalToolSteps();
            detail = detail.isBlank() ? step : detail + " · " + step;
        }

        long activeSince = Math.max(0L, System.currentTimeMillis() - activeElapsed);
        RealProgress realProgress = latestRealProgress(snapshot.events(), activeSince);
        if (realProgress != null && !realProgress.text().isBlank()
                && snapshot.state() != ModelRequestState.PREFILLING) {
            detail = detail.isBlank() ? realProgress.text() : detail + " · " + realProgress.text();
        }

        if (detail.isBlank()) {
            detail = fallbackContextForState(snapshot.state(), snapshot.prompt());
        }
        // Runtime elapsed/rate metrics are rendered separately in the neutral gray
        // metrics readout. Do not append elapsed time to the colorized live status
        // detail, otherwise the same timing information appears twice after the
        // status-detail separator.
        return compact(detail, 170);
    }

    private static String fallbackContextForState(ModelRequestState state, String prompt) {
        ModelRequestState safe = state == null ? ModelRequestState.FAILED : state;
        return switch (safe) {
            case WAITING_FOR_RUNTIME -> "local model runtime";
            case QUEUED -> "request waiting for model runtime";
            case PREPARING_CONTEXT -> "request context";
            case THINKING -> "working through the request";
            case PREFILLING -> "user prompt";
            case INSPECTING -> "available evidence";
            case PLANNING, REPLANNING -> "next actions";
            case VALIDATING_PLAN -> "planned actions";
            case WAITING_FOR_PLAN_APPROVAL -> "plan approval";
            case GENERATING -> "response";
            case SELECTING_TOOL -> "relevant tool";
            case WAITING_FOR_ACTION_APPROVAL -> "action approval";
            case EXECUTING_TOOL -> "tool action";
            case WAITING_FOR_TOOL_RESULT -> "tool result";
            case OBSERVING_RESULT -> "tool result";
            case EDITING -> "project changes";
            case VALIDATING -> "result";
            case RETRYING -> "request recovery";
            case CHECKPOINTING -> "checkpoint";
            case PAUSED -> "request paused";
            case WAITING_FOR_DATA -> "required data";
            case FINALIZING -> "final response";
            case COMPLETED -> "response finished";
            case BLOCKED -> "request blocked";
            case CANCELLING -> "request cancellation";
            case CANCELLED -> "request cancelled";
            case FAILED -> "request failed";
        };
    }

    private static boolean isToolPhase(ModelRequestState state) {
        return state == ModelRequestState.SELECTING_TOOL
                || state == ModelRequestState.EXECUTING_TOOL
                || state == ModelRequestState.WAITING_FOR_TOOL_RESULT
                || state == ModelRequestState.OBSERVING_RESULT
                || state == ModelRequestState.VALIDATING;
    }

    private static boolean hasRecentCognitiveOutput(List<ModelGenerationHudState.ActivityEvent> events) {
        if (events == null || events.isEmpty()) return false;
        for (int i = events.size() - 1; i >= 0; i--) {
            ModelGenerationHudState.ActivityEvent event = events.get(i);
            if (event == null) continue;
            if (event.type() == ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY) return true;
        }
        return false;
    }

    private static PromptProgress latestPromptProgress(List<ModelGenerationHudState.ActivityEvent> events) {
        if (events == null || events.isEmpty()) return null;
        for (int i = events.size() - 1; i >= 0; i--) {
            ModelGenerationHudState.ActivityEvent event = events.get(i);
            if (event == null || event.data() == null) continue;
            JsonObject data = event.data();
            if (!"prompt_progress".equals(string(data, "telemetryKind"))) continue;
            int total = integer(data, "promptTotalTokens");
            int processed = integer(data, "promptProcessedTokens");
            int cached = integer(data, "promptCachedTokens");
            return new PromptProgress(total, processed, cached, event.timestampMillis());
        }
        return null;
    }

    /**
     * Extracts progress only when an event provides explicit numeric progress.
     * It deliberately does not infer percentages from prose.
     */
    private static RealProgress latestRealProgress(
            List<ModelGenerationHudState.ActivityEvent> events,
            long activeSinceMillis
    ) {
        if (events == null || events.isEmpty()) return null;
        for (int i = events.size() - 1; i >= 0; i--) {
            ModelGenerationHudState.ActivityEvent event = events.get(i);
            if (event == null || event.data() == null) continue;
            if (activeSinceMillis > 0L && event.timestampMillis() + 250L < activeSinceMillis) continue;
            JsonObject data = event.data();
            if ("prompt_progress".equals(string(data, "telemetryKind"))) continue;
            int current = firstInteger(data, "completed", "current", "processed", "done", "index", "step");
            int total = firstInteger(data, "total", "count", "maximum", "max", "steps");
            if (current >= 0 && total > 0 && current <= total) {
                return new RealProgress(current + "/" + total);
            }
            double percent = firstDecimal(data, "percent", "progressPercent", "progress");
            if (percent >= 0.0D) {
                if (percent <= 1.0D) percent *= 100.0D;
                return new RealProgress(Math.round(Math.min(100.0D, percent)) + "%");
            }
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return "";
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try { return value.getAsString(); } catch (RuntimeException ignored) { return ""; }
    }

    private static int integer(JsonObject object, String key) {
        String value = string(object, key);
        if (value.isBlank()) return 0;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static int firstInteger(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object == null || !object.has(key)) continue;
            String value = string(object, key);
            if (value.isBlank()) continue;
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    private static double firstDecimal(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object == null || !object.has(key)) continue;
            String value = string(object, key);
            if (value.isBlank()) continue;
            try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { }
        }
        return -1.0D;
    }

    private static String compactDuration(long millis) {
        long seconds = Math.max(0L, millis) / 1_000L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        return remainder == 0L ? minutes + "m" : minutes + "m " + remainder + "s";
    }

    private static View specializedDetail(
            ModelRequestState state,
            String rawDetail,
            String normalizedDetail,
            String prompt
    ) {
        if (normalizedDetail.isBlank()) return null;
        String subject = usefulDetail(rawDetail, "");

        if (state == ModelRequestState.THINKING && contains(normalizedDetail, "reasoning", "thinking", "continuing model")) {
            return view("Thinking", "Finished thinking", ModelActivityState.THINKING, "through the answer");
        }
        if (contains(normalizedDetail, "prompt cache", "kv cache", "cache reuse", "cached prefix")) {
            return view("Reusing", "Reused", ModelActivityState.PREPARING,
                    subjectOr(subject, "user prompt"));
        }
        if (contains(normalizedDetail, "cache save", "saving cache")) {
            return view("Caching", "Cached", ModelActivityState.WRITING,
                    subjectOr(subject, "user prompt"));
        }
        if (contains(normalizedDetail, "restore", "restoring")) {
            return view("Restoring", "Restored", ModelActivityState.RECOVERING,
                    subjectOr(subject, "runtime state"));
        }
        if (state == ModelRequestState.PREFILLING
                && contains(normalizedDetail, "continuation", "tool result", "model reasoning", "model output", "final response correction")) {
            String continuationSubject = contains(normalizedDetail, "tool result") ? "tool result"
                    : contains(normalizedDetail, "final response correction") ? "final response correction"
                    : contains(normalizedDetail, "reasoning") ? "model reasoning"
                    : contains(normalizedDetail, "model output") ? "model output"
                    : "model state";
            return view("Continuing", "Continued", ModelActivityState.PREPARING, continuationSubject);
        }
        if (contains(normalizedDetail, "prefill", "user prompt", "processing prompt")) {
            return view("Ingesting", "Ingested", ModelActivityState.PREPARING, "user prompt");
        }
        if (contains(normalizedDetail, "tokeniz")) {
            return view("Tokenizing", "Tokenized", ModelActivityState.PREPARING,
                    subjectOr(subject, "user prompt"));
        }
        if (contains(normalizedDetail, "index", "indexing")) {
            return view("Indexing", "Indexed", ModelActivityState.INSPECTING,
                    subjectOr(subject, "available data"));
        }
        if (contains(normalizedDetail, "scan", "scanning")) {
            return view("Scanning", "Scanned", ModelActivityState.INSPECTING,
                    subjectOr(subject, "available data"));
        }
        if (contains(normalizedDetail, "enumerat")) {
            return view("Enumerating", "Enumerated", ModelActivityState.INSPECTING,
                    subjectOr(subject, "available entries"));
        }
        if (contains(normalizedDetail, "decode", "decoding")) {
            return view("Decoding", "Decoded", ModelActivityState.READING,
                    subjectOr(subject, "model output"));
        }
        if (contains(normalizedDetail, "encode", "encoding")) {
            return view("Encoding", "Encoded", ModelActivityState.PREPARING,
                    subjectOr(subject, "request data"));
        }
        if (contains(normalizedDetail, "normaliz")) {
            return view("Normalizing", "Normalized", ModelActivityState.PREPARING,
                    subjectOr(subject, "request data"));
        }
        if (contains(normalizedDetail, "deduplicat")) {
            return view("Deduplicating", "Deduplicated", ModelActivityState.PREPARING,
                    subjectOr(subject, "request data"));
        }
        if (contains(normalizedDetail, "merge", "merging")) {
            return view("Merging", "Merged", ModelActivityState.PREPARING,
                    subjectOr(subject, "context"));
        }
        if (contains(normalizedDetail, "sync", "synchroniz")) {
            return view("Synchronizing", "Synchronized", ModelActivityState.PREPARING,
                    subjectOr(subject, "runtime state"));
        }
        if (contains(normalizedDetail, "warmup", "warming")) {
            return view("Warming", "Warmed", ModelActivityState.STARTING,
                    subjectOr(subject, "model cache"));
        }
        if (contains(normalizedDetail, "dispatch")) {
            return view("Dispatching", "Dispatched", ModelActivityState.RESOLVING,
                    subjectOr(subject, "request"));
        }
        if (contains(normalizedDetail, "apply", "applying")) {
            return view("Applying", "Applied", ModelActivityState.EDITING,
                    subjectOr(subject, "changes"));
        }
        if (contains(normalizedDetail, "route", "routing")) {
            return view("Routing", "Routed", ModelActivityState.RESOLVING,
                    subjectOr(subject, "request"));
        }
        if (contains(normalizedDetail, "tool select", "selecting tool", "tool routing", "tool schema")) {
            return view("Selecting", "Selected", ModelActivityState.RESOLVING,
                    subjectOr(subject, "relevant tools"));
        }
        if (contains(normalizedDetail, "memory", "retriev", "context lookup")) {
            return view("Retrieving", "Retrieved", ModelActivityState.INSPECTING,
                    subjectOr(subject, "relevant context"));
        }
        if (contains(normalizedDetail, "skill", "capability")) {
            return view("Selecting", "Selected", ModelActivityState.RESOLVING,
                    subjectOr(subject, "relevant capability"));
        }
        if (contains(normalizedDetail, "format")) {
            return view("Formatting", "Formatted", ModelActivityState.FORMATTING,
                    subjectOr(subject, "final response"));
        }
        if (contains(normalizedDetail, "correct", "repair")) {
            return view("Repairing", "Repaired", ModelActivityState.REPAIRING,
                    subjectOr(subject, "response state"));
        }
        if (contains(normalizedDetail, "compile", "gradle")) {
            return view("Compiling", "Compiled", ModelActivityState.TESTING,
                    subjectOr(subject, "project"));
        }
        if (contains(normalizedDetail, "test", "proof")) {
            return view("Testing", "Tested", ModelActivityState.TESTING,
                    subjectOr(subject, "result"));
        }
        if (contains(normalizedDetail, "validat", "verify", "checking grounded", "registry")) {
            return view("Verifying", "Verified", ModelActivityState.VALIDATING,
                    subjectOr(subject, "result"));
        }
        if (contains(normalizedDetail, "parse", "parsing")) {
            return view("Parsing", "Parsed", ModelActivityState.RESOLVING,
                    subjectOr(subject, "response"));
        }
        if (contains(normalizedDetail, "rank", "ranking")) {
            return view("Ranking", "Ranked", ModelActivityState.COMPARING,
                    subjectOr(subject, "candidates"));
        }
        if (contains(normalizedDetail, "filter", "filtering")) {
            return view("Filtering", "Filtered", ModelActivityState.COMPARING,
                    subjectOr(subject, "results"));
        }
        if (contains(normalizedDetail, "summar", "synthes")) {
            return view("Summarizing", "Summarized", ModelActivityState.WRITING,
                    subjectOr(subject, "evidence"));
        }
        if (contains(normalizedDetail, "compare", "comparing")) {
            return view("Comparing", "Compared", ModelActivityState.COMPARING,
                    subjectOr(subject, "evidence"));
        }
        if (contains(normalizedDetail, "calculat", "comput")) {
            return view("Calculating", "Calculated", ModelActivityState.CALCULATING,
                    subjectOr(subject, "result"));
        }
        if (contains(normalizedDetail, "download")) {
            return view("Downloading", "Downloaded", ModelActivityState.READING,
                    subjectOr(subject, "model data"));
        }
        if (contains(normalizedDetail, "connect", "handshake")) {
            return view("Connecting", "Connected", ModelActivityState.STARTING,
                    subjectOr(subject, "model runtime"));
        }
        if (contains(normalizedDetail, "load", "loading")) {
            return view("Loading", "Loaded", ModelActivityState.STARTING,
                    subjectOr(subject, "model runtime"));
        }
        if (contains(normalizedDetail, "checkpoint", "saving")) {
            return view("Saving", "Saved", ModelActivityState.WRITING,
                    subjectOr(subject, "checkpoint"));
        }
        if (contains(normalizedDetail, "recover", "stalled", "recovery")) {
            return view("Recovering", "Recovered", ModelActivityState.RECOVERING,
                    subjectOr(subject, state == ModelRequestState.PREFILLING ? "user prompt" : "request"));
        }
        if (state == ModelRequestState.PREPARING_CONTEXT && contains(normalizedDetail, "context", "prompt", "system")) {
            return view("Assembling", "Assembled", ModelActivityState.PREPARING, "model context");
        }
        return null;
    }

    private static boolean toolRelevant(ModelRequestState state) {
        return state == ModelRequestState.INSPECTING
                || state == ModelRequestState.SELECTING_TOOL
                || state == ModelRequestState.EXECUTING_TOOL
                || state == ModelRequestState.WAITING_FOR_TOOL_RESULT
                || state == ModelRequestState.OBSERVING_RESULT
                || state == ModelRequestState.VALIDATING;
    }

    private static View view(String label, String completed, ModelActivityState state, String detail) {
        return new View(label, completed, detail, state);
    }

    private static String usefulDetail(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback == null ? "" : fallback;
        String clean = raw.replace('_', ' ').replaceAll("\\s+", " ").strip();
        String normalized = normalize(clean);
        if (normalized.matches("(?:queued|preparing|thinking|reasoning|prefilling|generating|writing|executing tool|waiting|observing result|planning|replanning|validating|finalizing|finishing|completed)")) {
            return fallback == null ? "" : fallback;
        }
        return compact(clean, 110);
    }

    private static boolean isGenericDetail(String value) {
        String normalized = normalize(value);
        return normalized.isBlank()
                || normalized.equals("model context")
                || normalized.equals("through the answer")
                || normalized.equals("user prompt")
                || normalized.equals("available evidence")
                || normalized.equals("response");
    }

    private static String subject(String detail, String activeLabel) {
        if (detail == null || detail.isBlank()) return "";
        String clean = detail.replace('_', ' ').replaceAll("\\s+", " ").strip();
        String label = activeLabel == null ? "" : activeLabel.strip();
        if (!label.isBlank() && clean.regionMatches(true, 0, label, 0, label.length())) {
            clean = clean.substring(label.length()).strip();
        }
        if (clean.startsWith(":")) clean = clean.substring(1).strip();
        return compact(clean, 118);
    }

    private static String subjectOr(String detail, String fallback) {
        if (detail == null || detail.isBlank()) return fallback == null ? "" : fallback;
        return detail;
    }

    private static boolean contains(String value, String... needles) {
        if (value == null || value.isBlank()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) return true;
        }
        return false;
    }

    private static String compact(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (maximum <= 1 || clean.length() <= maximum) return clean;
        return clean.substring(0, maximum - 1).stripTrailing() + "…";
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace('.', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static final class StatusMemory {
        private View current;
        private long currentSince;
        private long lastSeen;
    }

    private record PromptProgress(int total, int processed, int cached, long timestampMillis) {
    }

    private record RealProgress(String text) {
        private RealProgress {
            text = text == null ? "" : text.strip();
        }
    }

    public record HistoryView(
            String label,
            String detail,
            ModelActivityState activityState,
            long ageMillis,
            double alpha
    ) {
        public HistoryView {
            label = label == null ? "" : label.strip();
            detail = detail == null ? "" : detail.strip();
            activityState = activityState == null ? ModelActivityState.IDLE : activityState;
            ageMillis = Math.max(0L, ageMillis);
            alpha = Math.max(0.0D, Math.min(1.0D, alpha));
        }

        public static HistoryView empty() {
            return new HistoryView("", "", ModelActivityState.IDLE, 0L, 0.0D);
        }

        public boolean visible() {
            return !label.isBlank() && alpha > 0.0D;
        }

        public String semanticState() {
            return activityState.id();
        }
    }

    public record View(String label, String completedLabel, String detail, ModelActivityState activityState) {
        public View {
            label = label == null || label.isBlank() ? "Working" : label.strip();
            completedLabel = completedLabel == null || completedLabel.isBlank() ? label : completedLabel.strip();
            detail = detail == null ? "" : detail.strip();
            activityState = activityState == null ? ModelActivityState.IDLE : activityState;
        }

        public View(String label, ModelActivityState activityState) {
            this(label, defaultCompletedLabel(label), "", activityState);
        }

        private static String defaultCompletedLabel(String label) {
            if (label == null || label.isBlank()) return "Working";
            return switch (label) {
                case "Starting" -> "Started";
                case "Preparing" -> "Prepared";
                case "Thinking" -> "Finished thinking";
                case "Searching" -> "Searched";
                case "Inspecting" -> "Inspected";
                case "Reading" -> "Read";
                case "Writing" -> "Written";
                default -> label;
            };
        }

        /** Transitional wire/string adapter for existing consumers. */
        public String semanticState() {
            return activityState.id();
        }

        public String liveText() {
            return detail.isBlank() ? label : label + " " + detail;
        }

        public String completedText() {
            return detail.isBlank() ? completedLabel : completedLabel + " " + detail;
        }
    }
}
