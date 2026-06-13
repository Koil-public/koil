package com.spirit.client.gui.main.elements;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.spirit.Main.SUBLOGGER;

public class BookLoader {
    public static Map<String, String> loadBookPages(String filePath) {
        Map<String, String> pages = new HashMap<>();
        try (FileReader reader = new FileReader(filePath)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            jsonObject.entrySet().forEach(entry -> pages.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (IOException e) {
            SUBLOGGER.logE("File-Management thread", "Failed to load book pages from " + filePath + ": " + e.getMessage());
        }
        return pages;
    }
}
