package com.spirit.koil.api.kpak.install;

import com.spirit.Main;
import com.spirit.koil.api.kpak.KPak;
import com.spirit.koil.api.kpak.PackageManifest;
import com.spirit.koil.api.kpak.PackageOperation;
import com.spirit.koil.api.kpak.backup.KPakBackupManager;
import com.spirit.koil.api.kpak.registry.KPakRegistry;
import com.spirit.koil.api.kpak.registry.KPakRegistryEntry;
import com.spirit.koil.api.kpak.security.KPakHashUtil;
import com.spirit.koil.api.kpak.security.KPakSignatureVerifier;
import com.spirit.koil.api.kpak.security.KPakTrustStore;
import com.spirit.koil.api.kpak.security.KPakZipValidator;
import com.spirit.koil.api.kpak.version.KPakVersionRange;
import com.spirit.koil.api.util.web.WebFileDownloader;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.zip.ZipFile;

public final class KPakInstaller {

    private static final Path GAME_DIRECTORY =
        FabricLoader.getInstance()
            .getGameDir()
            .normalize();


    private KPakInstaller() {
    }


    public static void install(
        KPak kPak
    ) throws Exception {

        PackageManifest manifest =
            kPak.getManifest();

        validateManifest(
            manifest
        );

        KPakInstallLock.acquire();

        KPakTransaction transaction =
            new KPakTransaction(
                manifest.getPackageIdentity(),
                manifest.getPackageVersion()
            );

        transaction.save();

        try {

            verifyHash(
                kPak
            );

            verifySignature(
                kPak,
                manifest
            );

            verifyVersion(
                manifest
            );

            try (ZipFile zip = kPak.open()) {

                KPakZipValidator.validate(
                    zip
                );

                transaction.setState(
                    KPakTransaction.State.BACKING_UP
                );

                transaction.save();

                KPakBackupManager.create(
                    manifest
                );

                transaction.setState(
                    KPakTransaction.State.WRITING_FILES
                );

                transaction.save();

                installFiles(
                    zip,
                    manifest
                );

                transaction.setState(
                    KPakTransaction.State.VERIFYING
                );

                transaction.save();

                verifyInstalled(
                    manifest
                );

                register(
                    manifest
                );

                transaction.setState(
                    KPakTransaction.State.COMPLETED
                );

                transaction.save();
            }

            transaction.delete();

            Main.PKG_SUBLOGGER.logI(
                "Package Installer",
                "Installed "
                    + manifest.getPackageIdentity()
            );
        } catch (Exception e) {

            Main.PKG_SUBLOGGER.logE(
                "Package Installer",
                "Installation failed: "
                    + e.getMessage()
            );

            KPakBackupManager.restore(
                manifest.getPackageIdentity()
            );

            transaction.setState(
                KPakTransaction.State.FAILED
            );

            transaction.save();

            throw e;
        } finally {

            KPakInstallLock.release();
        }
    }


    private static void validateManifest(
        PackageManifest manifest
    ) throws KPakException {

        if (!"confirmedkoilpackage"
            .equals(manifest.getSerial())) {

            throw new KPakException(
                "Invalid package serial"
            );
        }

        if (manifest.getOperations() == null
            || manifest.getOperations().isEmpty()) {

            throw new KPakException(
                "Package has no operations"
            );
        }
    }


    private static void verifyHash(
        KPak kPak
    ) throws Exception {

        String expected =
            WebFileDownloader.downloadText(
                    "https://eeverest.dev/koil/hash/"
                        + kPak.getName()
                )
                .trim()
                .toLowerCase();

        String actual;

        try (InputStream input = Files.newInputStream(kPak.getFile())) {
            actual =
                KPakHashUtil.sha256(
                    input
                );
        }

        if (!expected.equals(actual)) {

            throw new KPakException(
                "Package hash mismatch"
            );
        }
    }


    private static void verifySignature(
        KPak kPak,
        PackageManifest manifest
    ) throws Exception {

        String key =
            KPakTrustStore.getKey(
                manifest.getAuthorId()
            );

        String packageHash;

        try (InputStream input =
                 Files.newInputStream(
                     kPak.getFile()
                 )) {

            packageHash =
                KPakHashUtil.sha256(
                    input
                );
        }

        String signedData =
            manifest.getPackageIdentity()
                + ":"
                + packageHash;

        if (!KPakSignatureVerifier.verify(
            key,
            manifest.getSignature(),
            signedData
        )) {

            throw new KPakException(
                "Invalid package signature"
            );
        }
    }


    private static void verifyVersion(
        PackageManifest manifest
    ) throws Exception {

        if (!KPakVersionRange.matches(
            Main.VERSION,
            manifest.getMinKoilVersion(),
            manifest.getMaxKoilVersion()
        )) {

            throw new KPakException(
                "Unsupported Koil version"
            );
        }
    }


    private static void installFiles(
        ZipFile zip,
        PackageManifest manifest
    ) throws Exception {

        for (PackageOperation operation :
            manifest.getOperations()) {

            Path target =
                GAME_DIRECTORY
                    .resolve(operation.getPath())
                    .normalize();

            if (!target.startsWith(
                GAME_DIRECTORY
            )) {

                throw new KPakException(
                    "Unsafe install path"
                );
            }

            Files.createDirectories(
                target.getParent()
            );

            var entry =
                zip.getEntry(
                    operation.getPath()
                );

            if (entry == null) {

                throw new KPakException(
                    "Missing file "
                        + operation.getPath()
                );
            }

            Path temp =
                target.resolveSibling(
                    target.getFileName()
                        + ".tmp"
                );

            try (InputStream input =
                     zip.getInputStream(entry)) {

                Files.copy(
                    input,
                    temp,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }

            Files.move(
                temp,
                target,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }


    private static void verifyInstalled(
        PackageManifest manifest
    ) throws Exception {

        for (PackageOperation operation :
            manifest.getOperations()) {

            Path file =
                GAME_DIRECTORY
                    .resolve(operation.getPath());

            try (InputStream input =
                     Files.newInputStream(file)) {

                String hash =
                    KPakHashUtil.sha256(
                        input
                    );

                if (!hash.equalsIgnoreCase(
                    operation.getSha256()
                )) {

                    throw new KPakException(
                        "Installed hash mismatch "
                            + operation.getPath()
                    );
                }
            }
        }
    }


    private static void register(
        PackageManifest manifest
    ) throws Exception {

        KPakRegistry.register(
            new KPakRegistryEntry(
                manifest.getPackageIdentity(),
                manifest.getPackageVersion(),
                manifest.getAuthorId(),
                Instant.now().toString(),
                ".koil/backups/"
                    + manifest.getPackageIdentity(),
                manifest.getOperations()
                    .stream()
                    .map(PackageOperation::getPath)
                    .toList()
            )
        );
    }
}
