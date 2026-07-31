package com.spirit;

import com.spirit.koil.api.kpak.security.KPakPrivateKeyStore;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class Prelaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        try {
            KPakPrivateKeyStore.generate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
