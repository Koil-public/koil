package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilListBoundsAccess;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.client.gui.ScreenChromeHost;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.Formatting;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Environment(EnvType.CLIENT)
@Mixin(LanguageOptionsScreen.class)
public class MixinLanguageOptionsScreen extends GameOptionsScreen {
    @Unique
    private static final int KOIL_BASE_HEADER_HEIGHT = 36;
    @Unique
    private static final int KOIL_BASE_FOOTER_HEIGHT = 35;
    @Unique
    private static final int KOIL_LANGUAGE_LIST_HEADER_OFFSET = 15;
    @Unique
    private static final int KOIL_LANGUAGE_WARNING_CLEARANCE = 8;
    @Unique
    private static final int KOIL_LANGUAGE_CHROME_HEADER_DOWN = 22;
    @Unique
    private static final int KOIL_LANGUAGE_CHROME_FOOTER_UP = 29;

    public MixinLanguageOptionsScreen(Screen parent, GameOptions gameOptions, Text title) {
        super(parent, gameOptions, title);
    }

    /**
     * @author SpiritXIV
     * @reason keep Koil option-screen chrome visible on the language screen, which does not use GameOptionsScreen's option-list renderer
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        AlwaysSelectedEntryListWidget<?> languageList = koil$getLanguageSelectionList();
        if (languageList == null) {
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            koil$syncLanguageListBounds(languageList);
            koil$renderLanguageChrome(context, languageList);
            languageList.render(context, mouseX, mouseY, delta);
            koil$renderNonLanguageDrawables(context, mouseX, mouseY, delta, languageList);
            context.drawCenteredTextWithShadow(this.textRenderer, koil$languageWarningText(), this.width / 2, this.height - 56, 8421504);
            ((ScreenChromeHost) (Object) this).koil$renderScreenChromeLate(context, mouseX, mouseY, delta);
            return;
        }

        languageList.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, koil$languageWarningText(), this.width / 2, this.height - 56, 8421504);
        super.render(context, mouseX, mouseY, delta);
    }

    @Unique
    private void koil$syncLanguageListBounds(AlwaysSelectedEntryListWidget<?> languageList) {
        if (languageList instanceof KoilListBoundsAccess bounds) {
            int top = koil$languageListTop();
            int warningSafeTop = this.height - 56 - KOIL_LANGUAGE_WARNING_CLEARANCE;
            int bottom = Math.max(top, Math.min(KoilVanillaScreenChrome.languageListBottom(this.height), warningSafeTop));
            bounds.koil$setListBounds(top, bottom);
        }
    }

    @Unique
    private void koil$renderNonLanguageDrawables(DrawContext context, int mouseX, int mouseY, float delta, AlwaysSelectedEntryListWidget<?> languageList) {
        for (Element child : this.children()) {
            if (child == languageList || !(child instanceof Drawable drawable)) {
                continue;
            }
            drawable.render(context, mouseX, mouseY, delta);
        }
    }

    private void koil$renderLanguageChrome(DrawContext context, AlwaysSelectedEntryListWidget<?> languageList) {
        KoilVanillaScreenChrome.renderListShell(
                context,
                this.client,
                this.width,
                this.height,
                koil$chromeTop(languageList),
                koil$chromeBottom(languageList)
        );
        KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Options"), this.title);
    }

    @Unique
    private int koil$listTop(AlwaysSelectedEntryListWidget<?> languageList) {
        if (languageList instanceof KoilListBoundsAccess bounds) {
            return Math.max(36, bounds.koil$getListTop());
        }
        return koil$languageListTop();
    }

    @Unique
    private int koil$listBottom(AlwaysSelectedEntryListWidget<?> languageList) {
        int warningSafeTop = this.height - 56 - KOIL_LANGUAGE_WARNING_CLEARANCE;
        if (languageList instanceof KoilListBoundsAccess bounds) {
            return Math.max(koil$listTop(languageList), Math.min(bounds.koil$getListBottom(), warningSafeTop));
        }
        return Math.max(koil$listTop(languageList), Math.min(KoilVanillaScreenChrome.languageListBottom(this.height), warningSafeTop));
    }

    @Unique
    private int koil$chromeTop(AlwaysSelectedEntryListWidget<?> languageList) {
        return KOIL_BASE_HEADER_HEIGHT + KOIL_LANGUAGE_CHROME_HEADER_DOWN;
    }

    @Unique
    private int koil$chromeBottom(AlwaysSelectedEntryListWidget<?> languageList) {
        return Math.max(koil$chromeTop(languageList), this.height - KOIL_BASE_FOOTER_HEIGHT - KOIL_LANGUAGE_CHROME_FOOTER_UP);
    }

    @Unique
    private int koil$languageListTop() {
        return KoilVanillaScreenChrome.listTop(false) + KOIL_LANGUAGE_LIST_HEADER_OFFSET;
    }

    private Text koil$languageWarningText() {
        return Text.literal("(")
                .append(Text.translatable("options.languageWarning"))
                .append(")")
                .formatted(Formatting.GRAY);
    }

    @Unique
    private AlwaysSelectedEntryListWidget<?> koil$getLanguageSelectionList() {
        for (var child : this.children()) {
            if (child instanceof AlwaysSelectedEntryListWidget<?> listWidget) {
                return listWidget;
            }
        }
        return null;
    }
}
