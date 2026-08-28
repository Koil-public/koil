package com.spirit.koil.api.automation.input;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves explicit player-input names without restricting tools to a brittle
 * enum. Semantic names follow the player's current bindings; literal letters,
 * digits and function keys mean the corresponding physical keyboard key.
 */
public final class RawPlayerInputResolver {
    private static final Map<String, String> KEY_TRANSLATIONS = Map.ofEntries(
            Map.entry("space", "key.keyboard.space"),
            Map.entry("shift", "key.keyboard.left.shift"),
            Map.entry("left_shift", "key.keyboard.left.shift"),
            Map.entry("ctrl", "key.keyboard.left.control"),
            Map.entry("control", "key.keyboard.left.control"),
            Map.entry("left_ctrl", "key.keyboard.left.control"),
            Map.entry("alt", "key.keyboard.left.alt"),
            Map.entry("enter", "key.keyboard.enter"),
            Map.entry("return", "key.keyboard.enter"),
            Map.entry("tab", "key.keyboard.tab"),
            Map.entry("escape", "key.keyboard.escape"),
            Map.entry("esc", "key.keyboard.escape"),
            Map.entry("backspace", "key.keyboard.backspace"),
            Map.entry("delete", "key.keyboard.delete"),
            Map.entry("up", "key.keyboard.up"),
            Map.entry("down", "key.keyboard.down"),
            Map.entry("left_arrow", "key.keyboard.left"),
            Map.entry("right_arrow", "key.keyboard.right"),
            Map.entry("home", "key.keyboard.home"),
            Map.entry("end", "key.keyboard.end"),
            Map.entry("page_up", "key.keyboard.page.up"),
            Map.entry("page_down", "key.keyboard.page.down")
    );

    private RawPlayerInputResolver() {
    }

    public static Optional<ResolvedInput> resolve(MinecraftClient client, String requested) {
        String key = normalize(requested);
        if (key.isBlank()) return Optional.empty();

        InputUtil.Key literal = literalKeyboardKey(key);
        if (literal != null) return Optional.of(new ResolvedInput(key, literal, true));

        if ("left_click".equals(key) || "leftclick".equals(key)) {
            return Optional.of(new ResolvedInput(key,
                    InputUtil.Type.MOUSE.createFromCode(GLFW.GLFW_MOUSE_BUTTON_LEFT), true));
        }
        if ("right_click".equals(key) || "rightclick".equals(key)) {
            return Optional.of(new ResolvedInput(key,
                    InputUtil.Type.MOUSE.createFromCode(GLFW.GLFW_MOUSE_BUTTON_RIGHT), true));
        }
        if ("middle_click".equals(key) || "middleclick".equals(key)) {
            return Optional.of(new ResolvedInput(key,
                    InputUtil.Type.MOUSE.createFromCode(GLFW.GLFW_MOUSE_BUTTON_MIDDLE), true));
        }

        KeyBinding binding = semanticBinding(client, key);
        if (binding == null || binding.isUnbound()) return Optional.empty();
        try {
            return Optional.of(new ResolvedInput(
                    key,
                    InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey()),
                    false
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static boolean syntacticallySupported(String requested) {
        String key = normalize(requested);
        if (key.isBlank()) return false;
        return literalKeyboardKey(key) != null
                || switch (key) {
                    case "left_click", "leftclick", "right_click", "rightclick", "middle_click", "middleclick",
                         "forward", "back", "backward", "left", "right", "jump", "sneak", "crouch",
                         "sprint", "attack", "use", "inventory", "chat", "swap_hands", "swap", "drop",
                         "pick_block", "pick", "perspective", "third_person", "camera" -> true;
                    default -> false;
                };
    }

    public static String normalize(String requested) {
        return requested == null ? "" : requested.strip().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }

    private static InputUtil.Key literalKeyboardKey(String key) {
        String translation = KEY_TRANSLATIONS.get(key);
        if (translation == null && key.length() == 1 && Character.isLetterOrDigit(key.charAt(0))) {
            translation = "key.keyboard." + key;
        }
        if (translation == null && key.matches("f(?:[1-9]|1[0-9]|2[0-5])")) {
            translation = "key.keyboard." + key;
        }
        if (translation == null) return null;
        try {
            return InputUtil.fromTranslationKey(translation);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static KeyBinding semanticBinding(MinecraftClient client, String key) {
        if (client == null || client.options == null) return null;
        return switch (key) {
            case "forward" -> client.options.forwardKey;
            case "back", "backward" -> client.options.backKey;
            case "left" -> client.options.leftKey;
            case "right" -> client.options.rightKey;
            case "jump" -> client.options.jumpKey;
            case "sneak", "crouch" -> client.options.sneakKey;
            case "sprint" -> client.options.sprintKey;
            case "attack" -> client.options.attackKey;
            case "use" -> client.options.useKey;
            case "inventory" -> client.options.inventoryKey;
            case "chat" -> client.options.chatKey;
            case "swap_hands", "swap" -> client.options.swapHandsKey;
            case "drop" -> client.options.dropKey;
            case "pick_block", "pick" -> client.options.pickItemKey;
            case "perspective", "third_person", "camera" -> client.options.togglePerspectiveKey;
            default -> null;
        };
    }

    public record ResolvedInput(String canonicalName, InputUtil.Key key, boolean physical) {
    }
}
