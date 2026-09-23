package com.spirit.koil.api.model.install;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exact size + SHA-256 verification shared by model and runtime downloads. */
public final class DownloadVerification {
    private DownloadVerification() {
    }

    public static void verify(Path path, long expectedBytes, String expectedSha256) throws IOException {
        if (expectedBytes > 0L) {
            long actualBytes = Files.size(path);
            if (actualBytes != expectedBytes) {
                throw new IOException("Size verification failed for " + path.getFileName()
                        + ": expected " + expectedBytes + " bytes, got " + actualBytes + ".");
            }
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IOException("SHA-256 is unavailable.", exception);
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA-256 verification failed for " + path.getFileName() + ".");
        }
    }
}
