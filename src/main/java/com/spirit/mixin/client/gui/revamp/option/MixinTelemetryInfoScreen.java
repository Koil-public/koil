package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.TelemetryEventWidget;
import net.minecraft.client.gui.screen.option.TelemetryInfoScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.util.telemetry.TelemetryEventProperty;
import net.minecraft.client.util.telemetry.TelemetryEventType;
import net.minecraft.text.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.lang.reflect.*;
import java.util.*;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(TelemetryInfoScreen.class)
public class MixinTelemetryInfoScreen extends Screen {
    @Shadow @Final private static Text DESCRIPTION_TEXT;

    private static final int DESCRIPTION_DASH_COLOR = 0xFF3C434D;
    private static final int DESCRIPTION_SEPARATOR_COLOR = 0xFF59626E;
    private static final int DESCRIPTION_HEADER_COLOR = 0xFF9DADBF;
    private static final int DESCRIPTION_IDENTIFIER_COLOR = 0xFFD7E1EC;
    private static final int DESCRIPTION_ID_VALUE_COLOR = 0xFF8FD5D0;
    private static final int DESCRIPTION_VERSION_VALUE_COLOR = 0xFF94C7FF;
    private static final int DESCRIPTION_SYSTEM_VALUE_COLOR = 0xFFAEC6E8;
    private static final int DESCRIPTION_NUMBER_VALUE_COLOR = 0xFFE8C86F;
    private static final int DESCRIPTION_RUNTIME_VALUE_COLOR = 0xFFB7A8FF;
    private static final int DESCRIPTION_SUCCESS_VALUE_COLOR = 0xFFA6D989;
    private static final int DESCRIPTION_WARNING_VALUE_COLOR = 0xFFE6A15C;
    private static final int DESCRIPTION_DEFAULT_VALUE_COLOR = 0xFFC8D6E4;

    private final Map<ClickableWidget, WidgetPosition> originalWidgetPositions = new IdentityHashMap<>();
    private int telemetryScrollOffset;
    private int telemetryScrollMax;
    private int telemetryViewportX = -1;
    private int telemetryViewportY = -1;
    private int telemetryViewportWidth;
    private int telemetryViewportHeight;
    private boolean telemetryScrollbarDragging;
    private int telemetryScrollbarDragOffset;
    private int telemetryFooterTop = -1;

