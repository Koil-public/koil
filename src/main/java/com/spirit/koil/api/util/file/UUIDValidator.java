package com.spirit.koil.api.util.file;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.IOException;

import static com.spirit.Main.multiDecrypt;
import static com.spirit.Main.SUBLOGGER;

public class UUIDValidator {
    public static boolean isPlayerUUIDAllowed(String filePath, String playerUUID, String playerName) throws IOException {
        boolean isPlayerAllowed = false;

        try (FileReader reader = new FileReader(filePath)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            // First, check if the playerName key exists in the JSON
            if (jsonObject.has(playerName)) {
                JsonElement playerElement = jsonObject.get(playerName);

                // Check if the value is a JsonObject, handle it accordingly
                if (playerElement.isJsonObject()) {
                    JsonObject playerObject = playerElement.getAsJsonObject();

                    if (playerObject.has("encryptedUUIDs") && playerObject.has("allowedUUIDs")) {
                        JsonArray encryptedUUIDs = playerObject.getAsJsonArray("encryptedUUIDs");
                        JsonArray allowedUUIDs = playerObject.getAsJsonArray("allowedUUIDs");

                        for (JsonElement encryptedUUIDElement : encryptedUUIDs) {
                            String encryptedUUID = encryptedUUIDElement.getAsString();
                            String decryptedUUID = multiDecrypt(encryptedUUID, 10).replace("?", "-");

                            for (JsonElement allowedUUIDElement : allowedUUIDs) {
                                String allowedUUID = allowedUUIDElement.getAsString();

                                if (decryptedUUID.equals(allowedUUID) && allowedUUID.equals(playerUUID)) {
                                    SUBLOGGER.logI("Management thread", "Player UUID validation accepted player: " + playerName);
                                    isPlayerAllowed = true;
                                    break;
                                }
                            }

                            if (isPlayerAllowed) break; // Exit loop if match found
                        }
                    } else {
                        SUBLOGGER.logW("Management thread", "Player UUID data is missing encryptedUUIDs or allowedUUIDs fields for: " + playerName);
                    }
                } else {
                    // Handle case where playerElement is not a JsonObject (could be JsonPrimitive or JsonArray)
                    SUBLOGGER.logW("Management thread", "Player UUID data is not an object for: " + playerName);
                }
            } else {
                SUBLOGGER.logW("Management thread", "Player UUID data not found for: " + playerName);
            }
        }
        return isPlayerAllowed;
    }
}
