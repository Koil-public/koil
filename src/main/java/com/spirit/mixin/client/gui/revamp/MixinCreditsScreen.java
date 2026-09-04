package com.spirit.mixin.client.gui.revamp;

import com.spirit.Main;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Environment(EnvType.CLIENT)
@Mixin(CreditsScreen.class)
public class MixinCreditsScreen {
    @Unique
    private static final int KOIL_VANILLA_CREDITS_WIDTH = 256;
    @Unique
    private static final int KOIL_SIDE_MAX_WIDTH = 180;
    @Unique
    private static final int KOIL_SIDE_MIN_WIDTH = 64;
    @Unique
    private static final int KOIL_SIDE_GAP = 12;
    @Unique
    private static final int KOIL_EDGE_MARGIN = 8;
    @Unique
    private static final int KOIL_LOGO_SIZE = 64;
    @Unique
    private static final int KOIL_LINE_HEIGHT = 12;
    @Unique
    private static final int KOIL_MOD_GAP = 24;
    @Unique
    private static final String KOIL_MOD_ID = "koil";
    @Unique
    private static final Text KOIL_SEPARATOR =
        Text.literal("============").formatted(Formatting.WHITE);

    @Shadow
    private float time;

    @Shadow
    private int creditsHeight;

    @Unique
    private List<ModContainer> koil$leftColumnMods = new ArrayList<>();

    @Unique
    private List<ModContainer> koil$rightColumnMods = new ArrayList<>();

    @Unique
    private final Map<String, Identifier> koil$loadedModIcons = new HashMap<>();

    @Unique
    private final Set<String> koil$failedModIcons = new HashSet<>();

    @Unique
    private int koil$sideColumnWidth = KOIL_SIDE_MAX_WIDTH;

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void koil$renderCreditsBackground(DrawContext context, CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        KoilVanillaScreenChrome.renderCreditsShell(
            context,
            client,
            client.getWindow().getScaledWidth(),
            client.getWindow().getScaledHeight()
        );
        ci.cancel();
    }

    /*
     * Vanilla only uses creditsHeight to determine how long the screen remains open.
     * The side columns can be longer than Mojang's own credit list, so include the
     * longest mod column in that lifetime calculation.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void koil$prepareModCredits(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }

        int screenWidth = client.getWindow().getScaledWidth();
        this.koil$sideColumnWidth = koil$calculateSideColumnWidth(screenWidth);

        List<ModContainer> mods = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            /*
             * Keep the credits list aligned with the Mod Menu's default presentation:
             * iconless entries are hidden, and implementation/support families are
             * separated from normal user-facing mods.
             */
            if (!koil$matchesModMenuBaseCreditsFilter(mod)) {
                continue;
            }

