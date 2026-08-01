package com.spirit.koil.api.model.provider.colibri;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelProvider;
import com.spirit.koil.api.model.LocalModelOwnedProcessRegistry;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ColibriLocalModelProvider implements LocalModelProvider {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final Object processLock = new Object();
    private final ColibriConfiguration configuration;
    private final OkHttpClient http;
    private final ExecutorService lifecycle;
    private final ExecutorService requests;
    private volatile ModelHealthSnapshot health = ModelHealthSnapshot.stopped();
    private volatile Process process;
    private volatile boolean ownsProcess;
    private volatile boolean stopping;
    private volatile boolean closed;
    private volatile int selectedPort;
    private volatile int restartAttempts;

    public ColibriLocalModelProvider(ColibriConfiguration configuration) {
        this(configuration, new OkHttpClient.Builder()
                .connectTimeout(5L, TimeUnit.SECONDS)
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .build());
    }

    ColibriLocalModelProvider(ColibriConfiguration configuration, OkHttpClient http) {
        this.configuration = configuration == null ? ColibriConfiguration.disabled() : configuration;
        this.http = http;
        this.selectedPort = this.configuration.port();
        this.lifecycle = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "koil-colibri-lifecycle"));
        this.requests = Executors.newCachedThreadPool(runnable -> daemon(runnable, "koil-colibri-request"));
    }

    @Override
    public String id() {
        return "colibri";
    }

    @Override
    public ModelCapabilityDescriptor capabilities() {
        return new ModelCapabilityDescriptor(
                true,
                true,
                true,
                true,
                true,
                131_072,
                Set.of("anthropic_messages", "openai_chat")
        );
    }

    @Override
    public ModelHealthSnapshot health() {
        return this.health;
    }

    @Override
    public CompletableFuture<ModelHealthSnapshot> start() {
        if (this.closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Colibri provider is closed"));
        }
        return CompletableFuture.supplyAsync(this::startBlocking, this.lifecycle);
    }

    private ModelHealthSnapshot startBlocking() {
        this.stopping = false;
        updateHealth(ModelHealthState.STARTING, "checking local runtime", Map.of());
        ColibriInstallationCheck connectionCheck = ColibriInstallationCheck.inspect(this.configuration, true);
        if (!connectionCheck.compatible()) {
            return failHealth(String.join("; ", connectionCheck.failures()));
        }
        if (this.configuration.port() > 0 && compatibleRuntimeAvailable(this.configuration.port())) {
            this.selectedPort = this.configuration.port();
            this.ownsProcess = false;
            this.restartAttempts = 0;
            return updateHealth(ModelHealthState.READY, "connected to existing local runtime", runtimeDiagnostics());
        }

        ColibriInstallationCheck launchCheck = ColibriInstallationCheck.inspect(this.configuration, false);
        if (!launchCheck.compatible()) {
            return failHealth(String.join("; ", launchCheck.failures()));
        }
        synchronized (this.processLock) {
            if (this.process != null && this.process.isAlive()) {
                return waitForReadiness();
            }
            try {
                this.selectedPort = this.configuration.port() == 0 ? selectLocalPort() : this.configuration.port();
                ProcessBuilder builder = new ProcessBuilder(command(this.selectedPort));
                builder.redirectErrorStream(true);
                builder.environment().put("COLI_API_KEY", this.configuration.apiKey());
                builder.environment().put("COLI_KV_SLOTS", Integer.toString(this.configuration.kvSlots()));
                builder.environment().put("COLI_MODEL", this.configuration.modelDirectory().toAbsolutePath().normalize().toString());
                this.process = builder.start();
                this.ownsProcess = true;
                Process launched = this.process;
                LocalModelOwnedProcessRegistry.register(launched);
                captureOutput(launched);
                launched.onExit().thenRun(() -> handleProcessExit(launched));
                LocalModelRuntimeLog.write("startup", "started local runtime on port " + this.selectedPort);
            } catch (Exception exception) {
                return failHealth("failed to start Colibri: " + message(exception));
            }
        }
        return waitForReadiness();
    }

    private ModelHealthSnapshot waitForReadiness() {
        long startedAt = System.nanoTime();
        boolean slowReported = false;
        String lastFailure = "runtime has not reported ready";
        while (!this.closed && !this.stopping) {
            Process current = this.process;
            if (current != null && !current.isAlive()) {
                return failHealth("Colibri exited before becoming ready (exit " + current.exitValue() + ")");
            }
            if (compatibleRuntimeAvailable(this.selectedPort)) {
                this.restartAttempts = 0;
                return updateHealth(ModelHealthState.READY, "local runtime ready", runtimeDiagnostics());
            }
            lastFailure = this.health.detail();
            if (!slowReported && System.nanoTime() - startedAt >= this.configuration.startupTimeout().toNanos()) {
                slowReported = true;
                updateHealth(ModelHealthState.STARTING, "Colibri is taking longer than expected; still waiting", runtimeDiagnostics());
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return failHealth("runtime startup was interrupted");
            }
        }
        return failHealth(this.stopping || this.closed ? "runtime startup cancelled" : lastFailure);
    }

    private boolean compatibleRuntimeAvailable(int port) {
        if (port <= 0) {
            return false;
        }
        Request healthRequest = authenticated(new Request.Builder()
                .url(baseUrl(port) + "/health")
                .get()).build();
        try (Response response = this.http.newCall(healthRequest).execute()) {
            if (!response.isSuccessful()) {
                return false;
            }
        } catch (Exception exception) {
            return false;
        }

        Request modelsRequest = authenticated(new Request.Builder()
                .url(baseUrl(port) + "/v1/models")
                .get()).build();
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
        ColibriCallCancellation cancellation = new ColibriCallCancellation();
        this.requests.execute(() -> generateBlocking(request, observer, cancellation));
        return cancellation;
    }

    private void generateBlocking(
            StreamingModelRequest request,
            StreamingModelObserver observer,
            ColibriCallCancellation cancellation
    ) {
        long started = System.nanoTime();
        long firstToken = 0L;
        long queueMillis = 0L;
        ColibriStreamDecoder decoder = new ColibriStreamDecoder(request.id(), new StreamingModelObserver() {
            @Override
            public void onTextDelta(java.util.UUID requestId, String delta) {
                observer.onTextDelta(requestId, delta);
            }

            @Override
            public void onToolCall(java.util.UUID requestId, com.spirit.koil.api.model.ModelToolCall call) {
                observer.onState(requestId, ModelRequestState.SELECTING_TOOL, call.toolId());
                observer.onToolCall(requestId, call);
            }
        });
        observer.onState(request.id(), ModelRequestState.PREPARING_CONTEXT, "preparing");
        JsonObject payload;
        try {
            payload = requestPayload(request);
        } catch (Exception exception) {
            observer.onFailure(request.id(), "request_invalid", message(exception), exception);
            return;
        }
        observer.onState(request.id(), ModelRequestState.PREFILLING, "prefilling");
        Request httpRequest = authenticated(new Request.Builder()
                .url(baseUrl(this.selectedPort) + "/v1/messages")
                .header("Accept", "text/event-stream")
                .header("anthropic-version", "2023-06-01")
                .post(RequestBody.create(payload.toString(), JSON))).build();
        Call call = this.http.newCall(httpRequest);
        cancellation.call = call;
        if (cancellation.isCancellationRequested()) {
            call.cancel();
        }

        try (Response response = call.execute()) {
            queueMillis = longHeader(response, "x-colibri-queue-wait-ms", 0L);
            if (!response.isSuccessful()) {
                throw httpFailure(response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ColibriStreamDecoder.ProtocolException("empty_response", "Colibri returned an empty response", null);
            }
            String eventName = "";
            StringBuilder data = new StringBuilder();
            boolean generatingAnnounced = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancellation.isCancellationRequested()) {
                        observer.onState(request.id(), ModelRequestState.CANCELLED, cancellation.cancellationReason());
                        observer.onFailure(request.id(), "cancelled", cancellation.cancellationReason(), null);
                        return;
                    }
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            int before = decoder.text().length();
                            decoder.accept(eventName, data.toString());
                            if (!generatingAnnounced && decoder.text().length() > before) {
                                generatingAnnounced = true;
                                firstToken = System.nanoTime();
                                observer.onState(request.id(), ModelRequestState.GENERATING, "writing");
                            }
                        }
                        eventName = "";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        eventName = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).trim());
                    }
                }
            }
            if (data.length() > 0) {
                decoder.accept(eventName, data.toString());
            }
            decoder.finishOpenBlocks();
            observer.onState(request.id(), ModelRequestState.FINALIZING, "finishing");
            long finished = System.nanoTime();
            long ttft = firstToken == 0L ? 0L : Math.max(0L, (firstToken - started) / 1_000_000L);
            double generationSeconds = Math.max(0.001D, (finished - (firstToken == 0L ? started : firstToken)) / 1_000_000_000.0D);
            ModelUsage baseUsage = decoder.usage(queueMillis, ttft, 0.0D);
            ModelUsage usage = new ModelUsage(
                    baseUsage.promptTokens(),
                    baseUsage.completionTokens(),
                    baseUsage.reusedPrefixTokens(),
                    baseUsage.queueMillis(),
                    baseUsage.timeToFirstTokenMillis(),
                    baseUsage.completionTokens() / generationSeconds
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
            LocalModelRuntimeLog.write("request", request.id() + " completed");
        } catch (ColibriStreamDecoder.ProtocolException exception) {
            observer.onState(request.id(), ModelRequestState.FAILED, exception.getMessage());
            observer.onFailure(request.id(), exception.code(), exception.getMessage(), exception);
        } catch (IOException exception) {
            if (cancellation.isCancellationRequested()) {
                observer.onState(request.id(), ModelRequestState.CANCELLED, cancellation.cancellationReason());
                observer.onFailure(request.id(), "cancelled", cancellation.cancellationReason(), null);
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

    private JsonObject requestPayload(StreamingModelRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", this.configuration.modelId());
        root.addProperty("max_tokens", request.maximumOutputTokens());
        root.addProperty("stream", true);
        if (!request.systemPrompt().isBlank()) {
            root.addProperty("system", request.systemPrompt());
        }
        JsonArray messages = new JsonArray();
        for (ModelMessage message : request.messages()) {
            if (message.role() == ModelRole.SYSTEM) {
                continue;
            }
            JsonObject entry = new JsonObject();
            if (message.role() == ModelRole.TOOL) {
                entry.addProperty("role", "user");
                JsonArray content = new JsonArray();
                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("type", "tool_result");
                toolResult.addProperty("tool_use_id", message.toolCallId());
                toolResult.addProperty("content", message.content());
                content.add(toolResult);
                entry.add("content", content);
            } else if (message.role() == ModelRole.ASSISTANT
                    && !message.toolCallId().isBlank()
                    && message.metadata().containsKey("tool_name")) {
                entry.addProperty("role", "assistant");
                JsonArray content = new JsonArray();
                if (!message.content().isBlank()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "text");
                    text.addProperty("text", message.content());
                    content.add(text);
                }
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("type", "tool_use");
                toolUse.addProperty("id", message.toolCallId());
                toolUse.addProperty("name", message.metadata().get("tool_name"));
                try {
                    JsonElement parsedArguments = JsonParser.parseString(
                            message.metadata().getOrDefault("tool_arguments", "{}")
                    );
                    toolUse.add("input", parsedArguments.isJsonObject()
                            ? parsedArguments.getAsJsonObject()
                            : new JsonObject());
                } catch (RuntimeException ignored) {
                    toolUse.add("input", new JsonObject());
                }
                content.add(toolUse);
                entry.add("content", content);
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
                JsonObject tool = new JsonObject();
                tool.addProperty("name", definition.id());
                tool.addProperty("description", definition.description());
                tool.add("input_schema", definition.inputSchema().deepCopy());
                tools.add(tool);
            }
            root.add("tools", tools);
        }
        String slot = request.metadata().get("cache_slot");
        if (slot != null && !slot.isBlank()) {
            try {
                int parsed = Integer.parseInt(slot);
                if (parsed >= 0 && parsed < this.configuration.kvSlots()) {
                    root.addProperty("cache_slot", parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return root;
    }

    private ColibriStreamDecoder.ProtocolException httpFailure(Response response) {
        String code = "http_" + response.code();
        String detail = "Colibri request failed with HTTP " + response.code();
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
        return new ColibriStreamDecoder.ProtocolException(code, detail, null);
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
        updateHealth(ModelHealthState.STOPPING, "stopping local runtime", Map.of());
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
        updateHealth(ModelHealthState.STOPPED, "local runtime stopped", Map.of());
        this.requests.shutdownNow();
        this.lifecycle.shutdown();
        LocalModelRuntimeLog.write("shutdown", owned ? "owned runtime stopped" : "provider disconnected");
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
        updateHealth(ModelHealthState.FAILED, "Colibri exited with code " + exitCode, Map.of("exitCode", Integer.toString(exitCode)));
        LocalModelRuntimeLog.write("crash", "runtime exited with code " + exitCode);
        if (this.restartAttempts >= this.configuration.maximumRestartAttempts()) {
            return;
        }
        this.restartAttempts++;
        this.lifecycle.execute(() -> {
            try {
                Thread.sleep(this.configuration.restartBackoff().toMillis() * this.restartAttempts);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!this.closed && !this.stopping) {
                startBlocking();
            }
        });
    }

    private void captureOutput(Process launched) {
        this.requests.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LocalModelRuntimeLog.write("runtime", line);
                }
            } catch (IOException exception) {
                if (!this.stopping) {
                    LocalModelRuntimeLog.write("runtime_log_error", message(exception));
                }
            }
        });
    }

    private List<String> command(int port) {
        Path executable = this.configuration.executable().toAbsolutePath().normalize();
        Path model = this.configuration.modelDirectory().toAbsolutePath().normalize();
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("serve");
        command.add("--model");
        command.add(model.toString());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--model-id");
        command.add(this.configuration.modelId());
        command.add("--max-queue");
        command.add(Integer.toString(this.configuration.maximumQueueDepth()));
        command.add("--queue-timeout");
        command.add(Long.toString(this.configuration.queueTimeout().toSeconds()));
        command.add("--kv-slots");
        command.add(Integer.toString(this.configuration.kvSlots()));
        return command;
    }

    private Request.Builder authenticated(Request.Builder builder) {
        return builder.header("x-api-key", this.configuration.apiKey())
                .header("Authorization", "Bearer " + this.configuration.apiKey());
    }

    private String baseUrl(int port) {
        return "http://" + this.configuration.host() + ":" + port;
    }

    private Map<String, String> runtimeDiagnostics() {
        return Map.of(
                "provider", id(),
                "model", this.configuration.modelId(),
                "host", this.configuration.host(),
                "port", Integer.toString(this.selectedPort),
                "ownedProcess", Boolean.toString(this.ownsProcess)
        );
    }

    private ModelHealthSnapshot failHealth(String detail) {
        LocalModelRuntimeLog.write("failure", detail);
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

    private static long longHeader(Response response, String name, long fallback) {
        try {
            String value = response.header(name);
            return value == null ? fallback : Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String string(JsonObject root, String key, String fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : fallback;
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

    private final class ColibriCallCancellation implements ModelCancellationHandle {
        private final AtomicBoolean requested = new AtomicBoolean();
        private volatile String reason = "";
        private volatile Call call;

        @Override
        public boolean cancel(String reason) {
            if (!this.requested.compareAndSet(false, true)) {
                return false;
            }
            this.reason = reason == null || reason.isBlank() ? "cancelled" : reason;
            Call activeCall = this.call;
            if (activeCall != null) {
                activeCall.cancel();
            }
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
