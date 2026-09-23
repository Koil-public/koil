package com.spirit.koil.api.model.chat;

import java.util.List;

/** Focused proof that catalog commands create and dismiss bottom-panel state. */
public final class LocalModelCatalogChatStateProof {
    private LocalModelCatalogChatStateProof() {
    }

    public static void main(String[] args) {
        LocalModelCatalogChatState.close();
        LocalModelCatalogChatState.show(List.of(), 9, "Catalog Search: qwen", "Searching...");
        var snapshot = LocalModelCatalogChatState.snapshot();
        require(snapshot != null, "catalog panel did not open");
        require(snapshot.page() == 1 && snapshot.pageCount() == 1 && snapshot.totalEntries() == 0,
                "empty catalog panel did not clamp page state");
        require("Catalog Search: qwen".equals(snapshot.title()) && "Searching...".equals(snapshot.detail()),
                "catalog panel did not preserve command presentation");
        LocalModelCatalogChatState.detail("Complete.");
        require("Complete.".equals(LocalModelCatalogChatState.snapshot().detail()), "catalog panel did not refresh status");
        LocalModelCatalogChatState.close();
        require(LocalModelCatalogChatState.snapshot() == null, "catalog panel did not close");
        System.out.println("Local model catalog chat-panel state proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
