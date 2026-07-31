package com.spirit.koil.api.model.catalog;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Optional network proof for maintainers updating the pinned model catalog.
 * Normal builds do not run this proof because it depends on Hugging Face.
 */
public final class LocalModelCatalogRemoteProof {
    private LocalModelCatalogRemoteProof() {
    }

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        int checked = 0;
        for (LocalModelCatalogEntry entry : LocalModelCatalog.entries()) {
            for (ModelArtifact artifact : entry.artifacts()) {
                HttpRequest request = HttpRequest.newBuilder(artifact.downloadUri())
                        .timeout(Duration.ofSeconds(30))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", "Koil-LocalModelCatalogProof/1")
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                require(response.statusCode() >= 200 && response.statusCode() < 400,
                        entry.id() + " returned HTTP " + response.statusCode());
                long remoteSize = response.headers()
                        .firstValue("x-linked-size")
                        .or(() -> response.headers().firstValue("content-length"))
                        .map(Long::parseLong)
                        .orElse(-1L);
                String remoteDigest = response.headers()
                        .firstValue("x-linked-etag")
                        .or(() -> response.headers().firstValue("etag"))
                        .map(LocalModelCatalogRemoteProof::normalizeEtag)
                        .orElse("");
                require(remoteSize == artifact.sizeBytes(),
                        entry.id() + " size drifted for " + artifact.fileName()
                                + ": expected " + artifact.sizeBytes() + ", remote " + remoteSize);
                require(artifact.sha256().equals(remoteDigest),
                        entry.id() + " digest drifted for " + artifact.fileName());
                checked++;
            }
        }
        System.out.println("Local model remote catalog proof passed for "
                + LocalModelCatalog.entries().size() + " models and " + checked + " artifacts.");
    }

    private static String normalizeEtag(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("w/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
