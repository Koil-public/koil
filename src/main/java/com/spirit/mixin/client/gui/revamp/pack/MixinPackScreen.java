
package com.spirit.mixin.client.gui.revamp.pack;

import com.google.gson.JsonElement;
import com.spirit.client.gui.FolderOpenHelper;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.datapack.DatapackScreen;
import com.spirit.client.gui.resourcepack.ResourcepackScreen;
import com.spirit.client.gui.resourcepack.ResourcePackMenuScreen;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.pack.ResourcePackOrganizer;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

@Environment(EnvType.CLIENT)
@Mixin(PackScreen.class)
public abstract class MixinPackScreen extends Screen {
    @Shadow @Final private ResourcePackOrganizer organizer;
    @Shadow @Final private Path file;
    @Unique private final PopupMenu koil$folderOpenPopup = new PopupMenu();
    @Unique private File koil$pendingFolderOpenDirectory;
    @Unique private int koil$lastMouseX;
    @Unique private int koil$lastMouseY;
    @Invoker("closeDirectoryWatcher")
    protected abstract void koil$closeDirectoryWatcher();

    protected MixinPackScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void koil$redirectToKoilScreen(CallbackInfo ci) {
        if (!this.koil$useRedesign()) {
            return;
        }

        ci.cancel();
        this.koil$closeDirectoryWatcher();
        Objects.requireNonNull(this.client).setScreen(new ResourcePackMenuScreen((PackScreen) (Object) this, this.organizer, this.file, this.title));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$addVanillaModeTools(CallbackInfo ci) {
        if (this.koil$useRedesign() || this.client == null) {
            return;
        }
        boolean datapack = koil$isDatapackContext();
        int buttonWidth = 150;
        int halfButtonWidth = 73;
        int buttonGap = 4;
        int leftX = Math.max(4, this.width / 2 - 154);
        int rightX = Math.min(this.width - buttonWidth - 4, this.width / 2 + 5);
        int websiteX = leftX + halfButtonWidth + buttonGap;
        int toolsY = this.height - 24;
        int folderY = this.height - 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal(datapack ? "Data Packs" : "Packs"), button -> {
            if (datapack) {
                this.client.setScreen(new DatapackScreen(this, this.file.toFile()));
            } else {
                this.client.setScreen(new ResourcepackScreen(this));
            }
        }).dimensions(leftX, toolsY, halfButtonWidth, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Website"), button -> {
            Util.getOperatingSystem().open(datapack ? "https://modrinth.com/datapacks" : "https://modrinth.com/resourcepacks");
        }).dimensions(websiteX, toolsY, halfButtonWidth, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Folder"), button -> {
            this.koil$openFolderPopupAtPointer(this.file.toFile());
        }).dimensions(rightX, folderY, buttonWidth, 20).build());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$renderVanillaModePopups(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.koil$lastMouseX = mouseX;
        this.koil$lastMouseY = mouseY;
        if (!this.koil$useRedesign()) {
            this.koil$folderOpenPopup.render(context, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.koil$folderOpenPopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.koil$folderOpenPopup.click(mouseX, mouseY);
            if (selected != null) {
                FolderOpenHelper.handleAction(this.client, this.koil$pendingFolderOpenDirectory, selected.id());
                return true;
            }
            if (!this.koil$folderOpenPopup.isOpen()) {
                return true;
            }
        }
        if (this.koil$folderOpenPopup.isOpen() && this.koil$folderOpenPopup.contains(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Unique
    private void koil$openFolderPopupAtPointer(File folder) {
        if (folder == null) {
            return;
        }
        if (!folder.exists()) {
            folder.mkdirs();
        }
        this.koil$pendingFolderOpenDirectory = folder;
        this.koil$folderOpenPopup.toggleAtPointer(this.koil$lastMouseX, this.koil$lastMouseY, this.width, this.height, FolderOpenHelper.menuEntries());
    }

    private boolean koil$useRedesign() {
        JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign");
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    private boolean koil$isDatapackContext() {
        String titleText = this.title == null ? "" : this.title.getString().toLowerCase(java.util.Locale.ROOT);
        String pathText = this.file == null ? "" : this.file.toString().toLowerCase(java.util.Locale.ROOT);
        return titleText.contains("data pack")
                || titleText.contains("datapack")
                || pathText.contains("datapack")
                || pathText.contains("data-pack");
    }
}
