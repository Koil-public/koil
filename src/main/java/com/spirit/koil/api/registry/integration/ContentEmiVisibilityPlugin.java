package com.spirit.koil.api.registry.integration;

import com.spirit.koil.api.registry.ContentActivationEvents;
import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicBoolean;

/** Removes inactive world-scoped Content holders whenever EMI rebuilds its item index. */
@EmiEntrypoint
public final class ContentEmiVisibilityPlugin implements EmiPlugin {
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean();

    @Override
    public void register(EmiRegistry registry) {
        ContentEmiFavoriteView.install();
        registry.removeEmiStacks(stack -> {
            var itemStack = stack.getItemStack();
            return !itemStack.isEmpty() && !ContentVisibilityPolicy.shouldExpose(itemStack.getItem());
        });
        if (LISTENER_REGISTERED.compareAndSet(false, true)) {
            ContentActivationEvents.register(snapshot -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> {
                    ContentEmiFavoriteView.invalidate();
                    EmiScreenManager.repopulatePanels(SidebarType.FAVORITES);
                });
            });
        }
    }
}
