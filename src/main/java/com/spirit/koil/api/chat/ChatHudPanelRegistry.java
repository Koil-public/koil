package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.latex.RichChatLatexTextureCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Registry and layout engine for chat-width-responsive top and bottom panels. */
public final class ChatHudPanelRegistry {
    private static final int VANILLA_CHAT_BOTTOM = 40;
    private static final int OPEN_CHAT_CONTROL_CLEARANCE = 35;
    private static final int CLOSED_CHAT_CLEARANCE = 8;
    private static final int MIN_PANEL_WIDTH = 190;
    private static final int CHAT_WIDTH_EXTENSION = 12;
    private static final Map<String, ChatHudPanel> PANELS = new ConcurrentHashMap<>();
    private static volatile int observedTopmostMessageY = Integer.MAX_VALUE;
    private static volatile int currentChatReservedHeight;

    private ChatHudPanelRegistry() {
    }

    public static void register(ChatHudPanel panel) {
        Objects.requireNonNull(panel, "panel");
        String id = Objects.requireNonNull(panel.id(), "panel.id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Chat HUD panel id cannot be blank.");
        }
        PANELS.put(id, panel);
    }

    public static void registerIfAbsent(ChatHudPanel panel) {
        Objects.requireNonNull(panel, "panel");
        String id = Objects.requireNonNull(panel.id(), "panel.id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Chat HUD panel id cannot be blank.");
        }
        PANELS.putIfAbsent(id, panel);
    }

    public static boolean unregister(String id) {
        return id != null && PANELS.remove(id) != null;
    }

    public static List<ChatHudPanel> panels() {
        return List.copyOf(PANELS.values());
    }

    public static int panelWidth(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return MIN_PANEL_WIDTH;
        }
        int chatWidth = client.inGameHud == null || client.inGameHud.getChatHud() == null
                ? 0
                : client.inGameHud.getChatHud().getWidth();
        return Math.min(client.getWindow().getScaledWidth(), Math.max(MIN_PANEL_WIDTH, chatWidth + CHAT_WIDTH_EXTENSION));
    }

    /**
     * Returns the shared panel width converted into ChatHud's local render
     * coordinates. Inline chat content is drawn behind the chat-scale matrix,
     * while registered panels are drawn directly in screen coordinates.
     */
    public static int localPanelWidth(MinecraftClient client) {
        double scale = client == null || client.inGameHud == null || client.inGameHud.getChatHud() == null
                ? 1.0D
                : Math.max(0.1D, client.inGameHud.getChatHud().getChatScale());
        return Math.max(1, (int) Math.floor(panelWidth(client) / scale));
    }

    public static ChatHudPanelContext context(MinecraftClient client) {
        int screenWidth = client == null || client.getWindow() == null ? 320 : client.getWindow().getScaledWidth();
        int screenHeight = client == null || client.getWindow() == null ? 240 : client.getWindow().getScaledHeight();
        int chatWidth = client == null || client.inGameHud == null || client.inGameHud.getChatHud() == null
                ? 0
                : client.inGameHud.getChatHud().getWidth();
        return new ChatHudPanelContext(
                client,
                screenWidth,
                screenHeight,
                chatWidth,
                panelWidth(client),
                client != null && client.currentScreen instanceof ChatScreen
        );
    }

    public static int reservedBottomHeight(MinecraftClient client) {
        ChatHudPanelContext context = context(client);
        int panelHeight = totalHeight(context, ChatHudPanelPlacement.BOTTOM);
        return panelHeight <= 0 ? 0 : Math.max(0, controlClearance(context) + panelHeight - VANILLA_CHAT_BOTTOM);
    }

    public static void beginChatFrame(int reservedHeight) {
        observedTopmostMessageY = Integer.MAX_VALUE;
        currentChatReservedHeight = Math.max(0, reservedHeight);
    }

    /** Records a line's final screen Y so closed-chat top panels follow real visible content. */
    public static void observeChatLine(DrawContext drawContext, int localY) {
        if (drawContext == null) {
            return;
        }
        Matrix4f matrix = drawContext.getMatrices().peek().getPositionMatrix();
        Vector4f point = new Vector4f(0.0F, localY, 0.0F, 1.0F);
        point.mul(matrix);
        observedTopmostMessageY = Math.min(observedTopmostMessageY, Math.round(point.y()));
    }

    public static void render(DrawContext drawContext, MinecraftClient client) {
        if (drawContext == null || client == null || client.getWindow() == null) {
            return;
        }
        ChatHudPanelContext context = context(client);
        renderBottom(drawContext, context);
        renderTop(drawContext, context);
    }

    public static boolean mouseClicked(MinecraftClient client, double mouseX, double mouseY, int button) {
        if (client == null || client.getWindow() == null || button != 0) {
            return false;
        }
        ChatHudPanelContext context = context(client);
        List<PlacedPanel> placed = layout(context);
        for (int i = placed.size() - 1; i >= 0; i--) {
            PlacedPanel entry = placed.get(i);
            if (entry.bounds().contains(mouseX, mouseY)) {
                try {
                    if (entry.panel().mouseClicked(context, entry.bounds(), mouseX, mouseY, button)) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // Keep other registered panels interactive.
                }
            }
        }
        return false;
    }

    private static void renderBottom(DrawContext drawContext, ChatHudPanelContext context) {
        for (PlacedPanel entry : layout(context, ChatHudPanelPlacement.BOTTOM)) {
            renderPanel(drawContext, context, entry);
        }
    }

    private static void renderTop(DrawContext drawContext, ChatHudPanelContext context) {
        for (PlacedPanel entry : layout(context, ChatHudPanelPlacement.TOP)) {
            renderPanel(drawContext, context, entry);
        }
    }

    private static void renderPanel(DrawContext drawContext, ChatHudPanelContext context, PlacedPanel entry) {
        drawContext.getMatrices().push();
        try {
            entry.panel().render(drawContext, context, entry.bounds());
        } catch (RuntimeException ignored) {
            // One extension panel must not break Minecraft's complete HUD pass.
        } finally {
            drawContext.getMatrices().pop();
        }
    }

    private static List<PlacedPanel> layout(ChatHudPanelContext context) {
        List<PlacedPanel> placed = new ArrayList<>();
        placed.addAll(layout(context, ChatHudPanelPlacement.BOTTOM));
        placed.addAll(layout(context, ChatHudPanelPlacement.TOP));
        return placed;
    }

    private static List<PlacedPanel> layout(ChatHudPanelContext context, ChatHudPanelPlacement placement) {
        List<PlacedPanel> placed = new ArrayList<>();
        List<ChatHudPanel> panels = activePanels(context, placement);
        int edge = placement == ChatHudPanelPlacement.BOTTOM
                ? context.screenHeight() - controlClearance(context) - MultilineChatInputLayout.reservedHeight(context.client())
                : topAnchor(context);
        for (ChatHudPanel panel : panels) {
            int height = safeHeight(panel, context);
            int width = safeWidth(panel, context);
            if (height <= 0 || width <= 0) {
                continue;
            }
            edge -= height;
            ChatHudPanelBounds bounds = new ChatHudPanelBounds(0, edge, width, height);
            placed.add(new PlacedPanel(panel, bounds));
        }
        return placed;
    }

    private static int topAnchor(ChatHudPanelContext context) {
        if (!context.chatOpen() && observedTopmostMessageY != Integer.MAX_VALUE) {
            return observedTopmostMessageY;
        }
        MinecraftClient client = context.client();
        double scale = client == null || client.inGameHud == null || client.inGameHud.getChatHud() == null
                ? 1.0D
                : Math.max(0.1D, client.inGameHud.getChatHud().getChatScale());
        int viewportTop = Math.round((float) (RichChatLatexTextureCache.currentChatViewportTop() * scale));
        return viewportTop - currentChatReservedHeight;
    }

    private static int totalHeight(ChatHudPanelContext context, ChatHudPanelPlacement placement) {
        int height = 0;
        for (ChatHudPanel panel : activePanels(context, placement)) {
            height += safeHeight(panel, context);
        }
        return height;
    }

    private static int safeHeight(ChatHudPanel panel, ChatHudPanelContext context) {
        try {
            return Math.max(0, panel.height(context));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int safeWidth(ChatHudPanel panel, ChatHudPanelContext context) {
        try {
            return Math.max(1, Math.min(context.screenWidth(), panel.width(context)));
        } catch (RuntimeException ignored) {
            return context.panelWidth();
        }
    }

    private static List<ChatHudPanel> activePanels(ChatHudPanelContext context, ChatHudPanelPlacement placement) {
        List<ChatHudPanel> active = new ArrayList<>();
        for (ChatHudPanel panel : PANELS.values()) {
            try {
                if (panel.placement() == placement && panel.visible(context)) {
                    active.add(panel);
                }
            } catch (RuntimeException ignored) {
                // Ignore a broken extension for this frame.
            }
        }
        active.sort(Comparator.comparingInt(ChatHudPanel::order).thenComparing(ChatHudPanel::id));
        return active;
    }

    private static int controlClearance(ChatHudPanelContext context) {
        return context.chatOpen() ? OPEN_CHAT_CONTROL_CLEARANCE : CLOSED_CHAT_CLEARANCE;
    }

    private record PlacedPanel(ChatHudPanel panel, ChatHudPanelBounds bounds) {
    }
}
