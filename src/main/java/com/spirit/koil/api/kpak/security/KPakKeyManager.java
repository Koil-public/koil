package com.spirit.koil.api.kpak.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class KPakKeyManager {

    private static final Path KEY_DIRECTORY =
        Path.of(
            System.getProperty("user.home"),
            ".koil",
            "kpak",
            "keys"
        );

    private KPakKeyManager() {}

    public static KPakKeyPair getOrCreate(String authorId) throws Exception {
        Path directory =
            KEY_DIRECTORY.resolve(
                authorId
            );

        Path privatePath =
            directory.resolve(
                "private.key"
            );

        Path publicPath =
            directory.resolve(
                "public.key"
            );

        if (Files.exists(privatePath) && Files.exists(publicPath)) {
            return load(privatePath, publicPath);
        }

        Files.createDirectories(directory);

        KeyPairGenerator generator =
            KeyPairGenerator.getInstance(
                "Ed25519"
            );

        KeyPair pair = generator.generateKeyPair();

        Files.writeString(
            privatePath,
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
            publicPath,
            Base64.getEncoder()
                .encodeToString(
                    pair.getPublic()
                        .getEncoded()
                ),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );

        return new KPakKeyPair(pair.getPrivate(), pair.getPublic());
    }

    private static KPakKeyPair load(Path privatePath, Path publicPath) throws Exception {
        byte[] privateBytes =
            Base64.getDecoder()
                .decode(
                    Files.readString(
                        privatePath
                    )
                );

        byte[] publicBytes =
            Base64.getDecoder()
                .decode(
                    Files.readString(
                        publicPath
                    )
                );

        PrivateKey privateKey =
            KeyFactory.getInstance(
                "Ed25519"
            ).generatePrivate(
                new PKCS8EncodedKeySpec(
                    privateBytes
                )
            );

        PublicKey publicKey =
            KeyFactory.getInstance(
                "Ed25519"
            ).generatePublic(
                new X509EncodedKeySpec(
                    publicBytes
                )
            );

        return new KPakKeyPair(privateKey, publicKey);
    }


    public static String getPublicKey(String authorId) throws Exception {
        Path path =
            KEY_DIRECTORY
                .resolve(authorId)
                .resolve("public.key");

        return Files.readString(
            path
        );
    }
}
