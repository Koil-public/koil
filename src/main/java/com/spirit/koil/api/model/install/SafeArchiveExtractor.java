package com.spirit.koil.api.model.install;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Archive extraction that refuses absolute/parent-escaping and hostile symlink entries. */
public final class SafeArchiveExtractor {
    private SafeArchiveExtractor() {
    }

    public static void extract(Path archive, Path output, String archiveType) throws IOException {
        if ("zip".equals(archiveType)) {
            extractZip(archive, output);
        } else if ("tar.gz".equals(archiveType) || "tgz".equals(archiveType)) {
            extractTarGz(archive, output);
        } else {
            throw new IOException("Unsupported runtime archive type: " + archiveType);
        }
    }

    public static void extractZip(Path archive, Path output) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = safeTarget(output, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                        zip.transferTo(out);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    public static void extractTarGz(Path archive, Path output) throws IOException {
        try (InputStream input = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            byte[] header = new byte[512];
            while (true) {
                readFully(input, header);
                if (allZero(header)) {
                    return;
                }
                String name = text(header, 0, 100);
                String prefix = text(header, 345, 155);
                if (!prefix.isBlank()) {
                    name = prefix + "/" + name;
                }
                long size = octal(header, 124, 12);
                int type = header[156] & 0xFF;
                Path target = safeTarget(output, name);
                if (type == '5') {
                    Files.createDirectories(target);
                    skipFully(input, size);
                } else if (type == 0 || type == '0') {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                        copyExactly(input, out, size);
                    }
                } else if (type == '2') {
                    String linkName = text(header, 157, 100);
                    Path linkTarget = Path.of(linkName);
                    if (linkTarget.isAbsolute() || target.getParent() == null
                            || !target.getParent().resolve(linkTarget).normalize().startsWith(output.toAbsolutePath().normalize())) {
                        throw new IOException("Unsafe symlink in runtime archive: " + name);
                    }
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, linkTarget);
                    skipFully(input, size);
                } else {
                    skipFully(input, size);
                }
                long padding = (512L - size % 512L) % 512L;
                skipFully(input, padding);
            }
        } catch (EOFException exception) {
            throw new IOException("Runtime archive ended unexpectedly.", exception);
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long bytes) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long remaining = bytes;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException();
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped <= 0L) {
                if (input.read() < 0) {
                    throw new EOFException();
                }
                skipped = 1L;
            }
            remaining -= skipped;
        }
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] bytes, int offset, int length) {
        int end = offset;
        int maximum = Math.min(bytes.length, offset + length);
        while (end < maximum && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] bytes, int offset, int length) {
        String value = text(bytes, offset, length).replace("\u0000", "").trim();
        return value.isEmpty() ? 0L : Long.parseLong(value, 8);
    }

    public static Path safeTarget(Path root, String entryName) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(entryName).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Unsafe path in runtime archive: " + entryName);
        }
        return target;
    }
}
