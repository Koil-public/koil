package com.spirit.mixin;

import com.spirit.client.gui.main.KoilMenuScreen;
import com.spirit.client.gui.main.KoilUpdateToast;
import com.spirit.client.gui.update.UpdateScreen;
import com.spirit.client.gui.update.elements.UpdateState;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import com.spirit.koil.api.util.web.WebFileDownloader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.spirit.Main.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;

@Mixin(TitleScreen.class)
public class KoilButton extends Screen {
    @Unique private static final Identifier KOIL_TITLE_BUTTON_FALLBACK = new Identifier("textures/gui/widgets.png");
    @Unique private static final int[] KOIL_CONFETTI_COLORS = {
            0xFF8A80,
            0xFFD166,
            0x7BDFF2,
            0xB8F2A1,
            0xCDB4FF,
            0xFFC6A5
    };
    @Unique private TexturedButtonWidget koil$entryButton;
    @Unique private boolean koil$entryOpensUpdate;
    @Unique private static long koil$lastUpdateToastAt;

    protected KoilButton(Text title) {
        super(title);
    }

    @Inject(at = @At("RETURN"), method = "init")
    private void addModsButton(CallbackInfo ci) {
        WebFileDownloader.downloadFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/sys.json", "sys.json", "./koil/sys", 16);
        File koil_texture = new File(uiImageDirectory, "koil.png");
        File koil_update_texture = new File(uiImageDirectory, "koil_update.png");
        ImageTextureService.markFilePersistent(koil_texture);
        ImageTextureService.markFilePersistent(koil_update_texture);

        UpdateState.Status updateStatus = UpdateState.resolve();
        String webVersion = updateStatus.remoteVersion();
        String currentVersion = updateStatus.localVersion();

        if (updateStatus.updateAvailable()) {
            if (!Objects.equals(currentVersion, webVersion)) SUBLOGGER.logU("File-Management thread", "Update: " + webVersion + " available!", false, "Update: " + webVersion + " available!");
            koil$showUpdateToast(updateStatus);

            this.koil$entryOpensUpdate = true;
            this.koil$entryButton = this.addDrawableChild(new TexturedButtonWidget(
                    this.width / 2 + 128, this.height / 4 + 48 + 72 + 12, 20, 20,
                    0, 0, 20,
                    koil$titleButtonTexture("koil_update.png"), 32, 64,
                    button -> Objects.requireNonNull(this.client).setScreen(new UpdateScreen(this))
            ));
        } else {
            this.koil$entryOpensUpdate = false;
            this.koil$entryButton = this.addDrawableChild(new TexturedButtonWidget(
                    this.width / 2 + 128, this.height / 4 + 48 + 72 + 12, 20, 20,
                    0, 0, 20,
                    koil$titleButtonTexture("koil.png"), 32, 64,
                    button -> Objects.requireNonNull(this.client).setScreen(new KoilMenuScreen())
            ));
        }
    }

    @Unique
    private void koil$showUpdateToast(UpdateState.Status status) {
        if (this.client == null || this.client.getToastManager() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - koil$lastUpdateToastAt < 30000L) {
            return;
        }
        koil$lastUpdateToastAt = now;
        KoilUpdateToast.add(
                this.client.getToastManager(),
                status.toastType(),
                Text.of("Koil Update Available"),
                Text.of(status.remoteVersion() + " | " + status.releaseName())
        );
    }

    @Unique
    private Identifier koil$titleButtonTexture(String fileName) {
        Identifier texture = loadExternalPngTexture(uiImageDirectory, fileName);
        if (texture == null) {
            SUBLOGGER.logE("Title Screen", "Missing Koil title button texture " + fileName + "; using safe vanilla fallback.");
        }
        return texture != null ? texture : KOIL_TITLE_BUTTON_FALLBACK;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$renderWelcomePopup(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.koil$entryButton == null || this.koil$entryOpensUpdate || !this.koil$entryButton.isHovered()) {
            return;
        }

        int buttonLeft = this.koil$entryButton.getX();
        int buttonTop = this.koil$entryButton.getY();
        int buttonRight = buttonLeft + this.koil$entryButton.getWidth();
        int buttonBottom = buttonTop + this.koil$entryButton.getHeight();
        long now = System.currentTimeMillis();
        int glowAlpha = 54 + (int) (34 * Math.abs(Math.sin(now / 240.0)));
        context.fill(buttonLeft - 3, buttonTop - 3, buttonRight + 3, buttonBottom + 3, withAlpha(0xFFF3DE, glowAlpha));
        context.drawBorder(buttonLeft - 2, buttonTop - 2, this.koil$entryButton.getWidth() + 4, this.koil$entryButton.getHeight() + 4, withAlpha(0xFFF8EE, 182));
        renderHoverConfetti(context, buttonLeft, buttonTop, now);
        renderFallingConfetti(context, buttonLeft, buttonTop, now);
        context.drawTooltip(this.textRenderer, buildWelcomeTooltip(), Optional.empty(), mouseX, mouseY + 10);
    }

