package com.spirit.koil.api.model;

/** Brigadier contract proof for the single model command surface. */
public final class LocalModelCommandBridgeProof {
    private LocalModelCommandBridgeProof() {
    }

    public static void main(String[] args) {
        var root = LocalModelCommandBridge.modelCommand().build();
        var list = root.getChild("list");
        var catalog = root.getChild("catalog");
        require(root.getCommand() != null, "bare /model status is missing");
        require(LocalModelCommandBridge.knowledgeStatus().equals("Knowledge: not initialized"),
                "knowledge status must not initialize retrieval as a diagnostic side effect");
        require(root.getChild("setup") == null, "/model setup must not be registered");
        require(root.getChild("install-url") != null && root.getChild("install-url").getChild("url") != null,
                "/model install-url <url> is missing");
        require(list != null && list.getCommand() != null && list.getChild("page") != null,
                "/model list pagination is missing");
        require(catalog != null && catalog.getCommand() != null && catalog.getChild("refresh") != null
                        && catalog.getChild("search") != null
                        && catalog.getChild("search").getChild("query") != null
                        && catalog.getChild("search-page") != null
                        && catalog.getChild("search-page").getChild("page") != null
                        && catalog.getChild("search-page").getChild("page").getChild("query") != null,
                "/model catalog search <query> is missing");
        System.out.println("Local model command bridge proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
