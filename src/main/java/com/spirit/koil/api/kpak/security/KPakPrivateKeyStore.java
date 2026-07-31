package com.spirit.koil.api.kpak.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class KPakPrivateKeyStore {

    private static final Path KEY_DIRECTORY =
        Path.of(
            System.getProperty("user.home"),
            ".koil",
            "keys"
        );

    private KPakPrivateKeyStore() {}

    public static void generate(String authorId) throws Exception {
        Files.createDirectories(KEY_DIRECTORY);

        KeyPairGenerator generator =
            KeyPairGenerator.getInstance(
                "Ed25519"
            );

        KeyPair pair = generator.generateKeyPair();

        Files.writeString(
            privatePath(authorId),
            Base64.getEncoder()
                .encodeToString(
                    pair.getPrivate()
                        .getEncoded()
                ),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );

        Files.writeString(
            publicPath(authorId),
            Base64.getEncoder()
                .encodeToString(
                    pair.getPublic()
                        .getEncoded()
                ),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    public static PrivateKey getPrivateKey(String authorId) throws Exception {
        Path path =
            privatePath(
                authorId
            );

        if (!Files.exists(path)) {
            throw new IOException(
                "Missing private key for "
                    + authorId
            );
        }

        byte[] keyBytes = Base64.getDecoder().decode(Files.readString(path));

        return KeyFactory
            .getInstance(
                "Ed25519"
            ).generatePrivate(
                new PKCS8EncodedKeySpec(
                    keyBytes
                )
            );
    }

    public static String getPublicKey(String authorId) throws IOException {
        Path path = publicPath(authorId);

        if (!Files.exists(path)) {
            throw new IOException("Missing public key for " + authorId);
        }

        return Files.readString(path);
    }

    public static boolean exists(String authorId) {
        return Files.exists(
            privatePath(authorId)
        );
    }

    public static void remove(String authorId) throws IOException {
        Files.deleteIfExists(
            privatePath(authorId)
        );

        Files.deleteIfExists(
            publicPath(authorId)
        );
    }

    private static Path privatePath(String authorId) {
        return KEY_DIRECTORY.resolve(
            authorId + ".private"
        );
    }

    private static Path publicPath(String authorId) {
        return KEY_DIRECTORY.resolve(
            authorId + ".public"
        );
    }
}
