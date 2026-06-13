package com.spirit.koil.api.util.file;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.spirit.Main.PKG_SUBLOGGER;
import static com.spirit.Main.decrypt;

public class KoilPackageManager {
    private static final String MODS_FOLDER = "./mods";
    private static final String GAME_DIRECTORY = String.valueOf(FabricLoader.getInstance().getGameDir());
    private static List<String> validDigits;
    private static List<String> validSerial;
    private static Set<String> verifiedAuthors;
    private static final String VALID_DIGITS_FILE = "./koil/auth/validDigits.json";
    private static final String VALID_SERIAL_FILE = "./koil/auth/validSerial.json";
    private static final String VERIFIED_AUTHORS_FILE = "./koil/auth/verifiedAuthors.json";

    public record PackageEntry(String relativePath, boolean directory, long size, boolean existsInInstance, String operation, String sha256) {
    }

    public record PendingPackage(
            File source,
            String id,
            String displayName,
            String packageVersion,
            String targetKoilVersion,
            String authorId,
            String author,
            String serial,
            boolean zipPackage,
            List<PackageEntry> entries,
            boolean valid,
            String status
    ) {
        public int fileCount() {
            int count = 0;
            for (PackageEntry entry : entries) {
                if (!entry.directory()) {
                    count++;
                }
            }
            return count;
        }

        public int changedExistingFileCount() {
            int count = 0;
            for (PackageEntry entry : entries) {
                if (!entry.directory() && "replace".equals(entry.operation())) {
                    count++;
                }
            }
            return count;
        }

        public int addedFileCount() {
            int count = 0;
            for (PackageEntry entry : entries) {
                if (!entry.directory() && "add".equals(entry.operation())) {
                    count++;
                }
            }
            return count;
        }

        public int removedFileCount() {
            int count = 0;
            for (PackageEntry entry : entries) {
                if (!entry.directory() && "remove".equals(entry.operation())) {
                    count++;
                }
            }
            return count;
        }
    }


    private static void loadConfiguration() {
        validDigits = loadListFromJson(VALID_DIGITS_FILE, "validDigits");
        validSerial = loadListFromJson(VALID_SERIAL_FILE, "validSerial");
        verifiedAuthors = new HashSet<>(loadListFromJson(VERIFIED_AUTHORS_FILE, "verifiedAuthors"));
    }

    private static List<String> loadListFromJson(String filePath, String key) {
        List<String> list = new ArrayList<>();
        File file = new File(filePath);

        if (!file.exists()) {
            PKG_SUBLOGGER.logE("Json-Validator tread", "Json File not found: " + filePath);
            return list;
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray jsonArray = jsonObject.getAsJsonArray(key);

            for (int i = 0; i < jsonArray.size(); i++) {
                list.add(jsonArray.get(i).getAsString());
            }
        } catch (IOException e) {
            PKG_SUBLOGGER.logE("Json-Validator tread", "Error reading file: " + filePath + e);
        } catch (Exception e) {
            PKG_SUBLOGGER.logE("Json-Validator tread", "Unexpected error parsing JSON from file: " + filePath + e);
        }

        return list;
    }


    public static void packageMain() throws IOException {
        PKG_SUBLOGGER.logI("Package thread", "Searching for packages");
        List<PendingPackage> pendingPackages = findPendingPackages();
        if (pendingPackages.isEmpty()) {
            PKG_SUBLOGGER.logI("Package thread", "No packages found.");
            return;
        }
        for (PendingPackage pendingPackage : pendingPackages) {
            if (pendingPackage.valid()) {
                PKG_SUBLOGGER.logI("Package thread", "Package pending user approval: " + pendingPackage.id());
            } else {
                PKG_SUBLOGGER.logW("Package thread", "Invalid package pending review: " + pendingPackage.id() + " | " + pendingPackage.status());
            }
        }
    }

    public static List<PendingPackage> findPendingPackages() {
        loadConfiguration();
        List<PendingPackage> packages = new ArrayList<>();
        collectPendingPackages(new File(MODS_FOLDER), packages);
        collectPendingPackages(new File("."), packages);
        packages.sort(Comparator.comparing(PendingPackage::id));
        return packages;
    }

