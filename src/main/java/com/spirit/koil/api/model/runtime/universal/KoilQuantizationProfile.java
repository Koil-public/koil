package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;
import com.spirit.koil.api.model.catalog.ModelArtifactInspection;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Quantization/storage facts inferred from the artifact tensor manifest. */
public record KoilQuantizationProfile(
        KoilQuantizationClass quantizationClass,
        String dominantStorageType,
        Set<String> storageTypes,
        String evidence
) {
    public KoilQuantizationProfile {
        quantizationClass = quantizationClass == null ? KoilQuantizationClass.UNKNOWN : quantizationClass;
        dominantStorageType = safe(dominantStorageType);
        storageTypes = storageTypes == null ? Set.of() : Set.copyOf(storageTypes);
        evidence = safe(evidence);
    }

    public static KoilQuantizationProfile infer(ModelArtifactInspection inspection, KoilTensorManifestSummary manifest) {
        if (inspection == null || !inspection.present() || manifest == null) {
            return new KoilQuantizationProfile(KoilQuantizationClass.UNKNOWN, "", Set.of(), "tensor storage evidence unavailable");
        }
        Set<String> types = new LinkedHashSet<>(manifest.tensorsByStorageType().keySet());
        if (types.isEmpty()) return new KoilQuantizationProfile(KoilQuantizationClass.UNKNOWN, "", Set.of(), "tensor storage types were not retained");

        boolean hasFloat = false;
        boolean hasQuantized = false;
        boolean hasInteger = false;
        for (String raw : types) {
            String type = raw.toUpperCase(Locale.ROOT);
            if (isFloating(type)) hasFloat = true;
            else if (isKnownQuantized(type, inspection.format())) hasQuantized = true;
            else if (isInteger(type)) hasInteger = true;
            else if (inspection.format() == ModelArtifactFormat.GGUF_FILE && type.startsWith("GGML_TYPE_")) hasQuantized = true;
        }

        KoilQuantizationClass classification;
        if (hasQuantized && hasFloat) classification = KoilQuantizationClass.MIXED_PRECISION;
        else if (hasQuantized) classification = KoilQuantizationClass.QUANTIZED;
        else if (hasInteger && hasFloat) classification = KoilQuantizationClass.MIXED_PRECISION;
        else if (hasInteger) classification = KoilQuantizationClass.INTEGER_OR_PACKED;
        else if (hasFloat) classification = types.size() > 1 ? KoilQuantizationClass.MIXED_PRECISION : KoilQuantizationClass.FLOATING_POINT;
        else classification = KoilQuantizationClass.UNKNOWN;

        return new KoilQuantizationProfile(classification, manifest.dominantStorageType(), types,
                "classified from " + manifest.tensorCount() + " retained tensor descriptors");
    }

    private static boolean isFloating(String type) {
        return type.equals("F64") || type.equals("F32") || type.equals("F16") || type.equals("BF16")
                || type.equals("GGML_TYPE_0") || type.equals("GGML_TYPE_1") || type.equals("GGML_TYPE_30");
    }

    private static boolean isInteger(String type) {
        return type.equals("I64") || type.equals("I32") || type.equals("I16") || type.equals("I8")
                || type.equals("U64") || type.equals("U32") || type.equals("U16") || type.equals("U8") || type.equals("BOOL");
    }

    private static boolean isKnownQuantized(String type, ModelArtifactFormat format) {
        if (format != ModelArtifactFormat.GGUF_FILE) return false;
        // GGML keeps F32=0, F16=1 and BF16=30 stable; other retained GGML storage ids are
        // conservatively treated as quantized/packed rather than claiming a family-specific name.
        return type.startsWith("GGML_TYPE_") && !isFloating(type);
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
