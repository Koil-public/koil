package com.spirit.koil.api.screen;

/** Confirms /screen factories are dynamic rather than a command-owned ID list. */
public final class KoilRemoteScreenRegistryProof {
    private KoilRemoteScreenRegistryProof() {
    }

    public static void main(String[] args) {
        String id = "proof:custom_screen";
        KoilRemoteScreenRegistry.register(id, (client, parent, data) -> parent);
        require(KoilRemoteScreenRegistry.registeredIds().contains(id), "registered screen id was not exposed");
        System.out.println("Remote screen registry proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
