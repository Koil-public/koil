package com.spirit.koil.api.screen;

import com.spirit.koil.api.util.console.log.KoilLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.Locale;

/**
 * Canonical presentation entry point for registry-backed Koil/Minecraft screens.
 *
 * <p>Popup presentation owns the real Screen immediately. Detached presentation
 * intentionally falls back to that same real Screen until a render/input
 * transport is registered; it never substitutes a non-functional Swing copy.</p>
 */
public final class ExternalUiWindow {
    private ExternalUiWindow() {
    }

    public static OpenResult open(
            MinecraftClient client,
            Screen parent,
            String screenId,
            String data,
            Presentation requested
    ) {
        if (client == null) return new OpenResult(false, requested, Presentation.POPUP, "Minecraft client unavailable");
        Screen screen = KoilRemoteScreenRegistry.create(client, parent, screenId, data);
        if (screen == null) return new OpenResult(false, requested, Presentation.POPUP, "Unknown registered screen: " + screenId);
        Presentation safe = requested == null ? Presentation.POPUP : requested;
        if (safe == Presentation.EXTERNAL) {
            // An arbitrary Minecraft Screen cannot be moved to another process:
            // it owns live client/render/input state. Preserve full behavior by
            // using the registered in-process popup until a transport adapter is
            // available for that exact screen.
            client.setScreen(screen);
            String detail = "External transport is not available for " + screenId + "; opened the real screen as a popup";
            KoilLog.warning(KoilLog.EXTERNAL_WINDOW_THREAD, "screen.fallback", detail);
            return new OpenResult(true, safe, Presentation.POPUP, detail);
        }
        client.setScreen(screen);
        return new OpenResult(true, safe, Presentation.POPUP, "Opened " + screenId);
    }

    public enum Presentation {
        POPUP,
        EXTERNAL;

        public static Presentation from(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return normalized.equals("external") || normalized.equals("window") || normalized.equals("popout")
                    ? EXTERNAL : POPUP;
        }
    }

    public record OpenResult(boolean opened, Presentation requested, Presentation actual, String detail) {
    }
}
