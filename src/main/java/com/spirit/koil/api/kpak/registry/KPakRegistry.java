package com.spirit.koil.api.kpak.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class KPakRegistry {

    private static final Path REGISTRY =
        FabricLoader.getInstance()
            .getGameDir()
            .resolve(".koil/packages/registry.json");


    private static final Gson GSON =
        new GsonBuilder()
            .setPrettyPrinting()
            .create();


    private static List<KPakRegistryEntry> entries =
        new ArrayList<>();


    public static void load() throws IOException {

        if (!Files.exists(REGISTRY)) {
            Files.createDirectories(REGISTRY.getParent());
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(REGISTRY)) {

            KPakRegistryEntry[] data =
                GSON.fromJson(reader, KPakRegistryEntry[].class);

            if (data != null) {
                entries =
                    new ArrayList<>(Arrays.asList(data));
            }
        }
    }


    public static void save() throws IOException {

        Files.createDirectories(REGISTRY.getParent());

        try (Writer writer =
                 Files.newBufferedWriter(REGISTRY)) {

            GSON.toJson(entries, writer);
        }
    }


    public static void register(
        KPakRegistryEntry entry
    ) throws IOException {

        remove(entry.id());

        entries.add(entry);

        save();
    }


    public static void remove(
        String id
    ) throws IOException {

        entries.removeIf(
            e -> e.id().equals(id)
        );

        save();
    }


    public static Optional<KPakRegistryEntry> get(
        String id
    ) {

        return entries.stream()
            .filter(e ->
                e.id().equals(id))
            .findFirst();
    }


    public static List<KPakRegistryEntry> all() {

        return Collections.unmodifiableList(entries);
    }
}
