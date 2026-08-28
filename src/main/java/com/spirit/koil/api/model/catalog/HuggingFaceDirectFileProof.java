package com.spirit.koil.api.model.catalog;

/** Optional bounded network proof for exact Hugging Face GGUF-link registration. */
public final class HuggingFaceDirectFileProof {
    private static final String FILE = "Qwen3.5-4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf";
    private static final String BLOB = "https://huggingface.co/Unrestricted/"
            + "Qwen3.5-4B-Uncensored-HauhauCS-Aggressive/blob/main/" + FILE;
    private static final String RESOLVE = "https://huggingface.co/Unrestricted/"
            + "Qwen3.5-4B-Uncensored-HauhauCS-Aggressive/resolve/main/" + FILE + "?download=true";

    private HuggingFaceDirectFileProof() {
    }

    public static void main(String[] args) {
        prove(BLOB);
        prove(RESOLVE);
        System.out.println("Hugging Face direct-file proof passed.");
    }

    private static void prove(String url) {
        HuggingFaceLocalModelDiscovery.DirectFileResult result =
                LocalModelCatalog.registerDirectFile(url).join();
        require(result.resolved() && result.entry() != null, "direct file was not resolved: " + result.detail());
        LocalModelCatalogEntry entry = result.entry();
        require(entry.runnable() && entry.artifacts().size() == 1, "direct file was not runnable");
        ModelArtifact artifact = entry.artifacts().get(0);
        require(FILE.equals(artifact.fileName()), "direct filename changed");
        require(artifact.sizeBytes() == 2_707_513_696L, "direct LFS size was not preserved");
        require("79e28ecacf84e75b6056cf4059636d435aa9eb67795780f7b7dbc7d32a962741"
                        .equals(artifact.sha256()),
                "direct LFS SHA-256 was not preserved");
        require(entry.toolCalling(), "model chat template tool capability was not detected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
