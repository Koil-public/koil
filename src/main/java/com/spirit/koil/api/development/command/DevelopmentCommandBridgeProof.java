package com.spirit.koil.api.development.command;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Build-time proof for command validation and atomic queue/result storage. */
public final class DevelopmentCommandBridgeProof {
    private DevelopmentCommandBridgeProof() {
    }

    public static void main(String[] arguments) throws Exception {
        Path proofRoot = arguments.length == 0
                ? Path.of("build", "development-command-bridge-proof")
                : Path.of(arguments[0]);
        Path current = proofRoot.resolve("run-" + UUID.randomUUID());
        DevelopmentCommandFileStore store = new DevelopmentCommandFileStore(current);
        store.initialize();

        require(accepted("/time set day"), "slash-prefixed Minecraft command was rejected");
        require(accepted("/unknowncommand"), "explicit unknown slash command should reach Minecraft for normal feedback");
        require(DevelopmentCommandValidation.validate("time set day", "time"::equals).accepted(),
                "known command without a slash was rejected");
        require(!DevelopmentCommandValidation.validate("hello everyone", "time"::equals).accepted(),
                "normal chat without a slash was accepted");
        require(!accepted("/bash -c whoami"), "shell command was accepted");
        require(!accepted("/java Example.java"), "Java runtime command was accepted");
        require(!accepted("/Users/example/file"), "file path was accepted");
        require(!accepted("/time set day\nsay hidden"), "multiline input was accepted");
        require(!accepted("/time\0set day"), "null character was accepted");
        require(!accepted("/public class Example {}"), "Java source was accepted");
        require(!accepted("/" + "x".repeat(257)), "overlong command was accepted");

        String id = "command-proof";
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("command", "/time set day");
        request.addProperty("submittedBy", "codex");
        request.addProperty("createdAt", Instant.now().toString());
        Path inboxFile = current.resolve("requests").resolve(id + ".json");
        Files.writeString(inboxFile, request.toString(), StandardCharsets.UTF_8);
        Path processing = store.claim(inboxFile);
        require(!Files.exists(inboxFile), "claimed request remained in requests");
        require(store.readObject(processing).get("id").getAsString().equals(id),
                "claimed request was not readable");

        JsonObject status = new JsonObject();
        status.addProperty("ready", true);
        store.writeStatus(status);
        require(Files.readString(store.statusPath()).contains("\"ready\": true"),
                "atomic status write failed");

        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("status", "sent");
        store.writeResult(id, result);
        store.complete(processing);
        require(store.resultExists(id), "result file was not persisted");
        require(!Files.exists(processing), "completed processing request was not removed");

        System.out.println("Development command bridge proof passed: validation boundary and atomic file queue.");
    }

    private static boolean accepted(String command) {
        return DevelopmentCommandValidation.validate(command, root -> true).accepted();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
