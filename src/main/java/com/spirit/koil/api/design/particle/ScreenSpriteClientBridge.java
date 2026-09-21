package com.spirit.koil.api.design.particle;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

/** Receives bounded screen-sprite requests, installs the HUD renderer and playground entrypoint. */
public final class ScreenSpriteClientBridge {
    private static boolean registered;
    private static PlaygroundAction pendingPlaygroundAction;
    private static String pendingSceneName = "";

    private ScreenSpriteClientBridge() { }

    public static synchronized void registerReceiver() {
        if (registered) return;
        registered = true;
        GameParticleRegistryBridge.registerAllAvailable();
        GameSpriteRegistryBridge.registerAllAvailable();
        ScreenSpriteOverlay.registerHudRenderer();

        // Local shortcut. Scene-authoritative equivalents also live under
        // /sprite scene playground so the command tree stays coherent.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("playground")
                        .executes(context -> {
                            queuePlayground(PlaygroundAction.OPEN, "");
                            return 1;
                        })
                        .then(ClientCommandManager.literal("load")
                                .executes(context -> {
                                    queuePlayground(PlaygroundAction.LOAD_LAST_EDITOR, "");
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("scene", StringArgumentType.string())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                                SpriteSceneStorage.listSceneNames(), builder))
                                        .executes(context -> {
                                            queuePlayground(PlaygroundAction.LOAD_EDITOR,
                                                    StringArgumentType.getString(context, "scene"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("clear").executes(context -> {
                            queuePlayground(PlaygroundAction.CLEAR_EDITOR, "");
                            return 1;
                        })))
        );

        // Opening a Screen directly from a chat command races ChatScreen's own
        // close-after-send path. Queue actions and execute them only after chat
        // is actually gone.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PlaygroundAction action;
            String sceneName;
            synchronized (ScreenSpriteClientBridge.class) {
                action = pendingPlaygroundAction;
                sceneName = pendingSceneName;
            }
            if (action == null || client == null) return;
            if (client.currentScreen instanceof ChatScreen) return;

            synchronized (ScreenSpriteClientBridge.class) {
                pendingPlaygroundAction = null;
                pendingSceneName = "";
            }
            executePlaygroundAction(client, action, sceneName);
        });

        ClientPlayNetworking.registerGlobalReceiver(SpritePlaygroundNetwork.CONTROL_PACKET,
                (client, handler, buffer, responseSender) -> {
                    String actionName = buffer.readString(SpritePlaygroundNetwork.MAX_ACTION_LENGTH);
                    String sceneName = buffer.readString(SpritePlaygroundNetwork.MAX_SCENE_NAME_LENGTH);
                    PlaygroundAction action = PlaygroundAction.fromNetwork(actionName);
                    if (action != null) client.execute(() -> queuePlayground(action, sceneName));
                });

        ClientPlayNetworking.registerGlobalReceiver(ScreenSpriteNetwork.SPRITE_PACKET, (client, handler, buffer, responseSender) -> {
            ScreenSpriteRequest request = new ScreenSpriteRequest(
                    buffer.readString(128), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readFloat(),
                    buffer.readString(ScreenSpriteRequest.MAX_OVERRIDE_LENGTH)
            );
            client.execute(() -> ScreenSpriteOverlay.enqueue(request));
        });
    }

    private static synchronized void queuePlayground(PlaygroundAction action, String sceneName) {
        pendingPlaygroundAction = action;
        pendingSceneName = sceneName == null ? "" : sceneName;
    }

    private static void executePlaygroundAction(MinecraftClient client, PlaygroundAction action, String sceneName) {
        UiParticleEngine engine = ScreenSpriteOverlay.engine();
        ScreenSpriteOverlay.resetSceneInput();
        switch (action) {
            case OPEN -> openEditor(client, null, null);
            case LOAD_EDITOR -> {
                String restore = SpritePlaygroundScreen.captureCurrentScene();
                SpriteSceneStorage.Result result = SpriteSceneStorage.load(engine, sceneName);
                client.setScreen(new SpritePlaygroundScreen(client.currentScreen, restore, sceneName, result.message()));
            }
            case LOAD_LAST_EDITOR -> {
                String restore = SpritePlaygroundScreen.captureCurrentScene();
                String name = SpriteSceneStorage.mostRecentSceneName().orElse("");
                if (name.isBlank()) {
                    client.setScreen(new SpritePlaygroundScreen(client.currentScreen, restore, "playground", "No saved playground scenes found"));
                } else {
                    SpriteSceneStorage.Result result = SpriteSceneStorage.load(engine, name);
                    client.setScreen(new SpritePlaygroundScreen(client.currentScreen, restore, name, result.message()));
                }
            }
            case CLEAR_EDITOR -> {
                String restore = SpritePlaygroundScreen.captureCurrentScene();
                engine.reset();
                client.setScreen(new SpritePlaygroundScreen(client.currentScreen, restore, "playground", "Opened a blank playground scene"));
            }
            case LOAD_BACKGROUND -> {
                SpriteSceneStorage.Result result = SpriteSceneStorage.load(engine, sceneName);
                notifyClient(client, result.message());
            }
            case LOAD_LAST_BACKGROUND -> {
                String name = SpriteSceneStorage.mostRecentSceneName().orElse("");
                if (name.isBlank()) notifyClient(client, "No saved playground scenes found");
                else notifyClient(client, SpriteSceneStorage.load(engine, name).message());
            }
            case CLEAR_BACKGROUND -> {
                engine.reset();
                notifyClient(client, "Cleared the active Koil sprite scene");
            }
        }
    }

    private static void openEditor(MinecraftClient client, String sceneName, String status) {
        String restore = SpritePlaygroundScreen.captureCurrentScene();
        client.setScreen(new SpritePlaygroundScreen(client.currentScreen, restore, sceneName, status));
    }

    private static void notifyClient(MinecraftClient client, String message) {
        if (client != null && client.player != null && message != null && !message.isBlank()) {
            client.player.sendMessage(Text.literal("[Koil] " + message), false);
        }
    }

    private enum PlaygroundAction {
        OPEN,
        LOAD_EDITOR,
        LOAD_LAST_EDITOR,
        CLEAR_EDITOR,
        LOAD_BACKGROUND,
        LOAD_LAST_BACKGROUND,
        CLEAR_BACKGROUND;

        static PlaygroundAction fromNetwork(String value) {
            if (SpritePlaygroundNetwork.ACTION_OPEN.equals(value)) return OPEN;
            if (SpritePlaygroundNetwork.ACTION_LOAD_EDITOR.equals(value)) return LOAD_EDITOR;
            if (SpritePlaygroundNetwork.ACTION_LOAD_LAST_EDITOR.equals(value)) return LOAD_LAST_EDITOR;
            if (SpritePlaygroundNetwork.ACTION_CLEAR_EDITOR.equals(value)) return CLEAR_EDITOR;
            if (SpritePlaygroundNetwork.ACTION_LOAD_BACKGROUND.equals(value)) return LOAD_BACKGROUND;
            if (SpritePlaygroundNetwork.ACTION_LOAD_LAST_BACKGROUND.equals(value)) return LOAD_LAST_BACKGROUND;
            if (SpritePlaygroundNetwork.ACTION_CLEAR_BACKGROUND.equals(value)) return CLEAR_BACKGROUND;
            return null;
        }
    }
}
