package com.spirit.client.gui.video;

import com.spirit.koil.api.performance.PerformanceMonitor;
import com.spirit.koil.api.performance.PerformanceProfileMode;
import com.spirit.koil.api.performance.PerformanceRecommendation;
import com.spirit.koil.api.performance.PerformanceRecommendationEngine;
import com.spirit.koil.api.performance.PerformanceSnapshot;
import com.spirit.mixin.client.gui.revamp.accessor.EntryListWidgetAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.OptionListWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.awt.Color;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.spirit.Main.SUBLOGGER;
import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class KoilVideoOptionsScreen extends VideoOptionsScreen {
    private static final long PERFORMANCE_REFRESH_INTERVAL_MS = 1250L;
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int COLUMN_GAP = 10;
    private static final Gson CONFIG_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String[] SODIUM_SCREEN_CLASS_NAMES = {
            "me.flashyreese.mods.reeses_sodium_options.client.gui.SodiumVideoOptionsScreen",
            "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI",
            "net.caffeinemc.mods.sodium.client.gui.SodiumOptions",
            "me.jellysquid.mods.sodium.client.gui.SodiumOptions",
            "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen",
            "me.jellysquid.mods.sodium.client.gui.VideoSettingsScreen"};
    private static final String[] SODIUM_PAGE_PROVIDER_CLASS_NAMES = {
            "me.flashyreese.mods.sodiumextra.client.gui.SodiumExtraGameOptionPages",
            "me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages",
            "net.caffeinemc.mods.sodium.client.gui.SodiumGameOptionPages",
            "net.caffeinemc.mods.sodium.client.gui.options.SodiumGameOptionPages",
            "me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages",
            "me.jellysquid.mods.sodium.client.gui.options.SodiumGameOptionPages"
    };

    private final Map<String, PerformanceRecommendation> recommendationsByKey = new LinkedHashMap<>();
    private final List<PerformanceRecommendation> latestRecommendations = new ArrayList<>();
    private final List<ClickableWidget> combinedOptionWidgets = new ArrayList<>();
    private final List<ClickableWidget> vanillaOptionWidgets = new ArrayList<>();
    private final List<ClickableWidget> sodiumWidgets = new ArrayList<>();
    private final Map<ClickableWidget, String> sodiumWidgetLabels = new IdentityHashMap<>();
    private final Map<ClickableWidget, ConfigFallbackEntry> sodiumConfigEntries = new IdentityHashMap<>();
    private final Set<ClickableWidget> dirtyConfigWidgets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<HintHitbox> hintHitboxes = new ArrayList<>();
    private Screen sodiumScreen;
    private ClickableWidget activeSodiumWidget;
    private double sodiumScroll;
    private int sodiumContentHeight;
    private int sodiumListLeft;
    private int sodiumListTop;
    private int sodiumListBottom;
    private int sodiumListWidth;
    private boolean sodiumScrollbarDragging;
    private int sodiumScrollbarDragOffset;
    private boolean sodiumIntegratedIntoOptionList;
    private long lastPerformanceRefreshMillis;
    private boolean sodiumDiagnosticLogged;

    public KoilVideoOptionsScreen(Screen parent) {
        super(parent, MinecraftClient.getInstance().options);
    }

    @Override
    protected void init() {
        super.init();
        refreshPerformanceHints(true);
        hideShaderPackButtons();
        installSodiumOptionWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        refreshPerformanceHints(false);
        hideShaderPackButtons();
        if (this.sodiumScreen != null) {
            this.sodiumScreen.tick();
        }
        for (ClickableWidget widget : this.sodiumWidgets) {
            if (widget instanceof TextFieldWidget textField) {
                textField.tick();
            }
        }
    }

    @Override
    public void removed() {
        saveSodiumConfigChanges();
        if (this.sodiumScreen != null) {
            applySodiumChanges();
            this.sodiumScreen.removed();
            this.sodiumScreen = null;
        }
        this.activeSodiumWidget = null;
        super.removed();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateSodiumWidgetLayout();
        super.render(context, mouseX, mouseY, delta);
        hideShaderPackButtons();
        renderSodiumWidgets(context, mouseX, mouseY, delta);
        renderHintBadges(context, mouseX, mouseY);
        renderHintTooltip(context, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        updateSodiumWidgetLayout();
        if (button == 0 && maxSodiumScroll() > 0 && isOverSodiumScrollbar(mouseX, mouseY)) {
            int thumbY = sodiumScrollbarThumbY();
            int thumbHeight = sodiumScrollbarThumbHeight();
            this.sodiumScrollbarDragging = true;
            if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
                this.sodiumScrollbarDragOffset = (int) mouseY - thumbY;
            } else {
                this.sodiumScrollbarDragOffset = thumbHeight / 2;
                setSodiumScrollFromThumbTop((int) mouseY - this.sodiumScrollbarDragOffset);
            }
            updateSodiumWidgetLayout();
            return true;
        }
        if (inside(mouseX, mouseY, this.sodiumListLeft - 8, this.sodiumListTop, this.sodiumListWidth + 16, this.sodiumListBottom - this.sodiumListTop)) {
            for (int i = this.combinedOptionWidgets.size() - 1; i >= 0; i--) {
                ClickableWidget widget = this.combinedOptionWidgets.get(i);
                if (!widget.visible || !widget.active || !inside(mouseX, mouseY, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight())) {
                    continue;
                }
                this.activeSodiumWidget = widget;
                if (widget.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.sodiumScrollbarDragging) {
            this.sodiumScrollbarDragging = false;
            return true;
        }
        if (this.activeSodiumWidget != null) {
            ClickableWidget widget = this.activeSodiumWidget;
            this.activeSodiumWidget = null;
            if (widget.mouseReleased(mouseX, mouseY, button)) {
                return true;
            }
        }
        for (ClickableWidget widget : this.combinedOptionWidgets) {
            if (widget.visible && widget.mouseReleased(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.sodiumScrollbarDragging) {
            setSodiumScrollFromThumbTop((int) mouseY - this.sodiumScrollbarDragOffset);
            updateSodiumWidgetLayout();
            return true;
        }
        if (this.activeSodiumWidget != null && this.activeSodiumWidget.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        updateSodiumWidgetLayout();
        if (maxSodiumScroll() > 0 && inside(mouseX, mouseY, this.sodiumListLeft - 8, this.sodiumListTop, this.sodiumListWidth + 16, this.sodiumListBottom - this.sodiumListTop)) {
            this.sodiumScroll = clamp(this.sodiumScroll - amount * ROW_HEIGHT, 0.0D, maxSodiumScroll());
            updateSodiumWidgetLayout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.activeSodiumWidget != null && this.activeSodiumWidget.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        for (ClickableWidget widget : this.combinedOptionWidgets) {
            if (widget.visible && widget.isFocused() && widget.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (this.activeSodiumWidget != null && this.activeSodiumWidget.charTyped(chr, modifiers)) {
            return true;
        }
        for (ClickableWidget widget : this.combinedOptionWidgets) {
            if (widget.visible && widget.isFocused() && widget.charTyped(chr, modifiers)) {
                return true;
            }
        }
        return super.charTyped(chr, modifiers);
    }

    private void refreshPerformanceHints(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - this.lastPerformanceRefreshMillis < PERFORMANCE_REFRESH_INTERVAL_MS) {
            return;
        }
        this.lastPerformanceRefreshMillis = now;
        MinecraftClient client = MinecraftClient.getInstance();
        PerformanceSnapshot snapshot = PerformanceMonitor.freshSnapshot(client);
        List<PerformanceRecommendation> freshRecommendations = PerformanceRecommendationEngine.recommend(client, PerformanceProfileMode.AUTO, snapshot);
        this.latestRecommendations.clear();
        this.latestRecommendations.addAll(freshRecommendations);
        this.recommendationsByKey.clear();
        for (PerformanceRecommendation recommendation : freshRecommendations) {
            if (recommendation == null) {
                continue;
            }
            addRecommendationKey(recommendation.settingKey(), recommendation);
            addRecommendationKey(labelFromRecommendation(recommendation), recommendation);
        }
    }

    private void installSodiumOptionWidgets() {
        removeOldSodiumWidgets();
        this.vanillaOptionWidgets.addAll(collectVanillaVideoOptionWidgets());
        if (!FabricLoader.getInstance().isModLoaded("sodium")) {
            logSodiumInfo("Sodium is not loaded; Koil video options will only show vanilla video settings.");
            installCombinedVideoOptionList();
            return;
        }
        this.sodiumScreen = createSodiumScreen();
        if (this.client == null) {
            logSodiumError("Cannot attach Sodium options because MinecraftClient is not available.", null);
            return;
        }
        if (this.sodiumScreen != null) {
            try {
                this.sodiumScreen.init(this.client, this.width, this.height);
            } catch (Throwable throwable) {
                logSodiumError("Sodium screen was found but failed to initialize. Falling back to direct option-page capture.", throwable);
                this.sodiumScreen = null;
            }
        } else {
            logSodiumWarning("No Sodium video screen class could be created. Trying direct Sodium/Sodium Extra page providers.");
        }

        Set<String> vanillaKeys = new LinkedHashSet<>();
        for (ClickableWidget widget : collectCurrentScreenWidgets()) {
            String label = cleanLabel(widget.getMessage() == null ? "" : widget.getMessage().getString());
            if (!label.isBlank()) {
                vanillaKeys.add(normalize(stripValue(label)));
            }
        }

        List<NativeCapture> captures = this.sodiumScreen == null ? collectDirectSodiumWidgetsOnly() : collectSodiumWidgetsAcrossPages(this.sodiumScreen);
        logSodiumInfo("Captured " + captures.size() + " raw Sodium-compatible video option widgets/pages before filtering.");
        captures.sort(Comparator
                .comparing((NativeCapture capture) -> capture.pageName() == null ? "" : capture.pageName())
                .thenComparingInt(capture -> capture.widget().getY())
                .thenComparingInt(capture -> capture.widget().getX())
                .thenComparing(capture -> captureLabel(capture)));

        Set<String> added = new LinkedHashSet<>();
        List<NativeCapture> capturesToAdd = new ArrayList<>();
        for (NativeCapture capture : captures) {
            ClickableWidget widget = capture.widget();
            String label = captureLabel(capture);
            String base = stripValue(label);
            String fallbackKey = widget.getClass().getName() + "@" + widget.getX() + "," + widget.getY();
            String key = normalize(capture.pageName()) + ":" + normalize(base.isBlank() ? fallbackKey : base);
            if (shouldHideWidget(label, widget) || isSodiumPageSelector(label, capture.pageNames())) {
                continue;
            }
            if (!base.isBlank() && vanillaKeys.contains(normalize(base))) {
                continue;
            }
            if (!added.add(key)) {
                continue;
            }
            capturesToAdd.add(capture);
            this.sodiumWidgetLabels.put(widget, label);
        }

        addSodiumConfigFallbackCaptures(capturesToAdd, added, vanillaKeys, capturesToAdd.isEmpty());
        logSodiumInfo("Prepared " + capturesToAdd.size() + " Sodium-compatible video option widgets after filtering and config fallback.");

        for (NativeCapture capture : capturesToAdd) {
            ClickableWidget widget = capture.widget();
            try {
                widget.visible = true;
                this.sodiumWidgets.add(widget);
            } catch (Throwable throwable) {
                logSodiumError("Failed to prepare Sodium option widget '" + captureLabel(capture) + "' for rendering.", throwable);
            }
        }
        installCombinedVideoOptionList();
    }

    private void removeOldSodiumWidgets() {
        for (ClickableWidget widget : this.combinedOptionWidgets) {
            try {
                this.remove(widget);
            } catch (Throwable ignored) {
            }
        }
        this.combinedOptionWidgets.clear();
        this.vanillaOptionWidgets.clear();
        this.sodiumWidgets.clear();
        this.sodiumWidgetLabels.clear();
        this.sodiumConfigEntries.clear();
        this.dirtyConfigWidgets.clear();
        this.sodiumIntegratedIntoOptionList = false;
        this.sodiumDiagnosticLogged = false;
    }

    private void installCombinedVideoOptionList() {
        detachVanillaOptionList();
        this.combinedOptionWidgets.clear();
        for (ClickableWidget widget : this.vanillaOptionWidgets) {
            if (!this.combinedOptionWidgets.contains(widget)) {
                this.combinedOptionWidgets.add(widget);
            }
        }
        for (ClickableWidget widget : this.sodiumWidgets) {
            if (!this.combinedOptionWidgets.contains(widget)) {
                this.combinedOptionWidgets.add(widget);
            }
        }
        this.sodiumIntegratedIntoOptionList = false;
        layoutSodiumWidgets(this.combinedOptionWidgets);
        logSodiumInfo("Installed combined video option list with " + this.vanillaOptionWidgets.size() + " vanilla widgets and " + this.sodiumWidgets.size() + " Sodium-compatible widgets.");
    }

    private void applySodiumChanges() {
        if (this.sodiumScreen == null) {
            return;
        }
        if (invokeBooleanLikeNoArg(this.sodiumScreen, "applyChanges", "apply", "save", "writeChanges", "flushChanges")) {
            return;
        }
        for (ClickableWidget widget : collectClickableWidgets(this.sodiumScreen)) {
            String label = cleanLabel(widget.getMessage() == null ? "" : widget.getMessage().getString());
            String normalized = normalize(label);
            if (!normalized.equals("apply") && !normalized.equals("save")) {
                continue;
            }
            try {
                double clickX = widget.getX() + Math.max(1, widget.getWidth() / 2.0D);
                double clickY = widget.getY() + Math.max(1, widget.getHeight() / 2.0D);
                widget.mouseClicked(clickX, clickY, 0);
                widget.mouseReleased(clickX, clickY, 0);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean invokeBooleanLikeNoArg(Object owner, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = owner.getClass().getDeclaredMethod(methodName);
                if (method.getParameterCount() != 0) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(owner);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void logSodiumInfo(String message) {
        if (!this.sodiumDiagnosticLogged) {
            this.sodiumDiagnosticLogged = true;
        }
        SUBLOGGER.logI("Video Options", "[Sodium attach] " + message);
    }

    private void logSodiumWarning(String message) {
        this.sodiumDiagnosticLogged = true;
        SUBLOGGER.logW("Video Options", "[Sodium attach] " + message);
    }

    private void logSodiumError(String message, Throwable throwable) {
        this.sodiumDiagnosticLogged = true;
        String detail = throwable == null ? "" : " (" + throwable.getClass().getSimpleName() + ": " + throwable.getMessage() + ")";
        SUBLOGGER.logE("Video Options", "[Sodium attach] " + message + detail);
    }

    private Screen createSodiumScreen() {
        List<String> failures = new ArrayList<>();
        for (String className : SODIUM_SCREEN_CLASS_NAMES) {
            try {
                Class<?> screenClass = Class.forName(className);
                logSodiumInfo("Found Sodium screen candidate " + className + ".");
                for (Method method : safeMethods(screenClass)) {
                    if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1 || method.getParameterTypes()[0] != Screen.class) {
                        continue;
                    }
                    String name = method.getName().toLowerCase(Locale.ROOT);
                    if (!name.contains("screen") && !name.contains("create")) {
                        continue;
                    }
                    method.setAccessible(true);
                    Object screen = method.invoke(null, this);
                    if (screen instanceof Screen cast) {
                        logSodiumInfo("Created Sodium screen through " + className + "#" + method.getName() + ".");
                        return cast;
                    }
                }
                Constructor<?> constructor = screenClass.getDeclaredConstructor(Screen.class);
                constructor.setAccessible(true);
                Object screen = constructor.newInstance(this);
                if (screen instanceof Screen cast) {
                    logSodiumInfo("Created Sodium screen through " + className + "(Screen).");
                    return cast;
                }
            } catch (ClassNotFoundException ignored) {
                failures.add(className + " missing");
            } catch (Throwable throwable) {
                failures.add(className + " failed: " + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            }
        }
        logSodiumWarning("Could not create a Sodium screen. Tried: " + String.join("; ", failures));
        return null;
    }

    private List<NativeCapture> collectDirectSodiumWidgetsOnly() {
        Set<String> pageNames = defaultSodiumPageNames();
        List<NativeCapture> captures = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addDirectSodiumOptionPageControls(captures, seen, pageNames);
        return captures;
    }

    private Set<String> defaultSodiumPageNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("General");
        names.add("Quality");
        names.add("Performance");
        names.add("Advanced");
        names.add("Animations");
        names.add("Details");
        names.add("Extras");
        names.add("Other");
        return names;
    }

    private List<NativeCapture> collectSodiumWidgetsAcrossPages(Screen screen) {
        Set<String> pageNames = discoverSodiumPageNames(screen);
        List<NativeCapture> captures = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addSodiumCaptures(captures, seen, collectClickableWidgets(screen), "", pageNames);
        addSodiumOptionPageControls(captures, seen, screen, pageNames);
        addDirectSodiumOptionPageControls(captures, seen, pageNames);

        List<ClickableWidget> selectors = collectClickableWidgets(screen);
        for (ClickableWidget selector : selectors) {
            String label = cleanLabel(selector.getMessage() == null ? "" : selector.getMessage().getString());
            if (!isSodiumPageSelector(label, pageNames)) {
                continue;
            }
            try {
                double clickX = selector.getX() + Math.max(1, selector.getWidth() / 2.0D);
                double clickY = selector.getY() + Math.max(1, selector.getHeight() / 2.0D);
                selector.mouseClicked(clickX, clickY, 0);
                selector.mouseReleased(clickX, clickY, 0);
                screen.tick();
            } catch (Throwable ignored) {
            }
            addSodiumCaptures(captures, seen, collectClickableWidgets(screen), stripValue(label), pageNames);
            addSodiumOptionPageControls(captures, seen, screen, pageNames);
            addDirectSodiumOptionPageControls(captures, seen, pageNames);
        }
        return captures;
    }

    private void addSodiumCaptures(List<NativeCapture> captures, Set<String> seen, List<ClickableWidget> widgets, String pageName, Set<String> pageNames) {
        for (ClickableWidget widget : widgets) {
            String label = cleanLabel(widget.getMessage() == null ? "" : widget.getMessage().getString());
            if (shouldHideWidget(label, widget) || isSodiumPageSelector(label, pageNames)) {
                continue;
            }
            String keyBase = stripValue(label).isBlank() ? widget.getClass().getName() + "@" + widget.getX() + "," + widget.getY() : stripValue(label);
            String key = normalize(pageName) + ":" + normalize(keyBase);
            if (seen.add(key)) {
                captures.add(new NativeCapture(widget, pageName, pageNames, label));
            }
        }
    }

    private void addSodiumOptionPageControls(List<NativeCapture> captures, Set<String> seen, Screen screen, Set<String> pageNames) {
        List<Object> pages = collectLikelyOptionPages(screen);
        int syntheticIndex = 0;
        for (Object page : pages) {
            String pageName = readPageName(page);
            List<Object> options = readOptionsFromPage(page);
            for (Object option : options) {
                String label = readOptionName(option);
                if (label.isBlank()) {
                    label = "Sodium Option " + syntheticIndex;
                }
                if (shouldHideWidget(label, null) || isSodiumPageSelector(label, pageNames)) {
                    syntheticIndex++;
                    continue;
                }
                String key = normalize(pageName) + ":" + normalize(label);
                if (!seen.add(key)) {
                    syntheticIndex++;
                    continue;
                }
                ClickableWidget widget = createWidgetFromSodiumOption(option, 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT);
                if (widget != null) {
                    captures.add(new NativeCapture(widget, pageName, pageNames, label));
                } else {
                    logSodiumWarning("Could not create a vanilla-compatible widget for Sodium option '" + label + "' from page '" + pageName + "'.");
                }
                syntheticIndex++;
            }
        }
    }


    private void addDirectSodiumOptionPageControls(List<NativeCapture> captures, Set<String> seen, Set<String> pageNames) {
        int syntheticIndex = 0;
        List<Object> pages = collectDirectSodiumOptionPages();
        logSodiumInfo("Direct Sodium page provider capture returned " + pages.size() + " pages.");
        for (Object page : pages) {
            String pageName = readPageName(page);
            if (pageName.isBlank()) {
                pageName = "Sodium";
            }
            for (Object option : readOptionsFromPage(page)) {
                String label = readOptionName(option);
                if (label.isBlank()) {
                    label = pageName + " Option " + syntheticIndex;
                }
                if (shouldHideWidget(label, null) || isSodiumPageSelector(label, pageNames)) {
                    syntheticIndex++;
                    continue;
                }
                String key = normalize(pageName) + ":" + normalize(label);
                if (!seen.add(key)) {
                    syntheticIndex++;
                    continue;
                }
                ClickableWidget widget = createWidgetFromSodiumOption(option, 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT);
                if (widget != null) {
                    captures.add(new NativeCapture(widget, pageName, pageNames, label));
                } else {
                    logSodiumWarning("Could not create a vanilla-compatible widget for direct Sodium option '" + label + "' from page '" + pageName + "'.");
                }
                syntheticIndex++;
            }
        }
    }

    private List<Object> collectDirectSodiumOptionPages() {
        List<Object> pages = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> providerFailures = new ArrayList<>();
        for (String className : SODIUM_PAGE_PROVIDER_CLASS_NAMES) {
            Class<?> providerClass;
            try {
                providerClass = Class.forName(className);
                logSodiumInfo("Found Sodium page provider " + className + ".");
            } catch (ClassNotFoundException ignored) {
                providerFailures.add(className + " missing");
                continue;
            } catch (Throwable throwable) {
                providerFailures.add(className + " failed to load: " + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
                continue;
            }
            for (Method method : safeMethods(providerClass)) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("page")
                        && !name.equals("general")
                        && !name.equals("quality")
                        && !name.equals("performance")
                        && !name.equals("advanced")
                        && !name.equals("animation")
                        && !name.equals("particle")
                        && !name.equals("detail")
                        && !name.equals("render")
                        && !name.equals("extra")) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    addDirectPagesFrom(method.invoke(null), pages, seen);
                } catch (Throwable throwable) {
                    logSodiumError("Failed to read Sodium page provider method " + providerClass.getName() + "#" + method.getName() + ".", throwable);
                }
            }
            for (Field field : safeFields(providerClass)) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    addDirectPagesFrom(field.get(null), pages, seen);
                } catch (Throwable throwable) {
                    logSodiumError("Failed to read Sodium page provider field " + providerClass.getName() + "#" + field.getName() + ".", throwable);
                }
            }
        }
        if (pages.isEmpty()) {
            logSodiumWarning("No direct Sodium option pages were captured. Provider status: " + String.join("; ", providerFailures));
        }
        return pages;
    }

    private void addDirectPagesFrom(Object value, List<Object> pages, Set<Object> seen) {
        if (value == null || seen.contains(value)) {
            return;
        }
        seen.add(value);
        if (isLikelyOptionPage(value)) {
            pages.add(value);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addDirectPagesFrom(item, pages, seen);
            }
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addDirectPagesFrom(Array.get(value, i), pages, seen);
            }
        }
    }

    private List<Object> collectLikelyOptionPages(Object root) {
        List<Object> pages = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectLikelyOptionPagesFrom(root, visited, pages, 0);
        return pages;
    }

    private void collectLikelyOptionPagesFrom(Object value, Set<Object> visited, List<Object> pages, int depth) {
        if (value == null || depth > 8 || visited.contains(value)) {
            return;
        }
        visited.add(value);
        if (isLikelyOptionPage(value) && !pages.contains(value)) {
            pages.add(value);
        }
        if (value instanceof MinecraftClient || value == this || value == this.client) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectLikelyOptionPagesFrom(item, visited, pages, depth + 1);
            }
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectLikelyOptionPagesFrom(Array.get(value, i), visited, pages, depth + 1);
            }
            return;
        }
        if (shouldSkipClass(valueClass)) {
            return;
        }
        for (Class<?> type = valueClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || shouldSkipField(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collectLikelyOptionPagesFrom(field.get(value), visited, pages, depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private List<Object> readOptionsFromPage(Object page) {
        List<Object> options = new ArrayList<>();
        List<Object> groups = invokeObjectList(page, "getGroups", "groups", "getOptionGroups", "optionGroups");
        for (Object group : groups) {
            for (Object option : invokeObjectList(group, "getOptions", "options", "getOptionsList")) {
                if (isLikelyOptionObject(option) && !options.contains(option)) {
                    options.add(option);
                }
            }
        }
        if (options.isEmpty()) {
            collectOptionObjects(page, Collections.newSetFromMap(new IdentityHashMap<>()), options, 0);
        }
        return options;
    }

    private void collectOptionObjects(Object value, Set<Object> visited, List<Object> options, int depth) {
        if (value == null || depth > 5 || visited.contains(value)) {
            return;
        }
        visited.add(value);
        if (isLikelyOptionObject(value) && !options.contains(value)) {
            options.add(value);
            return;
        }
        if (value instanceof MinecraftClient || value == this || value == this.client) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectOptionObjects(item, visited, options, depth + 1);
            }
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectOptionObjects(Array.get(value, i), visited, options, depth + 1);
            }
            return;
        }
        if (shouldSkipClass(valueClass)) {
            return;
        }
        for (Class<?> type = valueClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || shouldSkipField(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collectOptionObjects(field.get(value), visited, options, depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean isLikelyOptionObject(Object value) {
        if (value == null) {
            return false;
        }
        String className = value.getClass().getName().toLowerCase(Locale.ROOT);
        if (!className.contains("option")) {
            return false;
        }
        return invokeObject(value, "getControl", "control") != null || hasNoArgMethod(value, "getName") || hasNoArgMethod(value, "getTitle");
    }

    private ClickableWidget createWidgetFromSodiumOption(Object option, int x, int y, int width, int height) {
        Object control = invokeObject(option, "getControl", "control");
        if (control == null) {
            logSodiumWarning("Skipping Sodium option '" + readOptionName(option) + "' because it has no readable control object.");
            return null;
        }
        ClickableWidget widget = createVanillaBackedSodiumWidget(option, control, x, y, width, height);
        if (widget == null) {
            Object result = invokeCreateElement(control, x, y, width, height);
            widget = firstClickableWidget(result);
        }
        if (widget == null) {
            logSodiumWarning("Sodium option '" + readOptionName(option) + "' uses unsupported control " + control.getClass().getName() + ".");
        }
        if (widget != null) {
            String label = readOptionName(option);
            if (!label.isBlank() && (widget.getMessage() == null || widget.getMessage().getString().isBlank())) {
                try {
                    widget.setMessage(Text.literal(label));
                } catch (Throwable ignored) {
                }
            }
        }
        return widget;
    }

    private ClickableWidget createVanillaBackedSodiumWidget(Object option, Object control, int x, int y, int width, int height) {
        if (!sodiumOptionAvailable(option)) {
            return null;
        }
        Object value = sodiumOptionValue(option);
        String controlClass = control.getClass().getName().toLowerCase(Locale.ROOT);
        if (value instanceof Boolean) {
            return ButtonWidget.builder(sodiumOptionButtonText(option, value), button -> {
                Object current = sodiumOptionValue(option);
                boolean next = !(current instanceof Boolean bool && bool);
                setSodiumOptionValue(option, next);
                applySodiumOptionNow(option);
                button.setMessage(sodiumOptionButtonText(option, next));
            }).dimensions(x, y, width, height).build();
        }
        if (value instanceof Enum<?> enumValue) {
            Object[] allowedValues = sodiumAllowedValues(control, enumValue.getClass());
            Text[] names = sodiumAllowedNames(control);
            return ButtonWidget.builder(sodiumOptionButtonText(option, value), button -> {
                Object current = sodiumOptionValue(option);
                Object next = nextAllowedValue(allowedValues, current);
                setSodiumOptionValue(option, next);
                applySodiumOptionNow(option);
                button.setMessage(sodiumOptionButtonText(option, next, allowedValues, names));
            }).dimensions(x, y, width, height).build();
        }
        if (value instanceof Integer integerValue && controlClass.contains("slider")) {
            int min = readIntField(control, "min", Math.max(0, integerValue - 10));
            int max = readIntField(control, "max", Math.max(min + 1, integerValue + 10));
            int interval = Math.max(1, readIntField(control, "interval", 1));
            Object formatter = readObjectField(control, "mode");
            return new SodiumIntegerSliderWidget(x, y, width, height, option, min, max, interval, formatter);
        }
        return ButtonWidget.builder(sodiumOptionButtonText(option, value), button -> {
            applySodiumOptionNow(option);
            button.setMessage(sodiumOptionButtonText(option, sodiumOptionValue(option)));
        }).dimensions(x, y, width, height).build();
    }

    private Text sodiumOptionButtonText(Object option, Object value) {
        return sodiumOptionButtonText(option, value, sodiumAllowedValues(invokeObject(option, "getControl", "control"), value instanceof Enum<?> enumValue ? enumValue.getClass() : null), sodiumAllowedNames(invokeObject(option, "getControl", "control")));
    }

    private Text sodiumOptionButtonText(Object option, Object value, Object[] allowedValues, Text[] names) {
        String label = readOptionName(option);
        String valueText = sodiumValueText(value, allowedValues, names);
        return Text.literal(label.isBlank() ? valueText : label + ": " + valueText);
    }

    private String sodiumValueText(Object value, Object[] allowedValues, Text[] names) {
        if (value == null) {
            return "unknown";
        }
        if (value instanceof Boolean bool) {
            return bool ? "ON" : "OFF";
        }
        if (allowedValues != null && names != null) {
            for (int i = 0; i < allowedValues.length && i < names.length; i++) {
                if (Objects.equals(allowedValues[i], value) && names[i] != null) {
                    return names[i].getString();
                }
            }
        }
        if (value instanceof Enum<?> enumValue) {
            return humanizeKey(enumValue.name());
        }
        return String.valueOf(value);
    }

    private boolean sodiumOptionAvailable(Object option) {
        try {
            Method method = option.getClass().getMethod("isAvailable");
            Object value = method.invoke(option);
            return !(value instanceof Boolean bool) || bool;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private Object sodiumOptionValue(Object option) {
        return invokeObject(option, "getValue", "value");
    }

    private void setSodiumOptionValue(Object option, Object value) {
        for (Method method : safeMethods(option.getClass())) {
            String name = method.getName();
            if (!name.equals("setValue") || method.getParameterCount() != 1) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(option, value);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private void applySodiumOptionNow(Object option) {
        try {
            Method method = option.getClass().getMethod("applyChanges");
            method.invoke(option);
        } catch (Throwable ignored) {
        }
        Object storage = invokeObject(option, "getStorage", "storage");
        if (storage != null) {
            try {
                Method save = storage.getClass().getMethod("save");
                save.invoke(storage);
            } catch (Throwable ignored) {
            }
        }
    }

    private Object[] sodiumAllowedValues(Object control, Class<?> fallbackEnumClass) {
        Object values = readObjectField(control, "allowedValues");
        if (values != null && values.getClass().isArray()) {
            int length = Array.getLength(values);
            Object[] array = new Object[length];
            for (int i = 0; i < length; i++) {
                array[i] = Array.get(values, i);
            }
            return array;
        }
        if (fallbackEnumClass != null && fallbackEnumClass.isEnum()) {
            return fallbackEnumClass.getEnumConstants();
        }
        return new Object[0];
    }

    private Text[] sodiumAllowedNames(Object control) {
        Object values = readObjectField(control, "names");
        if (values != null && values.getClass().isArray()) {
            int length = Array.getLength(values);
            Text[] array = new Text[length];
            for (int i = 0; i < length; i++) {
                Object value = Array.get(values, i);
                if (value instanceof Text text) {
                    array[i] = text;
                }
            }
            return array;
        }
        return new Text[0];
    }

    private Object nextAllowedValue(Object[] allowedValues, Object current) {
        if (allowedValues == null || allowedValues.length == 0) {
            return current;
        }
        for (int i = 0; i < allowedValues.length; i++) {
            if (Objects.equals(allowedValues[i], current)) {
                return allowedValues[(i + 1) % allowedValues.length];
            }
        }
        return allowedValues[0];
    }

    private Object readObjectField(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String formatSodiumSliderValue(Object formatter, int value) {
        if (formatter != null) {
            try {
                Method method = formatter.getClass().getMethod("format", int.class);
                Object result = method.invoke(formatter, value);
                if (result instanceof Text text) {
                    return text.getString();
                }
                if (result != null) {
                    return String.valueOf(result);
                }
            } catch (Throwable ignored) {
            }
        }
        return String.valueOf(value);
    }

    private Object invokeCreateElement(Object control, int x, int y, int width, int height) {
        for (Method method : safeMethods(control.getClass())) {
            String name = method.getName();
            if (!name.equals("createElement") && !name.equals("createWidget") && !name.equals("createControl")) {
                continue;
            }
            try {
                method.setAccessible(true);
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    Object dim = createDim(parameterTypes[0], x, y, width, height);
                    if (dim != null) {
                        return method.invoke(control, dim);
                    }
                }
                if (parameterTypes.length == 2) {
                    Object dim = createDim(parameterTypes[1], x, y, width, height);
                    Object option = invokeObject(control, "getOption", "option");
                    if (dim != null && option != null && parameterTypes[0].isInstance(option)) {
                        return method.invoke(control, option, dim);
                    }
                }
                if (parameterTypes.length == 4
                        && parameterTypes[0] == int.class
                        && parameterTypes[1] == int.class
                        && parameterTypes[2] == int.class
                        && parameterTypes[3] == int.class) {
                    return method.invoke(control, x, y, width, height);
                }
                if (parameterTypes.length == 5
                        && parameterTypes[1] == int.class
                        && parameterTypes[2] == int.class
                        && parameterTypes[3] == int.class
                        && parameterTypes[4] == int.class) {
                    Object option = invokeObject(control, "getOption", "option");
                    if (option != null && parameterTypes[0].isInstance(option)) {
                        return method.invoke(control, option, x, y, width, height);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object createDim(Class<?> parameterType, int x, int y, int width, int height) {
        String name = parameterType.getName().toLowerCase(Locale.ROOT);
        if (!name.contains("dim2i") && !name.contains("rect") && !name.contains("bounds")) {
            return null;
        }
        try {
            Constructor<?> constructor = parameterType.getDeclaredConstructor(int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(x, y, width, height);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private ClickableWidget firstClickableWidget(Object root) {
        List<ClickableWidget> widgets = new ArrayList<>();
        collectClickableWidgetsFrom(root, Collections.newSetFromMap(new IdentityHashMap<>()), widgets, 0);
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private List<Object> invokeObjectList(Object owner, String... methodNames) {
        List<Object> values = new ArrayList<>();
        for (String methodName : methodNames) {
            Object result = invokeObject(owner, methodName);
            if (result instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    values.add(item);
                }
            } else if (result != null && result.getClass().isArray()) {
                int length = Array.getLength(result);
                for (int i = 0; i < length; i++) {
                    values.add(Array.get(result, i));
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        return values;
    }

    private Object invokeObject(Object owner, String... methodNames) {
        if (owner == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                Method method = owner.getClass().getMethod(methodName);
                if (method.getParameterCount() != 0) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(owner);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean hasNoArgMethod(Object owner, String methodName) {
        if (owner == null) {
            return false;
        }
        try {
            Method method = owner.getClass().getMethod(methodName);
            return method.getParameterCount() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }


    private void addSodiumConfigFallbackCaptures(List<NativeCapture> captures, Set<String> seen, Set<String> vanillaKeys, boolean includeCoreSodiumConfigs) {
        Set<String> pageNames = defaultSodiumPageNames();
        int index = 0;
        List<Path> paths = sodiumConfigPaths();
        logSodiumInfo("Sodium-compatible config fallback scan found " + paths.size() + " config files. Core Sodium configs included=" + includeCoreSodiumConfigs + ".");
        for (Path path : paths) {
            String fileName = path.getFileName().toString();
            String configName = configDisplayName(fileName);
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (!includeCoreSodiumConfigs && isCoreSodiumConfigFile(lower)) {
                logSodiumInfo("Skipping core Sodium config fallback for " + fileName + " because typed Sodium options were captured.");
                continue;
            }
            try {
                if (lower.endsWith(".json")) {
                    JsonElement rootElement;
                    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        rootElement = JsonParser.parseReader(reader);
                    }
                    if (rootElement == null || !rootElement.isJsonObject()) {
                        continue;
                    }
                    List<ConfigFallbackEntry> entries = new ArrayList<>();
                    collectJsonConfigEntries(path, configName, "", rootElement.getAsJsonObject(), entries);
                    for (ConfigFallbackEntry entry : entries) {
                        if (shouldSkipConfigEntry(entry, vanillaKeys)) {
                            continue;
                        }
                        String key = "config:" + normalize(entry.path().toString()) + ":" + normalize(entry.keyPath());
                        if (!seen.add(key)) {
                            continue;
                        }
                        ClickableWidget widget = createConfigWidget(entry, index++);
                        if (widget != null) {
                            this.sodiumConfigEntries.put(widget, entry);
                            this.sodiumWidgetLabels.put(widget, entry.label());
                            captures.add(new NativeCapture(widget, configName, pageNames, entry.label()));
                        }
                    }
                } else if (lower.endsWith(".properties")) {
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        properties.load(reader);
                    }
                    List<String> keys = new ArrayList<>(properties.stringPropertyNames());
                    keys.sort(String::compareToIgnoreCase);
                    for (String keyName : keys) {
                        String label = configName + ": " + humanizeKey(keyName);
                        ConfigFallbackEntry entry = new ConfigFallbackEntry(path, configName, keyName, label, "properties", "string", properties.getProperty(keyName, ""));
                        if (shouldSkipConfigEntry(entry, vanillaKeys)) {
                            continue;
                        }
                        String key = "config:" + normalize(path.toString()) + ":" + normalize(keyName);
                        if (!seen.add(key)) {
                            continue;
                        }
                        ClickableWidget widget = createConfigWidget(entry, index++);
                        if (widget != null) {
                            this.sodiumConfigEntries.put(widget, entry);
                            this.sodiumWidgetLabels.put(widget, entry.label());
                            captures.add(new NativeCapture(widget, configName, pageNames, entry.label()));
                        }
                    }
                }
            } catch (Throwable throwable) {
                logSodiumError("Failed to read Sodium-compatible config fallback file " + path + ".", throwable);
            }
        }
    }

    private boolean isCoreSodiumConfigFile(String lowerFileName) {
        return lowerFileName.equals("sodium-options.json")
                || lowerFileName.equals("sodium-extra-options.json")
                || lowerFileName.equals("reeses-sodium-options.json")
                || lowerFileName.equals("reeses_sodium_options.json");
    }

    private List<Path> sodiumConfigPaths() {
        List<Path> paths = new ArrayList<>();
        Path configDir = FabricLoader.getInstance().getConfigDir();
        String[] priorityNames = {
                "sodium-options.json",
                "sodium-extra-options.json",
                "reeses-sodium-options.json",
                "reeses_sodium_options.json"
        };
        for (String name : priorityNames) {
            Path path = configDir.resolve(name);
            if (Files.isRegularFile(path) && !paths.contains(path)) {
                paths.add(path);
            }
        }
        try (Stream<Path> stream = Files.list(configDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.contains("sodium") && (name.endsWith(".json") || name.endsWith(".properties"));
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> {
                        if (!paths.contains(path)) {
                            paths.add(path);
                        }
                    });
        } catch (Throwable throwable) {
            logSodiumError("Failed to scan Fabric config directory for Sodium-compatible config files.", throwable);
        }
        return paths;
    }

    private void collectJsonConfigEntries(Path path, String configName, String prefix, JsonObject object, List<ConfigFallbackEntry> entries) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonObject()) {
                collectJsonConfigEntries(path, configName, key, value.getAsJsonObject(), entries);
                continue;
            }
            if (!value.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            String type = primitive.isBoolean() ? "boolean" : primitive.isNumber() ? "number" : "string";
            String label = configName + ": " + humanizeKey(key);
            entries.add(new ConfigFallbackEntry(path, configName, key, label, "json", type, primitiveToString(primitive)));
        }
    }

    private boolean shouldSkipConfigEntry(ConfigFallbackEntry entry, Set<String> vanillaKeys) {
        String label = entry.label();
        String key = entry.keyPath();
        String normalizedLabel = normalize(label);
        String normalizedKey = normalize(key);
        if (normalizedLabel.isBlank() || normalizedKey.isBlank()) {
            return true;
        }
        if (isShaderPackButton(label) || normalizedKey.contains("shaderpack") || normalizedKey.equals("shader") || normalizedKey.contains("shaderpath")) {
            return true;
        }
        return vanillaKeys.contains(normalizedKey) || vanillaKeys.contains(normalize(stripValue(label)));
    }

    private ClickableWidget createConfigWidget(ConfigFallbackEntry entry, int index) {
        if ("boolean".equals(entry.valueType())) {
            return ButtonWidget.builder(Text.literal(configButtonText(entry, entry.value())), button -> {
                ConfigFallbackEntry current = this.sodiumConfigEntries.get(button);
                if (current == null) {
                    return;
                }
                String next = Boolean.parseBoolean(current.value()) ? "false" : "true";
                ConfigFallbackEntry updated = new ConfigFallbackEntry(current.path(), current.configName(), current.keyPath(), current.label(), current.fileType(), current.valueType(), next);
                this.sodiumConfigEntries.put(button, updated);
                button.setMessage(Text.literal(configButtonText(updated, next)));
                writeConfigValue(updated, next);
            }).dimensions(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        }
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, 0, 0, BUTTON_WIDTH, BUTTON_HEIGHT, Text.literal(entry.label()));
        field.setMaxLength(512);
        field.setText(configTextFieldText(entry, entry.value()));
        field.setChangedListener(value -> this.dirtyConfigWidgets.add(field));
        return field;
    }

    private String configButtonText(ConfigFallbackEntry entry, String value) {
        return trimButtonText(shortConfigLabel(entry) + ": " + (Boolean.parseBoolean(value) ? "ON" : "OFF"));
    }

    private String configTextFieldText(ConfigFallbackEntry entry, String value) {
        return shortConfigLabel(entry) + ": " + value;
    }

    private String shortConfigLabel(ConfigFallbackEntry entry) {
        String label = humanizeKey(entry.keyPath());
        int dot = label.lastIndexOf(" ");
        if (label.length() > 22 && dot > 0) {
            return label.substring(Math.max(0, dot + 1));
        }
        return label;
    }

    private String trimButtonText(String value) {
        String text = value == null ? "" : value;
        int width = BUTTON_WIDTH - 10;
        if (this.textRenderer.getWidth(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        while (!text.isEmpty() && this.textRenderer.getWidth(text + ellipsis) > width) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
    }

    private void saveSodiumConfigChanges() {
        List<ClickableWidget> dirty = new ArrayList<>(this.dirtyConfigWidgets);
        this.dirtyConfigWidgets.clear();
        for (ClickableWidget widget : dirty) {
            ConfigFallbackEntry entry = this.sodiumConfigEntries.get(widget);
            if (entry == null) {
                continue;
            }
            if (widget instanceof TextFieldWidget textField) {
                writeConfigValue(entry, parseConfigFieldValue(entry, textField.getText()));
            }
        }
    }

    private String parseConfigFieldValue(ConfigFallbackEntry entry, String text) {
        if (text == null) {
            return "";
        }
        String label = shortConfigLabel(entry) + ":";
        if (text.startsWith(label)) {
            return text.substring(label.length()).trim();
        }
        int colon = text.indexOf(':');
        if (colon >= 0 && colon + 1 < text.length()) {
            return text.substring(colon + 1).trim();
        }
        return text.trim();
    }

    private void writeConfigValue(ConfigFallbackEntry entry, String value) {
        try {
            if ("json".equals(entry.fileType())) {
                JsonObject root;
                try (Reader reader = Files.newBufferedReader(entry.path(), StandardCharsets.UTF_8)) {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (element == null || !element.isJsonObject()) {
                        return;
                    }
                    root = element.getAsJsonObject();
                }
                setJsonValue(root, entry.keyPath(), value, entry.valueType());
                try (Writer writer = Files.newBufferedWriter(entry.path(), StandardCharsets.UTF_8)) {
                    CONFIG_GSON.toJson(root, writer);
                }
            } else if ("properties".equals(entry.fileType())) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(entry.path(), StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                properties.setProperty(entry.keyPath(), value);
                try (Writer writer = Files.newBufferedWriter(entry.path(), StandardCharsets.UTF_8)) {
                    properties.store(writer, "Edited by Koil video options");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void setJsonValue(JsonObject root, String keyPath, String value, String valueType) {
        String[] parts = keyPath.split("\\.");
        JsonObject object = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = object.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                JsonObject replacement = new JsonObject();
                object.add(parts[i], replacement);
                object = replacement;
            } else {
                object = child.getAsJsonObject();
            }
        }
        String key = parts[parts.length - 1];
        if ("boolean".equals(valueType)) {
            object.addProperty(key, Boolean.parseBoolean(value));
        } else if ("number".equals(valueType)) {
            try {
                if (value.contains(".") || value.contains("e") || value.contains("E")) {
                    object.addProperty(key, Double.parseDouble(value));
                } else {
                    object.addProperty(key, Long.parseLong(value));
                }
            } catch (NumberFormatException ignored) {
                object.addProperty(key, value);
            }
        } else {
            object.addProperty(key, value);
        }
    }

    private String primitiveToString(JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return String.valueOf(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber().toString();
        }
        return primitive.getAsString();
    }

    private String configDisplayName(String fileName) {
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (name.equals("sodium-options")) {
            return "Sodium";
        }
        if (name.equals("sodium-extra-options")) {
            return "Sodium Extra";
        }
        if (name.contains("reeses")) {
            return "Reese's Sodium Options";
        }
        return humanizeKey(name);
    }

    private String humanizeKey(String key) {
        String cleaned = key == null ? "" : key.replace('.', ' ').replace('_', ' ').replace('-', ' ');
        StringBuilder builder = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != ' ') {
                    builder.append(' ');
                }
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return builder.toString().trim();
    }


    private void layoutSodiumWidgets(List<ClickableWidget> widgets) {
        this.sodiumScroll = 0.0D;
        applySodiumWidgetLayout(widgets);
    }

    private void updateSodiumWidgetLayout() {
        applySodiumWidgetLayout(this.combinedOptionWidgets);
    }

    private boolean addSodiumWidgetsToVanillaOptionList(List<ClickableWidget> widgets) {
        if (widgets.isEmpty()) {
            logSodiumWarning("No Sodium-compatible widgets were available to attach to the vanilla video options list.");
            return true;
        }
        OptionListWidget list = vanillaOptionList();
        if (list == null) {
            logSodiumError("Could not find the vanilla OptionListWidget on KoilVideoOptionsScreen.", null);
            return false;
        }
        int rowWidth = list.getRowWidth();
        int leftX = this.width / 2 - 155;
        int rightX = leftX + 160;
        try {
            Class<?> widgetEntryClass = Class.forName(OptionListWidget.class.getName() + "$WidgetEntry");
            Constructor<?> constructor = widgetEntryClass.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            for (int i = 0; i < widgets.size(); i += 2) {
                ClickableWidget first = widgets.get(i);
                ClickableWidget second = i + 1 < widgets.size() ? widgets.get(i + 1) : null;
                prepareVanillaOptionWidget(first, leftX, 150);
                Map<Object, ClickableWidget> row = new LinkedHashMap<>();
                row.put(new Object(), first);
                if (second != null) {
                    prepareVanillaOptionWidget(second, rightX, 150);
                    row.put(new Object(), second);
                } else {
                    prepareVanillaOptionWidget(first, this.width / 2 - 155, Math.min(310, Math.max(150, rowWidth)));
                }
                Object entry = constructor.newInstance(row);
                if (entry instanceof EntryListWidget.Entry<?> cast) {
                    ((EntryListWidgetAccessor) (Object) list).koil$invokeAddEntry(cast);
                } else {
                    logSodiumError("Created OptionListWidget row is not an EntryListWidget entry: " + entry.getClass().getName(), null);
                    return false;
                }
            }
            this.sodiumListLeft = list.getRowLeft();
            this.sodiumListWidth = list.getRowWidth();
            this.sodiumListTop = 0;
            this.sodiumListBottom = this.height;
            this.sodiumContentHeight = 0;
            this.sodiumScroll = 0.0D;
            return true;
        } catch (Throwable throwable) {
            logSodiumError("Failed while inserting Sodium-compatible widgets into OptionListWidget.", throwable);
            return false;
        }
    }

    private List<ClickableWidget> collectVanillaVideoOptionWidgets() {
        OptionListWidget list = vanillaOptionList();
        if (list == null) {
            logSodiumError("Could not find the vanilla OptionListWidget while building the combined video options list.", null);
            return List.of();
        }
        List<ClickableWidget> widgets = collectClickableWidgets(list);
        widgets.sort(Comparator.comparingInt(ClickableWidget::getY).thenComparingInt(ClickableWidget::getX));
        List<ClickableWidget> result = new ArrayList<>();
        Set<ClickableWidget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClickableWidget widget : widgets) {
            String label = labelForWidget(widget);
            if (shouldHideWidget(label, widget) || label.isBlank()) {
                continue;
            }
            if (seen.add(widget)) {
                result.add(widget);
            }
        }
        logSodiumInfo("Captured " + result.size() + " vanilla video option widgets for the combined video options list.");
        return result;
    }

    private void detachVanillaOptionList() {
        OptionListWidget list = vanillaOptionList();
        if (list == null) {
            return;
        }
        try {
            this.remove(list);
            logSodiumInfo("Detached vanilla OptionListWidget render/input shell; its option widgets are rendered by the combined Koil video list.");
        } catch (Throwable throwable) {
            logSodiumError("Failed to detach vanilla OptionListWidget. The combined list may double-render until this is fixed.", throwable);
        }
    }

    private OptionListWidget vanillaOptionList() {
        for (Class<?> type = this.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (!OptionListWidget.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(this);
                    if (value instanceof OptionListWidget list) {
                        return list;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            Field field = VideoOptionsScreen.class.getDeclaredField("list");
            field.setAccessible(true);
            Object value = field.get(this);
            return value instanceof OptionListWidget list ? list : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void prepareVanillaOptionWidget(ClickableWidget widget, int x, int width) {
        widget.setX(x);
        widget.setY(0);
        try {
            widget.setWidth(width);
        } catch (Throwable ignored) {
        }
        if (widget.getHeight() <= 0 || widget.getHeight() > BUTTON_HEIGHT) {
            trySetIntField(widget, "height", BUTTON_HEIGHT);
        }
        widget.visible = true;
        widget.active = true;
    }

    private void applySodiumWidgetLayout(List<ClickableWidget> widgets) {
        this.sodiumListWidth = 310;
        this.sodiumListLeft = this.width / 2 - 155;
        this.sodiumListTop = sodiumStartY();
        this.sodiumListBottom = Math.max(this.sodiumListTop + ROW_HEIGHT, this.height - 34);
        int rows = (widgets.size() + 1) / 2;
        this.sodiumContentHeight = Math.max(0, rows * ROW_HEIGHT);
        this.sodiumScroll = clamp(this.sodiumScroll, 0.0D, maxSodiumScroll());
        for (int i = 0; i < widgets.size(); i++) {
            ClickableWidget widget = widgets.get(i);
            int row = i / 2;
            int column = i % 2;
            int y = this.sodiumListTop + row * ROW_HEIGHT - (int) Math.round(this.sodiumScroll);
            int x = this.sodiumListLeft + column * (BUTTON_WIDTH + COLUMN_GAP);
            widget.setX(x);
            widget.setY(y);
            try {
                widget.setWidth(BUTTON_WIDTH);
            } catch (Throwable ignored) {
            }
            if (widget.getHeight() <= 0) {
                trySetIntField(widget, "height", BUTTON_HEIGHT);
            }
            if (widget.getHeight() > BUTTON_HEIGHT && widget.getHeight() < ROW_HEIGHT) {
                trySetIntField(widget, "height", BUTTON_HEIGHT);
            }
            widget.visible = y + widget.getHeight() >= this.sodiumListTop && y <= this.sodiumListBottom;
        }
    }

    private int sodiumStartY() {
        OptionListWidget list = vanillaOptionList();
        if (list != null) {
            return Math.max(32, readIntField(list, "top", 32));
        }
        return 32;
    }

    private int maxSodiumScroll() {
        return Math.max(0, this.sodiumContentHeight - Math.max(1, this.sodiumListBottom - this.sodiumListTop));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isOverSodiumScrollbar(double mouseX, double mouseY) {
        int trackX = this.sodiumListLeft + this.sodiumListWidth + 6;
        return mouseX >= trackX - 4
                && mouseX <= trackX + 10
                && mouseY >= this.sodiumListTop
                && mouseY <= this.sodiumListBottom;
    }

    private int sodiumScrollbarThumbHeight() {
        int trackHeight = Math.max(1, this.sodiumListBottom - this.sodiumListTop);
        return Math.max(20, (int) (trackHeight * (trackHeight / (double) Math.max(trackHeight, this.sodiumContentHeight))));
    }

    private int sodiumScrollbarThumbY() {
        int maxScroll = maxSodiumScroll();
        int trackHeight = Math.max(1, this.sodiumListBottom - this.sodiumListTop);
        int nubHeight = sodiumScrollbarThumbHeight();
        int range = Math.max(1, trackHeight - nubHeight);
        return this.sodiumListTop + (int) Math.round((this.sodiumScroll / Math.max(1, maxScroll)) * range);
    }

    private void setSodiumScrollFromThumbTop(int thumbTop) {
        int thumbHeight = sodiumScrollbarThumbHeight();
        int minTop = this.sodiumListTop;
        int maxTop = this.sodiumListBottom - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int travel = Math.max(1, maxTop - minTop);
        double ratio = (clampedTop - minTop) / (double) travel;
        this.sodiumScroll = clamp(Math.round(ratio * maxSodiumScroll()), 0.0D, maxSodiumScroll());
    }

    private void renderSodiumWidgets(DrawContext context, int mouseX, int mouseY, float delta) {
        updateSodiumWidgetLayout();
        if (this.combinedOptionWidgets.isEmpty()) {
            return;
        }
        context.enableScissor(Math.max(0, this.sodiumListLeft - 8), Math.max(0, this.sodiumListTop - 2), Math.min(this.width, this.sodiumListLeft + this.sodiumListWidth + 8), Math.min(this.height, this.sodiumListBottom + 2));
        for (ClickableWidget widget : this.combinedOptionWidgets) {
            if (!widget.visible) {
                continue;
            }
            try {
                widget.render(context, mouseX, mouseY, delta);
            } catch (Throwable ignored) {
            }
        }
        context.disableScissor();
        renderSodiumScrollbar(context);
    }

    private void renderSodiumScrollbar(DrawContext context) {
        int maxScroll = maxSodiumScroll();
        if (maxScroll <= 0) {
            return;
        }
        int trackX = this.sodiumListLeft + this.sodiumListWidth + 6;
        int trackTop = this.sodiumListTop;
        int trackBottom = this.sodiumListBottom;
        int nubHeight = sodiumScrollbarThumbHeight();
        int nubY = sodiumScrollbarThumbY();
        context.fill(trackX + 2, trackTop, trackX + 4, trackBottom, 0x66000000);
        context.fill(trackX, nubY, trackX + 6, nubY + nubHeight, 0xFF808080);
    }

    private void renderHintBadges(DrawContext context, int mouseX, int mouseY) {
        this.hintHitboxes.clear();
        for (ClickableWidget widget : collectCurrentScreenWidgets()) {
            if (!widget.visible) {
                continue;
            }
            String label = labelForWidget(widget);
            if (shouldHideWidget(label, widget) || normalize(label).equals("done") || label.isBlank()) {
                continue;
            }
            if (this.combinedOptionWidgets.contains(widget) && !isWidgetInsideVanillaListViewport(widget)) {
                continue;
            }
            PerformanceRecommendation recommendation = recommendationForWidget(widget, label);
            if (recommendation == null) {
                continue;
            }
            int badgeX = widget.getX() + widget.getWidth() - 8;
            int badgeY = widget.getY() + 1;
            context.drawTextWithShadow(this.textRenderer, "!", badgeX, badgeY, severityDisplayColor(recommendation.severity()));
            this.hintHitboxes.add(new HintHitbox(badgeX - 2, badgeY - 2, 12, 12, label, recommendation));
        }
    }

    private void renderHintTooltip(DrawContext context, int mouseX, int mouseY) {
        for (HintHitbox hitbox : this.hintHitboxes) {
            if (inside(mouseX, mouseY, hitbox.x(), hitbox.y(), hitbox.width(), hitbox.height())) {
                int x = Math.min(this.width - 18, Math.max(8, mouseX + 10));
                int y = Math.min(this.height - 18, Math.max(8, mouseY + 12));
                context.drawTooltip(this.textRenderer, recommendationTooltip(hitbox.label(), hitbox.recommendation()), Optional.empty(), x, y);
                return;
            }
        }
    }

    private boolean isWidgetInsideVanillaListViewport(ClickableWidget widget) {
        int top = this.sodiumListTop <= 0 ? 32 : this.sodiumListTop;
        int bottom = this.sodiumListBottom <= top ? this.height - 32 : this.sodiumListBottom;
        int widgetTop = widget.getY();
        int widgetBottom = widgetTop + widget.getHeight();
        return widgetBottom >= top && widgetTop <= bottom;
    }

    private int readIntField(Object owner, String name, int fallback) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(owner);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private List<Text> recommendationTooltip(String label, PerformanceRecommendation recommendation) {
        int severityColor = severityDisplayColor(recommendation.severity());
        int labelColor = new Color(uiColorToolTipLabel, true).getRGB();
        int primaryColor = new Color(uiColorToolTipPrimary, true).getRGB();
        int secondaryColor = new Color(uiColorToolTipSecondary, true).getRGB();
        int ideaColor = new Color(uiColorToolTipIdea, true).getRGB();
        int valueColor = recommendation.severity() == PerformanceRecommendation.Severity.MANUAL_REVIEW
                ? new Color(uiColorToolTipWarning, true).getRGB()
                : severityColor;
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(safeText(recommendation.severity().label())).setStyle(Style.EMPTY.withColor(severityColor).withBold(true))
                .append(Text.literal("  " + safeText(recommendation.title())).setStyle(Style.EMPTY.withColor(primaryColor))));
        lines.add(Text.literal("Setting: ").setStyle(Style.EMPTY.withColor(labelColor))
                .append(Text.literal(displaySettingKey(label, recommendation)).setStyle(Style.EMPTY.withColor(primaryColor))));
        lines.add(Text.literal("Change: ").setStyle(Style.EMPTY.withColor(labelColor))
                .append(Text.literal(displayBeforeValue(label, recommendation)).setStyle(Style.EMPTY.withColor(secondaryColor)))
                .append(Text.literal(" -> ").setStyle(Style.EMPTY.withColor(labelColor)))
                .append(Text.literal(displayAfterValue(recommendation)).setStyle(Style.EMPTY.withColor(valueColor).withBold(true))));
        String applyState = recommendationApplyState(recommendation);
        if (!applyState.isBlank()) {
            lines.add(Text.literal("State: ").setStyle(Style.EMPTY.withColor(labelColor))
                    .append(Text.literal(applyState).setStyle(Style.EMPTY.withColor(applyStateColor(applyState)).withBold(true))));
        }
        lines.add(Text.literal("Reason:").setStyle(Style.EMPTY.withColor(ideaColor).withBold(true)));
        for (String line : wrapTooltipText(systemVoice(recommendation.reason()), 72)) {
            lines.add(Text.literal("  " + line).setStyle(Style.EMPTY.withColor(secondaryColor)));
        }
        lines.add(Text.literal("Source: ").setStyle(Style.EMPTY.withColor(labelColor))
                .append(Text.literal(recommendationSource(recommendation)).setStyle(Style.EMPTY.withColor(primaryColor))));
        return lines;
    }

    private int severityDisplayColor(PerformanceRecommendation.Severity severity) {
        if (severity == PerformanceRecommendation.Severity.MANUAL_REVIEW) {
            return new Color(uiColorToolTipWarning, true).getRGB();
        }
        if (severity == PerformanceRecommendation.Severity.APPLIED) {
            return new Color(uiColorSaveSuccessColor, true).getRGB();
        }
        return severity.color();
    }

    private String recommendationApplyState(PerformanceRecommendation recommendation) {
        if (recommendation.severity() == PerformanceRecommendation.Severity.APPLIED) {
            return "Applied";
        }
        if (recommendation.severity() == PerformanceRecommendation.Severity.REJECTED) {
            return "Failed";
        }
        if (recommendation.severity() == PerformanceRecommendation.Severity.MANUAL_REVIEW) {
            return "Review";
        }
        return "";
    }

    private int applyStateColor(String state) {
        return switch (state) {
            case "Applied" -> new Color(uiColorSaveSuccessColor, true).getRGB();
            case "Review" -> new Color(uiColorToolTipWarning, true).getRGB();
            case "Failed" -> new Color(uiColorToolTipError, true).getRGB();
            default -> new Color(uiColorToolTipSecondary, true).getRGB();
        };
    }

    private String displaySettingKey(String label, PerformanceRecommendation recommendation) {
        String key = safeText(recommendation.settingKey());
        return key.isBlank() ? stripValue(label) : key;
    }

    private String displayBeforeValue(String label, PerformanceRecommendation recommendation) {
        String before = safeText(recommendation.beforeValue());
        if (!before.isBlank()) {
            return before;
        }
        String clean = cleanLabel(label);
        int colon = clean.indexOf(':');
        if (colon >= 0 && colon + 1 < clean.length()) {
            return clean.substring(colon + 1).trim();
        }
        return "current";
    }

    private String displayAfterValue(PerformanceRecommendation recommendation) {
        String after = safeText(recommendation.afterValue());
        return after.isBlank() ? "recommended" : after;
    }

    private String systemVoice(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("Koil's", "the system's")
                .replace("Koil ", "The system ")
                .replace(" Koil", " the system")
                .replace("koil ", "the system ");
    }

    private String recommendationSource(PerformanceRecommendation recommendation) {
        String key = recommendation.settingKey() == null ? "" : recommendation.settingKey();
        if (key.contains(".") || key.contains("::")) {
            return "editable optimization/shader config provider";
        }
        if ("loaded_mods".equals(key) || "resourcepacks".equals(key)) {
            return "Fabric Loader and Minecraft resource pack manager";
        }
        if ("memory_allocation".equals(key)) {
            return "JVM Runtime memory counters";
        }
        return "Minecraft GameOptions plus live benchmark snapshot";
    }

    private List<String> wrapTooltipText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.split("\\s+")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > maxChars) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private PerformanceRecommendation recommendationForWidget(ClickableWidget widget, String label) {
        ConfigFallbackEntry configEntry = this.sodiumConfigEntries.get(widget);
        if (configEntry != null) {
            PerformanceRecommendation configRecommendation = recommendationForSettingKey(configEntry.keyPath());
            if (configRecommendation != null) {
                return configRecommendation;
            }
        }
        String mappedSettingKey = knownSettingKeyForLabel(label);
        if (!mappedSettingKey.isBlank()) {
            PerformanceRecommendation mappedRecommendation = recommendationForSettingKey(mappedSettingKey);
            if (mappedRecommendation != null) {
                return mappedRecommendation;
            }
        }
        return recommendationForLabel(label);
    }

    private PerformanceRecommendation recommendationForLabel(String label) {
        String normalized = normalize(stripValue(label));
        if (normalized.isBlank()) {
            return null;
        }
        PerformanceRecommendation direct = this.recommendationsByKey.get(normalized);
        if (direct != null) {
            return direct;
        }
        String mappedSettingKey = knownSettingKeyForLabel(label);
        if (!mappedSettingKey.isBlank()) {
            return recommendationForSettingKey(mappedSettingKey);
        }
        return null;
    }

    private PerformanceRecommendation recommendationForSettingKey(String settingKey) {
        String normalized = normalize(settingKey);
        if (normalized.isBlank()) {
            return null;
        }
        PerformanceRecommendation direct = this.recommendationsByKey.get(normalized);
        if (direct != null) {
            return direct;
        }
        PerformanceRecommendation labelMatch = this.recommendationsByKey.get(normalize(labelFromRecommendationKey(settingKey)));
        if (labelMatch != null) {
            return labelMatch;
        }
        for (PerformanceRecommendation recommendation : this.latestRecommendations) {
            if (recommendation != null && settingKeyMatches(recommendation.settingKey(), settingKey)) {
                return recommendation;
            }
        }
        return null;
    }

    private boolean settingKeyMatches(String recommendationKey, String optionKey) {
        String recommendation = normalizeSettingPath(recommendationKey);
        String option = normalizeSettingPath(optionKey);
        if (recommendation.isBlank() || option.isBlank()) {
            return false;
        }
        return recommendation.equals(option)
                || recommendation.endsWith("." + option)
                || recommendation.endsWith("::" + option)
                || recommendation.endsWith("/" + option);
    }

    private String normalizeSettingPath(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .replace('_', '-')
                .replaceAll("\\s+", "")
                .trim();
    }

    private void addRecommendationKey(String key, PerformanceRecommendation recommendation) {
        String normalized = normalize(key);
        if (!normalized.isBlank()) {
            this.recommendationsByKey.put(normalized, recommendation);
        }
    }

    private String labelFromRecommendation(PerformanceRecommendation recommendation) {
        return labelFromRecommendationKey(recommendation.settingKey());
    }

    private String labelFromRecommendationKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        char[] chars = key.replace('_', ' ').replace('-', ' ').toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(chars[i - 1])) {
                builder.append(' ');
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private String knownSettingKeyForLabel(String label) {
        String normalized = normalize(stripValue(label));
        return switch (normalized) {
            case "renderdistance", "renderdistancechunks" -> "render_distance";
            case "simulationdistance", "simulationdistancechunks" -> "simulation_distance";
            case "entitydistance", "entitydistance%" -> "entity_distance";
            case "maxframerate", "frameratelimit", "maxfps" -> "max_fps";
            case "vsync", "v sync" -> "vsync";
            case "graphics", "graphicsmode" -> "graphics_mode";
            case "smoothlighting" -> "smooth_lighting";
            case "biomeblend" -> "biome_blend";
            case "mipmaplevels", "mipmaps" -> "mipmaps";
            case "particles" -> "particles";
            case "clouds" -> "clouds";
            case "entityshadows" -> "entity_shadows";
            case "weatherquality" -> "quality.weather_quality";
            case "leavesquality" -> "quality.leaves_quality";
            case "enablevignette", "vignette" -> "quality.enable_vignette";
            case "useentityculling", "entityculling" -> "performance.use_entity_culling";
            case "usefogocclusion", "fogocclusion" -> "performance.use_fog_occlusion";
            case "useblockfaceculling", "blockfaceculling" -> "performance.use_block_face_culling";
            case "usecompactvertexformat", "compactvertexformat" -> "performance.use_compact_vertex_format";
            case "animateonlyvisibletextures", "onlyanimatevisibletextures" -> "performance.animate_only_visible_textures";
            case "alwaysdeferchunkupdatesv2", "deferchunkupdates" -> "performance.always_defer_chunk_updates_v2";
            case "chunkbuilderthreads" -> "performance.chunk_builder_threads";
            case "useadvancedstagingbuffers", "advancedstagingbuffers" -> "advanced.use_advanced_staging_buffers";
            case "cpurenderaheadlimit", "renderaheadlimit" -> "advanced.cpu_render_ahead_limit";
            case "usememorytracing", "memorytracing" -> "advanced.enable_memory_tracing";
            case "usenocontentcontext", "noerrorglcontext", "usenoglerorcontext", "usenog lcontext" -> "performance.use_no_error_g_l_context";
            default -> "";
        };
    }

    private void hideShaderPackButtons() {
        for (ClickableWidget widget : collectCurrentScreenWidgets()) {
            String label = labelForWidget(widget);
            if (isShaderPackButton(label)) {
                widget.visible = false;
                widget.active = false;
            }
        }
    }

    private boolean shouldHideWidget(String label, ClickableWidget widget) {
        String normalized = normalize(label);
        if (normalized.equals("done") || normalized.equals("cancel") || normalized.equals("back") || normalized.equals("close") || normalized.equals("apply") || normalized.equals("save")) {
            return true;
        }
        return isShaderPackButton(label);
    }

    private boolean isShaderPackButton(String label) {
        String normalized = normalize(label);
        String lower = cleanLabel(label).toLowerCase(Locale.ROOT);
        return normalized.equals("shaders")
                || normalized.equals("shaderpacks")
                || normalized.equals("shaderpack")
                || lower.equals("shader packs")
                || lower.equals("shaderpacks")
                || lower.contains("shader packs...");
    }

    private boolean isSodiumPageSelector(String label, Set<String> pageNames) {
        String stripped = normalize(stripValue(label));
        if (stripped.isBlank()) {
            return false;
        }
        for (String pageName : pageNames) {
            if (stripped.equals(normalize(pageName))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> discoverSodiumPageNames(Screen screen) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectPageNames(screen, visited, names, 0);
        names.add("General");
        names.add("Quality");
        names.add("Performance");
        names.add("Advanced");
        names.add("Animations");
        names.add("Details");
        names.add("Extras");
        names.add("Other");
        return names;
    }

    private void collectPageNames(Object value, Set<Object> visited, Set<String> names, int depth) {
        if (value == null || depth > 8 || visited.contains(value)) {
            return;
        }
        visited.add(value);
        if (isLikelyOptionPage(value)) {
            String name = readPageName(value);
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        if (value instanceof MinecraftClient || value == this || value == this.client) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectPageNames(item, visited, names, depth + 1);
            }
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectPageNames(Array.get(value, i), visited, names, depth + 1);
            }
            return;
        }
        if (shouldSkipClass(valueClass)) {
            return;
        }
        for (Class<?> type = valueClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || shouldSkipField(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collectPageNames(field.get(value), visited, names, depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean isLikelyOptionPage(Object value) {
        String className = value.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("option") && className.contains("page");
    }

    private String readPageName(Object page) {
        String fromMethod = invokeStringMethod(page, "getName");
        if (!fromMethod.isBlank()) {
            return cleanLabel(fromMethod);
        }
        fromMethod = invokeStringMethod(page, "name");
        if (!fromMethod.isBlank()) {
            return cleanLabel(fromMethod);
        }
        fromMethod = invokeStringMethod(page, "getTitle");
        if (!fromMethod.isBlank()) {
            return cleanLabel(fromMethod);
        }
        for (Class<?> type = page.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String lower = field.getName().toLowerCase(Locale.ROOT);
                if (!lower.equals("name") && !lower.equals("title")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(page);
                    if (value instanceof Text text) {
                        return cleanLabel(text.getString());
                    }
                    if (value instanceof String string) {
                        return cleanLabel(string);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    private String readOptionName(Object option) {
        String fromMethod = invokeStringMethod(option, "getName");
        if (!fromMethod.isBlank()) {
            return cleanLabel(fromMethod);
        }
        fromMethod = invokeStringMethod(option, "name");
        if (!fromMethod.isBlank()) {
            return cleanLabel(fromMethod);
        }
        fromMethod = invokeStringMethod(option, "getTitle");
        if (!fromMethod.isBlank()) {
            return cleanLabel(fromMethod);
        }
        for (Class<?> type = option.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String lower = field.getName().toLowerCase(Locale.ROOT);
                if (!lower.equals("name") && !lower.equals("title")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(option);
                    if (value instanceof Text text) {
                        return cleanLabel(text.getString());
                    }
                    if (value instanceof String string) {
                        return cleanLabel(string);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    private String invokeStringMethod(Object owner, String methodName) {
        try {
            Method method = owner.getClass().getMethod(methodName);
            if (method.getParameterCount() != 0) {
                return "";
            }
            Object value = method.invoke(owner);
            if (value instanceof Text text) {
                return text.getString();
            }
            if (value instanceof String string) {
                return string;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private List<ClickableWidget> collectCurrentScreenWidgets() {
        List<ClickableWidget> widgets = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element element : this.children()) {
            collectClickableWidgetsFrom(element, visited, widgets, 0);
        }
        for (ClickableWidget widget : this.sodiumWidgets) {
            if (!widgets.contains(widget)) {
                widgets.add(widget);
            }
        }
        for (ClickableWidget widget : this.combinedOptionWidgets) {
            if (!widgets.contains(widget)) {
                widgets.add(widget);
            }
        }
        return widgets;
    }

    private List<ClickableWidget> collectClickableWidgets(Object root) {
        List<ClickableWidget> widgets = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectClickableWidgetsFrom(root, visited, widgets, 0);
        return widgets;
    }

    private void collectClickableWidgetsFrom(Object value, Set<Object> visited, List<ClickableWidget> widgets, int depth) {
        if (value == null || depth > 8 || visited.contains(value)) {
            return;
        }
        visited.add(value);
        if (value instanceof ClickableWidget widget) {
            if (!widgets.contains(widget)) {
                widgets.add(widget);
            }
            return;
        }
        if (value instanceof MinecraftClient || value == this || value == this.client) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectClickableWidgetsFrom(item, visited, widgets, depth + 1);
            }
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectClickableWidgetsFrom(Array.get(value, i), visited, widgets, depth + 1);
            }
            return;
        }
        if (shouldSkipClass(valueClass)) {
            return;
        }
        for (Class<?> type = valueClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : safeFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || shouldSkipField(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collectClickableWidgetsFrom(field.get(value), visited, widgets, depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Field[] safeFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    private Method[] safeMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        try {
            Collections.addAll(methods, type.getMethods());
        } catch (Throwable ignored) {
        }
        try {
            Collections.addAll(methods, type.getDeclaredMethods());
        } catch (Throwable ignored) {
        }
        return methods.toArray(new Method[0]);
    }

    private boolean shouldSkipClass(Class<?> type) {
        if (type.isEnum() || type == String.class || Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class || Text.class.isAssignableFrom(type)) {
            return true;
        }
        Package typePackage = type.getPackage();
        String packageName = typePackage == null ? "" : typePackage.getName();
        return packageName.startsWith("java.")
                || packageName.startsWith("jdk.")
                || packageName.startsWith("sun.")
                || packageName.startsWith("com.mojang.blaze3d")
                || packageName.startsWith("org.lwjgl");
    }

    private boolean shouldSkipField(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("client")
                || lower.equals("minecraft")
                || lower.equals("parent")
                || lower.equals("lastscreen")
                || lower.contains("texture")
                || lower.contains("font")
                || lower.contains("sound");
    }

    private void trySetIntField(Object owner, String name, int value) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(owner, value);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private String labelForWidget(ClickableWidget widget) {
        String stored = this.sodiumWidgetLabels.get(widget);
        if (stored != null) {
            return stored;
        }
        return cleanLabel(widget.getMessage() == null ? "" : widget.getMessage().getString());
    }

    private String captureLabel(NativeCapture capture) {
        String label = cleanLabel(capture.widget().getMessage() == null ? "" : capture.widget().getMessage().getString());
        if (label.isBlank()) {
            label = cleanLabel(capture.fallbackLabel());
        }
        return label;
    }

    private String cleanLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private String stripValue(String label) {
        String clean = cleanLabel(label);
        int colon = clean.indexOf(':');
        if (colon > 0) {
            return clean.substring(0, colon).trim();
        }
        return clean;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "").trim();
    }


    private boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private record NativeCapture(ClickableWidget widget, String pageName, Set<String> pageNames, String fallbackLabel) {
    }

    private record ConfigFallbackEntry(Path path, String configName, String keyPath, String label, String fileType, String valueType, String value) {
    }

    private record HintHitbox(int x, int y, int width, int height, String label, PerformanceRecommendation recommendation) {
    }

    private final class SodiumIntegerSliderWidget extends SliderWidget {
        private final Object option;
        private final int min;
        private final int max;
        private final int interval;
        private final Object formatter;

        private SodiumIntegerSliderWidget(int x, int y, int width, int height, Object option, int min, int max, int interval, Object formatter) {
            super(x, y, width, height, Text.empty(), sliderValueFromOption(option, min, max));
            this.option = option;
            this.min = min;
            this.max = max;
            this.interval = Math.max(1, interval);
            this.formatter = formatter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int current = valueFromSlider();
            String label = readOptionName(this.option);
            String valueText = formatSodiumSliderValue(this.formatter, current);
            setMessage(Text.literal(label.isBlank() ? valueText : label + ": " + valueText));
        }

        @Override
        protected void applyValue() {
            int next = valueFromSlider();
            setSodiumOptionValue(this.option, next);
            applySodiumOptionNow(this.option);
        }

        private int valueFromSlider() {
            int steps = Math.max(1, (this.max - this.min) / Math.max(1, this.interval));
            int raw = this.min + (int) Math.round(this.value * steps) * this.interval;
            return Math.max(this.min, Math.min(this.max, raw));
        }
    }

    private static double sliderValueFromOption(Object option, int min, int max) {
        Object value = invokeStaticOptionValue(option);
        int current = value instanceof Number number ? number.intValue() : min;
        if (max <= min) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (current - min) / (double) (max - min)));
    }

    private static Object invokeStaticOptionValue(Object option) {
        if (option == null) {
            return null;
        }
        try {
            Method method = option.getClass().getMethod("getValue");
            return method.invoke(option);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
