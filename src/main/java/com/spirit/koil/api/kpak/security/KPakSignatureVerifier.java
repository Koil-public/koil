package com.spirit.koil.api.kpak.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;


public class KPakSignatureVerifier {


    public static boolean verify(
        String publicKey,
        String signature,
        String data
    ) throws Exception {

        byte[] keyBytes =
            Base64.getDecoder()
                .decode(publicKey);

        PublicKey key =
            KeyFactory
                .getInstance("Ed25519")
                .generatePublic(
                    new java.security.spec.X509EncodedKeySpec(
                        keyBytes
                    )
                );

        Signature verifier =
            Signature.getInstance("Ed25519");

        verifier.initVerify(key);

        verifier.update(
            data.getBytes(
                StandardCharsets.UTF_8
            )
        );

        return verifier.verify(
            Base64.getDecoder()
                .decode(signature)
        );
    }
}
