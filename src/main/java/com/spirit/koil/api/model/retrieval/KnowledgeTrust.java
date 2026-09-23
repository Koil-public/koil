package com.spirit.koil.api.model.retrieval;

/** Provenance labels prevent retrieved text from being mistaken for current authority. */
public enum KnowledgeTrust {
    SYSTEM_POLICY,
    CURRENT_OBSERVATION,
    TOOL_RESULT,
    KOIL_DOCUMENTATION,
    DOCUMENTATION,
    USER_MEMORY,
    WORKSPACE_CONTENT,
    UNTRUSTED_RETRIEVED,
    HISTORICAL_CONTEXT
}
