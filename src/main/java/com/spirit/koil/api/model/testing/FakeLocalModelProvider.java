package com.spirit.koil.api.model.testing;

import com.spirit.koil.api.model.LocalModelProvider;
import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelCapabilityDescriptor;
import com.spirit.koil.api.model.ModelHealthSnapshot;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;
import com.spirit.koil.api.model.StreamingModelResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FakeLocalModelProvider implements LocalModelProvider {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "koil-fake-model-provider");
        thread.setDaemon(true);
        return thread;
    });
    private final long chunkDelayMillis;
    private volatile ModelHealthSnapshot health = ModelHealthSnapshot.stopped();
    private volatile ModelToolCall nextToolCall;
    private volatile String fixedResponse = "";
    private volatile boolean closed;

    public FakeLocalModelProvider(long chunkDelayMillis) {
        this.chunkDelayMillis = Math.max(0L, chunkDelayMillis);
    }

    public void fixedResponse(String response) {
        this.fixedResponse = response == null ? "" : response;
    }

    public void nextToolCall(ModelToolCall call) {
        this.nextToolCall = call;
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public ModelCapabilityDescriptor capabilities() {
        return new ModelCapabilityDescriptor(true, true, true, true, false, 32_768, Set.of("test"));
    }

    @Override
    public ModelHealthSnapshot health() {
        return this.health;
    }

    @Override
    public CompletableFuture<ModelHealthSnapshot> start() {
        if (this.closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("fake provider is closed"));
        }
        this.health = new ModelHealthSnapshot(ModelHealthState.READY, "fake provider ready", 0, Instant.now(), Map.of());
        return CompletableFuture.completedFuture(this.health);
    }

    @Override
    public ModelCancellationHandle generate(StreamingModelRequest request, StreamingModelObserver observer) {
        FakeCancellation cancellation = new FakeCancellation();
        this.worker.execute(() -> generateOnWorker(request, observer, cancellation));
        return cancellation;
    }

    private void generateOnWorker(
            StreamingModelRequest request,
            StreamingModelObserver observer,
            FakeCancellation cancellation
    ) {
        try {
            observer.onState(request.id(), ModelRequestState.PREPARING_CONTEXT, "preparing fake context");
            if (cancelled(request, observer, cancellation)) {
                return;
            }
            observer.onState(request.id(), ModelRequestState.PREFILLING, "prefilling fake context");
            if (cancelled(request, observer, cancellation)) {
                return;
            }
            ModelToolCall toolCall = this.nextToolCall;
            this.nextToolCall = null;
            if (toolCall != null) {
                observer.onState(request.id(), ModelRequestState.EXECUTING_TOOL, toolCall.toolId());
                observer.onToolCall(request.id(), toolCall);
            }
            observer.onState(request.id(), ModelRequestState.GENERATING, "generating fake response");
            String response = this.fixedResponse.isBlank() ? "Fake response: " + request.latestUserText() : this.fixedResponse;
            List<String> chunks = chunks(response, 7);
            long firstTokenAt = 0L;
            long startedAt = System.nanoTime();
            for (String chunk : chunks) {
                if (cancelled(request, observer, cancellation)) {
                    return;
                }
                if (this.chunkDelayMillis > 0L) {
                    Thread.sleep(this.chunkDelayMillis);
                }
                if (firstTokenAt == 0L) {
                    firstTokenAt = System.nanoTime();
                }
                observer.onTextDelta(request.id(), chunk);
            }
            observer.onState(request.id(), ModelRequestState.FINALIZING, "finalizing fake response");
            long finishedAt = System.nanoTime();
            int completionTokens = Math.max(1, response.length() / 4);
            long generationNanos = Math.max(1L, finishedAt - (firstTokenAt == 0L ? startedAt : firstTokenAt));
            ModelUsage usage = new ModelUsage(
                    Math.max(1, request.latestUserText().length() / 4),
                    completionTokens,
                    0,
                    0,
                    Math.max(0L, (firstTokenAt - startedAt) / 1_000_000L),
                    completionTokens / (generationNanos / 1_000_000_000.0D)
            );
            observer.onUsage(request.id(), usage);
            observer.onState(request.id(), ModelRequestState.COMPLETED, "completed");
            observer.onComplete(new StreamingModelResponse(
                    request.id(),
                    response,
                    toolCall == null ? List.of() : List.of(toolCall),
                    usage,
                    "end_turn"
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            observer.onFailure(request.id(), "fake_interrupted", "fake generation interrupted", exception);
        } catch (Exception exception) {
            observer.onFailure(request.id(), "fake_failed", exception.getMessage(), exception);
        }
    }

    private static boolean cancelled(
            StreamingModelRequest request,
            StreamingModelObserver observer,
            FakeCancellation cancellation
    ) {
        if (!cancellation.isCancellationRequested()) {
            return false;
        }
        observer.onState(request.id(), ModelRequestState.CANCELLED, cancellation.cancellationReason());
        observer.onFailure(request.id(), "cancelled", cancellation.cancellationReason(), null);
        return true;
    }

    private static List<String> chunks(String value, int size) {
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < value.length(); index += size) {
            chunks.add(value.substring(index, Math.min(value.length(), index + size)));
        }
        return chunks;
    }

    @Override
    public CompletableFuture<Void> stop() {
        this.closed = true;
        this.health = new ModelHealthSnapshot(ModelHealthState.STOPPED, "fake provider stopped", 0, null, Map.of());
        this.worker.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }

    private static final class FakeCancellation implements ModelCancellationHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String reason = "";

        @Override
        public boolean cancel(String reason) {
            if (!this.cancelled.compareAndSet(false, true)) {
                return false;
            }
            this.reason = reason == null || reason.isBlank() ? "cancelled" : reason;
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return this.cancelled.get();
        }

        @Override
        public String cancellationReason() {
            return this.reason.isBlank() ? "cancelled" : this.reason;
        }
    }
}