    @Unique
    private List<Text> buildWelcomeTooltip() {
        String versionLabel = currentVersion().startsWith("0.70.26") ? "0.70.26" : currentVersion();
        int titleColor = KOIL_CONFETTI_COLORS[1];
        int labelColor = KOIL_CONFETTI_COLORS[4];
        int primaryColor = 0xFFF7EC;
        int secondaryColor = KOIL_CONFETTI_COLORS[5];
        int highlightColor = KOIL_CONFETTI_COLORS[0];
        int accentColor = KOIL_CONFETTI_COLORS[2];
        return List.of(
                Text.literal("Welcome to Koil").setStyle(Style.EMPTY.withColor(titleColor).withBold(true))
                        .append(Text.literal("  ").setStyle(Style.EMPTY.withColor(labelColor)))
                        .append(Text.literal(versionLabel).setStyle(Style.EMPTY.withColor(labelColor))),
                Text.literal("Welcome: ").setStyle(Style.EMPTY.withColor(highlightColor))
                        .append(Text.literal("The system is ready whenever you are.").setStyle(Style.EMPTY.withColor(primaryColor))),
                Text.literal("Tip: ").setStyle(Style.EMPTY.withColor(accentColor))
                        .append(Text.literal("A lot is still in development, however everything surface should be patched.").setStyle(Style.EMPTY.withColor(secondaryColor)))
        );
    }

    @Unique
    private void renderHoverConfetti(DrawContext context, int buttonLeft, int buttonTop, long now) {
        int centerX = buttonLeft + (this.koil$entryButton.getWidth() / 2);
        int centerY = buttonTop + (this.koil$entryButton.getHeight() / 2);
        for (int i = 0; i < 16; i++) {
            double phase = (now / (180.0 + i * 6.0)) + (i * 0.55);
            double radius = 11.0 + (i % 4) * 3.2 + Math.sin(phase * 0.8) * 2.6;
            int confettiX = centerX + (int) Math.round(Math.cos(phase) * radius);
            int confettiY = centerY + (int) Math.round(Math.sin(phase * 1.15) * (8.0 + (i % 3) * 3.5));
            int size = i % 3 == 0 ? 4 : 3;
            int alpha = 118 + (int) (92 * Math.abs(Math.sin(phase * 1.2)));
            int color = withAlpha(KOIL_CONFETTI_COLORS[i % KOIL_CONFETTI_COLORS.length], alpha);
            if ((i & 1) == 0) {
                context.fill(confettiX, confettiY, confettiX + size, confettiY + 1, color);
                context.fill(confettiX + 1, confettiY - 1, confettiX + 3, confettiY + 2, withAlpha(0xFFFFFF, alpha / 3));
            } else {
                context.fill(confettiX, confettiY, confettiX + 1, confettiY + size, color);
                context.fill(confettiX - 1, confettiY + 1, confettiX + 2, confettiY + 3, withAlpha(0xFFFFFF, alpha / 3));
            }
        }
    }

    @Unique
    private void renderFallingConfetti(DrawContext context, int buttonLeft, int buttonTop, long now) {
        int centerX = buttonLeft + (this.koil$entryButton.getWidth() / 2);
        int spawnTop = Math.max(0, buttonTop - 26);
        int fallHeight = Math.max(40, this.height - spawnTop + 18);
        for (int i = 0; i < 16; i++) {
            double phase = (now / (130.0 + i * 9.0)) + (i * 1.37);
            int laneOffset = -30 + (i * 4);
            int confettiX = centerX + laneOffset + (int) Math.round(Math.sin(phase * 0.9) * 3.0);
            int fall = (int) (((now / (22.0 + i)) + i * 11.0) % fallHeight);
            int confettiY = spawnTop + fall;
            float fade = 1.0f - Math.min(1.0f, fall / (float) Math.max(1, fallHeight - 1));
            int alpha = Math.max(24, Math.round((110 + (90 * (float) Math.abs(Math.cos(phase * 1.1)))) * fade));
            int color = withAlpha(KOIL_CONFETTI_COLORS[(i + 2) % KOIL_CONFETTI_COLORS.length], alpha);
            if ((i & 1) == 0) {
                context.fill(confettiX, confettiY, confettiX + 2, confettiY + 4, color);
                context.fill(confettiX - 1, confettiY + 1, confettiX + 3, confettiY + 2, withAlpha(0xFFFFFF, alpha / 4));
            } else {
                context.fill(confettiX, confettiY, confettiX + 4, confettiY + 2, color);
                context.fill(confettiX + 1, confettiY - 1, confettiX + 2, confettiY + 3, withAlpha(0xFFFFFF, alpha / 4));
            }
        }
    }

    @Unique
    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }

    private String getModFileName() {
        try {
            return Files.list(Paths.get("./mods/"))
                    .filter(path -> path.getFileName().toString().startsWith("koil-"))
                    .map(path -> path.getFileName().toString())
                    .findFirst()
                    .orElse("");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
