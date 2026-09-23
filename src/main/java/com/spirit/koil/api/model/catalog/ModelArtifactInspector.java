package com.spirit.koil.api.model.catalog;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, dependency-free inspection of installed model artifacts.
 *
 * <p>Koil never reads tensor payloads here. The inspector consumes only the
 * format header and metadata section, retaining a small compatibility subset.
 * Malformed or unsupported artifacts fail closed and remain loadable by the
 * owning runtime, which is still authoritative for tensor execution.</p>
 */
public final class ModelArtifactInspector {
    private static final int GGUF_MAGIC = 0x46554747; // "GGUF" when read little-endian
    private static final int MAX_METADATA_ENTRIES = 200_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ARRAY_VALUES = 4_000_000;
    private static final int MAX_TENSOR_DIMS = 8;
    private static final int MAX_SAFETENSOR_SHARDS = 4096;
    private static final long MAX_SAFETENSORS_HEADER_BYTES = 100L * 1024L * 1024L;

    private ModelArtifactInspector() {
    }

    public static ModelArtifactInspection inspect(Path file, ModelArtifactFormat expectedFormat) {
        ModelArtifactFormat format = expectedFormat == null ? inferFormat(file) : expectedFormat;
        if (format == ModelArtifactFormat.GGUF_FILE) {
            return inspectGguf(file);
        }
        if (format == ModelArtifactFormat.SAFETENSORS_DIRECTORY) {
            return inspectSafetensorsDirectory(file);
        }
        return ModelArtifactInspection.unavailable(format, "artifact inspection is not implemented for " + format.name());
    }

    public static ModelArtifactInspection inspect(Path file) {
        return inspect(file, inferFormat(file));
    }

