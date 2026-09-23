package com.spirit.koil.api.model.catalog;

/** Physical installation shape consumed by a local-model runtime. */
public enum ModelArtifactFormat {
    GGUF_FILE,
    SAFETENSORS_DIRECTORY,
    COLIBRI_CONTAINER,
    MLX_DIRECTORY,
    ONNX_DIRECTORY,
    UNKNOWN
}
