package com.spirit.koil.api.telemetry;

/** Machine-readable operation kinds used by the Automation Workspace timeline/topology. */
public enum TelemetrySpanKind {
    REQUEST,
    MODEL_PASS,
    MODEL_STARTUP,
    PROMPT_INGESTION,
    PREFILL,
    CONTEXT,
    PLANNING,
    AUTOMATION_PLAN,
    TOOL_INVOCATION,
    TOOL_DISPATCH,
    SKILL_SELECTION,
    SKILL_INVOCATION,
    MAIN_THREAD,
    EXECUTOR_TASK,
    KTL_ACTION,
    APPROVAL,
    SERIALIZATION,
    MODEL_CONTINUATION,
    TOKEN_GENERATION,
    OUTPUT,
    QUEUE_WAIT,
    VALIDATION,
    RETRIEVAL,
    OTHER
}
