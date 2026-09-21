package com.spirit.koil.api.chat;

import com.spirit.client.gui.SuggestionPopupRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Chat entry that is safe to open from any menu while preserving the parent screen. */
public final class MenuChatScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget input;
    private List<String> suggestions = List.of();
    private int selectedSuggestion;
    private int suggestionX;
    private int suggestionY;
    private int suggestionWidth;
    private boolean consumeOpeningCharacter = true;

    public MenuChatScreen(Screen parent) {
        super(Text.literal("Menu Chat"));
        this.parent = parent;
    }

    public static Screen open(Screen parent, boolean worldLoaded) {
        return worldLoaded ? new OverlayChatScreen(parent) : new MenuChatScreen(parent);
    }

    public static boolean canOpenMenuChat(Element focused) {
        return !containsFocusedTextInput(focused);
    }

    /**
     * Menu chat must never steal a normal typing key from an active text input.
     * Some Koil screens keep focus on a nested parent element instead of the
     * TextFieldWidget itself, so inspect the focused subtree rather than only
     * checking the direct focused element.
     */
    public static boolean canOpenMenuChat(Screen screen) {
        if (screen == null) {
            return true;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return !containsFocusedTextInput(screen, visited, 0);
    }

    private static boolean containsFocusedTextInput(Element element) {
        if (element == null) return false;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return containsFocusedTextInput(element, visited, 0);
    }

    /**
     * Koil has a few manually-rendered fields that are intentionally not added as
     * Screen children. Walk only Koil-owned object fields, and only on the T-key
     * path, so those fields still retain their typed character without adding any
     * per-frame reflection cost.
     */
    private static boolean containsFocusedTextInput(Object value, Set<Object> visited, int depth) {
        if (value == null || depth > 3 || visited.size() >= 512 || !visited.add(value)) return false;
        if (value instanceof TextFieldWidget field) return field.isFocused();
        if (value instanceof ParentElement parent) {
            for (Element child : parent.children()) {
                if (containsFocusedTextInput(child, visited, depth + 1)) return true;
            }
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (containsFocusedTextInput(element, visited, depth + 1)) return true;
            }
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (containsFocusedTextInput(Array.get(value, index), visited, depth + 1)) return true;
            }
            return false;
        }
        if (!isKoilUiObject(type)) return false;

        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    if (!field.trySetAccessible()) continue;
                    Object nested = field.get(value);
                    if (nested == null || nested == value || nested instanceof Screen) continue;
                    if (nested instanceof TextFieldWidget
                            || nested instanceof ParentElement
                            || nested instanceof Iterable<?>
                            || nested.getClass().isArray()
                            || isKoilUiObject(nested.getClass())) {
                        if (containsFocusedTextInput(nested, visited, depth + 1)) return true;
                    }
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // A field that cannot be inspected is not allowed to break input dispatch.
                }
            }
        }
        return false;
    }

    private static boolean isKoilUiObject(Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        return name.startsWith("com.spirit.client.gui.")
                || name.startsWith("com.spirit.koil.api.chat.");
    }

    @Override
    protected void init() {
        this.input = new TextFieldWidget(this.textRenderer, 4, this.height - 12, this.width - 8, 12, Text.translatable("chat.editBox"));
        this.input.setMaxLength(256);
        this.input.setDrawsBackground(false);
        this.input.setChangedListener(ignored -> refreshSuggestions());
        this.input.setFocused(true);
        this.addDrawableChild(this.input);
        this.setFocused(this.input);
        refreshSuggestions();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 258 && acceptSuggestion()) {
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (this.consumeOpeningCharacter && isOpeningChatCharacter(chr)) {
            this.consumeOpeningCharacter = false;
            return true;
        }
        this.consumeOpeningCharacter = false;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public void tick() {
        this.consumeOpeningCharacter = false;
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(2, this.height - 14, this.width - 2, this.height - 2, 0x80000000);
        if (this.input != null) {
            renderSuggestions(context, mouseX, mouseY);
        }
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(this.textRenderer, "Chat is unavailable without a loaded world.", 4, this.height - 26, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.suggestions.size() > 0
                && SuggestionPopupRenderer.containsRow(this.suggestionX, this.suggestionY, this.suggestionWidth,
                SuggestionPopupRenderer.preferredHeight(this.suggestions.size()), mouseX, mouseY)) {
            int row = SuggestionPopupRenderer.rowAt(this.suggestionY, mouseY);
            if (row >= 0 && row < this.suggestions.size()) {
                this.selectedSuggestion = row;
                acceptSuggestion();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void submit() {
        Outbound outbound = outbound(this.input == null ? "" : this.input.getText());
        if (outbound instanceof Empty) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        if (outbound instanceof Command command) {
            RichChatCommandOutputBridge.rememberOutgoingChatCommand(command.value());
            client.getNetworkHandler().sendChatCommand(command.value());
        } else if (outbound instanceof Message message) {
            client.getNetworkHandler().sendChatMessage(message.value());
        }
        close();
    }

    private void refreshSuggestions() {
        MinecraftClient client = MinecraftClient.getInstance();
        List<String> history = client == null ? List.of() : client.inGameHud.getChatHud().getMessageHistory();
        this.suggestions = historySuggestions(this.input == null ? "" : this.input.getText(), history);
        this.selectedSuggestion = Math.min(this.selectedSuggestion, Math.max(0, this.suggestions.size() - 1));
        if (this.input != null) {
            String suffix = this.suggestions.isEmpty() ? "" : this.suggestions.get(this.selectedSuggestion).substring(this.input.getText().length());
            this.input.setSuggestion(suffix);
        }
    }

    private boolean acceptSuggestion() {
        if (this.input == null || this.suggestions.isEmpty()) {
            return false;
        }
        String value = this.suggestions.get(this.selectedSuggestion);
        this.input.setText(value);
        this.input.setCursor(value.length());
        return true;
    }

    private void renderSuggestions(DrawContext context, int mouseX, int mouseY) {
        if (this.suggestions.isEmpty()) {
            return;
        }
        List<SuggestionPopupRenderer.Entry> entries = this.suggestions.stream()
                .map(value -> new SuggestionPopupRenderer.Entry("HIS", 0xFFB8C4CE, value, ""))
                .toList();
        this.suggestionWidth = SuggestionPopupRenderer.preferredWidth(this.textRenderer, entries);
        this.suggestionX = Math.max(2, Math.min(this.input.getX(), this.width - this.suggestionWidth - 2));
        this.suggestionY = Math.max(2, this.input.getY() - SuggestionPopupRenderer.preferredHeight(entries) - 2);
        SuggestionPopupRenderer.render(context, this.textRenderer, this.suggestionX, this.suggestionY, this.suggestionWidth,
                entries, this.selectedSuggestion, mouseX, mouseY);
    }

    static List<String> historySuggestions(String draft, List<String> history) {
        String prefix = draft == null ? "" : draft;
        if (prefix.isEmpty() || history == null || history.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (int index = history.size() - 1; index >= 0 && matches.size() < 10; index--) {
            String candidate = history.get(index);
            if (candidate != null && candidate.length() > prefix.length()
                    && candidate.regionMatches(true, 0, prefix, 0, prefix.length())) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    static boolean isOpeningChatCharacter(char character) {
        return character == 't' || character == 'T';
    }

    static Outbound outbound(String draft) {
        String value = draft == null ? "" : draft.strip();
        if (value.isBlank()) return new Empty();
        if (!value.startsWith("/")) return new Message(value);
        String command = value.substring(1).strip();
        return command.isBlank() ? new Empty() : new Command(command);
    }

    sealed interface Outbound permits Empty, Message, Command {
    }

    record Empty() implements Outbound {
    }

    record Message(String value) implements Outbound {
    }

    record Command(String value) implements Outbound {
    }

    static final class OverlayChatScreen extends ChatScreen {
        private final Screen parent;
        private boolean consumeOpeningCharacter = true;

        private OverlayChatScreen(Screen parent) {
            super("");
            this.parent = parent;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            if (this.parent != null) {
                this.parent.render(context, mouseX, mouseY, delta);
            }
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            if (this.consumeOpeningCharacter && isOpeningChatCharacter(chr)) {
                this.consumeOpeningCharacter = false;
                return true;
            }
            this.consumeOpeningCharacter = false;
            return super.charTyped(chr, modifiers);
        }

        @Override
        public void tick() {
            this.consumeOpeningCharacter = false;
            super.tick();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == 256) {
                close();
                return true;
            }
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            if (this.client != null && this.client.currentScreen == null) {
                this.client.setScreen(this.parent);
            }
            return handled;
        }

        @Override
        public void close() {
            if (this.client != null) {
                this.client.setScreen(this.parent);
            }
        }

        @Override
        public boolean shouldPause() {
            return this.parent != null && this.parent.shouldPause();
        }
    }
}
