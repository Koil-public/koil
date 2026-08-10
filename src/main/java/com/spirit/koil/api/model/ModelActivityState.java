package com.spirit.koil.api.model;

import java.util.Locale;

/**
 * Typed, provider-neutral activity meaning shared by model, Automation,
 * presence, and Rich Chat presentation. Strings are accepted only at legacy
 * connector boundaries through {@link #fromLegacy(String)}.
 */
public enum ModelActivityState {
    STARTING,
    PREPARING,
    THINKING,
    RESOLVING,
    DISCOVERING,
    INSPECTING,
    SEARCHING,
    READING,
    COMPARING,
    CALCULATING,
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    NAVIGATING,
    ORIENTING,
    SPRINTING,
    SWIMMING,
    CLIMBING,
    PARKOUR,
    RIDING,
    GLIDING,
    INTERACTING,
    USING_ITEM,
    EATING,
    MINING,
    BUILDING,
    ATTACKING,
    OBSERVING,
    VALIDATING,
    TESTING,
    REPAIRING,
    RETRYING,
    RECOVERING,
    REPLANNING,
    EDITING,
    FORMATTING,
    WRITING,
    FINALIZING,
    COMPLETE,
    ALREADY_SATISFIED,
    PARTIAL,
    BLOCKED,
    FAILED,
    INTERRUPTED,
    CANCELLED,
    IDLE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return this == COMPLETE || this == ALREADY_SATISFIED || this == PARTIAL || this == BLOCKED
                || this == FAILED || this == INTERRUPTED || this == CANCELLED;
    }

    public static ModelActivityState fromLegacy(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT)
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.isBlank() || normalized.equals("idle")) return IDLE;
        try {
            return valueOf(normalized.replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // Compatibility inference is intentionally confined to this adapter.
        }
        if (contains(normalized, "fail", "error", "crash")) return FAILED;
        if (contains(normalized, "cancel", "stopped")) return CANCELLED;
        if (contains(normalized, "interrupt")) return INTERRUPTED;
        if (contains(normalized, "partial")) return PARTIAL;
        if (contains(normalized, "block", "unsupported", "denied", "impossible", "not found", "missing")) return BLOCKED;
        if (contains(normalized, "already satisfied", "already complete", "already reached")) return ALREADY_SATISFIED;
        if (contains(normalized, "complete", "success", "done") || normalized.equals("ok")) return COMPLETE;
        if (contains(normalized, "replan", "revise")) return REPLANNING;
        if (contains(normalized, "recover")) return RECOVERING;
        if (contains(normalized, "retry")) return RETRYING;
        if (contains(normalized, "repair", "correct")) return REPAIRING;
        if (contains(normalized, "valid", "verify", "check result", "fact check")) return VALIDATING;
        if (contains(normalized, "test", "compile", "proof")) return TESTING;
        if (contains(normalized, "format")) return FORMATTING;
        if (contains(normalized, "edit", "modify", "file change")) return EDITING;
        if (contains(normalized, "writ", "answer")) return WRITING;
        if (contains(normalized, "final", "finish")) return FINALIZING;
        if (contains(normalized, "attack", "combat", "kill")) return ATTACKING;
        if (contains(normalized, "build", "place block", "construct")) return BUILDING;
        if (contains(normalized, "mine", "dig", "break block")) return MINING;
        if (contains(normalized, "eat", "consume", "food")) return EATING;
        if (contains(normalized, "use item", "drink")) return USING_ITEM;
        if (contains(normalized, "interact", "right click", "use block")) return INTERACTING;
        if (contains(normalized, "elytra", "glid")) return GLIDING;
        if (contains(normalized, "ride", "mount", "boat")) return RIDING;
        if (contains(normalized, "parkour", "jump gap", "landing")) return PARKOUR;
        if (contains(normalized, "climb", "ladder", "vine")) return CLIMBING;
        if (contains(normalized, "swim", "water exit")) return SWIMMING;
        if (contains(normalized, "sprint", "running")) return SPRINTING;
        if (contains(normalized, "look", "camera", "orient", "face")) return ORIENTING;
        if (contains(normalized, "move", "moving", "walk", "path", "navigate", "route", "steer")) return NAVIGATING;
        if (contains(normalized, "execute", "primitive", "run tool")) return EXECUTING;
        if (contains(normalized, "approval", "confirm", "permission")) return AWAITING_APPROVAL;
        if (contains(normalized, "plan")) return PLANNING;
        if (contains(normalized, "calculat", "math")) return CALCULATING;
        if (contains(normalized, "compar")) return COMPARING;
        if (contains(normalized, "read")) return READING;
        if (contains(normalized, "search", "scan", "find")) return SEARCHING;
        if (contains(normalized, "inspect", "examin", "check state")) return INSPECTING;
        if (contains(normalized, "discover")) return DISCOVERING;
        if (contains(normalized, "resolv", "parse")) return RESOLVING;
        if (contains(normalized, "prepare", "context", "prefill")) return PREPARING;
        if (contains(normalized, "start", "initial", "queue", "wait for runtime")) return STARTING;
        if (contains(normalized, "observ", "result", "wait")) return OBSERVING;
        return THINKING;
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }
}
