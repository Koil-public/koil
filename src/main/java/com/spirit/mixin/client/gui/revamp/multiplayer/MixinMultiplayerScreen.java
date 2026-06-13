package com.spirit.mixin.client.gui.revamp.multiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.client.gui.BrowserLayoutHelper;
import com.spirit.client.gui.browser.ContentRemoteIconResolver;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.multiplayer.KoilServerAddressMaskAccess;
import com.spirit.koil.api.util.file.image.ExternalImageLoader;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.DirectConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.*;
import net.minecraft.client.network.LanServerQueryManager;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.design.uiColorVal.*;

@Mixin(MultiplayerScreen.class)
public abstract class MixinMultiplayerScreen extends Screen implements KoilServerAddressMaskAccess {
    @Shadow protected MultiplayerServerListWidget serverListWidget;
    @Shadow private boolean initialized;
    @Shadow private ServerList serverList;
    @Shadow private LanServerQueryManager.LanServerEntryList lanServers;
    @Shadow @Nullable private LanServerQueryManager.LanServerDetector lanServerDetector;
    @Shadow @Final private static Logger LOGGER;
    @Shadow private ButtonWidget buttonJoin;
    private ButtonWidget toggleMaskButton;
    private ButtonWidget copyIPButton;
    private ButtonWidget openIPButton;
    private ButtonWidget inDevButton;
    private TextFieldWidget serverSearchBox;
    @Shadow public abstract void connect();
    @Shadow private ServerInfo selectedEntry;
    @Shadow protected abstract void directConnect(boolean confirmedAction);
    @Shadow protected abstract void addEntry(boolean confirmedAction);
    @Shadow protected abstract void editEntry(boolean confirmedAction);
    @Shadow private ButtonWidget buttonEdit;
    @Shadow private ButtonWidget buttonDelete;
    @Shadow protected abstract void removeEntry(boolean confirmedAction);
    @Shadow protected abstract void refresh();
    @Shadow @Final private Screen parent;
    @Shadow @Nullable private List<Text> multiplayerScreenTooltip;
    @Nullable private String selectedServerName;
    @Nullable private String selectedServerSetName;
    @Nullable private String selectedServerIP;
    @Nullable private String selectedServerVersion;
    private boolean isMasked = true;
    private int previewScrollOffset;
    private int previewScrollMax;
    private int previewViewportX = -1;
    private int previewViewportY = -1;
    private int previewViewportWidth;
    private int previewViewportHeight;
    private boolean previewScrollbarDragging;
    private int previewScrollbarDragOffset;
    @Nullable private String previewSelectionKey;
    private String serverSearchQuery = "";
    private boolean modrinthServerMode;
    private boolean modrinthServerLoading;
    private String modrinthServerStatus = "Saved servers";
    private int modrinthSearchSequence;
    @Nullable private ServerList modrinthServerList;
    private final Map<String, ModrinthServerEntry> modrinthServersByAddress = new LinkedHashMap<>();
    private final Map<String, ModrinthServerEntry> modrinthServersByProjectId = new LinkedHashMap<>();
    private static final Set<String> MODRINTH_ICON_REQUESTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> MODRINTH_ICON_FAILURES = ConcurrentHashMap.newKeySet();
    private static final HttpClient MODRINTH_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Identifier KOIL_FALLBACK_SERVER_ICON = ExternalImageLoader.loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");

