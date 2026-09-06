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
 * /sprite firework_rocket_show
 * /sprite firework_rocket_show cursor 6 1.5
 * /sprite firework_rocket_show cursor 6 1.5 with gravity=10;layer_id=4
 * /sprite minecraft.heart at 120 70 12 2.0 with particle_collision=true;contact_mode=bounce
 * </pre>
 * Use vanilla `/execute as &lt;player&gt; run sprite ...` to target another player.
 */
public final class KoilScreenSpriteServerBridge {
    private KoilScreenSpriteServerBridge() { }

    public static void registerCommands() {
        // Suggestions must not depend on a client-side engine having been constructed.
        // Load both classpath built-ins and the active themed/user directory here.
        KoilUiParticlePackManager.loadDefaultDirectoryOnce();
        System.out.println("[Koil Sprite] " + KoilUiParticlePackManager.diagnosticSummary());
        if (!KoilUiParticlePackManager.missingBuiltinIds().isEmpty()) {
            System.err.println("[Koil Sprite] Missing built-in effect ids: "
                    + KoilUiParticlePackManager.missingBuiltinIds());
        }
        if (!KoilUiParticlePackManager.failedRegistrations().isEmpty()) {
            System.err.println("[Koil Sprite] Particle registration failures: "
                    + KoilUiParticlePackManager.failedRegistrations());
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            RequiredArgumentBuilder<ServerCommandSource, String> id = CommandManager.argument("id", StringArgumentType.word())
                    .suggests((context, builder) -> suggestIds(builder))
                    .executes(context -> send(context.getSource(), cursor(context, 1, 1.0F, "")))
                    .then(withBranch((context, options) -> cursor(context, 1, 1.0F, options)))
                    .then(cursorBranch())
                    .then(atBranch());
            dispatcher.register(CommandManager.literal("sprite").then(id));
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> cursorBranch() {
        RequiredArgumentBuilder<ServerCommandSource, Integer> count = CommandManager.argument("count",
                        IntegerArgumentType.integer(1, KoilScreenSpriteRequest.MAX_COUNT))
                .executes(context -> send(context.getSource(), cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, "")))
                .then(withBranch((context, options) -> cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, options)));

        RequiredArgumentBuilder<ServerCommandSource, Float> scale = CommandManager.argument("scale",
                        FloatArgumentType.floatArg(KoilScreenSpriteRequest.MIN_SCALE, KoilScreenSpriteRequest.MAX_SCALE))
                .executes(context -> send(context.getSource(), cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), "")))
                .then(withBranch((context, options) -> cursor(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), options)));
        count.then(scale);

        return CommandManager.literal("cursor")
                .executes(context -> send(context.getSource(), cursor(context, 1, 1.0F, "")))
                .then(withBranch((context, options) -> cursor(context, 1, 1.0F, options)))
                .then(count);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> atBranch() {
        RequiredArgumentBuilder<ServerCommandSource, Integer> y = CommandManager.argument("y",
                        IntegerArgumentType.integer(0, KoilScreenSpriteRequest.MAX_COORDINATE))
                .executes(context -> send(context.getSource(), at(context, 1, 1.0F, "")))
                .then(withBranch((context, options) -> at(context, 1, 1.0F, options)));

        RequiredArgumentBuilder<ServerCommandSource, Integer> count = CommandManager.argument("count",
                        IntegerArgumentType.integer(1, KoilScreenSpriteRequest.MAX_COUNT))
                .executes(context -> send(context.getSource(), at(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, "")))
                .then(withBranch((context, options) -> at(context,
                        IntegerArgumentType.getInteger(context, "count"), 1.0F, options)));

        RequiredArgumentBuilder<ServerCommandSource, Float> scale = CommandManager.argument("scale",
                        FloatArgumentType.floatArg(KoilScreenSpriteRequest.MIN_SCALE, KoilScreenSpriteRequest.MAX_SCALE))
                .executes(context -> send(context.getSource(), at(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), "")))
                .then(withBranch((context, options) -> at(context,
                        IntegerArgumentType.getInteger(context, "count"), FloatArgumentType.getFloat(context, "scale"), options)));
        count.then(scale);
        y.then(count);

        return CommandManager.literal("at")
                .then(CommandManager.argument("x", IntegerArgumentType.integer(0, KoilScreenSpriteRequest.MAX_COORDINATE))
                        .then(y));
    }

    @FunctionalInterface
    private interface RequestFactory {
        KoilScreenSpriteRequest create(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context, String options);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> withBranch(RequestFactory factory) {
        return CommandManager.literal("with")
                .then(CommandManager.argument("overrides", StringArgumentType.greedyString())
                        .executes(context -> send(context.getSource(), factory.create(context,
                                StringArgumentType.getString(context, "overrides")))));
    }

    private static KoilScreenSpriteRequest cursor(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context,
                                                  int count, float scale, String overrides) {
        return KoilScreenSpriteRequest.cursor(StringArgumentType.getString(context, "id"), count, scale, overrides);
    }

    private static KoilScreenSpriteRequest at(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context,
                                              int count, float scale, String overrides) {
        return KoilScreenSpriteRequest.at(StringArgumentType.getString(context, "id"),
                IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                count, scale, overrides);
    }

    static CompletableFuture<Suggestions> suggestIds(SuggestionsBuilder builder) {
        // Cheap no-op when the same theme is already loaded, but guarantees that
        // tab completion sees current external JSON packs on an integrated/server path.
        KoilUiParticlePackManager.loadDefaultDirectoryOnce();
        List<String> ids = new ArrayList<>(KoilUiParticleRegistry.ids());
        for (Identifier id : Registries.PARTICLE_TYPE.getIds()) {
            String mirrored = id.getNamespace().toLowerCase() + "."
                    + id.getPath().toLowerCase().replace('/', '.');
            if (!ids.contains(mirrored)) ids.add(mirrored);
        }
        return CommandSource.suggestMatching(ids, builder);
    }

    private static int send(ServerCommandSource source, KoilScreenSpriteRequest request) {
        if (request.id().isBlank()) {
            source.sendError(Text.literal("Sprite id cannot be empty."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("Run /sprite as a player, for example: /execute as <player> run sprite <id>."));
            return 0;
        }
        if (!ServerPlayNetworking.canSend(player, KoilScreenSpriteNetwork.SPRITE_PACKET)) {
            source.sendError(Text.literal("That player does not have Koil screen-sprite support."));
            return 0;
        }
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(request.id(), 128);
        buffer.writeInt(request.x());
        buffer.writeInt(request.y());
        buffer.writeInt(request.count());
        buffer.writeFloat(request.scale());
        buffer.writeString(request.overrides(), KoilScreenSpriteRequest.MAX_OVERRIDE_LENGTH);
        ServerPlayNetworking.send(player, KoilScreenSpriteNetwork.SPRITE_PACKET, buffer);
        source.sendFeedback(() -> Text.literal("Sprite request sent to " + player.getName().getString()
                + " x" + request.count() + " scale=" + request.scale() + "."), false);
        return 1;
    }
}
