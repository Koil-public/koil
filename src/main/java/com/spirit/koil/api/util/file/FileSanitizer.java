package com.spirit.koil.api.util.file;

import java.io.File;
import java.util.Objects;

import static com.spirit.Main.SUBLOGGER;

public class FileSanitizer {
        public static void sanitizeDirectory(File directory) {
            if (directory.exists() && directory.isDirectory()) {
                for (File file : Objects.requireNonNull(directory.listFiles())) {
                    if (file.isDirectory()) {
                        // Recursively sanitize directories
                        sanitizeDirectory(file);
                    } else {
                        // Rename files if necessary
                        String sanitizedName = sanitizeFileName(file.getName());
                        File newFile = new File(file.getParentFile(), sanitizedName);
                        if (!file.renameTo(newFile)) {
                            SUBLOGGER.logE("File-Sanitize thread", "Failed to rename file: " + file.getName());
                        }
                    }
                }
            }
        }

        private static String sanitizeFileName(String fileName) {
            return fileName.replace(" ", "_")
                    .replace(",", "")
                    .replace("'", "")
                    .replace("\"", "")
                    .replace("<", "")
                    .replace(">", "")
                    .replace(":", "")
                    .replace(";", "")
                    .replace("?", "")
                    .replace("*", "")
                    .replace("|", "");
        }
    }