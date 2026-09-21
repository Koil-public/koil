package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Provider-neutral rich-content normalization boundary. */
public interface ContentProvider extends AutoCloseable {
    String id();
    CompletableFuture<JsonObject> normalize(Path localFile);
    CompletableFuture<JsonObject> normalize(URI remoteUri);
    default JsonObject diagnostics() { return new JsonObject(); }
    @Override default void close() { }
}
