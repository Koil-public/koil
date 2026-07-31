package com.spirit.koil.api.kpak.security;

import com.spirit.koil.api.kpak.install.KPakException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KPakTrustStore {

    private static final Map<String, String> KEYS =
        new ConcurrentHashMap<>();

    private KPakTrustStore() {}

    public static void register(String authorId, String publicKey) {
        KEYS.put(
            authorId,
            publicKey
        );
    }


    public static String getKey(String authorId) throws KPakException {
        String key = KEYS.get(authorId);
        if (key == null) {
            throw new KPakException(
                "Unknown package author: "
                    + authorId
            );
        }

        return key;
    }

    public static boolean exists(String authorId) {
        return KEYS.containsKey(
            authorId
        );
    }


    public static void remove(String authorId) {
        KEYS.remove(
            authorId
        );
    }
}
