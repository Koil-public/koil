package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-light executable proof for inert GGUF and Safetensors import. */
public final class KoilModelImporterProof {
    private KoilModelImporterProof() {}

    public static void main(String[] args) throws Exception {
        proveSafetensors();
        proveGguf();
        System.out.println("Koil model importer proof passed");
    }

    private static void proveSafetensors() throws Exception {
        Path root = Files.createTempDirectory("koil-import-safetensors");
        try {
            Files.writeString(root.resolve("config.json"), "{\"architectures\":[\"LlamaForCausalLM\"],\"model_type\":\"llama\",\"max_position_embeddings\":8192}");
            Files.writeString(root.resolve("tokenizer_config.json"), "{\"tokenizer_class\":\"LlamaTokenizerFast\",\"chat_template\":\"{{ tools }}\"}");
            String header = "{\"model.embed_tokens.weight\":{\"dtype\":\"F16\",\"shape\":[4,8],\"data_offsets\":[0,64]},"
                    + "\"model.layers.0.weight\":{\"dtype\":\"F16\",\"shape\":[8,8],\"data_offsets\":[64,192]}}";
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(root.resolve("model.safetensors")))) {
                writeLongLE(out, headerBytes.length);
                out.write(headerBytes);
                out.write(new byte[192]);
            }
            KoilExecutionRepresentation imported = KoilModelImporter.importModel(root, ModelArtifactFormat.SAFETENSORS_DIRECTORY);
            require(imported.readiness() == KoilImportReadiness.GRAPH_VALIDATED, "Safetensors graph validated");
            require(imported.executionGraphReady(), "Safetensors execution graph ready");
            require(imported.graph().nodes().size() >= 3, "Safetensors graph nodes");
            require(imported.tensors().tensorCount() == 2, "Safetensors tensor count");
            require(imported.tensors().parameterCount() == 96, "Safetensors parameter count");
            require(imported.tensors().storageBytes() == 192, "Safetensors storage bytes");
            require("F16".equals(imported.tensors().dominantStorageType()), "Safetensors dominant dtype");
            require(imported.quantization().quantizationClass() == KoilQuantizationClass.FLOATING_POINT, "Safetensors floating classification");
            require(imported.tokenizer().toolTemplateEvidence(), "Safetensors tool template evidence");
        } finally {
            deleteTree(root);
        }
    }

    private static void proveGguf() throws Exception {
        Path file = Files.createTempFile("koil-import-", ".gguf");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.write(new byte[]{'G','G','U','F'});
            writeIntLE(out, 3);
            writeLongLE(out, 2);
            writeLongLE(out, 4);
            writeGgufStringMetadata(out, "general.architecture", "llama");
            writeGgufStringMetadata(out, "tokenizer.ggml.model", "llama");
            writeGgufUint32Metadata(out, "llama.context_length", 4096);
            writeGgufUint32Metadata(out, "llama.block_count", 1);
            writeString(out, "token_embd.weight");
            writeIntLE(out, 2);
            writeLongLE(out, 2);
            writeLongLE(out, 3);
            writeIntLE(out, 1); // GGML_TYPE_F16
            writeLongLE(out, 0);
            writeString(out, "blk.0.attn_q.weight");
            writeIntLE(out, 2);
            writeLongLE(out, 3);
            writeLongLE(out, 3);
            writeIntLE(out, 1);
            writeLongLE(out, 12);
        }
        try {
            KoilExecutionRepresentation imported = KoilModelImporter.importModel(file, ModelArtifactFormat.GGUF_FILE);
            require(imported.readiness() == KoilImportReadiness.GRAPH_VALIDATED, "GGUF graph validated");
            require(imported.executionGraphReady(), "GGUF execution graph ready");
            require(imported.tensors().tensorCount() == 2, "GGUF tensor count");
            require(imported.tensors().parameterCount() == 15, "GGUF parameter count");
            require(imported.tensors().tensorsByStorageType().containsKey("GGML_TYPE_1"), "GGUF storage type retained");
            require(imported.quantization().quantizationClass() == KoilQuantizationClass.FLOATING_POINT, "GGUF F16 classification");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void writeGgufStringMetadata(DataOutputStream out, String key, String value) throws Exception {
        writeString(out, key);
        writeIntLE(out, 8);
        writeString(out, value);
    }

    private static void writeGgufUint32Metadata(DataOutputStream out, String key, int value) throws Exception {
        writeString(out, key);
        writeIntLE(out, 4);
        writeIntLE(out, value);
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLongLE(out, bytes.length);
        out.write(bytes);
    }

    private static void writeIntLE(DataOutputStream out, int value) throws Exception {
        out.writeByte(value);
        out.writeByte(value >>> 8);
        out.writeByte(value >>> 16);
        out.writeByte(value >>> 24);
    }

    private static void writeLongLE(DataOutputStream out, long value) throws Exception {
        for (int i = 0; i < 8; i++) out.writeByte((int)(value >>> (8 * i)));
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("proof failed: " + message);
    }
}
