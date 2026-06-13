package com.spirit.client.gui.update.elements;

import com.spirit.Main;
import com.spirit.client.gui.main.KoilUpdateToast;
import com.spirit.koil.api.util.file.json.JSONFileEditor;

public final class UpdateState {
    private UpdateState() {
    }

    public static Status resolve() {
        boolean betaTesting = UpdateScreenData.isBetaTester();
        String branch = betaTesting ? UpdateScreenData.normalizeBranchKey(configString("betaBranch", "beta")) : "public";
        String localVersion = Main.currentVersion();
        String remoteVersion = Main.version();
        UpdateScreenData.UpdateData data = UpdateScreenData.readLocalData();
        boolean updateAvailable = UpdateScreenData.isRemoteNewer(localVersion, remoteVersion);
        UpdateScreenData.Release release = UpdateScreenData.releaseForVersion(data, branch, betaTesting, remoteVersion);
        if (release == null && updateAvailable) {
            release = UpdateScreenData.closestReleaseForVersion(data, branch, betaTesting, remoteVersion);
        }
        KoilUpdateToast.Type toastType = UpdateScreenData.toastTypeForRelease(data, release);
        String releaseName = release == null || release.name == null || release.name.isBlank() ? "Koil Update" : release.name;
        return new Status(betaTesting, branch, localVersion, remoteVersion, updateAvailable, data, release, toastType, releaseName);
    }

    private static String configString(String key, String fallback) {
        try {
            var element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", key);
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString();
                return value == null || value.isBlank() ? fallback : value;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public record Status(
            boolean betaTesting,
            String branch,
            String localVersion,
            String remoteVersion,
            boolean updateAvailable,
            UpdateScreenData.UpdateData data,
            UpdateScreenData.Release release,
            KoilUpdateToast.Type toastType,
            String releaseName
    ) {
    }
}
