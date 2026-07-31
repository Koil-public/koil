package com.spirit.koil.api.kpak.security;

import java.io.InputStream;
import java.security.MessageDigest;

public final class KPakHashUtil {

    private KPakHashUtil() {
    }


    public static String sha256(InputStream input) throws Exception {

        MessageDigest digest =
            MessageDigest.getInstance("SHA-256");

        byte[] buffer = new byte[8192];

        int read;

        while ((read = input.read(buffer)) != -1) {

            digest.update(
                buffer,
                0,
                read
            );
        }

        StringBuilder result =
            new StringBuilder();

        for (byte value : digest.digest()) {

            result.append(
                String.format(
                    "%02x",
                    value
                )
            );
        }

        return result.toString();
    }
}
