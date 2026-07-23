package com.spirit.koil.api.registry;

import java.util.concurrent.CopyOnWriteArrayList;

import static com.spirit.Main.SUBLOGGER;

/** Reusable activation event surface for Koil systems and optional mod integrations. */
public final class ContentActivationEvents {
    private static final CopyOnWriteArrayList<ContentActivationListener> LISTENERS = new CopyOnWriteArrayList<>();

    private ContentActivationEvents() {
    }

    public static void register(ContentActivationListener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void unregister(ContentActivationListener listener) {
        LISTENERS.remove(listener);
    }

    static void publish(WorldContentIndex.ActiveWorldSnapshot snapshot) {
        for (ContentActivationListener listener : LISTENERS) {
            try {
                listener.onContentActivationChanged(snapshot);
            } catch (RuntimeException exception) {
                SUBLOGGER.logE(
                        "Content Registry",
                        "Content activation listener failed without blocking world activation: " + exception.getMessage()
                );
            }
        }
    }
}
