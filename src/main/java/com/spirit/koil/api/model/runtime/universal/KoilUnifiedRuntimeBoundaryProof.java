package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.LocalModelProvider;
import com.spirit.koil.api.model.LocalModelRuntimeManager;
import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelCapabilityDescriptor;
import com.spirit.koil.api.model.ModelHealthSnapshot;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Fast proof for the one-provider boundary and generalized model-session state ownership. */
public final class KoilUnifiedRuntimeBoundaryProof {
    private KoilUnifiedRuntimeBoundaryProof() {
    }

    public static void main(String[] args) {
        FakeAdapter adapter = new FakeAdapter("llama_cpp");
        KoilUnifiedLocalModelProvider unified = new KoilUnifiedLocalModelProvider(adapter);
        require(KoilUnifiedLocalModelProvider.ID.equals(unified.id()), "outer provider identity leaked adapter id");
        require("llama_cpp".equals(unified.executionAdapter().id()), "execution adapter identity was lost");
        require("openai_tool_calls".equals(unified.executionAdapter().toolProtocol()), "tool protocol mapping changed");
        require("llama_cpp".equals(unified.health().diagnostics().get("executionAdapter")),
                "health diagnostics did not preserve internal adapter identity");

        try (LocalModelRuntimeManager manager = new LocalModelRuntimeManager(2)) {
            manager.registerProvider(unified);
            require(KoilUnifiedLocalModelProvider.ID.equals(manager.selectedProviderId()),
                    "runtime manager did not expose the unified provider");
            require("llama_cpp".equals(manager.selectedExecutionAdapterId()),
                    "runtime manager did not expose adapter diagnostics");
            boolean rejected = false;
            try {
                manager.registerProvider(new KoilUnifiedLocalModelProvider(new FakeAdapter("colibri")));
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            require(rejected, "runtime manager accepted a second outer inference provider");
        }

        KoilExecutionAdapterDescriptor descriptor = new KoilExecutionAdapterDescriptor(
                "llama_cpp", "openai_tool_calls", false, Map.of());
        KoilModelSession session = new KoilModelSession(null,
                new KoilExecutionPlan(descriptor, "proof", Map.of("backend", "cpu")));
        session.stateStore().put(new KoilModelStateStore.StateHandle(
                KoilModelStateKind.SSM, "ssm-state-0", 4096L, Map.of("layer", "0")));
        session.stateStore().put(new KoilModelStateStore.StateHandle(
                KoilModelStateKind.CONVOLUTION, "conv-state-0", 1024L, Map.of()));
        require(session.stateStore().estimatedBytes() == 5120L, "generalized state accounting failed");
        require(session.stateStore().get(KoilModelStateKind.ATTENTION_KV).isEmpty(),
                "non-transformer session was forced to own a KV cache");
        session.close();
        require(session.stateStore().snapshot().isEmpty(), "session close did not release model state");

        System.out.println("Koil unified runtime boundary proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class FakeAdapter implements LocalModelProvider {
        private final String id;

        private FakeAdapter(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public ModelCapabilityDescriptor capabilities() {
            return new ModelCapabilityDescriptor(true, true, true, true, true, 8192, Set.of("chat"));
        }

        @Override
        public ModelHealthSnapshot health() {
            return new ModelHealthSnapshot(ModelHealthState.READY, "proof", 0, Instant.now(), Map.of());
        }

        @Override
        public CompletableFuture<ModelHealthSnapshot> start() {
            return CompletableFuture.completedFuture(health());
        }

        @Override
        public ModelCancellationHandle generate(StreamingModelRequest request, StreamingModelObserver observer) {
            throw new UnsupportedOperationException("generation is not needed by this proof");
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
