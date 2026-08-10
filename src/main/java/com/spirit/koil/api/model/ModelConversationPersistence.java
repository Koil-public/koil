package com.spirit.koil.api.model;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded persistence for visible messages; hidden provider reasoning is never stored. */
final class ModelConversationPersistence {
    private static final Path PATH = Path.of("koil", "sys", "model", "conversation-history.json");
    private static final List<String> IDS = List.of(ModelConversationRegistry.GENERAL, ModelConversationRegistry.AUTOMATION);

    private ModelConversationPersistence() {}

    static synchronized void restore(ModelConversationRegistry registry) {
        try {
            if (!Files.isRegularFile(PATH)) return;
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String id : IDS) {
                ModelConversation conversation = registry.conversation(id);
                if (!conversation.snapshot().isEmpty() || !root.has(id) || !root.get(id).isJsonArray()) continue;
                for (var element : root.getAsJsonArray(id)) {
                    if (!element.isJsonObject()) continue;
                    JsonObject row = element.getAsJsonObject();
                    try {
                        conversation.add(new ModelMessage(
                                row.has("id") ? UUID.fromString(row.get("id").getAsString()) : null,
                                row.has("role") ? ModelRole.valueOf(row.get("role").getAsString()) : ModelRole.USER,
                                row.has("content") ? row.get("content").getAsString() : "",
                                row.has("toolCallId") ? row.get("toolCallId").getAsString() : "",
                                row.has("createdAt") ? Instant.parse(row.get("createdAt").getAsString()) : null,
                                Map.of()
                        ));
                    } catch (RuntimeException ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    static synchronized void save(ModelConversationRegistry registry) {
        try {
            JsonObject root = new JsonObject();
            for (String id : IDS) {
                JsonArray rows = new JsonArray();
                for (ModelMessage message : registry.conversation(id).snapshot()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("id", message.id().toString());
                    row.addProperty("role", message.role().name());
                    row.addProperty("content", message.content());
                    row.addProperty("toolCallId", message.toolCallId());
                    row.addProperty("createdAt", message.createdAt().toString());
                    rows.add(row);
                }
                root.add(id, rows);
            }
            Path parent = PATH.toAbsolutePath().normalize().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "conversation-history-", ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            try { Files.move(temporary, PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception unsupported) { Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception ignored) {}
    }
}
