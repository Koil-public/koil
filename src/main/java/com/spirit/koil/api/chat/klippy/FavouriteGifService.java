package com.spirit.koil.api.chat.klippy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FavouriteGifService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;

    private final List<GifSearchResult> favourites = new ArrayList<>();

    public FavouriteGifService() {
        this.file = MinecraftClient.getInstance()
            .runDirectory
            .toPath()
            .resolve("config")
            .resolve("koil")
            .resolve("gif_favourites.json");

        load();
    }

    public synchronized List<GifSearchResult> getAll() {
        return List.copyOf(favourites);
    }

    public synchronized boolean contains(String id) {
        return favourites.stream()
            .anyMatch(gif -> gif.id().equals(id));
    }

    public synchronized void add(GifSearchResult gif) {
        if (gif == null || contains(gif.id())) {
            return;
        }

        favourites.add(gif);
        save();
    }

    public synchronized void remove(String id) {
        favourites.removeIf(
            gif -> gif.id().equals(id)
        );

        save();
    }

    public synchronized void toggle(GifSearchResult gif) {
        if (contains(gif.id())) {
            remove(gif.id());
        } else {
            add(gif);
        }
    }

    private void load() {
        try {
            Files.createDirectories(file.getParent());

            if (!Files.exists(file)) {
                return;
            }

            favourites.clear();

            favourites.addAll(
                MAPPER.readValue(
                    file.toFile(),
                    new TypeReference<List<GifSearchResult>>() {
                    }
                )
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());

            MAPPER.writeValue(
                file.toFile(),
                favourites
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
