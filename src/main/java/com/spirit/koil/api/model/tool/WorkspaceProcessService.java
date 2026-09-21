package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class WorkspaceProcessService {
    private static final int MAX_LOG_LINES = 2000;
    private static final int MAX_PROCESSES = 64;
    private static final ConcurrentHashMap<String, ManagedProcess> PROCESSES = new ConcurrentHashMap<>();

    private WorkspaceProcessService() {}

    static String start(Path cwd, List<String> command, Map<String,String> environment) throws IOException {
        cleanup();
        if (PROCESSES.size() >= MAX_PROCESSES) throw new IOException("Managed process capacity reached.");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        if (environment != null) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        Process process = builder.start();
        String id = "proc-" + UUID.randomUUID().toString().substring(0, 12);
        ManagedProcess managed = new ManagedProcess(id, process, List.copyOf(command), cwd.toAbsolutePath().normalize(), System.currentTimeMillis());
        PROCESSES.put(id, managed);
        drain(process.getInputStream(), managed.stdout);
        drain(process.getErrorStream(), managed.stderr);
        process.onExit().thenRun(() -> managed.finishedAt = System.currentTimeMillis());
        return id;
    }

    static RunResult run(Path cwd, List<String> command, Map<String,String> environment, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        if (environment != null) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        long start = System.nanoTime();
        Process process = builder.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread t1 = collect(process.getInputStream(), out, 64 * 1024);
        Thread t2 = collect(process.getErrorStream(), err, 64 * 1024);
        boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        t1.join(1000); t2.join(1000);
        int exit = finished ? process.exitValue() : -1;
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new RunResult(exit, !finished, duration, out.toString(), err.toString());
    }

    static ManagedProcess get(String id) { return id == null ? null : PROCESSES.get(id.strip()); }
    static List<ManagedProcess> list() { cleanup(); return new ArrayList<>(PROCESSES.values()); }
    static boolean stop(String id, boolean force) {
        ManagedProcess p = get(id); if (p == null) return false;
        if (!p.process.isAlive()) return true;
        if (force) p.process.destroyForcibly(); else p.process.destroy();
        return true;
    }

    static JsonObject describe(ManagedProcess p, int tailLines) {
        JsonObject o = new JsonObject();
        o.addProperty("processId", p.id); o.addProperty("alive", p.process.isAlive());
        o.addProperty("pid", p.process.pid()); o.addProperty("startedAtEpochMs", p.startedAt);
        if (p.finishedAt > 0) o.addProperty("finishedAtEpochMs", p.finishedAt);
        if (!p.process.isAlive()) { try { o.addProperty("exitCode", p.process.exitValue()); } catch (Exception ignored) {} }
        JsonArray argv = new JsonArray(); p.command.forEach(argv::add); o.add("argv", argv);
        o.addProperty("cwd", p.cwd.toString());
        o.addProperty("stdout", p.stdout.tail(tailLines)); o.addProperty("stderr", p.stderr.tail(tailLines));
        o.addProperty("stdoutCursor", p.stdout.nextCursor()); o.addProperty("stderrCursor", p.stderr.nextCursor());
        return o;
    }

    static JsonObject incrementalOutput(ManagedProcess p, long stdoutCursor, long stderrCursor, int maxLines) {
        JsonObject o = new JsonObject();
        o.addProperty("processId", p.id);
        LineSlice stdout = p.stdout.since(stdoutCursor, maxLines);
        LineSlice stderr = p.stderr.since(stderrCursor, maxLines);
        o.addProperty("stdout", stdout.text());
        o.addProperty("stderr", stderr.text());
        o.addProperty("stdoutCursor", stdout.nextCursor());
        o.addProperty("stderrCursor", stderr.nextCursor());
        o.addProperty("stdoutCursorReset", stdout.cursorReset());
        o.addProperty("stderrCursorReset", stderr.cursorReset());
        o.addProperty("alive", p.process.isAlive());
        if (!p.process.isAlive()) { try { o.addProperty("exitCode", p.process.exitValue()); } catch (Exception ignored) {} }
        return o;
    }

    private static void cleanup() {
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000L;
        PROCESSES.entrySet().removeIf(e -> !e.getValue().process.isAlive() && e.getValue().finishedAt > 0 && e.getValue().finishedAt < cutoff);
    }
    private static void drain(InputStream in, LineBuffer buffer) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) buffer.add(line);
            } catch (IOException ignored) {}
        }, "Koil-Process-Drain"); t.setDaemon(true); t.start();
    }
    private static Thread collect(InputStream in, StringBuilder target, int max) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] b = new char[2048]; int n; while ((n = r.read(b)) >= 0) synchronized (target) {
                    int room = max - target.length(); if (room <= 0) break; target.append(b, 0, Math.min(room, n));
                }
            } catch (IOException ignored) {}
        }, "Koil-Process-Collect"); t.setDaemon(true); t.start(); return t;
    }

    static final class ManagedProcess {
        final String id; final Process process; final List<String> command; final Path cwd; final long startedAt;
        final LineBuffer stdout = new LineBuffer(); final LineBuffer stderr = new LineBuffer(); volatile long finishedAt;
        ManagedProcess(String id, Process process, List<String> command, Path cwd, long startedAt) { this.id=id; this.process=process; this.command=command; this.cwd=cwd; this.startedAt=startedAt; }
    }
    private static final class LineBuffer {
        final ArrayDeque<String> lines = new ArrayDeque<>();
        long firstCursor = 0L;
        long nextCursor = 0L;
        synchronized void add(String line) {
            lines.addLast(line); nextCursor++;
            while (lines.size() > MAX_LOG_LINES) { lines.removeFirst(); firstCursor++; }
        }
        synchronized long nextCursor() { return nextCursor; }
        synchronized String tail(int count) { int skip=Math.max(0, lines.size()-Math.max(1,count)); StringBuilder b=new StringBuilder(); int i=0; for(String s:lines){ if(i++<skip)continue; if(b.length()>0)b.append('\n'); b.append(s);} return b.toString(); }
        synchronized LineSlice since(long requestedCursor, int maxLines) {
            boolean reset = requestedCursor < firstCursor || requestedCursor > nextCursor;
            long cursor = reset ? firstCursor : requestedCursor;
            int skip = (int)Math.max(0L, cursor - firstCursor);
            StringBuilder b = new StringBuilder(); int i=0, emitted=0; long newCursor=cursor;
            for (String s : lines) {
                if (i++ < skip) continue;
                if (emitted++ >= Math.max(1, maxLines)) break;
                if (b.length() > 0) b.append('\n'); b.append(s); newCursor++;
            }
            return new LineSlice(b.toString(), newCursor, reset);
        }
    }
    private record LineSlice(String text, long nextCursor, boolean cursorReset) {}
    record RunResult(int exitCode, boolean timedOut, long durationMs, String stdout, String stderr) {}
}
