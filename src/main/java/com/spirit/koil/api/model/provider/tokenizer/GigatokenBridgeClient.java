package com.spirit.koil.api.model.provider.tokenizer;

import com.spirit.koil.api.model.LocalModelOwnedProcessRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Memory-safe Java owner for Koil's upstream-Gigatoken stdio process. */
public final class GigatokenBridgeClient implements Closeable {
    private static final int REQUEST_MAGIC = 0x47544F4B;
    private static final int RESPONSE_MAGIC = 0x4754414B;
    private static final int OP_PING = 1;
    private static final int OP_ENCODE = 3;
    private static final int OP_SHUTDOWN = 6;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024 * 1024;

    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "koil-gigatoken-deadline");
                thread.setDaemon(true);
                return thread;
            });

    private final Process process;
    private final DataInputStream input;
    private final DataOutputStream output;
    private int requestId;
    private boolean closed;

    private GigatokenBridgeClient(Path executable) throws IOException {
        this.process = new ProcessBuilder(executable.toAbsolutePath().normalize().toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        LocalModelOwnedProcessRegistry.register(this.process);
        this.input = new DataInputStream(new BufferedInputStream(this.process.getInputStream()));
        this.output = new DataOutputStream(new BufferedOutputStream(this.process.getOutputStream()));
    }

    public static GigatokenBridgeClient open(Path executable, Path tokenizer, int loadOperation)
            throws IOException {
        if (executable == null || !Files.isRegularFile(executable)) {
            throw new IOException("Gigatoken bridge executable is missing");
        }
        if (tokenizer == null || !Files.isRegularFile(tokenizer)) {
            throw new IOException("tokenizer.json is missing");
        }
        GigatokenBridgeClient client = new GigatokenBridgeClient(executable);
        boolean ready = false;
        try {
            client.call(loadOperation, Files.readAllBytes(tokenizer));
            client.ping();
            ready = true;
            return client;
        } finally {
            if (!ready) client.close();
        }
    }

    public synchronized String ping() throws IOException {
        return new String(call(OP_PING, new byte[0]), StandardCharsets.UTF_8);
    }

    public synchronized List<Integer> encode(String text) throws IOException {
        byte[] response = call(OP_ENCODE,
                (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        if (response.length < 4) throw new EOFException("Gigatoken token vector is truncated");
        int count = littleInt(response, 0);
        if (count < 0 || response.length != 4L + 4L * count) {
            throw new IOException("Gigatoken returned an invalid token vector");
        }
        List<Integer> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(littleInt(response, 4 + index * 4));
        }
        return List.copyOf(result);
    }

    private byte[] call(int operation, byte[] payload) throws IOException {
        if (this.closed || !this.process.isAlive()) throw new IOException("Gigatoken bridge is stopped");
        var deadline = DEADLINES.schedule(() -> this.process.destroyForcibly(), 30L, TimeUnit.SECONDS);
        try {
            int current = ++this.requestId;
            writeLittleInt(this.output, REQUEST_MAGIC);
            writeLittleInt(this.output, operation);
            writeLittleInt(this.output, current);
            writeLittleInt(this.output, payload.length);
            this.output.write(payload);
            this.output.flush();
            int magic = readLittleInt(this.input);
            int status = readLittleInt(this.input);
            int responseId = readLittleInt(this.input);
            int length = readLittleInt(this.input);
            if (magic != RESPONSE_MAGIC || responseId != current || length < 0 || length > MAX_RESPONSE_BYTES) {
                throw new IOException("invalid Gigatoken bridge response frame");
            }
            byte[] body = this.input.readNBytes(length);
            if (body.length != length) throw new EOFException("Gigatoken bridge response is truncated");
            if (status != 0) throw new IOException(new String(body, StandardCharsets.UTF_8));
            return body;
        } finally {
            deadline.cancel(false);
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        try {
            if (this.process.isAlive()) {
                int current = ++this.requestId;
                writeLittleInt(this.output, REQUEST_MAGIC);
                writeLittleInt(this.output, OP_SHUTDOWN);
                writeLittleInt(this.output, current);
                writeLittleInt(this.output, 0);
                this.output.flush();
            }
        } catch (IOException ignored) {
        }
        try {
            if (!this.process.waitFor(2L, TimeUnit.SECONDS)) this.process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.process.destroyForcibly();
        } finally {
            LocalModelOwnedProcessRegistry.unregister(this.process);
        }
    }

    private static int littleInt(byte[] source, int offset) {
        return (source[offset] & 0xFF)
                | (source[offset + 1] & 0xFF) << 8
                | (source[offset + 2] & 0xFF) << 16
                | (source[offset + 3] & 0xFF) << 24;
    }

    private static int readLittleInt(DataInputStream input) throws IOException {
        return Integer.reverseBytes(input.readInt());
    }

    private static void writeLittleInt(DataOutputStream output, int value) throws IOException {
        output.writeInt(Integer.reverseBytes(value));
    }
}
