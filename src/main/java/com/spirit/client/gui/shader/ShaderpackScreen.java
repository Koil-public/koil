package com.spirit.client.gui.shader;

import com.spirit.client.gui.browser.AbstractModrinthContentScreen;
import com.spirit.client.gui.BrowserLayoutHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

public class ShaderpackScreen extends AbstractModrinthContentScreen {
    private static final Identifier SHADERPACK_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "shader.png");
    private static final Identifier FALLBACK_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");

    public ShaderpackScreen(Screen parent) {
        super(parent);
    }

    public ShaderpackScreen(Screen parent, String initialQuery) {
        super(parent, initialQuery);
    }

    @Override
    protected void init() {
        super.init();
        if (this.cancelButton != null) {
            this.remove(this.cancelButton);
            this.cancelButton = null;
        }
        if (this.reloadButton != null) {
            this.remove(this.reloadButton);
            this.reloadButton = null;
        }
        int topButtonWidth = usesKoilChrome() ? BrowserLayoutHelper.FOOTER_TOP_BUTTON_WIDTH : 112;
        int rightActionX = usesKoilChrome() ? BrowserLayoutHelper.FOOTER_RIGHT_ACTION_X : this.width / 2 + 80;
        int cancelWidth = usesKoilChrome() ? BrowserLayoutHelper.FOOTER_SMALL_BUTTON_WIDTH : 74;
        if (this.openFolderButton != null && usesKoilChrome()) {
            this.openFolderButton.setX(rightActionX);
            this.openFolderButton.setY(this.height - 52);
            this.openFolderButton.setWidth(topButtonWidth);
        }
        this.addDrawableChild(ButtonWidget.builder(Text.literal("InDev"), button -> {
        }).dimensions(rightActionX, this.height - 28, topButtonWidth, 20).build()).active = false;
        this.cancelButton = this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> {
            if (this.client != null) {
                this.client.setScreen(this.parent);
            }
        }).dimensions(usesKoilChrome() ? BrowserLayoutHelper.footerSmallButtonX(3) : this.width / 2 + 80, this.height - 28, cancelWidth, 20).build());
    }

    @Override
    protected String getProjectTypeFacet() {
        return "shader";
    }

    @Override
    protected String getScreenTitle() {
        return "Download Shaders";
    }

    @Override
    protected String getSearchPlaceholder() {
        return "Search shaders";
    }

    @Override
    protected Identifier getFallbackIcon() {
        return SHADERPACK_ICON == null ? FALLBACK_ICON : SHADERPACK_ICON;
    }

    @Override
    protected File getContentDirectory() {
        Path path = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
        return path.toFile();
    }

    @Override
    protected String getLocalFileExtension() {
        return ".zip";
    }

    @Override
    protected String getLocalContextLabel() {
        return "";
    }

    @Override
    protected File findInstalledFile(String slug, String title) {
        File direct = super.findInstalledFile(slug, title);
        if (direct != null) {
            return direct;
        }
        File directory = getContentDirectory();
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) {
            return null;
        }
        List<String> queryTokens = shaderTokens(slug + " " + title);
        if (queryTokens.isEmpty()) {
            return null;
        }
        File bestFile = null;
        int bestScore = 0;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                continue;
            }
            List<String> fileTokens = shaderTokens(file.getName());
            int score = 0;
            for (String token : queryTokens) {
                if (fileTokens.contains(token)) {
                    score += token.length() >= 5 ? 3 : 1;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestFile = file;
            }
        }
        return bestScore >= 3 ? bestFile : null;
    }

    private List<String> shaderTokens(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replace(".zip", " ")
                .replaceAll("[^a-z0-9]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()
                    || token.equals("shader")
                    || token.equals("shaders")
                    || token.equals("shaderpack")
                    || token.equals("pack")
                    || token.equals("fabric")
                    || token.equals("iris")
                    || token.matches("v?\\d+(?:\\.\\d+)*")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    @Override
    protected Screen createReloadScreen() {
        return new ShaderpackScreen(this.parent, currentSearchQuery());
    }
}
