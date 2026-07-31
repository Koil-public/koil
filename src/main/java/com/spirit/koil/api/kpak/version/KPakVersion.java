package com.spirit.koil.api.kpak.version;

public class KPakVersion implements Comparable<KPakVersion> {

    private final int major;
    private final int minor;
    private final int patch;

    public KPakVersion(String version) {
        String clean = version.replaceAll("[^0-9.]", "");
        String[] parts = clean.split("\\.");

        major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
        minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

        patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
    }


    @Override
    public int compareTo(KPakVersion other) {
        if (major != other.major)
            return Integer.compare(
                major,
                other.major
            );

        if (minor != other.minor)
            return Integer.compare(
                minor,
                other.minor
            );

        return Integer.compare(
            patch,
            other.patch
        );
    }
}
