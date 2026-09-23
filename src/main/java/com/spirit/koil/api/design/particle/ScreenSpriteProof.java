package com.spirit.koil.api.design.particle;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

/** Small executable contract for bounded screen-sprite requests. */
public final class ScreenSpriteProof {
    private ScreenSpriteProof() {
    }

    public static void main(String[] args) {
        var sprite = ScreenSpriteServerBridge.spriteCommand().build();
        var spawn = sprite.getChild("particle").getChild("spawn");
        var scene = sprite.getChild("scene").getChild("place");
        require(spawn.getChild("id").getChild("cursor") != null, "/sprite particle spawn must support cursor placement");
        require(spawn.getChild("id").getChild("at") != null, "/sprite particle spawn must support at placement");
        require(spawn.getChild("id").getChild("from") != null, "/sprite particle spawn must support from placement");
        require(scene.getChild("id").getChild("cursor") != null, "/sprite scene place must support cursor placement");
        require(sprite.getChild("id") == null, "legacy /sprite <id> must not be registered");

        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(ScreenSpriteServerBridge.spriteCommand());
        boolean spawnSuggested = dispatcher.getCompletionSuggestions(dispatcher.parse("sprite particle ", null)).join()
                .getList().stream().anyMatch(suggestion -> suggestion.getText().equals("spawn"));
        require(spawnSuggested, "/sprite particle must suggest spawn");

        ScreenSpriteRequest cursor = ScreenSpriteRequest.cursor("firework_bloom",
                ScreenSpriteRequest.MAX_COUNT + 1, ScreenSpriteRequest.MAX_SCALE + 1.0F);
        require(cursor.cursor(), "cursor request must retain cursor placement");
        require(cursor.count() == ScreenSpriteRequest.MAX_COUNT, "count must be capped at the request limit");
        require(cursor.scale() == ScreenSpriteRequest.MAX_SCALE, "scale must be capped at the request limit");

        ScreenSpriteRequest point = ScreenSpriteRequest.at("heart_pop", -20, 20000, 0, 0.01F);
        require(!point.cursor(), "point request must retain coordinates");
        require(point.x() == 0 && point.y() == 16384, "coordinates must be bounded");
        require(point.count() == 1, "count must have a lower bound");
        require(point.scale() == ScreenSpriteRequest.MIN_SCALE, "scale must have a lower bound");
        require(ScreenSpriteNetwork.SPRITE_PACKET.getPath().equals("screen_sprite"), "sprite packet id must be stable");

        UiParticleRegistry.register(UiParticleEffect.builder("proof_sprite_suggestion").build());
        UiParticleRegistry.register(UiParticleEffect.builder("block.minecraft.proof_sprite_suggestion").build());
        UiParticleRegistry.register(UiParticleEffect.builder("item.minecraft.proof_sprite_suggestion").build());
        try {
            boolean suggested = ScreenSpriteServerBridge.suggestParticleIds(new SuggestionsBuilder("", 0)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("proof_sprite_suggestion"));
            boolean blockInParticle = ScreenSpriteServerBridge.suggestParticleIds(new SuggestionsBuilder("", 0)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("block.minecraft.proof_sprite_suggestion"));
            boolean blockInScene = ScreenSpriteServerBridge.suggestSceneIds(new SuggestionsBuilder("", 0)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("block.minecraft.proof_sprite_suggestion"));
            boolean itemInParticle = ScreenSpriteServerBridge.suggestParticleIds(new SuggestionsBuilder("", 0)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("item.minecraft.proof_sprite_suggestion"));
            boolean itemInScene = ScreenSpriteServerBridge.suggestSceneIds(new SuggestionsBuilder("", 0)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("item.minecraft.proof_sprite_suggestion"));
            boolean particleInScene = ScreenSpriteServerBridge.suggestSceneIds(new SuggestionsBuilder("", 0)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("proof_sprite_suggestion"));
            boolean blockSuggestedByParticleCommand = dispatcher.getCompletionSuggestions(
                    dispatcher.parse("sprite particle spawn ", null)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("block.minecraft.proof_sprite_suggestion"));
            boolean blockSuggestedBySceneCommand = dispatcher.getCompletionSuggestions(
                    dispatcher.parse("sprite scene place ", null)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("block.minecraft.proof_sprite_suggestion"));
            boolean particleSuggestedBySceneCommand = dispatcher.getCompletionSuggestions(
                    dispatcher.parse("sprite scene place ", null)).join().getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("proof_sprite_suggestion"));
            require(suggested, "particle spawn suggestions must include registered particle ids");
            require(!blockInParticle, "particle spawn must not suggest block ids");
            require(blockInScene, "scene place must suggest block ids");
            require(!itemInParticle, "particle spawn must not suggest item ids");
            require(itemInScene, "scene place must suggest item ids");
            require(!particleInScene, "scene place must not suggest particle ids");
            require(!blockSuggestedByParticleCommand, "particle command must not suggest block ids");
            require(blockSuggestedBySceneCommand, "scene command must suggest block ids");
            require(!particleSuggestedBySceneCommand, "scene command must not suggest particle ids");
            require(!ScreenSpriteServerBridge.allowsParticleId("block.minecraft.piston"),
                    "particle spawn must reject block ids even when typed manually");
            require(!ScreenSpriteServerBridge.allowsParticleId("item.minecraft.trident"),
                    "particle spawn must reject item ids even when typed manually");
            require(ScreenSpriteServerBridge.allowsSceneId("block.minecraft.piston"),
                    "scene place must accept block ids");
            require(ScreenSpriteServerBridge.allowsSceneId("item.minecraft.trident"),
                    "scene place must accept item ids");
            require(!ScreenSpriteServerBridge.allowsSceneId("minecraft.heart"),
                    "scene place must reject Minecraft particle ids even when typed manually");
            require(!ScreenSpriteServerBridge.allowsSceneId("proof_sprite_suggestion"),
                    "scene place must reject custom particle ids even when typed manually");
        } finally {
            UiParticleRegistry.unregister("proof_sprite_suggestion");
            UiParticleRegistry.unregister("block.minecraft.proof_sprite_suggestion");
            UiParticleRegistry.unregister("item.minecraft.proof_sprite_suggestion");
        }

        System.out.println("Koil screen sprite proof passed");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }
}
