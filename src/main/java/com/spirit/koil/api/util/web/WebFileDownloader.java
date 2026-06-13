package com.spirit.koil.api.util.web;

import com.google.gson.*;
import com.spirit.koil.api.util.system.DeviceInfoManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.spirit.Main.SUBLOGGER;

public class WebFileDownloader {
    public static void downloadFile(String urlString, String fileName, String savePath, int mbValue) {
        if (!DeviceInfoManager.hasInternetAccess()) {
            DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, urlString, Paths.get(savePath, fileName).toString(), 0, -1, "No internet connection.");
            return;
        }
        SUBLOGGER.logI("File-Management thread", "Requesting Online Download for: " + urlString);
        downloadToPath(urlString, fileName, savePath, mbValue, false);
    }

    public static void downloadCheckedFile(String urlString, String fileName, String savePath, int mbValue) {
        if (!DeviceInfoManager.hasInternetAccess()) {
            SUBLOGGER.logE("File-Management thread", "No internet connection. Cannot download: " + urlString);
            DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, urlString, Paths.get(savePath, fileName).toString(), 0, -1, "No internet connection.");
            return;
        }

        SUBLOGGER.logI("File-Management thread", "Requesting Online Download for: " + urlString);
        downloadToPath(urlString, fileName, savePath, mbValue, true);
    }

    public static void assetsUpdater(Path primaryJsonFile, Path secondaryJsonFile, String downloadPath) {
        if (DeviceInfoManager.hasInternetAccess()) {
            try {
                JsonObject primaryJson = JsonParser.parseReader(new FileReader(primaryJsonFile.toFile())).getAsJsonObject();
                String primaryKey = primaryJson.get("key").getAsString();

                JsonObject secondaryJson = JsonParser.parseReader(new FileReader(secondaryJsonFile.toFile())).getAsJsonObject();
                String secondaryKey = secondaryJson.get("key").getAsString();
                String fileUrl = secondaryJson.get("url").getAsString();

                if (!primaryKey.equals(secondaryKey)) {
                    SUBLOGGER.logI("File-Management thread", "Keys do not match. Downloading from URL: " + fileUrl);
                    DownloadProgressTracker.emit(DownloadProgressTracker.Type.INFO, fileUrl, downloadPath, 0, -1, "Asset key changed. Downloading asset list.");

                    downloadFolder(fileUrl, downloadPath);

                    primaryJson.addProperty("key", secondaryKey);

                    try (FileWriter writer = new FileWriter(primaryJsonFile.toFile())) {
                        new Gson().toJson(primaryJson, writer);
                    }

                    SUBLOGGER.logI("File-Management thread", "Primary JSON key updated to match the secondary key.");
                } else {
                    SUBLOGGER.logI("File-Management thread", "Keys match. No download necessary.");
                    DownloadProgressTracker.emit(DownloadProgressTracker.Type.SKIP, null, downloadPath, 0, -1, "Asset keys match. No folder download needed.");
                }
            } catch (FileNotFoundException e) {
                SUBLOGGER.logE("File-Management thread", "File not found: " + e.getMessage());
                DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, null, downloadPath, 0, -1, "Asset update file missing: " + e.getMessage());
            } catch (IOException e) {
                SUBLOGGER.logE("File-Management thread", "IO error: " + e.getMessage());
                DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, null, downloadPath, 0, -1, "Asset update IO error: " + e.getMessage());
            } catch (JsonSyntaxException | IllegalStateException e) {
                SUBLOGGER.logE("File-Management thread", "Error parsing JSON: " + e.getMessage());
                DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, null, downloadPath, 0, -1, "Asset update JSON error: " + e.getMessage());
            }
        }
    }

    public static void downloadFolder(String fileListUrl, String savePath) {
        if (DeviceInfoManager.hasInternetAccess()) {

            SUBLOGGER.logI("File-Management thread", "Fetching file list from URL: " + fileListUrl);
            DownloadProgressTracker.emit(DownloadProgressTracker.Type.INFO, fileListUrl, savePath, 0, -1, "Fetching file list.");

            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(fileListUrl).openConnection();
                connection.setRequestProperty("Accept", "application/json");

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    JsonArray fileArray = JsonParser.parseReader(new InputStreamReader(connection.getInputStream())).getAsJsonArray();

                    for (JsonElement fileElement : fileArray) {
                        JsonObject fileObject = fileElement.getAsJsonObject();
                        String filePath = fileObject.get("path").getAsString();
                        String fileName = fileObject.get("name").getAsString();
                        String downloadUrl = fileObject.get("url").getAsString();

                        downloadFile(downloadUrl, fileName, savePath + "/" + filePath, 64);
                    }
                    SUBLOGGER.logI("File-Management thread", "All files downloaded from list.");
                    DownloadProgressTracker.emit(DownloadProgressTracker.Type.COMPLETE, fileListUrl, savePath, fileArray.size(), fileArray.size(), "Folder list complete.");
                } else {
                    SUBLOGGER.logE("File-Management thread", "Failed to fetch file list: " + connection.getResponseMessage());
                    DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, fileListUrl, savePath, 0, -1, "Failed to fetch file list: " + connection.getResponseMessage());
                }

                connection.disconnect();
            } catch (IOException e) {
                SUBLOGGER.logE("File-Management thread", "Error downloading files from list: " + e);
                DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, fileListUrl, savePath, 0, -1, "Folder download error: " + e.getMessage());
            }
        }
    }

    public static void updateFileWithTemp(String urlString, String existingFileName, String savePath, int mbValue) {
        if (DeviceInfoManager.hasInternetAccess()) {

            SUBLOGGER.logI("File-Management thread", "Getting updates for file: " + existingFileName + " from URL: " + urlString);

            Path tempFilePath = Path.of(savePath, "koil-temp-file");
            Path existingFilePath = Path.of(savePath, existingFileName);

            downloadFile(urlString, "koil-temp-file", savePath, mbValue);

            try {
                JsonObject existingJson = JsonParser.parseReader(new FileReader(existingFilePath.toFile())).getAsJsonObject();
                JsonObject tempJson = JsonParser.parseReader(new FileReader(tempFilePath.toFile())).getAsJsonObject();

                boolean updated = false;

                for (String key : tempJson.keySet()) {
                    if (!existingJson.has(key)) {
                        existingJson.add(key, tempJson.get(key));
                        SUBLOGGER.logI("File-Management thread", "Added missing line: " + key + " to: " + existingFileName);
                        updated = true;
                    }
                }

                if (updated) {
                    try (BufferedWriter writer = Files.newBufferedWriter(existingFilePath)) {
                        Gson gson = new GsonBuilder().setPrettyPrinting().create();
                        gson.toJson(existingJson, writer);
                    }
                    SUBLOGGER.logI("File-Management thread", "Update completed. New entries added to: " + existingFileName);
                } else {
                    SUBLOGGER.logI("File-Management thread", "No new entries found. No update necessary.");
                }
            } catch (IOException | JsonSyntaxException e) {
                SUBLOGGER.logE("File-Management thread", "Error updating files: " + e);
            } finally {
                try {
                    Files.deleteIfExists(tempFilePath);
                } catch (IOException e) {
                    SUBLOGGER.logE("File-Management thread", "Failed to delete temp file: " + e);
                }
            }
        }
    }

    private static void downloadToPath(String urlString, String fileName, String savePath, int mbValue, boolean skipExisting) {
        int bufferSize = bufferSize(mbValue);
        Path fullPath = FabricLoader.getInstance().getGameDir().resolve(Paths.get(savePath, fileName));
        DownloadProgressTracker.emit(DownloadProgressTracker.Type.REQUEST, urlString, fullPath.toString(), 0, -1, "Requesting download.");

        try {
            if (Files.notExists(fullPath.getParent())) {
                Files.createDirectories(fullPath.getParent());
                SUBLOGGER.logI("File-Management thread", "Created directories: " + fullPath.getParent());
            }

            if (skipExisting && Files.exists(fullPath)) {
                SUBLOGGER.logI("File-Management thread", "File already exists at: " + fullPath + ". Skipping download.");
                DownloadProgressTracker.emit(DownloadProgressTracker.Type.SKIP, urlString, fullPath.toString(), Files.size(fullPath), Files.size(fullPath), "File already exists.");
                return;
            }

            URLConnection connection = new URL(urlString).openConnection();
            long totalBytes = connection.getContentLengthLong();
            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutputStream = new FileOutputStream(fullPath.toFile())) {

                byte[] dataBuffer = new byte[bufferSize];
                int bytesRead;
                long written = 0;
                while ((bytesRead = in.read(dataBuffer, 0, bufferSize)) != -1) {
                    fileOutputStream.write(dataBuffer, 0, bytesRead);
                    written += bytesRead;
                    DownloadProgressTracker.emit(DownloadProgressTracker.Type.PROGRESS, urlString, fullPath.toString(), written, totalBytes, "Downloading.");
                }

                SUBLOGGER.logI("File-Management thread", "Downloaded file to: " + fullPath);
                DownloadProgressTracker.emit(DownloadProgressTracker.Type.COMPLETE, urlString, fullPath.toString(), written, totalBytes, "Downloaded file.");
            }
        } catch (IOException e) {
            SUBLOGGER.logE("File-Management thread", "Download failed: " + e);
            DownloadProgressTracker.emit(DownloadProgressTracker.Type.ERROR, urlString, fullPath.toString(), 0, -1, "Download failed: " + e.getMessage());
        }
    }

    private static int bufferSize(int mbValue) {
        return switch (mbValue) {
            case 1 -> 1024;
            case 4 -> 4096;
            case 8 -> 8192;
            case 16 -> 16384;
            case 32 -> 32768;
            case 64 -> 65536;
            default -> 1024;
        };
    }
}
