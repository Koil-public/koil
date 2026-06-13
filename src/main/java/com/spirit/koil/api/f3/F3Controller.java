package com.spirit.koil.api.f3;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public final class F3Controller {
    private static boolean vanillaFallback;

    private F3Controller() {
    }

    public static boolean vanillaFallback() {
        return vanillaFallback;
    }

    public static void toggleVanillaFallback() {
        vanillaFallback = !vanillaFallback;
        send("Vanilla F3 fallback " + (vanillaFallback ? "enabled" : "disabled"));
    }

    public static void toggleOverlay() {
        F3LayoutState.toggleOverlayVisible();
    }

    public static void setOverlayMode(F3Mode mode) {
        if (mode != null) {
            F3LayoutState.mode(mode);
        }
        F3LayoutState.compactOverlay(false);
        F3LayoutState.resetOverlayScroll();
        F3LayoutState.overlayVisible(true);
    }

    public static void cycleMode() {
        F3Mode[] cycle = {
                F3Mode.SIMPLE,
                F3Mode.NORMAL,
                F3Mode.PLAYER,
                F3Mode.WORLD,
                F3Mode.TARGET,
                F3Mode.PERFORMANCE,
                F3Mode.CREATOR,
                F3Mode.GRAPHS,
                F3Mode.INSPECTOR,
                F3Mode.FULL,
                F3Mode.COMPACT
        };
        F3Mode current = F3LayoutState.mode();
        int next = 0;
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == current) {
                next = (i + 1) % cycle.length;
                break;
            }
        }
        F3LayoutState.mode(cycle[next]);
        F3LayoutState.resetOverlayScroll();
        F3LayoutState.overlayVisible(true);
        send("Overlay mode: " + cycle[next].label());
    }

    public static boolean handleOverlayMouseScroll(double verticalAmount) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen != null || !F3LayoutState.overlayVisible() || F3LayoutState.mode() == F3Mode.COMPACT) {
            return false;
        }
        int amount = scrollAmount(verticalAmount);
        if (amount == 0) {
            return true;
        }
        scrollOverlay(amount);
        return true;
    }

    public static void scrollOverlay(int amount) {
        if (amount == 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getWindow() != null) {
            long handle = client.getWindow().getHandle();
            boolean leftArrow = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_REPEAT;
            boolean rightArrow = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_REPEAT;
            boolean leftMouse = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean rightMouse = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (leftArrow && !rightArrow) {
                F3LayoutState.scrollLeftSidePanels(amount);
                return;
            }
            if (rightArrow && !leftArrow) {
                F3LayoutState.scrollRightSidePanels(amount);
                return;
            }
            if (leftMouse && !rightMouse) {
                F3LayoutState.scrollLeftSidePanels(amount);
                return;
            }
            if (rightMouse && !leftMouse) {
                F3LayoutState.scrollRightSidePanels(amount);
                return;
            }
        }
        F3LayoutState.scrollOverlayLines(amount);
    }

    private static F3LayoutState.ScrollPanel hoveredPanel(MinecraftClient client) {
        if (client == null || client.mouse == null || client.getWindow() == null || client.getWindow().getWidth() <= 0 || client.getWindow().getHeight() <= 0) {
            return F3LayoutState.ScrollPanel.NONE;
        }
        double scaledX = client.mouse.getX() * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth();
        double scaledY = client.mouse.getY() * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight();
        return F3LayoutState.panelAt(scaledX, scaledY);
    }

    private static int scrollAmount(double verticalAmount) {
        if (verticalAmount == 0.0D) {
            return 0;
        }
        int amount = Math.max(1, Math.min(12, (int) Math.ceil(Math.abs(verticalAmount))));
        return verticalAmount < 0.0D ? amount : -amount;
    }

    public static void toggleDashboardFromF3() {
        toggleOverlay();
    }

    public static void copyTarget() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.keyboard == null) {
            return;
        }
        F3Snapshot snapshot = F3SnapshotService.latest(client);
        client.keyboard.setClipboard(F3ReportService.shortTargetReport(snapshot));
        send("Copied target debug info.");
    }

    public static void writeReport() {
        MinecraftClient client = MinecraftClient.getInstance();
        F3ReportService.writeSnapshotReport(client);
        send("F3 report written to " + F3Paths.SNAPSHOTS);
    }

    public static void send(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Debug]: ").formatted(Formatting.YELLOW, Formatting.BOLD).append(Text.literal(message).styled(style -> style.withColor(Formatting.WHITE).withBold(false).withItalic(false).withUnderline(false).withStrikethrough(false).withObfuscated(false))), false);
        }
    }
}
