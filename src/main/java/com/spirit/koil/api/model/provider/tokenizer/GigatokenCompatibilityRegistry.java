package com.spirit.koil.api.model.provider.tokenizer;

import com.spirit.koil.api.model.catalog.ModelRuntimeCompatibility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Exactness certificates for tokenizer/runtime pairs that may consume
 * Gigatoken IDs directly.
 *
 * <p>A family name is deliberately insufficient. A certificate binds the
 * Colibri engine, immutable model repository revision, tokenizer bytes and
 * the runtime tokenization contract used by Koil's differential suite.</p>
 */
public final class GigatokenCompatibilityRegistry {
    private static final Map<String, Certificate> CERTIFICATES = Map.of(
            key("glm", "mastouri/GLM-5.2-colibri-int4-g64-with-int8-mtp",
                    "6bbb01ed3e515a8730b694dfae73aadfd6774581"),
            new Certificate(
                    "glm",
                    "mastouri/GLM-5.2-colibri-int4-g64-with-int8-mtp",
                    "6bbb01ed3e515a8730b694dfae73aadfd6774581",
                    "19e773648cb4e65de8660ea6365e10acca112d42a854923df93db4a6f333a82d",
                    7,
                    "Pinned Gigatoken 0.10.0 IDs must match Colibri v1.10.1 c/tok.h with NFC disabled."
            )
    );

    private GigatokenCompatibilityRegistry() {
    }

    public static Qualification qualify(ModelRuntimeCompatibility compatibility, Path modelDirectory) {
        if (compatibility == null) {
            return Qualification.fallback("no exact runtime compatibility record");
        }
        Certificate certificate = CERTIFICATES.get(key(
                compatibility.engineId(), compatibility.modelRepository(), compatibility.modelRevision()));
        if (certificate == null) {
            return Qualification.fallback("tokenizer/runtime pair has no exact Gigatoken certificate");
        }
        Path tokenizer = modelDirectory == null ? null : modelDirectory.resolve("tokenizer.json");
        if (tokenizer == null || !Files.isRegularFile(tokenizer)) {
            return Qualification.fallback("model tokenizer.json is missing");
        }
        try {
            String actual = sha256(tokenizer);
            if (!certificate.tokenizerSha256().equals(actual)) {
                return Qualification.fallback("tokenizer fingerprint differs from the certified revision");
            }
            return new Qualification(true, certificate, tokenizer,
                    "exact tokenizer/runtime certificate matched");
        } catch (IOException exception) {
            return Qualification.fallback("tokenizer fingerprint failed: " + message(exception));
        }
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static String key(String engine, String repository, String revision) {
        return value(engine).toLowerCase(java.util.Locale.ROOT) + "\n"
                + value(repository).toLowerCase(java.util.Locale.ROOT) + "\n"
                + value(revision).toLowerCase(java.util.Locale.ROOT);
    }

    private static String value(String value) {
        return value == null ? "" : value.strip();
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record Certificate(
            String engineId,
            String repository,
            String revision,
            String tokenizerSha256,
            int bridgeLoadOperation,
            String evidence
    ) {
    }

    public record Qualification(boolean active, Certificate certificate, Path tokenizer, String detail) {
        public static Qualification fallback(String detail) {
            return new Qualification(false, null, null, detail == null ? "unsupported" : detail);
        }
    }
}
