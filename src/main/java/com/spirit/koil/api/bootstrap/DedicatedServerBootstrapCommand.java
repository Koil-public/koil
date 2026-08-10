package com.spirit.koil.api.bootstrap;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** Dedicated-server console commands for consent and observable recovery. */
public final class DedicatedServerBootstrapCommand {
    private DedicatedServerBootstrapCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("koil")
                        .then(CommandManager.literal("terms")
                                .then(CommandManager.literal("status")
                                        .executes(context -> status(context.getSource(), true)))
                                .then(CommandManager.literal("accept")
                                        .executes(context -> accept(context.getSource()))))
                        .then(CommandManager.literal("bootstrap")
                                .then(CommandManager.literal("status")
                                        .executes(context -> status(context.getSource(), false)))
                                .then(CommandManager.literal("retry")
                                        .executes(context -> retry(context.getSource()))))
        ));
    }

    private static int status(ServerCommandSource source, boolean terms) {
        if (terms) {
            source.sendFeedback(() -> Text.literal(
                    "Koil terms " + (DedicatedServerBootstrapService.termsAccepted() ? "accepted" : "pending")
                            + " | version " + DedicatedServerBootstrapService.TERMS_VERSION
            ), false);
        } else {
            source.sendFeedback(() -> Text.literal(
                    "Koil bootstrap " + DedicatedServerBootstrapService.snapshot().statusLine()
            ), false);
        }
        return 1;
    }

    private static int accept(ServerCommandSource source) {
        if (!physicalConsole(source)) {
            source.sendError(Text.literal("Koil terms can be accepted only from the physical dedicated-server console."));
            return 0;
        }
        boolean accepted = DedicatedServerBootstrapService.acceptFromPhysicalConsole();
        source.sendFeedback(() -> Text.literal(accepted
                ? "Koil terms accepted. Server bootstrap started asynchronously; no restart is required."
                : "Koil terms were already accepted or could not be persisted. Check `koil bootstrap status`."), false);
        return accepted ? 1 : 0;
    }

    private static int retry(ServerCommandSource source) {
        if (!physicalConsole(source)) {
            source.sendError(Text.literal("Koil bootstrap retry can be started only from the physical dedicated-server console."));
            return 0;
        }
        boolean started = DedicatedServerBootstrapService.retryFromPhysicalConsole();
        source.sendFeedback(() -> Text.literal(started
                ? "Koil bootstrap retry started asynchronously."
                : "Koil bootstrap retry was not started; check terms and current status."), false);
        return started ? 1 : 0;
    }

    static boolean physicalConsole(ServerCommandSource source) {
        return source != null
                && source.getServer() != null
                && source.getServer().isDedicated()
                && source.getEntity() == null
                && "Server".equals(source.getName());
    }
}
