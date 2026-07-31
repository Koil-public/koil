package com.spirit.koil.api.kpak.builder;

import com.spirit.koil.api.kpak.PackageOperation;
import com.spirit.koil.api.kpak.security.KPakHashUtil;
import com.spirit.koil.api.kpak.security.KPakPrivateKeyStore;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class KPakBuilder {

    private final Path inputDirectory;
    private final Path outputFile;
    private final List<PackageOperation> operations = new ArrayList<>();

    public KPakBuilder(Path inputDirectory, Path outputFile) {
        this.inputDirectory = inputDirectory;
        this.outputFile = outputFile;
    }

    public KPakBuilder addFile(String path) throws Exception {
        Path file = inputDirectory.resolve(path);

        try (InputStream input = Files.newInputStream(file)) {
            operations.add(
                new PackageOperation(
                    "replace",
                    path,
                    KPakHashUtil.sha256(input)
                )
            );
        }

        return this;
    }

    public void build(KPakManifestBuilder manifestBuilder, String authorId) throws Exception {
        createZip();

        Path tempManifest = Files.createTempFile(
            "package",
            ".json"
        );

        manifestBuilder
            .operations(operations)
            .authorId(authorId)
            .write(tempManifest);

        injectManifest(tempManifest);

        sign(manifestBuilder, authorId);
    }

    private void createZip() throws Exception {
        Files.createDirectories(
            outputFile.getParent()
        );

        try (ZipOutputStream zip =
                 new ZipOutputStream(
                     Files.newOutputStream(outputFile)
                 )) {

            for (PackageOperation operation : operations) {

                Path file =
                    inputDirectory.resolve(
                        operation.getPath()
                    );

                zip.putNextEntry(
                    new ZipEntry(
                        operation.getPath()
                    )
                );

                Files.copy(
                    file,
                    zip
                );

                zip.closeEntry();
            }
        }
    }

    private void injectManifest(Path manifest) throws Exception {
        Path temp = Files.createTempFile(
            "kpak",
            ".zip"
        );

        try (FileSystem fs =
                 FileSystems.newFileSystem(
                     outputFile,
                     (ClassLoader) null
                 )) {

            Files.copy(
                manifest,
                fs.getPath("/package.json"),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private void sign(
        KPakManifestBuilder manifestBuilder,
        String authorId
    ) throws Exception {

        String hash;

        try (InputStream input =
                 Files.newInputStream(outputFile)) {

            hash =
                KPakHashUtil.sha256(
                    input
                );
        }

        String identity =
            manifestBuilder
                .build()
                .getPackageIdentity();

        String data =
            identity
                + ":"
                + hash;

        PrivateKey key =
            KPakPrivateKeyStore.getPrivateKey(
                authorId
            );

        Signature signature =
            Signature.getInstance(
                "Ed25519"
            );

        signature.initSign(
            key
        );

        signature.update(
            data.getBytes(
                StandardCharsets.UTF_8
            )
        );

        manifestBuilder.signature(
            Base64.getEncoder()
                .encodeToString(
                    signature.sign()
                )
        );
    }
}
