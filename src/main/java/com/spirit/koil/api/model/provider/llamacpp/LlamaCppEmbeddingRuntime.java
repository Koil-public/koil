package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelOwnedProcessRegistry;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.catalog.LocalModelRuntimePlatform;
import com.spirit.koil.api.model.retrieval.EmbeddingIdentity;
import com.spirit.koil.api.model.retrieval.EmbeddingProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns one localhost-only llama-server embedding process; never shares the chat-generation process. */
public final class LlamaCppEmbeddingRuntime implements EmbeddingProvider {
    private final LocalModelSelection selection;
    private final String apiKey;
    private final EmbeddingIdentity identity;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(task -> daemon(task, "koil-llama-embedding-runtime"));
    private final ExecutorService output = Executors.newSingleThreadExecutor(task -> daemon(task, "koil-llama-embedding-output"));
    private final OkHttpClient healthHttp = new OkHttpClient.Builder().connectTimeout(2L, TimeUnit.SECONDS).readTimeout(2L, TimeUnit.SECONDS).build();
    private volatile LlamaCppEmbeddingProvider provider;
    private volatile Process process;
    private volatile int port;
    private volatile boolean closed;

    public LlamaCppEmbeddingRuntime(LocalModelSelection selection, String apiKey, EmbeddingIdentity identity) {
        this.selection = selection == null ? LocalModelSelection.none() : selection;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.identity = identity;
    }

    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(this::startBlocking, this.lifecycle);
    }

    @Override
    public EmbeddingIdentity identity() {
        return this.identity;
    }

    @Override
    public CompletableFuture<List<float[]>> embed(List<String> texts) {
        LlamaCppEmbeddingProvider active = this.provider;
        return active == null
                ? CompletableFuture.failedFuture(new IllegalStateException("llama.cpp embedding runtime is not ready"))
                : active.embed(texts);
    }

    public int port() {
        return this.port;
    }

    private void startBlocking() {
        if (this.closed) throw new IllegalStateException("llama.cpp embedding runtime is closed");
        validate();
        try {
            this.port = localPort();
            ProcessBuilder launcher = new ProcessBuilder(command(this.port));
            launcher.redirectErrorStream(true);
            launcher.environment().put("LLAMA_API_KEY", this.apiKey);
            launcher.directory(this.selection.runtimeExecutable().toAbsolutePath().getParent().toFile());
            this.process = launcher.start();
            LocalModelOwnedProcessRegistry.register(this.process);
            captureOutput(this.process);
            waitForReadiness();
            this.provider = new LlamaCppEmbeddingProvider("127.0.0.1", this.port, this.apiKey, this.identity);
            LocalModelRuntimeLog.write("embedding_runtime", "ready model=" + this.identity.modelId() + " port=" + this.port);
        } catch (Exception exception) {
            close();
            throw new IllegalStateException("unable to start llama.cpp embedding runtime: " + exception.getMessage(), exception);
        }
    }

    private void validate() {
        if (!this.selection.complete() || !"llama_cpp".equals(this.selection.providerId())) {
            throw new IllegalStateException("no installed llama.cpp embedding model is selected");
        }
        if (this.apiKey.isBlank()) throw new IllegalStateException("local llama.cpp embedding API key is missing");
        if (!LocalModelRuntimePlatform.isLaunchable(this.selection.runtimeExecutable())) {
            throw new IllegalStateException("selected llama.cpp runtime executable is unavailable");
        }
        if (!Files.isRegularFile(this.selection.modelPath())) throw new IllegalStateException("selected embedding GGUF is unavailable");
    }

    private void waitForReadiness() {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!this.closed && System.nanoTime() < deadline) {
            if (this.process != null && !this.process.isAlive()) {
                throw new IllegalStateException("llama.cpp embedding runtime exited before readiness");
            }
            if (ready()) return;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("llama.cpp embedding startup interrupted", exception);
            }
        }
        throw new IllegalStateException("llama.cpp embedding runtime did not become ready");
    }

    private boolean ready() {
        try (Response health = this.healthHttp.newCall(auth(new Request.Builder().url(url("/health")).get().build())).execute()) {
            if (!health.isSuccessful()) return false;
        } catch (IOException ignored) {
            return false;
        }
        try (Response models = this.healthHttp.newCall(auth(new Request.Builder().url(url("/v1/models")).get().build())).execute()) {
            if (!models.isSuccessful() || models.body() == null) return false;
            JsonElement root = JsonParser.parseString(models.body().string());
            return root.isJsonObject() && root.getAsJsonObject().has("data")
                    && root.getAsJsonObject().getAsJsonArray("data").asList().stream().anyMatch(value -> value.isJsonObject()
                    && value.getAsJsonObject().has("id") && this.selection.modelId().equals(value.getAsJsonObject().get("id").getAsString()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> command(int selectedPort) {
        List<String> command = new ArrayList<>();
        command.add(this.selection.runtimeExecutable().toString());
        command.add("--model"); command.add(this.selection.modelPath().toString());
        command.add("--host"); command.add("127.0.0.1");
        command.add("--port"); command.add(Integer.toString(selectedPort));
        command.add("--alias"); command.add(this.selection.modelId());
        command.add("--ctx-size"); command.add(Integer.toString(this.selection.contextTokens()));
        command.add("--parallel"); command.add("1");
        command.add("--embedding"); command.add("--pooling"); command.add("last");
        return List.copyOf(command);
    }

    private Request auth(Request request) { return request.newBuilder().header("Authorization", "Bearer " + this.apiKey).build(); }
    private String url(String suffix) { return "http://127.0.0.1:" + this.port + suffix; }
    private static int localPort() throws IOException { try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }

    private void captureOutput(Process launched) {
        this.output.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) LocalModelRuntimeLog.write("embedding_runtime", line);
            } catch (IOException exception) {
                if (!this.closed) LocalModelRuntimeLog.write("embedding_runtime_log_error", exception.getMessage());
            }
        });
    }

    @Override public void close() {
        this.closed = true;
        LlamaCppEmbeddingProvider active = this.provider;
        if (active != null) active.close();
        Process launched = this.process;
        if (launched != null && launched.isAlive()) {
            launched.destroy();
            try {
                if (!launched.waitFor(5L, TimeUnit.SECONDS)) launched.destroyForcibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                launched.destroyForcibly();
            }
        }
        LocalModelOwnedProcessRegistry.unregister(launched);
        this.output.shutdownNow();
        this.lifecycle.shutdownNow();
    }
}
