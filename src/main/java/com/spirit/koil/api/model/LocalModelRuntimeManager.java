package com.spirit.koil.api.model;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalModelRuntimeManager implements AutoCloseable {
    private final Object lock = new Object();
    private final Map<String, LocalModelProvider> providers = new LinkedHashMap<>();
    private final ArrayDeque<QueuedRequest> queue = new ArrayDeque<>();
    private final ExecutorService controller;
    private final int maximumQueueDepth;
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile String selectedProviderId = "";
    private volatile QueuedRequest active;
    private volatile boolean closed;

    public LocalModelRuntimeManager(int maximumQueueDepth) {
        this.maximumQueueDepth = Math.max(1, maximumQueueDepth);
        this.controller = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "koil-local-model-runtime");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void registerProvider(LocalModelProvider provider) {
        Objects.requireNonNull(provider, "provider");
        synchronized (this.lock) {
            ensureOpen();
            LocalModelProvider existing = this.providers.putIfAbsent(provider.id(), provider);
            if (existing != null && existing != provider) {
                throw new IllegalArgumentException("model provider already registered: " + provider.id());
            }
            if (this.selectedProviderId.isBlank()) {
                this.selectedProviderId = provider.id();
            }
        }
    }

    public void selectProvider(String providerId) {
        String normalized = providerId == null ? "" : providerId.trim();
        synchronized (this.lock) {
            ensureOpen();
            if (!this.providers.containsKey(normalized)) {
                throw new IllegalArgumentException("unknown model provider: " + normalized);
            }
            this.selectedProviderId = normalized;
        }
    }

    public String selectedProviderId() {
        return this.selectedProviderId;
    }

    public int queueDepth() {
        synchronized (this.lock) {
            return this.queue.size() + (this.active == null ? 0 : 1);
        }
    }

    public List<QueuedModelRequestSnapshot> queuedRequests() {
        synchronized (this.lock) {
            List<QueuedModelRequestSnapshot> snapshots = new ArrayList<>();
            int position = 1;
            for (QueuedRequest queued : this.queue) {
                snapshots.add(queued.snapshot(position++));
            }
            return List.copyOf(snapshots);
        }
    }

    public boolean replaceQueuedPrompt(UUID requestId, long expectedRevision, String prompt) {
        String replacement = prompt == null ? "" : prompt.strip();
        if (requestId == null || replacement.isBlank()) {
            return false;
        }
        synchronized (this.lock) {
            for (QueuedRequest queued : this.queue) {
                if (!queued.request.id().equals(requestId) || queued.revision != expectedRevision) {
                    continue;
                }
                List<ModelMessage> messages = new ArrayList<>(queued.request.messages());
                int userIndex = -1;
                for (int index = messages.size() - 1; index >= 0; index--) {
                    if (messages.get(index).role() == ModelRole.USER) {
                        userIndex = index;
                        break;
                    }
                }
                if (userIndex < 0) {
                    return false;
                }
                messages.set(userIndex, ModelMessage.user(replacement));
                StreamingModelRequest current = queued.request;
                queued.request = new StreamingModelRequest(
                        current.id(), current.conversationId(), current.systemPrompt(), messages,
                        current.tools(), current.maximumOutputTokens(), current.timeout(), current.metadata()
                );
                queued.revision++;
                queued.observer.onState(requestId, ModelRequestState.QUEUED, "queued prompt updated");
                return true;
            }
        }
        return false;
    }

    public int selectedMaximumContextTokens() {
        synchronized (this.lock) {
            LocalModelProvider provider = this.providers.get(this.selectedProviderId);
            if (provider == null || provider.capabilities() == null) {
                return 0;
            }
            return provider.capabilities().maximumContextTokens();
        }
    }

    public ModelCapabilityDescriptor selectedCapabilities() {
        synchronized (this.lock) {
            LocalModelProvider provider = this.providers.get(this.selectedProviderId);
            return provider == null ? null : provider.capabilities();
        }
    }

    public ModelHealthSnapshot health() {
        LocalModelProvider provider;
        synchronized (this.lock) {
            provider = this.providers.get(this.selectedProviderId);
        }
        if (provider == null) {
            return new ModelHealthSnapshot(ModelHealthState.DISABLED, "no provider selected", queueDepth(), null, Map.of());
        }
        ModelHealthSnapshot snapshot = provider.health();
        return new ModelHealthSnapshot(
                snapshot.state(),
                snapshot.detail(),
                queueDepth(),
                snapshot.updatedAt(),
                snapshot.diagnostics()
        );
    }

    public CompletableFuture<ModelHealthSnapshot> prepareSelectedProvider() {
        LocalModelProvider provider;
        synchronized (this.lock) {
            ensureOpen();
            provider = this.providers.get(this.selectedProviderId);
        }
        if (provider == null) {
            return CompletableFuture.completedFuture(new ModelHealthSnapshot(
                    ModelHealthState.DISABLED,
                    "no model provider is selected",
                    queueDepth(),
                    null,
                    Map.of()
            ));
        }
        if (provider.health().state() == ModelHealthState.READY) {
            return CompletableFuture.completedFuture(health());
        }
        return provider.start().thenApply(ignored -> health());
    }

    public ManagedModelRequest submit(StreamingModelRequest request, StreamingModelObserver observer) {
        Objects.requireNonNull(request, "request");
        StreamingModelObserver safeObserver = observer == null ? new StreamingModelObserver() {
        } : observer;
        RuntimeCancellation cancellation = new RuntimeCancellation(request.id());
        CompletableFuture<StreamingModelResponse> completion = new CompletableFuture<>();
        String providerId;
        synchronized (this.lock) {
            providerId = this.selectedProviderId;
        }
        QueuedRequest queued = new QueuedRequest(request, providerId, safeObserver, cancellation, completion);
        cancellation.owner.set(queued);
        synchronized (this.lock) {
            ensureOpen();
            if (!this.providers.containsKey(this.selectedProviderId)) {
                failBeforeQueue(queued, "provider_unavailable", "no model provider is selected");
                return new ManagedModelRequest(request.id(), cancellation, completion);
            }
            if (this.queue.size() + (this.active == null ? 0 : 1) >= this.maximumQueueDepth) {
                failBeforeQueue(queued, "queue_full", "the local model request queue is full");
                return new ManagedModelRequest(request.id(), cancellation, completion);
            }
            this.queue.addLast(queued);
        }
        safeObserver.onState(request.id(), ModelRequestState.QUEUED, "queued");
        scheduleDrain();
        return new ManagedModelRequest(request.id(), cancellation, completion);
    }

    private void scheduleDrain() {
        if (this.draining.compareAndSet(false, true)) {
            this.controller.execute(this::drain);
        }
    }

    private void drain() {
        try {
            while (!this.closed) {
                QueuedRequest next;
                LocalModelProvider provider;
                synchronized (this.lock) {
                    next = this.queue.pollFirst();
                    if (next == null) {
                        return;
                    }
                    this.active = next;
                    provider = this.providers.get(next.providerId);
                }
                try {
                    execute(provider, next);
                } finally {
                    synchronized (this.lock) {
                        if (this.active == next) {
                            this.active = null;
                        }
                    }
                }
            }
        } finally {
            this.draining.set(false);
            synchronized (this.lock) {
                if (!this.closed && !this.queue.isEmpty()) {
                    scheduleDrain();
                }
            }
        }
    }

    private void execute(LocalModelProvider provider, QueuedRequest queued) {
        if (queued.cancellation.isCancellationRequested()) {
            completeCancelled(queued);
            return;
        }
        if (provider == null) {
            fail(queued, "provider_unavailable", "selected model provider is unavailable", null);
            return;
        }
        try {
            if (provider.health().state() != ModelHealthState.READY) {
                queued.observer.onState(queued.request.id(), ModelRequestState.WAITING_FOR_RUNTIME, "starting provider");
                ModelHealthSnapshot started = provider.start().get();
                if (started.state() != ModelHealthState.READY) {
                    fail(queued, "runtime_not_ready", started.detail(), null);
                    return;
                }
            }
        } catch (Exception exception) {
            fail(queued, "runtime_start_failed", message(exception), exception);
            return;
        }
        if (queued.cancellation.isCancellationRequested()) {
            completeCancelled(queued);
            return;
        }

        CountDownLatch terminal = new CountDownLatch(1);
        AtomicBoolean ended = new AtomicBoolean();
        StreamingModelObserver forwarding = new StreamingModelObserver() {
            @Override
            public void onState(UUID requestId, ModelRequestState state, String detail) {
                if (!ended.get()) {
                    queued.observer.onState(requestId, state, detail);
                }
            }

            @Override
            public void onTextDelta(UUID requestId, String delta) {
                if (!ended.get() && delta != null && !delta.isEmpty()) {
                    queued.observer.onTextDelta(requestId, delta);
                }
            }

            @Override
            public void onToolCall(UUID requestId, ModelToolCall call) {
                if (!ended.get() && call != null) {
                    queued.observer.onToolCall(requestId, call);
                }
            }

            @Override
            public void onUsage(UUID requestId, ModelUsage usage) {
                if (!ended.get() && usage != null) {
                    queued.observer.onUsage(requestId, usage);
                }
            }

            @Override
            public void onComplete(StreamingModelResponse response) {
                if (ended.compareAndSet(false, true)) {
                    queued.observer.onComplete(response);
                    queued.completion.complete(response);
                    terminal.countDown();
                }
            }

            @Override
            public void onFailure(UUID requestId, String code, String detail, Throwable cause) {
                if (ended.compareAndSet(false, true)) {
                    queued.observer.onFailure(requestId, code, detail, cause);
                    queued.completion.completeExceptionally(new ModelRequestException(code, detail, cause));
                    terminal.countDown();
                }
            }
        };

        try {
            queued.providerCancellation = provider.generate(queued.request, forwarding);
            if (queued.cancellation.isCancellationRequested() && queued.providerCancellation != null) {
                queued.providerCancellation.cancel(queued.cancellation.cancellationReason());
            }
            boolean completed = terminal.await(Math.max(1L, queued.request.timeout().toMillis()), TimeUnit.MILLISECONDS);
            if (!completed && ended.compareAndSet(false, true)) {
                if (queued.providerCancellation != null) {
                    queued.providerCancellation.cancel("request timed out");
                }
                String detail = "model request timed out after " + queued.request.timeout().toMillis() + " ms";
                queued.observer.onFailure(queued.request.id(), "request_timed_out", detail, null);
                queued.completion.completeExceptionally(new ModelRequestException("request_timed_out", detail, null));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (ended.compareAndSet(false, true)) {
                fail(queued, "runtime_interrupted", "model runtime queue was interrupted", exception);
            }
        } catch (Exception exception) {
            if (ended.compareAndSet(false, true)) {
                fail(queued, "generation_failed", message(exception), exception);
            }
        }
    }

    private void failBeforeQueue(QueuedRequest queued, String code, String detail) {
        queued.observer.onState(queued.request.id(), ModelRequestState.FAILED, detail);
        queued.observer.onFailure(queued.request.id(), code, detail, null);
        queued.completion.completeExceptionally(new ModelRequestException(code, detail, null));
    }

    private void fail(QueuedRequest queued, String code, String detail, Throwable cause) {
        queued.observer.onState(queued.request.id(), ModelRequestState.FAILED, detail);
        queued.observer.onFailure(queued.request.id(), code, detail, cause);
        queued.completion.completeExceptionally(new ModelRequestException(code, detail, cause));
    }

    private void completeCancelled(QueuedRequest queued) {
        String detail = queued.cancellation.cancellationReason();
        queued.observer.onState(queued.request.id(), ModelRequestState.CANCELLED, detail);
        queued.completion.completeExceptionally(new ModelRequestException("cancelled", detail, null));
    }

    private void cancel(RuntimeCancellation cancellation) {
        QueuedRequest owner = cancellation.owner.get();
        if (owner == null) {
            return;
        }
        boolean removed;
        synchronized (this.lock) {
            removed = this.queue.remove(owner);
        }
        if (removed) {
            completeCancelled(owner);
            return;
        }
        ModelCancellationHandle providerCancellation = owner.providerCancellation;
        if (providerCancellation != null) {
            providerCancellation.cancel(cancellation.cancellationReason());
        }
    }

    @Override
    public void close() {
        Map<String, LocalModelProvider> snapshot;
        List<QueuedRequest> queuedRequests = new ArrayList<>();
        QueuedRequest activeRequest;
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            QueuedRequest queued;
            while ((queued = this.queue.pollFirst()) != null) {
                queuedRequests.add(queued);
            }
            activeRequest = this.active;
            snapshot = Map.copyOf(this.providers);
        }
        for (QueuedRequest queued : queuedRequests) {
            if (queued.cancellation.cancel("runtime manager closed")) {
                completeCancelled(queued);
            }
        }
        if (activeRequest != null) {
            activeRequest.cancellation.cancel("runtime manager closed");
        }
        this.controller.shutdownNow();
        snapshot.values().forEach(provider -> {
            try {
                provider.stop().get(5L, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        });
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("local model runtime manager is closed");
        }
    }

    private static String message(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String detail = cursor.getMessage();
        return detail == null || detail.isBlank() ? cursor.getClass().getSimpleName() : detail;
    }

    private final class RuntimeCancellation implements ModelCancellationHandle {
        private final UUID requestId;
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicReference<QueuedRequest> owner = new AtomicReference<>();
        private volatile String reason = "";

        private RuntimeCancellation(UUID requestId) {
            this.requestId = requestId;
        }

        @Override
        public boolean cancel(String reason) {
            if (!this.requested.compareAndSet(false, true)) {
                return false;
            }
            this.reason = reason == null || reason.isBlank() ? "cancelled" : reason;
            LocalModelRuntimeManager.this.cancel(this);
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return this.requested.get();
        }

        @Override
        public String cancellationReason() {
            return this.reason.isBlank() ? "cancelled" : this.reason;
        }

        @Override
        public String toString() {
            return "ModelCancellation[" + this.requestId + "]";
        }
    }

    private static final class QueuedRequest {
        private volatile StreamingModelRequest request;
        private final String providerId;
        private final StreamingModelObserver observer;
        private final RuntimeCancellation cancellation;
        private final CompletableFuture<StreamingModelResponse> completion;
        private volatile ModelCancellationHandle providerCancellation;
        private long revision = 1L;

        private QueuedRequest(
                StreamingModelRequest request,
                String providerId,
                StreamingModelObserver observer,
                RuntimeCancellation cancellation,
                CompletableFuture<StreamingModelResponse> completion
        ) {
            this.request = request;
            this.providerId = providerId == null ? "" : providerId;
            this.observer = observer;
            this.cancellation = cancellation;
            this.completion = completion;
        }

        private QueuedModelRequestSnapshot snapshot(int position) {
            return new QueuedModelRequestSnapshot(
                    this.request.id(),
                    this.request.conversationId(),
                    this.providerId,
                    this.request.metadata().getOrDefault("mode", ""),
                    this.request.latestUserText(),
                    position,
                    this.revision
            );
        }
    }

    public static final class ModelRequestException extends RuntimeException {
        private final String code;

        public ModelRequestException(String code, String detail, Throwable cause) {
            super(detail == null ? "" : detail, cause);
            this.code = code == null ? "failed" : code;
        }

        public String code() {
            return this.code;
        }
    }
}
