package com.spirit.client.gui.world;

import com.spirit.client.gui.browser.ContentBrowserListRowRenderer;
import com.spirit.koil.api.world.LocalWorldDiscovery;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

public class DiscoveredWorldListWidget extends AlwaysSelectedEntryListWidget<DiscoveredWorldListWidget.DiscoveredWorldEntry> {
    private static final Identifier FALLBACK_WORLD_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");
    private final Function<LocalWorldDiscovery.DiscoveredWorld, Identifier> iconResolver;
    private final Supplier<LocalWorldDiscovery.DiscoveredWorld> selectionSupplier;
    private final Consumer<LocalWorldDiscovery.DiscoveredWorld> selectionConsumer;

    public DiscoveredWorldListWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight, Function<LocalWorldDiscovery.DiscoveredWorld, Identifier> iconResolver, Supplier<LocalWorldDiscovery.DiscoveredWorld> selectionSupplier, Consumer<LocalWorldDiscovery.DiscoveredWorld> selectionConsumer) {
        super(client, width, height, top, bottom, itemHeight);
        this.iconResolver = iconResolver;
        this.selectionSupplier = selectionSupplier;
        this.selectionConsumer = selectionConsumer;
    }

    public void updateEntries(List<LocalWorldDiscovery.DiscoveredWorld> worlds) {
        LocalWorldDiscovery.DiscoveredWorld previous = this.selectionSupplier.get();
        this.clearEntries();
        for (LocalWorldDiscovery.DiscoveredWorld world : worlds) {
            DiscoveredWorldEntry entry = new DiscoveredWorldEntry(world);
            this.addEntry(entry);
            if (previous != null && previous.worldPath().equals(world.worldPath())) {
                this.setSelected(entry);
            }
        }
        if (this.getSelectedOrNull() == null && this.getEntryCount() > 0) {
            DiscoveredWorldEntry first = this.getEntry(0);
            this.setSelected(first);
            this.selectionConsumer.accept(first.world);
        }
    }

    @Override
    public int getRowWidth() {
        return this.width - 12;
    }

    @Override
    public int getRowLeft() {
        return this.left + 2;
    }

    @Override
    protected int getScrollbarPositionX() {
        return this.right - 6;
    }

    @Override
    protected void renderBackground(DrawContext context) {
    }

    public final class DiscoveredWorldEntry extends AlwaysSelectedEntryListWidget.Entry<DiscoveredWorldEntry> {
        private final LocalWorldDiscovery.DiscoveredWorld world;

        private DiscoveredWorldEntry(LocalWorldDiscovery.DiscoveredWorld world) {
            this.world = world;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            String subLabel = this.world.folderName() + "  |  " + (this.world.versionCompatible() ? "Compatible" : "Version mismatch") + "  |  " + this.world.worldVersion();
            ContentBrowserListRowRenderer.renderModMenuStyleItem(context, DiscoveredWorldListWidget.this.client.textRenderer, DiscoveredWorldListWidget.this.iconResolver.apply(this.world), FALLBACK_WORLD_ICON, x, y, entryWidth, this.world.displayName(), subLabel);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            DiscoveredWorldListWidget.this.selectionConsumer.accept(this.world);
            DiscoveredWorldListWidget.this.setSelected(this);
            return true;
        }

        @Override
        public Text getNarration() {
            return Text.literal(this.world.displayName());
        }
    }
}