    protected MixinMultiplayerScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            if (this.initialized) {
                this.serverListWidget.updateSize(385, this.height, 39, this.height - 57);
            }
        }
    }

    @Override
    @SuppressWarnings("unused")
    protected void init() {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            int x = 37;

            assert this.client != null;
            if (this.initialized) {
                this.serverListWidget.updateSize(385, this.height, 39, this.height - 57);
            } else {
                this.initialized = true;
                this.serverList = new ServerList(this.client);
                this.serverList.loadFile();
                this.lanServers = new LanServerQueryManager.LanServerEntryList();

                try {
                    this.lanServerDetector = new LanServerQueryManager.LanServerDetector(this.lanServers);
                    this.lanServerDetector.start();
                } catch (Exception var9) {
                    LOGGER.warn("Unable to start LAN server detection: {}", var9.getMessage());
                }

                this.serverListWidget = new MultiplayerServerListWidget((MultiplayerScreen) (Object) this, this.client, this.width, this.height, 32, this.height - 64, 36);
            }

            initServerSearchBox(true);
            applyServerSearch();
            this.addSelectableChild(this.serverListWidget);
            this.addDrawableChild(this.serverSearchBox);
            this.buttonJoin = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.select"), (button) -> this.connect()).dimensions(x, this.height - 52, 100, 20).build());
            ButtonWidget buttonWidget = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.direct"), (button) -> {
                this.selectedEntry = new ServerInfo(I18n.translate("selectServer.defaultName"), "", false);
                this.client.setScreen(new DirectConnectScreen(this, this::directConnect, this.selectedEntry));
            }).dimensions(x + 104, this.height - 52, 100, 20).build());
            ButtonWidget buttonWidget2 = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.add"), (button) -> {
                this.selectedEntry = new ServerInfo(I18n.translate("selectServer.defaultName"), "", false);
                this.client.setScreen(new AddServerScreen(this, this::addEntry, this.selectedEntry));
            }).dimensions(x + 208, this.height - 52, 100, 20).build());
            this.buttonEdit = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.edit"), (button) -> {
                MultiplayerServerListWidget.Entry entry = this.serverListWidget.getSelectedOrNull();
                if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                    ServerInfo serverInfo = ((MultiplayerServerListWidget.ServerEntry) entry).getServer();
                    this.selectedEntry = new ServerInfo(serverInfo.name, serverInfo.address, false);
                    this.selectedEntry.copyWithSettingsFrom(serverInfo);
                    this.client.setScreen(new AddServerScreen(this, this::editEntry, this.selectedEntry));
                }

            }).dimensions(x, this.height - 28, 74, 20).build());
            this.buttonDelete = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.delete"), (button) -> {
                MultiplayerServerListWidget.Entry entry = this.serverListWidget.getSelectedOrNull();
                if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                    String string = ((MultiplayerServerListWidget.ServerEntry) entry).getServer().name;
                    if (string != null) {
                        Text text = Text.translatable("selectServer.deleteQuestion");
                        Text text2 = Text.translatable("selectServer.deleteWarning", string);
                        Text text3 = Text.translatable("selectServer.deleteButton");
                        Text text4 = ScreenTexts.CANCEL;
                        this.client.setScreen(new ConfirmScreen(this::removeEntry, text, text2, text3, text4));
                    }
                }
            }).dimensions(x + 78, this.height - 28, 74, 20).tooltip(Tooltip.of(Text.literal("[!] This cannot be undone!").formatted(Formatting.RED))).build());
            ButtonWidget buttonWidget3 = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.refresh"), (button) -> this.refresh()).dimensions(x + 156, this.height - 28, 74, 20).build());
            ButtonWidget buttonWidget4 = this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> this.client.setScreen(this.parent)).dimensions(x + 234, this.height - 28, 74, 20).build());
            this.toggleMaskButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Mask IP"), (button) -> this.isMasked = !this.isMasked).dimensions(x + 316, this.height - 28, 74, 20).tooltip(Tooltip.of(Text.literal("[!] This will show the ip address of the selected server!").formatted(Formatting.RED))).build());
            this.copyIPButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy IP"), (button) -> {
                if (selectedServerIP != null) {
                    MinecraftClient.getInstance().keyboard.setClipboard(selectedServerIP);
                }
            }).dimensions(x + 316, this.height - 52, 74, 20).build());
            this.openIPButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Website"), (button) -> {
                if (selectedServerIP != null) {
                    Util.getOperatingSystem().open("https://" + extractIpForWebSegment(selectedServerIP));
                }
            }).dimensions(x + 394, this.height - 52, 120, 20).build());
            this.inDevButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(modrinthServerMode ? "Saved List" : "Online List"), (button) -> toggleModrinthDiscovery()).dimensions(x + 394, this.height - 28, 120, 20).tooltip(Tooltip.of(Text.literal("Search Modrinth server-compatible content from the multiplayer screen."))).build());

            this.updateButtonActivationStates();
            selectFirstServerEntry();
        } else {
            if (this.initialized) {
                this.serverListWidget.updateSize(this.width, this.height, 48, this.height - 64);
            } else {
                this.initialized = true;
                this.serverList = new ServerList(this.client);
                this.serverList.loadFile();
                this.lanServers = new LanServerQueryManager.LanServerEntryList();

                try {
                    this.lanServerDetector = new LanServerQueryManager.LanServerDetector(this.lanServers);
                    this.lanServerDetector.start();
                } catch (Exception var9) {
                    LOGGER.warn("Unable to start LAN server detection: {}", var9.getMessage());
                }

                this.serverListWidget = new MultiplayerServerListWidget((MultiplayerScreen) (Object) this, this.client, this.width, this.height, 48, this.height - 64, 36);
            }

            initServerSearchBox(false);
            applyServerSearch();
            this.addSelectableChild(this.serverListWidget);
            this.addDrawableChild(this.serverSearchBox);
            this.buttonJoin = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.select"), (button) -> this.connect()).width(100).build());
            ButtonWidget buttonWidget = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.direct"), (button) -> {
                this.selectedEntry = new ServerInfo(I18n.translate("selectServer.defaultName"), "", false);
                this.client.setScreen(new DirectConnectScreen(this, this::directConnect, this.selectedEntry));
            }).width(100).build());
            ButtonWidget buttonWidget2 = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.add"), (button) -> {
                this.selectedEntry = new ServerInfo(I18n.translate("selectServer.defaultName"), "", false);
                this.client.setScreen(new AddServerScreen(this, this::addEntry, this.selectedEntry));
            }).width(100).build());
            this.buttonEdit = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.edit"), (button) -> {
                MultiplayerServerListWidget.Entry entry = this.serverListWidget.getSelectedOrNull();
                if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                    ServerInfo serverInfo = ((MultiplayerServerListWidget.ServerEntry)entry).getServer();
                    this.selectedEntry = new ServerInfo(serverInfo.name, serverInfo.address, false);
                    this.selectedEntry.copyWithSettingsFrom(serverInfo);
                    this.client.setScreen(new AddServerScreen(this, this::editEntry, this.selectedEntry));
                }

            }).width(74).build());
            this.buttonDelete = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.delete"), (button) -> {
                MultiplayerServerListWidget.Entry entry = this.serverListWidget.getSelectedOrNull();
                if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                    String string = ((MultiplayerServerListWidget.ServerEntry)entry).getServer().name;
                    if (string != null) {
                        Text text = Text.translatable("selectServer.deleteQuestion");
                        Text text2 = Text.translatable("selectServer.deleteWarning", string);
                        Text text3 = Text.translatable("selectServer.deleteButton");
                        Text text4 = ScreenTexts.CANCEL;
                        this.client.setScreen(new ConfirmScreen(this::removeEntry, text, text2, text3, text4));
                    }
                }

            }).width(74).build());
            ButtonWidget buttonWidget3 = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.refresh"), (button) -> this.refresh()).width(74).build());
            ButtonWidget buttonWidget4 = this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> this.client.setScreen(this.parent)).width(74).build());
            GridWidget gridWidget = new GridWidget();
            GridWidget.Adder adder = gridWidget.createAdder(1);
            AxisGridWidget axisGridWidget = adder.add(new AxisGridWidget(308, 20, AxisGridWidget.DisplayAxis.HORIZONTAL));
            axisGridWidget.add(this.buttonJoin);
            axisGridWidget.add(buttonWidget);
            axisGridWidget.add(buttonWidget2);
            adder.add(EmptyWidget.ofHeight(4));
            AxisGridWidget axisGridWidget2 = adder.add(new AxisGridWidget(308, 20, AxisGridWidget.DisplayAxis.HORIZONTAL));
            axisGridWidget2.add(this.buttonEdit);
            axisGridWidget2.add(this.buttonDelete);
            axisGridWidget2.add(buttonWidget3);
            axisGridWidget2.add(buttonWidget4);
            gridWidget.refreshPositions();
            SimplePositioningWidget.setPos(gridWidget, 0, this.height - 64, this.width, 64);
            addVanillaKoilToolButtons();
            this.updateButtonActivationStates();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            this.multiplayerScreenTooltip = null;
            KoilScreenBackgrounds.render(context, this.client, this.width, this.height);
            BrowserLayoutHelper.renderBrowserChrome(context, this.client, this.textRenderer, this.width, this.height, "Multiplayer");
            this.serverListWidget.render(context, mouseX, mouseY, delta);
            if (hasSelectedServerEntry()) {
                syncPreviewSelection();
                renderSelectedServerPanel(context);
            } else {
                this.previewSelectionKey = null;
                this.previewScrollOffset = 0;
                this.previewScrollMax = 0;
            }
            BrowserLayoutHelper.renderBrowserBars(context, this.client, this.textRenderer, this.width, this.height, "Multiplayer");
            if (this.modrinthServerMode || this.modrinthServerLoading) {
                context.drawText(this.textRenderer, trimPreview(this.modrinthServerStatus, 220), this.width - 210, 32, this.modrinthServerLoading ? 0xFFE3B735 : 0xFF7FC8C2, false);
            }
            super.render(context, mouseX, mouseY, delta);
            if (this.multiplayerScreenTooltip != null) {
                context.drawTooltip(this.textRenderer, this.multiplayerScreenTooltip, mouseX, mouseY);
            }
        } else {
            this.multiplayerScreenTooltip = null;
            this.renderBackground(context);
            this.serverListWidget.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 16777215);
            super.render(context, mouseX, mouseY, delta);
            if (this.multiplayerScreenTooltip != null) {
                context.drawTooltip(this.textRenderer, this.multiplayerScreenTooltip, mouseX, mouseY);
            }
        }
    }

    private void initServerSearchBox(boolean redesign) {
        int searchWidth = redesign ? 200 : 200;
        int searchX = redesign ? this.width - 210 : this.width / 2 - searchWidth / 2;
        int searchY = redesign ? 10 : 22;
        this.serverSearchBox = new TextFieldWidget(this.textRenderer, searchX, searchY, searchWidth, 20, this.serverSearchBox, Text.literal("Search Servers"));
        this.serverSearchBox.setPlaceholder(Text.literal("Search Servers"));
        this.serverSearchBox.setText(this.serverSearchQuery);
        this.serverSearchBox.setChangedListener(this::onServerSearchChanged);
    }

    private void onServerSearchChanged(String query) {
        this.serverSearchQuery = query == null ? "" : query;
        if (this.modrinthServerMode) {
            fetchModrinthServers(this.serverSearchQuery);
            return;
        }
        applyServerSearch();
    }

    private void applyServerSearch() {
        if (this.serverListWidget == null || this.serverList == null || this.client == null) {
            return;
        }
        if (this.modrinthServerMode) {
            ServerList list = this.modrinthServerList == null ? new ServerList(this.client) : this.modrinthServerList;
            this.serverListWidget.setServers(list);
            this.serverListWidget.setScrollAmount(0.0D);
            selectFirstServerEntry();
            updateButtonActivationStates();
            return;
        }
        String query = this.serverSearchQuery == null ? "" : this.serverSearchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            this.serverListWidget.setServers(this.serverList);
        } else {
            ServerList filtered = new ServerList(this.client);
            for (int index = 0; index < this.serverList.size(); index++) {
                ServerInfo server = this.serverList.get(index);
                if (matchesServerSearch(server, query)) {
                    filtered.add(server, false);
                }
            }
            this.serverListWidget.setServers(filtered);
        }
        this.serverListWidget.setScrollAmount(0.0D);
        selectFirstServerEntry();
        updateButtonActivationStates();
    }

    private void toggleModrinthDiscovery() {
        this.modrinthServerMode = !this.modrinthServerMode;
        this.modrinthServerStatus = this.modrinthServerMode ? "Loading Modrinth..." : "Saved servers";
        if (this.inDevButton != null) {
            this.inDevButton.setMessage(Text.literal(this.modrinthServerMode ? "Saved List" : "Online List"));
        }
        if (this.modrinthServerMode) {
            fetchModrinthServers(this.serverSearchQuery);
        } else {
            applyServerSearch();
        }
    }

    private void fetchModrinthServers(String rawQuery) {
        if (this.client == null) {
            return;
        }
        int sequence = ++this.modrinthSearchSequence;
        this.modrinthServerLoading = true;
        String query = rawQuery == null || rawQuery.isBlank() ? "server" : rawQuery.trim();
        this.modrinthServerStatus = "Searching Modrinth for \"" + query + "\"";
        CompletableFuture.supplyAsync(() -> requestModrinthServers(query)).whenComplete((result, error) -> {
            MinecraftClient minecraft = MinecraftClient.getInstance();
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (sequence != this.modrinthSearchSequence || !this.modrinthServerMode) {
                    return;
                }
                this.modrinthServerLoading = false;
                if (error != null || result == null) {
                    this.modrinthServerStatus = "Modrinth search failed";
                    this.modrinthServerList = new ServerList(minecraft);
                    this.modrinthServersByAddress.clear();
                    this.modrinthServersByProjectId.clear();
                } else {
                    this.modrinthServerList = result.list();
                    this.modrinthServersByAddress.clear();
                    this.modrinthServersByProjectId.clear();
                    this.modrinthServersByAddress.putAll(result.metadata());
                    this.modrinthServersByProjectId.putAll(result.projectMetadata());
                    this.modrinthServerStatus = result.metadata().isEmpty() ? "No Modrinth server results" : result.metadata().size() + " Modrinth servers";
                }
                applyServerSearch();
            });
        });
    }

    private ModrinthServerResult requestModrinthServers(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String facets = URLEncoder.encode("[[\"project_type:server\"]]", StandardCharsets.UTF_8);
            URI uri = URI.create("https://api.modrinth.com/v3/search?limit=25&index=relevance&query=" + encoded + "&facets=" + facets);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "SpiritXIV/Koil/" + com.spirit.Main.VERSION)
                    .header("Labrinth-Canary", "always")
                    .GET()
                    .build();
            HttpResponse<String> response = MODRINTH_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            ServerList discovered = new ServerList(MinecraftClient.getInstance());
            Map<String, ModrinthServerEntry> metadata = new LinkedHashMap<>();
            Map<String, ModrinthServerEntry> projectMetadata = new LinkedHashMap<>();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ModrinthServerResult(discovered, metadata, projectMetadata);
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray hits = firstJsonArray(root, "hits", "serverHits", "servers", "projects");
            for (JsonElement hitElement : hits) {
                if (!hitElement.isJsonObject()) {
                    continue;
                }
                JsonObject hit = normalizeModrinthServerHit(hitElement.getAsJsonObject());
                ModrinthServerEntry discovery = buildModrinthServerEntry(hit);
                if (discovery == null) {
                    continue;
                }
                ServerInfo info = new ServerInfo(discovery.title(), discovery.address(), false);
                info.label = Text.literal(discovery.description().isBlank() ? buildDiscoveryLabel(discovery) : discovery.description());
                if (!discovery.gameVersion().isBlank()) {
                    info.version = Text.literal(discovery.gameVersion());
                }
                if (!discovery.playersOnline().isBlank() || !discovery.playersMax().isBlank()) {
                    info.playerCountLabel = Text.literal(joinPlayerCount(discovery.playersOnline(), discovery.playersMax()));
                }
                if (!discovery.online().isBlank()) {
                    info.online = discovery.online().equalsIgnoreCase("online") || discovery.online().equalsIgnoreCase("true");
                }
                discovered.add(info, false);
                metadata.put(discovery.address(), discovery);
                if (!discovery.projectId().isBlank()) {
                    projectMetadata.put(discovery.projectId(), discovery);
                }
                queueModrinthIconLoad(info, discovery);
            }
            return new ModrinthServerResult(discovered, metadata, projectMetadata);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean matchesServerSearch(ServerInfo server, String query) {
        if (server == null || query == null || query.isBlank()) {
            return true;
        }
        return containsSearch(server.name, query)
                || containsSearch(server.address, query)
                || containsSearch(server.version == null ? "" : server.version.getString(), query)
                || containsSearch(server.label == null ? "" : server.label.getString(), query)
                || containsSearch(server.playerCountLabel == null ? "" : server.playerCountLabel.getString(), query);
    }

    private boolean containsSearch(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private void addVanillaKoilToolButtons() {
        int toolWidth = 74;
        int rightX = Math.min(this.width - toolWidth - 4, this.width / 2 + 160);
        int topY = this.height - 54;
        int bottomY = this.height - 30;
        this.copyIPButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy IP"), (button) -> {
            if (selectedServerIP != null) {
                MinecraftClient.getInstance().keyboard.setClipboard(selectedServerIP);
            }
        }).dimensions(rightX, bottomY, toolWidth, 20).build());
        this.openIPButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Web"), (button) -> {
            if (selectedServerIP != null) {
                Util.getOperatingSystem().open("https://" + extractIpForWebSegment(selectedServerIP));
            }
        }).dimensions(rightX, topY, toolWidth, 20).build());
        this.toggleMaskButton = null;
        this.inDevButton = null;
    }

    private String maskIP(String ipAddress) {
        StringBuilder maskedIP = new StringBuilder();
        for (int i = 0; i < ipAddress.length(); i++) {
            if (Character.isLetterOrDigit(ipAddress.charAt(i))) {
                maskedIP.append('#');
            } else {
                maskedIP.append(ipAddress.charAt(i));
            }
        }
        return maskedIP.toString();
    }

    @Override
    public String koil$displayServerAddress(String address) {
        if (address == null || address.isBlank()) {
            return "No address";
        }
        return this.isMasked ? maskIP(address) : address;
    }

    private String getSelectedServerIP() {
        MultiplayerServerListWidget.Entry entry = this.serverListWidget.getSelectedOrNull();
        if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
            return ((MultiplayerServerListWidget.ServerEntry) entry).getServer().address;
        }
        return null;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            this.previewViewportX = -1;
        }
    }

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private void koil$guardDiscoveryConnect(CallbackInfo info) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        ModrinthServerEntry discovery = selectedDiscoveryEntry();
        if (discovery != null && !discovery.hasDirectJoinAddress()) {
            Util.getOperatingSystem().open("https://" + discovery.address());
            info.cancel();
        }
    }

    private void renderSelectedServerPanel(DrawContext context) {
        MultiplayerServerListWidget.Entry entry = this.serverListWidget.getSelectedOrNull();
        if (!(entry instanceof MultiplayerServerListWidget.ServerEntry serverEntry)) {
            return;
        }
        ServerInfo server = serverEntry.getServer();
        if (server == null) {
            return;
        }
        int panelX = BrowserLayoutHelper.previewX(this.width);
        int panelY = BrowserLayoutHelper.previewY();
        int panelWidth = BrowserLayoutHelper.previewWidth(this.width);
        int panelHeight = BrowserLayoutHelper.previewHeight(this.height);
        BrowserLayoutHelper.renderPreviewPanelFrame(context, panelX, panelY, panelWidth, panelHeight);

        Identifier icon = resolveServerIcon(server);
        if (icon != null) {
            context.drawTexture(icon, panelX + 10, panelY + 10, 0, 0, 64, 64, 64, 64);
        }
        int titleX = panelX + 84;
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, trimPreview(server.name, 170), (int) (titleX / 1.5F), (int) ((panelY + 10) / 1.5F), new Color(uiColorContentBaseTitleText).getRGB(), true);
        context.getMatrices().pop();
        String address = this.isMasked ? maskIP(server.address) : server.address;
        context.drawText(this.textRenderer, trimPreview(address, panelWidth - 96), titleX, panelY + 34, 0xFFD8DFE9, false);
        String versionText = server.version == null ? "" : server.version.getString();
        if (!versionText.isBlank()) {
            context.drawText(this.textRenderer, trimPreview(versionText, panelWidth - 96), titleX, panelY + 48, 0xFFE9DFC9, false);
        }

        int detailsViewportY = panelY + 86;
        int detailsViewportHeight = Math.max(70, panelHeight - (detailsViewportY - panelY) - 8);
        this.previewViewportX = panelX + 8;
        this.previewViewportY = detailsViewportY;
        this.previewViewportWidth = panelWidth - 16;
        this.previewViewportHeight = detailsViewportHeight;
        context.enableScissor(this.previewViewportX, this.previewViewportY, this.previewViewportX + this.previewViewportWidth, this.previewViewportY + this.previewViewportHeight);

        int infoY = detailsViewportY - this.previewScrollOffset;
        int contentHeight = 0;
        String host = this.isMasked ? maskIP(extractServerHost(server.address)) : extractServerHost(server.address);

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Identity");
        infoY += 16;
        contentHeight += 16;
        int nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Address", address, 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Host", host, 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Port", extractServerPort(server.address), 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "MOTD", server.label == null ? "None" : server.label.getString(), 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Connection");
        infoY += 16;
        contentHeight += 16;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Version", versionText, 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Protocol", String.valueOf(server.protocolVersion), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Ping", server.ping >= 0L ? server.ping + " ms" : "Unknown", 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Status", server.online ? "Online" : "Offline", 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Flags", buildServerFlags(server), 0xFFD8DFE9);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Developer Details");
        infoY += 16;
        contentHeight += 16;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Players", formatPlayerCounts(server), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Samples", formatPlayerSamples(server), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "List", joinTextList(server.playerListSummary), 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Favicon", server.getFavicon() == null ? "None" : server.getFavicon().length + " bytes", 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Transport", buildTransportFlags(server), 0xFFE4DAC8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Identity", buildIdentityFlags(server), 0xFFD2E0CF);
        contentHeight += nextY - infoY;
        infoY = nextY;

        ModrinthServerEntry discovery = this.modrinthServersByAddress.get(server.address);
        if (discovery != null) {
            BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Modrinth Server");
            infoY += 16;
            contentHeight += 16;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Project", discovery.title(), 0xFFDCE4EE);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Project ID", discovery.projectId().isBlank() ? "Unknown" : discovery.projectId(), 0xFFD8E7DE);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Slug", discovery.slug().isBlank() ? "Unknown" : discovery.slug(), 0xFFD9D5E8);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Type", discovery.type(), 0xFFD6E5DA);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Author", discovery.author().isBlank() ? "Unknown" : discovery.author(), 0xFFE4DAC8);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Page", discovery.pageUrl(), 0xFFD2E0CF);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Description", discovery.description().isBlank() ? "None" : discovery.description(), 0xFFDCE4EE);
            contentHeight += nextY - infoY;
            infoY = nextY;

            BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Modrinth Join Data");
            infoY += 16;
            contentHeight += 16;
            String join = discovery.hasDirectJoinAddress() ? discovery.directAddress() : "No direct Java address exposed; opens Modrinth page";
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Join", join, discovery.hasDirectJoinAddress() ? 0xFFD2E0CF : 0xFFE3B735);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Java Address", discovery.javaAddress().isBlank() ? "Unknown" : discovery.javaAddress(), 0xFFD8E7DE);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Bedrock Address", discovery.bedrockAddress().isBlank() ? "Unknown" : discovery.bedrockAddress(), 0xFFD9D5E8);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Online", discovery.online().isBlank() ? "Unknown" : discovery.online(), 0xFFD6E5DA);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Players", joinPlayerCount(discovery.playersOnline(), discovery.playersMax()), 0xFFE4DAC8);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Verified Plays", discovery.verifiedPlays().isBlank() ? "Unknown" : discovery.verifiedPlays(), 0xFFD2E0CF);
            contentHeight += nextY - infoY;
            infoY = nextY;

            BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Modrinth Compatibility");
            infoY += 16;
            contentHeight += 16;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Game Version", discovery.gameVersion().isBlank() ? "Unknown" : discovery.gameVersion(), 0xFFDCE4EE);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Loader", discovery.loader().isBlank() ? "Unknown" : discovery.loader(), 0xFFD8E7DE);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Compatibility", discovery.compatibility().isBlank() ? "Unknown" : discovery.compatibility(), 0xFFD9D5E8);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Required Content", discovery.requiredContent().isBlank() ? "None" : discovery.requiredContent(), 0xFFD6E5DA);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Categories", discovery.categories().isBlank() ? "None" : discovery.categories(), 0xFFE4DAC8);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Region", discovery.country().isBlank() ? "Unknown" : discovery.country(), 0xFFD2E0CF);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Language", discovery.language().isBlank() ? "Unknown" : discovery.language(), 0xFFDCE4EE);
            contentHeight += nextY - infoY;
            infoY = nextY;

            BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Modrinth Metrics");
            infoY += 16;
            contentHeight += 16;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Followers", discovery.follows().isBlank() ? "Unknown" : discovery.follows(), 0xFFD8E7DE);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Downloads", discovery.downloads().isBlank() ? "Not used for servers" : discovery.downloads(), 0xFFDCE4EE);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Updated", discovery.updated().isBlank() ? "Unknown" : discovery.updated(), 0xFFD9D5E8);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Icon", discovery.iconUrl().isBlank() ? "None" : discovery.iconUrl(), 0xFFD6E5DA);
            contentHeight += nextY - infoY;
            infoY = nextY;
            nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Banner", discovery.bannerUrl().isBlank() ? "None" : discovery.bannerUrl(), 0xFFE4DAC8);
            contentHeight += nextY - infoY;
        }
        context.disableScissor();
        this.previewScrollMax = Math.max(0, contentHeight - detailsViewportHeight + 4);
        if (this.previewScrollOffset > this.previewScrollMax) {
            this.previewScrollOffset = this.previewScrollMax;
        }
        renderPreviewScrollbar(context);
    }

    private Identifier resolveServerIcon(ServerInfo server) {
        if (server == null || server.address == null || server.address.isBlank()) {
            return KOIL_FALLBACK_SERVER_ICON;
        }
        ModrinthServerEntry discovery = this.modrinthServersByAddress.get(server.address);
        if (server.getFavicon() != null) {
            try {
                Identifier icon = ExternalImageLoader.registerDynamicTexture("koil", "server_icon/" + sanitizeTextureKey(server.address), server.getFavicon());
                return icon != null ? icon : KOIL_FALLBACK_SERVER_ICON;
            } catch (IOException ignored) {
                return KOIL_FALLBACK_SERVER_ICON;
            }
        }
        if (discovery != null && !discovery.iconUrl().isBlank()) {
            queueModrinthIconLoad(server, discovery);
            Identifier icon = ContentRemoteIconResolver.resolve(discovery.iconUrl(), "server_icon/" + sanitizeTextureKey(discovery.textureKey()));
            if (icon != null) {
                return icon;
            }
        }
        return KOIL_FALLBACK_SERVER_ICON;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void select(MultiplayerServerListWidget.Entry entry) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            clearSelectedServerInfo();
            this.serverListWidget.setSelected(entry);
            if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                ServerInfo server = ((MultiplayerServerListWidget.ServerEntry) entry).getServer();
                this.selectedServerSetName = server.name;
                this.selectedServerName = extractServerIPSegment(server.address);
                this.selectedServerIP = server.address;
                this.selectedServerVersion = extractImportantVersionSegment(String.valueOf(server.version));
            } else {
                clearSelectedServerInfo();
            }
            updateButtonActivationStates();
        } else {
            clearSelectedServerInfo();
            this.serverListWidget.setSelected(entry);
            if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                ServerInfo server = ((MultiplayerServerListWidget.ServerEntry) entry).getServer();
                this.selectedServerSetName = server.name;
                this.selectedServerName = extractServerIPSegment(server.address);
                this.selectedServerIP = server.address;
                this.selectedServerVersion = extractImportantVersionSegment(String.valueOf(server.version));
            }
            this.updateButtonActivationStates();
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    protected void updateButtonActivationStates() {
        if (this.buttonJoin == null || this.buttonEdit == null || this.buttonDelete == null) {
            return;
        }
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            boolean bl = this.serverListWidget.getSelectedOrNull() != null && this.serverListWidget.getSelectedOrNull() instanceof MultiplayerServerListWidget.ServerEntry;
            boolean discovered = selectedDiscoveryEntry() != null;
            this.buttonJoin.active = bl;
            this.buttonEdit.active = bl && !discovered;
            this.buttonDelete.active = bl && !discovered;
            if (this.toggleMaskButton != null) this.toggleMaskButton.active = bl;
            if (this.copyIPButton != null) this.copyIPButton.active = bl;
            if (this.openIPButton != null) this.openIPButton.active = bl;
            if (this.inDevButton != null) this.inDevButton.active = true;
        } else {
            this.buttonJoin.active = false;
            this.buttonEdit.active = false;
            this.buttonDelete.active = false;
            MultiplayerServerListWidget.Entry entry = this.serverListWidget.getSelectedOrNull();
            if (entry != null && !(entry instanceof MultiplayerServerListWidget.ScanningEntry)) {
                this.buttonJoin.active = true;
                if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                    this.buttonEdit.active = true;
                    this.buttonDelete.active = true;
                }
            }
            boolean hasSavedServer = entry instanceof MultiplayerServerListWidget.ServerEntry;
            if (this.toggleMaskButton != null) this.toggleMaskButton.active = hasSavedServer;
            if (this.copyIPButton != null) this.copyIPButton.active = hasSavedServer;
            if (this.openIPButton != null) this.openIPButton.active = hasSavedServer;
            if (this.inDevButton != null) this.inDevButton.active = false;
        }
    }

    private String extractServerIPSegment(String address) {
        if (address == null || address.isEmpty()) {
            return "";
        }

        int firstDotIndex = address.indexOf('.');
        if (firstDotIndex == -1) {
            return "";
        }

        int secondDotIndex = address.indexOf('.', firstDotIndex + 1);
        if (secondDotIndex == -1) {
            return "";
        }
        return address.substring(firstDotIndex + 1, secondDotIndex);
    }

    private String extractImportantVersionSegment(String version) {
        if (version == null || version.isEmpty()) {
            return "";
        }

        int firstCurlBracIndex = version.indexOf('{');
        if (firstCurlBracIndex == -1) {
            return "";
        }

        int secondCurlBracIndex = version.indexOf('}', firstCurlBracIndex + 1);
        if (secondCurlBracIndex == -1) {
            return "";
        }
        return version.substring(firstCurlBracIndex + 1, secondCurlBracIndex);
    }

    private String extractIpForWebSegment(String address) {
        if (address == null || address.isEmpty()) {
            return "";
        }
        if (address.startsWith("modrinth.com/")) {
            return address;
        }

        int protocolIndex = address.indexOf("://");
        if (protocolIndex != -1) {
            address = address.substring(protocolIndex + 3);
        }

        int firstIndex = address.indexOf('.');
        if (firstIndex == -1) {
            return "";
        }

        int portIndex = address.indexOf(':');
        if (portIndex != -1) {
            return address.substring(firstIndex + 1, portIndex);
        } else {
            return address.substring(firstIndex + 1);
        }
    }

    private void clearSelectedServerInfo() {
        this.selectedServerName = null;
        this.selectedServerSetName = null;
        this.selectedServerIP = null;
        this.selectedServerVersion = null;
    }

    private String buildServerFlags(ServerInfo server) {
        StringBuilder builder = new StringBuilder();
        if (server.isLocal()) {
            builder.append("LAN");
        }
        if (server.isSecureChatEnforced()) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append("Secure Chat");
        }
        if (server.getResourcePackPolicy() != null) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(server.getResourcePackPolicy().name());
        }
        return builder.isEmpty() ? "None" : builder.toString();
    }

    private String buildTransportFlags(ServerInfo server) {
        StringBuilder builder = new StringBuilder();
        builder.append(server.address != null && server.address.contains(":") ? "Custom Port" : "Default Port");
        builder.append(server.ping >= 0L ? " | Ping Sampled" : " | Ping Pending");
        builder.append(server.online ? " | Status Resolved" : " | Status Unresolved");
        return builder.toString();
    }

    private String buildIdentityFlags(ServerInfo server) {
        StringBuilder builder = new StringBuilder();
        if (server.name != null && !server.name.isBlank()) {
            builder.append("Named Entry");
        }
        if (server.getFavicon() != null) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append("Custom Icon");
        } else {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append("Default Icon");
        }
        if (server.label != null && !server.label.getString().isBlank()) {
            builder.append(" | MOTD Cached");
        }
        return builder.toString();
    }

    private String formatPlayerCounts(ServerInfo server) {
        if (server == null || server.players == null) {
            return server == null || server.playerCountLabel == null ? "Unknown" : server.playerCountLabel.getString();
        }
        return server.players.online() + " / " + server.players.max();
    }

    private String formatPlayerSamples(ServerInfo server) {
        if (server == null || server.players == null || server.players.sample() == null || server.players.sample().isEmpty()) {
            return "None";
        }
        StringBuilder builder = new StringBuilder();
        for (var profile : server.players.sample()) {
            if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(profile.getName());
            if (builder.length() > 120) {
                break;
            }
        }
        return builder.isEmpty() ? "None" : builder.toString();
    }

    private String joinTextList(List<Text> lines) {
        if (lines == null || lines.isEmpty()) {
            return "None";
        }
        StringBuilder builder = new StringBuilder();
        for (Text line : lines) {
            if (line == null) {
                continue;
            }
            String text = line.getString();
            if (text.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(text);
            if (builder.length() > 160) {
                break;
            }
        }
        return builder.isEmpty() ? "None" : builder.toString();
    }

    private String extractServerHost(String address) {
        if (address == null || address.isBlank()) {
            return "Unknown";
        }
        String clean = address;
        int protocolIndex = clean.indexOf("://");
        if (protocolIndex >= 0) {
            clean = clean.substring(protocolIndex + 3);
        }
        int portIndex = clean.lastIndexOf(':');
        if (portIndex > 0 && clean.indexOf(']') < portIndex) {
            return clean.substring(0, portIndex);
        }
        return clean;
    }

    private String extractServerPort(String address) {
        if (address == null || address.isBlank()) {
            return "Default";
        }
        String clean = address;
        int protocolIndex = clean.indexOf("://");
        if (protocolIndex >= 0) {
            clean = clean.substring(protocolIndex + 3);
        }
        int portIndex = clean.lastIndexOf(':');
        if (portIndex > 0 && portIndex < clean.length() - 1 && clean.indexOf(']') < portIndex) {
            return clean.substring(portIndex + 1);
        }
        return "Default";
    }

    private String sanitizeTextureKey(String value) {
        return value == null ? "unknown" : value.toLowerCase().replaceAll("[^a-z0-9._/-]", "_");
    }

    private String trimPreview(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        String trimmed = this.textRenderer.trimToWidth(value, maxWidth);
        if (trimmed.length() == value.length()) {
            return trimmed;
        }
        return this.textRenderer.trimToWidth(value, Math.max(8, maxWidth - this.textRenderer.getWidth("..."))) + "...";
    }

    private void selectFirstServerEntry() {
        if (this.serverListWidget == null || this.serverListWidget.getSelectedOrNull() != null) {
            return;
        }
        for (MultiplayerServerListWidget.Entry entry : this.serverListWidget.children()) {
            if (entry instanceof MultiplayerServerListWidget.ServerEntry) {
                select(entry);
                return;
            }
        }
    }

    private boolean hasSelectedServerEntry() {
        return this.serverListWidget != null && this.serverListWidget.getSelectedOrNull() instanceof MultiplayerServerListWidget.ServerEntry;
    }

    @Nullable
    private ModrinthServerEntry selectedDiscoveryEntry() {
        MultiplayerServerListWidget.Entry entry = this.serverListWidget == null ? null : this.serverListWidget.getSelectedOrNull();
        if (!(entry instanceof MultiplayerServerListWidget.ServerEntry serverEntry)) {
            return null;
        }
        ServerInfo server = serverEntry.getServer();
        return server == null ? null : this.modrinthServersByAddress.get(server.address);
    }

    private void syncPreviewSelection() {
        MultiplayerServerListWidget.Entry entry = this.serverListWidget == null ? null : this.serverListWidget.getSelectedOrNull();
        String selectionKey = null;
        if (entry instanceof MultiplayerServerListWidget.ServerEntry serverEntry) {
            ServerInfo server = serverEntry.getServer();
            selectionKey = server == null ? null : server.name + "|" + server.address;
        }
        if (selectionKey != null && !selectionKey.equals(this.previewSelectionKey)) {
            this.previewSelectionKey = selectionKey;
            this.previewScrollOffset = 0;
            this.previewScrollMax = 0;
        }
    }

    private void renderPreviewScrollbar(DrawContext context) {
        if (this.previewScrollMax <= 0 || this.previewViewportHeight <= 0) {
            return;
        }
        int trackX = this.previewViewportX + this.previewViewportWidth - 4;
        context.fill(trackX, this.previewViewportY, trackX + 2, this.previewViewportY + this.previewViewportHeight, 0x2E67727F);
        int thumbHeight = Math.max(18, (int) ((this.previewViewportHeight / (float) (this.previewViewportHeight + this.previewScrollMax)) * this.previewViewportHeight));
        int thumbTravel = Math.max(1, this.previewViewportHeight - thumbHeight);
        int thumbY = this.previewViewportY + (int) ((this.previewScrollOffset / (float) this.previewScrollMax) * thumbTravel);
        context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, 0x9AA9B9CA);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX >= this.previewViewportX && mouseX <= this.previewViewportX + this.previewViewportWidth
                && mouseY >= this.previewViewportY && mouseY <= this.previewViewportY + this.previewViewportHeight
                && this.previewScrollMax > 0) {
            this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, this.previewScrollOffset - (int) amount * 12));
            return true;
        }
        if (this.serverListWidget != null && this.serverListWidget.isMouseOver(mouseX, mouseY)) {
            this.serverListWidget.setScrollAmount(Math.max(0.0D, this.serverListWidget.getScrollAmount() - (int) amount * 20.0D));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.previewScrollMax > 0 && isOverPreviewScrollbar(mouseX, mouseY)) {
            int thumbY = previewScrollbarThumbY();
            int thumbHeight = previewScrollbarThumbHeight();
            this.previewScrollbarDragging = true;
            this.previewScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
            setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.previewScrollbarDragging) {
            setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.previewScrollbarDragging) {
            this.previewScrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isOverPreviewScrollbar(double mouseX, double mouseY) {
        int trackX = this.previewViewportX + this.previewViewportWidth - 4;
        return this.previewViewportX >= 0
                && mouseX >= trackX - 4
                && mouseX <= trackX + 8
                && mouseY >= this.previewViewportY
                && mouseY <= this.previewViewportY + this.previewViewportHeight;
    }

    private int previewScrollbarThumbHeight() {
        return Math.max(18, (int) ((this.previewViewportHeight / (float) (this.previewViewportHeight + this.previewScrollMax)) * this.previewViewportHeight));
    }

    private int previewScrollbarThumbY() {
        int thumbTravel = Math.max(1, this.previewViewportHeight - previewScrollbarThumbHeight());
        return this.previewViewportY + (int) ((this.previewScrollOffset / (float) this.previewScrollMax) * thumbTravel);
    }

    private void setPreviewScrollFromThumbTop(int thumbTop) {
        int thumbHeight = previewScrollbarThumbHeight();
        int minTop = this.previewViewportY;
        int maxTop = this.previewViewportY + this.previewViewportHeight - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int travel = Math.max(1, maxTop - minTop);
        float ratio = (clampedTop - minTop) / (float) travel;
        this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, Math.round(ratio * this.previewScrollMax)));
    }

    private JsonObject normalizeModrinthServerHit(JsonObject raw) {
        JsonObject normalized = new JsonObject();
        if (raw == null) {
            return normalized;
        }
        copyJsonObject(normalized, raw);
        JsonObject project = firstJsonObject(raw, "project", "project_data", "projectData");
        if (project != null) {
            copyJsonObject(normalized, project, true);
        }
        JsonObject server = firstJsonObject(raw, "server", "server_data", "serverData");
        if (server != null) {
            normalized.add("server", server);
            if (!normalized.has("minecraft_java_server")) {
                JsonObject javaServer = firstJsonObject(server, "minecraft_java_server", "java_server", "java");
                normalized.add("minecraft_java_server", javaServer == null ? server : javaServer);
            }
            if (!normalized.has("minecraft_bedrock_server")) {
                JsonObject bedrockServer = firstJsonObject(server, "minecraft_bedrock_server", "bedrock_server", "bedrock");
                if (bedrockServer != null) {
                    normalized.add("minecraft_bedrock_server", bedrockServer);
                }
            }
        }
        return normalized;
    }

    private void copyJsonObject(JsonObject target, JsonObject source) {
        copyJsonObject(target, source, false);
    }

    private void copyJsonObject(JsonObject target, JsonObject source, boolean overwrite) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (entry.getKey() == null || (!overwrite && target.has(entry.getKey()))) {
                continue;
            }
            target.add(entry.getKey(), entry.getValue());
        }
    }

    private ModrinthServerEntry buildModrinthServerEntry(JsonObject hit) {
        JsonObject javaServer = firstJsonObject(hit, "minecraft_java_server", "java_server", "java", "server");
        JsonObject bedrockServer = firstJsonObject(hit, "minecraft_bedrock_server", "bedrock_server", "bedrock");
        JsonObject compatibility = firstJsonObject(hit, "server_compatibility", "compatibility", "serverCompatibility");
        String title = firstJsonString(hit, "Modrinth Server", "title", "name");
        String slug = firstJsonString(hit, "", "slug");
        String projectId = firstJsonString(hit, "", "project_id", "projectId", "id");
        String projectType = firstJsonString(hit, "", "project_type", "projectType");
        if (!projectType.isBlank() && !projectType.equalsIgnoreCase("server")) {
            return null;
        }
        String type = projectType.isBlank() ? firstJsonString(hit, "server", "type", "server_type", "serverType") : projectType;
        String directAddress = directJoinAddress(hit);
        String pageUrl = buildModrinthPageUrl(slug, projectId);
        String address = directAddress.isBlank() ? pageUrl : directAddress;
        String description = firstJsonString(hit, "", "description", "summary", "body");
        String iconUrl = firstJsonString(hit, "", "icon_url", "iconUrl", "icon");
        String bannerUrl = firstJsonString(hit, "", "banner_url", "bannerUrl", "banner");
        String author = firstJsonString(hit, "", "author", "author_name", "authorName", "creator", "owner");
        String updated = firstJsonString(hit, "", "date_modified", "dateModified", "updated", "modified", "published");
        String downloads = firstJsonString(hit, "", "downloads");
        String follows = firstJsonString(hit, "", "follows", "followers");
        String verifiedPlays = firstJsonString(hit, "", "verified_plays", "verifiedPlays", "recent_verified_plays", "recentVerifiedPlays", "plays");
        String playersOnline = firstJsonString(hit, "", "players_online", "playersOnline", "online_players", "onlinePlayers");
        if (playersOnline.isBlank()) {
            playersOnline = firstJsonString(javaServer, "", "players_online", "playersOnline", "online_players", "onlinePlayers");
        }
        String playersMax = firstJsonString(hit, "", "players_max", "playersMax", "max_players", "maxPlayers");
        if (playersMax.isBlank()) {
            playersMax = firstJsonString(javaServer, "", "players_max", "playersMax", "max_players", "maxPlayers", "max");
        }
        String online = normalizeOnlineState(firstJsonString(hit, "", "online", "status", "pingable"));
        if (online.isBlank()) {
            online = normalizeOnlineState(firstJsonString(javaServer, "", "online", "status", "pingable"));
        }
        String country = firstJsonString(hit, "", "country", "region", "host_country", "hostCountry");
        String language = firstJsonString(hit, "", "language", "primary_language", "primaryLanguage");
        String gameVersion = firstJsonString(javaServer, "", "game_version", "gameVersion", "minecraft_version", "minecraftVersion", "recommended_version", "recommendedVersion", "version");
        if (gameVersion.isBlank()) {
            gameVersion = firstJsonString(compatibility, "", "game_version", "gameVersion", "minecraft_version", "minecraftVersion", "recommended_version", "recommendedVersion", "version");
        }
        if (gameVersion.isBlank()) {
            gameVersion = joinJsonArray(hit, "versions");
        }
        String loader = firstJsonString(compatibility, "", "loader", "mod_loader", "modLoader", "platform", "loader_type", "loaderType");
        if (loader.isBlank()) {
            loader = joinJsonArray(hit, "loaders");
        }
        if (loader.isBlank()) {
            loader = joinJsonArray(hit, "categories");
        }
        String compatibilityText = buildCompatibilitySummary(compatibility, hit);
        String requiredContent = buildRequiredContentSummary(compatibility, hit);
        String javaAddress = directAddress.isBlank() ? firstAddressString(javaServer) : directAddress;
        String bedrockAddress = firstAddressString(bedrockServer);
        String categories = joinJsonArrays(hit, "categories", "additional_categories", "display_categories", "features", "gameplay", "meta");
        return new ModrinthServerEntry(title, slug, projectId, type, address, directAddress, pageUrl, author, description, iconUrl, bannerUrl, updated, downloads, follows, verifiedPlays, playersOnline, playersMax, online, country, language, gameVersion, loader, compatibilityText, requiredContent, javaAddress, bedrockAddress, categories);
    }

    private String buildModrinthPageUrl(String slug, String projectId) {
        String key = slug == null || slug.isBlank() ? projectId : slug;
        if (key == null || key.isBlank()) {
            return "modrinth.com/servers";
        }
        return "modrinth.com/server/" + key;
    }

    private String buildDiscoveryLabel(ModrinthServerEntry discovery) {
        ArrayList<String> parts = new ArrayList<>();
        if (!discovery.online().isBlank()) {
            parts.add(discovery.online());
        }
        String players = joinPlayerCount(discovery.playersOnline(), discovery.playersMax());
        if (!players.equals("Unknown")) {
            parts.add(players + " players");
        }
        if (!discovery.gameVersion().isBlank()) {
            parts.add(discovery.gameVersion());
        }
        return parts.isEmpty() ? "Modrinth Server" : String.join(" | ", parts);
    }

    private String directJoinAddress(JsonObject hit) {
        String direct = firstAddressString(hit);
        if (!direct.isBlank()) {
            return direct;
        }
        for (String key : List.of("minecraft_java_server", "java_server", "java", "server", "connection", "connect", "address")) {
            direct = firstAddressString(firstJsonObject(hit, key));
            if (!direct.isBlank()) {
                return direct;
            }
        }
        return "";
    }

    private String firstAddressString(JsonObject object) {
        if (object == null) {
            return "";
        }
        for (String key : List.of("java_address", "javaAddress", "server_address", "serverAddress", "join_address", "joinAddress", "address", "host", "hostname", "ip", "domain")) {
            String value = firstJsonString(object, "", key);
            String normalized = normalizeServerAddress(value);
            if (!normalized.isBlank()) {
                return withDetectedPort(object, normalized);
            }
        }
        JsonObject nested = firstJsonObject(object, "address", "server", "java", "connection");
        if (nested != null && nested != object) {
            return firstAddressString(nested);
        }
        return "";
    }

    private String withDetectedPort(JsonObject object, String address) {
        if (address == null || address.isBlank() || address.contains(":")) {
            return address == null ? "" : address;
        }
        String port = firstJsonString(object, "", "port", "java_port", "javaPort", "server_port", "serverPort");
        if (port.isBlank() || port.equals("25565")) {
            return address;
        }
        return address + ":" + port;
    }

    private String normalizeServerAddress(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.trim();
        if (clean.startsWith("minecraft://")) {
            clean = clean.substring("minecraft://".length());
        }
        if (clean.startsWith("mc://")) {
            clean = clean.substring("mc://".length());
        }
        if (clean.startsWith("https://") || clean.startsWith("http://") || clean.contains("/") || !clean.contains(".")) {
            return "";
        }
        return clean;
    }

    private String buildCompatibilitySummary(JsonObject compatibility, JsonObject hit) {
        ArrayList<String> parts = new ArrayList<>();
        collectPart(parts, firstJsonString(compatibility, "", "type", "kind", "server_type", "serverType"));
        collectPart(parts, firstJsonString(compatibility, "", "game_mode", "gameMode", "mode"));
        collectPart(parts, firstJsonString(compatibility, "", "loader", "mod_loader", "modLoader"));
        collectPart(parts, joinJsonArray(compatibility, "versions"));
        collectPart(parts, joinJsonArray(hit, "versions"));
        return joinParts(parts);
    }

    private String buildRequiredContentSummary(JsonObject compatibility, JsonObject hit) {
        ArrayList<String> parts = new ArrayList<>();
        collectPart(parts, firstJsonString(compatibility, "", "required_project", "requiredProject", "required_pack", "requiredPack", "modpack", "pack", "content"));
        collectPart(parts, firstJsonString(hit, "", "required_project", "requiredProject", "required_pack", "requiredPack", "modpack", "pack"));
        JsonObject required = firstJsonObject(compatibility, "required_content", "requiredContent", "required_project", "requiredProject", "required_pack", "requiredPack", "modpack", "pack");
        if (required != null) {
            collectPart(parts, firstJsonString(required, "", "title", "name", "slug", "project_id", "projectId", "id"));
        }
        collectPart(parts, joinJsonArray(compatibility, "required_content"));
        collectPart(parts, joinJsonArray(hit, "required_content"));
        return joinParts(parts);
    }

    private void collectPart(List<String> parts, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!parts.contains(value)) {
            parts.add(value);
        }
    }

    private String joinParts(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        return String.join(" | ", parts);
    }

    private String joinPlayerCount(String online, String max) {
        boolean hasOnline = online != null && !online.isBlank();
        boolean hasMax = max != null && !max.isBlank();
        if (hasOnline && hasMax) {
            return online + " / " + max;
        }
        if (hasOnline) {
            return online + " online";
        }
        if (hasMax) {
            return "Max " + max;
        }
        return "Unknown";
    }

    private String normalizeOnlineState(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.equalsIgnoreCase("true")) {
            return "Online";
        }
        if (value.equalsIgnoreCase("false")) {
            return "Offline";
        }
        return value;
    }

    private void queueModrinthIconLoad(ServerInfo info, ModrinthServerEntry discovery) {
        if (info == null || discovery == null || discovery.iconUrl().isBlank() || info.getFavicon() != null) {
            return;
        }
        String key = discovery.textureKey();
        if (key.isBlank() || MODRINTH_ICON_FAILURES.contains(key) || !MODRINTH_ICON_REQUESTS.add(key)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(discovery.iconUrl()))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "SpiritXIV/Koil/" + com.spirit.Main.VERSION)
                        .GET()
                        .build();
                HttpResponse<byte[]> response = MODRINTH_HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                byte[] bytes = response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 300 || bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024) {
                    return null;
                }
                return bytes;
            } catch (Exception ignored) {
                return null;
            }
        }).whenComplete((bytes, error) -> {
            MODRINTH_ICON_REQUESTS.remove(key);
            if (bytes == null || error != null) {
                MODRINTH_ICON_FAILURES.add(key);
                return;
            }
            MinecraftClient minecraft = MinecraftClient.getInstance();
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                try {
                    info.setFavicon(bytes);
                } catch (Exception ignored) {
                    MODRINTH_ICON_FAILURES.add(key);
                }
            });
        });
    }

    private JsonArray firstJsonArray(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return new JsonArray();
        }
        for (String key : keys) {
            if (key == null || !object.has(key)) {
                continue;
            }
            JsonElement element = object.get(key);
            if (element != null && element.isJsonArray()) {
                return element.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    @Nullable
    private JsonObject firstJsonObject(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !object.has(key)) {
                continue;
            }
            JsonElement element = object.get(key);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private String firstJsonString(JsonObject object, String fallback, String... keys) {
        if (object == null || keys == null) {
            return fallback;
        }
        for (String key : keys) {
            String value = jsonString(object, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private String jsonString(JsonObject object, String key, String fallback) {
        if (object == null || key == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            return joinJsonArray(element.getAsJsonArray());
        }
        if (element.isJsonObject()) {
            JsonObject nested = element.getAsJsonObject();
            return firstJsonString(nested, fallback, "title", "name", "slug", "id", "project_id", "projectId", "value");
        }
        return fallback;
    }

    private String joinJsonArrays(JsonObject object, String... keys) {
        ArrayList<String> parts = new ArrayList<>();
        if (object == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = joinJsonArray(object, key);
            if (!value.isBlank()) {
                for (String part : value.split(" ")) {
                    collectPart(parts, part.trim());
                }
            }
        }
        return joinParts(parts).replace(" | ", " ");
    }

    private String joinJsonArray(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) {
            return "";
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonArray()) {
            return joinJsonArray(element.getAsJsonArray());
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            return firstJsonString(element.getAsJsonObject(), "", "title", "name", "slug", "id", "project_id", "projectId", "value");
        }
        return "";
    }

    private String joinJsonArray(JsonArray array) {
        ArrayList<String> parts = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive()) {
                collectPart(parts, element.getAsString());
            } else if (element.isJsonObject()) {
                String value = firstJsonString(element.getAsJsonObject(), "", "title", "name", "slug", "id", "project_id", "projectId", "value");
                collectPart(parts, value);
            }
        }
        return String.join(" ", parts);
    }

    private record ModrinthServerResult(ServerList list, Map<String, ModrinthServerEntry> metadata, Map<String, ModrinthServerEntry> projectMetadata) {
    }

    private record ModrinthServerEntry(String title, String slug, String projectId, String type, String address, String directAddress,
                                       String pageUrl, String author, String description, String iconUrl, String bannerUrl,
                                       String updated, String downloads, String follows, String verifiedPlays, String playersOnline, String playersMax,
                                       String online, String country, String language, String gameVersion, String loader,
                                       String compatibility, String requiredContent, String javaAddress, String bedrockAddress,
                                       String categories) {
        private boolean hasDirectJoinAddress() {
            return directAddress != null && !directAddress.isBlank();
        }

        private String textureKey() {
            if (projectId != null && !projectId.isBlank()) {
                return projectId;
            }
            if (slug != null && !slug.isBlank()) {
                return slug;
            }
            return address == null ? "unknown" : address;
        }
    }
}
