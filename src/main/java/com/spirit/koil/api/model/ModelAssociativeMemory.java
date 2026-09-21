package com.spirit.koil.api.model;

import com.spirit.koil.api.model.retrieval.KnowledgeFilter;
import com.spirit.koil.api.model.retrieval.KnowledgeQuery;
import com.spirit.koil.api.model.retrieval.KnowledgeTrust;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.model.retrieval.KoilRetrievalEngine;

import java.util.concurrent.TimeUnit;

/** Transitional API for old callers; authority and persistence live only in {@link KoilRetrievalEngine}. */
final class ModelAssociativeMemory {
    private ModelAssociativeMemory() {
    }

    static void remember(String prompt, String answer) {
        remember(prompt, answer, "");
    }

    static void remember(String prompt, String answer, String sessionId) {
        KoilRetrievalEngine current = KoilKnowledgeRuntime.shared().orElse(null);
        if (current == null) return;
        current.rememberConversation(prompt, answer, sessionId).whenComplete((ignored, failure) -> {
            if (failure != null) LocalModelRuntimeLog.write("knowledge_remember_failed", concise(failure));
        });
    }

    static String relevantContext(String prompt) {
        KoilRetrievalEngine current = KoilKnowledgeRuntime.shared().orElse(null);
        if (current == null || prompt == null || prompt.isBlank()) return "";
        try {
            return current.retrieve(new KnowledgeQuery(prompt, KnowledgeFilter.any(), 30, 768,
                    "model-memory", KnowledgeTrust.HISTORICAL_CONTEXT)).get(2L, TimeUnit.SECONDS).contextText();
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("knowledge_retrieval_failed", concise(failure));
            return "";
        }
    }

    static synchronized void close() {
        KoilKnowledgeRuntime.close();
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