    private static ModelArtifactFormat inferFormat(Path file) {
        if (file == null || file.getFileName() == null) return ModelArtifactFormat.UNKNOWN;
        if (Files.isDirectory(file)) {
            if (Files.isRegularFile(file.resolve("config.json")) && (Files.isRegularFile(file.resolve("model.safetensors"))
                    || Files.isRegularFile(file.resolve("model.safetensors.index.json")))) {
                return ModelArtifactFormat.SAFETENSORS_DIRECTORY;
            }
            return ModelArtifactFormat.UNKNOWN;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gguf") ? ModelArtifactFormat.GGUF_FILE : ModelArtifactFormat.UNKNOWN;
    }

    private static ModelArtifactInspection inspectSafetensorsDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return ModelArtifactInspection.unavailable(ModelArtifactFormat.SAFETENSORS_DIRECTORY, "Safetensors model directory is unavailable");
        }
        try {
            Path root = directory.toAbsolutePath().normalize();
            Map<String, Object> config = SafeJsonMetadataReader.readObject(root.resolve("config.json"));
            if (config.isEmpty()) {
                return ModelArtifactInspection.unavailable(ModelArtifactFormat.SAFETENSORS_DIRECTORY, "config.json is unavailable");
            }
            Map<String, Object> tokenizer = SafeJsonMetadataReader.readObject(root.resolve("tokenizer_config.json"));
            Map<String, Object> generation = SafeJsonMetadataReader.readObject(root.resolve("generation_config.json"));
            Map<String, Object> index = SafeJsonMetadataReader.readObject(root.resolve("model.safetensors.index.json"));

            String architecture = firstString(config.get("architectures"));
            if (architecture.isBlank()) architecture = scalar(config.get("model_type"));
            String modelType = scalar(config.get("model_type"));
            String name = scalar(config.get("_name_or_path"));
            String tokenizerFamily = scalar(tokenizer.get("tokenizer_class"));
            String template = scalar(tokenizer.get("chat_template"));
            int context = firstPositiveInt(config, "max_position_embeddings", "max_sequence_length", "seq_length", "n_positions");
            int experts = firstPositiveInt(config, "num_experts", "n_routed_experts", "num_local_experts");
            int activeExperts = firstPositiveInt(config, "num_experts_per_tok", "num_selected_experts", "top_k");

            Map<String, String> retained = new LinkedHashMap<>();
            flatten("config", config, retained, 0);
            flatten("tokenizer", tokenizer, retained, 0);
            flatten("generation", generation, retained, 0);

            TensorManifestStats tensorManifest = inspectSafetensorsManifest(index, root);
            tensorManifest.appendMetadata(retained);
            long tensorCount = tensorManifest.tensorCount() > 0 ? tensorManifest.tensorCount() : tensorCount(index, root);

            Set<String> declared = new LinkedHashSet<>();
            if (!template.isBlank()) declared.add("chat_template");
            String lowerTemplate = template.toLowerCase(Locale.ROOT);
            if (containsAny(lowerTemplate, "tool_calls", "tools", "function")) declared.add("template_tools");
            if (containsAny(lowerTemplate, "reasoning", "thinking", "<think", "enable_thinking")) declared.add("template_reasoning");
            if (experts > 0 || activeExperts > 0) declared.add("moe");
            if (retained.keySet().stream().anyMatch(key -> key.contains("ssm") || key.contains("state_space"))) declared.add("state_space");
            if (retained.keySet().stream().anyMatch(key -> key.contains("conv"))) declared.add("convolution");

            return new ModelArtifactInspection(true, ModelArtifactFormat.SAFETENSORS_DIRECTORY, 1, tensorCount,
                    architecture, name, modelType, tokenizerFamily, template, context, experts, activeExperts,
                    declared, tensorManifest.tensors(), Map.copyOf(retained), "Hugging Face Safetensors directory metadata");
        } catch (Exception exception) {
            return ModelArtifactInspection.unavailable(ModelArtifactFormat.SAFETENSORS_DIRECTORY,
                    "Safetensors metadata could not be inspected: " + safeMessage(exception));
        }
    }

    private static TensorManifestStats inspectSafetensorsManifest(Map<String, Object> index, Path root) throws IOException {
        LinkedHashSet<Path> shards = new LinkedHashSet<>();
        Object weightMap = index.get("weight_map");
        if (weightMap instanceof Map<?, ?> map) {
            for (Object raw : map.values()) {
                if (!(raw instanceof String name) || name.isBlank()) continue;
                Path candidate = root.resolve(name).normalize();
                if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) continue;
                shards.add(candidate);
                if (shards.size() > MAX_SAFETENSOR_SHARDS) throw new IOException("Safetensors model declares too many shards");
            }
        }
        if (shards.isEmpty()) {
            Path single = root.resolve("model.safetensors");
            if (Files.isRegularFile(single)) shards.add(single);
        }
        if (shards.isEmpty()) return TensorManifestStats.incomplete("no Safetensors weight shards were found");

        TensorManifestAccumulator accumulator = new TensorManifestAccumulator();
        int parsedShards = 0;
        for (Path shard : shards) {
            Map<String, Object> header = readSafetensorsHeader(shard);
            parsedShards++;
            for (Map.Entry<String, Object> entry : header.entrySet()) {
                if ("__metadata__".equals(entry.getKey()) || !(entry.getValue() instanceof Map<?, ?> rawInfo)) continue;
                String dtype = scalar(rawInfo.get("dtype")).toUpperCase(Locale.ROOT);
                List<Long> shape = tensorShape(rawInfo.get("shape"));
                long parameters = shapeProduct(shape);
                long storageBytes = dataOffsetSpan(rawInfo.get("data_offsets"));
                long dataOffset = dataOffsetStart(rawInfo.get("data_offsets"));
                accumulator.add(new ModelTensorDescriptor(entry.getKey(), shape,
                        dtype.isBlank() ? "UNKNOWN" : dtype, dataOffset, storageBytes, shard.getFileName().toString()), parameters);
            }
        }
        return accumulator.finish(parsedShards, true, "Safetensors headers parsed without loading tensor payloads");
    }

    private static Map<String, Object> readSafetensorsHeader(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 8192))) {
            long headerLength = readLongLE(in);
            if (headerLength < 2 || headerLength > MAX_SAFETENSORS_HEADER_BYTES || headerLength > Integer.MAX_VALUE) {
                throw new IOException("invalid Safetensors header length");
            }
            byte[] header = in.readNBytes((int)headerLength);
            if (header.length != (int)headerLength) throw new EOFException("truncated Safetensors header");
            return SafeJsonMetadataReader.readObject(header);
        }
    }

    private static TensorManifestStats inspectGgufTensorManifest(DataInputStream in, long tensorCount) throws IOException {
        TensorManifestAccumulator accumulator = new TensorManifestAccumulator();
        for (long i = 0; i < tensorCount; i++) {
            String tensorName = readString(in);
            long rawDims = Integer.toUnsignedLong(readIntLE(in));
            if (rawDims <= 0 || rawDims > MAX_TENSOR_DIMS) throw new IOException("invalid GGUF tensor dimension count " + rawDims);
            long parameters = 1L;
            List<Long> shape = new ArrayList<>((int) rawDims);
            for (int dim = 0; dim < (int)rawDims; dim++) {
                long extent = readLongLE(in);
                if (extent < 0) throw new IOException("invalid GGUF tensor extent");
                shape.add(extent);
                parameters = saturatedMultiply(parameters, extent);
            }
            int ggmlType = readIntLE(in);
            long dataOffset = readLongLE(in); // payload is never read here
            String storageType = "GGML_TYPE_" + Integer.toUnsignedString(ggmlType);
            accumulator.add(new ModelTensorDescriptor(tensorName, shape, storageType, dataOffset, 0L, ""), parameters);
        }
        return accumulator.finish(1, true, "GGUF tensor descriptors parsed without loading tensor payloads");
    }

    private static List<Long> tensorShape(Object value) throws IOException {
        if (!(value instanceof List<?> shape)) return List.of();
        List<Long> result = new ArrayList<>(shape.size());
        for (Object raw : shape) {
            if (!(raw instanceof Number number)) throw new IOException("Safetensors tensor shape contains a non-numeric extent");
            long extent = number.longValue();
            if (extent < 0) throw new IOException("Safetensors tensor shape contains a negative extent");
            result.add(extent);
        }
        return List.copyOf(result);
    }

    private static long shapeProduct(List<Long> shape) {
        if (shape == null || shape.isEmpty()) return 0L;
        long product = 1L;
        for (long extent : shape) product = saturatedMultiply(product, extent);
        return product;
    }

    private static long dataOffsetStart(Object value) throws IOException {
        if (!(value instanceof List<?> offsets) || offsets.size() != 2
                || !(offsets.get(0) instanceof Number startNumber)) return 0L;
        long start = startNumber.longValue();
        if (start < 0) throw new IOException("invalid Safetensors tensor data offset");
        return start;
    }

    private static long dataOffsetSpan(Object value) throws IOException {
        if (!(value instanceof List<?> offsets) || offsets.size() != 2
                || !(offsets.get(0) instanceof Number startNumber)
                || !(offsets.get(1) instanceof Number endNumber)) return 0L;
        long start = startNumber.longValue();
        long end = endNumber.longValue();
        if (start < 0 || end < start) throw new IOException("invalid Safetensors tensor data offsets");
        return end - start;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0 || right == 0) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (left >= Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private record TensorManifestStats(
            long tensorCount,
            long parameterCount,
            long storageBytes,
            int shardCount,
            Map<String, Long> tensorsByType,
            Map<String, Long> parametersByType,
            List<ModelTensorDescriptor> tensors,
            boolean complete,
            String evidence
    ) {
        private static TensorManifestStats incomplete(String evidence) {
            return new TensorManifestStats(0L, 0L, 0L, 0, Map.of(), Map.of(), List.of(), false, evidence);
        }

        private void appendMetadata(Map<String, String> target) {
            target.put("tensor_manifest.tensor_count", Long.toString(tensorCount));
            target.put("tensor_manifest.parameter_count", Long.toString(parameterCount));
            target.put("tensor_manifest.storage_bytes", Long.toString(storageBytes));
            target.put("tensor_manifest.shard_count", Integer.toString(shardCount));
            target.put("tensor_manifest.complete", Boolean.toString(complete));
            target.put("tensor_manifest.evidence", evidence == null ? "" : evidence);
            for (Map.Entry<String, Long> entry : tensorsByType.entrySet()) {
                String key = normalizeStorageType(entry.getKey());
                target.put("tensor_manifest.type." + key + ".tensors", Long.toString(entry.getValue()));
                target.put("tensor_manifest.type." + key + ".parameters",
                        Long.toString(parametersByType.getOrDefault(entry.getKey(), 0L)));
            }
        }
    }

    private static final class TensorManifestAccumulator {
        private long tensorCount;
        private long parameterCount;
        private long storageBytes;
        private final Map<String, Long> tensorsByType = new LinkedHashMap<>();
        private final Map<String, Long> parametersByType = new LinkedHashMap<>();
        private final List<ModelTensorDescriptor> tensors = new ArrayList<>();

        private void add(ModelTensorDescriptor tensor, long parameters) {
            String normalized = tensor.storageType() == null || tensor.storageType().isBlank()
                    ? "UNKNOWN" : tensor.storageType().strip().toUpperCase(Locale.ROOT);
            tensorCount = saturatedAdd(tensorCount, 1L);
            parameterCount = saturatedAdd(parameterCount, Math.max(0L, parameters));
            storageBytes = saturatedAdd(storageBytes, tensor.storageBytes());
            tensorsByType.merge(normalized, 1L, ModelArtifactInspector::saturatedAdd);
            parametersByType.merge(normalized, Math.max(0L, parameters), ModelArtifactInspector::saturatedAdd);
            tensors.add(tensor);
        }

        private TensorManifestStats finish(int shardCount, boolean complete, String evidence) {
            return new TensorManifestStats(tensorCount, parameterCount, storageBytes, Math.max(0, shardCount),
                    Map.copyOf(tensorsByType), Map.copyOf(parametersByType), List.copyOf(tensors), complete, evidence);
        }
    }

    private static String normalizeStorageType(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static long tensorCount(Map<String, Object> index, Path root) throws IOException {
        Object weightMap = index.get("weight_map");
        if (weightMap instanceof Map<?, ?> map) return map.size();
        Path single = root.resolve("model.safetensors");
        if (!Files.isRegularFile(single)) return 0L;
        Map<String, Object> parsed = readSafetensorsHeader(single);
        return parsed.keySet().stream().filter(key -> !"__metadata__".equals(key)).count();
    }

    private static void flatten(String prefix, Map<String, Object> source, Map<String, String> target, int depth) {
        if (depth > 4 || source == null) return;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (target.size() >= 4096) return;
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> cast = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nestedEntry : nested.entrySet()) {
                    if (nestedEntry.getKey() instanceof String nestedKey) cast.put(nestedKey, nestedEntry.getValue());
                }
                flatten(key, cast, target, depth + 1);
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                target.put(key, String.valueOf(value));
            }
        }
    }

    private static String firstString(Object value) {
        if (value instanceof String string) return string.strip();
        if (value instanceof java.util.List<?> list && !list.isEmpty()) return scalar(list.get(0));
        return "";
    }

    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean ? String.valueOf(value).strip() : "";
    }

    private static int firstPositiveInt(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof Number number) return (int)Math.max(0L, Math.min(Integer.MAX_VALUE, number.longValue()));
            if (value instanceof String string) {
                try { return (int)Math.max(0L, Math.min(Integer.MAX_VALUE, Long.parseLong(string))); }
                catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    private static ModelArtifactInspection inspectGguf(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return ModelArtifactInspection.unavailable(ModelArtifactFormat.GGUF_FILE, "GGUF file is unavailable");
        }
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 64 * 1024))) {
            int magic = readIntLE(in);
            if (magic != GGUF_MAGIC) {
                return ModelArtifactInspection.unavailable(ModelArtifactFormat.GGUF_FILE, "file is not a GGUF artifact");
            }
            int version = readIntLE(in);
            if (version < 2 || version > 3) {
                return ModelArtifactInspection.unavailable(ModelArtifactFormat.GGUF_FILE, "unsupported GGUF metadata version " + version);
            }
            long tensorCount = readLongLE(in);
            long metadataCount = readLongLE(in);
            if (tensorCount < 0 || metadataCount < 0 || metadataCount > MAX_METADATA_ENTRIES) {
                return ModelArtifactInspection.unavailable(ModelArtifactFormat.GGUF_FILE, "invalid GGUF header counts");
            }

            Map<String, String> values = new LinkedHashMap<>();
            for (long i = 0; i < metadataCount; i++) {
                String key = readString(in);
                int type = readIntLE(in);
                String value = readValue(in, type, key);
                if (shouldRetain(key) && value != null) values.put(key, value);
            }
            TensorManifestStats tensorManifest = inspectGgufTensorManifest(in, tensorCount);
            tensorManifest.appendMetadata(values);
            return fromGguf(version, tensorCount, values, tensorManifest.tensors());
        } catch (Exception exception) {
            return ModelArtifactInspection.unavailable(
                    ModelArtifactFormat.GGUF_FILE,
                    "GGUF metadata could not be inspected: " + safeMessage(exception)
            );
        }
    }

    private static ModelArtifactInspection fromGguf(int version, long tensorCount, Map<String, String> values, List<ModelTensorDescriptor> tensors) {
        String architecture = values.getOrDefault("general.architecture", "");
        String name = values.getOrDefault("general.name", "");
        String type = values.getOrDefault("general.type", "");
        String tokenizer = values.getOrDefault("tokenizer.ggml.model", "");
        String template = values.getOrDefault("tokenizer.chat_template", "");
        int context = firstInteger(values, ".context_length");
        int experts = firstInteger(values, "expert_count", "expert_count");
        int activeExperts = firstInteger(values, "expert_used_count", "expert_used_count");

        Set<String> declared = new LinkedHashSet<>();
        declared.add("openai_chat");
        String lowerTemplate = template.toLowerCase(Locale.ROOT);
        if (containsAny(lowerTemplate, "tool_calls", "tools", "function")) declared.add("template_tools");
        if (containsAny(lowerTemplate, "reasoning", "thinking", "<think", "enable_thinking")) declared.add("template_reasoning");
        if (experts > 0 || activeExperts > 0) declared.add("moe");
        if (values.keySet().stream().anyMatch(key -> key.contains(".ssm.") || key.contains(".recurrent."))) {
            declared.add("hybrid_sequence");
        }
        if (!template.isBlank()) declared.add("embedded_chat_template");

        return new ModelArtifactInspection(
                true,
                ModelArtifactFormat.GGUF_FILE,
                version,
                tensorCount,
                architecture,
                name,
                type,
                tokenizer,
                template,
                context,
                experts,
                activeExperts,
                declared,
                tensors,
                Map.copyOf(values),
                "GGUF v" + version + " metadata"
        );
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static int firstInteger(Map<String, String> values, String suffixOrFragment) {
        return firstInteger(values, suffixOrFragment, suffixOrFragment);
    }

    private static int firstInteger(Map<String, String> values, String suffix, String fragment) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().endsWith(suffix) || entry.getKey().contains(fragment)) {
                try {
                    long parsed = Long.parseLong(entry.getValue());
                    return (int)Math.max(0L, Math.min(Integer.MAX_VALUE, parsed));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static boolean shouldRetain(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("general.architecture")
                || normalized.equals("general.name")
                || normalized.equals("general.type")
                || normalized.equals("general.file_type")
                || normalized.equals("general.quantization_version")
                || normalized.equals("general.alignment")
                || normalized.equals("tokenizer.chat_template")
                || normalized.equals("tokenizer.ggml.model")
                || normalized.equals("tokenizer.ggml.bos_token_id")
                || normalized.equals("tokenizer.ggml.eos_token_id")
                || normalized.equals("tokenizer.ggml.add_bos_token")
                || normalized.endsWith(".context_length")
                || normalized.endsWith(".embedding_length")
                || normalized.endsWith(".block_count")
                || normalized.endsWith(".layer_count")
                || normalized.contains(".attention.")
                || normalized.contains(".rope.")
                || normalized.contains(".ssm.")
                || normalized.contains(".recurrent.")
                || normalized.contains("expert_count")
                || normalized.contains("expert_used_count");
    }

    private static String readValue(DataInputStream in, int type, String key) throws IOException {
        return switch (type) {
            case 0 -> Integer.toUnsignedString(in.readUnsignedByte());
            case 1 -> Byte.toString(in.readByte());
            case 2 -> Integer.toUnsignedString(readUnsignedShortLE(in));
            case 3 -> Short.toString(readShortLE(in));
            case 4 -> Long.toUnsignedString(Integer.toUnsignedLong(readIntLE(in)));
            case 5 -> Integer.toString(readIntLE(in));
            case 6 -> Float.toString(Float.intBitsToFloat(readIntLE(in)));
            case 7 -> Boolean.toString(in.readUnsignedByte() != 0);
            case 8 -> readString(in);
            case 9 -> readArray(in, key);
            case 10 -> Long.toUnsignedString(readLongLE(in));
            case 11 -> Long.toString(readLongLE(in));
            case 12 -> Double.toString(Double.longBitsToDouble(readLongLE(in)));
            default -> throw new IOException("unsupported GGUF metadata type " + type);
        };
    }

    private static String readArray(DataInputStream in, String key) throws IOException {
        int elementType = readIntLE(in);
        long count = readLongLE(in);
        if (count < 0 || count > MAX_ARRAY_VALUES) throw new IOException("invalid GGUF array length");
        boolean retain = shouldRetain(key) && count <= 128;
        if (!retain) {
            skipArrayValues(in, elementType, count);
            return "[" + count + " values]";
        }
        StringBuilder result = new StringBuilder();
        for (long i = 0; i < count; i++) {
            String value = readValue(in, elementType, key + "[]");
            if (i > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private static void skipArrayValues(DataInputStream in, int type, long count) throws IOException {
        int fixedWidth = switch (type) {
            case 0, 1, 7 -> 1;
            case 2, 3 -> 2;
            case 4, 5, 6 -> 4;
            case 10, 11, 12 -> 8;
            default -> 0;
        };
        if (fixedWidth > 0) {
            skipFully(in, Math.multiplyExact(count, fixedWidth));
            return;
        }
        if (type == 8) {
            for (long i = 0; i < count; i++) {
                long length = readLongLE(in);
                if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid GGUF string length");
                skipFully(in, length);
            }
            return;
        }
        if (type == 9) {
            for (long i = 0; i < count; i++) {
                int nestedType = readIntLE(in);
                long nestedCount = readLongLE(in);
                if (nestedCount < 0 || nestedCount > MAX_ARRAY_VALUES) throw new IOException("invalid GGUF array length");
                skipArrayValues(in, nestedType, nestedCount);
            }
            return;
        }
        throw new IOException("unsupported GGUF array element type " + type);
    }

    private static void skipFully(DataInputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) throw new EOFException("truncated GGUF metadata");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        long length = readLongLE(in);
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid GGUF string length");
        byte[] data = in.readNBytes((int)length);
        if (data.length != (int)length) throw new EOFException("truncated GGUF string");
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int readUnsignedShortLE(DataInputStream in) throws IOException {
        return Short.toUnsignedInt(readShortLE(in));
    }

    private static short readShortLE(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return (short)(b0 | (b1 << 8));
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long readLongLE(DataInputStream in) throws IOException {
        long b0 = in.readUnsignedByte();
        long b1 = in.readUnsignedByte();
        long b2 = in.readUnsignedByte();
        long b3 = in.readUnsignedByte();
        long b4 = in.readUnsignedByte();
        long b5 = in.readUnsignedByte();
        long b6 = in.readUnsignedByte();
        long b7 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
                | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56);
    }

    private static String safeMessage(Exception exception) {
        String message = exception == null ? "unknown error" : exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
