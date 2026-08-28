package com.spirit.koil.api.model;

/** Brigadier contract proof for the plural model-discovery command surface. */
public final class LocalModelCommandBridgeProof {
    private LocalModelCommandBridgeProof() {
    }

    public static void main(String[] args) {
        var root = LocalModelCommandBridge.modelsCommand().build();
        var list = root.getChild("list");
        var catalog = root.getChild("catalog");
        require(root.getCommand() != null, "bare /models no longer opens setup");
        require(root.getChild("setup") != null, "/models setup is missing");
        require(root.getChild("install-url") != null && root.getChild("install-url").getChild("url") != null,
                "/models install-url <url> is missing");
        require(list != null && list.getCommand() != null && list.getChild("page") != null,
                "/models list pagination is missing");
        require(catalog != null && catalog.getChild("refresh") != null
                        && catalog.getChild("search") != null
                        && catalog.getChild("search").getChild("query") != null
                        && catalog.getChild("search-page") != null
                        && catalog.getChild("search-page").getChild("page") != null
                        && catalog.getChild("search-page").getChild("page").getChild("query") != null,
                "/models catalog search <query> is missing");
        System.out.println("Local model command bridge proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
