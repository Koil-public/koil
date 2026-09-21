package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelToolCall;

import java.util.Locale;

/**
 * Typed semantic projection for a registered model tool while it is running.
 *
 * <p>The presentation deliberately uses literal, task-oriented verbs. It does not invent
 * activity for animation. The active/completed labels are selected from the registered tool
 * id and the tool's real arguments so long waits remain informative without becoming noisy
 * or theatrical.</p>
 */
public final class ModelToolActivityPresentation {
    private ModelToolActivityPresentation() {
    }

    public static Activity activity(ModelToolCall call) {
        String toolId = call == null ? "" : call.toolId().strip().toLowerCase(Locale.ROOT);
        String subject = primaryArgument(call);
        if (subject.isBlank()) subject = ModelToolCallPresentation.callSummary(call);
        return activity(toolId, subject);
    }

    private static String primaryArgument(ModelToolCall call) {
        if (call == null || call.arguments() == null) return "";
        for (String key : new String[]{
                "query", "path", "url", "target", "item", "command", "symbol", "name",
                "file", "directory", "root", "pattern", "task", "objective", "repository"
        }) {
            try {
                var value = call.arguments().get(key);
                if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                    String text = value.getAsString();
                    if (text != null && !text.isBlank()) return text.strip();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    public static Activity activity(String toolId, String detail) {
        String normalizedToolId = normalize(toolId);
        String rawDetail = detail == null ? "" : detail.strip();
        Activity codeActivity = codeActivity(normalizedToolId, rawDetail);
        if (codeActivity != null) return codeActivity;

        ModelActivityState state = state(normalizedToolId);
        String summary = liveSubject(normalizedToolId, rawDetail, state);
        if (summary.isBlank()) summary = ModelToolCallPresentation.toolName(normalizedToolId);
        Verb verb = verb(normalizedToolId, state);
        return new Activity(state, summary.isBlank() ? "action" : summary, verb.active(), verb.completed());
    }

    private static String liveSubject(String toolId, String detail, ModelActivityState state) {
        String value = detail == null ? "" : detail.strip();
        String lower = value.toLowerCase(Locale.ROOT);
        String argument = value;
        for (String prefix : new String[]{
                "query ", "path ", "root ", "value ", "target ", "item ", "command ",
                "url ", "file ", "directory ", "pattern ", "symbol ", "name "
        }) {
            if (lower.startsWith(prefix)) {
                argument = value.substring(prefix.length()).strip();
                break;
            }
        }

        if (toolId.equals("internet.search")) return argument.isBlank() ? "websites" : "websites for " + compact(argument);
        if (toolId.equals("internet.crawl")) return argument.isBlank() ? "web sources" : "web sources for " + compact(argument);
        if (toolId.equals("internet.fetch")) return argument.isBlank() ? "web page" : "web page " + compact(argument);
        if (toolId.equals("internet.scrape")) return argument.isBlank() ? "web page contents" : "web page contents from " + compact(argument);
        if (toolId.equals("workspace.read")) return argument.isBlank() ? "project file" : "project file " + compact(argument);
        if (toolId.equals("workspace.search")) return argument.isBlank() ? "project files" : "project files for " + compact(argument);
        if (toolId.equals("workspace.list")) return argument.isBlank() ? "project directory" : "project directory " + compact(argument);
        if (toolId.equals("workspace.stat")) return argument.isBlank() ? "project path" : "project path " + compact(argument);
        if (toolId.equals("workspace.roots")) return "available project roots";
        if (toolId.startsWith("workspace.") && state == ModelActivityState.EDITING) {
            return argument.isBlank() ? "project files" : "project file " + compact(argument);
        }
        if (contains(toolId, "documentation", "docs")) return argument.isBlank() ? "documentation" : "documentation for " + compact(argument);
        if (contains(toolId, "knowledge", "retrieval", "memory")) return argument.isBlank() ? "relevant information" : "information for " + compact(argument);
        if (contains(toolId, "download")) return argument.isBlank() ? "model data" : compact(argument);
        if (contains(toolId, "upload")) return argument.isBlank() ? "artifact" : compact(argument);
        if (contains(toolId, "compile", "gradle", "build")) return argument.isBlank() ? "project" : compact(argument);
        if (contains(toolId, "test", "proof")) return argument.isBlank() ? "validation checks" : compact(argument);
        if (contains(toolId, "command", "process", "shell", "exec", ".run")) return argument.isBlank() ? "command" : compact(argument);
        return compact(value);
    }

    private static Activity codeActivity(String toolId, String detail) {
        String argument = detail == null ? "" : detail.strip();
        String lower = argument.toLowerCase(Locale.ROOT);
        if (lower.startsWith("query ")) argument = argument.substring(6).strip();
        String compactArgument = compact(argument);
        return switch (toolId) {
            case "code.architecture" -> activity(ModelActivityState.INSPECTING, "project architecture", "Mapping", "Mapped");
            case "code.symbols" -> activity(ModelActivityState.SEARCHING,
                    compactArgument.isBlank() ? "code symbols" : "code symbols for " + compactArgument,
                    "Locating", "Located");
            case "code.search" -> activity(ModelActivityState.SEARCHING,
                    compactArgument.isBlank() ? "source code" : "source code for " + compactArgument,
                    "Searching", "Searched");
            case "code.semantic_search" -> activity(ModelActivityState.SEARCHING,
                    compactArgument.isBlank() ? "code by intent" : "code by intent for " + compactArgument,
                    "Searching", "Searched");
            case "code.trace" -> activity(ModelActivityState.INSPECTING, "callers and dependencies", "Tracing", "Traced");
            case "code.impact" -> activity(ModelActivityState.VALIDATING, "change impact", "Assessing", "Assessed");
            case "code.changes" -> activity(ModelActivityState.VALIDATING, "changed symbols", "Reviewing", "Reviewed");
            case "code.snippet" -> activity(ModelActivityState.READING,
                    compactArgument.isBlank() ? "code snippet" : "code snippet " + compactArgument,
                    "Reading", "Read");
            case "code.schema" -> activity(ModelActivityState.INSPECTING, "code graph", "Mapping", "Mapped");
            case "code.query" -> activity(ModelActivityState.INSPECTING, "code graph", "Querying", "Queried");
            default -> null;
        };
    }

    private static Activity activity(ModelActivityState state, String detail, String active, String completed) {
        return new Activity(state, detail, active, completed);
    }

    private static Verb verb(String toolId, ModelActivityState state) {
        if (toolId.isBlank()) return defaultVerb(state);

        if (contains(toolId, "internet.search", ".search", "find")) return verb("Searching", "Searched");
        if (contains(toolId, "crawl")) return verb("Crawling", "Crawled");
        if (contains(toolId, "fetch")) return verb("Fetching", "Fetched");
        if (contains(toolId, "scrape", "extract")) return verb("Extracting", "Extracted");
        if (contains(toolId, ".read", "documentation", "docs")) return verb("Reading", "Read");
        if (contains(toolId, ".list")) return verb("Listing", "Listed");
        if (contains(toolId, ".stat", "inspect", ".info", ".state")) return verb("Inspecting", "Inspected");
        if (contains(toolId, ".roots", "catalog", "discover")) return verb("Discovering", "Discovered");
        if (contains(toolId, "knowledge", "retrieval", "memory")) return verb("Retrieving", "Retrieved");
        if (contains(toolId, "architecture", "schema", "graph")) return verb("Mapping", "Mapped");
        if (contains(toolId, "symbol")) return verb("Locating", "Located");
        if (contains(toolId, "trace")) return verb("Tracing", "Traced");
        if (contains(toolId, "impact")) return verb("Assessing", "Assessed");
        if (contains(toolId, "changes", "review")) return verb("Reviewing", "Reviewed");
        if (contains(toolId, "query")) return verb("Querying", "Queried");
        if (contains(toolId, "tokeniz")) return verb("Tokenizing", "Tokenized");
        if (contains(toolId, "index")) return verb("Indexing", "Indexed");
        if (contains(toolId, "scan")) return verb("Scanning", "Scanned");
        if (contains(toolId, "enumerat")) return verb("Enumerating", "Enumerated");
        if (contains(toolId, "decode")) return verb("Decoding", "Decoded");
        if (contains(toolId, "encode")) return verb("Encoding", "Encoded");
        if (contains(toolId, "normaliz")) return verb("Normalizing", "Normalized");
        if (contains(toolId, "deduplicat")) return verb("Deduplicating", "Deduplicated");
        if (contains(toolId, "merge")) return verb("Merging", "Merged");
        if (contains(toolId, "sync")) return verb("Synchronizing", "Synchronized");
        if (contains(toolId, "warmup", "warm")) return verb("Warming", "Warmed");
        if (contains(toolId, "dispatch")) return verb("Dispatching", "Dispatched");
        if (contains(toolId, "apply")) return verb("Applying", "Applied");
        if (contains(toolId, "parse")) return verb("Parsing", "Parsed");
        if (contains(toolId, "route")) return verb("Routing", "Routed");
        if (contains(toolId, "select")) return verb("Selecting", "Selected");
        if (contains(toolId, "rank")) return verb("Ranking", "Ranked");
        if (contains(toolId, "filter")) return verb("Filtering", "Filtered");
        if (contains(toolId, "summar")) return verb("Summarizing", "Summarized");
        if (contains(toolId, "compare")) return verb("Comparing", "Compared");
        if (contains(toolId, "calculat", "math")) return verb("Calculating", "Calculated");
        if (contains(toolId, "validation", "validate", "verify")) return verb("Verifying", "Verified");
        if (contains(toolId, "proof", "test")) return verb("Testing", "Tested");
        if (contains(toolId, "compile", "gradle")) return verb("Compiling", "Compiled");
        if (contains(toolId, "download")) return verb("Downloading", "Downloaded");
        if (contains(toolId, "upload")) return verb("Uploading", "Uploaded");
        if (contains(toolId, "connect")) return verb("Connecting", "Connected");
        if (contains(toolId, "checkpoint", "save")) return verb("Saving", "Saved");
        if (contains(toolId, "restore")) return verb("Restoring", "Restored");
        if (contains(toolId, "load")) return verb("Loading", "Loaded");
        if (contains(toolId, "cache")) return verb("Caching", "Cached");
        if (contains(toolId, "workspace.write")) return verb("Writing", "Written");
        if (contains(toolId, "workspace.append", "workspace.edit")) return verb("Updating", "Updated");
        if (contains(toolId, "workspace.create", "workspace.mkdir")) return verb("Creating", "Created");
        if (contains(toolId, "workspace.copy")) return verb("Copying", "Copied");
        if (contains(toolId, "workspace.move")) return verb("Moving", "Moved");
        if (contains(toolId, "workspace.delete", "remove")) return verb("Removing", "Removed");
        if (contains(toolId, "command", "process", "shell", "exec", ".run")) return verb("Running", "Ran");
        if (contains(toolId, "plan") && !contains(toolId, "replan")) return verb("Planning", "Planned");
        if (contains(toolId, "replan")) return verb("Replanning", "Replanned");
        if (contains(toolId, "recover")) return verb("Recovering", "Recovered");
        if (contains(toolId, "repair")) return verb("Repairing", "Repaired");
        if (contains(toolId, "open")) return verb("Opening", "Opened");
        return defaultVerb(state);
    }

    private static Verb defaultVerb(ModelActivityState state) {
        ModelActivityState safe = state == null ? ModelActivityState.EXECUTING : state;
        return switch (safe) {
            case STARTING -> verb("Starting", "Started");
            case PREPARING -> verb("Preparing", "Prepared");
            case THINKING -> verb("Thinking", "Finished thinking");
            case RESOLVING -> verb("Resolving", "Resolved");
            case DISCOVERING -> verb("Discovering", "Discovered");
            case INSPECTING -> verb("Inspecting", "Inspected");
            case SEARCHING -> verb("Searching", "Searched");
            case READING -> verb("Reading", "Read");
            case COMPARING -> verb("Comparing", "Compared");
            case CALCULATING -> verb("Calculating", "Calculated");
            case PLANNING -> verb("Planning", "Planned");
            case AWAITING_APPROVAL -> verb("Waiting", "Received");
            case EXECUTING -> verb("Executing", "Executed");
            case NAVIGATING -> verb("Navigating", "Navigated");
            case ORIENTING -> verb("Orienting", "Oriented");
            case SPRINTING -> verb("Sprinting", "Sprinted");
            case SWIMMING -> verb("Swimming", "Swam");
            case CLIMBING -> verb("Climbing", "Climbed");
            case PARKOUR -> verb("Traversing", "Traversed");
            case RIDING -> verb("Riding", "Rode");
            case GLIDING -> verb("Gliding", "Glided");
            case INTERACTING -> verb("Interacting", "Interacted");
            case USING_ITEM -> verb("Using", "Used");
            case EATING -> verb("Eating", "Ate");
            case MINING -> verb("Mining", "Mined");
            case BUILDING -> verb("Building", "Built");
            case ATTACKING -> verb("Attacking", "Attacked");
            case OBSERVING -> verb("Reviewing", "Reviewed");
            case VALIDATING -> verb("Verifying", "Verified");
            case TESTING -> verb("Testing", "Tested");
            case REPAIRING -> verb("Repairing", "Repaired");
            case RETRYING -> verb("Retrying", "Retried");
            case RECOVERING -> verb("Recovering", "Recovered");
            case REPLANNING -> verb("Replanning", "Replanned");
            case EDITING -> verb("Updating", "Updated");
            case FORMATTING -> verb("Formatting", "Formatted");
            case WRITING -> verb("Writing", "Written");
            case FINALIZING -> verb("Finalizing", "Finalized");
            case COMPLETE, ALREADY_SATISFIED -> verb("Complete", "Completed");
            case PARTIAL -> verb("Partial", "Partially completed");
            case BLOCKED -> verb("Blocked", "Blocked");
            case FAILED -> verb("Failed", "Failed");
            case INTERRUPTED -> verb("Interrupted", "Interrupted");
            case CANCELLED -> verb("Cancelled", "Cancelled");
            case IDLE -> verb("Idle", "Idle");
        };
    }

    private static ModelActivityState state(String toolId) {
        if (toolId.isBlank()) return ModelActivityState.EXECUTING;
        if (contains(toolId, "replan", "recover", "repair")) return ModelActivityState.REPLANNING;
        if (contains(toolId, "proof", "test", "compile", "gradle")) return ModelActivityState.TESTING;
        if (contains(toolId, "validation", "validate", "verify", "impact", "changes")) return ModelActivityState.VALIDATING;
        if (toolId.equals("automation.plan") || toolId.endsWith(".plan")) return ModelActivityState.PLANNING;
        if (contains(toolId, "internet.search", "internet.crawl", "mcp-catalogue", ".search", "skill_catalog", "find", "symbol")) return ModelActivityState.SEARCHING;
        if (contains(toolId, "documentation", "internet.fetch", ".read", "snippet")) return ModelActivityState.READING;
        if (contains(toolId, "internet.scrape", "extract", "trace", "architecture", "schema", "query")) return ModelActivityState.INSPECTING;
        if (contains(toolId, "knowledge", "command_syntax", "inspect", ".info", ".state", ".list", ".stat", ".roots", "catalog")) {
            return ModelActivityState.INSPECTING;
        }
        if (contains(toolId, "workspace.write", "workspace.append", "workspace.create", "workspace.mkdir",
                "workspace.copy", "workspace.move", "workspace.delete", "workspace.edit")) {
            return ModelActivityState.EDITING;
        }
        if (contains(toolId, "elytra", "glid")) return ModelActivityState.GLIDING;
        if (contains(toolId, "boat", "mount", "dismount", "rid")) return ModelActivityState.RIDING;
        if (contains(toolId, "swim")) return ModelActivityState.SWIMMING;
        if (contains(toolId, "climb", "ladder", "vine")) return ModelActivityState.CLIMBING;
        if (contains(toolId, "parkour", "jump")) return ModelActivityState.PARKOUR;
        if (contains(toolId, "sprint")) return ModelActivityState.SPRINTING;
        if (contains(toolId, "movement", "move_to", "walk", "navigate", "travel")) return ModelActivityState.NAVIGATING;
        if (contains(toolId, "look", "orient", "camera")) return ModelActivityState.ORIENTING;
        if (contains(toolId, "eat")) return ModelActivityState.EATING;
        if (contains(toolId, "use_item")) return ModelActivityState.USING_ITEM;
        if (contains(toolId, "mine", "break_block")) return ModelActivityState.MINING;
        if (contains(toolId, "build", "place")) return ModelActivityState.BUILDING;
        if (contains(toolId, "attack", "kill", "combat")) return ModelActivityState.ATTACKING;
        if (contains(toolId, "interact", "container", "inventory")) return ModelActivityState.INTERACTING;
        return ModelActivityState.EXECUTING;
    }

    private static Verb verb(String active, String completed) {
        return new Verb(active, completed);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        int maximum = 96;
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1).stripTrailing() + "…";
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private record Verb(String active, String completed) {
        private Verb {
            active = active == null || active.isBlank() ? "Executing" : active;
            completed = completed == null || completed.isBlank() ? active : completed;
        }
    }

    public record Activity(
            ModelActivityState state,
            String detail,
            String activeLabel,
            String completedLabel
    ) {
        public Activity(ModelActivityState state, String detail) {
            this(state, detail, defaultVerb(state).active(), defaultVerb(state).completed());
        }

        public Activity {
            state = state == null ? ModelActivityState.EXECUTING : state;
            detail = detail == null ? "" : detail;
            Verb fallback = defaultVerb(state);
            activeLabel = activeLabel == null || activeLabel.isBlank() ? fallback.active() : activeLabel.strip();
            completedLabel = completedLabel == null || completedLabel.isBlank() ? fallback.completed() : completedLabel.strip();
        }
    }
}
