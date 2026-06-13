package com.spirit.koil.api.util.file;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.Main;

import java.io.File;
import java.io.FileReader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class KoilPackageIdentity {
    public static final String PACKAGE_PREFIX = "koil-package-";
    public static final String LEGACY_STATEMENT = "pkg";
    private static final String VALID_DIGITS_FILE = "./koil/auth/validDigits.json";
    private static final String VALID_SERIAL_FILE = "./koil/auth/validSerial.json";
    private static final String VERIFIED_AUTHORS_FILE = "./koil/auth/verifiedAuthors.json";
    private static final SecureRandom RANDOM = new SecureRandom();

    private KoilPackageIdentity() {
    }

    public record GeneratedIdentity(String packageId, String packageSuffix, String decryptedAuthor, String digitToken) {
    }

    public static GeneratedIdentity generate(String authorCredential, String serialCredential) {
        String author = normalizedCredential(authorCredential);
        String serial = normalizedCredential(serialCredential);
        if (author.isBlank()) {
            throw new IllegalArgumentException("Encrypted author value is required.");
        }
        if (serial.isBlank()) {
            throw new IllegalArgumentException("Encrypted serial value is required.");
        }
        String authIssue = authConfigurationIssue();
        if (!authIssue.isBlank()) {
            throw new IllegalArgumentException(authIssue);
        }
        if (!isAuthorCredentialValid(author)) {
            throw new IllegalArgumentException("Invalid encrypted author value.");
        }
        if (!isSerialCredentialValid(serial)) {
            throw new IllegalArgumentException("Invalid encrypted serial value.");
        }

        String decryptedAuthor = credentialPlainValue(author, loadListFromJson(VERIFIED_AUTHORS_FILE, "verifiedAuthors"));
        if (decryptedAuthor.length() != 4) {
            throw new IllegalArgumentException("Author must resolve to a verified 4-character package author.");
        }
        String digitToken = firstValidDigitToken();
        if (digitToken.isBlank()) {
            throw new IllegalArgumentException("Missing valid package digit token.");
        }
        String suffix = LEGACY_STATEMENT + encryptLegacy(decryptedAuthor) + encryptLegacy(digitToken);
        return new GeneratedIdentity(PACKAGE_PREFIX + suffix, suffix, decryptedAuthor, digitToken);
    }

    public static String encryptLegacy(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        StringBuilder encrypted = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            String[] values = Main.ENCRYPTION_MAP.get(character);
            if (values == null || values.length == 0) {
                encrypted.append(character);
                continue;
            }
            encrypted.append(values[RANDOM.nextInt(values.length)]);
        }
        return encrypted.toString();
    }

    public static String decryptLegacy(String value) {
        return Main.decrypt(value == null ? "" : value.trim());
    }

    public static boolean isAuthorCredentialValid(String value) {
        return credentialMatches(loadListFromJson(VERIFIED_AUTHORS_FILE, "verifiedAuthors"), value);
    }

    public static boolean isDigitCredentialValid(String value) {
        return credentialMatches(loadListFromJson(VALID_DIGITS_FILE, "validDigits"), value);
    }

    public static boolean isSerialCredentialValid(String value) {
        return credentialMatches(loadListFromJson(VALID_SERIAL_FILE, "validSerial"), value);
    }

    public static String firstValidDigitToken() {
        List<String> values = loadListFromJson(VALID_DIGITS_FILE, "validDigits");
        for (String value : values) {
            String trimmed = normalizedCredential(value);
            if (trimmed.matches("\\d{8}")) {
                return trimmed;
            }
            String decrypted = decryptLegacy(trimmed);
            if (decrypted.matches("\\d{8}")) {
                return decrypted;
            }
        }
        return "";
    }

    public static String authConfigurationIssue() {
        List<String> missing = new ArrayList<>();
        if (loadListFromJson(VALID_DIGITS_FILE, "validDigits").isEmpty()) {
            missing.add("validDigits");
        }
        if (loadListFromJson(VALID_SERIAL_FILE, "validSerial").isEmpty()) {
            missing.add("validSerial");
        }
        if (loadListFromJson(VERIFIED_AUTHORS_FILE, "verifiedAuthors").isEmpty()) {
            missing.add("verifiedAuthors");
        }
        return missing.isEmpty() ? "" : "Missing package auth data: " + String.join(", ", missing);
    }

    public static boolean credentialMatches(Collection<String> validValues, String value) {
        if (validValues == null || value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return validValues.contains(trimmed) || validValues.contains(decryptLegacy(trimmed));
    }

    private static String credentialPlainValue(String credential, Collection<String> validValues) {
        String trimmed = normalizedCredential(credential);
        String decrypted = decryptLegacy(trimmed);
        if (validValues.contains(decrypted)) {
            return decrypted;
        }
        if (validValues.contains(trimmed)) {
            return decrypted.length() == 4 ? decrypted : trimmed;
        }
        return "";
    }

    private static String normalizedCredential(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> loadListFromJson(String filePath, String key) {
        List<String> list = new ArrayList<>();
        File file = new File(filePath);
        if (!file.exists()) {
            return list;
        }
        try (FileReader reader = new FileReader(file)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray jsonArray = jsonObject.getAsJsonArray(key);
            if (jsonArray == null) {
                return list;
            }
            for (int i = 0; i < jsonArray.size(); i++) {
                list.add(jsonArray.get(i).getAsString());
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}
