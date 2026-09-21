package com.spirit.koil.api.design.particle;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cosmetic screen-space sprite command.
 *
 * <p>Examples:</p>
 * <pre>
 * /sprite scene place block.minecraft.piston cursor 1 1
 * /sprite scene place item.minecraft.trident at 120 70 12 2.0
 * /sprite scene playground
 * /sprite scene playground load my_scene
 * /sprite scene load my_scene
 * /sprite scene clear
 * /sprite particle spawn minecraft.heart from 120 70 12 2.0 with particle_collision=true;contact_mode=bounce
 * </pre>
 * Use vanilla `/execute as &lt;player&gt; run sprite ...` to target another player.
 */
public final class ScreenSpriteServerBridge {
    private ScreenSpriteServerBridge() { }

    public static void registerCommands() {
        // Suggestions must not depend on a client-side engine having been constructed.
        // Load both classpath built-ins and the active themed/user directory here.
        UiParticlePackManager.loadDefaultDirectoryOnce();
        System.out.println("[Koil Sprite] " + UiParticlePackManager.diagnosticSummary());
        if (!UiParticlePackManager.missingBuiltinIds().isEmpty()) {
            System.err.println("[Koil Sprite] Missing built-in effect ids: "
                    + UiParticlePackManager.missingBuiltinIds());
        }
        if (!UiParticlePackManager.failedRegistrations().isEmpty()) {
            System.err.println("[Koil Sprite] Particle registration failures: "
                    + UiParticlePackManager.failedRegistrations());
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(spriteCommand());
        });
    }

    static LiteralArgumentBuilder<ServerCommandSource> spriteCommand() {
        return CommandManager.literal("sprite")
                .then(CommandManager.literal("particle")
                        .then(CommandManager.literal("spawn").then(idBranch(IdScope.PARTICLE))))
                .then(CommandManager.literal("scene")
                        .then(CommandManager.literal("place").then(idBranch(IdScope.SCENE)))
                        .then(playgroundBranch())
                        .then(CommandManager.literal("load")
                                .executes(context -> sendPlaygroundControl(context.getSource(),
                                        SpritePlaygroundNetwork.ACTION_LOAD_LAST_BACKGROUND, ""))
                                .then(CommandManager.argument("scene", StringArgumentType.string())
                                        .executes(context -> sendPlaygroundControl(context.getSource(),
                                                SpritePlaygroundNetwork.ACTION_LOAD_BACKGROUND,
                                                StringArgumentType.getString(context, "scene")))))
                        .then(CommandManager.literal("clear")
                                .executes(context -> sendPlaygroundControl(context.getSource(),
                                        SpritePlaygroundNetwork.ACTION_CLEAR_BACKGROUND, ""))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> playgroundBranch() {
        return CommandManager.literal("playground")
                .executes(context -> sendPlaygroundControl(context.getSource(),
                        SpritePlaygroundNetwork.ACTION_OPEN, ""))
                .then(CommandManager.literal("load")
                        .executes(context -> sendPlaygroundControl(context.getSource(),
                                SpritePlaygroundNetwork.ACTION_LOAD_LAST_EDITOR, ""))
                        .then(CommandManager.argument("scene", StringArgumentType.string())
                                .executes(context -> sendPlaygroundControl(context.getSource(),
                                        SpritePlaygroundNetwork.ACTION_LOAD_EDITOR,
                                        StringArgumentType.getString(context, "scene")))))
                .then(CommandManager.literal("clear")
                        .executes(context -> sendPlaygroundControl(context.getSource(),
                                SpritePlaygroundNetwork.ACTION_CLEAR_EDITOR, "")));
    }

    private static int sendPlaygroundControl(ServerCommandSource source, String action, String sceneName) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("Run /sprite scene as a player."));
            return 0;
        }
        if (!ServerPlayNetworking.canSend(player, SpritePlaygroundNetwork.CONTROL_PACKET)) {
            source.sendError(Text.literal("That player does not have Koil sprite playground support."));
            return 0;
        }
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(action == null ? "" : action, SpritePlaygroundNetwork.MAX_ACTION_LENGTH);
        buffer.writeString(sceneName == null ? "" : sceneName, SpritePlaygroundNetwork.MAX_SCENE_NAME_LENGTH);
        ServerPlayNetworking.send(player, SpritePlaygroundNetwork.CONTROL_PACKET, buffer);
        return 1;
    }

    private static RequiredArgumentBuilder<ServerCommandSource, String> idBranch(IdScope scope) {
        return CommandManager.argument("id", StringArgumentType.string())
                .suggests((context, builder) -> scope == IdScope.SCENE ? suggestSceneIds(builder) : suggestParticleIds(builder))
                .executes(context -> send(context.getSource(), cursor(context, 1, 1.0F, ""), scope))
                .then(withBranch(scope, (context, options) -> cursor(context, 1, 1.0F, options)))
                .then(cursorBranch(scope))
                .then(positionBranch("at", scope))
                .then(positionBranch("from", scope));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> cursorBranch(IdScope scope) {
        RequiredArgumentBuilder<ServerCommandSource, Integer> count = CommandManager.argument("count",
                        IntegerArgumentType.integer(1, ScreenSpriteRequest.MAX_COUNT))
                .executes(context -> send(context.getSource(), cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, ""), scope))
                .then(withBranch(scope, (context, options) -> cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, options)));

        RequiredArgumentBuilder<ServerCommandSource, Float> scale = CommandManager.argument("scale",
                        FloatArgumentType.floatArg(ScreenSpriteRequest.MIN_SCALE, ScreenSpriteRequest.MAX_SCALE))
                .executes(context -> send(context.getSource(), cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), ""), scope))
                .then(withBranch(scope, (context, options) -> cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), options)));
        count.then(scale);

        return CommandManager.literal("cursor")
                .executes(context -> send(context.getSource(), cursor(context, 1, 1.0F, ""), scope))
                .then(withBranch(scope, (context, options) -> cursor(context, 1, 1.0F, options)))
                .then(count);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> positionBranch(String placement, IdScope scope) {
        RequiredArgumentBuilder<ServerCommandSource, Integer> y = CommandManager.argument("y",
                        IntegerArgumentType.integer(0, ScreenSpriteRequest.MAX_COORDINATE))
                .executes(context -> send(context.getSource(), at(context, 1, 1.0F, ""), scope))
                .then(withBranch(scope, (context, options) -> at(context, 1, 1.0F, options)));

        RequiredArgumentBuilder<ServerCommandSource, Integer> count = CommandManager.argument("count",
                        IntegerArgumentType.integer(1, ScreenSpriteRequest.MAX_COUNT))
                .executes(context -> send(context.getSource(), at(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, ""), scope))
                .then(withBranch(scope, (context, options) -> at(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, options)));

        RequiredArgumentBuilder<ServerCommandSource, Float> scale = CommandManager.argument("scale",
                        FloatArgumentType.floatArg(ScreenSpriteRequest.MIN_SCALE, ScreenSpriteRequest.MAX_SCALE))
                .executes(context -> send(context.getSource(), at(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), ""), scope))
                .then(withBranch(scope, (context, options) -> at(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), options)));
        count.then(scale);
        y.then(count);

        return CommandManager.literal(placement)
                .then(CommandManager.argument("x", IntegerArgumentType.integer(0, ScreenSpriteRequest.MAX_COORDINATE))
                        .then(y));
    }

    @FunctionalInterface
    private interface RequestFactory {
        ScreenSpriteRequest create(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context, String options);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> withBranch(IdScope scope, RequestFactory factory) {
        return CommandManager.literal("with")
                .then(CommandManager.argument("overrides", StringArgumentType.greedyString())
                        .executes(context -> send(context.getSource(), factory.create(context,
                                StringArgumentType.getString(context, "overrides")), scope)));
    }

    private static ScreenSpriteRequest cursor(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context,
                                                  int count, float scale, String overrides) {
        return ScreenSpriteRequest.cursor(StringArgumentType.getString(context, "id"), count, scale, overrides);
    }

    private static ScreenSpriteRequest at(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context,
                                              int count, float scale, String overrides) {
        return ScreenSpriteRequest.at(StringArgumentType.getString(context, "id"),
                IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                count, scale, overrides);
    }

    static CompletableFuture<Suggestions> suggestParticleIds(SuggestionsBuilder builder) {
        return suggestIds(builder, IdScope.PARTICLE);
    }

    static CompletableFuture<Suggestions> suggestSceneIds(SuggestionsBuilder builder) {
        return suggestIds(builder, IdScope.SCENE);
    }

    static boolean allowsParticleId(String id) {
        return IdScope.PARTICLE.allows(id);
    }

    static boolean allowsSceneId(String id) {
        return IdScope.SCENE.allows(id);
    }

    private static CompletableFuture<Suggestions> suggestIds(SuggestionsBuilder builder, IdScope scope) {
        // Cheap no-op when the same theme is already loaded, but guarantees that
        // tab completion sees current external JSON packs on an integrated/server path.
        UiParticlePackManager.loadDefaultDirectoryOnce();
        List<String> ids = new ArrayList<>(UiParticleRegistry.ids());
        ids.removeIf(id -> !scope.allows(id));
        appendGameIds(ids, scope);
        return CommandSource.suggestMatching(ids, builder);
    }

    private static void appendGameIds(List<String> ids, IdScope scope) {
        try {
            if (scope == IdScope.PARTICLE) {
                for (Identifier id : Registries.PARTICLE_TYPE.getIds()) addId(ids, id.getNamespace() + "." + id.getPath());
            } else {
                for (Identifier id : Registries.BLOCK.getIds()) addId(ids, "block." + id.getNamespace() + "." + id.getPath());
                for (Identifier id : Registries.ITEM.getIds()) addId(ids, "item." + id.getNamespace() + "." + id.getPath());
            }
        } catch (ExceptionInInitializerError | NoClassDefFoundError ignored) {
            // Standalone command proofs do not bootstrap Minecraft registries.
        }
    }

    private static void addId(List<String> ids, String id) {
        String normalized = id.toLowerCase().replace('/', '.');
        if (!ids.contains(normalized)) ids.add(normalized);
    }

    private static int send(ServerCommandSource source, ScreenSpriteRequest request, IdScope scope) {
        if (request.id().isBlank()) {
            source.sendError(Text.literal("Sprite id cannot be empty."));
            return 0;
        }
        if (!scope.allows(request.id())) {
            source.sendError(Text.literal(scope == IdScope.PARTICLE
                    ? "Block and item IDs require /sprite scene place."
                    : "/sprite scene place only accepts block and item IDs."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            String example = scope == IdScope.PARTICLE
                    ? "/execute as <player> run sprite particle spawn <id> cursor"
                    : "/execute as <player> run sprite scene place <id> cursor";
            source.sendError(Text.literal("Run /sprite as a player, for example: " + example + "."));
            return 0;
        }
        if (!ServerPlayNetworking.canSend(player, ScreenSpriteNetwork.SPRITE_PACKET)) {
            source.sendError(Text.literal("That player does not have Koil screen-sprite support."));
            return 0;
        }
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(request.id(), 128);
        buffer.writeInt(request.x());
        buffer.writeInt(request.y());
        buffer.writeInt(request.count());
        buffer.writeFloat(request.scale());
        buffer.writeString(request.overrides(), ScreenSpriteRequest.MAX_OVERRIDE_LENGTH);
        ServerPlayNetworking.send(player, ScreenSpriteNetwork.SPRITE_PACKET, buffer);
        source.sendFeedback(() -> Text.literal("Sprite request sent to " + player.getName().getString()
                + " x" + request.count() + " scale=" + request.scale() + "."), false);
        return 1;
    }

    private enum IdScope {
        PARTICLE,
        SCENE;

        boolean allows(String id) {
            if (id == null) return false;
            String normalized = id.toLowerCase();
            boolean sceneId = normalized.startsWith("block.") || normalized.startsWith("item.");
            return this == SCENE ? sceneId : !sceneId;
        }
    }
}

