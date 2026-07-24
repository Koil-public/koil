package com.spirit.koil.api.registry;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** In-game inspection and reload commands for the world-scoped Content registry. */
public final class ContentCommandBridge {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ContentCommandBridge() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("content")
                        .executes(context -> listActive(context.getSource()))
                        .then(literal("status").executes(context -> status(context.getSource())))
                        .then(literal("list")
                                .executes(context -> listActive(context.getSource()))
                                .then(literal("active").executes(context -> listActive(context.getSource())))
                                .then(literal("worlds").executes(context -> listWorlds(context.getSource())))
                                .then(literal("world")
                                        .then(argument("world", StringArgumentType.greedyString())
                                                .executes(context -> listWorld(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "world")
                                                )))))
                        .then(literal("scan")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(literal("worlds").executes(context -> scanWorlds(context.getSource()))))
                        .then(literal("validate")
                                .executes(context -> validateActive(context.getSource()))
                                .then(literal("all").executes(context -> validateAll(context.getSource()))))
                        .then(literal("changes").executes(context -> changes(context.getSource())))
                        .then(literal("report").executes(context -> report(context.getSource())))
                        .then(literal("reload")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> reload(context.getSource()))
                                .then(literal("world").executes(context -> reload(context.getSource()))))
        ));
    }

    private static int status(ServerCommandSource source) {
        WorldContentIndex.ActiveWorldSnapshot active = DynamicRegistryManager.instance().activeWorldSnapshot();
        if (active.worldId().isBlank()) {
            message(source, "Content: no active local world registry.", Formatting.YELLOW);
            return 0;
        }
        message(
                source,
                "Content: " + active.worldId() + " - " + active.enabledPackIds().size() + " enabled pack(s) - "
                        + active.definitions().size() + " active definition(s)",
                Formatting.AQUA
        );
        message(
                source,
                "Physical holders: " + DynamicContentHolderRegistry.holders().size() + " registered - "
                        + DynamicContentHolderRegistry.restartRequiredDefinitions().size() + " restart-required definition(s)",
                DynamicContentHolderRegistry.restartRequiredDefinitions().isEmpty() ? Formatting.GREEN : Formatting.YELLOW
        );
        message(source, "Snapshot updated: " + active.updatedAt(), Formatting.DARK_GRAY);
        ContentReloadResult reload = DynamicRegistryManager.instance().lastReloadResult();
        if (!"not_run".equals(reload.reason())) {
            message(
                    source,
                    "Last reload: " + reload.hotReloadableCount() + " hot - "
                            + reload.restartRequiredCount() + " restart-required - "
                            + reload.validation().size() + " validation message(s)",
                    reload.restartRequiredCount() > 0 ? Formatting.YELLOW : Formatting.DARK_GRAY
            );
        }
        return active.definitions().size();
    }

    private static int listActive(ServerCommandSource source) {
        WorldContentIndex.ActiveWorldSnapshot active = DynamicRegistryManager.instance().activeWorldSnapshot();
        status(source);
        if (active.definitions().isEmpty()) {
            return 0;
        }
        active.definitions().stream()
                .sorted(Comparator.comparing(WorldContentIndex.DefinitionEntry::id))
                .forEach(definition -> message(
                        source,
                        "- " + definition.id() + " [" + definition.type() + "] from " + definition.packId()
                                + (DynamicContentHolderRegistry.hasHolder(definition.id())
                                ? " - physical holder ready"
                                : " - restart required for physical holder"),
                        definition.activatable() ? Formatting.GREEN : Formatting.RED
                ));
        return active.definitions().size();
    }

    private static int listWorlds(ServerCommandSource source) {
        WorldContentIndex index = DynamicRegistryManager.instance().worldIndex();
        message(source, "Indexed Content worlds: " + index.worlds().size(), Formatting.AQUA);
        for (WorldContentIndex.WorldEntry world : index.worlds()) {
            message(
                    source,
                    "- " + world.worldId() + " - " + world.packs().size() + " pack(s) - " + world.definitionCount() + " definition(s)",
                    world.definitionCount() > 0 ? Formatting.GREEN : Formatting.GRAY
            );
        }
        return index.worlds().size();
    }

    private static int listWorld(ServerCommandSource source, String requestedWorld) {
        WorldContentIndex.WorldEntry world = DynamicRegistryManager.instance().worldIndex().worlds().stream()
                .filter(candidate -> candidate.worldId().equalsIgnoreCase(requestedWorld)
                        || candidate.displayName().equalsIgnoreCase(requestedWorld))
                .findFirst()
                .orElse(null);
        if (world == null) {
            message(source, "Unknown indexed world: " + requestedWorld, Formatting.RED);
            return 0;
        }
        message(source, "Content available in " + world.worldId() + ":", Formatting.AQUA);
        int count = 0;
        for (WorldContentIndex.PackEntry pack : world.packs()) {
            if (pack.definitions().isEmpty()) {
                continue;
            }
            message(source, "- " + pack.packId() + " [" + pack.state() + "]", Formatting.YELLOW);
            for (WorldContentIndex.DefinitionEntry definition : pack.definitions()) {
                message(source, "  - " + definition.id() + " [" + definition.type() + "]", Formatting.GRAY);
                count++;
            }
        }
        return count;
    }

    private static int scanWorlds(ServerCommandSource source) {
        WorldContentIndex index = DynamicRegistryManager.instance().scanWorlds();
        message(
                source,
                "Content scan complete: " + index.worlds().size() + " worlds, " + index.definitionCount() + " definitions.",
                Formatting.GREEN
        );
        return index.definitionCount();
    }

    private static int validateActive(ServerCommandSource source) {
        ContentReloadResult reload = DynamicRegistryManager.instance().lastReloadResult();
        List<WorldContentIndex.ValidationMessage> messages = reload.validation();
        return showValidation(
                source,
                "Active-world Content validation",
                messages,
                "Use /content validate all to inspect cached definitions from every indexed world."
        );
    }

    private static int validateAll(ServerCommandSource source) {
        WorldContentIndex index = DynamicRegistryManager.instance().worldIndex();
        return showValidation(
                source,
                "All-world Content validation",
                validationMessages(index),
                "These results include disabled packs and worlds other than the active world."
        );
    }

    private static int showValidation(
            ServerCommandSource source,
            String label,
            List<WorldContentIndex.ValidationMessage> messages,
            String scopeNote
    ) {
        long errors = messages.stream().filter(WorldContentIndex.ValidationMessage::blocksActivation).count();
        long warnings = messages.size() - errors;
        Formatting color = errors > 0 ? Formatting.RED : warnings > 0 ? Formatting.YELLOW : Formatting.GREEN;
        message(source, label + ": " + errors + " error(s), " + warnings + " warning(s).", color);
        message(source, "- " + scopeNote, Formatting.DARK_GRAY);
        messages.stream().limit(12).forEach(issue -> message(
                source,
                "- [" + issue.code() + "] " + issue.message(),
                issue.blocksActivation() ? Formatting.RED : Formatting.YELLOW
        ));
        if (messages.size() > 12) {
            message(source, "… " + (messages.size() - 12) + " more issue(s); use the generated report files.", Formatting.GRAY);
        }
        List<String> restartRequired = DynamicContentHolderRegistry.restartRequiredDefinitions();
        if (!restartRequired.isEmpty()) {
            message(
                    source,
                    "Restart required to create " + restartRequired.size() + " new physical holder(s): "
                            + String.join(", ", restartRequired),
                    Formatting.YELLOW
            );
        }
        return errors == 0 ? 1 : 0;
    }

    private static int report(ServerCommandSource source) {
        Path gameDirectory = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        message(source, "Content generated state:", Formatting.AQUA);
        message(source, gameDirectory.resolve("koil/sys/content/world_content_index.json").toString(), Formatting.GRAY);
        message(source, gameDirectory.resolve("koil/sys/content/active_world_registry.json").toString(), Formatting.GRAY);
        message(source, gameDirectory.resolve("koil/sys/content/content_reload_history.json").toString(), Formatting.GRAY);
        message(source, gameDirectory.resolve("koil/sys/content/content_validation_results.json").toString(), Formatting.GRAY);
        message(source, DynamicRegistryManager.instance().contentReportDirectory().toString(), Formatting.GRAY);
        return 1;
    }

    private static int changes(ServerCommandSource source) {
        ContentReloadResult result = DynamicRegistryManager.instance().lastReloadResult();
        message(
                source,
                "Last Content reload [" + result.reason() + "]: " + result.changes().size() + " change(s)",
                result.successful() ? Formatting.AQUA : Formatting.RED
        );
        result.changes().stream().limit(20).forEach(change -> message(
                source,
                "- " + change.id() + " - " + change.kind().name().toLowerCase(java.util.Locale.ROOT)
                        + " - " + change.classification().name().toLowerCase(java.util.Locale.ROOT)
                        + (change.changedPaths().isEmpty() ? "" : " - " + String.join(", ", change.changedPaths())),
                switch (change.classification()) {
                    case HOT_RELOADABLE -> Formatting.GREEN;
                    case RESTART_REQUIRED -> Formatting.YELLOW;
                    case UNSUPPORTED -> Formatting.RED;
                }
        ));
        if (result.changes().size() > 20) {
            message(source, "… " + (result.changes().size() - 20) + " more; use /content report.", Formatting.GRAY);
        }
        return result.changes().size();
    }

    private static int reload(ServerCommandSource source) {
        Collection<String> enabledPacks = source.getServer().getDataPackManager().getEnabledNames();
        message(source, "Reloading datapacks and world-scoped Content…", Formatting.YELLOW);
        source.getServer().reloadResources(enabledPacks).whenComplete((unused, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                message(source, "Content reload failed: " + failure.getMessage(), Formatting.RED);
            } else {
                ContentReloadResult result = DynamicRegistryManager.instance().lastReloadResult();
                message(
                        source,
                        "Content reload complete: " + result.activeDefinitionCount() + " active - "
                                + result.hotReloadableCount() + " hot change(s) - "
                                + result.restartRequiredCount() + " restart-required change(s).",
                        result.restartRequiredCount() > 0 ? Formatting.YELLOW : Formatting.GREEN
                );
                message(
                        source,
                        "- Minecraft 1.20.1 client asset edits may require F3+T after Content reload.",
                        Formatting.YELLOW
                );
            }
        }));
        return 1;
    }

    private static List<WorldContentIndex.ValidationMessage> validationMessages(WorldContentIndex index) {
        List<WorldContentIndex.ValidationMessage> messages = new ArrayList<>(index.validation());
        for (WorldContentIndex.WorldEntry world : index.worlds()) {
            messages.addAll(world.validation());
            for (WorldContentIndex.PackEntry pack : world.packs()) {
                messages.addAll(pack.validation());
                for (WorldContentIndex.DefinitionEntry definition : pack.definitions()) {
                    messages.addAll(definition.validation());
                }
            }
        }
        return messages;
    }

    private static void message(ServerCommandSource source, String value, Formatting color) {
        source.sendFeedback(() -> Text.literal(value).formatted(color), false);
    }
}
