package com.spirit;

import com.spirit.koil.api.kpak.security.KPakPrivateKeyStore;
import com.spirit.koil.api.bootstrap.DedicatedServerBootstrapService;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class Prelaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER
                && !DedicatedServerBootstrapService.termsAccepted()) {
            System.out.println("Koil dedicated-server package key generation deferred until console terms acceptance.");
            return;
        }
        try {
            KPakPrivateKeyStore.generate();
            System.out.println("Pre-Launched");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
