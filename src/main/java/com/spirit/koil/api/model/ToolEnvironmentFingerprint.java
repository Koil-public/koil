package com.spirit.koil.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.tool.ModelWorkspaceRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Cheap dependency fingerprint used to reuse speculative evidence across unrelated epoch changes. */
public final class ToolEnvironmentFingerprint {
    private ToolEnvironmentFingerprint() {}

    public static String capture(ModelToolCall call, ToolExecutionPolicy policy) {
        if (policy == null) return "unknown";
        return switch (policy.freshness()) {
            case IMMUTABLE -> "immutable";
            case SESSION -> "session";
            case REMOTE -> "remote";
            case WORKSPACE -> workspace(call);
            case CONNECTION -> connection(false);
            case LIVE -> connection(true);
        };
    }

    private static String workspace(ModelToolCall call) {
        JsonObject args = call == null || call.arguments() == null ? new JsonObject() : call.arguments();
        String workspace = string(args.get("workspace"));
        String path = string(args.get("path"));
        if (workspace.isBlank() && call != null && call.toolId().startsWith("workspace.")) {
            java.util.Map<String, ModelWorkspaceRegistry.Workspace> available = ModelWorkspaceRegistry.workspaces();
            if (java.util.Set.of("workspace.list", "workspace.stat", "workspace.read", "workspace.search").contains(call.toolId())
                    && available.containsKey("project")) {
                workspace = "project";
            }
        }
        try {
            ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.inspect(workspace, path, false);
            Path target = resolved.path();
            if (!Files.exists(target)) return "workspace:" + resolved.workspace().id() + ":missing:" + resolved.relativePath();
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
            return "workspace:" + resolved.workspace().id()
                    + ":" + resolved.relativePath()
                    + ":" + attrs.lastModifiedTime().toMillis()
                    + ":" + attrs.size()
                    + ":" + attrs.isDirectory();
        } catch (IOException | RuntimeException failure) {
            return "workspace:unavailable:" + workspace + ":" + path;
        }
    }

    private static String connection(boolean live) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return "client:none";
        StringBuilder out = new StringBuilder("client")
                .append(":handler=").append(client.getNetworkHandler() == null ? "none" : "present")
                .append(":world=").append(client.world == null ? "none" : client.world.getRegistryKey().getValue());
        if (client.getNetworkHandler() != null && client.getNetworkHandler().getCommandDispatcher() != null) {
            int commandTreeHash = client.getNetworkHandler().getCommandDispatcher().getRoot().getChildren().stream()
                    .map(node -> node.getName())
                    .sorted()
                    .mapToInt(String::hashCode)
                    .reduce(1, (hash, value) -> 31 * hash + value);
            out.append(":commands=").append(Integer.toUnsignedString(commandTreeHash, 16));
        }
        if (!live) return out.toString();
        if (client.player == null) return out.append(":player=none").toString();
        out.append(":pos=").append(client.player.getBlockPos())
                .append(":slot=").append(client.player.getInventory().selectedSlot)
                .append(":yaw=").append(Math.round(client.player.getYaw() / 5.0F) * 5)
                .append(":pitch=").append(Math.round(client.player.getPitch() / 5.0F) * 5);
        HitResult hit = client.crosshairTarget;
        if (hit == null) return out.append(":target=none").toString();
        out.append(":target=").append(hit.getType());
        if (hit instanceof BlockHitResult block) out.append(':').append(block.getBlockPos()).append(':').append(block.getSide());
        else if (hit instanceof EntityHitResult entity) out.append(':').append(entity.getEntity().getId());
        return out.toString();
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().strip() : "";
    }
}
