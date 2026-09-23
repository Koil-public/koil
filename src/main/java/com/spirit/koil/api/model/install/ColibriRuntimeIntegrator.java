package com.spirit.koil.api.model.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Applies Koil's narrow, pinned Colibri gateway integration during staging. */
public final class ColibriRuntimeIntegrator {
    private static final String MODULE = "native/colibri/koil_gigatoken.py";
    private static final String ORIGINAL_PAYLOAD = """
        payload = prompt.encode("utf-8")
        if b"\\0" in payload:
""";
    private static final String LEGACY_PAYLOAD = """
        payload = prompt.encode("utf-8")
        token_ids = None
        if ARCH == "glm":
            try:
                from koil_gigatoken import encode_colibri_prompt
                token_ids = encode_colibri_prompt(self.model_dir, prompt)
            except Exception:
                token_ids = None
        if token_ids is not None:
            payload = " ".join(str(token_id) for token_id in token_ids).encode("ascii")
        if b"\\0" in payload:
""";
    private static final String INTEGRATED_PAYLOAD = LEGACY_PAYLOAD
            .replace("            except Exception:\n                token_ids = None",
                    "            except Exception as failure:\n"
                    + "                import sys\n"
                    + "                print('[GigaToken] status=fallback reason=glue_' + type(failure).__name__, file=sys.stderr, flush=True)\n"
                    + "                token_ids = None")
            .replace("        if b\"\\0\" in payload:",
                    "        import sys, hashlib\n"
                    + "        print('[Colibri] operation=payload_selected inputSha256=' + hashlib.sha256(prompt.encode('utf-8')).hexdigest()"
                    + " + ' tokenizer=' + ('gigatoken' if token_ids is not None else 'native')"
                    + " + ' idsCount=' + str(len(token_ids) if token_ids is not None else 0)"
                    + " + ' payloadBytes=' + str(len(payload)) + ' ids=' + str(token_ids is not None).lower(), file=sys.stderr, flush=True)\n"
                    + "        if b\"\\0\" in payload:");
    private static final String ORIGINAL_HEADER = """
                  + (prefix_field if prefix_field else (f" {len(xpayload)}" if xpayload else ""))
                  + "\\n").encode()
""";
    private static final String INTEGRATED_HEADER = """
                  + (prefix_field if prefix_field else (f" {len(xpayload)}" if xpayload else ""))
                  + (" ids=1" if token_ids is not None else "")
                  + "\\n").encode()
""";

    private ColibriRuntimeIntegrator() {
    }

    public static void integrate(Path stagedRuntime) throws IOException {
        Path server = find(stagedRuntime, "openai_server.py");
        if (server == null) {
            throw new IOException("Colibri staging is missing openai_server.py");
        }
        String source = Files.readString(server, StandardCharsets.UTF_8);
        if (!source.contains("from koil_gigatoken import encode_colibri_prompt")) {
            source = replaceExactly(source, ORIGINAL_PAYLOAD, INTEGRATED_PAYLOAD, "prompt intake");
            source = replaceExactly(source, ORIGINAL_HEADER, INTEGRATED_HEADER, "SUBMIT header");
        } else if (!source.contains("operation=payload_selected")) {
            source = replaceExactly(source, LEGACY_PAYLOAD, INTEGRATED_PAYLOAD, "legacy prompt diagnostics");
        }
        Files.writeString(server, source, StandardCharsets.UTF_8);
        try (InputStream input = ColibriRuntimeIntegrator.class.getClassLoader().getResourceAsStream(MODULE)) {
            if (input == null) throw new IOException("Koil JAR is missing " + MODULE);
            Files.copy(input, server.resolveSibling("koil_gigatoken.py"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String replaceExactly(String source, String before, String after, String label) throws IOException {
        int first = source.indexOf(before);
        if (first < 0 || source.indexOf(before, first + before.length()) >= 0) {
            throw new IOException("Pinned Colibri " + label + " seam did not match exactly");
        }
        return source.substring(0, first) + after + source.substring(first + before.length());
    }

    private static Path find(Path root, String fileName) throws IOException {
        try (var paths = Files.walk(root, 6)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst().orElse(null);
        }
    }
}
