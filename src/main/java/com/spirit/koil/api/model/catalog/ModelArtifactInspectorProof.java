package com.spirit.koil.api.model.catalog;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic proof for bounded GGUF metadata inspection. */
public final class ModelArtifactInspectorProof {
    private ModelArtifactInspectorProof() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("koil-qwen-moe-proof", ".gguf");
        Files.write(file, syntheticGguf());
        ModelArtifactInspection inspection = ModelArtifactInspector.inspect(file);
        require(inspection.present(), "synthetic GGUF was not recognized");
        require("qwen3moe".equals(inspection.architectureId()), "architecture was not decoded");
        require(inspection.contextTokens() == 131072, "context length was not decoded");
        require(inspection.expertCount() == 128, "expert count was not decoded");
        require(inspection.activeExpertCount() == 8, "active expert count was not decoded");
        require(inspection.mixtureOfExperts(), "MoE topology was not detected");
        require(inspection.declaredCapabilities().contains("template_tools"), "tool template was not detected");
        require(inspection.declaredCapabilities().contains("template_reasoning"), "reasoning template was not detected");

        Path directory = Files.createTempDirectory("koil-hf-safetensors-proof");
        Files.writeString(directory.resolve("config.json"), """
                {
  "architectures": ["MambaForCausalLM"],
  "model_type": "mamba",
  "max_position_embeddings": 65536,
  "state_space_size": 16,
  "conv_kernel": 4
}
""");
        Files.writeString(directory.resolve("tokenizer_config.json"), """
                {"tokenizer_class":"GPTNeoXTokenizerFast","chat_template":"{{ messages }}"}
""");
        Files.writeString(directory.resolve("model.safetensors.index.json"), """
                {"weight_map":{"backbone.layers.0.mixer.A_log":"model-00001-of-00001.safetensors","lm_head.weight":"model-00001-of-00001.safetensors"}}
""");
        ModelArtifactInspection safetensors = ModelArtifactInspector.inspect(directory);
        require(safetensors.present(), "Safetensors directory was not recognized");
        require("MambaForCausalLM".equals(safetensors.architectureId()), "Transformers architecture was not decoded");
        require(safetensors.contextTokens() == 65536, "Transformers context length was not decoded");
        require(safetensors.tensorCount() == 2, "Safetensors index tensor count was not decoded");
        require(safetensors.declaredCapabilities().contains("state_space"), "state-space metadata was not detected");
        com.spirit.koil.api.model.runtime.universal.KoilModelProfile profile =
                com.spirit.koil.api.model.runtime.universal.KoilModelProfiler.profile(safetensors);
        require(profile.architectureRecognized(), "Transformers class architecture did not map through the universal registry");
        require(profile.architecture().category() == com.spirit.koil.api.model.runtime.universal.KoilArchitectureCategory.STATE_SPACE,
                "Mamba Safetensors directory did not map to state-space execution requirements");
        System.out.println("model artifact inspector proof passed");
    }

    private static byte[] syntheticGguf() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("general.architecture", "qwen3moe");
        metadata.put("general.name", "Qwen proof");
        metadata.put("tokenizer.ggml.model", "gpt2");
        metadata.put("tokenizer.chat_template", "{% if tools %}tool_calls function{% endif %}{% if enable_thinking %}<think>reasoning</think>{% endif %}");
        metadata.put("qwen3moe.context_length", 131072L);
        metadata.put("qwen3moe.expert_count", 128L);
        metadata.put("qwen3moe.expert_used_count", 8L);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeIntLE(out, 0x46554747);
        writeIntLE(out, 3);
        writeLongLE(out, 0L);
        writeLongLE(out, metadata.size());
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            writeString(out, entry.getKey());
            if (entry.getValue() instanceof String value) {
                writeIntLE(out, 8);
                writeString(out, value);
            } else {
                writeIntLE(out, 10);
                writeLongLE(out, ((Number)entry.getValue()).longValue());
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        writeLongLE(out, data.length);
        out.write(data);
    }

    private static void writeIntLE(DataOutputStream out, int value) throws Exception {
        out.writeByte(value);
        out.writeByte(value >>> 8);
        out.writeByte(value >>> 16);
        out.writeByte(value >>> 24);
    }

    private static void writeLongLE(DataOutputStream out, long value) throws Exception {
        for (int shift = 0; shift < 64; shift += 8) out.writeByte((int)(value >>> shift));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
