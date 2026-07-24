package com.spirit.koil.api.registry.client;

import com.spirit.koil.api.world.WorldInstanceResourceProfileService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.spirit.Main.SUBLOGGER;

/**
 * Places Content resource work inside integrated-world transitions:
 * preload before a world starts and unload behind the saving screen before the
 * title screen is revealed.
 */
public final class ContentWorldTransitionCoordinator {
    private static final long TRANSITION_TIMEOUT_SECONDS = 30L;

    private static String preparedStartBypass = "";
    private static boolean leaveInProgress;

    private ContentWorldTransitionCoordinator() {
    }

    public static boolean interceptWorldStart(
            MinecraftClient client,
            IntegratedServerLoader loader,
            Screen parent,
            String worldFolder
    ) {
        if (client == null || loader == null || worldFolder == null || worldFolder.isBlank()) {
            return false;
        }
        if (worldFolder.equals(preparedStartBypass)) {
            preparedStartBypass = "";
            return false;
        }

        Path worldPath = client.runDirectory.toPath().toAbsolutePath().normalize()
                .resolve("saves")
                .resolve(worldFolder)
                .normalize();
        boolean profileChanged =
                WorldInstanceResourceProfileService.prepareBeforeWorldJoin(client, worldPath);
        CompletableFuture<Void> preload =
                ActiveWorldContentResourceBridge.prepareWorldBeforeJoin(
                        client,
                        worldPath,
                        profileChanged
                );
        if (preload.isDone()) {
            return false;
        }

        client.setScreen(new MessageScreen(Text.literal("Loading Content resources...")));
        preload.orTimeout(TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((ignored, failure) -> client.execute(() -> {
                    if (failure != null) {
                        SUBLOGGER.logW(
                                "Content Resources",
                                "Selected-world Content preload timed out or failed; continuing safely: "
                                        + failure.getMessage()
                        );
                    }
                    preparedStartBypass = worldFolder;
                    loader.start(parent, worldFolder);
                }));
        return true;
    }

    public static boolean interceptGameMenuLeave(MinecraftClient client) {
        if (client == null
                || leaveInProgress
                || !client.isInSingleplayer()
                || client.world == null
                || ActiveWorldContentResourceBridge.activeSources().isEmpty()
                && !WorldInstanceResourceProfileService.hasActiveProfile()) {
            return false;
        }

        leaveInProgress = true;
        MessageScreen savingScreen = new MessageScreen(Text.translatable("menu.savingLevel"));
        client.setScreen(savingScreen);
        boolean profileChanged =
                WorldInstanceResourceProfileService.restoreBeforeDisconnect(client);
        ActiveWorldContentResourceBridge.unloadBeforeDisconnect(client, profileChanged)
                .orTimeout(TRANSITION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((ignored, failure) -> client.execute(() -> {
                    if (failure != null) {
                        SUBLOGGER.logW(
                                "Content Resources",
                                "Pre-title Content unload timed out or failed; continuing world close safely: "
                                        + failure.getMessage()
                        );
                    }
                    try {
                        if (client.world != null) {
                            client.world.disconnect();
                        }
                        client.disconnect(savingScreen);
                        client.setScreen(new TitleScreen());
                    } finally {
                        leaveInProgress = false;
                    }
                }));
        return true;
    }
}
