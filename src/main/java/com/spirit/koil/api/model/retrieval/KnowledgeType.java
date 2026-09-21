package com.spirit.koil.api.model.retrieval;

/** Stable categories for retrieval metadata; sources may be registered incrementally. */
public enum KnowledgeType {
    CONVERSATION,
    EPISODIC,
    AUTOMATION,
    AUTOMATION_FAILURE,
    TASK,
    KTL,
    TOOL,
    KOIL_DOCUMENTATION,
    MINECRAFT_KNOWLEDGE,
    WORKSPACE,
    SOURCE_CODE,
    LOG,
    CONFIG,
    REASONING_MEMO,
    EXTERNAL_EVIDENCE,
    REPAIR_EPISODE,
    COUNTEREXAMPLE,
    WORKFLOW_RECIPE,
    ENVIRONMENT_FACT,
    PERFORMANCE_OBSERVATION,
    USER_KNOWLEDGE
}
