package com.spirit.koil.api.model.testing;

import com.spirit.koil.api.model.provider.tokenizer.GigatokenBridgeClient;
import com.spirit.koil.api.model.provider.tokenizer.GigatokenCompatibilityRegistry;
import com.spirit.koil.api.model.catalog.ModelRuntimeCompatibility;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Optional native differential proof: upstream Gigatoken vs pinned Colibri tokenizer IDs. */
public final class GigatokenDifferentialProof {
    private GigatokenDifferentialProof() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("bridge, tokenizer.json, and Colibri reference executable are required");
        }
        Path bridge = Path.of(arguments[0]);
        Path tokenizer = Path.of(arguments[1]);
        Path reference = Path.of(arguments[2]);
        require(Files.isRegularFile(bridge), "bridge fixture is missing");
        require(Files.isRegularFile(tokenizer), "tokenizer fixture is missing");
        require(Files.isRegularFile(reference), "Colibri reference fixture is missing");
        ModelRuntimeCompatibility compatibility = ModelRuntimeCompatibility.colibri(
                "glm", "colibri/glm-5.2", true, true, false, false, 1_048_576,
                java.util.Set.of("openai_chat", "anthropic_messages"),
                "mastouri/GLM-5.2-colibri-int4-g64-with-int8-mtp",
                "6bbb01ed3e515a8730b694dfae73aadfd6774581", "proof fixture");
        require(GigatokenCompatibilityRegistry.qualify(compatibility, tokenizer.getParent()).active(),
                "the exactness certificate did not qualify its pinned tokenizer fixture");

        List<String> corpus = corpus();
        long gigatokenNanos = 0L;
        long nativeNanos = 0L;
        try (GigatokenBridgeClient client = GigatokenBridgeClient.open(bridge, tokenizer, 7);
             ReferenceClient nativeTokenizer = new ReferenceClient(reference, tokenizer)) {
            for (String text : corpus) {
                long started = System.nanoTime();
                List<Integer> fast = client.encode(text);
                gigatokenNanos += System.nanoTime() - started;
                started = System.nanoTime();
                List<Integer> nativeIds = nativeTokenizer.encode(text);
                nativeNanos += System.nanoTime() - started;
                require(fast.equals(nativeIds), "token mismatch for " + printable(text)
                        + "\nGigatoken=" + fast + "\nColibri=" + nativeIds);
            }
            benchmarkSizes(client, nativeTokenizer);
        }
        System.out.println("Gigatoken differential proof passed: " + corpus.size() + " exact cases");
        System.out.printf(java.util.Locale.ROOT,
                "Warm corpus latency: Gigatoken %.3f ms | Colibri C %.3f ms%n",
                gigatokenNanos / 1_000_000.0D, nativeNanos / 1_000_000.0D);
    }

    private static void benchmarkSizes(GigatokenBridgeClient fast, ReferenceClient nativeTokenizer)
            throws Exception {
        System.out.println("Runtime-boundary latency by UTF-8 input size:");
        for (int bytes : new int[]{256, 1024, 4096, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024}) {
            String unit = "Koil tool call navigation inventory KTL exact tokens. ";
            StringBuilder text = new StringBuilder(bytes);
            while (text.length() < bytes) text.append(unit);
            text.setLength(bytes);
            // Warm each persistent process before measuring the transport plus tokenizer.
            fast.encode(text.toString());
            nativeTokenizer.encode(text.toString());
            long started = System.nanoTime();
            List<Integer> fastIds = fast.encode(text.toString());
            long fastNanos = System.nanoTime() - started;
            started = System.nanoTime();
            List<Integer> nativeIds = nativeTokenizer.encode(text.toString());
            long nativeNanos = System.nanoTime() - started;
            require(fastIds.equals(nativeIds), "size benchmark token mismatch at " + bytes + " bytes");
            System.out.printf(java.util.Locale.ROOT,
                    "  %7d B | Gigatoken %8.3f ms | Colibri C %8.3f ms | %6.2fx%n",
                    bytes, fastNanos / 1_000_000.0D, nativeNanos / 1_000_000.0D,
                    nativeNanos / (double)Math.max(1L, fastNanos));
        }
    }

    private static List<String> corpus() {
        ArrayList<String> corpus = new ArrayList<>(List.of(
                "", "hello", "hello world", " repeated   whitespace\tand\nnewlines ",
                "e\u0301", "é", "中文と日本語", "emoji 😀🚀", "§aMinecraft §#55AAFF formatting",
                "{\"tool\":\"minecraft.knowledge\",\"arguments\":{\"id\":\"minecraft:stone\"}}",
                "<|system|>Koil<|user|>Use a tool<|assistant|>",
                "sem.task.move_to(x=120,y=64,z=-91);", "https://example.test/a?q=one%20two&x=1",
                "public static void main(String[] args) { System.out.println(\"hi\"); }"
        ));
        String unit = "Automation tool schema Minecraft navigation inventory recovery. ";
        corpus.add(unit.repeat(64));
        corpus.add(unit.repeat(1024));
        corpus.add(unit.repeat(4096));
        return List.copyOf(corpus);
    }

    private static String printable(String text) {
        String value = text.replace("\n", "\\n").replace("\t", "\\t");
        return value.length() <= 100 ? value : value.substring(0, 100) + "...";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ReferenceClient implements AutoCloseable {
        private final Process process;
        private final DataInputStream input;
        private final DataOutputStream output;

        private ReferenceClient(Path executable, Path tokenizer) throws Exception {
            this.process = new ProcessBuilder(executable.toString(), tokenizer.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            this.input = new DataInputStream(new BufferedInputStream(this.process.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(this.process.getOutputStream()));
        }

        private List<Integer> encode(String text) throws Exception {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            this.output.writeInt(Integer.reverseBytes(bytes.length));
            this.output.write(bytes);
            this.output.flush();
            int count = Integer.reverseBytes(this.input.readInt());
            ArrayList<Integer> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                result.add(Integer.reverseBytes(this.input.readInt()));
            }
            return List.copyOf(result);
        }

        @Override
        public void close() throws Exception {
            if (this.process.isAlive()) {
                this.output.writeInt(-1);
                this.output.flush();
            }
            if (!this.process.waitFor(2L, TimeUnit.SECONDS)) this.process.destroyForcibly();
        }
    }
}