    protected MixinTelemetryInfoScreen(Text title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (!isKoilRedesignEnabled()) {
            restoreVanillaWidgetPositions();
            this.telemetryScrollbarDragging = false;
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        positionRedesignButtons();
        KoilVanillaScreenChrome.renderOptionsShell(context, client, this.width, this.height);
        KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Options"), this.title);
        renderTelemetryPanel(context, client);
        renderVanillaRedesignChildren(context, mouseX, mouseY, delta);
        info.cancel();
    }

    private boolean isKoilRedesignEnabled() {
        try {
            return JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void positionRedesignButtons() {
        List<ClickableWidget> widgets = new ArrayList<>();

        for (Element child : this.children()) {
            if (!(child instanceof ClickableWidget widget) || child instanceof TelemetryEventWidget || !widget.visible) {
                continue;
            }

            this.originalWidgetPositions.putIfAbsent(widget, new WidgetPosition(widget.getX(), widget.getY()));
            widgets.add(widget);
        }

        if (widgets.isEmpty()) {
            this.telemetryFooterTop = this.height - 8;
            return;
        }

        int gap = 8;
        int maxRowWidth = Math.max(120, this.width - 104);
        List<List<ClickableWidget>> rows = new ArrayList<>();
        List<ClickableWidget> currentRow = new ArrayList<>();
        int currentWidth = 0;

        for (ClickableWidget widget : widgets) {
            int widgetWidth = widget.getWidth();
            int nextWidth = currentRow.isEmpty() ? widgetWidth : currentWidth + gap + widgetWidth;

            if (!currentRow.isEmpty() && nextWidth > maxRowWidth) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentWidth = 0;
            }

            currentRow.add(widget);
            currentWidth = currentWidth == 0 ? widgetWidth : currentWidth + gap + widgetWidth;
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        int rowHeight = 20;

        for (ClickableWidget widget : widgets) {
            rowHeight = Math.max(rowHeight, widget.getHeight());
        }

        int totalHeight = rows.size() * rowHeight + Math.max(0, rows.size() - 1) * gap;
        int startY = Math.max(8, this.height - 6 - totalHeight);
        this.telemetryFooterTop = Math.max(8, startY - 8);

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<ClickableWidget> row = rows.get(rowIndex);
            int rowWidth = -gap;

            for (ClickableWidget widget : row) {
                rowWidth += widget.getWidth() + gap;
            }

            int x = Math.max(8, (this.width - rowWidth) / 2);
            int y = startY + rowIndex * (rowHeight + gap);

            for (ClickableWidget widget : row) {
                widget.setX(x);
                widget.setY(y);
                x += widget.getWidth() + gap;
            }
        }
    }

    private void restoreVanillaWidgetPositions() {
        for (Map.Entry<ClickableWidget, WidgetPosition> entry : this.originalWidgetPositions.entrySet()) {
            ClickableWidget widget = entry.getKey();
            WidgetPosition position = entry.getValue();
            widget.setX(position.x());
            widget.setY(position.y());
        }
    }

    private void renderVanillaRedesignChildren(DrawContext context, int mouseX, int mouseY, float delta) {
        for (Element child : this.children()) {
            if (child instanceof TelemetryEventWidget) {
                continue;
            }

            if (child instanceof Drawable drawable) {
                drawable.render(context, mouseX, mouseY, delta);
            }
        }
    }

    private void renderTelemetryPanel(DrawContext context, MinecraftClient client) {
        int x = 52;
        int y = 52;
        int width = this.width - 104;
        int footerTop = this.telemetryFooterTop <= 0 ? this.height - 38 : this.telemetryFooterTop;
        int bottom = Math.max(y + 140, footerTop - 4);
        int height = Math.max(140, bottom - y);

        context.fill(x, y, x + width, y + height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(x + 2, y + 2, x + 5, y + height - 2, new Color(uiColorContentStripeLeft, true).getRGB());

        int contentX = x + 12;
        int contentWidth = width - 24;
        int headerBottom = renderTelemetryHeader(context, contentX, y + 10, contentWidth);

        this.telemetryViewportX = contentX;
        this.telemetryViewportY = headerBottom + 8;
        this.telemetryViewportWidth = contentWidth;
        this.telemetryViewportHeight = Math.max(44, y + height - 12 - this.telemetryViewportY);

        context.enableScissor(this.telemetryViewportX, this.telemetryViewportY, this.telemetryViewportX + this.telemetryViewportWidth, this.telemetryViewportY + this.telemetryViewportHeight);

        int rowY = this.telemetryViewportY - this.telemetryScrollOffset;
        int contentHeight = 0;

        for (TelemetryRow row : telemetryRows(client)) {
            int nextY = drawTelemetryRow(context, row, this.telemetryViewportX + 2, rowY, this.telemetryViewportWidth - 12) + 8;
            contentHeight += nextY - rowY;
            rowY = nextY;
        }

        context.disableScissor();

        this.telemetryScrollMax = Math.max(0, contentHeight - this.telemetryViewportHeight);

        if (this.telemetryScrollOffset > this.telemetryScrollMax) {
            this.telemetryScrollOffset = this.telemetryScrollMax;
        }

        renderTelemetryScrollbar(context);
    }

    private int renderTelemetryHeader(DrawContext context, int x, int y, int width) {
        Text titleText = this.title == null || this.title.getString().isBlank() ? Text.literal("Telemetry Data Collection") : this.title;
        List<OrderedText> descriptionLines = this.textRenderer.wrapLines(DESCRIPTION_TEXT, Math.max(80, width - 8));

        context.drawText(this.textRenderer, titleText, x + 2, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);

        int lineY = y + 14;

        for (OrderedText line : descriptionLines) {
            context.drawText(this.textRenderer, line, x + 2, lineY, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            lineY += 10;
        }

        return lineY;
    }

    private int drawTelemetryRow(DrawContext context, TelemetryRow row, int x, int y, int width) {
        int reasonWidth = Math.max(80, width - 18);
        List<OrderedText> reason = wrapDescriptionLines(row.reason(), reasonWidth);
        int height = Math.max(34, 22 + reason.size() * 10);

        context.fill(x, y, x + width, y + height, 0x4A000000);
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, row.label(), x + 8, y + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, row.value(), x + 128, y + 6, row.valueColor(), false);

        int lineY = y + 18;

        for (OrderedText line : reason) {
            context.drawText(this.textRenderer, line, x + 8, lineY, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            lineY += 10;
        }

        return y + height;
    }

    private List<OrderedText> wrapDescriptionLines(String value, int width) {
        List<OrderedText> lines = new ArrayList<>();
        String safeValue = value == null ? "" : value;
        String[] literalLines = safeValue.split("\\R", -1);

        for (String literalLine : literalLines) {
            if (literalLine.isBlank()) {
                continue;
            }

            lines.addAll(this.textRenderer.wrapLines(descriptionTextForLine(literalLine), width));
        }

        return lines;
    }

    private MutableText descriptionTextForLine(String value) {
        if ("Fields:".equals(value)) {
            return coloredText(value, DESCRIPTION_HEADER_COLOR);
        }

        if (value.startsWith("- ")) {
            return fieldDescriptionText(value.substring(2));
        }

        return coloredText(value, new Color(uiColorContentBaseDescriptionText, true).getRGB());
    }

    private MutableText fieldDescriptionText(String value) {
        int separator = value.indexOf(':');

        if (separator < 0) {
            return Text.empty()
                    .append(coloredText("-", DESCRIPTION_DASH_COLOR))
                    .append(coloredText(" ", DESCRIPTION_SEPARATOR_COLOR))
                    .append(coloredText(value, DESCRIPTION_IDENTIFIER_COLOR));
        }

        String identifier = value.substring(0, separator).trim();
        String fieldValue = value.substring(separator + 1).trim();

        return Text.empty()
                .append(coloredText("-", DESCRIPTION_DASH_COLOR))
                .append(coloredText(" ", DESCRIPTION_SEPARATOR_COLOR))
                .append(coloredText(identifier, DESCRIPTION_IDENTIFIER_COLOR))
                .append(coloredText(": ", DESCRIPTION_SEPARATOR_COLOR))
                .append(coloredText(fieldValue, descriptionValueColor(identifier, fieldValue)));
    }

    private MutableText coloredText(String value, int color) {
        return Text.literal(value).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color & 0xFFFFFF)));
    }

    private int descriptionValueColor(String identifier, String value) {
        String key = (identifier == null ? "" : identifier).toLowerCase(Locale.ROOT);
        String lowerValue = (value == null ? "" : value).toLowerCase(Locale.ROOT);

        if (lowerValue.contains("unknown") || lowerValue.contains("unavailable") || lowerValue.contains("not currently exposed")) {
            return DESCRIPTION_WARNING_VALUE_COLOR;
        }

        if (lowerValue.equals("true") || lowerValue.equals("yes") || lowerValue.equals("active") || lowerValue.equals("enabled")) {
            return DESCRIPTION_SUCCESS_VALUE_COLOR;
        }

        if (lowerValue.equals("false") || lowerValue.equals("no") || lowerValue.equals("disabled")) {
            return DESCRIPTION_WARNING_VALUE_COLOR;
        }

        if (lowerValue.contains("generated when") || lowerValue.contains("sampled by") || lowerValue.contains("runtime")) {
            return DESCRIPTION_RUNTIME_VALUE_COLOR;
        }

        if (key.contains("id") || key.contains("identifier") || lowerValue.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*")) {
            return DESCRIPTION_ID_VALUE_COLOR;
        }

        if (key.contains("version") || key.contains("java") || key.contains("game version")) {
            return DESCRIPTION_VERSION_VALUE_COLOR;
        }

        if (key.contains("memory") || key.contains("distance") || key.contains("samples") || key.contains("time") || lowerValue.matches(".*\\d+.*")) {
            return DESCRIPTION_NUMBER_VALUE_COLOR;
        }

        if (key.contains("system") || key.contains("operating") || key.contains("platform") || key.contains("arch") || key.contains("client")) {
            return DESCRIPTION_SYSTEM_VALUE_COLOR;
        }

        if (key.contains("server") || key.contains("world") || key.contains("mode") || key.contains("session")) {
            return DESCRIPTION_WARNING_VALUE_COLOR;
        }

        return DESCRIPTION_DEFAULT_VALUE_COLOR;
    }

    private List<TelemetryRow> telemetryRows(MinecraftClient client) {
        List<TelemetryRow> rows = new ArrayList<>();
        String username = client == null || client.getSession() == null ? "unknown" : client.getSession().getUsername();
        String uuid = client == null || client.getSession() == null ? "unknown" : client.getSession().getUuid();
        String world = client == null || client.world == null ? "menu" : client.isInSingleplayer() ? "singleplayer" : "multiplayer server";

        rows.add(new TelemetryRow("Minecraft summary", "active", 0xFF8FC5FF, nonBlank(DESCRIPTION_TEXT.getString(), "Minecraft can collect required telemetry for diagnostics and optional telemetry if the player enables it. The rows below expand that into the values this client can see and the fields each vanilla telemetry event can include.")));
        rows.add(new TelemetryRow("User ID", uuid, 0xFF7FC8C2, "Account identifier used to attach telemetry to the signed-in Minecraft session. This is not chat text or a display name; it is the account/session id visible to the client."));
        rows.add(new TelemetryRow("Username", username, 0xFFE6EDF5, "Display name for proof of the currently active local session. Mojang telemetry generally uses stable ids instead of relying on a changeable display name."));
        rows.add(new TelemetryRow("Telemetry opt-in", telemetryOptionValue(client), 0xFFE3B735, "Shows whether optional telemetry is enabled. Optional events are only intended to send when this value allows them; required events may still be sent for diagnostics and service operation."));
        rows.add(new TelemetryRow("Game version", SharedConstants.getGameVersion().getName(), 0xFF0085A4, "Version data helps Mojang understand which Minecraft build produced a report or performance sample."));
        rows.add(new TelemetryRow("Session context", world, 0xFFE6862C, "World/server context explains whether telemetry comes from menus, local worlds, or multiplayer play."));
        rows.add(new TelemetryRow("Language", Locale.getDefault().toLanguageTag(), 0xFFE6EDF5, "Locale helps diagnose language, region, and text issues without exposing chat or private messages."));
        rows.add(new TelemetryRow("Java runtime", System.getProperty("java.version", "unknown"), 0xFF8FC5FF, "Runtime details help explain crashes, startup failures, and client compatibility issues."));
        rows.add(new TelemetryRow("Operating system", System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", ""), 0xFF8FC5FF, "OS and architecture are needed for platform-specific crash and driver diagnosis."));
        rows.add(new TelemetryRow("Client modded", FabricLoader.getInstance().isModLoaded("fabricloader") ? "yes" : "no", 0xFFE6862C, "Minecraft's telemetry model can include whether the client is modded. The system reports this locally as yes because Fabric Loader is present in this instance."));        rows.addAll(vanillaTelemetryEventRows());

        return rows;
    }

    private List<TelemetryRow> vanillaTelemetryEventRows() {
        List<TelemetryRow> rows = new ArrayList<>();

        List<TelemetryEventType> events = new ArrayList<>(TelemetryEventType.getTypes());
        events.sort(Comparator.comparing(TelemetryEventType::isOptional)
                .thenComparing(TelemetryEventType::getId, String.CASE_INSENSITIVE_ORDER));
        for (TelemetryEventType event : events) {
            try {
                String title = event.getTitle().getString();
                String description = event.getDescription().getString();
                String value = event.isOptional() ? "optional" : "required";
                String eventName = nonBlank(title, formatTelemetryFieldName(event.getId()));

                rows.add(new TelemetryRow(eventName, value, event.isOptional() ? 0xFFE3B735 : 0xFF7FC8C2, eventReason(event, description)));
            } catch (RuntimeException exception) {
                rows.add(new TelemetryRow(
                        formatTelemetryFieldName(event.getId()),
                        event.isOptional() ? "optional" : "required",
                        0xFFE06A21,
                        "This vanilla telemetry event is registered, but part of its localized detail could not be read."
                ));
            }
        }

        if (rows.isEmpty()) {
            rows.add(new TelemetryRow("Telemetry events", "unavailable", 0xFFE06A21, "Minecraft did not expose any registered telemetry events in this runtime."));
        }

        return rows;
    }

    private String eventReason(TelemetryEventType event, String description) {
        StringBuilder builder = new StringBuilder();
        builder.append(nonBlank(description, "Vanilla telemetry event registered by Minecraft."));

        List<TelemetryEventProperty<?>> properties = event.getProperties();

        if (!properties.isEmpty()) {
            builder.append('\n').append("Fields:");

            for (TelemetryEventProperty<?> property : properties) {
                builder.append('\n').append("- ").append(property.getTitle().getString()).append(": ").append(livePropertyValue(property));
            }
        }

        return builder.toString();
    }

    private String livePropertyValue(TelemetryEventProperty<?> property) {
        String id = property.id();
        MinecraftClient client = MinecraftClient.getInstance();

        if ("user_id".equals(id)) {
            return client == null || client.getSession() == null ? "unknown" : client.getSession().getUuid();
        }

        if ("game_version".equals(id)) {
            return SharedConstants.getGameVersion().getName();
        }

        if ("operating_system".equals(id)) {
            return System.getProperty("os.name", "unknown");
        }

        if ("platform".equals(id)) {
            return System.getProperty("os.arch", "unknown");
        }

        if ("client_modded".equals(id)) {
            return FabricLoader.getInstance().isModLoaded("fabricloader") ? "true" : "false";
        }

        if ("server_type".equals(id)) {
            return client == null || client.world == null ? "menu" : client.isInSingleplayer() ? "singleplayer" : "multiplayer";
        }

        if ("opt_in".equals(id)) {
            return telemetryOptionValue(client);
        }

        if ("event_timestamp_utc".equals(id)) {
            return "generated when event is sent";
        }

        if ("render_distance".equals(id) && client != null) {
            return String.valueOf(client.options.getViewDistance().getValue());
        }

        if ("dedicated_memory_kb".equals(id)) {
            return Runtime.getRuntime().maxMemory() / 1024L + " KB max heap";
        }

        if ("game_mode".equals(id) && client != null && client.interactionManager != null) {
            return client.interactionManager.getCurrentGameMode().getName();
        }

        if ("number_of_samples".equals(id) || id.endsWith("_samples") || id.endsWith("_ms") || id.endsWith("_time_ms")) {
            return "sampled by Minecraft during runtime";
        }

        if ("advancement_id".equals(id)) {
            return "advancement identifier when earned";
        }

        if ("advancement_game_time".equals(id)) {
            return "world game time when advancement is earned";
        }

        return "not currently exposed as a stable live client value";
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String formatTelemetryFieldName(String value) {
        if (value == null || value.isBlank()) {
            return "Telemetry event";
        }

        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.isEmpty() ? value : builder.toString();
    }

    private String telemetryOptionValue(MinecraftClient client) {
        if (client == null || client.options == null) {
            return "unknown";
        }

        try {
            Method method = client.options.getClass().getMethod("getTelemetryOptInExtra");
            Object option = method.invoke(client.options);
            Method getValue = option.getClass().getMethod("getValue");
            return String.valueOf(getValue.invoke(option));
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private void renderTelemetryScrollbar(DrawContext context) {
        if (this.telemetryScrollMax <= 0 || this.telemetryViewportHeight <= 0) {
            return;
        }

        int trackX = telemetryScrollbarTrackX();
        int thumbHeight = telemetryScrollbarThumbHeight();
        int thumbY = telemetryScrollbarThumbY();

        context.fill(trackX, this.telemetryViewportY, trackX + 2, this.telemetryViewportY + this.telemetryViewportHeight, 0x2E67727F);
        context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, 0x9AA9B9CA);
    }

    private int telemetryScrollbarTrackX() {
        return this.telemetryViewportX + this.telemetryViewportWidth - 4;
    }



    private int telemetryScrollbarThumbHeight() {
        if (this.telemetryScrollMax <= 0 || this.telemetryViewportHeight <= 0) {
            return this.telemetryViewportHeight;
        }

        return Math.max(18, (int) ((this.telemetryViewportHeight / (float) (this.telemetryViewportHeight + this.telemetryScrollMax)) * this.telemetryViewportHeight));
    }

    private int telemetryScrollbarThumbY() {
        if (this.telemetryScrollMax <= 0 || this.telemetryViewportHeight <= 0) {
            return this.telemetryViewportY;
        }

        int thumbHeight = telemetryScrollbarThumbHeight();
        int thumbTravel = Math.max(1, this.telemetryViewportHeight - thumbHeight);

        return this.telemetryViewportY + (int) ((this.telemetryScrollOffset / (float) this.telemetryScrollMax) * thumbTravel);
    }

    private boolean isMouseOverTelemetryScrollbar(double mouseX, double mouseY) {
        if (this.telemetryScrollMax <= 0 || this.telemetryViewportHeight <= 0) {
            return false;
        }

        int trackX = telemetryScrollbarTrackX();

        return mouseX >= trackX - 3
                && mouseX <= trackX + 5
                && mouseY >= this.telemetryViewportY
                && mouseY <= this.telemetryViewportY + this.telemetryViewportHeight;
    }

    private boolean isMouseOverTelemetryScrollbarThumb(double mouseX, double mouseY) {
        if (!isMouseOverTelemetryScrollbar(mouseX, mouseY)) {
            return false;
        }

        int thumbY = telemetryScrollbarThumbY();
        int thumbHeight = telemetryScrollbarThumbHeight();

        return mouseY >= thumbY && mouseY <= thumbY + thumbHeight;
    }

    private void setTelemetryScrollFromThumbY(double thumbY) {
        if (this.telemetryScrollMax <= 0 || this.telemetryViewportHeight <= 0) {
            this.telemetryScrollOffset = 0;
            return;
        }

        int thumbHeight = telemetryScrollbarThumbHeight();
        int thumbTravel = Math.max(1, this.telemetryViewportHeight - thumbHeight);
        int clampedThumbY = Math.max(this.telemetryViewportY, Math.min(this.telemetryViewportY + thumbTravel, (int) thumbY));
        float progress = (clampedThumbY - this.telemetryViewportY) / (float) thumbTravel;

        this.telemetryScrollOffset = Math.max(0, Math.min(this.telemetryScrollMax, Math.round(progress * this.telemetryScrollMax)));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isKoilRedesignEnabled()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button == 0 && isMouseOverTelemetryScrollbar(mouseX, mouseY)) {
            int thumbY = telemetryScrollbarThumbY();

            if (isMouseOverTelemetryScrollbarThumb(mouseX, mouseY)) {
                this.telemetryScrollbarDragOffset = (int) mouseY - thumbY;
            } else {
                this.telemetryScrollbarDragOffset = telemetryScrollbarThumbHeight() / 2;
                setTelemetryScrollFromThumbY(mouseY - this.telemetryScrollbarDragOffset);
            }

            this.telemetryScrollbarDragging = true;
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!isKoilRedesignEnabled()) {
            this.telemetryScrollbarDragging = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        if (button == 0 && this.telemetryScrollbarDragging) {
            this.telemetryScrollbarDragging = false;
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!isKoilRedesignEnabled()) {
            this.telemetryScrollbarDragging = false;
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        if (button == 0 && this.telemetryScrollbarDragging) {
            setTelemetryScrollFromThumbY(mouseY - this.telemetryScrollbarDragOffset);
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isKoilRedesignEnabled()) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }

        if (mouseX >= this.telemetryViewportX && mouseX <= this.telemetryViewportX + this.telemetryViewportWidth
                && mouseY >= this.telemetryViewportY && mouseY <= this.telemetryViewportY + this.telemetryViewportHeight
                && this.telemetryScrollMax > 0) {
            this.telemetryScrollOffset = Math.max(0, Math.min(this.telemetryScrollMax, this.telemetryScrollOffset - (int) amount * 18));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private record WidgetPosition(int x, int y) {
    }

    private record TelemetryRow(String label, String value, int valueColor, String reason) {
    }
}
