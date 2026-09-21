package com.spirit.koil.api.model.catalog;

/** Optional bounded network proof for exact Hugging Face GGUF-link registration. */
public final class HuggingFaceDirectFileProof {
    private static final String FILE = "Qwen3.5-4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf";
    private static final String BLOB = "https://huggingface.co/Unrestricted/"
            + "Qwen3.5-4B-Uncensored-HauhauCS-Aggressive/blob/main/" + FILE;
    private static final String RESOLVE = "https://huggingface.co/Unrestricted/"
            + "Qwen3.5-4B-Uncensored-HauhauCS-Aggressive/resolve/main/" + FILE + "?download=true";
    private static final String HUGGING_BAY = "https://huggingbay.xyz/api/downloads/"
            + "hf-model-quantfactory-qwen2-5-7b-instruct-1m-gguf/Qwen2.5-7B-Instruct-1M.Q4_K_M.gguf";

    private HuggingFaceDirectFileProof() {
    }

    public static void main(String[] args) {
        prove(BLOB);
        prove(RESOLVE);
        proveHuggingBay();
        proveHuggingBayCatalogSearch();
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

    private static void proveHuggingBay() {
        HuggingFaceLocalModelDiscovery.DirectFileResult result =
                LocalModelCatalog.registerDirectFile(HUGGING_BAY).join();
        require(result.resolved() && result.entry() != null, "Hugging Bay file was not resolved: " + result.detail());
        ModelArtifact artifact = result.entry().artifacts().get(0);
        require("Qwen2.5-7B-Instruct-1M.Q4_K_M.gguf".equals(artifact.fileName()),
                "Hugging Bay filename changed");
        require(artifact.sizeBytes() == 4_683_073_760L, "Hugging Bay content length was not preserved");
        require("18c7eb5d7b697ea4540bc56b4f03301e4e64dda92045c89d35f8b650dc8e88b3"
                        .equals(artifact.sha256()),
                "Hugging Bay SHA-256 was not preserved");
    }

    private static void proveHuggingBayCatalogSearch() {
        HuggingFaceLocalModelDiscovery.SearchResult result = LocalModelCatalog.searchRemote("qwen3 1.7b").join();
        require(result.detail().contains("Hugging Bay"), "Hugging Bay search was not reported: " + result.detail());
        LocalModelCatalogEntry entry = LocalModelCatalog.find("qwen3-1.7b-q8").orElseThrow();
        require(entry.artifacts().stream().anyMatch(artifact -> "huggingbay.xyz".equals(artifact.downloadUri().getHost())),
                "Hugging Bay catalog result was not mapped into /model list state");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
