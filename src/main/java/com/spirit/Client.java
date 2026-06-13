package com.spirit;

import com.spirit.client.gui.UiSoundHelper;
import com.spirit.client.gui.pkg.PackageDetectionService;
import com.spirit.koil.api.automation.AutomationPresenceClientBridge;
import com.spirit.koil.api.automation.AutomationRemoteRunClientBridge;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.console.ConsoleRequestBridge;
import com.spirit.koil.api.f3.F3CommandBridge;
import com.spirit.koil.api.f3.F3SnapshotService;
import com.spirit.koil.api.performance.PerformanceCommandBridge;
import com.spirit.koil.api.performance.PerformanceMonitor;
import com.spirit.koil.api.performance.PerformanceOptimizationTestService;
import com.spirit.koil.api.stats.global.GlobalActivityClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class Client implements ClientModInitializer {
    public static final KeyBinding KOIL_UTIL_POPUP_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding("koil.keybind.app", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, KeyBinding.GAMEPLAY_CATEGORY));
    public static final KeyBinding KOIL_UTIL_NBT_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding("koil.keybind.nbt", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, KeyBinding.INVENTORY_CATEGORY));
    public static int KOIL_UTIL_POPUP_KEY_INT = InputUtil.fromTranslationKey(KOIL_UTIL_POPUP_KEY.getBoundKeyTranslationKey()).getCode();
    private static Boolean lastWindowFocused;

    @Override
    public void onInitializeClient() {
        ConsoleRequestBridge.initializeHost();
        GlobalActivityClient.registerClient();
        ClientTickEvents.START_CLIENT_TICK.register(client -> AutomationRouter.tick());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PackageDetectionService.tick(client);
            AutomationPresenceClientBridge.tick(client);
            PerformanceMonitor.tick(client);
            PerformanceOptimizationTestService.tick(client);
            F3SnapshotService.tick(client);
            boolean focused = client.isWindowFocused();
            if (lastWindowFocused != null && focused && !lastWindowFocused) {
                UiSoundHelper.playButtonClick();
            }
            lastWindowFocused = focused;
        });
        AutomationPresenceClientBridge.registerReceiver();
        AutomationRemoteRunClientBridge.registerReceiver();
        AutomationRouter.registerClientCommands();
        PerformanceCommandBridge.registerClientCommands();
        F3CommandBridge.registerClientCommands();
    }
}
