package com.spirit.koil.api.kpak.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

public final class KPakPrivateKeyStore {

    private static final Path KEY_DIRECTORY =
        Path.of(
            System.getProperty("user.home"),
            ".koil",
            "kpak",
            "keys"
        );

    private KPakPrivateKeyStore() {}

    public static KPakKeyIdentity ensure() throws Exception {
        Files.createDirectories(
            KEY_DIRECTORY
        );

        Path identity =
            KEY_DIRECTORY.resolve(
                "identity"
            );

        if (Files.exists(identity)) {
            String authorId =
                Files.readString(identity);

            return new KPakKeyIdentity(
                authorId,
                getPublicKey(authorId)
            );
        }

        return generate();
    }

    public static KPakKeyIdentity generate() throws Exception {
        Files.createDirectories(
            KEY_DIRECTORY
        );

        String authorId =
            UUID.randomUUID()
                .toString();

        KeyPairGenerator generator =
            KeyPairGenerator.getInstance(
                "Ed25519"
            );

        KeyPair pair =
            generator.generateKeyPair();

        Files.writeString(
            privatePath(authorId),
            Base64.getEncoder()
                .encodeToString(
                    pair.getPrivate()
                        .getEncoded()
                )
        );

        String publicKey =
            Base64.getEncoder()
                .encodeToString(
                    pair.getPublic()
                        .getEncoded()
                );

        Files.writeString(
            publicPath(authorId),
            publicKey
        );

        Files.writeString(
            KEY_DIRECTORY.resolve(
                "identity"
            ),
            authorId
        );

        return new KPakKeyIdentity(
            authorId,
            publicKey
        );
    }

    public static String getPublicKey(String authorId) throws Exception {
        return Files.readString(publicPath(authorId));
    }

    private static Path privatePath(String authorId) {
        return KEY_DIRECTORY.resolve(authorId + ".private");
    }

    private static Path publicPath(String authorId) {
        return KEY_DIRECTORY.resolve(authorId + ".public");
    }

    public static PrivateKey getPrivateKey(String authorId) throws Exception {
        Path path = privatePath(authorId);

        if (!Files.exists(path)) {
            throw new IOException(
                "Missing private key for "
                    + authorId
            );
        }

        byte[] keyBytes =
            Base64.getDecoder()
                .decode(
                    Files.readString(path)
                );

        return KeyFactory.getInstance("Ed25519")
            .generatePrivate(
                new PKCS8EncodedKeySpec(
                    keyBytes
                )
            );
    }
}
