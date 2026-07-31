package com.spirit.koil.api.kpak.version;

public class KPakVersionRange {

    public static boolean matches(String current, String minimum, String maximum) {
        KPakVersion currentVersion = new KPakVersion(current);

        if (minimum != null && !minimum.isBlank()) {
            KPakVersion min = new KPakVersion(minimum);

            if (currentVersion.compareTo(min) < 0) {
                return false;
            }
        }

        if (maximum != null && !maximum.isBlank()) {
            KPakVersion max = new KPakVersion(maximum);

            return currentVersion.compareTo(max) <= 0;
        }

        return true;
    }
}
