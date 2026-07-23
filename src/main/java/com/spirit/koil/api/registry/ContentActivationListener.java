package com.spirit.koil.api.registry;

/** Public activation lifecycle hook for integrations that maintain world-scoped indexes. */
@FunctionalInterface
public interface ContentActivationListener {
    void onContentActivationChanged(WorldContentIndex.ActiveWorldSnapshot snapshot);
}
