package com.spirit.koil.api.model.catalog;

import java.net.URI;
import java.util.List;

/** Persistent Automation quarantine contract proof. */
public final class LocalModelReliabilityProof {
    private LocalModelReliabilityProof() {
    }

    public static void main(String[] args) {
        String id = "proof-tool-model";
        LocalModelCatalogEntry entry = new LocalModelCatalogEntry(
                id, "Proof Tool Model 3B", "llama_cpp", "llama_cpp", id,
                "3B", "Q4_K_M", "proof", 4096, 1, 1, 50, true,
                List.of(LocalModelCapabilityTag.CHAT, LocalModelCapabilityTag.AUTOMATION_TOOLS),
                "proof", List.of(new ModelArtifact(
                        "proof.gguf", URI.create("https://example.invalid/proof.gguf"), 1, "a".repeat(64)
                ))
        );
        LocalModelReliabilityStore.reset(entry);
        require(LocalModelAutomationEligibility.supportsAutomationTools(entry), "capable model started blocked");
        LocalModelReliabilityStore.recordProtocolFailure(id, "empty_response", "first");
        LocalModelReliabilityStore.recordProtocolFailure(id, "empty_response", "second");
        require(LocalModelAutomationEligibility.supportsAutomationTools(entry), "model quarantined before threshold");
        LocalModelReliabilityStore.recordProtocolFailure(id, "model_reasoning_loop", "third");
        require(!LocalModelAutomationEligibility.supportsAutomationTools(entry), "repeated major failures were forgotten");
        require(LocalModelReliabilityStore.reset(entry), "reliability reset did not clear evidence");
        require(LocalModelAutomationEligibility.supportsAutomationTools(entry), "reset did not restore tool eligibility");
        LocalModelReliabilityStore.recordCrash(id, "sidecar crash");
        require(!LocalModelAutomationEligibility.supportsAutomationTools(entry), "runtime crash did not quarantine Automation");
        LocalModelReliabilityStore.reset(entry);
        System.out.println("Local model reliability proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
