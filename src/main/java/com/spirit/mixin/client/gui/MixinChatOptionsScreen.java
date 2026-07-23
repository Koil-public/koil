package com.spirit.mixin.client.gui;

import net.minecraft.client.gui.screen.option.ChatOptionsScreen;
import net.minecraft.client.gui.screen.option.SimpleOptionsScreen;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.spirit.koil.api.chat.RichChatSettings;

import java.util.Arrays;

@Mixin(SimpleOptionsScreen.class)
public abstract class MixinChatOptionsScreen {
    @Shadow @Final @Mutable private SimpleOption<?>[] options;
    private boolean koil$richChatOptionsAdded;

    /**
     * Extend the actual vanilla option list before SimpleOptionsScreen builds
     * it. This keeps Koil toggles in the normal scrollable Chat Settings grid
     * instead of opening a second settings screen.
     */
    @Inject(method = "init()V", at = @At("HEAD"))
    private void koil$appendRichChatOptions(CallbackInfo ci) {
        if (!((Object) this instanceof ChatOptionsScreen) || koil$richChatOptionsAdded) {
            return;
        }
        SimpleOption<?>[] additions = {
                koil$toggle(RichChatSettings.ENABLED, "Rich Chat"),
                koil$toggle(RichChatSettings.MEDIA, "Rich media"),
                koil$toggle(RichChatSettings.IMAGES, "Rich images"),
                koil$toggle(RichChatSettings.AUDIO, "Rich audio"),
                koil$toggle(RichChatSettings.VIDEO, "Rich videos"),
                koil$toggle(RichChatSettings.GIFS, "Rich GIFs"),
                koil$toggle(RichChatSettings.FILES, "Rich files"),
                koil$toggle(RichChatSettings.LATEX, "Rich LaTeX"),
                koil$toggle(RichChatSettings.EFFECTS, "Rich text effects"),
                koil$toggle(RichChatSettings.TIMESTAMPS, "Rich timestamps"),
                koil$toggle(RichChatSettings.INDICATORS, "Rich message bars")
        };
        SimpleOption<?>[] expanded = Arrays.copyOf(options, options.length + additions.length);
        System.arraycopy(additions, 0, expanded, options.length, additions.length);
        options = expanded;
        koil$richChatOptionsAdded = true;
    }

    private static SimpleOption<Boolean> koil$toggle(String key, String label) {
        return SimpleOption.ofBoolean(
                RichChatSettings.optionTranslationKey(key),
                SimpleOption.emptyTooltip(),
                // SimpleOption renders its translation-key label itself.  The
                // formatter must provide only the value or it duplicates the
                // label and exposes the raw translation id when missing.
                (optionText, value) -> Text.literal(Boolean.TRUE.equals(value) ? "ON" : "OFF"),
                RichChatSettings.get(key, true),
                value -> RichChatSettings.set(key, Boolean.TRUE.equals(value))
        );
    }
}
