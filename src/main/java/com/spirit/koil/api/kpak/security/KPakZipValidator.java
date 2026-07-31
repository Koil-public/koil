package com.spirit.koil.api.kpak.security;

import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class KPakZipValidator {

    private static final long MAX_FILES = 10000;

    private static final long MAX_UNCOMPRESSED_SIZE =
        10L * 1024 * 1024 * 1024;

    private static final long MAX_SINGLE_FILE_SIZE =
        2L * 1024 * 1024 * 1024;


    public static void validate(ZipFile zip) throws IOException {

        long fileCount = 0;
        long totalSize = 0;

        var entries = zip.entries();

        while (entries.hasMoreElements()) {

            ZipEntry entry = entries.nextElement();

            if (entry.isDirectory()) {
                continue;
            }

            fileCount++;

            if (fileCount > MAX_FILES) {
                throw new SecurityException(
                    "Package contains too many files"
                );
            }

            long size = entry.getSize();

            if (size > MAX_SINGLE_FILE_SIZE) {
                throw new SecurityException(
                    "Package contains oversized file: "
                        + entry.getName()
                );
            }

            if (size > 0) {
                totalSize += size;
            }

            if (totalSize > MAX_UNCOMPRESSED_SIZE) {
                throw new SecurityException(
                    "Package extraction size exceeded"
                );
            }
        }
    }
}
