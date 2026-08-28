package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelProvider;
import com.spirit.koil.api.model.LocalModelOwnedProcessRegistry;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.catalog.LocalModelReliabilityStore;
import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelCapabilityDescriptor;
import com.spirit.koil.api.model.ModelHealthSnapshot;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelRole;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;
import com.spirit.koil.api.model.StreamingModelResponse;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LlamaCppLocalModelProvider implements LocalModelProvider {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final Object processLock = new Object();
    private final LlamaCppConfiguration configuration;
    private final OkHttpClient http;
    private final ExecutorService lifecycle;
    private final ExecutorService requests;
    private volatile ModelHealthSnapshot health = ModelHealthSnapshot.stopped();
    private volatile Process process;
    private volatile boolean ownsProcess;
    private volatile boolean stopping;
    private volatile boolean closed;
    private volatile int selectedPort;

    public LlamaCppLocalModelProvider(LlamaCppConfiguration configuration) {
        this(configuration, new OkHttpClient.Builder()
                .connectTimeout(5L, TimeUnit.SECONDS)
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .build());
    }

    LlamaCppLocalModelProvider(LlamaCppConfiguration configuration, OkHttpClient http) {
        this.configuration = configuration == null ? LlamaCppConfiguration.disabled() : configuration;
        this.http = http;
        this.selectedPort = this.configuration.port();
        this.lifecycle = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "koil-llama-lifecycle"));
        this.requests = Executors.newCachedThreadPool(runnable -> daemon(runnable, "koil-llama-request"));
    }

    @Override
    public String id() {
        return "llama_cpp";
    }

    @Override
    public ModelCapabilityDescriptor capabilities() {
        return new ModelCapabilityDescriptor(
                true,
                true,
                true,
                true,
                true,
                this.configuration.contextTokens(),
                Set.of("openai_chat")
        );
    }

    @Override
    public ModelHealthSnapshot health() {
        return this.health;
    }

    @Override
    public CompletableFuture<ModelHealthSnapshot> start() {
        if (this.closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("llama.cpp provider is closed"));
        }
        return CompletableFuture.supplyAsync(this::startBlocking, this.lifecycle);
    }

    private ModelHealthSnapshot startBlocking() {
        this.stopping = false;
        updateHealth(ModelHealthState.STARTING, "checking llama.cpp runtime", Map.of());
        String invalid = validateInstallation();
        if (!invalid.isBlank()) {
            return failHealth(invalid);
        }
        if (this.configuration.port() > 0 && compatibleRuntimeAvailable(this.configuration.port())) {
            this.selectedPort = this.configuration.port();
            this.ownsProcess = false;
            return updateHealth(ModelHealthState.READY, "connected to existing llama.cpp runtime", diagnostics());
        }
        synchronized (this.processLock) {
            if (this.process != null && this.process.isAlive()) {
                return waitForReadiness();
            }
            try {
                this.selectedPort = this.configuration.port() == 0 ? selectLocalPort() : this.configuration.port();
                ProcessBuilder builder = new ProcessBuilder(command(this.selectedPort));
                builder.redirectErrorStream(true);
                builder.environment().put("LLAMA_API_KEY", this.configuration.apiKey());
                Path parent = this.configuration.executable().toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    builder.directory(parent.toFile());
                }
                this.process = builder.start();
                this.ownsProcess = true;
                Process launched = this.process;
                LocalModelOwnedProcessRegistry.register(launched);
                captureOutput(launched);
                launched.onExit().thenRun(() -> handleProcessExit(launched));
                LocalModelRuntimeLog.write("llama_startup", "started llama.cpp on port " + this.selectedPort);
            } catch (Exception exception) {
                return failHealth("failed to start llama.cpp: " + message(exception));
            }
        }
        return waitForReadiness();
    }

    private String validateInstallation() {
        if (!this.configuration.enabled()) {
            return "llama.cpp integration is disabled";
        }
        if (!this.configuration.localhostOnly()) {
            return "llama.cpp must bind to localhost";
        }
        if (this.configuration.apiKey().isBlank()) {
            return "llama.cpp local API key is missing";
        }
        if (this.configuration.executable() == null || !Files.isRegularFile(this.configuration.executable())) {
            return "llama.cpp server executable is missing";
        }
        if (!Files.isExecutable(this.configuration.executable())) {
            return "llama.cpp server executable is not executable";
        }
        if (this.configuration.modelFile() == null || !Files.isRegularFile(this.configuration.modelFile())) {
            return "selected GGUF model file is missing";
        }
        return "";
    }

    private ModelHealthSnapshot waitForReadiness() {
        long startedAt = System.nanoTime();
        boolean slowReported = false;
        while (!this.closed && !this.stopping) {
            Process current = this.process;
            if (current != null && !current.isAlive()) {
                return failHealth("llama.cpp exited before becoming ready (exit " + current.exitValue() + ")");
            }
            if (compatibleRuntimeAvailable(this.selectedPort)) {
                return updateHealth(ModelHealthState.READY, "llama.cpp runtime ready", diagnostics());
            }
            if (!slowReported && System.nanoTime() - startedAt >= this.configuration.startupTimeout().toNanos()) {
                slowReported = true;
                updateHealth(ModelHealthState.STARTING, "llama.cpp is taking longer than expected; still waiting", diagnostics());
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return failHealth("llama.cpp startup was interrupted");
            }
        }
        return failHealth("llama.cpp startup cancelled");
    }

    private boolean compatibleRuntimeAvailable(int port) {
        if (port <= 0) {
            return false;
        }
        Request healthRequest = authenticated(new Request.Builder().url(baseUrl(port) + "/health").get()).build();
        try (Response response = this.http.newCall(healthRequest).execute()) {
            if (!response.isSuccessful()) {
                return false;
            }
        } catch (Exception exception) {
            return false;
        }
        Request modelsRequest = authenticated(new Request.Builder().url(baseUrl(port) + "/v1/models").get()).build();
        try (Response response = this.http.newCall(modelsRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            JsonElement parsed = JsonParser.parseString(response.body().string());
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("data")) {
                return false;
            }
            for (JsonElement element : parsed.getAsJsonObject().getAsJsonArray("data")) {
                if (element.isJsonObject()
                        && this.configuration.modelId().equals(string(element.getAsJsonObject(), "id", ""))) {
                    return true;
                }
            }
        } catch (Exception exception) {
            return false;
        }
        return false;
    }

    @Override
    public ModelCancellationHandle generate(StreamingModelRequest request, StreamingModelObserver observer) {
        if (this.health.state() != ModelHealthState.READY) {
            observer.onFailure(request.id(), "runtime_not_ready", this.health.detail(), null);
            return CancelledHandle.INSTANCE;
        }
        CallCancellation cancellation = new CallCancellation();
        this.requests.execute(() -> generateBlocking(request, observer, cancellation));
        return cancellation;
    }

    private void generateBlocking(
            StreamingModelRequest request,
            StreamingModelObserver observer,
            CallCancellation cancellation
    ) {
        long started = System.nanoTime();
        long firstToken = 0L;
        LlamaCppToolNameMap toolNames = LlamaCppToolNameMap.from(request);
        OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder(request.id(), new StreamingModelObserver() {
            @Override
            public void onTextDelta(UUID requestId, String delta) {
                observer.onTextDelta(requestId, delta);
            }
        }, toolNames::toCanonical);
        observer.onState(request.id(), ModelRequestState.PREPARING_CONTEXT, "preparing");
        JsonObject payload;
        try {
            payload = requestPayload(request, toolNames);
        } catch (Exception exception) {
            observer.onFailure(request.id(), "request_invalid", message(exception), exception);
            return;
        }
        observer.onState(request.id(), ModelRequestState.PREFILLING, "prefilling");
        Request httpRequest = authenticated(new Request.Builder()
                .url(baseUrl(this.selectedPort) + "/v1/chat/completions")
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(payload.toString(), JSON))).build();
        Call call = this.http.newCall(httpRequest);
        cancellation.call = call;
        if (cancellation.isCancellationRequested()) {
            call.cancel();
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw httpFailure(response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new OpenAiChatStreamDecoder.ProtocolException("empty_response", "llama.cpp returned an empty response.", null);
            }
            boolean generatingAnnounced = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancellation.isCancellationRequested()) {
                        cancelled(request, observer, cancellation);
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    int before = decoder.text().length();
                    decoder.accept(line.substring(5).trim());
                    if (!generatingAnnounced && decoder.text().length() > before) {
                        generatingAnnounced = true;
                        firstToken = System.nanoTime();
                        observer.onState(request.id(), ModelRequestState.GENERATING, "writing");
                    }
                    if (firstToken != 0L && decoder.liveCompletionTokens() > 0) {
                        observer.onUsage(request.id(), liveUsage(decoder, started, firstToken));
                    }
                }
            }
            observer.onState(request.id(), ModelRequestState.FINALIZING, "finishing");
            decoder.finishTools();
            if (!decoder.toolCalls().isEmpty()) {
                for (var toolCall : decoder.toolCalls()) {
                    observer.onState(request.id(), ModelRequestState.SELECTING_TOOL, toolCall.toolId());
                    observer.onToolCall(request.id(), toolCall);
                }
            }
            long finished = System.nanoTime();
            long ttft = firstToken == 0L ? 0L : Math.max(0L, (firstToken - started) / 1_000_000L);
            ModelUsage partial = decoder.usage(ttft, 0.0D);
            double seconds = Math.max(0.001D, (finished - (firstToken == 0L ? started : firstToken)) / 1_000_000_000.0D);
            ModelUsage usage = new ModelUsage(
                    partial.promptTokens(),
                    partial.completionTokens(),
                    partial.reusedPrefixTokens(),
                    partial.queueMillis(),
                    partial.timeToFirstTokenMillis(),
                    partial.completionTokens() / seconds
            );
            observer.onUsage(request.id(), usage);
            observer.onState(request.id(), ModelRequestState.COMPLETED, "completed");
            observer.onComplete(new StreamingModelResponse(
                    request.id(),
                    decoder.text(),
                    decoder.toolCalls(),
                    usage,
                    decoder.finishReason()
            ));
            LocalModelRuntimeLog.write("llama_request", request.id() + " completed");
        } catch (OpenAiChatStreamDecoder.ProtocolException exception) {
            observer.onState(request.id(), ModelRequestState.FAILED, exception.getMessage());
            observer.onFailure(request.id(), exception.code(), exception.getMessage(), exception);
        } catch (IOException exception) {
            if (cancellation.isCancellationRequested()) {
                cancelled(request, observer, cancellation);
            } else {
                observer.onState(request.id(), ModelRequestState.FAILED, message(exception));
                observer.onFailure(request.id(), "transport_failed", message(exception), exception);
            }
        } catch (Exception exception) {
            observer.onState(request.id(), ModelRequestState.FAILED, message(exception));
            observer.onFailure(request.id(), "generation_failed", message(exception), exception);
        } finally {
            cancellation.call = null;
        }
    }

    private static ModelUsage liveUsage(
            OpenAiChatStreamDecoder decoder,
            long startedNanos,
            long firstTokenNanos
    ) {
        long now = System.nanoTime();
        long ttft = Math.max(0L, (firstTokenNanos - startedNanos) / 1_000_000L);
        double seconds = Math.max(0.001D, (now - firstTokenNanos) / 1_000_000_000.0D);
        ModelUsage reported = decoder.usage(ttft, 0.0D);
        int liveCompletion = decoder.liveCompletionTokens();
        return new ModelUsage(
                reported.promptTokens(),
                liveCompletion,
                reported.reusedPrefixTokens(),
                reported.queueMillis(),
                reported.timeToFirstTokenMillis(),
                reported.tokensPerSecond() > 0.0D
                        ? reported.tokensPerSecond()
                        : liveCompletion / seconds
        );
    }

    private void cancelled(
            StreamingModelRequest request,
            StreamingModelObserver observer,
            CallCancellation cancellation
    ) {
        observer.onState(request.id(), ModelRequestState.CANCELLED, cancellation.cancellationReason());
        observer.onFailure(request.id(), "cancelled", cancellation.cancellationReason(), null);
    }

    private JsonObject requestPayload(StreamingModelRequest request, LlamaCppToolNameMap toolNames) {
        JsonObject root = new JsonObject();
        root.addProperty("model", this.configuration.modelId());
        root.addProperty("max_tokens", request.maximumOutputTokens());
        root.addProperty("stream", true);
        // llama-server can reuse the longest matching prefix already held in
        // its single Koil slot. This avoids prefilling Koil's stable system
        // contract again for every short follow-up such as "Hello".
        root.addProperty("cache_prompt", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);
        JsonArray messages = new JsonArray();
        if (!request.systemPrompt().isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", request.systemPrompt());
            messages.add(system);
        }
        for (ModelMessage message : request.messages()) {
            if (message.role() == ModelRole.SYSTEM) {
                continue;
            }
            JsonObject entry = new JsonObject();
            if (message.role() == ModelRole.TOOL) {
                entry.addProperty("role", "tool");
                entry.addProperty("tool_call_id", message.toolCallId());
                entry.addProperty("content", message.content());
            } else if (message.role() == ModelRole.ASSISTANT
                    && !message.toolCallId().isBlank()
                    && message.metadata().containsKey("tool_name")) {
                entry.addProperty("role", "assistant");
                entry.addProperty("content", message.content());
                JsonArray toolCalls = new JsonArray();
                JsonObject toolCall = new JsonObject();
                toolCall.addProperty("id", message.toolCallId());
                toolCall.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", toolNames.toWire(message.metadata().get("tool_name")));
                function.addProperty("arguments", message.metadata().getOrDefault("tool_arguments", "{}"));
                toolCall.add("function", function);
                toolCalls.add(toolCall);
                entry.add("tool_calls", toolCalls);
            } else {
                entry.addProperty("role", message.role() == ModelRole.ASSISTANT ? "assistant" : "user");
                entry.addProperty("content", message.content());
            }
            messages.add(entry);
        }
        root.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ModelToolDefinition definition : request.tools()) {
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", toolNames.toWire(definition.id()));
                function.addProperty("description", definition.description());
                function.add("parameters", LlamaCppToolSchemaAdapter.toWire(definition.inputSchema()));
                wrapper.add("function", function);
                tools.add(wrapper);
            }
            root.add("tools", tools);
        }
        return root;
    }

    private OpenAiChatStreamDecoder.ProtocolException httpFailure(Response response) {
        String code = "http_" + response.code();
        String detail = "llama.cpp request failed with HTTP " + response.code();
        try {
            if (response.body() != null) {
                JsonElement parsed = JsonParser.parseString(response.body().string());
                if (parsed.isJsonObject()) {
                    JsonObject error = parsed.getAsJsonObject().has("error")
                            && parsed.getAsJsonObject().get("error").isJsonObject()
                            ? parsed.getAsJsonObject().getAsJsonObject("error")
                            : parsed.getAsJsonObject();
                    code = string(error, "type", code);
                    detail = string(error, "message", detail);
                }
            }
        } catch (Exception ignored) {
        }
        return new OpenAiChatStreamDecoder.ProtocolException(code, detail, null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (this.closed && this.health.state() == ModelHealthState.STOPPED) {
            return CompletableFuture.completedFuture(null);
        }
        this.closed = true;
        this.stopping = true;
        return CompletableFuture.runAsync(this::stopBlocking, this.lifecycle);
    }

    private void stopBlocking() {
        updateHealth(ModelHealthState.STOPPING, "stopping llama.cpp runtime", Map.of());
        Process current;
        boolean owned;
        synchronized (this.processLock) {
            current = this.process;
            owned = this.ownsProcess;
            this.process = null;
            this.ownsProcess = false;
        }
        if (owned && current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(5L, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                    current.waitFor(2L, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        LocalModelOwnedProcessRegistry.unregister(current);
        updateHealth(ModelHealthState.STOPPED, "llama.cpp runtime stopped", Map.of());
        this.requests.shutdownNow();
        this.lifecycle.shutdown();
        LocalModelRuntimeLog.write("llama_shutdown", owned ? "owned runtime stopped" : "provider disconnected");
    }

    private void handleProcessExit(Process exited) {
        boolean jvmShutdown = LocalModelOwnedProcessRegistry.isJvmShutdownInProgress();
        LocalModelOwnedProcessRegistry.unregister(exited);
        if (jvmShutdown || this.stopping || this.closed) {
            return;
        }
        synchronized (this.processLock) {
            if (this.process != exited) {
                return;
            }
            this.process = null;
            this.ownsProcess = false;
        }
        int exitCode = exited.exitValue();
        updateHealth(ModelHealthState.FAILED, "llama.cpp exited with code " + exitCode, Map.of("exitCode", Integer.toString(exitCode)));
        LocalModelRuntimeLog.write("llama_crash", "runtime exited with code " + exitCode);
        LocalModelReliabilityStore.recordCrash(this.configuration.modelId(), "llama.cpp exited with code " + exitCode);
    }

    private void captureOutput(Process launched) {
        this.requests.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LocalModelRuntimeLog.write("llama_runtime", line);
                }
            } catch (IOException exception) {
                if (!this.stopping) {
                    LocalModelRuntimeLog.write("llama_log_error", message(exception));
                }
            }
        });
    }

    private List<String> command(int port) {
        List<String> command = new ArrayList<>();
        command.add(this.configuration.executable().toAbsolutePath().normalize().toString());
        command.add("--model");
        command.add(this.configuration.modelFile().toAbsolutePath().normalize().toString());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--alias");
        command.add(this.configuration.modelId());
        command.add("--ctx-size");
        command.add(Integer.toString(this.configuration.contextTokens()));
        command.add("--parallel");
        command.add("1");
        command.add("--jinja");
        return command;
    }

    private Request.Builder authenticated(Request.Builder builder) {
        return builder.header("Authorization", "Bearer " + this.configuration.apiKey());
    }

    private String baseUrl(int port) {
        return "http://" + this.configuration.host() + ":" + port;
    }

    private Map<String, String> diagnostics() {
        return Map.of(
                "provider", id(),
                "model", this.configuration.modelId(),
                "host", this.configuration.host(),
                "port", Integer.toString(this.selectedPort),
                "ownedProcess", Boolean.toString(this.ownsProcess)
        );
    }

    private ModelHealthSnapshot failHealth(String detail) {
        LocalModelRuntimeLog.write("llama_failure", detail);
        return updateHealth(ModelHealthState.FAILED, detail, Map.of());
    }

    private ModelHealthSnapshot updateHealth(ModelHealthState state, String detail, Map<String, String> diagnostics) {
        this.health = new ModelHealthSnapshot(state, detail, 0, Instant.now(), diagnostics);
        return this.health;
    }

    private static int selectLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String string(JsonObject root, String key, String fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull()
                    ? root.get(key).getAsString()
                    : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String message(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String value = cursor.getMessage();
        return value == null || value.isBlank() ? cursor.getClass().getSimpleName() : value;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class CallCancellation implements ModelCancellationHandle {
        private volatile Call call;
        private volatile boolean cancelled;
        private volatile String reason = "cancelled";

        @Override
        public boolean cancel(String reason) {
            if (this.cancelled) {
                return false;
            }
            this.reason = reason == null || reason.isBlank() ? "cancelled" : reason;
            this.cancelled = true;
            Call active = this.call;
            if (active != null) {
                active.cancel();
            }
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return this.cancelled;
        }

        @Override
        public String cancellationReason() {
            return this.reason;
        }
    }

    private enum CancelledHandle implements ModelCancellationHandle {
        INSTANCE;

        @Override
        public boolean cancel(String reason) {
            return false;
        }

        @Override
        public boolean isCancellationRequested() {
            return true;
        }

        @Override
        public String cancellationReason() {
            return "runtime not ready";
        }
    }
}
