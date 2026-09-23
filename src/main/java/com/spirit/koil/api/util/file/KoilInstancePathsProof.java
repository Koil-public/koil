package com.spirit.koil.api.util.file;

import java.nio.file.Path;

public final class KoilInstancePathsProof {
    private KoilInstancePathsProof() {
    }

    public static void main(String[] args) {
        Path instance = Path.of("/tmp/koil-instance");
        String previous = System.getProperty("koil.instanceRoot");
        System.setProperty("koil.instanceRoot", instance.toString());
        try {
            require(KoilInstancePaths.instanceRoot().equals(instance), "configured instance root was not used");
            require(KoilInstancePaths.automationRoot(instance).equals(instance.resolve("koil/sys/automation")),
                    "automation root must be instance-owned sys storage");
            require(KoilInstancePaths.modelRoot(instance).equals(instance.resolve("koil/sys/model")),
                    "model root must be instance-owned sys storage");
        } finally {
            if (previous == null) {
                System.clearProperty("koil.instanceRoot");
            } else {
                System.setProperty("koil.instanceRoot", previous);
            }
        }
        System.out.println("Koil instance paths proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