    public static String authConfigurationIssue() {
        loadConfiguration();
        List<String> missing = new ArrayList<>();
        if (validDigits == null || validDigits.isEmpty()) {
            missing.add("validDigits");
        }
        if (validSerial == null || validSerial.isEmpty()) {
            missing.add("validSerial");
        }
        if (verifiedAuthors == null || verifiedAuthors.isEmpty()) {
            missing.add("verifiedAuthors");
        }
        return missing.isEmpty() ? "" : "Missing package auth data: " + String.join(", ", missing);
    }

    private static void collectPendingPackages(File folder, List<PendingPackage> packages) {
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return;
        }
        File[] koilPackages = folder.listFiles((dir, name) -> {
            if (!name.startsWith("koil-package-") || name.length() <= "koil-package-".length()) {
                return false;
            }
            File file = new File(dir, name);
            return file.isDirectory() || name.toLowerCase(Locale.ROOT).endsWith(".zip");
        });
        if (koilPackages == null) {
            return;
        }
        for (File koilPackage : koilPackages) {
            PendingPackage pendingPackage = inspectPackage(koilPackage);
            boolean alreadyKnown = packages.stream().anyMatch(existing -> existing.source().equals(koilPackage));
            if (!alreadyKnown) {
                packages.add(pendingPackage);
            }
        }
    }

    public static PendingPackage inspectPackage(File koilPackage) {
        if (koilPackage == null) {
            return invalidPackage(new File(""), "missing", "Missing package file.");
        }
        String packageName = packageId(koilPackage);
        PKG_SUBLOGGER.logI("Package thread", "Validating package preview (id:" + packageName + ")");
        if (!validateFolderName(packageName)) {
            return invalidPackage(koilPackage, packageName, "Invalid package name.");
        }
        try {
            PackageJson packageJson = readPackageJson(koilPackage);
            if (packageJson == null) {
                return invalidPackage(koilPackage, packageName, "Missing package.json.");
            }
            boolean authorValid = checkAuthor(packageJson.author());
            boolean serialValid = checkSerial(packageJson.serial());
            List<PackageEntry> entries = listPackageEntries(koilPackage, packageJson);
            boolean valid = authorValid && serialValid;
            String status = valid ? "Ready for review." : packageValidationStatus(authorValid, serialValid);
            return new PendingPackage(
                    koilPackage,
                    packageJson.id().isBlank() ? packageName : packageJson.id(),
                    packageJson.displayName().isBlank() ? packageName : packageJson.displayName(),
                    packageJson.packageVersion(),
                    packageJson.targetKoilVersion(),
                    packageJson.authorId(),
                    packageJson.author(),
                    packageJson.serial(),
                    isZipPackage(koilPackage),
                    entries,
                    valid,
                    status
            );
        } catch (Exception exception) {
            PKG_SUBLOGGER.logE("Package thread", "Package preview failed (id:" + packageName + "): " + exception.getMessage());
            return invalidPackage(koilPackage, packageName, exception.getMessage());
        }
    }

    private static PendingPackage invalidPackage(File source, String id, String status) {
        return new PendingPackage(source, id, id, "", "", "", "", "", source != null && isZipPackage(source), List.of(), false, status);
    }

    private static String packageValidationStatus(boolean authorValid, boolean serialValid) {
        String authIssue = authConfigurationIssue();
        if (!authIssue.isBlank()) {
            return authIssue;
        }
        if (!authorValid && !serialValid) {
            return "Invalid author and serial.";
        }
        if (!authorValid) {
            return "Invalid author.";
        }
        if (!serialValid) {
            return "Invalid serial.";
        }
        return "Invalid package identity.";
    }

    public static void applyPackage(PendingPackage pendingPackage) throws IOException {
        if (pendingPackage == null) {
            return;
        }
        loadConfiguration();
        PendingPackage freshPackage = inspectPackage(pendingPackage.source());
        if (!freshPackage.valid()) {
            throw new IOException("Package is not valid: " + freshPackage.status());
        }
        removeSerial(freshPackage.serial());
        if (freshPackage.zipPackage()) {
            copyZipPackage(freshPackage.source(), freshPackage.entries());
            Files.deleteIfExists(freshPackage.source().toPath());
        } else {
            pairFolders(freshPackage.source(), freshPackage.entries());
            deleteDirectory(freshPackage.source());
        }
        PKG_SUBLOGGER.logI("Package thread", "Applied package and removed source: " + freshPackage.id());
    }

    private record PackageOperation(String operation, String sha256) {
    }

    private record PackageJson(
            String author,
            String serial,
            String id,
            String displayName,
            String packageVersion,
            String targetKoilVersion,
            String authorId,
            Map<String, PackageOperation> operations
    ) {
    }

    private static String packageId(File file) {
        String name = file.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static boolean isZipPackage(File file) {
        return file != null && file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean validateFolderName(String folderName) {
        String prefix = KoilPackageIdentity.PACKAGE_PREFIX;
        if (!folderName.startsWith(prefix)) {
            PKG_SUBLOGGER.logW("Package thread", "Folder name does not start with the required prefix (id:" + folderName + ")");
            return false;
        }

        String encryptedPart = folderName.substring(prefix.length());
        PKG_SUBLOGGER.logI("Package thread", "Encryption is correct: " + encryptedPart + "(id:" + folderName + ")");

        if (encryptedPart.length() < 3 + 8 + 16) {
            PKG_SUBLOGGER.logW("Package thread", "Encryption length is incorrect: " + encryptedPart + "(id:" + folderName + ")");
            return false;
        }

        String fixedPart = encryptedPart.substring(0, 3);
        String authorPart = encryptedPart.substring(3, 11);
        String numbersPart = encryptedPart.substring(11);

        analyzingLog(folderName, fixedPart, authorPart, numbersPart);

        boolean prefixValid = checkPrefix(fixedPart);
        boolean authorValid = checkAuthor(authorPart);
        boolean numbersValid = checkNumbers(numbersPart);

        return prefixValid && authorValid && numbersValid;
    }

    private static void analyzingLog(String folderName, String fixedPart, String authorPart, String numbersPart) {
        String logMessage = String.format("""
                        Analysing package
                        |-- Statement: %s | Valid %b
                        |-- Author:    %s | Valid %b
                        |-- UUID:      %s | Valid %b
                        """,
                fixedPart,
                checkPrefix(fixedPart),
                authorPart,
                checkAuthor(authorPart),
                numbersPart,
                checkNumbers(numbersPart)
        );

        PKG_SUBLOGGER.logI("Package thread", logMessage + "(id:" + folderName + ")");
    }


    private static boolean checkPrefix(String prefix) {
        return prefix.equals(KoilPackageIdentity.LEGACY_STATEMENT);
    }

    private static boolean checkAuthor(String author) {
        return credentialMatches(verifiedAuthors, author);
    }

    private static boolean checkNumbers(String numbers) {
        return credentialMatches(validDigits, numbers);
    }

    private static boolean checkSerial(String serial) {
        return credentialMatches(validSerial, serial);
    }

    private static boolean credentialMatches(Collection<String> validValues, String value) {
        if (validValues == null || value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return KoilPackageIdentity.credentialMatches(validValues, trimmed);
    }

    private static boolean validateJson(File jsonFile) {
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            String author = jsonObject.get("author").getAsString();
            String serial = jsonObject.get("serial").getAsString();

            // Validate decrypted author and serial
            if (checkAuthor(author) && checkSerial(serial)) {
                removeSerial(serial);
                return true;
            }
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static PackageJson readPackageJson(File koilPackage) throws IOException {
        if (isZipPackage(koilPackage)) {
            try (ZipFile zipFile = new ZipFile(koilPackage)) {
                ZipEntry packageJsonEntry = findPackageJsonEntry(zipFile, packageId(koilPackage));
                if (packageJsonEntry == null) {
                    return null;
                }
                try (InputStream inputStream = zipFile.getInputStream(packageJsonEntry);
                     InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                    return parsePackageJson(JsonParser.parseReader(reader).getAsJsonObject());
                }
            }
        }
        File contentRoot = packageContentRoot(koilPackage);
        File jsonFile = new File(contentRoot, "package.json");
        if (!jsonFile.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(jsonFile)) {
            return parsePackageJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    private static File packageContentRoot(File koilPackage) {
        if (koilPackage == null || isZipPackage(koilPackage)) {
            return koilPackage;
        }
        File rootManifest = new File(koilPackage, "package.json");
        if (rootManifest.exists()) {
            return koilPackage;
        }
        File[] children = koilPackage.listFiles(File::isDirectory);
        if (children == null || children.length != 1) {
            return koilPackage;
        }
        File nestedManifest = new File(children[0], "package.json");
        return nestedManifest.exists() ? children[0] : koilPackage;
    }

    private static PackageJson parsePackageJson(JsonObject jsonObject) {
        return new PackageJson(
                requiredString(jsonObject, "author"),
                requiredString(jsonObject, "serial"),
                optionalString(jsonObject, "id", optionalString(jsonObject, "packageId", "")),
                optionalString(jsonObject, "displayName", optionalString(jsonObject, "name", "")),
                optionalString(jsonObject, "packageVersion", optionalString(jsonObject, "version", "")),
                optionalString(jsonObject, "targetKoilVersion", optionalString(jsonObject, "koilVersion", "")),
                optionalString(jsonObject, "authorId", ""),
                parseOperations(jsonObject)
        );
    }

    private static String requiredString(JsonObject jsonObject, String key) {
        String value = optionalString(jsonObject, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing package manifest field: " + key);
        }
        return value;
    }

    private static String optionalString(JsonObject jsonObject, String key, String fallback) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            String value = jsonObject.get(key).getAsString();
            return value == null ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Map<String, PackageOperation> parseOperations(JsonObject jsonObject) {
        Map<String, PackageOperation> operations = new LinkedHashMap<>();
        if (jsonObject == null || !jsonObject.has("operations") || !jsonObject.get("operations").isJsonArray()) {
            return operations;
        }
        JsonArray array = jsonObject.getAsJsonArray("operations");
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                continue;
            }
            JsonObject operationObject = array.get(i).getAsJsonObject();
            String path = normalizePackagePath(optionalString(operationObject, "path", optionalString(operationObject, "target", "")));
            if (path.isBlank()) {
                continue;
            }
            String operation = normalizeOperation(optionalString(operationObject, "operation", optionalString(operationObject, "op", "")));
            String sha256 = optionalString(operationObject, "sha256", optionalString(operationObject, "hash", ""));
            operations.put(path, new PackageOperation(operation, sha256));
        }
        return operations;
    }

    private static ZipEntry findPackageJsonEntry(ZipFile zipFile, String packageRoot) {
        ZipEntry fallback = null;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String normalized = normalizeZipEntryName(entry.getName(), packageRoot);
            if ("package.json".equals(normalized)) {
                return entry;
            }
            String rawName = entry.getName() == null ? "" : entry.getName().replace("\\", "/");
            if (!entry.isDirectory()
                    && fallback == null
                    && rawName.endsWith("/package.json")
                    && !rawName.startsWith("__MACOSX/")
                    && !rawName.contains("/._")) {
                fallback = entry;
            }
        }
        return fallback;
    }

    private static String zipPackageRoot(ZipFile zipFile, String packageRoot) {
        ZipEntry packageJson = findPackageJsonEntry(zipFile, packageRoot);
        if (packageJson == null) {
            return packageRoot;
        }
        String normalized = packageJson.getName() == null ? "" : packageJson.getName().replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "" : normalized.substring(0, slash);
    }

    private static List<PackageEntry> listPackageEntries(File koilPackage, PackageJson packageJson) throws IOException {
        List<PackageEntry> entries = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        if (isZipPackage(koilPackage)) {
            try (ZipFile zipFile = new ZipFile(koilPackage)) {
                String packageRoot = zipPackageRoot(zipFile, packageId(koilPackage));
                Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
                while (zipEntries.hasMoreElements()) {
                    ZipEntry entry = zipEntries.nextElement();
                    String relativePath = normalizeZipEntryName(entry.getName(), packageRoot);
                    if (shouldSkipPackagePath(relativePath)) {
                        continue;
                    }
                    entries.add(toPackageEntry(relativePath, entry.isDirectory(), entry.getSize(), packageJson.operations()));
                    seenPaths.add(relativePath);
                }
            }
        } else {
            Path root = packageContentRoot(koilPackage).toPath();
            try (var stream = Files.walk(root)) {
                stream.filter(path -> !path.equals(root)).forEach(path -> {
                    String relativePath = root.relativize(path).toString().replace("\\", "/");
                    if (!shouldSkipPackagePath(relativePath)) {
                        entries.add(toPackageEntry(relativePath, Files.isDirectory(path), safeSize(path), packageJson.operations()));
                        seenPaths.add(relativePath);
                    }
                });
            }
        }
        for (Map.Entry<String, PackageOperation> operation : packageJson.operations().entrySet()) {
            if ("remove".equals(operation.getValue().operation()) && !seenPaths.contains(operation.getKey())) {
                entries.add(toPackageEntry(operation.getKey(), false, 0L, packageJson.operations()));
            }
        }
        entries.sort(Comparator
                .comparing(PackageEntry::relativePath, Comparator.comparingInt(String::length))
                .thenComparing(PackageEntry::relativePath));
        return entries;
    }

    private static PackageEntry toPackageEntry(String relativePath, boolean directory, long size, Map<String, PackageOperation> operations) {
        relativePath = normalizePackagePath(relativePath);
        File target = new File(GAME_DIRECTORY, relativePath);
        PackageOperation declared = operations.get(relativePath);
        String operation = declared == null ? inferOperation(target, directory) : declared.operation();
        String sha256 = declared == null ? "" : declared.sha256();
        return new PackageEntry(relativePath, directory, size, target.exists(), operation, sha256);
    }

    private static String inferOperation(File target, boolean directory) {
        if (directory) {
            return "folder";
        }
        return target.exists() ? "replace" : "add";
    }

    private static String normalizeOperation(String operation) {
        String normalized = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "delete", "remove" -> "remove";
            case "replace", "overwrite" -> "replace";
            case "add", "create" -> "add";
            default -> "auto";
        };
    }

    private static String normalizePackagePath(String path) {
        String normalized = path == null ? "" : path.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static long safeSize(Path path) {
        try {
            return Files.isDirectory(path) ? 0L : Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static boolean shouldSkipPackagePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return true;
        }
        String normalized = relativePath.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank()
                || "package.json".equals(normalized)
                || normalized.startsWith("__MACOSX/")
                || normalized.contains("/._")
                || normalized.startsWith("._");
    }

    private static String normalizeZipEntryName(String name, String packageRoot) {
        String normalized = name == null ? "" : name.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String root = packageRoot == null ? "" : packageRoot.replace("\\", "/");
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }

        if (!root.isBlank()) {
            if (normalized.equals(root)) {
                return "";
            }
            if (normalized.startsWith(root + "/")) {
                return normalized.substring(root.length() + 1);
            }
        }

        return normalized;
    }

    private static void pairFolders(File koilPackage, List<PackageEntry> entries) throws IOException {
        File contentRoot = packageContentRoot(koilPackage);
        File[] packageContents = contentRoot.listFiles();
        if (packageContents != null) {
            for (File item : packageContents) {
                String relativePath = item.getName();
                if (shouldSkipPackagePath(relativePath) || isRemoveOperation(entries, relativePath)) {
                    continue;
                }

                File target = new File(GAME_DIRECTORY, item.getName());

                PKG_SUBLOGGER.logI("Package thread", "Pairing file/folder: " + item.getPath() + " to " + target.getPath());

                if (item.isDirectory()) {
                    copyDirectory(item, target, entries, relativePath);
                } else {
                    verifyFileHash(item.toPath(), findEntry(entries, relativePath));
                    Files.copy(item.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    PKG_SUBLOGGER.logI("Package thread", "Replaced Existing file: " + item.getPath() + " to " + target.getPath());
                }
            }
        }
        applyRemoveOperations(entries);
    }

    private static void copyZipPackage(File koilPackage, List<PackageEntry> plannedEntries) throws IOException {
        try (ZipFile zipFile = new ZipFile(koilPackage)) {
            String packageRoot = zipPackageRoot(zipFile, packageId(koilPackage));
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String relativePath = normalizeZipEntryName(entry.getName(), packageRoot);
                if (shouldSkipPackagePath(relativePath) || isRemoveOperation(plannedEntries, relativePath)) {
                    continue;
                }
                Path target = Path.of(GAME_DIRECTORY).resolve(relativePath).normalize();
                Path gameRoot = Path.of(GAME_DIRECTORY).normalize();
                if (!target.startsWith(gameRoot)) {
                    PKG_SUBLOGGER.logW("Package thread", "Blocked unsafe zip entry outside instance root: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    PKG_SUBLOGGER.logI("Package thread", "Created directory: " + target);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    verifyStreamHash(zipFile, entry, findEntry(plannedEntries, relativePath));
                    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                    PKG_SUBLOGGER.logI("Package thread", "Copied zip file: " + entry.getName() + " to " + target);
                }
            }
        }
        applyRemoveOperations(plannedEntries);
    }

    private static void copyDirectory(File source, File target, List<PackageEntry> entries, String relativeRoot) throws IOException {
        if (!target.exists()) {
            target.mkdirs();
            PKG_SUBLOGGER.logI("Package thread", "Created directory: " + target.getPath());
        }
        for (File file : Objects.requireNonNull(source.listFiles())) {
            String relativePath = normalizePackagePath(relativeRoot + "/" + file.getName());
            if (shouldSkipPackagePath(relativePath) || isRemoveOperation(entries, relativePath)) {
                continue;
            }
            File targetFile = new File(target, file.getName());

            PKG_SUBLOGGER.logI("Package thread", "Copied file: " + file.getPath() + " to " + targetFile.getPath());

            if (file.isDirectory()) {
                copyDirectory(file, targetFile, entries, relativePath);
                PKG_SUBLOGGER.logI("Package thread", "Copied Directory file: " + file.getPath() + " to " + targetFile.getPath());
            } else {
                verifyFileHash(file.toPath(), findEntry(entries, relativePath));
                Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                PKG_SUBLOGGER.logI("Package thread", "Copied file: " + file.getPath() + " to " + targetFile.getPath());
            }
        }
    }

    private static PackageEntry findEntry(List<PackageEntry> entries, String relativePath) {
        String normalized = normalizePackagePath(relativePath);
        for (PackageEntry entry : entries) {
            if (entry.relativePath().equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isRemoveOperation(List<PackageEntry> entries, String relativePath) {
        PackageEntry entry = findEntry(entries, relativePath);
        return entry != null && "remove".equals(entry.operation());
    }

    private static void applyRemoveOperations(List<PackageEntry> entries) throws IOException {
        for (PackageEntry entry : entries) {
            if (!"remove".equals(entry.operation())) {
                continue;
            }
            Path target = Path.of(GAME_DIRECTORY).resolve(entry.relativePath()).normalize();
            Path gameRoot = Path.of(GAME_DIRECTORY).normalize();
            if (!target.startsWith(gameRoot)) {
                PKG_SUBLOGGER.logW("Package thread", "Blocked unsafe remove outside instance root: " + entry.relativePath());
                continue;
            }
            deletePath(target);
            PKG_SUBLOGGER.logI("Package thread", "Removed instance path: " + target);
        }
    }

    private static void deletePath(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isDirectory(target)) {
            try (var stream = Files.walk(target)) {
                List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
            }
            return;
        }
        Files.deleteIfExists(target);
    }

    private static void verifyFileHash(Path file, PackageEntry entry) throws IOException {
        if (entry == null || entry.sha256() == null || entry.sha256().isBlank()) {
            return;
        }
        String actual = sha256(file);
        if (!entry.sha256().equalsIgnoreCase(actual)) {
            throw new IOException("Hash mismatch for " + entry.relativePath());
        }
    }

    private static void verifyStreamHash(ZipFile zipFile, ZipEntry zipEntry, PackageEntry entry) throws IOException {
        if (entry == null || entry.sha256() == null || entry.sha256().isBlank()) {
            return;
        }
        try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
            String actual = sha256(inputStream);
            if (!entry.sha256().equalsIgnoreCase(actual)) {
                throw new IOException("Hash mismatch for " + entry.relativePath());
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file)) {
            return sha256(inputStream);
        }
    }

    private static String sha256(InputStream inputStream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                byte[] buffer = new byte[8192];
                while (digestInputStream.read(buffer) != -1) {
                    // Consume stream.
                }
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }

    private static void deleteDirectory(File directory) {
        File[] contents = directory.listFiles();
        if (contents != null) {
            for (File file : contents) {
                deleteDirectory(file);
            }
        }
        directory.delete();
    }

    private static void removeSerial(String serial) {
        if (serial == null) {
            return;
        }
        validSerial.remove(serial.trim());
        validSerial.remove(decrypt(serial.trim()));
    }
}