            mods.add(mod);
        }

        mods.sort(
            Comparator
                .comparingInt((ModContainer mod) ->
                    KOIL_MOD_ID.equals(mod.getMetadata().getId()) ? 0 : 1)
                .thenComparing(
                    mod -> koil$getModDisplayName(mod.getMetadata()),
                    String.CASE_INSENSITIVE_ORDER
                )
                .thenComparing(mod -> mod.getMetadata().getId())
        );

        this.koil$leftColumnMods = new ArrayList<>();
        this.koil$rightColumnMods = new ArrayList<>();

        int leftHeight = 0;
        int rightHeight = 0;

        for (ModContainer mod : mods) {
            int blockHeight = koil$layoutModCreditBlock(
                null,
                client.textRenderer,
                mod,
                0,
                this.koil$sideColumnWidth,
                0
            );

            if (leftHeight <= rightHeight) {
                this.koil$leftColumnMods.add(mod);
                leftHeight += blockHeight;
            } else {
                this.koil$rightColumnMods.add(mod);
                rightHeight += blockHeight;
            }
        }

        /*
         * creditsHeight is normally credits.size() * 12.
         * Our measured side-column height uses the same 12px cadence and includes
         * each logo region, so taking the max keeps every mod credit block on-screen
         * long enough to fully scroll past.
         */
        this.creditsHeight = Math.max(
            this.creditsHeight,
            Math.max(leftHeight, rightHeight)
        );
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableBlend()V",
            shift = At.Shift.BEFORE
        )
    )
    private void koil$renderModCredits(
        DrawContext context,
        int mouseX,
        int mouseY,
        float delta,
        CallbackInfo ci
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int vanillaLeft = screenWidth / 2 - KOIL_VANILLA_CREDITS_WIDTH / 2;
        int vanillaRight = screenWidth / 2 + KOIL_VANILLA_CREDITS_WIDTH / 2;

        int leftX = vanillaLeft - KOIL_SIDE_GAP - this.koil$sideColumnWidth;
        int rightX = vanillaRight + KOIL_SIDE_GAP;

        leftX = Math.max(KOIL_EDGE_MARGIN, leftX);
        rightX = Math.min(
            screenWidth - KOIL_EDGE_MARGIN - this.koil$sideColumnWidth,
            rightX
        );

        int contentTop = screenHeight + 50;

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, -this.time, 0.0F);

        int leftY = contentTop;
        for (ModContainer mod : this.koil$leftColumnMods) {
            leftY += koil$layoutModCreditBlock(
                context,
                client.textRenderer,
                mod,
                leftX,
                this.koil$sideColumnWidth,
                leftY
            );
        }

        int rightY = contentTop;
        for (ModContainer mod : this.koil$rightColumnMods) {
            rightY += koil$layoutModCreditBlock(
                context,
                client.textRenderer,
                mod,
                rightX,
                this.koil$sideColumnWidth,
                rightY
            );
        }

        context.getMatrices().pop();
    }

    /*
     * Dynamic textures are used instead of assuming an icon lives under
     * assets/<mod-id>/. Fabric's metadata icon may legally point anywhere inside
     * the mod JAR, including its root.
     */
    @Inject(method = "removed", at = @At("TAIL"))
    private void koil$releaseModCreditIcons(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            for (Identifier texture : this.koil$loadedModIcons.values()) {
                client.getTextureManager().destroyTexture(texture);
            }
        }

        this.koil$loadedModIcons.clear();
        this.koil$failedModIcons.clear();
    }

    @Unique
    private int koil$layoutModCreditBlock(
        DrawContext context,
        TextRenderer renderer,
        ModContainer mod,
        int columnX,
        int columnWidth,
        int startY
    ) {
        ModMetadata metadata = mod.getMetadata();
        int y = startY;
        int centerX = columnX + columnWidth / 2;

        if (koil$hasModIcon(mod)) {
            if (context != null && koil$isVisible(y, KOIL_LOGO_SIZE)) {
                Identifier icon = koil$getModIcon(mod);
                if (icon != null) {
                    context.drawTexture(
                        icon,
                        centerX - KOIL_LOGO_SIZE / 2,
                        y,
                        0,
                        0,
                        KOIL_LOGO_SIZE,
                        KOIL_LOGO_SIZE,
                        KOIL_LOGO_SIZE,
                        KOIL_LOGO_SIZE
                    );
                }
            }

            // Mirrors the existing Koil placement and vanilla's ~100px logo lead-in.
            y += 101;
        }

        y = koil$drawCenteredLine(
            context,
            renderer,
            KOIL_SEPARATOR,
            centerX,
            y
        );

        y = koil$drawCenteredWrapped(
            context,
            renderer,
            Text.literal(koil$getSectionName(metadata)).formatted(Formatting.YELLOW),
            centerX,
            columnWidth,
            y
        );

        y = koil$drawCenteredLine(
            context,
            renderer,
            KOIL_SEPARATOR,
            centerX,
            y
        );

        // Vanilla readCredits() inserts two empty lines after each section header.
        y += KOIL_LINE_HEIGHT * 2;

        if (KOIL_MOD_ID.equals(metadata.getId())) {
            /*
             * Preserve Koil's authored credit roles instead of reducing Koil itself
             * to only generic Fabric metadata.
             */
            y = koil$drawCreditGroup(
                context,
                renderer,
                "Studio Head of Koil",
                List.of("SpiritXIV"),
                columnX,
                columnWidth,
                y
            );
            y = koil$drawCreditGroup(
                context,
                renderer,
                "Backend Developer",
                List.of("eeverest"),
                columnX,
                columnWidth,
                y
            );
            y = koil$drawCreditGroup(
                context,
                renderer,
                "Asset Artist:",
                List.of("Computer User"),
                columnX,
                columnWidth,
                y
            );
            y = koil$drawCreditGroup(
                context,
                renderer,
                "Music and Sound Design Artist:",
                List.of("Bashful"),
                columnX,
                columnWidth,
                y
            );
            y = koil$drawCreditGroup(
                context,
                renderer,
                "Additional Help and Support from:",
                List.of("KingZhara"),
                columnX,
                columnWidth,
                y
            );
        } else {
            List<String> authors = koil$getPersonNames(metadata.getAuthors());
            List<String> contributors = koil$getPersonNames(metadata.getContributors());

            y = koil$drawCreditGroup(
                context,
                renderer,
                authors.size() == 1 ? "Author" : "Authors",
                authors,
                columnX,
                columnWidth,
                y
            );

            y = koil$drawCreditGroup(
                context,
                renderer,
                contributors.size() == 1 ? "Contributor" : "Contributors",
                contributors,
                columnX,
                columnWidth,
                y
            );
        }

        y += KOIL_MOD_GAP;
        return y - startY;
    }

    @Unique
    private int koil$drawCreditGroup(
        DrawContext context,
        TextRenderer renderer,
        String title,
        List<String> names,
        int columnX,
        int columnWidth,
        int y
    ) {
        if (names == null || names.isEmpty()) {
            return y;
        }

        y = koil$drawLeftWrapped(
            context,
            renderer,
            Text.literal(title).formatted(Formatting.GRAY),
            columnX,
            columnWidth,
            y
        );

        int vanillaIndent = renderer.getWidth("           ");
        int nameX = columnX + vanillaIndent;
        int nameWidth = Math.max(1, columnWidth - vanillaIndent);

        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }

            y = koil$drawLeftWrapped(
                context,
                renderer,
                Text.literal(name).formatted(Formatting.WHITE),
                nameX,
                nameWidth,
                y
            );
        }

        // Matches the two empty lines vanilla inserts after every title/name group.
        return y + KOIL_LINE_HEIGHT * 2;
    }

    @Unique
    private int koil$drawCenteredLine(
        DrawContext context,
        TextRenderer renderer,
        Text text,
        int centerX,
        int y
    ) {
        if (context != null && koil$isVisible(y, KOIL_LINE_HEIGHT + 8)) {
            context.drawCenteredTextWithShadow(renderer, text, centerX, y, 0xFFFFFF);
        }

        return y + KOIL_LINE_HEIGHT;
    }

    @Unique
    private int koil$drawCenteredWrapped(
        DrawContext context,
        TextRenderer renderer,
        Text text,
        int centerX,
        int maxWidth,
        int y
    ) {
        List<OrderedText> lines = renderer.wrapLines(text, Math.max(1, maxWidth));

        for (OrderedText line : lines) {
            if (context != null && koil$isVisible(y, KOIL_LINE_HEIGHT + 8)) {
                context.drawCenteredTextWithShadow(renderer, line, centerX, y, 0xFFFFFF);
            }

            y += KOIL_LINE_HEIGHT;
        }

        return y;
    }

    @Unique
    private int koil$drawLeftWrapped(
        DrawContext context,
        TextRenderer renderer,
        Text text,
        int x,
        int maxWidth,
        int y
    ) {
        List<OrderedText> lines = renderer.wrapLines(text, Math.max(1, maxWidth));

        for (OrderedText line : lines) {
            if (context != null && koil$isVisible(y, KOIL_LINE_HEIGHT + 8)) {
                context.drawTextWithShadow(renderer, line, x, y, 0xFFFFFF);
            }

            y += KOIL_LINE_HEIGHT;
        }

        return y;
    }

    @Unique
    private boolean koil$isVisible(int contentY, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }

        float screenY = contentY - this.time;
        return screenY + height > 0.0F
            && screenY < client.getWindow().getScaledHeight();
    }

    @Unique
    private Identifier koil$getModIcon(ModContainer mod) {
        ModMetadata metadata = mod.getMetadata();

        if (KOIL_MOD_ID.equals(metadata.getId())) {
            return Main.LOGO_TEXTURE;
        }

        String modId = metadata.getId();

        Identifier cached = this.koil$loadedModIcons.get(modId);
        if (cached != null) {
            return cached;
        }

        if (this.koil$failedModIcons.contains(modId)) {
            return null;
        }

        String iconPath = metadata.getIconPath(KOIL_LOGO_SIZE).orElse(null);
        if (iconPath == null) {
            this.koil$failedModIcons.add(modId);
            return null;
        }

        Path path = mod.findPath(iconPath).orElse(null);
        if (path == null) {
            this.koil$failedModIcons.add(modId);
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return null;
        }

        NativeImage image = null;
        NativeImageBackedTexture texture = null;

        try (InputStream input = Files.newInputStream(path)) {
            image = NativeImage.read(input);
            texture = new NativeImageBackedTexture(image);

            Identifier textureId = client.getTextureManager().registerDynamicTexture(
                "koil_credit_" + modId,
                texture
            );

            this.koil$loadedModIcons.put(modId, textureId);
            return textureId;
        } catch (Exception ignored) {
            if (texture != null) {
                texture.close();
            } else if (image != null) {
                image.close();
            }

            this.koil$failedModIcons.add(modId);
            return null;
        }
    }

    @Unique
    private static boolean koil$hasModIcon(ModContainer mod) {
        ModMetadata metadata = mod.getMetadata();
        return KOIL_MOD_ID.equals(metadata.getId())
            || metadata.getIconPath(KOIL_LOGO_SIZE).isPresent();
    }

    @Unique
    private static String koil$getSectionName(ModMetadata metadata) {
        if (KOIL_MOD_ID.equals(metadata.getId())) {
            return "Koil Mod Team";
        }

        return koil$getModDisplayName(metadata);
    }

    @Unique
    private static String koil$getModDisplayName(ModMetadata metadata) {
        String name = metadata.getName();
        if (name == null || name.isBlank()) {
            return metadata.getId();
        }

        return name;
    }

    @Unique
    private static boolean koil$matchesModMenuBaseCreditsFilter(ModContainer mod) {
        if (mod == null || mod.getMetadata() == null) {
            return false;
        }

        ModMetadata metadata = mod.getMetadata();
        String modId = metadata.getId();

        // Minecraft owns the center credits, and Java is a Loader builtin.
        if ("minecraft".equals(modId) || "java".equals(modId)) {
            return false;
        }

        /*
         * This matches ModMenuScreen's default picture preference:
         * showIconlessMods starts false, so installed mods need a metadata icon.
         */
        if (metadata.getIconPath(32).isEmpty()) {
            return false;
        }

        /*
         * ModMenuScreen's default FAMILY grouping classifies Fabric/Quilt internals
         * and common support libraries separately. Credits only include its normal
         * "Mods" family so those technical entries do not get individual credits.
         */
        return "Mods".equals(koil$modMenuFamilyLabel(mod));
    }

    @Unique
    private static String koil$modMenuFamilyLabel(ModContainer mod) {
        ModMetadata metadata = mod.getMetadata();
        String modId = koil$safeLower(metadata.getId());
        String name = koil$safeLower(metadata.getName());
        String author = metadata.getAuthors().stream()
            .findFirst()
            .map(Person::getName)
            .map(MixinCreditsScreen::koil$safeLower)
            .orElse("unknown");

        // Mirrors ModMenuScreen.familyLabel().
        if (modId.equals("fabricloader")
            || modId.startsWith("fabric-")
            || modId.startsWith("fabric_")
            || name.contains("fabric api")
            || author.contains("fabricmc")) {
            return "Fabric System";
        }

        if (modId.startsWith("quilt-")
            || modId.startsWith("qsl-")
            || author.contains("quilt")) {
            return "Quilt System";
        }

        if (modId.contains("cloth")
            || modId.contains("yacl")
            || modId.contains("modmenu")
            || modId.contains("midnightlib")) {
            return "Support Libraries";
        }

        return "Mods";
    }

    @Unique
    private static String koil$safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    @Unique
    private static List<String> koil$getPersonNames(Collection<Person> people) {
        List<String> names = new ArrayList<>();

        for (Person person : people) {
            if (person == null) {
                continue;
            }

            String name = person.getName();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }

        return names;
    }

    @Unique
    private static int koil$calculateSideColumnWidth(int screenWidth) {
        int available = screenWidth
            - KOIL_EDGE_MARGIN * 2
            - KOIL_VANILLA_CREDITS_WIDTH
            - KOIL_SIDE_GAP * 2;

        int perSide = available / 2;

        return Math.max(
            KOIL_SIDE_MIN_WIDTH,
            Math.min(KOIL_SIDE_MAX_WIDTH, perSide)
        );
    }
}
