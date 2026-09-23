package com.spirit.koil.api.model.retrieval;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Narrow JNI boundary. No TurboVec types escape this class. */
final class TurboVecNativeBridge implements AutoCloseable {
    private static final Availability AVAILABILITY = loadLibrary();
    private long handle;

    private TurboVecNativeBridge(long handle) {
        if (handle == 0L) throw new IllegalStateException("TurboVec native bridge returned an invalid handle");
        this.handle = handle;
    }

    static Availability availability() {
        return AVAILABILITY;
    }

    static TurboVecNativeBridge create(int dimensions, int bitWidth) {
        requireAvailable();
        return new TurboVecNativeBridge(createNative(dimensions, bitWidth));
    }

    static TurboVecNativeBridge load(Path path) {
        requireAvailable();
        return new TurboVecNativeBridge(loadNative(path.toAbsolutePath().toString()));
    }

    synchronized void add(long id, float[] embedding) {
        addNative(requireHandle(), id, embedding);
    }

    synchronized boolean remove(long id) {
        return removeNative(requireHandle(), id);
    }

    synchronized boolean contains(long id) {
        return containsNative(requireHandle(), id);
    }

    synchronized List<VectorSearchResult> search(float[] query, VectorSearchRequest request) {
        long[] allowed = request.allowedIds().stream().mapToLong(Long::longValue).sorted().toArray();
        long[] packed = searchNative(requireHandle(), query, request.limit(), allowed);
        if (packed == null || packed.length % 2 != 0) throw new IllegalStateException("TurboVec bridge returned malformed search results");
        List<VectorSearchResult> results = new ArrayList<>(packed.length / 2);
        for (int index = 0; index < packed.length; index += 2) {
            results.add(new VectorSearchResult(packed[index], Float.intBitsToFloat((int) packed[index + 1])));
        }
        return List.copyOf(results);
    }

    synchronized void sync(Path path) {
        syncNative(requireHandle(), path.toAbsolutePath().toString());
    }

    synchronized void calibrate(float[] sample) {
        calibrateNative(requireHandle(), sample);
    }

    synchronized long[] stats() {
        long[] stats = statsNative(requireHandle());
        if (stats == null || stats.length != 3) throw new IllegalStateException("TurboVec bridge returned malformed stats");
        return stats;
    }

    synchronized String version() {
        String version = versionNative();
        if (version == null || version.isBlank()) throw new IllegalStateException("TurboVec bridge returned no version");
        return version;
    }

    @Override
    public synchronized void close() {
        if (this.handle == 0L) return;
        closeNative(this.handle);
        this.handle = 0L;
    }

    private long requireHandle() {
        if (this.handle == 0L) throw new IllegalStateException("TurboVec bridge is closed");
        return this.handle;
    }

    private static void requireAvailable() {
        if (!AVAILABILITY.available()) throw new IllegalStateException(AVAILABILITY.detail());
    }

    private static Availability loadLibrary() {
        String configured = System.getProperty("koil.turbovec.library", "").strip();
        try {
            if (!configured.isEmpty()) {
                String incompatibility = platformCompatibilityProblem(Path.of(configured), System.getProperty("os.name", ""));
                if (!incompatibility.isBlank()) return new Availability(false, incompatibility);
            }
            if (configured.isEmpty()) System.loadLibrary("koil_turbovec");
            else System.load(configured);
            return new Availability(true, "");
        } catch (LinkageError | SecurityException exception) {
            String source = configured.isEmpty() ? "library koil_turbovec" : configured;
            return new Availability(false, "Unable to load TurboVec native bridge (" + source + "): " + exception.getMessage());
        }
    }

    static String expectedExtensionFor(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? ".dll" : os.contains("mac") ? ".dylib" : ".so";
    }

    static String platformCompatibilityProblem(Path path, String osName) {
        String expected = expectedExtensionFor(osName);
        String source = path == null ? "" : path.toString();
        if (!source.toLowerCase(java.util.Locale.ROOT).endsWith(expected)) {
            return "Configured TurboVec native bridge is incompatible with " + platformName(osName)
                    + ": expected a " + expected + " library, not " + source;
        }
        try {
            byte[] header = Files.readAllBytes(path);
            if (!hasExpectedHeader(header, osName)) {
                return "Configured TurboVec native bridge is not a " + platformBinaryName(osName) + " binary: " + source;
            }
            return "";
        } catch (IOException exception) {
            return "Configured TurboVec native bridge cannot be read: " + source + " (" + exception.getMessage() + ')';
        }
    }

    private static boolean hasExpectedHeader(byte[] header, String osName) {
        if (header.length < 4) return false;
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return header[0] == 'M' && header[1] == 'Z';
        if (os.contains("mac")) {
            int magic = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16) | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
            return magic == 0xfeedface || magic == 0xfeedfacf || magic == 0xcefaedfe || magic == 0xcffaedfe
                    || magic == 0xcafebabe || magic == 0xbebafeca;
        }
        return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
    }

    private static String platformName(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "Windows" : os.contains("mac") ? "macOS" : os.contains("linux") ? "Linux" : "this platform";
    }

    private static String platformBinaryName(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "PE/Windows" : os.contains("mac") ? "Mach-O" : os.contains("linux") ? "ELF/Linux" : "native";
    }

    record Availability(boolean available, String detail) {
        Availability {
            detail = detail == null ? "" : detail;
        }
    }

    private static native long createNative(int dimensions, int bitWidth);

    private static native long loadNative(String path);

    private static native void addNative(long handle, long id, float[] embedding);

    private static native boolean removeNative(long handle, long id);

    private static native boolean containsNative(long handle, long id);

    private static native long[] searchNative(long handle, float[] query, int limit, long[] allowedIds);

    private static native void syncNative(long handle, String path);

    private static native void calibrateNative(long handle, float[] sample);

    private static native long[] statsNative(long handle);

    private static native String versionNative();

    private static native void closeNative(long handle);
}
