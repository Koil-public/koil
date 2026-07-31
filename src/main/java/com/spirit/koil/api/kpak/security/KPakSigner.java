package com.spirit.koil.api.kpak.security;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

public final class KPakSigner {

    private KPakSigner() {}

    public static String sign(PrivateKey key, String data) throws Exception {
        Signature signature =
            Signature.getInstance(
                "Ed25519"
            );

        signature.initSign(key);
        signature.update(
            data.getBytes(
                StandardCharsets.UTF_8
            )
        );

        return Base64.getEncoder()
            .encodeToString(
                signature.sign()
            );
    }

    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder()
            .encodeToString(
                key.getEncoded()
            );
    }
}
