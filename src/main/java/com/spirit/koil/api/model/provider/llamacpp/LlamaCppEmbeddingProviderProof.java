package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.retrieval.EmbeddingIdentity;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Endpoint proof for the OpenAI-compatible llama.cpp embedding route. */
public final class LlamaCppEmbeddingProviderProof {
    private LlamaCppEmbeddingProviderProof() {
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            require("Bearer proof-key".equals(exchange.getRequestHeaders().getFirst("Authorization")), "API key must be retained");
            byte[] body = "{\"data\":[{\"index\":1,\"embedding\":[0,1,0,0,0,0,0,0]},{\"index\":0,\"embedding\":[1,0,0,0,0,0,0,0]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (LlamaCppEmbeddingProvider provider = new LlamaCppEmbeddingProvider("127.0.0.1", server.getAddress().getPort(),
                "proof-key", new EmbeddingIdentity("llama_cpp", "proof-embedding", "r1", 8, true))) {
            List<float[]> vectors = provider.embed(List.of("first", "second")).join();
            require(vectors.size() == 2 && vectors.get(0)[0] == 1.0F && vectors.get(1)[1] == 1.0F,
                    "response rows must be returned in request order");
        } finally {
            server.stop(0);
        }
        System.out.println("LlamaCppEmbeddingProviderProof: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
